import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:isolate';

import 'package:http/http.dart' as http;

import 'net.dart';
import 'log.dart';
import 'prefs.dart';

/// One audio, video or subtitle stream in a file.
class PlexStream {
  final int id;
  final int type; // 1 video, 2 audio, 3 subtitle
  final String codec;
  final String profile;
  final int channels;
  final String language;
  final String languageCode;
  final String displayTitle;
  final String extendedTitle;
  final bool selected;
  final bool forced;
  final String? key; // subtitle file outside the video
  final bool dovi; // Dolby Vision
  final String colorTrc; // smpte2084 = HDR10 (PQ), arib-std-b67 = HLG
  final String title;

  PlexStream.fromJson(Map<String, dynamic> o)
      : id = _int(o['id']),
        type = _int(o['streamType']),
        codec = (o['codec'] ?? '').toString(),
        profile = (o['profile'] ?? '').toString(),
        channels = _int(o['channels']),
        language = (o['language'] ?? '').toString(),
        languageCode = (o['languageCode'] ?? '').toString(),
        displayTitle = (o['displayTitle'] ?? '').toString(),
        extendedTitle = (o['extendedDisplayTitle'] ?? '').toString(),
        selected = o['selected'] == true,
        forced = o['forced'] == true,
        key = o['key']?.toString(),
        dovi = o['DOVIPresent'] == true ||
            o['DOVIPresent'] == 1 ||
            o['DOVIPresent'] == '1',
        colorTrc = (o['colorTrc'] ?? '').toString(),
        title = (o['title'] ?? '').toString();

  /// ac3, eac3, dts, dtshd, truehd, or 'other' (AAC, FLAC, PCM… which always play).
  String get soundKind {
    final c = codec.toLowerCase();
    final p = '$profile $displayTitle $extendedTitle'.toLowerCase();
    if (c == 'truehd') return 'truehd';
    if (c == 'dca' || c == 'dts')
      return p.contains('ma') || p.contains('hra') || p.contains('hd')
          ? 'dtshd'
          : 'dts';
    if (c == 'eac3') return 'eac3';
    if (c == 'ac3') return 'ac3';
    return 'other';
  }

  bool get playable {
    switch (soundKind) {
      case 'truehd':
        return Prefs.canTruehd;
      case 'dtshd':
        return Prefs.canDtshd;
      case 'dts':
        return Prefs.canDts;
      case 'eac3':
        return Prefs.canEac3;
      case 'ac3':
        return Prefs.canAc3;
    }
    return true;
  }

  String get label => extendedTitle.isNotEmpty ? extendedTitle : displayTitle;
}

class CastMember {
  final String name;
  final String role;
  final String? thumb;
  final String filter;

  CastMember(this.name, this.role, this.thumb, this.filter);
}

/// A movie, show, season or episode.
class PlexItem {
  String key = '';
  String type = '';
  String title = '';
  String summary = '';
  int year = 0;
  String contentRating = '';
  String? thumb;
  String? art;
  String? logo;
  String? gpTitle;
  String? gpKey;
  String? gpThumb;
  String? gpArt;
  int duration = 0;
  int viewOffset = 0;
  int viewCount = 0;
  int index = 0;
  int parentIndex = 0;
  double aspect =
      0; // the film's shape, e.g. 2.39 for a wide film (0 = unknown)
  String? partKey;
  String? librarySectionKey;
  String videoRes = '';
  String?
      note; // e.g. a Seerr status ("Requested", "Available") shown under the card
  String mAudioCodec = '';
  int mAudioChannels = 0;
  String mVideoProfile = '';
  final List<PlexStream> streams = [];
  final List<CastMember> cast = [];
  final List<List<int>> intros = []; // [start, end] ms
  final List<List<int>> credits = [];
  final List<String> genres = [];
  String tagline = '';
  int addedAt = 0; // seconds since 1970
  int leafCount = 0; // episodes in a show
  int viewedLeafCount = 0;
  String collName = ''; // the collection / franchise it belongs to, if any
  String collFilter = ''; // e.g. "collection=123"
  final List<List<String>> ratings = []; // [source, shown], e.g. ['RT', '92%']
  final List<CastMember> directors = [];

  /// Watched all the way through (a film or episode seen, or every episode of a show).
  bool get isWatched {
    if (type == 'show' || type == 'season') {
      return leafCount > 0 && viewedLeafCount >= leafCount;
    }
    return viewCount > 0 && viewOffset <= 0;
  }

  /// Added in the last week and not started.
  bool get isNew {
    if (addedAt <= 0 || isWatched || viewOffset > 0 || viewedLeafCount > 0) {
      return false;
    }
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    return now - addedAt < 7 * 24 * 3600;
  }

  bool get isPlayable =>
      (type == 'movie' || type == 'episode') && !key.startsWith('seerr');
  bool get isSeerr => key.startsWith('seerr');
  String? get heroArt => art ?? gpArt;
  String? get poster =>
      (type == 'episode' && gpThumb != null) ? gpThumb : thumb;
  String get displayTitle =>
      (type == 'episode' && gpTitle != null) ? gpTitle! : title;

