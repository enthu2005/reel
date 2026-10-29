import 'dart:async';
import 'dart:math';
import 'dart:typed_data';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../main.dart' show isFullscreen, toggleFullscreen;
import '../plex.dart';
import '../prefs.dart';
import '../ui/net_image.dart';
import '../ui/preview.dart';
import '../ui/motion.dart';
import '../ui/theme.dart';
import '../ui/resume.dart';
import '../live.dart';
import '../ui/widgets.dart';
import 'detail.dart';
import 'discover.dart';
import 'libraries.dart';
import 'live.dart';
import '../seerr.dart';
import '../ui/net_image.dart' show ImageLoader;
import 'player.dart';
import 'search.dart';
import 'settings.dart';

class _Row {
  final String title;
  final List<PlexItem> items;
  final bool landscape;
  final bool titles;
  final bool previews;
  final bool big;
  final Duration previewDelay;

  _Row(this.title, this.items,
      {this.landscape = true,
      this.big = false,
      this.titles = false,
      this.previews = true,
      this.previewDelay = Motion.previewDelay});
}

/// Home: a big backdrop of whatever you're on, a glass tab bar, and rows of posters.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const _tabs = [
    'Home',
    'Movies',
    'TV Shows',
    'Libraries',
    'Discover',
    'Live',
    'Search',
    'Settings'
  ];
  bool get _discover => _tabs[_tab] == 'Discover';
  bool _needSeerr = false;
  int _tab = 0;
  List<_Row> _rows = [];
  bool _loading = true;
  String? _error;
  // The top-left details and backdrop update on their own, without rebuilding all the rows
  // (rebuilding every card on every move is what made it laggy).
  final _heroN = ValueNotifier<PlexItem?>(null);
  PlexItem? get _hero => _heroN.value;
  set _hero(PlexItem? v) => _heroN.value = v;
  final _fadeN = ValueNotifier<double>(1.0);
  // The background tints to whichever poster is focused.
  final _ambientN = ValueNotifier<Color>(const Color(0xFF000000));
  final Map<String, Color> _ambientCache = {};
  final _quietN = ValueNotifier<bool>(Prefs.quiet);
  final _metaTick = ValueNotifier<int>(0);

  static String? _backdrop(PlexItem it) =>
      Plex.img(it.heroArt ?? it.poster, 1280, 720);
  Timer? _heroTimer;
  final _scroll = ScrollController();
  int _loadToken = 0;
  List<PlexSection> _secs = [];
  final _spotIdx = ValueNotifier<int>(0);
  final _playNode = FocusNode();
  final _infoNode = FocusNode();
  Timer? _spotTimer;
  String? _spotShown;
  bool _spotAway = false;
  bool get _spot => _rows.isNotEmpty && _rows.first.big;
  final Map<String, PlexItem?> _meta =
      {}; // full details: logo, picture and sound badges

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      final h = Reel.h(context);
      final a = (1 - _scroll.offset / (h * 0.25)).clamp(0.0, 1.0);
      if ((a - _fadeN.value).abs() > 0.02) _fadeN.value = a;
      if (!_spot) return;
      // A full-screen video playing behind rows you're scrolling through is what made going back up
      // stutter, so it stops the moment the page starts to move and comes back once it is home again.
      final away = _scroll.offset > h * 0.03;
      if (away != _spotAway) {
        _spotAway = away;
        _spotShown = null;
        if (away) {
          Preview.stop();
        } else if (_playNode.hasFocus || _infoNode.hasFocus) {
          _spotPreview(
              _rows.first.items[_spotIdx.value % _rows.first.items.length]);
        }
      }
    });
    _load();
  }

  @override
  void dispose() {
    _heroTimer?.cancel();
    _scroll.dispose();
    _spotTimer?.cancel();
    _spotIdx.dispose();
    _playNode.dispose();
    _infoNode.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final mine = ++_loadToken;
    // build the Live guide quietly now, so the Live tab opens at once
    // (after a pause, so it does not compete with the first preview for the server)
    if (Live.cached == null) {
      Future.delayed(const Duration(seconds: 12),
          () => Live.load().then((_) {}, onError: (_) {}));
    }
    setState(() {
      _loading = _rows.isEmpty;
      _error = null;
    });
    _needSeerr = false;
    _gReq = [];
    _gGenres = [];
    _gExtra = [];
    _genrePaths.clear();
    try {
      final rows = <_Row>[];
      if (_discover) {
        if (!Seerr.configured) {
          if (mine != _loadToken || !mounted) return;
          setState(() {
            _loading = false;
            _needSeerr = true;
            _error =
                'Discover shows what\'s trending and popular, and lets you request it with Seerr.\n'
                'Add your Seerr address to start.';
          });
          return;
        }
        rows.addAll(await _discoverRows());
      } else {
        // Ask for every row at once instead of one after another (much quicker over the internet).
        final sections = await Plex.sections();
        final hiddenSectionKeys =
            sections.where((s) => s.hiddenFromHome).map((s) => s.key).toSet();
        final tabSecs = _tab == 0
            ? Plex.homeSections(sections)
            : _tab == 1
                ? Plex.movieSections(sections)
                : [
                    for (final s in Plex.homeSections(sections))
                      if (s.type == 'show') s
                  ];
        _secs = tabSecs;
        final parts = await Future.wait<List<_Row>>([
          Plex.onDeck().then((d) {
            _checkElsewhere(d);
            final keep = _deckFor(d)
                .where((x) => !hiddenSectionKeys.contains(x.librarySectionKey))
                .toList();
            return keep.isEmpty
                ? <_Row>[]
                : [_Row('Continue Watching', keep, landscape: true)];
          }).catchError((_) => <_Row>[]),
          // One Recently Added row across every library on this tab, newest first
          _recentAcross(tabSecs).then((r) => r.isEmpty
              ? <_Row>[]
              : [_Row('Recently Added', r, landscape: true, titles: true)]),
          // The whole library is a tile that opens its page, not a row of thousands
          if (_tab != 0) _libraryTiles(tabSecs),
        ]);
        for (final p in parts) {
          rows.addAll(p);
        }
        _addFeatured(rows);
      }
      if (mine != _loadToken || !mounted) return;
      if (_rows.isEmpty && rows.isNotEmpty) {
        // First show: wait (briefly) for the first screenful of pictures so the page opens complete.
        final h = Reel.h(context);
        final dpr = MediaQuery.devicePixelRatioOf(context);
        final first = <Future<Object?>>[];
        for (final r in rows.take(4)) {
          final ch = r.big ? h * 0.40 : (r.landscape ? h * 0.185 : h * 0.28);
          final cw = r.landscape ? ch * 16 / 9 : ch * 2 / 3;
          for (final it in r.items.take(8)) {
            final u = PosterCard.imageUrl(it, cw, ch, r.landscape, dpr);
            if (u != null) first.add(ImageLoader.load(u));
          }
        }
        await Future.wait(first)
            .timeout(const Duration(seconds: 3), onTimeout: () => const []);
        if (mine != _loadToken || !mounted) return;
      }
      setState(() {
        _base = rows;
        _rows = _discover ? rows : _compose();
        _loading = false;
        if (rows.isNotEmpty && rows.first.items.isNotEmpty)
          _hero ??= rows.first.items.first;
      });
      if (_hero != null) _fetchLogo(_hero!);
      _prefetch(rows);
      // Featured and "Because you watched" come in a moment later, so they never hold Home up
      if (!_discover) unawaited(_spotlight(mine));
    } catch (e) {
      if (mine != _loadToken || !mounted) return;
      setState(() {
        _loading = false;
        _error = _discover
            ? "Can't reach Seerr.\n$e"
            : "Can't reach your Plex server.\n$e";
      });
    }
  }

  // What the first load gave (banner, Continue Watching, Recently Added, library tiles) and the groups
  // that arrive a moment later. _compose puts them in the same order as the Fire TV app.
  List<_Row> _base = [];
  List<_Row> _gReq = [];
  List<_Row> _gGenres = [];
  List<_Row> _gExtra = [];
  final Map<String, List<String>> _genrePaths = {};

  static bool _isTop(_Row r) =>
      r.title.startsWith('Because you watched') ||
      r.title.startsWith('More with ') ||
      r.title.startsWith('Directed by ');

  List<_Row> _compose() {
    _Row? one(String t) {
      for (final r in _base) {
        if (r.title == t) return r;
      }
      return null;
    }

    final cw = one('Continue Watching'), ra = one('Recently Added');
    final libs = one('Libraries');
    return [
      ..._base.where((r) => r.big),
      if (cw != null) cw,
      if (ra != null) ra,
      ..._gReq,
      ..._gGenres,
      ..._gExtra.where(_isTop),
      ..._gExtra
          .where((r) => !_isTop(r) && !r.title.startsWith('Unwatched')),
      ..._gExtra.where((r) => r.title.startsWith('Unwatched')),
      if (libs != null) libs,
    ];
  }

  /// Continue Watching on a tab holds only that kind of thing: films on Movies, episodes on TV Shows.
  List<PlexItem> _deckFor(List<PlexItem> d) => _tab == 0
      ? d
      : d
          .where((x) => _tab == 1 ? x.type == 'movie' : x.type == 'episode')
          .toList();

  Future<List<PlexItem>> _recentAcross(List<PlexSection> secs) async {
    final lists = await Future.wait([
      for (final s in secs)
        Plex.recentlyAdded(s.key).catchError((_) => <PlexItem>[])
    ]);
    final seen = <String>{};
    final all = [
      for (final l in lists)
        for (final x in l)
          if (seen.add(x.key)) x
    ]..sort((a, b) => b.addedAt.compareTo(a.addedAt));
    return all.take(60).toList();
  }

  Future<List<_Row>> _libraryTiles(List<PlexSection> secs) async {
    final tiles = await Future.wait([
      for (final s in secs)
        () async {
          final t = PlexItem()
            ..key = 'lib:${s.key}'
            ..type = 'genre' // drawn as a picture tile
            ..title = s.title.toLowerCase().startsWith('all ')
                ? s.title
                : 'All ${s.title}'
            ..summary = s.type;
          try {
            final one = await Plex.items(
                Plex.paged('/library/sections/${s.key}/all?sort=random', 1));
            if (one.isNotEmpty) t.thumb = one.first.art ?? one.first.thumb;
          } catch (_) {}
          return t;
        }()
    ]);
    return tiles.isEmpty
        ? []
        : [
            _Row('Libraries', tiles,
                landscape: true, titles: true, previews: false)
          ];
  }

  /// The second pass, so the page appears quickly: Your requests, Genres, Because you watched,
  /// people, collections and Unwatched. Each part is optional and lands as soon as it is ready.
  Future<void> _spotlight(int token) async {
    void land(void Function() put) {
      if (!mounted || token != _loadToken) return;
      setState(() {
        put();
        _rows = _compose();
      });
    }

    final tasks = <Future<void>>[
      if (_tab == 0)
        _requestRows().then((r) => land(() => _gReq = r), onError: (_) {}),
      _genreRows().then(
          (r) => land(() {
                _genrePaths.addAll(r.$2);
                _gGenres = r.$1;
              }),
          onError: (_) {}),
      _extraRows(_secs)
          .timeout(const Duration(seconds: 45))
          .then((r) => land(() => _gExtra = r), onError: (_) {}),
    ];
    await Future.wait(tasks);
  }

  /// "Your requests" from Seerr, if it is set up.
  Future<List<_Row>> _requestRows() async {
    if (!Seerr.configured) return [];
    final r = await Seerr.myRequests(take: 12);
    if (r.isEmpty) return [];
    return [
      _Row('Your requests', [for (final m in r) m.toItem()],
          landscape: true, titles: true, previews: false)
    ];
  }

  /// The Genres row: one tile per genre across the libraries on this tab, each with a picture from a
  /// title in it. Opening a tile browses that genre in all those libraries.
  Future<(List<_Row>, Map<String, List<String>>)> _genreRows() async {
    final names = <String, String>{};
    final paths = <String, List<String>>{};
    final libs = await Future.wait([
      for (final s in _secs)
        Plex.genresOf(s.key).catchError((_) => <List<String>>[])
    ]);
    for (final gs in libs) {
      for (final g in gs) {
        final k = g[1].toLowerCase();
        names.putIfAbsent(k, () => g[1]);
        (paths[k] ??= []).add(g[0]);
      }
    }
    final keys = names.keys.toList()..sort();
    final cards = await Future.wait([
      for (final k in keys)
        () async {
          final c = PlexItem()
            ..key = 'genre:$k'
            ..type = 'genre'
            ..title = names[k]!;
          try {
            final first = paths[k]!.first;
            final one = await Plex.items(Plex.paged(
                '$first${first.contains('?') ? '&' : '?'}sort=random', 1));
            if (one.isEmpty && paths[k]!.length == 1) return null; // empty genre
            if (one.isNotEmpty) c.thumb = one.first.art ?? one.first.thumb;
          } catch (_) {}
          return c;
        }()
    ]);
    final keep = <PlexItem>[
      for (final c in cards)
        if (c != null) c
    ];
    return (
      keep.isEmpty
          ? <_Row>[]
          : [
              _Row('Genres', keep,
                  landscape: true, titles: true, previews: false)
            ],
      {for (final c in keep) c.key: paths[c.key.substring(6)]!},
    );
  }

  Future<List<_Row>> _extraRows(List<PlexSection> secs) async {
    Future<List<PlexItem>> safe(Future<List<PlexItem>> f) =>
        f.catchError((_) => <PlexItem>[]);
    final out = <_Row>[];
    final rnd = Random();
    // One "Because you watched" and one "Unwatched" row for movies, and the same for shows, each
    // drawing on every library on the tab.
    final movieLibs = <PlexSection>[], showLibs = <PlexSection>[];
    for (final s in secs) {
      if (_tab == 1 || s.type == 'movie') {
        movieLibs.add(s);
      } else if (s.type == 'show') {
        showLibs.add(s);
      }
    }
    if (_tab == 2) movieLibs.clear();
    for (final movie in [true, false]) {
      final group = movie ? movieLibs : showLibs;
      if (group.isEmpty) continue;
      try {
        final lists = await Future.wait([
          for (final s in group)
            safe(Plex.items(Plex.paged(
                '/library/sections/${s.key}/all?sort=lastViewedAt:desc', 10)))
        ]);
        final last = [for (final l in lists) ...l]
          ..sort((a, b) => b.lastViewedAt.compareTo(a.lastViewedAt));
        for (final w in last) {
          // a show counts as watched once any episode has been
          if (w.viewCount <= 0 && w.viewOffset <= 0 && w.viewedLeafCount <= 0) {
            continue;
          }
          final like = (await safe(Plex.related(w.key)))
              .where((x) => x.viewCount == 0 && x.viewedLeafCount == 0)
              .take(20)
              .toList();
          if (like.length < 4) continue; // try the next thing you watched
          out.add(_Row('Because you watched ${w.displayTitle}', like,
              landscape: true, titles: true));
          break;
        }
        // ... and the lead actor and the director of something you watched lately
        if (movie) {
          for (final w in last) {
            if (w.viewCount <= 0) continue;
            final sec = w.librarySectionKey ?? group.first.key;
            final full = await Plex.metadata(w.key);
            await _personRow(out, sec, 'More with ',
                full.cast.isEmpty ? null : full.cast.first, w.key);
            await _personRow(out, sec, 'Directed by ',
                full.directors.isEmpty ? null : full.directors.first, w.key);
            break;
          }
        }
      } catch (_) {}
      // Unwatched, shuffled, from all of them together
      try {
        final lists = await Future.wait([
          for (final s in group)
            safe(Plex.items(Plex.paged(
                '/library/sections/${s.key}/all?unwatched=1&sort=random', 30)))
        ]);
        var un = [for (final l in lists) ...l]..shuffle(rnd);
        if (un.length > 30) un = un.sublist(0, 30);
        if (un.length >= 3) {
          out.add(_Row(movie ? 'Unwatched Movies' : 'Unwatched Shows', un,
              landscape: true, titles: true));
        }
      } catch (_) {}
    }
    // Collections: two on Home, three on Movies / TV Shows, per library
    final collRows = _tab == 0 ? 2 : 3;
    for (final s in secs) {
      if (_tab == 2 && s.type != 'show') continue;
      try {
        final cols = await Plex.collections(s.key);
        cols.shuffle(rnd);
        var added = 0;
        for (final c in cols) {
          if (added >= collRows) break;
          if ((int.tryParse(c[2]) ?? 0) < 3) continue;
          final kids = await Plex.items(
              Plex.paged('/library/collections/${c[1]}/children', 60));
          if (kids.length < 3) continue;
          out.add(_Row(c[0], kids, landscape: true, titles: true));
          added++;
        }
      } catch (_) {}
    }
    // Drop a row when most of its titles are already on the screen in a row above it
    final onScreen = <String>{
      for (final r in [..._base, ..._gReq])
        if (r.title != 'Libraries' && !r.big)
          for (final x in r.items) x.key
    };
    final kept = <_Row>[];
    for (final r in out) {
      final dup = r.items.where((x) => onScreen.contains(x.key)).length;
      if (dup * 2 > r.items.length) continue;
      onScreen.addAll(r.items.map((x) => x.key));
      kept.add(r);
    }
    return kept;
  }

  /// A row of the library's titles with this person in them, unwatched first; skipped if under 4.
  Future<void> _personRow(List<_Row> out, String sectionKey, String prefix,
      CastMember? who, String exceptKey) async {
    if (who == null) return;
    try {
      final list = (await Plex.items(Plex.paged(
              '/library/sections/$sectionKey/all?${who.filter}&sort=year:desc',
              40)))
          .where((x) => x.key != exceptKey)
          .toList();
      final sorted = [
        ...list.where((x) => !x.isWatched),
        ...list.where((x) => x.isWatched)
      ];
      if (sorted.length >= 4) {
        out.add(_Row('$prefix${who.name}', sorted,
            landscape: true, titles: true));
      }
    } catch (_) {}
  }

  /// The banner across the top, made from what Home has already fetched (newest in each library and
  /// what you're partway through), so it is there as soon as the page is, with nothing extra to wait for.
  void _addFeatured(List<_Row> rows) {
    final pools = <List<PlexItem>>[
      for (final r in rows)
        if (r.title.startsWith('Recently Added')) r.items,
      for (final r in rows)
        if (r.title == 'Continue Watching') r.items,
    ];
    final seen = <String>{};
    final featured = <PlexItem>[];
    for (var i = 0; featured.length < 12 && i < 12; i++) {
      for (final pool in pools) {
        if (i < pool.length && featured.length < 12 && seen.add(pool[i].key)) {
          featured.add(pool[i]);
        }
      }
    }
    if (featured.length >= 3) {
      rows.insert(
          0,
          _Row('Featured', featured,
              landscape: true,
              big: true,
              titles: true,
              previewDelay: Motion.previewDelay));
    }
  }

  Future<List<_Row>> _discoverRows() async {
    Future<List<_Row>> row(String title, Future<List<PlexItem>> f,
        {bool landscape = true}) async {
      try {
        final items = await f;
        return items.isEmpty
            ? []
            : [
                _Row(title, items,
                    titles: true, landscape: landscape, previews: false)
              ];
      } catch (_) {
        return [];
      }
    }

    List<PlexItem> items(List<SeerrMedia> l) =>
        l.map((m) => m.toItem()).toList();
    final parts = await Future.wait([
      row('Your requests', Seerr.myRequests(take: 15).then(items)),
      row('Trending', Seerr.trending().then(items)),
      row('Popular movies', Seerr.popularMovies().then(items)),
      row('Popular TV', Seerr.popularShows().then(items)),
      row('Coming soon', Seerr.upcomingMovies().then(items)),
      row(
          'Movie genres',
          Seerr.genreList('movie')
              .then((g) => g.map((x) => x.toItem()).toList()),
          landscape: true),
      row('TV genres',
          Seerr.genreList('tv').then((g) => g.map((x) => x.toItem()).toList()),
          landscape: true),
    ]);
    final out = [for (final p in parts) ...p];
    if (out.isEmpty)
      throw Exception(
          'Seerr gave nothing back. Check the address in Settings.');
    return out;
  }

  /// Fetch a little way ahead of what's on screen, a column at a time across the rows, so moving
  /// right or down finds the next pictures ready. Deliberately small: asking for hundreds at once
  /// is what made the ones you're looking at slow.
  void _prefetch(List<_Row> rows) {
    final h = Reel.h(context);
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final urls = <String?>[];
    for (var i = 0; i < 14; i++) {
      for (final r in rows) {
        if (i >= r.items.length) continue;
        final ch = r.big ? h * 0.40 : (r.landscape ? h * 0.185 : h * 0.28);
        final cw = r.landscape ? ch * 16 / 9 : ch * 2 / 3;
        urls.add(PosterCard.imageUrl(r.items[i], cw, ch, r.landscape, dpr));
      }
      if (i < 3) {
        for (final r in rows.take(3)) {
          if (i < r.items.length) urls.add(_backdrop(r.items[i]));
        }
      }
    }
    ImageLoader.prefetch(urls);
  }

  /// Refreshes Up Next progress after watching something, without moving anything.
  Future<void> _refreshDeck() async {
    if (_discover) return;
    try {
      final deck = _deckFor(await Plex.onDeck());
      if (!mounted) return;
      setState(() {
        final i = _base.indexWhere((r) => r.title == 'Continue Watching');
        if (i >= 0) {
          _base[i] = _Row('Continue Watching', deck, landscape: true);
        } else if (deck.isNotEmpty) {
          _base.insert(_base.isNotEmpty && _base.first.big ? 1 : 0,
              _Row('Continue Watching', deck, landscape: true));
        }
        _rows = _compose();
      });
    } catch (_) {}
  }

  PlexItem? _pendingHero;

  void _focused(PlexItem it) {
    if (it.type == 'genre') return; // the banner keeps the last title
    _pendingHero = it;
    _heroTimer?.cancel();
    _heroTimer = Timer(const Duration(milliseconds: 300), () async {
      if (!mounted) return;
      _fetchLogo(it);
      final art = _backdrop(it);
      Uint8List? bytes;
      if (art != null) {
        bytes = await ImageLoader.load(art)
            .timeout(const Duration(seconds: 2), onTimeout: () => null);
      }
      if (!mounted || _pendingHero != it) return;
      _hero = it;
      if (art != null && bytes != null && !_spot) _tint(art, bytes);
    });
  }

  /// Works out a deep, muted version of the picture's main colour and fades the background to it.
  Future<void> _tint(String key, Uint8List bytes) async {
    var c = _ambientCache[key];
    if (c == null) {
      try {
        final codec =
            await instantiateImageCodec(bytes, targetWidth: 16, targetHeight: 9);
        final img = (await codec.getNextFrame()).image;
        final data = await img.toByteData(format: ImageByteFormat.rawRgba);
        img.dispose();
        if (data == null) return;
        double r = 0, g = 0, b = 0, wsum = 0;
        for (var i = 0; i + 3 < data.lengthInBytes; i += 4) {
          final pr = data.getUint8(i), pg = data.getUint8(i + 1), pb = data.getUint8(i + 2);
          final mx = max(pr, max(pg, pb)), mn = min(pr, min(pg, pb));
          final wgt = 0.15 + (mx - mn) / 255.0; // colourful pixels count for more
          r += pr * wgt;
          g += pg * wgt;
          b += pb * wgt;
          wsum += wgt;
        }
        if (wsum == 0) return;
        final hsl = HSLColor.fromColor(
            Color.fromARGB(255, (r / wsum).round(), (g / wsum).round(), (b / wsum).round()));
        c = hsl
            .withLightness(hsl.lightness.clamp(0.16, 0.30).toDouble())
            .withSaturation(hsl.saturation.clamp(0.25, 0.65).toDouble())
            .toColor();
        _ambientCache[key] = c;
      } catch (_) {
        return;
      }
    }
    if (mounted) _ambientN.value = c;
  }

  void _fetchLogo(PlexItem it) {
    if (it.isSeerr || it.type == 'genre') return;
    if (it.type == 'episode' && it.gpKey != null) _fetchMeta(it.gpKey!);
    _fetchMeta(it.key);
  }

  Future<void> _fetchMeta(String k) async {
    if (_meta.containsKey(k)) return;
    _meta[k] = null;
    try {
      final m = await Plex.metadata(k);
      if (!mounted) return;
      _meta[k] = m;
      _metaTick.value++;
    } catch (_) {}
  }

  void _open(PlexItem it) async {
    if (it.key.startsWith('lib:')) {
      final m = _secs.where((x) => 'lib:${x.key}' == it.key);
      if (m.isNotEmpty) {
        await Navigator.of(context)
            .push(MaterialPageRoute(builder: (_) => LibraryScreen(section: m.first)));
      }
      return;
    }
    if (it.key.startsWith('genre:')) {
      final p = _genrePaths[it.key];
      if (p != null) {
        await Navigator.of(context).push(MaterialPageRoute(
            builder: (_) => LibraryScreen(paths: p, title: it.title)));
      }
      return;
    }
    if (it.key.startsWith('seerrgenre:')) {
      final g = Seerr.genres[it.key];
      if (g != null)
        await Navigator.of(context).push(fadeRoute(SeerrGenreScreen(genre: g)));
      return;
    }
    if (it.isSeerr) {
      final m = Seerr.known[it.key];
      if (m != null)
        await Navigator.of(context)
            .push(fadeRoute(SeerrDetailScreen(media: m)));
      if (_discover) _reloadRequests();
      return;
    }
    await Navigator.of(context)
        .push(fadeRoute(DetailScreen(ratingKey: it.key)));
    _refreshDeck();
  }

  /// After a request, refresh "Your requests" without moving anything else.
  Future<void> _reloadRequests() async {
    try {
      final r =
          (await Seerr.myRequests(take: 15)).map((m) => m.toItem()).toList();
      if (!mounted || !_discover) return;
      setState(() {
        final i = _rows.indexWhere((x) => x.title == 'Your requests');
        final row = _Row('Your requests', r, titles: true, previews: false);
        if (i >= 0) {
          _rows[i] = row;
        } else if (r.isNotEmpty) {
          _rows.insert(0, row);
        }
      });
    } catch (_) {}
  }

  void _expand(PlexItem it) async {
    if (it.isSeerr) {
      _open(it);
      return;
    }
    await Navigator.of(context)
        .push(fadeRoute(DetailScreen(ratingKey: it.key, expanded: true)));
    _refreshDeck();
  }

  void _play(PlexItem it) async {
    if (!it.isPlayable) {
      _open(it);
      return;
    }
    final start = await askResume(context, it);
    if (start == null || !mounted) return;
    await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => PlayerScreen(ratingKey: it.key, startMs: start)));
    _refreshDeck();
  }

  // ---- "Continue from another device": the newest thing you were partway through is newer than anything
  // this device has played, so it was watched somewhere else.
  static bool _elsewhereChecked = false;
  PlexItem? _elsewhere;
  String _elsewhereDevice = '';
  bool _elsewhereShown = false;

  Future<void> _checkElsewhere(List<PlexItem> deck) async {
    if (_elsewhereChecked) return;
    _elsewhereChecked = true;
    PlexItem? best;
    for (final x in deck) {
      if (x.viewOffset <= 0 || x.duration <= 0 || x.lastViewedAt <= 0) continue;
      if (best == null || x.lastViewedAt > best.lastViewedAt) best = x;
    }
    final it = best;
    if (it == null) return;
    // only if it was played in the last 5 minutes, otherwise it is just noise
    final recent = DateTime.now().millisecondsSinceEpoch ~/ 1000 - 5 * 60;
    if (it.lastViewedAt < recent || it.lastViewedAt <= Prefs.lastPlayedAt + 90) return;
    if (Prefs.dismissedBanner == '${it.key}@${it.viewOffset}') return;
    final d = await Plex.lastDevice(it.key);
    if (d.$2 || !mounted) return; // that was this device
    setState(() {
      _elsewhere = it;
      _elsewhereDevice = d.$1;
      _elsewhereShown = true;
    });
  }

  void _dismissElsewhere() {
    final it = _elsewhere;
    if (it != null) Prefs.dismissedBanner = '${it.key}@${it.viewOffset}';
    // fade it out and keep it mounted: pulling a button out from under a click is what we avoid
    setState(() => _elsewhereShown = false);
  }

  Future<void> _resumeElsewhere() async {
    final it = _elsewhere;
    if (it == null) return;
    _dismissElsewhere();
    await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => PlayerScreen(ratingKey: it.key, startMs: it.viewOffset)));
    _refreshDeck();
  }

  Widget _elsewhereBanner(double h) {
    final it = _elsewhere;
    if (it == null) {
      return const Positioned(left: 0, top: 0, child: SizedBox.shrink());
    }
    String t(int ms) {
      final s = ms ~/ 1000;
      final hh = s ~/ 3600;
      final m = (s % 3600) ~/ 60;
      final ss = (s % 60).toString().padLeft(2, '0');
      return hh > 0 ? '$hh:${m.toString().padLeft(2, '0')}:$ss' : '$m:$ss';
    }

    final name = it.type == 'episode'
        ? '${it.gpTitle ?? ''} S${it.parentIndex} E${it.index}'
        : it.title;
    final from = _elsewhereDevice.isEmpty ? 'another device' : _elsewhereDevice;
    return Positioned(
      top: h * 0.115,
      left: 0,
      right: 0,
      child: IgnorePointer(
        ignoring: !_elsewhereShown,
        child: ExcludeFocus(
          excluding: !_elsewhereShown,
          child: AnimatedOpacity(
            opacity: _elsewhereShown ? 1 : 0,
            duration: const Duration(milliseconds: 200),
            child: Center(
        child: Container(
          padding: EdgeInsets.symmetric(horizontal: h * 0.024, vertical: h * 0.012),
          decoration: BoxDecoration(
            color: const Color(0xF01C1C1E),
            borderRadius: BorderRadius.circular(h),
            border: Border.all(color: const Color(0x33FFFFFF)),
          ),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Text('Continue ',
                style: reelText(h * 0.024, color: Reel.text2)),
            ConstrainedBox(
              constraints: BoxConstraints(maxWidth: Reel.w(context) * 0.3),
              child: Text(name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: reelText(h * 0.024, weight: FontWeight.w700)),
            ),
            Text(' from $from, at ${t(it.viewOffset)}',
                style: reelText(h * 0.024, color: Reel.text2)),
            SizedBox(width: h * 0.024),
            PillButton('Resume', primary: true, onPressed: _resumeElsewhere),
            SizedBox(width: h * 0.01),
            PillButton('Not now', onPressed: _dismissElsewhere),
          ]),
        ),
            ),
          ),
        ),
      ),
    );
  }

  void _selectTab(int i) async {
    if (_tabs[i] == 'Libraries') {
      await Navigator.of(context)
          .push(MaterialPageRoute(builder: (_) => const LibrariesScreen()));
      return;
    }
    if (_tabs[i] == 'Live') {
      await Navigator.of(context)
          .push(MaterialPageRoute(builder: (_) => const LiveScreen()));
      return;
    }
    if (_tabs[i] == 'Search') {
      await Navigator.of(context)
          .push(MaterialPageRoute(builder: (_) => const SearchScreen()));
      return;
    }
    if (_tabs[i] == 'Settings') {
      await Navigator.of(context)
          .push(MaterialPageRoute(builder: (_) => const SettingsScreen()));
      return;
    }
    if (i == _tab) return;
    setState(() {
      _tab = i;
      _rows = [];
      _hero = null;
    });
    if (_scroll.hasClients) _scroll.jumpTo(0);
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    // Flutter's own focus movement also jumps the list to the new selection (no animation), and then
    // our smooth scroll runs on top of it: that jump-then-slide is the stutter. Our cards and buttons
    // already scroll themselves smoothly, so the built-in jump is switched off here.
    return FocusTraversalGroup(
      policy: ReadingOrderTraversalPolicy(
          requestFocusCallback: (node,
                  {alignment, alignmentPolicy, curve, duration}) =>
              node.requestFocus()),
      child: Scaffold(
      body: Stack(children: [
        // Backdrop of whatever you're on (the Featured spotlight draws its own)
        if (!_spot) ...[
          Positioned.fill(
            child: RepaintBoundary(
              child: ValueListenableBuilder<PlexItem?>(
                valueListenable: _heroN,
                builder: (c, hero, _) {
                  final backdrop = hero == null ? null : _backdrop(hero);
                  return AnimatedSwitcher(
                    duration: const Duration(milliseconds: 600),
                    child: backdrop == null
                        ? const SizedBox.expand()
                        : SizedBox(
                            key: ValueKey(backdrop),
                            width: w,
                            height: h,
                            child: NetImage(backdrop, decodeWidth: 1000)),
                  );
                },
              ),
            ),
          ),
          Positioned.fill(
            child: DecoratedBox(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  colors: [
                    Color(0xE6000000),
                    Color(0x99000000),
                    Color(0x00000000)
                  ],
                  stops: [0, 0.4, 0.75],
                ),
              ),
            ),
          ),
          Positioned.fill(
            child: IgnorePointer(
              child: ValueListenableBuilder<Color>(
                valueListenable: _ambientN,
                builder: (c, col, _) => TweenAnimationBuilder<Color?>(
                  tween: ColorTween(end: col),
                  duration: const Duration(milliseconds: 900),
                  builder: (c, v, _) => DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.bottomLeft,
                        end: Alignment.topRight,
                        colors: [
                          (v ?? col).withOpacity(0.55),
                          (v ?? col).withOpacity(0.0)
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            height: h * 0.62,
            child: const DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0x00000000),
                    Color(0xB3000000),
                    Color(0xFF000000)
                  ],
                ),
              ),
            ),
          ),
        ],

        // The focused title, top left (fades as the rows slide up)
        Positioned(
          left: w * 0.05,
          top: h * 0.15,
          width: w * 0.45,
          child: RepaintBoundary(
            child: AnimatedBuilder(
              animation: Listenable.merge([_heroN, _fadeN, _metaTick]),
              builder: (c, _) {
                final hero = _hero;
                // the big Featured cards at the top carry their own titles
                if (hero == null || (_rows.isNotEmpty && _rows.first.big))
                  return const SizedBox.shrink();
                return Opacity(
                    opacity: _fadeN.value, child: _heroInfo(hero, h));
              },
            ),
          ),
        ),

        // Rows (start below the tab bar so nothing slides behind it)
        Positioned(
          left: 0,
          right: 0,
          top: _spot ? 0 : h * 0.13,
          bottom: 0,
          child: ClipRect(
            child: _loading
                ? (_discover
                    ? _Skeleton(h: h, w: Reel.w(context))
                    : Center(
                        child: Text('Loading…',
                            style: reelText(h * 0.03, color: Reel.text2))))
                : _error != null
                    ? Center(
                        child:
                            Column(mainAxisSize: MainAxisSize.min, children: [
                          Text(_error!,
                              textAlign: TextAlign.center,
                              style: reelText(h * 0.026, color: Reel.text2)),
                          SizedBox(height: h * 0.03),
                          _needSeerr
                              ? PillButton('Set Seerr address', autofocus: true,
                                  onPressed: () async {
                                  if (await askSeerrAddress(context)) _load();
                                })
                              : PillButton('Try again',
                                  autofocus: true, onPressed: _load),
                        ]),
                      )
                    // Built as you reach them: with a full-screen banner and a dozen rows, building
                    // them all at once is what made scrolling back up stutter.
                    : ListView.builder(
                        controller: _scroll,
                        clipBehavior: Clip.none,
                        cacheExtent: h * 3,
                        padding: EdgeInsets.only(
                            top: _spot ? 0 : h * 0.45, bottom: h * 0.1),
                        itemCount: _rows.length,
                        itemBuilder: (c, ri) {
                          if (_spot && ri == 0)
                            return RepaintBoundary(child: _spotlightView(w, h));
                          final r = _rows[ri];
                          return RepaintBoundary(
                            child: CardRow(
                              key: ValueKey('${_tab}_${r.title}'),
                              autofocusFirst: !_spot && ri == 0,
                              big: r.big,
                              previewDelay: r.previewDelay,
                              title: r.title,
                              items: r.items,
                              landscape: r.landscape,
                              titles: r.titles,
                              previews: r.previews,
                              onPressed: _open,
                              onFocused: _focused,
                              onLongPress: _expand,
                            ),
                          );
                        },
                      ),
          ),
        ),

        // Glass tab bar
        Positioned(
          top: h * 0.035,
          left: 0,
          right: 0,
          child: Center(child: _tabBar(h)),
        ),

        _elsewhereBanner(h),

        // Quiet mode: a small moon beside full screen. Mutes previews, saved until you change it.
        Positioned(
          top: h * 0.035,
          right: w * 0.03 + h * 0.072,
          child: ValueListenableBuilder<bool>(
            valueListenable: _quietN,
            builder: (c, quiet, _) => Pressable(
              onPressed: () {
                Prefs.quiet = !quiet;
                _quietN.value = Prefs.quiet;
                Preview.applyQuiet();
              },
              scrollAlignment: 0,
              builder: (c, f) => Tooltip(
                message: quiet
                    ? 'Quiet mode is on: previews are muted'
                    : 'Quiet mode (mute previews)',
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: EdgeInsets.all(h * 0.012),
                  decoration: BoxDecoration(
                    color: f ? Colors.white : const Color(0x40000000),
                    shape: BoxShape.circle,
                    border: Border.all(color: const Color(0x26FFFFFF), width: 1),
                  ),
                  child: Icon(
                      quiet ? Icons.dark_mode_rounded : Icons.dark_mode_outlined,
                      color: f
                          ? Colors.black
                          : (quiet ? const Color(0xFFFFD98A) : Colors.white70),
                      size: h * 0.032),
                ),
              ),
            ),
          ),
        ),

        // Full screen on/off (also F11, Alt+Enter, or Esc on this screen to leave)
        Positioned(
          top: h * 0.035,
          right: w * 0.03,
          child: ValueListenableBuilder<bool>(
            valueListenable: isFullscreen,
            builder: (c, full, _) => Pressable(
              onPressed: toggleFullscreen,
              scrollAlignment: 0,
              builder: (c, f) => Tooltip(
                message:
                    full ? 'Exit full screen (Esc / F11)' : 'Full screen (F11)',
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: EdgeInsets.all(h * 0.012),
                  decoration: BoxDecoration(
                    color: f ? Colors.white : const Color(0x40000000),
                    shape: BoxShape.circle,
                    border:
                        Border.all(color: const Color(0x26FFFFFF), width: 1),
                  ),
                  child: Icon(
                      full
                          ? Icons.fullscreen_exit_rounded
                          : Icons.fullscreen_rounded,
                      color: f ? Colors.black : Colors.white,
                      size: h * 0.032),
                ),
              ),
            ),
          ),
        ),
      ]),
    ));
  }

  /// Rest on the banner for a few seconds and its trailer (or a scene) starts playing behind it.
  void _spotPreview(PlexItem it) {
    if (_spotShown == it.key) return;
    // not while the page is still sliding: the scroll listener starts it once it has arrived
    if (_scroll.hasClients && _scroll.offset > Reel.h(context) * 0.03) return;
    _spotShown = it.key;
    _spotTimer?.cancel();
    // looks up the trailer (or best scene) now, and starts playing after the rest
    Preview.focus(it, delay: const Duration(seconds: 4));
  }

  void _spotMove(int d) {
    final items = _rows.first.items;
    final n = items.length;
    final i = (_spotIdx.value + d + n) % n;
    Preview.stop();
    _spotShown = null;
    _spotIdx.value = i;
  }

  void _spotFocus(bool f) {
    if (!_spot) return;
    final items = _rows.first.items;
    final it = items[_spotIdx.value % items.length];
    if (f) {
      if (_scroll.hasClients && _scroll.offset > 0) {
        // An even glide: an ease-out slows to a sub-pixel crawl at the end, which looks choppy on a
        // fast monitor.
        _scroll.animateTo(0,
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeInOutSine);
      }
      _spotShown = null;
      _spotPreview(it);
    } else {
      Future.microtask(() {
        if (!mounted || !_spot) return;
        if (!_playNode.hasFocus) {
          _spotTimer?.cancel();
          _spotShown = null;
          Preview.blur(it);
        }
      });
    }
  }

  /// The big banner at the top of Home, like the Apple TV app: the title's picture fills the
  /// screen, its logo, details and Play button sit on the left, and the dots show how many there are.
  /// Left on Play (or Right on the i button) flips to the previous / next one; a preview plays
  /// after a few seconds.
  Widget _spotlightView(double w, double h) {
    final items = _rows.first.items;
    final bh = h * 0.84;
    return SizedBox(
      height: bh,
      width: w,
      child: Focus(
        canRequestFocus: false,
        skipTraversal: true,
        onKeyEvent: (n, e) {
          if (e is KeyUpEvent) return KeyEventResult.ignored;
          if (e.logicalKey == LogicalKeyboardKey.arrowLeft &&
              _playNode.hasFocus) {
            _spotMove(-1);
            return KeyEventResult.handled;
          }
          if (e.logicalKey == LogicalKeyboardKey.arrowRight &&
              _playNode.hasFocus) {
            _spotMove(1);
            return KeyEventResult.handled;
          }
          return KeyEventResult.ignored;
        },
        child: ValueListenableBuilder<int>(
          valueListenable: _spotIdx,
          builder: (c, idx0, _) {
            final idx = idx0 % items.length;
            final it = items[idx];
            _fetchLogo(it);
            _spotPreview(it);
            final bd = _backdrop(it);
            // keep the next one's picture ready so flicking along is instant
            final next = _backdrop(items[(idx + 1) % items.length]);
            if (next != null) ImageLoader.load(next);
            return Stack(fit: StackFit.expand, children: [
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 500),
                child: bd == null
                    ? const SizedBox.expand(key: ValueKey('none'))
                    : SizedBox(
                        key: ValueKey(bd),
                        width: w,
                        height: bh,
                        child: NetImage(bd, decodeWidth: 1000)),
              ),
              Positioned.fill(child: PreviewVideo.hero(it.key)),
              const Positioned.fill(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        Color(0xE6000000),
                        Color(0x80000000),
                        Color(0x00000000)
                      ],
                      stops: [0, 0.45, 0.8],
                    ),
                  ),
                ),
              ),
              const Positioned.fill(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [
                        Color(0x00000000),
                        Color(0x00000000),
                        Color(0xFF000000)
                      ],
                      stops: [0, 0.6, 1],
                    ),
                  ),
                ),
              ),
              Positioned(
                left: w * 0.05,
                bottom: h * 0.12,
                width: w * 0.42,
                // the title, details and button drop while the preview plays, so the picture is easy to see
                child: ValueListenableBuilder<String?>(
                  valueListenable: Preview.showing,
                  builder: (c, showing, child) => AnimatedSlide(
                      offset: Offset(0, showing == null ? 0 : 0.3),
                      duration: const Duration(milliseconds: 600),
                      curve: Curves.easeInOutSine,
                      child: child),
                  child: AnimatedBuilder(
                      animation: _metaTick,
                      builder: (c, _) => _spotInfo(it, h)),
                ),
              ),
              // Arrows for the mouse
              Positioned(
                left: w * 0.015,
                top: 0,
                bottom: 0,
                child: Center(
                  child: HoverButton(Icons.chevron_left_rounded,
                      size: h * 0.07,
                      tooltip: 'Previous',
                      onPressed: () => _spotMove(-1)),
                ),
              ),
              Positioned(
                right: w * 0.015,
                top: 0,
                bottom: 0,
                child: Center(
                  child: HoverButton(Icons.chevron_right_rounded,
                      size: h * 0.07,
                      tooltip: 'Next',
                      onPressed: () => _spotMove(1)),
                ),
              ),
              Positioned(
                left: 0,
                right: 0,
                bottom: h * 0.06,
                child: ValueListenableBuilder<String?>(
                  valueListenable: Preview.showing,
                  builder: (c, showing, child) => AnimatedOpacity(
                      opacity: showing == null ? 1 : 0,
                      duration: const Duration(milliseconds: 300),
                      child: child),
                  child:
                    Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                  for (var i = 0; i < items.length; i++)
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      margin: EdgeInsets.symmetric(horizontal: h * 0.005),
                      width: i == idx ? h * 0.024 : h * 0.008,
                      height: h * 0.008,
                      decoration: BoxDecoration(
                        color:
                            i == idx ? Colors.white : const Color(0x66FFFFFF),
                        borderRadius: BorderRadius.circular(h),
                      ),
                    ),
                ]),
                ),
              ),
            ]);
          },
        ),
      ),
    );
  }

  Widget _spotInfo(PlexItem it, double h) {
    final logoKey =
        it.type == 'episode' && it.gpKey != null ? it.gpKey! : it.key;
    final logo = _meta[logoKey]?.logo;
    final genres = (_meta[it.key] ?? it).genres;
    final kind = it.type == 'movie'
        ? 'Movie'
        : (it.type == 'show'
            ? 'TV Show'
            : (it.type == 'episode' ? 'Episode' : ''));
    final meta = [kind, ...genres.take(2), if (it.year > 0) '${it.year}']
        .where((s) => s.isNotEmpty)
        .join(' · ');
    final titleStyle = reelText(h * 0.08, weight: FontWeight.w800, height: 1.0)
        .copyWith(letterSpacing: h * 0.012);
    return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            height: h * 0.17,
            child: Align(
              alignment: Alignment.bottomLeft,
              child: logo != null
                  ? Image.network(Plex.raw(logo)!,
                      fit: BoxFit.contain,
                      alignment: Alignment.bottomLeft,
                      errorBuilder: (_, __, ___) => Text(
                          it.displayTitle.toUpperCase(),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: titleStyle))
                  : Text(it.displayTitle.toUpperCase(),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: titleStyle),
            ),
          ),
          SizedBox(height: h * 0.02),
          Row(mainAxisSize: MainAxisSize.min, children: [
            Flexible(
              child: Text(meta,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: reelText(h * 0.021,
                      weight: FontWeight.w500, color: Colors.white)),
            ),
            if (it.contentRating.isNotEmpty) ...[
              SizedBox(width: h * 0.012),
              Badges([it.contentRating])
            ],
          ]),
          SizedBox(height: h * 0.012),
          Text(it.summary,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: reelText(h * 0.021,
                  color: const Color(0xCCFFFFFF), height: 1.4)),
          SizedBox(height: h * 0.025),
          Row(children: [
            PillButton(
                it.type == 'show'
                    ? 'Go to show'
                    : (it.type == 'episode' ? 'Go to episode' : 'Go to movie'),
                icon: Icons.arrow_forward_rounded,
                primary: true,
                autofocus: true,
                focusNode: _playNode,
                scrollAlignment: -1,
                onFocusChange: _spotFocus, onPressed: () {
              Preview.handOff();
              _open(it);
            }),
          ]),
        ]);
  }

  Widget _heroInfo(PlexItem it, double h) {
    final logoKey =
        it.type == 'episode' && it.gpKey != null ? it.gpKey! : it.key;
    final logo = _meta[logoKey]?.logo;
    final badges = (_meta[it.key] ?? it).badges;
    return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            height: h * 0.15,
            child: Align(
              alignment: Alignment.bottomLeft,
              child: logo != null
                  ? Image.network(Plex.raw(logo)!,
                      fit: BoxFit.contain,
                      alignment: Alignment.bottomLeft,
                      errorBuilder: (_, __, ___) => _titleText(it, h))
                  : _titleText(it, h),
            ),
          ),
          SizedBox(height: h * 0.014),
          Text(it.metaLine,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: reelText(h * 0.023,
                  weight: FontWeight.w500, color: Reel.text2)),
          if (badges.isNotEmpty) ...[
            SizedBox(height: h * 0.01),
            Badges(badges),
          ],
          SizedBox(height: h * 0.012),
          Text(it.summary,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: reelText(h * 0.024,
                  color: const Color(0xD9FFFFFF), height: 1.35)),
          SizedBox(height: h * 0.025),
          Row(children: [
            PillButton(
                it.isPlayable
                    ? (it.viewOffset > 0 ? 'Resume' : 'Play')
                    : 'Open',
                icon: Icons.play_arrow_rounded,
                primary: true,
                autofocus: true,
                onPressed: () => _play(it)),
            SizedBox(width: h * 0.016),
            PillButton('Details', onPressed: () => _open(it)),
          ]),
        ]);
  }

  Widget _titleText(PlexItem it, double h) => Text(it.displayTitle,
      maxLines: 2,
      overflow: TextOverflow.ellipsis,
      style: reelText(h * 0.062, weight: FontWeight.w700, height: 1.05));

  Widget _tabBar(double h) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(h),
      child: RepaintBoundary(
        child: Container(
          padding:
              EdgeInsets.symmetric(horizontal: h * 0.012, vertical: h * 0.008),
          decoration: BoxDecoration(
            color: const Color(0x8C1C1C1E),
            borderRadius: BorderRadius.circular(h),
            border: Border.all(color: const Color(0x26FFFFFF), width: 1),
          ),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            for (var i = 0; i < _tabs.length; i++)
              Pressable(
                onPressed: () => _selectTab(i),
                scrollAlignment: 0,
                builder: (c, f) => AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: EdgeInsets.symmetric(
                      horizontal: h * 0.026, vertical: h * 0.011),
                  decoration: BoxDecoration(
                    color: f
                        ? Colors.white
                        : (i == _tab
                            ? const Color(0x40FFFFFF)
                            : Colors.transparent),
                    borderRadius: BorderRadius.circular(h),
                  ),
                  child: Text(_tabs[i],
                      style: reelText(h * 0.023,
                          weight: i == _tab ? FontWeight.w600 : FontWeight.w500,
                          color: f
                              ? Colors.black
                              : (i == _tab ? Colors.white : Colors.white70))),
                ),
              ),
          ]),
        ),
      ),
    );
  }
}


