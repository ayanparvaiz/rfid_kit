/// Names of the platform channels that bridge Dart <-> native (Kotlin).
///
/// These are intentionally generic ("rfid", not "zebra_rfd40") — the native
/// side maps them onto whatever vendor SDK is present, which is what keeps the
/// Dart API model-agnostic. A new device == a new native handler behind the
/// SAME channel names; Dart never changes.
class RfidKitChannels {
  RfidKitChannels._();

  /// Request/response calls (connect, startInventory, locate, probe…).
  ///
  /// The native side also calls back INTO Dart on this channel with
  /// `onTrigger` and `onReaderNote` — see `ReaderSession`.
  static const String rfidMethods = 'rfid_kit/rfid';
  static const String printerMethods = 'rfid_kit/printer';

  /// Streamed tag reads during inventory and locate.
  static const String tagEvents = 'rfid_kit/rfid/tags';

  /// Streamed 0–100 proximity values, when the reader works distance out itself.
  static const String locateEvents = 'rfid_kit/rfid/locate';

  /// Streamed barcodes from the Zebra hardware scan trigger (DataWedge).
  static const String scannerEvents = 'rfid_kit/scanner/scans';
  static const String scannerMethods = 'rfid_kit/scanner';

  /// Keep-screen-on while scanning.
  static const String screenMethods = 'rfid_kit/screen';

  /// Native-side diagnostics (crash guard, previous run's crash trace).
  static const String diagnosticsMethods = 'rfid_kit/diagnostics';

  // --- Method names (shared contract with the native bridge) ---
  static const String mProbe = 'probe'; // returns DeviceDescriptor map
  static const String mConnect = 'connect';
  static const String mDisconnect = 'disconnect';
  static const String mStartInventory = 'startInventory';
  static const String mStopInventory = 'stopInventory';
  static const String mStartLocate = 'startLocate';
  static const String mStopLocate = 'stopLocate';

  /// Narrow or widen the locate radio's reach — see `RfidReader.setLocateRange`.
  /// Vendor-neutral on purpose: a reader that cannot change power answers this
  /// by doing nothing.
  static const String mSetLocateRange = 'setLocateRange';
  static const String mPrintRfidLabel = 'printRfidLabel';
  static const String mSendRaw = 'sendRaw';
  static const String mCalibrateRfid = 'calibrateRfid';

  /// Native -> Dart callbacks on [rfidMethods].
  static const String cbTrigger = 'onTrigger';
  static const String cbReaderNote = 'onReaderNote';
}
