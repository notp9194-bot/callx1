# v760 — FollowConnectionsActivity: lazy profiles + disk cache + skeleton/retry (Template-uses parity)

## Files
- `feature-reels/.../followers/FollowConnectionsActivity.java` (loaders, filter, adapter bind, UI states)
- new `feature-reels/.../followers/FollowProfileCache.java` (memory + disk + Room-seed profile cache)
- new `feature-reels/src/main/res/values/strings_follow_connections.xml`

## Before -> after
- Followers / Following / Suggested / computed Mutual: was one `users/{uid}` read PER uid up front (10,000 followers = 10,000 reads),
  list shown only after the last one. Now: ONE read of the uid list, rows are placeholders, `users/{uid}` is read only for rows that
  are really bound (max 6 in flight, latest-bound first, queue capped at 60 so a fast fling does not pay for skipped rows).
- Paging: first 40 rows, +40 when scrolled within 12 rows of the end (local window over the uid list).
- Profile cache: static LruCache(1500) + disk (SharedPreferences, 1000 entries, 6 h TTL, stale painted instantly and re-read only when
  bound). Chat partners are seeded from Room. Ordered (Sound "Used by") mode also reads/writes this cache and skips reads for fresh ones.
- Skeleton rows (pulse) instead of the spinner; first-load failure shows "Couldn't load · Tap to retry" per tab. Page state is
  state-driven, which also fixes the old race where a load that finished before the page was built left the spinner visible.
- Search: matches every row whose profile is known; unknown ones (max 300 per tab) are fetched in the background and appear as they arrive.
  Cross-tab "Found in ..." hint only from the visible tab, shown once per search text, and not if the search found something meanwhile.
- Unfollow removes by uid (row objects are replaced when a profile arrives); list refresh goes through filterTab/AsyncListDiffer.

## Behaviour changes to know
- Order is now the Firebase key (uid) order, not alphabetical by name (names are not known up front any more).
  reelFollows/reelFollowers values are plain `true`, so there is no timestamp to sort by recency.
- The uid list itself is still one full read (keys only). True server-side paging of the list needs a counter node + ordered values.

## v761 addendum — Used This Template dark-mode text
- Secondary text (@username, time, "N people used…", "N People", empty text, count icon) used the global `text_muted`, which is #555555 in night mode (too dim on black).
  New screen-local `tpl_text_secondary` (day #64748B, night #A6ADBB) in feature-status `values/` + `values-night/template_uses_colors.xml`; the 2 layouts now use it.
- "3people used…": the leading space of `tpl_uses_people_used` was stripped by aapt; the string is now quoted so the space is kept.

## v762 addendum — play the template from the Used This Template header
- Tapping the thumbnail (play icon) opens `StatusViewerActivity` for `status/{me}/{statusId}` (EXTRA_TARGET_STATUS_ID + SINGLE_STATUS_ONLY), so only that template story plays and Back returns to this screen.
- Click is attached once the header loads (a deleted/expired template status has no header -> no play).
