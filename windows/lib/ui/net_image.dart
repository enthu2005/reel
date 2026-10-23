import 'dart:async';
import 'dart:collection';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import '../net.dart';

class _Wait {
  final String url;
  final Completer<void> done = Completer<void>();
  _Wait(this.url);
}

/// Loads pictures from the Plex server as fast as a weak connection allows:
///  * every picture is saved to disk (%LOCALAPPDATA%\Reel\images), so after the first time
///    posters come up instantly, even after restarting the app;
///  * what's on screen goes first (newest request first); whole rows are fetched quietly
///    in the background behind it, so scrolling right finds them ready;
///  * a few at a time, with retries, falling back to the original picture if resizing fails.
class ImageLoader {
  static final http.Client _client = makeClient();
  static final LinkedHashMap<String, Uint8List> _mem = LinkedHashMap();
  static int _memBytes = 0;
  static const int _maxMemBytes = 80 * 1024 * 1024;
  static const int _parallel = 12;
  static const int _parallelLow = 6; // background fetching never takes every slot

  // How it's going, shown in Settings so we can see where the time goes.
  static int fromDisk = 0, fromServer = 0, failed = 0, _ms = 0, _bytes = 0, slowest = 0;

  static String get summary {
    if (fromServer + fromDisk + failed == 0) return 'Nothing loaded yet';
    final avgMs = fromServer == 0 ? 0 : _ms ~/ fromServer;
    final avgKb = fromServer == 0 ? 0 : _bytes ~/ fromServer ~/ 1024;
    final kbps = _ms == 0 ? 0 : (_bytes * 8 / _ms).round(); // kilobits per second, per download
    return '$fromServer from server (avg $avgMs ms, $avgKb KB, slowest $slowest ms, ~$kbps kbit/s each) · '
        '$fromDisk from this PC · $failed failed';
  }
  static int _active = 0;
  static final List<_Wait> _high = [];
  static final Queue<_Wait> _low = Queue();
  static final Map<String, Future<Uint8List?>> _inFlight = {};
  static Directory? _dir;
  static bool _dirTried = false;

  static Directory? get _disk {
    if (_dirTried) return _dir;
    _dirTried = true;
    try {
      final base = Platform.environment['LOCALAPPDATA'] ?? Directory.systemTemp.path;
      final d = Directory('$base${Platform.pathSeparator}Reel${Platform.pathSeparator}images');
      d.createSync(recursive: true);
      _dir = d;
    } catch (_) {}
    return _dir;
  }

  /// Same picture, same file name, whatever the sign-in token is.
  static String _name(String url) {
    // ignore the server address and token, so switching between home and away addresses keeps the cache
    final u = Uri.tryParse(url);
    final s = (u == null ? url : '${u.path}?${u.query}').replaceAll(RegExp(r'[?&]?X-Plex-Token=[^&]+'), '');
    var a = 0x811c9dc5, b = 0x01000193;
    for (final c in s.codeUnits) {
      a = ((a ^ c) * 0x01000193) & 0xFFFFFFFF;
      b = ((b ^ c) * 0x0100019d + 7) & 0xFFFFFFFF;
    }
    return '${a.toRadixString(16).padLeft(8, '0')}${b.toRadixString(16).padLeft(8, '0')}.img';
  }

  static File? _file(String url) {
    final d = _disk;
    return d == null ? null : File('${d.path}${Platform.pathSeparator}${_name(url)}');
  }

  static Uint8List? cached(String url) {
    final b = _mem.remove(url);
    if (b != null) _mem[url] = b; // most recently used goes to the end
    return b;
  }

  /// [background] = fetch it for later, behind anything on screen.
  static Future<Uint8List?> load(String url, {bool background = false, bool remember = true}) {
    final hit = cached(url);
    if (hit != null) return Future.value(hit);
    final going = _inFlight[url];
    if (going != null) {
      if (!background) _promote(url);
      return going;
    }
    return _inFlight[url] = _get(url, background, remember).whenComplete(() => _inFlight.remove(url));
  }

  /// Warm up a list of pictures in the background.
  static void prefetch(Iterable<String?> urls) {
    for (final u in urls) {
      if (u != null && !_mem.containsKey(u) && !_inFlight.containsKey(u)) load(u, background: true);
    }
  }

  /// Quietly saves pictures to disk for later (skips ones already saved; nothing kept in memory).
  static void warm(Iterable<String?> urls) {
    for (final u in urls) {
      if (u == null || _inFlight.containsKey(u)) continue;
      final f = _file(u);
      if (f != null && f.existsSync()) continue;
      load(u, background: true, remember: false);
    }
  }

