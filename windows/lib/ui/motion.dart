import 'package:flutter/animation.dart';

/// Timings shared by every screen, so the app moves the same way everywhere (same as the Fire TV app).
class Motion {
  /// A card has to rest this long before its preview starts (cards, Featured, libraries).
  static const previewDelay = Duration(milliseconds: 800);

  /// Opening a title page: a plain, quick cross-fade. No growing, no sliding.
  static const pageFade = Duration(milliseconds: 300);

  /// On the title page the poster eases into the playing preview over this long, once it is playing.
  static const behindFade = Duration(milliseconds: 2200);
  static const behindEase = Curves.easeInOut;

  /// Watched ticks and NEW pills.
  static const newDays = 7;
}
