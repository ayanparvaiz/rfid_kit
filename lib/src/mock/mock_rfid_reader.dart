import 'dart:async';
import 'dart:math';

import '../models/device_capabilities.dart';
import '../models/device_descriptor.dart';
import '../models/tag_read.dart';
import '../rfid_reader.dart';

/// A fake reader so an app runs on Flutter Web, in tests and on dev machines
/// with no hardware attached. Emits random tags during inventory and a
/// wandering proximity value during locate.
class MockRfidReader implements RfidReader {
  final _rng = Random();
  final _tags = StreamController<TagRead>.broadcast();
  Timer? _inventoryTimer;
  Timer? _locateTimer;
  bool _connected = false;

  static const _sampleEpcs = [
    'E280117000000209A1B2C301',
    'E280117000000209A1B2C302',
    'E280117000000209A1B2C303',
  ];

  @override
  final DeviceDescriptor descriptor = const DeviceDescriptor(
    id: 'mock',
    vendor: 'Mock',
    model: 'Simulator',
    transport: DeviceTransport.mock,
    capabilities: DeviceCapabilities.handheldReader,
  );

  @override
  bool get isConnected => _connected;

  @override
  Future<void> connect() async => _connected = true;

  @override
  Future<void> disconnect() async {
    await stopInventory();
    await stopLocate();
    _connected = false;
  }

  @override
  Stream<TagRead> get tags => _tags.stream;

  @override
  Future<void> startInventory({double? power}) async {
    _inventoryTimer?.cancel();
    _inventoryTimer = Timer.periodic(const Duration(milliseconds: 400), (_) {
      _tags.add(TagRead(
        epc: _sampleEpcs[_rng.nextInt(_sampleEpcs.length)],
        rssi: -30 - _rng.nextInt(50),
        antenna: 1,
        seenAt: DateTime.now(),
      ));
    });
  }

  @override
  Future<void> stopInventory() async {
    _inventoryTimer?.cancel();
    _inventoryTimer = null;
  }

  int _lastRssi = 0;

  @override
  String? get lastEpc => null;

  @override
  int get lastRssi => _lastRssi;

  @override
  Future<void> setLocateRange(bool near) async {
    // No radio to turn down.
  }

  @override
  Stream<int> locate(List<String> epcs, {Stream<bool>? live}) {
    final controller = StreamController<int>();
    var proximity = 10;
    _locateTimer?.cancel();
    _locateTimer = Timer.periodic(const Duration(milliseconds: 300), (_) {
      proximity = (proximity + _rng.nextInt(21) - 8).clamp(0, 100);
      // Roughly the inverse of the real mapping, so the simulator shows a
      // plausible dBm next to the meter.
      _lastRssi = -75 + (proximity * 23 ~/ 100);
      controller.add(proximity);
    });
    controller.onCancel = stopLocate;
    return controller.stream;
  }

  @override
  Future<void> stopLocate() async {
    _locateTimer?.cancel();
    _locateTimer = null;
    _lastRssi = 0;
  }
}
