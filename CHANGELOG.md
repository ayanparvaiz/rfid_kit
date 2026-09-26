## 0.1.1

* `DeviceMode.auto` now runs the simulator on Android devices without Zebra
  RFID hardware (ordinary phones, emulators). Before, it picked the Zebra
  reader on every Android device, which could only fail to connect. The native
  probe counts a Zebra handheld, an installed Zebra RFID service or a paired
  RFD sled as hardware; without Bluetooth permission it can't rule out a sled
  and still tries the reader. `DeviceMode.real` is unchanged.

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
