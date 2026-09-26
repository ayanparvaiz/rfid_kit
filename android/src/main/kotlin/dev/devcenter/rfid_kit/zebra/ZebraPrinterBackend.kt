package dev.devcenter.rfid_kit.zebra

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dev.devcenter.rfid_kit.bridge.PrinterBackend
import com.zebra.sdk.btleComm.BluetoothLeConnection
import com.zebra.sdk.comm.BluetoothConnection
import com.zebra.sdk.comm.Connection
import com.zebra.sdk.comm.TcpConnection
import com.zebra.sdk.printer.PrinterLanguage
import com.zebra.sdk.printer.ZebraPrinter
import com.zebra.sdk.printer.ZebraPrinterFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A connect failure that carries **why** it failed in its message.
 *
 * The Link-OS SDK reports every possible cause as the same bare
 * `ConnectionException` ("Could not connect to device"), so on the app side a
 * printer that is switched off looked exactly like Bluetooth being disabled, a
 * missing permission, or a mistyped MAC. Everything thrown from
 * [ZebraPrinterBackend.connect] is turned into one of these first.
 */
class PrinterConnectException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Zebra Link-OS printer backend (ZT411 and other Zebra printers) using
 * `ZSDK_ANDROID_API.jar`. Supports TCP/WiFi, Bluetooth Classic and BLE; USB is
 * left as a follow-up (needs the UsbManager permission flow).
 *
 * All connection/IO runs on a worker thread (Link-OS throws on UI-thread
 * networking), and the calling method blocks until it finishes so the Flutter
 * result reflects success/failure.
 */
class ZebraPrinterBackend(private val context: Context) : PrinterBackend {

    private val io = Executors.newSingleThreadExecutor()
    private var connection: Connection? = null

    override fun connect(transport: String, host: String?, port: Int, mac: String?) {
        runIo {
            closeQuietly()
            // Android rejects a lowercase MAC outright ("Invalid Bluetooth
            // address") and operators type these by hand — normalise first.
            val address = mac?.trim()?.uppercase()
            // Answer the questions the SDK never does, before it can throw its
            // one-size-fits-all exception.
            preflight(transport, host, address)?.let { throw PrinterConnectException(it) }
            val conn: Connection = when (transport) {
                "tcp" -> TcpConnection(host!!.trim(), port)
                "bluetooth" -> BluetoothConnection(address!!)
                "bluetoothLe" -> BluetoothLeConnection(address!!, context)
                else -> throw PrinterConnectException(
                    "USB printing is not implemented yet — use WiFi/TCP, Bluetooth or BLE",
                )
            }
            try {
                conn.open()
            } catch (e: Exception) {
                try {
                    conn.close()
                } catch (_: Exception) {
                }
                throw PrinterConnectException(explain(e), e)
            }
            connection = conn
        }
    }

