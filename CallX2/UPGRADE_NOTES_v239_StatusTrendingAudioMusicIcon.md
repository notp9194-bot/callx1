# v239 — Status Upload: Trending Audio Music Icon

## What was added

When a user picks a photo or video for their status, a **music icon button** now
appears in a semi-transparent toolbar at the **top of the media preview**.
Tapping it opens the Reels **Trending Audio screen** (`ReelTrendingAudioActivity`)
— the same screen already used in the Reels / video camera flow — so the user can
browse trending songs by genre, tab (Trending / Viral / New / Saved), and preview
them inline before selecting.

Once a song is selected:
- It is attached to the status as a **🎵 Music sticker** (same JSON format already
  used by `StatusStickerPickerSheet`'s "Music" option), placed on the media preview
  and draggable/resizable like any other sticker.
- A **music badge bar** appears at the **bottom** of the preview, showing the song
  title and artist so the composer can see the selection at a glance.
- The badge has an **×** button to remove the song without discarding the media.
- If the user opens Trending Audio a second time and picks a different song, the
  old sticker and badge are automatically replaced.
- When the user taps "Discard media" (×) the music sticker and badge are also
  cleared.

The existing `attachMusicStickerIfAny()` path (camera hand-off from
`ReelCameraActivity`) now also tracks `musicStickerView`/`pendingMusicStickerJson`
so that a song picked in the camera and then changed via the new toolbar icon is
replaced cleanly rather than stacked.

## Files changed

### `feature-status/src/main/res/layout/activity_new_status.xml`
- Added `@id/media_edit_toolbar` — a `LinearLayout` overlay at
  `layout_gravity="top"` inside `media_preview_frame`, containing
  `@id/btn_music_edit` (the music-note icon button).  
  Hidden (`gone`) until photo/video is shown.
- Added `@id/music_badge_bar` — a `LinearLayout` overlay at
  `layout_gravity="bottom"` inside `media_preview_frame`, containing
  `@id/iv_music_badge_icon`, `@id/tv_music_badge_title`, and
  `@id/btn_music_badge_remove`.  
  Hidden (`gone`) until a song is selected.

### `feature-status/src/main/java/com/callx/app/compose/NewStatusActivity.java`
- Added `trendingAudioLauncher` (`ActivityResultLauncher<Intent>`) field.
- Added `pendingMusicStickerJson` (String) field.
- Added `musicStickerView` (`StatusStickerOverlayView`) field.
- `onCreate`: registers `trendingAudioLauncher` and calls `setupMusicEditBar()`.
- New `setupMusicEditBar()` — hooks up `btn_music_edit` → `openTrendingAudioForStatus()`
  and `btn_music_badge_remove` → `removeMusicSticker()`.
- New `openTrendingAudioForStatus()` — launches `ReelTrendingAudioActivity` via
  class-name Intent (no compile-time dependency on feature-reels).
- New `handleTrendingAudioResult()` — reads `audio_title / audio_artist / audio_url
  / audio_id / audio_cover_url / audio_preview_url` from the result Intent, builds
  the music-sticker JSON, replaces any previous music sticker, calls `addStickerOverlay()`,
  then calls `showMusicBadge()`.
- New `showMusicBadge(title, artist)` — makes `music_badge_bar` visible and sets label.
- New `removeMusicSticker()` — removes sticker view + JSON + hides badge bar.
- `showImagePreview()` / `showVideoPreview()` — now also show `media_edit_toolbar`.
- `discardMedia()` — now also hides `media_edit_toolbar` + `music_badge_bar` and
  clears pending music state.
- `attachMusicStickerIfAny()` (existing — camera hand-off path) — now also
  replaces any previously attached music sticker, tracks `musicStickerView`, and
  calls `showMusicBadge()` for a consistent UX.

## No new dependencies
Both activities already exist in the project.  The launch is via class-name Intent
so feature-status still does not depend on feature-reels at compile time.
