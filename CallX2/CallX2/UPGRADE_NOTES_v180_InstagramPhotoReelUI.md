# v180 — Instagram-Level Photo Reel UI Upgrade

## What changed

A complete visual overhaul of the photo slideshow (photo_slideshow reel type)
to match Instagram's exact quality level for photo reels / carousels.

---

## 1. Story progress bar — `ll_story_progress`

| Before | After |
|--------|-------|
| 3dp tall flat grey bar | **4dp tall rounded-pill segments** |
| Plain `setBackgroundColor()` | `GradientDrawable` with 2dp corner radius |
| Sharp rectangular ends | Fully rounded caps (Instagram-identical) |
| 0x55FFFFFF track | Same opacity, now a proper rounded drawable |

**Files changed:**
- `feature-reels/.../ReelPhotoSlideshowController.java` → `buildStoryProgress()`
- `feature-reels/.../fragment_reel_player.xml` → `ll_story_progress` height + margins

**New drawables (reference only — drawn programmatically):**
- `bg_photo_progress_segment.xml` — rounded grey track
- `bg_photo_progress_fill.xml` — rounded white fill

---

## 2. Photo counter — `tv_photo_counter`

| Before | After |
|--------|-------|
| Top-right corner badge | **Top-center pill** (Instagram carousel style) |
| Sharp corners `#66000000` | Rounded pill `bg_photo_counter_pill` |
| `gravity="top|end"` | `gravity="top|center_horizontal"` |
| 12dp margin-top | 26dp margin-top (below status bar) |

**Files changed:**
- `fragment_reel_player.xml` → counter gravity, background, margins, font
- **New drawable:** `bg_photo_counter_pill.xml`

---

## 3. Dot page indicator — `ll_dot_indicator`

| Before | After |
|--------|-------|
| All dots = circle, scale trick for active | **Active = wide pill (20×8dp)** |
| Scale 1.4× for active, jarring resize | **Inactive = circle (8×8dp)** |
| Margin 4dp each side | Margin 3dp, tighter spacing |
| Same colour, just scaled | White pill vs semi-white circle |

This matches exactly how Instagram renders its carousel dot indicator.

**Files changed:**
- `ReelPhotoSlideshowController.java` → `buildDotIndicator()` + `updateDotIndicator()`

---

## 4. Caption overlay — `tv_caption_overlay`

| Before | After |
|--------|-------|
| Flat `#BB000000` no radius | **Rounded card** `bg_photo_caption_card` (12dp radius) |
| 13sp size | 14sp for better legibility |
| No `letterSpacing` | `0.01` letter spacing |
| Instant show/hide | **Slide-up + fade-in** with decelerate interpolator |
| translationY 0 on show | Starts 20dp below, springs up 280ms |

**Files changed:**
- `fragment_reel_player.xml` → caption margins, background, font, translationY
- `ReelPhotoSlideshowController.java` → `showCaptionForPhoto()` with spring animation
- **New drawable:** `bg_photo_caption_card.xml`

---

## 5. Per-slide caption in adapter

| Before | After |
|--------|-------|
| `CAPTION_ANIM_DURATION = 280L` | **300L** — slightly more luxurious |
| `DecelerateInterpolator()` (default) | `DecelerateInterpolator(1.6f)` — snappier deceleration |
| `bgColor = 0xBB000000` | **0xCC000000** — slightly more opaque for readability |
| No translationY reset on recycle | `setTranslationY(0f)` on recycle to prevent glitch |

**Files changed:**
- `feature-reels/.../ReelPhotoSlideshowAdapter.java` → `bindCaption()`, `onViewRecycled()`

---

## 6. Each photo slide — `item_reel_photo_slide.xml`

| Before | After |
|--------|-------|
| Single `gradient_reel_bottom` (120dp) | **New `gradient_photo_reel_bottom`** deeper 3-stop gradient |
| No top gradient on individual slide | **New `gradient_photo_reel_top`** (120dp, for story bar readability) |
| Caption had `#BB000000` inline | Now references `bg_photo_caption_card` for rounded corners |

---

## 7. Photo Style button — `btn_photo_style`

| Before | After |
|--------|-------|
| `bg_reel_follow_btn` (outline only) | **`bg_photo_style_btn_modern`** — dark glassmorphism pill |
| No border glow | Subtle `#66FFFFFF` border on dark background |
| 13sp | 12sp + `letterSpacing 0.02` |

---

## New files added

| File | Purpose |
|------|---------|
| `drawable/bg_photo_progress_segment.xml` | Story segment track (rounded pill) |
| `drawable/bg_photo_progress_fill.xml` | Story segment fill (rounded pill) |
| `drawable/gradient_photo_reel_bottom.xml` | Deep 3-stop bottom gradient |
| `drawable/gradient_photo_reel_top.xml` | Top scrim for story bar readability |
| `drawable/bg_photo_caption_card.xml` | Frosted rounded card for captions |
| `drawable/bg_photo_counter_pill.xml` | Counter pill badge |
| `drawable/bg_photo_style_btn_modern.xml` | Glassmorphism style button |
| `drawable/bg_dot_active.xml` | Active dot pill reference (for docs) |
| `drawable/bg_dot_inactive.xml` | Inactive dot circle reference (for docs) |

---

## No breaking changes

All view IDs are unchanged. All Java classes, models, and fragment/activity
references remain identical. Only visual output changes.
