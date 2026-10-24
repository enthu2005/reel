# Port the Fire TV changes to the Windows app

Brief for whoever (me or Claude) opens the repo on the other computer. The Fire TV app (`firetv/`) got the changes below.
Do them in `windows/` (Flutter), in the Windows app's own visual style. The Fire TV Java code is the reference for behaviour:
`firetv/app/src/main/java/com/enthu/reel/`. Skip anything that is Fire TV hardware only (marked "skip"). Check what the Windows app
already has before building each item, some of it may exist.

## Player
1. **Queue** (static list that survives between videos). A Shuffle button on movie-type libraries (Fire TV: BrowseActivity.shufflePlay
   -> PlayerActivity.startQueue) plays the whole library shuffled. Played videos stay in the queue. A queue panel like the
   episodes panel: click plays that item; hold (or a drag on Windows) picks one up to move it; remove an item from the panel
   (not the one playing). Keep the same panel for episodes of a show.
2. **Previous / Next** buttons in the player (and media keys). They step through the queue, or a show's episodes. Dimmed when there is no neighbour.
3. **Timeline stays visible while the queue/episode panel is open** (pinned, lifted above the panel).
4. **Up/settings behaviour** (remote only; on Windows just make sure the first reveal of controls does not also open settings).
5. **Skip preview thumbnail**: while scrubbing, a small box above the bar shows the frame at that time. Plex BIF index
   (`/library/parts/{partId}/indexes/sd`), and if the server has none, fall back to the chapter pictures
   (`/library/metadata/{id}?includeChapters=1`, `Chapter[].thumb`, `startTimeOffset`). See PlayerOsd.loadThumbnails / loadChapterThumbnails / showThumb.
6. **No audio before video after a seek**: do not play sound until the first frame is ready; show the buffering spinner instead.
   (Fire TV: `setAllowedVideoJoiningTimeMs(0)`, `SHOW_BUFFERING_ALWAYS`.) Apply the equivalent to the Windows player.
7. **Changing video quality (e.g. transcode to 720) then back must always show picture.** Fire TV fix: fresh video surface on each restart.
8. **Subtitle appearance** replaces the timing/sync rows: Size (4 steps, default 2nd), Colour (white / yellow / cyan / green),
   Position (5 steps from the bottom edge, default middle). Stored as `subSize` 0-3, `subColour` 0-3, `subPos` 0-4. See PlayerActivity.cycleSubSetting / applySubStyle.
9. **Picture fit** per film: Fit / Fill / Stretch (not saved).
10. Subtitle search: after downloading, pause, wait for the new stream to appear, select it, close the panel, resume.
11. skip: AC3 / E-AC3 passthrough work (Fire TV audio sink only).

## Home
12. **Spotlight**: one button "Go to movie / show / episode" opening the title page (no prev/next arrows). Rotates every 9 s through up to 10
    items, which include the Up Next list. **Trailer preview**: after 3 seconds on a spotlight title the backdrop becomes its
    preview (trailer file, else a scene from the film, via the same source lookup the cards use), with the video loaded during
    those 3 seconds so it starts immediately. The rotation holds while it plays. Volume from the existing preview-sound setting.
13. Movies / TV tabs get a **Continue Watching** row (`/library/onDeck`): films on Movies, episodes on TV.
14. "Because you watched": up to 2 watched seeds per library, only unwatched related items, at least 4 results.
15. Previews of a show use its first episode when there is no trailer.

## Title page
16. **No Trailer button.** The trailer (or a scene from the film when there is none) plays quietly behind the page 2.5 s after it opens,
    unless a preview was already playing on the card you came from (then it carries on). Setting: "Trailer behind title pages".
17. Preview button is a small expand icon (four corner brackets), not text. Opens the preview full screen.
18. In a full-screen preview on a card, OK opens that title's page (fading in).
19. Cast and Similar sit in a column anchored at the bottom so they never overlap the summary/badges; summary is 2 lines when Cast is shown.
20. Shuffle button on show pages: queue = this episode first, then the rest, keeping already-played ones in the list.
21. **Discover** keeps its "Watch trailer" button (full-screen trailer) - leave as is.

