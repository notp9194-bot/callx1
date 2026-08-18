# v251 — Reels Editor: Filmstrip Trim UI

## What changed
Replaced the reels edit screen's Step 1 (Trim) dual-SeekBar UI with a
CapCut/Instagram-style filmstrip trim control, matching the reference
screenshot.

## New file
- `feature-reels/src/main/java/com/callx/app/views/VideoTrimFilmstripView.java`
  - Custom `View` that:
    - Extracts video frame thumbnails in the background (`MediaMetadataRetriever`)
      and tiles them across a rounded strip.
    - Dims the portions outside the selected trim range.
    - Draws two white draggable pill handles (with a grip glyph) at the
      trim boundaries, connected by thin white top/bottom bars.
    - Draws a blue playhead line that tracks live playback position.
    - Exposes `OnTrimChangeListener` (`onTrimChanged`, `onTrimTouchEnd`).

## Changed files
- `feature-reels/src/main/res/layout/activity_reel_editor.xml`
  - Removed `sb_editor_trim_start` / `sb_editor_trim_end` SeekBars.
  - Added `trim_filmstrip_view` (`VideoTrimFilmstripView`) below a
    start/end/duration label row.
- `feature-reels/src/main/java/com/callx/app/editor/ReelEditorActivity.java`
  - Swapped `SeekBar sbTrimStart, sbTrimEnd` fields for
    `VideoTrimFilmstripView trimFilmstripView`.
  - `loadMetadata()` now calls `setDuration()`, `setTrimRange()`, and
    `loadThumbnails()` on the filmstrip view instead of configuring SeekBars.
  - `setupListeners()` now wires `OnTrimChangeListener` instead of two
    `OnSeekBarChangeListener`s.
  - Added a lightweight 150ms handler poll (`playheadUpdater`, started in
    `setupPlayer()`, cleared in the existing `onDestroy()`) that feeds
    `player.getCurrentPosition()` into `setPlayheadPosition()` so the blue
    line moves with playback.

## Behavior preserved
- `trimStartMs` / `trimEndMs` fields and downstream usage
  (`ReelUploadActivity.EXTRA_TRIM_START/END`) are unchanged.
- Minimum trim window is still 1 second.
