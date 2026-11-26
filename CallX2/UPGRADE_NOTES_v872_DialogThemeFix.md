# CallX2 — dialogs / sheets jo theme (light/dark) follow nahi karte  (v872 — fixed)

Pehle ka static scan (code padh ke) + is baar har item ka host-screen check kiya. Device pe build/run nahi hua — isliye neeche "Test" section dekho.

**Rule:** dialog/sheet ke layout, drawable ya Java me fixed white/black/grey colour (ya forced-Light style) jo day/night ke saath nahi badalta.
**Approach:** core me theme tokens banaye (`values/` + `values-night/`), light value = purana literal (light mode same dikhega), night value = dark counterpart. Phir har jagah literal hata ke token lagaya.

## Naye tokens (core `colors.xml` + `values-night/colors.xml`)

`sheet_drag_handle`, `sheet_divider`, `sheet_divider_strong`, `sheet_text_primary`, `sheet_text_secondary`, `sheet_text_hint`, `sheet_card_bg`, `sheet_card_lavender`, `sheet_row_lilac`, `sheet_grad_start/end`, `sheet_option_fill`, `sec_row_{blue,purple,dark,red,orange,green}_bg`, `sec_ok_green`, `sec_danger_red`

---

## A. FIXED — asli bugs

| Dialog / sheet | Problem tha | Fix |
|---|---|---|
| **View-once dialog** (`ChatActivity.showViewOnceDialog`) | `bg_view_once_dialog` (app + feature-chat dono copy) `#1E1E1E` hamesha dark; content text theme default → light me dark-on-dark | bg → `@color/dialog_surface`; header/counter/duration/file/text → `dialog_text_*`; warning → `sec_danger_red` |
| **`bg_rounded_alert_dialog` (feature-status duplicate)** | Core wale ka duplicate `#F0F0F0` fixed tha — merge me core wale ko shadow kar sakta tha | → `@color/dialog_surface` |
| `bottom_sheet_chat_security` (ChatSecurityBottomSheet) | bg `#FFFFFF`, text `#1A1A2E/#888`, dividers `#F0F0F0`, handle `#CCCCCC` | sab tokens |
| `bottom_sheet_encryption_details` | same + green/red status text | tokens + `sec_ok_green` / `sec_danger_red` |
| `bg_sec_row_*` (6 drawables) + `bg_safety_number_card` | pastel light fills night me chamakte the | `sec_row_*_bg` / `sheet_card_bg` |
| `bottom_sheet_export_chat` | text `#212121` / divider `#E0E0E0` — night sheet pe invisible | tokens |
| `bottom_sheet_pairing_approval` | text `#1A1A2E`, tint `#F0F0F0` | tokens |
| `bottom_sheet_channel_info` | root `@android:color/white`, text `#111/#666`, handle `#CCC` | tokens |
| `bottom_sheet_channel_viewer_info` | `#888888` | `sheet_text_secondary` |
| `bottom_sheet_reaction_picker` + `bg_search_input` | divider `#EEEEEE`, search bg `#F1F3F8` | tokens / `surface_input` |
| `dialog_forgot_password` | `#888888` | `sheet_text_secondary` |
| **Drag handle** — `bg_drag_handle` (core, chat, status, calls) | `#CCCCCC` fixed → section 3 ki saari 10 sheets | `@color/sheet_drag_handle` |
| `item_reel_liker`, `item_reel_commenter` (Likes/Shares/Collaborators/Comments sheets) | name/username `#FFFFFF` → light me white-on-white | `?attr/colorOnSurface` |
| `bg_btn_secondary` (core + app) | `#333333` fixed, text `colorOnSurface` → light me dark-on-dark | `@color/surface_input` |
| `dialog_collab_send` (ReelCollabRequestActivity) | bg `#1E1E1E` + white text but fields `surface_input` → light me white-on-white | `dialog_surface` / `dialog_text_*` |
| `ChatDisplayModeBottomSheet`, `ChatCustomizationBottomSheet` | poora sheet Java me dark gradient + white text | gradient/text/handle/option fill → tokens |
| `ChatPaymentBottomSheet` | bg `rgb(7,27,22)` + white text | `surface_card` / `text_primary` / `text_secondary` |
| `OwnTemplatePicker` | forced white sheet + `#14142B` text | tokens |
| `HighlightRingColorPickerBottomSheet`, `RainbowStripColorPickerBottomSheet`, `StatusAddToHighlightBottomSheet` | GRAY text, `#F0F0F0` chips + `#333` text, `#F5F5F5` rows, black selection ring | tokens |
| `StatusAnalyticsBottomSheet` | `#F5F0FF` card | `sheet_card_lavender` |
| `NewStatusActivity` → video trim sheet | forced white sheet, `#111111` text | tokens |
| `DuetSeriesPickerBottomSheet` | list item text `0xFF000000` on **always-dark** sheet (black-on-dark bug) | white |

