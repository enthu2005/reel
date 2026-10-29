import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../stats.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

String _hours(double h) => h >= 100 ? h.round().toString() : h.toStringAsFixed(1);

/// Hours watched, top genres and actors, and when you watch. "Wrapped" tells it as a slideshow.
class StatsScreen extends StatefulWidget {
  const StatsScreen({super.key});

  @override
  State<StatsScreen> createState() => _StatsScreenState();
}

class _StatsScreenState extends State<StatsScreen> {
  final int _thisYear = DateTime.now().year;
  late int _year = _thisYear; // 0 = all time
  final Map<int, WatchStats> _cache = {};
  bool _loading = true;
  String _error = '';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (_cache.containsKey(_year)) {
      setState(() => _loading = false);
      return;
    }
    setState(() {
      _loading = true;
      _error = '';
    });
    final y = _year;
    try {
      final s = await WatchStats.load(y);
      _cache[y] = s;
    } catch (e) {
      if (mounted && y == _year) _error = "Couldn't read your watch history: $e";
    }
    if (mounted && y == _year) setState(() => _loading = false);
  }

  Widget _chip(double h, String label, int year) {
    final on = _year == year;
    return Pressable(
      autofocus: on,
      onPressed: () {
        _year = year;
        _load();
      },
      builder: (c, f) => AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        padding: EdgeInsets.symmetric(horizontal: h * 0.026, vertical: h * 0.012),
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

  Widget _big(double h, double w, String value, String label) => Container(
        width: w * 0.19,
        padding: EdgeInsets.all(h * 0.022),
        decoration: BoxDecoration(
            color: Reel.card, borderRadius: BorderRadius.circular(h * 0.018)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(value,
              style: reelText(h * 0.062, weight: FontWeight.w700, color: Reel.accent)),
          Text(label, style: reelText(h * 0.022, color: Reel.text2)),
        ]),
      );

  Widget _bars(double h, double w, String title, List<Ranked> l,
      String Function(Ranked) trailing) {
    final top = l.isEmpty ? 1.0 : l.first.value;
    return SizedBox(
      width: w * 0.37,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: reelText(h * 0.03, weight: FontWeight.w700)),
        SizedBox(height: h * 0.012),
        if (l.isEmpty)
          Text('Nothing yet', style: reelText(h * 0.023, color: Reel.text2)),
        for (final r in l)
          Padding(
            padding: EdgeInsets.only(bottom: h * 0.012),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(
                    child: Text(r.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: reelText(h * 0.024, weight: FontWeight.w500))),
                Text(trailing(r), style: reelText(h * 0.021, color: Reel.text2)),
              ]),
              SizedBox(height: h * 0.004),
              LayoutBuilder(
                builder: (c, box) => Stack(children: [
                  Container(
                      height: h * 0.008,
                      width: box.maxWidth,
                      decoration: BoxDecoration(
                          color: const Color(0x26FFFFFF),
                          borderRadius: BorderRadius.circular(h))),
                  Container(
                      height: h * 0.008,
                      width: box.maxWidth * (r.value / top).clamp(0.02, 1.0),
                      decoration: BoxDecoration(
                          color: Reel.accent,
                          borderRadius: BorderRadius.circular(h))),
                ]),
              ),
            ]),
          ),
      ]),
    );
  }

  Widget _columns(double h, double w, String title, List<double> v, List<String> labels) {
    final top = v.fold<double>(0, (a, b) => b > a ? b : a);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title, style: reelText(h * 0.03, weight: FontWeight.w700)),
      SizedBox(height: h * 0.012),
      SizedBox(
        height: h * 0.16,
        child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
          for (var i = 0; i < v.length; i++)
            Padding(
              padding: EdgeInsets.only(right: h * 0.008),
              child: Column(mainAxisAlignment: MainAxisAlignment.end, children: [
                Container(
                  width: w * 0.04,
                  height: top <= 0 ? 2 : (h * 0.12 * v[i] / top).clamp(2.0, h * 0.12),
                  decoration: BoxDecoration(
                      color: v[i] == top && top > 0 ? Reel.accent : const Color(0x55FFFFFF),
                      borderRadius: BorderRadius.circular(h * 0.004)),
                ),
                SizedBox(height: h * 0.006),
                Text(labels[i], style: reelText(h * 0.018, color: Reel.text2)),
              ]),
            ),
        ]),
      ),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final s = _cache[_year];
    return Scaffold(
      body: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(w * 0.07, h * 0.08, w * 0.07, h * 0.08),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Stats', style: reelText(h * 0.055, weight: FontWeight.w700)),
          Text('What you have watched on this profile',
              style: reelText(h * 0.026, color: Reel.text2)),
          SizedBox(height: h * 0.025),
          Wrap(spacing: h * 0.012, runSpacing: h * 0.012, children: [
            _chip(h, 'This year', _thisYear),
            _chip(h, 'Last year', _thisYear - 1),
            _chip(h, 'All time', 0),
            if (s != null && !s.isEmpty && _year != 0)
              PillButton('Your $_year Wrapped',
                  primary: true,
                  onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => WrappedScreen(stats: s)))),
          ]),
          SizedBox(height: h * 0.03),
          if (_loading)
            Text('Adding it up…', style: reelText(h * 0.028, color: Reel.text2))
          else if (_error.isNotEmpty)
            Text(_error, style: reelText(h * 0.026, color: Reel.text2))
          else if (s == null || s.isEmpty)
            Text('Nothing watched ${_year == 0 ? 'yet' : 'in $_year'}.',
                style: reelText(h * 0.028, color: Reel.text2))
          else ...[
            Wrap(spacing: h * 0.018, runSpacing: h * 0.018, children: [
              _big(h, w, _hours(s.hours), 'hours watched'),
              _big(h, w, '${s.movies}', 'films'),
              _big(h, w, '${s.episodes}', 'episodes'),
              _big(h, w, '${s.shows}', 'shows'),
              _big(h, w, '${s.longestStreak}', 'day streak, your longest'),
            ]),
            SizedBox(height: h * 0.04),
            Wrap(spacing: w * 0.05, runSpacing: h * 0.03, children: [
              _bars(h, w, 'Top genres', s.genres, (r) => '${_hours(r.value)} h'),
              _bars(h, w, 'Top actors', s.actors, (r) => '${r.count} titles'),
              _bars(h, w, 'Top shows', s.topShows,
                  (r) => '${r.count} eps · ${_hours(r.value)} h'),
              _bars(h, w, 'Most watched films', s.topMovies,
                  (r) => '${_hours(r.value)} h'),
            ]),
            SizedBox(height: h * 0.04),
            Wrap(spacing: w * 0.06, runSpacing: h * 0.03, children: [
              _columns(h, w, 'Days you watch', s.weekday,
                  ['M', 'T', 'W', 'T', 'F', 'S', 'S']),
              _columns(h, w, 'Months', s.month,
                  ['J', 'F', 'M', 'A', 'M', 'J', 'J', 'A', 'S', 'O', 'N', 'D']),
            ]),
            SizedBox(height: h * 0.03),
            Text(
                'Based on what Plex records as watched. A film or episode you watched more than once counts once for a year, and every time under All time.',
                style: reelText(h * 0.02, color: Reel.text3)),
          ],
        ]),
      ),
    );
  }
}

