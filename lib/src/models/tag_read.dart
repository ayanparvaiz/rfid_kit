/// A single RFID tag observation, normalized so the rest of the app never
/// has to care which reader brand or model produced it.
///
/// Every vendor SDK (Zebra RFD40, FXR90, …) reports tags differently; each
/// device implementation is responsible for mapping its raw payload into this
/// shape before it reaches the app.
class TagRead {
  /// The tag's EPC as an uppercase hex string (e.g. "E2801170...").
  final String epc;

  /// Raw signal strength in dBm (negative; closer to 0 == stronger).
  final int rssi;

  /// Antenna/port that saw the tag, when the reader exposes it.
  final int? antenna;

  /// When this observation was made on the device.
  final DateTime seenAt;

  const TagRead({
    required this.epc,
    required this.rssi,
    this.antenna,
    required this.seenAt,
  });

  factory TagRead.fromMap(Map<dynamic, dynamic> map) {
    return TagRead(
      epc: (map['epc'] as String).toUpperCase(),
      rssi: (map['rssi'] as num).toInt(),
      antenna: (map['antenna'] as num?)?.toInt(),
      seenAt: map['seenAtMs'] != null
          ? DateTime.fromMillisecondsSinceEpoch((map['seenAtMs'] as num).toInt())
          : DateTime.now(),
    );
  }

  @override
  String toString() => 'TagRead(epc: $epc, rssi: $rssi, antenna: $antenna)';
}
