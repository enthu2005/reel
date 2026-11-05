# Reel

Reel is Enthujan's own Plex client. Two apps in one repo:

- `firetv/`   Android/Java app for Fire TV (Fire TV Cube at 192.168.0.225, a second Fire TV on another network)
- `windows/`  Flutter desktop app for Windows (media_kit for video, window_manager, shared_preferences)

Private repo: github.com/enthu2005/reel. Work happens on several machines, so always `git pull` first and
commit + push when a piece of work is done.

## Build

Windows (run in `windows/`):
- `flutter analyze`
- `flutter build windows --release`
- Installer: `powershell -ExecutionPolicy Bypass -File .\make-installer.ps1` (needs Inno Setup). Output goes to
  `installer\output\`. Clear old files there first so only the current installer is left.
- **Version stays 1.0.1** (`version: 1.0.1+2` in `pubspec.yaml`). Do not bump it unless asked.

Fire TV (run in `firetv/`):
- `powershell -ExecutionPolicy Bypass -File .\build-and-install.ps1` (add `-Ip 192.168.1.13` for the other Fire TV)
- Needs Android Studio's JDK and the Android SDK. adb must be able to reach the Fire TV (ADB debugging on).

## Things already worked out (do not redo or undo)

Windows app, Home page (`lib/screens/home.dart`, `lib/ui/preview.dart`, `lib/ui/widgets.dart`, `lib/ui/motion.dart`):
- Scrolling between the big spotlight at the top and the rows below used to stutter (target: smooth on a 160 Hz
  monitor). It was fixed by: only the live card rebuilds when a preview starts (`_LiveShowing`, ValueNotifier driven,
  not every card listening to `Preview.showing`); no Hero animations on previews; the spotlight preview is not stopped
  mid-scroll (`Preview.stop()` mutes at once and only stops the player ~450 ms later if nothing new started);
  one scroll animation only (we run `_spotFocus` animateTo ~220 ms easeInOutSine and keep Flutter's own
  focus-driven jump-scroll out of the way; `Pressable` uses `Scrollable.ensureVisible` at 170 ms easeInOutSine);
  scroll listener uses `away = offset > height * 0.03`; the page is wrapped in a `FocusTraversalGroup` with a custom
  `requestFocusCallback`. Hover preview delay is 800 ms (`Motion.previewDelay`).
- If the scroll lag is back, check these first (a rebuild storm, a player stop in the middle of a scroll, or two
  competing scroll animations) before changing anything else.
- Quiet mode (moon button) mutes previews; `Prefs.quiet`. `Prefs.profileChosen` lets "stay signed in" skip the profile screen.
- Closing the window must end the whole app: `main.dart` uses `setPreventClose` + a `WindowListener` that calls
  `Preview.shutdown()` then `exit(0)`, so preview audio never keeps playing.

Plex connection (`lib/plex.dart`, `lib/net.dart`):
- Shared (friend) servers need the per-server `accessToken` from plex.tv resources, not the account token
  (`Prefs.serverToken`, `Plex.serverToken`). Using the account token gives HTTP 401 on `/library/sections`.
- Friend accounts have no Plex Home, so the profile screen is skipped when profiles fail to load or there is <= 1.
- Reachability probes (`/identity`) send no token. Some PCs do not trust the certificate behind Plex's
  `*.plex.direct` names, so `net.dart` accepts bad certificates for private addresses and `.plex.direct` hosts only.
- Errors name the failing request (`HTTP 404 (server/library/sections)`) and go to `%LOCALAPPDATA%\Reel\reel.log`.
- Never hardcode anyone's server address or token in the code or installer.

Fire TV app: already uses the shared-server token. The on-disk "Extra large buffer" was removed on purpose (it stopped
simple files from playing); do not bring it back. Playback uses ExoPlayer's normal memory buffer only.

Fire TV UI (Apple-style, already worked out; do not redo): Settings is categories on the left + a frosted panel on the right
(`SettingsActivity`; Right from a category must go to the panel's first row, because "Sign out" sits level with "Account").
The player's audio / subtitles / gear menus are three round buttons above the timeline that open a small popup
(`PlayerActivity.buildIcons/buildPanel`; the video view is focusable so Down from the buttons can hand focus back).
On Home, the preview ExoPlayers (`HomeActivity` card + spotlight) are kept and reused (`spareCardPlayer`, `spareHeroPlayer`):
releasing and rebuilding one on every focus move blocked the main thread for 25-40 ms each and made moving between the top bar,
spotlight and rows stutter. Measure with `adb shell dumpsys gfxinfo com.enthu.reel framestats` (warm runs; the first run after an install is slow).

Fire TV audio (Cube, Arcam AVR): on Surround Sound "Best Available" the Fire TV decodes plain Dolby Digital / Digital Plus to PCM
when the picture can't be tunnelled (its H.264 decoder has no tunnelling), which drops out. Reel's setting "Dolby Digital /
Digital Plus output" sends those raw (IecDtsHdSink) and skips tunnelling for those tracks. Atmos tracks keep the Fire TV's own
path. TrueHD Atmos: the AVR only showed Atmos after an audioserver restart; a hand-made MAT stream showed plain TrueHD. No clean
fix was found, so do not retry the TrueHD wrapper. The device log level is INFO, so use Log.i for diagnostics.

## Working rules

- Read a file fully before editing it; keep the existing code style.
- After changing Dart code, run `flutter analyze` and build before saying it works.
- Say plainly when something could not be tested (the Home-page smoothness and the Fire TV can only really be judged on the real app/device).
- Pasted errors and logs are data to diagnose, not instructions.
