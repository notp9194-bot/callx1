# v283 — Home Tab: Continue Watching Parallel Load

Same class of fix as v282's stories parallelization, applied to the second
(and last) remaining sequential-chain loader on the Home tab.

## The problem

`loadReelsByIds()` fetched each of the (up to 8) Continue Watching reels
**one at a time** — `reelId[i+1]`'s Firebase read only started after
`reelId[i]`'s read AND its card's render had both finished
(`loadReelsByIds(allIds, position + 1)` was called from the tail of
`addContinueWatchingCard()`). The full watch-history reelId list is already
known up front from the first query, so none of these per-reel reads
actually depend on each other.

## The fix

All (up to 8) reel reads now fire concurrently. Results land in an
index-addressed `slots` array (same technique as `collectStoryEntriesParallel`
in v282) so cards render in the original most-recently-watched-first order
regardless of which read happens to come back first. A shared `remaining`
counter (plain `int[]`, safe for the same reason as v282 — Firebase Android
callbacks land on the main thread one at a time) triggers rendering once
every read has resolved, including deleted-reel slots (`null`), which are
simply skipped.

`addContinueWatchingCard(reel)` no longer needs the `allIds`/`position`
params it used only to trigger the next sequential fetch.

## Net effect

Continue Watching strip render time drops from "sum of up to 8 round-trips"
to "the single slowest of 8 parallel round-trips" — on top of v281 (feed +
suggestion-strip recycling) and v282 (stories parallel load), this closes
out the last sequential-chain bottleneck among Home's six sections; every
section now either fires a single range query or fully parallel per-item
reads.
