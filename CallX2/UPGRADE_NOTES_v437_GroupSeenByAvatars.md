# v437 — Group "Seen by" reader avatars under my sent messages

Group chat: under one of MY sent bubbles, small overlapping circles of the members who have read it
(right-aligned to the bubble's end edge). Tap the strip → existing `GroupReadByActivity` (Read / Delivered / Pending tabs).

The old code had this half-promised and never wired: `bindSeenByStrip()` was a no-op stub ("canvas renders its own
read-by overlay" — it never did), `memberPhotos` was stored but read nowhere, and `seenByClickListener` was set in
`GroupChatActivity` but never invoked. All three are live now.

## Which readers show where (Messenger-style)
A reader appears under the LAST of my messages they've read, not repeated under every older one:
row N shows `readBy(N)` minus everyone who is also in `readBy` of the next own row below (`selectSeenByReaders`).
- ≤5 readers → all as avatars; >5 → 4 avatars + grey "+N" chip (5 circles max). Earliest readers first.
- Only my own, canvas-rendered, non-deleted, non-view-once, non-call rows (`canShowSeenBy`). Legacy (non-canvas) sent
  bubbles have no strip — and are skipped as the "next own row" so readers are never dropped into a row that can't show them.
- Scan for the next own row is `peek()` only (no Paging load hints), capped at 80 rows; an unloaded row = "can't tell", nothing excluded.

## Canvas view (`MessageBubbleCanvasView`)
- New row BELOW the bubble + reactions badge (`SEEN_BY_*` constants, 14dp circles, 4dp overlap). Reserves height only while present:
  `hasSeenBy` is the single layout-affecting bit, keyed as `|SB1/|SB0` in `computeSizeSignature()`. Circle count / photos never re-measure.
- Drawn in `drawDynamicOverlayLayer()` (NOT baked into the cached RenderNode/Picture) → a late avatar / count change = dirty-region `invalidate(Rect)` only (`invalidateSeenByRegion`; a plain `invalidate()` would mark the whole cached bubble stale — see the override — so it's avoided; corrected in v438).
  Zero per-frame alloc: one cached `BitmapShader` per slot, re-aimed by a shared Matrix; flat grey circle while a photo is unresolved/absent.
- API: `setSeenBy(key, avatarCount, overflow)` (returns true if the shown set changed → caller loads bitmaps),
  `setSeenByAvatarBitmap(key, slot, bmp)` (dropped if `key` no longer matches → recycled/rebound rows can't get a wrong face),
  `clearSeenBy()`. `clearRecycledBitmaps()` also drops the strip's bitmaps and resets the key.
- Tap hit-target = strip ±8dp, only for a short tap (a long-press still opens the bubble's normal multi-select sheet).
  New `OnBubbleClickListener.onSeenByClick()`.

## Adapter (`MessagePagingAdapter`)
- `bindSeenByAvatars()` called from `bindCanvasMessage()` (clears itself on every non-eligible row) and from the `PAYLOAD_READ_BY` fast path.
- Avatar photos: same `groupMemberPhotos` map + same `ChatAvatarBinder.bindBitmap()` TIER_INLINE (24dp) as the sender avatar → shared L2/L3 entries.
  Strip key = `uid@photoHash,…+overflow`, so a changed profile photo alone re-requests just that bitmap.
- Prefetch: `runSenderAvatarPrefetch()` now also warms readers' photos from my own rows.
- **Staleness (the trap, same as the run-tail avatar):** row N's strip depends on the NEXT own row's `readBy`, which DiffUtil never rebinds.
  `refreshSeenByOwnRowAbove()` re-evaluates the nearest own row above whenever
  (a) a row's readBy changes — `applyRealtimeUpdate()` and new public `notifyReadByChanged(pos)`,
  (b) own rows are inserted (`rangeHasOwnRow`) or any row removed — the group `AdapterDataObserver`.
  `onMemberPhotosChanged()` also re-binds my rows whose readers' photos changed.

## GroupChatActivity
- Seen-by click handler accepts `m.id` OR `m.messageId` (paged rows can carry either).
- Message-Info live observer now calls `notifyReadByChanged(i)` instead of a bare `notifyItemChanged`.

Files: canvas/MessageBubbleCanvasView.java, canvas/OnBubbleClickListener.java, MessagePagingAdapter.java, group/GroupChatActivity.java
Not compile-tested here (no Android SDK/Gradle) — Java parse-checked only; run a normal build.
