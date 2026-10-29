import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../live.dart';
import '../plex.dart';
import '../prefs.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Edit the Live guide: reorder or hide the channels, and make your own from a collection, a show or a mix
/// of genres ("Marvel Marathon", "Friends 24/7").
class LiveEditScreen extends StatefulWidget {
  const LiveEditScreen({super.key});

  @override
  State<LiveEditScreen> createState() => _LiveEditScreenState();
}

enum _Step { list, kind, pick, name }

class _Source {
  final String key;
  final String title;
  final String note;
  _Source(this.key, this.title, this.note);
}

class _LiveEditScreenState extends State<LiveEditScreen> {
  List<LiveChannel> _channels = [];
  bool _loading = true;
  bool _changed = false;

  _Step _step = _Step.list;
  String _kind = '';
  String _query = '';
  List<_Source> _sources = [];
  bool _sourcesLoading = false;
  _Source? _chosen;
  final List<_Source> _picked = []; // 'A mix of shows'
  final Set<String> _genres = {};
  String _type = 'movie';
  final _name = TextEditingController();
  final _search = TextEditingController();

  static const _genreChoices = [
    'Action', 'Adventure', 'Animation', 'Comedy', 'Crime', 'Documentary', 'Drama',
    'Family', 'Fantasy', 'Horror', 'Mystery', 'Romance', 'Science Fiction',
    'Thriller', 'War', 'Western',
  ];

  late final FocusNode _searchField = FocusNode(onKeyEvent: (n, e) {
    if (e is! KeyUpEvent && e.logicalKey == LogicalKeyboardKey.arrowDown) {
      if (!n.focusInDirection(TraversalDirection.down)) n.nextFocus();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  });

  @override
  void initState() {
    super.initState();
    () async {
      try {
        await Live.load();
      } catch (_) {}
      if (mounted) {
        setState(() {
          _channels = Live.allInOrder();
          _loading = false;
        });
      }
    }();
  }

  @override
  void dispose() {
    _name.dispose();
    _search.dispose();
    _searchField.dispose();
    // the guide shows the new lineup when it is next opened
    if (_changed) Live.rearrange();
    super.dispose();
  }

  void _refresh() => setState(() => _channels = Live.allInOrder());

  bool _hidden(LiveChannel c) => Prefs.liveHidden.contains(c.id);

  // ------------------------------------------------------------ add a channel

  Future<void> _loadSources() async {
    setState(() {
      _sources = [];
      _sourcesLoading = true;
    });
    final out = <_Source>[];
    try {
      final secs = Plex.enabledSections(await Plex.sections());
      if (_kind == 'collection') {
        for (final s in secs) {
          if (s.type != 'movie' && s.type != 'show') continue;
          for (final c in await Plex.collections(s.key)) {
            out.add(_Source(c[1], c[0], '${c[2]} ${s.type == 'show' ? 'shows' : 'films'}'));
          }
        }
      } else {
        for (final s in secs) {
          if (s.type != 'show') continue;
          for (final m in await Plex.rawAll(s.key)) {
            out.add(_Source('${m['ratingKey']}', '${m['title']}',
                '${m['year'] ?? ''}'));
          }
        }
      }
      out.sort((a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()));
    } catch (_) {}
    if (mounted) {
      setState(() {
        _sources = out;
        _sourcesLoading = false;
      });
    }
  }

  void _start(String kind) {
    _kind = kind;
    _query = '';
    _search.clear();
    _chosen = null;
    _picked.clear();
    _genres.clear();
    _type = 'movie';
    _name.clear();
    if (kind == 'genres') {
      setState(() => _step = _Step.pick);
    } else {
      setState(() => _step = _Step.pick);
      _loadSources();
    }
  }

  void _toName() {
    if (_kind == 'genres') {
      if (_genres.isEmpty) return;
      final g = _genres.toList();
      _name.text = '${g.take(2).join(' & ')} ${_type == 'movie' ? 'Movies' : 'TV'}';
    } else if (_kind == 'shows') {
      if (_picked.isEmpty) return;
      _name.text = _picked.length == 1
          ? '${_picked.first.title} 24/7'
          : '${_picked.take(2).map((p) => p.title).join(' + ')}${_picked.length > 2 ? ' & more' : ''}';
    } else if (_chosen != null) {
      _name.text = _kind == 'show' ? '${_chosen!.title} 24/7' : _chosen!.title;
    } else {
      return;
    }
    setState(() => _step = _Step.name);
  }

  void _create() {
    final name = _name.text.trim();
    if (name.isEmpty) return;
    final id = 'c:${DateTime.now().millisecondsSinceEpoch}';
    Live.addCustom(CustomChannel(id, name, _kind,
        ref: _chosen?.key ?? '',
        refTitle: _chosen?.title ?? '',
        refs: [for (final p in _picked) p.key],
        refTitles: [for (final p in _picked) p.title],
        type: _type,
        genres: _genres.toList()));
    _changed = true;
    setState(() {
      _step = _Step.list;
      _loading = true;
    });
    Live.load().then((_) {
      if (mounted) {
        setState(() {
          _channels = Live.allInOrder();
          _loading = false;
        });
      }
    });
  }

  // ------------------------------------------------------------ pieces

  /// A small button with a word on it (always readable, unlike a tiny icon).
  Widget _btn(double h, String label, VoidCallback? onTap) {
    return Pressable(
      onPressed: onTap,
      scrollAlignment: 0.5,
      builder: (c, f) => AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        padding: EdgeInsets.symmetric(horizontal: h * 0.018, vertical: h * 0.008),
        decoration: BoxDecoration(
            color: f ? Colors.white : const Color(0x26FFFFFF),
            borderRadius: BorderRadius.circular(h)),
        child: Text(label,
            style: reelText(h * 0.021,
                weight: FontWeight.w600,
                color: onTap == null
                    ? Reel.text3
                    : (f ? Colors.black : Colors.white))),
      ),
    );
  }

