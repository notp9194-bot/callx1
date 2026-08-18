package com.callx.app.chatlist;

import androidx.lifecycle.ViewModel;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerViewPoolViewModel — v87 Activity-scoped pool lifetime.
 *
 * WHY THIS EXISTS
 * ───────────────
 * RecyclerView.RecycledViewPool created inside Fragment.onViewCreated()
 * lives exactly as long as the Fragment's View. With ViewPager2's default
 * offscreenPageLimit, distant tab switches trigger Fragment View destruction.
 * The pool is GC'd, all 25 cached ViewHolders are thrown away, and they must
 * be re-inflated + re-bound when the tab is revisited.
 *
 * By holding the pool in an Activity-scoped ViewModel:
 *   ┌─────────────────────────────────────────────────┐
 *   │  Activity (MainActivity)                        │
 *   │  └── RecyclerViewPoolViewModel  ← lives here   │
 *   │       ├── chatsPool (25 VHs, item_chat)        │
 *   │       └── groupsPool (20 VHs, item_group)      │
 *   │                                                 │
 *   │  ChatsFragment  ← onViewCreated: poolVm.get()  │
 *   │  GroupsFragment ← onViewCreated: poolVm.get()  │
 *   └─────────────────────────────────────────────────┘
 *
 * The pools survive every tab switch and every Fragment View recreation.
 * VHs re-attach to a new RecyclerView instance but never need to be re-inflated.
 *
 * IMPORTANT — why TWO separate pools
 * ───────────────────────────────────
 * ChatsFragment uses R.layout.item_chat   → ChatListAdapter.VH
 * GroupsFragment uses R.layout.item_group → GroupAdapter.VH
 *
 * RecycledViewPool recycles by (adapter viewType → VH). If we put both
 * adapters into ONE shared pool, ChatListAdapter.VH would be given to
 * GroupAdapter (same viewType=0) → ClassCastException or wrong layout.
 *
 * Two pools, same size lifecycle, zero cross-contamination.
 *
 * POOL SIZES
 * ──────────
 * chatsPool:  25 VHs — matches the setItemViewCacheSize(20) + 5 extra for
 *             fast bi-directional scroll burst beyond the cache horizon.
 * groupsPool: 20 VHs — groups list is typically shorter.
 */
public class RecyclerViewPoolViewModel extends ViewModel {

    // item_chat  VHs — must NOT mix with item_group VHs
    private final RecyclerView.RecycledViewPool chatsPool;

    // item_group VHs — must NOT mix with item_chat VHs
    private final RecyclerView.RecycledViewPool groupsPool;

    public RecyclerViewPoolViewModel() {
        chatsPool = new RecyclerView.RecycledViewPool();
        chatsPool.setMaxRecycledViews(0, 25);   // view type 0 = only type in ChatListAdapter

        groupsPool = new RecyclerView.RecycledViewPool();
        groupsPool.setMaxRecycledViews(0, 20);  // view type 0 = only type in GroupAdapter
    }

    /** Pool for ChatsFragment's RecyclerView. Never pass to GroupsFragment. */
    public RecyclerView.RecycledViewPool getChatsPool() { return chatsPool; }

    /** Pool for GroupsFragment's RecyclerView. Never pass to ChatsFragment. */
    public RecyclerView.RecycledViewPool getGroupsPool() { return groupsPool; }
}
