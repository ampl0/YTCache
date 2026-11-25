# The idea, and how this code came to exist

A short engineering history: what was attempted, what failed, and why the final design looks the way
it does. Useful if you want to change something without re-learning the same lessons.

## The problem

Watching a YouTube video on a phone in a tunnel, on a plane, or on a metered connection is
unreliable, and the official app's downloads are limited to regions and plans. The obvious idea —
"download the video file" — leads straight into signature ciphers, throttled URL expiry, segmented
DASH manifests, and a cat-and-mouse game that breaks every few weeks.

YTCache takes a different route: **if the phone is already playing the video, the phone already has
the bytes.** Keep a copy of them and replay them.

## The insight that shaped everything

The YouTube web player feeds media into a WebView through Media Source Extensions:

```
network segment → player → SourceBuffer.appendBuffer(bytes) → decoder → screen
```

That `appendBuffer` call is the one place where a complete, decodable, correctly-timed fragment
exists in memory, in the codec the device can actually play. Intercept it, and:

- there is nothing to reverse-engineer — it is the same data the screen is showing;
- timing, container and codecs are already correct;
- replay is guaranteed to work on that device, because it already worked once;
- it is a *capture*, not a bypass: no DRM, no paywall, no URL extraction.

So the app is a tee on a pipe that already exists.

## The wrong turn: remuxing to a file first

The first implementation captured fragments and then tried to build one MP4 with `MediaMuxer`.
That produced the classic bug reports:

- *"Failed to add the track"* — `MediaMuxer` cannot put AV1/VP9 video with Opus audio into MP4;
- video playing with no audio;
- files that would not seek;
- export that silently fell back to a silent video.

Days went into "fixing" the muxer before the conclusion became unavoidable: **the captured codec
pair has no MP4-compatible home, and no amount of muxer code changes that.** The fix was to stop
muxing for playback and play the fragments with MSE in the same WebView that captured them. The
remuxer survived only as an *export* feature, restricted to codec pairs that are legal for the
target container (MP4: H.264/H.265/AV1 + AAC; WebM: VP8/VP9/AV1 + Opus/Vorbis).

That decision is the spine of the codebase and is restated at the top of
[AI-AGENT-GUIDE.md](AI-AGENT-GUIDE.md) so no future contributor repeats it.

## Timeline of the build

### v0.x — proof of capture
- WebView + document-start script wrapping `addSourceBuffer`/`appendBuffer`.
- base64 `@JavascriptInterface` transport (the `ArrayBuffer` `WebMessageListener` path kept throwing
  `conversion failure from *`, so base64 became the default).
- Fragments written as `0.bin, 1.bin, …` per stream directory.

First successes, first confusions: a single video produced *many* stream directories
(`s3-video`, `s5-video`, `s7-video`, …) as the player adapted quality. Picking the first one played
four seconds and stopped. The fix — rank streams by bytes and match audio by numeric index — is now
`ytc-core.js`'s `pickStreams`, covered by unit tests.

### v1.0 — a real product
- Library grid with thumbnails, search, sort, multi-select, delete.
- Shorts-style player with vertical paging; previous page pauses.
- Metadata captured at cache time from `ytInitialPlayerResponse`, with meta-tag fallbacks and an
  ISO-8601 duration parser.
- Container sniffing from the init segment, because the page's declared mime lied often enough to
  break playback.
- Atomic writes and id sanitisation after a path-traversal near-miss.

### v1.1 — correctness and export
- Export remuxer with a planner that refuses illegal pairs instead of producing silent files.
- Audio-only export.
- Paged fragment reads (`readStreamPage`), after one large JSON read exceeded the
  `@JavascriptInterface` return limit on long videos and the player reported "no decodable streams".
- `partial` and `playable` flags so the UI can be honest about incomplete captures.
- Long-press batch export/delete; tap-for-sound gate; toggleable record animation.
- 1.1.1: fixed a crash in the grid adapter's ripple handler.

### v1.2 — the player starts telling the truth
Four complaints drove this release, all about the player's relationship with time:

1. **"Seek to cached parts with the real duration."** The bar had used `MediaSource.duration`, so a
   two-minute capture of a 45-minute video looked like a two-minute video. Now `durationOf()` takes
   the max of metadata length, MSE duration and captured end, and `bufferedRange()` draws the cached
   window as a band on that real timeline. `seekTo()` clamps into the window and explains; the chip
   reads `Cached 12:34–24:10 / 45:00`.
2. **"It should start where the data is."** A mid-video capture kept its original timestamps and sat
   at 0:00 forever. `alignPosition()` now jumps to the saved resume position if it is inside the
   window, otherwise to the first cached frame, and says which.
3. **"Titles and info should fade like YouTube."** Chrome hides after 2.6 s of playback, returns on
   tap/pause/seek. Also fixed two event listeners that were passing the `Event` where a page object
   was expected.
4. **"The back button closes the app on YouTube."** YouTube's mobile site is an SPA: most navigation
   is `pushState`, so `WebView.canGoBack()` stays false. `hooks/back.js` counts `pushState` depth at
   document start and `MainActivity` asks the page first, then native history, then arms
   press-again-to-exit.

Plus last-watched restore and a tap ripple on the player surface.

## Design decisions worth keeping

| Decision | Reason |
|---|---|
| WebView + MSE, not yt-dlp/NewPipe-style extraction | No signature/URL maintenance, no server, no root; reuses the exact decoders the device has |
| base64 bridge instead of ArrayBuffer messages | It is the transport Chrome never drops; correctness beat throughput after the "dead buttons" bug |
| Background queue for writes | Decoding and writing inside the bridge froze the renderer |
| Sniff instead of trust the page's mime | Real captures routinely disagreed with their declared mime |
| Real-duration timeline with a cached band | It is honest: the user sees what exists and what does not |
| Remux for export only, never for playback | Playback stays codec-agnostic; export stays lossless |
| Metadata at capture time | The product promise is "offline stays offline" |
| No foreground service (yet) | Keeps the scope small and the WebView lifecycle simple; documented as a limit |

## What was measured, and what was not

- 57 JVM tests, 19 core JS checks, 19 hook JS checks — all green on the released source.
- Signature, badging and checksums verified for every published APK.
- **Not verified on physical hardware in this build environment.** Codec behaviour, chrome fades,
  gesture feel and the back gesture follow documented platform semantics but are not device-proven.
  That honesty matters more than a green check mark; if you own an Android phone, your bug report is
  the missing test.
