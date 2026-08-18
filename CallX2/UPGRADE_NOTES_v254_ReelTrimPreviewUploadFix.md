# v254 — Reel Editor: Trim Preview Loop & Upload Now Match Selected Range

## Bug
On the Reels **Edit Reel** screen (`ReelEditorActivity`, Step 1 of 5 · Trim),
dragging the start/end trim handles only affected the numbers shown
(`0:03` / `0:12` etc.) — it did NOT affect actual behaviour:

1. **Preview loop was wrong.** The player was set to
   `Player.REPEAT_MODE_ONE`, which loops the *entire* source video, not the
   selected trim range. So trimming to a 5s middle section still previewed
   (and looped) the full original clip.
2. **Upload was wrong.** `ReelEditorActivity` sent `trimStartMs`/`trimEndMs`
   to `ReelUploadActivity` via `EXTRA_TRIM_START` / `EXTRA_TRIM_END`, but
   `ReelUploadActivity` never read those extras anywhere — they were declared
   and set, never consumed. The full, untrimmed original file was uploaded
   every time, regardless of what the trim handles showed.

## Fix

**Preview (`ReelEditorActivity.java`)**
- `setupPlayer()`: repeat mode changed from `REPEAT_MODE_ONE` to
  `REPEAT_MODE_OFF`.
- `playheadUpdater` (already polling every ~150ms to drive the filmstrip
  playhead) now also checks `pos >= trimEndMs` and seeks back to
  `trimStartMs` when true — so playback loops strictly within the
  user-selected range, live, as the handles are dragged.

**Upload (`ReelVideoExportEngine.java` + `ReelEditorActivity.java`)**
- `ReelVideoExportEngine.export(...)` gained a `trimStartMs, trimEndMs`
  overload that applies `MediaItem.ClippingConfiguration` to the Media3
  Transformer job, physically cutting the exported .mp4 to the requested
  range (old signature kept as a passthrough overload, so nothing else
  breaks).
- `ReelEditorActivity.proceedToUpload()` previously only ran the hard-bake
  export step (`runHardBakeExport()`) when a filter or overlay was active.
  It now also runs it whenever the trim range is non-default
  (`trimStartMs > 0 || trimEndMs < totalDurationMs`), so a trim-only edit
  (no filter, no stickers) now correctly re-encodes before upload.
- After a trim bake succeeds, `trimStartMs`/`trimEndMs`/`totalDurationMs`
  are reset to reflect the new (already-trimmed) file, so nothing downstream
  can re-apply the old offsets.

## Result
Preview loop and the uploaded video now always match exactly what the trim
handles show — move them and both the loop range and the final uploaded
video length change together.

## Note
Same as the existing filter/overlay bake step, this only runs when
`isFilePath` is true (local file). If a reel's source is a bare `content://`
Uri that was never copied to a local file, trimming (like filters) won't be
baked — this mirrors a pre-existing limitation, not something this fix
introduces.
