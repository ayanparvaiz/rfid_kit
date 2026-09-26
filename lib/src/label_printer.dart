import 'models/device_descriptor.dart';

/// A single RFID label to encode + print with the built-in layout: the barcode
/// as text and as Code 128, with [lines] of free text underneath.
class RfidLabel {
  /// Human-readable barcode printed on the label.
  final String barcode;

  /// EPC to encode into the label's RFID inlay — 24 hex characters (96-bit).
  final String epc;

  /// How many identical copies to print.
  final int quantity;

  /// Free text printed under the barcode, one entry per line (operator, date,
  /// location…). Blank entries are skipped.
  final List<String> lines;

  const RfidLabel({
    required this.barcode,
    required this.epc,
    this.quantity = 1,
    this.lines = const [],
  });
}

/// Brand/model-agnostic contract for any label printer.
///
/// Backed today by the Zebra Link-OS SDK (ZT411 and friends), but callers only
/// ever talk to this interface, so any other printer is a drop-in.
abstract interface class LabelPrinter {
  DeviceDescriptor get descriptor;

  bool get isConnected;

  Future<void> connect();
  Future<void> disconnect();

  /// Encode + print [label] with the built-in ZPL layout.
  ///
  /// Throws on a printer that does not speak ZPL: printing without encoding
  /// would hand back a sticker that looks right and carries its factory EPC.
  Future<void> printRfidLabel(RfidLabel label);

  /// Send [commands] (ZPL, CPCL, SGD…) to the printer exactly as given, for
  /// layouts the built-in label does not cover. To encode a tag in your own ZPL,
  /// include `^RFW,H^FD<epc>^FS`.
  Future<void> sendRaw(String commands);

  /// Run the printer's RFID Tag Calibration for the loaded label stock. Needed
  /// once per media type so that encoding lands on the tag (an un-calibrated
  /// printer voids every encode and the tag keeps its factory EPC).
  Future<void> calibrateRfid();
}
