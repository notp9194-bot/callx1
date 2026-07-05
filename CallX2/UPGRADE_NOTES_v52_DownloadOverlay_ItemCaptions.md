# v52 — Live Download-Progress Overlay + Per-Item Media-Group Captions

Scope agreed with the user: (1) download-progress overlay → (2) swipe-to-reply
→ (3) per-item captions → (4) GIF/audio/file/poll/link-preview/reel-share as
Canvas bubbles. This pass covers **#1 and #3**. **#2 turned out to already be
fully implemented** (see below). **#4 is a separate, much larger pass** — not
started here, see "Not done in this pass".

## #1 — Live download-progress overlay (Canvas rendering)

### `feature-chat/.../conversation/canvas/MessageBubbleCanvasView.java`
- **Single received "image" bubble**: previously always rendered as either
  a bitmap or a flat placeholder box — no download affordance at all (that
  case simply wasn't Canvas-eligible before, see below). New
  `setMediaDownloadGate(downloading, progressPercent, idleLabel)` /
  `setMediaDownloadProgress(percent)` / `clearMediaDownloadGate()` draw a
  dim scrim + centered pill over the image slot: idle "⬇ <label>" (tap to
  start) or a live spinner/percentage ring while a download is in flight —
  mirrors the legacy `fl_download_overlay`/`pb_download_spinner` treatment
  exactly.
- **New `drawProgressRing()`**: replaces the old *static* partial-arc
  "spinner" (`drawGateIcon`'s previous `downloading` branch, which never
  moved or reflected real progress) with a real one — a determinate
  clockwise arc for 0–100%, or a time-driven rotating arc
  (`postInvalidateOnAnimation()`) when no percent has arrived yet. Also
  wired into the media-**group** per-cell badge (new `setGroupCellProgress()`,
  parallel `groupCellProgress[]` array), so group-cell downloads now sweep
  live too instead of sitting on a frozen arc.
- `drawGateIcon()` simplified to only draw the idle tray-icon glyph now that
  the downloading case has its own method.
- Tap handling: `onTouchEvent()`'s media-tap branch now checks `mediaGated`
  first — idle → `onMediaDownloadClick()`, mid-download → tap swallowed
  (same "ignore while downloading" precedent the group gate already used).

### `feature-chat/.../conversation/MessagePagingAdapter.java`
- `isCanvasEligible()`: a single **received** `"image"` message is now
  Canvas-eligible too (previously only the sender's own already-local image
  qualified — see the removed comment explaining why).
- `bindCanvasMessage()`'s `isImage` branch: mirrors the legacy
  `bindDownloadOverlay()` exactly — checks `MediaCache.getCached()` first;
  cached (or sent) → loads the bitmap straight away; not cached → arms the
  gate, fetches just the remote size for the idle label via
  `MediaCache.getRemoteSize()`.
- New `onMediaDownloadClick()` on the Canvas click listener starts
  `MediaCache.getWithProgress()`, feeding `setMediaDownloadProgress()` on
  every tick, swapping in the real bitmap via `setMediaBitmap()` on
  `onReady`, and offering "Tap to retry" on `onError` — same dedupe against
  `downloadingMediaUrls` the legacy pill and the group-cell downloader
  already share.
- `downloadGroupCell()`'s `onProgress` (previously a no-op — "grid cells
  too small for a % label") now feeds `setGroupCellProgress()` so the
  badge's ring itself sweeps live, even without a text label.

## #2 — Swipe-to-reply — already done, no changes needed

Checked `ChatActivity.setupSwipeToReply()` (called from `onCreate`),
`SwipeReplyHandler` (`ItemTouchHelper.Callback` — rubber-band resistance,
haptics, RTL support, reverse-swipe cancel, hardware-layer optimization
during drag) and `ReplyController`. `ENABLE_SWIPE_REPLY = true` and never
toggled off anywhere. This is a complete, production-grade implementation
already wired to `startReply()` — nothing to add here.

## #3 — Per-item captions in media groups (Canvas rendering)

### `feature-chat/.../conversation/canvas/MessageBubbleCanvasView.java`
- `GridItem` gained a third field, `caption` (new 3-arg constructor; old
  2-arg one still works, defaults to no caption).
- `drawMediaGroup()`: each cell with a non-empty `caption` (and not the
  "+N" overflow cell) now draws a 22dp bottom gradient strip + single-line
  ellipsized 10sp white text — mirrors `MediaGroupLayoutHelper`'s per-item
  caption treatment exactly, just Canvas-drawn (`TextUtils.ellipsize` +
  `canvas.drawText`, no child View).
- Video cells: when a caption is also present, the duration badge moves
  from bottom-start to top-end so the two don't overlap — same
  conflict-avoidance the legacy helper already used.

### `feature-chat/.../conversation/MessagePagingAdapter.java`
- `isCanvasEligible()`: dropped the per-item-caption exclusion for
  `multi_media` groups (previously any cell with a caption forced the
  whole group onto the legacy path).
- `bindCanvasMessage()`'s `isMultiMedia` branch now reads each item's
  `"caption"` key and passes it into the 3-arg `GridItem` constructor.

## Not done in this pass

- **GIF, audio, file, poll, link-preview, reel-share as Canvas bubbles.**
  These still render through the legacy `item_message_sent/received.xml` +
  `MessagePagingAdapter` path (`isCanvasEligible()` still returns `false`
  for all of them) — this is a materially larger, separate piece of work:
  six distinct custom-drawn bubble layouts, new touch/hit-testing per type
  (poll vote taps, audio scrub/play, link-preview thumbnail load, etc.), on
  top of `MessageBubbleCanvasView`'s existing ~1900 lines. Flagged to the
  user as its own follow-up pass rather than folded in here.
