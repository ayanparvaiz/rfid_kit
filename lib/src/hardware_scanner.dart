import 'package:flutter/services.dart';

import 'channels.dart';
import 'device_manager.dart';

/// Barcodes from the device's **physical scan trigger**, through Zebra
/// DataWedge.
///
/// On the first listener the plugin creates a DataWedge profile bound to your
/// app with Intent output (and keystroke output off), so the hardware laser
/// button delivers barcodes here without opening the camera. On devices
/// without DataWedge, on web and in tests the stream simply stays quiet — keep
/// a camera scanner as the fallback.
class HardwareScanner {
  /// [profileName] is the DataWedge profile to create or update; it defaults
  /// to your app's package name.
  HardwareScanner({this.profileName});

  /// A scanner with the default profile name.
  static final HardwareScanner instance = HardwareScanner();

  final String? profileName;

  Stream<String>? _scans;

  /// Barcodes as they arrive. One shared stream per scanner, so any number of
  /// widgets can listen without opening a second native subscription.
  Stream<String> get scans {
    if (!DeviceManager.isHardwareCapable) return const Stream.empty();
    final name = profileName;
    return _scans ??= const EventChannel(RfidKitChannels.scannerEvents)
        .receiveBroadcastStream(name == null ? null : {'profile': name})
        .map((event) => event?.toString().trim() ?? '')
        .where((code) => code.isNotEmpty)
        // No DataWedge / channel not available — the stream just stays quiet.
        .handleError((Object _) {});
  }

  /// Turn the barcode scanner off ([enabled] false) or back on.
  ///
  /// On some all-in-one handhelds the grip trigger drives DataWedge's barcode
  /// scanner, so a press fires the laser and the RFID reader never hears it.
  /// Disable the scanner for the length of an RFID scan, and enable it again
  /// when the scan ends.
  Future<void> setEnabled(bool enabled) async {
    if (!DeviceManager.isHardwareCapable) return;
    try {
      await const MethodChannel(
        RfidKitChannels.scannerMethods,
      ).invokeMethod('setEnabled', {'enabled': enabled});
    } on MissingPluginException {
      // No native side — nothing to toggle.
    }
  }
}
