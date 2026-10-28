import 'package:flutter/material.dart';

import '../main.dart' show isFullscreen, navKey, setFullscreen;
import '../plex.dart';
import '../prefs.dart';
import '../seerr.dart';
import '../ui/net_image.dart';
import 'discover.dart';
import '../ui/preview.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'login.dart';
import 'profiles.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  String? _seerrNote;

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    const sizes = ['Small', 'Medium', 'Large', 'Extra large'];
    return Scaffold(
      body: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(w * 0.12, h * 0.08, w * 0.12, h * 0.08),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Settings', style: reelText(h * 0.055, weight: FontWeight.w700)),
          Text('Playback, sound and account',
              style: reelText(h * 0.026, color: Reel.text2)),
          SizedBox(height: h * 0.035),
          _row(
              h,
              'Audio passthrough',
              Prefs.passthrough ? 'On' : 'Off',
              'Sends the formats ticked below untouched to a receiver or soundbar connected by HDMI. '
                  'Leave off for TV speakers, headphones or a PC sound card.',
              () {
            setState(() => Prefs.passthrough = !Prefs.passthrough);
          }, autofocus: true),
          _row(
              h,
              'Dolby Digital (AC-3)',
              Prefs.canAc3 ? 'Supported' : 'Not supported',
              'Tick what your speakers or receiver can play. Unsupported sound is swapped for another track in the same language when the file has one, and only converted by the server if it doesn\'t.',
              () => setState(() => Prefs.canAc3 = !Prefs.canAc3)),
          _row(
              h,
              'Dolby Digital+ (E-AC-3)',
              Prefs.canEac3 ? 'Supported' : 'Not supported',
              'Also used for Dolby Atmos streaming tracks.',
              () => setState(() => Prefs.canEac3 = !Prefs.canEac3)),
          _row(
              h,
              'DTS',
              Prefs.canDts ? 'Supported' : 'Not supported',
              'The standard DTS track (also the core inside DTS-HD).',
              () => setState(() => Prefs.canDts = !Prefs.canDts)),
          _row(
              h,
              'Dolby TrueHD / Atmos',
              Prefs.canTruehd ? 'Supported' : 'Not supported',
              'Lossless Dolby. Leave off unless your setup really plays it.',
              () => setState(() => Prefs.canTruehd = !Prefs.canTruehd)),
          _row(
              h,
              'DTS-HD Master Audio / DTS:X',
              Prefs.canDtshd ? 'Supported' : 'Not supported',
              'Lossless DTS. Leave off unless your setup really plays it.',
              () => setState(() => Prefs.canDtshd = !Prefs.canDtshd)),
          _row(h, 'Subtitle size', sizes[Prefs.subSize.clamp(0, 3)],
              'Text subtitles. Picture subtitles keep their own size.', () {
            setState(() => Prefs.subSize = (Prefs.subSize + 1) % 4);
          }),
          _row(
              h,
              'Subtitle colour',
              const ['White', 'Yellow', 'Cyan', 'Pink'][Prefs.subColor % 4],
              'Choose the subtitle colour used across all videos.', () {
            setState(() => Prefs.subColor = (Prefs.subColor + 1) % 4);
          }),
          _row(
              h,
              'Subtitle position',
              Prefs.subPosition < 50 ? 'Top' : 'Bottom',
              'Choose whether subtitles appear at the top or bottom in all videos.',
              () {
            setState(
                () => Prefs.subPosition = Prefs.subPosition < 50 ? 100 : 20);
          }),
          _row(
              h,
              'Subtitle language',
              Prefs.subtitleLanguage == 'en'
                  ? 'English'
                  : Prefs.subtitleLanguage.toUpperCase(),
              'Subtitles start in this language when the video provides a matching track. English is the default. Press Enter to cycle English, Tamil and Hindi.',
              () {
            const languages = ['en', 'ta', 'hi'];
            final i = languages.indexOf(Prefs.subtitleLanguage);
            setState(() =>
                Prefs.subtitleLanguage = languages[(i + 1) % languages.length]);
          }),
          _row(
              h,
              'Home & TV libraries',
              _libSummary(Prefs.homeLibraries),
              'Which libraries Home and the TV Shows tab draw from. Press Enter to choose.',
              () => _pickLibraries('Home & TV libraries', home: true)),
          _row(
              h,
              'Movies libraries',
              _libSummary(Prefs.movieLibraries),
              'Which libraries the Movies tab draws from. Press Enter to choose.',
              () => _pickLibraries('Movies libraries', home: false)),
          _row(h, 'Video previews', Prefs.previews ? 'On' : 'Off',
              'Rest on a card and its trailer or a scene plays inside it, then carries on behind the title page.',
              () {
            setState(() => Prefs.previews = !Prefs.previews);
            if (!Prefs.previews) Preview.stop();
          }),
          _row(h, 'Auto-play next episode', Prefs.autoPlayNext ? 'On' : 'Off',
              'When the end credits start, an Up Next card counts down 10 seconds and plays the next episode.',
              () {
            setState(() => Prefs.autoPlayNext = !Prefs.autoPlayNext);
          }),
          _row(h, 'Stay signed in', Prefs.staySignedIn ? 'On' : 'Off',
              'Keep this Plex profile signed in on this Windows account.', () {
            setState(() => Prefs.staySignedIn = !Prefs.staySignedIn);
            if (!Prefs.staySignedIn) {
              Prefs.token = null;
              Prefs.ownerToken = null;
            }
          }),
          _row(h, 'Full screen', isFullscreen.value ? 'On' : 'Off',
              'Switch now. F11 or Alt+Enter also switch, and Esc on the home screen leaves full screen.',
              () async {
            await setFullscreen(!isFullscreen.value);
            if (mounted) setState(() {});
          }),
          _row(h, 'Start full screen', Prefs.startFullscreen ? 'On' : 'Off',
              'Open Reel full screen. F11 (or Alt+Enter) switches any time.',
              () {
            setState(() => Prefs.startFullscreen = !Prefs.startFullscreen);
          }),
          _row(
              h,
              'Server',
              '${Plex.serverName ?? 'Unknown'} · ${_kindName(Prefs.serverKind)}',
              Prefs.serverKind == 'relay'
                  ? 'Going through Plex Relay, which is capped at about 2 Mbps, so pictures and previews are slow. '
                      'Turn on Remote Access in your Plex server settings (or use it at home) for full speed. Press Enter to look again.'
                  : '${Plex.server ?? ''}\nTried: ${Prefs.serverTest.isEmpty ? 'not yet' : Prefs.serverTest}\nPress Enter to look again.',
              () async {
            try {
              await Plex.findServer();
            } catch (_) {}
            if (mounted) setState(() {});
          }),
          _row(h, 'Seerr', Seerr.configured ? Seerr.base : 'Not set',
              'For the Discover tab: trending and popular titles you can request. Press Enter to change the address.',
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
          }),
          if (_seerrNote != null)
            Padding(
              padding: EdgeInsets.only(left: h * 0.035, bottom: h * 0.014),
              child: Text(_seerrNote!,
                  style: reelText(h * 0.02, color: Reel.text2)),
            ),
          if (Prefs.serverList.split('|').where((x) => x.isNotEmpty).length > 1)
            _row(
                h,
                'Which server',
                Prefs.serverPick.isEmpty ? 'Choose for me' : Prefs.serverPick,
                'Your account can see more than one Plex server: ${Prefs.serverList.replaceAll('|', ', ')}. '
                    'Press Enter to move to the next one.', () async {
              final all = Prefs.serverList
                  .split('|')
                  .where((x) => x.isNotEmpty)
                  .toList();
              final i = Prefs.serverPick.isEmpty
                  ? 0
                  : all.indexOf(Prefs.serverPick) + 1;
              Prefs.serverPick = i >= all.length ? '' : all[i];
              try {
                await Plex.findServer();
              } catch (_) {}
              if (mounted) setState(() {});
            }),
          _row(
              h,
              'Posters come from',
              Prefs.imgOriginal
                  ? 'The file on the server'
                  : 'The server, resized',
              'Resized is usually quicker: the server keeps its own copy after the first time, and the files are small. '
                  'The file as it is skips that work but downloads more. Press Enter to swap and watch the line below.',
              () {
            setState(() {
              Prefs.imgOriginal = !Prefs.imgOriginal;
              ImageLoader.resetCounts();
            });
          }),
          _row(h, 'Picture loading', '', ImageLoader.summary,
              () => setState(() => ImageLoader.resetCounts())),
          _row(h, 'Sign out', '', 'Signs Reel out of your Plex account.', () {
            Plex.signOut();
            navKey.currentState?.pushAndRemoveUntil(
                MaterialPageRoute(builder: (_) => const LoginScreen()),
                (_) => false);
          }),
          _row(h, 'Switch profile', '', 'Choose a different Plex Home profile.',
              () {
            navKey.currentState?.push(
                MaterialPageRoute(builder: (_) => const ProfilesScreen()));
          }),
        ]),
      ),
    );
  }

  static String _kindName(String k) {
    if (k == 'local') return 'Local network';
    if (k == 'remote') return 'Remote (direct)';
    if (k == 'relay') return 'Relay (slow)';
    return 'Unknown';
  }

  static String _libSummary(List<String>? v) =>
      v == null ? 'All' : '${v.length} chosen';

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
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (dc) => StatefulBuilder(builder: (dc, setD) {
        final h = Reel.h(dc);
        return Dialog(
          backgroundColor: const Color(0xFF1C1C1E),
          child: Padding(
            padding: EdgeInsets.all(h * 0.03),
            child: SizedBox(
              width: h * 0.7,
              child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: reelText(h * 0.034, weight: FontWeight.w700)),
                    SizedBox(height: h * 0.02),
                    for (var i = 0; i < secs.length; i++)
                      Padding(
                        padding: EdgeInsets.only(bottom: h * 0.008),
                        child: Pressable(
                          autofocus: i == 0,
                          scrollAlignment: 0.5,
                          onPressed: () {
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
                            if (mounted) setState(() {});
                          },
                          builder: (c, f) => AnimatedContainer(
                            duration: const Duration(milliseconds: 120),
                            padding: EdgeInsets.symmetric(
                                horizontal: h * 0.02, vertical: h * 0.014),
                            decoration: BoxDecoration(
                              color: f ? Colors.white : const Color(0x14FFFFFF),
                              borderRadius: BorderRadius.circular(h * 0.014),
                            ),
                            child: Row(children: [
                              Icon(
                                  ticked.contains(secs[i].key)
                                      ? Icons.check_box_rounded
                                      : Icons.check_box_outline_blank_rounded,
                                  color: f ? Colors.black : Colors.white,
                                  size: h * 0.034),
                              SizedBox(width: h * 0.016),
                              Expanded(
                                child: Text(secs[i].title,
                                    style: reelText(h * 0.026,
                                        weight: FontWeight.w600,
                                        color: f ? Colors.black : Colors.white)),
                              ),
                              Text(secs[i].type == 'show' ? 'TV' : 'Movies',
                                  style: reelText(h * 0.021,
                                      color: f
                                          ? const Color(0xFF555555)
                                          : Reel.text2)),
                            ]),
                          ),
                        ),
                      ),
                    SizedBox(height: h * 0.012),
                    Text('Esc to close',
                        style: reelText(h * 0.02, color: Reel.text3)),
                  ]),
            ),
          ),
        );
      }),
    );
  }

  Widget _row(
      double h, String name, String value, String help, VoidCallback onTap,
      {bool autofocus = false}) {
    return Padding(
      padding: EdgeInsets.only(bottom: h * 0.014),
      child: Pressable(
        autofocus: autofocus,
        onPressed: onTap,
        scrollAlignment: 0.4,
        builder: (c, f) => AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding:
              EdgeInsets.symmetric(horizontal: h * 0.035, vertical: h * 0.022),
          decoration: BoxDecoration(
            color: f ? Colors.white : Reel.card,
            borderRadius: BorderRadius.circular(h * 0.018),
          ),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(
                child: Text(name,
                    style: reelText(h * 0.028,
                        weight: FontWeight.w600,
                        color: f ? Colors.black : Colors.white)),
              ),
              Text(value,
                  style: reelText(h * 0.026,
                      weight: FontWeight.w500,
                      color: f ? const Color(0xFF333333) : Reel.text2)),
            ]),
            if (help.isNotEmpty) ...[
              SizedBox(height: h * 0.006),
              Text(help,
                  style: reelText(h * 0.021,
                      color: f ? const Color(0xFF555555) : Reel.text3)),
            ],
          ]),
        ),
      ),
    );
  }
}
