package dev.devcenter.rfid_kit.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Zebra DataWedge hardware-scanner bridge: makes the device's **physical scan
 * trigger** (laser / imager button on a TC2x / TC5x / RFD40) feed barcodes
 * straight into the app. On the first listener it creates a DataWedge profile
 * bound to this app with Intent output, listens for the scan broadcast, and
 * streams each barcode to Flutter over an EventChannel.
 *
 * On a non-Zebra device (no DataWedge) the API intents are simply ignored and
 * the receiver never fires — the stream stays quiet, with no crash.
 */
class ScannerBridge(
    private val context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {

    companion object {
        private const val EVENTS = "rfid_kit/scanner/scans"
        private const val METHODS = "rfid_kit/scanner"
        private const val DW_API = "com.symbol.datawedge.api.ACTION"
        private const val DW_PACKAGE = "com.symbol.datawedge"
        private const val DATA_STRING = "com.symbol.datawedge.data_string"
    }

    /** Scoped to this app so two apps using the plugin never hear each other. */
    private val scanAction = "${context.packageName}.RFID_KIT_SCAN"

    private val events = EventChannel(messenger, EVENTS)
    private val methods = MethodChannel(messenger, METHODS)

    private var sink: EventChannel.EventSink? = null
    private var listeners = 0
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == scanAction) {
                val data = intent.getStringExtra(DATA_STRING)
                if (!data.isNullOrEmpty()) sink?.success(data)
            }
        }
    }

    init {
        methods.setMethodCallHandler(this)
        events.setStreamHandler(
            object : EventChannel.StreamHandler {
                // Counted, like RfidBridge's handlers: two Dart listeners can
                // overlap, and the older one leaving must not silence the newer.
                override fun onListen(args: Any?, events: EventChannel.EventSink?) {
                    listeners++
                    sink = events
                    if (!registered) {
                        val profile = (args as? Map<*, *>)?.get("profile") as? String
                        start(profile?.takeIf { it.isNotBlank() } ?: context.packageName)
                    }
                }

                override fun onCancel(args: Any?) {
                    listeners--
                    if (listeners <= 0) {
                        listeners = 0
                        sink = null
                        stop()
                    }
                }
            },
        )
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "setEnabled" -> {
                setScannerInputEnabled(call.argument<Boolean>("enabled") ?: true)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun start(profile: String) {
        val filter = IntentFilter().apply {
            addAction(scanAction)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        // The broadcast comes from another app (DataWedge) → must be exported on 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
        createProfile(profile)
    }

    private fun stop() {
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    /** Create/enable a DataWedge profile bound to this app with Intent output. */
    private fun createProfile(profile: String) {
        send(Bundle().apply {
            putString("com.symbol.datawedge.api.CREATE_PROFILE", profile)
        })

        val appConfig = Bundle().apply {
            putString("PACKAGE_NAME", context.packageName)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }

        val barcode = Bundle().apply {
            putString("PLUGIN_NAME", "BARCODE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("scanner_selection", "auto")
                putString("scanner_input_enabled", "true")
            })
        }

        val intentOut = Bundle().apply {
            putString("PLUGIN_NAME", "INTENT")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("intent_output_enabled", "true")
                putString("intent_action", scanAction)
                putString("intent_delivery", "2") // 2 = broadcast
            })
        }

        // Keystroke output OFF: with it on, every scan is also typed into
        // whatever field has focus, so a barcode lands twice.
        val keystroke = Bundle().apply {
            putString("PLUGIN_NAME", "KEYSTROKE")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("keystroke_output_enabled", "false")
            })
        }

        val config = Bundle().apply {
            putString("PROFILE_NAME", profile)
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "UPDATE")
            putParcelableArray("APP_LIST", arrayOf<Parcelable>(appConfig))
            putParcelableArray(
                "PLUGIN_CONFIG",
                arrayOf<Parcelable>(barcode, intentOut, keystroke),
            )
        }
        send(Bundle().apply {
            putBundle("com.symbol.datawedge.api.SET_CONFIG", config)
        })
    }

    /**
     * Hand the hardware trigger over to RFID, or take it back.
     *
     * On some all-in-one handhelds the grip trigger is wired to DataWedge's
     * barcode scanner, so a press fires the laser and the RFID SDK never hears
     * it. Switching the barcode plugin off for the length of an RFID scan gives
     * the trigger to the reader; switch it back on when the scan ends.
     */
    private fun setScannerInputEnabled(enabled: Boolean) {
        send(Bundle().apply {
            putString(
                "com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN",
                if (enabled) "ENABLE_PLUGIN" else "DISABLE_PLUGIN",
            )
        })
    }

    private fun send(extras: Bundle) {
        val intent = Intent().apply {
            action = DW_API
            putExtras(extras)
            setPackage(DW_PACKAGE)
        }
        try {
            context.sendBroadcast(intent)
        } catch (_: Exception) {
            // DataWedge not installed (non-Zebra device) — ignore.
        }
    }

    fun dispose() {
        stop()
        sink = null
        events.setStreamHandler(null)
        methods.setMethodCallHandler(null)
    }
}
