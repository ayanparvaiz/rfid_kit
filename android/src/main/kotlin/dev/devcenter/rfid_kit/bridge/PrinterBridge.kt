package dev.devcenter.rfid_kit.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.devcenter.rfid_kit.zebra.ZebraPrinterBackend
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * Bridges the generic `rfid_kit/printer` channel to a [PrinterBackend].
 *
 * Every printer op (connect/disconnect/print) runs on a worker thread, NOT the
 * platform (UI) thread. A TCP/BT printer connect can block for up to ~15s (e.g.
 * a wrong IP or a printer on another subnet); doing that on the UI thread freezes
 * the app and Android raises an ANR. Results are posted back on the main thread,
 * where MethodChannel requires them.
 */
class PrinterBridge(
    private val context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {

    private val methods = MethodChannel(messenger, CH_METHODS)

    /** Built on first use — see [RfidBridge] for why. */
    private var backend: PrinterBackend? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    init {
        methods.setMethodCallHandler(this)
    }

    @Synchronized
    private fun backend(): PrinterBackend {
        backend?.let { return it }
        val created: PrinterBackend = try {
            ZebraPrinterBackend(context)
        } catch (e: LinkageError) {
            throw IllegalStateException(
                "Zebra Link-OS SDK not found. Copy ZSDK_ANDROID_API.jar and its " +
                    "dependency jars into your app's android/app/libs — see the " +
                    "rfid_kit README.",
                e,
            )
        }
        backend = created
        return created
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "connect" -> {
                val transport = call.argument<String>("transport") ?: "tcp"
                val host = call.argument<String>("host")
                val port = call.argument<Int>("port") ?: 9100
                val mac = call.argument<String>("mac")
                runAsync(result) { backend().connect(transport, host, port, mac) }
            }
            "disconnect" -> runAsync(result) { backend?.disconnect() }
            "printRfidLabel" -> {
                val barcode = call.argument<String>("barcode")
                val epc = call.argument<String>("epc")
                if (barcode == null || epc == null) {
                    result.error("PRINTER_ERROR", "barcode and epc are required", null)
                    return
                }
                val quantity = call.argument<Int>("quantity") ?: 1
                val lines = call.argument<List<String>>("lines") ?: emptyList()
                runAsync(result) { backend().printRfidLabel(barcode, epc, quantity, lines) }
            }
            "sendRaw" -> {
                val commands = call.argument<String>("commands")
                if (commands == null) {
                    result.error("PRINTER_ERROR", "commands are required", null)
                    return
                }
                runAsync(result) { backend().sendRaw(commands) }
            }
            "calibrateRfid" -> runAsync(result) { backend().calibrateRfid() }
            else -> result.notImplemented()
        }
    }

    /** Run [block] on the worker thread, then reply on the main thread. */
    private fun runAsync(result: MethodChannel.Result, block: () -> Unit) {
        io.execute {
            try {
                block()
                mainHandler.post { result.success(null) }
            } catch (e: Exception) {
                // Never reply with a null message: several SDK exceptions carry
                // none, which reaches the app as a bare "connect failed".
                val message = e.message?.trim()?.takeIf(String::isNotEmpty)
                    ?: e.javaClass.simpleName
                val details = causeChain(e)
                mainHandler.post { result.error("PRINTER_ERROR", message, details) }
            }
        }
    }

    /**
     * `ConnectionException <- IOException: read failed…` — the trail that the
     * top-level message alone throws away. Null when there is nothing to add.
     */
    private fun causeChain(e: Throwable): String? {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        if (chain.size < 2) return null
        return chain.joinToString(" <- ") { t ->
            val m = t.message?.trim()?.takeIf(String::isNotEmpty)
            if (m != null) "${t.javaClass.simpleName}: $m" else t.javaClass.simpleName
        }
    }

    fun dispose() {
        try {
            backend?.disconnect()
        } catch (_: Exception) {
            // going away anyway
        }
        io.shutdown()
        methods.setMethodCallHandler(null)
    }

    companion object {
        private const val CH_METHODS = "rfid_kit/printer"
    }
}
