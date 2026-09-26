import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rfid_kit/rfid_kit.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const rfid = MethodChannel(RfidKitChannels.rfidMethods);

  tearDown(() => messenger.setMockMethodCallHandler(rfid, null));

  test('mock mode never touches the native side', () async {
    final manager = DeviceManager(mode: DeviceMode.mock);
    expect(await manager.resolveReader(), isA<MockRfidReader>());
  });

  test(
    'auto mode falls back to the mock when nothing answers the probe',
    () async {
      final manager = DeviceManager();
      expect(await manager.resolveReader(), isA<MockRfidReader>());
    },
  );

  test('real mode fails loudly when nothing answers the probe', () async {
    final manager = DeviceManager(mode: DeviceMode.real);
    expect(manager.resolveReader(), throwsA(isA<NoDeviceBackendException>()));
  });

  test('a probed device is driven by the Zebra reader', () async {
    messenger.setMockMethodCallHandler(rfid, (call) async {
      if (call.method == RfidKitChannels.mProbe) {
        return {'model': 'TC22R', 'vendor': 'Zebra'};
      }
      return null;
    });
    final reader = await DeviceManager().resolveReader();
    expect(reader, isA<ZebraRfidReader>());
    expect(reader.descriptor.displayName, 'Zebra TC22R');
  });

  test('a registered model gets its own factory', () async {
    messenger.setMockMethodCallHandler(rfid, (call) async {
      return {'model': 'fxr90', 'vendor': 'Zebra'};
    });
    final fake = MockRfidReader();
    final manager = DeviceManager()..registerReader('FXR90', (_, _) => fake);
    expect(await manager.resolveReader(), same(fake));
  });

  test('real mode refuses a printer with no connection', () async {
    final manager = DeviceManager(mode: DeviceMode.real);
    expect(manager.resolvePrinter(), throwsA(isA<NoDeviceBackendException>()));
  });

  test('an explicit mock transport is honoured even in real mode', () async {
    final manager = DeviceManager(mode: DeviceMode.real);
    final printer = await manager.resolvePrinter(
      connection: const ConnectionConfig.mock(),
    );
    expect(printer, isA<MockLabelPrinter>());
  });

  test('a TCP printer resolves to the Link-OS printer', () async {
    final printer = await DeviceManager().resolvePrinter(
      connection: const ConnectionConfig.tcp('10.0.0.9'),
    );
    expect(printer, isA<ZebraLinkOsPrinter>());
    expect(printer.descriptor.id, '10.0.0.9');
  });
}
