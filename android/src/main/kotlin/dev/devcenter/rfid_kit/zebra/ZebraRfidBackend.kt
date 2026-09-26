package dev.devcenter.rfid_kit.zebra

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.devcenter.rfid_kit.bridge.RfidBackend
import com.zebra.rfid.api3.DYNAMIC_POWER_OPTIMIZATION
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.FILTER_ACTION
import com.zebra.rfid.api3.MEMORY_BANK
import com.zebra.rfid.api3.PreFilters
import com.zebra.rfid.api3.STATE_AWARE_ACTION
import com.zebra.rfid.api3.TARGET
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.INVENTORY_STATE
import com.zebra.rfid.api3.OperationFailureException
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.RFIDResults
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.SESSION
import com.zebra.rfid.api3.SL_FLAG
import com.zebra.rfid.api3.START_TRIGGER_TYPE
import com.zebra.rfid.api3.STATUS_EVENT_TYPE
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE
import com.zebra.rfid.api3.TagData
import com.zebra.rfid.api3.TriggerInfo
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zebra handheld RFID backend (RFD8500 / RFD40 / RFD90 / MC33xx) on the Zebra
 * RFID API3 SDK (`rfidapi3lib`). Implemented against the SDK's
 * `HHSampleApp/RFIDHandler`.
 *
 * Reading: `Inventory.perform()` streams tags via [EventHandler.eventReadNotify];
 * each tag's EPC + peak RSSI is forwarded to Flutter.
 * Locating: an inventory filtered (in the reader) to the target EPC, whose peak
 * RSSI becomes the 0–100 meter.
 *
 * `TagLocationing.Perform()` is the SDK's purpose-built call for this and would
 * be better, because the reader works the distance out itself instead of us
 * inferring it from signal strength. It is NOT used, and the reason written here
 * before — "it does not function on this handheld" — was wrong and is worth
 * recording so it is not repeated:
 *
 *  * The one experiment behind that claim never called `Perform()`. It called
 *    `PerformMultiLocate(...)`, a different command whose bytecode never sets
 *    the EPC on the wire at all — so the radio was asked to locate nothing.
 *  * And until now [EventHandler.onRead] read each tag's id before anything
 *    else and skipped the tag when it was null. A proximity report carries a
 *    percentage and NO id, so every one would have been dropped one line before
 *    it could be used. The channel could not have carried anything whatever the
 *    reader did.
 *
 * The second of those is fixed, and [locateReport] now counts how many
 * proximity reports arrive, so the question can be settled with a number from
 * the field rather than another assertion. Re-enabling `Perform()` also costs
 * the RSSI stream entirely (locationing sends no tag reads), so it is a
 * deliberate swap, not an addition.
 *
 * RFD8500 specifics: it is a *Bluetooth* reader that must already be paired to
 * the host (e.g. TC57X) in Android Bluetooth settings, and the app must hold
 * BLUETOOTH_CONNECT/SCAN permission before [connect] — otherwise
 * `GetAvailableRFIDReaderList()` returns empty.
 *
 * Threading: [RfidBridge] calls these methods on a worker thread (BT connect is
 * slow), so the synchronous SDK calls here do not block the UI thread.
 */
class ZebraRfidBackend(private val context: Context) : RfidBackend {

    private val main = Handler(Looper.getMainLooper())
    private var listener: RfidBackend.Listener? = null

    /**
     * Which instance of this class we are, counting across the whole process.
     *
     * There should only ever be one. A second means the Activity was rebuilt
     * while the process lived on — Android does that under memory pressure —
     * and that matters here because every piece of Zebra SDK state we depend on
     * (the serial manager's run flag, the transport, the Readers registry) is
     * static, i.e. shared, while everything we use to keep order is per-object.
     * Two backends therefore cannot lock against each other and can drive the
     * same radio at the same time.
     *
     * Reported rather than assumed: if the field log never shows a second one,
     * the theory is dead and we stop suspecting it.
     */
    private val instanceNo = liveBackends.incrementAndGet()

    /** Report the duplicate once, and only once we have someone to report to. */
    private var warnedAboutDuplicate = false

    private var readers: Readers? = null
    private var reader: RFIDReader? = null
    private var eventHandler: EventHandler? = null

    /** While locating, the tags we will accept — empty means "not locating".
     *
     *  A set, not one string, because one item can carry several labels (one
     *  per box on a pallet, say). In a rack they shadow each other, so whether
     *  the "primary" one is the one the radio can hear is luck. Any of them
     *  answering means the item has been found. */
    @Volatile
    private var locateEpcs: Set<String> = emptySet()

    /** Everything a locate run is worth knowing about, gathered while it runs
     *  and reported when it ends — see [locateReport]. Written from the SDK's
     *  event thread and read from the bridge's worker, so it has its own lock. */
    private val statsLock = Any()
    private var locateStartMs = 0L
    private var targetReads = 0
    private var otherReads = 0
    private val otherEpcs = LinkedHashSet<String>()
    /** Count of target reads per whole dBm, indexed by -rssi. Cheaper than
     *  keeping every reading, and still gives an exact median. */
    private val rssiHist = IntArray(151)
    private var secondStartMs = 0L
    private val secondRssi = ArrayList<Int>()
    /** Median RSSI for each elapsed second — the walk itself, in one line. */
    private val rssiPerSecond = ArrayList<Int>()
    /** dB below full power this run was made at, for the report. */
    private var locateBackoffDb = 0

    /** Whether this run used the short-reach confirm stage at any point. */
    private var usedConfirmRange = false

    /** How many messages carried the reader's OWN proximity figure rather than
     *  a tag read. Counted because "the reader does not do locationing on this
     *  handheld" has been asserted once already on no evidence, and the only
     *  way to settle it is a number from the field. */
    @Volatile
    private var locationReports = 0

    /** Strongest RSSI seen in each elapsed second. The per-second MEDIAN alone
     *  cannot tell a tag that is genuinely far from one that is close but
     *  answering through a bad angle half the time — the peak can. */
    private val rssiPerSecondMax = ArrayList<Int>()

    /** The transmit-power ladder the reader advertises, cached at connect so
     *  locate and inventory can move along it without re-querying. */
    private var powerLevels: IntArray? = null

    /** True while a single-tag filter is loaded in the reader — see
     *  [applyLocateFilter]. Tracked so clearing it is free on the common path:
     *  every clear is a blocking SDK command, and a bulk scan that never set a
     *  filter should not pay for one before it can start reading. */
    @Volatile
    private var locateFilterApplied = false

    /** True between start/stopInventory. While armed, the physical handheld
     *  TRIGGER drives the actual reads (pull to read, release to stop) — the app's
     *  "Start scan" only ARMS; nothing is read until the operator holds the yellow
     *  trigger. This matches warehouse use (phone in one hand, trigger in the
     *  other) and is the same pattern as Zebra's HHSampleApp. */
    @Volatile
    private var triggerArmed = false

    /** Trigger perform()/stop() run here — a single worker thread so rapid
     *  press/release calls are serialized (never interleave) and stay off the
     *  SDK's event-callback thread. */
    private val triggerExec = Executors.newSingleThreadExecutor()

    /** True while the operator physically holds the trigger. Then THEY own
     *  start/stop, and a stop must not be undone by the keep-alive below. */
    @Volatile
    private var triggerHeld = false

    /**
     * True when the reading currently under way was started by a trigger PRESS
     * rather than by the screen's Start button.
     *
     * This is what lets a release stop only what a press started. A
     * screen-started scan is meant to run until the screen stops it, and it was
     * being killed instead by any release that happened to arrive — including
     * the ones this handheld emits with no press before them.
     */
    @Volatile
    private var readingFromTrigger = false

