# Instant Chat Reopen (WhatsApp-Level) — Fix Notes

## Problem
Every chat open — even reopening a chat viewed 2 minutes ago — showed a
blank screen until the full chain finished:
`onDbReady() → Firebase buffer flush → Room Paging3 query → adapter submit`.

Root cause: `LastMessagesCache` (the in-memory warm cache) was being
maintained (seeded on every `onDbReady()`, upserted on every Firebase
event) but was **never submitted to the RecyclerView adapter**. It was
only used to preload image thumbnails. A prior fix had removed the
cache→adapter submission entirely to kill a duplicate-render flicker bug
(submitting cache, then submitting the real Room page, caused two full
adapter generations / two visible layout passes). That trade-off silently
turned every warm reopen into a cold-load-shaped experience.

## Fix
Restored the cache→adapter fast path (`seedInstantRenderFromCache()`,
called synchronously right after `setupPagingRecyclerView()` in
`onCreate()`), but fixed the actual flicker bug instead of avoiding it:

- The cached seed is built with `withDateSeparators()` — a helper that
  already existed in the file but was unused — which mirrors the real
  Paging3 pipeline's `insertSeparators()` transform exactly (same date-chip
  rules, same `sep_<timestamp>` ids).
- Because the cached generation and the real Room-backed generation are
  now **structurally identical** when nothing changed, `DiffUtil` finds no
  differences when the real page lands a moment later — one render pass,
  not two. If new messages *did* arrive while the chat was closed, the
  diff is a clean append/insert instead of a full rebuild.
- The cache submission is a raw `pagingAdapter.submitData(getLifecycle(),
  PagingData.from(list))`, completely outside `pagingMediator` (which
  doesn't exist yet at this point — it's built later in
  `observePagedMessages()`, once `onDbReady()` fires). When the real Pager
  attaches, its own `submitData()` call simply replaces this generation —
  the same swap-in-a-newer-generation pattern already used by
  `StarredMessagesActivity` elsewhere in this codebase.

## Side effects fixed for free
- `addOnPagesUpdatedListener()` (used to resume the postponed WhatsApp-style
  slide-in transition) already fires on *any* generation with items > 0, so
  a warm reopen now resumes the enter transition immediately instead of
  waiting on Room/Firebase.
- The shimmer loader is now only scheduled for genuinely cold opens
  (`!LastMessagesCache.getInstance().has(chatId)`) — warm reopens never
  show it, matching what the code comments already claimed but the code
  itself didn't do.

## Files changed
- `feature-chat/src/main/java/com/callx/app/conversation/ChatActivity.java`
  - `onCreate()`: replaced the "intentionally not submitted" block with a
    call to `seedInstantRenderFromCache()` + conditional shimmer scheduling.
  - Added `seedInstantRenderFromCache()` near `observePagedMessages()`.

No changes needed to `LastMessagesCache`, `MessagePagingAdapter`, Room DAOs,
or the Firebase sync path — they already did the right background work,
they just weren't being shown to the user.

## Follow-up correctness fixes in the delivered archive

- `LastMessagesCache` now normalizes the Firebase message key into both
  `Message.id` and `Message.messageId`. Firebase child listeners commonly fill
  only `id`, while the Paging adapter uses `messageId` for stable identity.
  Without this normalization, a direct warm render could treat the same
  message as an identity-less item and rebuild it when Room took over.
- Local-first text and media sends now update the process and disk snapshots
  immediately, before their asynchronous Room insert finishes. Success and
  failure status transitions, including failed media uploads, update the same
  warm entry. Group sends use the same path.

These changes close the short process-death window after a send and keep the
warm generation's item identity aligned with the Room-backed generation.
