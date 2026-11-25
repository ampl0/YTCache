# AI agent guide — reproduce YTCache

This page is a build order for an agent (or a new human contributor) who has to recreate this app
from nothing, or rebuild it after a context loss. It encodes the invariants that make the
difference between "the demo plays four seconds of silence" and a working app. Read it top to
bottom once before writing code.

- Source of truth for the original brief: [`../YTCache_HANDOFF.md`](../YTCache_HANDOFF.md)
- Detailed design: [`ARCHITECTURE.md`](ARCHITECTURE.md)
- Build commands: [`BUILD.md`](BUILD.md)

---

## 0. Non-negotiable core truths

1. **Do not remux to play.** YouTube serves AV1/VP9 video with Opus audio. Android's `MediaMuxer`
   cannot put those codec pairs in MP4 ("Failed to add the track") and generally cannot produce a
   playable file. Playback must feed the *captured bytes* back into MSE in a WebView. That WebView
   already decoded them once, so the codecs are guaranteed to work on that device.
2. **Capture at the `appendBuffer` boundary.** Hook `MediaSource.prototype.addSourceBuffer` for
   stream identity and `SourceBuffer.prototype.appendBuffer` for the bytes. Anything upstream (URLs,
   manifests, DRM) is a dead end.
3. **Metadata must be captured with the media.** Title, channel, duration, description and
   thumbnail come from the page at cache time. Never fetch them at view time; the app promises
   offline.
4. **Never transcode.** Export is a sample copy. If a pair has no legal container, export the video
   alone and tell the user why.
5. **Fail open.** A capture hook must never break the page it observes: wrap everything in
   try/catch, always call the original function, guard against double installation.

---

## 1. Environment

| Need | Version used here | Notes |
|---|---|---|
| JDK | 17 | AGP 8.5 requires 17 |
| Android SDK | platform 34, build-tools 34.0.0 | `compileSdk 34`, `targetSdk 34` |
| Gradle | 8.7 | wrapper included |
| Kotlin | 1.9.24 | set in root build script |
| AGP | 8.5.2 | |
| Node | any LTS | only for the JS test harnesses |

`bash scripts/setup-env.sh` provisions all of it (JDK, SDK, Gradle, `local.properties`, dev
keystore) and is safe to re-run. Then:

```bash
export JAVA_HOME="$HOME/tools/jdk17"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
"$HOME/tools/gradle-8.7/bin/gradle" --no-daemon test assembleDebug assembleRelease
```

If the environment is wiped between sessions, re-run the script; the SDK is the usual casualty.

---

## 2. Build order (do not reorder)

### Phase 1 — skeleton
- Gradle project: `com.android.application`, Kotlin, view binding, `minSdk 26`, `targetSdk 34`.
- Dependencies: `androidx.core:core-ktx`, `androidx.appcompat`, `androidx.webkit` (for
  `WebViewCompat`, `WebViewAssetLoader`, `DOCUMENT_START_SCRIPT`), Material, JUnit4.
- `MainActivity` with a `WebView`, a top bar (Cache / Library / Settings) and a status pill.
- Mobile UA without the `wv` token, otherwise YouTube serves a degraded page.
- WebView settings: JavaScript, DOM storage, `mediaPlaybackRequiresUserGesture = false`,
  `allowFileAccess = false`, `allowContentAccess = false`.
- **Verify:** `m.youtube.com` loads and plays.

### Phase 2 — capture (the hard part)
- `assets/hooks/v1.js`: wrap `addSourceBuffer` (assign `sN-kind`, record mime) and `appendBuffer`
  (send a copy). Idempotence guard, try/catch around all of it.
- Transport: `@JavascriptInterface postChunk(base64)` first, `WebMessageListener` optional.
- Frame the message as `[uint32 LE headerLen][JSON header][bytes]`; include
  `v, videoId, streamKey, kind, mime, seq, isFirstAppend, byteLength`.
- `data/ChunkProtocol.kt` parses and sanitises; `data/CaptureQueue.kt` decodes off-thread;
  `data/CaptureStore.kt` writes `<seq>.bin.tmp` then renames, and `meta.json` on the first chunk.