## Libraries and settings
22. Libraries page: hide music libraries; thumbnail cards (latest title's artwork, kind label, title, item count), no folder paths.
23. Sort options: A to Z, Recently added, Release date, Rating.
24. Settings: **Home & movies libraries** picker and **Screensaver libraries** picker (multi-select dialog that stays open, saves on every
    tick, keeps at least one ticked, clear focus highlight on the row). Prefs `visibleLibraries` and `screensaverLibraries`; the
    screensaver defaults to movie-type libraries only. Load the library list off the UI thread.
    NB: unticking one library while all were shown must not make every library reappear.
25. Screensaver (if the Windows app has one) only uses the chosen libraries.

## Order I would do it in
Libraries/settings pickers -> home (spotlight, Continue Watching) -> title page -> player (queue, prev/next, subtitle look, thumbnails, seek behaviour).

---

# Second batch (Oct 2026): everything added since the list above

Same rules: Fire TV Java in `firetv/app/src/main/java/com/enthu/reel/` is the reference, do it in the Windows app's own style, skip remote-only parts.
Where a number is given it is the value the Fire TV app uses now; keep the same timings so both apps feel alike.
Note: items 16 and 12 above are SUPERSEDED by the timings below (title-page gap is now 4 s, spotlight preview gap 3 s).

## One set of animation timings (do this first, everything else uses it)
Fire TV class `Motion.java`. Define one shared file in Flutter (e.g. `motion.dart`) and use it for every animation in the app:
- Durations: tap 100 ms, fast 150, focus 200, medium 320, slow 480, long 650 ms.
- One easing everywhere: cubic-bezier(0.2, 0, 0, 1) (`Cubic(0.2, 0.0, 0.0, 1.0)`). Do not mix curves.
- Focus/hover scale comes from one function (`focusScale`), not per-widget numbers.
- Poster-to-video dissolves are the one exception: 2200 ms with a plain ease-in-out (no quick start).
- Window/page transitions between screens: 420 ms dissolve (new page fades in over the old one, old one stays put under it).

## Delays and gaps (all the "wait before something happens" values)
- **Hover preview on a card** (Home rows and Libraries): 4 s after the card is focused/hovered before its preview video shows (the video is prepared ~150 ms after focus so it is ready). Moving off cancels both.
- **Title page background preview**: 4 s after the page opens. Until then keep showing the poster/backdrop. If a preview was already playing on the card you came from, it carries straight on (no gap).
  If that carried-over player had not started yet, hold it paused for the full gap.
- **Poster -> preview fade**: never show the video's first frame early. Keep the video layer at opacity 0 while it is paused/buffering; only when it is really *playing* fade it in over 2.2 s (ease in-out). The side/bottom shading lightens over the same 2.2 s. Sound fades in separately over ~1.8 s to the preview volume setting. Applies to file trailers, Plex clips and YouTube trailers alike.
- **Home spotlight preview**: 3 s after a spotlight title shows. Rotation holds while it plays.
  - 1.2 s after the preview starts playing, push the rows below down (translateY about 34% of window height, long/slow ease) so the whole video is visible, while the nav bar, title and "Go to movie" button stay in view.
  - Pause the slow background-pan ("drift") animation while any preview plays (it costs GPU and makes video choppy). Resume when the preview stops.
  - **Hold OK on "Go to movie"** goes full screen with the preview (everything else fades out; Back/any key brings it back). A normal click opens the title page.
- Release preview players as soon as the screen is paused/left (not on stop): the next screen starts before the old one stops, and two players fight over the decoder/audio.

## Opening a title from a card
- No swivel/3D flip. The card grows to fill the window (slow, shared easing), then the title page dissolves in (420 ms); remove the grown card after ~1 s so nothing is left behind. A hidden clone of the card is used so the row doesn't jump.
- Do not let the expanding card's text overlap anything during previews: while the card is grown, hide/clip the text under it (the overlap bug seen with the title under the preview).

## Instant open
- Cache `/library/...` API responses to disk (not the huge ones: skip bodies over ~2 MB). Home shows the cached version immediately, then refreshes in the background and only rebuilds the rows if the content actually changed (compare a signature of the lists), so focus never jumps.
- Poster images: show the card-coloured placeholder and crossfade the image in over 200 ms.

## Cards
- **Poster tilt on focus**: focused poster tilts slightly toward the direction you last moved (about 7 deg on posters, 5 deg on wide cards), with the shared focus scale. Remember the last arrow direction (`Motion.noteKey`) so tilt follows it.
- **Library pages use 16:9 cards everywhere** (5 columns; episode/photo lists 4). Fixes the grown preview covering neighbouring cards. Cards show the title/second line under them.
- Home row "Up Next" is renamed **Continue Watching** (same `/library/onDeck` data, progress bar + minutes left on each card). Every place that looked for the old name (soft refresh, "Your requests" placement, spotlight/rows ordering) uses the new name.

## Ambient colour on Home
- Take the focused title's poster (48x72 thumb), compute its dominant colour (weight = saturation*value + 0.02, pixels with value < 0.15 count 10 times less; average RGB) and tint the left and bottom dark gradients with it. Shade at fixed darkness so text stays readable (HSV value: left .17/.20/.20, bottom .14/.12/.07; saturation capped at .75). Animate the colour change over 1.3 s (2 x long). Cache colours per poster path; first colour is set instantly.
- The title page does the same (applyTint) with the same shades.

## Title page
- **Title logos**: use the Plex `clearLogo` Image entry instead of plain title text (for episodes, the show's logo); fall back to text.
- **Ratings strip** under the meta line: coloured tag + value per source then the runtime, all on one line, e.g. `RT 94%  IMDb 7.8  2h 8m`. Plex JSON: `rating` + `ratingImage`, `audienceRating` + `audienceRatingImage`. Image starting `rottentomatoes` -> "RT", value x10 as %; `imdb` -> "IMDb", one decimal; `themoviedb` -> "TMDb", x10 as %. Colours: RT #FA320A white text, IMDb #F5C518 dark text, TMDb #01B4E4. Only if there are ratings; then the runtime moves out of the meta line into the strip.
- **Collections**: if the movie has a `Collection` tag with `filter=collection=ID`, fetch `/library/sections/{sectionId}/all?collection=ID`, remove the current film, sort by year then title (release order), and show them in the bottom row labelled with the collection name, replacing "Similar". No collection -> "Similar" as before.
- **Cast click** opens a person page: big portrait, name, short bio (Wikipedia REST summary: try name, then "(actor)", "(actress)", "(director)"; only use it if it reads like a film person), then their titles from `/library/all?{cast.filter}` (movies and shows only), **newest first**, as a poster row. Going back returns to the same cast member (do not rebuild the row when the page is refreshed on return: only reset the adapter if the cast list changed; same for the Similar/collection row).
- Cast/bottom rows sit a little lower than before (bottom margin ~0.4% of height) so they don't touch the badge row once the ratings line is added.
- **Audio badges**: use the official Dolby Vision, Dolby Atmos, Dolby TrueHD, Dolby Digital Plus, Dolby Digital, DTS-HD (and HRA), DTS:X and DTS logos as white-on-transparent images instead of text tags. No channel-count tags (no 5.1 / 7.1). Height about 4.6% of window height. The PNGs are in `firetv/app/src/main/res/drawable-nodpi/badge_*.png`, copy them into the Flutter assets.

## Player
- **Play/Pause** media key also works while the audio/subtitle/info panel or the episode panel is open (toggles playback, panel stays).
- **Up from the audio/subtitle panel's list** goes to the tab you are on (not the Video tab).
- **Fast forward / rewind (2x 4x 8x 16x), Plex-style**: the video does NOT play or seek while you skip. On the first FF/RW press: remember the position and whether it was playing, pause, pin the controls and show a label (`>> 4x  1:02:30`) at the top centre. Only the timeline and scrub thumbnail move, every 100 ms, at 10 / 30 / 90 / 240 seconds of film per real second for 2x / 4x / 8x / 16x. Same direction again = next speed (cap 16x); opposite direction = one step slower, and from 2x it cancels. **OK / Play-Pause / any other key = jump to that point and play.** **Back = cancel and return to where you were.** Stops at the start, and 4 s before the end. Keyboard on Windows: use `[` / `]` or the media keys.
- Tunneled playback must be OFF when it would break sound (decoded multichannel PCM, or the DTS-HD passthrough sink) and a failed audio-track init retries once without it. (Fire TV hardware detail; on Windows just make sure a failed audio init retries with a safe path.)
- Release the player and restore audio mode as soon as the screen is left for good.
- After going back from the cast page, the title page must keep focus on the cast member (see above).

## Order
Motion/timings file -> delays and fades -> cards (tilt, 16:9 library, Continue Watching rename) -> title page (ratings, collections, cast page, logos, badges) -> Home (ambient colour, spotlight push/fullscreen) -> player (FF/RW).

---

# Third batch (Oct 2026): added after the second batch

Same rules as before. These come last, so where they disagree with the second batch, THIS batch wins.

## Opening a title from a card (replaces "grow then dissolve")
- **No growing animation at all.** Scaling a card with live video up to full screen was heavy and looked laggy. Clicking a card hands the playing/loading preview player to the title page and the title page dissolves in over the card in **300 ms** (page transitions are 300 ms now, not 420). The old card is removed about 1 s later.
- This applies on Home and in the libraries. The only thing that still grows the preview to full screen is **holding OK/clicking-and-holding on a card whose preview is playing** (just the preview, full screen).
- Make sure nothing cancels the dissolve afterwards (on Fire TV a leftover "no transition" call cut the fade in the libraries).

## Delays (final values)
- Hover preview on a card, **everywhere (Home rows AND library pages): 4 s**.
- Title-page background preview: 4 s, but the video is **loaded in the background the moment the page opens** (paused, silent, invisible), then started when the 4 s are up. Trailer files, Plex scene clips and the carried-over player all work this way. (YouTube trailers still load at the end of the gap.) Do not require window focus for this early load; check it again when the gap is over.
- Scene previews ("clip of the film" when there is no trailer) ask Plex for a **720p / 3.5 Mbit** transcode instead of 1080p / 6 Mbit: starts much faster, plays smoother.
- Poster-to-video fade on the title page: 2.2 s, ease in-out (see batch 2). Keep the video layer hidden until it is really playing; never show its first frame early.

## Card marks
- Top-right corner of every poster/wide card: a **white tick in a dark circle** when watched, or a blue **NEW** pill (#0A84FF, white text) when added in the last 7 days and not started. Never both; partly watched items get neither (they have the progress bar).
- Watched: movie/episode `viewCount > 0` and no `viewOffset`; show/season `leafCount > 0` and `viewedLeafCount >= leafCount`.
- New: `addedAt` within 7 days, not watched, no `viewOffset`, no `viewedLeafCount`.
- Needed Plex fields: `addedAt`, `leafCount`, `viewedLeafCount`, `viewCount`, `viewOffset`.

## Home rows from people
- On Home and the Movies tab (movie libraries only), near the top: **"More with {lead actor}"** and **"Directed by {director}"**, built from the most recently watched film (first one with `viewCount > 0` among the last 10 by `lastViewedAt`). Fetch its metadata (`Role[0]` and `Director[0]` with their `filter`), then `/library/sections/{id}/all?{filter}&sort=year:desc` size 40, drop the seed film, unwatched first then watched, show only if 4 or more. They sit with "Because you watched" and "Unwatched", above genre and collection rows.
- Parse `Director` tags the same way as `Role`: `tag`, `filter`, `thumb`.

## Collection pages
- On a title page whose film belongs to a collection, the bottom row label reads `{collection name} ›` and is focusable (white pill when focused). Clicking it opens a full page (the library grid page) of every film in the collection including the current one, `/library/sections/{id}/all?{collection filter}&sort=originallyAvailableAt:asc`, wide 16:9 cards. The "Similar" label is not focusable.

## Quiet mode (moon next to Settings)
- A small moon icon (crescent drawn as a shape, not an emoji) just left of "Settings" in the top-right corner. Faint (45% opacity, outline) when off; warm yellow (#FFE9A8), filled, full opacity when on; dark moon on the white pill when focused. Click toggles; briefly shows "Quiet mode on/off". Pref `quiet`, saved until changed.
- When on: no card previews (Home or libraries), no spotlight video, no background trailer on title pages, preview volume forced to 0. Turning it on stops anything playing. The actual player is untouched.

## Screensaver
- Each slide shows the title's logo (`clearLogo`, fall back to text title) bottom-left, with the film's one-line **tagline** under it (fall back to the year). Fetch the item's full metadata per slide, cache it, fade the logo and tagline in over 0.9 s. Artwork keeps its slow zoom/pan drift.

## Info panel
- Player Info tab gets lines for **RAM** (app memory split into managed + native, and device free/total) and **Disk buffer** (stored MB of the 3 GB store and MB fetched ahead, or "off"). The numbers refresh once a second.

## Extra large buffer (optional on Windows)
- Fire TV setting "Extra large buffer" (pref `bigBuffer`, off by default): for direct play only (not Plex transcodes), read the file through an on-disk cache (LRU, 3 GB max) while a background task keeps fetching up to ~2.6 GB ahead of the playhead in 8 MB chunks (it skips the first 100 MB after the playhead, re-aims after a seek using position/duration x file length as an estimate, and clears the cache when a different film starts or the setting is turned off). On Windows the player already caches differently, so only do this if buffering is a problem there.

## Other small things done on Fire TV
- Cast page: going back returns focus to the same cast member (do not rebuild the cast/Similar rows when the title page refreshes unless the data changed).
- Library grid is **5 columns** of 16:9 cards (4 for episodes/photos).
- Cast/bottom rows sit slightly lower (bottom margin about 0.4% of window height).

## Order
Transitions and delays -> card marks -> people rows and collection pages -> quiet mode -> screensaver -> info panel -> (optional) disk buffer.
