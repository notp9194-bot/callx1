# v78 — Add Status screen: missing UI wired up

## Problem
`NewStatusActivity.java` already had full working logic for several features (GIF/sticker input,
custom expiry timer, close friends toggle, text alignment, link preview, 2 extra font styles) —
but `activity_new_status.xml` never had the matching views. All those code paths used
`findViewWithTag(...)`, silently returned null, and did nothing. Screen showed only: text input,
10 color swatches, 4 font buttons, Add Photo, Add Video, Privacy, Post.

## Fixed — added to activity_new_status.xml
- `btn_gif` (tag) — "Add GIF / Sticker" button, opens `showGifInputDialog()`
- `btn_expiry` (tag) — expiry chip ("⏱ 24 hours"), opens `showExpiryPicker()` (1h–72h)
- `toggle_close_friends` (tag, SwitchMaterial) — Close Friends only switch
- `btn_align_left` / `btn_align_center` / `btn_align_right` (tags) — text alignment row
- `link_preview_card` + `link_preview_progress/content/title/desc/domain/image` (tags) —
  auto-appearing OG link preview card when a URL is typed in status text
- `btn_font_condensed` / `btn_font_serif` (tags) — 2 extra font style buttons (font row now scrolls horizontally)

## New drawables (feature-status/res/drawable, prefixed `ic_status_*` to avoid cross-module
resource collisions — see past resource-conflict issue):
- ic_status_align_left.xml, ic_status_align_center.xml, ic_status_align_right.xml
- ic_status_star.xml (close friends)
- ic_status_link.xml (link preview domain icon)

## Not touched
- NewStatusActivity.java — zero code changes, only wired existing logic to UI
- No new dependencies added (SwitchMaterial already available via material:1.11.0)
