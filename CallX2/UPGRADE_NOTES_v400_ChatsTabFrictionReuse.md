# v400 — Chats Tab: reused chat-bubble long-glide friction fix

## What changed
`RecyclerViewFrictionTuner.applyReducedFriction(rv)` (core, extracted in
v399 from `FastFlingRecyclerView`'s v4 reflection fix) is now also applied
to `ChatsFragment.java`'s `rvChats` — the conversation list itself — right
after `ChatListLayoutManager` is set.

## Why this screen is a good fit
Same reasoning as v399's reel-comments reuse: the chat list is a plain
single-column text+avatar list, same shape as chat message bubbles and
comments — no grid tap-precision issue, no autoplay-decision timing like
Home feed.

## Known interaction (not a bug)
`GlideScrollListener` (already attached to `rvChats`) pauses avatar image
loads while the list is scrolling and resumes them on `SCROLL_STATE_IDLE`.
Because the list now glides longer before going idle, avatar thumbnails
will visibly pop in a bit later after a fling settles — this is expected
and was called out before implementing; no separate fix needed unless it
looks off in practice.

## Safety
Same contract as v399: friction field found by type, every reflection
failure silently falls back to stock friction, never crashes. `feature-chat`
already depends on `:core`, so no new module wiring was needed.
