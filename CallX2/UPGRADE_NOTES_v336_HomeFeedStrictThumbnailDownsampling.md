# v336 — Home Feed: strict Glide decode-size caps (fast-scroll heat, part 2)

## Problem
Following v335's Glide pause/resume fling gate, the remaining heat source
during fast scroll was avoidable oversized bitmap decoding: several avatar
and image loads inside the main feed's row-bind path had no `.override()`,
so Glide decoded the *source* image's full resolution (often a multi-
megapixel profile photo or ad creative) before it ever got scaled down to a
32–90dp view. That decode work happens synchronously on Glide's bitmap
pool/decode threads every time one of these rows binds — including on every
card that races past during a fling.

Most of the codebase already followed this discipline correctly
(THUMB_DECODE_W/H, AVATAR_DECODE_PX, STRIP_THUMB_DECODE_PX all pre-existed
and were already applied almost everywhere) — this pass closes the gaps
that were missed:

## Fixed spots (all in HomeFragment.java, all part of the main feed
RecyclerView's row-bind path, so all directly relevant to fling heat)
- `SuggestedReelsTileAdapter` tile thumbnail (160×284dp) — new
  `SUGGESTED_TILE_DECODE_W/H` constants + `.override()`.
- Collab post's second avatar `av2` (32dp) and the shared `avatar` view's
  collab-photo branch (36dp) — both now use the existing `AVATAR_DECODE_PX`
  cap the solo-owner path already had.
- Sponsored row: 32dp sponsor avatar → `AVATAR_DECODE_PX`; the 220dp-tall ad
  image → capped to actual device width instead of the ad creative's raw
  resolution.
- Suggested-creators tile: 90dp avatar → `dpToPx(AVATAR_DP)`; the 12dp
  "N mutual" mini-avatar (previously the only avatar in the whole feed with
  *zero* cap) → `dpToPx(12)`.

## Scope
Every change is additive — an `.override()` (and, where the rest of the
feed already does it, `FEED_IMAGE_OPTS`) added to an existing Glide chain.
No load target, view, click behavior, or data flow changed. Two other
uncapped Glide loads found during the audit (a "Friends activity" row and a
followed-creators list) were left untouched — both build their views once
into a plain container rather than through RecyclerView recycling, so
they're not part of the fling-time bind path this pass targets.

Playback selection is untouched by this pass as well — same as v335,
nothing here touches `attachPlayerToCard`, `playMostVisibleCard`,
`currentPlayingIndex`, or `HomeFeedAutoplayPolicy`.
