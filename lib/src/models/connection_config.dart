import 'device_descriptor.dart';

/// How to reach a specific device — transport + address.
///
/// Zebra printers (ZT411 …) support all of TCP/WiFi, Bluetooth Classic,
/// Bluetooth LE and USB; this object carries whichever address the chosen
/// transport needs and is passed straight to the native bridge.
class ConnectionConfig {
  final DeviceTransport transport;

  /// TCP/WiFi host or DNS name.
  final String? host;

  /// TCP port — Zebra printers default to 9100 (raw).
  final int port;

  /// Bluetooth Classic / BLE MAC address.
  final String? mac;

  /// USB device identifier (resolved via UsbDiscoverer on Android).
  final String? usbId;

  const ConnectionConfig({
    required this.transport,
    this.host,
    this.port = 9100,
    this.mac,
    this.usbId,
  });

  const ConnectionConfig.tcp(String host, {int port = 9100})
      : this(transport: DeviceTransport.tcp, host: host, port: port);

  const ConnectionConfig.bluetooth(String mac)
      : this(transport: DeviceTransport.bluetooth, mac: mac);

  const ConnectionConfig.bluetoothLe(String mac)
      : this(transport: DeviceTransport.bluetoothLe, mac: mac);

  const ConnectionConfig.usb(String usbId)
      : this(transport: DeviceTransport.usb, usbId: usbId);

  const ConnectionConfig.mock() : this(transport: DeviceTransport.mock);

  /// The human-facing address for whatever transport is in use.
  String get address => host ?? mac ?? usbId ?? 'mock';

  /// What a message should call this target, e.g. `192.168.1.50:9100` or
  /// `AC:3F:A4:11:22:33`. Errors quote it so a failure names the printer it was
  /// actually talking to.
  String get displayTarget =>
      transport == DeviceTransport.tcp ? '${host ?? '?'}:$port' : address;

  /// Flattened for sending across the platform channel.
  Map<String, dynamic> toArgs() => {
        'transport': transport.name,
        'host': host,
        'port': port,
        'mac': mac,
        'usbId': usbId,
      };

  /// Serializable form (same shape as [toArgs]) for persisting to storage.
  Map<String, dynamic> toMap() => toArgs();

  /// Rebuilds a config from its [toMap] form.
  factory ConnectionConfig.fromMap(Map<String, dynamic> map) => ConnectionConfig(
        transport: DeviceTransport.values.firstWhere(
          (t) => t.name == map['transport'],
          orElse: () => DeviceTransport.mock,
        ),
        host: map['host'] as String?,
        port: (map['port'] as num?)?.toInt() ?? 9100,
        mac: map['mac'] as String?,
        usbId: map['usbId'] as String?,
      );
}
