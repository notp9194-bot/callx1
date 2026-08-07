package com.callx.app.chatlist;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.chatlist.ChatListAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.db.entity.ChatFolderEntity;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import androidx.lifecycle.ViewModelProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.utils.AppBgExecutor;
import com.callx.app.utils.UiCriticalReadExecutor;

/**
 * ChatsFragment v21 — Delete / Delete-All System
 *
 * CHANGES v21:
 *  1. Long-press on chat item → selection mode starts immediately.
 *     PrivacyDirectDialog ab REMOVED from long-press.
 *     (Privacy actions are now accessible via avatar long-press zoom dialog
 *      or a future context menu; this avoids conflicting with selection.)
 *  2. Selection bar: ✕ cancel | count | All | 🗑️ delete icon
 *  3. Delete Selected: Firebase contacts node se remove + Room DB cleanup
 *  4. Delete All: confirm dialog → clear everything → Firebase + Room
 *  5. adapter.setOnLongPressListener() NOT set anymore.
 */
public class ChatsFragment extends Fragment implements ChatListAdapter.SelectionListener {

    private final List<User> contacts = new ArrayList<>();
    private ChatListAdapter adapter;
    private View emptyState;

    private LinearLayout llSelectionBar;
    private TextView tvSelectedCount;

    // PERF FIX: now read from a background thread inside
    // processContactsSnapshot()'s sort comparator while loadSpecialRequests()
    // still writes it on the main thread — plain HashSet isn't safe across
    // that, so this is a thread-safe set now (ConcurrentHashMap#newKeySet).
    private final Set<String> specialRequestUids = ConcurrentHashMap.newKeySet();

    // PERF FIX: AppBgExecutor is a 3-thread pool, so if onDataChange() fires
    // twice in quick succession (two Firebase updates close together) the two
    // background jobs it spawns can finish OUT OF ORDER — without this guard
    // an older snapshot's result could land on the UI after a newer one and
    // briefly show stale data. Each dispatch stamps a ticket; only the result
    // matching the latest ticket is applied, older ones are dropped silently.
    private final AtomicLong contactsSyncSeq = new AtomicLong(0);

    // Feature 1: Chat Folders
    private HorizontalScrollView hsvFolders;
    private LinearLayout         llFolderTabs;
    private int                  selectedFolderId = -1; // -1 = All Chats
    private ChatListViewModel    viewModel;

    // FIX #MEM-3C: Listener references store karo taaki onDestroyView mein detach kar sakein.
    // v92: Query, not DatabaseReference — the live listener is now bounded to
    // the most recent LIVE_SYNC_WINDOW chats (see loadContacts()); older chats
    // are paged in on demand via loadMoreOlderContacts() as the user scrolls.
    private Query contactsRef;
    // WhatsApp-style delta sync: ChildEventListener instead of ValueEventListener.
    // Firebase now delivers ONE child snapshot per change (the row that actually
    // changed) instead of re-sending the entire LIVE_SYNC_WINDOW on every tick
    // flip / new message anywhere in the window. See loadContacts() +
    // processContactsDelta() for the accumulate → debounce → background-process
    // → main-thread-merge pipeline this replaces the old full-snapshot path with.
    private ChildEventListener contactsListener;
    private DatabaseReference specialRequestsRef;
    private ValueEventListener specialRequestsListener;

    // v94: pending delta accumulator for the debounce window. Written only on
    // the main thread (inside the Firebase child callbacks, which always fire
    // on main), read/cleared only inside pendingContactsWork right before the
    // background hop — so no locking needed despite being "shared" state.
    // Keyed by child key (partner uid) so a rapid burst of changes to the same
    // row (e.g. sent→delivered→read ticks arriving within CONTACTS_DEBOUNCE_MS)
    // coalesces to just the latest snapshot for that row, exactly once.
    private final LinkedHashMap<String, DataSnapshot> pendingChildUpserts = new LinkedHashMap<>();
    private final Set<String> pendingChildRemovals = new LinkedHashSet<>();

    // ── v92: WhatsApp-style chat-list pagination ────────────────────────────
    // Room mirrors everything ever synced from Firebase (see processContactsSnapshot
    // / processOlderPageSnapshot), so it's the offline-first source for instant
    // first paint; Firebase stays the source of truth and is queried directly,
    // page by page, for anything beyond what's cached.
    private static final int PAGE_SIZE        = 30; // chats fetched per "load more"
    private static final int LIVE_SYNC_WINDOW = 60; // most-recent chats kept live-synced

    private boolean isLoadingMoreChats = false;
    private boolean hasMoreOlderChats  = true;
    // Cursor: oldest lastMessageAt/lastSeen currently loaded into `contacts`.
    // Next "load more" page fetches everything strictly older than this
    // (used only by the Firebase network backfill path — see
    // fetchOlderPageFromFirebase()).
    private Long oldestLoadedTimestamp = null;
    // v206 — EDGE-CASE FIX: was `int roomPageOffset`, advanced by PAGE_SIZE
    // each call and fed into a LIMIT/OFFSET Room query. Plain OFFSET
    // pagination breaks under concurrent writes (see ChatDao.getChatsPagedSync
    // doc) — a row can permanently skip past the offset boundary and never
    // get fetched. Replaced with a keyset cursor: the (lastMessageAt,
    // chatId) of the LAST row actually rendered from Room, which is stable
    // regardless of how many rows above it get inserted/reordered meanwhile.
    // null == no page fetched from Room yet (first "load more" call).
    private Long roomCursorTimestamp = null;
    private String roomCursorChatId = null;
    private ProgressBar pbLoadingMoreChats;

    // v93: scroll-ahead avatar preloading — warms Glide's disk cache for rows
    // just below the visible window (same override/format/transform signature
    // ChatListAdapter actually binds with) so avatars are already cached by
    // the time the user scrolls to them. WhatsApp/Telegram-style smooth scroll.
    private static final int AVATAR_PRELOAD_AHEAD = 12; // ~1 screenful of rows

