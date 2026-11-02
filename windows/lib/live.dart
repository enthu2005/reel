import 'dart:convert';
import 'dart:math';

import 'plex.dart';
import 'smart_search.dart';
import 'prefs.dart';

/// Pretend TV channels made from your own libraries. Each channel has a fixed, endless loop of films or
/// episodes, and the loop is laid against the clock, so what is "on" right now is the same every time you
/// tune in and tuning in joins it part-way through, like real TV. Nothing here is stored on the server.
class LiveProg {
  final String key;
  final String title;
  final String sub;
  final int durMs;
  final int season; // episodes only
  final int episode;
  LiveProg(this.key, this.title, this.sub, this.durMs,
      {this.season = 0, this.episode = 0});

  /// "S2 E5", or "" for a film.
  String get code => season > 0 && episode > 0 ? 'S$season E$episode' : '';

  /// "Season 2 · Episode 5", or "" for a film.
  String get longCode =>
      season > 0 && episode > 0 ? 'Season $season · Episode $episode' : '';
}

class LiveSlot {
  final LiveProg prog;
  final DateTime start;
  final DateTime end;
  LiveSlot(this.prog, this.start, this.end);
}

class LiveChannel {
  /// The number shown on the guide: its place in the (reordered, unhidden) list.
  int num;
  final String id;
  final String name;
  final bool custom;
  final List<LiveProg> loop = [];
  int total = 0;
  LiveChannel(this.num, this.id, this.name, {this.custom = false});
}

/// A channel you made: from a collection, one show, or a mix of genres.
class CustomChannel {
  final String id;
  String name;
  String kind; // 'collection', 'show', 'shows', 'genres' or 'search'
  String ref; // the collection's / show's key
  String refTitle; // for showing in the editor
  String type; // genres: 'movie' or 'show'
  List<String> genres;
  List<String> refs; // 'shows': the keys and titles of the shows mixed
  List<String> refTitles;
  List<String> libs; // library keys it may use (empty = every enabled library)
  bool builtin; // one of the standard channels (kept as a changed copy once you edit it)

  CustomChannel(this.id, this.name, this.kind,
      {this.ref = '',
      this.refTitle = '',
      this.type = 'movie',
      List<String>? genres,
      List<String>? refs,
      List<String>? refTitles,
      List<String>? libs,
      this.builtin = false})
      : genres = genres ?? [],
        refs = refs ?? [],
        refTitles = refTitles ?? [],
        libs = libs ?? [];

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'kind': kind,
        'ref': ref,
        'refTitle': refTitle,
        'type': type,
        'genres': genres,
        'refs': refs,
        'refTitles': refTitles,
        'libs': libs,
        'builtin': builtin,
      };

  static CustomChannel fromJson(Map m) => CustomChannel(
        '${m['id']}',
        '${m['name']}',
        '${m['kind']}',
        ref: '${m['ref'] ?? ''}',
        refTitle: '${m['refTitle'] ?? ''}',
        type: '${m['type'] ?? 'movie'}',
        genres: [for (final g in (m['genres'] as List? ?? const [])) '$g'],
        refs: [for (final g in (m['refs'] as List? ?? const [])) '$g'],
        refTitles: [for (final g in (m['refTitles'] as List? ?? const [])) '$g'],
        libs: [for (final g in (m['libs'] as List? ?? const [])) '$g'],
        builtin: m['builtin'] == true,
      );

  String get describe => switch (kind) {
        'collection' => 'Collection: $refTitle',
        'show' => 'Show: $refTitle',
        'shows' => 'Mix of shows: ${refTitles.join(', ')}',
        'search' => 'Search: $ref',
        _ => '${type == 'movie' ? 'Movies' : 'TV'}: ${genres.join(', ')}',
      };
}

class Live {
  Live._();

