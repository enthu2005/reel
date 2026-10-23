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
          Text('Playback, sound and account', style: reelText(h * 0.026, color: Reel.text2)),
          SizedBox(height: h * 0.035),
          _row(h, 'Audio passthrough', Prefs.passthrough ? 'On' : 'Off',
              'Sends the formats ticked below untouched to a receiver or soundbar connected by HDMI. '
                  'Leave off for TV speakers, headphones or a PC sound card.', () {
            setState(() => Prefs.passthrough = !Prefs.passthrough);
          }, autofocus: true),
          _row(h, 'Dolby Digital (AC-3)', Prefs.canAc3 ? 'Supported' : 'Not supported', 'Tick what your speakers or receiver can play. Unsupported sound is swapped for another track in the same language when the file has one, and only converted by the server if it doesn\'t.',
              () => setState(() => Prefs.canAc3 = !Prefs.canAc3)),
          _row(h, 'Dolby Digital+ (E-AC-3)', Prefs.canEac3 ? 'Supported' : 'Not supported', 'Also used for Dolby Atmos streaming tracks.',
              () => setState(() => Prefs.canEac3 = !Prefs.canEac3)),
          _row(h, 'DTS', Prefs.canDts ? 'Supported' : 'Not supported', 'The standard DTS track (also the core inside DTS-HD).',
              () => setState(() => Prefs.canDts = !Prefs.canDts)),
          _row(h, 'Dolby TrueHD / Atmos', Prefs.canTruehd ? 'Supported' : 'Not supported', 'Lossless Dolby. Leave off unless your setup really plays it.',
              () => setState(() => Prefs.canTruehd = !Prefs.canTruehd)),
          _row(h, 'DTS-HD Master Audio / DTS:X', Prefs.canDtshd ? 'Supported' : 'Not supported', 'Lossless DTS. Leave off unless your setup really plays it.',
              () => setState(() => Prefs.canDtshd = !Prefs.canDtshd)),
          _row(h, 'Subtitle size', sizes[Prefs.subSize.clamp(0, 3)], 'Text subtitles. Picture subtitles keep their own size.', () {
            setState(() => Prefs.subSize = (Prefs.subSize + 1) % 4);
          }),
          _row(h, 'Video previews', Prefs.previews ? 'On' : 'Off',
              'Rest on a card and its trailer or a scene plays inside it, then carries on behind the title page.', () {
            setState(() => Prefs.previews = !Prefs.previews);
            if (!Prefs.previews) Preview.stop();
          }),
          _row(h, 'Auto-play next episode', Prefs.autoPlayNext ? 'On' : 'Off',
              'When the end credits start, an Up Next card counts down 10 seconds and plays the next episode.', () {
            setState(() => Prefs.autoPlayNext = !Prefs.autoPlayNext);
          }),
          _row(h, 'Full screen', isFullscreen.value ? 'On' : 'Off',
              'Switch now. F11 or Alt+Enter also switch, and Esc on the home screen leaves full screen.', () async {
            await setFullscreen(!isFullscreen.value);
            if (mounted) setState(() {});
          }),
          _row(h, 'Start full screen', Prefs.startFullscreen ? 'On' : 'Off',
              'Open Reel full screen. F11 (or Alt+Enter) switches any time.', () {
            setState(() => Prefs.startFullscreen = !Prefs.startFullscreen);
          }),
          _row(
              h,
              'Server',
              '${Plex.serverName ?? 'Unknown'} · ${_kindName(Prefs.serverKind)}',
              Prefs.serverKind == 'relay'
                  ? 'Going through Plex Relay, which is capped at about 2 Mbps, so pictures and previews are slow. '
                      'Turn on Remote Access in your Plex server settings (or use it at home) for full speed. Press Enter to look again.'
                  : '${Plex.server ?? ''}\nTried: ${Prefs.serverTest.isEmpty ? 'not yet' : Prefs.serverTest}\nPress Enter to look again.', () async {
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
              child: Text(_seerrNote!, style: reelText(h * 0.02, color: Reel.text2)),
            ),
          if (Prefs.serverList.split('|').where((x) => x.isNotEmpty).length > 1)
            _row(h, 'Which server', Prefs.serverPick.isEmpty ? 'Choose for me' : Prefs.serverPick,
                'Your account can see more than one Plex server: ${Prefs.serverList.replaceAll('|', ', ')}. '
                    'Press Enter to move to the next one.', () async {
              final all = Prefs.serverList.split('|').where((x) => x.isNotEmpty).toList();
              final i = Prefs.serverPick.isEmpty ? 0 : all.indexOf(Prefs.serverPick) + 1;
              Prefs.serverPick = i >= all.length ? '' : all[i];
              try {
                await Plex.findServer();
              } catch (_) {}
              if (mounted) setState(() {});
            }),
          _row(h, 'Posters come from', Prefs.imgOriginal ? 'The file on the server' : 'The server, resized',
              'Resized is usually quicker: the server keeps its own copy after the first time, and the files are small. '
                  'The file as it is skips that work but downloads more. Press Enter to swap and watch the line below.', () {
            setState(() {
              Prefs.imgOriginal = !Prefs.imgOriginal;
              ImageLoader.resetCounts();
            });
          }),
          _row(h, 'Picture loading', '', ImageLoader.summary, () => setState(() => ImageLoader.resetCounts())),
          _row(h, 'Sign out', '', 'Signs Reel out of your Plex account.', () {
            Plex.signOut();
            navKey.currentState?.pushAndRemoveUntil(
                MaterialPageRoute(builder: (_) => const LoginScreen()), (_) => false);
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

  Widget _row(double h, String name, String value, String help, VoidCallback onTap, {bool autofocus = false}) {
    return Padding(
      padding: EdgeInsets.only(bottom: h * 0.014),
      child: Pressable(
        autofocus: autofocus,
        onPressed: onTap,
        scrollAlignment: 0.4,
        builder: (c, f) => AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: EdgeInsets.symmetric(horizontal: h * 0.035, vertical: h * 0.022),
          decoration: BoxDecoration(
            color: f ? Colors.white : Reel.card,
            borderRadius: BorderRadius.circular(h * 0.018),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(
                child: Text(name,
                    style: reelText(h * 0.028, weight: FontWeight.w600, color: f ? Colors.black : Colors.white)),
              ),
              Text(value,
                  style: reelText(h * 0.026, weight: FontWeight.w500, color: f ? const Color(0xFF333333) : Reel.text2)),
            ]),
            if (help.isNotEmpty) ...[
              SizedBox(height: h * 0.006),
              Text(help, style: reelText(h * 0.021, color: f ? const Color(0xFF555555) : Reel.text3)),
            ],
          ]),
        ),
      ),
    );
  }
}
