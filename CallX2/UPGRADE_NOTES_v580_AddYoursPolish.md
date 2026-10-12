# v580 — Add Yours polish (privacy sync, chain-screen UX, resources)

## Deploy
`firebase deploy --only functions:onStatusPrivacyChangedAddYours`
(plus the three v579 functions if not deployed yet). No DB-rules change.

## 6. Privacy change → entry sync (functions/index.js)
`onStatusPrivacyChangedAddYours` (onUpdate of /status/{owner}/{statusId}): when `privacy` changes it flips the chain
entry's `listed` flag. public → private wipes name/photo/thumb/type/bg/text from the world-readable entry;
private → public fills them back. Ignores updates where privacy didn't change, deleted statuses, and entries that belong
to another status of the same user. Shared field builder `addYoursPublicFields()` is now used by create + update.
Note: the current client has no "edit audience after posting" flow, so this is protection for admin tools / future features.

## 7. Chain screen UX (AddYoursChainActivity, rewritten)
- Real `Toolbar` (back arrow w/ touch target, TalkBack label "Back").
- Skeleton tiles (pulsing, 12 placeholders) while the first page loads — no more blank grid.
- Pull-to-refresh (SwipeRefreshLayout): reloads the grid + joined state; counter/chain doc are live anyway.
- Error state with Retry; 10s timeout on the first page because an offline Realtime Database read never fails, it waits.
  Later pages that fail keep the grid and toast "Couldn't load more stories"; scrolling down again retries.
- Stale callbacks from a previous load are dropped (`gen` counter), so Retry/refresh can't duplicate or reorder tiles.
- Tiles have content descriptions ("Story by X" / "Original story by X"); inner text views are hidden from TalkBack.

## 8. Resources instead of hardcoded text/colours
- `core/src/main/res/values/add_yours_strings.xml` — creator dialog, sticker "N people added" (plurals), toasts, chain screen
  (plurals for count and the "not shown" note). In :core so AddYoursHelper / sticker views / viewer / chain screen share it.
- `core/src/main/res/values/add_yours_colors.xml` — chain-screen palette (screen is always dark, no night variant).
- `AddYoursHelper.adding(ctx, originName)` = one localised "Adding to X's prompt" used by viewer, chain screen and reels.
- Still hardcoded (outside this flow, left alone): the sticker-picker card labels ("Add Yours" / "Start a prompt others can join")
  and the "➕ Add Yours sticker" labels in NewStatusActivity / ReelEditorActivity / ReelPhotoEditorActivity.

## Untested
No Gradle/network here: only brace/XML/JS syntax checks. Please build and check: skeleton → tiles, pull-to-refresh,
airplane-mode error + Retry, TalkBack on tiles, creator dialog texts, "N people added" singular/plural.
