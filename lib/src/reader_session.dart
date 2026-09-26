import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'channels.dart';
import 'device_manager.dart';
import 'exceptions.dart';
import 'models/connection_config.dart';
import 'rfid_reader.dart';

/// Owns the ONE RFID reader an app should have open, and the hardware
/// trigger's state.
///
/// Screens must not connect on entry and disconnect on exit. A Zebra reader
/// takes the better part of ten seconds to open, and after open/close/open
/// churn it can stop answering altogether ("Response timeout") until the
/// handheld is rebooted. So [reader] opens it once and keeps it for the life
/// of the app; only [release] lets it go.
///
/// Use [ReaderSession.instance]: the native side calls back into Dart on one
/// channel, and only one session can own that callback.
class ReaderSession {
  ReaderSession({
    DeviceManager? manager,
    this.cooldown = const Duration(seconds: 30),
    @visibleForTesting DateTime Function()? clock,
  })  : manager = manager ?? DeviceManager.instance,
        _now = clock ?? DateTime.now;

  /// The shared session.
  static final ReaderSession instance = ReaderSession();

  final DeviceManager manager;

  /// How long the radio is left alone after a failed open.
  ///
  /// Not a guess: in the field a failed reader recovered only when it was left
  /// untouched — once after two and a quarter minutes (open in 7.0 s), once
  /// after three and a half (open in 4 s) — and every attempt made *without*
  /// that gap failed. Retrying immediately is not neutral; it is what keeps
  /// the reader down. Thirty seconds breaks the press-fail-press cycle without
  /// locking anyone out of a radio that may be perfectly fine.
  final Duration cooldown;

  final DateTime Function() _now;

  RfidReader? _rfid;

  /// The open-in-progress call, so overlapping callers share one connect.
  Future<RfidReader>? _opening;

  /// When the last open failed, so the next one can leave the radio alone.
  DateTime? _lastFailureAt;

  final _triggerSeen = ValueNotifier<bool>(false);
  final _triggerHeld = ValueNotifier<bool>(false);
  final _triggerHeldChanges = StreamController<bool>.broadcast();
  final _triggerEvents = StreamController<String>.broadcast();
  final _notes = StreamController<String>.broadcast();
  bool _listening = false;

  /// True once the handheld's own RFID trigger has been seen at least once.
  /// Until it is, a broken button can't be told from one another app owns.
  ValueListenable<bool> get triggerSeen {
    _listen();
    return _triggerSeen;
  }

  /// True while the operator is physically holding the RFID trigger, i.e.
  /// while the radio is actually reading.
  ValueListenable<bool> get triggerHeld {
    _listen();
    return _triggerHeld;
  }

  /// [triggerHeld] as a stream of changes — pass it to
  /// `RfidReader.locate(live: ...)` so the meter shows "not reading" between
  /// trigger pulls instead of decaying as if the tag were lost.
  Stream<bool> get triggerHeldChanges {
    _listen();
    return _triggerHeldChanges.stream;
  }

  /// Every trigger event, by the SDK's own name
  /// (`HANDHELD_TRIGGER_PRESSED (1)`, `..._RELEASED (0)`, …).
  Stream<String> get triggerEvents {
    _listen();
    return _triggerEvents.stream;
  }

  /// Diagnostics nobody is waiting on: how long an open took, the transmit
  /// power actually applied, a restart that failed, the end-of-locate report.
  /// Log or forward these — on a device you can't attach to, they are how a
  /// failure reaches you.
  Stream<String> get notes {
    _listen();
    return _notes.stream;
  }

  /// Time still to wait after a failed open, or zero when the reader is free.
  Duration get cooldownRemaining {
    final at = _lastFailureAt;
    if (at == null) return Duration.zero;
    final left = cooldown - _now().difference(at);
    return left.isNegative ? Duration.zero : left;
  }

