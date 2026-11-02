import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import '../live.dart';
import '../plex.dart';
import '../prefs.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'live.dart' show tuneChannel;

/// On to [c]'s next programme, after a break of trailers if they are switched on.
void trailerBreakThenTune(BuildContext context, LiveChannel c) {
  if (!Prefs.liveTrailers) {
    tuneChannel(context, c, replace: true);
    return;
  }
  Navigator.of(context).pushReplacement(PageRouteBuilder(
    pageBuilder: (_, __, ___) => TrailerBreakScreen(channel: c.num),
    transitionDuration: const Duration(milliseconds: 260),
    transitionsBuilder: (_, a, __, child) =>
        FadeTransition(opacity: a, child: child),
  ));
}

/// Between two programmes on a Live channel: up to three trailers of what is coming up, like a break on real
/// TV. "Skip trailer" moves to the next one, "Skip all" (or Esc) goes straight on to the channel.
class TrailerBreakScreen extends StatefulWidget {
  final int channel;
  const TrailerBreakScreen({super.key, required this.channel});

  @override
  State<TrailerBreakScreen> createState() => _TrailerBreakScreenState();
}

class _TrailerBreakScreenState extends State<TrailerBreakScreen> {
  static const _count = 3;
  late final Player _player = Player(
      configuration:
          const PlayerConfiguration(title: 'Reel', bufferSize: 32 * 1024 * 1024));
  late final VideoController _video = VideoController(_player,
      configuration: const VideoControllerConfiguration(hwdec: 'auto-copy'));
  final _skipOne = FocusNode();
  final List<String> _titles = [];
  final List<StreamSubscription> _subs = [];
  int _at = 0;
  bool _left = false;

  @override
  void initState() {
    super.initState();
    _find();
  }

  /// What is coming up on the channel, each as a trailer if Plex has one (up to three).
  Future<void> _find() async {
    final urls = <String>[];
    try {
      final ch = Live.byNum(widget.channel);
      if (ch != null) {
        final now = DateTime.now();
        final seen = <String>{};
        for (final s in Live.window(ch, now, now.add(const Duration(hours: 10)))) {
          if (urls.length >= _count) break;
          try {
            final m = await Plex.metadata(s.prog.key);
            final k = m.type == 'episode' && m.gpKey != null ? m.gpKey! : m.key;
            if (!seen.add(k)) continue;
            final url = await Plex.trailerUrl(k);
            if (url != null) {
              urls.add(url);
              _titles.add(m.type == 'episode' ? (m.gpTitle ?? m.title) : m.displayTitle);
            }
          } catch (_) {} // no trailer for that one: try the next
        }
      }
    } catch (_) {}
    if (!mounted || _left) return;
    if (urls.isEmpty) {
      _goOn();
      return;
    }
    _subs.add(_player.stream.playlist.listen((p) {
      if (mounted) setState(() => _at = p.index);
    }));
    _subs.add(_player.stream.completed.listen((done) {
      if (done) _goOn();
    }));
    _subs.add(_player.stream.error.listen((e) {
      if (RegExp(r'avhw|hwdec|hw device|hardware|d3d11|dxva|cuda|vaapi',
              caseSensitive: false)
          .hasMatch(e)) {
        final np = _player.platform;
        if (np is NativePlayer) np.setProperty('hwdec', 'no');
        return;
      }
      _next(); // a trailer that will not play is skipped
    }));
    await _player.open(Playlist([for (final u in urls) Media(u)]), play: true);
    if (mounted) setState(() {});
  }

  void _next() {
    if (_at + 1 < _titles.length) {
      _player.next();
    } else {
      _goOn();
    }
  }

  /// The break is over (or skipped): on to the channel, joining it where the clock has got to.
  void _goOn() {
    if (_left || !mounted) return;
    _left = true;
    final c = Live.byNum(widget.channel);
    if (c != null) {
      tuneChannel(context, c, replace: true);
    } else {
      Navigator.of(context).maybePop();
    }
  }

  @override
  void dispose() {
    _left = true;
    for (final s in _subs) {
      s.cancel();
    }
    _skipOne.dispose();
    _player.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final last = _at + 1 >= _titles.length;
    return Focus(
      autofocus: true,
      onKeyEvent: (n, e) {
        if (e is KeyDownEvent &&
            (e.logicalKey == LogicalKeyboardKey.escape ||
                e.logicalKey == LogicalKeyboardKey.browserBack)) {
          _goOn();
          return KeyEventResult.handled;
        }
        return KeyEventResult.ignored;
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        body: Stack(children: [
          Positioned.fill(
              child: Video(
                  controller: _video,
                  controls: NoVideoControls,
                  fill: Colors.black)),
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: Container(
              color: const Color(0xB0000000),
              padding: EdgeInsets.symmetric(
                  horizontal: w * 0.04, vertical: h * 0.02),
              child: Row(children: [
                Expanded(
                  child: Text(
                      _titles.isEmpty
                          ? 'Coming up…'
                          : 'Coming up   ${_at + 1} of ${_titles.length}   ·   ${_at < _titles.length ? _titles[_at] : ''}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: reelText(h * 0.03, weight: FontWeight.w600)),
                ),
                PillButton(last ? 'Skip last trailer' : 'Skip trailer',
                    focusNode: _skipOne, autofocus: true, onPressed: _next),
                SizedBox(width: w * 0.012),
                PillButton('Skip all', onPressed: _goOn),
              ]),
            ),
          ),
        ]),
      ),
    );
  }
}
