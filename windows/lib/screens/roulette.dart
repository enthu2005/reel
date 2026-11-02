import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/material.dart';

import '../plex.dart';
import '../prefs.dart';
import '../ui/net_image.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'detail.dart';
import 'player.dart';

enum _Stage { pick, loading, spinning, reveal }

/// Movie roulette: pick a library and the reel spins through its titles, slows down and lands on a film you
/// have not watched. The pick stays hidden (the reel ends on question marks) until the reveal. Films it has
/// already landed on are remembered per library, so the next spin starts with ones it has not used.
class RouletteScreen extends StatefulWidget {
  const RouletteScreen({super.key});

  @override
  State<RouletteScreen> createState() => _RouletteScreenState();
}

class _RouletteScreenState extends State<RouletteScreen>
    with SingleTickerProviderStateMixin {
  _Stage _stage = _Stage.pick;
  List<PlexSection> _libs = [];
  PlexSection? _lib; // null = every film library
  bool _loadingLibs = true;
  String _note = '';

  String _reelText = '';
  bool _reelEnd = false;
  PlexItem? _landed;
  final _rnd = Random();
  final List<Timer> _timers = [];
  late final AnimationController _fx = AnimationController(
      vsync: this, duration: const Duration(milliseconds: 4200));
  final List<_Spark> _sparks = [];
  int _run = 0;

  @override
  void initState() {
    super.initState();
    () async {
      try {
        final secs = Plex.enabledSections(await Plex.sections())
            .where((s) => s.type == 'movie')
            .toList();
        if (mounted) {
          setState(() {
            _libs = secs;
            _loadingLibs = false;
          });
        }
      } catch (e) {
        if (mounted) {
          setState(() {
            _loadingLibs = false;
            _note = "Couldn't load your libraries: $e";
          });
        }
      }
    }();
  }

  @override
  void dispose() {
    for (final t in _timers) {
      t.cancel();
    }
    _fx.dispose();
    super.dispose();
  }

  // ------------------------------------------------------------ what it has used before

  String _scope() => 'roulette_${_lib?.key ?? 'all'}';

  Set<String> _seen() {
    try {
      final m = jsonDecode(Prefs.rouletteSeen) as Map;
      return {for (final k in (m[_scope()] as List? ?? const [])) '$k'};
    } catch (_) {
      return {};
    }
  }

  void _remember(String key) {
    Map m;
    try {
      m = jsonDecode(Prefs.rouletteSeen) as Map;
    } catch (_) {
      m = {};
    }
    final l = {...((m[_scope()] as List? ?? const []).map((e) => '$e')), key};
    m[_scope()] = l.toList();
    Prefs.rouletteSeen = jsonEncode(m);
  }

  // ------------------------------------------------------------ spin

  Future<void> _spin() async {
    final run = ++_run;
    for (final t in _timers) {
      t.cancel();
    }
    _timers.clear();
    _fx.stop();
    setState(() {
      _stage = _Stage.loading;
      _note = '';
      _landed = null;
      _reelEnd = false;
      _reelText = '';
    });
    List<PlexItem> pool = [];
    try {
      final libs = _lib == null ? _libs : [_lib!];
      for (final unwatched in [true, false]) {
        pool = [];
        for (final s in libs) {
          pool.addAll(await Plex.items(Plex.paged(
              '/library/sections/${s.key}/all?${unwatched ? 'unwatched=1&' : ''}sort=random',
              40)));
        }
        if (pool.length >= 5) break; // too few unwatched: take any film
      }
    } catch (e) {
      if (mounted && run == _run) {
        setState(() {
          _stage = _Stage.pick;
          _note = "Couldn't load films: $e";
        });
      }
      return;
    }
    if (!mounted || run != _run) return;
    final byKey = <String, PlexItem>{for (final it in pool) it.key: it};
    pool = byKey.values.toList()..shuffle(_rnd);
    if (pool.length > 40) pool = pool.sublist(0, 40);
    if (pool.length < 2) {
      setState(() {
        _stage = _Stage.pick;
        _note = 'Not enough films in that library.';
      });
      return;
    }
    final seen = _seen();
    final fresh = pool.where((f) => !seen.contains(f.key)).toList();
    final candidates = fresh.isEmpty ? pool : fresh;
    final landed = candidates[_rnd.nextInt(candidates.length)];
    _remember(landed.key);
    setState(() => _stage = _Stage.spinning);

    // Ticks slow down like a real reel: quick at first, then longer and longer gaps. The chosen film is never
    // shown on the reel: it flickers through the others and ends on question marks.
    var at = 400;
    final delays = <int>[];
    for (var d = 55.0; d < 480; d *= 1.14) {
      delays.add(d.round());
    }
    for (var i = 0; i < delays.length; i++) {
      at += delays[i];
      final last = i == delays.length - 1;
      _timers.add(Timer(Duration(milliseconds: at), () {
        if (!mounted || run != _run) return;
        var f = pool[_rnd.nextInt(pool.length)];
        for (var g = 0; f.key == landed.key && g < 20; g++) {
          f = pool[_rnd.nextInt(pool.length)];
        }
        setState(() {
          _reelText = last ? '?   ?   ?' : f.displayTitle;
          _reelEnd = last;
        });
      }));
    }
    _timers.add(Timer(Duration(milliseconds: at + 900), () {
      if (!mounted || run != _run) return;
      _sparks
        ..clear()
        ..addAll(List.generate(110, (_) => _Spark(_rnd)));
      _fx.forward(from: 0);
      setState(() {
        _landed = landed;
        _stage = _Stage.reveal;
      });
    }));
  }

  void _play() {
    final it = _landed;
    if (it == null) return;
    Navigator.of(context).push(fadeRoute(PlayerScreen(ratingKey: it.key)));
  }

  void _open() {
    final it = _landed;
    if (it == null) return;
    Navigator.of(context).push(fadeRoute(DetailScreen(ratingKey: it.key)));
  }

  // ------------------------------------------------------------ pieces

  Widget _chip(double h, String label, bool on, VoidCallback onTap,
      {bool autofocus = false}) {
    return Pressable(
      autofocus: autofocus,
      onPressed: onTap,
      scrollAlignment: 0.5,
      builder: (c, f) => AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        padding: EdgeInsets.symmetric(horizontal: h * 0.026, vertical: h * 0.012),
        decoration: BoxDecoration(
          color: f ? Colors.white : (on ? Reel.accent : const Color(0x26FFFFFF)),
          borderRadius: BorderRadius.circular(h),
        ),
        child: Text(label,
            style: reelText(h * 0.024,
                weight: FontWeight.w600,
                color: f || on ? Colors.black : Colors.white)),
      ),
    );
  }

  Widget _pickStage(double h, double w) {
    return Column(mainAxisAlignment: MainAxisAlignment.center, children: [
      Text('MOVIE ROULETTE',
          style: reelText(h * 0.06, weight: FontWeight.w800, color: Reel.accent)
              .copyWith(letterSpacing: h * 0.01)),
      SizedBox(height: h * 0.012),
      Text('Pick a library. The reel lands on a random film you have not watched.',
          style: reelText(h * 0.026, color: Reel.text2)),
      SizedBox(height: h * 0.04),
      if (_loadingLibs)
        Text('Loading…', style: reelText(h * 0.026, color: Reel.text2))
      else
        Wrap(
            alignment: WrapAlignment.center,
            spacing: h * 0.012,
            runSpacing: h * 0.012,
            children: [
              if (_libs.length > 1)
                _chip(h, 'All film libraries', _lib == null,
                    () => setState(() => _lib = null),
                    autofocus: true),
              for (var i = 0; i < _libs.length; i++)
                _chip(h, _libs[i].title, _lib?.key == _libs[i].key,
                    () => setState(() => _lib = _libs[i]),
                    autofocus: _libs.length == 1 && i == 0),
            ]),
      if (_note.isNotEmpty) ...[
        SizedBox(height: h * 0.02),
        Text(_note, style: reelText(h * 0.024, color: Reel.text2)),
      ],
      SizedBox(height: h * 0.04),
      PillButton('Spin', primary: true, onPressed: () {
        if (_libs.isNotEmpty) _spin();
      }),
    ]);
  }

  Widget _spinStage(double h, double w) {
    return Column(mainAxisAlignment: MainAxisAlignment.center, children: [
      Text('MOVIE ROULETTE',
          style: reelText(h * 0.05, weight: FontWeight.w800, color: Reel.accent)
              .copyWith(letterSpacing: h * 0.012)),
      SizedBox(height: h * 0.04),
      Container(
        width: w * 0.8,
        height: h * 0.32,
        alignment: Alignment.center,
        padding: EdgeInsets.symmetric(horizontal: w * 0.03),
        decoration: BoxDecoration(
            color: const Color(0x22FFFFFF),
            borderRadius: BorderRadius.circular(h * 0.03)),
        child: _stage == _Stage.loading
            ? Text('Loading the reel…',
                style: reelText(h * 0.04, color: Reel.text2))
            : Text(_reelText,
                maxLines: 2,
                textAlign: TextAlign.center,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.09,
                    weight: FontWeight.w700,
                    color: _reelEnd ? Reel.accent : Colors.white)),
      ),
    ]);
  }

  Widget _revealStage(double h, double w) {
    final it = _landed!;
    final poster = Plex.img(it.poster, 600, 900);
    final facts = [
      if (it.year > 0) '${it.year}',
      if (it.contentRating.isNotEmpty) it.contentRating,
      if (it.duration > 0) '${(it.duration / 60000).round()} min',
      ...it.genres.take(2),
    ].join('  ·  ');
    return Row(mainAxisAlignment: MainAxisAlignment.center, children: [
      if (poster != null)
        ClipRRect(
          borderRadius: BorderRadius.circular(h * 0.014),
          child: SizedBox(
              width: h * 0.42,
              height: h * 0.63,
              child: NetImage(poster, decodeWidth: 600)),
        ),
      SizedBox(width: w * 0.04),
      ConstrainedBox(
        constraints: BoxConstraints(maxWidth: w * 0.4),
        child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text("TONIGHT'S FILM IS…",
                  style: reelText(h * 0.026, weight: FontWeight.w700, color: Reel.accent)
                      .copyWith(letterSpacing: h * 0.006)),
              SizedBox(height: h * 0.012),
              Text(it.displayTitle,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: reelText(h * 0.06, weight: FontWeight.w800, height: 1.05)),
              SizedBox(height: h * 0.012),
              Text(facts, style: reelText(h * 0.024, color: Reel.text2)),
              if (it.summary.isNotEmpty) ...[
                SizedBox(height: h * 0.016),
                Text(it.summary,
                    maxLines: 4,
                    overflow: TextOverflow.ellipsis,
                    style: reelText(h * 0.022,
                        color: const Color(0xCCFFFFFF), height: 1.4)),
              ],
              SizedBox(height: h * 0.035),
              Wrap(spacing: h * 0.012, runSpacing: h * 0.012, children: [
                PillButton('Play',
                    icon: Icons.play_arrow_rounded,
                    primary: true,
                    autofocus: true,
                    onPressed: _play),
                PillButton('Open page', onPressed: _open),
                PillButton('Spin again', onPressed: _spin),
              ]),
            ]),
      ),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    return Scaffold(
      backgroundColor: const Color(0xFF05050A),
      body: Stack(children: [
        Positioned.fill(
          child: Padding(
            padding: EdgeInsets.symmetric(horizontal: w * 0.06, vertical: h * 0.06),
            child: switch (_stage) {
              _Stage.pick => _pickStage(h, w),
              _Stage.loading || _Stage.spinning => _spinStage(h, w),
              _Stage.reveal => _revealStage(h, w),
            },
          ),
        ),
        if (_stage == _Stage.reveal)
          Positioned.fill(
            child: IgnorePointer(
              child: AnimatedBuilder(
                animation: _fx,
                builder: (c, _) => CustomPaint(
                    painter: _Confetti(_sparks, _fx.value, h)),
              ),
            ),
          ),
      ]),
    );
  }
}

