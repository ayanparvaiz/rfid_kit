package dev.devcenter.rfid_kit.bridge

/**
 * Native-side contract for a label printer. Backed today by the Zebra Link-OS
 * SDK, but [PrinterBridge] only knows this interface, so any other printer
 * implementation drops straight in.
 */
interface PrinterBackend {
    /**
     * Open a connection over the chosen [transport]:
     *  - "tcp"          -> [host]:[port]   (WiFi/Ethernet, Zebra default port 9100)
     *  - "bluetooth"    -> [mac]           (Bluetooth Classic)
     *  - "bluetoothLe"  -> [mac]           (Bluetooth LE)
     */
    fun connect(transport: String, host: String?, port: Int, mac: String?)

    fun disconnect()

    /**
     * Encode [epc] into the label's RFID inlay and print [quantity] copies
     * carrying [barcode], with [lines] of free text underneath.
     */
    fun printRfidLabel(
        barcode: String,
        epc: String,
        quantity: Int,
        lines: List<String>,
    )

    /**
     * Send [commands] (ZPL, CPCL, SGD…) to the printer exactly as given — for
     * label layouts the built-in one does not cover.
     */
    fun sendRaw(commands: String)

    /**
     * Run the printer's RFID Tag Calibration for the loaded label/inlay stock
     * (finds the optimal program position + power). Must be done once per media
     * type or encodes will void and the tag keeps its factory EPC.
     */
    fun calibrateRfid()
}