  /// The chosen sound track is TrueHD / Atmos or DTS-HD (lossless).
  bool get hasLosslessAudio {
    final a = streamsOf(2);
    if (a.isEmpty) return mAudioCodec.toLowerCase() == 'truehd';
    final s = a.firstWhere((x) => x.selected, orElse: () => a.first);
    final c = s.codec.toLowerCase();
    final p = '${s.profile} ${s.displayTitle} ${s.extendedTitle}'.toLowerCase();
    return c == 'truehd' ||
        (c == 'dca' || c == 'dts') && (p.contains('ma') || p.contains('hd'));
  }

  List<PlexStream> streamsOf(int t) =>
      streams.where((s) => s.type == t).toList();

  /// Picture and sound labels for the title page: 4K, Dolby Vision, HDR10, Dolby Atmos, DTS-HD MA, 5.1, CC.
  /// Full details need the title's own metadata (streams); list items give a rougher answer.
  List<String> get badges {
    final out = <String>[];
    final r = videoRes.toLowerCase();
    if (r == '4k' || r == '2160') {
      out.add('4K');
    } else if (r == '1080' || r == '720') {
      out.add('HD');
    } else if (r.isNotEmpty) {
      out.add('SD');
    }

    final v = streamsOf(1);
    if (v.isNotEmpty) {
      final s = v.first;
      final t = '${s.displayTitle} ${s.extendedTitle}'.toLowerCase();
      if (s.dovi || t.contains('dovi') || t.contains('dolby vision')) {
        out.add('Dolby Vision');
      } else if (t.contains('hdr10+')) {
        out.add('HDR10+');
      } else if (s.colorTrc == 'smpte2084' || t.contains('hdr')) {
        out.add('HDR10');
      } else if (s.colorTrc == 'arib-std-b67' || t.contains('hlg')) {
        out.add('HLG');
      }
    } else if (mVideoProfile.toLowerCase().contains('dolby')) {
      out.add('Dolby Vision');
    }

    final a = streamsOf(2);
    PlexStream? s;
    if (a.isNotEmpty)
      s = a.firstWhere((x) => x.selected, orElse: () => a.first);
    final codec = (s?.codec ?? mAudioCodec).toLowerCase();
    final profile = (s?.profile ?? '').toLowerCase();
    final at =
        '${s?.displayTitle ?? ''} ${s?.extendedTitle ?? ''} ${s?.title ?? ''}'
            .toLowerCase();
    String? sound;
    if (at.contains('atmos')) {
      sound = 'Dolby Atmos';
    } else if (codec == 'truehd') {
      sound = 'Dolby TrueHD';
    } else if (codec == 'eac3') {
      sound = 'Dolby Digital+';
    } else if (codec == 'ac3') {
      sound = 'Dolby Digital';
    } else if (codec == 'dca' || codec == 'dts' || codec.startsWith('dts')) {
      if (profile == 'x' || at.contains('dts:x') || at.contains('dts-x')) {
        sound = 'DTS:X';
      } else if (profile == 'ma' ||
          at.contains('dts-hd ma') ||
          at.contains('dts-hd master')) {
        sound = 'DTS-HD MA';
      } else if (profile == 'hra' || at.contains('dts-hd hra')) {
        sound = 'DTS-HD HRA';
      } else {
        sound = 'DTS';
      }
    } else if (codec == 'flac') {
      sound = 'FLAC';
    } else if (codec == 'aac') {
      sound = 'AAC';
    }
    if (sound != null) out.add(sound);
    if (streamsOf(3).isNotEmpty) out.add('CC');
    return out;
  }

  String metaLineFor({bool withRuntime = true}) {
    final p = <String>[];
    switch (type) {
      case 'movie':
        p.add('Movie');
        break;
      case 'show':
        p.add('TV Show');
        break;
      case 'season':
        p.add('Season');
        break;
      case 'episode':
        if (parentIndex > 0) p.add('S$parentIndex E$index');
        p.add(title);
        break;
      default:
        p.add(type);
    }
    if (type != 'episode' && year > 0) p.add('$year');
    if (genres.isNotEmpty && type != 'episode')
      p.add(genres.take(2).join(', '));
    if (contentRating.isNotEmpty) p.add(contentRating);
    if (withRuntime && duration > 0 && isPlayable) {
      p.add(formatDuration(duration));
    }
    if (note != null) p.add(note!);
    return p.join(' · ');
  }

  String get metaLine => metaLineFor();

  /// Turns Plex's rating + image id (e.g. rottentomatoes://image.rating.ripe) into a labelled score.
  void _addRating(dynamic value, dynamic image) {
    final v = double.tryParse('${value ?? ''}');
    final img = (image ?? '').toString();
    if (v == null || img.isEmpty) return;
    String src, shown;
    if (img.startsWith('rottentomatoes')) {
      src = 'RT';
      shown = '${(v * 10).round()}%';
    } else if (img.startsWith('imdb')) {
      src = 'IMDb';
      shown = v.toStringAsFixed(1);
    } else if (img.startsWith('themoviedb')) {
      src = 'TMDb';
      shown = '${(v * 10).round()}%';
    } else {
      return;
    }
    if (ratings.any((r) => r[0] == src)) return;
    ratings.add([src, shown]);
  }