- Metadata: `ytInitialPlayerResponse.videoDetails` → `saveVideoInfo(json)`; retry while hydrating.
- **Verify:** play a video with Cache armed, then inspect the directory — `0.bin` starts with
  `ftyp` or `1A45DFA3`, fragment count grows, `video.json` has a real title and duration.

### Phase 3 — library
- Two-column grid: thumbnail (or coloured letter placeholder), title, channel, duration, date, size.
- `CaptureStore.list()` newest-first; search over title+author; sort newest/oldest/largest/A–Z.
- Delete with confirmation; multi-select with long-press, batch export/delete.
- Partial captures get a badge.
- **Verify:** `./gradlew test` covers listing, paging, delete, path safety.

### Phase 4 — offline player
- `PlayerActivity` hosts `shorts.html` through `WebViewAssetLoader` with a `/local/` path handler
  mapped to the capture root, so the page can `fetch()` files without file:// access.
- `player.js`: page list → `pickStreams` → fetch `0.bin` → `MediaSource` + one `SourceBuffer` per
  track with a sniffed mime → append fragments sequentially on `updateend` → `endOfStream`.
- Scroll-snap feed; pause/unload the previous page, save resume position.
- Autoplay gate: first tap plays.
- **Verify:** a full capture plays with sound; swiping stops the previous video.

### Phase 5 — polish that users actually asked for
- Real-duration seek bar with the cached window band (section 4 below).
- Chrome that fades while playing.
- Working system back on YouTube.
- Export, resume, last-watched restore, mute preference, tap ripple.

### Phase 6 — verification
- `node tools/test-core.js`, `node tools/test-hook.js`, `./gradlew test`.
- Build signed release, `apksigner verify`, `aapt dump badging`, publish checksums.
- Be explicit in the report about what was and was not exercised on hardware.

---

## 3. Pitfalls, symptom → cause → fix

These all happened. Recognise them by symptom.

| Symptom | Cause | Fix |
|---|---|---|
| "Failed to add the track", no audio in export | Trying to mux AV1/VP9+Opus into MP4 | Play with MSE; export only to a legal container |
| Video plays ~4 s then ends | Picked the first video SourceBuffer (an adaptive burst) instead of the dominant one | Rank by bytes, group by numeric stream index |
| Log spam: `conversion failure from *` | `WebMessageListener.postMessage(buffer, "*")` | Call `postMessage(buffer)` with no origin argument |
| Player: "no decodable streams" on long videos | One giant `readStream` JSON call exceeds the `@JavascriptInterface` return limit | Page reads: `readStreamPage(offset, count=8)` in a loop, or fetch through the asset loader |
| Wrong codec / black video | `meta.json` mime disagreed with the bytes | Sniff the init segment (ISO-BMFF fourcc or EBML CodecID) and try candidate mimes |
| YouTube buttons feel dead while caching | base64 decode + disk write inside the bridge call | Queue the string; decode and write on background threads |
| Back button closes the app on YouTube | SPA uses `pushState`; `canGoBack()` is false | Wrap `pushState`, count depth, `history.back()` from the page first |
| Seek bar fills to 100 % after 10 s of a 40-min video | Bar used `MediaSource.duration` as the total | Use metadata `lengthSeconds` as the total, buffered range as the band |
| Partial capture sits at 0:00 forever | Playhead at 0 while data starts at 12:34 | Align to `buffered.start(0)` (or saved position) after finalize |
| Playhead at 0 but audio plays | Early-return bug: listeners passed the `Event` where a page object was expected | Type-check/guard page arguments in event listeners |
| Path traversal | videoId/streamKey used raw in paths | Sanitise to `[A-Za-z0-9._-]`, collapse `..`, atomic rename |
| Double capture / duplicated fragments | Hook installed twice | `window.__ytcHookInstalled` guard plus idempotent native registration |
| No sound after reopening | Autoplay policy | Keep the mute/tap-for-sound gate; persist the preference |
| Audio continues on the next swipe | Previous page not paused | Pause + unload on `activate()` |

