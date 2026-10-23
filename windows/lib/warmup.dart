import 'dart:async';

import 'plex.dart';
import 'ui/net_image.dart';
import 'ui/widgets.dart';

/// Saves posters to this PC in the background as soon as the app starts, so by the time you open
/// a page they are already on disk. Runs quietly behind anything you're looking at.
class Warmup {
  static bool _ran = false;

  static Future<void> run() async {
    // Long after everything you're looking at has loaded: this is only to have a few posters ready
    // next time, never something that competes with the screen.
    await Future.delayed(const Duration(minutes: 2));
    if (_ran || Plex.server == null) return;
    _ran = true;
    try {
      final sections = (await Plex.sections()).where((s) => s.type == 'movie' || s.type == 'show').toList();
      String? p(PlexItem i) => PosterCard.imageUrl(i, 0, 0, false, 1);
      String? l(PlexItem i) => PosterCard.imageUrl(i, 0, 0, true, 1);
      final parts = await Future.wait([
        Plex.onDeck().catchError((_) => <PlexItem>[]),
        for (final s in sections) Plex.recentlyAdded(s.key).catchError((_) => <PlexItem>[]),
      ]);
      final urls = <String?>[...parts[0].map(l)];
      for (var i = 1; i < parts.length; i++) {
        urls.addAll(parts[i].take(40).map(p));
      }
      ImageLoader.warm(urls);
    } catch (_) {}
  }
}