    /** Set when the operator released the trigger: the radio is meant to be off
     *  and must STAY off until they press again (or restart the scan).
     *
     *  This was a time window before, and the window kept expiring before the
     *  reader got round to announcing its stop — so the keep-alive below saw an
     *  "unexpected" stop and started reading again. From the operator's side the
     *  meter came back to life on its own, seconds after they let go. */
    @Volatile
    private var stoppedByTrigger = false

    /** When the keep-alive last restarted the radio, to rate-limit it. */
    @Volatile
    private var lastRestartMs = 0L

    /**
     * How many start/stop operations we currently have in flight.
     *
     * Every stop WE issue makes the reader announce INVENTORY_STOP_EVENT, and
     * the keep-alive reads that as "the radio died, bring it back" — so a retry
     * that stops-then-starts sets off a start/stop ping-pong that ends with the
     * radio off and the meter dead. While this is above zero the stop is ours
     * and the keep-alive stays out of it.
     */
    private val radioOps = AtomicInteger(0)


    override fun setListener(listener: RfidBackend.Listener?) {
        this.listener = listener
    }

    /** Per-transport reader counts from the last [findReader] scan, e.g.
     *  "USB=0 SERIAL=0 BT=0" — surfaced in the error so we can see what the SDK
     *  actually enumerated on the device. */
    private var lastScanReport: String = ""

    override fun connect(id: String?) = synchronized(SDK_LOCK) {
        // Say it once, here, where a listener is guaranteed to exist — the
        // constructor runs before RfidBridge has attached one, so a warning
        // raised there would go nowhere.
        if (instanceNo > 1 && !warnedAboutDuplicate) {
            warnedAboutDuplicate = true
            main.post {
                listener?.onReaderNote(
                    "Second RFID backend in this process (#$instanceNo) — the " +
                        "activity was rebuilt while the app kept running",
                )
            }
        }
        connectLocked()
    }

    private fun connectLocked() {
        if (reader?.isConnected == true) return

        // ONE attempt. No ladder, no re-enumerate-and-try-again.
        //
        // There used to be up to three connect() calls in here per press, and
        // the field logs are unambiguous that they did nothing but harm:
        //
        //  * Not one success in any log ever came from a retry. Every reader
        //    that opened, opened on the FIRST attempt, in a few seconds. Every
        //    ladder that ran to the end failed at the end.
        //  * Every attempt costs a serial thread. connect() -> initservicesport()
        //    builds a SerialInputOutputManager and starts a thread for it, and
        //    the flag that thread checks ("am I already running?") is static, so
        //    it is shared with every thread the previous attempts left dying.
        //    Start one inside that window and it throws "Already running",
        //    dies before its receive loop begins, and from then on the reader
        //    accepts commands and answers none of them.
        //
        // So the retries were manufacturing the very timeout they were meant to
        // recover from — five presses at three attempts each is fifteen thread
        // starts, and a field log that reached Thread-74 in one session before
        // "Already running" finally landed.
        //
        // One attempt per press: the operator can press again, and each press is
        // a clean single try instead of one that leaves the radio worse than it
        // found it. It also fails in about fifteen seconds instead of twenty-five.
        val known = reader
        val r: RFIDReader
        if (known != null) {
            r = known
        } else {
            r = findReader() ?: throw IllegalStateException(
                "No RFID reader found [$lastScanReport]. On an all-in-one handheld " +
                    "(TC22R) the reader is built in — check it is enabled and that " +
                    "Zebra's 123RFID app can connect to it. With a separate reader " +
                    "(RFD40/RFD8500), snap it on / power it up, pair it in Android " +
                    "Bluetooth settings and grant the app Nearby-devices permission.",
            )
            // Let the vendor RFID service finish binding before we ask it for
            // anything.
            //
            // Enumerating is what starts that bind, and the bind is
            // asynchronous. The SDK does wait for it — but only for three
            // seconds, on a queue poll inside its own Connect() — and when the
            // bind has not landed by then it carries on anyway, with no usable
            // port. Every command after that goes unanswered, which surfaces to
            // us as "RFID_API_COMMAND_TIMEOUT · Response timeout" and reads
            // exactly like dead hardware.
            //
            // The field log is what makes this concrete: the reader was found
            // at 13:04:02 and the connect that followed it in the same second
            // failed at 13:04:09. Enumeration and connect were back to back, so
            // the SDK's three seconds were all the bind ever got. This pause
            // is only paid on a fresh enumeration — reconnecting on a handle we
            // already hold skips it, because that bind happened long ago.
            settleForServiceBind()
        }
        reader = r
        try {
            connectReader(r)
        } catch (first: Exception) {
            // The one retry still worth making, because it is not a retry of the
            // same thing: a reader with no regulatory region fails EVERY command
            // until it has one, and reports that only as a result code. Set the
            // region and the next connect is a genuinely different attempt. This
            // is conditional on that exact cause, so a plain timeout never
            // reaches it.
            if (isRegionNotConfigured(first)) {
                val note = try {
                    " " + applyRegion(r)
                } catch (regionError: Exception) {
                    " The region could not be set automatically " +
                        "(${describe(regionError)})."
                }
                try {
                    reconnect(r)
                    return
                } catch (afterRegion: Exception) {
                    reader = null
                    throw IllegalStateException(
                        "Reader found but connect failed " +
                            "(${describe(afterRegion)}).$note",
                    )
                }
            }
            // Hand the radio back before giving up.
            //
            // The vendor service registers a CLIENT for each connect — its own
            // log line is "Attempting to connect, Client Name: …, Client PID: …".
            // Walk away from a failed connect without releasing and it can be
            // left holding a registration for a client that no longer exists.
            // Our process dying does not tell it either: the service runs
            // outside our process, which is exactly why force-stopping the app
            // never helps and only a reboot does. The next attempt then finds
            // the radio already spoken for and gets no answer at all.
            //
            // This is the one place the teardown belongs. Nothing reconnects
            // after it — the throw ends the attempt — so unlike every earlier
            // version of this code it cannot land in front of a connect and
            // restart the serial thread twice. The operator pressing again is
            // the spacing.
            try { r.Dispose() } catch (_: Exception) {}
            try { readers?.Dispose() } catch (_: Exception) {}
            readers = null
            reader = null
            // Tell the operator to WAIT, not to press again.
            //
            // This message used to say "press again — each press is a single
            // clean attempt", and that was advice that kept the reader broken:
            // every recovery in the field logs came from leaving the radio
            // alone for a couple of minutes, and every attempt made without
            // that gap failed. The app was inviting the one behaviour that
            // guarantees the next attempt fails too.
            throw IllegalStateException(
                "Reader found but did not answer [$lastScanReport] " +
                    "(${describe(first)}). Leave it alone for about a minute, " +
                    "then try ONCE. Trying again straight away is what stops it " +
                    "recovering. If it still will not open, reboot the handheld: " +
                    "the radio belongs to Zebra's own RFID service, which runs " +
                    "outside this app and survives force-stopping it.",
            )
        }
    }

    /**
     * Everything the SDK actually knows about a failure.
     * [OperationFailureException.getMessage] is typically just the class name —
     * the usable detail (result code, status, vendor text) hides behind getters,
     * which is why a failed connect used to read as the useless
     * "OperationFailureException".
     */
    private fun describe(e: Throwable): String {
        if (e is OperationFailureException) {
            val parts = listOfNotNull(
                e.results?.toString(),
                e.statusDescription?.takeIf { it.isNotBlank() },
                e.vendorMessage?.takeIf { it.isNotBlank() },
            )
            return if (parts.isEmpty()) "OperationFailureException" else parts.joinToString(" · ")
        }
        return e.message?.trim()?.takeIf { it.isNotEmpty() } ?: e.javaClass.simpleName
    }

    private fun isRegionNotConfigured(e: Throwable): Boolean =
        e is OperationFailureException &&
            e.results == RFIDResults.RFID_READER_REGION_NOT_CONFIGURED

