# v382 — Message-level model cache (item 3: Paging + Canvas/media rebind)

## Problem
`ChatActivity.entityToModel()` / `GroupChatActivity.entityToModel()` run inside
`PagingDataTransforms.map(pagingData, ioExecutor, ...::entityToModel)` — called
on every page Paging (re)loads from Room. `MessageKeysetPagingSource`'s
anchor-REFRESH (see its `MAX_PRESERVED_BEFORE_CONTEXT` / `MAX_BOTTOM_CATCHUP_AFTER`)
can re-load several hundred rows on a single send/receive while a chat stays
open — and every one of those rows was fully remapped every time, even though
normally only 1-2 messages actually changed:

- `MessageEntityMapper.toModel()` (or GroupChatActivity's inline equivalent):
  a fresh `Message` allocation + ~90 field copies.
- Up to 6 JSON re-parses per message: reactions, both group receipt maps,
  poll options + votes, media items, edit history.
- A full pass through every `precompute*IfPossible()` Canvas-layout call.

## Fix
Added `core/utils/MessageModelCache` — a static, id-keyed cache (LRU-capped at
1500) storing the last-built `Message` per message id alongside a cheap
"fingerprint" of only the fields that can change after insert (tick status,
delivered/read receipts, edits, reactions, poll votes, view-once state,
in-flight local media path, caption, deletion, star/pin, forward, topic).
Fields that never change post-insert (timestamp, senderId, type, ...) are
deliberately excluded so they can never cause a false "changed" verdict.

`entityToModel()` in both `ChatActivity` and `GroupChatActivity` now calls
`MessageModelCache.getOrMap(entity, ...::buildModelUncached)`: on a
fingerprint match, the previously-built `Message` is returned directly — no
remap, no JSON parsing, no new allocation, no precompute calls. The real
mapper (renamed to `buildModelUncached()`) only runs on an actual change or a
first-time id.

Shared across both activities since message ids are globally unique — no
chatId component needed, no cache-clear on chat switch required.

## Combines with items 1 & 2
- Item 1 (Room `InvalidationTracker` scoped to current chat) and item 2
  (batched Firebase → Room writes) cut down how *often* a refresh fires.
- Item 3 cuts down how much work each refresh actually does once it fires,
  by making a refresh over mostly-unchanged history close to free at the
  mapping layer.
