/// Zebra RFID readers, Link-OS label printers and DataWedge barcode scanners
/// for Flutter, behind vendor-neutral interfaces.
///
/// Start with [ReaderSession.instance] for the reader, [DeviceManager] for a
/// printer, and [HardwareScanner.instance] for the scan trigger. See the
/// README for the Android setup (the Zebra SDK files are not bundled).
library;

export 'src/channels.dart' show RfidKitChannels;
export 'src/device_manager.dart';
export 'src/diagnostics.dart';
export 'src/exceptions.dart';
export 'src/hardware_scanner.dart';
export 'src/label_printer.dart';
export 'src/mock/mock_label_printer.dart';
export 'src/mock/mock_rfid_reader.dart';
export 'src/models/connection_config.dart';
export 'src/models/device_capabilities.dart';
export 'src/models/device_descriptor.dart';
export 'src/models/tag_read.dart';
export 'src/reader_session.dart';
export 'src/rfid_reader.dart';
export 'src/screen_wake.dart';
export 'src/zebra/zebra_linkos_printer.dart';
export 'src/zebra/zebra_rfid_reader.dart';
