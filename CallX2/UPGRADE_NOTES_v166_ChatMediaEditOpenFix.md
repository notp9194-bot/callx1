# v166 — Fix: "Edit" on a sent/received chat photo didn't open the editor

## The bug

Opening a single photo (or a photo inside a multi-media group) from a chat
bubble and tapping **Edit** did nothing / silently failed, even though
`MediaEditActivity` (the actual editor, added in v160, bug-fixed in v165)
was fully working when reached from the **attach sheet** (before sending).

## Root cause

Single-image messages in the chat never opened `MediaViewerActivity`
directly. Both single-image tap paths (the canvas-rendered bubble's
`onImageClick()` and the legacy `ivImage` bubble) routed through a
different, older bottom sheet — `showImageActionSheet()` — which only had
View / Share / Forward / Star / Delete. It had:

1. **No "Edit" option at all.**
2. Its "View" action opened `MediaViewerActivity` **without** the
   `chatId`/`messageId` extras. `MediaViewerActivity`'s own Edit pencil
   (added later) relies on those extras — without them it just shows
   *"Can't edit — not opened from a chat"* and stops.

Grouped-media taps (the `+N` photo grid, `MediaGroupLayoutHelper` /
`onMediaCellClick`) already passed `chatId`/`messageId` correctly, so that
path mostly worked — this was specifically a single-photo-message bug, but
since single photos are the far more common case, it looked like "editing
from chat is just broken."

## Fix

- `showImageActionSheet()` (feature-chat, `MessagePagingAdapter`):
  - Added a **✏️ Edit** option (WhatsApp puts this right next to View).
  - Fixed **View** to pass `chatId`/`messageId` so the in-viewer pencil
    works too if the user opens View first.
  - **Edit** opens `MediaViewerActivity` with a new `autoEdit=true` extra
    so it jumps straight into `MediaEditActivity` — no need to View first,
    then tap Edit again, matching WhatsApp's one-tap flow.
- `MediaViewerActivity` (app): reads the new `autoEdit` extra and, once the
  photo is set up (single-media *or* gallery/grouped mode, skipped for
  video), automatically fires the same `onEditClicked()` the pencil button
  already used — no new editor-launch code path, just reuses the existing
  (already-working) one.

## Files touched

- `feature-chat/.../conversation/MessagePagingAdapter.java` —
  `showImageActionSheet()`: new Edit entry, View now passes chatId/messageId.
- `app/.../activities/MediaViewerActivity.java` — reads `autoEdit` extra,
  auto-triggers `onEditClicked()` for both single-media and gallery mode.

## Not verified by build

Same caveat as v160/v165 — no Java/Android SDK toolchain in this
environment, so this was traced and fixed by reading the full call chain
(bubble tap → action sheet → MediaViewerActivity → MediaEditActivity →
GalleryEditBridge → resend) rather than compiled/run. Please build and
sanity-check on device: tap a single sent photo → Edit → editor should
open immediately; tap a photo inside a multi-photo group → Edit inside the
viewer should also work now with the caption/HD carrying through to the
resend.
