import 'package:flutter/services.dart';

import 'channels.dart';

/// A crash the native side recorded on the way down, read back on the next
/// launch.
class NativeCrash {
  const NativeCrash({
    required this.fatal,
    required this.trace,
    this.thread,
  });

  /// False when the throw came from a Zebra SDK thread and the crash guard let
  /// that thread die without taking the app down.
  final bool fatal;

  /// Name of the thread that threw.
  final String? thread;

  /// The full stack trace.
  final String trace;

  /// Parses the `fatal: …` / `thread: …` / trace file the native side writes.
  factory NativeCrash.parse(String raw) {
    var fatal = true;
    String? thread;
    final lines = raw.split('\n');
    var start = 0;
    for (var i = 0; i < lines.length && i < 2; i++) {
      final line = lines[i];
      if (line.startsWith('fatal: ')) {
        fatal = line.substring(7).trim() != 'false';
        start = i + 1;
      } else if (line.startsWith('thread: ')) {
        thread = line.substring(8).trim();
        start = i + 1;
      }
    }
    return NativeCrash(
      fatal: fatal,
      thread: thread,
      trace: lines.skip(start).join('\n').trim(),
    );
  }

  @override
  String toString() =>
      'NativeCrash(fatal: $fatal, thread: $thread)\n$trace';
}

/// Native crash reporting for a device you can't attach a cable to.
///
/// A crash inside the Zebra SDK's own threads kills the process before Dart can
/// report anything. [installCrashGuard] writes the trace to disk on the way
/// down; [takeLastCrash] hands it to you on the next launch, so you can log or
/// forward it.
class RfidDiagnostics {
  RfidDiagnostics._();

  static const _channel = MethodChannel(RfidKitChannels.diagnosticsMethods);

  /// Chain a crash recorder in front of Android's default handler. Call it
  /// once, early (before connecting the reader).
  ///
  /// It also changes one thing on purpose: an exception thrown on a **Zebra
  /// SDK thread** (`com.zebra.rfid` / `com.zebra.sdk` in the trace — the
  /// serial reader's "Already running", for instance) lets that thread die
  /// without killing the app, and is recorded with `fatal: false`. Anything
  /// from your own code still crashes the app as normal.
  static Future<void> installCrashGuard() async {
    try {
      await _channel.invokeMethod('installCrashGuard');
    } on MissingPluginException {
      // No native side (web, tests).
    }
  }

  /// The crash recorded on a previous run, once — reading it clears it.
  static Future<NativeCrash?> takeLastCrash() async {
    try {
      final raw = await _channel.invokeMethod<String>('lastCrash');
      if (raw == null || raw.trim().isEmpty) return null;
      return NativeCrash.parse(raw);
    } on MissingPluginException {
      return null;
    }
  }
}
