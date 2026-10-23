import 'package:flutter/material.dart';

import '../plex.dart';
import '../ui/net_image.dart';
import '../ui/preview.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'detail.dart';

class _Lib {
  final PlexSection s;
  int count = -1;
  PlexItem? latest;
  _Lib(this.s);
}

/// Every library on your Plex server, with the folders it reads from.
class LibrariesScreen extends StatefulWidget {
  const LibrariesScreen({super.key});

  @override
  State<LibrariesScreen> createState() => _LibrariesScreenState();
}

class _LibrariesScreenState extends State<LibrariesScreen> {
  List<_Lib> _libs = [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final secs = await Plex.sections();
      if (!mounted) return;
      setState(() {
        _libs = [for (final s in secs) _Lib(s)];
        _loading = false;
      });
      for (final l in _libs) {
        Plex.sectionPeek(l.s.key).then((p) {
          if (!mounted) return;
          setState(() {
            l.count = p.count;
            l.latest = p.latest;
          });
        }, onError: (_) {});
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = "Can't reach your Plex server.\n$e";
        });
      }
    }
  }

  static bool _supported(PlexSection s) => s.type == 'movie' || s.type == 'show';

  static IconData _icon(String t) {
    switch (t) {
      case 'movie':
        return Icons.movie_outlined;
      case 'show':
        return Icons.tv_rounded;
      case 'artist':
        return Icons.music_note_rounded;
      case 'photo':
        return Icons.photo_library_outlined;
      default:
        return Icons.folder_outlined;
    }
  }

  static String _kind(String t) {
    switch (t) {
      case 'movie':
        return 'Movies';
      case 'show':
        return 'TV shows';
      case 'artist':
        return 'Music';
      case 'photo':
        return 'Photos';
      default:
        return t;
    }
  }

  void _open(_Lib l) {
    if (!_supported(l.s)) {
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('${_kind(l.s.type)} libraries aren\'t supported in Reel yet')));
      return;
    }
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => LibraryScreen(section: l.s)));
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    const cols = 3;
    return Scaffold(
      body: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.05, h * 0.07, w * 0.05, 0),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Libraries', style: reelText(h * 0.055, weight: FontWeight.w700)),
          Text('Your Plex libraries and the folders they read from', style: reelText(h * 0.024, color: Reel.text2)),
          SizedBox(height: h * 0.03),
          Expanded(
            child: _loading
                ? Center(child: Text('Loading…', style: reelText(h * 0.03, color: Reel.text2)))
                : _error != null
                    ? Center(child: Text(_error!, textAlign: TextAlign.center, style: reelText(h * 0.026, color: Reel.text2)))
                    : GridView.builder(
                        clipBehavior: Clip.hardEdge,
                        padding: EdgeInsets.only(top: h * 0.01, bottom: h * 0.06),
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: cols,
                          mainAxisSpacing: h * 0.025,
                          crossAxisSpacing: h * 0.025,
                          childAspectRatio: 16 / 7.5,
                        ),
                        itemCount: _libs.length,
                        itemBuilder: (c, i) => _card(_libs[i], h, i == 0),
                      ),
          ),
        ]),
      ),
    );
  }

  Widget _card(_Lib l, double h, bool first) {
    final art = l.latest == null ? null : Plex.img(l.latest!.heroArt ?? l.latest!.poster, 640, 360);
    final count = l.count < 0 ? '' : '${l.count} ${l.s.type == 'movie' ? (l.count == 1 ? 'film' : 'films') : (l.count == 1 ? 'title' : 'titles')}';
    return Pressable(
      autofocus: first,
      onPressed: () => _open(l),
      scrollAlignment: 0.3,
      builder: (c, f) => AnimatedScale(
        scale: f ? 1.04 : 1,
        duration: const Duration(milliseconds: 160),
        curve: Curves.easeOut,
        child: Container(
          decoration: BoxDecoration(
            color: Reel.card,
            borderRadius: BorderRadius.circular(h * 0.018),
            border: Border.all(color: f ? Colors.white : const Color(0x26FFFFFF), width: f ? 2.5 : 1),
            boxShadow: f ? [BoxShadow(color: const Color(0x99000000), blurRadius: h * 0.03, offset: Offset(0, h * 0.012))] : null,
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(h * 0.016),
            child: Stack(fit: StackFit.expand, children: [
              if (art != null) NetImage(art, fade: false, decodeWidth: 640),
              const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0x66000000), Color(0xE6000000)],
                  ),
                ),
              ),
              Padding(
                padding: EdgeInsets.all(h * 0.022),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Icon(_icon(l.s.type), size: h * 0.034, color: Colors.white),
                    SizedBox(width: h * 0.01),
                    Text(_kind(l.s.type), style: reelText(h * 0.02, color: Reel.text2)),
                  ]),
                  const Spacer(),
                  Text(l.s.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: reelText(h * 0.036, weight: FontWeight.w700)),
                  SizedBox(height: h * 0.004),
                  Text(count, style: reelText(h * 0.021, weight: FontWeight.w500)),
                  if (l.s.paths.isNotEmpty) ...[
                    SizedBox(height: h * 0.006),
                    Text(l.s.paths.join('   ·   '),
                        maxLines: 2, overflow: TextOverflow.ellipsis, style: reelText(h * 0.0165, color: Reel.text3)),
                  ],
                ]),
              ),
            ]),
          ),
        ),
      ),
    );
  }
}

