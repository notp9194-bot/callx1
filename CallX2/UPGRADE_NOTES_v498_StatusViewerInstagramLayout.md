# v498 — Status viewer: Instagram-style card layout

**1. Card layout** (`activity_status_viewer.xml`)
- Story is now a rounded (20dp) card (`fl_story_card`, `bg_story_card`, clipToOutline) that starts below the status bar / notch and ends above a separate reply bar (`ll_reply_bar`). Reply row no longer overlays the photo.
- App background is dark navy (#0B0F17) instead of pure black.
- Card top margin uses `getInsetsIgnoringVisibility` (bars are hidden in immersive mode, plain `getInsets` would be 0). Reply bar takes nav-bar + keyboard insets, so the card shrinks when the keyboard opens.
- Caption / location / seen-by / last-reply bubble stay inside the card (bottom gradient).
- Own story: reply row hidden, bar stays as nav-bar spacer.

**2. Header**
- Time moved onto the name line, compact ("32m", "17h", "2d", "now") via `formatAgoShort`.
- "Expires in …" shown to the owner only.

**3. Right-side buttons**
- Download / Forward / Stabilizer / Repost column hidden; the buttons stay as click proxies, the ⋯ menu calls `performClick()` so permission checks and listeners are reused.
- Viewer ⋯ menu: Mute, Download, Forward, Repost (if owner allows sharing), Stabilizer on/off, Report. Owner + Highlight-owner menus got a Stabilizer entry.
- Top-right back arrow hidden (system back / gesture still closes).
