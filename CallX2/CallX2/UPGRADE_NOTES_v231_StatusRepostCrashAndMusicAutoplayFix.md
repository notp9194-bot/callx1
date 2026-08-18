# v231 — Status Repost Crash Fix + Music Sticker Autoplay Fix

## Bug 1: Screen crash on opening a reposted status ("silent crash")

**Root cause (two compounding bugs):**

1. `StatusViewerActivity.showCurrent()` — for any reshare type
   (`status_reshare`/`reel_reshare`/`post_reshare`/`channel_post_reshare`),
   the code assumed "mediaUrl present → must be a video" and unconditionally
   called `showVideoStatus()` (ExoPlayer). A reshare's own `type` field never
   actually tells you whether the underlying media is a photo or a video —
   so reposting an **image** status handed an image URL to ExoPlayer, which
   errored out and left the viewer hung/broken.

2. `load()` — `c.getValue(StatusItem.class)` (Firebase POJO deserialization)
   was not wrapped in try/catch. A single malformed/unexpected-shape child
   (more likely on reshare rows, which carry many extra fields) could throw
   an uncaught exception straight out of the `onDataChange` callback,
   crashing the activity before any item was even shown.

**Fix:**

- Added `StatusItem.resharedMediaType` — set by `StoryReshareActivity` at
  repost time from the real source media type (`"video"`/`"image"`).
- `StatusViewerActivity` now branches on `resharedMediaType` to pick the
  correct renderer (Glide for image, ExoPlayer for video). Old reshare rows
  saved before this field existed fall back to a file-extension guess
  (`looksLikeVideoUrl()`) instead of assuming video.
- `load()`'s per-child parse is now wrapped in try/catch — a bad child is
  logged + reported and skipped, never crashes the whole list load.
- `showVideoStatus()` now has an `onPlayerError` listener (previously
  unhandled) — a real playback failure is logged and skips to `next()`
  instead of leaving the viewer stuck on a frozen frame.

## Bug 2: Music sticker never autoplays on status open (works only on tap)

**Root cause:** autoplay resolution only checked the `sounds/{soundId}`
Firebase node. Tracks picked from Trending Audio's **Music tab** live under
`musicLibrary/{soundId}` instead — which is exactly why tapping the sticker
(opens the full Sound Detail sheet, which already has this two-step lookup)
played fine while autoplay stayed silent.

**Fix:** added `resolveFromMusicLibrary()` fallback, mirroring
`SoundDetailFragment#loadSoundDataFromMusicLibrary()` — checked in the same
order (`previewAudioUrl` → `audioUrl`) whenever `sounds/{soundId}` doesn't
exist or has no audio field.

## Debugging

Every decision point above now logs under tag **`StatusViewerDbg`**.
Run `adb logcat -s StatusViewerDbg` while reproducing either issue to see
exactly which status/sticker/URL was processed and where it failed.

## Files changed

- `core/src/main/java/com/callx/app/models/StatusItem.java`
- `feature-status/src/main/java/com/callx/app/social/StoryReshareActivity.java`
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