/// One piece of confetti: starts at the bottom centre, flies up and out, then falls.
class _Spark {
  final double angle, speed, spin;
  final Color color;
  _Spark(Random r)
      : angle = -pi / 2 + (r.nextDouble() - 0.5) * 1.8,
        speed = 0.6 + r.nextDouble() * 0.9,
        spin = r.nextDouble() * 6,
        color = HSVColor.fromAHSV(1, r.nextDouble() * 360, 0.7, 1).toColor();
}

class _Confetti extends CustomPainter {
  final List<_Spark> sparks;
  final double t; // 0..1 over the whole burst
  final double h;
  _Confetti(this.sparks, this.t, this.h);

  @override
  void paint(Canvas canvas, Size size) {
    if (t >= 1) return;
    final p = Paint();
    for (final s in sparks) {
      final life = t * 3.2; // seconds-ish
      final x = size.width / 2 + cos(s.angle) * s.speed * size.width * 0.45 * life;
      final y = size.height * 0.95 +
          sin(s.angle) * s.speed * size.height * 0.9 * life +
          size.height * 0.5 * life * life;
      if (y > size.height + 20) continue;
      p.color = s.color.withOpacity((1 - t).clamp(0.0, 1.0));
      canvas.save();
      canvas.translate(x, y);
      canvas.rotate(s.spin * life * 4);
      canvas.drawRect(Rect.fromCenter(center: Offset.zero, width: h * 0.014, height: h * 0.008), p);
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(_Confetti o) => o.t != t;
}