    /**
     * The checks Link-OS doesn't make. Returns the reason the connect cannot
     * work (ready to show the operator), or null to go ahead and try.
     */
    private fun preflight(transport: String, host: String?, mac: String?): String? {
        when (transport) {
            "tcp" -> if (host.isNullOrBlank()) return "No printer IP address is configured"
            "bluetooth", "bluetoothLe" -> {
                if (mac.isNullOrBlank()) return "No printer MAC address is configured"
                if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
                    return "'$mac' is not a valid Bluetooth address (expected AA:BB:CC:DD:EE:FF)"
                }
                if (!hasConnectPermission()) {
                    return "Bluetooth permission is not granted — allow 'Nearby devices' " +
                        "for this app in Android settings"
                }
                val adapter = bluetoothAdapter() ?: return "This handheld has no Bluetooth adapter"
                if (!adapter.isEnabled) return "Bluetooth is turned off on the handheld"
                // Both transports need the bond: the ZT411 demands a passkey on
                // Classic *and* on BLE ("Passkey not entered").
                return ensureBonded(adapter, mac, transport)
            }
        }
        return null
    }

    /**
     * Pair with the printer (if it isn't already) **before** handing it to the
     * SDK.
     *
     * The ZT411 asks for a pairing passkey and shows the code on its own
     * screen, but nothing ever appeared on the handheld to type it into: Link-OS
     * only opens a socket, which gives Android no reason to raise the pairing
     * UI, so the attempt died as a bare `ConnectionException` (Classic) or
     * "Passkey not entered" (BLE). [BluetoothDevice.createBond] is what puts the
     * system passkey prompt on screen; we then wait for the operator to type the
     * code the printer is displaying.
     *
     * Returns null once bonded, otherwise the reason it could not be.
     */
    private fun ensureBonded(
        adapter: BluetoothAdapter,
        mac: String,
        transport: String,
    ): String? {
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            return "'$mac' is not a valid Bluetooth address"
        }
        if (device.bondState == BluetoothDevice.BOND_BONDED) return null

        val state = AtomicInteger(device.bondState)
        // What Android actually asked the operator for. -1 means it never asked
        // at all, which is itself the answer when no prompt shows up.
        val variant = AtomicInteger(NO_PAIRING_REQUEST)
        val settled = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val from = deviceOf(intent)
                if (!mac.equals(from?.address, ignoreCase = true)) return
                when (intent.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val asked = intent.getIntExtra(
                            BluetoothDevice.EXTRA_PAIRING_VARIANT,
                            NO_PAIRING_REQUEST,
                        )
                        variant.set(asked)
                        // A plain yes/no needs no operator at all — answer it so
                        // the pairing doesn't sit waiting behind a notification.
                        if (asked == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ||
                            asked == PAIRING_VARIANT_CONSENT
                        ) {
                            try {
                                from?.setPairingConfirmation(true)
                            } catch (_: Exception) {
                                // Needs a privileged permission on some builds —
                                // fall back to the operator tapping the prompt.
                            }
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val next = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE,
                        )
                        state.set(next)
                        // BOND_BONDING just means the prompt is up — keep waiting.
                        if (next == BluetoothDevice.BOND_BONDED ||
                            next == BluetoothDevice.BOND_NONE
                        ) {
                            settled.countDown()
                        }
                    }
                }
            }
        }
        registerBondReceiver(receiver)
        try {
            val started = try {
                device.createBond()
            } catch (_: SecurityException) {
                false
            }
            if (!started) {
                openBluetoothSettings()
                return "Android refused to start pairing with $mac — Bluetooth settings are now " +
                    "open, pair the printer there using the passkey on its screen"
            }
            if (!settled.await(BOND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                openBluetoothSettings()
                return "Waited ${BOND_TIMEOUT_SECONDS}s for pairing with $mac and it was never " +
                    "completed (${variantLabel(variant.get())}). Bluetooth settings are now " +
                    "open — pair the printer there with the passkey on its screen" +
                    bleAdvice(transport)
            }
            if (state.get() != BluetoothDevice.BOND_BONDED) {
                openBluetoothSettings()
                return "Pairing with $mac was refused or the passkey was wrong " +
                    "(${variantLabel(variant.get())}) — retry from the Bluetooth settings now " +
                    "on screen, using the code the printer shows" + bleAdvice(transport)
            }
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Names what Android asked for, so a failed pairing says whether the
     * operator was shown a passkey field, a yes/no, or nothing at all — the last
     * one means the prompt never reached the screen and only Settings will do.
     */
    private fun variantLabel(variant: Int): String = when (variant) {
        NO_PAIRING_REQUEST -> "Android never raised a pairing prompt"
        BluetoothDevice.PAIRING_VARIANT_PIN -> "Android asked for a PIN"
        PAIRING_VARIANT_PASSKEY -> "Android asked for the passkey shown on the printer"
        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ->
            "Android asked to confirm a passkey"
        PAIRING_VARIANT_CONSENT -> "Android asked for consent"
        PAIRING_VARIANT_DISPLAY_PASSKEY -> "Android displayed a passkey to type on the printer"
        PAIRING_VARIANT_DISPLAY_PIN -> "Android displayed a PIN to type on the printer"
        else -> "Android pairing request type $variant"
    }

    /** BLE bonding on a dual-mode printer is flaky; Classic is the safe road. */
    private fun bleAdvice(transport: String): String =
        if (transport == "bluetoothLe") " — or switch this printer to Bluetooth Classic" else ""

    /** Put the operator where pairing definitely works when our own attempt didn't. */
    private fun openBluetoothSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // No settings activity (unlikely) — the message alone has to do.
        }
    }

    /** Both are protected system broadcasts (never exported). */
    private fun registerBondReceiver(receiver: BroadcastReceiver) {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    /**
     * Flatten the cause chain (Link-OS buries the real `IOException` under its
     * own exception) and add a plain-language hint for the failures we actually
     * see in the warehouse.
     */
    private fun explain(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        val text = chain
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString(" <- ")
        val type = chain.last().javaClass.simpleName
        val reason = if (text.isEmpty()) type else "$text [$type]"
        val hint = hintFor("$text $type".lowercase())
        return if (hint != null) "$reason — $hint" else reason
    }

    private fun hintFor(haystack: String): String? = when {
        "passkey" in haystack || "pairing" in haystack || "bond" in haystack ->
            "the printer wants a pairing passkey — accept the Android pairing request and " +
                "type the code shown on the printer screen"
        "socket might closed" in haystack || "read failed" in haystack ->
            "the printer is off, out of range, or already connected to another phone/PC"
        "busy" in haystack ->
            "the Bluetooth port is busy — another app or device is holding the printer"
        "refused" in haystack ->
            "nothing is listening on that port — check the printer's raw port (usually 9100)"
        "timed out" in haystack || "timeout" in haystack ->
            "no answer — check the IP, and that the printer is on and on the same WiFi"
        "unreachable" in haystack ->
            "the handheld cannot reach that address — it is probably on another WiFi/subnet"
        "discovery" in haystack ->
            "no printing service at that MAC — is it the printer's address and not the reader's?"
        else -> null
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun disconnect() {
        runIo { closeQuietly() }
    }

    override fun printRfidLabel(
        barcode: String,
        epc: String,
        quantity: Int,
        lines: List<String>,
    ) {
        runIo {
            val conn = connection ?: throw IllegalStateException("Printer not connected")
            require(epc.matches(Regex("^[0-9A-Fa-f]{24}$"))) {
                "EPC must be exactly 24 hex chars (96-bit); got '$epc'"
            }
            val printer: ZebraPrinter = ZebraPrinterFactory.getInstance(conn)
            // Only ZPL can encode the inlay. Printing the label anyway on a CPCL
            // printer would hand back a sticker that looks right and carries its
            // factory EPC — a failure nobody notices until the tag is scanned.
            if (printer.printerControlLanguage != PrinterLanguage.ZPL) {
                throw IllegalStateException(
                    "This printer speaks ${printer.printerControlLanguage}; RFID encoding " +
                        "needs a ZPL printer. Use sendRaw() for non-RFID labels.",
                )
            }
            conn.write(buildLabel(barcode, epc, quantity, lines))
        }
    }

    override fun sendRaw(commands: String) {
        runIo {
            val conn = connection ?: throw IllegalStateException("Printer not connected")
            conn.write(commands.toByteArray())
        }
    }

    override fun calibrateRfid() {
        runIo {
            val conn = connection ?: throw IllegalStateException("Printer not connected")
            // ^HR runs the RFID transponder position/power calibration for the
            // currently-loaded label stock and stores the result on the printer.
            conn.write("^XA^HR^XZ".toByteArray())
        }
    }

    /** Build the built-in ZPL encode + print label. */
    private fun buildLabel(
        barcode: String,
        epc: String,
        qty: Int,
        lines: List<String>,
    ): ByteArray {
        // Escape ZPL field delimiters so free text never breaks the format.
        fun z(s: String) = s.replace("^", " ").replace("~", " ")
        val text = buildString {
            lines.filter { it.isNotBlank() }.forEachIndexed { i, line ->
                append("^FO40,${240 + i * 40}^A0N,26,26^FD${z(line)}^FS")
            }
        }
        // ^RS8,,,3 : Gen2 tag, void-and-retry up to 3 labels on encode error
        // — so a bad encode prints VOID + retries instead of silently shipping a
        // label whose tag kept its factory EPC. Requires the printer to have
        // been RFID-calibrated for this label stock (see calibrateRfid).
        return ("^XA" +
            "^RS8,,,3" +
            "^RFW,H^FD$epc^FS" + // write the 96-bit EPC (24 hex) to the inlay
            "^FO40,40^A0N,40,40^FD${z(barcode)}^FS" +
            "^FO40,100^BY2^BCN,120,Y,N,N^FD${z(barcode)}^FS" +
            text +
            "^PQ$qty" +
            "^XZ").toByteArray()
    }

    private fun closeQuietly() {
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
    }

    companion object {
        /** Long enough for an operator to read the printer's screen and type the code. */
        private const val BOND_TIMEOUT_SECONDS = 60L

        /** No ACTION_PAIRING_REQUEST was ever broadcast for this device. */
        private const val NO_PAIRING_REQUEST = -1

        // Pairing variants Android broadcasts but doesn't expose as public
        // constants (values from BluetoothDevice, stable since API 19).
        private const val PAIRING_VARIANT_PASSKEY = 1
        private const val PAIRING_VARIANT_CONSENT = 3
        private const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4
        private const val PAIRING_VARIANT_DISPLAY_PIN = 5
    }

    private fun runIo(block: () -> Unit) {
        try {
            io.submit(Runnable { block() }).get()
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }
}
