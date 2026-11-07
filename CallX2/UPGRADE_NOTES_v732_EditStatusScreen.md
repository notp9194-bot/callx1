# v732 — Status: "Edit Status" screen redesign (screenshot style)

Applies to Step 2 (Edit) of the Add Status wizard **only while a photo/video is picked**. Create and Publish steps and text-only statuses keep the old layout.

## What changed
- Dark full-screen Edit step: top bar (back circle, "Edit Status", gradient **Done**), 9:16 canvas filling the space between top bar and bottom bars, thumbnail strip + "+", **Save draft** + **Share**.
- Right rail: icon discs + dark label pills — Text, Stickers, Music, Draw, Effects, Mention, Crop, Filters, Emoji, More.
- Chips over the canvas (outside `media_preview_frame`, so never exported): **Add location**, **Mention people**, **Caption**.
- Done / Share → existing Publish step (privacy, expiry, schedule, Post now unchanged).

## Nothing removed
- Guide, Save, Label AI, Display time → **More** menu (same `onStatusEditorTool` actions). Fit → **Crop** menu (+ Fill toggle, Reset). Filters = old "Restyle" action.
- Caption field (`et_caption`) moved into the caption chip's holder; safe-area guide TextWatcher unchanged.

## New
- Add location: `StatusItem.locationName` (already in model + viewer) is now written by `saveStatus` / `saveBatchStatus`. Free-text place name (no map picker yet).
- Mention people: reuses the existing @mention dialog. Emoji: appends to the story text overlay. Save draft: `saveDraft()` (text + stickers) then closes.

## Drawables
- Reused: `ic_media_edit_text/draw/crop`, `ic_location_pin`, `ic_group_add` (feature-chat); `ic_sticker`, `ic_music_note`, `ic_alternate_email`, `ic_more_vert`, `ic_add_reels`, `ic_send_fill`, `bg_trim_circle_btn_on_photo`, `bg_trim_gradient_button_ripple` (core); `ic_bookmark`, `ic_status_edit_pencil`, `bg_status_reply_pill` (status).
- Added to `:core` (copies; originals in reels/x untouched): `ic_sparkle`, `ic_filters`, `ic_emoji`.
- New small shapes in feature-status: `bg_status_edit_dark_pill`, `bg_status_edit_dark_tile`, `bg_status_edit_thumb_ring`.
- All rail/button icons get an explicit white tint (same-named icons exist in several modules with different baked colours).

## Files
`feature-status/.../compose/NewStatusActivity.java`, `feature-status/src/main/res/layout/activity_new_status.xml`, 3 new drawables (status), 3 icons (core).

## Verification
Java parse-checked only (no Android SDK here). Please build and test: pick photo → Edit step layout on small + tall phones, draw/zoom/text, rail More/Crop/Emoji, caption chip + keyboard, location → post → viewer shows it, Done/Share → Publish → Post, Back, "+" replace media, video path.

## Known limits
Save draft does not keep the photo/video (draft store holds text + stickers). Thumbnail strip shows the one current media. Crop = existing Fit/Fill/Reset, not a free-form crop.
