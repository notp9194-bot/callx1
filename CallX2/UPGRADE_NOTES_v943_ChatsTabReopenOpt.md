# v943 — Chats tab reopen: no re-bind / re-record / re-alloc
Root cause: every ChatsFragment.onCreateView built a NEW ChatListAdapter (empty differ) and dropped the visible
ViewHolders, so each tab open re-sorted, re-diffed, re-precomputed and re-bound every 1:1 + group row, re-inflated
14 prewarm rows on a fresh HandlerThread, and rebuilt per-bind lambdas / drawables.

- ChatsFragment: ONE adapter per Fragment instance (reused across view recreation). applyChatSearch() stays a
  no-op on reopen (markers kept). Adapter is unhooked from the dead RV in onDestroyView (shared pool detached first
  so RecycledViewPool.onAdapterChanged can't clear it). Prewarm thread only when the shared pool is cold;
  contactSheetPool stopped in onDestroy. onScrolled: warm-id list / prefetch only when the visible window moved.
- ChatListLayoutManager: setRecycleChildrenOnDetach(true) -> visible+cached rows go into the Activity pool on
  view destroy (reopen = pool hit, no inflate). Scroll anchor captured in onDetachedFromWindow.
- RecyclerViewPoolViewModel: chats pool 25 -> 32.
- ChatListAdapter: submitList skips identical lists (no differ/precompute/prefetch); per-VH reusable story-ring
  callbacks + BadgeSetter (no per-bind lambdas/method refs, no registry put); row background / group placeholder
  avatar only touched when changed (no needless invalidate/requestLayout).
Not build-tested here (no Gradle/SDK). Verify: Perfetto on Chats tab reopen — onCreateViewHolder count, Bubble/row record count.

## v944 — adapter-agnostic VH listeners
Pooled VHs outlive the adapter that created them. Click / long-click / call / story-ring listeners and the deferred
typing-attach runnable now dispatch through `VH.owner` (set on every bind to the adapter that currently shows the row)
instead of capturing the creating adapter. Fixes selection mode / avatar click acting on a dead adapter after the
Fragment instance is recreated, and stops old adapters (-> old fragments) being pinned by the shared pool.

## v945 — row-level (1:1 + group) optimisation
- item_chat.xml: selection overlay (FrameLayout + ImageView + View) is now a ViewStub (item_chat_select_overlay.xml),
  inflated only when selection mode first shows it -> 4 fewer views inflated/pooled per row.
- ChatListAdapter: preview string memo per VH (previewFor builds Strings / "Sender: " concat per bind, groups too);
  removed the duplicate StatusCacheManager lookups in bind (ringRefresh already resolves hasStoryNow); one reusable
  typing ValueEventListener per VH (1:1 + group) instead of a new anonymous listener per attach; chatId memo per uid.
- ChatRowContentView: verified-badge name width measured once per ellipsis rebuild, not per draw.
Not build-tested. Verify: row inflate time / views-per-row in Layout Inspector, allocations during Chats scroll.
