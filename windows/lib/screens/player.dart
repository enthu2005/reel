import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import '../main.dart' as app;
import '../plex.dart';
import '../prefs.dart';
import '../ui/preview.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Plays a library item (direct play: the original file, no conversion) or any URL (trailers).
/// Controls in the style of the new Plex player.
class PlayerScreen extends StatefulWidget {
  final String? ratingKey;
  final int startMs;
  final String? url;
  final String? title;

  const PlayerScreen({super.key, this.ratingKey, this.startMs = 0, this.url, this.title});

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  late final Player _player = Player(
    configuration: const PlayerConfiguration(
      title: 'Reel',
      bufferSize: 128 * 1024 * 1024,
      libass: true, // mpv draws all subtitles itself: styled ASS and picture (PGS) ones too
    ),
  );
  // 'auto-copy' keeps hardware decoding but copies frames back, which avoids the blank picture
  // some 10-bit HDR files give with the direct (zero-copy) route.
  late final VideoController _video =
      VideoController(_player, configuration: const VideoControllerConfiguration(hwdec: 'auto-copy'));
  final _focus = FocusNode();
  final List<StreamSubscription> _subs = [];

  PlexItem? _item;
  String _status = '';
  Duration _pos = Duration.zero;
  Duration _dur = Duration.zero;
  Duration _buf = Duration.zero;
  bool _playing = false;
  bool _buffering = true;
  Tracks _tracks = const Tracks();
  Track _current = const Track();

  bool _osd = true;
  Timer? _hide;
  Duration? _scrubTo;
  Timer? _commit;
  bool _panel = false;
  int _panelTab = 0; // 0 audio, 1 subtitles, 2 picture
  int _fit = Prefs.fitMode; // 0 fit, 1 fill (crop black bars), 2 stretch
  Rect? _bars; // black bars found in the picture, for "fill"
  bool _probed = false;
  final _panelScope = FocusScopeNode();
  Timer? _report;
  bool _reportedEnd = false;

  // Up next
  List<PlexItem> _episodes = [];
  PlexItem? _next;
  bool _upNextCancelled = false;
  int _upNextLeftMs = 10000;
  DateTime? _upNextTick;

  @override
  void initState() {
    super.initState();
    Preview.stop();
    _start();
  }

