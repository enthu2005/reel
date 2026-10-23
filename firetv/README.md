# Reel: a Plex app for Fire TV

A Plex app built around a big hero banner: move across posters and the background, logo,
summary and Play button change to match. Rows of posters underneath, a detail page, and a player.

First version, written without being able to run it. Expect a few rough edges.

## What it does

- Sign in with a code: Reel shows a code, you enter it at **plex.tv/link** on your phone. No typing a password on the TV.
- Finds your Plex server automatically (tries your home network first, then remote, then Plex's relay).
- **Home** tab: a spotlight card at the top that rotates through your newest titles (dots show where you are; Left/Right on the buttons steps through them), then Continue Watching, Currently Watching (what is playing on your server right now, with who and on which device) and Recently Added for each library.
- Frosted-glass tab bar, cross-fading backdrops and animated tab switches. Real blur isn't available on the stick's Android version, so the bar uses a shrunken copy of the backdrop to fake it.
- **Movies** and **TV Shows** tabs: Recently Added and the whole library, A to Z.
- Detail page: logo, summary, Play / Continue / Start over / Mark watched. Shows list every episode.
- Playback with ExoPlayer: plays the original file; if the stick can't, it switches itself to Plex's
  transcoder from the same spot. Progress is sent back to Plex, so Continue Watching stays in sync
  with your other devices. Audio and subtitle choices are in the player's settings button.

## Format badges

Open a movie and the detail page shows badges worked out from the real file: 4K / 1080p, Dolby Vision,
HDR10, and the audio format (Dolby Digital, Dolby Digital Plus, Dolby Atmos, Dolby TrueHD, DTS,
DTS-HD MA, DTS:X) with channels such as 5.1 or 7.1. They are styled labels, not the official logos.

## Audio and subtitle menu (in the player)

Press the **Menu** button (the three lines) on the remote while a video plays, or move up to the
"Audio & subtitles" button that appears with the controls.

- **Audio:** every audio track with its real format. If your stick can't decode a track (often DTS or
  TrueHD) it is marked "needs transcoding" and Reel switches to Plex's transcoder when you pick it.
- **Subtitles:** Off, built-in tracks and subtitle files. Choosing one while transcoding burns it in.
- **Find subtitles online:** pick a language and your Plex server searches for you (needs Plex Pass
  and a subtitle provider turned on in the server settings). Pick a result to download it.
- **Info:** a live audio debug screen: direct play or transcode, video and audio format, which
  decoders are running, dropped frames, audio underruns, which sound formats your TV or receiver
  accepts, and whether each track in the file is supported. A switch puts the same info on the video.

## Surround passthrough

Dolby TrueHD, Atmos, DTS-HD MA and DTS:X are sent untouched to your TV or receiver whenever it says it
can take them. On the Fire TV set Settings > Display & Sounds > Audio > Surround Sound to **Best
available** (or Dolby/DTS passthrough) and connect through an eARC/receiver. The Info tab in the
player shows which formats your setup accepts.

## Not in yet

Search, collections, choosing which of several servers to use, and picking a specific audio or video
version (it uses the first one).

## Build it (Android Studio)

1. Unzip, then open the inner `Reel` folder (the one containing `settings.gradle`).
2. Let Gradle sync. The first sync downloads libraries, so give it a few minutes.
3. Build > Generate App Bundles or APKs > Generate APKs, then "locate".
4. Install on the stick:

       .\adb connect 192.168.1.12:5555
       .\adb install -r C:\path\to\app-debug.apk

5. Open **Reel** from the Fire TV apps list and sign in.

## Remove it

    .\adb uninstall com.enthu.reel
