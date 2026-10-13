# v595 — Attach sheet polish 3 (items 6–10)

Shared sheet: `AttachSheetRecentMediaBinder` + `bottom_sheet_attach.xml` (chat, group, status).

6. Strings: caption hint, "Send", "Send as view once" -> `strings_attach_sheet.xml`
   (`attach_caption_hint`, `attach_send`, `attach_send_view_once`, `attach_preview_play`).
7. Haptics: CLOCK_TICK on select/deselect (grid + preview pill), LONG_PRESS on long-press preview.
8. Caption focus/tap while collapsed -> sheet expands (STATE_EXPANDED) so keyboard + bar settle.
9. Preview: PhotoView (pinch + double-tap zoom, tap closes only at 1x); videos play in place
   (VideoView, tap = pause/resume, error -> falls back to frame + play button, stops on dismiss).
10. Draft: new `AttachSheetDraft` (in-memory, per chat/group key, 1h TTL, max 8). Selection, caption,
    HD and view-once are saved on every change and restored when the sheet reopens
    (rotation / swipe-dismiss). Cleared on send/edit. `Config.draftKey(String)` overrides the
    default key (Activity class + String intent extras).
