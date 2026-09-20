# Advanced Chat Performance Upgrade — v5 + v6

## v5 — frame-level Firebase/decrypt callback batching

- Added `core/.../ChatUiEventBatcher.java`.
- 1:1 chat and group chat now queue post-decrypt UI work and drain it once at
  the next `Choreographer` frame instead of posting one main-thread Runnable
  per decrypted message.
- The decrypt executors remain off-main-thread and retain their existing FIFO
  guarantees. The batcher only changes delivery to the UI, not decrypt order.
- The batcher is cancelled from both chat Activities during teardown.

## v6 — incremental loaded-row updates

- Added `MessagePagingAdapter.applyRealtimeUpdate(Message)`.
- Status/tick, reactions, edit/delete content, and group receipt updates patch
  the already-loaded row with existing payload binds.
- Room still receives every update for persistence and sync.
- A keyset PagingSource refresh is now requested only for structural changes:
  new rows that are not currently loaded, deletes, and local inserts.
- 1:1 and group chat refresh requests remain debounced, so one Firebase burst
  produces at most one structural refresh.
- Group chat now uses the same lightweight refresh path instead of leaving its
  live pager refresh as a no-op.

No Gradle build, APK build, emulator test, or device test was run by design.