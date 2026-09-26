package dev.devcenter.rfid_kit.bridge

import android.app.Activity
import android.view.WindowManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Keeps the display awake while the operator is scanning.
 *
 * Android turns the screen off on its own timer, and it has no idea the
 * operator is in the middle of hunting a tag: locating is minutes of walking
 * with the meter in view and a finger on the grip trigger, not on the glass, so
 * the display times out exactly when it is needed most — and waking it again
 * costs a PIN and the scan.
 *
 * `FLAG_KEEP_SCREEN_ON` is the right tool rather than a wake lock: it needs no
 * permission, it is scoped to this window, and Android drops it for us if the
 * app is backgrounded or killed — so a scan that ends badly can never leave the
 * screen pinned on and flatten the battery.
 */
class ScreenBridge(messenger: BinaryMessenger) : MethodChannel.MethodCallHandler {

    private val methods = MethodChannel(messenger, CH_METHODS)

    /** Set while the plugin is attached to an activity; window flags need one. */
    var activity: Activity? = null

    init {
        methods.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "keepAwake" -> {
                val on = call.argument<Boolean>("on") ?: false
                val host = activity
                if (host == null) {
                    // No window to flag (e.g. a background engine) — nothing to do.
                    result.success(null)
                    return
                }
                // Window flags are main-thread only.
                host.runOnUiThread {
                    if (on) {
                        host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
        // Never leave the flag set behind us.
        try {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {
            // window already gone — nothing to clear
        }
        activity = null
    }

    companion object {
        private const val CH_METHODS = "rfid_kit/screen"
    }
}
