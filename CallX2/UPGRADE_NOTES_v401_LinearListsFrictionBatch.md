# v401 — Batch reuse: long-glide friction on 9 more linear list screens

## What changed
`RecyclerViewFrictionTuner.applyReducedFriction(rv)` (core, from v399) added
right after `setLayoutManager(new LinearLayoutManager(...))` in:

- `AllContactsActivity.java`
- `ContactsActivity.java`
- `RequestsActivity.java`
- `NotificationCenterActivity.java` (`rvNotifs`)
- `BlockedUsersActivity.java` (`rvBlocked`)
- `GroupInfoActivity.java` (`rvMembers` only — `rvMedia` is a grid, left untouched)
- `GroupReadByActivity.java`
- `LinkedDevicesActivity.java`
- `MutedChatsActivity.java`

## Why these are safe
All nine are plain single-column vertical row lists (contacts, requests,
notifications, blocked/muted users, group members, linked devices) — same
shape as chat/comments, no grid tap-precision issue, no autoplay or
velocity-sensitive pagination/preloader logic to retune.

## Explicitly NOT touched in this pass
Grids and velocity-sensitive screens flagged earlier stay on stock friction
until their pagination/preloader/autoplay logic is retuned: reels/profile
grids (`UserReelsActivity`, `SoundDetailFragment`, `PostsFeedActivity`,
`ReelGridAdapter`), `HomeFragment`/`XHomeFragment`/YouTube home & shorts,
`WatchHistoryActivity`, `SavedReelsActivity`, media grids, and small
bounded/nested pickers (mention suggestions, attach-sheet folder picker,
etc.) where there's no meaningful glide distance to begin with.

## Safety
Same contract throughout: friction field found by type, reflection failure
silently falls back to stock friction, never crashes. No new module
dependencies — `app` and `feature-chat` already depend on `:core`.