  /// {id and name, genre words (any matches), "movie" or "show"}
  static const _lineup = [
    ['Adventure', 'adventure', 'movie'],
    ['Action Movies', 'action', 'movie'],
    ['Comedy TV', 'comedy', 'show'],
    ['Sci-Fi', 'science fiction,sci-fi', 'movie'],
    ['Drama TV', 'drama', 'show'],
    ['Horror', 'horror', 'movie'],
    ['Comedy Movies', 'comedy', 'movie'],
    ['Animation', 'animation', 'movie'],
    ['Crime TV', 'crime', 'show'],
    ['Thrillers', 'thriller', 'movie'],
    ['Documentaries', 'documentary', 'movie'],
    ['Sci-Fi & Fantasy TV', 'sci-fi,fantasy,science fiction', 'show'],
  ];

  // any fixed moment: the loops start here
  static final int _epoch = 1700000000000;
  static const _minProg = 4 * 60000;
  static List<LiveChannel>? _everything; // every channel built, hidden or not, in the usual order
  static List<LiveChannel>? _built; // what the guide shows: unhidden, in your order, numbered
  static DateTime _builtAt = DateTime.fromMillisecondsSinceEpoch(0);
  static final Map<String, List<LiveProg>> _epCache = {};

  static List<LiveChannel>? get cached => _built;

  static LiveChannel? byNum(int n) {
    for (final c in _built ?? const <LiveChannel>[]) {
      if (c.num == n) return c;
    }
    return null;
  }

  // ------------------------------------------------------------ your own channels

  static List<CustomChannel> customChannels() {
    try {
      return [
        for (final m in (jsonDecode(Prefs.liveCustom) as List))
          CustomChannel.fromJson(m as Map)
      ];
    } catch (_) {
      return [];
    }
  }

  static void _saveCustom(List<CustomChannel> l) {
    Prefs.liveCustom = jsonEncode([for (final c in l) c.toJson()]);
    invalidate();
  }

  static void addCustom(CustomChannel c) =>
      _saveCustom([...customChannels(), c]);

  static void removeCustom(String id) {
    _saveCustom([for (final c in customChannels()) if (c.id != id) c]);
    Prefs.liveHidden = [for (final x in Prefs.liveHidden) if (x != id) x];
    Prefs.liveOrder = [for (final x in Prefs.liveOrder) if (x != id) x];
  }

  static CustomChannel _standard(List<String> def) => CustomChannel(
      def[0], def[0], 'genres',
      type: def[2],
      genres: [for (final g in def[1].split(',')) g.trim()],
      builtin: true);

  static Map<String, dynamic> _overrides() {
    try {
      return Map<String, dynamic>.from(jsonDecode(Prefs.liveOver) as Map);
    } catch (_) {
      return {};
    }
  }

  /// Every channel's recipe: the standard ones (or your changed copy of them), then the ones you made.
  static List<CustomChannel> definitions() {
    final over = _overrides();
    return [
      for (final def in _lineup)
        over[def[0]] is Map
            ? CustomChannel.fromJson(over[def[0]] as Map)
            : _standard(def),
      ...customChannels(),
    ];
  }

  static CustomChannel? definition(String id) {
    for (final c in definitions()) {
      if (c.id == id) return c;
    }
    return null;
  }

  /// True when a standard channel has been changed from how it came.
  static bool changed(String id) => _overrides().containsKey(id);

  /// Saves a changed recipe (name, what it plays, which libraries) for a standard or own channel.
  static void saveDefinition(CustomChannel c) {
    if (c.builtin) {
      final over = _overrides();
      over[c.id] = c.toJson();
      Prefs.liveOver = jsonEncode(over);
      invalidate();
      return;
    }
    _saveCustom([for (final x in customChannels()) x.id == c.id ? c : x]);
  }

  /// Puts a standard channel back as it came.
  static void resetDefinition(String id) {
    final over = _overrides()..remove(id);
    Prefs.liveOver = jsonEncode(over);
    invalidate();
  }