    // v93: debounce bursty Firebase snapshots (e.g. multiple tick flips /
    // typing updates arriving within milliseconds of each other during an
    // active group conversation). Without this, EVERY onDataChange() spawns
    // its own full background reprocess-and-diff pass even though only the
    // LAST one in a burst ever reaches the screen (contactsSyncSeq already
    // drops stale UI applies — but the redundant CPU work upstream of that
    // still happened). Coalescing to one pass per short window is exactly
    // how WhatsApp avoids re-rendering the chat list on every micro-update.
    private static final long CONTACTS_DEBOUNCE_MS = 120;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingContactsWork;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);
        RecyclerView rv  = v.findViewById(R.id.rv_chats);
        emptyState       = v.findViewById(R.id.empty_state);
        llSelectionBar   = v.findViewById(R.id.ll_selection_bar);
        tvSelectedCount  = v.findViewById(R.id.tv_selected_count);
        pbLoadingMoreChats = v.findViewById(R.id.pb_loading_more_chats);

        View banner = v.findViewById(R.id.banner_requests);
        if (banner != null) banner.setVisibility(View.GONE);

        // v251: Quick-access card header removed (Add Story / My Status /
        // Games) — X / YouTube / Games moved to the 3-dot overflow menu in
        // MainActivity instead. See setupQuickAccessHeader() removal below.

        // v88: ChatListLayoutManager (custom LLM)
        //  • supportsPredictiveItemAnimations=false → single layout pass per update
        //  • getExtraLayoutSpace=screenHeight → rows pre-laid out before scroll
        //  • isMeasurementCacheEnabled=true → no re-measure for fixed-height rows
        ChatListLayoutManager llm = new ChatListLayoutManager(requireContext());
        llm.setInitialPrefetchItemCount(8);
        rv.setLayoutManager(llm);

        // v83: constructor no longer takes a list — submitList() is the write path
        adapter = new ChatListAdapter(this);
        rv.setAdapter(adapter);

        // Fixed-size rows: tell RV it never needs to re-measure the whole list
        // when an item changes — saves a full layout pass on every Firebase update.
        rv.setHasFixedSize(true);

        // Keep 20 off-screen VHs alive (was 12). On a 50-contact list this
        // virtually eliminates VH destruction/recreation during a scroll and
        // avoids re-attaching typing listeners + reloading Glide on every bind.
        rv.setItemViewCacheSize(20);

        // v87: Activity-scoped pool — survives Fragment View destruction on tab switches.
        // Pool is owned by RecyclerViewPoolViewModel (Activity lifecycle), so the 25 cached
        // ChatListAdapter.VHs are never thrown away when the user visits a distant tab.
        // NEVER pass this pool to GroupsFragment — VH layouts are incompatible.
        RecyclerViewPoolViewModel poolVm =
                new androidx.lifecycle.ViewModelProvider(requireActivity())
                        .get(RecyclerViewPoolViewModel.class);
        rv.setRecycledViewPool(poolVm.getChatsPool());

        // Pause Glide bitmap decoding during fast flings (resume on idle/drag).
        rv.addOnScrollListener(new GlideScrollListener(requireContext()));

        // v92: WhatsApp-style "load more" — fetch the next older page once the
        // user scrolls near the bottom of the currently-loaded chats. Skipped
        // while a folder filter is active (selectedFolderId != -1) since the
        // visible list there is a filtered subset, not the full paginated set.
        //
        // v93: also preloads avatars for the next screenful of rows just below
        // the visible window — by the time the user scrolls to them, Glide's
        // disk cache already has the exact (size+format+circleCrop) resource
        // ready, so the avatar appears instantly instead of popping in after a
        // network fetch. Same technique WhatsApp/Telegram use for smooth scroll.
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private int lastPreloadedEnd = -1;

            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0) return;
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null || adapter == null) return;
                int lastVisible = lm.findLastVisibleItemPosition();
                int total = adapter.getItemCount();
                if (lastVisible < 0 || total == 0) return;

                if (selectedFolderId == -1 && lastVisible >= total - 5) {
                    loadMoreOlderContacts();
                }

                int preloadEnd = Math.min(total - 1, lastVisible + AVATAR_PRELOAD_AHEAD);
                if (preloadEnd > lastPreloadedEnd) {
                    preloadAvatarsInRange(Math.max(lastVisible + 1, lastPreloadedEnd + 1), preloadEnd);
                    lastPreloadedEnd = preloadEnd;
                }
            }
        });

        // v85+: null ItemAnimator — removes all animation overhead
        rv.setItemAnimator(null);

        // v86: disable over-scroll edge glow + nested scroll overhead
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setNestedScrollingEnabled(false);

        // v88: fine-tune touch slop — default gives precise single-tap detection
        // while still recognising flings cleanly
        rv.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_DEFAULT);

        // v88: Glide safety net — clear any in-flight request the moment a VH
        // is returned to the pool, before the next bind can fire with a stale URL
        rv.setRecyclerListener(holder -> {
            if (holder instanceof ChatListAdapter.VH) {
                ChatListAdapter.VH vh = (ChatListAdapter.VH) holder;
                if (vh.ivAvatar != null) {
                    try { Glide.with(vh.ivAvatar.getContext()).clear(vh.ivAvatar); }
                    catch (Exception ignored) {}
                }
            }
        });

        // Reduce clipping work on every scroll frame
        rv.setClipToPadding(false);
        rv.setClipChildren(false);

        // ULTRA DIAGNOSTICS: register the live RecyclerView so the
        // "🔬 Ultra Advanced Diagnostics" screen can inspect real,
        // currently-attached RV state (child count, pool occupancy,
        // cache size) instead of guessing from config alone. WeakReference
        // inside PerformanceMonitor — never keeps this fragment's view alive.
        com.callx.app.perf.PerformanceMonitor.get().attachChatListRecyclerView(rv);

        // Avatar click → contact bottom sheet (same as Calls tab)
        adapter.setOnAvatarClickListener(u -> showContactBottomSheet(u));

        // v21: longPressListener NOT set → long-press now starts selection mode directly.

        // Selection bar buttons
        v.findViewById(R.id.btn_cancel_selection_chats).setOnClickListener(x -> {
            adapter.clearSelection();
            llSelectionBar.setVisibility(View.GONE);
        });

        v.findViewById(R.id.btn_select_all_chats).setOnClickListener(x -> {
            adapter.selectAll();
            updateSelectionCount();
        });

        v.findViewById(R.id.btn_delete_selected_chats).setOnClickListener(x ->
            confirmDeleteSelected());

        // Feature 1: Chat Folders — setup folder chip row
        hsvFolders   = v.findViewById(R.id.hsv_folders);
        llFolderTabs = v.findViewById(R.id.ll_folder_tabs);
        viewModel    = new ViewModelProvider(requireActivity()).get(ChatListViewModel.class);
        viewModel.folders.observe(getViewLifecycleOwner(), folders -> setupFolderTabs(folders));

        // PERF MONITOR: marks the start of this screen's data-load window —
        // feeds the "Performance" report (3-dot menu). Ended in
        // diffUpdateContacts() the first time real data actually lands.
        com.callx.app.perf.PerformanceMonitor.get().markChatListLoadStart();

        // v15 FIX 1: Pehle Room se load karo (offline ke liye instant display)
        loadFromRoom();

        // Phir Firebase listener lagao (online sync + Room update)
        loadContacts();
        loadSpecialRequests();
        return v;
    }


    // ─── Chat Folders ─────────────────────────────────────────────────────────

    /**
     * Rebuild the folder chip row whenever the folders LiveData emits.
     * "All Chats" chip is always first; each folder gets one chip.
     * A "+" chip at the end opens FolderEditActivity to create a new folder.
     * Selected chip turns brand_primary; others stay grey.
     */
    private void setupFolderTabs(List<ChatFolderEntity> folders) {
        if (llFolderTabs == null || hsvFolders == null) return;
        llFolderTabs.removeAllViews();

        // Show/hide the whole row
        boolean hasFolders = folders != null && !folders.isEmpty();
        hsvFolders.setVisibility(hasFolders ? View.VISIBLE : View.GONE);

        if (!hasFolders) return;

        // "All Chats" chip (always first)
        addFolderChip("🗂", "All", -1);

        // One chip per folder
        for (ChatFolderEntity f : folders) {
            addFolderChip(f.emoji != null ? f.emoji : "📁", f.name, f.id);
        }

        // "+" chip to create a new folder
        android.content.Context ctx = requireContext();
        TextView plus = new TextView(ctx);
        plus.setText("＋");
        plus.setTextSize(18f);
        plus.setGravity(android.view.Gravity.CENTER);
        float dp = ctx.getResources().getDisplayMetrics().density;
        int pad = (int)(14 * dp); int vpad = (int)(4 * dp); int margin = (int)(6 * dp);
        plus.setPadding(pad, vpad, pad, vpad);
        android.widget.LinearLayout.LayoutParams lp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                (int)(36 * dp));
        lp.setMargins(margin, 0, margin, 0);
        plus.setLayoutParams(lp);
        plus.setBackgroundResource(R.drawable.bg_folder_tab_unselected);
        plus.setTextColor(ctx.getResources().getColor(R.color.brand_primary));
        plus.setClickable(true); plus.setFocusable(true);
        plus.setOnClickListener(v -> startActivity(
            new android.content.Intent(ctx, FolderEditActivity.class)));
        llFolderTabs.addView(plus);

        // Apply selection state
        updateFolderChipSelection(selectedFolderId);
    }

    private void addFolderChip(String emoji, String name, int folderId) {
        android.content.Context ctx = requireContext();
        View chip = android.view.LayoutInflater.from(ctx)
            .inflate(R.layout.item_folder_tab, llFolderTabs, false);
        ((TextView) chip.findViewById(R.id.tv_folder_emoji)).setText(emoji);
        ((TextView) chip.findViewById(R.id.tv_folder_name)).setText(name);
        chip.setTag(folderId);
        chip.setOnClickListener(v -> onFolderChipTapped(folderId));
        // Long-press on an existing folder → open edit activity
        if (folderId >= 0) {
            chip.setOnLongClickListener(v -> {
                android.content.Intent intent =
                    new android.content.Intent(ctx, FolderEditActivity.class);
                intent.putExtra(FolderEditActivity.EXTRA_FOLDER_ID, folderId);
                startActivity(intent);
                return true;
            });
        }
        llFolderTabs.addView(chip);
    }

    private void onFolderChipTapped(int folderId) {
        selectedFolderId = folderId;
        updateFolderChipSelection(folderId);
        // Filter the contacts list to only show chats in the selected folder
        // (Simple implementation: filter by folderId on the User objects)
        // The full Room-backed LiveData approach would require a separate
        // observer per folder; this in-memory filter covers 99% of use cases.
        // Users who open a folder will see only chats matching the folder's rules.
        // For now we filter contacts by their chatEntity.folderId, loaded from Room.
        if (folderId == -1) {
            // All Chats — show everything (reset filter)
            diffUpdateContacts(new ArrayList<>(contacts));
            return;
        }
        // Filter by folderId in background via Room, then update UI
        AppBgExecutor.execute(() -> {
            if (getContext() == null) return;
            List<ChatEntity> folderChats = AppDatabase.getInstance(requireContext())
                .chatDao().getChatsForFolder(folderId).getValue();
            if (folderChats == null) return;
            Set<String> partnerUids = new HashSet<>();
            for (ChatEntity e : folderChats) if (e.partnerUid != null) partnerUids.add(e.partnerUid);
            List<User> filtered = new ArrayList<>();
            for (User u : contacts) if (u.uid != null && partnerUids.contains(u.uid)) filtered.add(u);
            if (getActivity() != null)
                getActivity().runOnUiThread(() -> diffUpdateContacts(filtered));
        });
    }

    private void updateFolderChipSelection(int selectedId) {
        if (llFolderTabs == null) return;
        android.content.Context ctx = requireContext();
        for (int i = 0; i < llFolderTabs.getChildCount(); i++) {
            View child = llFolderTabs.getChildAt(i);
            Object tag = child.getTag();
            boolean isSelected = (tag instanceof Integer) && ((Integer) tag) == selectedId;
            child.setBackgroundResource(isSelected
                ? R.drawable.bg_folder_tab_selected
                : R.drawable.bg_folder_tab_unselected);
            // Update text color for chip labels
            if (child instanceof ViewGroup) {
                TextView tvName = child.findViewById(R.id.tv_folder_name);
                if (tvName != null) tvName.setTextColor(isSelected
                    ? ctx.getResources().getColor(android.R.color.white)
                    : ctx.getResources().getColor(R.color.text_primary));
            }
        }
    }

    /**
     * v208 — PERF FIX: batch-warm Glide's memory+disk cache for a whole page
     * of avatars in one go, on the background thread that already loaded
     * this page from Room — BEFORE the RecyclerView ever binds a single row.
     *
     * Previously the ONLY warming was preloadAdjacentAvatar() (v85), which
     * fires per-row, one-ahead, from a 180ms-deferred runnable AFTER that
     * row has already bound and rendered. That's fine for steady scrolling,
     * but it means: first paint (loadFromRoom's initial PAGE_SIZE page) and
     * every "load more" page's FIRST few visible rows always pay a real
     * decode on first bind — the one-ahead preload literally cannot warm a
     * row before it's shown, only the ones after it.
     *
     * Fix: as soon as a page of ChatEntity rows comes back from Room (still
     * on the background thread — UiCriticalReadExecutor/AppBgExecutor, never
     * the calling thread's problem), fire a Glide .preload() for every
     * avatar in that page at once. By the time diffUpdateContacts() actually
     * creates/binds the ViewHolders a few ms later (after the main-thread
     * hop + AsyncListDiffer), most/all of these are already sitting in
     * Glide's memory cache — onBindViewHolderTimed()'s .into() call becomes
     * a cache hit instead of a decode. Uses the exact same URL resolution
     * (resolveListAvatarUrl — thumb-only), size, format, and transform as
     * the real bind, via ChatListAdapter's shared static helpers, so the
     * cache key matches exactly and this warming isn't wasted.
     *
     * Safe to call off any background thread — Glide's .preload() does not
     * touch a target View or need the main thread.
     */
    private void preloadAvatarsForPage(Context appCtx, List<User> page) {
        if (page.isEmpty()) return;
        int px = ChatListAdapter.getAvatarSizePx(appCtx);
        for (User u : page) {
            String url = ChatListAdapter.resolveListAvatarUrl(u);
            if (url == null || url.isEmpty()) continue;
            Glide.with(appCtx)
                    .load(url)
                    .override(px, px)
                    .format(ChatListAdapter.AVATAR_FORMAT)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .apply(RequestOptions.circleCropTransform())
                    .preload(px, px);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Room se offline-first load
    // ─────────────────────────────────────────────────────────────

    private void loadFromRoom() {
        if (getContext() == null) return;
        // PERF FIX v237: AppDatabase.getInstance() moved off the caller
        // (main) thread and into the background task below, and routed
        // through UiCriticalReadExecutor instead of the shared AppBgExecutor
        // — see that class's doc. This is the Chat List's very first-paint
        // read; it must never wait behind an unrelated queued write (folder
        // assignment, chat delete, message-send insert) on a shared pool,
        // and it must never risk touching AppDatabase.getInstance()'s cold
        // path on the main thread.
        android.content.Context appCtx = getContext().getApplicationContext();

        UiCriticalReadExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(appCtx);
            // v92: only the first page — instant first paint, not the whole table.
            // Older cached chats page in the same way as fresh-from-network ones,
            // via loadMoreOlderContacts() as the user scrolls.
            List<ChatEntity> cached = db.chatDao().getChatsPagedSync(PAGE_SIZE);
            if (cached == null || cached.isEmpty()) return;

            List<User> roomUsers = new ArrayList<>();
            for (ChatEntity e : cached) {
                User u = entityToUser(e);
                if (u != null) roomUsers.add(u);
            }
            // v208: warm the avatar cache for this whole page BEFORE the
            // main-thread hop — see preloadAvatarsForPage() doc.
            preloadAvatarsForPage(appCtx, roomUsers);
            // v206: seed the keyset cursor from the LAST row of this first
            // page — loadMoreOlderContacts()'s first call reads from here.
            ChatEntity lastRow = cached.get(cached.size() - 1);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (contacts.isEmpty()) {
                        // FIX #5: Room first-load — contacts list is empty so diffUpdate
                        // is equivalent to notifyItemRangeInserted but cleaner via diff
                        sortByLatestMessage();
                        diffUpdateContacts(roomUsers);
                        if (emptyState != null)
                            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                        // Only adopt this cursor if "load more" hasn't already
                        // advanced it (e.g. a fast scroll during this async load).
                        if (roomCursorTimestamp == null) {
                            roomCursorTimestamp = lastRow.lastMessageAt;
                            roomCursorChatId = lastRow.chatId;
                        }
                    }
                });
            }
        });
    }

    /**
     * v93: fires off Glide preload() requests (no ImageView target — just
     * warms the disk cache) for rows just below the currently visible window.
     * Uses the EXACT same override size / decode format / circleCrop transform
     * as ChatListAdapter's real bind-time load, so the resource cache key
     * matches and this isn't wasted work.
     */
    private void preloadAvatarsInRange(int start, int end) {
        if (getContext() == null) return;
        int size = ChatListAdapter.getAvatarSizePx(requireContext());
        com.bumptech.glide.RequestManager glide =
                Glide.with(requireContext().getApplicationContext());
        for (int i = start; i <= end && i >= 0 && i < contacts.size(); i++) {
            User u = contacts.get(i);
            if (u == null) continue;
            String url = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            if (url == null || url.isEmpty()) continue;
            glide.load(url)
                    .dontAnimate()
                    .override(size, size)
                    .format(ChatListAdapter.AVATAR_FORMAT)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .apply(RequestOptions.circleCropTransform())
                    .preload();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Firebase listener — online sync + Room save
    // ─────────────────────────────────────────────────────────────

    /**
     * v92: bounded live sync. Previously this listened to the ENTIRE
     * "contacts" node — every tick flip, every new message anywhere in a
     * user's chat history re-downloaded and re-processed the WHOLE list,
     * no matter how large it grew. Real-time sync now only covers the
     * LIVE_SYNC_WINDOW most-recently-active chats (exactly what WhatsApp
     * keeps "hot"); anything older is fetched on demand as the user scrolls
     * — see loadMoreOlderContacts() — and merged in without disturbing the
     * live window. This bounds both bandwidth and onDataChange() CPU cost
     * regardless of total chat count.
     *
     * NOTE: for best server-side query performance, add an index on
     * "lastMessageAt" under /contacts/$uid in the Firebase console rules
     * (`.indexOn: ["lastMessageAt"]`) — the query still works without it,
     * just with a "no index" warning in logcat.
     */
    private void loadContacts() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseUtils.getCurrentUid();

        // v94: WhatsApp-level delta sync. A ValueEventListener on a
        // limitToLast() query re-sends the ENTIRE window on every single
        // change under it — one tick flip anywhere in the last 60 chats used
        // to mean re-downloading and re-deserializing all 60 rows. A
        // ChildEventListener on the exact same query gets Firebase to do the
        // diffing server-side instead: onChildChanged fires with just the ONE
        // row that changed, onChildAdded with just the new row, onChildRemoved
        // with just the row that fell out of the window. This is exactly how
        // WhatsApp's own sync layer only pushes the delta, never the whole list.
        //
        // Each callback only ACCUMULATES the raw DataSnapshot into
        // pendingChildUpserts/pendingChildRemovals (cheap, main-thread, no
        // deserialization) and (re)schedules the same debounce window used
        // before — so a burst of several child events within
        // CONTACTS_DEBOUNCE_MS still collapses into a single background pass,
        // but that pass now only touches the handful of rows that actually
        // changed instead of the whole live window.
        contactsListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, String prevKey) {
                if (snap.getKey() == null) return;
                pendingChildRemovals.remove(snap.getKey());
                pendingChildUpserts.put(snap.getKey(), snap);
                scheduleContactsDelta(uid);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snap, String prevKey) {
                if (snap.getKey() == null) return;
                pendingChildRemovals.remove(snap.getKey());
                pendingChildUpserts.put(snap.getKey(), snap);
                scheduleContactsDelta(uid);
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot snap) {
                if (snap.getKey() == null) return;
                pendingChildUpserts.remove(snap.getKey());
                pendingChildRemovals.add(snap.getKey());
                scheduleContactsDelta(uid);
            }
            @Override public void onChildMoved(@NonNull DataSnapshot snap, String prevKey) {
                // Ordering is decided by our own sortContactsList() (special
                // requests float to top, then most-recent), not Firebase's
                // orderByChild position — nothing to do here.
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        contactsRef = FirebaseUtils.getContactsRef(uid)
                .orderByChild("lastMessageAt")
                .limitToLast(LIVE_SYNC_WINDOW);
        contactsRef.addChildEventListener(contactsListener);
    }

    /** Debounce a burst of child events into a single background delta pass. */
    private void scheduleContactsDelta(String uid) {
        if (pendingContactsWork != null) mainHandler.removeCallbacks(pendingContactsWork);
        pendingContactsWork = () -> {
            // Snapshot + clear the accumulators here, on the main thread,
            // right before handing off — anything arriving after this point
            // starts a fresh accumulation window rather than racing the
            // background pass that's about to read them.
            List<DataSnapshot> upserts = new ArrayList<>(pendingChildUpserts.values());
            List<String> removals = new ArrayList<>(pendingChildRemovals);
            pendingChildUpserts.clear();
            pendingChildRemovals.clear();
            if (upserts.isEmpty() && removals.isEmpty()) return;

            long ticket = contactsSyncSeq.incrementAndGet();
            AppBgExecutor.execute(() -> processContactsDelta(upserts, removals, uid, ticket));
        };
        mainHandler.postDelayed(pendingContactsWork, CONTACTS_DEBOUNCE_MS);
    }

    /**
     * PERF FIX — ROOT CAUSE of most of the reported "main-thread lag = 154ms"
     * and a chunk of the janky-frame rate:
     *
     * Firebase's ValueEventListener ALWAYS calls onDataChange() on the main
     * thread. This method used to run entirely inside that callback:
     *   • c.getValue(User.class) — reflection-based deserialization, once
     *     per contact in the snapshot
     *   • building a full List<ChatEntity> for the Room save
     *   • Collections.sort() over the ENTIRE contact list
     *
     * None of that touches a View — it's pure CPU/object-allocation work —
     * but because it ran inside the main-thread callback, it blocked the UI
     * thread for however long that took. And this callback doesn't fire
     * once: it fires on every single change anywhere under this user's
     * "contacts" node (a tick flipping sent→delivered, one new message,
     * anything), so the stall could land at any moment — including mid-
     * fling, where it shows up as a dropped/janky frame, and the in-app
     * Performance report's main-thread responsiveness probe (a Handler.post
     * round-trip) is delayed by exactly this amount whenever it happens to
     * be scheduled behind this work.
     *
     * Fix: run all of it here, on AppBgExecutor's background thread. Only
     * the final step — diffUpdateContacts(), which touches the adapter/
     * RecyclerView — hops back to the main thread, and even that is cheap:
     * AsyncListDiffer computes the actual diff on its own background
     * executor, so the main-thread hop is just handing off a List reference.
     *
     * v92: this snapshot now only ever contains the LIVE_SYNC_WINDOW most
     * recent chats (see loadContacts()), so the result is MERGED into the
     * existing `contacts` list (replacing anything inside the live window,
     * preserving anything older that was paged in separately) rather than
     * replacing it outright — otherwise every real-time update would wipe
     * out whatever the user had already scrolled/paged into.
     */
    /**
     * v94: WhatsApp-level delta processing — the background-thread twin of
     * scheduleContactsDelta(). Unlike the old processContactsSnapshot(), the
     * `upserts` list here is only the handful of rows that actually changed
     * (typically 1, rarely more than a few even in a burst), not the whole
     * LIVE_SYNC_WINDOW. Same CPU-off-main-thread discipline as before:
     * deserialization, Room writes, and any enrichment lookups all happen
     * here; only the final small merge + diff hops back to main.
     */
    private void processContactsDelta(List<DataSnapshot> upserts, List<String> removals,
                                        String uid, long ticket) {
        List<User> changedUsers = new ArrayList<>();
        List<ChatEntity> toSave = new ArrayList<>();

        for (DataSnapshot c : upserts) {
            User u = c.getValue(User.class);
            if (u == null) continue;
            if (u.uid == null) u.uid = c.getKey();
            if ((u.name == null || u.name.isEmpty()
                    || u.photoUrl == null) && u.uid != null) {
                enrichContactFromUsers(u, uid);
            }
            changedUsers.add(u);
            toSave.add(buildChatEntity(u, uid));
        }

        if (getContext() != null && !toSave.isEmpty()) {
            // Already running on a background thread (AppBgExecutor) —
            // no need to hop to yet another executor for this.
            AppDatabase.getInstance(getContext()).chatDao().insertChats(toSave);
        }
        if (getContext() != null && !removals.isEmpty()) {
            AppDatabase db = AppDatabase.getInstance(getContext());
            for (String removedUid : removals) db.chatDao().deleteByPartnerUid(removedUid);
        }

        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            // Drop this result if a newer delta has already been dispatched —
            // prevents an out-of-order background completion from briefly
            // showing stale data (see contactsSyncSeq).
            if (ticket != contactsSyncSeq.get()) return;
            mergeContactsDelta(changedUsers, removals);
            if (emptyState != null)
                emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    /**
     * v94: applies a small delta (a few changed/added rows + a few removed
     * uids) onto `contacts` in place — O(window + delta), never rebuilds the
     * list from a full snapshot. Must be called on the main thread (contacts
     * is main-thread-owned).
     */
    /**
     * v95: ultra-optimized delta merge. The previous version scanned the
     * whole `contacts` window once per changed user (O(delta × window)) —
     * fine for a single tick flip, wasteful for a burst of several rows
     * changing inside one debounce cycle. This builds one uid→index map
     * over the current window ONCE (O(window)), then applies every upsert/
     * removal against it in O(1) each — O(window + delta) total, the same
     * complexity WhatsApp's own local-DB merge step runs at.
     */
    private void mergeContactsDelta(List<User> changedUsers, List<String> removals) {
        Map<String, Integer> indexByUid = new HashMap<>(contacts.size() * 2);
        for (int i = 0; i < contacts.size(); i++) {
            User u = contacts.get(i);
            if (u.uid != null) indexByUid.put(u.uid, i);
        }

        if (!removals.isEmpty()) {
            Set<String> removedSet = new HashSet<>(removals);
            contacts.removeIf(u -> u.uid != null && removedSet.contains(u.uid));
            // Removal shifts every index after the removed slot(s) — cheaper
            // to just rebuild the map than track shifts, and it's still O(window).
            if (!changedUsers.isEmpty()) {
                indexByUid.clear();
                for (int i = 0; i < contacts.size(); i++) {
                    User u = contacts.get(i);
                    if (u.uid != null) indexByUid.put(u.uid, i);
                }
            }
        }

        for (User changed : changedUsers) {
            if (changed.uid == null) continue;
            Integer idx = indexByUid.get(changed.uid);
            if (idx != null && idx < contacts.size()) {
                contacts.set(idx, changed);
            } else {
                indexByUid.put(changed.uid, contacts.size());
                contacts.add(changed);
            }
        }
        sortContactsList(contacts, specialRequestUids);
        // NOTE: diffUpdateContacts() does contacts.clear()+addAll(newList) —
        // passing `contacts` itself here would wipe it before the addAll can
        // read it back. Must pass a distinct list.
        diffUpdateContacts(new ArrayList<>(contacts));
    }

    // ── v96: Room-first "load more" — pages in chats older than what's currently loaded ──

    /**
     * v96 — WhatsApp-level pagination: Room is now checked FIRST, exactly
     * like WhatsApp's local-DB-first list. If the next page is already
     * cached in Room (from an earlier session, or a prior Firebase fetch),
     * it renders INSTANTLY — no network wait, works offline. Firebase is
     * only used as: (a) a silent background backfill to keep Room warm and
     * discover rows Room doesn't have yet, or (b) the actual blocking source
     * when Room has a true cache miss (nothing cached at this offset yet).
     * Guarded by isLoadingMoreChats / hasMoreOlderChats so a fast scroll
     * can't fire overlapping requests.
     */
    private void loadMoreOlderContacts() {
        if (isLoadingMoreChats || !hasMoreOlderChats) return;
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        if (getContext() == null) return;

        isLoadingMoreChats = true;
        showLoadingMoreIndicator(true);
        String uid = FirebaseUtils.getCurrentUid();
        android.content.Context appCtx = getContext().getApplicationContext();
        // v206: snapshot the keyset cursor (last row actually rendered),
        // not a numeric offset — see ChatDao.getChatsPagedSync doc.
        Long cursorTs = roomCursorTimestamp;
        String cursorChatId = roomCursorChatId;

        UiCriticalReadExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(appCtx);
            List<ChatEntity> cached = (cursorTs != null && cursorChatId != null)
                    ? db.chatDao().getChatsPagedSync(cursorTs, cursorChatId, PAGE_SIZE)
                    : db.chatDao().getChatsPagedSync(PAGE_SIZE);
            List<User> roomPage = new ArrayList<>();
            for (ChatEntity e : cached) {
                User u = entityToUser(e);
                if (u != null) roomPage.add(u);
            }
            // v208: warm the avatar cache for this page before the main-thread
            // hop — same as loadFromRoom(), so "load more" scrolling gets the
            // same instant-avatar benefit as first paint.
            preloadAvatarsForPage(appCtx, roomPage);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                boolean roomWasCacheMiss = roomPage.isEmpty();
                if (!roomWasCacheMiss) {
                    // Room served the page instantly — done, no network wait.
                    // Advance the cursor to THIS page's last row (not a count),
                    // so the next call is immune to any reordering above it.
                    ChatEntity lastRow = cached.get(cached.size() - 1);
                    roomCursorTimestamp = lastRow.lastMessageAt;
                    roomCursorChatId = lastRow.chatId;
                    mergeOlderPage(roomPage);
                    isLoadingMoreChats = false;
                    showLoadingMoreIndicator(false);
                }
                // Either way, kick Firebase in the background: silent refill
                // when Room already served the page (keeps Room warm for the
                // NEXT scroll + surfaces anything Room is missing), or as the
                // actual blocking fetch when Room had nothing cached here.
                fetchOlderPageFromFirebase(uid, roomWasCacheMiss);
            });
        });
    }

    /**
     * v96: Firebase backfill/fallback. Same single-value query as before —
     * only difference is it no longer drives the loading spinner unless
     * Room genuinely had nothing for this page (roomWasCacheMiss). When
     * Room already rendered the page, this runs silently after the fact
     * purely to keep Room's cache warm and to catch new/older rows.
     */
    private void fetchOlderPageFromFirebase(String uid, boolean roomWasCacheMiss) {
        if (oldestLoadedTimestamp == null) {
            if (roomWasCacheMiss) { isLoadingMoreChats = false; showLoadingMoreIndicator(false); }
            return;
        }
        if (roomWasCacheMiss) {
            isLoadingMoreChats = true;
            showLoadingMoreIndicator(true);
        }
        long cursor = oldestLoadedTimestamp;

        FirebaseUtils.getContactsRef(uid)
                .orderByChild("lastMessageAt")
                .endAt(cursor - 1)
                .limitToLast(PAGE_SIZE)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        AppBgExecutor.execute(() -> processOlderPageSnapshot(snap, uid, roomWasCacheMiss));
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        if (roomWasCacheMiss) {
                            isLoadingMoreChats = false;
                            if (getActivity() != null)
                                getActivity().runOnUiThread(() -> showLoadingMoreIndicator(false));
                        }
                    }
                });
    }

    /**
     * Background-thread processing for one older Firebase page. `wasBlocking`
     * is true only when this was the actual source (Room cache miss) — in
     * that case it owns the loading spinner and the hasMoreOlderChats flag.
     * When false (silent backfill after Room already rendered), it only
     * writes to Room + quietly merges anything new, without touching the
     * loading UI or the "no more chats" flag.
     */
    private void processOlderPageSnapshot(DataSnapshot snap, String uid, boolean wasBlocking) {
        List<User> olderPage = new ArrayList<>();
        List<ChatEntity> toSave = new ArrayList<>();

        for (DataSnapshot c : snap.getChildren()) {
            User u = c.getValue(User.class);
            if (u == null) continue;
            if (u.uid == null) u.uid = c.getKey();
            olderPage.add(u);
            toSave.add(buildChatEntity(u, uid));
        }

        if (getContext() != null && !toSave.isEmpty()) {
            AppDatabase.getInstance(getContext()).chatDao().insertChats(toSave);
        }

        boolean pageWasFull = olderPage.size() >= PAGE_SIZE;

        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (wasBlocking) {
                isLoadingMoreChats = false;
                showLoadingMoreIndicator(false);
                if (olderPage.isEmpty()) {
                    hasMoreOlderChats = false;
                    return;
                }
                if (!pageWasFull) hasMoreOlderChats = false;
            }
            if (!olderPage.isEmpty()) mergeOlderPage(olderPage);
        });
    }

    /** Dedupe-append an older page into `contacts`, re-sort, and diff it in. */
    private void mergeOlderPage(List<User> page) {
        Set<String> existingUids = new HashSet<>();
        for (User u : contacts) if (u.uid != null) existingUids.add(u.uid);
        boolean addedAny = false;
        for (User u : page) {
            if (u.uid == null || !existingUids.contains(u.uid)) {
                contacts.add(u);
                addedAny = true;
            }
        }
        if (!addedAny) return;
        sortContactsList(contacts, specialRequestUids);
        diffUpdateContacts(new ArrayList<>(contacts));
    }

    private void showLoadingMoreIndicator(boolean show) {
        if (pbLoadingMoreChats != null) {
            pbLoadingMoreChats.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    /** Same field mapping used everywhere a Firebase User → local ChatEntity is saved. */
    private static ChatEntity buildChatEntity(User u, String myUid) {
        ChatEntity entity = new ChatEntity();
        entity.chatId       = myUid + "_contact_" + (u.uid != null ? u.uid : "");
        entity.type         = "private";
        entity.partnerUid   = u.uid;
        entity.partnerName  = u.name;
        entity.partnerPhoto = u.photoUrl;
        entity.partnerThumb = u.thumbUrl;
        entity.lastMessage  = u.lastMessage;
        entity.lastMessageAt = u.lastMessageAt;
        entity.unread       = u.unread;
        entity.lastMessageType      = u.lastMessageType;
        entity.lastMessageStatus    = u.lastMessageStatus;
        entity.lastMessageSenderUid = u.lastMessageSenderUid;
        entity.lastMessageId        = u.lastMessageId;
        entity.syncedAt     = System.currentTimeMillis();
        return entity;
    }

    /** Same field mapping used to hydrate a Room-cached row back into a display User. */
    private static User entityToUser(ChatEntity e) {
        if (e.partnerUid == null || e.partnerUid.isEmpty()) return null;
        User u = new User();
        u.uid      = e.partnerUid;
        u.name     = e.partnerName;
        u.photoUrl = e.partnerPhoto;
        u.thumbUrl = e.partnerThumb;
        u.lastMessageAt = e.lastMessageAt;
        u.unread   = e.unread;
        u.lastMessage           = e.lastMessage;
        u.lastMessageType       = e.lastMessageType;
        u.lastMessageStatus     = e.lastMessageStatus;
        u.lastMessageSenderUid  = e.lastMessageSenderUid;
        u.lastMessageId         = e.lastMessageId;
        return u;
    }

    /** Effective sort timestamp — lastMessageAt, falling back to lastSeen. */
    private static long effTs(User u) {
        if (u.lastMessageAt != null) return u.lastMessageAt;
        if (u.lastSeen != null) return u.lastSeen;
        return 0L;
    }

    /** Special-request senders float to top; everything else by most-recent activity. */
    private static void sortContactsList(List<User> list, Set<String> specialUids) {
        Collections.sort(list, (a, b) -> {
            boolean aS = a.uid != null && specialUids.contains(a.uid);
            boolean bS = b.uid != null && specialUids.contains(b.uid);
            if (aS != bS) return aS ? -1 : 1;
            return Long.compare(effTs(b), effTs(a));
        });
    }

    private void enrichContactFromUsers(User u, String myUid) {
        if (u.uid == null) return;
        FirebaseUtils.getUserRef(u.uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String name     = snap.child("name").getValue(String.class);
                    String photo    = snap.child("photoUrl").getValue(String.class);
                    String thumb    = snap.child("thumbUrl").getValue(String.class);
                    boolean changed = false;
                    if (name != null && !name.isEmpty() && (u.name == null || u.name.isEmpty())) {
                        u.name = name; changed = true;
                        FirebaseUtils.getContactsRef(myUid)
                            .child(u.uid).child("name").setValue(name);
                    }
                    if (photo != null && !photo.isEmpty() && u.photoUrl == null) {
                        u.photoUrl = photo; changed = true;
                        FirebaseUtils.getContactsRef(myUid)
                            .child(u.uid).child("photoUrl").setValue(photo);
                    }
                    if (thumb != null && !thumb.isEmpty() && u.thumbUrl == null) {
                        u.thumbUrl = thumb; changed = true;
                        FirebaseUtils.getContactsRef(myUid)
                            .child(u.uid).child("thumbUrl").setValue(thumb);
                    }
                    if (changed && adapter != null) {
                        // FIX #5: Find the specific position of this user and only rebind that row
                        for (int i = 0; i < contacts.size(); i++) {
                            if (u.uid != null && u.uid.equals(contacts.get(i).uid)) {
                                adapter.notifyItemChanged(i);
                                break;
                            }
                        }
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void loadSpecialRequests() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseUtils.getCurrentUid();

        specialRequestsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                specialRequestUids.clear();
                for (DataSnapshot c : snap.getChildren())
                    if (c.getKey() != null) specialRequestUids.add(c.getKey());
                if (adapter != null) {
                    adapter.setSpecialRequestSenders(specialRequestUids);
                    sortByLatestMessage();
                    // FIX #5: diffUpdate instead of notifyDataSetChanged
                    diffUpdateContacts(new ArrayList<>(contacts));
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        specialRequestsRef = FirebaseUtils.db().getReference("specialRequests").child(uid);
        specialRequestsRef.addValueEventListener(specialRequestsListener);
    }

    // PERF MONITOR: real android.view.FrameMetrics tracking only while this
    // tab is actually the one on screen (API 24+; older devices simply won't
    // populate the frame-jank section of the report — never faked).
    @Override
    public void onResume() {
        super.onResume();
        if (android.os.Build.VERSION.SDK_INT >= 24 && getActivity() != null) {
            com.callx.app.perf.PerformanceMonitor.get().attachFrameTracking(getActivity());
        }
    }

    @Override
    public void onPause() {
        if (android.os.Build.VERSION.SDK_INT >= 24 && getActivity() != null) {
            com.callx.app.perf.PerformanceMonitor.get().detachFrameTracking(getActivity());
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (contactsRef != null && contactsListener != null) {
            contactsRef.removeEventListener(contactsListener);
            contactsRef = null; contactsListener = null;
        }
        if (specialRequestsRef != null && specialRequestsListener != null) {
            specialRequestsRef.removeEventListener(specialRequestsListener);
            specialRequestsRef = null; specialRequestsListener = null;
        }
        // v92: in case a loadMoreOlderContacts() request is still in flight when
        // the view is torn down (its single-value callback bails out early via
        // the getActivity()==null guard and never resets this) — don't leave
        // pagination stuck "loading" forever if the fragment's view is recreated.
        isLoadingMoreChats = false;
        pbLoadingMoreChats = null;
        // v93: cancel any debounced snapshot-processing work still pending.
        if (pendingContactsWork != null) {
            mainHandler.removeCallbacks(pendingContactsWork);
            pendingContactsWork = null;
        }
        // v94: drop any not-yet-processed delta so a recreated view starts clean.
        pendingChildUpserts.clear();
        pendingChildRemovals.clear();
        super.onDestroyView();
    }

    private void sortByLatestMessage() {
        sortContactsList(contacts, specialRequestUids);
    }

    /**
     * v83: DiffUtil logic moved into ChatListAdapter.DIFF_CALLBACK + AsyncListDiffer.
     * The diff now runs on a background thread (AsyncListDiffer default executor)
     * so the main thread never blocks on calculateDiff(), no matter how large the
     * list grows. This method keeps its signature so all existing call-sites
     * compile without change — it just updates the local working copy and hands
     * the new list to the adapter's differ.
     *
     * v92: also refreshes oldestLoadedTimestamp — the pagination cursor used by
     * loadMoreOlderContacts() — from whatever is now in `contacts`, so "load
     * more" always continues from wherever the currently-displayed list ends,
     * regardless of whether it came from Room, the live window, or a page load.
     */
    private void diffUpdateContacts(List<User> newList) {
        contacts.clear();
        contacts.addAll(newList);

        Long oldest = null;
        for (User u : contacts) {
            long t = effTs(u);
            if (oldest == null || t < oldest) oldest = t;
        }
        oldestLoadedTimestamp = oldest;

        if (adapter != null) adapter.submitList(new ArrayList<>(contacts));
        // PERF MONITOR: no-op after the first call each load-cycle (guarded
        // internally via loadStartNanos == 0 check) — closes the load-time
        // window opened in onCreateView.
        com.callx.app.perf.PerformanceMonitor.get().markChatListLoadEnd();
    }

    // ── SelectionListener callbacks ─────────────────────────────────────────

    @Override public void onSelectionStarted() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.VISIBLE);
        updateSelectionCount();
    }
    @Override public void onSelectionChanged() { updateSelectionCount(); }
    @Override public void onSelectionCleared() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    private void updateSelectionCount() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        int total = contacts.size();
        if (tvSelectedCount != null)
            tvSelectedCount.setText(count + " / " + total + " selected");
    }

    // ── v21 DELETE SELECTED ────────────────────────────────────────────────

    private void confirmDeleteSelected() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        if (count == 0) {
            Toast.makeText(getContext(), "Koi bhi select nahi kiya", Toast.LENGTH_SHORT).show();
            return;
        }
        com.callx.app.utils.AlertDialogStyler.showReusableConfirm(requireContext(),
                "delete_selected_chats", com.callx.app.utils.AlertDialogStyler.DialogSize.COMPACT,
                "Delete " + count + " chat" + (count > 1 ? "s" : "") + "?",
                "Selected conversations aapki chat list se remove ho jayenge.",
                "Delete", this::deleteSelected,
                null, null,
                "Cancel");
    }

    private void deleteSelected() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseUtils.getCurrentUid();
        List<User> selected = adapter.getSelectedItems();
        if (selected.isEmpty()) return;

        // Firebase se remove
        for (User u : selected) {
            if (u.uid != null) {
                FirebaseUtils.getContactsRef(myUid).child(u.uid).removeValue();
            }
        }

        // Room DB se remove (background thread)
        if (getContext() != null) {
            AppDatabase db = AppDatabase.getInstance(getContext());
            AppBgExecutor.execute(() -> {
                for (User u : selected) {
                    if (u.uid != null) {
                        db.chatDao().deleteByPartnerUid(u.uid);
                    }
                }
            });
        }

        // Local list update
        contacts.removeAll(selected);
        adapter.clearSelection();
        // FIX #5: diffUpdate animates the removed rows instead of blinking the full list
        diffUpdateContacts(new ArrayList<>(contacts));

        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        if (emptyState != null)
            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);

        int cnt = selected.size();
        Toast.makeText(getContext(),
            cnt + " chat" + (cnt > 1 ? "s" : "") + " delete ho gaye",
            Toast.LENGTH_SHORT).show();
    }

    // ── v21 DELETE ALL ─────────────────────────────────────────────────────

    /**
     * Delete All chats — accessible via 3-dot overflow menu in MainActivity/toolbar.
     * ChatsFragment exposes this as a public method so parent can call it.
     */
    public void confirmDeleteAll() {
        if (contacts.isEmpty()) {
            Toast.makeText(getContext(), "Koi chat nahi hai", Toast.LENGTH_SHORT).show();
            return;
        }
        com.callx.app.utils.AlertDialogStyler.showReusableConfirm(requireContext(),
                "delete_all_chats", com.callx.app.utils.AlertDialogStyler.DialogSize.COMPACT,
                "Delete All Chats?",
                "Aapki saari " + contacts.size() + " conversations chat list se remove ho jayengi.\n\nYe action undo nahi ho sakti.",
                "Delete All", this::deleteAllChats,
                null, null,
                "Cancel");
    }

    private void deleteAllChats() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseUtils.getCurrentUid();

        // Firebase contacts node clear karo
        FirebaseUtils.getContactsRef(myUid).removeValue();

        // Room DB completely clear karo
        if (getContext() != null) {
            AppDatabase db = AppDatabase.getInstance(getContext());
            AppBgExecutor.execute(() ->
                db.chatDao().deleteAllChats());
        }

        // Local list clear karo
        List<User> empty = Collections.emptyList();
        adapter.clearSelection();
        // FIX #5: diffUpdate dispatches removeItem animations for each deleted row
        diffUpdateContacts(empty);

        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        if (emptyState != null) emptyState.setVisibility(View.VISIBLE);

        Toast.makeText(getContext(), "Saare chats delete ho gaye", Toast.LENGTH_SHORT).show();
    }

    // ── Contact Bottom Sheet ─────────────────────────────────────────────────

    private void showContactBottomSheet(User user) {
        if (getContext() == null || user == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(getContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        View sv = LayoutInflater.from(getContext())
            .inflate(R.layout.bottom_sheet_contact_call, null);
        sheet.setContentView(sv);

        CircleImageView ivAvatar = sv.findViewById(R.id.iv_avatar_sheet);
        TextView tvName          = sv.findViewById(R.id.tv_name_sheet);
        TextView tvStatus        = sv.findViewById(R.id.tv_status_sheet);
        View onlineDot           = sv.findViewById(R.id.view_online_dot_sheet);
        View btnMessage          = sv.findViewById(R.id.btn_message_sheet);
        View btnVoice            = sv.findViewById(R.id.btn_voice_call_sheet);
        View btnVideo            = sv.findViewById(R.id.btn_video_call_sheet);
        View btnHistory          = sv.findViewById(R.id.btn_call_history_sheet);

        // Social platform buttons
        View btnXSheet            = sv.findViewById(R.id.btn_x_sheet);
        View btnReelsSheet        = sv.findViewById(R.id.btn_reels_sheet);
        View btnYoutubeSheet      = sv.findViewById(R.id.btn_youtube_sheet);
        CircleImageView ivAnimX   = sv.findViewById(R.id.iv_anim_x_sheet);
        CircleImageView ivAnimReel= sv.findViewById(R.id.iv_anim_reel_sheet);
        CircleImageView ivAnimYt  = sv.findViewById(R.id.iv_anim_youtube_sheet);

        View layoutXRow      = sv.findViewById(R.id.layout_x_follow_row);
        View layoutReelsRow  = sv.findViewById(R.id.layout_reels_follow_row);
        View layoutYtRow     = sv.findViewById(R.id.layout_youtube_subscribe_row);
        TextView tvXCount    = sv.findViewById(R.id.tv_x_followers_count);
        TextView tvReelsCount= sv.findViewById(R.id.tv_reels_followers_count);
        TextView tvYtCount   = sv.findViewById(R.id.tv_youtube_subs_count);
        Button btnXFollow    = sv.findViewById(R.id.btn_x_follow_action);
        Button btnReelsFollow= sv.findViewById(R.id.btn_reels_follow_action);
        Button btnYtSub      = sv.findViewById(R.id.btn_youtube_subscribe_action);

        tvName.setText(user.name != null ? user.name : "User");

        String avatarUrl = (user.thumbUrl != null && !user.thumbUrl.isEmpty())
            ? user.thumbUrl : user.photoUrl;
        if (avatarUrl != null && !avatarUrl.isEmpty() && ivAvatar != null) {
            Glide.with(getContext()).load(avatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .override(96, 96)
                .into(ivAvatar);
        }

        if (user.uid != null) {
            FirebaseUtils.getUserRef(user.uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        Boolean online = snap.child("online").getValue(Boolean.class);
                        if (Boolean.TRUE.equals(online)) {
                            if (onlineDot != null) onlineDot.setVisibility(View.VISIBLE);
                            if (tvStatus  != null) {
                                tvStatus.setText("Online");
                                tvStatus.setTextColor(getResources().getColor(R.color.brand_accent, null));
                            }
                        } else {
                            if (onlineDot != null) onlineDot.setVisibility(View.GONE);
                            if (tvStatus  != null) {
                                tvStatus.setText("Offline");
                                tvStatus.setTextColor(getResources().getColor(R.color.text_muted, null));
                            }
                        }
                        String photo = snap.child("photoUrl").getValue(String.class);
                        String thumb = snap.child("thumbUrl").getValue(String.class);
                        String url   = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        if (url != null && !url.isEmpty() && getContext() != null && ivAvatar != null)
                            Glide.with(getContext()).load(url)
                                .apply(RequestOptions.circleCropTransform())
                                .placeholder(R.drawable.ic_person).into(ivAvatar);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        }

        if (ivAvatar != null) {
            ivAvatar.setOnClickListener(x -> {
                if (user.uid == null || getContext() == null) return;
                FirebaseUtils.getUserRef(user.uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            String full = snap.child("photoUrl").getValue(String.class);
                            showChatAvatarZoom((full != null && !full.isEmpty()) ? full : avatarUrl);
                        }
                        @Override public void onCancelled(DatabaseError e) {
                            showChatAvatarZoom(avatarUrl);
                        }
                    });
            });
        }

        if (btnMessage != null) {
            btnMessage.setOnClickListener(x -> {
                sheet.dismiss();
                if (user.uid == null || getContext() == null) return;
                final Context appCtx = getContext().getApplicationContext();
                String myUid = FirebaseUtils.getCurrentUid();
                String chatId = myUid != null ? FirebaseUtils.getChatId(myUid, user.uid) : null;

                Runnable navigate = () -> {
                    if (getContext() == null) return;
                    Intent i = new Intent().setClassName(getContext().getPackageName(),
                        "com.callx.app.conversation.ChatActivity");
                    i.putExtra("partnerUid",   user.uid);
                    i.putExtra("partnerName",  user.name != null ? user.name : "");
                    i.putExtra("partnerPhoto", user.photoUrl != null ? user.photoUrl : "");
                    i.putExtra("partnerThumb", user.thumbUrl != null ? user.thumbUrl : "");
                    startActivity(i);
                };

                if (chatId == null) { navigate.run(); return; }

                // Same WhatsApp-style priming as the chat-list tap: read local
                // Room data first, then open — with a short safety cap so a
                // brand-new chat never feels stuck.
                final boolean[] navigated = {false};
                android.os.Handler safetyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable safetyFallback = () -> { if (!navigated[0]) { navigated[0] = true; navigate.run(); } };
                safetyHandler.postDelayed(safetyFallback, 150L);
                com.callx.app.repository.ChatRepository.getInstance(appCtx).primeChatFromRoom(chatId, () -> {
                    safetyHandler.removeCallbacks(safetyFallback);
                    if (!navigated[0]) { navigated[0] = true; navigate.run(); }
                });
            });
        }

        if (btnVoice != null) {
            btnVoice.setOnClickListener(x -> {
                sheet.dismiss();
                if (user.uid == null || getContext() == null) return;
                Intent i = new Intent().setClassName(getContext().getPackageName(),
                    "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid",  user.uid);
                i.putExtra("partnerName", user.name != null ? user.name : "");
                i.putExtra("isCaller", true);
                i.putExtra("video", false);
                startActivity(i);
            });
        }

        if (btnVideo != null) {
            btnVideo.setOnClickListener(x -> {
                sheet.dismiss();
                if (user.uid == null || getContext() == null) return;
                Intent i = new Intent().setClassName(getContext().getPackageName(),
                    "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid",  user.uid);
                i.putExtra("partnerName", user.name != null ? user.name : "");
                i.putExtra("isCaller", true);
                i.putExtra("video", true);
                startActivity(i);
            });
        }

        if (btnHistory != null) {
            btnHistory.setOnClickListener(x -> {
                sheet.dismiss();
                showChatCallHistorySheet(user);
            });
        }

        if (user.uid != null) {
            final CircleImageView[] peekViews = {ivAnimX, ivAnimReel, ivAnimYt};
            final Handler[] animHandler       = {new Handler(Looper.getMainLooper())};
            final boolean[] animRunning       = {false};
            final Runnable[] animRunnable     = {null};

            loadChatSocialButtons(user.uid, sv,
                btnXSheet, btnReelsSheet, btnYoutubeSheet,
                ivAnimX, ivAnimReel, ivAnimYt,
                layoutXRow, layoutReelsRow, layoutYtRow,
                tvXCount, tvReelsCount, tvYtCount,
                btnXFollow, btnReelsFollow, btnYtSub,
                sheet, peekViews, animHandler, animRunning, animRunnable);

            sheet.setOnDismissListener(d -> {
                animRunning[0] = false;
                animHandler[0].removeCallbacks(animRunnable[0]);
                for (CircleImageView iv : peekViews) {
                    if (iv != null) {
                        iv.setVisibility(View.INVISIBLE);
                        iv.setScaleX(0f); iv.setScaleY(0f); iv.setAlpha(0f);
                    }
                }
            });
        }

        sheet.show();
    }

    private void loadChatSocialButtons(
            String partnerUid, View sv,
            View btnXSheet, View btnReelsSheet, View btnYoutubeSheet,
            CircleImageView ivAnimX, CircleImageView ivAnimReel, CircleImageView ivAnimYt,
            View layoutXRow, View layoutReelsRow, View layoutYtRow,
            TextView tvXCount, TextView tvReelsCount, TextView tvYtCount,
            Button btnXFollow, Button btnReelsFollow, Button btnYtSub,
            BottomSheetDialog sheet,
            CircleImageView[] peekViews,
            Handler[] animHandler, boolean[] animRunning, Runnable[] animRunnable) {

        if (getContext() == null) return;
        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
        final FirebaseDatabase db = FirebaseDatabase.getInstance(DB);
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseUtils.getCurrentUid() : null;

        // ── X ──
        db.getReference("x/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;
                    String xPhoto = snap.child("photoUrl").getValue(String.class);
                    if (xPhoto != null && !xPhoto.isEmpty() && ivAnimX != null)
                        Glide.with(getContext()).load(xPhoto).circleCrop()
                            .override(96, 96)
                            .placeholder(R.drawable.ic_person).into(ivAnimX);
                    startChatAvatarPeekLoop(peekViews, animHandler, animRunning, animRunnable);

                    Long xF = snap.child("followerCount").getValue(Long.class);
                    long xFCount = xF != null ? xF : 0;
                    if (tvXCount != null) tvXCount.setText(formatCount(xFCount) + " Followers");
                    if (layoutXRow != null) layoutXRow.setVisibility(View.VISIBLE);

                    if (myUid != null && btnXFollow != null) {
                        db.getReference("x/followers").child(partnerUid).child(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot ds) {
                                    boolean[] isF = {ds.exists() && Boolean.TRUE.equals(ds.getValue(Boolean.class))};
                                    updateXBtn(btnXFollow, isF[0]);
                                    btnXFollow.setOnClickListener(v -> {
                                        isF[0] = !isF[0]; updateXBtn(btnXFollow, isF[0]);
                                        if (isF[0]) {
                                            db.getReference("x/followers").child(partnerUid).child(myUid).setValue(true);
                                            db.getReference("x/following").child(myUid).child(partnerUid).setValue(true);
                                            if (tvXCount != null) bumpCount(tvXCount, 1, "Followers");
                                        } else {
                                            db.getReference("x/followers").child(partnerUid).child(myUid).removeValue();
                                            db.getReference("x/following").child(myUid).child(partnerUid).removeValue();
                                            if (tvXCount != null) bumpCount(tvXCount, -1, "Followers");
                                        }
                                    });
                                }
                                @Override public void onCancelled(DatabaseError e) {}
                            });
                    }

                    if (btnXSheet != null) {
                        btnXSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            try {
                                Class<?> cls = Class.forName("com.callx.app.profile.XProfileSheet");
                                java.lang.reflect.Method m = cls.getMethod("showProfile",
                                    androidx.fragment.app.FragmentManager.class, String.class);
                                m.invoke(null, getParentFragmentManager(), partnerUid);
                            } catch (Exception ex) {
                                if (getContext() != null)
                                    Toast.makeText(getContext(), "X profile not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

        // ── Reels ──
        db.getReference("reels/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String rp    = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (rp != null && !rp.isEmpty() && ivAnimReel != null)
                        Glide.with(getContext()).load(rp).circleCrop()
                            .override(96, 96)
                            .placeholder(R.drawable.ic_person).into(ivAnimReel);

                    db.getReference("reels/followers").child(partnerUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot fSnap) {
                                long cnt = fSnap.getChildrenCount();
                                if (tvReelsCount != null) tvReelsCount.setText(formatCount(cnt) + " Followers");
                                if (layoutReelsRow != null) layoutReelsRow.setVisibility(View.VISIBLE);
                                if (myUid != null && btnReelsFollow != null) {
                                    boolean[] isF = {fSnap.hasChild(myUid)};
                                    updateReelsBtn(btnReelsFollow, isF[0]);
                                    btnReelsFollow.setOnClickListener(v -> {
                                        isF[0] = !isF[0]; updateReelsBtn(btnReelsFollow, isF[0]);
                                        if (isF[0]) {
                                            db.getReference("reels/followers").child(partnerUid).child(myUid).setValue(true);
                                            db.getReference("reels/following").child(myUid).child(partnerUid).setValue(true);
                                            if (tvReelsCount != null) bumpCount(tvReelsCount, 1, "Followers");
                                        } else {
                                            db.getReference("reels/followers").child(partnerUid).child(myUid).removeValue();
                                            db.getReference("reels/following").child(myUid).child(partnerUid).removeValue();
                                            if (tvReelsCount != null) bumpCount(tvReelsCount, -1, "Followers");
                                        }
                                    });
                                }
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });

                    if (btnReelsSheet != null) {
                        btnReelsSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            if (getContext() == null) return;
                            try {
                                Class<?> cls = Class.forName("com.callx.app.profile.UserReelsActivity");
                                Intent i = new Intent(getContext(), cls);
                                i.putExtra("uid", partnerUid);
                                startActivity(i);
                            } catch (ClassNotFoundException ex) {
                                Toast.makeText(getContext(), "Reels not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

        // ── YouTube ──
        db.getReference("youtube/channels").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;
                    String yt = snap.child("thumbUrl").getValue(String.class);
                    String yp = snap.child("photoUrl").getValue(String.class);
                    String ya = (yt != null && !yt.isEmpty()) ? yt : yp;
                    if (ya != null && !ya.isEmpty() && ivAnimYt != null)
                        Glide.with(getContext()).load(ya).circleCrop()
                            .override(96, 96)
                            .placeholder(R.drawable.ic_person).into(ivAnimYt);

                    Long subC = snap.child("subscriberCount").getValue(Long.class);
                    long subs = subC != null ? subC : 0;
                    if (tvYtCount != null) tvYtCount.setText(formatCount(subs) + " Subscribers");
                    if (layoutYtRow != null) layoutYtRow.setVisibility(View.VISIBLE);

                    if (myUid != null && btnYtSub != null) {
                        db.getReference("youtube/subscribers").child(partnerUid).child(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot ds) {
                                    boolean[] isS = {ds.exists() && Boolean.TRUE.equals(ds.getValue(Boolean.class))};
                                    updateYtBtn(btnYtSub, isS[0]);
                                    btnYtSub.setOnClickListener(v -> {
                                        isS[0] = !isS[0]; updateYtBtn(btnYtSub, isS[0]);
                                        if (isS[0]) {
                                            db.getReference("youtube/subscribers").child(partnerUid).child(myUid).setValue(true);
                                            if (tvYtCount != null) bumpCount(tvYtCount, 1, "Subscribers");
                                        } else {
                                            db.getReference("youtube/subscribers").child(partnerUid).child(myUid).removeValue();
                                            if (tvYtCount != null) bumpCount(tvYtCount, -1, "Subscribers");
                                        }
                                    });
                                }
                                @Override public void onCancelled(DatabaseError e) {}
                            });
                    }

                    if (btnYoutubeSheet != null) {
                        btnYoutubeSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            if (getContext() == null) return;
                            try {
                                Class<?> cls = Class.forName("com.callx.app.channel.YouTubeChannelActivity");
                                Intent i = new Intent(getContext(), cls);
                                i.putExtra("uid", partnerUid);
                                startActivity(i);
                            } catch (ClassNotFoundException ex) {
                                Toast.makeText(getContext(), "YouTube not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ── Avatar Peek Loop ───────────────────────────────────────────────────
    private void startChatAvatarPeekLoop(
            CircleImageView[] views, Handler[] handlerArr,
            boolean[] runningArr, Runnable[] runnableArr) {
        if (runningArr[0]) return;
        runningArr[0] = true;
        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f); iv.setScaleY(0f); iv.setAlpha(0f);
        }
        // Avatar peek loop animation removed for performance — show avatars statically
        runnableArr[0] = new Runnable() {
            int idx = 0;
            @Override public void run() {
                if (!runningArr[0] || getContext() == null) return;
                CircleImageView iv = views[idx % views.length]; idx++;
                if (iv == null) { handlerArr[0].postDelayed(this, 3000); return; }
                iv.setScaleX(1f); iv.setScaleY(1f); iv.setAlpha(1f);
                iv.setVisibility(View.VISIBLE);
                final Runnable me = this;
                handlerArr[0].postDelayed(() -> {
                    iv.setVisibility(View.INVISIBLE);
                    if (runningArr[0] && getContext() != null)
                        handlerArr[0].postDelayed(me, 1000);
                }, 3000);
            }
        };
        handlerArr[0].postDelayed(runnableArr[0], 1500);
    }

    // ── Button state helpers ───────────────────────────────────────────────
    private void updateXBtn(Button btn, boolean following) {
        if (btn == null) return;
        btn.setText(following ? "Following" : "Follow");
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(following ? 0xFF333333 : 0xFF000000));
    }
    private void updateReelsBtn(Button btn, boolean following) {
        if (btn == null) return;
        btn.setText(following ? "Following" : "Follow");
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(following ? 0xFF555555 : 0xFFDD2A7B));
    }
    private void updateYtBtn(Button btn, boolean subscribed) {
        if (btn == null) return;
        btn.setText(subscribed ? "Subscribed" : "Subscribe");
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(subscribed ? 0xFF333333 : 0xFFFF0000));
    }
    private void bumpCount(TextView tv, int delta, String label) {
        try {
            String text = tv.getText().toString().split(" ")[0].replace("K","000").replace("M","000000");
            long cur = Long.parseLong(text);
            tv.setText(formatCount(Math.max(0, cur + delta)) + " " + label);
        } catch (Exception ignored) {}
    }
    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ── Call History Sheet ──────────────────────────────────────────────────
    private void showChatCallHistorySheet(User user) {
        if (getContext() == null || user.uid == null) return;

        BottomSheetDialog histSheet = new BottomSheetDialog(getContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        View sv = LayoutInflater.from(getContext())
            .inflate(R.layout.bottom_sheet_call_history, null);
        histSheet.setContentView(sv);

        CircleImageView ivAvatar = sv.findViewById(R.id.iv_history_avatar);
        TextView tvName          = sv.findViewById(R.id.tv_history_name);
        TextView tvCount         = sv.findViewById(R.id.tv_history_count);
        View btnClose            = sv.findViewById(R.id.btn_close_history);
        androidx.recyclerview.widget.RecyclerView rv = sv.findViewById(R.id.rv_call_history_sheet);
        View llEmpty             = sv.findViewById(R.id.ll_history_empty);

        tvName.setText(user.name != null ? user.name : "User");
        tvCount.setText("Loading...");

        String avatarUrl = (user.thumbUrl != null && !user.thumbUrl.isEmpty())
            ? user.thumbUrl : user.photoUrl;
        if (avatarUrl != null && !avatarUrl.isEmpty() && ivAvatar != null) {
            Glide.with(getContext()).load(avatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person).into(ivAvatar);
        }

        if (ivAvatar != null) {
            ivAvatar.setOnClickListener(x -> showChatAvatarZoom(avatarUrl));
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(x -> histSheet.dismiss());
        }

        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseUtils.getCurrentUid() : null;
        if (myUid == null) {
            tvCount.setText("0 calls");
            if (llEmpty != null) llEmpty.setVisibility(View.VISIBLE);
            histSheet.show();
            return;
        }

        FirebaseUtils.getCallsRef(myUid)
            .orderByChild("partnerUid").equalTo(user.uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null) return;
                    List<com.callx.app.models.CallLog> contactLogs = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        com.callx.app.models.CallLog l = c.getValue(com.callx.app.models.CallLog.class);
                        if (l != null) {
                            if (l.id == null) l.id = c.getKey();
                            contactLogs.add(l);
                        }
                    }
                    contactLogs.sort((a, b) -> {
                        long ta = a.timestamp != null ? a.timestamp : 0;
                        long tb = b.timestamp != null ? b.timestamp : 0;
                        return Long.compare(tb, ta);
                    });

                    int total = contactLogs.size();
                    if (tvCount != null) tvCount.setText(total + " call" + (total != 1 ? "s" : ""));

                    if (contactLogs.isEmpty()) {
                        if (rv     != null) rv.setVisibility(View.GONE);
                        if (llEmpty != null) llEmpty.setVisibility(View.VISIBLE);
                    } else {
                        if (llEmpty != null) llEmpty.setVisibility(View.GONE);
                        if (rv != null) {
                            rv.setLayoutManager(new LinearLayoutManager(getContext()));
                            rv.setAdapter(new ChatCallHistoryAdapter(contactLogs));
                        }
                    }
                }
                @Override public void onCancelled(DatabaseError e) {
                    if (tvCount != null) tvCount.setText("0 calls");
                    if (llEmpty != null) llEmpty.setVisibility(View.VISIBLE);
                }
            });

        histSheet.show();
    }

    // ── Inline adapter for call history sheet ──────────────────────────────
    private class ChatCallHistoryAdapter
            extends androidx.recyclerview.widget.RecyclerView.Adapter<ChatCallHistoryAdapter.VH> {

        private final List<com.callx.app.models.CallLog> logs;
        private final java.text.SimpleDateFormat fmt =
            new java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault());

        ChatCallHistoryAdapter(List<com.callx.app.models.CallLog> logs) { this.logs = logs; }

        @androidx.annotation.NonNull
        @Override
        public VH onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup parent, int viewType) {
            android.view.View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_call_history_sheet, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) {
            com.callx.app.models.CallLog l = logs.get(pos);
            android.content.Context ctx = h.itemView.getContext();

            boolean isVideo = "video".equals(l.mediaType);
            String dir = l.direction == null ? "" : l.direction.toLowerCase();

            String label; int iconColor; int iconRes;
            if (dir.contains("missed")) {
                label = isVideo ? "Missed Video" : "Missed Voice";
                iconColor = android.graphics.Color.parseColor("#EF4444");
                iconRes = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            } else if (dir.contains("incoming") || dir.contains("in")) {
                label = isVideo ? "Incoming Video" : "Incoming Voice";
                iconColor = android.graphics.Color.parseColor("#22C55E");
                iconRes = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            } else {
                label = isVideo ? "Outgoing Video" : "Outgoing Voice";
                iconColor = android.graphics.Color.parseColor("#5B5BF6");
                iconRes = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            }

            h.tvLabel.setText(label);
            h.tvLabel.setTextColor(iconColor);
            h.ivIcon.setImageResource(iconRes);
            h.ivIcon.setColorFilter(iconColor);
            h.tvTime.setText(l.timestamp != null ? fmt.format(new java.util.Date(l.timestamp)) : "—");

            if (l.duration != null && l.duration > 0) {
                long d = l.duration;
                h.tvDuration.setText(d >= 60 ? (d / 60) + "m " + (d % 60) + "s" : d + "s");
                h.tvDuration.setVisibility(android.view.View.VISIBLE);
            } else {
                h.tvDuration.setVisibility(android.view.View.GONE);
            }

            h.ivQuickCall.setImageResource(isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone);
            h.ivQuickCall.setColorFilter(android.graphics.Color.parseColor("#5B5BF6"));
            h.ivQuickCall.setOnClickListener(v -> {
                if (l.partnerUid == null) return;
                android.content.Intent i = new android.content.Intent()
                    .setClassName(ctx.getPackageName(), "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid",  l.partnerUid);
                i.putExtra("partnerName", l.partnerName != null ? l.partnerName : "");
                i.putExtra("isCaller", true);
                i.putExtra("video", isVideo);
                ctx.startActivity(i);
            });
        }

        @Override public int getItemCount() { return logs.size(); }

        class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            android.widget.ImageView ivIcon, ivQuickCall;
            android.widget.TextView tvLabel, tvTime, tvDuration;
            VH(android.view.View v) {
                super(v);
                ivIcon      = v.findViewById(R.id.iv_call_type_icon);
                tvLabel     = v.findViewById(R.id.tv_call_type_label);
                tvTime      = v.findViewById(R.id.tv_call_time);
                tvDuration  = v.findViewById(R.id.tv_call_duration);
                ivQuickCall = v.findViewById(R.id.iv_quick_call);
            }
        }
    }

    // ── Avatar Zoom ────────────────────────────────────────────────────────
    private void showChatAvatarZoom(String photoUrl) {
        if (getContext() == null) return;
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            getContext(), photoUrl, R.drawable.ic_person, R.drawable.ic_close);
    }
}