  static PlexItem fromJson(Map<String, dynamic> m) {
    final i = PlexItem();
    i.key = (m['ratingKey'] ?? '').toString();
    i.type = (m['type'] ?? '').toString();
    i.title = (m['title'] ?? '').toString();
    i.summary = (m['summary'] ?? '').toString();
    i.year = _int(m['year']);
    i.contentRating = (m['contentRating'] ?? '').toString();
    i.thumb = m['thumb']?.toString();
    i.art = m['art']?.toString();
    i.gpTitle = m['grandparentTitle']?.toString();
    i.gpKey = m['grandparentRatingKey']?.toString();
    i.gpThumb = m['grandparentThumb']?.toString();
    i.gpArt = m['grandparentArt']?.toString();
    i.librarySectionKey = m['librarySectionID']?.toString();
    i.tagline = (m['tagline'] ?? '').toString();
    i.addedAt = _int(m['addedAt']);
    i.leafCount = _int(m['leafCount']);
    i.viewedLeafCount = _int(m['viewedLeafCount']);
    i._addRating(m['rating'], m['ratingImage']);
    i._addRating(m['audienceRating'], m['audienceRatingImage']);
    for (final c in (m['Collection'] as List? ?? const [])) {
      final o = c as Map;
      final f = (o['filter'] ?? '').toString();
      final t = (o['tag'] ?? '').toString();
      if (f.startsWith('collection=') && t.isNotEmpty) {
        i.collName = t;
        i.collFilter = f;
        break;
      }
    }
    for (final d in (m['Director'] as List? ?? const []).take(3)) {
      final o = d as Map;
      final name = (o['tag'] ?? '').toString();
      final filter = (o['filter'] ?? '').toString();
      if (name.isNotEmpty && filter.isNotEmpty) {
        i.directors.add(CastMember(name, 'Director', o['thumb']?.toString(), filter));
      }
    }
    i.duration = _int(m['duration']);
    i.viewOffset = _int(m['viewOffset']);
    i.viewCount = _int(m['viewCount']);
    i.index = _int(m['index']);
    i.parentIndex = _int(m['parentIndex']);
    for (final g in (m['Genre'] as List? ?? const [])) {
      final t = (g as Map)['tag'];
      if (t != null) i.genres.add(t.toString());
    }
    for (final r in (m['Role'] as List? ?? const []).take(30)) {
      final ro = r as Map;
      final name = (ro['tag'] ?? '').toString();
      final filter = (ro['filter'] ?? '').toString();
      if (name.isNotEmpty) {
        i.cast.add(CastMember(name, (ro['role'] ?? '').toString(),
            ro['thumb']?.toString(), filter));
      }
    }
    for (final mk in (m['Marker'] as List? ?? const [])) {
      final o = mk as Map;
      final range = [_int(o['startTimeOffset']), _int(o['endTimeOffset'])];
      if (o['type'] == 'intro') i.intros.add(range);
      if (o['type'] == 'credits') i.credits.add(range);
    }
    for (final im in (m['Image'] as List? ?? const [])) {
      final o = im as Map;
      if (o['type'] == 'clearLogo' && o['url'] != null)
        i.logo = o['url'].toString();
    }
    final media = m['Media'] as List?;
    if (media != null && media.isNotEmpty) {
      final m0 = media.first as Map;
      i.videoRes = (m0['videoResolution'] ?? '').toString();
      i.mAudioCodec = (m0['audioCodec'] ?? '').toString();
      i.mAudioChannels = _int(m0['audioChannels']);
      i.mVideoProfile = (m0['videoProfile'] ?? '').toString();
      i.aspect = double.tryParse('${m0['aspectRatio'] ?? ''}') ?? 0;
      if (i.aspect <= 0) {
        final vw = _int(m0['width']), vh = _int(m0['height']);
        if (vw > 0 && vh > 0) i.aspect = vw / vh;
      }
      final parts = m0['Part'] as List?;
      if (parts != null && parts.isNotEmpty) {
        final p0 = parts.first as Map;
        i.partKey = p0['key']?.toString();
        for (final s in (p0['Stream'] as List? ?? const [])) {
          i.streams
              .add(PlexStream.fromJson(Map<String, dynamic>.from(s as Map)));
        }
      }
    }
    return i;
  }
}

/// A subtitle Plex found online and can download.
class SubtitleFind {
  final String key;
  final String title;
  final String provider;
  final double score;
  SubtitleFind(this.key, this.title, this.provider, this.score);
}

class PlexSection {
  final String key;
  final String type;
  final String title;
  final List<String> paths; // the folders on the server this library reads from

  PlexSection(this.key, this.type, this.title, [this.paths = const []]);

  bool get hiddenFromHome => switch (title.trim().toLowerCase()) {
        'demo' || 'tamil comedy' || 'tamil music videos' => true,
        _ => false,
      };
}

class PlexHomeUser {
  final String id;
  final String title;
  final String? thumb;
  final bool admin;

  PlexHomeUser(this.id, this.title, this.thumb, this.admin);
}

/// A quick look at a library: how many titles, and the newest one (for its picture).
class SectionPeek {
  final int count;
  final PlexItem? latest;
  SectionPeek(this.count, this.latest);
}

int _int(dynamic v) {
  if (v == null) return 0;
  if (v is int) return v;
  if (v is double) return v.toInt();
  return int.tryParse(v.toString()) ?? 0;
}

String formatDuration(int ms) {
  final min = ms ~/ 60000;
  if (min >= 60) return '${min ~/ 60}h ${min % 60}m';
  return '${min}m';
}

