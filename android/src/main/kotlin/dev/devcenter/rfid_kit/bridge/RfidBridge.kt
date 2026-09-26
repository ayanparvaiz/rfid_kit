package dev.devcenter.rfid_kit.bridge

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.devcenter.rfid_kit.zebra.ZebraRfidBackend
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * Bridges the generic `rfid_kit/rfid` channels to a selected [RfidBackend].
 * Pure plumbing + device detection — no vendor SDK calls live here, which is
 * what keeps model support pluggable.
 *
 * Reader operations (connect/inventory/locate) run on a single worker thread,
 * NOT the platform (UI) thread: a Bluetooth reader like the RFD8500 can take
 * several seconds to connect, which would ANR the app if done on the main
 * thread. Results are posted back on the main thread, where MethodChannel
 * requires them.
 */
class RfidBridge(
    private val context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler, RfidBackend.Listener {

    private val methods = MethodChannel(messenger, CH_METHODS)
    private val tagEvents = EventChannel(messenger, CH_TAGS)
    private val locateEvents = EventChannel(messenger, CH_LOCATE)

    private var tagSink: EventChannel.EventSink? = null
    private var locateSink: EventChannel.EventSink? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /**
     * Built on first use, not at plugin attach. The vendor SDK is supplied by
     * the host app, so it may be missing — and a missing class must turn into
     * an error the developer can read, not a crash the moment the app starts.
     */
    private var backend: RfidBackend? = null

    init {
        methods.setMethodCallHandler(this)
        tagEvents.setStreamHandler(handler { tagSink = it })
        locateEvents.setStreamHandler(handler { locateSink = it })
    }

    @Synchronized
    private fun backend(): RfidBackend {
        backend?.let { return it }
        val created: RfidBackend = try {
            ZebraRfidBackend(context)
        } catch (e: LinkageError) {
            throw IllegalStateException(
                "Zebra RFID SDK not found. Copy rfidapi3lib-<version>.aar into your " +
                    "app's android/app/libs and add it to the app's dependencies — " +
                    "see the rfid_kit README.",
                e,
            )
        }
        created.setListener(this)
        backend = created
        return created
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            // Pure local read — safe on the main thread.
            "probe" -> result.success(describeDevice())

            // Reader I/O — must run off the UI thread (BT connect is slow).
            "connect" -> {
                val id = call.argument<String>("id")
                runAsync(result) { backend().connect(id) }
            }
            "disconnect" -> runAsync(result) { backend?.disconnect() }
            "startInventory" -> {
                val power = call.argument<Double>("power")
                runAsync(result) { backend().startInventory(power) }
            }
            "stopInventory" -> runAsync(result) { backend?.stopInventory() }
            "startLocate" -> {
                val epcs = call.argument<List<String>>("epcs") ?: emptyList()
                runAsync(result) { backend().startLocate(epcs) }
            }
            "setLocateRange" -> {
                val near = call.argument<Boolean>("near") ?: false
                runAsync(result) { backend?.setLocateRange(near) }
            }
            "stopLocate" -> runAsync(result) { backend?.stopLocate() }
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
                mainHandler.post { result.error("RFID_ERROR", e.message, null) }
            }
        }
    }

    /** Reports the connected hardware + capabilities to Dart's DeviceManager. */
    private fun describeDevice(): Map<String, Any?> {
        val model = Build.MODEL ?: "unknown"
        return mapOf(
            "id" to model,
            "vendor" to (Build.MANUFACTURER ?: "Zebra"),
            "model" to model,
            "transport" to "integrated",
            "capabilities" to mapOf(
                "canReadRfid" to true,
                "canLocateRfid" to true,
                "canEncodeRfid" to true,
                "canScanBarcode" to true,
            ),
        )
    }

    override fun onTag(epc: String, rssi: Int, antenna: Int?) {
        tagSink?.success(mapOf("epc" to epc, "rssi" to rssi, "antenna" to antenna,
            "seenAtMs" to System.currentTimeMillis()))
    }

    override fun onLocate(proximity: Int) {
        locateSink?.success(proximity)
    }

    /** Straight up to Dart, where ReaderSession turns them into streams. */
    override fun onTrigger(event: String) = notifyDart("onTrigger", event)

    override fun onReaderNote(note: String) = notifyDart("onReaderNote", note)

    private fun notifyDart(method: String, argument: String) {
        mainHandler.post {
            try {
                methods.invokeMethod(method, argument)
            } catch (_: Exception) {
                // engine gone / detached — a diagnostic is never worth a crash
            }
        }
    }

    fun dispose() {
        // Hand the radio back when the app goes away. The reader is meant to be
        // held open for the app's whole life, so without this a closed app
        // leaves it claimed — and the next launch finds a reader that answers
        // nothing until the handheld is rebooted.
        try {
            backend?.disconnect()
        } catch (_: Exception) {
            // going away anyway
        }
        backend?.setListener(null)
        io.shutdown()
        methods.setMethodCallHandler(null)
        tagEvents.setStreamHandler(null)
        locateEvents.setStreamHandler(null)
    }

    /**
     * Stream handler that survives overlapping subscriptions.
     *
     * Dart can briefly hold two listeners on the same channel — tap "Start scan"
     * twice, or leave a screen just as the next one subscribes. A handler that
     * clears the sink on ANY cancel lets the *older* stream shutting down kill
     * tag delivery for the live one: reads stop for good and the locate meter
     * sits at 0 until the reader is reconnected. Count the listeners instead and
     * only drop the sink when the last one goes.
     */
    private fun handler(assign: (EventChannel.EventSink?) -> Unit) =
        object : EventChannel.StreamHandler {
            private var listeners = 0

            override fun onListen(args: Any?, sink: EventChannel.EventSink?) {
                listeners++
                assign(sink)
            }

            override fun onCancel(args: Any?) {
                listeners--
                if (listeners <= 0) {
                    listeners = 0
                    assign(null)
                }
            }
        }

    companion object {
        private const val CH_METHODS = "rfid_kit/rfid"
        private const val CH_TAGS = "rfid_kit/rfid/tags"
        private const val CH_LOCATE = "rfid_kit/rfid/locate"
    }
}
