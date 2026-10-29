import 'plex.dart';

/// Plain-English library search with no online service: "sci-fi under two hours", "unwatched comedy",
/// "tom hanks 4k", "80s horror rated 7+", "which batman did i watch last". The words are turned into filters and
/// applied to the Plex library listing on this PC.
class SmartQuery {
  bool movies = false, shows = false;
  final Set<String> genres = {};
  int yearMin = 0, yearMax = 0;
  double minRating = 0;
  int minMinutes = 0, maxMinutes = 0;
  bool unwatched = false, watched = false, lastWatched = false;
  String resolution = ''; // "4k", "1080", "720"
  String person = '';
  String text = '';
  final List<String> notes = [];

  /// True when the words hold at least one filter (a bare title or name goes to the ordinary search).
  bool get smart =>
      genres.isNotEmpty ||
      yearMin > 0 ||
      yearMax > 0 ||
      minRating > 0 ||
      minMinutes > 0 ||
      maxMinutes > 0 ||
      unwatched ||
      watched ||
      lastWatched ||
      resolution.isNotEmpty ||
      person.isNotEmpty;

  String describe() => notes.join('  ·  ');
}

class SmartSearch {
  SmartSearch._();

  static const _genres = [
    ['science fiction', 'sci-fi', 'sci fi', 'scifi', 'science fiction', 'space'],
    ['comedy', 'comedy', 'comedies', 'funny', 'hilarious', 'sitcom', 'sitcoms'],
    ['horror', 'horror', 'scary', 'spooky', 'creepy'],
    ['action', 'action', 'explosive'],
    ['drama', 'drama', 'dramas'],
    ['thriller', 'thriller', 'thrillers', 'suspense', 'suspenseful'],
    ['romance', 'romance', 'romantic', 'rom-com', 'romcom'],
    ['animation', 'animation', 'animated', 'cartoon', 'cartoons', 'anime'],
    ['documentary', 'documentary', 'documentaries', 'docs'],
    ['fantasy', 'fantasy'],
    ['crime', 'crime', 'heist', 'gangster'],
    ['mystery', 'mystery', 'whodunit', 'whodunnit'],
    ['war', 'war'],
    ['western', 'western', 'westerns', 'cowboy'],
    ['family', 'family', 'kids', 'kid-friendly'],
    ['adventure', 'adventure', 'adventures'],
    ['music', 'musical', 'musicals', 'music'],
    ['history', 'historical', 'history'],
  ];

  static const _noise = {
    'find', 'show', 'me', 'give', 'get', 'a', 'an', 'the', 'some', 'something', 'any',
    'good', 'great', 'best', 'i', 'ive', 'own', 'have', 'that', 'are', 'is', 'of', 'to',
    'please', 'which', 'what', 'did', 'do', 'my', 'movies', 'movie', 'films', 'film',
    'shows', 'series', 'tv', 'episodes', 'episode', 'library', 'one', 'ones', 'with',
    'and', 'in', 'on', 'for', 'watch', 'watching', 'want', 'looking', 'recommend',
    'new', 'old', 'at', 'from', 'about'
  };

