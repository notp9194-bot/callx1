# v255 — Reel Upload: Photo Mode No Longer Leaves Video-Pick Area Clickable

## Bug
On the Reel Upload screen, after selecting photos and tapping in the area
just below them, the tap was opening the **video picker** instead of doing
anything photo-related — and visually, remnants of the video-upload card
still showed while in Photo mode.

## Root cause
`switchToPhotoMode()` only hid the inner **"Tap to select video" hint**
(`layoutPickVideo`) and the ExoPlayer view (`playerPreview`). It never hid
`iv_thumb_preview` — a `match_parent`-sized `ImageView` stacked in the same
220dp video card, with its own click listener:

```java
ivThumbPreview.setOnClickListener(v -> checkPermissionAndPickVideo());
```

Since `layoutPickVideo` was `GONE` (no longer intercepting touches) but
`ivThumbPreview` stayed `VISIBLE` and full-size underneath it, it became the
topmost touchable layer in that spot — so the (now-empty, black) video card
kept sitting there in Photo mode, and tapping it fired the video picker.

## Fix
- `activity_reel_upload.xml`: gave the whole video-preview `CardView` an id
  — `card_video_preview` — instead of only ever toggling its children.
- `ReelUploadActivity.java`:
  - `switchToPhotoMode()` now hides `cardVideoPreview` (the entire card),
    so nothing from the video area — visible or invisible-but-clickable —
    remains on screen in Photo mode.
  - `switchToVideoMode()` now shows `cardVideoPreview` again when switching
    back.

## Result
Photo mode now only shows the photo slideshow card. No leftover video-pick
touch target underneath it, so taps below the photo thumbnails go where
expected.
