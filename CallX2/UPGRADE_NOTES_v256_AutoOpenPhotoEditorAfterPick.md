# v256 — Reel Upload: Photo Editor Now Auto-Opens Right After Picking

## Change
On the Reel Upload screen, Photo mode → **+ Add Photos**: previously, after
picking photo(s) from the gallery, the per-photo editor (filters, effects,
caption, stickers, rotation, Ken Burns, duration) only opened if the user
manually tapped the thumbnail in the preview strip.

Now the editor opens **automatically** right after the picker returns —
same handoff feel as the camera "+" → Photo mode flow, which already
auto-opened the editor immediately after capture.

## Details (`ReelUploadActivity.java`, `onActivityResult` → `REQ_PICK_PHOTOS`)
- Tracks `firstNewIndex` (the index of the first photo in this pick batch,
  i.e. `selectedPhotoUris.size()` before adding).
- After adding the picked photo(s) and updating the thumbnail strip/count as
  before, it now also launches `ReelPhotoEditorActivity.start(...)` for
  `firstNewIndex` — the exact same call already used by the manual
  thumbnail-tap listener, just triggered automatically instead of waiting
  for a tap.
- Works the same way whether it's the very first pick or a later
  **"Add More Photos"** append — the editor opens for the first photo of
  whichever batch was just picked.
- Editing/removing photos by tapping their thumbnails afterwards still works
  exactly as before — this only changes when the editor *first* opens.
