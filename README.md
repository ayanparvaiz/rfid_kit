# rfid_kit

Zebra RFID readers, Link-OS RFID label printers and DataWedge barcode scanners
for Flutter, behind vendor-neutral interfaces.

It was extracted from a warehouse app that runs on Zebra handhelds every day.
The code wrapping the Zebra SDKs is the easy part. This package also carries
the behaviour that took field failures to get right:

- **The reader is opened once and shared.** A Zebra reader takes several
  seconds to open. Opening and closing it per screen can leave it answering
  nothing ("Response timeout") until the handheld reboots.
- **After a failed open, the radio is left alone for 30 s.** In the field, a
  failed reader recovered only when nobody touched it.
- **Reading follows the hardware trigger.** Hold the trigger to read, release
  to stop. Stray release events from some handhelds are ignored, and a radio
  that stops on its own is restarted.
- **The locate meter is built for walking.** It filters to the hunted EPCs
  inside the reader and lowers transmit power while hunting. The meter takes
  the median over a 1.5 s window and holds through read gaps. It shows "not
  reading" between trigger pulls instead of decaying.
- **Close-range confirm mode.** At arm's length only the right tag still
  answers, and the neighbouring bin goes silent.
- **Printer connect errors say why.** Bluetooth printers are paired
  automatically, and a failure names the actual cause: Bluetooth off, missing
  permission, wrong passkey, printer on another subnet, and so on.
- **Crash guard.** A crash on a Zebra SDK thread is recorded without killing
  the app, and handed to you on the next launch.

## Supported hardware

| | Status |
|---|---|
| Zebra TC22R, built-in RFID reader | Field-tested in the original app |
| Zebra ZT411 printer | Field-tested in the original app |
| DataWedge scan trigger (Zebra TC series) | Field-tested in the original app |
| RFD40 / RFD8500 / RFD90 sleds, MC3300R | Wired up through the same Zebra SDK, not field-verified |
| Other Link-OS ZPL printers | Should work, not verified |

The package is Android only. USB printing is not implemented.

## Installation

```bash
flutter pub add rfid_kit
```

Tested with Flutter 3.35 (AGP 8) and Flutter 3.44 (AGP 9).

## Android setup

The Zebra SDKs are proprietary, so this package does **not** include them. You
download them from Zebra and add them to your app.

### 1. Add the Zebra SDK files

