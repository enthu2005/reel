import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import 'plex.dart';
import 'prefs.dart';

/// A movie or show as Seerr describes it.
class SeerrMedia {
  String type = 'movie'; // movie or tv
  int id = 0; // TMDB id
  String title = '';
  int year = 0;
  String overview = '';
  String? poster;
  String? backdrop;
  int status = 1; // 1 unknown, 2 pending, 3 processing, 4 partly available, 5 available
  String? ratingKey; // the Plex item, once it's in the library
  int requestStatus = 0; // 1 waiting for approval, 2 approved, 3 declined
  final List<List<int>> seasons = []; // [number, episodes, status]
  int dlPercent = -1;
  int dlMinutesLeft = -1;
  String? digitalRelease;
  bool digitalReleaseFuture = false;
  String? nextEpisode;
  String? youtubeTrailer;

  String get key => 'seerr:$type:$id';

  String get statusText {
    if (requestStatus == 3) return 'Declined';
    if (requestStatus == 1 && status < 4) return 'Waiting for approval';
    switch (status) {
      case 2:
        return 'Requested';
      case 3:
        return 'Downloading';
      case 4:
        return 'Partly available';
      case 5:
        return 'Available';
    }
    return '';
  }

  bool get requestable => status <= 1 || (type == 'tv' && status == 4);

  /// The best "when will I get it" line Seerr can give.
  String get eta {
    if (status == 5) return '';
    if (dlPercent >= 0) {
      final t = dlMinutesLeft < 0
          ? ''
          : dlMinutesLeft < 1
              ? ' · almost done'
              : dlMinutesLeft < 90
                  ? ' · about $dlMinutesLeft min left'
                  : ' · about ${(dlMinutesLeft / 60).round()} hr left';
      return 'Downloading · $dlPercent%$t';
    }
    if (digitalRelease != null && digitalReleaseFuture) {
      return '${status >= 2 ? 'Requested · ' : ''}Out digitally on $digitalRelease${status >= 2 ? ", it'll download then" : ''}';
    }
    if (nextEpisode != null) return 'Next episode: $nextEpisode';
    if (status == 3) return 'Approved · waiting for a download to be found';
    if (status == 2) return 'Waiting for approval';
    return '';
  }

  /// As a PlexItem so the normal cards and rows can show it.
  PlexItem toItem() {
    final i = PlexItem();
    i.key = key;
    i.type = type == 'tv' ? 'show' : 'movie';
    i.title = title;
    i.summary = overview;
    i.year = year;
    i.thumb = poster;
    i.art = backdrop;
    i.note = statusText.isEmpty ? null : statusText;
    Seerr.known[key] = this;
    return i;
  }
}

class SeerrGenre {
  final String type; // movie or tv
  final int id;
  final String name;
  final String? image;
  SeerrGenre(this.type, this.id, this.name, this.image);

  PlexItem toItem() {
    final i = PlexItem();
    i.key = 'seerrgenre:$type:$id';
    i.type = 'genre';
    i.title = name;
    i.thumb = image;
    Seerr.genres[i.key] = this;
    return i;
  }
}

/// Talks to Seerr (Overseerr / Jellyseerr): discover, search and request things that aren't in
/// Plex yet. Signs in with the same Plex account, so only Seerr's address is needed.
class Seerr {
  static const img = 'https://image.tmdb.org/t/p/';
  static final http.Client _client = http.Client();
  static final Map<String, SeerrMedia> known = {};
  static final Map<String, SeerrGenre> genres = {};

  static String get base {
    var u = Prefs.seerrUrl.trim();
    while (u.endsWith('/')) {
      u = u.substring(0, u.length - 1);
    }
    return u;
  }

  static bool get configured => base.isNotEmpty;

  static void setBase(String url) {
    var u = url.trim();
    if (u.isNotEmpty && !u.startsWith('http')) u = 'http://$u';
    Prefs.seerrUrl = u;
    Prefs.seerrCookie = null;
  }

  static Future<String> _send(String method, String path, {Object? body, String? cookie, bool wantCookie = false}) async {
    final req = http.Request(method, Uri.parse('$base$path'));
    req.headers['Accept'] = 'application/json';
    if (cookie != null) req.headers['Cookie'] = cookie;
    if (body != null) {
      req.headers['Content-Type'] = 'application/json';
      req.body = jsonEncode(body);
    }
    final res = await _client.send(req).timeout(const Duration(seconds: 20));
    final text = await res.stream.bytesToString();
    if (wantCookie) {
      final raw = res.headers['set-cookie'] ?? '';
      final m = RegExp(r'connect\.sid=[^;,\s]+').firstMatch(raw);
      final c = m?.group(0) ?? (raw.isEmpty ? null : raw.split(';').first);
      if (c != null) Prefs.seerrCookie = c;
    }
    if (res.statusCode == 401 || res.statusCode == 403) throw const _Auth();
    if (res.statusCode >= 400) {
      var msg = '';
      try {
        msg = ((jsonDecode(text) as Map)['message'] ?? '').toString();
      } catch (_) {}
      throw Exception('Seerr said HTTP ${res.statusCode}${msg.isEmpty ? '' : ': $msg'}');
    }
    return text;
  }

