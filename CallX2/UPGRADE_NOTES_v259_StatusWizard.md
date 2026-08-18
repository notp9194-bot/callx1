# v259 — Status composer wizard

## Add Status flow

- Converted the Add Status composer into a three-step flow:
  1. **Create** — type text, choose background/font/alignment, or open Upload.
  2. **Edit** — review selected media, add a caption, GIF, or interactive stickers.
  3. **Publish** — set expiry, privacy, close-friends, sharing, and ring options.
- Added a modern reel-style step indicator and Back/Continue navigation.
- Added a persistent preview card that updates immediately for typed text, selected
  images, selected videos, captions, and text styling while moving between steps.
- Kept the existing upload, compression, Cloudinary, Firebase, scheduling, and
  sticker behavior intact; the final Post action still uses the existing pipeline.

## Shared core visuals

- Moved the trim palette tokens into `core` so the modern composer visuals can be
  reused by feature modules.
- Reused the `bg_trim_*` card, preview badge, rounded photo, gradient CTA, and
  duration-chip drawables from `core` in the Add Status screen.
- Added light/dark shared trim color values for consistent rendering.