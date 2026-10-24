import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import '../plex.dart';
import '../ui/motion.dart';
import '../ui/net_image.dart';
import '../ui/preview.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'detail.dart';

/// Everything else in your library with this person in it.
class PersonScreen extends StatefulWidget {
  final String name;
  final String filter; // e.g. "actor=1234", as Plex gives it with the cast
  final String subtitle;
  final String? thumb; // their picture, as Plex gave it with the cast

  /// Collections read best in release order; a person's page shows their newest work first.
  final bool oldestFirst;

  const PersonScreen(
      {super.key,
      required this.name,
      required this.filter,
      this.subtitle = 'In your library',
      this.thumb,
      this.oldestFirst = false});

  @override
  State<PersonScreen> createState() => _PersonScreenState();
}

class _PersonScreenState extends State<PersonScreen> {
  List<PlexItem> _items = [];
  bool _loading = true;
  String? _error;
  String? _tagline; // e.g. "British actor"
  String? _bio;
  String? _wikiPhoto;

  // A person gets a picture and a short bio on top, like on Fire TV; a collection is just a grid.
  bool get _isPerson => !widget.oldestFirst;

  @override
  void initState() {
    super.initState();
    _load();
    if (_isPerson) _loadBio();
  }

  static final _filmy = RegExp(
      r'actor|actress|director|filmmaker|producer|screenwriter|comedian|singer|writer|performer|presenter|model|rapper',
      caseSensitive: false);

  /// A short description, the bio and (if Plex had no picture) a photo, all from Wikipedia.
  Future<void> _loadBio() async {
    final who = widget.name;
    for (final t in [who, '$who (actor)', '$who (actress)', '$who (director)']) {
      try {
        final u = Uri.parse(
            'https://en.wikipedia.org/api/rest_v1/page/summary/${Uri.encodeComponent(t.replaceAll(' ', '_'))}');
        final r = await http
            .get(u, headers: {'User-Agent': 'Reel/1.0 (Windows Plex client)'})
            .timeout(const Duration(seconds: 8));
        if (r.statusCode != 200) continue;
        final o = jsonDecode(utf8.decode(r.bodyBytes)) as Map;
        final desc = (o['description'] ?? '').toString();
        if (o['type'] != 'standard' || !_filmy.hasMatch(desc)) continue;
        final img = (o['originalimage'] ?? o['thumbnail']) as Map?;
        if (!mounted) return;
        setState(() {
          _tagline = desc.isEmpty
              ? null
              : desc[0].toUpperCase() + desc.substring(1);
          _bio = (o['extract'] ?? '').toString();
          _wikiPhoto = img?['source']?.toString();
        });
        return;
      } catch (_) {}
    }
  }

  Future<void> _load() async {
    try {
      final secs = (await Plex.sections())
          .where((s) => s.type == 'movie' || s.type == 'show')
          .toList();
      final lists = await Future.wait([
        for (final s in secs)
          Plex.withPerson(s.key, widget.filter).catchError((_) => <PlexItem>[]),
      ]);
      final seen = <String>{};
      final all = <PlexItem>[];
      for (final l in lists) {
        for (final it in l) {
          if (seen.add(it.key)) all.add(it);
        }
      }
      all.sort((a, b) =>
          widget.oldestFirst ? a.year.compareTo(b.year) : b.year.compareTo(a.year));
      if (!mounted) return;
      setState(() {
        _items = all;
        _loading = false;
        _error = all.isEmpty
            ? 'Nothing else in your library with ${widget.name}.'
            : null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = "Couldn't look that up.\n$e";
      });
    }
  }

