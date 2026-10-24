# v650 — Sticker polish: perf (2), text in the sticker engine (4), handle touch size (6)

Files changed
- core/.../stickers/StatusStickerOverlayView.java
- core/src/main/res/values/ids.xml                      (2 tag ids for the guide chrome)
- feature-status/.../compose/NewStatusActivity.java
- feature-status/.../utils/StatusTextOverlayRenderer.java (toJson overload, JSON format unchanged)
- feature-status/src/main/res/values/strings_status_text_engine.xml (new)

2. Performance (drag / pinch MOVE path)
- Guides (pink centre, yellow align, rotation line + degree pill) fade only when their on/off STATE changes
  (fadeTo + view tag). Before: 4-6 animate() restarts on every MOVE.
- Zero allocation per MOVE: snapPosition returns a reused buffer, other stickers' bounds are collected once per
  gesture (collectSnapTargets), inner float[] loops replaced by scalar loops, RectF in selection draw reused,
  chrome views looked up once per gesture, rotation label text only set when the degree changes.

4. Text = same engine as stickers
- The composer text is wrapped via StatusStickerOverlayView.wrapContent(type "text"), so it now gets: snap guides
  (frame centre + other stickers, and stickers snap to it), corner resize/rotate handle, canvas-wide pinch, undo/redo,
  lock, bring-front/back, drag-to-trash / long-press remove, safe-area guide + magnet.
- Tap: first tap selects (bar + handle), tap on an already-selected text — or the bar's "Aa Edit" — opens the editor.
- Saved format is unchanged ("overlaytext": cx, cy, scale, rot …) → StatusViewerActivity needs no change.
- Wiring shared with stickers: NewStatusActivity.wireStickerEngine(). Copy button is hidden for text (one text layer).

6. Handle size
- Visual radius 11dp on screen (not below 8dp), touch zone never below 32dp at ANY scale (was ~2-3dp at 0.3x).
  Note: touches outside the sticker's own bounds never reach it, so on very tiny stickers the zone is clipped to the card.

3. Viewer match (sticker placement)
- Before: the viewer placed stickers with leftMargin = ratio × WHOLE card width at a fixed 280dp-capped card size, while the
  composer canvas is 9:16 and the viewer shows the baked 9:16 image fitCenter in a differently-shaped card → stickers drifted
  and looked relatively bigger/smaller (worst at 3x–5x and on tall/short screens). Text overlay already used the photo box.
- Now toJsonWithScale() also saves cxRatio/cyRatio (centre), frameWdp/frameHdp (composer canvas) and wDp (card layout width).
  The viewer maps the sticker into the SAME 9:16 box inside the card, re-uses the layout width (same text wrapping), scales the
  card by boxWidth / canvasWidth and places its CENTRE in a pre-draw pass (no flash), centre clamped to the card
  (placeCenteredOnceLaidOut). Pivot = centre, so rotation + scale line up. Zoom-to-front keeps the fit factor.
- Old posts (no cxRatio) fall back to the previous placement, unchanged.

1. Device-test pass (code review of the risky flows; nothing could be run here)
- NewStatusActivity is now portrait-locked (the 9:16 canvas, safe-area spec and viewer are portrait-only; a rotation used to
  recreate the activity and drop stickers / text / selection).
- Video mode: a tap that only deselects a sticker / text no longer also pauses the video.
- Canvas-wide pinch: traced the DOWN → POINTER_DOWN → POINTER_UP → UP sequence in video and photo mode; the synthetic CANCEL
  to the gesture layer stops stray clicks, and the leftover finger is swallowed until UP. No change needed.
