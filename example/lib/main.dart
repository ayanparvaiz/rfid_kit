import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:rfid_kit/rfid_kit.dart';

void main() {
  // `auto` drives the real reader on a Zebra device and the simulator anywhere
  // else. Ship with DeviceMode.real so a mock can never reach production.
  DeviceManager.instance.mode = DeviceMode.auto;
  runApp(const ExampleApp());
}

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'rfid_kit example',
      theme: ThemeData(colorSchemeSeed: Colors.indigo),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _session = ReaderSession.instance;
  final _notes = <String>[];

  /// The EPC picked on the Inventory tab, hunted on the Locate tab.
  final _target = ValueNotifier<String>('');

  RfidReader? _reader;
  String _status = 'Not connected';
  bool _connecting = false;
  StreamSubscription<String>? _notesSub;

  @override
  void initState() {
    super.initState();
    _notesSub = _session.notes.listen(_log);
    // Record native crashes (including ones inside the Zebra SDK) and report
    // the previous run's, if there was one.
    RfidDiagnostics.installCrashGuard();
    RfidDiagnostics.takeLastCrash().then((crash) {
      if (crash != null) _log('Previous run crashed: $crash');
    });
  }

  @override
  void dispose() {
    _notesSub?.cancel();
    super.dispose();
  }

  void _log(String note) {
    if (!mounted) return;
    setState(() => _notes.insert(0, note));
  }

  Future<void> _connect() async {
    setState(() {
      _connecting = true;
      _status = 'Connecting…';
    });
    // A Bluetooth sled or printer needs these; a built-in reader does not.
    await [Permission.bluetoothScan, Permission.bluetoothConnect].request();
    try {
      final reader = await _session.reader();
      setState(() {
        _reader = reader;
        _status = 'Connected: ${reader.descriptor.displayName}';
      });
    } on DeviceException catch (e) {
      setState(() => _status = e.message);
    } catch (e) {
      setState(() => _status = '$e');
    } finally {
      if (mounted) setState(() => _connecting = false);
    }
  }

  void _showLog() {
    showModalBottomSheet<void>(
      context: context,
      builder: (_) => ListView(
        padding: const EdgeInsets.all(16),
        children: [
          for (final note in _notes)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(note, style: const TextStyle(fontFamily: 'monospace')),
            ),
          if (_notes.isEmpty) const Text('Nothing logged yet.'),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('rfid_kit'),
          actions: [
            IconButton(
              tooltip: 'Reader log',
              icon: const Icon(Icons.receipt_long),
              onPressed: _showLog,
            ),
          ],
          bottom: const TabBar(tabs: [
            Tab(text: 'Inventory'),
            Tab(text: 'Locate'),
            Tab(text: 'Print'),
            Tab(text: 'Scan'),
          ]),
        ),
        body: Column(
          children: [
            _ReaderBar(
              status: _status,
              connecting: _connecting,
              connected: _reader != null,
              triggerHeld: _session.triggerHeld,
              onConnect: _connect,
            ),
            Expanded(
              child: TabBarView(children: [
                InventoryTab(reader: _reader, target: _target),
                LocateTab(reader: _reader, session: _session, target: _target),
                const PrintTab(),
                const ScanTab(),
              ]),
            ),
          ],
        ),
      ),
    );
  }
}

class _ReaderBar extends StatelessWidget {
  const _ReaderBar({
    required this.status,
    required this.connecting,
    required this.connected,
    required this.triggerHeld,
    required this.onConnect,
  });

  final String status;
  final bool connecting;
  final bool connected;
  final ValueListenable<bool> triggerHeld;
  final VoidCallback onConnect;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
        child: Row(
          children: [
            ValueListenableBuilder<bool>(
              valueListenable: triggerHeld,
              builder: (_, held, _) => Icon(
                held ? Icons.radio_button_checked : Icons.radio_button_off,
                color: held ? Colors.green : null,
                semanticLabel: held ? 'Trigger held' : 'Trigger released',
              ),
            ),
            const SizedBox(width: 12),
            Expanded(child: Text(status)),
            if (!connected)
              FilledButton(
                onPressed: connecting ? null : onConnect,
                child: const Text('Connect'),
              ),
          ],
        ),
      ),
    );
  }
}

// --- Inventory ---------------------------------------------------------------

class InventoryTab extends StatefulWidget {
  const InventoryTab({super.key, required this.reader, required this.target});

  final RfidReader? reader;
  final ValueNotifier<String> target;

  @override
  State<InventoryTab> createState() => _InventoryTabState();
}

class _InventoryTabState extends State<InventoryTab> {
  final _seen = <String, ({int rssi, int count})>{};
  StreamSubscription<TagRead>? _sub;
  bool _running = false;

