# v264 — Bigger "Suggested for you" avatar tiles in Home feed

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`
Method: `buildInlineSuggestedRow()`

## What changed
Same treatment as v263's "Suggested reels" tile resize, applied to the
"Suggested for you" creators row (the horizontal row of circular avatar
+ name chips mixed into the Home feed). Chips enlarged so ~2.5 chips
show per screen instead of ~4-5.

- Chip width: 78dp → **136dp**
- Avatar size: 52dp → **90dp**
- Chip margin-end: 10dp → 12dp
- Chip padding: 4/8/4/8dp → 6/10/6/10dp
- Name text: 11sp → 13sp, top margin 4dp → 6dp

Tap-to-open (`UserReelsActivity`) behavior and candidate pool logic
untouched.

## Verification
Brace/paren balance confirmed: 467/467 braces, 2878/2878 parens.
**Not compiled** — no Android SDK/Gradle network access in this
sandbox. Build locally (`./gradlew :feature-reels:assembleDebug`)
before shipping.