/// Talks to plex.tv (sign-in) and your Plex server.
class Plex {
  static final http.Client _client = makeClient();
  static String? _sessionToken;
  static String? _sessionOwnerToken;

  static String? get token => _sessionToken ?? Prefs.token;

  /// What to show your Plex server: a shared server has its own access token; your own server takes
  /// the account token.
  static String? get serverToken =>
      Prefs.serverToken.isNotEmpty ? Prefs.serverToken : token;
  static String? get server => Prefs.server;
  static String? get serverName => Prefs.serverName;
  static bool get isReady => token != null && server != null;

  /// At start-up, look again for the best address in the background: at home that's the local
  /// one, away it's the direct remote one, and only if neither answers, Plex's slow relay.
  static Future<void> init() async {
    if (isReady) unawaited(findServer().catchError((_) {}));
  }

  static Map<String, String> get headers => {
        'Accept': 'application/json',
        'X-Plex-Client-Identifier': Prefs.clientId,
        'X-Plex-Product': 'Reel',
        'X-Plex-Version': '0.1',
        'X-Plex-Platform': 'Windows',
        'X-Plex-Device': 'Windows',
        'X-Plex-Device-Name': 'Reel for Windows',
        if (serverToken != null) 'X-Plex-Token': serverToken!,
      };

  static Future<String> _send(String method, String url,
      {Duration timeout = const Duration(seconds: 15)}) async {
    final req = http.Request(method, Uri.parse(url));
    req.headers.addAll(headers);
    // plex.tv always gets the account token; only your server gets the shared-server token
    if (req.url.host.endsWith('plex.tv') && token != null) req.headers['X-Plex-Token'] = token!;
    final res = await _client.send(req).timeout(timeout);
    final body = await res.stream.bytesToString().timeout(timeout);
    if (res.statusCode >= 400) {
      final u = Uri.parse(url);
      final what = '${u.host.endsWith('plex.tv') ? 'plex.tv' : 'server'}${u.path}';
      Log.w('HTTP ${res.statusCode}  $method $what');
      throw Exception('HTTP ${res.statusCode} ($what)');
    }
    return body;
  }

  static Future<Map<String, dynamic>> _get(String path) async {
    final body = await _send('GET', '$server$path');
    return Map<String, dynamic>.from(jsonDecode(body) as Map);
  }

  static String paged(String path, int size) =>
      '$path${path.contains('?') ? '&' : '?'}X-Plex-Container-Start=0&X-Plex-Container-Size=$size';

  // ------------------------------------------------------------ sign-in

  /// Starts a sign-in: returns {id, code}. The user enters the code at plex.tv/link.
  static Future<Map<String, dynamic>> createPin() async {
    final body =
        await _send('POST', 'https://plex.tv/api/v2/pins?strong=false');
    return Map<String, dynamic>.from(jsonDecode(body) as Map);
  }

  static Future<String?> pollPin(int id) async {
    final body = await _send('GET', 'https://plex.tv/api/v2/pins/$id');
    final o = jsonDecode(body) as Map;
    final t = o['authToken'];
    return t == null || t.toString().isEmpty ? null : t.toString();
  }

