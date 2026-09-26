import 'dart:async';

import 'package:flutter/services.dart';

import '../channels.dart';
import '../exceptions.dart';
import '../models/connection_config.dart';
import '../models/device_descriptor.dart';
import '../models/tag_read.dart';
import '../rfid_reader.dart';

/// RFID reader backed by a native Zebra SDK (RFD40/RFD90/MC33xx, …) through the
/// generic platform-channel bridge.
///
/// Note there is nothing RFD40-specific in here — it only speaks the bridge
/// contract in [RfidKitChannels]. Supporting another Zebra reader is a native
/// change; this Dart class is reused as-is.
class ZebraRfidReader implements RfidReader {
  ZebraRfidReader(this.descriptor, [this.connection]);

  @override
  final DeviceDescriptor descriptor;

  /// Optional transport hint (BT/USB to the sled). When null the native Zebra
  /// SDK pairs with the first available reader.
  final ConnectionConfig? connection;

  static const _methods = MethodChannel(RfidKitChannels.rfidMethods);
  static const _tagEvents = EventChannel(RfidKitChannels.tagEvents);

  /// Proximity straight from the reader while [locate] is running — see the
  /// subscription in [locate] for why this is preferred over our own estimate.
  static const _locateEvents = EventChannel(RfidKitChannels.locateEvents);

  bool _connected = false;
  int _lastRssi = 0;

  /// The label that answered most recently, of the several an item may carry.
  String? _lastEpc;

  @override
  bool get isConnected => _connected;

  @override
  int get lastRssi => _lastRssi;

  @override
  String? get lastEpc => _lastEpc;

  @override
  Future<void> connect() async {
    try {
      await _methods.invokeMethod(RfidKitChannels.mConnect, {
        'id': descriptor.id,
        ...?connection?.toArgs(),
      });
      _connected = true;
    } on PlatformException catch (e) {
      throw DeviceConnectionException(e.message ?? 'connect failed', cause: e);
    }
  }

  @override
  Future<void> disconnect() async {
    await _methods.invokeMethod(RfidKitChannels.mDisconnect);
    _connected = false;
  }

  @override
  Stream<TagRead> get tags =>
      _tagEvents.receiveBroadcastStream().map((e) => TagRead.fromMap(e as Map));

  @override
  Future<void> startInventory({double? power}) =>
      _methods.invokeMethod(RfidKitChannels.mStartInventory, {'power': power});

  @override
  Future<void> stopInventory() =>
      _methods.invokeMethod(RfidKitChannels.mStopInventory);

