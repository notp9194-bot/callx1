# v372 — Voice-recording dot blink: hardware layer during the animation

## The problem

`ChatMediaController.startDotBlink()` drives the red recording-indicator
dot with an `ObjectAnimator` on `View.ALPHA`, `REPEAT_COUNT.INFINITE`, for
the entire length of a voice recording. No layer type was ever set on the
dot, so every single blink frame re-ran a full software
invalidate→measure→draw dispatch of that `ImageView` — at the exact same
time the waveform view is already repainting on every amplitude tick.
Two independent invalidation sources competing for the same 16ms frame
budget for as long as the user is recording.

## The fix

`startDotBlink()` now switches the dot to `LAYER_TYPE_HARDWARE` before
starting the animator, so Android caches it as a GPU texture once; every
alpha-only frame after that is a cheap composited blend instead of a
redraw. `stopDotBlink()` releases it back to `LAYER_TYPE_NONE` the
instant the blink stops (recording ends/cancels), so an idle dot never
keeps a GPU texture pinned. Same pattern already used elsewhere in this
app for other infinite/long-running property animations (see
`MessagePagingAdapter.playSendInAnimation()`'s send-in spring and the
RecyclerView fling hardware-layer switch in `ChatActivity`).

The dot itself is tiny (10dp), so the texture cost is negligible — this
is purely about removing redundant software draw work while recording,
not a memory tradeoff.
