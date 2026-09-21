# v444 — Feature 6 (reactor / poll-voter avatars) per-bind cost: memoized + zero-alloc rebind

## Before (per bind of a reacted row, group)
`formatReactions()` (LinkedHashMap + boxed Integers + StringBuilder + trim) + `bindReactionAvatars()` (uid[] + entrySet iterator +
key StringBuilder + N photo-map lookups/hashCodes + 2 lambdas). Poll rows: same for `bindPollVoters()`. Result identical on every
scroll pass unless reactions / votes / photos changed.

## Now
- `ReactionBound` (badge text + strip key + urls) and `PollVoterBound` (key + urls + overflow) are frozen per `Message`
  (`cachedReactionBound`, `cachedPollVoterBound` — transient, Firebase/Room skip).
- Validity = source-map identity + size + `photosEpoch`. New map from DiffUtil/Room/Firebase/`applyRealtimeUpdate` → identity differs;
  add/remove reaction or vote in place → size differs; profile photo change → `photosEpoch` bump
  (`setGroupMemberPhotos` / `onMemberPhotosChanged`). `photosEpoch` is separate from the seen-by epoch, so inserts/removes
  around a row do NOT invalidate its reaction/poll memo (they don't depend on neighbours).
- In-place mutation safety: `applyLocalReaction()` nulls the memo; live-vote path `bindPollOnly()` calls `bindPollVoters(..., force=true)`.
- Cache hit = 1 identity + 1 size + 1 int compare, then `cv.setReactions(cachedText)` / `setReactionAvatars(key,n)`; zero allocation.
- Removed the two per-bind functional-interface lambdas (`MiniStripSetter`/`MiniStripBitmapSetter`): `applyMiniStrip(kind, …)` calls the
  view directly; a lambda is created only when bitmaps actually have to be (re)requested.
- Legacy (non-canvas) reactions path unchanged (still calls `formatReactions` directly).

## Files
core/.../models/Message.java (2 transient slots), feature-chat/.../conversation/MessagePagingAdapter.java

Not compile-tested (no Android SDK/Gradle here) — brace balance checked only; run a normal build.
