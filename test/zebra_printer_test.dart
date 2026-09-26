import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rfid_kit/rfid_kit.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const channel = MethodChannel(RfidKitChannels.printerMethods);

  final calls = <MethodCall>[];
  late LabelPrinter printer;

  setUp(() async {
    calls.clear();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return null;
    });
    printer = await DeviceManager().resolvePrinter(
      connection: const ConnectionConfig.bluetooth('AC:3F:A4:11:22:33'),
    );
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('connect sends the transport and address', () async {
    await printer.connect();
    expect(calls.single.method, 'connect');
    expect(calls.single.arguments, containsPair('mac', 'AC:3F:A4:11:22:33'));
    expect(calls.single.arguments, containsPair('transport', 'bluetooth'));
    expect(printer.isConnected, isTrue);
  });

  test('a label goes over with its free-text lines', () async {
    await printer.printRfidLabel(const RfidLabel(
      barcode: 'SKU-1',
      epc: 'E28011700000020000000001',
      quantity: 2,
      lines: ['Op: Ayan', '2026-09-26'],
    ));
    expect(calls.single.method, 'printRfidLabel');
    expect(calls.single.arguments, {
      'barcode': 'SKU-1',
      'epc': 'E28011700000020000000001',
      'quantity': 2,
      'lines': ['Op: Ayan', '2026-09-26'],
    });
  });

  test('raw commands are passed through untouched', () async {
    await printer.sendRaw('^XA^FDhello^FS^XZ');
    expect(calls.single.method, 'sendRaw');
    expect(calls.single.arguments, {'commands': '^XA^FDhello^FS^XZ'});
  });

  test('a native failure names the printer and the cause chain', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(
        code: 'PRINTER_ERROR',
        message: 'Bluetooth is turned off on the handheld',
        details: 'ConnectionException <- IOException: read failed',
      );
    });
    expect(
      printer.connect(),
      throwsA(isA<DeviceConnectionException>().having(
        (e) => e.message,
        'message',
        allOf(
          contains('bluetooth AC:3F:A4:11:22:33'),
          contains('Bluetooth is turned off'),
          contains('cause: ConnectionException'),
        ),
      )),
    );
  });
}
