# Architecture

YTCache is a capture-and-replay system, not a media downloader. This page explains every moving
part, the protocol between JavaScript and Kotlin, the on-disk format, and the maths behind the
player's timeline.

```
┌──────────────────────────── MainActivity (browse) ────────────────────────────┐
│  WebView  ──  https://m.youtube.com                                           │
│     │  document-start script: hooks/back.js + hooks/v1.js                      │
│     │                                                                          │
│     │  appendBuffer(fragment) ──► base64 message ──► @JavascriptInterface      │
│     ▼                                                                          │
│  CacheBridge ──► CaptureQueue (2 threads) ──► CaptureSession ──► CaptureStore  │
│                                                                   │            │
│  status pill ◄── stats() every 500 ms                             ▼            │
│                                                     files/YTCache/<videoId>/   │
└───────────────────────────────────────────────────────────────────────────────┘
                                                                   │
        ┌──────────────────────────────────────────────────────────┤
        ▼                                                          ▼
┌ PlayerActivity ─ shorts.html ─ player.js ┐        ┌ Exporter ─ MediaRemuxer ┐
│ WebViewAssetLoader /local/ reads the files│        │ MediaExtractor + Muxer  │
│ MediaSource + 2 SourceBuffers (MSE)       │        │ copy samples, no codec  │
│ real-duration seek bar, cached band       │        │ MP4 / WebM / audio-only │
└───────────────────────────────────────────┘        └──────► Movies/YTCache ──┘
```

## 1. Components

| File | Responsibility |
|---|---|
| `MainActivity.kt` | WebView host pointed at `m.youtube.com`, capture arm/disarm, floating status pill, settings, fullscreen video handling, back-button dispatcher |
| `CacheBridge.kt` | The only JS-visible object (`YTCacheAndroid`): chunk intake, metadata intake, thumbnails, resume positions, export/delete/library intents |
| `data/CaptureSession.kt` | Per-video session: which streams exist, byte counts, title, finish → build `VideoEntry` |
| `data/CaptureQueue.kt` | Bounded work queue on two threads: base64 decode + atomic write, off the bridge thread |
| `data/ChunkProtocol.kt` | Parses `[uint32 LE headerLen][JSON header][bytes]`, sanitises ids, validates framing |
| `data/CaptureStore.kt` | Directory layout, atomic writes, `video.json` / `streams.json` / `thumbnail.jpg`, paged reads, delete, library listing |
| `data/MediaSniffer.kt` | Container + codec detection from the init bytes (ISO-BMFF box walk, EBML ids, fourcc scan) |
| `data/StreamPicker.kt`, `data/StreamAssembler.kt` | Choose dominant video + matching audio; concatenate fragments on demand |
| `data/MuxPlanner.kt` | Decide whether a (video, audio) codec pair is legal for MP4 or WebM |
| `MediaRemuxer.kt` | Stream-copy samples into a new container with `MediaMuxer`, preserving relative timing |
| `Exporter.kt` | Orchestrates export, writes through MediaStore into `Movies/YTCache`, reports honest outcomes |
| `LibraryActivity.kt`, `ui/VideoAdapter.kt` | Grid, search, sort, multi-select, batch export/delete |
| `PlayerActivity.kt` | Fullscreen host for `shorts.html`, asset loader, back and delete handling |
| `assets/hooks/v1.js` | MSE tee, stream registration, metadata extraction, transport |
| `assets/hooks/back.js` | `pushState` depth bridge → `window.__ytcGoBack()` |
| `assets/player/player.js` | Feed paging, MSE append pump, seek bar maths, chrome fade, gestures, resume |
| `assets/player/ytc-core.js` | Pure helpers shared by the player and Node tests |

## 2. Capture protocol

### 2.1 Hook installation

`MainActivity` builds one script from `hooks/back.js` + a capture flag + `hooks/v1.js` and installs
it with `WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))`. Document start is
mandatory: the hook must replace `MediaSource.prototype.addSourceBuffer` before YouTube's bundle
runs. If the WebView lacks `DOCUMENT_START_SCRIPT`, `onPageFinished` evaluates the same script as a
fallback. Both paths are guarded by `window.__ytcHookInstalled`, so double injection is a no-op.

`window.__ytcCaptureEnabled` is flipped live by the Cache button, so arming mid-playback works
without reloading the page.

### 2.2 Stream identity

`addSourceBuffer(mime)` is wrapped to register each buffer:

```
streamKey = "s" + (counter++) + "-" + kind     // s0-video, s1-audio, s3-video …
```

A single video is spread over *many* source buffers as the player adapts quality. Each gets its own
directory. This is expected, not a bug.

### 2.3 Fragment message

