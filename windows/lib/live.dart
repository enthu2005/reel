import 'dart:math';

import 'plex.dart';

/// Pretend TV channels made from your own libraries. Each channel has a fixed, endless loop of films or
/// episodes, and the loop is laid against the clock, so what is "on" right now is the same every time you
/// tune in and tuning in joins it part-way through, like real TV. Nothing here is stored on the server.
class LiveProg {
  final String key;
  final String title;
  final String sub;
  final int durMs;
  LiveProg(this.key, this.title, this.sub, this.durMs);
}

class LiveSlot {
  final LiveProg prog;
  final DateTime start;
  final DateTime end;
  LiveSlot(this.prog, this.start, this.end);
}

class LiveChannel {
  final int num;
  final String name;
  final List<LiveProg> loop = [];
  int total = 0;
  LiveChannel(this.num, this.name);
}

class Live {
  Live._();

  /// {channel number, name, genre words (any matches), "movie" or "show"}
  static const _lineup = [
    ['1', 'Adventure', 'adventure', 'movie'],
    ['2', 'Action Movies', 'action', 'movie'],
    ['3', 'Comedy TV', 'comedy', 'show'],
    ['4', 'Sci-Fi', 'science fiction,sci-fi', 'movie'],
    ['5', 'Drama TV', 'drama', 'show'],
    ['6', 'Horror', 'horror', 'movie'],
    ['7', 'Comedy Movies', 'comedy', 'movie'],
    ['8', 'Animation', 'animation', 'movie'],
    ['9', 'Crime TV', 'crime', 'show'],
    ['10', 'Thrillers', 'thriller', 'movie'],
    ['11', 'Documentaries', 'documentary', 'movie'],
    ['12', 'Sci-Fi & Fantasy TV', 'sci-fi,fantasy,science fiction', 'show'],
  ];

  // any fixed moment: the loops start here
  static final int _epoch = 1700000000000;
  static const _minProg = 4 * 60000;
  static List<LiveChannel>? _built;
  static DateTime _builtAt = DateTime.fromMillisecondsSinceEpoch(0);

  static List<LiveChannel>? get cached => _built;

  static LiveChannel? byNum(int n) {
    for (final c in _built ?? const <LiveChannel>[]) {
      if (c.num == n) return c;
    }
    return null;
  }

  /// Java's String.hashCode, so a channel's order never changes between runs.
  static int _hash(String s) {
    var h = 0;
    for (final u in s.codeUnits) {
      h = (h * 31 + u) & 0x7fffffff;
    }
    return h;
  }

  /// Builds (or reuses, for half an hour) the channel list.
  static Future<List<LiveChannel>> load() async {
    final now = DateTime.now();
    if (_built != null && now.difference(_builtAt).inMinutes < 30) {
      return _built!;
    }
    final movies = <Map<String, dynamic>>[];
    final shows = <Map<String, dynamic>>[];
    for (final s in Plex.enabledSections(await Plex.sections())) {
      final movie = s.type == 'movie';
      if (!movie && s.type != 'show') continue;
      (movie ? movies : shows).addAll(await Plex.rawAll(s.key));
    }
    int byKey(Map a, Map b) =>
        '${a['ratingKey']}'.compareTo('${b['ratingKey']}');
    movies.sort(byKey);
    shows.sort(byKey);
    final out = <LiveChannel>[];
    for (final def in _lineup) {
      final c = LiveChannel(int.parse(def[0]), def[1]);
      final rnd = Random(_hash(def[1]));
      final pool = _match(def[3] == 'movie' ? movies : shows, def[2].split(','))
        ..shuffle(rnd);
      if (def[3] == 'movie') {
        for (final m in pool) {
          if (c.loop.length >= 150) break;
          final p = _prog(m);
          if (p != null) c.loop.add(p);
        }
      } else {
        await _buildShows(c, pool, rnd);
      }
      for (final p in c.loop) {
        c.total += p.durMs;
      }
      if (c.total > 0) out.add(c);
    }
    _built = out;
    _builtAt = now;
    return out;
  }