  Future<void> _start() async {
    final reader = widget.reader;
    if (reader == null) return;
    // Subscribe BEFORE starting, or the first reads are dropped.
    _sub = reader.tags.listen((tag) {
      setState(() {
        final before = _seen[tag.epc];
        _seen[tag.epc] = (rssi: tag.rssi, count: (before?.count ?? 0) + 1);
      });
    });
    await ScreenWake.keepAwake(true);
    await reader.startInventory();
    setState(() => _running = true);
  }

  Future<void> _stop() async {
    await widget.reader?.stopInventory();
    await _sub?.cancel();
    _sub = null;
    await ScreenWake.keepAwake(false);
    if (mounted) setState(() => _running = false);
  }

  @override
  void dispose() {
    if (_running) _stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final epcs = _seen.keys.toList()..sort();
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              FilledButton.icon(
                onPressed: widget.reader == null
                    ? null
                    : (_running ? _stop : _start),
                icon: Icon(_running ? Icons.stop : Icons.play_arrow),
                label: Text(_running ? 'Stop' : 'Start inventory'),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Text(
                  _running
                      ? 'Hold the trigger to read · ${epcs.length} tags'
                      : '${epcs.length} tags',
                ),
              ),
              IconButton(
                tooltip: 'Clear',
                onPressed: () => setState(_seen.clear),
                icon: const Icon(Icons.clear_all),
              ),
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            itemCount: epcs.length,
            itemBuilder: (_, i) {
              final epc = epcs[i];
              final info = _seen[epc]!;
              return ListTile(
                title: Text(epc, style: const TextStyle(fontFamily: 'monospace')),
                subtitle: Text('${info.rssi} dBm · ${info.count} reads'),
                trailing: const Icon(Icons.my_location),
                onTap: () {
                  widget.target.value = epc;
                  DefaultTabController.of(context).animateTo(1);
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

// --- Locate ------------------------------------------------------------------

class LocateTab extends StatefulWidget {
  const LocateTab({
    super.key,
    required this.reader,
    required this.session,
    required this.target,
  });

  final RfidReader? reader;
  final ReaderSession session;
  final ValueNotifier<String> target;

  @override
  State<LocateTab> createState() => _LocateTabState();
}

class _LocateTabState extends State<LocateTab> {
  late final _epc = TextEditingController(text: widget.target.value);
  StreamSubscription<int>? _sub;
  int _proximity = -1;
  bool _near = false;

  @override
  void initState() {
    super.initState();
    widget.target.addListener(_onTarget);
  }

  void _onTarget() => _epc.text = widget.target.value;

  Future<void> _start() async {
    final reader = widget.reader;
    final epc = _epc.text.trim();
    if (reader == null || epc.isEmpty) return;
    await ScreenWake.keepAwake(true);
    _sub = reader
        .locate([epc], live: widget.session.triggerHeldChanges)
        .listen(
          (value) => setState(() => _proximity = value),
          onError: (Object e) {
            if (!mounted) return;
            ScaffoldMessenger.of(context)
                .showSnackBar(SnackBar(content: Text('$e')));
            _stop();
          },
        );
    setState(() {});
  }

  Future<void> _stop() async {
    await _sub?.cancel(); // cancelling ends the locate session
    _sub = null;
    await ScreenWake.keepAwake(false);
    if (mounted) setState(() => _proximity = -1);
  }

  @override
  void dispose() {
    widget.target.removeListener(_onTarget);
    if (_sub != null) _stop();
    _epc.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final running = _sub != null;
    final reading = _proximity >= 0;
    final rssi = widget.reader?.lastRssi ?? 0;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        TextField(
          controller: _epc,
          enabled: !running,
          decoration: const InputDecoration(
            labelText: 'EPC to find',
            helperText: 'Tap a tag on the Inventory tab, or type one',
          ),
        ),
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: widget.reader == null ? null : (running ? _stop : _start),
          icon: Icon(running ? Icons.stop : Icons.my_location),
          label: Text(running ? 'Stop' : 'Locate'),
        ),
        const SizedBox(height: 32),
        Text(
          !running
              ? '—'
              : reading
                  ? '$_proximity%'
                  : 'Hold the trigger',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.displayMedium,
        ),
        const SizedBox(height: 12),
        LinearProgressIndicator(
          value: reading ? _proximity / 100 : 0,
          minHeight: 16,
          borderRadius: BorderRadius.circular(8),
        ),
        const SizedBox(height: 8),
        Text(
          rssi == 0 ? 'no read' : '$rssi dBm',
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 24),
        SwitchListTile(
          title: const Text('Confirm range'),
          subtitle: const Text(
              'Short reach: at arm\'s length only the right tag still answers'),
          value: _near,
          onChanged: widget.reader == null
              ? null
              : (on) {
                  setState(() => _near = on);
                  widget.reader!.setLocateRange(on);
                },
        ),
      ],
    );
  }
}

// --- Print -------------------------------------------------------------------

class PrintTab extends StatefulWidget {
  const PrintTab({super.key});

  @override
  State<PrintTab> createState() => _PrintTabState();
}

class _PrintTabState extends State<PrintTab> {
  final _address = TextEditingController();
  final _barcode = TextEditingController(text: 'DEMO-0001');
  final _epc = TextEditingController(text: 'E28011700000020000000001');
  DeviceTransport _transport = DeviceTransport.tcp;
  LabelPrinter? _printer;
  bool _busy = false;

  ConnectionConfig get _config => switch (_transport) {
        DeviceTransport.tcp => ConnectionConfig.tcp(_address.text.trim()),
        DeviceTransport.bluetooth =>
          ConnectionConfig.bluetooth(_address.text.trim()),
        DeviceTransport.bluetoothLe =>
          ConnectionConfig.bluetoothLe(_address.text.trim()),
        _ => const ConnectionConfig.mock(),
      };

  Future<void> _run(Future<void> Function() job, String done) async {
    setState(() => _busy = true);
    try {
      await job();
      _say(done);
    } on DeviceException catch (e) {
      _say(e.message);
    } catch (e) {
      _say('$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _say(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _connect() => _run(() async {
        await _printer?.disconnect();
        final printer =
            await DeviceManager.instance.resolvePrinter(connection: _config);
        await printer.connect();
        setState(() => _printer = printer);
      }, 'Printer connected');

  Future<void> _print() => _run(
        () => _printer!.printRfidLabel(RfidLabel(
          barcode: _barcode.text.trim(),
          epc: _epc.text.trim(),
          lines: ['Printed by rfid_kit', DateTime.now().toString()],
        )),
        'Label sent',
      );

  @override
  void dispose() {
    _printer?.disconnect();
    _address.dispose();
    _barcode.dispose();
    _epc.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final connected = _printer?.isConnected ?? false;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        SegmentedButton<DeviceTransport>(
          segments: const [
            ButtonSegment(value: DeviceTransport.tcp, label: Text('WiFi')),
            ButtonSegment(value: DeviceTransport.bluetooth, label: Text('BT')),
            ButtonSegment(value: DeviceTransport.bluetoothLe, label: Text('BLE')),
            ButtonSegment(value: DeviceTransport.mock, label: Text('Mock')),
          ],
          selected: {_transport},
          onSelectionChanged: (s) => setState(() => _transport = s.first),
        ),
        if (_transport != DeviceTransport.mock)
          TextField(
            controller: _address,
            decoration: InputDecoration(
              labelText: _transport == DeviceTransport.tcp
                  ? 'Printer IP (port 9100)'
                  : 'Printer MAC (AA:BB:CC:DD:EE:FF)',
            ),
          ),
        const SizedBox(height: 12),
        OutlinedButton(
          onPressed: _busy ? null : _connect,
          child: Text(connected ? 'Reconnect' : 'Connect printer'),
        ),
        const Divider(height: 32),
        TextField(
          controller: _barcode,
          decoration: const InputDecoration(labelText: 'Barcode'),
        ),
        TextField(
          controller: _epc,
          decoration: const InputDecoration(labelText: 'EPC (24 hex)'),
        ),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: _busy || !connected ? null : _print,
          child: const Text('Encode + print label'),
        ),
        TextButton(
          onPressed: _busy || !connected
              ? null
              : () => _run(_printer!.calibrateRfid, 'Calibration started'),
          child: const Text('Calibrate RFID (once per label stock)'),
        ),
      ],
    );
  }
}

// --- Scan --------------------------------------------------------------------

class ScanTab extends StatefulWidget {
  const ScanTab({super.key});

  @override
  State<ScanTab> createState() => _ScanTabState();
}

class _ScanTabState extends State<ScanTab> {
  final _codes = <String>[];
  StreamSubscription<String>? _sub;

  @override
  void initState() {
    super.initState();
    _sub = HardwareScanner.instance.scans
        .listen((code) => setState(() => _codes.insert(0, code)));
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_codes.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(32),
          child: Text(
            'Press the barcode scan button on a Zebra device.\n'
            'Nothing happens on devices without DataWedge.',
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    return ListView(
      children: [for (final code in _codes) ListTile(title: Text(code))],
    );
  }
}
