import 'dart:async';

import 'package:flutter/material.dart';

import '../live.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'player.dart';

const _liveAccent = Color(0xFFE8833A);

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
              weight: FontWeight.w600, color: _liveAccent)),
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
    final gap = h * 0.004;
    final t0 = DateTime.fromMillisecondsSinceEpoch(_t0);
    final t1 = DateTime.fromMillisecondsSinceEpoch(_t0 + _windowMs);
    final nowMs = _now.millisecondsSinceEpoch;
    return Scaffold(
      body: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.05, h * 0.07, w * 0.05, h * 0.03),
        child: LayoutBuilder(builder: (context, box) {
          final gridW = box.maxWidth - nameW;
          final ppm = gridW / _windowMs;
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(
                  child: Text('Live',
                      style: reelText(h * 0.05, weight: FontWeight.w700))),
              Text(_clock(_now),
                  style: reelText(h * 0.034, color: Reel.text2)),
            ]),
            SizedBox(
                height: h * 0.2,
                child: Padding(
                    padding: EdgeInsets.only(top: h * 0.018),
                    child: _detail(h))),
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
                  child: const ColoredBox(color: _liveAccent),
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
                                      color: _liveAccent)),
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
                            final bw = ((b - a) * ppm - gap * 2).clamp(1.0, gridW);
                            return Positioned(
                              left: (a - _t0) * ppm + gap,
                              top: gap,
                              width: bw,
                              height: rowH - gap * 2,
                              child: Pressable(
                                autofocus: ci == 0 && live,
                                scrollAlignment: 0.5,
                                onFocusChange: (f) {
                                  if (f) {
                                    setState(() {
                                      _dCh = ch;
                                      _dSlot = s;
                                    });
                                  }
                                },
                                onPressed: () => tuneChannel(context, ch),
                                builder: (c, f) => AnimatedContainer(
                                  duration: const Duration(milliseconds: 120),
                                  clipBehavior: Clip.hardEdge,
                                  alignment: Alignment.centerLeft,
                                  padding: EdgeInsets.symmetric(
                                      horizontal: w * 0.007),
                                  decoration: BoxDecoration(
                                    color: f
                                        ? _liveAccent
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
    );
  }
}