/// One library, every title in it, with a choice of order.
class LibraryScreen extends StatefulWidget {
  final PlexSection section;
  const LibraryScreen({super.key, required this.section});

  @override
  State<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends State<LibraryScreen> {
  static const _sorts = [
    ['A–Z', 'titleSort'],
    ['Recently added', 'addedAt:desc'],
    ['Release date', 'originallyAvailableAt:desc'],
    ['Rating', 'audienceRating:desc'],
  ];
  int _sort = 0;
  List<PlexItem> _items = [];
  bool _loading = true;
  String? _error;
  int _token = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final mine = ++_token;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final r = await Plex.all(widget.section.key, sort: _sorts[_sort][1]);
      if (!mounted || mine != _token) return;
      setState(() {
        _items = r;
        _loading = false;
      });
    } catch (e) {
      if (!mounted || mine != _token) return;
      setState(() {
        _loading = false;
        _error = "Couldn't load this library.\n$e";
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    const cols = 7;
    final gap = h * 0.022;
    final cw = (w * 0.9 - gap * cols) / cols;
    final ch = cw * 1.5;
    return Scaffold(
      body: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.05, h * 0.07, w * 0.05, 0),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(widget.section.title, style: reelText(h * 0.055, weight: FontWeight.w700)),
          SizedBox(height: h * 0.02),
          Row(children: [
            for (var i = 0; i < _sorts.length; i++)
              Padding(
                padding: EdgeInsets.only(right: h * 0.01),
                child: Pressable(
                  onPressed: () {
                    if (_sort == i) return;
                    _sort = i;
                    _load();
                  },
                  scrollAlignment: 0,
                  builder: (c, f) => AnimatedContainer(
                    duration: const Duration(milliseconds: 150),
                    padding: EdgeInsets.symmetric(horizontal: h * 0.022, vertical: h * 0.01),
                    decoration: BoxDecoration(
                      color: f ? Colors.white : (i == _sort ? const Color(0x40FFFFFF) : Colors.transparent),
                      borderRadius: BorderRadius.circular(h),
                    ),
                    child: Text(_sorts[i][0],
                        style: reelText(h * 0.022, weight: FontWeight.w600, color: f ? Colors.black : Colors.white)),
                  ),
                ),
              ),
          ]),
          SizedBox(height: h * 0.01),
          Expanded(
            child: _loading
                ? Center(child: Text('Loading…', style: reelText(h * 0.03, color: Reel.text2)))
                : _error != null
                    ? Center(child: Text(_error!, textAlign: TextAlign.center, style: reelText(h * 0.026, color: Reel.text2)))
                    : GridView.builder(
                        clipBehavior: Clip.hardEdge,
                        cacheExtent: h * 3,
                        padding: EdgeInsets.only(top: h * 0.03, bottom: h * 0.06),
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: cols,
                          childAspectRatio: cw / (ch + PosterCard.textBlock(h) + h * 0.03),
                        ),
                        itemCount: _items.length,
                        itemBuilder: (c, i) {
                          final it = _items[i];
                          return PosterCard(
                            item: it,
                            width: cw,
                            height: ch,
                            showTitle: true,
                            onFocusChange: (f) {
                              if (f) {
                                Preview.focus(it);
                              } else {
                                Preview.blur(it);
                              }
                            },
                            onPressed: () {
                              Preview.handOff();
                              Navigator.of(context).push(fadeRoute(DetailScreen(ratingKey: it.key)));
                            },
                            onLongPress: () {
                              Preview.handOff();
                              Navigator.of(context).push(fadeRoute(DetailScreen(ratingKey: it.key, expanded: true)));
                            },
                          );
                        },
                      ),
          ),
        ]),
      ),
    );
  }
}
