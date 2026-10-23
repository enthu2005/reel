import 'dart:async';
import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import '../log.dart';
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
  static final Map<String, PlexItem> _episodeOf = {}; // a show's first episode, for its preview

  /// What the server transcodes a scene preview to, so we know where the black bars are.
  static const double _streamAspect = 1280 / 720;

  /// The part of the picture that isn't black bars, as fractions (left, top, right, bottom), measured
  /// for film scenes so they can be scaled to fill the screen. Null = use the whole picture.
  static final ValueNotifier<Rect?> crop = ValueNotifier(null);

  /// Bumped when the video's own size becomes known, so the picture is laid out again with it.
  static final ValueNotifier<int> sizeTick = ValueNotifier(0);

  /// One loudness for every preview and trailer alike.
  static const double _level = 40;

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
    p.stream.width.listen((_) => sizeTick.value++);
    p.stream.height.listen((_) => sizeTick.value++);
    p.setPlaylistMode(PlaylistMode.single); // loop a short trailer
    // Even out the loudness as it plays, so one trailer isn't a shock after the last one, and none
    // of them jump out against the film you were watching.
    final np = p.platform;
    if (np is NativePlayer) {
      () async {
        try {
          await np.setProperty('af', 'dynaudnorm=f=200:g=15:p=0.85:m=8:r=0.9');
          await np.setProperty('volume-max', '100');
        } catch (_) {}
      }();
    }
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
      var src = it;
      if (it.type == 'show') {
        final e = _episodeOf[it.key] ?? await Plex.firstEpisode(it.key);
        if (e == null) return;
        _episodeOf[it.key] = src = e;
      }
      if (src.type == 'movie' || src.type == 'episode') {
        _offsets[src.key] ??= await Plex.sceneOffset(src.key, src.duration);
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
      Log.w('PREVIEW opening  ${it.displayTitle}');
      await p.setVolume(_level); // the same for every preview, whatever it is
      await p.open(Media(src), play: true);
      // Only show it once frames are actually moving, so the card never goes black.
      await p.stream.position
          .firstWhere((d) => d > const Duration(milliseconds: 300))
          .timeout(const Duration(seconds: 20));
      if (gen != _gen) return;
      Log.w('PREVIEW playing  ${it.displayTitle}');
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
    // A show has no video of its own: play a scene from its first episode instead.
    var src = it;
    if (it.type == 'show') {
      final e = _episodeOf[it.key] ?? await Plex.firstEpisode(it.key);
      if (e == null) return null;
      _episodeOf[it.key] = src = e;
    }
    if (src.type == 'movie' || src.type == 'episode') {
      final off = _offsets[src.key] ??= await Plex.sceneOffset(src.key, src.duration);
      final s = 'reelprev${Random().nextInt(1 << 30)}';
      _session = s;
      // The server letterboxes the scene into a 16:9 stream, so cut those bars off straight away.
      crop.value = barsFor(src.aspect > 0 ? src.aspect : it.aspect, _streamAspect);
      return Plex.previewUrl(src.key, off, s);
    }
    return null;
  }

  /// Where the real picture sits inside a stream that letterboxes a [content]-shaped film into a
  /// [stream]-shaped video. Null when they match (or the shape isn't known).
  static Rect? barsFor(double content, double stream) {
    if (content <= 0 || stream <= 0) return null;
    if ((content - stream).abs() < 0.03) return null;
    if (content > stream) {
      final f = stream / content; // bars top and bottom
      return Rect.fromLTRB(0, (1 - f) / 2, 1, 1 - (1 - f) / 2);
    }
    final f = content / stream; // bars left and right
    return Rect.fromLTRB((1 - f) / 2, 0, 1 - (1 - f) / 2, 1);
  }

  /// Looks at a few frames and works out how much black border the picture has, then scales to fill.
  static Future<void> _probe(int gen) async {
    final r = await probeBars(_player!, cancelled: () => gen != _gen);
    if (gen != _gen || r == null) return; // nothing found: keep what the film's shape told us
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
    if (_want != null) Log.w('PREVIEW stopped');
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
  static Widget _filled(VideoController v) => AnimatedBuilder(
        animation: Listenable.merge([Preview.crop, Preview.sizeTick]),
        builder: (c, _) => cover(v, Preview.crop.value),
      );

  /// Fills the whole area with the picture, cropping what doesn't fit. [r] is the part of the video
  /// that is real picture (the rest being black bars the stream has baked in): it is blown up until
  /// that part covers the area, so a letterboxed film fills the screen instead of sitting in a box.
  static Widget cover(VideoController v, Rect? r, {SubtitleViewConfiguration? subtitles}) {
    final subs = subtitles ?? const SubtitleViewConfiguration();
    Widget plain(BoxFit fit) => Video(
          controller: v,
          controls: NoVideoControls,
          fit: fit,
          fill: Colors.transparent,
          subtitleViewConfiguration: subs,
        );
    // Always laid out by hand: left to itself the video sometimes sits at its own size in a corner
    // instead of filling the space it was given.
    final cr0 = r ?? const Rect.fromLTRB(0, 0, 1, 1);
    final st = v.player.state;
    final vw = (st.width ?? 0) > 0 ? st.width!.toDouble() : 1920.0;
    final vh = (st.height ?? 0) > 0 ? st.height!.toDouble() : 1080.0;
    return ClipRect(
      child: LayoutBuilder(
        builder: (c, box) {
          final w = box.maxWidth, h = box.maxHeight;
          if (!w.isFinite || !h.isFinite || w <= 0 || h <= 0) return plain(BoxFit.cover);
          // how much to blow the whole frame up so the real picture covers the area
          final s = max(w / (vw * cr0.width), h / (vh * cr0.height));
          final fw = vw * s, fh = vh * s;
          return SizedBox(
            width: w,
            height: h,
            child: Stack(clipBehavior: Clip.hardEdge, children: [
              Positioned(
                left: w / 2 - (cr0.left + cr0.width / 2) * fw,
                top: h / 2 - (cr0.top + cr0.height / 2) * fh,
                width: fw,
                height: fh,
                child: plain(BoxFit.fill),
              ),
            ]),
          );
        },
      ),
    );
  }
}
