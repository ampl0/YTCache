# YTCache documentation

Everything needed to understand, use, rebuild or extend YTCache. If you only read one page,
read [ARCHITECTURE.md](ARCHITECTURE.md); if you are an AI agent asked to reproduce the app,
start at [AI-AGENT-GUIDE.md](AI-AGENT-GUIDE.md).

| Document | Audience | Contents |
|---|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | developers | Component map, capture protocol, on-disk format, playback timeline, export planner, threading, failure modes |
| [IDEA-AND-PROCESS.md](IDEA-AND-PROCESS.md) | everyone curious | The idea, the dead ends, the design decisions, the release history as a narrative |
| [AI-AGENT-GUIDE.md](AI-AGENT-GUIDE.md) | AI agents / new contributors | Reproduction blueprint: environment, phases, invariants, pitfalls table, acceptance checklist, extension points |
| [BUILD.md](BUILD.md) | builders | Toolchain setup, Gradle commands, tests, signing, APK verification, environment troubleshooting |
| [USAGE.md](USAGE.md) | users | Installation, the capture workflow, player gestures, export, limits, FAQ, bug-report recipe |
| [CHANGELOG.md](CHANGELOG.md) | users / maintainers | Version-by-version changes |
| [../YTCache_HANDOFF.md](../YTCache_HANDOFF.md) | AI agents | The original handoff brief the codebase was built from — still accurate on the fundamentals |

## The 60-second version

- The app embeds `m.youtube.com` and injects a document-start script.
- The script tees every `SourceBuffer.appendBuffer` fragment and sends it to Kotlin as base64.
- Fragments are stored as `<videoId>/<streamKey>/<seq>.bin`, with the init segment at `0.bin`.
- The offline player reads those files through a `WebViewAssetLoader` and appends them into two
  `MediaBuffer`s (video, audio). This is why every codec the phone can decode offline-plays.
- Export is a *remux* (copy samples with `MediaMuxer`), never a transcode, and refuses politely
  when the codec pair has no legal container.
- The seek bar uses the video's real duration; the cached window is drawn inside it.

## Conventions in this repository

- Pure logic (no `android.*` imports) lives in `data/` and `assets/player/ytc-core.js` so it can
  be unit tested on the JVM or in Node.
- Never transcode. If a codec pair cannot be muxed, say so in the UI.
- The capture hook must be fail-open: any exception is swallowed and the original player call
  still runs.
- Nothing writes outside app-specific storage except an explicit user **Export**.
