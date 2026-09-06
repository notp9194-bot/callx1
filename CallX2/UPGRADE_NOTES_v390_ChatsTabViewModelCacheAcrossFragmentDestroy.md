# v390 — Chats Tab: real root cause of "screen open slow" fixed

## Root cause

`MainActivity` sets `binding.viewPager.setOffscreenPageLimit(1)` (see the
"FIX #LAZY" comment in `MainActivity.onCreate`) as a memory optimization —
only the tab adjacent to the current one stays alive.

Once ViewPager2 has an *explicit* (non-default) `offscreenPageLimit`, its
`FragmentMaxLifecycleEnforcer` doesn't just detach the View of far-away
tabs — it fully **destroys the Fragment instance** for any tab more than
`limit` pages away. Chats is tab 0. Navigate to Groups (3) or Calls (4) and
`ChatsFragment` itself gets destroyed, not just its View.

Every previous "WhatsApp-level" chat-list fix (v210 instant snapshot, v386
warm tab-switch repaint, v387 listener persistence, v388 scroll position,
v389 color cache) optimizes bind/scroll/redraw cost, and the v386-388 chain
specifically assumes the **Fragment instance survives** and only its View
is recreated. That's true for a *nearby* tab switch, but false the moment
the user is 2+ tabs away — at which point a brand-new `ChatsFragment` is
created with an empty `contacts` list, and the screen falls all the way
back to `loadFromRoom()`'s encrypted (SQLCipher) DB read and a full
Firebase listener re-attach (which replays `onChildAdded` for the whole
live-sync window — a real network round trip). That's the actual "chats
tab abhi bhi slow" the user kept seeing despite every prior fix landing
correctly.

## Fix

Added a plain in-memory cache (`cachedContacts`) to `ChatListViewModel`,
which is already scoped to `requireActivity()` (see
`RecyclerViewPoolViewModel` for the same pattern already used for the
RecyclerView pool). This ViewModel survives exactly the Fragment
destroy/recreate cycle described above — it's only cleared when
`MainActivity` itself is destroyed (e.g. logout), which already tears the
whole ViewModel down.

- `ChatsFragment#diffUpdateContacts()` now mirrors `contacts` into
  `viewModel.setCachedContacts(...)` every time it updates (same place it
  already mirrors into `ChatSnapshotCache` for cold-start).
- `ChatsFragment#onCreateView()`'s `contacts.isEmpty()` branch now checks
  `viewModel.getCachedContacts()` **before** falling back to
  `ChatSnapshotCache`'s SharedPreferences snapshot. It's real, already-
  decrypted `User` objects sitting in RAM — no JSON parse, no disk I/O —
  so a freshly-recreated `ChatsFragment` repaints the exact list the user
  was just looking at, instantly, the same way the v386 warm-repaint path
  already does for the Fragment-survives case.

`loadFromRoom()` / Firebase listener re-attach still run underneath as
before (unavoidable — it really is a new Fragment instance) but no longer
gate the first paint.

## Not yet verified on a real device

This is a static-analysis fix — this environment has no Android
SDK/emulator to build and profile against. Before trusting it fully:
confirm on-device that navigating Chats → Calls → back to Chats now
repaints instantly instead of showing the placeholder/blank state, and
that `ChatListViewModel`'s activity scope is correctly cleared on
logout (no stale contacts flashing for a different account — this should
already hold since the ViewModel is never persisted to disk, but worth an
explicit check given `ChatSnapshotCache` needed its own explicit
`clearSnapshotAsync()` call for the same scenario).
