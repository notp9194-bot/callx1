# v441 — Features 1/6/7/8: wired into the core avatar prefetch pipeline

Follows v10 (join/leave avatar), v434 (batch prefetch + run-tail), v437 (seen-by),
v439 (reactor/poll-voter avatars), v440 (member message filter). All four already
bound their avatars through `ChatAvatarBinder.bindBitmap()` (shared L2/L3, same
TIER_INLINE as the group sender avatar) — what none of them had yet was
**prefetch**: their bitmaps were only ever requested cold, at the moment their
row/dialog/sheet actually bound. This pass warms all four ahead of that moment,
reusing the exact request-building code paths already in place instead of adding
new ones.

## #6 — Reaction badge + poll voter avatars now batch-prefetched

`MessagePagingAdapter#runSenderAvatarPrefetch()` (the same coalesced batch that
already warms run-tail sender avatars and "seen by" reader avatars) now also
scans each row's `reactions` (first `MINI_STRIP_REACTORS`, matching
`bindReactionAvatars()`) and non-anonymous `pollVotes` (up to
`SEEN_BY_MAX_CIRCLES`, matching `bindPollVoters()`), resolves each uid through
the live `groupMemberPhotos` map, and folds the results into the same
`ChatAvatarBinder.prefetchBatch()` call. Same dedup set
(`prefetchedSenderPhotoUrls`), same 24-photo cap, same coalesce window — this
is strictly more entries in one existing batch, not a second batch.

Net effect: reaction badges and poll-footer voter strips are already-decoded
by the time their row scrolls into view or the "Reactions" / "Poll votes"
dialog opens, instead of decoding on demand.

## #8 — Join/leave row avatar now batch-prefetched

Same scan also special-cases `"system"` rows with a non-empty `eventUid`
(Feature 8's join/leave rows): `eventPhoto` sits directly on the message (set
once at post time), so it's added straight to the prefetch batch with no
`groupMemberPhotos` lookup needed. These rows are rare, so this is a cheap
addition, but it means scrolling up through history no longer cold-decodes the
first join/leave chip that scrolls into the buffered window.

## #1 — "View photo" now warm before the tap

`DialogFullscreenHelper#showAvatarZoom` (the fullscreen viewer #1 reuses)
deliberately loads the **raw**, un-tiered `photoUrl` — a zoomable full-screen
photo needs original resolution, not one of `ChatAvatarBinder`'s CDN-resized
TIER buckets, so it was never going to be an L2/L3 hit off the small avatar's
own cache entries (different URL → different Glide cache key).

New `ChatAvatarBinder.prefetchFullPhoto(ctx, photoUrl)` — issues that same raw
URL as a bytes-only (`DiskCacheStrategy.DATA`), `Priority.LOW` Glide preload.
`GroupChatActivity#showMemberActionSheet()` fires it right when the small
header avatar binds, alongside the existing `ChatAvatarBinder.bind()` call —
by the time the user reads the sheet and taps "View photo", the full photo's
bytes are already sitting in Glide's disk cache (or an in-flight request Glide
coalesces the real load into), so the viewer decodes from disk instead of
paying a cold network round-trip. Never competes with the small avatar's own
HIGH-priority bind — explicit LOW, same rule every other prefetch path in the
app already follows.

## #7 — Member filter jump now warm before it lands

"Messages from `<member>`" is the whole point of the filter — it jumps to
messages that are very often *outside* `runSenderAvatarPrefetch()`'s normal
± viewport scan window, so the first jumped-to row could still cold-decode.

New `MessagePagingAdapter#prefetchMemberFilterAvatar(uid)` — one-photo call
into the same `ChatAvatarBinder.prefetchBatch()` pipeline (same dedup set, so
it's a no-op if that member's avatar was already warmed). Called from
`GroupChatActivity#openMemberMessageSearch()` the instant the filter opens, so
the jump target's avatar is (near-instantly, given it's almost always already
an L2 hit from elsewhere in the chat) ready before `ChatSearchController`
even finishes resolving the first match.

## Files
- `feature-chat/.../conversation/MessagePagingAdapter.java` — `runSenderAvatarPrefetch()`
  extended; new `prefetchMemberFilterAvatar()`
- `feature-chat/.../cache/ChatAvatarBinder.java` — new `prefetchFullPhoto()`
- `feature-chat/.../group/GroupChatActivity.java` — `showMemberActionSheet()` and
  `openMemberMessageSearch()` now call into the above

## Not touched
Nothing about *what* renders changed — no new avatar surfaces, no layout changes.
This is purely moving existing decode work earlier (prefetch) instead of leaving
it on the bind-time critical path, for the four features that didn't have that yet.

Not compile-tested here (no Android SDK/Gradle in sandbox) — Java parse-checked
only (brace balance confirmed); run a normal build.