  /// Picks your own server first, preferring local addresses, then remote, then relay.
  static Future<void> findServer() async {
    final body = await _send('GET',
        'https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1');
    final res = jsonDecode(body) as List;
    final candidates = <List<String>>[]; // [uri, name, kind, 'mine'|'shared', serverToken]
    for (final pass in [true, false]) {
      for (final r0 in res) {
        final r = r0 as Map;
        if (!(r['provides'] ?? '').toString().contains('server')) continue;
        if ((r['owned'] == true) != pass) continue;
        final name = (r['name'] ?? 'Plex').toString();
        final local = <String>[];
        final remote = <String>[];
        final relay = <String>[];
        for (final c0 in (r['connections'] as List? ?? const [])) {
          final c = c0 as Map;
          final uri = (c['uri'] ?? '').toString();
          if (uri.isEmpty) continue;
          if (c['relay'] == true) {
            relay.add(uri);
          } else if (c['local'] == true) {
            final addr = (c['address'] ?? '').toString();
            if (addr.isNotEmpty) {
              local.add('http://$addr:${c['port'] ?? 32400}');
              local.add(
                  'https://$addr:${c['port'] ?? 32400}'); // by IP: works even if your router blocks plex.direct names
            }
            local.add(uri);
          } else {
            remote.add(uri);
          }
        }
        final whose = pass ? 'mine' : 'shared';
        final srvTok = pass ? '' : (r['accessToken'] ?? '').toString();
        for (final u in local) {
          candidates.add([u, name, 'local', whose, srvTok]);
        }
        for (final u in remote) {
          candidates.add([u, name, 'remote', whose, srvTok]);
        }
        for (final u in relay) {
          candidates.add([u, name, 'relay', whose, srvTok]);
        }
      }
    }
    if (candidates.isEmpty)
      throw Exception('No Plex server found on this account');

    // Try them all at once, then take the best (earliest in the list) that answered.
    final ok = List<bool>.filled(candidates.length, false);
    final ms = List<int>.filled(candidates.length, -1);
    await Future.wait(List.generate(candidates.length, (i) async {
      final sw = Stopwatch()..start();
      try {
        await _send('GET', '${candidates[i][0]}/identity',
            timeout: const Duration(seconds: 5));
        ok[i] = true;
        ms[i] = sw.elapsedMilliseconds;
      } catch (_) {}
    })).timeout(const Duration(seconds: 9), onTimeout: () => []);
    // What was tried, for Settings: "local http ✗ · local https ✓ 12 ms · remote ✓ 95 ms"
    Prefs.serverTest = [
      for (var i = 0; i < candidates.length; i++)
        '${candidates[i][2]} ${candidates[i][0].startsWith('https://') ? (candidates[i][0].contains('plex.direct') ? 'name' : 'https') : 'http'} '
            '${ok[i] ? '✓ ${ms[i]} ms' : '✗'}'
    ].join('  ·  ');
    // Your own server always wins, even if someone else's answers quicker: picking a friend's server
    // would show their library and leave your own titles missing. If you've chosen one by hand in
    // Settings, that one wins outright. After that it's your own network, then the internet, then
    // Plex's relay, and only then speed.
    const rank = {'local': 0, 'remote': 1, 'relay': 2};
    var best = -1;
    for (var i = 0; i < candidates.length; i++) {
      if (!ok[i]) continue;
      final wanted = Prefs.serverPick;
      if (wanted.isNotEmpty && candidates[i][1] != wanted) continue;
      if (best < 0) {
        best = i;
        continue;
      }
      final mineA = candidates[best][3] == 'mine',
          mineB = candidates[i][3] == 'mine';
      if (mineA != mineB) {
        if (mineB) best = i;
        continue;
      }
      final a = rank[candidates[best][2]] ?? 3;
      final b = rank[candidates[i][2]] ?? 3;
      if (b < a || (b == a && ms[i] < ms[best])) best = i;
    }
    // Remember which servers were found, so Settings can offer the choice.
    Prefs.serverList = {
      for (var i = 0; i < candidates.length; i++)
        if (ok[i]) candidates[i][1]
    }.join('|');
    if (best >= 0) {
      Prefs.serverToken = candidates[best][4];
      Prefs.server = candidates[best][0];
      Prefs.serverName = candidates[best][1];
      Prefs.serverKind = candidates[best][2];
      return;
    }
    throw Exception('Found your server but none of its addresses answered');
  }

  static void signIn(String t) {
    Prefs.profileChosen = false;
    _sessionOwnerToken = t;
    _sessionToken = t;
    if (Prefs.staySignedIn) {
      Prefs.ownerToken = t;
      Prefs.token = t;
    } else {
      Prefs.ownerToken = null;
      Prefs.token = null;
    }
  }

  static Future<List<PlexHomeUser>> homeUsers() async {
    final owner = _sessionOwnerToken ?? Prefs.ownerToken ?? token;
    if (owner == null) return [];
    final req = http.Request(
        'GET',
        Uri.parse(
            'https://plex.tv/api/v2/home/users?X-Plex-Client-Identifier=${Uri.encodeQueryComponent(Prefs.clientId)}&X-Plex-Token=${Uri.encodeQueryComponent(owner)}'));
    req.headers.addAll({...headers, 'X-Plex-Token': owner});
    final res = await _client.send(req).timeout(const Duration(seconds: 15));
    final body = await res.stream.bytesToString();
    if (res.statusCode >= 400) {
      Log.w('HTTP ${res.statusCode}  GET plex.tv/api/v2/home/users');
      throw Exception('HTTP ${res.statusCode} (plex.tv/api/v2/home/users)');
    }
    final decoded = jsonDecode(body);
    final raw = decoded is List
        ? decoded
        : (decoded is Map ? (decoded['users'] ?? decoded['User'] ?? []) : []);
    return [
      for (final value in raw is List ? raw : const [])
        if (value is Map)
          PlexHomeUser(
              '${value['id'] ?? value['uuid'] ?? ''}',
              '${value['title'] ?? value['username'] ?? 'Plex user'}',
              value['thumb']?.toString(),
              value['admin'] == true || value['admin'] == '1')
    ];
  }

  static Future<void> switchHomeUser(PlexHomeUser user) async {
    final owner = _sessionOwnerToken ?? Prefs.ownerToken ?? token;
    if (owner == null) throw Exception('Not signed in');
    final body = await _send('POST',
        'https://plex.tv/api/home/users/${Uri.encodeComponent(user.id)}/switch?X-Plex-Token=${Uri.encodeQueryComponent(owner)}');
    String? selectedToken;
    if (body.trimLeft().startsWith('<')) {
      // plex.tv sometimes answers in XML: pull the token out of it
      selectedToken = RegExp(r'(?:authenticationToken|authToken)="([^"]+)"')
          .firstMatch(body)
          ?.group(1);
    } else {
      final decoded = jsonDecode(body);
      selectedToken = decoded is Map
          ? (decoded['authToken'] ?? decoded['authentication_token'])
              ?.toString()
          : null;
    }
    if (selectedToken == null || selectedToken.isEmpty)
      throw Exception('Plex did not return a profile token');
    _sessionToken = selectedToken;
    if (Prefs.staySignedIn) Prefs.token = selectedToken;
    Prefs.profileChosen = Prefs.staySignedIn;
  }