  /// Forget what was built, so the next load builds the channels again.
  static void invalidate() {
    _builtAt = DateTime.fromMillisecondsSinceEpoch(0);
  }

  /// Every channel in the guide's order (hidden ones included), for the editor.
  static List<LiveChannel> allInOrder() => _arranged(_everything ?? const [], hidden: false);

  static List<LiveChannel> _arranged(List<LiveChannel> all, {required bool hidden}) {
    final order = Prefs.liveOrder;
    final hide = Prefs.liveHidden.toSet();
    final rest = [...all];
    final out = <LiveChannel>[];
    for (final id in order) {
      final i = rest.indexWhere((c) => c.id == id);
      if (i >= 0) out.add(rest.removeAt(i));
    }
    out.addAll(rest);
    return hidden ? [for (final c in out) if (!hide.contains(c.id)) c] : out;
  }

  /// Re-applies your order and hidden choices without building anything again.
  static void rearrange() {
    final l = _arranged(_everything ?? const [], hidden: true);
    for (var i = 0; i < l.length; i++) {
      l[i].num = i + 1;
    }
    _built = l;
  }

  static void moveChannel(String id, int delta) {
    final ids = [for (final c in allInOrder()) c.id];
    final i = ids.indexOf(id);
    final j = i + delta;
    if (i < 0 || j < 0 || j >= ids.length) return;
    ids.insert(j, ids.removeAt(i));
    Prefs.liveOrder = ids;
    rearrange();
  }

  static void setHidden(String id, bool hide) {
    final h = Prefs.liveHidden.where((x) => x != id).toList();
    if (hide) h.add(id);
    Prefs.liveHidden = h;
    rearrange();
  }

