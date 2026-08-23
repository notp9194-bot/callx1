# v267 — Reel Edit screen, Step 3 → Filters: preview wasn't loading

## Bug
Tapping "Filters" in Step 3 of the Reel/Status editor opened the Filters &
Adjust screen with a blank preview — no video frame or photo showed up.

## Root cause
`ReelEditorActivity` launched `ReelFiltersActivity` by passing the raw video
URI/path (`videoUriStr`) as `EXTRA_THUMBNAIL_URI`. `ReelFiltersActivity`
loads that extra straight into an `ImageView` via `setImageURI()` — which can
only decode still images, never a video file. The call failed silently
(wrapped in a `catch (Exception ignored)`), so the preview `ImageView` just
stayed empty.

## Fix — `ReelEditorActivity.java`
- New `openFiltersScreen()` replaces the old inline click listener. It grabs
  a real frame off the video at the current playhead position using
  `MediaMetadataRetriever` (same pattern already used by
  `ReelThumbnailPickerActivity`), on a background thread
  (`filterPreviewExecutor`, a single-thread `ExecutorService`) so large-video
  frame decoding never blocks the UI thread.
- The extracted frame is saved as a temp JPEG under `getCacheDir()/filter_preview/`
  and exposed via the app's existing `com.callx.app.fileprovider` FileProvider
  (already covers the whole cache dir — no manifest/paths changes needed), then
  passed to `ReelFiltersActivity` as a proper `content://` URI with
  `FLAG_GRANT_READ_URI_PERMISSION`.
- If the playhead frame fails to decode, falls back to the first frame; if
  extraction fails entirely, the screen still opens (so filter/slider values
  remain usable) with a toast instead of silently showing nothing.
- Bonus fix: the already-applied filter name + brightness/contrast/saturation/
  beauty values are now forwarded via `ReelFiltersActivity`'s
  `EXTRA_CURRENT_*` extras (these existed on the receiving side already but
  were never actually sent) — reopening Filters after applying one now
  resumes from the current values instead of resetting to "Normal".
- `filterPreviewExecutor` is shut down in `onDestroy()`.

## Behavior
Filters & Adjust screen now always shows a real frame from the reel/status
video being edited, and correctly remembers the currently-applied filter.