  static Future<Uint8List?> _get(String url, bool background, bool remember) async {
    // 1. from disk
    final f = _file(url);
    if (f != null) {
      try {
        if (await f.exists()) {
          final b = await f.readAsBytes();
          if (b.isNotEmpty) {
            fromDisk++;
            if (remember) _remember(url, b);
            return b;
          }
        }
      } catch (_) {}
    }
    // 2. from the server
    var last = '';
    for (var attempt = 0; attempt < 3; attempt++) {
      final get = attempt == 2 ? (_original(url) ?? url) : url;
      await _slot(url, background && attempt == 0);
      final sw = Stopwatch()..start();
      try {
        final r = await _client.get(Uri.parse(get)).timeout(const Duration(seconds: 20));
        if (r.statusCode == 200 && r.bodyBytes.isNotEmpty) {
          final b = r.bodyBytes;
          final ms = sw.elapsedMilliseconds;
          fromServer++;
          _ms += ms;
          _bytes += b.length;
          if (ms > slowest) slowest = ms;
          if (remember) _remember(url, b);
          if (f != null) unawaited(f.writeAsBytes(b).then((_) {}, onError: (_) {}));
          return b;
        }
        last = 'HTTP ${r.statusCode}';
        if (r.statusCode == 404) break; // there's no picture, don't keep asking
      } catch (e) {
        last = e.toString();
      } finally {
        _release();
      }
      await Future.delayed(Duration(milliseconds: 500 * (attempt + 1)));
    }
    final safe = url.replaceAll(RegExp(r'X-Plex-Token=[^&]+'), 'X-Plex-Token=…');
    failed++;
    debugPrint('Reel image failed ($last): $safe');
    return null;
  }

  /// The original picture behind a /photo/:/transcode address (used if resizing keeps failing).
  static String? _original(String url) {
    final u = Uri.tryParse(url);
    if (u == null || !u.path.contains('/photo/:/transcode')) return null;
    final path = u.queryParameters['url'];
    final token = u.queryParameters['X-Plex-Token'];
    if (path == null) return null;
    if (path.startsWith('http')) return path;
    return '${u.origin}$path${path.contains('?') ? '&' : '?'}X-Plex-Token=$token';
  }

  static Future<void> _slot(String url, bool low) {
    if (_active < (low ? _parallelLow : _parallel)) {
      _active++;
      return Future.value();
    }
    final w = _Wait(url);
    if (low) {
      _low.add(w);
    } else {
      _high.add(w);
    }
    return w.done.future;
  }

  static void _promote(String url) {
    _Wait? found;
    for (final w in _low) {
      if (w.url == url) {
        found = w;
        break;
      }
    }
    if (found != null) {
      _low.remove(found);
      _high.add(found);
    }
  }

  static void _release() {
    if (_high.isNotEmpty) {
      _high.removeLast().done.complete(); // newest on-screen request first
    } else if (_low.isNotEmpty && _active <= _parallelLow) {
      _low.removeFirst().done.complete();
    } else {
      _active--;
    }
  }

  static void _remember(String url, Uint8List b) {
    _mem[url] = b;
    _memBytes += b.length;
    while (_memBytes > _maxMemBytes && _mem.isNotEmpty) {
      final k = _mem.keys.first;
      _memBytes -= _mem.remove(k)!.length;
    }
  }
}

/// A picture from the server that fades in once it arrives.
class NetImage extends StatefulWidget {
  final String? url;
  final BoxFit fit;
  final Alignment alignment;
  final int? decodeWidth;
  final Widget? fallback;
  final bool fade;

  const NetImage(this.url,
      {super.key,
      this.fit = BoxFit.cover,
      this.alignment = Alignment.center,
      this.decodeWidth,
      this.fallback,
      this.fade = true});

  @override
  State<NetImage> createState() => _NetImageState();
}

class _NetImageState extends State<NetImage> {
  Uint8List? _bytes;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _start();
  }

  @override
  void didUpdateWidget(covariant NetImage old) {
    super.didUpdateWidget(old);
    if (old.url != widget.url) _start();
  }

  void _start() {
    final url = widget.url;
    _failed = false;
    if (url == null) {
      _bytes = null;
      _failed = true;
      return;
    }
    _bytes = ImageLoader.cached(url);
    if (_bytes != null) return;
    ImageLoader.load(url).then((b) {
      if (!mounted || widget.url != url) return;
      setState(() {
        _bytes = b;
        _failed = b == null;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final b = _bytes;
    if (b == null) return _failed ? (widget.fallback ?? const SizedBox.expand()) : const SizedBox.expand();
    return Image.memory(
      b,
      fit: widget.fit,
      alignment: widget.alignment,
      cacheWidth: widget.decodeWidth,
      gaplessPlayback: true,
      width: double.infinity,
      height: double.infinity,
      errorBuilder: (_, __, ___) => widget.fallback ?? const SizedBox.expand(),
      frameBuilder: (c, child, frame, sync) => sync || !widget.fade
          ? child
          : AnimatedOpacity(
              opacity: frame == null ? 0 : 1,
              duration: const Duration(milliseconds: 180),
              child: child,
            ),
    );
  }
}
