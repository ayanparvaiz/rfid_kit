package dev.devcenter.rfid_kit

import dev.devcenter.rfid_kit.bridge.DiagnosticsBridge
import dev.devcenter.rfid_kit.bridge.PrinterBridge
import dev.devcenter.rfid_kit.bridge.RfidBridge
import dev.devcenter.rfid_kit.bridge.ScannerBridge
import dev.devcenter.rfid_kit.bridge.ScreenBridge
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

/**
 * Registers the vendor-neutral device bridges. The Dart side only ever talks to
 * these channels; which vendor SDK sits behind them is decided in the bridges.
 */
class RfidKitPlugin : FlutterPlugin, ActivityAware {

    private var rfid: RfidBridge? = null
    private var printer: PrinterBridge? = null
    private var scanner: ScannerBridge? = null
    private var diagnostics: DiagnosticsBridge? = null
    private var screen: ScreenBridge? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val context = binding.applicationContext
        val messenger = binding.binaryMessenger
        rfid = RfidBridge(context, messenger)
        printer = PrinterBridge(context, messenger)
        scanner = ScannerBridge(context, messenger)
        diagnostics = DiagnosticsBridge(context, messenger)
        screen = ScreenBridge(messenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        rfid?.dispose()
        printer?.dispose()
        scanner?.dispose()
        diagnostics?.dispose()
        screen?.dispose()
        rfid = null
        printer = null
        scanner = null
        diagnostics = null
        screen = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        screen?.activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        screen?.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        screen?.activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        screen?.activity = null
    }
}
