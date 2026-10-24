import 'package:flutter/material.dart';

import '../plex.dart';
import '../ui/preview.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'detail.dart';

/// Everything else in your library with this person in it.
class PersonScreen extends StatefulWidget {
  final String name;
  final String filter; // e.g. "actor=1234", as Plex gives it with the cast

  const PersonScreen({super.key, required this.name, required this.filter});

  @override
  State<PersonScreen> createState() => _PersonScreenState();
}

class _PersonScreenState extends State<PersonScreen> {
  List<PlexItem> _items = [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
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
      all.sort((a, b) => b.year.compareTo(a.year));
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

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
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
          Text('In your library',
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
                                Preview.focus(it);
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