/// Grey placeholder title and poster rows that pulse while Seerr loads.
class _Skeleton extends StatefulWidget {
  final double h;
  final double w;
  const _Skeleton({required this.h, required this.w});

  @override
  State<_Skeleton> createState() => _SkeletonState();
}

class _SkeletonState extends State<_Skeleton>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
      vsync: this, duration: const Duration(milliseconds: 900))
    ..repeat(reverse: true);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  Widget _bar(double w, double h, double r, double left, double top) =>
      Positioned(
        left: left,
        top: top,
        width: w,
        height: h,
        child: DecoratedBox(
            decoration: BoxDecoration(
                color: const Color(0x26FFFFFF),
                borderRadius: BorderRadius.circular(r))),
      );

  @override
  Widget build(BuildContext context) {
    final h = widget.h, w = widget.w;
    final left = w * 0.05;
    final cw = w * 0.14;
    final gap = h * 0.022;
    return FadeTransition(
      opacity: Tween(begin: 0.35, end: 1.0).animate(_c),
      child: Stack(children: [
        _bar(w * 0.38, h * 0.05, h * 0.012, left, h * 0.06),
        _bar(w * 0.45, h * 0.022, h * 0.008, left, h * 0.14),
        _bar(w * 0.40, h * 0.022, h * 0.008, left, h * 0.18),
        _bar(w * 0.16, h * 0.032, h * 0.01, left, h * 0.37),
        for (var i = 0; i * (cw + gap) < w; i++)
          _bar(cw, cw * 9 / 16, h * 0.014, left + i * (cw + gap), h * 0.43),
      ]),
    );
  }
}