  static List<Map<String, dynamic>> _match(
      List<Map<String, dynamic>> src, List<String> words) {
    final out = <Map<String, dynamic>>[];
    for (final m in src) {
      final g = m['Genre'] as List?;
      if (g == null) continue;
      var hit = false;
      for (final t in g) {
        final tag = ((t as Map)['tag'] ?? '').toString().toLowerCase();
        for (final w in words) {
          if (tag.contains(w.trim())) hit = true;
        }
      }
      if (hit) out.add(m);
    }
    return out;
  }

  static LiveProg? _prog(Map<String, dynamic> m) {
    final d = (m['duration'] as num?)?.toInt() ?? 0;
    final key = '${m['ratingKey'] ?? ''}';
    if (d < _minProg || key.isEmpty) return null;
    final year = (m['year'] as num?)?.toInt() ?? 0;
    return LiveProg(key, '${m['title'] ?? ''}', year > 0 ? '$year' : '', d);
  }

  /// A few shows, their episodes in order, mixed together so it feels like an evening of different shows.
  static Future<void> _buildShows(
      LiveChannel c, List<Map<String, dynamic>> pool, Random rnd) async {
    final lists = <List<LiveProg>>[];
    for (var i = 0; i < pool.length && lists.length < 8; i++) {
      final show = pool[i];
      final eps = <LiveProg>[];
      try {
        for (final e in await Plex.allEpisodes('${show['ratingKey']}')) {
          if (e.duration < _minProg || e.parentIndex <= 0) continue; // skip specials
          eps.add(LiveProg(e.key, '${show['title'] ?? ''}',
              'S${e.parentIndex} · E${e.index}  ${e.title}', e.duration));
          if (eps.length >= 120) break;
        }
      } catch (_) {}
      if (eps.isNotEmpty) lists.add(eps);
    }
    final at = List<int>.filled(lists.length, 0);
    while (c.loop.length < 400) {
      final live = [
        for (var i = 0; i < lists.length; i++)
          if (at[i] < lists[i].length) i
      ];
      if (live.isEmpty) break;
      final pick = live[rnd.nextInt(live.length)];
      c.loop.add(lists[pick][at[pick]++]);
    }
  }

  // ------------------------------------------------------------ the schedule

  /// The programmes of [c] that touch [from, to).
  static List<LiveSlot> window(LiveChannel c, DateTime from, DateTime to) {
    final out = <LiveSlot>[];
    if (c.total <= 0) return out;
    final f = from.millisecondsSinceEpoch;
    var pos = (f - _epoch) % c.total;
    var i = 0;
    while (i < c.loop.length && pos >= c.loop[i].durMs) {
      pos -= c.loop[i].durMs;
      i++;
    }
    var start = f - pos;
    for (var guard = 0;
        start < to.millisecondsSinceEpoch && guard < 5000;
        guard++) {
      final p = c.loop[i % c.loop.length];
      out.add(LiveSlot(p, DateTime.fromMillisecondsSinceEpoch(start),
          DateTime.fromMillisecondsSinceEpoch(start + p.durMs)));
      start += p.durMs;
      i++;
    }
    return out;
  }

  static LiveSlot? nowOn(LiveChannel c) {
    final now = DateTime.now();
    final w = window(c, now, now.add(const Duration(milliseconds: 1)));
    return w.isEmpty ? null : w.first;
  }

  /// What to play to join [c]: the programme on right now and how far in. Close to the end it joins the
  /// next one at its start.
  static (LiveProg, int)? tuneIn(LiveChannel c) {
    var s = nowOn(c);
    if (s == null) return null;
    final now = DateTime.now();
    var off = max(0, now.difference(s.start).inMilliseconds);
    if (s.end.difference(now).inSeconds < 15) {
      final n = window(c, s.end, s.end.add(const Duration(milliseconds: 1)));
      if (n.isNotEmpty) s = n.first;
      off = 0;
    }
    return (s.prog, off);
  }
}
