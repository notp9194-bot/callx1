# v110 — Full Picture Caching for Single-Media Bubbles (indeterminate spinner only)

Implements the item flagged as invasive in v108/v109's notes, for
`MediaRenderer` (single image/video bubble) specifically.

## Design

`MediaRenderer.draw()` is now split via a `spinnerHandledSeparately` flag:

- `draw(canvas, hPad, vPad)` — unchanged public behavior, used for the
  normal (non-cached) path.
- `draw(canvas, hPad, vPad, true)` — draws everything **except** the live
  indeterminate spinner ring (determinate 0-100% progress is untouched —
  it's still drawn here always, since it only updates on real progress
  events, not every frame).
- `drawIndeterminateSpinnerOnly(canvas)` — draws **only** the spinner ring,
  positioned from `host.mediaGatePillRect` (already computed by the static
  draw pass above), for replaying on top of a cached Picture every frame.

`MessageBubbleCanvasView.drawMediaWithOptionalCache()` orchestrates:
- **Not** in the indeterminate-spinner state → calls `mediaRenderer.draw()`
  directly, exactly as before this change. This is the common case (no
  active download) and is completely untouched.
- **In** the indeterminate-spinner state → records the static content into
  a `Picture` once (cached in `cachedMediaPicture`), replays it via
  `canvas.drawPicture(...)` every frame instead of re-running all the
  image-shader/GIF-badge/gate-pill drawing work, then draws just the live
  ring on top via `drawIndeterminateSpinnerOnly()`.

## Invalidation — where staticPictureDirty is set

- `bindMedia()` — **critical**: guarantees a recycled view never replays a
  Picture cached for a previous, different message.
- `setMediaBitmap()`, `setMediaDownloadGate()`, `setMediaDownloadProgress()`,
  `clearMediaDownloadGate()` — any state change that could affect what the
  gate/image looks like.
- The non-cached branch of `drawMediaWithOptionalCache()` itself also sets
  it, so the *next* indeterminate episode (even much later, same bind)
  always starts from a fresh capture.
- Extra safety net: the cached Picture's recorded width/height are compared
  against the view's current size on every use; a mismatch forces a
  rebuild regardless of the dirty flag, in case some unrelated relayout
  happened mid-download.

Reactions, pinned/forwarded labels, sender name, reply preview, and the
bubble background itself are all drawn **outside** `mediaRenderer.draw()`
in `onDraw()` — never part of this cache — so none of those needed a dirty
hook.

## Why this one and not MediaGroupRenderer/poll yet

- **Poll has no spinner at all** (no downloads happen for poll content) —
  there's nothing for this cache to help with there; dropped from scope
  entirely rather than adding unused machinery.
- **MediaGroupRenderer** has the same indeterminate-spinner issue, but
  per-cell (multiple independent gates in one grid) rather than one gate
  for the whole bubble — the same pattern applies but needs its own
  careful pass (per-cell dirty tracking, not just one flag) rather than
  reusing this exact code. Wanted this one fully verified in isolation
  first.

## Compatibility

`Canvas.drawPicture()` has been fully hardware-acceleration-compatible
since API 23 — this app's `minSdk` is exactly 23, so there's no
compatibility gap to worry about here.

## What I still can't verify

No Android SDK/emulator in this environment. Checked brace/paren balance
against pre-edit originals (no new imbalance introduced) as a syntax
sanity check, and traced every mutation site by hand, but this needs a
real build and a manual test — specifically: start a download on an image
message with unknown progress (indeterminate spinner), confirm the image/
gate/pill render correctly and stay correct once the download completes,
try it on a message being actively edited/reacted-to while mid-download,
and scroll a recycled view from one downloading-image message to another.
