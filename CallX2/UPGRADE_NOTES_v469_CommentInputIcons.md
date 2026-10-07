# v469 — Reel comments: input bar icon consistency

Rule now: secondary action = outline, muted; primary action = filled, brand colour.
- Photo button: `ic_gallery` (heavy filled square) -> new outlined `ic_comment_photo` (24dp glyph in 40dp target,
  padding 8dp, tint comment_input_icon so it stays theme-aware).
- Send: glyph was 20dp (padding 10dp) vs photo's 24dp; now padding 8dp -> 24dp glyph in the 36dp brand circle.
- Both are 24dp glyphs in 40dp touch targets. `ic_gallery` itself untouched (used elsewhere).

Files: activity_reel_comment.xml, ic_comment_photo.xml (new), version.properties (127).
