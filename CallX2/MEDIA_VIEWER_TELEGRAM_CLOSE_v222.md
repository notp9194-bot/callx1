# Media Viewer — Swipe-to-close (both directions) + Telegram-style return animation (v222)

## 1. Swipe UP and swipe DOWN both close the viewer

`core/utils/MediaSwipeReplyCloseHelper.java`
- Previously: swipe down → close, swipe up → "reply" (grouped-media only).
- Now: swipe up **or** down past the same 100dp threshold → close.
  `onSwipeUpReply()` is still declared on the `Callback` interface for
  backward compatibility but is never invoked anymore — it's fully
  retired in favor of "both directions close".

## 2. Telegram-style "shrink into the thumbnail" close/open animation

New: `core/utils/MediaViewerSourceRect.java`
- Carries the tapped chat-bubble thumbnail's **on-screen rect** through
  the `Intent` (`srcRectLeft/Top/Width/Height` extras) from wherever the
  viewer is opened, to `MediaViewerActivity`.
- `attach(Intent, View)` / `attach(Intent, Rect)` on the sending side,
  `read(Intent)` on the receiving side. All no-ops when the view isn't
  laid out yet (falls back to the old plain fade/close — never crashes).

`feature-chat/conversation/canvas/MessageBubbleCanvasView.java`
- Added `getMediaRectOnScreen()` — converts the canvas-drawn bubble's
  internal `mediaRect` into real screen coordinates, since chat bubbles
  are Canvas-drawn (no real ImageView to read bounds from otherwise).

`app/activities/MediaViewerActivity.java`
- Reads the rect via `MediaViewerSourceRect.read(getIntent())`.
- `closeViewer()` — now the single entry point for close-button tap,
  back-press, and swipe-to-close. If a source rect is available, calls
  `animateCloseToSource()`: the visible image/video/gallery page
  shrinks + translates until it exactly overlaps the original thumbnail
  spot (chrome and background scrim fade out in sync), *then* the
  activity finishes — so it visually "sticks" to the thumbnail instead
  of just cutting away.
  Falls back to instant `finish()` when no rect was supplied.
- `animateOpenFromSource()` — mirrors the same rect on entry: the
  content view starts scaled/positioned to look like the thumbnail and
  expands to full screen (background fades in from transparent).

**Wired at every place the viewer is opened:**
- Single-image bottom sheet ("View") — `MessagePagingAdapter`
  (`showImageActionSheet` / `showMediaActionSheet`, new `Rect srcRect`
  parameter, sourced from `MessageBubbleCanvasView.getMediaRectOnScreen()`)
- Single-video bottom sheet ("Play") — same path, video variant
- Direct video-bubble tap (`flVideo` / fallback `ivImage`) — both the
  immediate-open and "download-then-open" flows
- GIF tap (`onGifClick`)
- Legacy single-image `ivImage` click path
- Grouped-media grid cell tap — `MediaGroupLayoutHelper` (`cell` is a
  real `FrameLayout` here, so its rect is exact, not canvas-derived)

Grouped-media-**inside-a-single-bubble** cell taps (the `onMediaCellClick`
canvas path) don't yet have a precise per-cell rect — those still open
with the plain fade animation (`srcRect = null`, safe fallback) since
`MessageBubbleCanvasView` only tracks one overall `mediaRect`, not a
per-cell grid rect.
