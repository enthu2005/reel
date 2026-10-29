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
import 'live_edit.dart';
import 'player.dart';


String _clock(DateTime t) {
  final h = t.hour % 12 == 0 ? 12 : t.hour % 12;
  return '$h:${t.minute.toString().padLeft(2, '0')} ${t.hour < 12 ? 'AM' : 'PM'}';
}

/// Opens a channel in the player: joins the programme that is on, part-way through.
Future<void> tuneChannel(BuildContext context, LiveChannel c,
    {bool replace = false}) async {
  final t = Live.tuneIn(c);
  if (t == null) return;
  final page = PlayerScreen(
      ratingKey: t.$1.key, startMs: t.$2, channel: c.num);
  if (replace) {
    await Navigator.of(context).pushReplacement(PageRouteBuilder(
      pageBuilder: (_, __, ___) => page,
      transitionDuration: const Duration(milliseconds: 260),
      transitionsBuilder: (_, a, __, child) =>
          FadeTransition(opacity: a, child: child),
    ));
  } else {
    await Navigator.of(context).push(fadeRoute(page));
  }
}

/// Live: a TV guide of channels made from your libraries. Click any programme to tune the channel.
class LiveScreen extends StatefulWidget {
  const LiveScreen({super.key});

  @override
  State<LiveScreen> createState() => _LiveScreenState();
}

class _LiveScreenState extends State<LiveScreen> {
  static const _windowMs = 3 * 3600000;
  static const _halfHour = 30 * 60000;

  List<LiveChannel> _channels = [];
  String _message = 'Setting up your channels…';
  Timer? _tick;
  int _t0 = 0;
  DateTime _now = DateTime.now();

  LiveChannel? _dCh;
  LiveSlot? _dSlot;

  // Up / Down move to what is on air NOW in the next channel, not to whichever later programme happens to
  // sit nearest: one focus node per channel's "on now" block, and the key is steered to it.
  final Map<String, FocusNode> _slotNodes = {}; // by "row_start" of the programme
  final Map<int, String> _nowId = {}; // row -> the programme on air now
  final ScrollController _scroll = ScrollController();
  int _focusRow = 0;
  bool _inGrid = false;
  double _rowH = 80;

  FocusNode _slotNode(String id) => _slotNodes.putIfAbsent(id, () => FocusNode());

  FocusNode? _nowNodeOf(int row) {
    final id = _nowId[row];
    return id == null ? null : _slotNodes[id];
  }

