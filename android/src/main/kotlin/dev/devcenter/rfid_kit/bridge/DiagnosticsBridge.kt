package dev.devcenter.rfid_kit.bridge

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Hands native-side diagnostics to Dart, which decides where to report them.
 *
 * Today that is the crash guard ([CrashLog]) and the previous run's crash
 * trace; the channel exists so anything else the native side learns about the
 * device can follow the same route instead of growing a second reporting path.
 */
class DiagnosticsBridge(
    private val context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {

    private val methods = MethodChannel(messenger, CH_METHODS)

    init {
        methods.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "installCrashGuard" -> {
                CrashLog.install(context)
                result.success(null)
            }
            "lastCrash" -> result.success(CrashLog.consume(context))
            else -> result.notImplemented()
        }
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
    }

    companion object {
        private const val CH_METHODS = "rfid_kit/diagnostics"
    }
}
