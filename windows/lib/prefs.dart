import 'dart:math';

import 'package:shared_preferences/shared_preferences.dart';

/// Saved settings and sign-in details.
class Prefs {
  static late SharedPreferences _p;

  static Future<void> load() async {
    _p = await SharedPreferences.getInstance();
    if (clientId.isEmpty) {
      final r = Random.secure();
      final id = List.generate(
          16, (_) => r.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
      await _p.setString('cid', 'reel-win-$id');
    }
  }

  static String get clientId => _p.getString('cid') ?? '';

  static String? get token => _p.getString('token');
  static set token(String? v) =>
      v == null ? _p.remove('token') : _p.setString('token', v);
  /// The access token Plex gives for a server that was shared with you (empty for your own server).
  static String get serverToken => _p.getString('serverToken') ?? '';
  static set serverToken(String v) =>
      v.isEmpty ? _p.remove('serverToken') : _p.setString('serverToken', v);
  static String? get ownerToken => _p.getString('ownerToken');
  static set ownerToken(String? v) =>
      v == null ? _p.remove('ownerToken') : _p.setString('ownerToken', v);
  static bool get staySignedIn => _p.getBool('staySignedIn') ?? true;
  static set staySignedIn(bool v) => _p.setBool('staySignedIn', v);

  static String? get server => _p.getString('server');
  static set server(String? v) =>
      v == null ? _p.remove('server') : _p.setString('server', v);

  /// 'local', 'remote' or 'relay' (relay = through Plex's servers, capped at about 2 Mbps)
  static String get serverTest => _p.getString('serverTest') ?? '';
  static set serverTest(String v) => _p.setString('serverTest', v);
  static String get serverKind => _p.getString('serverKind') ?? '';
  static set serverKind(String v) => _p.setString('serverKind', v);

  static String? get serverName => _p.getString('serverName');
  static set serverName(String? v) =>
      v == null ? _p.remove('serverName') : _p.setString('serverName', v);

  /// Send Dolby / DTS untouched to an HDMI receiver (mpv "spdif" passthrough, exclusive output).
  /// Quiet mode: previews are muted and trailers don't start by themselves (the moon on Home).
  /// A profile was picked and "stay signed in" is on: start straight on Home next time.
  static bool get profileChosen => _p.getBool('profileChosen') ?? false;
  static set profileChosen(bool v) => _p.setBool('profileChosen', v);

  static bool get quiet => _p.getBool('quiet') ?? false;
  static set quiet(bool v) => _p.setBool('quiet', v);

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

  static String get subtitleLanguage =>
      _p.getString('subtitleLanguage') ?? 'en';
  static set subtitleLanguage(String v) => _p.setString('subtitleLanguage', v);
  static int get subColor => _p.getInt('subColor') ?? 0;
  static set subColor(int v) => _p.setInt('subColor', v);
  static int get subPosition => _p.getInt('subPosition') ?? 100;
  static set subPosition(int v) => _p.setInt('subPosition', v);

  /// Play a short clip or trailer in a card after resting on it, and behind the title page.
  static bool get previews => _p.getBool('previews') ?? true;
  static set previews(bool v) => _p.setBool('previews', v);

  /// Seerr (Overseerr / Jellyseerr) address, e.g. http://192.168.1.20:5055, and its session.
  static String get seerrUrl => _p.getString('seerrUrl') ?? '';
  static set seerrUrl(String v) => _p.setString('seerrUrl', v);
  static String? get seerrCookie => _p.getString('seerrCookie');
  static set seerrCookie(String? v) =>
      v == null ? _p.remove('seerrCookie') : _p.setString('seerrCookie', v);

  /// How the picture fills the screen: 0 fit (black bars), 1 fill (crop black bars and edges), 2 stretch
  static int get fitMode => _p.getInt('fitMode') ?? 0;
  static set fitMode(int v) => _p.setInt('fitMode', v);

  /// Where posters come from on your own network: true = the picture file as it is on the server
  /// (no work for the server, bigger download), false = the server shrinks it first (its own cache
  /// makes this quick after the first time).
  static bool get imgOriginal => _p.getBool('imgOriginal') ?? true;
  static set imgOriginal(bool v) => _p.setBool('imgOriginal', v);

  /// The server you picked by hand in Settings (its name), or empty to choose for you.
  static String get serverPick => _p.getString('serverPick') ?? '';
  static set serverPick(String v) => _p.setString('serverPick', v);

  /// The servers found last time we looked, separated by |, so Settings can offer them.
  static String get serverList => _p.getString('serverList') ?? '';
  static set serverList(String v) => _p.setString('serverList', v);

  static bool get autoPlayNext => _p.getBool('autoPlay') ?? true;
  static set autoPlayNext(bool v) => _p.setBool('autoPlay', v);
}