  void _focusNow(int row) {
    _focusRow = row;
    final n = _nowNodeOf(row);
    if (n != null && n.context != null) {
      n.requestFocus();
      return;
    }
    // that row is not built yet: bring it into view, then focus it
    if (_scroll.hasClients) {
      final max = _scroll.position.maxScrollExtent;
      _scroll.jumpTo((row * _rowH - _rowH * 3).clamp(0.0, max));
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _nowNodeOf(row)?.requestFocus();
    });
  }

  KeyEventResult _guideKey(FocusNode node, KeyEvent e) {
    if (e is KeyUpEvent || !_inGrid || _channels.isEmpty) {
      return KeyEventResult.ignored;
    }
    final k = e.logicalKey;
    if (k == LogicalKeyboardKey.arrowDown) {
      if (_focusRow < _channels.length - 1) _focusNow(_focusRow + 1);
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.arrowUp) {
      if (_focusRow == 0) return KeyEventResult.ignored; // on to the header buttons
      _focusNow(_focusRow - 1);
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  // The little picture-in-picture: what is on the highlighted channel right now, muted, like Sky's mini TV
  Player? _pipPlayer;
  VideoController? _pipVideo;
  Timer? _pipDelay;
  int _pipChannel = 0;
  bool _pipShowing = false;
  int _pipToken = 0;
  String _pipSession = '';

  bool get _pipAllowed => Prefs.previews && !Prefs.quiet;

  void _pipTo(LiveChannel c) {
    if (!_pipAllowed || c.num == _pipChannel) return;
    _pipDelay?.cancel();
    _pipDelay = Timer(const Duration(milliseconds: 700), () => _pipStart(c));
  }

  Future<void> _pipStart(LiveChannel c) async {
    final t = Live.tuneIn(c);
    if (t == null || !mounted) return;
    final mine = ++_pipToken;
    try {
      // the light 720p stream from the server (it starts at the right minute), not the whole original file
      final session = 'pip${DateTime.now().millisecondsSinceEpoch}';
      final url = Plex.previewUrl(t.$1.key, t.$2, session);
      if (_pipSession.isNotEmpty) Plex.stopTranscode(_pipSession);
      _pipSession = session;
      if (!mounted || mine != _pipToken) return;
      if (_pipPlayer == null) {
        final p = Player(
            configuration: const PlayerConfiguration(
                title: 'Reel', bufferSize: 32 * 1024 * 1024));
        p.stream.error.listen((e) {
          if (RegExp(r'avhw|hwdec|hw device|hardware|d3d11|dxva|cuda|vaapi',
                  caseSensitive: false)
              .hasMatch(e)) {
            final np = p.platform;
            if (np is NativePlayer) np.setProperty('hwdec', 'no');
          }
        });
        await p.setVolume(0);
        _pipPlayer = p;
        _pipVideo = VideoController(p,
            configuration:
                const VideoControllerConfiguration(hwdec: 'auto-copy'));
      }
      _pipChannel = c.num;
      await _pipPlayer!.open(
          Media(url),
          play: true);
      if (mounted && mine == _pipToken) setState(() => _pipShowing = true);
    } catch (_) {}
  }

  Future<void> _tune(LiveChannel c) async {
    _pipToken++;
    _pipDelay?.cancel();
    // let go of the little picture completely: two streams from the server at once can stall the real one
    final p = _pipPlayer;
    _pipPlayer = null;
    _pipVideo = null;
    _pipShowing = false;
    _pipChannel = 0;
    await p?.dispose();
    if (_pipSession.isNotEmpty) Plex.stopTranscode(_pipSession);
    _pipSession = '';
    if (!mounted) return;
    setState(() {});
    await tuneChannel(context, c);
    // back from watching: the programme has moved on, so pick the picture up again
    if (mounted && _dCh != null) {
      _pipChannel = 0;
      _pipStart(_dCh!);
    }
  }

  static int _windowStart() {
    final n = DateTime.now().millisecondsSinceEpoch;
    return n - n % _halfHour;
  }

  @override
  void initState() {
    super.initState();
    _t0 = _windowStart();
    Live.load().then((r) {
      if (!mounted) return;
      setState(() {
        _channels = r;
        _message = r.isEmpty
            ? 'No channels yet. They fill up from the genres in your libraries.'
            : '';
      });
    }).catchError((e) {
      if (mounted) setState(() => _message = "Couldn't build the guide: $e");
    });
    _tick = Timer.periodic(const Duration(seconds: 20), (_) {
      if (!mounted) return;
      setState(() {
        _now = DateTime.now();
        _t0 = _windowStart();
      });
    });
  }

  @override
  void dispose() {
    for (final n in _slotNodes.values) {
      n.dispose();
    }
    _scroll.dispose();
    _pipDelay?.cancel();
    _pipPlayer?.dispose();
    if (_pipSession.isNotEmpty) Plex.stopTranscode(_pipSession);
    _tick?.cancel();
    super.dispose();
  }

  Widget _detail(double h) {
    final c = _dCh;
    final s = _dSlot;
    if (c == null || s == null) return const SizedBox();
    final now = DateTime.now();
    final live = !s.start.isAfter(now) && now.isBefore(s.end);
    final mins = s.prog.durMs ~/ 60000;
    final dur = mins >= 60 ? '${mins ~/ 60} h ${mins % 60} min' : '$mins min';
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('CH ${c.num}  ·  ${c.name}${live ? '   ·   ON NOW' : ''}',
          style: reelText(h * 0.022,
              weight: FontWeight.w600, color: Reel.accent)),
      Text(s.prog.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: reelText(h * 0.042, weight: FontWeight.w700)),
      Text(s.prog.sub,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: reelText(h * 0.026, color: const Color(0xE6FFFFFF))),
      Text(
          '${_clock(s.start)} – ${_clock(s.end)}   ·   $dur'
          '${live ? '   ·   ${(s.end.difference(now).inMinutes).clamp(1, 9999)} min left' : ''}',
          style: reelText(h * 0.023, color: Reel.text2)),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final nameW = w * 0.12;
    final rowH = h * 0.088;
    _rowH = rowH;
    final gap = h * 0.004;
    final t0 = DateTime.fromMillisecondsSinceEpoch(_t0);
    final t1 = DateTime.fromMillisecondsSinceEpoch(_t0 + _windowMs);
    final nowMs = _now.millisecondsSinceEpoch;
    return Scaffold(
      body: Focus(
        onKeyEvent: _guideKey,
        skipTraversal: true,
        child: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.05, h * 0.07, w * 0.05, h * 0.03),
        child: LayoutBuilder(builder: (context, box) {
          final gridW = box.maxWidth - nameW;
          final ppm = gridW / _windowMs;
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(
                  child: Text('Live',
                      style: reelText(h * 0.05, weight: FontWeight.w700))),
              PillButton('Edit channels',
                  onFocusChange: (f) {
                    if (f) _inGrid = false;
                  },
                  onPressed: () async {
                _pipToken++;
                _pipDelay?.cancel();
                await Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const LiveEditScreen()));
                if (!mounted) return;
                // the lineup may have changed
                final r = Live.cached ?? await Live.load();
                if (mounted) setState(() => _channels = r);
              }),
              SizedBox(width: w * 0.02),
              Text(_clock(_now),
                  style: reelText(h * 0.034, color: Reel.text2)),
            ]),
            SizedBox(
                height: h * 0.2,
                child: Padding(
                    padding: EdgeInsets.only(top: h * 0.018),
                    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Expanded(child: _detail(h)),
                      if (_pipShowing && _pipVideo != null && _pipAllowed)
                        Padding(
                          padding: EdgeInsets.only(left: w * 0.02),
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(h * 0.012),
                            child: Container(
                              width: h * 0.2 * 16 / 9,
                              height: h * 0.2,
                              color: Colors.black,
                              child: Stack(fit: StackFit.expand, children: [
                                Video(
                                    controller: _pipVideo!,
                                    controls: NoVideoControls,
                                    fill: Colors.black),
                                Positioned(
                                  left: h * 0.008,
                                  top: h * 0.008,
                                  child: Container(
                                    padding: EdgeInsets.symmetric(
                                        horizontal: h * 0.008,
                                        vertical: h * 0.002),
                                    decoration: BoxDecoration(
                                        color: Reel.accent,
                                        borderRadius:
                                            BorderRadius.circular(h * 0.006)),
                                    child: Text('CH $_pipChannel',
                                        style: reelText(h * 0.016,
                                            weight: FontWeight.w700,
                                            color: Colors.black)),
                                  ),
                                ),
                              ]),
                            ),
                          ),
                        ),
                    ]))),
            SizedBox(
              height: h * 0.045,
              child: Stack(children: [
                for (var t = _t0; t < _t0 + _windowMs; t += _halfHour)
                  Positioned(
                    left: nameW + (t - _t0) * ppm,
                    top: 0,
                    bottom: 0,
                    child: Center(
                        child: Text(
                            _clock(DateTime.fromMillisecondsSinceEpoch(t)),
                            style: reelText(h * 0.022, color: Reel.text2))),
                  ),
                Positioned(
                  left: nameW + (nowMs - _t0) * ppm,
                  top: 0,
                  bottom: 0,
                  width: 2,
                  child: ColoredBox(color: Reel.accent),
                ),
              ]),
            ),
            if (_message.isNotEmpty)
              Padding(
                  padding: EdgeInsets.only(top: h * 0.05),
                  child: Text(_message,
                      style: reelText(h * 0.032, color: Reel.text2))),
            Expanded(
              child: ListView.builder(
                controller: _scroll,
                clipBehavior: Clip.hardEdge,
                itemCount: _channels.length,
                itemExtent: rowH,
                itemBuilder: (c, ci) {
                  final ch = _channels[ci];
                  final slots = Live.window(ch, t0, t1);
                  return Row(children: [
                    SizedBox(
                      width: nameW,
                      child: Padding(
                        padding: EdgeInsets.only(left: w * 0.006, right: w * 0.01),
                        child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('${ch.num}',
                                  style: reelText(h * 0.032,
                                      weight: FontWeight.w600,
                                      color: Reel.accent)),
                              Text(ch.name,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: reelText(h * 0.022)),
                            ]),
                      ),
                    ),
                    SizedBox(
                      width: gridW,
                      child: Stack(children: [
                        for (final s in slots)
                          () {
                            final a = s.start.millisecondsSinceEpoch < _t0
                                ? _t0
                                : s.start.millisecondsSinceEpoch;
                            final b = s.end.millisecondsSinceEpoch > _t0 + _windowMs
                                ? _t0 + _windowMs
                                : s.end.millisecondsSinceEpoch;
                            final live = s.start.millisecondsSinceEpoch <= nowMs &&
                                nowMs < s.end.millisecondsSinceEpoch;
                            final sid = '${ci}_${s.start.millisecondsSinceEpoch}';
                            if (live) _nowId[ci] = sid;
                            final bw = ((b - a) * ppm - gap * 2).clamp(1.0, gridW);
                            return Positioned(
                              left: (a - _t0) * ppm + gap,
                              top: gap,
                              width: bw,
                              height: rowH - gap * 2,
                              child: Pressable(
                                key: ValueKey(sid),
                                focusNode: _slotNode(sid),
                                autofocus: ci == 0 && live,
                                scrollAlignment: 0.5,
                                onFocusChange: (f) {
                                  if (f) {
                                    _inGrid = true;
                                    _focusRow = ci;
                                    setState(() {
                                      _dCh = ch;
                                      _dSlot = s;
                                    });
                                    _pipTo(ch);
                                  }
                                },
                                onPressed: () => _tune(ch),
                                builder: (c, f) => AnimatedContainer(
                                  duration: const Duration(milliseconds: 120),
                                  clipBehavior: Clip.hardEdge,
                                  alignment: Alignment.centerLeft,
                                  padding: EdgeInsets.symmetric(
                                      horizontal: w * 0.007),
                                  decoration: BoxDecoration(
                                    color: f
                                        ? Reel.accent
                                        : (live
                                            ? const Color(0xFF2B2B30)
                                            : Reel.card),
                                    borderRadius:
                                        BorderRadius.circular(h * 0.01),
                                  ),
                                  child: Text(
                                      s.prog.title +
                                          (s.prog.sub.isEmpty
                                              ? ''
                                              : '\n${s.prog.sub}'),
                                      maxLines: 2,
                                      overflow: TextOverflow.ellipsis,
                                      style: reelText(h * 0.022,
                                          weight: FontWeight.w500,
                                          color:
                                              f ? Colors.black : Colors.white)),
                                ),
                              ),
                            );
                          }(),
                      ]),
                    ),
                  ]);
                },
              ),
            ),
          ]);
        }),
        ),
      ),
    );
  }
}
