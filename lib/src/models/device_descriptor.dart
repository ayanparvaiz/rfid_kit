import 'device_capabilities.dart';

/// How the app reaches a device.
enum DeviceTransport { integrated, usb, bluetooth, bluetoothLe, tcp, mock }

/// Identifies a concrete device + what it can do, brand/model agnostic.
///
/// This is what `DeviceManager` hands around so the UI can show "Zebra RFD40"
/// or "Zebra ZT411" without any model-specific code paths.
class DeviceDescriptor {
  /// Stable id for this device (serial, MAC, or "mock").
  final String id;

  /// e.g. "Zebra".
  final String vendor;

  /// e.g. "RFD40", "ZT411", "FXR90", "mock".
  final String model;

  final DeviceTransport transport;
  final DeviceCapabilities capabilities;

  const DeviceDescriptor({
    required this.id,
    required this.vendor,
    required this.model,
    required this.transport,
    required this.capabilities,
  });

  String get displayName => '$vendor $model';

  factory DeviceDescriptor.fromMap(Map<dynamic, dynamic> map) {
    return DeviceDescriptor(
      id: map['id'] as String? ?? 'unknown',
      vendor: map['vendor'] as String? ?? 'Zebra',
      model: map['model'] as String? ?? 'unknown',
      transport: DeviceTransport.values.firstWhere(
        (t) => t.name == map['transport'],
        orElse: () => DeviceTransport.integrated,
      ),
      capabilities: DeviceCapabilities.fromMap(
        (map['capabilities'] as Map?) ?? const {},
      ),
    );
  }

  @override
  String toString() => 'DeviceDescriptor($displayName, $transport)';
}
