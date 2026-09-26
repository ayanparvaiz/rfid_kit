import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'channels.dart';
import 'exceptions.dart';
import 'label_printer.dart';
import 'mock/mock_label_printer.dart';
import 'mock/mock_rfid_reader.dart';
import 'models/connection_config.dart';
import 'models/device_capabilities.dart';
import 'models/device_descriptor.dart';
import 'rfid_reader.dart';
import 'zebra/zebra_linkos_printer.dart';
import 'zebra/zebra_rfid_reader.dart';

/// How [DeviceManager] obtains its devices.
///
///  * [auto] — use the Zebra reader when this device has Zebra RFID hardware
///    (a Zebra handheld, the Zebra RFID service, or a paired RFD sled), and the
///    simulator otherwise — on an ordinary phone, an emulator, web or tests.
///    Before Bluetooth permission is granted a paired sled can't be ruled
///    out, so the reader is tried and its connect error says what's missing.
///    Safe default for development.
///  * [mock] — force the simulator. No hardware needed (tests, web, laptop).
///  * [real] — force real hardware; fail loudly if none is detected. Use this
///    for the build you ship, so a mock can never sneak into production.
enum DeviceMode { auto, mock, real }

/// Builds an [RfidReader] for a probed device.
typedef RfidReaderFactory =
    RfidReader Function(
      DeviceDescriptor descriptor,
      ConnectionConfig? connection,
    );

/// Builds a [LabelPrinter] for a device + connection.
typedef LabelPrinterFactory =
    LabelPrinter Function(
      DeviceDescriptor descriptor,
      ConnectionConfig? connection,
    );

/// The single place that decides *which* concrete device implementation an app
/// runs against:
///
///  * The app asks [DeviceManager] for an [RfidReader] / [LabelPrinter].
///  * [mode] decides mock vs real. In `auto`/`real` it probes the native side
///    and looks up a registered backend by model.
///  * Supporting another model = `registerReader('FXR90', ...)`; nothing else
///    in the app changes.
///
/// On Flutter Web, in `mock` mode, or when no backend matches in `auto`, it
/// falls back to the mock implementations so the app always runs.
class DeviceManager {
  DeviceManager({this.mode = DeviceMode.auto}) {
    // Zebra handheld RFID readers (RFD8500/RFD40/RFD90/MC33xx) share one native
    // RFID API3 SDK, so one factory covers them all. New families add another
    // key. Note: the RFD8500 is a *Bluetooth* sled that pairs to the handheld,
    // so the native side connects to it over BT rather than as a built-in
    // reader.
    for (final model in const ['RFD8500', 'RFD40', 'RFD90', 'MC3300R']) {
      registerReader(model, ZebraRfidReader.new);
    }
    // Zebra printers via Link-OS (auto-detects ZPL/CPCL itself).
    for (final model in const ['ZT411', 'ZQ', 'ZD']) {
      registerPrinter(model, ZebraLinkOsPrinter.new);
    }
  }

  /// The shared manager. Set its [mode] once at app start.
  static final DeviceManager instance = DeviceManager();

  /// Mock vs real — see [DeviceMode].
  DeviceMode mode;

  static const _probe = MethodChannel(RfidKitChannels.rfidMethods);

  final Map<String, RfidReaderFactory> _readers = {};
  final Map<String, LabelPrinterFactory> _printers = {};

  /// True when a native bridge can exist at all. Web and desktop can only ever
  /// run the mock.
  static bool get isHardwareCapable =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  void registerReader(String model, RfidReaderFactory factory) =>
      _readers[model.toUpperCase()] = factory;

  void registerPrinter(String model, LabelPrinterFactory factory) =>
      _printers[model.toUpperCase()] = factory;

  /// Asks the native bridge to describe the current hardware. Returns `null`
  /// on platforms without the bridge or when probing fails.
  Future<DeviceDescriptor?> probe() async {
    final raw = await _probeRaw();
    return raw == null ? null : DeviceDescriptor.fromMap(raw);
  }

  Future<Map<dynamic, dynamic>?> _probeRaw() async {
    if (!isHardwareCapable) return null;
    try {
      final raw = await _probe.invokeMethod(RfidKitChannels.mProbe);
      if (raw is Map) return raw;
    } on PlatformException {
      // Bridge present but errored.
    } on MissingPluginException {
      // No native bridge registered — fall through to mock.
    }
    return null;
  }

  /// Resolve the active RFID reader. Honours [mode] and falls back to a mock
  /// unless [DeviceMode.real] forbids it.
  ///
  /// Prefer `ReaderSession.reader()`, which opens the reader once and shares
  /// it — this method builds a fresh, unconnected handle every time.
  Future<RfidReader> resolveReader({ConnectionConfig? connection}) async {
    if (mode == DeviceMode.mock || !isHardwareCapable) {
      return MockRfidReader();
    }
    final raw = await _probeRaw();
    // The native side answers false only when it is sure there is no Zebra
    // RFID hardware to reach; null ("can't tell yet") still tries the reader.
    if (mode == DeviceMode.auto && raw?['zebraRfid'] == false) {
      return MockRfidReader();
    }
    final descriptor = raw == null ? null : DeviceDescriptor.fromMap(raw);
    if (descriptor != null) {
      final factory = _readers[descriptor.model.toUpperCase()];
      if (factory != null) return factory(descriptor, connection);
      // Every reader shipped here speaks the same Zebra RFID API3 SDK, so once
      // the native side has probed a device we drive it through that backend
      // — whatever the exact model string is (the probe reports the handheld,
      // e.g. "TC22R", not the reader inside it). The native backend then
      // reports a precise reason (not paired / permission / no reader) if it
      // can't attach, instead of us pre-judging by the model name here.
      return ZebraRfidReader(descriptor, connection);
    }
    if (mode == DeviceMode.real) {
      // No descriptor at all — the native bridge didn't answer the probe.
      throw const NoDeviceBackendException(
        'RFID reader could not be probed (DeviceMode.real). Make sure the '
        'app is running on a device with the rfid_kit native side.',
      );
    }
    return MockRfidReader();
  }

  /// Resolve a label printer for the given [connection]. A printer is a
  /// separate networked/BT device (not the handheld), so it is addressed by the
  /// connection the app supplies rather than by probing. [model] picks a
  /// registered factory; unknown models use the Link-OS printer.
  Future<LabelPrinter> resolvePrinter({
    ConnectionConfig? connection,
    String model = 'ZT411',
  }) async {
    // In real mode "no printer configured" must fail loudly. Silently handing
    // back the mock would report "printed OK" while nothing ever left the
    // printer — the worst kind of failure, an invisible one. An explicitly
    // chosen mock transport is still honoured: that one is a deliberate
    // request for the simulator, not an unset printer.
    if (mode == DeviceMode.real && isHardwareCapable && connection == null) {
      throw const NoDeviceBackendException(
        'No printer connection given (DeviceMode.real). Pass a '
        'ConnectionConfig for the printer.',
      );
    }
    final useMock =
        mode == DeviceMode.mock ||
        !isHardwareCapable ||
        connection == null ||
        connection.transport == DeviceTransport.mock;
    if (useMock) return MockLabelPrinter();

    final descriptor = DeviceDescriptor(
      id: connection.address,
      vendor: 'Zebra',
      model: model,
      transport: connection.transport,
      capabilities: DeviceCapabilities.printer,
    );
    final factory = _printers[model.toUpperCase()];
    return factory != null
        ? factory(descriptor, connection)
        : ZebraLinkOsPrinter(descriptor, connection);
  }
}
