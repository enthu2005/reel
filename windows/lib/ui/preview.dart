import 'dart:async';
import 'dart:math';

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
    return p;
  }

  /// A card got focus: start its preview after a short rest.
  static void focus(PlexItem it, {Duration delay = const Duration(milliseconds: 900)}) {
    if (!Prefs.previews) return;
    if (_want == it.key || _timerKey == it.key) {
      _keep = false; // back on the card it came from: carry on playing here
      return;
    }
    stop();
    _timerKey = it.key;
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

  static Future<void> start(PlexItem it) async {
    if (!Prefs.previews) return;
    final gen = ++_gen;
    _want = it.key;
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

  static void stop() {
    _timer?.cancel();
    _timer = null;
    _timerKey = null;
    _gen++;
    _want = null;
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

  /// Scales the picture up until it covers the whole area (cropping the edges), whatever shape the
  /// video is, so a letterboxed film or a small stream never leaves the background smaller.
  static Widget _filled(VideoController v) {
    final st = v.player.state;
    final vw = (st.width ?? 0) > 0 ? st.width!.toDouble() : 1920.0;
    final vh = (st.height ?? 0) > 0 ? st.height!.toDouble() : 1080.0;
    return ClipRect(
      child: SizedBox.expand(
        child: FittedBox(
          fit: BoxFit.cover,
          clipBehavior: Clip.hardEdge,
          child: SizedBox(
            width: vw,
            height: vh,
            child: Video(
              controller: v,
              controls: NoVideoControls,
              fit: BoxFit.fill,
              fill: Colors.transparent,
            ),
          ),
        ),
      ),
    );
  }
}