  @override
  Stream<int> locate(List<String> epcs, {Stream<bool>? live}) {
    final targets = epcs
        .map((e) => e.toUpperCase())
        .where((e) => e.isNotEmpty)
        .toSet();
    // Locate by reusing the RELIABLE inventory/tags path (the same one that
    // reads a tag's EPC during registration) and computing the proximity here in
    // Dart: filter to the target EPC and map its RSSI to a 0–100 meter. The
    // dedicated native locate-events channel proved unreliable on the RFD40,
    // whereas plain inventory reads work — so we drive the meter from those.
    //
    // Subscribe to the tag stream FIRST, then start inventory (exactly the order
    // the proven registration read uses) so the native tag sink is wired before
    // any reads arrive — otherwise the first reads fire into a null sink and are
    // dropped, delaying (or, on a brief inventory, missing) the signal.
    //
    // The meter is driven by a periodic TICK, not raw reads, so it behaves like
    // a real radar and stays stable even when reads are sparse/bouncy:
    //   1. FIXED range: RSSI maps to a fixed dBm window (see
    //      _proximityFromRssi, which holds the measured numbers). Distance reads honestly — far is genuinely low,
    //      near approaches 100% — instead of an adaptive peak that a single weak
    //      distant read could jump straight to 100% and then drop.
    //   2. MEDIAN + ease: the median of recent reads kills radio-bounce spikes,
    //      and the meter eases toward it (no flicker while held still).
    //   3. HOLD, then DECAY: UHF reads arrive erratically (multipath fading — a
    //      stationary tag drops in and out), so we HOLD the last value through a
    //      short silence before easing it down. The meter doesn't crash to 0
    //      between reads; only a real loss ramps it to 0.
    //
    // Every constant below is a trade between steadiness and honesty, and they
    // were all tuned too far toward steadiness. Measured on the old values, the
    // meter reached 90% of a new reading in 1.2 s, and took 4.8 s to fall away
    // after the tag was lost. Walking away from a pallet therefore left the
    // meter showing where the operator HAD been — so a tag further away could
    // read HIGHER than one close by, which is worse than useless for a tool
    // whose whole job is "warmer or colder".
    //
    // Retuned so the meter reaches 90% in 0.8 s and falls away in 2.4 s. Single
    // stray reads are still rejected — that is the median's job and it still
    // needs a majority of the window to move — but the meter now follows the
    // operator instead of trailing them.
    //
    // How long the meter holds its value through silence is no longer fixed: it
    // follows how often the tag is actually answering.
    //
    // Up close the tag answers ten times a second, so a gap really does mean the
    // operator moved and the meter has to follow at once. At the edge of range
    // the same tag answers once or twice a second, and a fixed 0.8 s hold read
    // every one of those ordinary gaps as "lost" — the field log shows the meter
    // going 24% → 0% → 31% while the operator stood still, which is worse than
    // useless at exactly the distance where they need a hint. Three times the
    // recent gap between reads rides through the quiet without hiding a tag that
    // has genuinely gone.
    const minHoldMs = 800;
    const maxHoldMs = 2500;
    // How LONG we average over, not how MANY reads.
    //
    // This was "the last 5 reads", and that is the defect behind the meter
    // swinging 75-76-54-78-67% while the operator stood still in front of the
    // bin. At the 12 reads a second this tag delivers up close, five reads is
    // 0.4 s — so the smoothing was at its weakest exactly when the operator was
    // closest and needed it most, and what reached the screen was very nearly a
    // single raw read.
    //
    // That matters because of the numbers underneath: measured on a real
    // 120-second run, the reading swings about 14 dB while standing still,
    // while the whole difference between the right bin and a wrong one is only
    // about 8 dB. The noise was bigger than the thing we were trying to show.
    // Averaging over a fixed 1.5 s instead cuts that swing to roughly 4 dB, and
    // is the single change that lets the two bins read differently at all.
    const windowMs = 1500;
    const easing = 0.65; // share of the gap to the median closed per tick
    const decay = 0.75; // per-tick fade once the tag is genuinely lost
    const gapWindow = 5; // read intervals kept, for the hold above
    late final StreamController<int> controller;
    StreamSubscription<TagRead>? sub;
    StreamSubscription<dynamic>? locateSub;
    Timer? ticker;
    // Readings inside the averaging window, each with when it arrived so the
    // window can be trimmed by age rather than by count.
    final recent = <({DateTime at, int value})>[];
    final gaps = <int>[]; // ms between recent reads, for the hold above
    var displayed = 0.0; // current meter value
    DateTime? lastRead; // when the target tag was last seen
    StreamSubscription<bool>? liveSub;
    // False between trigger pulls: the radio is deliberately off, so silence
    // means nothing about where the tag is.
    var reading = live == null;
    void trim(DateTime now) {
      recent.removeWhere((r) => now.difference(r.at).inMilliseconds > windowMs);
    }

    void noteRead(int value) {
      final now = DateTime.now();
      if (lastRead != null) {
        gaps.add(now.difference(lastRead!).inMilliseconds);
        if (gaps.length > gapWindow) gaps.removeAt(0);
      }
      lastRead = now;
      recent.add((at: now, value: value));
      trim(now);
    }

    void resetSession() {
      recent.clear();
      gaps.clear();
      lastRead = null;
      displayed = 0;
      _lastRssi = 0;
    }

    // True once the READER has told us a distance of its own. From then on its
    // figure is the only one used and our RSSI estimate is ignored.
    var readerKnowsDistance = false;
    controller = StreamController<int>(
      onListen: () {
        // The reader's own answer, when it has one.
        //
        // `TagLocationing` makes the reader work out how near the tag is — from
        // many reads, its own transmit power and its antenna — and that is what
        // Zebra's 123RFID shows. Ours had been estimating instead, from peak
        // RSSI, and peak means the STRONGEST read: one reflection off a wall or
        // a rack keeps the meter high while the operator walks away, which is
        // exactly the "far away but still 100%" they reported.
        //
        // Both are wired up on purpose. If locationing is unavailable on a
        // device, nothing arrives here and the RSSI path below keeps the meter
        // working as it does today, rather than leaving it dead.
        locateSub = _locateEvents.receiveBroadcastStream().listen(
          (event) {
            final value = (event as num?)?.toInt();
            if (value == null) return;
            if (!readerKnowsDistance) {
              readerKnowsDistance = true;
              recent.clear(); // drop our estimates; the reader's are better
            }
            noteRead(value);
          },
          onError: (_) {
            // No locate channel on this device — the RSSI path carries on.
          },
        );
        sub = tags.where((t) => targets.contains(t.epc.toUpperCase())).listen((
          t,
        ) {
          // Which of the hunted labels actually answered — worth showing,
          // because on an item with several labels that is the difference
          // between "found the box I expected" and "found the other box on the
          // same pallet".
          _lastEpc = t.epc.toUpperCase();
          // The dBm shown beside the meter always comes from the reads, whether
          // or not the reader is telling us a distance.
          _lastRssi = t.rssi;
          if (readerKnowsDistance) return;
          noteRead(_proximityFromRssi(t.rssi));
        }, onError: controller.addError);
        // Each trigger pull is its OWN measurement. Without this the first
        // reads of a new pull are averaged against the tail of the last one,
        // taken from wherever the operator was standing before.
        liveSub = live?.listen((held) {
          if (held == reading) return;
          reading = held;
          resetSession();
        });
        ticker = Timer.periodic(const Duration(milliseconds: 200), (_) {
          // Radio deliberately off (trigger released): say "not reading" rather
          // than inventing a number. Decaying here drew a sawtooth that tracked
          // the operator's grip instead of the distance, and freezing the last
          // percentage would be worse still — it looks like a live reading.
          if (!reading) {
            _lastRssi = 0;
            controller.add(-1);
            return;
          }
          final now = DateTime.now();
          trim(now);
          final silentMs = lastRead == null
              ? 1 << 30
              : now.difference(lastRead!).inMilliseconds;
          final holdMs = gaps.isEmpty
              ? minHoldMs
              : (_median(gaps) * 3).clamp(minHoldMs, maxHoldMs);
          if (recent.isNotEmpty && silentMs < holdMs) {
            // Ease toward the median of the last windowMs of reads.
            displayed +=
                easing *
                (_median([for (final r in recent) r.value]) - displayed);
          } else {
            // Long silence → tag lost/out of range: ease down gently, not a crash.
            displayed *= decay;
            // The dBm shown beside the meter is a reading, not a memory. It used
            // to survive until the meter hit exactly zero, so the whole way down
            // the ramp the screen paired a falling percentage with the last
            // strong reading — "3% · -34 dBm" is in the field log. Once we are
            // no longer following live reads there is no reading to show.
            _lastRssi = 0;
            if (displayed < 1) {
              displayed = 0;
              recent.clear();
              gaps.clear();
            }
          }
          controller.add(displayed.round().clamp(0, 100));
        });
        // Forward a failed start to the listener. Fire-and-forget here meant a
        // reader that refused to start threw into nowhere: the meter sat at 0,
        // the screen showed no error and no alert was ever sent — the button
        // simply looked dead.
        // Arm the reader for THIS tag, so it can run its own locationing rather
        // than a plain inventory we then filter ourselves.
        _methods
            .invokeMethod(RfidKitChannels.mStartLocate, {
              'epcs': targets.toList(),
            })
            .catchError((Object e, StackTrace s) {
              controller.addError(e, s);
              return null;
            });
      },
      onCancel: () async {
        ticker?.cancel();
        await sub?.cancel();
        await locateSub?.cancel();
        await liveSub?.cancel();
        _lastRssi = 0;
        _lastEpc = null;
        await stopLocate();
      },
    );
    return controller.stream;
  }

