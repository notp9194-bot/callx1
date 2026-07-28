# UserReelsActivity — Dark/Light Polish + Common Rainbow Box (v51)

## 1. Username strip (profile-song pill)
- New premium, thinner (26dp, was 32dp) hairline-pill drawables:
  `feature-reels/res/drawable/bg_song_pill.xml` (light) and
  `drawable-night/bg_song_pill.xml` (dark). Border is now theme-correct in
  both modes (previously a black-only stroke that vanished in dark mode).
  Text was already `?attr/colorOnSurface` (correctly white/black); the fix
  was the strip visibility, not the text.

## 2. YouTube button
- `btn_open_youtube` (and the hidden legacy duplicate) now use
  `bg_circle_action` — same dark chip as Call/Video/X — with only the inner
  icon tinted red, instead of a full solid-red circle.

## 3. Grid gutter
- `rv_reels` background switched from hardcoded `#FFFFFF` to new
  `@color/reel_grid_gutter` (white in light, black in dark —
  `values-night/colors.xml`), matching Instagram's grid line behavior.

## 4. Tab icon row (Reels/Liked/Saved/Repost/Series)
- Added `color/tab_icon_tint_selector.xml`, applied via
  `app:tabIconTint` on the TabLayout. Resolves through
  `?attr/colorOnSurface` — full strength when selected, 65% alpha otherwise
  — so icons read correctly white-leaning in dark mode / black-leaning in
  light mode instead of using their raw un-tinted drawable colors.

## 5. Add-highlight "+" button
- `HighlightsRowAdapter.bindNewButton()` now detects night mode and flips
  the circle to black + plus icon to white in dark mode; light mode is
  unchanged.

## 6 & 7. Profile-song strip: Remove/Replace + long-press color
- Tap on the filled strip (isSelf) now opens an Open/Replace/Remove popup
  menu instead of jumping straight to Sound Detail. Remove clears
  `reels/users/{uid}/profileSong`; Replace opens `ReelTrendingAudioActivity`.
- Long-press on either strip state (filled or the "Add a song" stub, isSelf
  only) opens the shared rainbow color picker and persists the chosen hex
  to `reels/users/{uid}/profileSongStripColor`. The color is applied as a
  recolored hairline-pill background across both strip states.

## 8. Common rainbow box
- `RainbowColorPickerView` moved from `feature-status` into
  `core/java/com/callx/app/utils/` so it's genuinely shared infrastructure.
  `HighlightRingColorPickerBottomSheet` (feature-status) now imports it from
  core — no behavior change there.
- Added `core/java/com/callx/app/utils/RainbowStripColorPickerBottomSheet.java`
  — a lighter single-color picker (no swatch grid / ring-mode toggle) built
  on the same shared `RainbowColorPickerView`, used by the new
  UserReelsActivity strip long-press feature.
