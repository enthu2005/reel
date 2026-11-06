import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';

import '../main.dart' show isFullscreen, navKey, setFullscreen;
import '../plex.dart';
import '../prefs.dart';
import '../seerr.dart';
import '../sound.dart';
import 'speedtest.dart';
import '../ui/net_image.dart';
import 'discover.dart';
import '../ui/preview.dart';
import '../ui/seasonal.dart';
import 'stats.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'login.dart';
import 'profiles.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

/// Settings, laid out like the Fire TV app: categories down the left, a frosted panel on the right with
/// one line per setting (value on the right) and the focused setting's explanation underneath.
class _SettingsScreenState extends State<SettingsScreen> {
  static const _categories = [
    'Playback',
    'Audio',
    'Subtitles',
    'Display',
    'Home and browsing',
    'Account'
  ];
  static const _sizes = ['Small', 'Medium', 'Large', 'Extra large'];
  static const _colours = ['White', 'Yellow', 'Cyan', 'Pink'];
  static const _languages = ['en', 'ta', 'hi'];
  static const _languageNames = ['English', 'Tamil', 'Hindi'];

  String? _seerrNote;
  int _category = 0;
  final _caption = ValueNotifier<String>('');
  final _catNodes = [for (var i = 0; i < _categories.length; i++) FocusNode()];
  final _catScope = FocusScopeNode();
  final _listScope = FocusScopeNode();

  @override
  void dispose() {
    _caption.dispose();
    for (final n in _catNodes) {
      n.dispose();
    }
    _catScope.dispose();
    _listScope.dispose();
    super.dispose();
  }

