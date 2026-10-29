import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../plex.dart';
import '../prefs.dart';
import '../smart_search.dart';
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
  String _smartNote = ''; // what the plain-English words were understood as
  static const _historyMax = 8;
  List<String> _history = Prefs.searchHistory;

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

  /// Most recent first, no repeats. Opening a result (or pressing Enter) is what makes a search worth keeping.
  void _remember(String text) {
    final t = text.trim().toLowerCase();
    if (t.length < 2) return;
    final h = [
      t,
      ..._history.where((x) => x.toLowerCase() != t),
    ].take(_historyMax).toList();
    Prefs.searchHistory = h;
    _history = h;
  }

  void _searchFor(String t) {
    _text.text = t;
    _text.selection = TextSelection.collapsed(offset: t.length);
    _changed(t);
    _field.requestFocus();
  }

  void _changed(String q) {
    _debounce?.cancel();
    final query = q.trim();
    if (query.length < 2) {
      setState(() {
        _smartNote = '';
        _results = [];
        _message = query.isEmpty ? 'Type to search your library' : 'Keep typing…';
      });
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 400), () async {
      final mine = ++_token;
      try {
        // Plain English ("unwatched sci-fi under two hours") becomes filters on your own library
        final sq = SmartSearch.parse(query);
        if (mounted) {
          setState(() => _smartNote =
              sq.smart ? 'Searching your library for  ${sq.describe()}' : '');
        }
        final r = sq.smart ? await SmartSearch.run(sq) : await Plex.search(query);
        if (!mounted || mine != _token) return;
        final urls = r.map((it) => PosterCard.imageUrl(it, 0, 0, true, 1.0)).toList();
        // the first screenfuls load straight away, the rest quietly behind them
        for (final u in urls.take(20)) {
          if (u != null) ImageLoader.load(u);
        }
        ImageLoader.prefetch(urls.skip(20));
        setState(() {
          _results = r;
          _message = r.isEmpty ? 'No results for “$query”' : '';
        });
      } catch (e) {
        if (mounted) setState(() => _message = "Search didn't work: $e");
      }
    });
  }

  Widget _historyRow(String label, double h, VoidCallback onTap) {
    return Padding(
      padding: EdgeInsets.only(bottom: h * 0.008),
      child: Pressable(
        onPressed: onTap,
        scrollAlignment: 0,
        builder: (c, f) => AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: EdgeInsets.symmetric(horizontal: h * 0.022, vertical: h * 0.01),
          decoration: BoxDecoration(
            color: f ? Colors.white : const Color(0x26FFFFFF),
            borderRadius: BorderRadius.circular(h),
          ),
          child: Text(label,
              style: reelText(h * 0.024,
                  weight: FontWeight.w600,
                  color: f ? Colors.black : Colors.white)),
        ),
      ),
    );
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
            width: w * 0.6,
            child: TextField(
              controller: _text,
              focusNode: _field,
              autofocus: true,
              onChanged: _changed,
              onSubmitted: _remember,
              style: reelText(h * 0.032, weight: FontWeight.w500),
              cursorColor: Colors.white,
              decoration: InputDecoration(
                hintText: 'Movies, shows, actors… or "unwatched sci-fi under 2 hours"',
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
          // The recent searches, while the box is empty
          if (_text.text.trim().isEmpty && _history.isNotEmpty) ...[
            for (final t in _history) _historyRow('↻  $t', h, () => _searchFor(t)),
            _historyRow('Clear search history', h, () {
              Prefs.searchHistory = [];
              setState(() => _history = []);
              _field.requestFocus();
            }),
          ] else if (_message.isNotEmpty) Text(_message, style: reelText(h * 0.026, color: Reel.text2)),
          if (_smartNote.isNotEmpty)
            Text(_smartNote,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.024,
                    weight: FontWeight.w500, color: const Color(0xCCE8833A))),
          Expanded(
            child: GridView.builder(
              clipBehavior: Clip.hardEdge,
              cacheExtent: h * 4, // build the cards well ahead so their pictures are ready before you get there
              padding: EdgeInsets.only(top: h * 0.03, bottom: h * 0.06),
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
                    _remember(_text.text);
                    Preview.handOff();
                    Navigator.of(context).push(fadeRoute(DetailScreen(ratingKey: it.key)));
                  },
                  onLongPress: () {
                    _remember(_text.text);
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