  Future<void> _start() async {
    final np = _player.platform;
    if (np is NativePlayer) {
      // A big read-ahead cache for weak connections, and patient network timeouts.
      await np.setProperty('cache', 'yes');
      await np.setProperty('demuxer-max-bytes', '300MiB');
      await np.setProperty('demuxer-max-back-bytes', '50MiB');
      await np.setProperty('demuxer-readahead-secs', '300');
      await np.setProperty('tls-verify', 'no'); // your own server's certificate, by local address
      await np.setProperty('network-timeout', '60');
      // HDR / Dolby Vision shown on a normal (SDR) picture: squash the brightness range sensibly.
      await np.setProperty('tone-mapping', 'bt.2390');
      await np.setProperty('hdr-compute-peak', 'yes');
      await np.setProperty('target-colorspace-hint', 'no');
      await np.setProperty('sub-scale', ['0.8', '1.0', '1.25', '1.5'][Prefs.subSize.clamp(0, 3)]);
      // Without passthrough, mix any surround sound (7.1, DTS-HD, TrueHD) down to 2 channels here on the PC.
      if (!Prefs.passthrough) await np.setProperty('audio-channels', 'stereo');
      if (Prefs.passthrough) {
        // Send Dolby / DTS untouched to the receiver over HDMI.
        await np.setProperty('audio-spdif', [
          if (Prefs.canAc3) 'ac3',
          if (Prefs.canEac3) 'eac3',
          if (Prefs.canDts) 'dts',
          if (Prefs.canDtshd) 'dts-hd',
          if (Prefs.canTruehd) 'truehd',
        ].join(','));
        await np.setProperty('audio-exclusive', 'yes');
      }
    }
    _subs.add(_player.stream.position.listen((p) {
      setState(() => _pos = p);
      _checkUpNext();
    }));
    _subs.add(_player.stream.duration.listen((d) => setState(() => _dur = d)));
    _subs.add(_player.stream.buffer.listen((b) => setState(() => _buf = b)));
    _subs.add(_player.stream.playing.listen((p) {
      setState(() => _playing = p);
      if (p && _fit == 1 && !_probed) _probeBars();
      if (!p) _showOsd(stay: true);
    }));
    _subs.add(_player.stream.buffering.listen((b) => setState(() => _buffering = b)));
    _subs.add(_player.stream.tracks.listen((t) {
      setState(() => _tracks = t);
      if (_wantAudio >= 0) {
        final real = t.audio.where((x) => x.id != 'auto' && x.id != 'no').toList();
        final n = _item?.streamsOf(2).length ?? -1;
        if (real.length == n && _wantAudio < real.length) {
          final track = real[_wantAudio];
          _wantAudio = -1;
          _player.setAudioTrack(track);
        }
      }
    }));
    _subs.add(_player.stream.track.listen((t) => setState(() => _current = t)));
    _subs.add(_player.stream.completed.listen((done) {
      if (done) _finished();
    }));
    _subs.add(_player.stream.error.listen((e) {
      // Sound this PC can't handle (TrueHD, DTS-HD): switch to a 2-channel version from the server.
      if (!_stereo && _item != null && RegExp(r'audio|decoder|truehd|dts|spdif', caseSensitive: false).hasMatch(e)) {
        _why = e;
        _useStereo();
        return;
      }
      if (mounted) setState(() => _status = e);
    }));

    String? url = widget.url;
    if (widget.ratingKey != null) {
      try {
        final it = await Plex.metadata(widget.ratingKey!);
        _item = it;
        url = Plex.directUrl(it);
        // Sound this setup can't play (see Settings): prefer another track in the same language;
        // only if there is none, have the server convert it.
        final audio = it.streamsOf(2);
        if (audio.isNotEmpty) {
          final cur = audio.firstWhere((x) => x.selected, orElse: () => audio.first);
          if (!cur.playable) {
            var best = -1;
            for (var i = 0; i < audio.length; i++) {
              final a = audio[i];
              if (a.id == cur.id || !a.playable || a.languageCode != cur.languageCode) continue;
              if (best < 0 || a.channels > audio[best].channels) best = i;
            }
            if (best >= 0) {
              _wantAudio = best;
              _why = 'Using "${audio[best].label}" instead of ${cur.codec.toUpperCase()}, which your setup can\'t play (Settings → Sound)';
            } else {
              _stereo = true;
              _session = 'reel-${DateTime.now().millisecondsSinceEpoch}';
              url = Plex.stereoUrl(it.key, _session!);
              _why = 'No other ${cur.language.isEmpty ? '' : '${cur.language} '}track your setup can play, so the server converts the sound';
            }
          }
        }
        if (it.type == 'episode' && it.gpKey != null) {
          Plex.allEpisodes(it.gpKey!).then((e) {
            if (!mounted) return;
            _episodes = e;
            final i = e.indexWhere((x) => x.key == it.key);
            if (i >= 0 && i + 1 < e.length) setState(() => _next = e[i + 1]);
          });
        }
      } catch (e) {
        setState(() => _status = "Couldn't start: $e");
        return;
      }
    }
    if (url == null) {
      setState(() => _status = 'Nothing to play');
      return;
    }
    await _player.open(Media(url), play: true);
    if (widget.startMs > 0) {
      // Seek once the file is open and its length is known.
      _player.stream.duration.firstWhere((d) => d > Duration.zero).then((_) {
        _player.seek(Duration(milliseconds: widget.startMs));
      });
    }
    if (_item != null) {
      _report = Timer.periodic(const Duration(seconds: 10), (_) => _reportProgress());
    }
    _showOsd();
  }

  bool _stereo = false;
  int _wantAudio = -1; // index of the same-language track to switch to once the file is open
  String _why = '';
  bool _info = false;
  Map<String, String> _stats = {};

  Timer? _infoTimer;

  Future<void> _readStats() async {
    final np = _player.platform;
    if (np is! NativePlayer) return;
    final m = <String, String>{};
    for (final k in [
      'video-format', 'hwdec-current', 'video-params/w', 'video-params/h', 'video-params/gamma', 'video-params/primaries',
      'video-params/pixelformat', 'audio-codec-name', 'audio-params/samplerate', 'audio-params/channel-count',
      'container-fps', 'estimated-vf-fps', 'frame-drop-count', 'decoder-frame-drop-count', 'video-bitrate',
      'audio-bitrate', 'cache-speed', 'demuxer-cache-duration', 'paused-for-cache', 'file-format', 'file-size',
    ]) {
      try {
        m[k] = await np.getProperty(k);
      } catch (_) {}
    }
    if (mounted && _info) setState(() => _stats = m);
  }

  Future<void> _toggleInfo() async {
    _infoTimer?.cancel();
    if (_info) {
      setState(() => _info = false);
      return;
    }
    setState(() => _info = true);
    _showOsd(stay: true);
    await _readStats();
    _infoTimer = Timer.periodic(const Duration(seconds: 1), (_) => _readStats());
  }
  String? _session;

  Future<void> _useStereo() async {
    final it = _item;
    if (it == null) return;
    final at = _pos;
    _stereo = true;
    _session = 'reel-${DateTime.now().millisecondsSinceEpoch}';
    if (mounted) setState(() => _status = '');
    await _player.open(Media(Plex.stereoUrl(it.key, _session!)), play: true);
    if (at > const Duration(seconds: 3)) {
      _player.stream.duration.firstWhere((d) => d > Duration.zero).then((_) => _player.seek(at));
    }
  }

