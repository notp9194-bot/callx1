# v182 — Chat List "50 active threads / GC pressure" fix

## Diagnosis reported
- Active threads = 50 🔴 (Ultra Diagnostics screen, `Thread.activeCount()`)
- GC = 14 count / 469ms pause ⚠️, despite only 16MB heap in use

## Root cause

`Thread.activeCount()` is **process-wide**, not scoped to the Chat List
screen — so the "50 threads" number is really "every thread the app has
ever spun up and never shut down, still parked, anywhere in the session."
Same story for the GC counters (`Debug.getRuntimeStat("art.gc.*")`) —
process-wide since app start.

Root cause was a single anti-pattern repeated at ~20 call sites across the
chat/group/starred flows: `Executors.newSingleThreadExecutor()` (or
`newFixedThreadPool()`) either

1. created fresh **inline** on every call (`Executors.newSingleThreadExecutor().execute(...)`) —
   a brand-new pool + queue + non-daemon worker thread allocated every
   single invocation, none of it ever shut down, or
2. stored as an **instance field** on an Activity/Controller whose
   `onDestroy()` either never called `shutdown()` on it, or — in two cases
   — *couldn't*, because the field was typed as the bare `Executor`
   interface (no `shutdown()` method on that interface at all).

Every non-daemon thread an `ExecutorService` creates stays alive forever
until `shutdown()`/`shutdownNow()` is called — the pool being unreachable
from Java code does **not** free it. So normal usage (open a chat, open a
group, back out, repeat) permanently leaked threads throughout the
session, and each leaked thread is itself a burst of small object
allocations (`ThreadPoolExecutor`, `LinkedBlockingQueue`, `Worker`,
`Thread`) — which is exactly the "small, frequent allocations" pattern
the GC counters were flagging, on top of the growing thread count itself.

### Biggest single offender
`ChatMediaController.destroy()` already existed and correctly shut down
`mediaQueryExecutor` + the upload queue's cached thread pool — but
**`ChatActivity.onDestroy()` never called it.** Every chat opened and
closed leaked that thread pool permanently. This alone, across a normal
session opening a dozen-plus chats, accounts for most of the reported
thread count.

## Fixes applied

**`ChatActivity`**
- `onDestroy()` now calls `mediaController.destroy()` (was missing entirely).

**`ChatMediaController`**
- `destroy()` now also shuts down `mediaQueryExecutor`.
- `onChatResumed()` + 4 other inline `Executors.newSingleThreadExecutor().execute(...)`
  call sites routed through the shared `AppBgExecutor`.

**`GroupChatActivity`**
- `ioExecutor` retyped `Executor` → `ExecutorService` (the old type made
  `shutdown()` uncallable without a cast) and now shut down in `onDestroy()`.
- `attachMediaExecutor` now shut down in `onDestroy()` (was never shut down).
- One inline throwaway executor (DB-ready hop) routed through `AppBgExecutor`.

**`ChatBackupActivity`**
- `io` retyped `Executor` → `ExecutorService`.
- Activity had **no `onDestroy()` override at all** — added one that shuts
  `io` down.

**`FolderEditActivity`, `GroupsFragment`, `GroupInfoActivity`,
`StarredMessagesActivity`, `GlobalSavedMessagesActivity`**
- All inline `Executors.newSingleThreadExecutor().execute(...)` call sites
  (11 total across these five files) routed through `AppBgExecutor`.

**`ChatMessageSender`**
- All 7 inline throwaway executor call sites routed through
  `AppBgExecutor` — including the per-message-send Room insert, which was
  the highest-frequency offender (a new leaked thread on every single
  message sent).

**`AppBgExecutor`**
- Pool bumped 3 → 4 threads to absorb the newly-routed per-send traffic
  from `ChatMessageSender` without queuing behind unrelated
  folder/backup/export writes.

## Scope note
This pass covers `feature-chat` (chat list, groups, starred/saved,
conversation controllers) — the module the Chat List screen and its
reachable flows live in. The same `Executors.newXxx()`-without-shutdown
pattern also exists in `feature-reels`, `feature-status`, `feature-x`,
and `feature-youtube` (mostly notification handlers and editor
activities); those weren't touched here since they're outside what the
Chat List screen reaches, but they're the same class of bug and worth a
follow-up pass if those screens' own diagnostics show similar numbers.

## Expected effect
- Active-thread count should stop climbing across a normal session and
  return to a small, stable baseline instead of accumulating with every
  chat/group opened.
- GC count/pause time should drop correspondingly, since the dominant
  source of small, frequent allocations (fresh executor machinery per
  call/per screen-open) is gone.