/// The year as a slideshow: Enter / Right for next, Left for back, Esc to leave.
class WrappedScreen extends StatefulWidget {
  final WatchStats stats;
  const WrappedScreen({super.key, required this.stats});

  @override
  State<WrappedScreen> createState() => _WrappedScreenState();
}

class _WrappedScreenState extends State<WrappedScreen> {
  int _i = 0;

  WatchStats get s => widget.stats;

  List<(List<Color>, Widget Function(double h))> get _slides {
    Widget big(double h, String t) => Text(t,
        textAlign: TextAlign.center,
        style: reelText(h * 0.11, weight: FontWeight.w800, height: 1.05));
    Widget mid(double h, String t) => Text(t,
        textAlign: TextAlign.center,
        style: reelText(h * 0.042, weight: FontWeight.w700, height: 1.15));
    Widget small(double h, String t) => Text(t,
        textAlign: TextAlign.center,
        style: reelText(h * 0.03, color: const Color(0xCCFFFFFF)));
    Widget col(double h, List<Widget> kids) => Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          for (var i = 0; i < kids.length; i++) ...[
            kids[i],
            if (i < kids.length - 1) SizedBox(height: h * 0.025)
          ]
        ]);
    final y = s.year == 0 ? 'all time' : '${s.year}';
    final days = s.hours / 24;
    final firstGenre = s.genres.isEmpty ? null : s.genres.first;
    final firstActor = s.actors.isEmpty ? null : s.actors.first;
    final show = s.topShows.isEmpty ? null : s.topShows.first;
    final movie = s.topMovies.isEmpty ? null : s.topMovies.first;
    return [
      ([const Color(0xFF3A1C71), const Color(0xFFD76D77)], (h) => col(h, [
        small(h, 'Your'),
        big(h, s.year == 0 ? 'All Time' : '${s.year}'),
        big(h, 'Wrapped'),
        small(h, 'Press Enter to begin'),
      ])),
      ([const Color(0xFF0F2027), const Color(0xFF2C5364)], (h) => col(h, [
        small(h, 'In $y you watched'),
        big(h, _hours(s.hours)),
        mid(h, 'hours'),
        small(h, days >= 1
            ? "That's ${days.toStringAsFixed(1)} whole days in front of the screen."
            : 'A cosy ${_hours(s.hours)} hours.'),
      ])),
      ([const Color(0xFF42275A), const Color(0xFF734B6D)], (h) => col(h, [
        small(h, 'That was'),
        big(h, '${s.movies}'),
        mid(h, s.movies == 1 ? 'film' : 'films'),
        small(h, 'and ${s.episodes} episodes across ${s.shows} shows.'),
      ])),
      if (show != null)
        ([const Color(0xFF134E5E), const Color(0xFF71B280)], (h) => col(h, [
          small(h, 'Your show of the year'),
          mid(h, show.name),
          big(h, '${show.count}'),
          small(h, 'episodes  ·  ${_hours(show.value)} hours'),
        ])),
      if (movie != null)
        ([const Color(0xFF232526), const Color(0xFF414345)], (h) => col(h, [
          small(h, 'The film you spent the most time with'),
          mid(h, movie.name),
          small(h, movie.count > 1 ? 'watched ${movie.count} times' : '${_hours(movie.value)} hours'),
        ])),
      if (firstGenre != null)
        ([const Color(0xFF8E2DE2), const Color(0xFF4A00E0)], (h) => col(h, [
          small(h, 'Your top genres'),
          for (var i = 0; i < s.genres.length && i < 3; i++)
            i == 0 ? big(h, s.genres[i].name) : mid(h, '${i + 1}.  ${s.genres[i].name}'),
          small(h, '${_hours(firstGenre.value)} hours of ${firstGenre.name}'),
        ])),
      if (firstActor != null)
        ([const Color(0xFFCB356B), const Color(0xFFBD3F32)], (h) => col(h, [
          small(h, 'The face you saw most'),
          big(h, firstActor.name),
          small(h, 'in ${firstActor.count} of your titles'),
        ])),
      ([const Color(0xFF141E30), const Color(0xFF243B55)], (h) => col(h, [
        small(h, 'You are a'),
        big(h, s.persona),
        small(h, 'Most of your watching happens around ${_clockHour(s.peakHour)}.'),
        small(h, 'Busiest day: ${WatchStats.weekdayNames[s.busiestWeekday]}s  ·  Busiest month: ${WatchStats.monthNames[s.busiestMonth]}'),
      ])),
      if (s.longestStreak > 1)
        ([const Color(0xFFFF8008), const Color(0xFFFFC837)], (h) => col(h, [
          small(h, 'Your longest streak'),
          big(h, '${s.longestStreak}'),
          mid(h, 'days in a row'),
        ])),
      ([const Color(0xFF3A1C71), const Color(0xFFD76D77)], (h) => col(h, [
        mid(h, "That's a wrap on $y"),
        small(h, 'Press Esc to go back'),
      ])),
    ];
  }

  static String _clockHour(int h) {
    final x = h % 12 == 0 ? 12 : h % 12;
    return '$x ${h < 12 ? 'AM' : 'PM'}';
  }

  void _go(int d) {
    final n = _slides.length;
    final j = _i + d;
    if (j >= n) {
      Navigator.of(context).maybePop();
      return;
    }
    if (j >= 0) setState(() => _i = j);
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final slides = _slides;
    final cur = slides[_i.clamp(0, slides.length - 1)];
    return Scaffold(
      body: Focus(
        autofocus: true,
        onKeyEvent: (n, e) {
          if (e is! KeyDownEvent) return KeyEventResult.ignored;
          final k = e.logicalKey;
          if (k == LogicalKeyboardKey.arrowRight ||
              k == LogicalKeyboardKey.enter ||
              k == LogicalKeyboardKey.space ||
              k == LogicalKeyboardKey.numpadEnter) {
            _go(1);
            return KeyEventResult.handled;
          }
          if (k == LogicalKeyboardKey.arrowLeft) {
            _go(-1);
            return KeyEventResult.handled;
          }
          return KeyEventResult.ignored;
        },
        child: GestureDetector(
          onTapUp: (d) => _go(d.globalPosition.dx < Reel.w(context) * 0.3 ? -1 : 1),
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 450),
            child: Container(
              key: ValueKey(_i),
              width: double.infinity,
              height: double.infinity,
              padding: EdgeInsets.symmetric(horizontal: Reel.w(context) * 0.1),
              decoration: BoxDecoration(
                gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: cur.$1),
              ),
              child: Stack(children: [
                Center(child: cur.$2(h)),
                Positioned(
                  bottom: h * 0.04,
                  left: 0,
                  right: 0,
                  child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                    for (var i = 0; i < slides.length; i++)
                      Container(
                        margin: EdgeInsets.symmetric(horizontal: h * 0.004),
                        width: h * 0.012,
                        height: h * 0.012,
                        decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: i == _i
                                ? Colors.white
                                : const Color(0x55FFFFFF)),
                      ),
                  ]),
                ),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}
