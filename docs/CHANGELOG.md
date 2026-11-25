# Changelog

All notable changes to YTCache. Versions follow `major.minor.patch`; Android `versionCode` is
incremented for every published build.

## 1.2.0 — versionCode 4

### Player timeline
- Seek bar now spans the **real video duration** (`lengthSeconds`), not the captured length.
- The cached window is drawn as a band on the bar; a chip reads `Cached 12:34–24:10 / 45:00`.
- Playback auto-aligns to the first cached frame, or to your saved position when it is inside the
  window (`Resumed at mm:ss`).
- Seeking outside the cached window clamps to the nearest cached frame and explains why.
- Remaining time is computed against the real duration.

### Player UI
- Title, channel, actions and controls fade out after ~2.6 s of playback and return on tap, pause or
  seek, like the YouTube player.
- Tap ripple on the video surface.
- Last-watched video is restored when the player is reopened.
- Fixed `loadedmetadata` / `durationchange` listeners passing the event object where a page object
  was expected.

### Navigation
- Android back now works inside YouTube: fullscreen → page history (new `pushState`-aware bridge) →
  WebView history → *Press back again to exit* (2.2 s window).

## 1.1.1 — versionCode 3

- Fixed a crash in the library grid when a card's ripple handler fired after a rebind.

## 1.1.0 — versionCode 2

- Export: lossless remux to MP4 or WebM with audio when the codec pair is legal; audio-only export;
  explicit message instead of a silently silent file.
- Export prefers MP4 for MP4-legal pairs.
- Paged fragment reads (`readStreamPage`) so long videos no longer exceed the bridge return limit.
- Shorts player metadata fixed (title/channel/duration from the capture, not the page).
- Tap-for-sound gate for autoplay restrictions.
- Optional recording animation / capture pill (Settings).
- YouTube back handling improvements.
- Play triangle no longer overlays playback.
- Long-press multi-select with batch export and batch delete.

## 1.0.0 — versionCode 1

- First complete release: WebView MSE capture hook, fragmented storage with atomic writes, metadata
  and thumbnail captured at cache time, library grid with search/sort/delete, offline MSE shorts
  player with vertical paging and resume positions.
