# v335 — Home Feed: Instagram-level fast-scroll optimization (device heating / jank)

## Problem
Fast-flinging the Home tab feed (reels/posts) made the device heat up and
scroll noticeably janky. Root cause: every card that raced past during a
fling still went through the full `addFeedPostCard()` bind, which fires
several fresh Glide decode chains per card — thumbnail, avatar, story ring,
audio cover. During an aggressive fling that's dozens of decode jobs queued
in a couple of seconds, all competing with the scroll choreographer for
CPU/GPU time. That's the actual source of the heat and the jank, not video
playback — `HomeFeedScrollStateManager`'s own comments already identify
`SCROLL_STATE_SETTLING` (a fling still coasting) as "the highest-jank
window" where "ViewHolders are binding aggressively."

## Fix
Added a pause/resume gate on Glide's `RequestManager` (`pauseRequests()` /
`resumeRequests()` — Glide's own documented mechanism for exactly this
RecyclerView-fling scenario), wired into the existing consolidated
`RecyclerView.OnScrollListener.onScrollStateChanged()` in `HomeFragment`:

- `SCROLL_STATE_SETTLING` (fast fling coasting) → `pauseRequests()`. Queues
  new image requests instead of decoding them; already-decoded/cached images
  already on screen are untouched.
- `SCROLL_STATE_DRAGGING` / `SCROLL_STATE_IDLE` → `resumeRequests()`
  immediately. A slow drag (finger down, no real fling) is left alone —
  only the actual fast-scroll window is gated — and the instant the fling
  ends, every queued image fires immediately, so nothing is missing or
  delayed once the user stops.
- `onDestroyView()` calls `resumeRequests()` defensively so the view can
  never be torn down mid-fling and leave Glide paused for whatever screen
  loads next.

## Scope
~20 lines in `HomeFragment.java` only (the scroll listener + one line in
`onDestroyView`). Nothing about **which** card becomes the active/playing
one changed — `attachPlayerToCard()`, `playMostVisibleCard()`,
`currentPlayingIndex`, `HomeFeedAutoplayPolicy`, and every other
playback-selection path are untouched. Only the still-image decode work
happening *around* the video during a fling is deferred.
