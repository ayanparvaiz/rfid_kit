import 'package:flutter/services.dart';

import 'channels.dart';

/// Keeps the display awake for as long as a scan is running.
///
/// Locating a tag is minutes of walking with the meter in view and a finger on
/// the grip trigger — never on the glass — so Android's own display timeout
/// fires exactly when the operator needs to see the signal, and waking it
/// again costs them the unlock screen and the scan.
///
/// Every call is best-effort: on web, in tests, or without the native side the
/// channel simply is not there and nothing happens.
class ScreenWake {
  ScreenWake._();

  static const _channel = MethodChannel(RfidKitChannels.screenMethods);

  /// Turn the display timeout off ([on] true) or hand it back to Android.
  ///
  /// Pair every `true` with a `false` — including on the error path and on the
  /// way out of the screen — or the display stays lit for the rest of the
  /// session and drains the battery.
  static Future<void> keepAwake(bool on) async {
    try {
      await _channel.invokeMethod('keepAwake', {'on': on});
    } catch (_) {
      // No native side here — the screen keeps its normal timeout, which is
      // the safe way to be wrong.
    }
  }
}
