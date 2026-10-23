import 'dart:async';

import 'plex.dart';
import 'ui/net_image.dart';
import 'ui/widgets.dart';

/// Saves posters to this PC in the background as soon as the app starts, so by the time you open
/// a page they are already on disk. Runs quietly behind anything you're looking at.
class Warmup {
  static bool _ran = false;

  static Future<void> run() async {
    // let the pages you're looking at load first; the server is busy enough at start-up
    await Future.delayed(const Duration(seconds: 25));
    if (_ran || Plex.server == null) return;
    _ran = true;
    try {
      final sections = (await Plex.sections()).where((s) => s.type == 'movie' || s.type == 'show').toList();
      final first = <String?>[];
      final rest = <String?>[];
      String? p(PlexItem i) => PosterCard.imageUrl(i, 0, 0, false, 1);
      String? l(PlexItem i) => PosterCard.imageUrl(i, 0, 0, true, 1);
      final parts = await Future.wait([
        Plex.onDeck().catchError((_) => <PlexItem>[]),
        for (final s in sections) Plex.recentlyAdded(s.key).catchError((_) => <PlexItem>[]),
        for (final s in sections) Plex.all(s.key).catchError((_) => <PlexItem>[]),
      ]);
      first.addAll(parts[0].map(l));
      for (var i = 1; i <= sections.length; i++) {
        first.addAll(parts[i].map(p));
      }
      for (var i = sections.length + 1; i < parts.length; i++) {
        rest.addAll(parts[i].map(p));
      }
      ImageLoader.warm(first);
      ImageLoader.warm(rest);
    } catch (_) {}
  }
}
