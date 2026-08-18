# v160 — Attach-sheet photo editor + filter carousel

## What's new

The attach sheet's selection bar (shown once 1+ items are picked in the
Recents grid) now has an **Edit** (pencil) icon next to the view-once
toggle and Send button. Tapping it opens a new full-screen editor,
`MediaEditActivity` (feature-chat), instead of sending immediately.

### Editor screen

- **Top toolbar**: close (X), download, HD toggle, rotate, sticker, "Aa"
  text, pencil/draw — all functional.
  - **Rotate**: rotates the current photo 90° per tap.
  - **Sticker**: opens an emoji row; tapping an emoji drops a
    draggable + pinch-scalable sticker onto the photo.
  - **Aa (text)**: prompts for text + a color swatch, then drops a
    draggable + pinch-scalable text overlay.
  - **Pencil**: freehand drawing via `DrawOverlayView` — color swatches,
    undo last stroke, done.
  - **Download**: bakes the current edit (rotation + filter + stickers +
    drawing) into a JPEG and saves it to `Pictures/CallX` via MediaStore.
  - **HD**: same Standard/HD compression toggle as the sheet's own HD
    chip; carries through to the final send.
- **Bottom bar**: delete (removes the current item from the batch),
  a thumbnail strip to switch between multiple selected items (each with
  its own per-item ✕ to remove it directly), a caption field, and Send.
- **Swipe up for filters**: a fling gesture on the photo slides up a
  filter carousel (`MediaFilters`: None / Pop / B&W / Cool / Chrome /
  Film), each with a live thumbnail preview; tapping one applies it to
  the full preview immediately. Swipe down (or the chevron) collapses
  the panel back to the normal bottom bar.

Multiple selected photos each keep their own independent edit state
(rotation/filter/stickers/drawing) — scrubbing the thumbnail strip
between them doesn't lose anything. Videos in the same selection are
shown read-only (tools disabled) so they can still be reordered/deleted/
captioned alongside edited photos, but pass through unedited.

### Send pipeline

On Send, every edited photo is re-rendered at full resolution (rotation
+ filter + stickers/text + freehand strokes baked in via `Canvas`/
`ColorMatrixColorFilter`), written to the app cache dir, and re-exposed
through the existing `com.callx.app.fileprovider` authority. The
resulting URIs + caption + HD flag feed straight into the same
`uploadSequentially` pipeline the sheet's own Send button already used
— both `ChatMediaController` (1-1 chat) and `GroupChatActivity` (group
chat) got a matching `mediaEditLauncher` + `onMediaEdit` implementation
so the two entry points stay in lockstep, same pattern the rest of the
attach sheet already follows.

## Files touched

- New: `MediaEditActivity`, `MediaFilters`, `DrawOverlayView`
  (feature-chat, `conversation/controllers`), `activity_media_edit.xml`,
  `item_media_edit_filter.xml`, `item_media_edit_thumb.xml`, and a set of
  `ic_media_edit_*` / `bg_media_edit_*` drawables (feature-chat).
- Changed: `bottom_sheet_attach.xml` (new Edit icon in `selection_bar`),
  `AttachSheetRecentMediaBinder` (`Callbacks#onMediaEdit`, wiring),
  `ChatMediaController`, `GroupChatActivity` (launcher + `onMediaEdit`),
  `feature-chat/AndroidManifest.xml` (registers `MediaEditActivity`).

## Not verified by build

This change was written and reviewed by reading the existing codebase's
own patterns (rotation/filter matrices modeled on `ReelPhotoEditorActivity`,
sticker drag/pinch modeled on the same, FileProvider usage matched to the
app's existing `com.callx.app.fileprovider` authority/`file_paths.xml`).
**No Java/Android SDK toolchain was available in the environment this was
written in, so it could not be compiled or run** — please build/run it in
Android Studio and sanity-check the editor (especially rotation baking,
the swipe gesture thresholds, and sticker drag on a real device) before
shipping.