  static Future<void> login() async {
    if (!configured) throw Exception('No Seerr address set');
    await _send('POST', '/api/v1/auth/plex', body: {'authToken': Prefs.token}, wantCookie: true);
    if (Prefs.seerrCookie == null) throw Exception("Seerr didn't return a session");
  }

  static Future<dynamic> _call(String method, String path, {Object? body}) async {
    if (Prefs.seerrCookie == null) await login();
    try {
      return jsonDecode(await _send(method, path, body: body, cookie: Prefs.seerrCookie));
    } on _Auth {
      await login(); // the session ran out: sign in again once
      return jsonDecode(await _send(method, path, body: body, cookie: Prefs.seerrCookie));
    }
  }

  // ------------------------------------------------------------ lists

  static Future<List<SeerrMedia>> _results(String path, [String? forceType]) async {
    final o = await _call('GET', path) as Map;
    final out = <SeerrMedia>[];
    for (final m0 in (o['results'] as List? ?? const [])) {
      final m = m0 as Map;
      final t = forceType ?? (m['mediaType'] ?? '').toString();
      if (t == 'person') continue;
      final x = _parse(m, t.isEmpty ? null : t);
      if (x != null) out.add(x);
    }
    return out;
  }

  static Future<List<SeerrMedia>> trending() => _results('/api/v1/discover/trending?page=1');
  static Future<List<SeerrMedia>> popularMovies() => _results('/api/v1/discover/movies?page=1');
  static Future<List<SeerrMedia>> popularShows() => _results('/api/v1/discover/tv?page=1');
  static Future<List<SeerrMedia>> upcomingMovies() => _results('/api/v1/discover/movies/upcoming?page=1');

  static Future<List<SeerrGenre>> genreList(String type) async {
    final a = await _call('GET', '/api/v1/discover/genreslider/$type') as List;
    return [
      for (final g0 in a)
        () {
          final g = g0 as Map;
          final b = g['backdrops'] as List?;
          return SeerrGenre(type, _int(g['id']), (g['name'] ?? '').toString(),
              b != null && b.isNotEmpty ? '${img}w780${b.first}' : null);
        }()
    ];
  }

  static Future<List<SeerrMedia>> byGenre(String type, int genreId, int page) => _results(
      type == 'tv' ? '/api/v1/discover/tv/genre/$genreId?page=$page' : '/api/v1/discover/movies/genre/$genreId?page=$page',
      type);

  static Future<List<SeerrMedia>> search(String q) =>
      _results('/api/v1/search?query=${Uri.encodeComponent(q)}&page=1');

  static Future<List<SeerrMedia>> moreLike(SeerrMedia m) async {
    final base = '/api/v1/${m.type == 'tv' ? 'tv' : 'movie'}/${m.id}';
    try {
      final r = await _results('$base/recommendations?page=1', m.type);
      if (r.length >= 4) return r;
    } catch (_) {}
    try {
      return await _results('$base/similar?page=1', m.type);
    } catch (_) {
      return [];
    }
  }

  /// Your latest requests, with titles, posters and progress filled in.
  static Future<List<SeerrMedia>> myRequests({int take = 20}) async {
    final o = await _call('GET', '/api/v1/request?take=$take&skip=0&sort=added&filter=all') as Map;
    final reqs = (o['results'] as List? ?? const []).cast<Map>();
    final out = await Future.wait(reqs.map((r) async {
      final media = r['media'] as Map?;
      if (media == null) return null;
      final type = (media['mediaType'] ?? 'movie').toString();
      final tmdb = _int(media['tmdbId']);
      if (tmdb == 0) return null;
      try {
        final m = await details(type, tmdb);
        m.requestStatus = _int(r['status']);
        return m;
      } catch (_) {
        return null;
      }
    }));
    return out.whereType<SeerrMedia>().toList();
  }

  static Future<SeerrMedia> details(String type, int id) async {
    final o = await _call('GET', type == 'tv' ? '/api/v1/tv/$id' : '/api/v1/movie/$id') as Map;
    final m = _parse(o, type);
    if (m == null) throw Exception('Not found');
    known[m.key] = m;
    return m;
  }

  // ------------------------------------------------------------ requests

  static Future<void> requestMovie(int tmdbId) =>
      _call('POST', '/api/v1/request', body: {'mediaType': 'movie', 'mediaId': tmdbId});

  static Future<void> requestShow(int tmdbId, List<int> seasons) =>
      _call('POST', '/api/v1/request', body: {'mediaType': 'tv', 'mediaId': tmdbId, 'seasons': seasons});

  // ------------------------------------------------------------ parsing

