# v237 — Chat List "Load time 378ms vs 150ms target" fix

## Diagnosis reported
- Load time avg/worst: 378ms ⚠️ (target 150ms, ~2.5x slow)
- Cold first-run session (11 rows) — cache-hit-ratio and pool-occupancy
  numbers ignored per report (expected to be low/zero on a cold run;
  they only become meaningful after a repeat report on a warm session).

## Root cause

`PerformanceMonitor` times the window from `ChatsFragment.onCreateView()`
(`markChatListLoadStart()`) to the first time real data reaches the
RecyclerView (`markChatListLoadEnd()`, inside `diffUpdateContacts()`).
Three separate issues stack inside that window on a cold app start:

1. **DB-open cost deferred onto the Chat List's own timed window.**
   `AppDatabase.getInstance()` (the singleton builder call) is cheap —
   just object construction. The real cost this app's own comments
   describe ("SQLCipher loadLibs() + Keystore + Room schema check —
   500ms to 3sec") only actually happens lazily, on whichever thread
   makes the **first real DAO query** — opening/creating the file,
   validating the schema hash across all ~20 entities, running through
   the migration chain. `CallxApp`'s existing "db-warmup" thread only
   ever called `getInstance()`, never a DAO method — so it warmed
   nothing real. That full cost silently landed on whichever screen the
   user opened first, which on a normal cold start is the Chat List
   (default tab) via `ChatsFragment.loadFromRoom()`'s otherwise-trivial
   11-row query.

2. **First-paint read sharing a pool with unrelated writes.**
   `loadFromRoom()` dispatched its query through `AppBgExecutor` — the
   same shared 4-thread pool that also carries folder-assignment writes,
   chat-delete writes, backup/export writes, and `ChatMessageSender`'s
   per-message-send Room insert (the pool's highest-frequency consumer).
   Any of those queued or in-flight at the moment the Chat List opened
   added straight to the measured load time, even though none of them
   are as latency-sensitive as the first screen's first paint.

3. **`AppDatabase.getInstance()` called on the main thread.**
   `loadFromRoom()` called it directly, before hopping to the background
   executor — a needless (if usually cheap) main-thread touch of the
   synchronized singleton, right in the same spot `ChatActivity` already
   documents as the exact anti-pattern to avoid.

## Fixes applied

**`CallxApp.java`**
- DB warm-up thread now also runs `db.chatDao().getChatCount()` — the
  cheapest possible real query — right after `getInstance()`. This forces
  the actual file-open + migration-validation cost to happen here, on a
  background thread, at process start, well before the user ever reaches
  the Chat List. Same technique already used for Glide warm-up just above
  it in the same file, now applied to Room's real first query too.

**New `core/utils/UiCriticalReadExecutor.java`**
- Dedicated, app-lifetime, never-shut-down single-thread executor
  reserved for "screen needs its first real data" reads — same safe
  shared-singleton idiom as `AppBgExecutor` (see v182 notes: this is NOT
  the per-call-executor leak pattern that fix targeted). Keeps
  latency-sensitive first-paint reads from ever queuing behind
  unrelated fire-and-forget writes.

**`ChatsFragment.loadFromRoom()`**
- `AppDatabase.getInstance()` moved off the main thread, into the
  background task.
- Query now dispatched via `UiCriticalReadExecutor` instead of the
  shared `AppBgExecutor`.

## Scope note
This pass covers the Chat List's own load path. `AppBgExecutor` remains
correct and unchanged for what it's meant for (writes, folder ops,
message sends, backups). If other screens show a similar "first paint
waits behind an unrelated write" pattern in their own diagnostics, route
their first read through `UiCriticalReadExecutor` too rather than adding
another one-off executor.

## Expected effect
- Chat List "Load time" should drop toward/under the 150ms target on a
  warm-enough start (DB-open cost already paid at app launch instead of
  screen load), and should no longer show occasional outliers caused by
  queuing behind unrelated background writes.
