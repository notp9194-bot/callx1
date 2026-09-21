# v10 Ultra Chat Hot-Path Pass

## Included

- Reworked `ChatUiEventBatcher` to swap reusable pending/drain queues at
  frame boundaries instead of copying the whole pending queue into a new
  `ArrayDeque` on every frame. Posts arriving while callbacks drain remain
  isolated in the next-frame queue, preserving ordering and message-keyed
  replacement behavior.
- Reused one frame-post `Runnable` and one `Choreographer.FrameCallback` in the
  batcher instead of allocating new callback lambdas for each scheduled frame.
- Removed same-state `requestLayout()`/invalidate work from recycled canvas
  holders for quick-forward visibility, deleted style, expiry text, group
  sender labels, and forwarded labels. Real content/state changes still take
  the existing relayout or dirty-region path.
- Reused `ChatActivity`'s existing main-thread scheduler for the postponed
  transition safety timeout, with explicit cancellation in `onDestroy()`
  instead of allocating a throwaway `Handler` on every chat open.

## Safety and measurement

- Existing canvas bind ordering, cache invalidation, message-type routing,
  lifecycle guards, and shared-pool listener ownership were preserved.
- No Gradle build, APK build, emulator/device benchmark, or automated test
  was run. This archive is source-audited only; performance proof still
  requires the user's own build and target-device benchmark.