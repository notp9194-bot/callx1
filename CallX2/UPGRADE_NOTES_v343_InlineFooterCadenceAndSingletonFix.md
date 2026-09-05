# v343 — Cadence lock-in, Continue Watching one-shot, singleton-row gap fix

## What was asked

Confirm/lock the 4 inline footer rows' first-appearance cadence, make
Continue Watching a true one-shot (never repeats), and close the "single
shared view instance" gap flagged against the v342 architecture.

## Cadence (unchanged, now just confirmed)

`TRENDING_ROW_AFTER_POST_MIN/MAX` etc. already matched the requested
ranges from v334 onward:
- Trending → post 3–4
- Suggested Creators (Top Creators footer block) → post 8–10
- Friends Activity → post 15–18
- Continue Watching → post 20–25

No change needed here; engagement-based scaling (v342) still applies on
top of these as the neutral reference point.

## Continue Watching → one-shot

`maybeInsertContinueWatchingRow()` no longer re-arms a repeat target after
firing. A new `continueWatchingRowFired` flag latches true the moment it
actually inserts, and every later call short-circuits immediately. If its
window is reached while blocked (daily cap / ✕-dismiss cooldown still
active from an earlier session), it keeps retrying on each following post
exactly as before — but the instant it succeeds once, it's done for the
rest of that scroll session. `CONTINUE_WATCHING_ROW_REPEAT_MIN/MAX` are
left in the file (now unread) rather than deleted, so their absence isn't
mistaken for an unrelated change.

## Gap fix — single shared view instance

All 4 rows are backed by one lazily-built singleton View per type
(`trendingRowView` / `topCreatorsRowView` / `friendsActivityRowView` /
`continueWatchingRowView`), same pattern as `headerView`/`footerView`.
That only works if at most one `FeedRow` of a given type exists in
`feedItems` at once — RecyclerView can't bind the same View object to two
ViewHolders simultaneously. Since Trending/Suggested Creators/Friends
Activity are recurring (v334) and a previous instance is only ever removed
by explicit ✕-dismiss (never automatically once scrolled past), a long
session could end up with an old, still-present instance sitting above a
freshly-inserted repeat — two `FeedRow`s of the same type, one singleton
View, a crash waiting for the right layout/fling timing.

`insertInlineFooterRow()` now calls `removeFeedRowByType(rowType)` before
adding the new instance, so a repeat always **replaces** the previous
occurrence instead of ever being able to duplicate alongside it. This is
the minimal fix that makes the existing singleton-View pattern safe again
for a recurring row — it deliberately does not attempt true concurrent
multi-instance rendering (two Trending shelves visible on screen at once),
since nothing in the current design calls for that: real Instagram
replaces a shelf on repeat too, it doesn't stack a second copy above the
first.

If simultaneous multi-instance rendering of the same row type is ever
actually wanted, that needs a deeper change — each row's child-view refs
and card-pool (`trendingCardPool`, `friendsActivityCardPool`, etc.,
currently fragment-level fields) would have to move onto a per-ViewHolder
object built fresh in `onCreateViewHolder`, with `loadTrending()` /
`loadFriendsActivity()` / `loadContinueWatching()` / `loadSuggestedCreators()`
targeting a specific instance's data instead of the shared
`cached*ForRow` fields. Flagging this here rather than doing it silently,
since it's a materially bigger change than what was asked for this round.
