import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:window_manager/window_manager.dart';

import 'plex.dart';
import 'prefs.dart';
import 'screens/home.dart';
import 'screens/login.dart';
import 'warmup.dart';
import 'ui/theme.dart';

final GlobalKey<NavigatorState> navKey = GlobalKey<NavigatorState>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // room for a whole library of posters, so scrolling back never shows blanks
  PaintingBinding.instance.imageCache.maximumSizeBytes = 160 << 20;
  PaintingBinding.instance.imageCache.maximumSize = 1200;
  MediaKit.ensureInitialized();
  await windowManager.ensureInitialized();
  await Prefs.load();
  await Plex.init();
  unawaited(Warmup.run());

  const opts = WindowOptions(
    size: Size(1600, 900),
    minimumSize: Size(960, 540),
    center: true,
    backgroundColor: Colors.black,
    title: 'Reel',
  );
  windowManager.waitUntilReadyToShow(opts, () async {
    await windowManager.show();
    await windowManager.focus();
    if (Prefs.startFullscreen) await setFullscreen(true);
  });
  runApp(const ReelApp());
}

class BackIntent extends Intent {
  const BackIntent();
}

class FullscreenIntent extends Intent {
  const FullscreenIntent();
}

/// Whether the window is full screen right now, so buttons can show the right icon.
final ValueNotifier<bool> isFullscreen = ValueNotifier(false);

Future<void> setFullscreen(bool on) async {
  await windowManager.setFullScreen(on);
  isFullscreen.value = on;
}

Future<void> toggleFullscreen() async {
  await setFullscreen(!await windowManager.isFullScreen());
}

/// Esc / Back: go back a screen. On the first screen, leave full screen instead.
Future<void> goBack() async {
  final popped = await (navKey.currentState?.maybePop() ?? Future.value(false));
  if (!popped && await windowManager.isFullScreen()) await setFullscreen(false);
}

class ReelApp extends StatelessWidget {
  const ReelApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Reel',
      debugShowCheckedModeBanner: false,
      navigatorKey: navKey,
      theme: reelTheme(),
      shortcuts: {
        ...WidgetsApp.defaultShortcuts,
        const SingleActivator(LogicalKeyboardKey.escape): const BackIntent(),
        const SingleActivator(LogicalKeyboardKey.browserBack): const BackIntent(),
        const SingleActivator(LogicalKeyboardKey.gameButtonB): const BackIntent(),
        const SingleActivator(LogicalKeyboardKey.f11): const FullscreenIntent(),
        const SingleActivator(LogicalKeyboardKey.enter, alt: true): const FullscreenIntent(),
      },
      actions: {
        ...WidgetsApp.defaultActions,
        BackIntent: CallbackAction<BackIntent>(onInvoke: (_) {
          goBack();
          return null;
        }),
        FullscreenIntent: CallbackAction<FullscreenIntent>(onInvoke: (_) {
          toggleFullscreen();
          return null;
        }),
      },
      // Sizes already follow the window, so ignore Windows text scaling (it made cards overflow).
      builder: (c, child) => MediaQuery(
        data: MediaQuery.of(c).copyWith(textScaler: TextScaler.noScaling),
        child: child!,
      ),
      home: Plex.isReady ? const HomeScreen() : const LoginScreen(),
    );
  }
}
