# v466 — Reel comments: haptics + error/offline states

## Haptics (new `CommentHaptics`, 3 verbs: confirm / tick / reject)
Uses View#performHapticFeedback (respects system touch-feedback setting, no permission).
API 30+ uses CONFIRM / REJECT, older falls back to VIRTUAL_KEY / LONG_PRESS.
- Like (comment + reply, heart tap AND double-tap): confirm on like, tick on un-like.
  Fired at tap time in toggleLike()/toggleReplyLike(); rolled-back like => reject.
  (The pop animation on the heart already existed: popHeart() in ReelCommentsAdapter.)
- Reaction pick: confirm (tick when removing). Write failure => reject + toast.
- Post comment / reply: confirm the moment the local-first bubble appears (not after the
  server ack, so it lines up with what the user sees). Send failure => reject. Retry tap => tick.
- "Commenting too fast" cooldown => reject.

## Error / offline states
Realtime DB never calls back while offline, so failures are detected via timeout (10s online,
3s offline) + a connectivity watcher, not only onCancelled.
1. Full-screen error (`layout_error_state`): first page failed/timed out AND nothing to show.
   Offline vs generic wording, Retry button (48dp). Auto-retries when the network returns.
2. Status banner (`tv_status_banner`): rows are on screen (live or disk cache) but offline /
   refresh failed. Non-blocking; "refresh failed" variant is tappable.
3. "Load older" failure: the "Loading earlier comments…" chip turns into a tappable
   "Couldn't load earlier comments · Tap to retry" (48dp). Scroll/viewport-fill triggers are
   blocked while failed (no hammering). Auto-retry on network return. Per-request ids so a
   late result of a given-up request is never applied on top of a retry.
4. Replies load failure: toggle text becomes "Couldn't load replies · Tap to retry"
   (tapping runs the normal expand path). Silent if the thread is already open (background refresh).

Bug fixes found on the way:
- loadComments().onCancelled used to call showEmpty(true) => a load FAILURE showed
  "No comments yet. Be the first to comment!". Now shows the error state / banner.
- Empty state is now gated on `firstPageReady` (new one-shot single-value read on the same
  query = also the only reliable "thread is empty" signal, since a ChildEventListener on an
  empty node never fires). Stops the blocklist listener answering first and flashing
  "Be the first…" over the skeleton / offline screen.
- showEmpty() now also clears the error state, so late data always recovers the UI.

Files: CommentHaptics.java (new), ReelCommentFragment.java, activity_reel_comment.xml,
strings_reel_comments.xml (10 new strings), version.properties (124).
Needs ACCESS_NETWORK_STATE (already in :app manifest; watcher is best-effort/try-caught).
