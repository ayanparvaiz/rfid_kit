/// What a connected device can actually do.
///
/// The UI reads these flags to adapt itself instead of hard-coding behaviour
/// per model — e.g. hide the "Locate" button on a device that can only print,
/// or hide "Print" on a read-only fixed reader.
class DeviceCapabilities {
  /// Can perform an RFID inventory (read tags in range).
  final bool canReadRfid;

  /// Supports the Geiger-counter "locate a specific tag" mode.
  final bool canLocateRfid;

  /// Can encode/write an EPC onto a tag.
  final bool canEncodeRfid;

  /// Can print labels.
  final bool canPrint;

  /// Has an onboard barcode/QR scanner driven through the same bridge.
  final bool canScanBarcode;

  const DeviceCapabilities({
    this.canReadRfid = false,
    this.canLocateRfid = false,
    this.canEncodeRfid = false,
    this.canPrint = false,
    this.canScanBarcode = false,
  });

  /// A reader that does everything a handheld RFID sled typically does.
  static const handheldReader = DeviceCapabilities(
    canReadRfid: true,
    canLocateRfid: true,
    canEncodeRfid: true,
    canScanBarcode: true,
  );

  /// A label printer (e.g. ZT411).
  static const printer = DeviceCapabilities(
    canPrint: true,
    canEncodeRfid: true,
  );

  factory DeviceCapabilities.fromMap(Map<dynamic, dynamic> map) {
    return DeviceCapabilities(
      canReadRfid: map['canReadRfid'] == true,
      canLocateRfid: map['canLocateRfid'] == true,
      canEncodeRfid: map['canEncodeRfid'] == true,
      canPrint: map['canPrint'] == true,
      canScanBarcode: map['canScanBarcode'] == true,
    );
  }
}