  static SmartQuery parse(String raw) {
    final q = SmartQuery();
    var s = ' ${raw.toLowerCase().replaceAll('’', "'").replaceAll(RegExp(r'[,!?]'), ' ')} ';
    RegExpMatch? m;

    // Last watched: "which batman did i watch last"
    m = RegExp(r'\b(last watched|watched last|watch last|most recently watched|recently watched|'
            r'watched recently|i last watched|latest watched)\b')
        .firstMatch(s);
    if (m != null) {
      q.lastWatched = true;
      q.watched = true;
      q.notes.add('last watched');
      s = s.replaceAll(m.group(0)!, ' ');
    }

    // Decades: 1980s, 80s, eighties
    m = RegExp(r"\b(19|20)(\d)0'?s\b").firstMatch(s);
    if (m != null) {
      final d = int.parse('${m.group(1)}${m.group(2)}0');
      q.yearMin = d;
      q.yearMax = d + 9;
      q.notes.add('${d}s');
      s = s.replaceAll(m.group(0)!, ' ');
    } else {
      m = RegExp(r"\b(\d)0'?s\b").firstMatch(s);
      if (m != null) {
        final dg = int.parse(m.group(1)!);
        if (dg >= 2 || dg == 0) {
          final d = dg == 0 ? 2000 : 1900 + dg * 10;
          q.yearMin = d;
          q.yearMax = d + 9;
          q.notes.add('${d}s');
          s = s.replaceAll(m.group(0)!, ' ');
        }
      } else {
        const words = [
          ['fifties', '1950'],
          ['sixties', '1960'],
          ['seventies', '1970'],
          ['eighties', '1980'],
          ['nineties', '1990'],
        ];
        for (final w in words) {
          if (s.contains(' ${w[0]} ')) {
            final d = int.parse(w[1]);
            q.yearMin = d;
            q.yearMax = d + 9;
            q.notes.add('${d}s');
            s = s.replaceAll(' ${w[0]} ', ' ');
            break;
          }
        }
      }
    }

    // Years
    m = RegExp(r'\b(?:after|since|newer than|released after)\s+((?:19|20)\d\d)\b').firstMatch(s);
    if (m != null) {
      q.yearMin = int.parse(m.group(1)!) + (m.group(0)!.contains('after') ? 1 : 0);
      q.notes.add('from ${q.yearMin}');
      s = s.replaceAll(m.group(0)!, ' ');
    }
    m = RegExp(r'\b(?:before|older than|until|prior to)\s+((?:19|20)\d\d)\b').firstMatch(s);
    if (m != null) {
      q.yearMax = int.parse(m.group(1)!) - 1;
      q.notes.add('before ${m.group(1)}');
      s = s.replaceAll(m.group(0)!, ' ');
    }
    m = RegExp(r'\b(?:in|from|of|year)?\s*((?:19|20)\d\d)\b').firstMatch(s);
    if (q.yearMin == 0 && q.yearMax == 0 && m != null) {
      q.yearMin = q.yearMax = int.parse(m.group(1)!);
      q.notes.add(m.group(1)!);
      s = s.replaceAll(m.group(0)!, ' ');
    }

    // Runtime
    const num = r'(\d+(?:\.\d+)?|an|one|two|three|four|a)';
    m = RegExp(r'\b(?:under|less than|shorter than|below|no more than|within|max)\s+' +
            num +
            r'(?:\s+and a half)?\s*(hours?|hrs?|h|minutes?|mins?|m)\b')
        .firstMatch(s);
    if (m != null) {
      q.maxMinutes = _minutes(m.group(1)!, m.group(0)!.contains('and a half'), m.group(2)!);
      q.notes.add('under ${_runtimeText(q.maxMinutes)}');
      s = s.replaceAll(m.group(0)!, ' ');
    }
    m = RegExp(r'\b(?:over|more than|longer than|at least|above)\s+' +
            num +
            r'(?:\s+and a half)?\s*(hours?|hrs?|minutes?|mins?)\b')
        .firstMatch(s);
    if (m != null) {
      q.minMinutes = _minutes(m.group(1)!, m.group(0)!.contains('and a half'), m.group(2)!);
      q.notes.add('over ${_runtimeText(q.minMinutes)}');
      s = s.replaceAll(m.group(0)!, ' ');
    }
    if (s.contains(' short ')) {
      q.maxMinutes = 100;
      q.notes.add('short');
      s = s.replaceAll(' short ', ' ');
    }

    // Rating out of 10
    const ratingPatterns = [
      r'\b(?:rating|rated|score|scored)\s+(?:of\s+)?(?:at least|over|above|more than|higher than|min)?\s*(\d(?:\.\d)?)\b',
      r'\b(?:at least|over|above|more than|higher than|minimum)\s+(?:a\s+)?(\d(?:\.\d)?)\s*(?:rating|score|stars?|out of 10|/10)',
      r'\b(\d(?:\.\d)?)\s*\+(?=\s)',
      r'\b(\d(?:\.\d)?)\s*(?:or (?:higher|more|better|above)|out of 10|/10)\b',
    ];
    for (final p in ratingPatterns) {
      m = RegExp(p).firstMatch(s);
      if (m != null) {
        final r = double.parse(m.group(1)!);
        if (r > 0 && r <= 10) {
          q.minRating = r;
          q.notes.add('rated ${r == r.floorToDouble() ? r.toInt() : r}+');
          s = s.replaceAll(m.group(0)!, ' ');
          break;
        }
      }
    }
    if (q.minRating == 0) {
      m = RegExp(r'\b(highly rated|well rated|well-rated|top rated|top-rated|acclaimed|excellent|critically acclaimed)\b')
          .firstMatch(s);
      if (m != null) {
        q.minRating = 8;
        q.notes.add('rated 8+');
        s = s.replaceAll(m.group(0)!, ' ');
      } else {
        m = RegExp(r'\b(good|great|best)\b').firstMatch(s);
        if (m != null) {
          q.minRating = m.group(0) == 'good' ? 7 : 7.5;
          q.notes.add('well rated');
          s = s.replaceFirst(RegExp(r'\b(good|great|best)\b'), ' ');
        }
      }
    }

    // Watched or not
    m = RegExp(r"\b(haven'?t|have not|havent|never|not|yet to)\s+(?:yet\s+)?(?:watched|seen)\b|\bunwatched\b|"
            r'\bnot seen\b|\bnew to me\b|\bnever seen\b')
        .firstMatch(s);
    if (m != null) {
      q.unwatched = true;
      q.notes.add('unwatched');
      s = s.replaceAll(m.group(0)!, ' ');
    } else if (!q.lastWatched) {
      m = RegExp(r"\b(?:i'?ve|i have)\s+(?:already\s+)?(?:watched|seen)\b|\balready (?:watched|seen)\b|\bwatched\b")
          .firstMatch(s);
      if (m != null) {
        q.watched = true;
        q.notes.add('watched');
        s = s.replaceAll(m.group(0)!, ' ');
      }
    }

    // Picture size
    m = RegExp(r'\b(4k|uhd|2160p|ultra hd)\b').firstMatch(s);
    if (m != null) {
      q.resolution = '4k';
      q.notes.add('4K');
      s = s.replaceAll(m.group(0)!, ' ');
    } else {
      m = RegExp(r'\b(1080p?|full hd)\b').firstMatch(s);
      if (m != null) {
        q.resolution = '1080';
        q.notes.add('1080p');
        s = s.replaceAll(m.group(0)!, ' ');
      } else {
        m = RegExp(r'\b(720p?)\b').firstMatch(s);
        if (m != null) {
          q.resolution = '720';
          q.notes.add('720p');
          s = s.replaceAll(m.group(0)!, ' ');
        }
      }
    }

    // Movies or shows
    if (RegExp(r'\b(movies?|films?)\b').hasMatch(s)) q.movies = true;
    if (RegExp(r'\b(shows?|series|tv|sitcoms?)\b').hasMatch(s)) q.shows = true;
    if (q.movies && !q.shows) q.notes.insert(0, 'movies');
    if (q.shows && !q.movies) q.notes.insert(0, 'shows');

    // Genres
    for (final g in _genres) {
      for (var i = 1; i < g.length; i++) {
        final gm = RegExp('(?<![a-z])${RegExp.escape(g[i])}(?![a-z])').firstMatch(s);
        if (gm != null) {
          if (q.genres.add(g[0])) {
            q.notes.insert(0, g[0] == 'science fiction' ? 'sci-fi' : g[0]);
          }
          s = s.replaceAll(gm.group(0)!, ' ');
          break;
        }
      }
    }

    // People: "with tom hanks", "directed by nolan"
    m = RegExp(r"\b(?:starring|featuring|directed by|director|with|by|actor)\s+([a-z][a-z'.\- ]+?)"
            r'(?=\s+(?:in|that|which|under|over|rated|and|i|from|before|after|on)\b|\s*$)')
        .firstMatch(s);
    if (m != null) {
      final name = m.group(1)!.trim();
      if (name.split(RegExp(r'\s+')).length <= 4 && name.isNotEmpty) {
        q.person = name;
        q.notes.add('with $name');
        s = s.replaceAll(m.group(0)!, ' ');
      }
    }

    // What's left: the title or a name
    final rest = <String>[];
    for (final w in s.replaceAll(RegExp(r"[^a-z0-9'\- ]"), ' ').trim().split(RegExp(r'\s+'))) {
      if (w.isEmpty || _noise.contains(w.replaceAll("'", ''))) continue;
      rest.add(w);
    }
    q.text = rest.join(' ');
    if (q.text.isNotEmpty && q.smart) q.notes.add('“${q.text}”');
    return q;
  }