## B. INTENTIONAL — jaan-bujh ke change nahi kiya (screen khud dark-only hai)

Yeh sheets un screens pe khulti hain jo khud hamesha dark hain; light sheet daalne se mismatch hota.

- **`YtBottomSheetStyle`** (YouTubeCommentOptionsSheet, YouTubeVideoOptionsSheet) + saari `bottom_sheet_yt_*` — YouTube module ka theme (`YouTubeActivity.Theme`) bina `values-night` ke dark-only hai.
- **X**: `bottom_sheet_x_profile` — X screens dark-only.
- **Reel player chrome**: `ReelMoreBottomSheetTheme` (ReelMore, ReelDisplayMode, ReelPlaybackOptions, ReelRemixSequencePicker), `bottom_sheet_reel_cinema/analytics/user_profile/sound_detail/series_picker`, `fragment_reel_share_sheet` — always-dark video player. (Precedent: `ReelSoundQuickActionSheet` already DayNight hai — agar chaho to baaki bhi isi pattern pe ja sakte hain.)
- **Editors**: `bottom_sheet_clip_trim`, `bottom_sheet_text_overlay_style`, `bottom_sheet_template_picker`, `bottom_sheet_status_add`, `MusicStickerOptionsSheet`, `DialogFullscreenHelper`, `MultiMediaPreviewDialog` — dark editor / fullscreen media canvas.
- `VideoQualitySheet` / `bottom_sheet_video_quality` — code me kahin use hi nahi hota (dead), isliye chhoda.
- `dialog_send_unblock_request`, `dialog_unblock_joy` (ChatBlockController) — decorative full-bleed colour-mood overlays (red / green tint), apna fixed palette. **Agar light version chahiye to bolo** — alag tokens banane padenge.

## C. False positives — scan ne flag kiya par koi bug nahi

- `#33FFFFFF` ripple colours (`bg_attach_chip`, `bg_hd_toggle_inactive`, `bg_advance_edit_gradient_button_ripple`, `bg_more_item_ripple`) — ripple hai, fill `surface_input` themed hai.
- `bottom_sheet_attach`, `bottom_sheet_photo/video_send_advance_edit` — fill pehle se themed.
- `bottom_sheet_contact_call` — brand buttons (X black, Reel pink, YouTube red, white icon) — brand colours.
- `dialog_group_qr_code` — QR ke peeche white zaroori (scan ke liye).
- `dialog_request_popup`, `bottom_sheet_pairing_approval` — white text coloured button ke upar.
- `item_join_request` — grey avatar placeholder; `#888888` mid-grey dono modes me readable.
- Normal `AlertDialog`s jo `AlertDialogStyler.showRounded()` se jaate hain (ChatReaction, ScheduledSend, MessageEditHistory, ReelUi description, …) — styler night me legacy dark text (`#222/#111/...`) ko `dialog_text_primary` se remap kar deta hai.

## D. Test checklist (device pe)

Light + Dark dono me ek baar kholo: View-once, Chat security, Encryption details, Export chat, Pairing approval, Channel info, Reaction picker, Chat display mode, Chat customization, Chat payment, Reel likes/shares/collaborators (naam dikhna chahiye), Collab request dialog, Status video-trim sheet, Highlight ring/Rainbow picker, Add-to-highlight, Status analytics, Own template picker.