  static void signOut() {
    _sessionToken = null;
    _sessionOwnerToken = null;
    Prefs.token = null;
    Prefs.ownerToken = null;
    Prefs.profileChosen = false;
    Prefs.server = null;
    Prefs.serverToken = '';
    Prefs.serverName = null;
  }

  // ------------------------------------------------------------ library

  static Future<List<PlexItem>> items(String path) async {
    final body = await _send('GET', '$server$path');
    // Big lists (a whole library) are read off the main thread so the screen never stutters.
    if (body.length > 120000) return Isolate.run(() => _parseItems(body));
    return _parseItems(body);
  }

  static List<PlexItem> _parseItems(String body) {
    final mc = Map<String, dynamic>.from(
        (jsonDecode(body) as Map)['MediaContainer'] as Map);
    final list = (mc['Metadata'] ?? mc['Directory']) as List?;
    if (list == null) return [];
    return list
        .map((e) => PlexItem.fromJson(Map<String, dynamic>.from(e as Map)))
        .toList();
  }

  static Future<PlexItem> metadata(String ratingKey) async {
    final l = await items(
        '/library/metadata/$ratingKey?includeMarkers=1&includeChapters=1');
    if (l.isEmpty) throw Exception('Not found');
    return l.first;
  }

  static Future<List<PlexSection>> sections() async {
    final mc = (await _get('/library/sections'))['MediaContainer'] as Map;
    final d = mc['Directory'] as List? ?? const [];
    return d.map((e) {
      final o = e as Map;
      final paths = [
        for (final l in (o['Location'] as List? ?? const []))
          (l as Map)['path'].toString(),
      ];
      return PlexSection((o['key'] ?? '').toString(),
          (o['type'] ?? '').toString(), (o['title'] ?? '').toString(), paths);
    }).toList();
  }

  /// The genres in a library, biggest first-ish, as [key, title] pairs.
  static Future<List<List<String>>> genresOf(String sectionKey) async {
    final mc =
        (await _get('/library/sections/$sectionKey/genre'))['MediaContainer']
            as Map;
    final out = <List<String>>[];
    for (final d in (mc['Directory'] as List? ?? const [])) {
      final o = d as Map;
      final title = (o['title'] ?? '').toString();
      final fast = (o['fastKey'] ?? '').toString();
      final key = (o['key'] ?? '').toString();
      if (title.isEmpty || (fast.isEmpty && key.isEmpty)) continue;
      out.add([
        fast.isNotEmpty ? fast : '/library/sections/$sectionKey/all?genre=$key',
        title
      ]);
    }
    return out;
  }

  /// Everything in a library with a given person in it ([filter] is Plex's own, e.g. "actor=1234").
  static Future<List<PlexItem>> withPerson(String sectionKey, String filter) =>
      items(paged('/library/sections/$sectionKey/all?$filter', 200));

  static Future<List<PlexItem>> byGenre(String path, {int count = 20}) =>
      items(paged('$path&sort=audienceRating:desc', count));

  /// Subtitles Plex can fetch from the internet for this title.
  static Future<List<SubtitleFind>> searchSubtitles(
      String ratingKey, String language) async {
    final mc = (await _get('/library/metadata/$ratingKey/subtitles'
            '?language=$language&hearingImpaired=0&forced=0'))['MediaContainer']
        as Map;
    final out = <SubtitleFind>[];
    for (final s in (mc['Stream'] as List? ?? const [])) {
      final o = s as Map;
      final key = (o['key'] ?? '').toString();
      if (key.isEmpty) continue;
      out.add(SubtitleFind(
        key,
        (o['title'] ?? o['displayTitle'] ?? 'Subtitles').toString(),
        (o['providerTitle'] ?? o['sourceKey'] ?? '').toString(),
        double.tryParse('${o['score'] ?? 0}') ?? 0,
      ));
    }
    return out;
  }

  /// Tells the server to fetch one of those and attach it to the title.
  static Future<void> downloadSubtitle(String ratingKey, String key) => _send(
      'PUT',
      '$server/library/metadata/$ratingKey/subtitles?key=${Uri.encodeQueryComponent(key)}',
      timeout: const Duration(seconds: 60));

  static Future<SectionPeek> sectionPeek(String key) async {
    final mc =
        (await _get(paged('/library/sections/$key/all?sort=addedAt:desc', 1)))[
            'MediaContainer'] as Map;
    final list = mc['Metadata'] as List?;
    final latest = (list == null || list.isEmpty)
        ? null
        : PlexItem.fromJson(Map<String, dynamic>.from(list.first as Map));
    return SectionPeek(
        int.tryParse('${mc['totalSize'] ?? mc['size'] ?? 0}') ?? 0, latest);
  }

  static Future<List<PlexItem>> onDeck() => items(paged('/library/onDeck', 20));

  static Future<List<PlexItem>> recentlyAdded(String sectionKey,
          {int count = 40}) =>
      items(
          paged('/library/sections/$sectionKey/all?sort=addedAt:desc', count));

  static Future<List<PlexItem>> recentlyReleased(String sectionKey,
          {int count = 20}) =>
      items(paged(
          '/library/sections/$sectionKey/all?sort=originallyAvailableAt:desc',
          count));

