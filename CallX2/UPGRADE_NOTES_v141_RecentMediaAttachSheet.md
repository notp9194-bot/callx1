# v141 — Recent-media attach sheet (WhatsApp-style)

## What changed
Attach sheet (`bottom_sheet_attach.xml` + `ChatMediaController`) now has two new
pieces on top of the existing icon grid:

1. **Recent-media strip** — horizontal row right under the drag handle:
   camera tile + last ~60 gallery photos/videos (`RecentMediaStripAdapter`).
   Tap a thumbnail → sends it straight away (same upload path as the Gallery
   picker). Tap camera tile → opens camera.

2. **Expandable "Recents" grid** — 4-column grid (`RecentMediaGridAdapter`)
   sitting below the icon rows / camera row, hidden below the peek line.
   Dragging the sheet up past the collapsed content reveals it — same
   physics as WhatsApp's attach sheet.

## How the collapsed/expanded split works
- `top_content` wraps drag handle + strip + icon grid + camera row.
- On first layout, its measured height is set as `BottomSheetBehavior.peekHeight`
  (via a one-shot `ViewTreeObserver` listener), so the sheet opens compact.
- The Recents grid lives *after* `top_content` in the same LinearLayout, so
  it's simply clipped below the peek line until the user drags up —
  `BottomSheetBehavior` handles the nested-scroll handoff into the grid's
  `RecyclerView` automatically once expanded.

## MediaStore query
- `RecentMediaLoader.loadRecent()` — single query against
  `MediaStore.Files` (images ∪ video, sorted by `DATE_ADDED DESC`), run on a
  background executor. Same result list feeds BOTH the strip and the grid —
  one disk hit per sheet-open, not two.
- Gated on `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (older).
  If not granted yet, both rows just stay empty — the icon-grid options
  (Gallery/Camera/etc.) still work, and Gallery's own picker requests
  permission independently when tapped.

## What was deliberately NOT canvas-rendered
The Recents grid and strip are plain `RecyclerView` + View-based cells, not
Canvas. This sheet is short-lived (inflated only while open), so canvas
rendering wouldn't pay for its added complexity here — unlike the persistent
chat message list (`MessageBubbleCanvasView`, see v51/v128/v134 notes),
which is long-lived and high scroll-volume, where canvas rendering is the
right call. Don't merge these two into one system.

## Files touched
- `feature-chat/src/main/res/layout/bottom_sheet_attach.xml`
- `feature-chat/src/main/res/layout/item_attach_strip_media.xml` (new)
- `feature-chat/src/main/res/layout/item_attach_grid_media.xml` (new)
- `feature-chat/src/main/res/drawable/bg_media_thumb_camera.xml` (new)
- `feature-chat/src/main/res/drawable/bg_video_duration_pill.xml` (new)
- `feature-chat/src/main/java/.../controllers/RecentMediaLoader.java` (new)
- `feature-chat/src/main/java/.../controllers/RecentMediaStripAdapter.java` (new)
- `feature-chat/src/main/java/.../controllers/RecentMediaGridAdapter.java` (new)
- `feature-chat/src/main/java/.../controllers/ChatMediaController.java`
  (`showAttachSheet()` + new `setupRecentMedia()` / `hasMediaReadPermission()`)

## Known follow-ups (not done here)
- No de-dupe against images already sent in this chat.
- No fade/crossfade animation between collapsed↔expanded — relies on
  `BottomSheetBehavior`'s default slide.
- `mediaQueryExecutor` in `ChatMediaController` isn't explicitly shut down on
  activity destroy (matches existing pattern in this file — `VoiceRecorder`
  etc. aren't either); fine for a single-thread executor scoped to one
  short-lived controller instance, but flag if you add a proper lifecycle
  teardown pass later.
