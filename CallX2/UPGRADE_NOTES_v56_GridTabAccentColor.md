# v56 — Grid Tab + Thumbnail Grid-Line Accent Color (bio-strip-style long-press)

## What was added
Same long-press → shared rainbow color picker flow the bio strip chips
already have, now added to the grid tabs (Reels/Liked/Saved/Repost/Series)
above the thumbnail grid in `UserReelsActivity`.

- **Long-press any tab** (owner only) → opens
  `RainbowStripColorPickerBottomSheet` (same "common rainbow box" used
  everywhere else in this screen).
- Picked color persists to `reels/users/{targetUid}/gridAccentColor` and
  applies to:
  1. **Active tab stays colourful** — the selected tab's indicator line
     and icon are tinted with the picked color (previously always plain
     `?attr/colorOnSurface`, i.e. black/white only). Unselected tabs get
     the same color at ~65% alpha, matching the dimmed look the old
     static selector already used.
  2. **Grid thumbnail separator lines** — the 1dp gaps
     `ReelGridAdapter.WhiteGridDecoration` leaves between thumbnails
     don't draw their own color; they just reveal whatever's behind them
     (`rvReels`'s background). So the same picked color is now also set
     as `rvReels`'s background, which makes those in-between lines match.
- "Use default" in the picker resets both back to the original
  theme-aware look (`?attr/colorOnSurface` for the tab indicator/icon,
  `@color/reel_grid_gutter` — day/night aware — for the grid lines).
- Color is read back and re-applied automatically whenever the profile
  loads (`onDataChange`), same persistence pattern as the bio-chip colors.

## Files changed
- `feature-reels/src/main/java/com/callx/app/profile/UserReelsActivity.java`
  - New field `gridAccentColorHex`.
  - `setupTabs()`: wires a long-press listener onto each tab's anchor view
    (owner only) via the existing `tabAnchorView(tab)` helper.
  - New `applyGridAccentColor(hex)` / `openGridAccentColorPicker()`.
  - Profile load: reads `gridAccentColor` from Firebase and applies it.

## Not touched
- `ReelGridAdapter.WhiteGridDecoration` itself — unchanged; it still just
  reserves the 1dp gap. The color comes from `rvReels`'s background, so
  no drawing code needed to change.
- Bio-strip chip colors, profile-song strip color — separate features,
  untouched, still work exactly as before.
