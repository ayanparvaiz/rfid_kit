import '../label_printer.dart';
import '../models/device_capabilities.dart';
import '../models/device_descriptor.dart';

/// A fake printer for tests, web and dev machines: "prints" by completing after
/// a short delay. Everything it was asked to print is kept in [printed] and
/// [raw], so tests can assert on it.
class MockLabelPrinter implements LabelPrinter {
  bool _connected = false;

  /// Labels passed to [printRfidLabel], in order.
  final List<RfidLabel> printed = [];

  /// Commands passed to [sendRaw], in order.
  final List<String> raw = [];

  @override
  final DeviceDescriptor descriptor = const DeviceDescriptor(
    id: 'mock-printer',
    vendor: 'Mock',
    model: 'Simulator',
    transport: DeviceTransport.mock,
    capabilities: DeviceCapabilities.printer,
  );

  @override
  bool get isConnected => _connected;

  @override
  Future<void> connect() async => _connected = true;

  @override
  Future<void> disconnect() async => _connected = false;

  @override
  Future<void> printRfidLabel(RfidLabel label) async {
    // Simulate the round-trip of encoding + printing each copy.
    await Future.delayed(Duration(milliseconds: 200 * label.quantity));
    printed.add(label);
  }

  @override
  Future<void> sendRaw(String commands) async {
    await Future.delayed(const Duration(milliseconds: 100));
    raw.add(commands);
  }

  @override
  Future<void> calibrateRfid() async {
    await Future.delayed(const Duration(milliseconds: 400));
  }
}
