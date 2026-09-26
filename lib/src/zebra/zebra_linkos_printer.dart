import 'package:flutter/services.dart';

import '../channels.dart';
import '../exceptions.dart';
import '../label_printer.dart';
import '../models/connection_config.dart';
import '../models/device_descriptor.dart';

/// Label printer backed by the native Zebra Link-OS SDK (ZT411 and other Zebra
/// printers) through the generic bridge.
///
/// The Link-OS SDK auto-detects the printer's command language, so this one
/// class covers the whole Zebra printer range — only the connection address
/// changes per unit.
///
/// Connecting over Bluetooth pairs with the printer first when it isn't bonded
/// yet, so Android shows its passkey prompt — see the README.
class ZebraLinkOsPrinter implements LabelPrinter {
  ZebraLinkOsPrinter(this.descriptor, [this.connection]);

  @override
  final DeviceDescriptor descriptor;

  /// Transport + address (TCP/WiFi, Bluetooth or BLE) for this printer.
  final ConnectionConfig? connection;

  static const _methods = MethodChannel(RfidKitChannels.printerMethods);

  bool _connected = false;

  @override
  bool get isConnected => _connected;

  /// The printer this instance drives, e.g. `bluetooth AC:3F:A4:11:22:33`.
  /// Every failure quotes it, so an error says *which* printer/address failed
  /// instead of only that something failed.
  String get _target {
    final config = connection;
    if (config == null) return descriptor.displayName;
    return '${config.transport.name} ${config.displayTarget}';
  }

  /// What we were doing, to which printer, why it failed, and the native cause
  /// chain the bridge passes in `details`.
  String _failure(String action, PlatformException e) {
    final reason = e.message?.trim() ?? '';
    final details = e.details is String ? (e.details as String).trim() : '';
    final buffer = StringBuffer('$action $_target failed');
    if (reason.isNotEmpty) buffer.write(': $reason');
    if (details.isNotEmpty) buffer.write('\ncause: $details');
    return buffer.toString();
  }

  @override
  Future<void> connect() async {
    try {
      await _methods.invokeMethod(RfidKitChannels.mConnect, {
        'id': descriptor.id,
        ...?connection?.toArgs(),
      });
      _connected = true;
    } on PlatformException catch (e) {
      throw DeviceConnectionException(_failure('Connect to', e), cause: e);
    }
  }

  @override
  Future<void> disconnect() async {
    await _methods.invokeMethod(RfidKitChannels.mDisconnect);
    _connected = false;
  }

  @override
  Future<void> printRfidLabel(RfidLabel label) async {
    try {
      await _methods.invokeMethod(RfidKitChannels.mPrintRfidLabel, {
        'barcode': label.barcode,
        'epc': label.epc,
        'quantity': label.quantity,
        'lines': label.lines,
      });
    } on PlatformException catch (e) {
      throw DeviceException(_failure('Print on', e), cause: e);
    }
  }

  @override
  Future<void> sendRaw(String commands) async {
    try {
      await _methods.invokeMethod(RfidKitChannels.mSendRaw, {
        'commands': commands,
      });
    } on PlatformException catch (e) {
      throw DeviceException(_failure('Send to', e), cause: e);
    }
  }

  @override
  Future<void> calibrateRfid() async {
    try {
      await _methods.invokeMethod(RfidKitChannels.mCalibrateRfid);
    } on PlatformException catch (e) {
      throw DeviceException(_failure('Calibrate on', e), cause: e);
    }
  }
}
