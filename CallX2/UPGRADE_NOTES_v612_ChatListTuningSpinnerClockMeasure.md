# v612 — Media bubble items 5, 6, 7

Static changes only. Not compiled, not run on a device: build it, then use the new
benchmark / ChatJank log below to confirm the numbers.

## 5. RecyclerView tuning (feature-chat)
Correction to the earlier read: ChatActivity and GroupChatActivity already tune
`setItemViewCacheSize`, `setInitialPrefetchItemCount` (adaptive, via `AdaptiveChatScrollPolicy`),
`setHasFixedSize` and a shared `RecycledViewPool`. Those are left as they are.

What was actually wrong: the pool is process-wide (`MessagePagingAdapter.getSharedCanvasPool()`)
but each activity wrote its own sizes into it (1:1: 18 for the hot types; group: 10).
`setMaxRecycledViews()` with a smaller value trims holders already pooled, so opening a group
chat after a 1:1 chat dropped warm canvas holders and left the pool smaller.
`trimSharedCanvasPool()` also hard-coded 18 on restore.
- New `MessagePagingAdapter.configureSharedPool(ctx, pool)`: one table for all 12 view types,
  capacity is only ever raised, never shrunk. Low-RAM devices (`DeviceTier`) get 10/6/3
  instead of 18/10/6 (each canvas holder carries ~30 Paints + a RenderNode).
- `trimSharedCanvasPool()` restores to the tracked capacity.
- Both activities call the helper; the inlined size tables are removed.
- Side effect to check: group chat's pool for types 1/2/11/12 goes from 10 to 18 (normal tier).

## 6. Shared spinner clock
New `conversation/canvas/SpinnerClock.java`.
- Before: each indeterminate ring re-armed itself from inside `onDraw()`
  (`postInvalidateOnAnimation()` or `postInvalidateDelayed()`), one chain per bubble; a
  scroll-triggered draw re-armed it again, so chains could stack on one view.
- Now: `drawProgressRing()` registers the view (idempotent). One `Choreographer.FrameCallback`
  for all spinners, same 32 ms / 64 ms (low-RAM) cadence as before, invalidates every
  registered view in the same frame, stops itself when empty.
- Lifecycle is self-correcting: a view that drew no indeterminate ring since the last tick
  (download finished, rebound) is dropped on the next tick; `onDetachedFromWindow()` unregisters.
  No bubble state machine is duplicated in the clock.
- The Picture/RenderNode "static part + ring only" drawing is unchanged.

## 7. Measurement
- New `macrobenchmark/.../MediaBubbleScrollBenchmark.kt`: slow scroll, fling, fling without AOT
  profile on `rv_messages`. Metrics: FrameTiming, `Msg#bind`, `BubbleBitmap#decode`,
  `BubbleBlur#compute`, `SpinnerClock#tick` (Sum), heap. The target chat needs many photos.
- New trace sections: `BubbleBitmap#decode`, `BubbleBlur#compute`, `SpinnerClock#tick`
  (`Msg#bind` already existed).
- New `chat/performance/ChatJankMonitor` (JankStats, debuggable builds only, no cost at idle):
  `adb logcat -s ChatJank` prints frames / jank % / worst UI frame per scroll gesture, for 1:1 and
  group. Hooked into the existing `applyAdaptiveMessageRecyclerBudget()`; no new RecyclerView
  listener. New dependency in feature-chat: `androidx.metrics:metrics-performance:1.0.0-beta01`
  (could not be resolved here — no network; check the version you want).
- Baseline profile: rules appended for SpinnerClock, `configureSharedPool`, pipeline decode.

### Existing benchmark problems found (and fixed)
- Resource ids: all macrobenchmarks used `recyclerChats` / `recyclerMessages`; the real ids are
  `rv_chats` / `rv_messages`. The message-thread tests were waiting on a non-existent id and
  then falling back to "first RecyclerView on screen". Replaced across all files in the module.
  `btnSearch`, `btnAttach`, `recyclerMediaThumbs` etc. were not verified.
- `TARGET_PACKAGE` was a file-private const in four files and referenced, undefined, in five
  others (Kotlin does not share file-private top-levels). Now one `internal const` in
  `BenchmarkConstants.kt`.
- `baselines/jank_baseline.json` for `messageThreadScrollWithProfile` was captured with those
  ids, so treat it as unreliable. Re-capture it deliberately with
  `check_jank_regression.js --update-baseline`; I did not touch the numbers.
- Pre-existing `TraceSectionMetric` names such as `AudioWaveform#onDraw`, `MediaThumb#bind`,
  `ChatSearch#query` have no matching `Trace.beginSection` in the source I searched
  (feature-chat/core/app); they will report empty.

## Not done / not verified
- No compile, no Perfetto trace, no on-device numbers.
- A/B against the pre-v611 build on the same device and chat is still the real test.
