import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../plex.dart';
import '../ui/net_image.dart';
import '../ui/preview.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'detail.dart';

/// Type to search movies, shows and episodes.
class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key});

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  final _text = TextEditingController();
  Timer? _debounce;
  List<PlexItem> _results = [];
  String _message = 'Type to search your library';
  int _token = 0;

  // Down arrow in the box jumps to the results (a text box normally keeps the arrows for itself).
  late final FocusNode _field = FocusNode(onKeyEvent: (n, e) {
    if (e is! KeyUpEvent && e.logicalKey == LogicalKeyboardKey.arrowDown) {
      if (!n.focusInDirection(TraversalDirection.down)) n.nextFocus();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  });

  @override
  void dispose() {
    _debounce?.cancel();
    _text.dispose();
    _field.dispose();
    super.dispose();
  }

  void _changed(String q) {
    _debounce?.cancel();
    final query = q.trim();
    if (query.length < 2) {
      setState(() {
        _results = [];
        _message = query.isEmpty ? 'Type to search your library' : 'Keep typing…';
      });
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 400), () async {
      final mine = ++_token;
      try {
        final r = await Plex.search(query);
        if (!mounted || mine != _token) return;
        ImageLoader.prefetch(r.map((it) => PosterCard.imageUrl(it, 0, 0, true, 1.0)));
        setState(() {
          _results = r;
          _message = r.isEmpty ? 'No results for “$query”' : '';
        });
      } catch (e) {
        if (mounted) setState(() => _message = "Search didn't work: $e");
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final cols = 5;
    final cw = (w * 0.9 - h * 0.022 * cols) / cols;
    return Scaffold(
      body: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.05, h * 0.07, w * 0.05, 0),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Search', style: reelText(h * 0.05, weight: FontWeight.w700)),
          SizedBox(height: h * 0.025),
          SizedBox(
            width: w * 0.5,
            child: TextField(
              controller: _text,
              focusNode: _field,
              autofocus: true,
              onChanged: _changed,
              style: reelText(h * 0.032, weight: FontWeight.w500),
              cursorColor: Colors.white,
              decoration: InputDecoration(
                hintText: 'Movies, shows, episodes',
                hintStyle: reelText(h * 0.032, color: Reel.text3),
                filled: true,
                fillColor: Reel.card,
                prefixIcon: Icon(Icons.search_rounded, color: Reel.text2, size: h * 0.035),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(h * 0.02), borderSide: BorderSide.none),
                contentPadding: EdgeInsets.symmetric(horizontal: h * 0.02, vertical: h * 0.02),
              ),
            ),
          ),
          SizedBox(height: h * 0.02),
          if (_message.isNotEmpty) Text(_message, style: reelText(h * 0.026, color: Reel.text2)),
          Expanded(
            child: GridView.builder(
              clipBehavior: Clip.none,
              padding: EdgeInsets.only(top: h * 0.02, bottom: h * 0.06),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: cols,
                childAspectRatio: cw / (cw * 9 / 16 + h * 0.1),
              ),
              itemCount: _results.length,
              itemBuilder: (c, i) {
                final it = _results[i];
                return PosterCard(
                  item: it,
                  width: cw,
                  height: cw * 9 / 16,
                  landscape: true,
                  subtitle: it.type == 'episode'
                      ? '${it.gpTitle ?? ''} · S${it.parentIndex} E${it.index}'
                      : (it.year > 0 ? '${it.year}' : null),
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
