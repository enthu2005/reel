import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../main.dart' show isFullscreen, toggleFullscreen;
import '../plex.dart';
import '../ui/net_image.dart';
import '../ui/preview.dart';
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
      {this.landscape = false,
      this.big = false,
      this.titles = false,
      this.previews = true,
      this.previewDelay = const Duration(milliseconds: 900)});
}

/// Home: a big backdrop of whatever you're on, a glass tab bar, and rows of posters.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const _tabs = ['Home', 'Movies', 'TV Shows', 'Libraries', 'Discover', 'Search', 'Settings'];
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
  final _metaTick = ValueNotifier<int>(0);

  static String? _backdrop(PlexItem it) => Plex.img(it.heroArt ?? it.poster, 1280, 720);
  Timer? _heroTimer;
  final _scroll = ScrollController();
  int _loadToken = 0;
  List<PlexSection> _secs = [];
  final _spotIdx = ValueNotifier<int>(0);
  final _playNode = FocusNode();
  final _infoNode = FocusNode();
  bool get _spot => _rows.isNotEmpty && _rows.first.big;
  final Map<String, PlexItem?> _meta = {}; // full details: logo, picture and sound badges

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      final h = Reel.h(context);
      final a = (1 - _scroll.offset / (h * 0.25)).clamp(0.0, 1.0);
      if ((a - _fadeN.value).abs() > 0.02) _fadeN.value = a;
    });
    _load();
  }

  @override
  void dispose() {
    _heroTimer?.cancel();
    _scroll.dispose();
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
            _error = 'Discover shows what\'s trending and popular, and lets you request it with Seerr.\n'
                'Add your Seerr address to start.';
          });
          return;
        }
        rows.addAll(await _discoverRows());
      } else {
        // Ask for every row at once instead of one after another (much quicker over the internet).
        final sections = await Plex.sections();
        if (_tab == 0) {
          final parts = await Future.wait<List<_Row>>([
            Plex.onDeck().then((d) => d.isEmpty ? <_Row>[] : [_Row('Up Next', d, landscape: true)]),
            for (final s in sections.where((s) => s.type == 'movie' || s.type == 'show'))
              Plex.recentlyAdded(s.key)
                  .then((r) => r.isEmpty ? <_Row>[] : [_Row('Recently Added · ${s.title}', r)]),
          ]);
          for (final p in parts) {
            rows.addAll(p);
          }
          _secs = sections.where((s) => s.type == 'movie' || s.type == 'show').toList();
        } else {
          final want = _tab == 1 ? 'movie' : 'show';
          final parts = await Future.wait<List<_Row>>([
            for (final s in sections.where((s) => s.type == want))
              Future.wait([Plex.recentlyAdded(s.key), Plex.all(s.key)]).then((l) => [
                    if (l[0].isNotEmpty) _Row('Recently Added · ${s.title}', l[0], titles: true),
                    if (l[1].isNotEmpty) _Row('All ${s.title}', l[1], titles: true),
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
        await Future.wait(first).timeout(const Duration(seconds: 3), onTimeout: () => const []);
        if (mine != _loadToken || !mounted) return;
      }
      setState(() {
        _rows = rows;
        _loading = false;
        if (rows.isNotEmpty && rows.first.items.isNotEmpty) _hero ??= rows.first.items.first;
      });
      if (_hero != null) _fetchLogo(_hero!);
      _prefetch(rows);
      // Featured and "Because you watched" come in a moment later, so they never hold Home up
      if (_tab == 0 && !_discover) unawaited(_spotlight(mine));
    } catch (e) {
      if (mine != _loadToken || !mounted) return;
      setState(() {
        _loading = false;
        _error = _discover ? "Can't reach Seerr.\n$e" : "Can't reach your Plex server.\n$e";
      });
    }
  }

  Future<void> _spotlight(int token) async {
    final copy = List<_Row>.of(_rows);
    try {
      await _addSpotlight(copy, _secs).timeout(const Duration(seconds: 30));
    } catch (_) {
      return; // just carry on without them
    }
    if (!mounted || token != _loadToken || _tab != 0) return;
    setState(() => _rows = copy);
  }

  /// A "Featured" row at the very top (newly released, recently added and recommended, with a video
  /// preview after a few seconds) and "Because you watched…" rows at the bottom.
  Future<void> _addSpotlight(List<_Row> rows, List<PlexSection> secs) async {
    Future<List<PlexItem>> safe(Future<List<PlexItem>> f) => f.catchError((_) => <PlexItem>[]);

    // what you watched lately: finished films, plus the shows behind your Up Next episodes
    final seedLists = await Future.wait([
      for (final s in secs.where((s) => s.type == 'movie')) safe(Plex.recentlyWatched(s.key, count: 2)),
    ]);
    final seeds = <MapEntry<String, String>>[
      for (final l in seedLists) for (final m in l) MapEntry(m.key, m.title),
    ];
    final shows = <String>{};
    for (final r in rows.where((r) => r.title == 'Up Next')) {
      for (final e in r.items) {
        if (e.gpKey != null && shows.length < 2 && shows.add(e.gpKey!)) seeds.add(MapEntry(e.gpKey!, e.gpTitle ?? e.title));
      }
    }
    final seedsUsed = seeds.take(4).toList();

    final results = await Future.wait([
      Future.wait([for (final s in secs) safe(Plex.recentlyAdded(s.key, count: 6))]),
      Future.wait([for (final s in secs) safe(Plex.recentlyReleased(s.key, count: 6))]),
      Future.wait([for (final s in seedsUsed) safe(Plex.related(s.key))]),
    ]);
    final added = results[0] as List<List<PlexItem>>;
    final released = results[1] as List<List<PlexItem>>;
    final related = results[2] as List<List<PlexItem>>;

    // Because you watched … (only things you haven't seen yet)
    final because = <_Row>[];
    for (var i = 0; i < seedsUsed.length; i++) {
      final fresh = related[i].where((x) => x.viewCount == 0).take(20).toList();
      if (fresh.length >= 4) because.add(_Row('Because you watched ${seedsUsed[i].value}', fresh, titles: true));
    }

    // Featured: take turns between the three kinds so it's a mix
    final seen = <String>{};
    final featured = <PlexItem>[];
    final pools = <List<PlexItem>>[
      [for (final l in released) ...l.take(4)],
      [for (final l in added) ...l.take(4)],
      [for (final r in because.take(2)) ...r.items.take(3)],
    ];
    for (var i = 0; featured.length < 12 && i < 12; i++) {
      for (final pool in pools) {
        if (i < pool.length && featured.length < 12 && seen.add(pool[i].key)) featured.add(pool[i]);
      }
    }
    if (featured.length >= 3) {
      rows.insert(0, _Row('Featured', featured,
          landscape: true, big: true, titles: true, previewDelay: const Duration(seconds: 3)));
    }
    rows.addAll(because);
  }

  Future<List<_Row>> _discoverRows() async {
    Future<List<_Row>> row(String title, Future<List<PlexItem>> f, {bool landscape = false}) async {
      try {
        final items = await f;
        return items.isEmpty ? [] : [_Row(title, items, titles: true, landscape: landscape, previews: false)];
      } catch (_) {
        return [];
      }
    }

    List<PlexItem> items(List<SeerrMedia> l) => l.map((m) => m.toItem()).toList();
    final parts = await Future.wait([
      row('Your requests', Seerr.myRequests(take: 15).then(items)),
      row('Trending', Seerr.trending().then(items)),
      row('Popular movies', Seerr.popularMovies().then(items)),
      row('Popular TV', Seerr.popularShows().then(items)),
      row('Coming soon', Seerr.upcomingMovies().then(items)),
      row('Movie genres', Seerr.genreList('movie').then((g) => g.map((x) => x.toItem()).toList()), landscape: true),
      row('TV genres', Seerr.genreList('tv').then((g) => g.map((x) => x.toItem()).toList()), landscape: true),
    ]);
    final out = [for (final p in parts) ...p];
    if (out.isEmpty) throw Exception('Seerr gave nothing back. Check the address in Settings.');
    return out;
  }

  /// Fetch every row's pictures in the background, a column at a time across all rows (the first
  /// few cards of every row, then the next few…), so they're ready before you scroll to them.
  void _prefetch(List<_Row> rows) {
    final h = Reel.h(context);
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final urls = <String?>[];
    final longest = rows.fold<int>(0, (m, r) => r.items.length > m ? r.items.length : m);
    for (var i = 0; i < longest && i < 400; i++) {
      for (final r in rows) {
        if (i >= r.items.length) continue;
        final ch = r.big ? h * 0.40 : (r.landscape ? h * 0.185 : h * 0.28);
        final cw = r.landscape ? ch * 16 / 9 : ch * 2 / 3;
        urls.add(PosterCard.imageUrl(r.items[i], cw, ch, r.landscape, dpr));
      }
      // the big background picture too, for the first few of each row
      if (i < 6) {
        for (final r in rows) {
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
        final i = _rows.indexWhere((r) => r.title == 'Up Next');
        if (i >= 0) {
          _rows[i] = _Row('Up Next', deck, landscape: true);
        } else if (deck.isNotEmpty) {
          _rows.insert(_rows.isNotEmpty && _rows.first.big ? 1 : 0, _Row('Up Next', deck, landscape: true));
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
      if (art != null) {
        await ImageLoader.load(art).timeout(const Duration(seconds: 2), onTimeout: () => null);
      }
      if (!mounted || _pendingHero != it) return;
      _hero = it;
    });
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
      if (g != null) await Navigator.of(context).push(fadeRoute(SeerrGenreScreen(genre: g)));
      return;
    }
    if (it.isSeerr) {
      final m = Seerr.known[it.key];
      if (m != null) await Navigator.of(context).push(fadeRoute(SeerrDetailScreen(media: m)));
      if (_discover) _reloadRequests();
      return;
    }
    await Navigator.of(context).push(fadeRoute(DetailScreen(ratingKey: it.key)));
    _refreshDeck();
  }

  /// After a request, refresh "Your requests" without moving anything else.
  Future<void> _reloadRequests() async {
    try {
      final r = (await Seerr.myRequests(take: 15)).map((m) => m.toItem()).toList();
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
    await Navigator.of(context).push(fadeRoute(DetailScreen(ratingKey: it.key, expanded: true)));
    _refreshDeck();
  }

  void _play(PlexItem it) async {
    if (!it.isPlayable) {
      _open(it);
      return;
    }
    await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => PlayerScreen(ratingKey: it.key, startMs: it.viewOffset)));
    _refreshDeck();
  }

  void _selectTab(int i) async {
    if (_tabs[i] == 'Libraries') {
      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => const LibrariesScreen()));
      return;
    }
    if (_tabs[i] == 'Search') {
      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => const SearchScreen()));
      return;
    }
    if (_tabs[i] == 'Settings') {
      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => const SettingsScreen()));
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
                      : SizedBox(key: ValueKey(backdrop), width: w, height: h, child: NetImage(backdrop)),
                );
              },
            ),
          ),
        ),
        Positioned.fill(
          child: DecoratedBox(
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                colors: [Color(0xE6000000), Color(0x99000000), Color(0x00000000)],
                stops: [0, 0.4, 0.75],
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
                colors: [Color(0x00000000), Color(0xB3000000), Color(0xFF000000)],
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
                if (hero == null || (_rows.isNotEmpty && _rows.first.big)) return const SizedBox.shrink();
                return Opacity(opacity: _fadeN.value, child: _heroInfo(hero, h));
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
                ? Center(child: Text('Loading…', style: reelText(h * 0.03, color: Reel.text2)))
                : _error != null
                    ? Center(
                        child: Column(mainAxisSize: MainAxisSize.min, children: [
                          Text(_error!, textAlign: TextAlign.center, style: reelText(h * 0.026, color: Reel.text2)),
                          SizedBox(height: h * 0.03),
                          _needSeerr
                              ? PillButton('Set Seerr address', autofocus: true, onPressed: () async {
                                  if (await askSeerrAddress(context)) _load();
                                })
                              : PillButton('Try again', autofocus: true, onPressed: _load),
                        ]),
                      )
                    : SingleChildScrollView(
                        controller: _scroll,
                        clipBehavior: Clip.none,
                        padding: EdgeInsets.only(top: _spot ? 0 : h * 0.45, bottom: h * 0.1),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (_spot) _spotlightView(w, h),
                            for (var ri = _spot ? 1 : 0; ri < _rows.length; ri++)
                              RepaintBoundary(child: CardRow(
                                key: ValueKey('${_tab}_${_rows[ri].title}'),
                                autofocusFirst: !_spot && ri == 0,
                                big: _rows[ri].big,
                                previewDelay: _rows[ri].previewDelay,
                                title: _rows[ri].title,
                                items: _rows[ri].items,
                                landscape: _rows[ri].landscape,
                                titles: _rows[ri].titles,
                                previews: _rows[ri].previews,
                                onPressed: _open,
                                onFocused: _focused,
                                onLongPress: _expand,
                              )),
                          ],
                        ),
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
                message: full ? 'Exit full screen (Esc / F11)' : 'Full screen (F11)',
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: EdgeInsets.all(h * 0.012),
                  decoration: BoxDecoration(
                    color: f ? Colors.white : const Color(0x40000000),
                    shape: BoxShape.circle,
                    border: Border.all(color: const Color(0x26FFFFFF), width: 1),
                  ),
                  child: Icon(full ? Icons.fullscreen_exit_rounded : Icons.fullscreen_rounded,
                      color: f ? Colors.black : Colors.white, size: h * 0.032),
                ),
              ),
            ),
          ),
        ),
      ]),
    );
  }

  void _spotMove(int d) {
    final items = _rows.first.items;
    final n = items.length;
    final i = (_spotIdx.value + d + n) % n;
    _spotIdx.value = i;
    Preview.focus(items[i], delay: const Duration(seconds: 3));
  }

  void _spotFocus(bool f) {
    if (!_spot) return;
    final items = _rows.first.items;
    final it = items[_spotIdx.value % items.length];
    if (f) {
      if (_scroll.hasClients && _scroll.offset > 0) {
        _scroll.animateTo(0, duration: const Duration(milliseconds: 320), curve: Curves.easeOutCubic);
      }
      Preview.focus(it, delay: const Duration(seconds: 3));
    } else {
      Future.microtask(() {
        if (!mounted || !_spot) return;
        if (!_playNode.hasFocus && !_infoNode.hasFocus) Preview.blur(it);
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
          if (e.logicalKey == LogicalKeyboardKey.arrowLeft && _playNode.hasFocus) {
            _spotMove(-1);
            return KeyEventResult.handled;
          }
          if (e.logicalKey == LogicalKeyboardKey.arrowRight && _infoNode.hasFocus) {
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
            final bd = _backdrop(it);
            return Stack(fit: StackFit.expand, children: [
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 500),
                child: bd == null
                    ? const SizedBox.expand(key: ValueKey('none'))
                    : SizedBox(key: ValueKey(bd), width: w, height: bh, child: NetImage(bd)),
              ),
              Positioned.fill(child: PreviewVideo.hero(it.key)),
              const Positioned.fill(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [Color(0xE6000000), Color(0x80000000), Color(0x00000000)],
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
                      colors: [Color(0x00000000), Color(0x00000000), Color(0xFF000000)],
                      stops: [0, 0.6, 1],
                    ),
                  ),
                ),
              ),
              Positioned(
                left: w * 0.05,
                bottom: h * 0.12,
                width: w * 0.42,
                child: AnimatedBuilder(animation: _metaTick, builder: (c, _) => _spotInfo(it, h)),
              ),
              Positioned(
                left: 0,
                right: 0,
                bottom: h * 0.06,
                child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                  for (var i = 0; i < items.length; i++)
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      margin: EdgeInsets.symmetric(horizontal: h * 0.005),
                      width: i == idx ? h * 0.024 : h * 0.008,
                      height: h * 0.008,
                      decoration: BoxDecoration(
                        color: i == idx ? Colors.white : const Color(0x66FFFFFF),
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
    final logoKey = it.type == 'episode' && it.gpKey != null ? it.gpKey! : it.key;
    final logo = _meta[logoKey]?.logo;
    final genres = (_meta[it.key] ?? it).genres;
    final kind = it.type == 'movie' ? 'Movie' : (it.type == 'show' ? 'TV Show' : (it.type == 'episode' ? 'Episode' : ''));
    final meta = [kind, ...genres.take(2), if (it.year > 0) '${it.year}'].where((s) => s.isNotEmpty).join(' · ');
    final titleStyle = reelText(h * 0.08, weight: FontWeight.w800, height: 1.0).copyWith(letterSpacing: h * 0.012);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
      SizedBox(
        height: h * 0.17,
        child: Align(
          alignment: Alignment.bottomLeft,
          child: logo != null
              ? Image.network(Plex.raw(logo)!,
                  fit: BoxFit.contain,
                  alignment: Alignment.bottomLeft,
                  errorBuilder: (_, __, ___) => Text(it.displayTitle.toUpperCase(), maxLines: 2, overflow: TextOverflow.ellipsis, style: titleStyle))
              : Text(it.displayTitle.toUpperCase(), maxLines: 2, overflow: TextOverflow.ellipsis, style: titleStyle),
        ),
      ),
      SizedBox(height: h * 0.02),
      Row(mainAxisSize: MainAxisSize.min, children: [
        Flexible(
          child: Text(meta,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: reelText(h * 0.021, weight: FontWeight.w500, color: Colors.white)),
        ),
        if (it.contentRating.isNotEmpty) ...[SizedBox(width: h * 0.012), Badges([it.contentRating])],
      ]),
      SizedBox(height: h * 0.012),
      Text(it.summary,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: reelText(h * 0.021, color: const Color(0xCCFFFFFF), height: 1.4)),
      SizedBox(height: h * 0.025),
      Row(children: [
        PillButton(it.isPlayable ? (it.viewOffset > 0 ? 'Resume' : 'Play') : 'Open',
            icon: Icons.play_arrow_rounded,
            primary: true,
            autofocus: true,
            focusNode: _playNode,
            scrollAlignment: -1,
            onFocusChange: _spotFocus,
            onPressed: () {
              Preview.stop();
              _play(it);
            }),
        SizedBox(width: h * 0.014),
        IconPill(Icons.info_outline_rounded,
            focusNode: _infoNode,
            scrollAlignment: -1,
            onFocusChange: _spotFocus,
            onPressed: () {
              Preview.handOff();
              _open(it);
            }),
      ]),
    ]);
  }

  Widget _heroInfo(PlexItem it, double h) {
    final logoKey = it.type == 'episode' && it.gpKey != null ? it.gpKey! : it.key;
    final logo = _meta[logoKey]?.logo;
    final badges = (_meta[it.key] ?? it).badges;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
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
      Text(it.metaLine, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: reelText(h * 0.023, weight: FontWeight.w500, color: Reel.text2)),
      if (badges.isNotEmpty) ...[
        SizedBox(height: h * 0.01),
        Badges(badges),
      ],
      SizedBox(height: h * 0.012),
      Text(it.summary, maxLines: 2, overflow: TextOverflow.ellipsis,
          style: reelText(h * 0.024, color: const Color(0xD9FFFFFF), height: 1.35)),
      SizedBox(height: h * 0.025),
      Row(children: [
        PillButton(it.isPlayable ? (it.viewOffset > 0 ? 'Resume' : 'Play') : 'Open',
            icon: Icons.play_arrow_rounded, primary: true, autofocus: true, onPressed: () => _play(it)),
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
          padding: EdgeInsets.symmetric(horizontal: h * 0.012, vertical: h * 0.008),
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
                  padding: EdgeInsets.symmetric(horizontal: h * 0.026, vertical: h * 0.011),
                  decoration: BoxDecoration(
                    color: f ? Colors.white : (i == _tab ? const Color(0x40FFFFFF) : Colors.transparent),
                    borderRadius: BorderRadius.circular(h),
                  ),
                  child: Text(_tabs[i],
                      style: reelText(h * 0.023,
                          weight: i == _tab ? FontWeight.w600 : FontWeight.w500,
                          color: f ? Colors.black : (i == _tab ? Colors.white : Colors.white70))),
                ),
              ),
          ]),
        ),
      ),
    );
  }
}
