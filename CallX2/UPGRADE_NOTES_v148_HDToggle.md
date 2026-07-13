# v148 — Attach Sheet: WhatsApp-style HD toggle

## Problem
Attach sheet's expanded "Recents" header already had a static "HD" label
(cosmetic only — not tappable, did nothing). Meanwhile the multi-select
group-send path (`uploadSequentially` → `rawUploadGroupItem` in both
`ChatMediaController` and `GroupChatActivity`) uploaded **raw, uncompressed**
images straight to Cloudinary — no `ImageCompressor` pass at all, unlike the
single-image send path.

## Fix
1. **`ImageCompressor`** now has two quality tiers instead of one:
   - **Standard** (default): 1280×1920, WebP q80, ~800KB cap — unchanged.
   - **HD** (new): 1920×2560, WebP q92, ~3MB cap — bigger + sharper, but
     still resized/re-encoded (never the untouched original), same
     philosophy as WhatsApp's own "HD" send.
   - `compress(ctx, uri, hd, callback)` / `compressSync(ctx, uri, hd)`
     overloads added; old no-`hd` overloads still work (default to Standard).

2. **`btn_hd_toggle`** — the header "HD" chip is now a real tappable toggle
   (`bottom_sheet_attach.xml`), OFF by default. Tap swaps its look between
   a muted outline (OFF) and filled green pill (ON) — `bg_hd_toggle_inactive`
   / `bg_hd_toggle_active`. Resets to OFF every time the sheet re-opens.

3. **`AttachSheetRecentMediaBinder.Callbacks#onMediaSend`** gained an
   `isHD` boolean (whatever the toggle was set to when Send was tapped).

4. **`ChatMediaController` + `GroupChatActivity`** — the whole
   `uploadSequentially(...)` chain now threads `isHD` through. Images in a
   multi-select batch are compressed via `ImageCompressor` (Standard or HD
   tier per the toggle) before upload, instead of going up raw. Videos and
   audio/file items are unaffected — only the image branch changed.

## Files touched
- `core/.../utils/ImageCompressor.java`
- `feature-chat/.../controllers/AttachSheetRecentMediaBinder.java`
- `feature-chat/.../controllers/ChatMediaController.java`
- `feature-chat/.../group/GroupChatActivity.java`
- `feature-chat/src/main/res/layout/bottom_sheet_attach.xml`
- `feature-chat/src/main/res/drawable/bg_hd_toggle_active.xml` (new)
- `feature-chat/src/main/res/drawable/bg_hd_toggle_inactive.xml` (new)
