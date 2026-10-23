# Reel for Windows

The Windows version of Reel: your Plex library with an Apple TV style look, direct play
of the original files (no conversion), and Dolby / DTS passthrough to an HDMI receiver.

## First-time setup

1. **Install Flutter** (stable): https://docs.flutter.dev/get-started/install/windows/desktop
   Unzip it somewhere like `C:\dev\flutter` and add `C:\dev\flutter\bin` to your PATH.
2. **Install Visual Studio 2022** (Community is free) with the **Desktop development with C++**
   workload. Flutter needs it to build Windows apps.
3. Check everything with `flutter doctor` (the Windows lines should be green).

## Build and run

In this folder, in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1           # run it
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1 release   # build the finished app
```

The first run downloads the packages (including mpv for video), so it takes a few minutes.
The finished app ends up in `build\windows\x64\runner\Release` (copy that whole folder anywhere;
`reel.exe` is the app).

## Using it

| Key | What it does |
|---|---|
| Arrow keys | Move around (a game controller's D-pad works too) |
| Enter / Space | Select, play / pause |
| Hold Enter | Long press |
| Esc | Back |
| F11 or Alt+Enter | Full screen on / off |
| In the player: Left / Right | Skip 10 s (hold for 30 s, then 60 s) |
| In the player: Up, A or S | Audio & subtitles |
| In the player: Enter | Skip intro / credits when the button shows |
| In the player: M | Mute |

The mouse works everywhere too: hover to highlight, click to select, double-click the video for full screen.

## Sound

Settings > **Audio passthrough** sends Dolby Digital, Dolby Digital+ and DTS untouched to a receiver
connected by HDMI (with **Lossless audio** also TrueHD / Atmos and DTS-HD MA). Leave it off for TV
speakers, headphones or a normal PC sound card, and the PC decodes the sound itself.

## What's in this first version

Sign-in with plex.tv/link, Home (Up Next and Recently Added), Movies and TV Shows tabs, Search, title
pages with seasons, episodes and cast, the trailer button, and the player (direct play, audio and
subtitle choice including picture subtitles, skip intro / credits, Up Next with auto-play, progress
saved to Plex).

Coming next: live previews on cards, Discover / Seerr, libraries, screensaver.
