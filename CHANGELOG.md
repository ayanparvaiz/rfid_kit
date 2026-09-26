## 0.1.0

First release, extracted from a warehouse RFID app running on Zebra handhelds.

* `RfidReader`: connect, inventory, and a Geiger-counter `locate()` over several
  EPCs at once, with a close-range "confirm" mode (`setLocateRange`).
* `ReaderSession`: one shared reader for the whole app, a shared in-flight open,
  a 30 s cooldown after a failed open, trigger state and reader notes.
* `LabelPrinter`: Zebra Link-OS printers over TCP, Bluetooth Classic and BLE,
  with automatic pairing, RFID encode + print, raw ZPL and RFID calibration.
* `HardwareScanner`: barcodes from the DataWedge scan trigger.
* `ScreenWake` and `RfidDiagnostics` (native crash guard for Zebra SDK threads).
* Mock reader and printer for tests, web and development machines.
