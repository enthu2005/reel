import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import '../net.dart';
import '../plex.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// How fast the Plex server can send to THIS PC. It reads a piece of one of the server's own films
/// over the same address the app uses, so on a home network it measures the link between the server
/// and this PC (not your internet connection), and away from home it measures the whole way there.
class SpeedTestScreen extends StatefulWidget {
  const SpeedTestScreen({super.key});

  @override
  State<SpeedTestScreen> createState() => _SpeedTestScreenState();
}

class _SpeedTestScreenState extends State<SpeedTestScreen> {
  final http.Client _client = makeClient();
  final ValueNotifier<double> _live = ValueNotifier(0); // Mbit/s right now
  String _step = '';
  bool _running = false;
  bool _cancel = false;
  String? _error;
  int _pingMs = -1;
  double _one = 0, _many = 0; // Mbit/s: one stream (what playback uses), several at once (the most the link gives)
  String _file = '';
  int _fileKbit = 0; // the test film's average bit rate
  final _go = FocusNode();

  @override
  void dispose() {
    _cancel = true;
    _client.close();
    _live.dispose();
    _go.dispose();
    super.dispose();
  }

  /// Where the app reaches the server, in words.
  String get _route {
    final u = Uri.tryParse(Plex.server ?? '');
    final host = u?.host ?? '';
    if (host.isEmpty) return 'No server';
    final private = host == 'localhost' ||
        RegExp(r'^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|127\.|169\.254\.)').hasMatch(host);
    if (private) return 'Your own network (the server is local)';
    if (host.endsWith('.plex.direct')) return 'Over the internet, direct to the server';
    if (host.contains('plex.services') || u!.port == 443) return 'Over the internet, maybe through the Plex relay (slow)';
    return 'Over the internet, direct to the server';
  }

  Future<void> _run() async {
    if (_running) return;
    setState(() {
      _running = true;
      _cancel = false;
      _error = null;
      _pingMs = -1;
      _one = 0;
      _many = 0;
      _step = 'Reaching the server';
    });
    _live.value = 0;
    try {
      // 1. how long a request takes (no token needed: /identity is open)
      final times = <int>[];
      for (var i = 0; i < 5; i++) {
        final sw = Stopwatch()..start();
        await _client
            .get(Uri.parse('${Plex.server}/identity'))
            .timeout(const Duration(seconds: 6));
        times.add(sw.elapsedMilliseconds);
        if (_cancel) return;
      }
      times.sort();
      if (mounted) setState(() => _pingMs = times[times.length ~/ 2]);

      // 2. a real film file to read from
      setState(() => _step = 'Finding a film to read from');
      final test = await _pickFile();
      if (test == null) throw Exception('No film on the server is big enough to test with.');
      final (url, size, kbit, name) = test;
      _file = name;
      _fileKbit = kbit;

      // 3. one stream, then several
      setState(() => _step = 'One stream (how playback reads)');
      _one = await _download(url, size, streams: 1, seconds: 7);
      if (_cancel) return;
      setState(() => _step = 'Four streams (the most the link gives)');
      _many = await _download(url, size, streams: 4, seconds: 6);
      _live.value = _one;
      setState(() => _step = '');
    } catch (e) {
      if (!_cancel && mounted) setState(() => _error = '$e'.replaceFirst('Exception: ', ''));
    }
    if (mounted) {
      setState(() => _running = false);
      _go.requestFocus();
    }
  }

  /// (address, size in bytes, average kbit/s, title) of a large film or episode.
  Future<(String, int, int, String)?> _pickFile() async {
    final secs = await Plex.sections();
    for (final s in secs.where((s) => s.type == 'movie' || s.type == 'show')) {
      final list = await Plex.items(Plex.paged(
          '/library/sections/${s.key}/${s.type == 'show' ? 'all?type=4&' : 'all?'}sort=addedAt:desc', 40));
      for (final it in list) {
        var p = it;
        if (p.partKey == null || p.partSize == 0) {
          try {
            p = await Plex.metadata(it.key);
          } catch (_) {
            continue;
          }
        }
        final url = Plex.directUrl(p);
        if (url != null && p.partSize > 150 * 1024 * 1024) {
          return (url, p.partSize, p.mediaBitrate, p.displayTitle);
        }
      }
    }
    return null;
  }

  /// Reads for [seconds] over [streams] connections at once; returns the Mbit/s that arrived.
  Future<double> _download(String url, int size, {required int streams, required int seconds}) async {
    final total = <int>[0];
    final sw = Stopwatch()..start();
    var last = 0, lastMs = 0;
    final ticker = Timer.periodic(const Duration(milliseconds: 250), (_) {
      final ms = sw.elapsedMilliseconds;
      if (ms - lastMs >= 250) {
        _live.value = (total[0] - last) * 8 / 1e6 / ((ms - lastMs) / 1000);
        last = total[0];
        lastMs = ms;
      }
    });
    Future<void> one(int n) async {
      // each stream starts somewhere different in the file, away from the very start
      final from = (size * (0.12 + 0.2 * n)).floor();
      final req = http.Request('GET', Uri.parse(url))
        ..headers['Range'] = 'bytes=$from-${size - 1}';
      final res = await _client.send(req).timeout(const Duration(seconds: 10));
      if (res.statusCode >= 400) throw Exception('The server said HTTP ${res.statusCode}.');
      await for (final chunk in res.stream) {
        total[0] += chunk.length;
        if (_cancel || sw.elapsed.inSeconds >= seconds) break;
      }
    }

    try {
      await Future.wait([for (var n = 0; n < streams; n++) one(n)]);
    } finally {
      ticker.cancel();
    }
    final secs = math.max(0.001, sw.elapsedMilliseconds / 1000);
    return total[0] * 8 / 1e6 / secs;
  }

