# v342 — Engagement-based dynamic repositioning for the 4 inline footer rows

## Gap being closed

v334 gave the 4 ex-footer rows (Trending / Suggested Creators / Friends
Activity / Continue Watching) cross-session persistence, a ✕-dismiss
cooldown, and a daily shown-count cap — but the cadence itself (when a row
first appears, how often it repeats, how many times/day it's allowed) was
still pure hardcoded static ranges. Every user saw Trending at post 3-4
whether they tapped into it every single time or scrolled straight past it.
Real Instagram promotes shelves a person actually engages with and quietly
backs off ones they ignore — per person, per shelf, not one fixed global
schedule.

## What changed

**`InlineFooterRowStore`** — new per-(user, row) engagement tracking,
alongside the existing dismiss/daily-cap state:
- `recordImpression(ctx, rowKey)` — lifetime impression counter, decayed
  (halved) once it passes 60 so the rate reflects *recent* behavior, not a
  lifetime average a months-old habit can never be corrected.
- `recordTap(ctx, rowKey)` — lifetime tap counter, incremented whenever the
  user opens something from that row.
- `getEngagementMultiplier(ctx, rowKey)` — smoothed tap-through rate
  (Laplace-blended against a neutral 12% prior so 1-2 impressions can't
  produce a wild swing) versus that neutral baseline, clamped to
  **0.5x – 2.0x**. `>1` = user engages with this row more than baseline
  (promote); `<1` = mostly ignored (demote). Never 0 — a row is spaced
  out further, not switched off, so it can always earn its way back.

**`HomeFragment`** — `maybeInsertTrendingRow()` / `maybeInsertTopCreatorsFooterRow()`
/ `maybeInsertFriendsActivityRow()` / `maybeInsertContinueWatchingRow()` now
run their existing base `AFTER_POST_MIN/MAX`, `REPEAT_MIN/MAX`, and
`MAX_PER_DAY` constants through `effectiveRowRange()` / `effectiveMaxPerDay()`,
which divide/multiply by that row's own engagement multiplier before picking
a target post count. The static constants stay as the neutral reference
point — a brand-new user with no engagement history yet gets exactly the old
v334 static behavior (multiplier = 1.0). `insertInlineFooterRow()` now calls
`recordImpression()` alongside the existing `recordShown()`.

Tap sites wired to `recordTap()`:
- Trending card → opens the reel
- Suggested Creators card tap → opens profile; Follow button → following
  (unfollowing isn't recorded — that's a correction, not engagement)
- Continue Watching card → resumes the reel
- Friends Activity row — **this row had no click handler at all before**,
  meaning it could never accumulate a positive signal and would have been
  permanently demoted regardless of relevance. Added a tap → opens the
  friend's profile (`from_uid`), same destination pattern as Suggested
  Creators.

## What this deliberately does not do

No scroll-dwell / "shown but ignored" instrumentation — there's no reliable
signal for "the user looked at this and chose not to tap" without adding a
visibility tracker to every row, so the multiplier is driven by
impressions-vs-taps only. This is a client-side heuristic, same honest
framing as `FeedRankingEngine` — it isn't an A/B experimentation framework
(no server-side arm assignment/variance testing), just per-user adaptive
cadence.
