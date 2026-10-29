import 'package:flutter/material.dart';

import '../plex.dart';
import 'theme.dart';
import 'widgets.dart';

String _mmss(int ms) {
  final s = ms ~/ 1000;
  final h = s ~/ 3600;
  final m = (s % 3600) ~/ 60;
  final sec = s % 60;
  String two(int n) => n.toString().padLeft(2, '0');
  return h > 0 ? '$h:${two(m)}:${two(sec)}' : '$m:${two(sec)}';
}

/// Where to start [it]: 0 if it was not started, otherwise asks "Resume at 31:20" or "Start over".
/// Returns null if you backed out.
Future<int?> askResume(BuildContext context, PlexItem it) async {
  if (it.viewOffset <= 0) return 0;
  final r = await showDialog<int>(
    context: context,
    barrierColor: const Color(0xB3000000),
    builder: (c) {
      final h = Reel.h(c);
      Widget option(String label, int value, {bool first = false}) => Pressable(
            autofocus: first,
            onPressed: () => Navigator.of(c).pop(value),
            builder: (c2, f) => AnimatedContainer(
              duration: const Duration(milliseconds: 120),
              width: h * 0.55,
              margin: EdgeInsets.only(top: h * 0.012),
              padding: EdgeInsets.symmetric(vertical: h * 0.018),
              alignment: Alignment.center,
              decoration: BoxDecoration(
                  color: f ? Colors.white : const Color(0x26FFFFFF),
                  borderRadius: BorderRadius.circular(h)),
              child: Text(label,
                  style: reelText(h * 0.026,
                      weight: FontWeight.w600,
                      color: f ? Colors.black : Colors.white)),
            ),
          );
      return Dialog(
        backgroundColor: const Color(0xF01C1C1E),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(h * 0.03)),
        child: Padding(
          padding: EdgeInsets.all(h * 0.04),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(it.displayTitle,
                maxLines: 2,
                textAlign: TextAlign.center,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.032, weight: FontWeight.w700)),
            SizedBox(height: h * 0.008),
            Text('You have already watched part of this',
                style: reelText(h * 0.022, color: Reel.text2)),
            SizedBox(height: h * 0.012),
            option('Resume at ${_mmss(it.viewOffset)}', it.viewOffset, first: true),
            option('Start over', 0),
          ]),
        ),
      );
    },
  );
  return r;
}
