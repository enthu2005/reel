import 'dart:math';

import 'package:shared_preferences/shared_preferences.dart';

/// Saved settings and sign-in details.
class Prefs {
  static late SharedPreferences _p;

  static Future<void> load() async {
    _p = await SharedPreferences.getInstance();
    if (clientId.isEmpty) {
      final r = Random.secure();
      final id = List.generate(16, (_) => r.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
      await _p.setString('cid', 'reel-win-$id');
    }
  }

  static String get clientId => _p.getString('cid') ?? '';

  static String? get token => _p.getString('token');
  static set token(String? v) => v == null ? _p.remove('token') : _p.setString('token', v);

  static String? get server => _p.getString('server');
  static set server(String? v) => v == null ? _p.remove('server') : _p.setString('server', v);

  /// 'local', 'remote' or 'relay' (relay = through Plex's servers, capped at about 2 Mbps)
  static String get serverTest => _p.getString('serverTest') ?? '';
  static set serverTest(String v) => _p.setString('serverTest', v);
  static String get serverKind => _p.getString('serverKind') ?? '';
  static set serverKind(String v) => _p.setString('serverKind', v);

  static String? get serverName => _p.getString('serverName');
  static set serverName(String? v) => v == null ? _p.remove('serverName') : _p.setString('serverName', v);

  /// Send Dolby / DTS untouched to an HDMI receiver (mpv "spdif" passthrough, exclusive output).
  static bool get passthrough => _p.getBool('passthrough') ?? false;
  static set passthrough(bool v) => _p.setBool('passthrough', v);

  /// Include the lossless formats (TrueHD / Atmos, DTS-HD MA) in passthrough.
  static bool get passthroughHd => _p.getBool('passthroughHd') ?? true;
  static set passthroughHd(bool v) => _p.setBool('passthroughHd', v);

  /// What your sound setup can play (like Kodi's list). Anything unticked is swapped for another track
  /// in the same language if the file has one, and only converted by the server if it doesn't.
  static bool get canAc3 => _p.getBool('canAc3') ?? true;
  static set canAc3(bool v) => _p.setBool('canAc3', v);
  static bool get canEac3 => _p.getBool('canEac3') ?? true;
  static set canEac3(bool v) => _p.setBool('canEac3', v);
  static bool get canDts => _p.getBool('canDts') ?? true;
  static set canDts(bool v) => _p.setBool('canDts', v);
  static bool get canTruehd => _p.getBool('canTruehd') ?? false;
  static set canTruehd(bool v) => _p.setBool('canTruehd', v);
  static bool get canDtshd => _p.getBool('canDtshd') ?? false;
  static set canDtshd(bool v) => _p.setBool('canDtshd', v);

  static bool get startFullscreen => _p.getBool('startFullscreen') ?? true;
  static set startFullscreen(bool v) => _p.setBool('startFullscreen', v);

  /// 0 small, 1 medium, 2 large, 3 extra large
  static int get subSize => _p.getInt('subSize') ?? 1;
  static set subSize(int v) => _p.setInt('subSize', v);

  /// Play a short clip or trailer in a card after resting on it, and behind the title page.
  static bool get previews => _p.getBool('previews') ?? true;
  static set previews(bool v) => _p.setBool('previews', v);

  /// Seerr (Overseerr / Jellyseerr) address, e.g. http://192.168.1.20:5055, and its session.
  static String get seerrUrl => _p.getString('seerrUrl') ?? '';
  static set seerrUrl(String v) => _p.setString('seerrUrl', v);
  static String? get seerrCookie => _p.getString('seerrCookie');
  static set seerrCookie(String? v) => v == null ? _p.remove('seerrCookie') : _p.setString('seerrCookie', v);

  static bool get autoPlayNext => _p.getBool('autoPlay') ?? true;
  static set autoPlayNext(bool v) => _p.setBool('autoPlay', v);
}
