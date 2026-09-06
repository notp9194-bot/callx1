# v399 — Reel Comments: reused chat's long-glide friction fix

## What changed
`FastFlingRecyclerView`'s v4 fix (feature-chat) lowers a RecyclerView's
internal `OverScroller` friction via reflection (0.015 → 0.007) so a fling
decelerates over a longer distance — the actual physics behind Telegram's
long-glide feel, independent of launch velocity.

That logic is now extracted into a reusable utility,
`core/utils/RecyclerViewFrictionTuner.java`, so a plain `RecyclerView` (no
custom subclass needed) can opt in with one call:

```java
RecyclerViewFrictionTuner.applyReducedFriction(rvComments);
```

Wired into `ReelCommentFragment.java`'s `rvComments` (shared by both
`ReelCommentActivity` and `ReelCommentSheetFragment` — see that class's
"single source of truth" javadoc), right after `setLayoutManager()`.

## Why comments and not reels grid / Home feed
Comments are a plain vertical text list — closest match to chat's own
use case. No pagination-trigger or Glide `RecyclerViewPreloader` velocity
assumptions to retune, unlike:
- **UserReelsActivity / SoundDetailFragment** — 3-column thumbnail grids;
  longer glide risks blank-flash cells (preloader window sized for the old
  friction profile) and hurts tap precision on a specific thumbnail.
- **Home feed** — `HomeFeedAutoplayPolicy` decides the "most visible card"
  on scroll-IDLE; a longer glide delays that decision, and
  `HomeFeedPrefetchManager` / `NetworkBatcher` are tuned for the old
  velocity/friction profile.

Both remain on stock friction for now — reuse there needs prefetch/
pagination retuning first (see chat discussion), not just dropping in the
same utility call.

## Safety
Same contract as the original: field found by type (not a hardcoded AndroidX
field name), every reflection failure silently falls back to stock
friction — never crashes. Verified `feature-reels` already depends on
`:core`, so no new module dependency was needed.
