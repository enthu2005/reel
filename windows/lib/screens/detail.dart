import 'dart:async';

import 'package:flutter/material.dart';

import 'person.dart';
import 'package:flutter/services.dart';

import '../plex.dart';
import '../prefs.dart';
import '../ui/net_image.dart';
import '../ui/preview.dart';
import '../ui/motion.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'player.dart';

/// A movie, show or episode: backdrop, logo, buttons, summary, and episodes / cast underneath.
class DetailScreen extends StatefulWidget {
  final String ratingKey;

  /// Open with just the preview full screen (held Enter on a card). Enter shows the title page.
  final bool expanded;

  const DetailScreen(
      {super.key, required this.ratingKey, this.expanded = false});

  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen> {
  PlexItem? _item;
  String? _logo;
  List<PlexItem> _episodes = [];
  List<PlexItem> _related = [];
  List<PlexItem> _similar = [];
  List<PlexItem> _collection = []; // the rest of its franchise, oldest first
  int _season = -1; // 0 = all
  String? _error;
  String? _trailer;

  // Expanded preview: the video full screen with the page hidden.
  late bool _expanded = widget.expanded;
  late bool _cameExpanded = widget.expanded; // Esc then goes back to the card
  final _pageFocus = FocusNode(debugLabel: 'detail-page');
  final _playFocus = FocusNode(debugLabel: 'detail-play');
  final _pageScroll = ScrollController();
  final _castScroll = ScrollController();
  bool _hint = true;
  Timer? _hintTimer;

  @override
  void initState() {
    super.initState();
    _load();
    if (_expanded) {
      WidgetsBinding.instance
          .addPostFrameCallback((_) => _pageFocus.requestFocus());
      _showHint();
    }
  }

  @override
  void dispose() {
    _hintTimer?.cancel();
    _pageFocus.dispose();
    _playFocus.dispose();
    _pageScroll.dispose();
    _castScroll.dispose();
    Preview.release(widget.ratingKey);
    super.dispose();
  }

  void _showHint() {
    _hintTimer?.cancel();
    if (mounted) setState(() => _hint = true);
    _hintTimer = Timer(const Duration(seconds: 3), () {
      if (mounted) setState(() => _hint = false);
    });
  }

  void _expand() {
    final it = _item;
    if (it != null && Preview.wanted != it.key) Preview.start(it);
    _pageFocus.requestFocus();
    setState(() => _expanded = true);
    _showHint();
  }

  void _collapse() {
    setState(() {
      _expanded = false;
      _cameExpanded = false;
    });
    _resetPageTop();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _pageFocus.requestFocus();
      _resetPageTop();
      WidgetsBinding.instance.addPostFrameCallback((_) => _resetPageTop());
    });
  }

  void _resetPageTop() {
    if (_pageScroll.hasClients && _pageScroll.offset != 0) {
      _pageScroll.jumpTo(0);
    }
  }

  void _moveCast(double amount) {
    if (!_castScroll.hasClients) return;
    final target = (_castScroll.offset + amount)
        .clamp(0.0, _castScroll.position.maxScrollExtent);
    _castScroll.animateTo(target,
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeOutCubic);
  }

  KeyEventResult _onKey(FocusNode n, KeyEvent e) {
    if (!_expanded) return KeyEventResult.ignored;
    // Only fresh presses count: the Enter you held to get here mustn't open anything.
    if (e is! KeyDownEvent) return KeyEventResult.handled;
    final k = e.logicalKey;
    if (k == LogicalKeyboardKey.enter ||
        k == LogicalKeyboardKey.numpadEnter ||
        k == LogicalKeyboardKey.select) {
      _collapse();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.escape ||
        k == LogicalKeyboardKey.browserBack ||
        k == LogicalKeyboardKey.gameButtonB) {
      if (_cameExpanded) {
        Navigator.of(context).maybePop();
      } else {
        _collapse();
      }
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.space ||
        k == LogicalKeyboardKey.mediaPlayPause) {
      Preview.togglePause();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.f11 ||
        (k == LogicalKeyboardKey.enter &&
            HardwareKeyboard.instance.isAltPressed)) {
      return KeyEventResult.ignored; // full screen still works
    }
    _showHint();
    return KeyEventResult.handled;
  }

  /// Full-screen preview hint and a spinner until the video is moving.
  Widget _expandedOverlay(double h, double w, String title) => Stack(children: [
        Positioned.fill(
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: _collapse,
            child: const SizedBox.expand(),
          ),
        ),
        ValueListenableBuilder<String?>(
          valueListenable: Preview.showing,
          builder: (c, k, _) => k == widget.ratingKey
              ? const SizedBox.shrink()
              : const Center(
                  child: SizedBox(
                      width: 44,
                      height: 44,
                      child: CircularProgressIndicator(
                          color: Colors.white70, strokeWidth: 3))),
        ),
        Positioned(
          left: w * 0.04,
          bottom: h * 0.06,
          child: AnimatedOpacity(
            opacity: _hint ? 1 : 0,
            duration: const Duration(milliseconds: 400),
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              if (title.isNotEmpty)
                Text(title, style: reelText(h * 0.04, weight: FontWeight.w700)),
              SizedBox(height: h * 0.008),
              Text('Enter  Title page    ·    Space  Pause    ·    Esc  Back',
                  style: reelText(h * 0.022, color: Reel.text2)),
            ]),
          ),
        ),
      ]);

  bool get _container =>
      _item != null && (_item!.type == 'show' || _item!.type == 'season');

  Future<void> _load() async {
    try {
      final it = await Plex.metadata(widget.ratingKey);
      List<PlexItem> eps = [];
      String? logo = it.logo;
      if (it.type == 'episode' && it.gpKey != null) {
        eps = await Plex.allEpisodes(it.gpKey!);
        try {
          logo = (await Plex.metadata(it.gpKey!)).logo;
        } catch (_) {}
      } else if (it.type == 'show') {
        eps = await Plex.allEpisodes(it.key);
      } else if (it.type == 'season') {
        eps = await Plex.children(it.key);
      }
      if (!mounted) return;
      setState(() {
        _item = it;
        _logo = logo;
        _episodes = eps;
        if (_season < 0) {
          final t = it.type == 'episode' ? it : _target();
          _season = t != null && t.parentIndex > 0 ? t.parentIndex : 0;
        }
      });
      final results = await Future.wait([
        Plex.relatedInLibrary(it),
        Plex.related(it.key),
      ]);
      if (mounted) {
        setState(() {
          _related = results[0];
          _similar = results[1];
        });
      }
      if (it.collFilter.isNotEmpty && it.librarySectionKey != null) {
        Plex.withPerson(it.librarySectionKey!, it.collFilter).then((l) {
          final rest = l.where((x) => x.key != it.key).toList()
            ..sort((a, b) => a.year.compareTo(b.year));
          if (mounted && rest.isNotEmpty) setState(() => _collection = rest);
        }).catchError((_) {});
      }
      // The preview keeps playing behind the page if it came from the card; otherwise start one.

      if (Preview.wanted != it.key && Preview.showing.value != it.key) {
        Preview.focus(it, delay: Motion.previewDelay);
      }
      final k = it.type == 'episode' && it.gpKey != null ? it.gpKey! : it.key;
      final tr = await Plex.trailerUrl(k);
      if (mounted) setState(() => _trailer = tr);
    } catch (e) {
      if (mounted) setState(() => _error = "Couldn't load this: $e");
    }
  }

  /// What Play should start: the item itself, or for a show the episode to continue / the next unwatched.
  PlexItem? _target() {
    if (!_container) return _item;
    for (final e in _episodes) {
      if (e.viewOffset > 0) return e;
    }
    for (final e in _episodes) {
      if (e.viewCount == 0) return e;
    }
    return _episodes.isEmpty ? null : _episodes.first;
  }

  List<int> get _seasons {
    final s = _episodes
        .map((e) => e.parentIndex)
        .where((n) => n > 0)
        .toSet()
        .toList()
      ..sort();
    return s;
  }

  Future<void> _play(PlexItem it, {bool fromStart = false}) async {
    await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => PlayerScreen(
            ratingKey: it.key, startMs: fromStart ? 0 : it.viewOffset)));
    if (mounted && _pageScroll.hasClients) {
      _resetPageTop();
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          _pageFocus.requestFocus();
          _resetPageTop();
        }
      });
    }
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final it = _item;
    if (it == null) {
      // the preview grows into the page straight away, even before the details arrive
      return Scaffold(
        body: Focus(
          focusNode: _pageFocus,
          onKeyEvent: _onKey,
          child: Stack(children: [
            Positioned.fill(child: PreviewVideo.hero(widget.ratingKey)),
            if (_expanded)
              Positioned.fill(child: _expandedOverlay(h, Reel.w(context), ''))
            else
              ValueListenableBuilder<String?>(
                valueListenable: Preview.showing,
                builder: (c, k, _) => k == widget.ratingKey && _error == null
                    ? const SizedBox.shrink()
                    : Center(
                        child: Text(_error ?? 'Loading…',
                            style: reelText(h * 0.03, color: Reel.text2))),
              ),
          ]),
        ),
      );
    }
    final art = Plex.img(it.heroArt ?? it.poster, 1920, 1080);
    final target = _target();
    final watched = _container
        ? _episodes.isNotEmpty && _episodes.every((e) => e.viewCount > 0)
        : it.viewCount > 0;
    final shown = _season == 0
        ? _episodes
        : _episodes.where((e) => e.parentIndex == _season).toList();

    return Scaffold(
      body: Focus(
        focusNode: _pageFocus,
        onKeyEvent: _onKey,
        child: Stack(children: [
          if (art != null)
            Positioned.fill(child: NetImage(art, decodeWidth: 1000)),
          Positioned.fill(
              child: PreviewVideo.hero(widget.ratingKey,
                  fade: Motion.behindFade, ease: Motion.behindEase)),
          // the shading lightens while the preview plays, so you can see it
          Positioned.fill(
            child: _dimWhilePreview(const DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(colors: [
                  Color(0xEE000000),
                  Color(0x99000000),
                  Color(0x00000000)
                ], stops: [
                  0,
                  0.45,
                  0.8
                ]),
              ),
            )),
          ),
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            height: h * 0.55,
            child: _dimWhilePreview(const DecoratedBox(
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
            )),
          ),
          // the page itself hides while the preview is expanded
          IgnorePointer(
            ignoring: _expanded,
            child: ExcludeFocus(
              excluding: _expanded,
              child: AnimatedOpacity(
                opacity: _expanded ? 0 : 1,
                duration: const Duration(milliseconds: 350),
                child: SingleChildScrollView(
                  controller: _pageScroll,
                  padding: EdgeInsets.only(top: h * 0.1, bottom: h * 0.08),
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Padding(
                          padding: EdgeInsets.only(left: w * 0.05),
                          child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                SizedBox(
                                  height: h * 0.17,
                                  width: w * 0.42,
                                  child: Align(
                                    alignment: Alignment.bottomLeft,
                                    child: _logo != null
                                        ? Image.network(Plex.raw(_logo)!,
                                            fit: BoxFit.contain,
                                            alignment: Alignment.bottomLeft,
                                            errorBuilder: (_, __, ___) =>
                                                _title(it, h))
                                        : _title(it, h),
                                  ),
                                ),
                                SizedBox(height: h * 0.014),
                                SizedBox(
                                  width: w * 0.5,
                                  child: Text(it.metaLineFor(withRuntime: false),
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: reelText(h * 0.023,
                                          weight: FontWeight.w500,
                                          color: Reel.text2)),
                                ),
                                _ratingsStrip(it, h, w),
                                if (it.badges.isNotEmpty) ...[
                                  SizedBox(height: h * 0.012),
                                  SizedBox(
                                      width: w * 0.5, child: Badges(it.badges)),
                                ],
                                SizedBox(height: h * 0.028),
                                Wrap(
                                    spacing: h * 0.016,
                                    runSpacing: h * 0.016,
                                    children: [
                                      if (target != null)
                                        PillButton(
                                            target.viewOffset > 0
                                                ? 'Resume${_container ? '  S${target.parentIndex} E${target.index}' : ''}'
                                                : 'Play${_container && target.parentIndex > 0 ? '  S${target.parentIndex} E${target.index}' : ''}',
                                            icon: Icons.play_arrow_rounded,
                                            primary: true,
                                            autofocus: !_expanded,
                                            focusNode: _playFocus,
                                            onPressed: () => _play(target)),
                                      // expand the preview, right next to Play
                                      if (Prefs.previews)
                                        IconPill(Icons.open_in_full_rounded,
                                            onPressed: _expand),
                                      if (_trailer != null)
                                        PillButton('Trailer',
                                            icon: Icons.movie_outlined,
                                            onPressed: () => Navigator.of(
                                                    context)
                                                .push(MaterialPageRoute(
                                                    builder: (_) => PlayerScreen(
                                                        url: _trailer,
                                                        title:
                                                            '${it.displayTitle} · Trailer')))),
                                      if (it.collName.isNotEmpty &&
                                          _collection.isNotEmpty)
                                        PillButton(it.collName,
                                            icon: Icons
                                                .collections_bookmark_outlined,
                                            onPressed: () {
                                          Preview.stop();
                                          Navigator.of(context).push(fadeRoute(
                                              PersonScreen(
                                                  name: it.collName,
                                                  filter: it.collFilter,
                                                  subtitle:
                                                      'Collection · in release order',
                                                  oldestFirst: true)));
                                        }),
                                      if (target != null &&
                                          target.viewOffset > 0)
                                        PillButton('Start over',
                                            icon: Icons.replay_rounded,
                                            onPressed: () =>
                                                _play(target, fromStart: true)),
                                      PillButton(
                                          watched
                                              ? 'Mark unwatched'
                                              : 'Mark watched',
                                          autofocus:
                                              target == null && !_expanded,
                                          focusNode: target == null
                                              ? _playFocus
                                              : null, onPressed: () async {
                                        await Plex.setWatched(it.key, !watched);
                                        _load();
                                      }),
                                    ]),
                                SizedBox(height: h * 0.026),
                                SizedBox(
                                  width: w * 0.5,
                                  child: Text(it.summary,
                                      maxLines: 4,
                                      overflow: TextOverflow.ellipsis,
                                      style: reelText(h * 0.024,
                                          color: const Color(0xD9FFFFFF),
                                          height: 1.4)),
                                ),
                              ]),
                        ),

                        // Seasons and episodes
                        if (_episodes.isNotEmpty) ...[
                          if (_seasons.length > 1)
                            SizedBox(
                              height: h * 0.09,
                              child: ListView(
                                scrollDirection: Axis.horizontal,
                                padding: EdgeInsets.only(
                                    left: w * 0.045, top: h * 0.03),
                                children: [
                                  for (final n in [..._seasons, 0])
                                    Padding(
                                      padding: EdgeInsets.only(right: h * 0.01),
                                      child: Pressable(
                                        onPressed: () =>
                                            setState(() => _season = n),
                                        scrollAlignment: 0.5,
                                        builder: (c, f) => AnimatedContainer(
                                          duration:
                                              const Duration(milliseconds: 150),
                                          padding: EdgeInsets.symmetric(
                                              horizontal: h * 0.022,
                                              vertical: h * 0.008),
                                          decoration: BoxDecoration(
                                            color: f
                                                ? Colors.white
                                                : (n == _season
                                                    ? const Color(0x40FFFFFF)
                                                    : Colors.transparent),
                                            borderRadius:
                                                BorderRadius.circular(h),
                                          ),
                                          child: Text(
                                              n == 0 ? 'All' : 'Season $n',
                                              style: reelText(h * 0.022,
                                                  weight: FontWeight.w600,
                                                  color: f
                                                      ? Colors.black
                                                      : (n == _season
                                                          ? Colors.white
                                                          : Colors.white70))),
                                        ),
                                      ),
                                    ),
                                ],
                              ),
                            ),
                          CardRow(
                            key: ValueKey('season_$_season'),
                            title: _seasons.length > 1
                                ? (_season == 0
                                    ? 'All episodes'
                                    : 'Season $_season')
                                : 'Episodes',
                            items: shown,
                            landscape: true,
                            previews:
                                false, // the title's own preview keeps playing behind the page
                            onPressed: (e) => _play(e),
                          ),
                        ],

                        // Cast
                        if ((it.type == 'movie' || it.type == 'show') &&
            it.cast.isNotEmpty) ...[
                          Padding(
                            padding: EdgeInsets.only(
                                left: w * 0.045 + h * 0.011, top: h * 0.03),
                            child: Text('Cast',
                                style: reelText(h * 0.03,
                                    weight: FontWeight.w600)),
                          ),
                          SizedBox(
                            height: h * 0.26,
                            child: Stack(children: [
                              ListView.builder(
                                controller: _castScroll,
                                scrollDirection: Axis.horizontal,
                                clipBehavior: Clip.none,
                                cacheExtent: w,
                                padding: EdgeInsets.symmetric(
                                    horizontal: w * 0.045, vertical: h * 0.015),
                                itemCount: it.cast.length,
                                itemBuilder: (c, i) => _castCard(it.cast[i], h),
                              ),
                              Positioned(
                                left: w * 0.012,
                                top: 0,
                                bottom: 0,
                                child: Center(
                                  child: HoverButton(
                                    Icons.chevron_left_rounded,
                                    size: h * 0.055,
                                    tooltip: 'Previous cast',
                                    onPressed: () => _moveCast(-w * 0.55),
                                  ),
                                ),
                              ),
                              Positioned(
                                right: w * 0.012,
                                top: 0,
                                bottom: 0,
                                child: Center(
                                  child: HoverButton(
                                    Icons.chevron_right_rounded,
                                    size: h * 0.055,
                                    tooltip: 'Next cast',
                                    onPressed: () => _moveCast(w * 0.55),
                                  ),
                                ),
                              ),
                            ]),
                          ),
                        ],
                        if (_collection.isNotEmpty)
                          CardRow(
                            title: it.collName,
                            items: _collection,
                            landscape: true,
                            titles: true,
                            onPressed: (e) {
                              Preview.handOff();
                              Navigator.of(context).push(
                                  fadeRoute(DetailScreen(ratingKey: e.key)));
                            },
                          ),
                        if (_similar.isNotEmpty)
                          CardRow(
                            title: 'Movies Like This',
                            items: _similar,
                            landscape: true,
                            titles: true,
                            onPressed: (e) {
                              Preview.handOff();
                              Navigator.of(context).push(
                                  fadeRoute(DetailScreen(ratingKey: e.key)));
                            },
                          ),
                        if (_related.isNotEmpty)
                          CardRow(
                            title: 'More in this library',
                            items: _related,
                            landscape: true,
                            titles: true,
                            onPressed: (e) {
                              Preview.handOff();
                              Navigator.of(context).push(
                                  fadeRoute(DetailScreen(ratingKey: e.key)));
                            },
                          ),
                      ]),
                ),
              ),
            ),
          ),
          if (_expanded)
            Positioned.fill(child: _expandedOverlay(h, w, it.displayTitle)),
        ]),
      ),
    );
  }

  Widget _dimWhilePreview(Widget child) => ValueListenableBuilder<String?>(
        valueListenable: Preview.showing,
        builder: (c, k, _) => AnimatedOpacity(
          opacity: _expanded ? 0 : (k == widget.ratingKey ? 0.55 : 1),
          duration: const Duration(milliseconds: 500),
          child: child,
        ),
      );

  /// "RT 92%  ·  IMDb 7.8  ·  2h 15m" in one line under the title.
  Widget _ratingsStrip(PlexItem it, double h, double w) {
    final parts = <Widget>[];
    void add(String label, String value) {
      if (parts.isNotEmpty) {
        parts.add(Text('  ·  ',
            style: reelText(h * 0.023, color: Reel.text3)));
      }
      parts.add(Text(label,
          style: reelText(h * 0.019,
              weight: FontWeight.w700, color: Reel.text2)));
      parts.add(Text(' $value',
          style: reelText(h * 0.023,
              weight: FontWeight.w600, color: Colors.white)));
    }

    for (final r in it.ratings) {
      add(r[0], r[1]);
    }
    if (it.duration > 0 && it.isPlayable) {
      if (parts.isNotEmpty) {
        parts.add(Text('  ·  ',
            style: reelText(h * 0.023, color: Reel.text3)));
      }
      parts.add(Text(formatDuration(it.duration),
          style: reelText(h * 0.023,
              weight: FontWeight.w600, color: Colors.white)));
    }
    if (parts.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: EdgeInsets.only(top: h * 0.012),
      child: SizedBox(
          width: w * 0.5,
          child: Row(crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic, children: parts)),
    );
  }

  Widget _title(PlexItem it, double h) => Text(it.displayTitle,
      maxLines: 2,
      overflow: TextOverflow.ellipsis,
      style: reelText(h * 0.062, weight: FontWeight.w700, height: 1.05));

  Widget _castCard(CastMember c, double h) {
    final d = h * 0.13;
    final url = c.thumb == null ? null : Plex.img(c.thumb, 300, 300);
    return Padding(
      padding: EdgeInsets.symmetric(horizontal: h * 0.01),
      child: Pressable(
        onPressed: () {
          Preview.stop();
          Navigator.of(context)
              .push(fadeRoute(PersonScreen(name: c.name, filter: c.filter)));
        },
        builder: (ctx, f) => SizedBox(
          width: h * 0.17,
          child: Column(children: [
            AnimatedScale(
              scale: f ? 1.12 : 1,
              duration: const Duration(milliseconds: 180),
              child: Container(
                width: d,
                height: d,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: Reel.card,
                  boxShadow: f
                      ? [
                          BoxShadow(
                              color: Colors.black.withOpacity(0.6),
                              blurRadius: h * 0.03)
                        ]
                      : null,
                ),
                child: url == null
                    ? null
                    : ClipOval(
                        child: NetImage(url, decodeWidth: (h * 0.14).round())),
              ),
            ),
            SizedBox(height: h * 0.012),
            Text(c.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: reelText(h * 0.02, weight: FontWeight.w600)),
            Text(c.role,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: reelText(h * 0.017, color: Reel.text2)),
          ]),
        ),
      ),
    );
  }
}
