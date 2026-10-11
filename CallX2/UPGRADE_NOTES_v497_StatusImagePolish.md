# v497 — Status image/GIF viewer polish

**Build fix:** StatusViewerActivity.java had an 840-line block duplicated (renderStickers/music/open*Sticker… + old showCaption) plus an orphan `hideAllContent` tail → duplicate methods / statements outside a method. Removed the stale copy; the expandable `showCaption` (+ expand/collapse) is kept.

**Photo**
- Photos now get a `thumbnailUrl` (Cloudinary `c_limit,w_320`, aspect-preserving, no extra upload) → viewer blur-up works, tray/highlight covers stop loading the full file. New helper `CloudinaryUploader.deriveLimitThumbUrl`.
- `mediaWidth/mediaHeight` saved from ImageCompressor (single + batch post).
- Foreground placeholder is transparent → blurred backdrop shows instead of a flat black flash.

**GIF / sticker**
- Same treatment as photo: blurred backdrop, progress held until the frame is on screen (was a fixed 4s timer), disk cache ALL.
- Animated decode first, then plain-drawable decode (static PNG/WebP sticker), then tap-to-retry card.

**Error card + a11y:** text moved to strings (`status_load_failed`, `status_load_failed_desc`), 48dp min target, Button role for TalkBack, polite live region.

**Compose:** GIF URL must be http(s) with a host; a stale "gif" tag can no longer save a gallery pick as a gif with a local URI (tag cleared on discard + scheme check).
