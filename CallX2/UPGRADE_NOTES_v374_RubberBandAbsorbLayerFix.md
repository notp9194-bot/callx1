# v374 — RubberBandEdgeEffectFactory: hardware layer on absorb-only path

## Bug
`RubberBandEdgeEffectFactory` (pre-S manual rubber-band overscroll used by
`ChatActivity`, `GroupChatActivity`, `ChatsFragment`) was already at an
"ultra-advanced" v2 pass: non-linear iOS-style resistance curve, one shared
`SpringAnimation` per RecyclerView (zero per-release allocation), velocity
hand-off from the fling into the spring, and — since v241 — a hardware layer
toggled on only while the view is actually being translated, so idle scroll
never pays for a layer sitting on unnecessarily.

That hardware-layer toggle was only ever armed from `pull()` (the drag path).
A fast fling that reaches the edge without a preceding drag fires
`EdgeEffect.onAbsorb()` directly — `release()` was called with no layer ever
turned on. The resulting spring bounce-back (can run for hundreds of ms) then
re-rasterized and re-composited every visible message bubble's canvas content
on every frame of the settle, exactly the per-frame repaint cost the layer
toggle exists to avoid.

## Fix
`RubberState.release()` now calls `setHardwareLayer(true)` itself before
starting the spring, so both entry points — drag-then-release and
direct-fling-absorb — get the same cheap GPU-texture-transform bounce. The
existing end-listener still strips the layer the instant the spring settles
back to 0, so idle scroll is unaffected.

## Files touched
- `feature-chat/src/main/java/com/callx/app/chat/performance/RubberBandEdgeEffectFactory.java`

## Scope
Chat message list, group chat message list, and the chat list screen all use
this shared factory (`ChatActivity`, `GroupChatActivity`, `ChatsFragment`) —
all three benefit from this fix with no per-screen changes needed.