  /// Middle value of [values] — rejects the odd RSSI spike an average would let
  /// through. Caller guarantees the list is non-empty.
  int _median(List<int> values) {
    final sorted = [...values]..sort();
    return sorted[sorted.length ~/ 2];
  }

  /// Map RSSI → 0–100 over a fixed -80..-24 dBm radar window: far reads sit low,
  /// near reads approach full. Fixed (not adaptive) so distance reads honestly
  /// and a single weak distant read can't jump the meter to 100%.
  ///
  /// Both ends are MEASURED, and the window must span both or the meter lies.
  /// A ten-minute locate run on the handheld reported, from the reader itself:
  ///
  ///     4286 reads · RSSI min -77 · median -61 · max -24 dBm
  ///
  /// The window that produced those readings was -85..-35, and it was wrong at
  /// both ends — roughly 10 dB too low:
  ///
  ///  * Ceiling. The tag goes on getting stronger all the way to -24 dBm, but
  ///    everything from -35 up was already reading 100%. The last 11 dB — the
  ///    entire final approach, the part the operator most needs — was thrown
  ///    away, and the meter sat full while they were still hunting. On the
  ///    earlier full-power builds this was worse still: every reading arrives
  ///    8 dB stronger, so -35 was reached two or three metres out and the meter
  ///    read 100% beside the WRONG pallet. That was the field complaint, and it
  ///    was arithmetic, not radio.
  ///  * Floor. Nothing weaker than -77 dBm ever arrives — that is where the
  ///    receiver gives up — so the bottom 8 dB of the scale could never be used.
  ///
  /// The ceiling then over-corrected the other way. A later run measured a
  /// strongest-ever read of -23 dBm across 9885 reads, so a -20 dBm ceiling made
  /// 100% arithmetically unreachable: the session peak was 95%, which is exactly
  /// what this function returns for -23. The ceiling is now -24, the strongest
  /// figure the radio has actually produced.
  ///
  /// Deliberately NOT raised to Zebra's own "you are on it" constant
  /// (MultiTagLocateInfo.MAX_PROXIMITY_RSSI = -33 dBm), tempting as that is:
  /// at the bin this tag answers at -33..-35, so it would read 100% — but a bin
  /// several metres away answers at -38..-44, and the same shift lifts THAT to
  /// the eighties. The operator's second complaint is that the wrong bin already
  /// reads too high; anchoring at -33 makes it worse. That anchor is only safe
  /// once the meter is calibrated against a known distance.
  ///
  /// The rule both ends follow: never put the floor above the weakest RSSI the
  /// reader actually delivers, and never put the ceiling below the strongest.
  /// A read that arrives must move the meter off zero, and getting closer must
  /// keep moving it until the tag is in hand.
  int _proximityFromRssi(int rssi) {
    final clamped = rssi.clamp(-80, -24);
    return ((clamped + 80) * 100 ~/ 56).clamp(0, 100);
  }

  @override
  Future<void> setLocateRange(bool near) async {
    await _methods.invokeMethod(RfidKitChannels.mSetLocateRange, {
      'near': near,
    });
  }

  @override
  Future<void> stopLocate() =>
      _methods.invokeMethod(RfidKitChannels.mStopLocate);
}
