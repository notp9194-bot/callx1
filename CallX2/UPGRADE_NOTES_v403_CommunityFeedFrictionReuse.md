# v403 — Community Feed: reused long-glide friction fix

## What changed
`RecyclerViewFrictionTuner.applyReducedFriction(rvFeed)` added right after
`setLayoutManager()` in `CommunityFeedFragment.java`.

## Why this was safe
Plain text+avatar vertical list, same shape as chat/comments. Its scroll
listener only drives `CommunityAvatarPreloader` (6-item-ahead avatar
prefetch), not a heavy canvas-render/height-cache/IDLE-triggered preload
pipeline like `ChannelViewerActivity` — that one stays untouched, as
discussed, until its Glide preloader + layout prewarmer timing is retuned
for the longer glide.

## Safety
Same contract: friction field found by type, reflection failure falls back
to stock friction silently, never crashes. `feature-chat` already depends
on `:core`.