  void _toList() {
    _listScope.requestFocus();
    if (_listScope.focusedChild == null) _listScope.nextFocus();
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final rows = switch (_category) {
      0 => _playback(h),
      1 => _audio(h),
      2 => _subtitles(h),
      3 => _display(h),
      4 => _home(h),
      _ => _account(h),
    };
    return Scaffold(
      body: Padding(
        padding: EdgeInsets.fromLTRB(w * 0.06, h * 0.09, w * 0.06, h * 0.07),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          // Left: title and categories
          SizedBox(
            width: w * 0.25,
            child: FocusScope(
              node: _catScope,
              onKeyEvent: (n, e) {
                if (e is KeyDownEvent &&
                    e.logicalKey == LogicalKeyboardKey.arrowRight) {
                  _toList();
                  return KeyEventResult.handled;
                }
                if (e.logicalKey == LogicalKeyboardKey.arrowLeft) {
                  return KeyEventResult.handled;
                }
                return KeyEventResult.ignored;
              },
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: EdgeInsets.only(
                          left: w * 0.012, bottom: h * 0.04),
                      child: Text('Settings',
                          style: reelText(h * 0.058, weight: FontWeight.w700)),
                    ),
                    for (var i = 0; i < _categories.length; i++)
                      Padding(
                        padding: EdgeInsets.only(bottom: h * 0.006),
                        child: Pressable(
                          focusNode: _catNodes[i],
                          autofocus: i == 0,
                          scrollAlignment: -1,
                          onFocusChange: (f) {
                            if (f && _category != i) {
                              setState(() => _category = i);
                            }
                          },
                          onPressed: () {
                            setState(() => _category = i);
                            WidgetsBinding.instance
                                .addPostFrameCallback((_) => _toList());
                          },
                          builder: (c, f) => AnimatedContainer(
                            duration: const Duration(milliseconds: 120),
                            width: double.infinity,
                            padding: EdgeInsets.symmetric(
                                horizontal: w * 0.012, vertical: h * 0.018),
                            decoration: BoxDecoration(
                              color: f
                                  ? Colors.white
                                  : i == _category
                                      ? const Color(0x26FFFFFF)
                                      : Colors.transparent,
                              borderRadius: BorderRadius.circular(h * 0.02),
                            ),
                            child: Text(_categories[i],
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: reelText(h * 0.032,
                                    weight: FontWeight.w600,
                                    color: f
                                        ? Colors.black
                                        : i == _category
                                            ? Colors.white
                                            : Reel.text2)),
                          ),
                        ),
                      ),
                  ]),
            ),
          ),
          SizedBox(width: w * 0.025),
          // Right: the frosted panel
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(h * 0.04),
              child: BackdropFilter(
                filter: ui.ImageFilter.blur(sigmaX: 24, sigmaY: 24),
                child: Container(
                  height: double.infinity,
                  padding: EdgeInsets.fromLTRB(
                      w * 0.018, h * 0.035, w * 0.018, h * 0.03),
                  decoration: BoxDecoration(
                    color: const Color(0xD9262629),
                    borderRadius: BorderRadius.circular(h * 0.04),
                    border: Border.all(color: const Color(0x26FFFFFF)),
                  ),
                  child: FocusScope(
                    node: _listScope,
                    onKeyEvent: (n, e) {
                      if (e is KeyDownEvent &&
                          e.logicalKey == LogicalKeyboardKey.arrowLeft) {
                        _catNodes[_category].requestFocus();
                        return KeyEventResult.handled;
                      }
                      if (e.logicalKey == LogicalKeyboardKey.arrowRight) {
                        return KeyEventResult.handled;
                      }
                      return KeyEventResult.ignored;
                    },
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Padding(
                            padding: EdgeInsets.only(
                                left: w * 0.012, bottom: h * 0.015),
                            child: Text(_categories[_category],
                                style: reelText(h * 0.026,
                                    weight: FontWeight.w700,
                                    color: Reel.text2)),
                          ),
                          Expanded(
                            child: ListView(
                                key: ValueKey(_category),
                                padding: EdgeInsets.zero,
                                children: rows),
                          ),
                          Container(
                              height: 1,
                              margin: EdgeInsets.symmetric(vertical: h * 0.012),
                              color: const Color(0x1FFFFFFF)),
                          Padding(
                            padding:
                                EdgeInsets.symmetric(horizontal: w * 0.012),
                            child: SizedBox(
                              height: h * 0.12,
                              child: ValueListenableBuilder<String>(
                                valueListenable: _caption,
                                builder: (c, text, _) => Text(text,
                                    maxLines: 4,
                                    overflow: TextOverflow.ellipsis,
                                    style: reelText(h * 0.0235,
                                        color: Reel.text2, height: 1.2)),
                              ),
                            ),
                          ),
                        ]),
                  ),
                ),
              ),
            ),
          ),
        ]),
      ),
    );
  }

  // ---- Categories ----

  List<Widget> _playback(double h) => [
        _toggle(h, 'Auto-play next episode', Prefs.autoPlayNext,
            'When the end credits start, an Up Next card counts down 10 seconds and plays the next episode.',
            (v) => Prefs.autoPlayNext = v,
            first: true),
        _toggle(h, 'Still watching?', Prefs.stillOn,
            'Autoplay pauses and asks "Still watching?" when several episodes in a row have started on their own with no key pressed, and it is also late at night or has been playing a long time. Turn it off to let autoplay run all night.',
            (v) => Prefs.stillOn = v),
        if (Prefs.stillOn) ...[
          _choice(
              h,
              'Ask after episodes',
              const ['2 in a row', '3 in a row', '4 in a row', '5 in a row', '6 in a row'],
              const [2, 3, 4, 5, 6].indexOf(Prefs.stillEpisodes),
              'How many episodes can start on their own, with no key pressed, before it may ask.',
              (i) => Prefs.stillEpisodes = const [2, 3, 4, 5, 6][i]),
          _toggle(h, 'Late at night', Prefs.stillNight,
              'Between midnight and 6am it asks as soon as the episode count above is reached.',
              (v) => Prefs.stillNight = v,
              onLabel: '12am to 6am'),
          _choice(
              h,
              'Long viewing time',
              const ['Off', 'After 2 hours', 'After 3 hours', 'After 4 hours', 'After 6 hours'],
              const [0, 2, 3, 4, 6].indexOf(Prefs.stillHours),
              'At any hour, it also asks once autoplay has run this long with no key pressed.',
              (i) => Prefs.stillHours = const [0, 2, 3, 4, 6][i]),
        ],
        _toggle(h, 'Title cards', Prefs.titleCards,
            'When a film or episode starts from the beginning, its name appears in big type for a few seconds, like an opening credit.',
            (v) => Prefs.titleCards = v),
        _line(h, 'Speed to your server', 'Test',
            'Measures how fast the server can send to this PC: the link between them (your network at home), not your internet. Says which qualities will play smoothly.',
            () => navKey.currentState?.push(
                MaterialPageRoute(builder: (_) => const SpeedTestScreen())),
            arrow: true),
      ];

  List<Widget> _audio(double h) => [
        _toggle(h, 'Audio passthrough', Prefs.passthrough,
            'Sends the formats ticked below untouched to a receiver or soundbar connected by HDMI. Leave off for TV speakers, headphones or a PC sound card.',
            (v) => Prefs.passthrough = v,
            first: true),
        _line(
            h,
            'Passthrough output',
            Prefs.audioDevice.isEmpty
                ? 'Windows default'
                : (Prefs.audioDeviceName.isEmpty
                    ? Prefs.audioDevice
                    : Prefs.audioDeviceName),
            'The sound output passthrough is sent to. Pick your receiver\'s HDMI output (the one named after your receiver or your graphics card). '
            'In Windows Sound settings that output must also allow Dolby / DTS (Properties, Supported Formats).',
            _pickAudioDevice,
            arrow: true),
        _toggle(h, 'Dolby Digital (AC-3)', Prefs.canAc3,
            'Tick what your speakers or receiver can play. Unsupported sound is swapped for another track in the same language when the file has one, and only converted by the server if it doesn\'t.',
            (v) => Prefs.canAc3 = v,
            onLabel: 'Supported',
            offLabel: 'Not supported'),
        _toggle(h, 'Dolby Digital+ (E-AC-3)', Prefs.canEac3,
            'Also used for Dolby Atmos streaming tracks.',
            (v) => Prefs.canEac3 = v,
            onLabel: 'Supported',
            offLabel: 'Not supported'),
        _toggle(h, 'DTS', Prefs.canDts,
            'The standard DTS track (also the core inside DTS-HD).',
            (v) => Prefs.canDts = v,
            onLabel: 'Supported',
            offLabel: 'Not supported'),
        _toggle(h, 'Dolby TrueHD / Atmos', Prefs.canTruehd,
            'Lossless Dolby. Leave off unless your setup really plays it.',
            (v) => Prefs.canTruehd = v,
            onLabel: 'Supported',
            offLabel: 'Not supported'),
        _toggle(h, 'DTS-HD Master Audio / DTS:X', Prefs.canDtshd,
            'Lossless DTS. Leave off unless your setup really plays it.',
            (v) => Prefs.canDtshd = v,
            onLabel: 'Supported',
            offLabel: 'Not supported'),
        _toggle(h, 'Sound effects', Prefs.sounds,
            'Soft ticks and clicks as you move around, a whoosh between pages and a chime when Reel starts. Never while a film plays.',
            (v) {
          Prefs.sounds = v;
          if (v) Sound.click();
        }),
      ];

  List<Widget> _subtitles(double h) {
    final lang = _languages.indexOf(Prefs.subtitleLanguage);
    return [
      _choice(h, 'Default subtitles', _languageNames, lang < 0 ? 0 : lang,
          'Subtitles start in this language when the video provides a matching track.',
          (i) => Prefs.subtitleLanguage = _languages[i],
          first: true),
      _choice(h, 'Size', _sizes, Prefs.subSize.clamp(0, 3),
          'Text subtitles only. Picture subtitles keep the size they were made at.',
          (i) => Prefs.subSize = i),
      _choice(h, 'Colour', _colours, Prefs.subColor % 4,
          'The colour of subtitle text. Picture subtitles keep their own colours.',
          (i) => Prefs.subColor = i),
      _choice(h, 'Position', const ['Top', 'Bottom'],
          Prefs.subPosition < 50 ? 0 : 1,
          'Whether subtitles appear at the top or bottom in all videos.',
          (i) => Prefs.subPosition = i == 0 ? 20 : 100),
    ];
  }

  List<Widget> _display(double h) => [
        _line(h, 'Full screen', isFullscreen.value ? 'On' : 'Off',
            'Switch now. F11 or Alt+Enter also switch, and Esc on the home screen leaves full screen.',
            () async {
          await setFullscreen(!isFullscreen.value);
          if (mounted) setState(() {});
        }, first: true),
        _toggle(h, 'Start full screen', Prefs.startFullscreen,
            'Open Reel full screen. F11 (or Alt+Enter) switches any time.',
            (v) => Prefs.startFullscreen = v),
        _choice(
            h,
            'Theme',
            [seasonName(0), seasonName(1), seasonName(2)],
            Prefs.theme.clamp(0, 2),
            'A seasonal look for special occasions: snow and red and green for Christmas, rising embers and orange and purple for Halloween. It pauses while a film plays.',
            (i) {
          Prefs.theme = i;
          seasonTheme.value = i;
        }),
      ];

  List<Widget> _home(double h) => [
        _toggle(h, 'Video previews', Prefs.previews,
            'Rest on a card and its trailer or a scene plays inside it, then carries on behind the title page.',
            (v) {
          Prefs.previews = v;
          if (!v) Preview.stop();
        }, first: true),
        _toggle(h, 'Trailers on Live TV', Prefs.liveTrailers,
            'Between one programme and the next on a Live channel, show up to three trailers of what is coming up. Each one can be skipped, or skip them all.',
            (v) => Prefs.liveTrailers = v),
        _line(h, 'Home & TV libraries', _libSummary(Prefs.homeLibraries),
            'Which libraries Home and the TV Shows tab draw from. Hidden libraries stay available elsewhere.',
            () => _pickLibraries('Home & TV libraries', home: true),
            arrow: true),
        _line(h, 'Movies libraries', _libSummary(Prefs.movieLibraries),
            'Which libraries the Movies tab draws from.',
            () => _pickLibraries('Movies libraries', home: false),
            arrow: true),
        _line(
            h,
            'Posters come from',
            Prefs.imgOriginal ? 'The file on the server' : 'The server, resized',
            'Resized is usually quicker: the server keeps its own copy after the first time, and the files are small. '
            'The file as it is skips that work but downloads more.', () {
          setState(() {
            Prefs.imgOriginal = !Prefs.imgOriginal;
            ImageLoader.resetCounts();
          });
        }),
        _line(h, 'Picture loading', '', ImageLoader.summary,
            () => setState(() => ImageLoader.resetCounts())),
      ];

  List<Widget> _account(double h) {
    final servers =
        Prefs.serverList.split('|').where((x) => x.isNotEmpty).toList();
    return [
      _line(h, 'Stats and Wrapped', '',
          'Hours watched, your top genres and actors, when you watch, and a year-end Wrapped slideshow.',
          () => Navigator.of(context)
              .push(MaterialPageRoute(builder: (_) => const StatsScreen())),
          arrow: true, first: true),
      _line(h, 'Seerr', Seerr.configured ? Seerr.base : 'Not set',
          'For the Discover tab: trending and popular titles you can request.${_seerrNote == null ? '' : '\n$_seerrNote'}',
          () async {
        if (await askSeerrAddress(context)) {
          try {
            await Seerr.login();
            _seerrNote = 'Signed in to Seerr.';
          } catch (e) {
            _seerrNote = "Couldn't sign in to Seerr: $e";
          }
        }
        if (mounted) setState(() {});
      }, arrow: true),
      _toggle(h, 'Stay signed in', Prefs.staySignedIn,
          'Keep this Plex profile signed in on this Windows account.', (v) {
        Prefs.staySignedIn = v;
        if (!v) {
          Prefs.token = null;
          Prefs.ownerToken = null;
        }
      }),
      _line(
          h,
          'Server',
          '${Plex.serverName ?? 'Unknown'} · ${_kindName(Prefs.serverKind)}',
          Prefs.serverKind == 'relay'
              ? 'Going through Plex Relay, which is capped at about 2 Mbps, so pictures and previews are slow. '
                  'Turn on Remote Access in your Plex server settings (or use it at home) for full speed. Press Enter to look again.'
              : '${Plex.server ?? ''}\nTried: ${Prefs.serverTest.isEmpty ? 'not yet' : Prefs.serverTest}',
          () async {
        try {
          await Plex.findServer();
        } catch (_) {}
        if (mounted) setState(() {});
      }),
      if (servers.length > 1)
        _line(
            h,
            'Which server',
            Prefs.serverPick.isEmpty ? 'Choose for me' : Prefs.serverPick,
            'Your account can see more than one Plex server: ${servers.join(', ')}. Press Enter to move to the next one.',
            () async {
          final i = Prefs.serverPick.isEmpty
              ? 0
              : servers.indexOf(Prefs.serverPick) + 1;
          Prefs.serverPick = i >= servers.length ? '' : servers[i];
          try {
            await Plex.findServer();
          } catch (_) {}
          if (mounted) setState(() {});
        }),
      _line(h, 'Switch profile', '', 'Choose a different Plex Home profile.',
          () => navKey.currentState
              ?.push(MaterialPageRoute(builder: (_) => const ProfilesScreen())),
          arrow: true),
      _line(h, 'Sign out', '', 'Signs Reel out of your Plex account.', () {
        Plex.signOut();
        navKey.currentState?.pushAndRemoveUntil(
            MaterialPageRoute(builder: (_) => const LoginScreen()), (_) => false);
      }),
    ];
  }

  static String _kindName(String k) {
    if (k == 'local') return 'Local network';
    if (k == 'remote') return 'Remote (direct)';
    if (k == 'relay') return 'Relay (slow)';
    return 'Unknown';
  }

  static String _libSummary(List<String>? v) =>
      v == null ? 'All' : '${v.length} chosen';

  // ---- Row kinds ----

  Widget _toggle(double h, String name, bool on, String help,
          void Function(bool) set,
          {String onLabel = 'On',
          String offLabel = 'Off',
          bool first = false}) =>
      _line(h, name, on ? onLabel : offLabel, help, () {
        set(!on);
        setState(() {});
      }, first: first);

  /// A setting with a few choices. Opens a pick list.
  Widget _choice(double h, String name, List<String> labels, int cur,
          String help, void Function(int) pick,
          {bool first = false}) {
    final at = cur < 0 ? 0 : cur.clamp(0, labels.length - 1);
    return _line(h, name, labels[at], help, () async {
      final i = await _picker(name, labels, at);
      if (i != null) {
        pick(i);
        if (mounted) setState(() {});
      }
    }, arrow: true, first: first);
  }

  /// One line of the panel: the name, the value on the right and an arrow when it opens something.
  Widget _line(double h, String name, String value, String help,
      VoidCallback onTap,
      {bool arrow = false, bool first = false}) {
    return Pressable(
      autofocus: first,
      scrollAlignment: 0.4,
      onFocusChange: (f) {
        if (f) _caption.value = help;
      },
      onPressed: onTap,
      builder: (c, f) => AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        margin: EdgeInsets.only(bottom: h * 0.004),
        padding: EdgeInsets.symmetric(
            horizontal: Reel.w(c) * 0.012, vertical: h * 0.0215),
        decoration: BoxDecoration(
          color: f ? Colors.white : Colors.transparent,
          borderRadius: BorderRadius.circular(h * 0.024),
        ),
        child: Row(children: [
          Expanded(
            child: Text(name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.03,
                    weight: FontWeight.w600,
                    color: f ? Colors.black : Colors.white)),
          ),
          ConstrainedBox(
            constraints: BoxConstraints(maxWidth: Reel.w(c) * 0.3),
            child: Text(value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.028,
                    weight: FontWeight.w500,
                    color: f ? const Color(0xFF4A4A4A) : Reel.text2)),
          ),
          if (arrow)
            Padding(
              padding: EdgeInsets.only(left: Reel.w(c) * 0.006),
              child: Icon(Icons.chevron_right_rounded,
                  size: h * 0.045,
                  color: f ? const Color(0xFF4A4A4A) : Reel.text3),
            ),
        ]),
      ),
    );
  }

  // ---- Pop-ups ----

  /// The frosted card all the pick lists sit in.
  Future<T?> _popup<T>(
      Widget Function(BuildContext dc, double h) body, double widthFraction) {
    return showDialog<T>(
      context: context,
      barrierColor: const Color(0x99000000),
      builder: (dc) {
        final h = Reel.h(dc);
        return Dialog(
          backgroundColor: Colors.transparent,
          elevation: 0,
          insetPadding: EdgeInsets.all(h * 0.04),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(h * 0.04),
            child: BackdropFilter(
              filter: ui.ImageFilter.blur(sigmaX: 24, sigmaY: 24),
              child: Container(
                width: Reel.w(dc) * widthFraction,
                padding: EdgeInsets.fromLTRB(
                    h * 0.02, h * 0.03, h * 0.02, h * 0.025),
                decoration: BoxDecoration(
                  color: const Color(0xF22A2A2D),
                  borderRadius: BorderRadius.circular(h * 0.04),
                  border: Border.all(color: const Color(0x26FFFFFF)),
                ),
                child: body(dc, h),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _popupTitle(double h, String t) => Padding(
        padding: EdgeInsets.only(left: h * 0.018, bottom: h * 0.014),
        child: Text(t,
            style: reelText(h * 0.026,
                weight: FontWeight.w700, color: Reel.text2)),
      );

  /// A line in a pick list: a tick column, the name, a white pill while selected.
  Widget _pickRow(double h, String label, bool ticked, VoidCallback onTap,
      {bool autofocus = false, String trailing = ''}) {
    return Pressable(
      autofocus: autofocus,
      scrollAlignment: 0.5,
      onPressed: onTap,
      builder: (c, f) => AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        margin: EdgeInsets.only(bottom: h * 0.004),
        padding:
            EdgeInsets.symmetric(horizontal: h * 0.018, vertical: h * 0.019),
        decoration: BoxDecoration(
          color: f ? Colors.white : Colors.transparent,
          borderRadius: BorderRadius.circular(h * 0.024),
        ),
        child: Row(children: [
          SizedBox(
            width: h * 0.05,
            child: ticked
                ? Icon(Icons.check_rounded,
                    size: h * 0.036, color: f ? Colors.black : Colors.white)
                : null,
          ),
          Expanded(
            child: Text(label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.03,
                    weight: FontWeight.w600,
                    color: f ? Colors.black : Colors.white)),
          ),
          if (trailing.isNotEmpty)
            Text(trailing,
                style: reelText(h * 0.0235,
                    color: f ? const Color(0xFF555555) : Reel.text2)),
        ]),
      ),
    );
  }

  /// The pick list: a small label, a tick on the current choice, a white pill on the focused one.
  Future<int?> _picker(String title, List<String> labels, int cur) {
    return _popup<int>(
        (dc, h) => Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _popupTitle(h, title),
                  Flexible(
                    child: SingleChildScrollView(
                      child: Column(mainAxisSize: MainAxisSize.min, children: [
                        for (var i = 0; i < labels.length; i++)
                          _pickRow(h, labels[i], i == cur,
                              () => Navigator.of(dc).pop(i),
                              autofocus: i == cur),
                      ]),
                    ),
                  ),
                ]),
        0.3);
  }

  /// Lists the PC's sound outputs and saves the one chosen for passthrough.
  Future<void> _pickAudioDevice() async {
    final p = Player(configuration: const PlayerConfiguration(vo: 'null'));
    List<AudioDevice> devs;
    try {
      devs = await p.stream.audioDevices
          .firstWhere((l) => l.length > 1)
          .timeout(const Duration(seconds: 4));
    } catch (_) {
      devs = p.state.audioDevices;
    }
    await p.dispose();
    if (!mounted) return;
    final list = [
      const AudioDevice('', 'Windows default'),
      ...devs.where((d) => d.name != 'auto')
    ];
    await _popup<void>(
        (dc, h) => Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _popupTitle(h, 'Passthrough output'),
                  Flexible(
                    child: SingleChildScrollView(
                      child: Column(mainAxisSize: MainAxisSize.min, children: [
                        for (final d in list)
                          _pickRow(h, d.description, d.name == Prefs.audioDevice,
                              () {
                            Prefs.audioDevice = d.name;
                            Prefs.audioDeviceName =
                                d.name.isEmpty ? '' : d.description;
                            Navigator.of(dc).pop();
                          }, autofocus: d.name == Prefs.audioDevice),
                      ]),
                    ),
                  ),
                ]),
        0.4);
    if (mounted) setState(() {});
  }

  /// A list of libraries to tick. It stays open, saves on every tick and keeps at least one ticked.
  Future<void> _pickLibraries(String title, {required bool home}) async {
    List<PlexSection> secs;
    try {
      secs = (await Plex.sections())
          .where((s) => s.type == 'movie' || s.type == 'show')
          .toList();
    } catch (_) {
      return;
    }
    if (!mounted || secs.isEmpty) return;
    // what is shown now when nothing has been chosen yet
    final ticked = <String>{
      for (final s in home ? Plex.homeSections(secs) : Plex.movieSections(secs))
        s.key
    };
    await _popup<void>(
        (dc, h) => StatefulBuilder(
            builder: (dc, setD) => Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _popupTitle(h, title),
                      Flexible(
                        child: SingleChildScrollView(
                          child:
                              Column(mainAxisSize: MainAxisSize.min, children: [
                            for (var i = 0; i < secs.length; i++)
                              _pickRow(
                                  h, secs[i].title, ticked.contains(secs[i].key),
                                  () {
                                final k = secs[i].key;
                                if (ticked.contains(k)) {
                                  if (ticked.length == 1) return; // keep at least one
                                  ticked.remove(k);
                                } else {
                                  ticked.add(k);
                                }
                                final saved = ticked.toList();
                                if (home) {
                                  Prefs.homeLibraries = saved;
                                } else {
                                  Prefs.movieLibraries = saved;
                                }
                                setD(() {});
                              },
                                  autofocus: i == 0,
                                  trailing:
                                      secs[i].type == 'show' ? 'TV' : 'Movies'),
                          ]),
                        ),
                      ),
                      Padding(
                        padding: EdgeInsets.only(left: h * 0.018, top: h * 0.01),
                        child: Text('Esc to close',
                            style: reelText(h * 0.02, color: Reel.text3)),
                      ),
                    ])),
        0.34);
    if (mounted) setState(() {});
  }
}
