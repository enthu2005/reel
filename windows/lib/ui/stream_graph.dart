import 'dart:async';

import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';

import 'theme.dart';

/// A live strip chart of what the player is doing, one second per point, for chasing stutters:
/// the file's bit rate, what is arriving from the server, how much is buffered ahead, and
/// dropped frames. Reads the values straight from the player while it is on screen.
class StreamGraph extends StatefulWidget {
  final Player player;

  const StreamGraph({super.key, required this.player});

  @override
  State<StreamGraph> createState() => _StreamGraphState();
}

class _StreamGraphState extends State<StreamGraph> {
  static const _n = 60; // a minute of history
  final _bit = <double>[]; // Mbit/s of the picture and sound being played
  final _net = <double>[]; // Mbit/s arriving from the server
  final _buf = <double>[]; // seconds buffered ahead
  final _drop = <double>[]; // frames dropped in that second
  double _lastDrops = -1;
  Timer? _t;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _sample();
    _t = Timer.periodic(const Duration(seconds: 1), (_) => _sample());
  }

  @override
  void dispose() {
    _t?.cancel();
    super.dispose();
  }

  Future<double> _num(NativePlayer np, String k) async {
    try {
      return double.tryParse(await np.getProperty(k)) ?? 0;
    } catch (_) {
      return 0;
    }
  }

  Future<void> _sample() async {
    final np = widget.player.platform;
    if (np is! NativePlayer || _busy) return;
    _busy = true;
    final v = await _num(np, 'video-bitrate');
    final a = await _num(np, 'audio-bitrate');
    final net = await _num(np, 'cache-speed');
    final buf = await _num(np, 'demuxer-cache-duration');
    final d = await _num(np, 'frame-drop-count') + await _num(np, 'decoder-frame-drop-count');
    _busy = false;
    if (!mounted) return;
    void add(List<double> l, double x) {
      l.add(x);
      if (l.length > _n) l.removeAt(0);
    }

    setState(() {
      add(_bit, (v + a) / 1e6);
      add(_net, net * 8 / 1e6);
      add(_buf, buf);
      add(_drop, _lastDrops < 0 ? 0 : (d - _lastDrops).clamp(0, 1000).toDouble());
      _lastDrops = d;
    });
  }

  Widget _chart(double h, String label, List<double> data, String unit, Color col,
      {bool bars = false, double floor = 1}) {
    final last = data.isEmpty ? 0.0 : data.last;
    return Padding(
      padding: EdgeInsets.only(bottom: h * 0.012),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Text(label, style: reelText(h * 0.019, weight: FontWeight.w600, color: Reel.text2)),
          const Spacer(),
          Text('${last.toStringAsFixed(last >= 100 ? 0 : 1)} $unit',
              style: reelText(h * 0.021, weight: FontWeight.w700, color: col)),
        ]),
        SizedBox(height: h * 0.004),
        SizedBox(
          height: h * 0.055,
          width: double.infinity,
          child: CustomPaint(painter: ChartPainter(data, col, _n, bars, floor)),
        ),
      ]),
    );
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final drops = _drop.fold<double>(0, (a, b) => a + b);
    return Container(
      width: w * 0.27,
      padding: EdgeInsets.all(h * 0.018),
      decoration: BoxDecoration(
          color: const Color(0xD91C1C1E), borderRadius: BorderRadius.circular(h * 0.018)),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Text('Stream, last minute', style: reelText(h * 0.023, weight: FontWeight.w700)),
          const Spacer(),
          Text('G to close', style: reelText(h * 0.017, color: Reel.text3)),
        ]),
        SizedBox(height: h * 0.012),
        _chart(h, 'Picture and sound being played', _bit, 'Mbit/s', Reel.accent, floor: 5),
        _chart(h, 'Arriving from the server', _net, 'Mbit/s', const Color(0xFF64D2FF), floor: 5),
        _chart(h, 'Buffered ahead', _buf, 's', const Color(0xFF30D158), floor: 10),
        _chart(h, 'Dropped frames (${drops.toStringAsFixed(0)} in the last minute)', _drop, '/ s',
            const Color(0xFFFF453A), bars: true, floor: 3),
      ]),
    );
  }
}

class ChartPainter extends CustomPainter {
  final List<double> d;
  final Color col;
  final int n;
  final bool bars;
  final double floor; // the chart is never scaled tighter than this, so tiny wobbles stay tiny

  ChartPainter(this.d, this.col, this.n, this.bars, this.floor);

  @override
  void paint(Canvas c, Size s) {
    final grid = Paint()
      ..color = const Color(0x1FFFFFFF)
      ..strokeWidth = 1;
    c.drawLine(Offset(0, s.height - 0.5), Offset(s.width, s.height - 0.5), grid);
    c.drawLine(Offset(0, s.height / 2), Offset(s.width, s.height / 2), grid);
    if (d.isEmpty) return;
    var top = floor;
    for (final x in d) {
      if (x > top) top = x;
    }
    top *= 1.1;
    final dx = s.width / (n - 1);
    final x0 = s.width - (d.length - 1) * dx; // the newest point sits at the right edge
    if (bars) {
      final p = Paint()..color = col;
      for (var i = 0; i < d.length; i++) {
        if (d[i] <= 0) continue;
        final bh = d[i] / top * s.height;
        c.drawRect(Rect.fromLTWH(x0 + i * dx - dx * 0.35, s.height - bh, dx * 0.7, bh), p);
      }
      return;
    }
    final path = Path();
    for (var i = 0; i < d.length; i++) {
      final p = Offset(x0 + i * dx, s.height - d[i] / top * s.height);
      i == 0 ? path.moveTo(p.dx, p.dy) : path.lineTo(p.dx, p.dy);
    }
    final fill = Path.from(path)
      ..lineTo(x0 + (d.length - 1) * dx, s.height)
      ..lineTo(x0, s.height)
      ..close();
    c.drawPath(fill, Paint()..color = col.withOpacity(0.18));
    c.drawPath(
        path,
        Paint()
          ..color = col
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2
          ..strokeJoin = StrokeJoin.round);
  }

  @override
  bool shouldRepaint(covariant ChartPainter o) => true;
}