  static SeerrMedia? _parse(Map m, String? type) {
    final x = SeerrMedia();
    x.type = type ?? (m.containsKey('name') && !m.containsKey('title') ? 'tv' : 'movie');
    x.id = _int(m['id']);
    if (x.id == 0) return null;
    x.title = (m[x.type == 'tv' ? 'name' : 'title'] ?? m['title'] ?? m['name'] ?? '').toString();
    final date = (m[x.type == 'tv' ? 'firstAirDate' : 'releaseDate'] ?? '').toString();
    if (date.length >= 4) x.year = int.tryParse(date.substring(0, 4)) ?? 0;
    x.overview = (m['overview'] ?? '').toString();
    final p = m['posterPath']?.toString();
    final b = m['backdropPath']?.toString();
    if (p != null && p.isNotEmpty && p != 'null') x.poster = '${img}w342$p';
    if (b != null && b.isNotEmpty && b != 'null') x.backdrop = '${img}w1280$b';
    final info = m['mediaInfo'] as Map?;
    if (info != null) {
      x.status = _int(info['status']) == 0 ? 1 : _int(info['status']);
      final rk = info['ratingKey']?.toString();
      if (rk != null && rk.isNotEmpty && rk != 'null') x.ratingKey = rk;
      final dl = info['downloadStatus'] as List?;
      if (dl != null && dl.isNotEmpty) {
        var size = 0, left = 0, minutes = -1;
        for (final d0 in dl) {
          final d = d0 as Map;
          size += _int(d['size']);
          left += _int(d['sizeLeft']);
          var mins = _minutesUntil(d['estimatedCompletionTime']?.toString());
          if (mins < 0) mins = _parseTimeLeft(d['timeLeft']?.toString());
          if (mins > minutes) minutes = mins;
        }
        if (size > 0) {
          x.dlPercent = (100 - left * 100 ~/ size).clamp(0, 100);
          x.dlMinutesLeft = minutes;
        }
      }
    }
    // Digital release date (movies): the UK first, then the US
    final rel = (m['releases'] as Map?)?['results'] as List?;
    if (rel != null) {
      String? best;
      for (final want in ['GB', 'US']) {
        for (final c0 in rel) {
          final c = c0 as Map;
          if (c['iso_3166_1'] != want) continue;
          for (final d0 in (c['release_dates'] as List? ?? const [])) {
            final d = d0 as Map;
            if (_int(d['type']) == 4) best = (d['release_date'] ?? '').toString();
          }
        }
        if (best != null) break;
      }
      if (best != null && best.length >= 10) {
        final ymd = best.substring(0, 10);
        x.digitalRelease = _niceDate(ymd);
        x.digitalReleaseFuture = ymd.compareTo(DateTime.now().toIso8601String().substring(0, 10)) > 0;
      }
    }
    final vids = m['relatedVideos'] as List?;
    if (vids != null) {
      for (final v0 in vids) {
        final v = v0 as Map;
        if (v['site'] == 'YouTube' && v['type'] == 'Trailer') {
          x.youtubeTrailer = v['key']?.toString();
          break;
        }
      }
    }
    final next = m['nextEpisodeToAir'] as Map?;
    final air = next?['airDate']?.toString() ?? '';
    if (next != null && air.length >= 10) {
      x.nextEpisode = 'S${next['seasonNumber']} E${next['episodeNumber']} on ${_niceDate(air.substring(0, 10))}';
    }
    final seasons = m['seasons'] as List?;
    if (seasons != null) {
      final have = info?['seasons'] as List?;
      for (final s0 in seasons) {
        final s = s0 as Map;
        final n = _int(s['seasonNumber']);
        if (n <= 0) continue;
        var st = 1;
        for (final h0 in (have ?? const [])) {
          final h = h0 as Map;
          if (_int(h['seasonNumber']) == n) st = _int(h['status']);
        }
        x.seasons.add([n, _int(s['episodeCount']), st]);
      }
    }
    return x;
  }

  static const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  static String _niceDate(String ymd) {
    final d = DateTime.tryParse(ymd);
    return d == null ? ymd : '${d.day} ${_months[d.month - 1]} ${d.year}';
  }

  static int _minutesUntil(String? iso) {
    if (iso == null || iso.length < 19) return -1;
    final t = DateTime.tryParse(iso.endsWith('Z') ? iso : '${iso.substring(0, 19)}Z');
    if (t == null) return -1;
    final m = t.difference(DateTime.now().toUtc()).inMinutes;
    return m < 0 ? 0 : m;
  }

  /// Radarr / Sonarr style "01:23:45" (or "1.02:03:04" with days).
  static int _parseTimeLeft(String? s) {
    if (s == null || s.isEmpty) return -1;
    var days = 0;
    var rest = s;
    if (rest.contains('.')) {
      days = int.tryParse(rest.substring(0, rest.indexOf('.'))) ?? 0;
      rest = rest.substring(rest.indexOf('.') + 1);
    }
    final p = rest.split(':');
    if (p.length < 2) return -1;
    return days * 1440 + (int.tryParse(p[0]) ?? 0) * 60 + (int.tryParse(p[1]) ?? 0);
  }

  static int _int(dynamic v) {
    if (v == null) return 0;
    if (v is int) return v;
    if (v is double) return v.toInt();
    return int.tryParse(v.toString()) ?? 0;
  }
}

class _Auth implements Exception {
  const _Auth();
}
