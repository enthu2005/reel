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
import '../ui/widgets.dart';
import 'detail.dart';
import 'discover.dart';
import 'libraries.dart';
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
      // stutter, so it stops as soon as the banner is out of the way and comes back when you return.
      final away = _scroll.offset > h * 0.35;
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
    setState(() {
      _loading = _rows.isEmpty;
      _error = null;
    });
    _needSeerr = false;
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
        if (_tab == 0) {
          final parts = await Future.wait<List<_Row>>([
            Plex.onDeck().then((d) {
              final keep = d
                  .where(
                      (x) => !hiddenSectionKeys.contains(x.librarySectionKey))
                  .toList();
              return keep.isEmpty
                  ? <_Row>[]
                  : [_Row('Continue Watching', keep, landscape: true)];
            }),
            for (final s in sections.where((s) =>
                (s.type == 'movie' || s.type == 'show') && !s.hiddenFromHome))
              Plex.recentlyAdded(s.key).then((r) => r.isEmpty
                  ? <_Row>[]
                  : [_Row('Recently Added · ${s.title}', r)]),
          ]);
          for (final p in parts) {
            rows.addAll(p);
          }
          _secs = sections
              .where((s) =>
                  (s.type == 'movie' || s.type == 'show') && !s.hiddenFromHome)
              .toList();
          _addFeatured(rows);
        } else {
          final want = _tab == 1 ? 'movie' : 'show';
          final mine2 = sections
              .where((s) => s.type == want && !s.hiddenFromHome)
              .toList();
          _secs = mine2;
          final parts = await Future.wait<List<_Row>>([
            Plex.onDeck().then((d) {
              final keep = d
                  .where((x) =>
                      !hiddenSectionKeys.contains(x.librarySectionKey) &&
                      (want == 'movie'
                          ? x.type == 'movie'
                          : x.type == 'episode'))
                  .toList();
              return keep.isEmpty
                  ? <_Row>[]
                  : [_Row('Continue Watching', keep, landscape: true)];
            }).catchError((_) => <_Row>[]),
            for (final s in mine2)
              Future.wait([Plex.recentlyAdded(s.key), Plex.all(s.key)])
                  .then((l) => [
                        if (l[0].isNotEmpty)
                          _Row('Recently Added · ${s.title}', l[0],
                              titles: true),
                        if (l[1].isNotEmpty)
                          _Row('All ${s.title}', l[1], titles: true),
                      ]),
          ]);
          for (final p in parts) {
            rows.addAll(p);
          }
        }
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
        _rows = rows;
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

  Future<void> _spotlight(int token) async {
    final copy = List<_Row>.of(_rows);
    try {
      if (_tab == 0) {
        await _addSpotlight(copy, _secs).timeout(const Duration(seconds: 30));
      } else {
        await _addExtras(copy, _secs).timeout(const Duration(seconds: 40));
      }
    } catch (_) {
      return; // just carry on without them
    }
    if (!mounted || token != _loadToken) return;
    // The banner fills the screen, so have its first pictures in hand before it appears.
    if (copy.isNotEmpty && copy.first.big) {
      final art =
          copy.first.items.take(4).map(_backdrop).whereType<String>().toList();
      if (art.isNotEmpty) {
        await Future.wait([for (final u in art) ImageLoader.load(u)])
            .timeout(const Duration(seconds: 6), onTimeout: () => const []);
      }
      if (!mounted || token != _loadToken) return;
    }
    setState(() => _rows = copy);
  }

  /// Movies / TV Shows: "Because you watched…" and a row per genre, added after the page is up.
  Future<void> _addExtras(List<_Row> rows, List<PlexSection> secs) async {
    Future<List<PlexItem>> safe(Future<List<PlexItem>> f) =>
        f.catchError((_) => <PlexItem>[]);
    final seeds = <MapEntry<String, String>>[];
    for (final s in secs) {
      for (final m in await safe(Plex.recentlyWatched(s.key, count: 2))) {
        seeds.add(MapEntry(m.key, m.title));
      }
    }
    final related = await Future.wait(
        [for (final s in seeds.take(3)) safe(Plex.related(s.key))]);
    final because = <_Row>[];
    for (var i = 0; i < related.length; i++) {
      final fresh = related[i].where((x) => x.viewCount == 0).take(20).toList();
      if (fresh.length >= 4)
        because.add(
            _Row('Because you watched ${seeds[i].value}', fresh, titles: true));
    }
    because.addAll(await _peopleRows(seeds.take(2).toList(), secs));
    // genres, biggest libraries first
    final genreRows = <_Row>[];
    for (final s in secs) {
      List<List<String>> gs;
      try {
        gs = await Plex.genresOf(s.key);
      } catch (_) {
        continue;
      }
      final picked = gs.take(10).toList();
      final lists =
          await Future.wait([for (final g in picked) safe(Plex.byGenre(g[0]))]);
      for (var i = 0; i < picked.length; i++) {
        if (lists[i].length >= 4)
          genreRows.add(_Row(picked[i][1], lists[i], titles: true));
      }
    }
    // "All …" stays last, after the browsing rows
    final allAt = rows.indexWhere((r) => r.title.startsWith('All '));
    final at = allAt < 0 ? rows.length : allAt;
    rows.insertAll(at, [...because, ...genreRows]);
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
        if (i < pool.length && featured.length < 12 && seen.add(pool[i].key))
          featured.add(pool[i]);
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

  /// "Because you watched X · More with <actor>" and "... · Directed by <director>" rows.
  Future<List<_Row>> _peopleRows(
      List<MapEntry<String, String>> seeds, List<PlexSection> secs) async {
    final out = <_Row>[];
    final usedPeople = <String>{};
    for (final seed in seeds) {
      try {
        final m = await Plex.metadata(seed.key);
        final people = <MapEntry<String, CastMember>>[
          if (m.cast.isNotEmpty) MapEntry('More with', m.cast.first),
          if (m.directors.isNotEmpty) MapEntry('Directed by', m.directors.first),
        ];
        for (final p in people) {
          if (!usedPeople.add(p.value.name)) continue;
          final lists = await Future.wait([
            for (final s
                in secs.where((s) => s.type == 'movie' || s.type == 'show'))
              Plex.withPerson(s.key, p.value.filter)
                  .catchError((_) => <PlexItem>[]),
          ]);
          final seen = <String>{m.key};
          final found = <PlexItem>[
            for (final l in lists)
              for (final x in l)
                if (seen.add(x.key)) x
          ]..sort((a, b) => b.year.compareTo(a.year));
          if (found.length >= 4) {
            out.add(_Row(
                'Because you watched ${seed.value} · ${p.key} ${p.value.name}',
                found.take(20).toList(),
                titles: true));
          }
        }
      } catch (_) {}
    }
    return out;
  }

  /// "Because you watched…" rows, added once the page is already up.
  Future<void> _addSpotlight(List<_Row> rows, List<PlexSection> secs) async {
    Future<List<PlexItem>> safe(Future<List<PlexItem>> f) =>
        f.catchError((_) => <PlexItem>[]);

    // what you watched lately: finished films, plus the shows behind your Up Next episodes
    final seedLists = await Future.wait([
      for (final s in secs.where((s) => s.type == 'movie'))
        safe(Plex.recentlyWatched(s.key, count: 2)),
    ]);
    final seeds = <MapEntry<String, String>>[
      for (final l in seedLists)
        for (final m in l) MapEntry(m.key, m.title),
    ];
    final shows = <String>{};
    for (final r in rows.where((r) => r.title == 'Continue Watching')) {
      for (final e in r.items) {
        if (e.gpKey != null && shows.length < 2 && shows.add(e.gpKey!))
          seeds.add(MapEntry(e.gpKey!, e.gpTitle ?? e.title));
      }
    }
    final seedsUsed = seeds.take(4).toList();

    final related = await Future.wait(
        [for (final s in seedsUsed) safe(Plex.related(s.key))]);

    // Because you watched … (only things you haven't seen yet)
    final because = <_Row>[];
    for (var i = 0; i < seedsUsed.length; i++) {
      final fresh = related[i].where((x) => x.viewCount == 0).take(20).toList();
      if (fresh.length >= 4)
        because.add(_Row('Because you watched ${seedsUsed[i].value}', fresh,
            titles: true));
    }

    rows.addAll(because);

    // actor / director rows from the last things you watched
    rows.addAll(await _peopleRows(seedsUsed.take(2).toList(), secs));
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
    if (_tab != 0) return;
    try {
      final deck = await Plex.onDeck();
      if (!mounted) return;
      setState(() {
        final i = _rows.indexWhere((r) => r.title == 'Continue Watching');
        if (i >= 0) {
          _rows[i] = _Row('Continue Watching', deck, landscape: true);
        } else if (deck.isNotEmpty) {
          _rows.insert(_rows.isNotEmpty && _rows.first.big ? 1 : 0,
              _Row('Continue Watching', deck, landscape: true));
        }
      });
    } catch (_) {}
  }

  PlexItem? _pendingHero;

  void _focused(PlexItem it) {
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
      if (art != null && bytes != null) _tint(art, bytes);
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
    await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) =>
            PlayerScreen(ratingKey: it.key, startMs: it.viewOffset)));
    _refreshDeck();
  }

  void _selectTab(int i) async {
    if (_tabs[i] == 'Libraries') {
      await Navigator.of(context)
          .push(MaterialPageRoute(builder: (_) => const LibrariesScreen()));
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
    return Scaffold(
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
                ? Center(
                    child: Text('Loading…',
                        style: reelText(h * 0.03, color: Reel.text2)))
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
    );
  }

  /// Rest on the banner for a few seconds and its trailer (or a scene) starts playing behind it.
  void _spotPreview(PlexItem it) {
    if (_spotShown == it.key) return;
    _spotShown = it.key;
    _spotTimer?.cancel();
    // looks up the trailer (or best scene) now, and starts playing after the rest
    Preview.focus(it, delay: Motion.previewDelay);
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
        _scroll.animateTo(0,
            duration: const Duration(milliseconds: 320),
            curve: Curves.easeOutCubic);
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
                child: AnimatedBuilder(
                    animation: _metaTick, builder: (c, _) => _spotInfo(it, h)),
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
