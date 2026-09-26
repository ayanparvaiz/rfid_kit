import 'models/device_descriptor.dart';
import 'models/tag_read.dart';

/// The brand/model-agnostic contract for any RFID reader.
///
/// Write your app against this interface only. Swapping an RFD40 for an FXR90
/// or any other reader means adding one more implementation of this interface
/// — no caller changes.
abstract interface class RfidReader {
  /// Identity + capabilities of the connected reader.
  DeviceDescriptor get descriptor;

  bool get isConnected;

  Future<void> connect();
  Future<void> disconnect();

  /// Tags seen during an active inventory session. Listen BEFORE starting, or
  /// the first reads are dropped.
  Stream<TagRead> get tags;

  /// Arm an inventory: read every tag in range. On a Zebra handheld the radio
  /// only runs while the physical trigger is held. [power] is an optional
  /// 0–100 hint; implementations clamp/ignore it if the hardware doesn't
  /// support it.
  Future<void> startInventory({double? power});

  Future<void> stopInventory();

  /// Geiger-counter proximity for ANY of [epcs]: emits 0–100 (higher ==
  /// nearer), or **-1 while the radio is not reading**. Cancelling the
  /// subscription (or calling [stopLocate]) ends the session.
  ///
  /// A list because one item can carry several labels — one per box on a
  /// pallet, say — and in a rack they shadow one another. Which one the radio
  /// can hear is luck, so hunting only the "primary" one throws away every
  /// other chance of finding it.
  ///
  /// [live] tells the reader when the operator is actually holding the trigger
  /// (pass `ReaderSession.triggerHeldChanges`). Without it the meter cannot
  /// tell "the tag is gone" from "the radio is deliberately off between two
  /// trigger pulls", and it decays through every release — a sawtooth locked
  /// to the operator's grip rather than to distance. Readers that always read
  /// may ignore it.
  Stream<int> locate(List<String> epcs, {Stream<bool>? live});

  /// RSSI in dBm of the most recent read during [locate] — 0 before anything is
  /// read. Exposed here rather than as a second stream because a platform
  /// EventChannel serves ONE listener: subscribing twice to [tags] silently
  /// starves the first subscriber.
  int get lastRssi;

  /// Which of the hunted labels answered most recently, or null if none has.
  String? get lastEpc;

  Future<void> stopLocate();

  /// Pull the radio's reach in to arm's length ([near] true), or put it back to
  /// hunting reach.
  ///
  /// Signal strength alone cannot reliably tell the right item from its
  /// neighbour — in a metal aisle the difference between them is smaller than
  /// the way a single tag's own strength wanders while the operator stands
  /// still. Whether the tag answers AT ALL is a much sharper question, so the
  /// close-range setting turns the meter into a yes/no: at arm's length the
  /// target still answers and the neighbour two bins along has gone quiet.
  ///
  /// Best-effort. A reader that cannot change its power simply keeps reading as
  /// before.
  Future<void> setLocateRange(bool near);
}
