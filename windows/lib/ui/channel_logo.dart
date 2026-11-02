import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'theme.dart';

/// A logo drawn for a Live channel: a rounded tile in a colour that suits its kind, with a simple picture
/// for the common kinds (action, comedy, sci-fi, horror, kids) and the channel's first letters for anything else.
class ChannelLogo extends StatelessWidget {
  final String name;
  final double size;
  const ChannelLogo(this.name, {super.key, required this.size});

  @override
  Widget build(BuildContext context) =>
      SizedBox(width: size, height: size, child: CustomPaint(painter: _LogoPainter(name)));
}

class _LogoPainter extends CustomPainter {
  final String name;
  late final int kind; // 1 action, 2 comedy, 3 sci-fi, 4 horror, 5 kids, 0 other
  late final Color top, bottom;

  _LogoPainter(this.name) {
    final n = name.toLowerCase();
    var k = 0;
    double hue;
    if (n.contains('action') || n.contains('adventure')) {
      k = 1;
      hue = 14;
    } else if (n.contains('comedy') || n.contains('sitcom')) {
      k = 2;
      hue = 44;
    } else if (n.contains('sci') || n.contains('fantasy')) {
      k = 3;
      hue = 215;
    } else if (n.contains('horror') || n.contains('thriller')) {
      k = 4;
      hue = 350;
    } else if (n.contains('kid') ||
        n.contains('family') ||
        n.contains('animation') ||
        n.contains('cartoon')) {
      k = 5;
      hue = 285;
    } else if (n.contains('drama')) {
      hue = 160;
    } else {
      hue = (n.hashCode % 360).abs().toDouble(); // a colour of its own for channels you made
    }
    kind = k;
    top = HSVColor.fromAHSV(1, hue, 0.62, 0.95).toColor();
    bottom = HSVColor.fromAHSV(1, hue, 0.8, 0.55).toColor();
  }

  String _letters() {
    final sb = StringBuffer();
    var n = 0;
    for (final w in name.trim().split(RegExp(r'\s+'))) {
      if (w.isEmpty || !RegExp(r'[A-Za-z0-9]').hasMatch(w[0])) continue;
      sb.write(w[0].toUpperCase());
      if (++n >= 2) break;
    }
    return sb.isEmpty ? 'TV' : sb.toString();
  }

  @override
  void paint(Canvas cv, Size size) {
    final w = size.width, h = size.height;
    final paint = Paint()..isAntiAlias = true;
    paint.shader = LinearGradient(
            begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [top, bottom])
        .createShader(Rect.fromLTWH(0, 0, w, h));
    cv.drawRRect(RRect.fromRectXY(Rect.fromLTWH(0, 0, w, h), w * 0.22, w * 0.22), paint);
    paint.shader = null;
    paint.color = Colors.white;
    final cx = w / 2, cy = h / 2, u = w / 10;
    switch (kind) {
      case 1: // a lightning bolt
        final p = Path()
          ..moveTo(cx + 1.2 * u, cy - 3.6 * u)
          ..lineTo(cx - 2.2 * u, cy + 0.6 * u)
          ..lineTo(cx - 0.1 * u, cy + 0.6 * u)
          ..lineTo(cx - 1.2 * u, cy + 3.6 * u)
          ..lineTo(cx + 2.2 * u, cy - 0.8 * u)
          ..lineTo(cx + 0.1 * u, cy - 0.8 * u)
          ..close();
        cv.drawPath(p, paint);
      case 2: // a smile
        cv.drawCircle(Offset(cx, cy), 3.6 * u, paint);
        paint.color = bottom;
        cv.drawCircle(Offset(cx - 1.3 * u, cy - 0.9 * u), 0.5 * u, paint);
        cv.drawCircle(Offset(cx + 1.3 * u, cy - 0.9 * u), 0.5 * u, paint);
        paint
          ..style = PaintingStyle.stroke
          ..strokeWidth = 0.45 * u
          ..strokeCap = StrokeCap.round;
        cv.drawArc(Rect.fromLTRB(cx - 1.9 * u, cy - 1.2 * u, cx + 1.9 * u, cy + 2.2 * u),
            20 * math.pi / 180, 140 * math.pi / 180, false, paint);
      case 3: // a planet with a ring
        cv.drawCircle(Offset(cx, cy), 2.4 * u, paint);
        paint
          ..style = PaintingStyle.stroke
          ..strokeWidth = 0.5 * u;
        cv.save();
        cv.translate(cx, cy);
        cv.rotate(-22 * math.pi / 180);
        cv.drawOval(Rect.fromLTRB(-4.2 * u, -1.2 * u, 4.2 * u, 1.2 * u), paint);
        cv.restore();
      case 4: // a ghost
        final p = Path()
          ..moveTo(cx - 2.8 * u, cy + 3.4 * u)
          ..lineTo(cx - 2.8 * u, cy - 0.6 * u)
          ..arcTo(Rect.fromLTRB(cx - 2.8 * u, cy - 3.6 * u, cx + 2.8 * u, cy + 2.0 * u),
              math.pi, math.pi, false)
          ..lineTo(cx + 2.8 * u, cy + 3.4 * u)
          ..lineTo(cx + 1.4 * u, cy + 2.4 * u)
          ..lineTo(cx, cy + 3.4 * u)
          ..lineTo(cx - 1.4 * u, cy + 2.4 * u)
          ..close();
        cv.drawPath(p, paint);
        paint.color = bottom;
        cv.drawCircle(Offset(cx - 1.0 * u, cy - 0.6 * u), 0.55 * u, paint);
        cv.drawCircle(Offset(cx + 1.0 * u, cy - 0.6 * u), 0.55 * u, paint);
      case 5: // a star
        final p = Path();
        for (var i = 0; i < 10; i++) {
          final a = -math.pi / 2 + i * math.pi / 5;
          final r = (i % 2 == 0 ? 3.8 : 1.6) * u;
          final x = cx + math.cos(a) * r, y = cy + math.sin(a) * r;
          if (i == 0) {
            p.moveTo(x, y);
          } else {
            p.lineTo(x, y);
          }
        }
        p.close();
        cv.drawPath(p, paint);
      default: // the first letters
        final t = _letters();
        final tp = TextPainter(
            text: TextSpan(
                text: t,
                style: reelText(t.length > 1 ? h * 0.42 : h * 0.52,
                    weight: FontWeight.w700, color: Colors.white)),
            textDirection: TextDirection.ltr)
          ..layout();
        tp.paint(cv, Offset(cx - tp.width / 2, cy - tp.height / 2));
    }
  }

  @override
  bool shouldRepaint(_LogoPainter o) => o.name != name;
}
