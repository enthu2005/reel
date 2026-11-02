import 'package:flutter/material.dart';

import 'theme.dart';

/// The opening title: the film's name in big, widely spaced type that fades in, holds, and fades out,
/// like the first card of a film. Draws nothing once it is done.
class TitleCard extends StatelessWidget {
  final String title;
  final String line; // year, or season and episode
  final VoidCallback onDone;

  const TitleCard({super.key, required this.title, required this.line, required this.onDone});

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    return IgnorePointer(
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: 0, end: 1),
        duration: const Duration(milliseconds: 5200),
        onEnd: onDone,
        builder: (c, t, _) {
          // in over the first 15%, hold, out over the last 25%
          final o = t < 0.15 ? t / 0.15 : (t > 0.75 ? (1 - t) / 0.25 : 1.0);
          // the type slowly spreads apart while it is on screen
          final spacing = h * (0.006 + 0.012 * t);
          return Center(
            child: Opacity(
              opacity: Curves.easeInOut.transform(o.clamp(0.0, 1.0)),
              child: Container(
                constraints: BoxConstraints(maxWidth: w * 0.8),
                padding: EdgeInsets.symmetric(horizontal: h * 0.04, vertical: h * 0.03),
                decoration: const BoxDecoration(
                  gradient: RadialGradient(
                      colors: [Color(0x66000000), Color(0x00000000)], radius: 0.9),
                ),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Text(title.toUpperCase(),
                      textAlign: TextAlign.center,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: reelText(h * 0.085, weight: FontWeight.w300).copyWith(
                          letterSpacing: spacing,
                          shadows: const [Shadow(blurRadius: 24, color: Color(0xCC000000))])),
                  if (line.isNotEmpty) ...[
                    SizedBox(height: h * 0.018),
                    Container(width: w * 0.06, height: 2, color: Reel.accent),
                    SizedBox(height: h * 0.018),
                    Text(line,
                        style: reelText(h * 0.03, weight: FontWeight.w400, color: Colors.white70)
                            .copyWith(letterSpacing: h * 0.006)),
                  ],
                ]),
              ),
            ),
          );
        },
      ),
    );
  }
}