    /**
     * Give the reader its regulatory region. Out of the box it has none, and
     * until one is set every operation — including connect — fails.
     *
     * The region is a legal matter (it decides which frequencies the radio may
     * use), so nothing is guessed: use the only supported region when there is
     * exactly one, otherwise the handheld's own country, and if neither
     * resolves, report the available codes so a human chooses in 123RFID.
     */
    private fun applyRegion(r: RFIDReader): String {
        val supported = r.ReaderCapabilities.SupportedRegions
        val regions = (0 until supported.length()).mapNotNull { supported.getRegionInfo(it) }
        if (regions.isEmpty()) return "The reader reports no supported regions."
        val country = Locale.getDefault().country.uppercase(Locale.US)
        val chosen = regions.singleOrNull()
            ?: regions.firstOrNull { it.regionCode.uppercase(Locale.US) == country }
            ?: return "The reader has no region set — choose one in Zebra's 123RFID " +
                "app (supported: ${regions.joinToString { it.regionCode }})."
        val config = r.Config.regulatoryConfig
        config.region = chosen.regionCode
        if (chosen.isChannelSelectable) config.setEnabledChannels(chosen.supportedChannels)
        r.Config.regulatoryConfig = config
        return "The reader had no region set — applied ${chosen.regionCode} " +
            "(${chosen.name})."
    }

    /**
     * Tear the link down before opening it again. A half-open reader still has
     * its serial reader thread alive, and calling connect() on top of that is
     * exactly the "Already running" crash — so a retry is never a bare retry.
     */
    private fun reconnect(r: RFIDReader) {
        try { r.disconnect() } catch (_: Exception) {}
        settleAfterTeardown()
        connectReader(r)
    }

