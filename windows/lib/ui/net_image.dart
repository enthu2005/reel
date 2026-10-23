import 'dart:async';
import 'dart:collection';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart' show SynchronousFuture;
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import '../log.dart';
import '../net.dart';

/// Fetches pictures from the Plex server and keeps them on this PC.
///
/// Deliberately plain: ask for a picture and you get it back. There is no queue of our own - disk
/// reads need none, and the connection itself already limits how many downloads run at once. An
/// earlier version queued requests here and quietly lost most of them, which is what left cards
/// empty until they were touched.
class ImageLoader {
  static final http.Client _client = makeClient();
  static final LinkedHashMap<String, Uint8List> _mem = LinkedHashMap();
  static int _memBytes = 0;
  static const int _maxMemBytes = 96 * 1024 * 1024;
  static const int _maxMemItemBytes = 3 * 1024 * 1024;

  // How it's going, shown in Settings so we can see where the time goes.
  static int fromDisk = 0, fromServer = 0, failed = 0, _ms = 0, _bytes = 0, slowest = 0;
  static int brokenPicture = 0; // arrived but this PC couldn't read it
  static final List<String> lastErrors = [];
  static final Set<String> noPicture = {}; // titles the server has no poster for at all

  static String get summary {
    final store = _disk == null ? 'NOT saving to this PC (no folder)' : 'saved in ${_disk!.path}';
    if (fromServer + fromDisk + failed == 0) return 'Nothing loaded yet · $store';
    final avgMs = fromServer == 0 ? 0 : _ms ~/ fromServer;
    final avgKb = fromServer == 0 ? 0 : _bytes ~/ fromServer ~/ 1024;
    final kbps = _ms == 0 ? 0 : (_bytes * 8 / _ms).round(); // kilobits per second, per download
    final broken = brokenPicture == 0 ? '' : ' · $brokenPicture unreadable';
    final none = noPicture.isEmpty
        ? ''
        : '\n${noPicture.length} with no poster on the server: ${noPicture.take(6).join(', ')}';
    final why = lastErrors.isEmpty ? '' : '\n${lastErrors.take(3).join('\n')}';
    return '$fromServer from server (avg $avgMs ms, $avgKb KB, slowest $slowest ms, ~$kbps kbit/s each) · '
        '$fromDisk already on this PC · $failed failed$broken$none\n$store$why';
  }

  static void resetCounts() {
    fromDisk = fromServer = failed = _ms = _bytes = slowest = brokenPicture = 0;
    lastErrors.clear();
    noPicture.clear();
  }

  static void note(String what) {
    lastErrors.insert(0, what);
    if (lastErrors.length > 3) lastErrors.removeLast();
  }

  /// The same picture, but asked for through the server's resizer. Used when the picture file itself
  /// won't download or this PC can't read it (some of them are in formats Windows won't open).
  static String? viaServer(String url) {
    final u = Uri.tryParse(url);
    if (u == null || u.path.contains('/photo/:/transcode') || !u.path.startsWith('/library')) return null;
    final tok = u.queryParameters['X-Plex-Token'];
    if (tok == null) return null;
    return '${u.origin}/photo/:/transcode?width=720&height=1080&minSize=1'
        '&url=${Uri.encodeQueryComponent(u.path)}&X-Plex-Token=$tok';
  }

  /// Enough of the address to recognise it in the log, without the sign-in token.
  static String short(String url) {
    final u = Uri.tryParse(url);
    if (u == null) return url;
    final inner = u.queryParameters['url'];
    final p = inner ?? u.path;
    return p.length > 60 ? p.substring(p.length - 60) : p;
  }

  // Everyone waiting for a picture, by address. Each gets their own handle on the answer: sharing
  // one between them delivered it to the first asker only and left the rest waiting for ever.
  static final Map<String, List<Completer<Uint8List?>>> _waiting = {};
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

  /// The picture for [url]: from memory, else this PC's copy, else the server.
  static Future<Uint8List?> load(String url, {bool background = false, bool remember = true}) {
    final hit = cached(url);
    if (hit != null) return Future.value(hit);
    final c = Completer<Uint8List?>();
    final queue = _waiting.putIfAbsent(url, () => <Completer<Uint8List?>>[]);
    queue.add(c);
    if (queue.length == 1) {
      // whoever asks first does the fetching; everyone else is handed the same answer
      Log.w('ask  ${short(url)}');
      _get(url, remember).then((b) => _deliver(url, b), onError: (_) => _deliver(url, null));
    }
    return c.future;
  }

  static void _deliver(String url, Uint8List? b) {
    final queue = _waiting.remove(url);
    if (queue == null) return;
    for (final w in queue) {
      if (!w.isCompleted) w.complete(b);
    }
  }

  /// Fetch these quietly, for when you get to them.
  static void prefetch(Iterable<String?> urls) {
    for (final u in urls) {
      if (u != null && !_mem.containsKey(u) && !_waiting.containsKey(u)) load(u, background: true);
    }
  }

  /// Quietly saves pictures to this PC for later (skips ones already saved; nothing kept in memory).
  static void warm(Iterable<String?> urls) {
    for (final u in urls) {
      if (u == null || _waiting.containsKey(u)) continue;
      final f = _file(u);
      if (f != null && f.existsSync()) continue;
      load(u, background: true, remember: false);
    }
  }