  void _reportProgress([String? state]) {
    final it = _item;
    if (it == null) return;
    Plex.timeline(it.key, state ?? (_playing ? 'playing' : 'paused'), _pos.inMilliseconds,
        _dur.inMilliseconds > 0 ? _dur.inMilliseconds : it.duration);
  }

  @override
  void dispose() {
    final it = _item;
    if (it != null && !_reportedEnd) {
      _reportProgress('stopped');
      final d = _dur.inMilliseconds > 0 ? _dur.inMilliseconds : it.duration;
      if (d > 0 && _pos.inMilliseconds > d * 0.92) Plex.setWatched(it.key, true);
    }
    _panelScope.dispose();
    _infoTimer?.cancel();
    if (_session != null) Plex.stopTranscode(_session!);
    _report?.cancel();
    _hide?.cancel();
    _commit?.cancel();
    for (final s in _subs) {
      s.cancel();
    }
    _player.dispose();
    _focus.dispose();
    super.dispose();
  }

  // ------------------------------------------------------------ controls

  void _showOsd({bool stay = false}) {
    setState(() => _osd = true);
    _hide?.cancel();
    if (!stay) {
      _hide = Timer(const Duration(milliseconds: 4500), () {
        if (mounted && _playing && _scrubTo == null && !_panel) setState(() => _osd = false);
      });
    }
  }

  void _togglePlay() {
    _player.playOrPause();
    _showOsd();
  }

  void _scrub(Duration by) {
    final from = _scrubTo ?? _pos;
    var t = from + by;
    if (t < Duration.zero) t = Duration.zero;
    if (_dur > Duration.zero && t > _dur - const Duration(seconds: 2)) t = _dur - const Duration(seconds: 2);
    setState(() => _scrubTo = t);
    _showOsd(stay: true);
    _commit?.cancel();
    // Several quick presses become one jump.
    _commit = Timer(const Duration(milliseconds: 650), _commitScrub);
  }

  void _commitScrub() {
    final t = _scrubTo;
    if (t == null) return;
    _player.seek(t);
    setState(() => _scrubTo = null);
    _showOsd();
  }

  List<int>? get _skipRange {
    final it = _item;
    if (it == null) return null;
    final p = _pos.inMilliseconds;
    for (final r in it.intros) {
      if (p >= r[0] && p < r[1] - 1000) return r;
    }
    if (!(Prefs.autoPlayNext && _next != null)) {
      for (final r in it.credits) {
        if (p >= r[0] && p < r[1] - 1000) return r;
      }
    }
    return null;
  }

  bool get _inIntro {
    final it = _item;
    if (it == null) return false;
    final p = _pos.inMilliseconds;
    return it.intros.any((r) => p >= r[0] && p < r[1] - 1000);
  }

  void _skip() {
    final r = _skipRange;
    if (r != null) _player.seek(Duration(milliseconds: r[1]));
  }

  // ------------------------------------------------------------ up next

  int get _creditsStart {
    final it = _item;
    if (it == null) return -1;
    final d = _dur.inMilliseconds > 0 ? _dur.inMilliseconds : it.duration;
    var best = -1;
    for (final r in it.credits) {
      if (r[0] > d * 0.7 && (best < 0 || r[0] < best)) best = r[0];
    }
    if (best < 0 && d > 60000) best = d - 30000;
    return best;
  }

  bool get _upNextShowing =>
      Prefs.autoPlayNext && _next != null && !_upNextCancelled && _creditsStart > 0 && _pos.inMilliseconds >= _creditsStart;

  void _checkUpNext() {
    if (!(Prefs.autoPlayNext && _next != null)) return;
    final start = _creditsStart;
    if (start < 0) return;
    if (_pos.inMilliseconds < start) {
      _upNextCancelled = false;
      _upNextLeftMs = 10000;
      _upNextTick = null;
      return;
    }
    if (_upNextCancelled) return;
    final now = DateTime.now();
    if (_upNextTick != null && _playing) _upNextLeftMs -= now.difference(_upNextTick!).inMilliseconds;
    _upNextTick = now;
    if (_upNextLeftMs <= 0) _playNext();
  }

  void _playNext() {
    final n = _next;
    final it = _item;
    if (n == null) return;
    if (it != null) {
      _reportedEnd = true;
      Plex.setWatched(it.key, true);
      Plex.timeline(it.key, 'stopped', it.duration, it.duration);
    }
    Navigator.of(context).pushReplacement(PageRouteBuilder(
      pageBuilder: (_, __, ___) => PlayerScreen(ratingKey: n.key),
      transitionDuration: const Duration(milliseconds: 300),
      transitionsBuilder: (_, a, __, child) => FadeTransition(opacity: a, child: child),
    ));
  }

