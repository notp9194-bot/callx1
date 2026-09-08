# v94 — Reel Search screen: Instagram parity pass

Scope: `ReelSearchHistoryActivity` (the "tap the search box" contact/username
search screen opened from `ReelSearchActivity`'s Explore grid).

## 1. Follow button in results
Every account row (Recent, live results, Suggested) now shows an inline
`Follow` / `Following` pill button, hidden only on the signed-in user's own
row. Tapping it writes/removes the same `reelFollows` + `reelFollowers`
mirrored edges `FollowConnectionsActivity#toggleFollowFromBtn()` already
uses, and refreshes all three lists so follow state stays in sync across
them.

## 2. "Suggested" section
When the search box is empty, a new "Suggested" block appears below Recent —
popular accounts (`users` ordered by `reelCount`) the signed-in user doesn't
already follow, same source query `DiscoverPeopleActivity#loadCandidates()`
uses, capped to 10. No more dead "No recent searches" screen on first run.

## 3. Bold query highlighting
Live-results rows now bold every case-insensitive occurrence of the typed
query inside the name/username/hashtag text (`RowAdapter.highlighted()`),
Instagram-style. History rows are left plain since there's no "match" to
highlight there.

## 4. Category tabs — Top / Accounts / Tags / Places
A `TabLayout` (same left-aligned underline style as
`FollowConnectionsActivity`'s tabs) appears once a query is typed:
- **Accounts** — the existing nameLower/username/name Firebase search.
- **Tags** — client-side search over `reels/` hashtags (same source
  `ReelSearchActivity`'s trending chips read), fetched once and cached for
  the screen's lifetime; tapping a tag opens the existing
  `HashtagReelsActivity`.
- **Top** — a few matching tags followed by matching accounts.
- **Places** — honestly empty. `ReelModel` has no location field, so this
  says "Place search isn't available yet" instead of faking results.

## 5. Real `username` field
`orderByChild("username")` was already being queried here, but nothing in
the app ever wrote `users/{uid}/username` — only `callxId` (the mobile
number, shown as `@callxId`) was ever saved. That made every username
search silently fall through to the nameLower/name fallbacks.
`ProfileSetupActivity` and `ProfileActivity` now also write
`username = callxId.toLowerCase()` on save, so this index is finally
populated for both new and edited profiles.

## Files touched
- `feature-reels/.../explore/ReelSearchHistoryActivity.java`
- `feature-reels/src/main/res/layout/activity_reel_search_history.xml`
- `feature-reels/src/main/res/layout/item_reel_search_history.xml`
- `feature-reels/src/main/res/drawable/ic_hashtag.xml` (new)
- `app/.../activities/ProfileSetupActivity.java`
- `app/.../activities/ProfileActivity.java`