  static int _minutes(String n, bool andHalf, String unit) {
    double v;
    switch (n) {
      case 'an':
      case 'a':
      case 'one':
        v = 1;
      case 'two':
        v = 2;
      case 'three':
        v = 3;
      case 'four':
        v = 4;
      default:
        v = double.tryParse(n) ?? 0;
    }
    if (andHalf) v += 0.5;
    return (unit.startsWith('h') ? v * 60 : v).round();
  }

  static String _runtimeText(int min) => min % 60 == 0
      ? '${min ~/ 60} h'
      : min >= 60
          ? '${min ~/ 60} h ${min % 60} m'
          : '$min min';

  // ------------------------------------------------------------ running it

  static Future<List<PlexItem>> run(SmartQuery q, {int limit = 60}) async {
    final hits = <_Hit>[];
    final text = q.text.toLowerCase();
    final person = q.person.toLowerCase();
    for (final sec in Plex.enabledSections(await Plex.sections())) {
      final movie = sec.type == 'movie';
      if (!movie && sec.type != 'show') continue;
      if (q.movies && !q.shows && !movie) continue;
      if (q.shows && !q.movies && movie) continue;
      for (final o in await Plex.rawAll(sec.key)) {
        final year = _int(o['year']);
        if (q.yearMin > 0 && year < q.yearMin) continue;
        if (q.yearMax > 0 && year > q.yearMax) continue;
        final dur = _int(o['duration']);
        if (movie || q.maxMinutes > 0 || q.minMinutes > 0) {
          if (q.maxMinutes > 0 && (dur <= 0 || dur > q.maxMinutes * 60000)) continue;
          if (q.minMinutes > 0 && dur < q.minMinutes * 60000) continue;
        }
        var rating = _dbl(o['audienceRating']);
        if (rating <= 0) rating = _dbl(o['rating']);
        if (q.minRating > 0 && rating < q.minRating) continue;
        final seen = movie ? _int(o['viewCount']) > 0 : _int(o['viewedLeafCount']) > 0;
        if (q.unwatched && seen) continue;
        if (q.watched && !seen) continue;
        if (q.resolution.isNotEmpty) {
          final media = o['Media'] as List?;
          final res = media != null && media.isNotEmpty
              ? '${(media.first as Map)['videoResolution'] ?? ''}'
              : '';
          if (res.toLowerCase() != q.resolution) continue;
        }
        if (q.genres.isNotEmpty && !_hasAll(o['Genre'] as List?, q.genres)) continue;
        final title = '${o['title'] ?? ''}'.toLowerCase();
        var titleMatch = false;
        if (person.isNotEmpty) {
          if (!_hasName(o['Role'] as List?, person) &&
              !_hasName(o['Director'] as List?, person) &&
              !_hasName(o['Writer'] as List?, person)) {
            continue;
          }
        }
        if (text.isNotEmpty) {
          titleMatch = _containsAll(title, text);
          if (!titleMatch &&
              !_hasName(o['Role'] as List?, text) &&
              !_hasName(o['Director'] as List?, text) &&
              !_containsAll('${o['summary'] ?? ''}'.toLowerCase(), text)) {
            continue;
          }
        }
        hits.add(_Hit(PlexItem.fromJson(o), rating, _int(o['lastViewedAt']), titleMatch));
      }
    }
    final last = q.lastWatched;
    final byRating = q.minRating > 0;
    hits.sort((a, b) {
      if (last) return b.lastViewed.compareTo(a.lastViewed);
      if (a.titleMatch != b.titleMatch) return a.titleMatch ? -1 : 1;
      if (byRating || a.rating != b.rating) return b.rating.compareTo(a.rating);
      return a.item.title.toLowerCase().compareTo(b.item.title.toLowerCase());
    });
    return [for (final h in hits.take(last ? 12 : limit)) h.item];
  }

