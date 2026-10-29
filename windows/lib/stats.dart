import 'plex.dart';

class Ranked {
  final String name;
  final double value; // hours, or a count
  final int count;
  Ranked(this.name, this.value, this.count);
}

/// What you have watched, worked out from the watch state Plex keeps for this profile (when each film or
/// episode was last watched, and how many times). A rewatch inside a year only shows once; "All time" counts every play.
class WatchStats {
  final int year; // 0 = all time
  double hours = 0;
  int movies = 0;
  int episodes = 0;
  int shows = 0;
  List<Ranked> genres = []; // hours
  List<Ranked> actors = []; // titles
  List<Ranked> topShows = []; // hours, count = episodes
  List<Ranked> topMovies = []; // hours, count = plays
  final List<double> weekday = List.filled(7, 0); // hours, Monday first
  final List<double> hourOfDay = List.filled(24, 0);
  final List<double> month = List.filled(12, 0);
  int longestStreak = 0; // days in a row
  DateTime? first;
  DateTime? last;

  WatchStats(this.year);

  bool get isEmpty => hours <= 0;

  int get busiestWeekday {
    var b = 0;
    for (var i = 1; i < 7; i++) {
      if (weekday[i] > weekday[b]) b = i;
    }
    return b;
  }

  int get busiestMonth {
    var b = 0;
    for (var i = 1; i < 12; i++) {
      if (month[i] > month[b]) b = i;
    }
    return b;
  }

  int get peakHour {
    var b = 0;
    for (var i = 1; i < 24; i++) {
      if (hourOfDay[i] > hourOfDay[b]) b = i;
    }
    return b;
  }

  /// A friendly label for when you watch.
  String get persona {
    final h = peakHour;
    if (h >= 22 || h < 4) return 'Night owl';
    if (h >= 4 && h < 10) return 'Early bird';
    if (h >= 10 && h < 17) return 'Daytime viewer';
    return 'Evening regular';
  }

  static const weekdayNames = [
    'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'
  ];
  static const monthNames = [
    'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
    'September', 'October', 'November', 'December'
  ];

  static int _int(Object? v) => v is num ? v.toInt() : int.tryParse('${v ?? ''}') ?? 0;

  static List<String> _tags(Object? l) => [
        for (final t in (l as List? ?? const []))
          if (t is Map && '${t['tag'] ?? ''}'.isNotEmpty) '${t['tag']}'
      ];

  static Future<WatchStats> load(int year) async {
    final st = WatchStats(year);
    final genreMs = <String, double>{};
    final actorCount = <String, int>{};
    final showMs = <String, double>{};
    final showEps = <String, int>{};
    final movieRows = <Ranked>[];
    final days = <int>{};

    void note(DateTime when, double ms) {
      final hrs = ms / 3600000;
      st.hours += hrs;
      st.weekday[when.weekday - 1] += hrs;
      st.hourOfDay[when.hour] += hrs;
      st.month[when.month - 1] += hrs;
      days.add(DateTime(when.year, when.month, when.day).millisecondsSinceEpoch ~/ 86400000);
      if (st.first == null || when.isBefore(st.first!)) st.first = when;
      if (st.last == null || when.isAfter(st.last!)) st.last = when;
    }

    for (final sec in Plex.enabledSections(await Plex.sections())) {
      if (sec.type == 'movie') {
        for (final m in await Plex.rawAll(sec.key)) {
          final seen = _int(m['viewCount']);
          final at = _int(m['lastViewedAt']);
          if (seen <= 0 || at <= 0) continue;
          final when = DateTime.fromMillisecondsSinceEpoch(at * 1000);
          if (year != 0 && when.year != year) continue;
          final plays = year == 0 ? seen : 1;
          final ms = _int(m['duration']).toDouble() * plays;
          if (ms <= 0) continue;
          st.movies++;
          note(when, ms);
          for (final g in _tags(m['Genre'])) {
            genreMs[g] = (genreMs[g] ?? 0) + ms;
          }
          for (final a in _tags(m['Role']).take(6)) {
            actorCount[a] = (actorCount[a] ?? 0) + 1;
          }
          movieRows.add(Ranked('${m['title']}', ms / 3600000, plays));
        }
      } else if (sec.type == 'show') {
        final showsByKey = {
          for (final s in await Plex.rawAll(sec.key)) '${s['ratingKey']}': s
        };
        final seenShows = <String>{};
        for (final e in await Plex.rawWatchedEpisodes(sec.key)) {
          final seen = _int(e['viewCount']);
          final at = _int(e['lastViewedAt']);
          if (seen <= 0 || at <= 0) continue;
          final when = DateTime.fromMillisecondsSinceEpoch(at * 1000);
          if (year != 0 && when.year != year) continue;
          final key = '${e['grandparentRatingKey']}';
          final show = showsByKey[key];
          var dur = _int(e['duration']);
          if (dur <= 0) dur = _int(show?['duration']);
          if (dur <= 0) dur = 40 * 60000;
          final plays = year == 0 ? seen : 1;
          final ms = dur.toDouble() * plays;
          st.episodes++;
          note(when, ms);
          final title = '${e['grandparentTitle'] ?? show?['title'] ?? ''}';
          showMs[title] = (showMs[title] ?? 0) + ms;
          showEps[title] = (showEps[title] ?? 0) + 1;
          if (show != null) {
            for (final g in _tags(show['Genre'])) {
              genreMs[g] = (genreMs[g] ?? 0) + ms;
            }
            if (seenShows.add(key)) {
              for (final a in _tags(show['Role']).take(6)) {
                actorCount[a] = (actorCount[a] ?? 0) + 1;
              }
            }
          }
        }
        st.shows += seenShows.length;
      }
    }

    List<Ranked> top<T>(Map<String, T> m, double Function(T) v, int Function(String) c, int n) {
      final l = [for (final e in m.entries) Ranked(e.key, v(e.value), c(e.key))]
        ..sort((a, b) => b.value.compareTo(a.value));
      return l.take(n).toList();
    }

    st.genres = top<double>(genreMs, (v) => v / 3600000, (_) => 0, 8);
    st.actors = top<int>(actorCount, (v) => v.toDouble(), (k) => actorCount[k]!, 8);
    st.topShows = top<double>(showMs, (v) => v / 3600000, (k) => showEps[k] ?? 0, 8);
    movieRows.sort((a, b) => b.value.compareTo(a.value));
    st.topMovies = movieRows.take(8).toList();

    final sorted = days.toList()..sort();
    var run = 0, best = 0;
    for (var i = 0; i < sorted.length; i++) {
      run = (i > 0 && sorted[i] == sorted[i - 1] + 1) ? run + 1 : 1;
      if (run > best) best = run;
    }
    st.longestStreak = best;
    return st;
  }
}