Download these from [Zebra's developer portal](https://developer.zebra.com):

- **RFID SDK for Android**: `rfidapi3lib-<version>.aar` (tested with 2.0.5.275)
- **Link-OS SDK for Android**: `ZSDK_ANDROID_API.jar`, plus the jars shipped
  next to it (`commons-io`, `commons-lang3`, `commons-net`,
  `commons-validator`, `core`, `httpcore`, `httpmime`, `jackson-annotations`,
  `jackson-core`, `jackson-databind`, `opencsv`, `pkix`, `prov`, `snmp6_1z`)

Copy all of them into your app's `android/app/libs/`. If you keep them
elsewhere, set `rfidKit.zebraLibs=<path relative to android/>` in
`android/gradle.properties`. If the files are missing, the build stops and
says so.

### 2. `android/app/build.gradle.kts`

```kotlin
android {
    defaultConfig {
        // The Zebra RFID SDK requires Android 10 (API 29).
        minSdk = maxOf(flutter.minSdkVersion, 29)
    }

    // The Link-OS jars each carry their own copies of these files.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}
```

### 3. `android/app/src/main/AndroidManifest.xml`

The RFID SDK declares its own app label, so tell the manifest merger to keep
yours:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:label="My app"
        tools:replace="android:label"
        ...>
```

The plugin adds the permissions and `<queries>` entries it needs itself
(Bluetooth, DataWedge and the Zebra RFID services). It also adds the R8 keep
rules for a minified release.

### 4. Runtime permissions

A built-in reader needs no runtime permission. A Bluetooth sled or a
Bluetooth printer needs `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` granted before
you connect, for example with
[`permission_handler`](https://pub.dev/packages/permission_handler):

```dart
await [Permission.bluetoothScan, Permission.bluetoothConnect].request();
```

## Usage

```dart
import 'package:rfid_kit/rfid_kit.dart';
```

### Pick mock or real hardware

```dart
// auto: the Zebra reader when this device has Zebra RFID hardware, the
//       simulator otherwise — ordinary phones, emulators, web, tests (default).
// real: always the Zebra reader, failing loudly without hardware — use this
//       for the build you ship.
// mock: always the simulator.
DeviceManager.instance.mode = DeviceMode.real;
```

`auto` counts a Zebra handheld, an installed Zebra RFID service or a paired
RFD sled as hardware. Until Bluetooth permission is granted a paired sled can't
be ruled out, so `auto` tries the reader and its connect error says what's
missing. Request the Bluetooth permissions before the first `reader()` call.

### Open the reader

```dart
final session = ReaderSession.instance;
session.notes.listen(print); // open time, power applied, locate reports…

try {
  final reader = await session.reader(); // opened once, then shared
} on DeviceException catch (e) {
  print(e.message); // says what to do, including "wait N seconds"
}
```

Don't disconnect when a screen closes. Call `session.release()` only when the
app is done with the reader.

### Inventory

```dart
// Subscribe BEFORE starting, or the first reads are dropped.
final sub = reader.tags.listen((tag) => print('${tag.epc} ${tag.rssi} dBm'));
await reader.startInventory(); // arms the reader; the trigger reads

// later
await reader.stopInventory();
await sub.cancel();
```

### Locate

```dart
final meter = reader
    .locate([epc, otherEpcOnTheSamePallet],
        live: session.triggerHeldChanges)
    .listen((value) {
  if (value < 0) {
    // trigger released — not reading
  } else {
    // 0..100, higher is nearer; reader.lastRssi has the dBm
  }
});

await reader.setLocateRange(true);  // close range: confirm the right bin
await meter.cancel();               // ends the locate session
```

### Print an RFID label

```dart
final printer = await DeviceManager.instance.resolvePrinter(
  connection: const ConnectionConfig.tcp('192.168.1.50'), // or .bluetooth(mac)
);
await printer.connect();
await printer.calibrateRfid(); // once per label stock

await printer.printRfidLabel(const RfidLabel(
  barcode: 'SKU-1001',
  epc: 'E28011700000020000000001', // 24 hex chars
  lines: ['Aisle 4', '2026-09-26'],
));

// Your own layout:
await printer.sendRaw('^XA^RFW,H^FD$epc^FS^FO40,40^A0N,40,40^FDHello^FS^XZ');
```

`printRfidLabel` refuses a printer that does not speak ZPL. Printing without
encoding would give you a sticker that looks right but still carries its
factory EPC.

### Scan barcodes with the hardware trigger

```dart
HardwareScanner.instance.scans.listen((barcode) => print(barcode));
```

On first use this creates a DataWedge profile for your app. Devices without
DataWedge simply never emit, so keep a camera scanner as a fallback. If the
grip trigger fires the barcode laser instead of RFID, turn the scanner off for
the length of the RFID scan:

```dart
await HardwareScanner.instance.setEnabled(false); // ... RFID scan ...
await HardwareScanner.instance.setEnabled(true);
```

### Keep the screen on while scanning

```dart
await ScreenWake.keepAwake(true);
// ...
await ScreenWake.keepAwake(false); // always pair it, including on errors
```

### Native crash reports

```dart
await RfidDiagnostics.installCrashGuard(); // early, before connecting
final crash = await RfidDiagnostics.takeLastCrash();
if (crash != null) report(crash.trace, fatal: crash.fatal);
```

## Testing your app

`MockRfidReader` and `MockLabelPrinter` are exported. `MockLabelPrinter`
records what it was asked to print (`printed`, `raw`). With
`DeviceMode.mock`, the whole app runs on a laptop or in a browser.

## Adding another reader

Dart only knows the `RfidReader` and `LabelPrinter` interfaces. The platform
channels are vendor-neutral. To support another reader, register a factory
by model:

```dart
DeviceManager.instance.registerReader('FXR90', (descriptor, connection) =>
    MyFxr90Reader(descriptor, connection));
```

On the native side, implement `RfidBackend` or `PrinterBackend` and select it in
the bridge.

## License

MIT for this package. The Zebra SDKs are licensed separately by Zebra.

rfid_kit is not affiliated with or endorsed by Zebra Technologies. Zebra,
Link-OS and DataWedge are trademarks of Zebra Technologies.