  /// Films you've finished, most recently watched first.
  static Future<List<PlexItem>> recentlyWatched(String sectionKey,
          {int count = 3}) =>
      items(paged(
          '/library/sections/$sectionKey/all?sort=lastViewedAt:desc&unwatched=0',
          count));

  /// "More like this": what Plex suggests next to a film or show.
  static Future<List<PlexItem>> related(String ratingKey) async {
    final mc = (await _get('/library/metadata/$ratingKey/related?count=24'))[
        'MediaContainer'] as Map;
    for (final h in (mc['Hub'] as List? ?? const [])) {
      final hub = h as Map;
      final t = (hub['type'] ?? '').toString();
      if (t != 'movie' && t != 'show') continue;
      final out = <PlexItem>[];
      final seen = <String>{};
      for (final m in (hub['Metadata'] as List? ?? const [])) {
        final it = PlexItem.fromJson(Map<String, dynamic>.from(m as Map));
        if (it.key != ratingKey && seen.add(it.key)) out.add(it);
      }
      if (out.isNotEmpty) return out;
    }
    return [];
  }

  static Future<List<PlexItem>> all(String sectionKey,
          {String sort = 'titleSort'}) =>
      items(paged('/library/sections/$sectionKey/all?sort=$sort', 3000));

  static Future<List<PlexItem>> relatedInLibrary(PlexItem item) async {
    final section = item.librarySectionKey;
    if (section == null || section.isEmpty) return [];
    final allItems = await all(section);
    final related = allItems
        .where(
            (x) => x.key != item.key && (x.type == 'movie' || x.type == 'show'))
        .toList();
    related.shuffle(Random());
    return related.take(20).toList();
  }

  static Future<List<PlexItem>> children(String ratingKey) =>
      items('/library/metadata/$ratingKey/children');

  /// The first episode of a show (used for a preview when the show has no trailer).
  static Future<PlexItem?> firstEpisode(String showKey) async {
    final l = await items(paged('/library/metadata/$showKey/allLeaves', 1));
    return l.isEmpty ? null : l.first;
  }

  static Future<List<PlexItem>> allEpisodes(String showKey) =>
      items(paged('/library/metadata/$showKey/allLeaves', 800));

  static Future<List<PlexItem>> search(String q) async {
    final mc = (await _get(
            '/hubs/search?query=${Uri.encodeQueryComponent(q)}&limit=24'))[
        'MediaContainer'] as Map;
    final out = <PlexItem>[];
    for (final h in (mc['Hub'] as List? ?? const [])) {
      final hub = h as Map;
      final t = hub['type'];
      if (t != 'movie' && t != 'show' && t != 'episode') continue;
      for (final m in (hub['Metadata'] as List? ?? const [])) {
        out.add(PlexItem.fromJson(Map<String, dynamic>.from(m as Map)));
      }
    }
    return out;
  }

  /// A trailer Plex shows in its own apps (your files, or one Plex fetched online), as a playable URL.
  static Future<String?> trailerUrl(String ratingKey) async {
    String? pick(List? a) {
      if (a == null) return null;
      String? first;
      for (final o0 in a) {
        final o = o0 as Map;
        final media = o['Media'] as List?;
        if (media == null || media.isEmpty) continue;
        final parts = (media.first as Map)['Part'] as List?;
        if (parts == null || parts.isEmpty) continue;
        final k = ((parts.first as Map)['key'] ?? '').toString();
        if (k.isEmpty) continue;
        final url = k.startsWith('http') ? k : raw(k);
        final trailer = o['subtype'] == 'trailer' || _int(o['extraType']) == 1;
        if (trailer) return url;
        first ??= url;
      }
      return first;
    }

    try {
      final mc = (await _get('/library/metadata/$ratingKey?includeExtras=1'))[
          'MediaContainer'] as Map;
      final m = mc['Metadata'] as List?;
      if (m != null && m.isNotEmpty) {
        final ex = (m.first as Map)['Extras'] as Map?;
        final u = pick(ex?['Metadata'] as List?);
        if (u != null) return u;
      }
    } catch (_) {}
    try {
      final mc =
          (await _get('/library/metadata/$ratingKey/extras'))['MediaContainer']
              as Map;
      return pick(mc['Metadata'] as List?);
    } catch (_) {
      return null;
    }
  }

  /// Where a scene clip should start: a chapter a little before the middle (skipping the opening
  /// and the credits), or a fifth of the way in when there are no chapters.
  static Future<int> sceneOffset(String ratingKey, int durationMs) async {
    var fallback = (durationMs * 0.2).round();
    try {
      final mc = (await _get('/library/metadata/$ratingKey?includeChapters=1'))[
          'MediaContainer'] as Map;
      final m = mc['Metadata'] as List?;
      if (m == null || m.isEmpty) return fallback;
      final o = m.first as Map;
      final dur = durationMs > 0 ? durationMs : _int(o['duration']);
      if (fallback <= 0) fallback = (dur * 0.2).round();
      final ch = o['Chapter'] as List?;
      if (ch == null || ch.length < 5 || dur <= 0) return fallback;
      final target = dur * 0.42;
      var best = -1;
      var bestDiff = double.infinity;
      for (var i = 2; i < ch.length - 2; i++) {
        final st = _int((ch[i] as Map)['startTimeOffset']);
        if (st < dur * 0.25 || st > dur * 0.7) continue;
        final d = (st - target).abs();
        if (d < bestDiff) {
          bestDiff = d;
          best = st;
        }
      }
      return best > 0 ? best + 2000 : fallback;
    } catch (_) {
      return fallback;
    }
  }

