# Reel Seen — v21 Upgrade Notes
## "Out of messages tree" refactor

### Problem (v20 and earlier)
`reel_seen` events were written as real rows into `messages/{chatId}`.
This caused:
- **Warm-cache / pagination pollution** — every `reel_seen` row occupied a slot in `limitToLast(30)` / the 20-msg warm-cache, pushing real text messages out.
- **DB growth** — permanent rows in `messages/{chatId}` with zero expiry / cleanup.
- **Room overhead** — full sync listener, Room upsert, PagingSource invalidation per event.
- **RecyclerView overhead (viewer side)** — a 0×0 `TYPE_HIDDEN` ViewHolder was still bound and recycled for every event the viewer sent.

### Solution (v21)
`reel_seen` events are now written to a **dedicated side-tree**:

```
reelSeenEvents/{ownerUid}/{viewerUid}/{eventId}
  senderId      — viewer UID
  senderName    — viewer display name
  senderPhoto   — viewer photo URL
  reelId        — reel Firebase key
  reelThumbUrl  — reel thumbnail URL
  timestamp     — ServerValue.TIMESTAMP
```

`messages/{chatId}` is **never written to** by `ReelSeenTracker` anymore.

### What changed

| File | Change |
|---|---|
| `ReelSeenTracker.java` | Writes to `reelSeenEvents/{ownerUid}/{viewerUid}` instead of `messages/{chatId}` |
| `ChatReelSeenController.java` | **NEW** — listens to `reelSeenEvents/{myUid}/{partnerUid}`, injects synthetic `Message` objects into the adapter at display-time only |
| `MessagePagingAdapter.java` | Added `setSyntheticReelSeenRows()`, `getItemAt()`, overrode `getItemCount()` to append synthetic tail; replaced `getItem(position)` → `getItemAt(position)` in `getItemViewType` / `onBindViewHolder` / audio callbacks |
| `ChatActivity.java` | Declares + constructs `ChatReelSeenController`; calls `attach()` on `onResume()`, `detach()` on `onPause()` |
| `ChatViewModel.java` | Filters `"reel_seen"` type in `onChildAdded` / `onChildChanged` — legacy rows in old chat trees are silently skipped |
| `firebase_chat_rules.json` | Added `reelSeenEvents` + `reelSeenDedup` rule nodes |

### Firebase rules added

```
reelSeenDedup/{viewerUid}/{reelId}  → Number (ms timestamp)
  read/write: own UID only

reelSeenEvents/{ownerUid}/{viewerUid}/{eventId}
  read:  ownerUid only (auth.uid === $ownerUid)
  write: viewerUid only (auth.uid === $viewerUid)
```

### Backward compatibility
- Old `reel_seen` rows already in `messages/{chatId}` are silently ignored by `ChatViewModel` (filtered by type). They will not appear in the adapter.
- `reelOwnerUid`, `reelId`, `reelThumbUrl` fields remain in `Message.java` and `MessageEntity.java` — no Room migration needed since no new rows of this type will be written.

### Adapter design (synthetic injection)
`MessagePagingAdapter` now has a parallel synthetic list appended **after** all paged rows. `getItemCount()` is overridden to return `super.getItemCount() + syntheticReelSeenRows.size()`. A private `getItemAt(int)` method routes positions ≥ `super.getItemCount()` to the synthetic list; all other internal calls (`getItemViewType`, `onBindViewHolder`, audio callbacks) use `getItemAt` instead of `getItem`. The `reel_seen` bind path in the adapter is **unchanged** — synthetic rows are shaped identically to old Firebase rows, just sourced from memory instead of Room.

### Performance gains
- **Zero Room writes** from reel_seen events.
- **Zero messages/{chatId} writes** — warm-cache and pagination slots fully available for real messages.
- **Viewer side**: no RecyclerView bind at all (the synthetic list only lives on the owner side — `ChatReelSeenController` only attaches as owner).
- **DB size**: permanent growth from reel_seen events stops. Existing rows remain but are skipped.
