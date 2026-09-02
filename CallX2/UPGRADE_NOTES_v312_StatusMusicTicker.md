# v312 — StatusViewerActivity: below-name audio ticker for music stickers

## The feature

When a status has a music sticker attached (via `StatusStickerPickerSheet`'s
🎵 Music option, or trending-audio picked from the camera and auto-attached
by `NewStatusActivity#attachMusicStickerIfAny`), the viewer now shows a
scrolling audio-name row directly below the owner's name in the header —
same spot/style as the reel player's bio song row (left side, under the
name).

**No duplicate ticker was written.** This reuses `com.callx.app.views.MusicTickerView`
(already in `core`, already the exact component the reel player's
`ll_bio_song_row` / `tv_bio_song_name` uses) as-is — same continuous
seamless-loop scroll animation, same static-if-it-fits behavior, same
glyph-bitmap-cache perf work already done for reels.

## What changed

- `activity_status_viewer.xml`: new `ll_status_song_row` (music-note icon +
  `MusicTickerView` as `tv_status_song_name`) inserted directly below the
  name row and above the timestamp row, inside the existing header
  LinearLayout. `visibility="gone"` by default.
- `StatusViewerActivity.java`:
  - `hideAllContent()` (already called on every status change) now also
    calls new `hideStatusSongTicker()` — hides the row and pauses the
    ticker, so it doesn't keep animating/showing stale text on a status
    with no music sticker.
  - `renderStickers()`'s existing `"music".equals(sticker.getStickerType())`
    branch now also calls new `showStatusSongTicker(sticker)`, which reads
    `sticker.getMusicSong()` / `getMusicArtist()` (both already exposed by
    `StatusStickerOverlayView`, core module) and formats "Song · Artist"
    the same way `ReelUiController#buildMusicDisplay()` does, then
    `setText()` + shows the row + `resume()`s the ticker.
  - `onDestroy()` now calls `tvStatusSongName.release()` to free the
    cached glyph bitmap, mirroring `ReelUiController`'s teardown of the
    same view type.
- No changes to `MusicTickerView.java` itself, no new drawables (reused
  `@drawable/ic_music_note`, already present in `core`), no gradle changes
  (`feature-status` already depends on `:core`).

Files touched: `activity_status_viewer.xml`, `StatusViewerActivity.java`.