---

## 4. Invariants for the player timeline

A capture can start anywhere in a video, so the UI has two independent quantities:

- **total** = `max(metadata.lengthSeconds, ms.duration, capturedEnd)` — the real video length;
- **window** = `[buffered.start(0), buffered.end(last)]` — what exists on disk.

Rules that must hold:
1. Seek bar always spans `0…total`, never `0…window`.
2. The band is drawn at `window.start/total … window.end/total`.
3. Playback begins at the saved resume if it is inside the window, else at `window.start`.
4. Any seek is clamped into the window (−0.25 s margin) and the clamp is explained.
5. Remaining time is `total − current`, so it shows the truth, not the capture length.

Breaking rule 1 is what makes a partial long-video capture look like a 10-second clip; breaking
rule 3 is what leaves the player stuck at 0:00.

---

## 5. Verification harnesses

| Command | Covers |
|---|---|
| `node tools/test-core.js` (19 checks) | `sniff`, `mimeCandidates`, `pickStreams`, `fmtTime`, stream indexing |
| `node tools/test-hook.js` (19 checks) | `v1.js` in Node with mocked MSE + bridge: byte-exact capture, seq order, subarray offsets, double-install idempotence, exception propagation, videoId parsing |
| `./gradlew test` (57 tests) | framing, atomic writes, path traversal, storage listing/paging/delete, sniffer, mux planning against real ffmpeg output, export plan, stream assembly |

When you touch the hook, extend `tools/test-hook.js` first. When you touch storage or muxing, add a
JVM test — those classes deliberately have no `android.*` imports so they stay testable.

Building an end-to-end check without a phone: concatenate a real init + fragments
(`ffmpeg -f lavfi -i testsrc=64x64:rate=10 -t 1 -c:v libx264 -movflags
frag_keyframe+empty_moov+default_base_moof -frag_duration 200000 out.mp4`) and confirm the player
appends it and reports the right duration.

---

## 6. Acceptance checklist before shipping a build

- [ ] `./gradlew test assembleDebug assembleRelease` exits 0.
- [ ] `node tools/test-core.js` and `node tools/test-hook.js` pass.
- [ ] `versionCode` incremented, `versionName` bumped.
- [ ] `apksigner verify --print-certs` passes; the signer matches previous releases.
- [ ] `aapt dump badging` shows the expected package, version, `minSdkVersion 26`.
- [ ] APK copied to `artifacts/`, SHA-256 recorded, attached to a GitHub release.
- [ ] Release notes list user-visible changes and the verification caveat.
- [ ] Report says clearly what was not tested on a device.

---

## 7. Extension points

- **Another site:** keep `hooks/v1.js` as-is (it is MSE-generic) and replace the metadata reader and
  URL rules; `data/` and the player need no changes.
- **Faster transport:** wire `WebMessageListener` with `*` origins allowed, verify byte order on the
  target WebView, keep base64 as the fallback.
- **Background capture:** needs a foreground service holding the WebView; be careful with
  `onDestroy` cleanup and the activity-bound bridge.
- **Playlists:** the storage format already keys everything by `videoId`; add a queue table rather
  than changing the directory layout.
- **Export presets:** `MuxPlanner` is the only place that decides legality; extend it, never bypass
  it with a transcoder.

---

## 8. If you are starting from this repository

You do not need to rebuild anything to change behaviour. Map of where to look:

| Goal | File |
|---|---|
| Capture misses fragments / wrong mime | `assets/hooks/v1.js`, `data/MediaSniffer.kt` |
| Player timeline, chrome, gestures | `assets/player/player.js`, `style.css` |
| Storage / metadata / listing | `data/CaptureStore.kt`, `CacheBridge.kt` |
| Export behaviour | `Exporter.kt`, `MediaRemuxer.kt`, `data/MuxPlanner.kt` |
| Back button | `MainActivity.kt`, `assets/hooks/back.js` |
| UI polish | `res/layout/*.xml`, `LibraryActivity.kt`, `ui/VideoAdapter.kt` |
