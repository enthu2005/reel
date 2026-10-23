import 'dart:async';
import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import '../plex.dart';
import '../prefs.dart';

/// Live previews, like the Fire TV app: rest on a card and after a moment its trailer (Plex's own,
/// from the library's extras) or a scene from the film plays inside it, quietly. Open the title and
/// the same video carries on behind the title page. One preview plays at a time.
class Preview {
  static Player? _player;
  static VideoController? _video;

  /// The key of the title whose preview is on screen right now (frames are coming), or null.
  static final ValueNotifier<String?> showing = ValueNotifier(null);

  static String? _want;
  static String? _session;
  static Timer? _timer;
  static String? _timerKey;
  static bool _keep = false;
  static int _gen = 0;
  static final Map<String, String?> _trailers = {};
  static final Map<String, int> _offsets = {};

  /// The part of the picture that isn't black bars, as fractions (left, top, right, bottom), measured
  /// for film scenes so they can be scaled to fill the screen. Null = use the whole picture.
  static final ValueNotifier<Rect?> crop = ValueNotifier(null);

  static VideoController? get video => _video;

  static String? _desired;
  static bool _queued = false;

  /// Changes what's showing, but never in the middle of the screen being built (that threw
  /// "setState() called during build" when a page opened and stopped the preview).
  static void _show(String? key) {
    _desired = key;
    final phase = SchedulerBinding.instance.schedulerPhase;
    final building = phase == SchedulerPhase.persistentCallbacks || phase == SchedulerPhase.midFrameMicrotasks;
    if (!building) {
      showing.value = key;
      return;
    }
    if (_queued) return;
    _queued = true;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      _queued = false;
      showing.value = _desired;
    });
  }
  static String? get wanted => _want ?? _timerKey;

  /// This title's preview is being carried into another page (title page or full screen).
  static bool held(String key) => _keep && wanted == key;

  static Player _ensure() {
    if (_player != null) return _player!;
    final p = Player(configuration: const PlayerConfiguration(title: 'Reel preview', bufferSize: 48 * 1024 * 1024));
    _player = p;
    _video = VideoController(p);
    p.setPlaylistMode(PlaylistMode.single); // loop a short trailer
    // Even out the loudness, so every trailer and scene plays at about the same level.
    try {
      (p.platform as dynamic).setProperty('af', 'loudnorm=I=-20:LRA=9:TP=-2');
    } catch (_) {}
    return p;
  }

  /// A card got focus: start its preview after a short rest.
  static void focus(PlexItem it, {Duration delay = const Duration(milliseconds: 500)}) {
    if (!Prefs.previews) return;
    if (_want == it.key || _timerKey == it.key) {
      _keep = false; // back on the card it came from: carry on playing here
      return;
    }
    stop();
    _timerKey = it.key;
    unawaited(_warm(it)); // look up the trailer / best scene while the card rests, so it starts sooner
    _timer = Timer(delay, () {
      _timerKey = null;
      start(it);
    });
  }

  /// A card lost focus.
  static void blur(PlexItem it) {
    if (_keep) return;
    if (_timerKey == it.key || _want == it.key) stop();
  }

  /// About to open a title (or the full-screen preview): keep its preview playing into that page.
  static void handOff() => _keep = true;

  static Future<void> togglePause() async => _player?.playOrPause();

  /// Which card the preview grows out of (and flies back into), so a title showing in two rows
  /// doesn't confuse the animation.
  static Object? heroOwner;

  /// Leaving a title page: if its card takes focus again the preview carries on playing in it;
  /// otherwise stop it.
  static void release(String key) {
    _keep = true;
    Future.delayed(const Duration(milliseconds: 450), () {
      if (_keep && wanted == key) stop();
    });
  }

  static Future<void> _warm(PlexItem it) async {
    try {
      if (it.type == 'movie' || it.type == 'show' || it.type == 'season') {
        if (!_trailers.containsKey(it.key)) _trailers[it.key] = await Plex.trailerUrl(it.key);
        if (_trailers[it.key] != null) return;
      }
      if (it.type == 'movie' || it.type == 'episode') {
        _offsets[it.key] ??= await Plex.sceneOffset(it.key, it.duration);
      }
    } catch (_) {}
  }

  static Future<void> start(PlexItem it) async {
    if (!Prefs.previews) return;
    final gen = ++_gen;
    _want = it.key;
    crop.value = null;
    _show(null);
    try {
      final src = await _source(it);
      if (gen != _gen || src == null) return;
      final p = _ensure();
      await p.setVolume(35);
      await p.open(Media(src), play: true);
      // Only show it once frames are actually moving, so the card never goes black.
      await p.stream.position
          .firstWhere((d) => d > const Duration(milliseconds: 300))
          .timeout(const Duration(seconds: 20));
      if (gen != _gen) return;
      _show(it.key);
      if (_session != null) unawaited(_probe(gen)); // a film scene (not a trailer): look for black bars
    } catch (_) {
      // no preview for this one; the poster stays
    }
  }

  static Future<String?> _source(PlexItem it) async {
    if (it.type == 'movie' || it.type == 'show' || it.type == 'season') {
      final t = _trailers.containsKey(it.key) ? _trailers[it.key] : (_trailers[it.key] = await Plex.trailerUrl(it.key));
      if (t != null) return t;
    }
    if (it.type == 'movie' || it.type == 'episode') {
      final off = _offsets[it.key] ??= await Plex.sceneOffset(it.key, it.duration);
      final s = 'reelprev${Random().nextInt(1 << 30)}';
      _session = s;
      return Plex.previewUrl(it.key, off, s);
    }
    return null;
  }

  /// Looks at a few frames and works out how much black border the picture has, then scales to fill.
  static Future<void> _probe(int gen) async {
    final r = await probeBars(_player!, cancelled: () => gen != _gen);
    if (gen != _gen) return;
    crop.value = r;
  }

  /// Samples a few frames of [p] and returns the part that isn't black bars (as fractions), or null
  /// if there are no real bars. Keeps the smallest border seen, so one dark scene never crops picture.
  static Future<Rect?> probeBars(Player p, {bool Function()? cancelled, int samples = 3}) async {
    Rect? best;
    for (var i = 0; i < samples; i++) {
      await Future.delayed(Duration(milliseconds: i == 0 ? 1200 : 1500));
      if (cancelled != null && cancelled()) return null;
      Rect? r;
      try {
        r = await measureBars(p);
      } catch (_) {}
      if (r == null) continue;
      best = best == null
          ? r
          : Rect.fromLTRB(min(best.left, r.left), min(best.top, r.top), max(best.right, r.right), max(best.bottom, r.bottom));
    }
    if (best == null) return null;
    final l = best.left < 0.02 ? 0.0 : best.left;
    final t = best.top < 0.02 ? 0.0 : best.top;
    final rr = best.right > 0.98 ? 1.0 : best.right;
    final b = best.bottom > 0.98 ? 1.0 : best.bottom;
    return (l == 0 && t == 0 && rr == 1 && b == 1) ? null : Rect.fromLTRB(l, t, rr, b);
  }

  static Future<Rect?> measureBars(Player? p) async {
    final bytes = await p?.screenshot(format: 'image/jpeg');
    if (bytes == null) return null;
    final codec = await ui.instantiateImageCodec(bytes);
    final img = (await codec.getNextFrame()).image;
    final bd = await img.toByteData(format: ui.ImageByteFormat.rawRgba);
    final w = img.width, h = img.height;
    img.dispose();
    if (bd == null || w < 32 || h < 32) return null;
    bool dark(int x, int y) {
      final o = (y * w + x) * 4;
      return (bd.getUint8(o) * 299 + bd.getUint8(o + 1) * 587 + bd.getUint8(o + 2) * 114) ~/ 1000 < 22;
    }

    bool rowDark(int y) {
      var n = 0, d = 0;
      for (var x = 0; x < w; x += 8) {
        n++;
        if (dark(x, y)) d++;
      }
      return d >= n * 0.985;
    }

    bool colDark(int x) {
      var n = 0, d = 0;
      for (var y = 0; y < h; y += 8) {
        n++;
        if (dark(x, y)) d++;
      }
      return d >= n * 0.985;
    }

    var top = 0, bottom = 0, left = 0, right = 0;
    while (top < h ~/ 3 && rowDark(top)) {
      top++;
    }
    while (bottom < h ~/ 3 && rowDark(h - 1 - bottom)) {
      bottom++;
    }
    while (left < w ~/ 3 && colDark(left)) {
      left++;
    }
    while (right < w ~/ 3 && colDark(w - 1 - right)) {
      right++;
    }
    return Rect.fromLTRB(left / w, top / h, 1 - right / w, 1 - bottom / h);
  }

  static void stop() {
    _timer?.cancel();
    _timer = null;
    _timerKey = null;
    _gen++;
    _want = null;
    crop.value = null;
    _keep = false;
    _show(null);
    _player?.stop();
    final s = _session;
    _session = null;
    if (s != null) Plex.stopTranscode(s);
  }
}