  void _finished() {
    if (Prefs.autoPlayNext && _next != null && !_upNextCancelled) {
      _playNext();
      return;
    }
    final it = _item;
    if (it != null) {
      _reportedEnd = true;
      Plex.setWatched(it.key, true);
      Plex.timeline(it.key, 'stopped', it.duration, it.duration);
    }
    if (mounted) Navigator.of(context).maybePop();
  }

  // ------------------------------------------------------------ keys

  /// Opens the Audio / Subtitles / Picture panel and moves the selection into it, so the arrow keys
  /// and Enter work straight away.
  void _openPanel(int tab) {
    setState(() {
      _panel = true;
      _panelTab = tab;
    });
    _showOsd(stay: true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _panel) _panelScope.requestFocus();
    });
  }

  static const _fitNames = ['Fit to screen', 'Fill screen', 'Stretch'];

  void _setFit(int m, {bool flash = false}) {
    setState(() => _fit = m);
    Prefs.fitMode = m;
    if (m == 1) _probeBars();
    if (flash) {
      final s = _fitNames[m];
      setState(() => _status = s);
      Timer(const Duration(milliseconds: 1200), () {
        if (mounted && _status == s) setState(() => _status = '');
      });
    }
  }

  Future<void> _probeBars() async {
    _probed = true;
    final r = await Preview.probeBars(_player, cancelled: () => !mounted);
    if (mounted) setState(() => _bars = r);
  }

  /// The picture itself, shaped by the Fit / Fill / Stretch choice.
  Widget _picture() {
    const subs = SubtitleViewConfiguration(visible: false);
    if (_fit == 1) return PreviewVideo.cover(_video, _bars, subtitles: subs);
    return Video(
      controller: _video,
      controls: NoVideoControls,
      fill: Colors.black,
      fit: _fit == 2 ? BoxFit.fill : BoxFit.contain,
      subtitleViewConfiguration: subs,
    );
  }

  KeyEventResult _onKey(FocusNode n, KeyEvent e) {
    final k = e.logicalKey;
    if (_info) {
      if (e is KeyDownEvent &&
          (k == LogicalKeyboardKey.escape || k == LogicalKeyboardKey.browserBack || k == LogicalKeyboardKey.keyI ||
              k == LogicalKeyboardKey.enter || k == LogicalKeyboardKey.arrowDown)) {
        _infoTimer?.cancel();
        setState(() => _info = false);
        _showOsd();
      }
      return KeyEventResult.handled;
    }
    if (_panel) {
      if (e is KeyDownEvent && (k == LogicalKeyboardKey.escape || k == LogicalKeyboardKey.browserBack)) {
        setState(() => _panel = false);
        _focus.requestFocus();
        _showOsd();
        return KeyEventResult.handled;
      }
      return KeyEventResult.ignored; // arrows and Enter go to the panel
    }
    final down = e is KeyDownEvent || e is KeyRepeatEvent;
    if (_upNextShowing && e is KeyDownEvent) {
      if (k == LogicalKeyboardKey.enter || k == LogicalKeyboardKey.select || k == LogicalKeyboardKey.numpadEnter) {
        _playNext();
        return KeyEventResult.handled;
      }
      if (k == LogicalKeyboardKey.escape || k == LogicalKeyboardKey.browserBack) {
        setState(() => _upNextCancelled = true);
        return KeyEventResult.handled;
      }
    }
    if (k == LogicalKeyboardKey.arrowLeft || k == LogicalKeyboardKey.arrowRight ||
        k == LogicalKeyboardKey.mediaRewind || k == LogicalKeyboardKey.mediaFastForward) {
      if (!down) return KeyEventResult.handled;
      // Holding speeds up: 10 s steps, then 30 s, then 60 s.
      final held = e is KeyRepeatEvent ? (_heldSince ??= DateTime.now()) : (_heldSince = null);
      final secs = held == null
          ? 10
          : DateTime.now().difference(held).inMilliseconds < 1500
              ? 10
              : DateTime.now().difference(held).inMilliseconds < 4000
                  ? 30
                  : 60;
      final back = k == LogicalKeyboardKey.arrowLeft || k == LogicalKeyboardKey.mediaRewind;
      _scrub(Duration(seconds: back ? -secs : secs));
      return KeyEventResult.handled;
    }
    if (e is! KeyDownEvent) return KeyEventResult.ignored;
    if (k == LogicalKeyboardKey.enter || k == LogicalKeyboardKey.numpadEnter || k == LogicalKeyboardKey.select) {
      if (_scrubTo != null) {
        _commit?.cancel();
        _commitScrub();
      } else if (_skipRange != null) {
        _skip();
      } else {
        _togglePlay();
      }
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.space || k == LogicalKeyboardKey.keyK || k == LogicalKeyboardKey.mediaPlayPause) {
      _togglePlay();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.mediaPlay) {
      _player.play();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.mediaPause) {
      _player.pause();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.arrowUp || k == LogicalKeyboardKey.keyA || k == LogicalKeyboardKey.keyS) {
      _openPanel(k == LogicalKeyboardKey.keyS ? 1 : 0);
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.keyZ) {
      if (e is KeyDownEvent) _setFit((_fit + 1) % 3, flash: true);
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.keyI || (k == LogicalKeyboardKey.arrowDown && _osd && e is KeyDownEvent)) {
      if (e is KeyDownEvent) _toggleInfo();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.arrowDown) {
      _showOsd();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.keyF) {
      app.toggleFullscreen();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.keyM) {
      _player.setVolume(_player.state.volume > 0 ? 0.0 : 100.0);
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.escape && _scrubTo != null) {
      _commit?.cancel();
      setState(() => _scrubTo = null);
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  DateTime? _heldSince;

  // ------------------------------------------------------------ build

  static String _time(Duration d) {
    final s = d.inSeconds;
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    final sec = s % 60;
    return h > 0 ? '$h:${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}' : '$m:${sec.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final shown = _scrubTo ?? _pos;
    final total = _dur > Duration.zero ? _dur : Duration(milliseconds: _item?.duration ?? 0);
    final frac = total.inMilliseconds > 0 ? (shown.inMilliseconds / total.inMilliseconds).clamp(0.0, 1.0) : 0.0;
    final bufFrac = total.inMilliseconds > 0 ? (_buf.inMilliseconds / total.inMilliseconds).clamp(frac, 1.0) : 0.0;
    final it = _item;
    final title = widget.title ?? it?.displayTitle ?? '';
    final sub = it?.type == 'episode' ? 'S${it!.parentIndex} · E${it.index}  ${it.title}' : null;
    final skip = _skipRange;

    return Scaffold(
      backgroundColor: Colors.black,
      body: Focus(
        focusNode: _focus,
        autofocus: true,
        onKeyEvent: _onKey,
        child: Stack(children: [
              Positioned.fill(
                child: _picture(),
              ),
              if (_buffering && _status.isEmpty)
                const Center(child: SizedBox(width: 48, height: 48, child: CircularProgressIndicator(color: Colors.white70))),
              if (_status.isNotEmpty)
                Center(child: Text(_status, textAlign: TextAlign.center, style: reelText(h * 0.026, color: Reel.text2))),

              // Controls
              IgnorePointer(
                ignoring: !_osd,
                child: AnimatedOpacity(
                  opacity: _osd ? 1 : 0,
                  duration: const Duration(milliseconds: 250),
                  child: Stack(children: [
                    Positioned(
                      left: 0,
                      right: 0,
                      top: 0,
                      height: h * 0.28,
                      child: const DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [Color(0xB3000000), Color(0x00000000)]),
                        ),
                      ),
                    ),
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 0,
                      height: h * 0.42,
                      child: const DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [Color(0x00000000), Color(0x99000000), Color(0xE6000000)]),
                        ),
                      ),
                    ),
                    Positioned(
                      left: w * 0.04,
                      top: h * 0.05,
                      right: w * 0.3,
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(title, maxLines: 2, style: reelText(h * 0.04, weight: FontWeight.w700)),
                        if (sub != null) Text(sub, style: reelText(h * 0.026, color: const Color(0xE6FFFFFF))),
                      ]),
                    ),
                    Positioned(
                      right: w * 0.04,
                      top: h * 0.055,
                      child: Row(children: [
                        _hint(h, Icons.keyboard_arrow_up_rounded, 'Audio & subtitles'),
                        SizedBox(width: h * 0.02),
                        _hint(h, Icons.keyboard_arrow_down_rounded, 'Info'),
                      ]),
                    ),
                    // Timeline
                    Positioned(
                      left: w * 0.05,
                      right: w * 0.05,
                      bottom: h * 0.07,
                      child: LayoutBuilder(builder: (c, box) {
                        final bw = box.maxWidth;
                        final barH = (h * 0.0075).clamp(4.0, 10.0);
                        return Column(mainAxisSize: MainAxisSize.min, children: [
                          if (_scrubTo != null)
                            Align(
                              alignment: Alignment((frac * 2 - 1).clamp(-1.0, 1.0), 0),
                              child: Container(
                                margin: EdgeInsets.only(bottom: h * 0.015),
                                padding: EdgeInsets.symmetric(horizontal: h * 0.016, vertical: h * 0.005),
                                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(h)),
                                child: Text(_time(shown), style: reelText(h * 0.024, weight: FontWeight.w600, color: Colors.black)),
                              ),
                            ),
                          SizedBox(
                              height: barH * 4,
                              child: Stack(alignment: Alignment.centerLeft, children: [
                                Container(height: barH, decoration: BoxDecoration(color: const Color(0x33FFFFFF), borderRadius: BorderRadius.circular(barH))),
                                Container(width: bw * bufFrac, height: barH, decoration: BoxDecoration(color: const Color(0x99B0B0B0), borderRadius: BorderRadius.circular(barH))),
                                Container(width: bw * frac, height: barH, decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(barH))),
                                Positioned(
                                  left: (bw * frac - barH * 1.6).clamp(0.0, bw - barH * 3.2),
                                  child: AnimatedContainer(
                                    duration: const Duration(milliseconds: 120),
                                    width: barH * (_scrubTo != null ? 4.0 : 3.2),
                                    height: barH * (_scrubTo != null ? 4.0 : 3.2),
                                    decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                                  ),
                                ),
                              ]),
                            ),
                          SizedBox(height: h * 0.012),
                          Row(children: [
                            Text(_time(shown), style: reelText(h * 0.023, weight: FontWeight.w500)),
                            const Spacer(),
                            if (total > Duration.zero)
                              Text('Ends at ${TimeOfDay.fromDateTime(DateTime.now().add(total - shown)).format(context)}',
                                  style: reelText(h * 0.022, color: Reel.text2)),
                            const Spacer(),
                            Text('-${_time(total - shown < Duration.zero ? Duration.zero : total - shown)}',
                                style: reelText(h * 0.023, weight: FontWeight.w500)),
                          ]),
                        ]);
                      }),
                    ),
                  ]),
                ),
              ),

              // Big play / pause mark while paused
              if (!_playing && !_buffering && _status.isEmpty)
                Center(
                  child: Container(
                    width: h * 0.14,
                    height: h * 0.14,
                    decoration: const BoxDecoration(color: Color(0x66000000), shape: BoxShape.circle),
                    child: Icon(Icons.pause_rounded, size: h * 0.08, color: Colors.white),
                  ),
                ),

              // Skip intro / credits
              if (skip != null && !_panel)
                Positioned(
                  right: w * 0.04,
                  bottom: h * 0.22,
                  child: Container(
                    padding: EdgeInsets.symmetric(horizontal: h * 0.03, vertical: h * 0.016),
                    decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(h)),
                    child: Text(_inIntro ? 'Skip intro  ⏎' : 'Skip credits  ⏎',
                        style: reelText(h * 0.024, weight: FontWeight.w600, color: Colors.black)),
                  ),
                ),

              if (_upNextShowing && _next != null) Positioned(right: w * 0.04, bottom: h * 0.2, child: _upNextCard(h, w)),

              if (_panel) _trackPanel(h, w),
              if (_info) _infoPanel(h, w),
            ]),
      ),
    );
  }

  Widget _upNextCard(double h, double w) {
    final n = _next!;
    final img = Plex.img(n.thumb ?? n.poster, 480, 270);
    return Container(
      padding: EdgeInsets.all(h * 0.016),
      decoration: BoxDecoration(color: const Color(0xE61C1C1E), borderRadius: BorderRadius.circular(h * 0.022)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(h * 0.012),
          child: SizedBox(
            width: w * 0.13,
            height: w * 0.13 * 9 / 16,
            child: img == null ? const SizedBox() : Image.network(img, fit: BoxFit.cover),
          ),
        ),
        SizedBox(width: h * 0.025),
        ConstrainedBox(
          constraints: BoxConstraints(maxWidth: w * 0.22),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
            Text('Up Next', style: reelText(h * 0.02, weight: FontWeight.w600, color: Reel.text2)),
            Text('S${n.parentIndex}, E${n.index} · ${n.title}',
                maxLines: 2, style: reelText(h * 0.026, weight: FontWeight.w600)),
            SizedBox(height: h * 0.012),
            Container(
              padding: EdgeInsets.symmetric(horizontal: h * 0.016, vertical: h * 0.006),
              decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(h)),
              child: Text(_playing ? 'Playing in ${(_upNextLeftMs / 1000).ceil().clamp(0, 10)}' : 'Paused',
                  style: reelText(h * 0.021, weight: FontWeight.w600, color: Colors.black)),
            ),
            SizedBox(height: h * 0.008),
            Text('Enter to play now · Esc to keep watching', style: reelText(h * 0.017, color: Reel.text3)),
          ]),
        ),
      ]),
    );
  }

  // ------------------------------------------------------------ audio & subtitles

  String _audioLabel(AudioTrack t, int i) {
    final plex = _item?.streamsOf(2) ?? const <PlexStream>[];
    if (i < plex.length && plex.length == _realAudio.length) return plex[i].label;
    final parts = [t.title, t.language].whereType<String>().where((s) => s.isNotEmpty).toList();
    return parts.isEmpty ? 'Track ${t.id}' : parts.join(' · ');
  }

  String _subLabel(SubtitleTrack t, int i) {
    final plex = (_item?.streamsOf(3) ?? const <PlexStream>[]).where((s) => s.key == null).toList();
    if (i < plex.length && plex.length == _realSubs.length) return plex[i].label;
    final parts = [t.title, t.language].whereType<String>().where((s) => s.isNotEmpty).toList();
    return parts.isEmpty ? 'Track ${t.id}' : parts.join(' · ');
  }

  List<AudioTrack> get _realAudio => _tracks.audio.where((t) => t.id != 'auto' && t.id != 'no').toList();

  List<SubtitleTrack> get _realSubs => _tracks.subtitle.where((t) => t.id != 'auto' && t.id != 'no').toList();

  Widget _trackPanel(double h, double w) {
    final rows = <Widget>[];
    if (_panelTab == 0) {
      final list = _realAudio;
      for (var i = 0; i < list.length; i++) {
        final t = list[i];
        rows.add(_panelRow(_audioLabel(t, i), _current.audio.id == t.id, () => _player.setAudioTrack(t), h, first: i == 0));
      }
    } else if (_panelTab == 2) {
      for (var i = 0; i < 3; i++) {
        rows.add(_panelRow(
            i == 1 ? 'Fill screen (crops black bars and edges)' : (i == 0 ? 'Fit to screen (whole picture)' : 'Stretch'),
            _fit == i,
            () => _setFit(i),
            h,
            first: i == 0));
      }
    } else {
      rows.add(_panelRow('Off', _current.subtitle.id == 'no', () => _player.setSubtitleTrack(SubtitleTrack.no()), h,
          first: true));
      final list = _realSubs;
      for (var i = 0; i < list.length; i++) {
        final t = list[i];
        rows.add(_panelRow(_subLabel(t, i), _current.subtitle.id == t.id, () => _player.setSubtitleTrack(t), h));
      }
      // Subtitle files that sit next to the video on the server
      for (final s in (_item?.streamsOf(3) ?? const <PlexStream>[]).where((s) => s.key != null)) {
        final url = Plex.raw(s.key);
        if (url == null) continue;
        rows.add(_panelRow('${s.label} · file', _current.subtitle.id == url,
            () => _player.setSubtitleTrack(SubtitleTrack.uri(url, title: s.label, language: s.languageCode)), h));
      }
    }
    return Positioned(
      right: 0,
      top: 0,
      bottom: 0,
      width: w * 0.32,
      child: FocusScope(
        node: _panelScope,
        child: Container(
        color: const Color(0xF2141414),
        padding: EdgeInsets.fromLTRB(h * 0.03, h * 0.06, h * 0.03, h * 0.04),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            for (var i = 0; i < 3; i++)
              Padding(
                padding: EdgeInsets.only(right: h * 0.01),
                child: Pressable(
                  onPressed: () => setState(() => _panelTab = i),
                  scrollAlignment: 0,
                  builder: (c, f) => AnimatedContainer(
                    duration: const Duration(milliseconds: 150),
                    padding: EdgeInsets.symmetric(horizontal: h * 0.022, vertical: h * 0.01),
                    decoration: BoxDecoration(
                      color: f ? Colors.white : (i == _panelTab ? const Color(0x40FFFFFF) : Colors.transparent),
                      borderRadius: BorderRadius.circular(h),
                    ),
                    child: Text(i == 0 ? 'Audio' : (i == 1 ? 'Subtitles' : 'Picture'),
                        style: reelText(h * 0.024, weight: FontWeight.w600, color: f ? Colors.black : Colors.white)),
                  ),
                ),
              ),
          ]),
          SizedBox(height: h * 0.025),
          Expanded(child: ListView(children: rows)),
          Text(Prefs.passthrough ? 'Dolby / DTS go straight to your receiver (passthrough on)' : 'Sound decoded by the PC',
              style: reelText(h * 0.017, color: Reel.text3)),
        ]),
      ),
      ),
    );
  }

  Widget _hint(double h, IconData icon, String label) => Container(
        padding: EdgeInsets.symmetric(horizontal: h * 0.018, vertical: h * 0.009),
        decoration: BoxDecoration(color: const Color(0x66000000), borderRadius: BorderRadius.circular(h * 0.03)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, size: h * 0.03, color: Colors.white),
          SizedBox(width: h * 0.006),
          Text(label, style: reelText(h * 0.021, weight: FontWeight.w500)),
        ]),
      );

  Widget _infoPanel(double h, double w) {
    final it = _item;
    final v = it?.streamsOf(1);
    final a = it?.streamsOf(2) ?? [];
    final cur = a.isEmpty ? null : a.firstWhere((x) => x.selected, orElse: () => a.first);
    String num(String k) => _stats[k] ?? '';
    double? dnum(String k) => double.tryParse(num(k));
    String rate(double? bps) => bps == null || bps <= 0 ? '' : '${(bps / 1000000).toStringAsFixed(1)} Mbit/s';
    String dur(Duration d) => d.inSeconds < 90 ? '${d.inSeconds}s' : '${d.inMinutes}m ${d.inSeconds % 60}s';
    final ahead = _buf > _pos ? _buf - _pos : Duration.zero;
    final speed = dnum('cache-speed');
    final vbit = dnum('video-bitrate');
    final abit = dnum('audio-bitrate');
    final fps = dnum('estimated-vf-fps');
    final size = dnum('file-size');
    final rows = <List<String>>[
      ['Title', widget.title ?? it?.displayTitle ?? ''],
      if (it != null) ['Quality', it.badges.join('  ·  ')],
      ['Position', '${_time(_pos)} of ${_time(_dur)}'],
      ['Buffered ahead', '${dur(ahead)}${_buffering ? '   (buffering…)' : ''}'],
      if (speed != null) ['Download speed', speed > 0 ? '${(speed * 8 / 1000000).toStringAsFixed(1)} Mbit/s' : 'idle (cache full)'],
      if (rate(vbit).isNotEmpty || rate(abit).isNotEmpty)
        ['Bitrate', 'video ${rate(vbit)}   audio ${rate(abit)}'],
      if (v != null && v.isNotEmpty) ['Video', '${v.first.codec.toUpperCase()}  ${v.first.profile}  ${v.first.dovi ? 'Dolby Vision' : v.first.colorTrc}'.trim()],
      if (_stats['video-params/w'] != null)
        ['Picture', '${_stats['video-params/w']} × ${_stats['video-params/h']}  ${num('video-params/gamma')}  ${num('video-params/primaries')}  ${num('video-params/pixelformat')}'],
      if (_stats['hwdec-current'] != null) ['Decoding', _stats['hwdec-current'] == 'no' ? 'Software' : 'Hardware (${_stats['hwdec-current']})'],
      if (fps != null) ['Frames', '${fps.toStringAsFixed(1)} fps playing (file ${num('container-fps')}) · dropped ${num('frame-drop-count')} shown, ${num('decoder-frame-drop-count')} decoder'],
      if (cur != null) ['Sound (file)', cur.label],
      ['Sound (playing)', _stereo ? 'Converted to stereo by the server' : '${num('audio-codec-name')} ${num('audio-params/channel-count')}ch ${num('audio-params/samplerate')} Hz'.trim()],
      if (_why.isNotEmpty) ['Why converted', _why],
      ['Delivery', _stereo ? 'Video untouched, sound converted (HLS)' : 'Direct play of the file'],
      ['Connection', Prefs.serverKind.isEmpty ? 'unknown' : Prefs.serverKind],
      if (size != null && size > 0) ['File', '${(size / 1073741824).toStringAsFixed(1)} GB ${num('file-format')}'],
      if (it != null) ['Plex id', it.key],
    ];
    return Positioned.fill(
      child: Container(
        color: const Color(0xB3000000),
        alignment: Alignment.center,
        child: SizedBox(
          width: w * 0.55,
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Info', style: reelText(h * 0.045, weight: FontWeight.w700)),
            SizedBox(height: h * 0.03),
            for (final r in rows)
              Padding(
                padding: EdgeInsets.only(bottom: h * 0.014),
                child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  SizedBox(width: w * 0.14, child: Text(r[0], style: reelText(h * 0.024, color: Reel.text2))),
                  Expanded(child: Text(r[1], style: reelText(h * 0.024))),
                ]),
              ),
            SizedBox(height: h * 0.02),
            Text('Esc or Enter to close', style: reelText(h * 0.02, color: Reel.text3)),
          ]),
        ),
      ),
    );
  }

  Widget _panelRow(String label, bool selected, VoidCallback onTap, double h, {bool first = false}) {
    return Pressable(
      autofocus: first,
      onPressed: () {
        onTap();
        setState(() {});
      },
      scrollAlignment: 0.3,
      builder: (c, f) => AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        margin: EdgeInsets.only(bottom: h * 0.006),
        padding: EdgeInsets.symmetric(horizontal: h * 0.018, vertical: h * 0.014),
        decoration: BoxDecoration(color: f ? Colors.white : Colors.transparent, borderRadius: BorderRadius.circular(h * 0.012)),
        child: Row(children: [
          SizedBox(
            width: h * 0.035,
            child: selected ? Icon(Icons.check_rounded, size: h * 0.028, color: f ? Colors.black : Colors.white) : null,
          ),
          Expanded(
            child: Text(label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.023, weight: FontWeight.w500, color: f ? Colors.black : Colors.white)),
          ),
        ]),
      ),
    );
  }
}
