# rfid_kit example

A small app with four tabs: **Inventory** (hold the trigger, see every tag),
**Locate** (tap a tag, then walk with the meter), **Print** (connect a Zebra
printer and encode a label) and **Scan** (barcodes from the scan trigger). The
reader log (top right) shows the notes the reader sends back.

It runs anywhere: away from Zebra hardware it uses the built-in simulator.

To build it, put the Zebra SDK files in `android/app/libs/` first — see
[Android setup](../README.md#android-setup) in the package README.
