# Using YTCache

## Install

1. Download `YTCache-<version>-release.apk` from
   [Releases](https://github.com/ampl0/YTCache/releases/latest).
2. Open the file on the phone and allow install from that source when Android asks.
3. Or install over USB: `adb install -r YTCache-<version>-release.apk`.

Requirements: Android 8.0+ (API 26), an up-to-date Android System WebView, and a device that can
decode the videos you watch online (see [Limits](#limits)).

## Capture a video

1. The app opens `m.youtube.com`. Start the video you want.
2. Tap **Cache** in the top bar. It turns red and the pill appears:
   `Some video title · 2 streams · 12.4 MB`.
   Caching begins at that moment — anything the player fetched before you tapped is not captured.
3. Let it play (or pause and let it buffer) for as long as you want to keep. Progress is shown
   live; seek within the video to capture other parts, if you like.
4. Tap **Stop**. **Saved · title** appears, with a shortcut to the library.

> **Best results:** open the video after arming Cache. A capture that starts mid-stream has no
> initialisation segment, and MSE cannot play it — the player will say so rather than show a broken
> file.

The pill can be hidden in **Settings** (top-right). Two toggles live there: the floating capture
status, and whether offline playback uses sound by default.

## The library

- Two-column grid, newest first, with thumbnail, title, channel, duration, date and size.
- **Search** matches title and channel; **sort** by newest, oldest, largest or A–Z.
- **Tap** a card to open the player.
- **Long-press** to start a selection: batch **Export** or batch **Delete** from the toolbar.
- **Delete** asks for confirmation and removes the media and metadata from this device only.
- **PARTIAL** badge: the capture is missing fragments or was stopped early. It still plays what it
  has.

## The player

- Full screen, one video per screen: swipe up for the next, down for the previous.
- **Tap** toggles the chrome (title, channel, actions, controls). While playing it fades away after
  about 2.6 s, like YouTube; pause or seek to bring it back.
- **Seek bar** spans the video's real length. The darker/grey band is the part cached on the device.
  - Tapping inside the band seeks there.
  - Tapping outside snaps to the nearest cached frame and shows
    `Cached 12:34–24:10 of 45:00`.
  - The chip above the bar shows the cached window whenever it is shorter than the video.
- **Resume:** your position is remembered per video. Coming back shows *Resumed at mm:ss*; reopening
  the player returns to the video you were last watching.
- **Sound:** the first tap may be needed to start playback with sound (browser autoplay rules). The
  speaker button mutes/unmutes; the choice is remembered.
- **Export** (in the action rail): *Export with sound* or *Export audio only*.
- **Copy link** copies the original YouTube URL.
- **Back** first closes the chrome/fullscreen, then goes back through YouTube's own history, and
  only exits the app after a second *Press back again to exit*.

### Exported files

Exports go to the public media collection:

- video: `Movies/YTCache/<title>.mp4` or `.webm`
- audio: `Music/YTCache/<title>.m4a`, `.opus` or `.ogg`

They appear in gallery/music apps immediately. Export is a container remux — no frame is
re-encoded, so it is lossless and fast.

## Limits

| Limit | Why |
|---|---|
| Only what plays is captured | The app records the player's own buffers; there is no separate download stream |
| Mid-stream captures may be unplayable | No init segment means MSE cannot start |
| No background downloading | No foreground service; capture lives in the browse screen |
| Quality is playback quality | Whatever YouTube chose for the current session |
| Device codecs only | Playback reuses the WebView's decoders |
| Silent export possible | When the codec pair fits neither MP4 nor WebM, only video is exported — with a reason |
| No DRM / private content | Those bytes never reach the capture point |

## FAQ

**Where do captures live?**
`Android/data/com.ytcache.app/files/YTCache/<videoId>/`. That directory is app-private on modern
Android and is deleted when you uninstall. Use **Export** for files you want to keep.

**Why is my 40-minute video only 6 minutes on the bar?**
It isn't — the bar is 40 minutes. The grey band marks the 6 minutes cached. That is also why seeking
past the band snaps back.

**Can I cache without watching?**
You can pause and let it buffer for a while, but the app only stores what the player fetched. There
is no "download whole video" mode by design.

**The export has no sound.**
The video/audio pair cannot be muxed into MP4 or WebM on this device (for example, VP9 video with
AAC audio). The app tells you when that happens. Playback in the app still has sound.

**Nothing saves.**
Check the pill appears after tapping Cache, that the video is actually playing, and that storage is
free. `adb logcat -s YTCacheMain YTCachePlayer` shows bridge and storage messages in debug builds.

**Does it work offline on a plane?**
Library and player make no network requests. Thumbnails and metadata are stored at capture time.
Yes.

## Reporting a bug

Include:

- phone model and Android version, WebView version (`chrome://version` in a browser),
- the YouTube URL and whether it is a Short,
- what you expected vs what happened,
- `adb logcat -s YTCacheMain YTCachePlayer AndroidRuntime` around the failure,
- the app version from **Settings → About** / release name.

## Legal

Cache content you are allowed to keep. YTCache avoids DRM and paywalls entirely, but downloading
copyrighted video may still violate YouTube's Terms of Service and local law. You are responsible
for what you cache.
