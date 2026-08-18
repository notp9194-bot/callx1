# v264 — Added missing "For You" option to the Home feed dropdown

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`

## The bug
`showFeedFilterDropdown()` only ever offered **Following** and
**Favorites** — and both of those call `switchFeedMode(true)`
(`isFollowingMode = true`). There was no menu item anywhere that called
`switchFeedMode(false)`, so `isFollowingMode` could never actually become
`false` from the UI. Since the v243 ranked For-You feed, the v243 inline
"Suggested for you" creators row, and the v260 "Suggested reels" row are
all gated on `!isFollowingMode`, none of them were reachable no matter
what the user tapped — the underlying `fragment_home.xml` toggle that
used to expose this (`btn_home_following` / `btn_home_for_you`) is
`visibility="gone"`, replaced by this dropdown, which never got the
"For You" option carried over.

## The fix
Added a third menu item, **"For You"** (id `3`), between Following and
Favorites, wired to `switchFeedMode(false)` — same pattern as the other
two options (updates `tvFeedTitle`, calls `switchFeedMode`). Following
and Favorites are unchanged.

## Net effect
Home tab dropdown ("CallX ▾") now has 3 real options: Following,
For You, Favorites. Picking **For You** is what surfaces the ranked
feed, the inline "Suggested for you" row, and the "Suggested reels" row —
all three were implemented correctly, just unreachable until now.

## Known limitations
- Default on fresh load is still `isFollowingMode = true` (Following) —
  unchanged; the user has to explicitly tap For You once per session
  unless you also want the default flipped, which wasn't asked for here.
- Authored and hand-reviewed for syntax (brace/paren balance confirmed:
  467/467, 2877/2877) but **not compiled** — no Android SDK/Gradle
  network access in this sandbox. Build locally
  (`./gradlew :feature-reels:assembleDebug`) before shipping.