  Widget _personPage(double h, double w) {
    final ph = h * 0.40;
    final pw = ph * 0.78;
    final photo = widget.thumb != null
        ? (widget.thumb!.startsWith('http')
            ? widget.thumb
            : Plex.img(widget.thumb, 600, 800))
        : _wikiPhoto;
    return Scaffold(
      body: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.045, h * 0.07, w * 0.03, 0),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
            height: ph,
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Container(
                width: pw,
                height: ph,
                decoration: BoxDecoration(
                    color: Reel.card,
                    borderRadius: BorderRadius.circular(h * 0.02)),
                clipBehavior: Clip.antiAlias,
                child: photo == null ? null : NetImage(photo, decodeWidth: 600),
              ),
              SizedBox(width: w * 0.03),
              Expanded(
                child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(widget.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: reelText(h * 0.07, weight: FontWeight.w700)),
                      if (_tagline != null)
                        Padding(
                          padding: EdgeInsets.only(
                              top: h * 0.006, bottom: h * 0.018),
                          child: Text(_tagline!,
                              style:
                                  reelText(h * 0.03, color: Reel.text2)),
                        ),
                      if (_bio != null)
                        SizedBox(
                          width: w * 0.55,
                          child: Text(_bio!,
                              maxLines: 7,
                              overflow: TextOverflow.ellipsis,
                              style: reelText(h * 0.026,
                                  color: const Color(0xE6FFFFFF), height: 1.3)),
                        ),
                    ]),
              ),
            ]),
          ),
          if (_loading)
            Padding(
              padding: EdgeInsets.only(top: h * 0.05),
              child: Text('Loading…',
                  style: reelText(h * 0.03, color: Reel.text2)),
            )
          else if (_error != null)
            Padding(
              padding: EdgeInsets.only(top: h * 0.05),
              child: Text(_error!,
                  style: reelText(h * 0.026, color: Reel.text2)),
            )
          else
            Expanded(
              child: SingleChildScrollView(
                child: CardRow(
                  title: 'In your library  ·  ${_items.length}',
                  items: _items,
                  landscape: true,
                  titles: true,
                  autofocusFirst: true,
                  onPressed: (it) {
                    Preview.handOff();
                    Navigator.of(context)
                        .push(fadeRoute(DetailScreen(ratingKey: it.key)));
                  },
                  onLongPress: (it) {
                    Preview.handOff();
                    Navigator.of(context).push(fadeRoute(
                        DetailScreen(ratingKey: it.key, expanded: true)));
                  },
                ),
              ),
            ),
        ]),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    if (_isPerson) return _personPage(h, w);
    const cols = 7;
    final gap = h * 0.022;
    final cw = (w * 0.9 - gap * cols) / cols;
    final ch = cw * 9 / 16;
    return Scaffold(
      body: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.05, h * 0.07, w * 0.05, 0),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(widget.name,
              style: reelText(h * 0.055, weight: FontWeight.w700)),
          Text(widget.subtitle,
              style: reelText(h * 0.024, color: Reel.text2)),
          SizedBox(height: h * 0.02),
          Expanded(
            child: _loading
                ? Center(
                    child: Text('Loading…',
                        style: reelText(h * 0.03, color: Reel.text2)))
                : _error != null
                    ? Center(
                        child: Text(_error!,
                            textAlign: TextAlign.center,
                            style: reelText(h * 0.026, color: Reel.text2)))
                    : GridView.builder(
                        clipBehavior: Clip.hardEdge,
                        cacheExtent: h * 3,
                        padding:
                            EdgeInsets.only(top: h * 0.03, bottom: h * 0.06),
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: cols,
                          childAspectRatio:
                              cw / (ch + PosterCard.textBlock(h) + h * 0.03),
                        ),
                        itemCount: _items.length,
                        itemBuilder: (c, i) {
                          final it = _items[i];
                          return PosterCard(
                            item: it,
                            width: cw,
                            height: ch,
                            landscape: true,
                            showTitle: true,
                            autofocus: i == 0,
                            onFocusChange: (f) {
                              if (f) {
                                Preview.focus(it, delay: Motion.previewDelay);
                              } else {
                                Preview.blur(it);
                              }
                            },
                            onPressed: () {
                              Preview.handOff();
                              Navigator.of(context).push(
                                  fadeRoute(DetailScreen(ratingKey: it.key)));
                            },
                            onLongPress: () {
                              Preview.handOff();
                              Navigator.of(context).push(fadeRoute(DetailScreen(
                                  ratingKey: it.key, expanded: true)));
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
