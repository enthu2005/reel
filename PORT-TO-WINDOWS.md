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
