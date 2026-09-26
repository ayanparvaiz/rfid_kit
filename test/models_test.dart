import 'package:flutter_test/flutter_test.dart';
import 'package:rfid_kit/rfid_kit.dart';

void main() {
  test('TagRead normalises the EPC to uppercase and reads the timestamp', () {
    final read = TagRead.fromMap({
      'epc': 'e2801170aaaa',
      'rssi': -61,
      'antenna': 1,
      'seenAtMs': 1700000000000,
    });
    expect(read.epc, 'E2801170AAAA');
    expect(read.rssi, -61);
    expect(read.antenna, 1);
    expect(read.seenAt, DateTime.fromMillisecondsSinceEpoch(1700000000000));
  });

  test('ConnectionConfig survives a round trip through toMap', () {
    const config = ConnectionConfig.tcp('192.168.1.50', port: 6101);
    final back = ConnectionConfig.fromMap(config.toMap());
    expect(back.transport, DeviceTransport.tcp);
    expect(back.host, '192.168.1.50');
    expect(back.port, 6101);
    expect(back.displayTarget, '192.168.1.50:6101');
  });

  test('ConnectionConfig names a Bluetooth printer by its MAC', () {
    const config = ConnectionConfig.bluetooth('AC:3F:A4:11:22:33');
    expect(config.displayTarget, 'AC:3F:A4:11:22:33');
    expect(config.toArgs()['transport'], 'bluetooth');
  });

  test('DeviceDescriptor falls back to safe defaults', () {
    final d = DeviceDescriptor.fromMap({'model': 'TC22R'});
    expect(d.model, 'TC22R');
    expect(d.transport, DeviceTransport.integrated);
    expect(d.capabilities.canReadRfid, isFalse);
  });

  test('NativeCrash reads the header the crash guard writes', () {
    final crash = NativeCrash.parse(
      'fatal: false\n'
      'thread: Thread-74\n'
      'java.lang.IllegalStateException: Already running\n'
      '\tat com.zebra.rfid.api3.SerialInputOutputManager.run',
    );
    expect(crash.fatal, isFalse);
    expect(crash.thread, 'Thread-74');
    expect(crash.trace, startsWith('java.lang.IllegalStateException'));
  });

  test('NativeCrash without a header is treated as fatal', () {
    final crash = NativeCrash.parse('java.lang.RuntimeException: boom');
    expect(crash.fatal, isTrue);
    expect(crash.thread, isNull);
    expect(crash.trace, 'java.lang.RuntimeException: boom');
  });
}
