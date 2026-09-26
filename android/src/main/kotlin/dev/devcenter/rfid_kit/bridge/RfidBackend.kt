package dev.devcenter.rfid_kit.bridge

/**
 * Native-side contract for an RFID reader. This is the device-agnostic seam on
 * the Android side: [RfidBridge] speaks the Flutter channel protocol and never
 * touches a vendor SDK directly — it delegates to whichever [RfidBackend] was
 * selected for the detected hardware.
 *
 * Supporting a new Zebra reader (or a different vendor) = one new class that
 * implements this interface. The bridge and the whole Dart layer stay untouched.
 */
interface RfidBackend {

    /** Pushes tag reads / locate proximity back up to Flutter. */
    interface Listener {
        fun onTag(epc: String, rssi: Int, antenna: Int?)
        fun onLocate(proximity: Int)

        /**
         * The device's physical RFID trigger fired, carrying the SDK's own event
         * name (`HANDHELD_TRIGGER_PRESSED`, `..._RELEASED`, `..._LOCK`, …).
         *
         * Reported so the app can say whether the hardware button reaches the
         * RFID SDK at all, and *as what* — handhelds differ, and treating an
         * unrecognised event as "released" is how a running scan got killed.
         */
        fun onTrigger(event: String)

        /**
         * Something the reader did that the operator can't see and no Dart call
         * is waiting on — a restart that failed, the power actually applied, the
         * end-of-locate report. Surfaced so the radio can never go quiet without
         * leaving a trace.
         */
        fun onReaderNote(note: String)
    }

    fun setListener(listener: Listener?)

    fun connect(id: String?)
    fun disconnect()

    fun startInventory(power: Double?)
    fun stopInventory()

    /**
     * Hunt for ANY of [epcs].
     *
     * One item can carry more than one label — one per physical box on a
     * pallet, say — and in a rack they hide behind one another, so the one label
     * the app happens to call "primary" is often the one the radio cannot hear.
     * Hunting all of them keeps every chance of a reply.
     */
    fun startLocate(epcs: List<String>)
    fun stopLocate()

    /**
     * Shrink the radio's reach to arm's length ([near] true) or put it back to
     * hunting reach.
     *
     * A no-op on hardware that cannot change transmit power; the app must keep
     * working without it.
     */
    fun setLocateRange(near: Boolean)
}