  static Future<Uint8List?> _get(String url, bool remember) async {
    // 1. this PC's own copy
    final f = _file(url);
    if (f != null) {
      try {
        if (await f.exists()) {
          final b = await f.readAsBytes();
          if (b.isNotEmpty) {
            Log.w('disk ${(b.length / 1024).round()}KB  ${short(url)}');
            fromDisk++;
            if (remember) _remember(url, b);
            return b;
          }
        }
      } catch (_) {}
    }
    // 2. from the server, with a couple of goes, falling back to a resized copy
    var last = '';
    for (var attempt = 0; attempt < 3; attempt++) {
      final get = attempt == 2 ? (viaServer(url) ?? url) : url;
      final sw = Stopwatch()..start();
      try {
        final r = await _client.get(Uri.parse(get)).timeout(const Duration(seconds: 20));
        if (r.statusCode == 200 && r.bodyBytes.isNotEmpty) {
          final b = r.bodyBytes;
          final ms = sw.elapsedMilliseconds;
          fromServer++;
          _ms += ms;
          _bytes += b.length;
          Log.w('net ${ms}ms ${(b.length / 1024).round()}KB  ${short(url)}');
          if (ms > slowest) slowest = ms;
          if (remember) _remember(url, b);
          if (f != null) unawaited(f.writeAsBytes(b).then((_) {}, onError: (_) {}));
          return b;
        }
        last = 'HTTP ${r.statusCode}';
        if (r.statusCode == 404) break; // there's no picture, don't keep asking
      } catch (e) {
        last = e.toString();
      }
      await Future.delayed(Duration(milliseconds: 400 * (attempt + 1)));
    }
    final safe = url.replaceAll(RegExp(r'X-Plex-Token=[^&]+'), 'X-Plex-Token=…');
    failed++;
    note('failed ($last): ${safe.length > 90 ? '${safe.substring(0, 90)}…' : safe}');
    Log.w('FAIL ($last)  ${short(url)}');
    return null;
  }

  static void _remember(String url, Uint8List b) {
    if (b.length > _maxMemItemBytes) return;
    final old = _mem.remove(url);
    if (old != null) _memBytes -= old.length; // replacing: don't count it twice
    _mem[url] = b;
    _memBytes += b.length;
    while (_memBytes > _maxMemBytes && _mem.length > 1) {
      final k = _mem.keys.first;
      _memBytes -= _mem.remove(k)!.length;
    }
  }
}

/// A picture from the server, handed to Flutter as a proper picture source.
///
/// This matters: Flutter then keeps the unpacked picture in its own store, keyed by the address, and
/// hands it straight back to any card that asks for it again. Reel used to fetch the bytes itself and
/// hold them in one card's state, so a card that was rebuilt or put aside mid-fetch lost the result
/// and sat empty for ever - which is why posters only appeared once you touched them.
@immutable
class ReelImage extends ImageProvider<ReelImage> {
  final String url;
  final int? width; // unpack it at roughly the size it will be drawn, not full size

  const ReelImage(this.url, {this.width});

  @override
  Future<ReelImage> obtainKey(ImageConfiguration configuration) => SynchronousFuture<ReelImage>(this);

  @override
  ImageStreamCompleter loadImage(ReelImage key, ImageDecoderCallback decode) {
    Log.w('RESOLVE  ${ImageLoader.short(key.url)}');
    return MultiFrameImageStreamCompleter(
        codec: _codec(key, decode),
        scale: 1.0,
      debugLabel: ImageLoader.short(key.url),
    );
  }

  Future<ui.Codec> _codec(ReelImage key, ImageDecoderCallback decode) async {
    Future<ui.Codec> unpack(Uint8List bytes) async {
      Log.w('u2 making buffer  ${ImageLoader.short(key.url)}');
      final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
      Log.w('u3 unpacking  ${ImageLoader.short(key.url)}');
      final w = key.width;
      return w == null
          ? decode(buffer)
          : decode(buffer, getTargetSize: (int iw, int ih) => ui.TargetImageSize(width: w));
    }

    final started = Log.ms;
    final bytes = await ImageLoader.load(key.url).timeout(const Duration(seconds: 8), onTimeout: () {
      Log.w('TOOK TOO LONG, using whatever we have  ${ImageLoader.short(key.url)}');
      return ImageLoader.cached(key.url);
    });
    if (bytes != null && bytes.isNotEmpty) {
      try {
        final c = await unpack(bytes);
        Log.w('shown after ${Log.ms - started}ms  ${ImageLoader.short(key.url)}');
        return c;
      } catch (_) {
        ImageLoader.brokenPicture++;
        ImageLoader.note("couldn't be read, asking the server to resize it");
        Log.w('UNREADABLE, asking the server to resize  ${ImageLoader.short(key.url)}');
      }
    }
    // Last go: ask the server for a plain resized copy of the same picture.
    final alt = ImageLoader.viaServer(key.url);
    if (alt != null) {
      final b = await ImageLoader.load(alt);
      if (b != null && b.isNotEmpty) return unpack(b);
    }
    Log.w('GAVE UP  ${ImageLoader.short(key.url)}');
    throw StateError('no picture for ${ImageLoader.short(key.url)}');
  }

  @override
  bool operator ==(Object other) => other is ReelImage && other.url == url && other.width == width;

  @override
  int get hashCode => Object.hash(url, width);
}

/// A picture from the server that fades in once it arrives.
class NetImage extends StatelessWidget {
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
  Widget build(BuildContext context) {
    final u = url;
    if (u == null) return fallback ?? const SizedBox.expand();
    return Image(
      image: ReelImage(u, width: decodeWidth),
      fit: fit,
      alignment: alignment,
      gaplessPlayback: true,
      width: double.infinity,
      height: double.infinity,
      errorBuilder: (_, __, ___) => fallback ?? const SizedBox.expand(),
      frameBuilder: (c, child, frame, sync) => sync || !fade
          ? child
          : AnimatedOpacity(
              opacity: frame == null ? 0 : 1,
              duration: const Duration(milliseconds: 180),
              child: child,
            ),
    );
  }
}
