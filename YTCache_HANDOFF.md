# YTCache — Complete Engineering Handoff

A build-reproducible brief so another agent can create this app from scratch,
straightforward and without the bugs we hit.

Target: an Android app that captures video+audio from the YouTube mobile WebView
as it plays, stores the raw media, and provides a YouTube-style offline library
(grid → shorts player) with metadata (title/author/description/thumbnail).

================================================================================
0. THE CORE TRUTH (read this first — it saves days)
================================================================================

- DO NOT try to rewrite/remux the captured media into a new MP4 with
  MediaMuxer. YouTube serves AV1/VP9 video + Opus audio. MediaMuxer's MP4
  output CANNOT mux these codecs (you get "Failed to add the track"). This
  caused every "no audio / not seekable / can't play" bug.
- PLAY the captured bytes with MSE in the same WebView that captured them.
  The WebView already decoded them (that's how they were captured), so MSE
  is guaranteed to handle the codecs. This is the ONLY reliable path.
- The capture is "tee at SourceBuffer.appendBuffer": whenever the player hands
  bytes to a SourceBuffer, we save a copy.
- Metadata MUST be captured at the same moment as the media (from the page),
  not fetched later. "Offline stays offline."

================================================================================
1. ARCHITECTURE
================================================================================

Android (Kotlin), minSdk 26, targetSdk 34.

- MainActivity hosts a WebView pointed at https://m.youtube.com.
- A JS hook (assets/hooks/v1.js) is injected into the page. It:
    * wraps MediaSource.prototype.addSourceBuffer -> captures mime + assigns a
      stream key (s{N}-video / s{N}-audio by kind, count N per page)
    * wraps SourceBuffer.prototype.appendBuffer -> sends a copy of every fragment
      to native
    * reads video metadata (title/author/duration/description/thumbnail URL)
      from the page and sends it to native
- Native stores:
    * media:  getExternalFilesDir("YTCache")/<videoId>/<streamKey>/<seq>.bin
      (0.bin = init segment, then moof+mdat fragments)
    * metadata: <videoId>/video.json + <videoId>/thumbnail.jpg
    * per-stream mime: <streamKey>/meta.json
- Library UI:
    * home.html = grid of saved videos (thumbnail, title, author, date), newest
      first, with delete
    * shorts.html = full-screen vertical-swipe MSE player for all videos
- Bridge: addJavascriptInterface (base64 POST) as the reliable transport.
  WebMessageListener(ArrayBuffer) is a faster alternative but must be wired
  exactly (see pitfall #3).

================================================================================
2. FILE LAYOUT
================================================================================

storage root = getExternalFilesDir("YTCache")   (=> /storage/emulated/0/Android/data/<pkg>/files/YTCache)

<videoId>/
  video.json            { "videoId","title","author","lengthSeconds","description","thumbUrl" }
  thumbnail.jpg         JPEG
  <streamKey>/          e.g. "s0-video", "s1-audio"
    meta.json            { "videoId","streamKey","kind","mime" }
    0.bin               init segment (ftyp+moov or EBML header)
    1.bin,2.bin,...     fragments (moof+mdat)

NOTE: a single YouTube video is split across MANY SourceBuffers
(e.g. s3-video, s5-video, s7-video...) as the player switches quality/adapts.
Each has its own dir. To play, pick the DOMINANT (largest byte count) video
stream and the dominant audio stream.

================================================================================
3. THE JS HOOK (assets/hooks/v1.js) — the critical piece
================================================================================

Key requirements:
- IDEMPOTENT: guard with window.__ytcHookInstalled.
- FAIL-OPEN: never throw into the page; always call the original methods.
- Do NOT pass a second argument to postMessage on WebMessageListener, and do
  NOT use the 'targetOrigin' arg: `window.YTCacheBridge.postMessage(arrayBuffer)`
  — passing a string like '*' throws "conversion failure from *" on Chrome,
  which we hit (spammed the log and forced base64 fallback).
- Credit: every append = [uint32 LE headerLen][JSON header][raw bytes].

Sending a fragment:
  1. Try base64 via @JavascriptInterface: window.YTCacheAndroid.postChunk(b64)
     This is the RELIABLE path. Use it as the default.
  2. Optionally the ArrayBuffer WebMessageListener path (faster) if you verify
     it on your device; keep base64 as fallback.

Reading metadata (at capture time):
  - Read from window.ytInitialPlayerResponse.videoDetails (title, author,
    lengthSeconds, thumbnail.thumbnails), microformat description.
  - Fallbacks: <meta property="og:title">, <meta name="title">, <meta
    name="author">, <meta name="duration"> (ISO-8601 PT1M23S), document.title
    (strip " - YouTube"), <meta property="og:image">.
  - Send once per page (window.__ytcInfoSent) via saveVideoInfo(json).
  - TRIGGER it when caching starts (when a SourceBuffer registers) AND on page
    load, so metadata is captured with the media.

================================================================================
4. PITFALLS WE HIT (each caused a real user-facing failure)
================================================================================

1. MediaMuxer can't mux AV1/VP9/Opus -> play with MSE instead (see #0).
2. The DOMINANT stream bug: picking the FIRST video stream played a tiny
   snippet and "ended early". Group and pick the largest by bytes.
3. WebMessageListener postMessage with a '*' origin arg -> "conversion failure
   from *" spam. Omit the arg entirely.
4. Massive single readStream JSON bridge call blows the @JavascriptInterface
   return-size limit on long videos (98+ fragments) -> returns "" -> player gets
   "No decodable streams". ALWAYS page: readStreamPage(videoId, streamKey,
   offset, count=8) and loop.
5. meta.json mime can lie (said webm/vp09 while init bytes were MP4/ftyp+AV1).
   Sniff the real container from the init bytes: first 4 bytes 1a45dfa3 =
   WebM/EBML, else MP4. Then pick a supported codec string per kind.
6. Failed remux/playback silently fell back to video-only -> no audio, no seek.
   Remove the fallback; play both tracks via MSE.
7. Double injection on document-start + onPageStarted fallback -> guard with
   __ytcHookInstalled.
8. Autoplay with sound is blocked in mobile browsers -> show a big Play button;
   start the MSE playback on that tap (video.play() only then).
9. The "no files in Downloads" report was expected: we never write to Downloads.
   Tell the user to look in app-specific external storage / the app UI.
10. Redroid/emulators won't validate this (kernel binder/codec gaps). Validation
    must be on a physical device; use the automated harnesses below meanwhile.

================================================================================
5. WIRE FORMAT & STORAGE DETAILS
================================================================================

Fragment message (per appendBuffer):
  [uint32 LE headerLength][UTF-8 JSON header][raw fragment bytes]
  header = { "v":1, "videoId", "streamKey", "kind", "mime", "seq",
             "isFirstAppend", "byteLength" }

Native write:
  - Always write <seq>.bin.tmp then atomic rename to <seq>.bin.
  - Write meta.json on the first chunk of a stream.

File writer safety:
  - Sanitize videoId/streamKey: keep [A-Za-z0-9._-], strip leading/trailing
    dots, collapse "..", never allow empty -> "unknown". (We had a path
    traversal via "..".)

================================================================================
6. PLAYER (shorts.html) — MUST-HAVE LOGIC
================================================================================

- Full-screen vertical scroll-snap feed; one page per saved video.
- For each video:
    1. Pick dominant pair: largest-by-bytes video stream + its matching audio
       (same numeric index s{N}) else largest audio.
    2. loadAll(): loop readStreamPage(offset+=8) to collect all base64 items.
    3. sniffMime(initBytes, kind): choose correct codec string (see pitfall #5).
    4. Create MediaSource; on sourceopen add a SourceBuffer per track (video,
       audio) with the sniffed mime; append init then fragments sequentially
       (chain on 'updateend'), then endOfStream when all appended.
    5. Start on Play button tap.
- On swipe to another page, PAUSE the previous page's video (we had a bug where
  it kept playing).
- Overlay: title, author, description, Copy URL, Export (best-effort), time +
  seek bar, mute, big play.

================================================================================
7. METADATA/OFFLINE REQUIREMENT
================================================================================

- Capture metadata AT cache time from the page (quiz #7). Never rely on network
  at view time.
- Do also keep an oEmbed backfill (https://www.youtube.com/oembed?format=json&url=...)
  for legacy captures, but it is NOT the primary path.
- Thumbnail: read from page or download from the URL at capture time; store
  thumbnail.jpg. Show a colored-letter placeholder if missing.

================================================================================
8. VALIDATION HARNESSES (run before handing to device)
================================================================================

A. test-hook.js: execute the real v1.js in Node with a mocked MSE + bridge;
   feed a real fMP4 (ffmpeg: `ffmpeg -f lavfi -i testsrc=64x64:rate=10 -t 1
   -c:v libx264 -movflags frag_keyframe+empty_moov+default_base_moof -frag_duration
   200000 selftest.mp4`). Assert byte-exact capture, SHA-256 of init+fragments,
   seq sequence, transports, subarray offsets, double-inject idempotence,
   player-exception propagation, videoId parsing. (~53 checks)
B. test-ui.js: run real home.html/shorts.html scripts in a mocked DOM+bridge;
   include a null-stream entry to reproduce the old crash; assert no throw, page
   count, placeholders. Feed readStreamPage mock. (~7 checks)
C. JVM tests: framing, atomic writer, path traversal, report hash, library
   listing/delete/paged read, muxer against real ffmpeg video+audio, and a
   CaptureStreamReadTest that reads a REAL captured directory layout.
D. Gradle: `./gradlew test assembleDebug` green for debug + release.

================================================================================
9. BUILD/ENV NOTES
================================================================================

- Gradle 8.7 / AGP 8.5.x / Kotlin 1.9.x; SDK 35; build-tools 35.
- Dependencies: androidx.core, androidx.webkit 1.11 (for WebViewCompat),
  Media3 (ExoPlayer) only if you use the native player (not needed for MSE UI),
  JUnit4.
- The Android SDK is frequently wiped between sessions in our environment;
  reinstall with sdkmanager before building. local.properties -> sdk.dir.
- Keep all capture/networking logic in pure files (no android imports) so it is
  JVM-testable.

================================================================================
10. UI/UX REQUIREMENTS (as the user asked)
================================================================================

- Library first: a YouTube-style GRID (2-column cards: thumbnail, title,
  author, duration, capture date). Then tapping opens the shorts player. Do NOT
  go straight to a player.
- Sort newest-first by capture date; allow deleting a capture (two-tap confirm).
- Shorts player: vertical swipe between videos (like YouTube Shorts), the
  previous video pauses on swipe.
- Show title, author, description, clickable copy URL.
- Export button must give feedback (Toast: "Exported to Movies/YTCache") and a
  button "Exporting…" state. Best-effort: if the device can mux AV1/Opus (API
  31+ WebM) export combined; else export the video alone (silent) — be honest.
- Everything offline once captured.

================================================================================
11. HONEST LIMITS (say these to the user)
================================================================================

- Real device is required for final media/codec validation; no emulator/Redroid
  can prove this (kernel/codec gaps).
- AV1/VP9/Opus decode support depends on the device's WebView/OS; MSE playback
  reuses the capture path so it matches whatever the device could already play.
- Export with audio is only possible if the device can transcode/mux those
  codecs; otherwise export is video-only.

================================================================================
12. MINIMAL REFERENCE IMPLEMENTATION (JS hook, self-contained)
================================================================================

(function () {
  'use strict';
  if (window.__ytcHookInstalled) return;
  window.__ytcHookInstalled = true;
  var TAG = 'YTCache:js';
  var BRIDGE = 'YTCacheBridge';      // optional ArrayBuffer WebMessageListener
  var ANDROID = 'YTCacheAndroid';     // base64 @JavascriptInterface (reliable)
  var sbInfo = new WeakMap();
  var streamCounter = 0;

  function log(m) { try { console.log(TAG + ' ' + m); } catch (e) {} }
  function utf8(s) { return new TextEncoder().encode(s); }
  function toB64(bytes, { var s = ''; for (var i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)); return btoa(s); }
  function androidAvail() { try { return window[ANDROID] && typeof window[ANDROID].postChunk === 'function'; } catch (e) { return false; } }

  function getVideoId() {
    try {
      var u = new URL(location.href), v = u.searchParams.get('v');
      if (v) return v;
      var m = u.pathname.match(/\/shorts\/([A-Za-z0-9_-]{5,})/);
      if (m) return m[1];
      if (u.hostname === 'youtu.be') { var p = u.pathname.replace(/^\//, ''); if (p) return p.split('/')[0]; }
      var pr = window.ytInitialPlayerResponse;
      if (pr && pr.videoDetails && pr.videoDetails.videoId) return pr.videoDetails.videoId;
      var meta = document.querySelector('meta[itemprop="videoId"]');
      if (meta && meta.content) return meta.content;
    } catch (e) {}
    return 'unknown';
  }
  function kindOf(mime) { return /^audio\//i.test(mime) ? 'audio' : /^video\//i.test(mime) ? 'video' : 'other'; }

  function register(sb, mime) {
    if (!sb || sbInfo.has(sb)) return;
    var kind = kindOf(mime);
    var key = 's' + (streamCounter++) + '-' + kind;
    sbInfo.set(sb, { mime: mime, streamKey: key, kind: kind, seq: 0 });
    log('addSourceBuffer streamKey=' + key + ' mime=' + mime);
    tryReportVideoInfo();   // capture metadata at cache time
  }
  function fallbackInfo(sb) {
    if (sbInfo.has(sb)) return sbInfo.get(sb);
    var info = { mime: 'unknown', streamKey: 's' + (streamCounter++) + '-unknown', kind: 'unknown', seq: 0 };
    sbInfo.set(sb, info); return info;
  }

  function send(meta, view) {
    var header = utf8(JSON.stringify(meta));
    var total = 4 + header.length + view.byteLength;
    var out = new ArrayBuffer(total), dv = new DataView(out);
    dv.setUint32(0, header.length, true);
    new Uint8Array(out, 4, header.length).set(header);
    new Uint8Array(out, 4 + header.length).set(view);
    try {
      if (androidAvail()) { window[ANDROID].postChunk(toB64(new Uint8Array(out))); return; }
      // Optional faster path (omit origin arg!):
      if (window[BRIDGE] && typeof window[BRIDGE].postMessage === 'function') { window[BRIDGE].postMessage(out); return; }
      log('no transport, dropping seq=' + meta.seq);
    } catch (e) { log('send failed ' + e); }
  }

  var origAdd = MediaSource.prototype.addSourceBuffer;
  MediaSource.prototype.addSourceBuffer = function (mime) {
    var sb = origAdd.apply(this, arguments);
    try { register(sb, String(mime)); } catch (e) {}
    return sb;
  };
  var origAppend = SourceBuffer.prototype.appendBuffer;
  SourceBuffer.prototype.appendBuffer = function (data) {
    try {
      if (window.__ytcCaptureEnabled === false) return origAppend.apply(this, arguments);
      var info = fallbackInfo(this), view;
      if (data instanceof ArrayBuffer) view = new Uint8Array(data);
      else if (ArrayBuffer.isView(data)) view = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
      if (view) {
        var seq = info.seq++;
        send({ v: 1, videoId: getVideoId(), mime: info.mime, streamKey: info.streamKey,
               kind: info.kind, seq: seq, isFirstAppend: seq === 0,
               byteLength: view.byteLength }, view);
      }
    } catch (e) {}
    return origAppend.apply(this, arguments);
  };
  log('hook v1 installed');

  function metaC(p) { try { var e = document.querySelector('meta[property="' + p + '"]'); return (e && e.content) ? e.content : ''; } catch (e) { return ''; } }
  function metaN(n) { try { var e = document.querySelector('meta[name="' + n + '"]'); return (e && e.content) ? e.content : ''; } catch (e) { return ''; } }
  var ISO_DUR = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/;
  function iso(s) { var m = ISO_DUR.exec(s || ''); return m ? ((+m[1]||0)*3600)+((+m[2]||0)*60)+(+m[3]||0) : 0; }
  function titleFromDoc() { try { return (document.title || '').replace(/\s*[-–—|]\s*YouTube$/, '').trim(); } catch (e) { return ''; } }

  function tryReportVideoInfo() {
    try {
      if (window.__ytcInfoSent) return;
      var pr = window.ytInitialPlayerResponse, vd = pr && pr.videoDetails ? pr.videoDetails : null;
      var videoId = vd && vd.videoId ? vd.videoId : getVideoId();
      if (!videoId || videoId === 'unknown') return;
      var title = (vd && vd.title) || metaC('og:title') || metaN('title') || titleFromDoc();
      if (!title || title === 'Untitled') return;
      var author = (vd && vd.author) || metaN('author') || '';
      var len = (vd && vd.lengthSeconds) ? vd.lengthSeconds : iso(metaN('duration') || metaC('video:duration'));
      var thumbUrl = '';
      if (vd && vd.thumbnail && vd.thumbnail.thumbnails) { var t = vd.thumbnail.thumbnails; for (var i = 0; i < t.length; i++) if (t[i].url) thumbUrl = t[i].url; }
      if (!thumbUrl) thumbUrl = metaC('og:image');
      var desc = (pr && pr.microformat && pr.microformat.playerMicroformatRenderer) ? pr.microformat.playerMicroformatRenderer.description || '' : '';
      if (!desc) desc = metaC('og:description');
      window.__ytcInfoSent = true;
      if (androidAvail()) {
        window[ANDROID].saveVideoInfo(JSON.stringify({ v: 1, videoId: videoId, title: title, author: author, lengthSeconds: len, description: desc, thumbUrl: thumbUrl }));
        log('video info saved ' + videoId + ' "' + title + '"');
      }
    } catch (e) { log('video info error ' + e); }
  }
  (function poll(a) { tryReportVideoInfo(); if (!window.__ytcInfoSent && a < 40) setTimeout(function () { poll(a + 1); }, 500); })(0);
})();
