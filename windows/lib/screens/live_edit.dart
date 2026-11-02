import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../live.dart';
import '../plex.dart';
import '../prefs.dart';
import '../smart_search.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Edit the Live guide: reorder or hide the channels, and make your own from a collection, a show or a mix
/// of genres ("Marvel Marathon", "Friends 24/7").
class LiveEditScreen extends StatefulWidget {
  const LiveEditScreen({super.key});

  @override
  State<LiveEditScreen> createState() => _LiveEditScreenState();
}

enum _Step { list, kind, pick, name, edit, rename, libs }

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
  CustomChannel? _def; // the channel being edited (renamed, given new contents or other libraries)
  bool _changing = false; // choosing new contents for [_def], not adding a channel
  List<PlexSection> _libChoices = [];
  final Set<String> _libPicked = {};
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
  final _queryCtl = TextEditingController(); // 'A saved search'
  String _queryNote = '';
  String _queryCount = '';
  Timer? _queryTimer;
  int _queryToken = 0;

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
    _queryCtl.dispose();
    _queryTimer?.cancel();
    _searchField.dispose();
    // the guide shows the new lineup when it is next opened
    if (_changed) Live.rearrange();
    super.dispose();
  }

  void _refresh() => setState(() => _channels = Live.allInOrder());

  /// Builds the channels again after an edit and goes back to the list.
  void _reload({_Step step = _Step.list}) {
    _changed = true;
    setState(() {
      _step = step;
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
    _queryCtl.clear();
    _queryNote = '';
    _queryCount = '';
    _genres.clear();
    _type = 'movie';
    _name.clear();
    final d = _def;
    if (_changing && d != null) {
      // start from what the channel plays now
      if (kind == 'genres' && d.kind == 'genres') {
        _type = d.type;
        _genres.addAll(d.genres.map((g) => _genreChoices.firstWhere(
            (c) => c.toLowerCase() == g.toLowerCase(),
            orElse: () => g)));
      } else if (kind == 'shows' && d.kind == 'shows') {
        for (var i = 0; i < d.refs.length; i++) {
          _picked.add(_Source(d.refs[i], i < d.refTitles.length ? d.refTitles[i] : '', ''));
        }
      } else if (kind == 'search' && d.kind == 'search') {
        _queryCtl.text = d.ref;
      }
    }
    if (kind == 'genres' || kind == 'search') {
      setState(() => _step = _Step.pick);
    } else {
      setState(() => _step = _Step.pick);
      _loadSources();
    }
  }

  /// New contents for an existing channel: its name, number and libraries stay as they are.
  void _applyChange(CustomChannel e) {
    switch (_kind) {
      case 'genres':
        if (_genres.isEmpty) return;
        e.genres = _genres.toList();
        e.type = _type;
        e.ref = '';
        e.refTitle = '';
        e.refs = [];
        e.refTitles = [];
      case 'search':
        final q = _queryCtl.text.trim();
        if (q.isEmpty) return;
        e.ref = q;
        e.refTitle = '';
        e.genres = [];
        e.refs = [];
        e.refTitles = [];
      case 'shows':
        if (_picked.isEmpty) return;
        e.refs = [for (final p in _picked) p.key];
        e.refTitles = [for (final p in _picked) p.title];
        e.ref = '';
        e.refTitle = '';
        e.genres = [];
      default:
        final c = _chosen;
        if (c == null) return;
        e.ref = c.key;
        e.refTitle = c.title;
        e.genres = [];
        e.refs = [];
        e.refTitles = [];
    }
    e.kind = _kind;
    if (_kind != 'genres') e.libs = [];
    Live.saveDefinition(e);
    _changing = false;
    _def = null;
    _reload();
  }

  void _toName() {
    final d = _def;
    if (_changing && d != null) {
      _applyChange(d);
      return;
    }
    if (_kind == 'genres') {
      if (_genres.isEmpty) return;
      final g = _genres.toList();
      _name.text = '${g.take(2).join(' & ')} ${_type == 'movie' ? 'Movies' : 'TV'}';
    } else if (_kind == 'search') {
      final q = _queryCtl.text.trim();
      if (q.isEmpty) return;
      _name.text = q[0].toUpperCase() + q.substring(1);
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
    _def = null;
    _changing = false;
    final name = _name.text.trim();
    if (name.isEmpty) return;
    final id = 'c:${DateTime.now().millisecondsSinceEpoch}';
    Live.addCustom(CustomChannel(id, name, _kind,
        ref: _kind == 'search' ? _queryCtl.text.trim() : (_chosen?.key ?? ''),
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

  void _applyRename() {
    final d = _def;
    final n = _name.text.trim();
    if (d == null || n.isEmpty) return;
    d.name = n;
    Live.saveDefinition(d);
    _def = null;
    _reload();
  }

  Future<void> _openLibs(CustomChannel d) async {
    final secs = Plex.enabledSections(await Plex.sections())
        .where((s) => s.type == d.type)
        .toList();
    if (!mounted) return;
    setState(() {
      _libChoices = secs;
      _libPicked
        ..clear()
        ..addAll(d.libs);
      _step = _Step.libs;
    });
  }

  void _saveLibs() {
    final d = _def;
    if (d == null) return;
    // everything ticked (or nothing) means "all of them"
    d.libs = _libPicked.length >= _libChoices.length ? [] : _libPicked.toList();
    Live.saveDefinition(d);
    _def = null;
    _reload();
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
    final hidden = _hidden(c);
    final def = Live.definition(c.id);
    return Container(
      key: ValueKey('ch_${c.id}'),
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
                    : (def == null
                        ? 'Built-in'
                        : '${c.custom ? '' : (Live.changed(c.id) ? 'Changed: ' : 'Standard: ')}${def.describe}'),
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
        _btn(h, 'Edit', def == null ? null : () {
          setState(() {
            _def = def;
            _changing = false;
            _step = _Step.edit;
          });
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

  Widget _editStep(double h, double w) {
    final d = _def;
    if (d == null) return const SizedBox.shrink();
    Widget action(String label, String note, VoidCallback onTap, {bool first = false}) =>
        Padding(
          padding: EdgeInsets.only(bottom: h * 0.014),
          child: Pressable(
            autofocus: first,
            onPressed: onTap,
            scrollAlignment: 0.5,
            builder: (c, f) => AnimatedContainer(
              duration: const Duration(milliseconds: 120),
              width: w * 0.5,
              padding: EdgeInsets.all(h * 0.022),
              decoration: BoxDecoration(
                  color: f ? Colors.white : Reel.card,
                  borderRadius: BorderRadius.circular(h * 0.016)),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(label,
                    style: reelText(h * 0.028,
                        weight: FontWeight.w700,
                        color: f ? Colors.black : Colors.white)),
                if (note.isNotEmpty)
                  Text(note,
                      style: reelText(h * 0.021,
                          color: f ? const Color(0xFF444444) : Reel.text2)),
              ]),
            ),
          ),
        );
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(d.name, style: reelText(h * 0.03, weight: FontWeight.w600)),
      Text(d.describe, style: reelText(h * 0.022, color: Reel.text2)),
      SizedBox(height: h * 0.02),
      Expanded(
        child: ListView(clipBehavior: Clip.hardEdge, children: [
          action('Rename', 'Now called "${d.name}".', () {
            _name.text = d.name;
            setState(() => _step = _Step.rename);
          }, first: true),
          action('Change what it plays', 'Pick new contents. Its name and place in the guide stay.', () {
            _changing = true;
            setState(() => _step = _Step.kind);
          }),
          if (d.kind == 'genres')
            action('Libraries it uses',
                d.libs.isEmpty ? 'All of them. Choose only some.' : '${d.libs.length} picked.',
                () => _openLibs(d)),
          if (d.builtin && Live.changed(d.id))
            action('Put back as it came', 'Forget your changes to this standard channel.', () {
              Live.resetDefinition(d.id);
              _def = null;
              _reload();
            }),
        ]),
      ),
    ]);
  }

  Widget _renameStep(double h, double w) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Name this channel', style: reelText(h * 0.03, weight: FontWeight.w600)),
      SizedBox(height: h * 0.02),
      SizedBox(
        width: w * 0.5,
        child: TextField(
          controller: _name,
          autofocus: true,
          onSubmitted: (_) => _applyRename(),
          style: reelText(h * 0.032, weight: FontWeight.w500),
          cursorColor: Colors.white,
          decoration: InputDecoration(
            filled: true,
            fillColor: Reel.card,
            border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(h * 0.02),
                borderSide: BorderSide.none),
            contentPadding:
                EdgeInsets.symmetric(horizontal: h * 0.02, vertical: h * 0.02),
          ),
        ),
      ),
      SizedBox(height: h * 0.025),
      PillButton('Save', primary: true, onPressed: _applyRename),
    ]);
  }

  Widget _libsStep(double h, double w) {
    final d = _def;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Libraries for ${d?.name ?? ''}',
          style: reelText(h * 0.03, weight: FontWeight.w600)),
      SizedBox(height: h * 0.012),
      Text('Only look in the libraries you tick. None ticked means all of them.',
          style: reelText(h * 0.022, color: Reel.text2)),
      SizedBox(height: h * 0.02),
      Wrap(spacing: h * 0.012, runSpacing: h * 0.012, children: [
        for (var i = 0; i < _libChoices.length; i++)
          _chip(h, _libChoices[i].title, _libPicked.contains(_libChoices[i].key), () {
            setState(() {
              final k = _libChoices[i].key;
              _libPicked.contains(k) ? _libPicked.remove(k) : _libPicked.add(k);
            });
          }, autofocus: i == 0),
      ]),
      SizedBox(height: h * 0.025),
      PillButton('Save', primary: true, onPressed: _saveLibs),
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
      card('A saved search', 'Describe it in plain English: "80s horror rated 7+" or "unwatched sci-fi under 2 hours". It plays whatever matches.', 'search'),
      card('A mix of genres', 'Films or shows that match the genres you pick, shuffled.', 'genres'),
    ]);
  }

  void _queryChanged(String v) {
    _queryTimer?.cancel();
    final q = v.trim();
    if (q.isEmpty) {
      setState(() {
        _queryNote = '';
        _queryCount = '';
      });
      return;
    }
    final sq = SmartSearch.parse(q);
    setState(() {
      _queryNote = sq.smart ? sq.describe() : 'Looking for “${sq.text}”';
      _queryCount = 'Counting what matches…';
    });
    _queryTimer = Timer(const Duration(milliseconds: 600), () async {
      final mine = ++_queryToken;
      String c;
      try {
        final r = await SmartSearch.run(sq, limit: 400);
        c = r.isEmpty ? 'Nothing matches yet' : '${r.length} matches';
      } catch (_) {
        c = '';
      }
      if (mounted && mine == _queryToken) setState(() => _queryCount = c);
    });
  }

  Widget _searchStep(double h, double w) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Describe what the channel should play',
          style: reelText(h * 0.03, weight: FontWeight.w600)),
      SizedBox(height: h * 0.012),
      Text('Try "80s horror rated 7+", "unwatched comedy under 100 minutes", "tom hanks 4k".',
          style: reelText(h * 0.022, color: Reel.text2)),
      SizedBox(height: h * 0.02),
      SizedBox(
        width: w * 0.6,
        child: TextField(
          controller: _queryCtl,
          autofocus: true,
          onChanged: _queryChanged,
          onSubmitted: (_) => _toName(),
          style: reelText(h * 0.032, weight: FontWeight.w500),
          cursorColor: Colors.white,
          decoration: InputDecoration(
            hintText: 'What should it play?',
            hintStyle: reelText(h * 0.032, color: Reel.text3),
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
      SizedBox(height: h * 0.015),
      if (_queryNote.isNotEmpty)
        Text('Understood as  $_queryNote',
            style: reelText(h * 0.024, weight: FontWeight.w500, color: Reel.accent)),
      if (_queryCount.isNotEmpty)
        Text(_queryCount, style: reelText(h * 0.024, color: Reel.text2)),
      SizedBox(height: h * 0.025),
      PillButton('Next', primary: true, onPressed: _toName),
    ]);
  }

  Widget _pickStep(double h, double w) {
    if (_kind == 'search') return _searchStep(h, w);
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
        if (_changing) {
          _changing = false;
          setState(() => _step = _Step.edit);
        } else {
          setState(() => _step = _Step.list);
        }
      case _Step.pick:
        setState(() => _step = _Step.kind);
      case _Step.name:
        setState(() => _step = _Step.pick);
      case _Step.edit:
        _def = null;
        setState(() => _step = _Step.list);
      case _Step.rename:
      case _Step.libs:
        setState(() => _step = _Step.edit);
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
                _Step.edit => _editStep(h, w),
                _Step.rename => _renameStep(h, w),
                _Step.libs => _libsStep(h, w),
              },
            ),
          ]),
        ),
      ),
    );
  }
}
