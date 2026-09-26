import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:rfid_kit/rfid_kit.dart';

/// A reader whose connect can be held open or made to fail.
class _FakeReader extends MockRfidReader {
  int connects = 0;
  Completer<void>? gate;
  Object? failWith;
  bool _up = false;

  @override
  bool get isConnected => _up;

  @override
  Future<void> connect() async {
    connects++;
    await gate?.future;
    final error = failWith;
    if (error != null) throw error;
    _up = true;
  }

  @override
  Future<void> disconnect() async => _up = false;
}

class _FakeManager extends DeviceManager {
  _FakeManager(this.reader);
  final _FakeReader reader;
  int resolves = 0;

  @override
  Future<RfidReader> resolveReader({ConnectionConfig? connection}) async {
    resolves++;
    return reader;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _FakeReader reader;
  late _FakeManager manager;
  late DateTime now;
  late ReaderSession session;

  setUp(() {
    reader = _FakeReader();
    manager = _FakeManager(reader);
    now = DateTime(2026, 1, 1, 12);
    session = ReaderSession(manager: manager, clock: () => now);
  });

  test('overlapping callers share one open', () async {
    reader.gate = Completer<void>();
    final first = session.reader();
    final second = session.reader();
    reader.gate!.complete();
    expect(await first, same(await second));
    expect(reader.connects, 1);
  });

  test('an open reader is reused, not reconnected', () async {
    await session.reader();
    await session.reader();
    expect(reader.connects, 1);
    expect(manager.resolves, 1);
  });

  test('a failed open holds the radio off for the cooldown', () async {
    reader.failWith = const DeviceConnectionException('Response timeout');
    await expectLater(session.reader(), throwsA(isA<DeviceException>()));
    expect(session.cooldownRemaining, const Duration(seconds: 30));

    reader.failWith = null;
    now = now.add(const Duration(seconds: 10));
    await expectLater(
      session.reader(),
      throwsA(
        isA<DeviceConnectionException>().having(
          (e) => e.message,
          'message',
          contains('21 more seconds'),
        ),
      ),
    );
    expect(reader.connects, 1, reason: 'the cooldown must not touch the radio');

    now = now.add(const Duration(seconds: 21));
    await session.reader();
    expect(reader.connects, 2);
    expect(session.cooldownRemaining, Duration.zero);
  });

  test('opens and failures are reported as notes', () async {
    final notes = <String>[];
    final sub = session.notes.listen(notes.add);
    await session.reader();
    await Future<void>.delayed(Duration.zero);
    expect(notes.single, startsWith('Reader opened in'));
    await sub.cancel();
  });

  test('release drops the reader so the next call opens a fresh one', () async {
    await session.reader();
    await session.release();
    expect(reader.isConnected, isFalse);
    await session.reader();
    expect(manager.resolves, 2);
  });

  test(
    'trigger events drive triggerHeld, and only real changes are streamed',
    () async {
      final changes = <bool>[];
      final sub = session.triggerHeldChanges.listen(changes.add);

      session.handleNativeCall('onTrigger', 'HANDHELD_TRIGGER_PRESSED (1)');
      session.handleNativeCall('onTrigger', 'HANDHELD_TRIGGER_PRESSED (1)');
      expect(session.triggerHeld.value, isTrue);
      session.handleNativeCall('onTrigger', 'HANDHELD_TRIGGER_RELEASED (0)');
      session.handleNativeCall('onTrigger', 'HANDHELD_TRIGGER_LOCK (5)');
      await Future<void>.delayed(Duration.zero);

      expect(session.triggerSeen.value, isTrue);
      expect(session.triggerHeld.value, isFalse);
      expect(changes, [true, false]);
      await sub.cancel();
    },
  );

  test('reader notes from the native side reach the notes stream', () async {
    final notes = <String>[];
    final sub = session.notes.listen(notes.add);
    session.handleNativeCall('onReaderNote', 'Radio at 25.9 dBm');
    await Future<void>.delayed(Duration.zero);
    expect(notes, ['Radio at 25.9 dBm']);
    await sub.cancel();
  });
}
