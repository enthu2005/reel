import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../plex.dart';
import '../seerr.dart';
import '../ui/net_image.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'detail.dart';

/// Asks for the Seerr address (keyboard: type it, Enter to save, Esc to cancel). Returns true if saved.
Future<bool> askSeerrAddress(BuildContext context) async {
  final h = Reel.h(context);
  final text = TextEditingController(text: Seerr.base);
  final ok = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      backgroundColor: Reel.card,
      title: Text('Seerr address',
          style: reelText(h * 0.03, weight: FontWeight.w700)),
      content: SizedBox(
        width: h * 0.7,
        child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                  'The address you open Seerr at, for example http://192.168.1.20:5055 or https://seerr.yourdomain.com. '
                  'It signs in with your Plex account.',
                  style: reelText(h * 0.02, color: Reel.text2, height: 1.35)),
              SizedBox(height: h * 0.02),
              TextField(
                controller: text,
                autofocus: true,
                style: reelText(h * 0.026),
                cursorColor: Colors.white,
                decoration: InputDecoration(
                  filled: true,
                  fillColor: const Color(0xFF2C2C2E),
                  border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(h * 0.012),
                      borderSide: BorderSide.none),
                ),
                onSubmitted: (_) => Navigator.of(c).pop(true),
              ),
              SizedBox(height: h * 0.012),
              Text('Enter to save · Esc to cancel',
                  style: reelText(h * 0.018, color: Reel.text3)),
            ]),
      ),
    ),
  );
  if (ok != true) return false;
  Seerr.setBase(text.text);
  return true;
}

/// A title from Seerr: what it is, whether you have it, when it'll arrive, and a Request button.
class SeerrDetailScreen extends StatefulWidget {
  final SeerrMedia media;
  const SeerrDetailScreen({super.key, required this.media});

  @override
  State<SeerrDetailScreen> createState() => _SeerrDetailScreenState();
}

class _SeerrDetailScreenState extends State<SeerrDetailScreen> {
  late SeerrMedia _m = widget.media;
  List<SeerrMedia> _more = [];
  String? _message;
  bool _busy = false;
  Timer? _refresh;
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _load();
    _loadMore();
    // Keep the download progress / ETA fresh while you're looking at it.
    _refresh = Timer.periodic(const Duration(seconds: 20), (_) {
      if (_m.status >= 2 && _m.status <= 4) _load();
    });
  }

  @override
  void dispose() {
    _refresh?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final d = await Seerr.details(_m.type, _m.id);
      if (mounted) {
        setState(() {
          d.requestStatus = _m.requestStatus;
          _m = d;
          _loaded = true;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _message = "Couldn't reach Seerr: $e");
    }
  }

  Future<void> _loadMore() async {
    final r = await Seerr.moreLike(_m);
    if (mounted) setState(() => _more = r);
  }

  Future<void> _request() async {
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      if (_m.type == 'tv') {
        var want = _m.seasons.where((s) => s[2] <= 1).map((s) => s[0]).toList();
        if (want.isEmpty) want = _m.seasons.map((s) => s[0]).toList();
        await Seerr.requestShow(_m.id, want);
      } else {
        await Seerr.requestMovie(_m.id);
      }
      await _load();
      if (mounted)
        setState(() => _message =
            'Requested. It shows up in "Your requests" on Discover.');
    } catch (e) {
      if (mounted)
        setState(() => _message = "The request didn't go through: $e");
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final m = _m;
    final eta = m.eta;
    final status = m.statusText;
    final seasonLine = m.type == 'tv' && m.seasons.isNotEmpty
        ? m.seasons.map((s) {
            final st = s[2] == 5
                ? 'have'
                : s[2] == 4
                    ? 'some'
                    : s[2] >= 2
                        ? 'requested'
                        : '';
            return 'S${s[0]}${st.isEmpty ? '' : ' ($st)'}';
          }).join('  ·  ')
        : '';
    return Scaffold(
      body: Stack(children: [
        if (m.backdrop != null) Positioned.fill(child: NetImage(m.backdrop)),
        const Positioned.fill(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(colors: [
                Color(0xF0000000),
                Color(0xA6000000),
                Color(0x1A000000)
              ], stops: [
                0,
                0.5,
                0.85
              ]),
            ),
          ),
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          height: h * 0.5,
          child: const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  Color(0x00000000),
                  Color(0xCC000000),
                  Color(0xFF000000)
                ],
              ),
            ),
          ),
        ),
        SingleChildScrollView(
          padding: EdgeInsets.only(top: h * 0.14, bottom: h * 0.08),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Padding(
              padding: EdgeInsets.only(left: w * 0.05),
              child: SizedBox(
                width: w * 0.5,
                child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(m.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: reelText(h * 0.062,
                              weight: FontWeight.w700, height: 1.05)),
                      SizedBox(height: h * 0.014),
                      Text(
                          [
                            m.type == 'tv' ? 'TV Show' : 'Movie',
                            if (m.year > 0) '${m.year}',
                            if (status.isNotEmpty) status
                          ].join(' · '),
                          style: reelText(h * 0.023,
                              weight: FontWeight.w500, color: Reel.text2)),
                      if (eta.isNotEmpty) ...[
                        SizedBox(height: h * 0.01),
                        Row(children: [
                          Icon(Icons.schedule_rounded,
                              size: h * 0.026, color: Colors.white),
                          SizedBox(width: h * 0.008),
                          Flexible(
                              child: Text(eta,
                                  style: reelText(h * 0.023,
                                      weight: FontWeight.w600))),
                        ]),
                      ],
                      if (m.dlPercent >= 0) ...[
                        SizedBox(height: h * 0.012),
                        ClipRRect(
                          borderRadius: BorderRadius.circular(4),
                          child: LinearProgressIndicator(
                              value: m.dlPercent / 100,
                              minHeight: 6,
                              color: Colors.white,
                              backgroundColor: const Color(0x40FFFFFF)),
                        ),
                      ],
                      SizedBox(height: h * 0.028),
                      Wrap(
                          spacing: h * 0.016,
                          runSpacing: h * 0.016,
                          children: [
                            if (m.ratingKey != null)
                              PillButton('Open in your library',
                                  icon: Icons.play_arrow_rounded,
                                  primary: true,
                                  autofocus: true,
                                  onPressed: () => Navigator.of(context).push(
                                      fadeRoute(DetailScreen(
                                          ratingKey: m.ratingKey!)))),
                            if (_loaded && m.requestable)
                              PillButton(
                                  _busy
                                      ? 'Requesting…'
                                      : (m.type == 'tv'
                                          ? (m.status == 4
                                              ? 'Request the rest'
                                              : 'Request all seasons')
                                          : 'Request'),
                                  icon: Icons.add_rounded,
                                  primary: m.ratingKey == null,
                                  autofocus: m.ratingKey == null,
                                  onPressed: _busy ? () {} : _request),
                            if (m.youtubeTrailer != null)
                              PillButton('Trailer',
                                  icon: Icons.movie_outlined,
                                  autofocus: !(_loaded && m.requestable) &&
                                      m.ratingKey == null,
                                  onPressed: () => launchUrl(Uri.parse(
                                      'https://www.youtube.com/watch?v=${m.youtubeTrailer}'))),
                            if (!_loaded)
                              Text('Loading…',
                                  style:
                                      reelText(h * 0.022, color: Reel.text2)),
                          ]),
                      if (_message != null) ...[
                        SizedBox(height: h * 0.016),
                        Text(_message!,
                            style: reelText(h * 0.021, color: Reel.text2)),
                      ],
                      SizedBox(height: h * 0.026),
                      Text(m.overview,
                          maxLines: 5,
                          overflow: TextOverflow.ellipsis,
                          style: reelText(h * 0.024,
                              color: const Color(0xD9FFFFFF), height: 1.4)),
                      if (seasonLine.isNotEmpty) ...[
                        SizedBox(height: h * 0.016),
                        Text(seasonLine,
                            style: reelText(h * 0.02, color: Reel.text2)),
                      ],
                    ]),
              ),
            ),
            if (_more.isNotEmpty)
              CardRow(
                title: 'More like this',
                items: _more.map((x) => x.toItem()).toList(),
                titles: true,
                previews: false,
                onPressed: (it) => Navigator.of(context).push(fadeRoute(
                    SeerrDetailScreen(media: Seerr.known[it.key] ?? _m))),
              ),
          ]),
        ),
      ]),
    );
  }
}

