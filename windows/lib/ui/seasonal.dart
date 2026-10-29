import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../prefs.dart';

/// 0 none, 1 Christmas, 2 Halloween. Changing it (Settings -> Theme) restyles the app straight away.
final ValueNotifier<int> seasonTheme = ValueNotifier(Prefs.theme);

String seasonName(int t) => switch (t) {
      1 => 'Christmas',
      2 => 'Halloween',
      _ => 'Off',
    };

/// A gentle seasonal layer above every page: snow and a warm red/green glow for Christmas, rising embers and
/// a purple/orange glow for Halloween. It never takes clicks, and it sleeps while a film is playing.
class SeasonalOverlay extends StatefulWidget {
  /// True while the player is on screen: nothing is drawn or animated then.
  final ValueListenable<bool> paused;
  const SeasonalOverlay({super.key, required this.paused});

  @override
  State<SeasonalOverlay> createState() => _SeasonalOverlayState();
}

class _Flake {
  double x, y, r, speed, drift, phase;
  _Flake(this.x, this.y, this.r, this.speed, this.drift, this.phase);
}

class _SeasonalOverlayState extends State<SeasonalOverlay>
    with SingleTickerProviderStateMixin {
  late final Ticker _ticker = createTicker(_tick);
  final _rnd = math.Random();
  final List<_Flake> _flakes = [];
  final ValueNotifier<int> _frame = ValueNotifier(0);
  Duration _last = Duration.zero;

  bool get _on => seasonTheme.value != 0 && !widget.paused.value;

  @override
  void initState() {
    super.initState();
    seasonTheme.addListener(_sync);
    widget.paused.addListener(_sync);
    _sync();
  }

  @override
  void dispose() {
    seasonTheme.removeListener(_sync);
    widget.paused.removeListener(_sync);
    _ticker.dispose();
    _frame.dispose();
    super.dispose();
  }

  void _sync() {
    _flakes.clear();
    if (_on) {
      final n = seasonTheme.value == 1 ? 70 : 38;
      for (var i = 0; i < n; i++) {
        _flakes.add(_spawn(anywhere: true));
      }
      _last = Duration.zero;
      if (!_ticker.isActive) _ticker.start();
    } else if (_ticker.isActive) {
      _ticker.stop();
    }
    if (mounted) setState(() {});
  }

  _Flake _spawn({bool anywhere = false}) {
    final xmas = seasonTheme.value == 1;
    return _Flake(
      _rnd.nextDouble(),
      anywhere ? _rnd.nextDouble() : (xmas ? -0.03 : 1.03),
      xmas ? 1.5 + _rnd.nextDouble() * 3.0 : 1.2 + _rnd.nextDouble() * 2.4,
      (xmas ? 0.035 : 0.045) + _rnd.nextDouble() * 0.05, // screen heights per second
      0.01 + _rnd.nextDouble() * 0.025,
      _rnd.nextDouble() * 6.28,
    );
  }

  void _tick(Duration t) {
    final dt = _last == Duration.zero
        ? 0.016
        : (t - _last).inMicroseconds / 1e6;
    _last = t;
    final xmas = seasonTheme.value == 1;
    for (var i = 0; i < _flakes.length; i++) {
      final f = _flakes[i];
      f.y += (xmas ? 1 : -1) * f.speed * dt;
      f.phase += dt * 1.2;
      f.x += math.sin(f.phase) * f.drift * dt;
      if (xmas ? f.y > 1.03 : f.y < -0.03) _flakes[i] = _spawn();
    }
    _frame.value++;
  }

  @override
  Widget build(BuildContext context) {
    if (!_on) return const SizedBox.shrink();
    return IgnorePointer(
      child: RepaintBoundary(
        child: CustomPaint(
          size: Size.infinite,
          painter: _SeasonPainter(seasonTheme.value, _flakes, _frame),
        ),
      ),
    );
  }
}

class _SeasonPainter extends CustomPainter {
  final int theme;
  final List<_Flake> flakes;
  _SeasonPainter(this.theme, this.flakes, Listenable repaint)
      : super(repaint: repaint);

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final xmas = theme == 1;
    // soft coloured glow from the bottom corners
    final left = xmas ? const Color(0x33D42426) : const Color(0x44FF7518);
    final right = xmas ? const Color(0x331F8A4C) : const Color(0x447B2FBE);
    canvas.drawRect(
        rect,
        Paint()
          ..shader = RadialGradient(
                  center: const Alignment(-1.1, 1.2),
                  radius: 1.0,
                  colors: [left, const Color(0x00000000)])
              .createShader(rect));
    canvas.drawRect(
        rect,
        Paint()
          ..shader = RadialGradient(
                  center: const Alignment(1.1, 1.2),
                  radius: 1.0,
                  colors: [right, const Color(0x00000000)])
              .createShader(rect));
    final p = Paint();
    for (final f in flakes) {
      final c = Offset(f.x * size.width, f.y * size.height);
      if (xmas) {
        p.color = const Color(0xB3FFFFFF);
        canvas.drawCircle(c, f.r, p);
      } else {
        // an ember: a warm dot with a faint halo
        p.color = const Color(0x22FF7518);
        canvas.drawCircle(c, f.r * 3, p);
        p.color = const Color(0xCCFFA040);
        canvas.drawCircle(c, f.r, p);
      }
    }
  }

  @override
  bool shouldRepaint(covariant _SeasonPainter old) => old.theme != theme;
}