Every `appendBuffer` call is teed, without touching the original call:

```
[ uint32 LE headerLength ][ UTF-8 JSON header ][ raw fragment bytes ]   (base64 on the wire)
header = { v:1, videoId, streamKey, kind, mime, seq, isFirstAppend, byteLength }
```

Transport order of preference:

1. `@JavascriptInterface` `postChunk(base64)` — reliable, ordered, survives everything.
2. `WebMessageListener` `ArrayBuffer` — faster, but only if verified on the target WebView;
   `postMessage` must be called **without** a targetOrigin argument or Chrome throws
   "conversion failure from *".

The bridge hands the string straight to `CaptureQueue`, so the WebView renderer never waits for a
disk write. Doing the decode inline is what made YouTube's own buttons feel dead in early builds.

### 2.4 Metadata

At capture time the hook reads `window.ytInitialPlayerResponse.videoDetails` for title, author,
`lengthSeconds` and thumbnails, and `microformat.playerMicroformatRenderer.description` for the
description. Fallbacks: `<meta property="og:*">`, `<meta name="duration">` (ISO-8601), and
`document.title`. It posts once (`saveVideoInfo(json)`), retrying up to ~20 s while the page is
still hydrating. Metadata saved at capture time is the only metadata the player ever shows —
offline stays offline. A thumbnail is downloaded once, at capture time.

### 2.5 Storage layout

```
/files/YTCache/
  <videoId>/
    video.json        { videoId, title, author, lengthSeconds, description, thumbUrl,
                        sourceUrl, capturedAt, sizeBytes, streamCount, hasThumb, resumeMs,
                        playable, partial }
    streams.json      [ { streamKey, kind, mime, container, codecs, bytes, fragments,
                          playable, durationMs } ]
    thumbnail.jpg
    s0-video/
      meta.json       { videoId, streamKey, kind, mime }
      0.bin           init segment (ftyp+moov, or EBML header)
      1.bin 2.bin …   media fragments (moof+mdat)
    s1-audio/ …
```

Writes are `tmp` + atomic rename. Ids are sanitised to `[A-Za-z0-9._-]`, with `..` collapsed and
empty values mapped to `unknown` — a path traversal was a real bug here.

### 2.6 Container sniffing

`streams.json`'s `mime` comes from the page and can lie (a stream labelled `webm/vp09` whose init
bytes are a fragmented MP4). `MediaSniffer` walks the init bytes instead: ISO-BMFF boxes
(`ftyp`/`moov`, sample-entry fourcc `av01`/`vp09`/`avc1`/`mp4a`/`Opus`…) or Matroska/EBML
(`1A45DFA3`, CodecID `V_AV1`/`V_VP9`/`A_OPUS`…). `ytc-core.js` performs the mirror-image check in
the player and produces candidate mime strings, trying the most specific first.

## 3. Playback

### 3.1 Track selection

`YTC.pickStreams(streams)` ranks video streams by byte count, picks the largest **playable** one
(has an init segment), then prefers the audio stream with the same numeric index, else the largest
audio. Picking the first stream ever seen was the original "plays 4 seconds then ends" bug: the
first buffer is usually a low-quality adaptive burst.

### 3.2 Append pump

For each track: fetch `0.bin`, create a `SourceBuffer` with a sniffed mime (falling back through a
candidate list on `NotSupportedError`), append the init segment, then stream `1.bin…N.bin`
sequentially, chaining on `updateend`, with a small look-ahead window so long captures do not
buffer everything in memory at once. When all fragments are in, `finalize()` sets
`MediaSource.duration` to the buffered end and calls `endOfStream()`.

### 3.3 The timeline (real duration, cached window)

A capture that started mid-video keeps the stream's original timestamps: the first cached frame may
be at 12:34 of a 45:00 video. Three functions define the behaviour:

- `durationOf(page)` → `max(metadata lengthSeconds, MediaSource.duration, capturedEnd)`. The bar is
  always the **real** video length.
- `bufferedRange(page)` → `{ start: buffered.start(0), end: buffered.end(last) }`. This is the window
  that actually exists on disk.
- `alignPosition(page)` → if a saved resume position falls inside the window, jump there (toast
  *Resumed at mm:ss*); otherwise jump to `window.start + 0.05` (toast *Cached part starts at mm:ss*).
  It runs once per page, and again on `progress`/`canplay` until a window exists.

The seek bar is a `0…1000` ratio of the real duration. The grey band is drawn with
`left = window.start / duration`, `width = (window.end − window.start) / duration`, so a mid-video
capture appears as a segment part-way along the bar. `seekTo()` clamps any request into the cached
window and explains the clamp with a toast. `updateTimes()` drives current time, remaining time
(`-mm:ss`), the played fill and the band; a chip reads `Cached 12:34–24:10 / 45:00` whenever the
window is shorter than the video.