    /**
     * Give the vendor RFID service time to bind after an enumeration, before
     * anything tries to talk to it. See the call site for why three seconds of
     * the SDK's own waiting is not enough.
     */
    private fun settleForServiceBind() {
        try {
            Thread.sleep(SERVICE_BIND_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Wait out a teardown before touching the reader again.
     *
     * Call this after EVERY disconnect()/Dispose() that is followed by another
     * connect. The SDK's stop is a request, not an event: it flips a
     * process-wide flag to "stopping" and interrupts the serial thread, but the
     * flag only reaches "stopped" when that thread itself returns — and it may
     * be parked in a read on the vendor port when we ask. Start the next one
     * inside that gap and it throws "Already running" on a thread we cannot
     * catch, leaving a reader that answers nothing until the app is restarted.
     *
     * There is no way to ask the SDK whether it has finished, so waiting is the
     * only lever. Only ever on the failure path — a normal connect never pays it.
     */
    private fun settleAfterTeardown() {
        try {
            Thread.sleep(RECONNECT_PAUSE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Open the physical connection and apply our trigger/event config. */
    private fun connectReader(r: RFIDReader) {
        // Give the reader time to answer. Opening this handheld's built-in
        // reader alone takes about eight seconds, and the SDK's stock response
        // timeout is far shorter — every slow-but-healthy reply came back as
        // "RFID_API_COMMAND_TIMEOUT · Response timeout" and looked like dead
        // hardware.
        try {
            if (r.timeout < COMMAND_TIMEOUT_MS) r.timeout = COMMAND_TIMEOUT_MS
        } catch (_: Exception) {
            // not settable on this reader — carry on with its default
        }
        // Already up (e.g. the link survived a region fix) — don't re-open it.
        if (!r.isConnected) {
            // Connect straight away. There used to be a "clear a half-open link
            // first" disconnect() on this line, written to PREVENT the
            // "Already running" crash; disassembling the SDK showed it was the
            // direct cause of it. The chain, all inside the vendored aar:
            //
            //   disconnect() -> RfidUsbMgr.UsbSerialDisconnect() calls
            //     SerialInputOutputManager.stop(), which only sets the state to
            //     STOPPING and interrupts the thread — it never joins it, and
            //     nothing but the dying thread itself ever writes STOPPED — then
            //     immediately nulls usbIoManager.
            //   connect()    -> RfidUsbMgr.initservicesport() sees that null,
            //     builds a SECOND manager and starts its thread, whose run()
            //     reads the state, finds STOPPING, and throws "Already running".
            //
            // The state is a process-wide STATIC field, so the two managers get
            // no mutual exclusion from each other and no lock of ours can help.
            // The new thread dies before its receive loop starts, which is why
            // the reader then accepts every command and answers none of them
            // ("RFID_API_COMMAND_TIMEOUT · Response timeout") until the process
            // is restarted.
            //
            // A genuinely half-open link is still recovered — by connectLocked's
            // ladder below, which routes a failed connect through reconnect()
            // and so pauses properly between the disconnect and the retry. The
            // one thing that must never happen again is a disconnect() sitting
            // directly in front of a connect().
            r.connect()
        }
        // A reader left mid-inventory by 123RFID or a previous session swallows
        // our first reads until it's physically power-cycled. Do the equivalent
        // in software: stop any running inventory so this connection starts from
        // a clean radio state (removes the "turn the reader off then on" step).
        try { r.Actions.Inventory.stop() } catch (_: Exception) {}
        configure(r)
    }

    /**
     * Find an attached/paired reader — permission-free transports first.
     *
     * Order matters, and it is about permissions rather than speed. The SDK's
     * enumeration is six internal loaders (bluetooth, serial, NGE, USB, QC,
     * RE-USB); each one runs only when the transport it was given is its own
     * *or* ALL. The bluetooth loader calls `getBondedDevices()`, which THROWS
     * without BLUETOOTH_CONNECT — and it runs FIRST inside the SDK. So asking
     * for ALL on a device that has not granted bluetooth yet loses the whole
     * enumeration, the built-in serial reader included, to a permission the
     * built-in reader never needed:
     *
     *   "No RFID reader found [scan failed: Need android.permission.
     *    BLUETOOTH_CONNECT permission ... AdapterService getBondedDevices]"
     *
     * Serial and USB need no runtime permission, and on an all-in-one handheld
     * the reader is on serial — so those are asked first and answer before
     * bluetooth is ever touched. Bluetooth is tried last, for a separate sled
     * (RFD8500), and its failure can no longer take the others down with it.
     *
     * One [Readers] instance for all of it: the transport is a static field the
     * SDK reads at scan time, so [Readers.setTransport] re-points it without
     * the `Dispose()` this loop used to do between passes — and that Dispose
     * was a process-wide transport teardown, which is the same serial-thread
     * stop a disconnect is, landing immediately before a connect.
     */
    private fun findReader(): RFIDReader? {
        val transports = listOf(
            // USB first, because that is where the reader on this handheld
            // actually is. The field log settled it: "SERIAL=0 USB=1 [TC22R …
            // via=SERVICE_USB addr=USB_PORT]". Every comment in this file used
            // to claim an all-in-one handheld keeps its reader on the serial
            // transport; that was wrong, and it cost us a lot of time.
            //
            // Order is not just about speed. The serial pass is not a passive
            // look — inside the SDK it binds the vendor's RFIDService — so on
            // this device it was starting a service we then never used, right
            // before the USB path went to bind the one it needs. Asking the
            // transport that answers first means one bind instead of two.
            "USB" to ENUM_TRANSPORT.SERVICE_USB,
            // Kept as a fallback: a different handheld, or a snapped-on sled,
            // may well be here. Costs nothing when USB has already answered.
            "SERIAL" to ENUM_TRANSPORT.SERVICE_SERIAL,
            // Needs BLUETOOTH_CONNECT — last, and isolated, so a missing
            // permission costs us the sled scan and nothing else.
            "BT" to ENUM_TRANSPORT.BLUETOOTH,
        )
        val rs = try {
            readers ?: Readers(context, ENUM_TRANSPORT.SERVICE_SERIAL).also { readers = it }
        } catch (e: Exception) {
            lastScanReport = "enumerate failed: ${describe(e)}"
            return null
        }
        val report = StringBuilder()
        for ((label, transport) in transports) {
            try {
                rs.setTransport(transport)
                val list = rs.GetAvailableRFIDReaderList()
                report.append("$label=${list?.size ?: 0} ")
                if (list != null && list.isNotEmpty()) {
                    val device = list[0]
                    // Say WHAT was found, not just that something was. "Reader
                    // found but connect failed" is the message we have been
                    // staring at for days without ever knowing whether the thing
                    // found is a real radio or just an entry the vendor service
                    // is willing to name. The device carries all of this and we
                    // were throwing it away.
                    report.append(
                        "[${runCatching { device.name }.getOrNull()}" +
                            " model=${runCatching { device.modelNumber }.getOrNull()}" +
                            " sn=${runCatching { device.serialNumber }.getOrNull()}" +
                            " via=${runCatching { device.transport }.getOrNull()}" +
                            " addr=${runCatching { device.address }.getOrNull()}] ",
                    )
                    lastScanReport = report.toString().trim()
                    main.post { listener?.onReaderNote("Reader found: $lastScanReport") }
                    return device.getRFIDReader()
                }
            } catch (e: Exception) {
                // This transport is unavailable on this device or not permitted
                // yet. Say which, and carry on to the next one.
                report.append("$label=${describe(e).take(60)} ")
            }
        }
        lastScanReport = report.toString().trim()
        return null
    }

    private fun configure(r: RFIDReader) {
        val trigger = TriggerInfo()
        trigger.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE)
        trigger.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE)
        val handler = eventHandler ?: EventHandler().also { eventHandler = it }
        r.Events.addEventsListener(handler)
        r.Events.setHandheldEvent(true)
        r.Events.setTagReadEvent(true)
        r.Events.setAttachTagDataWithReadEvent(false)
        // Tell us when the radio stops on its own. Without this the reader could
        // quietly end its session and nothing ever restarted it — the locate
        // meter fell to 0 and stayed there until the screen was left.
        r.Events.setInventoryStopEvent(true)
        r.Config.setStartTrigger(trigger.StartTrigger)
        r.Config.setStopTrigger(trigger.StopTrigger)

        // Transmit power. A reader that has just been given its region comes
        // back on that region's DEFAULT power, which on this hardware can be the
        // lowest step: the radio is on, the session runs, and not one tag is
        // read — indistinguishable from a broken scan. Ask for the strongest
        // level the reader advertises.
        //
        // Full power is right for COUNTING (reach every tag on the pallet) and
        // wrong for HUNTING one — see [setTxBackoff]. Locate lowers it and
        // inventory puts it back.
        // Dynamic Power Optimization OFF, BEFORE the power is set.
        //
        // With it on the firmware quietly steps the transmit power down while a
        // tag is reading well and back up when reads falter. That is good for
        // battery and ruinous for us: every number the app derives from dBm
        // assumes the power is the one we asked for, so the same tag at the same
        // distance reports a different strength depending on how well it has
        // recently been read — the meter sags while the operator stands still
        // and there is nothing in our own code to blame it on.
        //
        // The read-back is reported rather than the request, because "we asked"
        // and "it took" are not the same thing and this is exactly the kind of
        // setting that fails silently.
        try {
            r.Config.setDPOState(DYNAMIC_POWER_OPTIMIZATION.DISABLE)
            val state = r.Config.getDPOState()
            main.post { listener?.onReaderNote("Dynamic power optimisation: $state") }
        } catch (e: Exception) {
            main.post { listener?.onReaderNote("DPO not settable (${describe(e)})") }
        }

        try {
            powerLevels = r.ReaderCapabilities.transmitPowerLevelValues
            applyPowerIndex(r, strongestPowerIndex())
        } catch (_: Exception) {
            // not configurable on this reader — keep whatever it came with
        }

        // Gen2 SESSION S0 — critical for LOCATE. In S1/S2 a tag replies once then
        // its inventoried flag persists (seconds in S1, indefinitely-while-powered
        // in S2), so a stationary tag is read ONCE and then goes silent — the meter
        // shows a value for a moment then dies. S0's flag resets almost immediately,
        // so the same tag is re-read continuously while the trigger is held, keeping
        // the signal alive. (Same as HHSampleApp's singulation setup.)
        try {
            val sc = r.Config.Antennas.getSingulationControl(1)
            sc.setSession(SESSION.SESSION_S0)
            sc.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A)
            sc.Action.setSLFlag(SL_FLAG.SL_ALL)
            r.Config.Antennas.setSingulationControl(1, sc)
        } catch (_: Exception) {
            // non-fatal: reader keeps its default session if this isn't supported
        }
    }

    override fun disconnect() = synchronized(SDK_LOCK) {
        // Under the SAME lock as connect(). Both reach the same static SDK
        // state, and this one is the path an Activity teardown takes — on the
        // main thread, outside the bridge's worker — so without the lock a
        // destroy can tear the transport down underneath a connect that is
        // still running, which is precisely how the serial thread ends up
        // started twice.
        triggerArmed = false
        clearLocateFilter()
        try {
            reader?.let { r ->
                eventHandler?.let { r.Events.removeEventsListener(it) }
                r.disconnect()
            }
            readers?.Dispose()
        } catch (_: Exception) {
            // best-effort cleanup
        }
        reader = null
        readers = null
        eventHandler = null
        locateEpcs = emptySet()
    }

    override fun startInventory(power: Double?) {
        locateEpcs = emptySet()
        // A bulk read must see EVERYTHING — every tag, at full reach. If a
        // locate ran before this it left the radio narrowed to one tag and
        // turned down; either one alone would make the count look like broken
        // hardware rather than leftover settings.
        clearLocateFilter()
        setTxBackoff(0)
        // Stop any prior/stuck inventory first so the next read session starts
        // clean (a stale session reads nothing until the reader is power-cycled),
        // then ARM the trigger.
        try { reader?.Actions?.Inventory?.stop() } catch (_: Exception) {}
        triggerArmed = true
        // A fresh scan clears any "the operator let go" state from the last one.
        stoppedByTrigger = false
        readingFromTrigger = false
        // ARM ONLY — the radio stays off until the operator pulls the trigger.
        //
        // This has been on and off twice, so here is what is actually known.
        //
        // A performNow() here reads the moment Start is pressed, and that is
        // PROVEN to work: a field scan at 17:14:34 logged 21 tags with not one
        // HANDHELD_TRIGGER line in it. What is NOT known is whether the trigger
        // alone would have done the same — with the radio already running, a
        // press starts nothing, so no log can separate the two.
        //
        // Arm-only is back deliberately, to answer exactly that. If the next
        // field run counts tags, the trigger drives this screen and the
        // operator keeps control of the radio; if it counts nothing, the
        // immediate start goes back in permanently and this comment with it.
        // "RFID scan stopped" reports New-this-scan, so the answer is one line
        // in the log rather than another round of asking what they saw.
        //
        // The stakes either way: reading continuously at 30 dBm also counts the
        // next aisle as the operator walks past it, which on a stock-take is a
        // wrong number rather than a slow one.
    }

    /**
     * Start reading straight away, without waiting for a trigger pull.
     *
     * Arming alone was enough for a snapped-on sled, which delivers
     * HANDHELD_TRIGGER_EVENT through the RFID SDK. On an all-in-one handheld
     * (TC22R and friends) the grip trigger usually belongs to DataWedge, so that
     * event never arrives, `perform()` was never called, and "Start scan" looked
     * completely dead — no tags, no error, nothing. Reading now makes the button
     * do something on every device; a real trigger press/release still drives
     * perform()/stop() through [EventHandler.eventStatusNotify].
     */
    private fun performNow(propagate: Boolean = false, attempts: Int = PERFORM_ATTEMPTS) {
        radioOps.incrementAndGet()
        val task = triggerExec.submit(
            Runnable {
                try {
                    performLoop(attempts)
                } finally {
                    radioOps.decrementAndGet()
                }
            },
        )
        // The app's own Start button waits for the answer, so a real failure
        // reaches the screen instead of looking like a dead button.
        if (propagate) {
            try {
                task.get()
            } catch (e: ExecutionException) {
                throw (e.cause as? Exception ?: e)
            }
        }
    }

    /**
     * Start the radio for whatever the operator is doing right now.
     *
     * Locating a specific tag is NOT the same job as reading everything in
     * range, and the SDK has a separate call for it. `TagLocationing.Perform`
     * hands the EPC to the reader and the reader answers with a relative
     * distance it works out itself — from its own transmit power, the antenna,
     * and many reads — instead of the single peak RSSI a plain inventory gives
     * us. That is the difference the operator sees: peak RSSI is the STRONGEST
     * read, so one lucky reflection off a wall keeps the meter high while they
     * walk away, whereas the reader's own figure falls as they go. It is also
     * what Zebra's own 123RFID app shows, which is the bar we are being held to.
     *
     * The plain inventory path stays as the fallback: if locationing is not
     * available on a device, [onRead] still derives a signal from RSSI, so the
     * meter keeps working rather than going dead.
     */
    private fun startRadio() {
        // Plain inventory, for locating as well as reading.
        //
        // `TagLocationing.Perform()` was tried here and it is NOT usable on this
        // handheld. The call is accepted — it throws nothing, and the retry loop
        // below never fired — but not a single tag read arrives afterwards: a
        // whole locate session logged "0% · no read" while the operator squeezed
        // the trigger a dozen times. Inventory, by contrast, has been delivering
        // reads all along. Whatever the SDK exposes, the TC22R's firmware does
        // not answer locationing, and silence is the worst possible failure for
        // the one screen whose job is to say "warmer or colder".
        //
        // So the meter is driven by inventory reads, and [onRead] still uses the
        // reader's own LocationInfo if a device ever does populate it.
        reader?.Actions?.Inventory?.perform()
    }

    /** Stop what [startRadio] started. */
    private fun stopRadio() {
        try { reader?.Actions?.Inventory?.stop() } catch (_: Exception) {}
    }

    /**
     * Turn the radio down while hunting a single tag, and put it back after.
     *
     * The meter was reading near the top of its scale from metres away — the
     * operator got 100% on the wrong bin and 80% on the right one. That is not a
     * tuning error in the meter, it is the transmit power: we ask for the
     * strongest level the reader has, which is correct for counting a pallet and
     * wrong for hunting one item.
     *
     * At full power on this handheld the target answers at −35 to −46 dBm across
     * a whole aisle. Two things go wrong at once, and both are fixed by turning
     * the power down rather than by moving the meter's window:
     *
     *  * **No gradient left.** Every reading lands in the top fifth of the
     *    −85..−35 dBm scale, so walking two metres barely moves the number. Drop
     *    the power by [LOCATE_BACKOFF_DB] and the same readings land in the
     *    middle of the scale, where distance actually shows.
     *  * **Reflections answer too.** A signal that reaches the tag by bouncing
     *    off racking and concrete has travelled much further than the straight
     *    line, so it is the first to fall below what the tag needs to wake up at
     *    all. Cutting the power silences those bounces while the direct path
     *    still reads — which is exactly the false 100% the operator was chasing.
     *
     * Range is the price: the tag has to be roughly half as far away before it
     * answers. That is the trade a Geiger counter is supposed to make, and the
     * one number to turn if the field says otherwise is [LOCATE_BACKOFF_DB].
     *
     * The power the reader actually settled on is announced, because "asked for
     * −8 dB" and "got −8 dB" are not the same thing — the ladder is coarse and
     * this must never be guessed at from a distance again.
     */
    private fun setTxBackoff(db: Int) {
        val r = reader ?: return
        val levels = powerLevels ?: return
        if (levels.isEmpty()) return
        val maxIdx = strongestPowerIndex()
        if (maxIdx < 0) return
        val strongest = levels[maxIdx]
        // The SDK reports the ladder in its own units — tenths of a dBm on this
        // hardware, whole dBm on others. Read it off the values themselves
        // instead of assuming: nobody is going to notice a tenfold power error
        // in a log line, they will just report that locate stopped working.
        val perDb = if (strongest > 100) 10 else 1
        val wanted = strongest - db * perDb
        // Nearest rung at or above what we asked for, so a coarse ladder errs
        // toward reading the tag rather than toward silence.
        var idx = maxIdx
        for (i in levels.indices) {
            if (levels[i] >= wanted && levels[i] < levels[idx]) idx = i
        }
        try {
            applyPowerIndex(r, idx)
            val dbm = levels[idx].toDouble() / perDb
            main.post {
                listener?.onReaderNote(
                    "Radio at $dbm dBm (${(strongest - levels[idx]) / perDb} dB " +
                        "below max) for " +
                        when (db) {
                            0 -> "counting"
                            LOCATE_CONFIRM_BACKOFF_DB -> "CONFIRM (short range)"
                            else -> "hunting"
                        },
                )
            }
        } catch (e: Exception) {
            // Keep whatever power it already had — that is today's behaviour.
            main.post {
                listener?.onReaderNote("Radio power not adjustable (${describe(e)})")
            }
        }
    }

    /**
     * Tell the reader whether to honour a state-aware pre-filter.
     *
     * Adding a filter is not enough on its own — [PreFilters.add] does not touch
     * singulation, so without this the reader stores the filter and inventories
     * everything anyway. Read-modify-write, so the session and inventory state
     * set up at connect survive.
     */
    private fun setStateAwareSingulation(on: Boolean) {
        val r = reader ?: return
        try {
            val sc = r.Config.Antennas.getSingulationControl(1)
            sc.Action.setPerformStateAwareSingulationAction(on)
            r.Config.Antennas.setSingulationControl(1, sc)
        } catch (_: Exception) {
            // Not supported here: the software EPC match in onRead still filters,
            // exactly as it did before any of this existed.
        }
    }

    /**
     * Start a fresh set of locate measurements.
     *
     * The meter reads high on the wrong pallet and lower on the right one, and
     * from an office there is no way to tell which of the possible causes it is:
     * a filter the reader accepted but ignores, a tag being read twice a second
     * instead of ten times, a radio saturating so every distance looks the same,
     * or simply an operator standing somewhere other than where they think. Each
     * one has a different fix and the wrong guess costs another day.
     *
     * So the run measures itself and says what happened, once, at the end.
     */
    private fun resetLocateStats(backoffDb: Int) = synchronized(statsLock) {
        locateStartMs = System.currentTimeMillis()
        secondStartMs = locateStartMs
        targetReads = 0
        otherReads = 0
        otherEpcs.clear()
        rssiHist.fill(0)
        secondRssi.clear()
        rssiPerSecond.clear()
        rssiPerSecondMax.clear()
        locationReports = 0
        locateBackoffDb = backoffDb
        usedConfirmRange = false
    }

    private fun recordTargetRead(rssi: Int) = synchronized(statsLock) {
        if (locateStartMs == 0L) return
        targetReads++
        val bin = (-rssi).coerceIn(0, rssiHist.size - 1)
        rssiHist[bin]++
        secondRssi.add(rssi)
        val now = System.currentTimeMillis()
        if (now - secondStartMs >= 1000L) {
            if (rssiPerSecond.size < MAX_LOCATE_SECONDS) {
                rssiPerSecond.add(medianOf(secondRssi))
                rssiPerSecondMax.add(secondRssi.max())
            }
            secondRssi.clear()
            secondStartMs = now
        }
    }

    /**
     * A tag that is not the one being hunted, heard anyway.
     *
     * This is the whole test of whether the pre-filter is real. If the reader is
     * honouring it, nothing ever reaches here; if this count is not zero, the
     * filter was accepted and then ignored — which looks identical from the
     * outside and is the reason it is counted rather than assumed.
     */
    private fun recordOtherRead(epc: String) = synchronized(statsLock) {
        if (locateStartMs == 0L) return
        otherReads++
        if (otherEpcs.size < MAX_OTHER_EPCS) otherEpcs.add(epc)
    }

    private fun medianOf(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        return values.sorted()[values.size / 2]
    }

    /** Build the end-of-run report, and clear the run. */
    private fun locateReport(epc: String?): String? = synchronized(statsLock) {
        if (locateStartMs == 0L) return null
        val ms = System.currentTimeMillis() - locateStartMs
        locateStartMs = 0L
        if (secondRssi.isNotEmpty() && rssiPerSecond.size < MAX_LOCATE_SECONDS) {
            rssiPerSecond.add(medianOf(secondRssi))
            rssiPerSecondMax.add(secondRssi.max())
        }
        val secs = ms / 1000.0
        val sb = StringBuilder("Locate ended · ${epc ?: "?"}\n")
        sb.append(String.format(Locale.US, "%.0fs · target %d reads", secs, targetReads))
        if (secs > 0) sb.append(String.format(Locale.US, " (%.1f/s)", targetReads / secs))
        sb.append("\n")
        if (targetReads > 0) {
            // Walking the histogram from index 0 upwards is walking from the
            // strongest reading to the weakest, since it is indexed by -dBm.
            var min = 0
            var max = 0
            var median = 0
            var seen = 0
            var first = true
            for (i in rssiHist.indices) {
                if (rssiHist[i] == 0) continue
                if (first) {
                    max = -i
                    first = false
                }
                min = -i
                seen += rssiHist[i]
                if (median == 0 && seen >= targetReads / 2) median = -i
            }
            sb.append("RSSI min $min · median $median · max $max dBm\n")
            // A run whose readings barely moved carries no distance in it at
            // all, however good the meter is. Judged on the SPREAD rather than
            // on absolute dBm, so this stays true whatever power the radio is
            // set to and whatever window the meter happens to use.
            if (max - min < MIN_USEFUL_SPREAD_DB) {
                sb.append(
                    "WARNING readings only spanned ${max - min} dB: too flat to " +
                        "judge distance\n",
                )
            }
        } else {
            sb.append("NO READS of the target at all\n")
        }
        // Judged as a SHARE, not a count. A run that heard two stray tags in
        // four thousand reads is a filter doing its job, and calling that a
        // failure sends us hunting a bug that isn't there — which is exactly
        // what the first field report did.
        val allReads = targetReads + otherReads
        val strayPct = if (allReads == 0) 0 else otherReads * 100 / allReads
        sb.append(
            when {
                otherReads == 0 -> "Other tags heard: none — filter working\n"
                strayPct < STRAY_TAG_PCT ->
                    "Other tags heard: $otherReads reads ($strayPct%) — filter working\n"
                else -> "Other tags heard: $otherReads reads ($strayPct%) from " +
                    "${otherEpcs.size}+ tags — FILTER NOT HONOURED\n"
            },
        )
        sb.append(
            "Radio $locateBackoffDb dB below max" +
                (if (usedConfirmRange) " (confirm stage was used)" else "") + "\n",
        )
        sb.append(
            if (locationReports > 0) "Reader gave its OWN distance $locationReports times\n"
            else "Reader never offered a distance of its own\n",
        )
        // Truncation has to announce itself. The trace was silently cut at this
        // length before, which reads exactly like an operator who stopped early.
        if (rssiPerSecond.size >= MAX_LOCATE_SECONDS) {
            sb.append("(trace below covers the first $MAX_LOCATE_SECONDS s only)\n")
        }
        if (rssiPerSecond.isNotEmpty()) {
            sb.append("Per-second RSSI: ").append(rssiPerSecond.joinToString(" "))
        }
        if (rssiPerSecondMax.isNotEmpty()) {
            sb.append("\nPer-second BEST: ").append(rssiPerSecondMax.joinToString(" "))
        }
        return sb.toString()
    }

    /** Index of the strongest rung on the ladder. Found rather than assumed to
     *  be last: a reader that reports its levels the other way round would
     *  otherwise be run at its WEAKEST setting and read nothing at all. */
    private fun strongestPowerIndex(): Int {
        val levels = powerLevels ?: return -1
        if (levels.isEmpty()) return -1
        var best = 0
        for (i in levels.indices) if (levels[i] > levels[best]) best = i
        return best
    }

    private fun applyPowerIndex(r: RFIDReader, idx: Int) {
        val levels = powerLevels ?: return
        if (idx < 0 || idx >= levels.size) return
        val antenna = r.Config.Antennas.getAntennaRfConfig(1)
        antenna.transmitPowerIndex = idx
        r.Config.Antennas.setAntennaRfConfig(1, antenna)
    }

    /**
     * Ask the reader to answer only for the hunted labels, while locating.
     *
     * Without this the radio interrogates every tag in range and we throw all
     * but one away in software. On a desk with a single tag that is invisible;
     * in an aisle with fifty pallets it is the whole problem — each round has to
     * work through all of them, so the tag being hunted is heard from once in a
     * while instead of many times a second, and the meter starves. It flickers
     * and sinks while the operator is standing right next to what they want.
     *
     * A pre-filter is applied in the reader, before any of that: tags that do
     * not match are never singulated, so the round is spent entirely on the
     * tags that matter and the meter gets a dense, steady stream.
     *
     * One filter per EPC. An item can carry several labels and in a rack they
     * shadow one another, so which of them the radio can hear is luck.
     * Filtering to only the "primary" one throws that luck away; filtering to
     * the whole set keeps every chance of a reply.
     *
     * Best-effort by design. Filtering is firmware territory and this handheld
     * has accepted such a call before and then done nothing, so a failure here
     * changes nothing: the reader reports everything and the EPC match in
     * [onRead] carries on filtering in software. The outcome is reported either
     * way, because a filter silently not applying is exactly the kind of thing
     * that would otherwise be mistaken for bad tuning.
     */
    private fun applyLocateFilter(epcs: Set<String>) {
        val filters = reader?.Actions?.PreFilters ?: return
        if (epcs.isEmpty()) return
        try {
            filters.deleteAll()
            for (epc in epcs) {
                // Inner class: the SDK builds a filter against a specific filter
                // list, so it is constructed on that list, not standalone.
                val f = filters.PreFilter()
                f.memoryBank = MEMORY_BANK.MEMORY_BANK_EPC
                f.setTagPattern(epc)
                // The EPC bank starts with a 16-bit CRC and a 16-bit PC word, so
                // the EPC itself begins 32 bits in. Match its full length: each
                // hex character is 4 bits.
                f.bitOffset = 32
                f.tagPatternBitCount = epc.length * 4
                // STATE-AWARE, not the simpler "select" action, because of how
                // the reader is already set up. A select filter only raises the
                // tag's SL flag, and our singulation asks for SL_ALL — "read
                // every tag, SL or not". The reader would accept such a filter,
                // report success, and then ignore it: a filter that is on paper
                // only, which is worse than none because it looks like it works.
                //
                // State-aware moves matching tags to inventoried state A and
                // everything else to B, and the inventory already asks for A. It
                // also self-heals: session S0's flags fall back the moment a tag
                // loses power, so a filter left behind cannot silence a later
                // scan.
                f.filterAction = FILTER_ACTION.FILTER_ACTION_STATE_AWARE
                f.StateAwareAction.target = TARGET.TARGET_INVENTORIED_STATE_S0
                f.StateAwareAction.stateAwareAction =
                    STATE_AWARE_ACTION.STATE_AWARE_ACTION_INV_A_NOT_INV_B
                f.antennaID = 1
                filters.add(f)
            }
            // The switch that makes the reader act on the above at all.
            setStateAwareSingulation(true)
            locateFilterApplied = true
            main.post {
                listener?.onReaderNote(
                    "Locate filter applied for ${epcs.size} label(s): " +
                        epcs.joinToString(", "),
                )
            }
        } catch (e: Exception) {
            // Leave nothing half-set behind, then carry on unfiltered.
            try { filters.deleteAll() } catch (_: Exception) {}
            locateFilterApplied = false
            main.post {
                listener?.onReaderNote(
                    "Locate filter not supported (${describe(e)}) — reading all " +
                        "tags and matching in software, which is slower in a busy aisle",
                )
            }
        }
    }

    /**
     * Drop any locate filter.
     *
     * This MUST run when a locate ends. A filter left in place would follow the
     * reader into the next job — and the next job is usually a bulk inventory
     * count, which would then find exactly one tag and look like a broken scan
     * rather than a leftover filter.
     */
    private fun clearLocateFilter() {
        if (!locateFilterApplied) return
        locateFilterApplied = false
        setStateAwareSingulation(false)
        try { reader?.Actions?.PreFilters?.deleteAll() } catch (_: Exception) {}
    }

    private fun performLoop(attempts: Int) {
        var last: Exception? = null
        for (attempt in 1..attempts) {
            try {
                startRadio()
                return
            } catch (e: Exception) {
                        // Nearly always "Operation In Progress": the previous
                        // session's radio hasn't wound down yet. Swallowing this
                        // is what made the first press of "Start scan" do
                        // nothing, so the operator tapped two or three times —
                        // and each tap raced the last. Tell the radio to stop,
                        // then try again.
                last = e
                stopRadio()
                try {
                    Thread.sleep(PERFORM_RETRY_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
        last?.let {
            // Reported, not swallowed: a restart that quietly fails is how the
            // meter ends up dead with nothing on screen and nothing in the log.
            main.post { listener?.onReaderNote("Could not start reading (${describe(it)})") }
            throw IllegalStateException("Could not start reading (${describe(it)})", it)
        }
    }

    override fun stopInventory() {
        triggerArmed = false
        try { reader?.Actions?.Inventory?.stop() } catch (_: Exception) {}
    }

    override fun startLocate(epcs: List<String>) {
        // This IS the locate path now. Dart used to run a plain inventory and
        // work the proximity out itself from peak RSSI; setting the EPC here is
        // what lets [startRadio] hand the job to the reader's own locationing
        // instead, which is the figure Zebra's 123RFID shows and the one that
        // actually falls as the operator walks away.
        locateEpcs = epcs.map { it.uppercase() }.filter { it.isNotEmpty() }.toSet()
        try { reader?.Actions?.Inventory?.stop() } catch (_: Exception) {}
        // Narrow the radio to this one tag, and turn it down so distance shows
        // in the reading. Both while the radio is idle — arm-only means nothing
        // is being read until the operator pulls the trigger.
        applyLocateFilter(locateEpcs)
        // Every hunt starts wide. Confirm is something the operator asks for.
        setTxBackoff(LOCATE_BACKOFF_DB)
        resetLocateStats(LOCATE_BACKOFF_DB)
        triggerArmed = true
        // A fresh scan clears any "the operator let go" state from the last one.
        stoppedByTrigger = false
        readingFromTrigger = false
        // ARM ONLY, same as startInventory — the trigger starts the reading.
    }

    /**
     * Trade reach for certainty.
     *
     * Signal STRENGTH is a poor way to tell the right bin from the wrong one
     * here: measured in the aisle, the difference between standing at the target
     * and standing at a bin several metres away is about 8 dB, while the same
     * tag's strength swings roughly 14 dB on its own while the operator holds
     * still. The number can never be trusted to that resolution.
     *
     * Whether the tag ANSWERS AT ALL is a far better question, because it is not
     * a measurement — it is a threshold, and the drop-off is steep. Turning the
     * radio down to [LOCATE_CONFIRM_BACKOFF_DB] pulls the reach in to roughly a
     * metre and a half (measured: at that power the operator reported nothing
     * readable beyond it, which was a complaint then and is the whole point
     * now). At that setting the wrong bin does not read 65% — it goes silent.
     *
     * So: hunt at [LOCATE_BACKOFF_DB] to find the aisle and the shelf, then come
     * in close and ask this. Silence is the answer.
     *
     * Applied with the radio stopped, and only restarted if it was running.
     * Changing the antenna's power underneath a live inventory is exactly the
     * kind of thing this reader answers with a command timeout, and it runs on
     * [triggerExec] so it can never interleave with a trigger press or release.
     */
    override fun setLocateRange(near: Boolean) {
        val wanted = if (near) LOCATE_CONFIRM_BACKOFF_DB else LOCATE_BACKOFF_DB
        if (near) usedConfirmRange = true
        triggerExec.execute {
            val wasReading = triggerHeld && !stoppedByTrigger
            radioOps.incrementAndGet()
            try {
                if (wasReading) stopRadio()
                setTxBackoff(wanted)
                synchronized(statsLock) { locateBackoffDb = wanted }
                if (wasReading) performLoop(PERFORM_ATTEMPTS)
            } catch (e: Exception) {
                main.post {
                    listener?.onReaderNote("Range change failed (${describe(e)})")
                }
            } finally {
                radioOps.decrementAndGet()
            }
        }
    }

    override fun stopLocate() {
        triggerArmed = false
        // Stop BEFORE forgetting the EPC: stopRadio() reads locateEpc to decide
        // whether it is locationing or a plain inventory it has to end, so
        // clearing it first would leave the reader's locationing running.
        stopRadio()
        clearLocateFilter()
        locateReport(locateEpcs.joinToString(", "))?.let { report ->
            main.post { listener?.onReaderNote(report) }
        }
        locateEpcs = emptySet()
    }

    // A second RSSI-to-percent mapping used to live here, with a different
    // window (-85..-40) from the one the app actually uses. Nothing called it.
    // It was deleted rather than left as reference: two disagreeing definitions
    // of the same curve in one codebase is how a tuning session ends up chasing
    // the wrong constant. The meter is defined once, in Dart, in
    // ZebraRfidReader._proximityFromRssi.

    // A polling watchdog used to sit here, restarting the radio whenever reads
    // went quiet. It was removed: driving the vendor SDK from a timer, on top of
    // its own callbacks, is what turned an occasional dead meter into an app
    // that kept crashing. The reader's own INVENTORY_STOP_EVENT (handled below)
    // is the safe way to notice a radio that stopped by itself.

    private fun now() = System.currentTimeMillis()

    private companion object {
        /**
         * One lock for the whole process, not one per backend.
         *
         * Everything it guards — the serial manager's run flag, the transport,
         * the Readers registry — is `static` inside the SDK, so a per-object
         * lock guards nothing the moment a second backend exists. That is not
         * hypothetical: the backend is built per Activity, and Android rebuilds
         * the Activity under memory pressure while keeping the process. Two
         * objects, two locks, one radio.
         */
        val SDK_LOCK = Any()

        /** How many backends this process has built. See [instanceNo]. */
        val liveBackends = AtomicInteger(0)

        /**
         * How far below full power the radio runs while hunting one tag — see
         * [setTxBackoff] for why hunting and counting want different power.
         *
         * This started at 8 dB, when the meter was reading 100% beside the wrong
         * pallet and turning the radio down was the only lever we had. It was
         * the wrong lever: a measured run then showed the meter's own window sat
         * about 10 dB too low, which is what made distance disappear. With that
         * corrected, the power cut is no longer carrying the fix — and 8 dB was
         * costing real reach, with the operator reporting nothing readable past
         * a metre and a half.
         *
         * 4 dB keeps the honest part of the trade — the longest, most reflected
         * paths still fall below what a tag needs to answer at all — and gives
         * back most of the range. It remains a straight trade with nothing else
         * attached: raise it if false highs come back, lower it if the tag
         * cannot be found from a sensible distance.
         */
        const val LOCATE_BACKOFF_DB = 4

        /**
         * Power backoff for the confirm stage — see [setLocateRange].
         *
         * Even 8 dB — the setting the field rejected for HUNTING — already left
         * "nothing readable past about a metre and a half". Short reach is
         * precisely what confirming wants, so this goes well past it.
         *
         * Raise it if a neighbouring bin still answers during confirm; lower it
         * if the operator has to touch the pallet before it responds.
         */
        const val LOCATE_CONFIRM_BACKOFF_DB = 20

        /** Caps on the end-of-run report, so a hunt left running for an hour
         *  still produces a message short enough to log or forward. */
        const val MAX_LOCATE_SECONDS = 120
        const val MAX_OTHER_EPCS = 20

        /** Share of reads from other tags above which the pre-filter is not
         *  actually being applied, rather than merely leaking. */
        const val STRAY_TAG_PCT = 5

        /** Below this much difference between the strongest and weakest read,
         *  a run tells us nothing about distance. */
        const val MIN_USEFUL_SPREAD_DB = 15

        /**
         * Head start given to the vendor RFID service's bind after an
         * enumeration, on top of the three seconds the SDK waits internally.
         * Paid once per fresh enumeration, never on a reconnect.
         */
        const val SERVICE_BIND_MS = 3000L

        /** A perform() right after a stop often reports "busy" — retry briefly.
         *  Five tries at 300ms gives the radio ~1.5s to wind down, which is
         *  what the field logs showed it actually needs. */
        const val PERFORM_ATTEMPTS = 5
        const val PERFORM_RETRY_MS = 300L

        /** Let the reader's serial thread finish dying before reconnecting.
         *
         *  This has to cover a *blocked* thread, not a busy one. The SDK's
         *  disconnect only asks the serial thread to stop; the flag that says it
         *  really stopped is written by that thread on its way out, after its
         *  read on the vendor port returns. Reconnect before then and the next
         *  thread throws "Already running" and dies, leaving a reader that
         *  answers nothing until the app is restarted.
         *
         *  There is no API to ask whether it has finished, so the pause is the
         *  only lever we have — 600ms was not enough to survive a blocked read.
         *  If "Already running" ever comes back, raise this first. */
        const val RECONNECT_PAUSE_MS = 3000L

        /** How long the reader may take to answer a command. Generous: this
         *  handheld is simply slow, and a short wait reads as broken hardware. */
        const val COMMAND_TIMEOUT_MS = 15000

        /** Floor between keep-alive restarts, so a reader that refuses to run
         *  can't turn into a hot loop. */
        const val RESTART_COOLDOWN_MS = 500L
    }

    private inner class EventHandler : RfidEventsListener {
        // NOTE: both callbacks run on the SDK's own thread, where an escaping
        // exception takes the whole process down ("<app> keeps stopping").
        // getReadTags() alone throws whenever the radio is stopping underneath
        // us, so nothing in here may be left unguarded.
        override fun eventReadNotify(e: RfidReadEvents?) {
            try {
                onRead()
            } catch (_: Throwable) {
                // a dropped read is nothing; a crashed app is everything
            }
        }

        private fun onRead() {
            val r = reader ?: return
            val tags: Array<TagData> = r.Actions.getReadTags(100) ?: return
            val targets = locateEpcs
            for (tag in tags) {
                // A proximity report FIRST, because it has no EPC to match on.
                //
                // When the reader works the distance out itself, the message it
                // sends back carries a percentage and nothing else — no tag id
                // at all. This loop used to read the id before anything else and
                // skip the tag when it was null, so every one of those reports
                // was thrown away one line before it could be used, and the
                // locate channel could never carry anything however the reader
                // was configured. Silent, and indistinguishable from a radio
                // that simply never answered.
                if (tag.isContainsLocationInfo) {
                    locationReports++
                    val near = tag.LocationInfo.relativeDistance.toInt()
                    main.post { listener?.onLocate(near.coerceIn(0, 100)) }
                    continue
                }
                val epc = tag.getTagID() ?: continue
                if (targets.isNotEmpty()) {
                    // Locate mode: ANY of the hunted labels drives the meter.
                    if (epc.uppercase() in targets) {
                        val rssi = tag.getPeakRSSI().toInt()
                        recordTargetRead(rssi)
                        main.post { listener?.onTag(epc, rssi, 1) }
                    } else {
                        recordOtherRead(epc)
                    }
                } else {
                    main.post { listener?.onTag(epc, tag.getPeakRSSI().toInt(), 1) }
                }
            }
        }

        override fun eventStatusNotify(e: RfidStatusEvents?) {
            try {
                onStatus(e)
            } catch (_: Throwable) {
                // same rule as above — never let the SDK thread die
            }
        }

        private fun onStatus(e: RfidStatusEvents?) {
            val data = e?.StatusEventData ?: return
            // Report the trigger even with no scan running: "does the hardware
            // button reach us at all" is the question we need answered, and it
            // can only be answered by listening unconditionally. The exact event
            // goes up by NAME — this handheld turned out never to send PRESSED,
            // and a boolean hid that completely.
            if (data.statusEventType == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                val fired = data.HandheldTriggerEventData.handheldEvent
                val name = "${fired?.toString() ?: "UNKNOWN"} (${fired?.value ?: -1})"
                main.post { listener?.onTrigger(name) }
            }
            if (!triggerArmed) return
            when (data.statusEventType) {
                // Physical handheld TRIGGER drives the reads while a session is
                // armed. Pull → perform(), release → stop() — same as Zebra's
                // HHSampleApp. Run on the single-thread [triggerExec] so a fast
                // press/release can't interleave, and off the SDK callback thread.
                STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                    // The handheld reports PRESSED(1)/RELEASED(0) properly, so
                    // the operator's trigger drives the radio: squeeze to read,
                    // let go to stop. Only these two events act — an event we
                    // don't recognise is ignored rather than treated as a stop,
                    // which is what used to kill a working scan.
                    when (data.HandheldTriggerEventData.handheldEvent) {
                        HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED -> {
                            triggerHeld = true
                            stoppedByTrigger = false
                            // Remember that THIS reading is the trigger's, so the
                            // release that follows knows it owns it.
                            readingFromTrigger = true
                            performNow()
                        }

                        HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED -> {
                            triggerHeld = false
                            // A release only stops what a press started.
                            //
                            // It used to stop the radio unconditionally, which
                            // meant a scan the operator had started from the
                            // SCREEN died the moment the grip trigger was let go
                            // — including releases this handheld sends with no
                            // press in front of them (the field log has two in a
                            // row at 14:48:02 and 14:48:08). From the operator's
                            // side the reading simply stopped on its own.
                            //
                            // Hold-to-read still behaves exactly as before: a
                            // press sets this, so the release that follows owns
                            // the reading and ends it. What changes is that a
                            // stray release, or one arriving during a
                            // screen-started scan, is now ignored.
                            if (!readingFromTrigger) return
                            readingFromTrigger = false
                            // Deliberate stop: keep the INVENTORY_STOP_EVENT
                            // keep-alive out of it until the next press.
                            stoppedByTrigger = true
                            triggerExec.execute { stopRadio() }
                        }

                        else -> Unit
                    }
                }

                // The radio stopped without us asking: the reader ends a session
                // by itself (and some handhelds emit a stray release). The scan
                // is still armed on screen, so bring it straight back — this is
                // what stopped the locate meter from dying at 0 for good.
                STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT -> {
                    val at = now()
                    if (!triggerHeld &&
                        !stoppedByTrigger &&
                        radioOps.get() == 0 &&
                        at - lastRestartMs > RESTART_COOLDOWN_MS
                    ) {
                        lastRestartMs = at
                        performNow()
                    }
                }

                else -> Unit
            }
        }
    }
}