  // ------------------------------------------------------------ building

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
      final all = await Plex.rawAll(s.key);
      for (final m in all) {
        m['_lib'] = s.key; // which library it came from, for channels limited to some libraries
      }
      (movie ? movies : shows).addAll(all);
    }
    int byKey(Map a, Map b) =>
        '${a['ratingKey']}'.compareTo('${b['ratingKey']}');
    movies.sort(byKey);
    shows.sort(byKey);
    final out = <LiveChannel>[];
    for (final cc in definitions()) {
      final c = LiveChannel(0, cc.id, cc.name, custom: !cc.builtin);
      try {
        switch (cc.kind) {
          case 'collection':
            await _fromCollection(c, cc.ref);
          case 'show':
            await _fromShow(c, cc.ref, cc.refTitle);
          case 'search':
            await _fromSearch(c, cc.ref);
          case 'shows':
            await _buildShows(
                c,
                [
                  for (var i = 0; i < cc.refs.length; i++)
                    {
                      'ratingKey': cc.refs[i],
                      'title': i < cc.refTitles.length ? cc.refTitles[i] : ''
                    }
                ],
                Random(_hash(cc.id)),
                maxShows: 12);
          default:
            await _fromGenres(c, cc.genres.map((g) => g.toLowerCase()).toList(),
                cc.type == 'movie', movies, shows, cc.libs);
        }
      } catch (_) {}
      for (final p in c.loop) {
        c.total += p.durMs;
      }
      if (c.total > 0) out.add(c);
    }
    _everything = out;
    _builtAt = now;
    rearrange();
    return _built!;
  }

  static Future<void> _fromGenres(
      LiveChannel c,
      List<String> words,
      bool movie,
      List<Map<String, dynamic>> movies,
      List<Map<String, dynamic>> shows,
      List<String> libs) async {
    final rnd = Random(_hash(c.id));
    final pool = _match(movie ? movies : shows, words, libs)..shuffle(rnd);
    if (movie) {
      for (final m in pool) {
        if (c.loop.length >= 150) break;
        final p = _prog(m);
        if (p != null) c.loop.add(p);
      }
    } else {
      await _buildShows(c, pool, rnd);
    }
  }

  /// A collection plays in release order (a marathon); a collection of shows mixes their episodes.
  static Future<void> _fromCollection(LiveChannel c, String key) async {
    final items = await Plex.children(key);
    if (items.isEmpty) return;
    if (items.first.type == 'show') {
      await _buildShows(c, [
        for (final s in items) {'ratingKey': s.key, 'title': s.title}
      ], Random(_hash(c.id)));
      return;
    }
    final movies = items.where((m) => m.type == 'movie').toList()
      ..sort((a, b) {
        final y = a.year.compareTo(b.year);
        return y != 0 ? y : a.title.compareTo(b.title);
      });
    for (final m in movies) {
      if (m.duration < _minProg) continue;
      c.loop.add(LiveProg(m.key, m.title, m.year > 0 ? '${m.year}' : '', m.duration));
    }
  }

  /// A saved smart search ("80s horror rated 7+"): whatever it finds today, films and shows mixed together.
  static Future<void> _fromSearch(LiveChannel c, String query) async {
    final items = await SmartSearch.run(SmartSearch.parse(query), limit: 300);
    final rnd = Random(_hash(c.id));
    final lists = <List<LiveProg>>[];
    var shows = 0;
    for (final it in items) {
      if (it.type == 'movie') {
        if (it.duration >= _minProg) {
          lists.add([
            LiveProg(it.key, it.title, it.year > 0 ? '${it.year}' : '', it.duration)
          ]);
        }
      } else if (it.type == 'show' && shows < 12) {
        try {
          final eps = await _episodesOf(it.key, it.title);
          if (eps.isNotEmpty) {
            lists.add(eps);
            shows++;
          }
        } catch (_) {}
      }
    }
    lists.shuffle(rnd);
    _mix(c, lists, rnd);
  }

  /// One show, every episode in order, round and round (24/7).
  static Future<void> _fromShow(LiveChannel c, String key, String title) async {
    final eps = await _episodesOf(key, title, cap: 600);
    c.loop.addAll(eps);
  }

  static Future<List<LiveProg>> _episodesOf(String showKey, String showTitle,
      {int cap = 120}) async {
    final cached = _epCache['$showKey/$cap'];
    if (cached != null) return cached;
    final eps = <LiveProg>[];
    for (final e in await Plex.allEpisodes(showKey)) {
      if (e.duration < _minProg || e.parentIndex <= 0) continue; // skip specials
      eps.add(LiveProg(e.key, showTitle,
          'S${e.parentIndex} · E${e.index}  ${e.title}', e.duration,
          season: e.parentIndex, episode: e.index));
      if (eps.length >= cap) break;
    }
    return _epCache['$showKey/$cap'] = eps;
  }

  static List<Map<String, dynamic>> _match(
      List<Map<String, dynamic>> src, List<String> words, List<String> libs) {
    final out = <Map<String, dynamic>>[];
    for (final m in src) {
      if (libs.isNotEmpty && !libs.contains('${m['_lib']}')) continue;
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
      LiveChannel c, List<Map<String, dynamic>> pool, Random rnd,
      {int maxShows = 8}) async {
    final lists = <List<LiveProg>>[];
    // the shows' episode lists are fetched together, not one after the other
    final jobs = <Future<List<LiveProg>>>[
      for (var i = 0; i < pool.length && i < maxShows + 4; i++)
        _episodesOf('${pool[i]['ratingKey']}', '${pool[i]['title'] ?? ''}')
            .catchError((_) => <LiveProg>[])
    ];
    for (final eps in await Future.wait(jobs)) {
      if (lists.length >= maxShows) break;
      if (eps.isNotEmpty) lists.add(eps);
    }
    _mix(c, lists, rnd);
  }

  /// Takes the next programme from a randomly picked list, over and over, so each list stays in order.
  static void _mix(LiveChannel c, List<List<LiveProg>> lists, Random rnd) {
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