  Widget _verdict(double h, String what, double need, double have) {
    final ok = have >= need;
    final tight = !ok && have >= need * 0.8;
    final col = ok ? const Color(0xFF30D158) : (tight ? const Color(0xFFFF9F0A) : const Color(0xFFFF453A));
    return Padding(
      padding: EdgeInsets.only(bottom: h * 0.01),
      child: Row(children: [
        Icon(ok ? Icons.check_circle_rounded : (tight ? Icons.error_rounded : Icons.cancel_rounded),
            color: col, size: h * 0.034),
        SizedBox(width: h * 0.014),
        Expanded(child: Text(what, style: reelText(h * 0.026, weight: FontWeight.w500))),
        Text(ok ? 'Fine' : (tight ? 'Might stutter' : 'Too slow'),
            style: reelText(h * 0.024, color: col, weight: FontWeight.w600)),
      ]),
    );
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    // keep a quarter back for the file's bursts and for everything else on the link
    final usable = _one * 0.75;
    final done = !_running && _one > 0;
    return Scaffold(
      body: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.12, h * 0.1, w * 0.12, h * 0.06),
        child: SingleChildScrollView(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Speed to your server', style: reelText(h * 0.05, weight: FontWeight.w700)),
            SizedBox(height: h * 0.008),
            Text(
                'Reads a piece of one of the server\'s own films and times it. This is the link between the server and this PC, not your internet speed.',
                style: reelText(h * 0.024, color: Reel.text2)),
            SizedBox(height: h * 0.012),
            Text('Reaching it: $_route', style: reelText(h * 0.024, color: Reel.text2)),
            SizedBox(height: h * 0.04),
            Center(
              child: ValueListenableBuilder<double>(
                valueListenable: _live,
                builder: (c, v, _) => Column(children: [
                  Text(v.toStringAsFixed(v >= 100 ? 0 : 1),
                      style: reelText(h * 0.16, weight: FontWeight.w700, color: Reel.accent)),
                  Text('Mbit/s', style: reelText(h * 0.03, color: Reel.text2)),
                ]),
              ),
            ),
            SizedBox(height: h * 0.01),
            Center(
              child: Text(
                  _running
                      ? _step
                      : (_error != null ? '' : (done ? 'Done' : 'Press Start')),
                  style: reelText(h * 0.026, color: Reel.text2)),
            ),
            if (_error != null)
              Padding(
                padding: EdgeInsets.only(top: h * 0.01),
                child: Center(
                    child: Text(_error!,
                        textAlign: TextAlign.center,
                        style: reelText(h * 0.026, color: const Color(0xFFFF453A)))),
              ),
            SizedBox(height: h * 0.03),
            Center(
              child: PillButton(_running ? 'Stop' : (done ? 'Test again' : 'Start'),
                  primary: true, autofocus: true, focusNode: _go, onPressed: () {
                if (_running) {
                  _cancel = true;
                } else {
                  _run();
                }
              }),
            ),
            if (_pingMs >= 0 || done) SizedBox(height: h * 0.04),
            if (_pingMs >= 0)
              Text(
                  'Reply time: $_pingMs ms${_pingMs > 150 ? '  (slow to answer: seeking will feel slow)' : ''}',
                  style: reelText(h * 0.026, weight: FontWeight.w500)),
            if (done) ...[
              SizedBox(height: h * 0.01),
              Text(
                  'One stream: ${_one.toStringAsFixed(1)} Mbit/s   ·   Four at once: ${_many.toStringAsFixed(1)} Mbit/s',
                  style: reelText(h * 0.026, weight: FontWeight.w500)),
              if (_many > _one * 1.6)
                Padding(
                  padding: EdgeInsets.only(top: h * 0.006),
                  child: Text(
                      'Several streams are much faster than one: a long or busy path limits a single stream. The bigger buffer in Settings helps here.',
                      style: reelText(h * 0.022, color: Reel.text2)),
                ),
              SizedBox(height: h * 0.03),
              Text('What will play smoothly (with a quarter kept back): ${usable.toStringAsFixed(0)} Mbit/s',
                  style: reelText(h * 0.03, weight: FontWeight.w700)),
              SizedBox(height: h * 0.014),
              _verdict(h, '4K Blu-ray quality, about 80 Mbit/s', 80, usable),
              _verdict(h, '4K streaming quality, about 25 Mbit/s', 25, usable),
              _verdict(h, '1080p high quality, about 12 Mbit/s', 12, usable),
              _verdict(h, '1080p, about 8 Mbit/s', 8, usable),
              _verdict(h, '720p, about 4 Mbit/s', 4, usable),
              if (_fileKbit > 0) ...[
                SizedBox(height: h * 0.012),
                _verdict(h, '"$_file" as it is (${(_fileKbit / 1000).toStringAsFixed(0)} Mbit/s on average, bursts up to about double)',
                    _fileKbit / 1000 * 1.5, usable),
              ],
              SizedBox(height: h * 0.02),
              Text(
                  usable >= 40
                      ? 'This link handles everything at full quality.'
                      : (usable >= 12
                          ? 'Fine for most things. Huge 4K remuxes may need the bigger buffer (Settings).'
                          : 'Slow. Pick a lower quality for big files, and turn on the bigger buffer in Settings.'),
                  style: reelText(h * 0.024, color: Reel.text2)),
            ],
          ]),
        ),
      ),
    );
  }
}
