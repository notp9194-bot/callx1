# v950 — Calls tab header perf pass

Calls tab (CallsFragment) is index 3 of the ViewPager2, so its view is rebuilt on every tab return. What was
allocated / drawn / re-recorded on each open, and what changed:

**Online panel (biggest)**
- Was: a listener on every contact's WHOLE `users/{uid}` node; each initial callback ran `renderOnlineUsers()` =
  `removeAllViews()` + re-inflate every online user + avatar re-bind + new listeners + new ArrayList. N contacts => N full
  rebuilds per open, and again on any field change of any contact.
- Now: listener only on `users/{uid}/online` (boolean). Contact add/remove is diffed (no detach/re-attach of all).
  Status events coalesce into ONE render (60 ms). Render is a diff: add/remove only changed users, name/avatar updated
  in place only when different, click listener reads a live holder. Panel is a ViewStub (inflated on first online user);
  visibility only set on change. Missing name/photo => one single-shot read, only for that user.

**Call list**
- `notifyDataSetChanged()` (re-bind + re-draw every visible row) replaced by DiffUtil (no moves, change-animations off);
  chip tap / keystroke / identical Firebase re-delivery no longer re-bind unchanged rows. Search debounced 120 ms.
- Room load + Firebase parse/sort/entity-mapping now on a static single-thread executor (was main thread for Firebase),
  results posted back. Identical data (content signature) is skipped: no re-apply, no redundant Room insert.
- Calls `addValueEventListener` was never removed (one extra listener per tab return => duplicate applyFilter work).
  Now removed in onDestroyView. Verified-badge prefetch only when data changed.
- Firebase listeners (calls + contacts/online) attach after the first frame (`view.post`), Room paints first.
- Executor is static (was created per fragment and shut down in onDestroyView -> rejected-execution risk on view re-create).

**Header views / draw**
- Selection bar (5 views + 3 listeners), online panel (4 views), empty state (4 views) are ViewStubs.
- Dead `tv_no_online` view removed. Header inflate: ~25 views -> ~17 on open.
- Chips: one state-list bg (`bg_calls_chip`) + text colour (`calls_chip_text`), `setSelected()`; a tap now touches only the
  previous + new chip (was setBackgroundResource + getColor + setTextColor on all 5). One shared click listener.
- RecyclerView: `setHasFixedSize(true)`.
- `onResume`: NotificationManager.cancel + SharedPreferences edit moved off the main thread.

New files: `color/calls_chip_text.xml`, `drawable/bg_calls_chip.xml`, `layout/calls_selection_bar.xml`,
`layout/calls_online_panel.xml`, `layout/calls_empty_state.xml`.

Not build-tested (no Gradle/SDK here; javac parse pass only). Verify: long-press selection (select all / delete / cancel),
chip filters + search, empty state, online strip (user going online/offline, tap -> sheet), tab away/back (no duplicate
rows), Room-first paint then Firebase update.