  Widget _chip(double h, String label, bool on, VoidCallback onTap,
      {bool autofocus = false}) {
    return Pressable(
      autofocus: autofocus,
      onPressed: onTap,
      scrollAlignment: 0.5,
      builder: (c, f) => AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        padding:
            EdgeInsets.symmetric(horizontal: h * 0.026, vertical: h * 0.012),
        decoration: BoxDecoration(
          color: f ? Colors.white : (on ? Reel.accent : const Color(0x26FFFFFF)),
          borderRadius: BorderRadius.circular(h),
        ),
        child: Text(label,
            style: reelText(h * 0.024,
                weight: FontWeight.w600,
                color: f || on ? Colors.black : Colors.white)),
      ),
    );
  }

  Widget _row(double h, double w, LiveChannel c, int i) {
    final custom = c.custom
        ? Live.customChannels().where((x) => x.id == c.id).firstOrNull
        : null;
    final hidden = _hidden(c);
    return Container(
      margin: EdgeInsets.only(bottom: h * 0.01),
      padding: EdgeInsets.symmetric(horizontal: h * 0.02, vertical: h * 0.012),
      decoration: BoxDecoration(
          color: Reel.card, borderRadius: BorderRadius.circular(h * 0.014)),
      child: Row(children: [
        SizedBox(
            width: w * 0.03,
            child: Text('${i + 1}',
                style: reelText(h * 0.03,
                    weight: FontWeight.w700,
                    color: hidden ? Reel.text3 : Reel.accent))),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(c.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.027,
                    weight: FontWeight.w600,
                    color: hidden ? Reel.text3 : Colors.white)),
            Text(
                hidden
                    ? 'Hidden from the guide'
                    : (custom?.describe ?? 'Built-in'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.02, color: Reel.text2)),
          ]),
        ),
        _btn(h, 'Up', i == 0 ? null : () {
          Live.moveChannel(c.id, -1);
          _changed = true;
          _refresh();
        }),
        SizedBox(width: h * 0.01),
        _btn(h, 'Down', i == _channels.length - 1 ? null : () {
          Live.moveChannel(c.id, 1);
          _changed = true;
          _refresh();
        }),
        SizedBox(width: h * 0.01),
        _btn(h, hidden ? 'Show' : 'Hide', () {
          Live.setHidden(c.id, !hidden);
          _changed = true;
          _refresh();
        }),
        SizedBox(width: h * 0.01),
        if (c.custom)
          _btn(h, 'Delete', () {
            Live.removeCustom(c.id);
            _changed = true;
            Live.load().then((_) {
              if (mounted) _refresh();
            });
          })
        else
          SizedBox(width: h * 0.075),
      ]),
    );
  }

  Widget _listStep(double h, double w) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Reorder or hide channels, or make your own.',
          style: reelText(h * 0.026, color: Reel.text2)),
      SizedBox(height: h * 0.02),
      PillButton('Add a channel',
          autofocus: true,
          onPressed: () => setState(() => _step = _Step.kind)),
      SizedBox(height: h * 0.02),
      Expanded(
        child: _loading
            ? Text('Loading…', style: reelText(h * 0.026, color: Reel.text2))
            : ListView(clipBehavior: Clip.hardEdge, children: [
                for (var i = 0; i < _channels.length; i++)
                  _row(h, w, _channels[i], i),
              ]),
      ),
    ]);
  }

  Widget _kindStep(double h, double w) {
    Widget card(String title, String body, String kind, {bool first = false}) =>
        Pressable(
          autofocus: first,
          onPressed: () => _start(kind),
          scrollAlignment: 0.5,
          builder: (c, f) => AnimatedContainer(
            duration: const Duration(milliseconds: 120),
            width: w * 0.5,
            margin: EdgeInsets.only(bottom: h * 0.014),
            padding: EdgeInsets.all(h * 0.024),
            decoration: BoxDecoration(
                color: f ? Colors.white : Reel.card,
                borderRadius: BorderRadius.circular(h * 0.016)),
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title,
                  style: reelText(h * 0.03,
                      weight: FontWeight.w700,
                      color: f ? Colors.black : Colors.white)),
              Text(body,
                  style: reelText(h * 0.022,
                      color: f ? const Color(0xFF444444) : Reel.text2)),
            ]),
          ),
        );
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('What should the channel play?',
          style: reelText(h * 0.03, weight: FontWeight.w600)),
      SizedBox(height: h * 0.02),
      card('A collection', 'A franchise or set from your libraries, in release order. "Marvel Marathon".',
          'collection',
          first: true),
      card('One show', 'Every episode of one show, in order, round and round. "Friends 24/7".', 'show'),
      card('A mix of shows', 'Pick several TV shows; their episodes are mixed together, like an evening of different shows.', 'shows'),
      card('A mix of genres', 'Films or shows that match the genres you pick, shuffled.', 'genres'),
    ]);
  }

  Widget _pickStep(double h, double w) {
    if (_kind == 'genres') {
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Pick one or more genres',
            style: reelText(h * 0.03, weight: FontWeight.w600)),
        SizedBox(height: h * 0.02),
        Row(children: [
          _chip(h, 'Movies', _type == 'movie',
              () => setState(() => _type = 'movie'),
              autofocus: true),
          SizedBox(width: h * 0.012),
          _chip(h, 'TV shows', _type == 'show',
              () => setState(() => _type = 'show')),
        ]),
        SizedBox(height: h * 0.02),
        Expanded(
          child: SingleChildScrollView(
            child: Wrap(spacing: h * 0.012, runSpacing: h * 0.012, children: [
              for (final g in _genreChoices)
                _chip(h, g, _genres.contains(g), () {
                  setState(() =>
                      _genres.contains(g) ? _genres.remove(g) : _genres.add(g));
                }),
            ]),
          ),
        ),
        SizedBox(height: h * 0.015),
        PillButton('Next', primary: true, onPressed: _toName),
      ]);
    }
    final q = _query.toLowerCase();
    final shown = [
      for (final s in _sources)
        if (q.isEmpty || s.title.toLowerCase().contains(q)) s
    ].take(60).toList();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(_kind == 'collection' ? 'Choose a collection' : (_kind == 'shows' ? 'Choose the shows to mix' : 'Choose a show'),
          style: reelText(h * 0.03, weight: FontWeight.w600)),
      SizedBox(height: h * 0.015),
      SizedBox(
        width: w * 0.5,
        child: TextField(
          controller: _search,
          focusNode: _searchField,
          autofocus: true,
          onChanged: (v) => setState(() => _query = v),
          style: reelText(h * 0.028, weight: FontWeight.w500),
          cursorColor: Colors.white,
          decoration: InputDecoration(
            hintText: 'Search',
            hintStyle: reelText(h * 0.028, color: Reel.text3),
            filled: true,
            fillColor: Reel.card,
            prefixIcon: Icon(Icons.search_rounded, color: Reel.text2, size: h * 0.032),
            border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(h * 0.02),
                borderSide: BorderSide.none),
            contentPadding: EdgeInsets.symmetric(
                horizontal: h * 0.02, vertical: h * 0.016),
          ),
        ),
      ),
      SizedBox(height: h * 0.015),
      Expanded(
        child: _sourcesLoading
            ? Text('Loading…', style: reelText(h * 0.026, color: Reel.text2))
            : shown.isEmpty
                ? Text(
                    _kind == 'collection'
                        ? 'No collections found in your libraries.'
                        : 'Nothing found.',
                    style: reelText(h * 0.026, color: Reel.text2))
                : ListView(clipBehavior: Clip.hardEdge, children: [
                    for (final s in shown)
                      Pressable(
                        scrollAlignment: 0.5,
                        onPressed: () {
                          if (_kind == 'shows') {
                            setState(() {
                              final i = _picked.indexWhere((p) => p.key == s.key);
                              i >= 0 ? _picked.removeAt(i) : _picked.add(s);
                            });
                            return;
                          }
                          _chosen = s;
                          _toName();
                        },
                        builder: (c, f) => AnimatedContainer(
                          duration: const Duration(milliseconds: 120),
                          width: w * 0.5,
                          margin: EdgeInsets.only(bottom: h * 0.006),
                          padding: EdgeInsets.symmetric(
                              horizontal: h * 0.02, vertical: h * 0.012),
                          decoration: BoxDecoration(
                              color: f ? Colors.white : Reel.card,
                              borderRadius: BorderRadius.circular(h * 0.012)),
                          child: Row(children: [
                            Expanded(
                                child: Text(s.title,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: reelText(h * 0.026,
                                        weight: FontWeight.w600,
                                        color: f ? Colors.black : Colors.white))),
                            if (_kind == 'shows' && _picked.any((p) => p.key == s.key))
                              Padding(
                                padding: EdgeInsets.only(right: h * 0.015),
                                child: Text('Added',
                                    style: reelText(h * 0.021,
                                        weight: FontWeight.w700,
                                        color: f ? Colors.black : Reel.accent)),
                              ),
                            Text(s.note,
                                style: reelText(h * 0.021,
                                    color: f
                                        ? const Color(0xFF555555)
                                        : Reel.text2)),
                          ]),
                        ),
                      ),
                  ]),
      ),
      if (_kind == 'shows') ...[
        SizedBox(height: h * 0.012),
        PillButton(
            _picked.isEmpty ? 'Pick at least one show' : 'Next (${_picked.length} chosen)',
            primary: true,
            onPressed: _toName),
      ],
    ]);
  }

  Widget _nameStep(double h, double w) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Name your channel',
          style: reelText(h * 0.03, weight: FontWeight.w600)),
      SizedBox(height: h * 0.02),
      SizedBox(
        width: w * 0.5,
        child: TextField(
          controller: _name,
          autofocus: true,
          onSubmitted: (_) => _create(),
          style: reelText(h * 0.032, weight: FontWeight.w500),
          cursorColor: Colors.white,
          decoration: InputDecoration(
            filled: true,
            fillColor: Reel.card,
            border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(h * 0.02),
                borderSide: BorderSide.none),
            contentPadding: EdgeInsets.symmetric(
                horizontal: h * 0.02, vertical: h * 0.02),
          ),
        ),
      ),
      SizedBox(height: h * 0.025),
      PillButton('Create channel', primary: true, onPressed: _create),
    ]);
  }

  /// Esc / Back steps back through the add flow before it leaves the page.
  bool _back() {
    switch (_step) {
      case _Step.list:
        return false;
      case _Step.kind:
        setState(() => _step = _Step.list);
      case _Step.pick:
        setState(() => _step = _Step.kind);
      case _Step.name:
        setState(() => _step = _Step.pick);
    }
    return true;
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    return PopScope(
      canPop: _step == _Step.list,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _back();
      },
      child: Scaffold(
        body: Padding(
          padding: EdgeInsets.fromLTRB(w * 0.08, h * 0.07, w * 0.08, h * 0.03),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Edit channels',
                style: reelText(h * 0.05, weight: FontWeight.w700)),
            SizedBox(height: h * 0.01),
            Expanded(
              child: switch (_step) {
                _Step.list => _listStep(h, w),
                _Step.kind => _kindStep(h, w),
                _Step.pick => _pickStep(h, w),
                _Step.name => _nameStep(h, w),
              },
            ),
          ]),
        ),
      ),
    );
  }
}
