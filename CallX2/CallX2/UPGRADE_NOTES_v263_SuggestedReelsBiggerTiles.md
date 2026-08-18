# v263 — Bigger "Suggested reels" tiles in Home feed

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`
Method: `buildInlineSuggestedReelsRow()`

## What changed
Tile size for the inline "Suggested reels" row (horizontally-scrollable
row mixed into the Home feed, added in v260) increased so a screen now
shows **~2 full tiles + a peek of the 3rd** instead of ~3 full tiles + a
sliver — matches the reference screenshot.

- `cardW`: 112dp → **160dp**
- `cardH`: 198dp → **284dp** (kept exact 9:16 ratio: 160 * 16/9 = 284)
- Tile-to-tile margin: 6dp → 8dp (a bit more breathing room at the bigger size)
- Views-count pill text: 11sp → 12sp, margins 6dp → 8dp (scaled up to
  match the larger tile)

Nothing else in the row (header, kebab menu, long-press peek preview,
tap-to-open behavior, candidate pool logic) was touched.

## Verification
Brace/paren balance confirmed after edit: 467/467 braces, 2878/2878
parens. **Not compiled** — no Android SDK/Gradle network access in this
sandbox. Build locally (`./gradlew :feature-reels:assembleDebug`) before
shipping.