/// The preview video for [itemKey], faded in once it's playing. Shows nothing otherwise.
class PreviewVideo extends StatelessWidget {
  final String itemKey;
  final bool active;

  const PreviewVideo({super.key, required this.itemKey, this.active = true});

  /// Wrapped in a Hero so it grows out of the card into the title page / full screen.
  static Widget hero(String itemKey, {bool active = true}) =>
      Hero(tag: 'preview-$itemKey', child: PreviewVideo(itemKey: itemKey, active: active));

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<String?>(
      valueListenable: Preview.showing,
      builder: (c, k, _) {
        final on = active && k == itemKey && Preview.video != null;
        return AnimatedOpacity(
          opacity: on ? 1 : 0,
          duration: const Duration(milliseconds: 350),
          child: on
              ? _filled(Preview.video!)
              : const SizedBox.expand(),
        );
      },
    );
  }

  /// The video scaled to cover the whole area (cropping the edges), after cutting off any black bars.
  static Widget _filled(VideoController v) =>
      ValueListenableBuilder<Rect?>(valueListenable: Preview.crop, builder: (c, r, _) => cover(v, r));

  /// Scales the picture up until it covers the whole area (cropping the edges), after cutting off the
  /// black bars [r] (fractions of the picture), so a letterboxed film fills the screen.
  static Widget cover(VideoController v, Rect? r, {SubtitleViewConfiguration? subtitles}) {
    final st = v.player.state;
    final vw = (st.width ?? 0) > 0 ? st.width!.toDouble() : 1920.0;
    final vh = (st.height ?? 0) > 0 ? st.height!.toDouble() : 1080.0;
    final cr = r ?? const Rect.fromLTRB(0, 0, 1, 1);
    final wf = cr.width.clamp(0.25, 1.0).toDouble();
    final hf = cr.height.clamp(0.25, 1.0).toDouble();
    final ax = wf >= 1 ? 0.0 : (cr.left / (1 - wf)) * 2 - 1;
    final ay = hf >= 1 ? 0.0 : (cr.top / (1 - hf)) * 2 - 1;
    return ClipRect(
      child: SizedBox.expand(
        child: FittedBox(
          fit: BoxFit.cover,
          clipBehavior: Clip.hardEdge,
          child: SizedBox(
            width: vw * wf,
            height: vh * hf,
            child: ClipRect(
              child: OverflowBox(
                alignment: Alignment(ax.clamp(-1.0, 1.0).toDouble(), ay.clamp(-1.0, 1.0).toDouble()),
                minWidth: vw,
                maxWidth: vw,
                minHeight: vh,
                maxHeight: vh,
                child: Video(
                  controller: v,
                  controls: NoVideoControls,
                  fit: BoxFit.fill,
                  fill: Colors.transparent,
                  subtitleViewConfiguration: subtitles ?? const SubtitleViewConfiguration(),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
