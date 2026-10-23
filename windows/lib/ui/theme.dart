import 'package:flutter/material.dart';

/// Apple TV-like palette and type.
class Reel {
  static const bg = Color(0xFF000000);
  static const card = Color(0xFF1C1C1E);
  static const text = Color(0xFFFFFFFF);
  static const text2 = Color(0x99EBEBF5);
  static const text3 = Color(0x66EBEBF5);
  static const fill = Color(0x33FFFFFF);

  /// Sizes scale with the window height so it looks the same on a laptop and a TV.
  static double h(BuildContext c) => MediaQuery.sizeOf(c).height;
  static double w(BuildContext c) => MediaQuery.sizeOf(c).width;
}

ThemeData reelTheme() {
  final base = ThemeData.dark(useMaterial3: true);
  return base.copyWith(
    scaffoldBackgroundColor: Reel.bg,
    colorScheme: base.colorScheme.copyWith(
      primary: Colors.white,
      surface: Reel.card,
    ),
    textTheme: base.textTheme.apply(fontFamily: 'Inter', bodyColor: Colors.white, displayColor: Colors.white),
    focusColor: Colors.transparent,
    hoverColor: Colors.transparent,
    splashFactory: NoSplash.splashFactory,
    highlightColor: Colors.transparent,
    pageTransitionsTheme: const PageTransitionsTheme(builders: {
      TargetPlatform.windows: FadeUpwardsPageTransitionsBuilder(),
    }),
  );
}

TextStyle reelText(double size, {FontWeight weight = FontWeight.w400, Color color = Reel.text, double? height}) =>
    TextStyle(fontFamily: 'Inter', fontSize: size, fontWeight: weight, color: color, height: height);