/// Everything in one genre, as a grid of posters.
class SeerrGenreScreen extends StatefulWidget {
  final SeerrGenre genre;
  const SeerrGenreScreen({super.key, required this.genre});

  @override
  State<SeerrGenreScreen> createState() => _SeerrGenreScreenState();
}

class _SeerrGenreScreenState extends State<SeerrGenreScreen> {
  final List<PlexItem> _items = [];
  int _page = 0;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _more();
  }

  Future<void> _more() async {
    if (_loading || _page >= 10) return;
    _loading = true;
    try {
      final r =
          await Seerr.byGenre(widget.genre.type, widget.genre.id, _page + 1);
      _page++;
      if (mounted) setState(() => _items.addAll(r.map((m) => m.toItem())));
    } catch (e) {
      if (mounted) setState(() => _error = "Couldn't load this genre: $e");
    } finally {
      _loading = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    const cols = 7;
    final cw = (w * 0.9 - h * 0.022 * cols) / cols;
    final ch = cw * 9 / 16;
    return Scaffold(
      body: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.05, h * 0.07, w * 0.05, 0),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(
              '${widget.genre.name} · ${widget.genre.type == 'tv' ? 'TV Shows' : 'Movies'}',
              style: reelText(h * 0.05, weight: FontWeight.w700)),
          if (_error != null)
            Text(_error!, style: reelText(h * 0.024, color: Reel.text2)),
          SizedBox(height: h * 0.02),
          Expanded(
            child: GridView.builder(
              clipBehavior: Clip.none,
              padding: EdgeInsets.only(top: h * 0.02, bottom: h * 0.06),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: cols,
                childAspectRatio: (cw + h * 0.022) /
                    (ch + PosterCard.textBlock(h) + h * 0.04),
              ),
              itemCount: _items.length,
              itemBuilder: (c, i) {
                if (i >= _items.length - cols * 2)
                  _more(); // load the next page before you reach the end
                final it = _items[i];
                return PosterCard(
                  item: it,
                  width: cw,
                  height: ch,
                  landscape: true,
                  showTitle: true,
                  preview: false,
                  autofocus: i == 0,
                  onPressed: () => Navigator.of(context).push(fadeRoute(
                      SeerrDetailScreen(media: Seerr.known[it.key]!))),
                );
              },
            ),
          ),
        ]),
      ),
    );
  }
}