  static int _int(Object? v) => v is num ? v.toInt() : int.tryParse('${v ?? ''}') ?? 0;
  static double _dbl(Object? v) => v is num ? v.toDouble() : double.tryParse('${v ?? ''}') ?? -1;

  static bool _hasAll(List? tags, Set<String> want) {
    if (tags == null) return false;
    final have = [for (final t in tags) '${(t as Map)['tag'] ?? ''}'.toLowerCase()];
    for (final w in want) {
      var ok = false;
      for (final h in have) {
        if (h.contains(w) || (w == 'science fiction' && h.contains('sci-fi'))) ok = true;
      }
      if (!ok) return false;
    }
    return true;
  }

  static bool _hasName(List? tags, String name) {
    if (tags == null) return false;
    for (final t in tags) {
      if (_containsAll('${(t as Map)['tag'] ?? ''}'.toLowerCase(), name)) return true;
    }
    return false;
  }

  /// Every word of [words] appears in [hay].
  static bool _containsAll(String hay, String words) {
    for (final w in words.split(RegExp(r'\s+'))) {
      if (w.isNotEmpty && !hay.contains(w)) return false;
    }
    return true;
  }
}

class _Hit {
  final PlexItem item;
  final double rating;
  final int lastViewed;
  final bool titleMatch;
  _Hit(this.item, this.rating, this.lastViewed, this.titleMatch);
}