  /// A light stream of the film from [offsetMs] for previews: 1080p at most, stereo AAC,
  /// so it starts fast over weak Wi-Fi instead of pulling the full-size file.
  static String previewUrl(String ratingKey, int offsetMs, String session) {
    String e(String s) => Uri.encodeQueryComponent(s);
    const profile =
        'add-transcode-target(type=videoProfile&context=streaming&protocol=hls'
        '&container=mpegts&videoCodec=h264,hevc&audioCodec=aac)'
        '+add-limitation(scope=videoAudioCodec&scopeName=aac&type=upperBound&name=audio.channels&value=2)';
    return '$server/video/:/transcode/universal/start.m3u8'
        '?path=${e('/library/metadata/$ratingKey')}'
        '&mediaIndex=0&partIndex=0&protocol=hls&fastSeek=1'
        '&directPlay=0&directStream=1&directStreamAudio=0&audioChannelCount=2'
        '&maxVideoBitrate=3500&videoQuality=100&videoResolution=1280x720'
        '&subtitleStreamID=0&subtitles=none'
        '&offset=${offsetMs ~/ 1000}&session=$session'
        '&X-Plex-Client-Profile-Extra=${e(profile)}'
        '&X-Plex-Client-Profile-Name=Konvergo'
        '&X-Plex-Client-Identifier=${e(Prefs.clientId)}'
        '&X-Plex-Product=Reel&X-Plex-Version=0.1&X-Plex-Platform=Windows'
        '&X-Plex-Device=Windows&X-Plex-Device-Name=Reel'
        '&X-Plex-Token=$serverToken';
  }

  /// Whole film, video untouched, but the sound converted to 2-channel AAC by the server.
  /// Used for TrueHD / DTS-HD, which this PC can't always decode or pass through.
  static String stereoUrl(String ratingKey, String session) {
    String e(String s) => Uri.encodeQueryComponent(s);
    const profile =
        'add-transcode-target(type=videoProfile&context=streaming&protocol=hls'
        '&container=mpegts&videoCodec=h264,hevc&audioCodec=aac)'
        '+add-limitation(scope=videoAudioCodec&scopeName=aac&type=upperBound&name=audio.channels&value=2)';
    return '$server/video/:/transcode/universal/start.m3u8'
        '?path=${e('/library/metadata/$ratingKey')}'
        '&mediaIndex=0&partIndex=0&protocol=hls&fastSeek=1'
        '&directPlay=0&directStream=1&directStreamAudio=0&audioChannelCount=2'
        '&maxVideoBitrate=200000&videoQuality=100'
        '&subtitleStreamID=0&subtitles=none&offset=0&session=$session'
        '&X-Plex-Client-Profile-Extra=${e(profile)}'
        '&X-Plex-Client-Profile-Name=Konvergo'
        '&X-Plex-Client-Identifier=${e(Prefs.clientId)}'
        '&X-Plex-Product=Reel&X-Plex-Version=0.1&X-Plex-Platform=Windows'
        '&X-Plex-Device=Windows&X-Plex-Device-Name=Reel'
        '&X-Plex-Token=$serverToken';
  }

  /// Tells the server to stop making a preview stream nobody is watching any more.
  static void stopTranscode(String session) {
    _send('GET', '$server/video/:/transcode/universal/stop?session=$session')
        .catchError((_) => '');
  }

  // ------------------------------------------------------------ urls

  /// A resized server image (posters, backdrops, stills). Full web addresses pass straight through.
  static String? img(String? path, int w, int h) {
    if (path == null || server == null) return null;
    if (path.startsWith('http')) return path;
    // Resizing is the slow part: the server has to decode and shrink every poster, and a NAS is weak
    // at that. On your own network the picture as it is on disk arrives far quicker, so take that and
    // let this PC shrink it (which it does while decoding, for free).
    if (Prefs.serverKind == 'local') return raw(path);
    return '$server/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1'
        '&url=${Uri.encodeQueryComponent(path)}&X-Plex-Token=$serverToken';
  }

  /// The original file (logos keep their transparency; video files play directly).
  static String? raw(String? path) {
    if (path == null || server == null) return null;
    return '$server$path${path.contains('?') ? '&' : '?'}X-Plex-Token=$serverToken';
  }

  static String? directUrl(PlexItem i) => raw(i.partKey);

  // ------------------------------------------------------------ state

  static Future<void> timeline(
      String key, String state, int timeMs, int durMs) async {
    try {
      await _send(
          'GET',
          '$server/:/timeline?ratingKey=$key&key=${Uri.encodeQueryComponent('/library/metadata/$key')}'
              '&identifier=com.plexapp.plugins.library&state=$state&time=$timeMs&duration=$durMs',
          timeout: const Duration(seconds: 8));
    } catch (_) {}
  }

  static Future<void> setWatched(String key, bool watched) async {
    try {
      await _send('GET',
          '$server/:/${watched ? 'scrobble' : 'unscrobble'}?key=$key&identifier=com.plexapp.plugins.library');
    } catch (_) {}
  }
}
