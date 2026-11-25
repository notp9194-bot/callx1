# v869 — View-once tombstone fixes

1. MessagePagingAdapter: `if (isDeleted)` -> `if (isDeleted && !isViewOnceMsg)`. Opened/Expired/Removed card now renders (was "This message was deleted").
2. ViewOnceExpiryWorker: also wipes `mediaItems` (multi_media view-once stayed readable in Firebase).
3. ChatViewOnceController: pending deletes persisted in SharedPreferences `view_once_maint`; flushed on next chat open (`runMaintenance()`), survives app kill.
4. ChatViewOnceController: markOpened + hardDelete merged into one `markOpenedAndWipe()` updateChildren.
5. Tombstone TTL: view-once tombstones older than 7 days removed from Firebase (max once/day/chat, 50 per run). Room rows kept. New DAO query `getViewOnceTombstoneIdsOlderThan`.
6. ChatActivity: bg prefs check -> controller built only when maintenance is due.
7. MessagePagingAdapter.DIFF: areContentsTheSame/getChangePayload now compare `deleted`, `viewOnceState`, `openedAt` (media view-once with empty text never rebound on open->deleted flip). applyRealtimeUpdate copies viewOnceState/openedAt.
8. MessageDao.softDelete/softDeleteAll: `AND (deleted IS NULL OR deleted = 0)` -> already-deleted rows are no-ops (no Room invalidation, no paging refresh, expired/revoked label no longer overwritten with 'deleted'). New softDeleteViewOnce(id, state) used for expire/revoke.
9. MessageBubbleCanvasView.bindViewOnce: identical-rebind early return (same variant/labels/time/sent, not dirty) -> no invalidate()/re-record on chat open, scroll-back, tick payload.
10. MessageBubbleCanvasView.setDeliveryStatus: view-once / seen / call-entry cards skip invalidateExpiryRegion (they never draw ticks; footer is baked in the cached node, so a status flip forced a full re-record).