  /// The one RFID reader the whole app shares, connected.
  ///
  /// Callers overlap in normal use — a screen warms the reader up on entry
  /// while the operator presses Locate a second later — so the open is shared
  /// rather than repeated. Without that, both callers would see no reader,
  /// both would ask the native side to connect, and the second press would
  /// wait behind the first's whole attempt: exactly what a dead button looks
  /// like.
  ///
  /// Throws a [DeviceConnectionException] during [cooldown] after a failure.
  Future<RfidReader> reader({ConnectionConfig? connection}) {
    _listen();
    // Refuse to hammer a reader that just failed.
    final waitLeft = cooldownRemaining;
    if (waitLeft > Duration.zero && _rfid == null) {
      return Future.error(
        DeviceConnectionException(
          'The reader is recovering from a failed connection. Please wait '
          '${waitLeft.inSeconds + 1} more seconds, then try once. Trying again '
          'straight away is what stops it recovering.',
        ),
      );
    }
    final open = _opening;
    if (open != null) return open;
    final ready = _rfid;
    if (ready != null && ready.isConnected) return Future.value(ready);
    final future = _open(connection);
    _opening = future;
    // Clear the slot however it ends, so a failure doesn't pin every later
    // caller to the same dead attempt.
    return future.whenComplete(() {
      if (identical(_opening, future)) _opening = null;
    });
  }

  Future<RfidReader> _open(ConnectionConfig? connection) async {
    // Timed on both outcomes: a reader that is genuinely slow to wake and one
    // stuck in a retry ladder look identical unless the number is logged.
    final started = _now();
    String took() =>
        '${(_now().difference(started).inMilliseconds / 1000).toStringAsFixed(1)}s';
    final reader =
        _rfid ?? await manager.resolveReader(connection: connection);
    _rfid = reader;
    if (!reader.isConnected) {
      try {
        await reader.connect();
      } catch (e) {
        _rfid = null; // never keep a handle that failed to open
        // Start the quiet period — the radio comes back on its own if left
        // alone, and not otherwise.
        _lastFailureAt = _now();
        _note('Reader open failed after ${took()}: $e');
        rethrow;
      }
      _lastFailureAt = null;
      _note('Reader opened in ${took()} (${reader.descriptor.displayName})');
    }
    return reader;
  }

  /// Drop the reader — after a failure, or when the app is going away. The
  /// next [reader] call enumerates a fresh one.
  Future<void> release() async {
    final reader = _rfid;
    _rfid = null;
    if (reader == null) return;
    try {
      await reader.stopLocate();
    } catch (_) {
      // already stopped
    }
    try {
      await reader.disconnect();
    } catch (_) {
      // already gone
    }
  }

  void _note(String note) {
    if (!_notes.isClosed) _notes.add(note);
  }

  /// The native side calls back on the RFID method channel when the physical
  /// trigger is pressed or released, and with notes about the reader.
  void _listen() {
    if (_listening || !DeviceManager.isHardwareCapable) return;
    _listening = true;
    const MethodChannel(RfidKitChannels.rfidMethods)
        .setMethodCallHandler((call) async {
      handleNativeCall(call.method, call.arguments);
      return null;
    });
  }

  /// Applies one native -> Dart callback. Public so tests can drive it.
  @visibleForTesting
  void handleNativeCall(String method, Object? arguments) {
    switch (method) {
      case RfidKitChannels.cbTrigger:
        _triggerSeen.value = true;
        // The SDK spells these HANDHELD_TRIGGER_PRESSED / _RELEASED; match on
        // the verb so a device that prefixes them differently still works.
        final event = '$arguments'.toUpperCase();
        bool? held;
        if (event.contains('PRESSED')) {
          held = true;
        } else if (event.contains('RELEASED')) {
          held = false;
        }
        if (held != null && held != _triggerHeld.value) {
          _triggerHeld.value = held;
          if (!_triggerHeldChanges.isClosed) _triggerHeldChanges.add(held);
        }
        if (!_triggerEvents.isClosed) _triggerEvents.add('$arguments');
      case RfidKitChannels.cbReaderNote:
        // The reader went quiet on its own, or has something to report, and
        // nobody is waiting on a call — it would otherwise vanish.
        _note('$arguments');
    }
  }

  /// Closes the streams. Only for sessions you created yourself (tests).
  Future<void> dispose() async {
    await release();
    await _triggerHeldChanges.close();
    await _triggerEvents.close();
    await _notes.close();
    _triggerSeen.dispose();
    _triggerHeld.dispose();
  }
}