### 3.4 Chrome fade

While playing, title/author/actions/controls get `.ui-hidden` after 2600 ms; any tap, pause, seek or
page activation calls `showChrome()` and restarts the timer. Paused means chrome is always visible.
This mirrors the real YouTube player instead of covering the bottom of every video permanently.

### 3.5 Feed behaviour

`shorts.html` is a scroll-snap column, one `<section>` per video. `activate(page)` loads the page it
scrolls to and pauses/unloads the previous one — including revoking its object URL — so audio never
continues behind the next video. Position is saved to the capture's `resumeMs` on pause and on page
change, and to `localStorage['ytc.last']` so reopening the player returns to the last video.

### 3.6 Sound

Autoplay with sound is blocked in a WebView without a gesture. The player therefore starts muted or
behind a play gate, and the first tap calls `video.play()`. The sound preference (`ytc.muted`) is
shared across pages.

## 4. Export

`export(videoId, kind)`:

1. `StreamPicker` chooses the dominant video and its audio.
2. `MuxPlanner` checks the pair against container rules:
   - MP4: H.264 / H.265 / AV1 video + AAC audio (or video-only)
   - WebM: VP8 / VP9 / AV1 video + Opus / Vorbis audio
3. `StreamAssembler` concatenates fragments into a temp file per track in `cacheDir/export`.
4. `MediaRemuxer` opens each with `MediaExtractor`, finds the first video/audio track, offsets both
   timelines to the earliest sample, and writes samples unchanged through `MediaMuxer`
   (`MUXER_OUTPUT_MPEG_4` or `MUXER_OUTPUT_WEBM`, via a `RandomAccessFile` descriptor because the
   WebM muxer rejects a plain output stream).
5. `Exporter` publishes the result with `MediaStore` into `Movies/YTCache`, then deletes staging.

If no container can legally hold both tracks, only the video is exported and the toast says why.
There is no fallback that silently drops audio without telling the user; that was an earlier bug.

## 5. Back navigation

`m.youtube.com` is a single-page app: it navigates with `pushState`, so `WebView.canGoBack()` is
often `false` even three screens deep, and the system back button used to close the app. The
dispatcher is layered:

1. Fullscreen video open → close it.
2. Ask the page: `window.__ytcGoBack()` (from `hooks/back.js`) returns `true` if it consumed the
   press via `history.back()`; depth is counted by wrapping `pushState` and decremented on
   `popstate`. Native waits at most 400 ms for the answer.
3. Native WebView history: `canGoBack()` → `goBack()`.
4. Otherwise arm exit: toast *Press back again to exit*; a second press within 2.2 s finishes the
   activity.

`hooks/back.js` is prepended to the same document-start script as the capture hook, because it must
wrap `pushState` before YouTube's bundle does.

## 6. Threading and lifecycle

- Bridge calls arrive on the WebView's Java thread → `CaptureQueue` (2 threads) → `CaptureStore`.
- Metadata and thumbnails run on a small executor; the UI is updated on the main looper.
- The status pill polls `session.stats()` every 500 ms while the activity is resumed.
- `onDestroy` removes the JS interface, drops the WebView from its parent and destroys it, cancelling
  the pulse animation and the polling runnable.
- There is no foreground service: capture stops when the browse activity is destroyed. This is a
  deliberate scope decision, documented in [USAGE.md](USAGE.md#limits).

## 7. Failure modes and what the app does

| Failure | Detection | Behaviour |
|---|---|---|
| Capture started mid-stream (no init segment) | `playable == false` for every stream | Player shows an explanation and tells you to cache from the start |
| Page lies about mime | `MediaSniffer` / `sniff()` on init bytes | Correct container and candidate codec list are used |
| Codec pair not muxable | `MuxPlanner` | Video-only export with an explicit reason |
| Fragment missing or capture stopped early | contiguity check in `CaptureStore` | Entry flagged `partial`, library badges it, chip shows the real window |
| Seek outside cached window | `seekTo` clamp | Jump to nearest cached frame + toast with the window |
| WebView lacks document-start scripts | `WebViewFeature` check | Same script evaluated on `onPageFinished` |
| Bridge unavailable | `androidAvail()` | Chunk dropped with a log line; page playback unaffected |

## 8. What is deliberately *not* here

- No DRM handling, no signature/URL extraction, no server component.
- No transcoding anywhere; `MediaCodec` is used only to read codec names for mux planning.
- No background service, no scheduled downloads, no playlist queue.
- No analytics, no network calls at playback time.
