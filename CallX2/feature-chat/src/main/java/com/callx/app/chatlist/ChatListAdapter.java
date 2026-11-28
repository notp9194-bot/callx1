package com.callx.app.chatlist;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;
import com.callx.app.cache.ChatAvatarBinder;
import com.callx.app.cache.AvatarVersionSyncManager;

import com.callx.app.chatlist.canvas.ChatListLastMessageView;
import com.callx.app.chatlist.canvas.ChatRowAvatarView;
import com.callx.app.chatlist.canvas.ChatRowContentView;
import com.callx.app.chatlist.canvas.ChatRowMetaView;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.docked.DockedOverlayRegistry;
import com.callx.app.models.User;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.cache.StatusCacheManager;
import com.callx.app.cache.StoryRingRegistry;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.ChatListPreviewUtil;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ChatListAdapter v83
 *
 * CHANGES v83 — AsyncListDiffer (background-thread diff):
 *  • DiffUtil.ItemCallback<User> DIFF_CALLBACK defined as static constant —
 *    areItemsTheSame() compares UID, areContentsTheSame() compares every field
 *    that drives row UI (name, lastMessage, photo, unread, status, senderUid, time).
 *  • Internal list is now owned by AsyncListDiffer<User> rather than the caller's
 *    ArrayList — all reads go through differ.getCurrentList().
 *  • submitList(List<User>) replaces the old constructor-injected list; AsyncListDiffer
 *    ships the diff computation to a background thread so the main thread never blocks
 *    on calculateDiff(), regardless of list size.
 *  • ChatsFragment calls adapter.submitList(sorted) instead of managing
 *    diffUpdateContacts() itself — diff logic lives in one place (the adapter).
 *
 * CHANGES v90 — Row-content consolidation:
 *  ChatListNameTimeView + ChatListLastMessageView (both already canvas from
 *  v82/v23) merged into ONE view, ChatRowContentView — one measure/layout/
 *  draw pass in the row's text column instead of two. VH keeps both old
 *  field names (nameTimeView, lastMessageView) pointing at the same
 *  instance so every existing call site below is unchanged. Scoped to
 *  item_chat.xml / this adapter only — GroupAdapter/item_group.xml still
 *  use the original two separate views.
 *
 * CHANGES v82 — Full canvas row (perf):
 *  CardView → FrameLayout; tv_name+tv_time → ChatListNameTimeView;
 *  tv_unread_badge → ChatListUnreadBadgeView; iv_story_ring → ChatListStoryRingView;
 *  ll_call_btns + ImageButtons → ChatListCallButtonsView.
 *
 * CHANGES v23 — Canvas last-message + ticks (ChatListLastMessageView).
 * CHANGES v22 — Read receipts, media labels, live typing.
 * CHANGES v21 — Selection mode, Delete/Delete-All.
 */
public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {

    // ── v86: Partial-bind payload flags ──────────────────────────────────────
    // getChangePayload() returns an Integer bitmask of what changed.
    // onBindViewHolder(payloads) checks the flags and only redraws the canvas
    // views that actually need updating — e.g. a delivered→read tick flip only
    // redraws ChatListLastMessageView, not the name/time/badge/avatar views.
    static final int CHANGE_IDENTITY = 0x01; // name, photo, thumbUrl
    static final int CHANGE_LAST_MSG = 0x02; // lastMessage, type, status, senderUid
    static final int CHANGE_UNREAD   = 0x04; // unread count
    static final int CHANGE_TIME     = 0x08; // lastMessageAt timestamp

    // ── v83: DiffUtil.ItemCallback ────────────────────────────────────────────
    public static final DiffUtil.ItemCallback<User> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<User>() {

        @Override
        public boolean areItemsTheSame(@NonNull User a, @NonNull User b) {
            return a.uid != null && a.uid.equals(b.uid);
        }

        @Override
        public boolean areContentsTheSame(@NonNull User a, @NonNull User b) {
            return safeEq(a.name, b.name)
                && safeEq(a.photoUrl, b.photoUrl)
                && safeEq(a.thumbUrl, b.thumbUrl)
                && safeEq(a.lastMessage, b.lastMessage)
                && safeEq(a.lastMessageType, b.lastMessageType)
                && safeEq(a.lastMessageStatus, b.lastMessageStatus)
                && safeEq(a.lastMessageSenderUid, b.lastMessageSenderUid)
                && longEq(a.lastMessageAt, b.lastMessageAt)
                && longEq(a.unread, b.unread)
                && a.localPinned == b.localPinned
                && a.localMuted == b.localMuted
                && a.memberCount == b.memberCount
                && safeEq(a.lastSenderName, b.lastSenderName)
                && safeEq(a.lastMessageId, b.lastMessageId);
        }

        /**
         * v86: Return a bitmask of WHICH fields changed so onBindViewHolder
         * can do a surgical partial redraw instead of a full row rebind.
         * A tick flip (sent→delivered) sets CHANGE_LAST_MSG only — the
         * name/time/badge/avatar canvas views do zero work.
         */
        @Override
        public Object getChangePayload(@NonNull User a, @NonNull User b) {
            int flags = 0;
            if (!safeEq(a.name, b.name) || !safeEq(a.photoUrl, b.photoUrl)
                    || !safeEq(a.thumbUrl, b.thumbUrl)
                    || a.localPinned != b.localPinned)           flags |= CHANGE_IDENTITY;
            if (!safeEq(a.lastMessage, b.lastMessage)
                    || !safeEq(a.lastMessageType, b.lastMessageType)
                    || !safeEq(a.lastMessageStatus, b.lastMessageStatus)
                    || !safeEq(a.lastMessageSenderUid, b.lastMessageSenderUid)
                    || !safeEq(a.lastSenderName, b.lastSenderName)
                    || a.localMuted != b.localMuted
                    || a.memberCount != b.memberCount) flags |= CHANGE_LAST_MSG;
            if (!longEq(a.unread, b.unread))                     flags |= CHANGE_UNREAD;
            if (!longEq(a.lastMessageAt, b.lastMessageAt))       flags |= CHANGE_TIME;
            return flags == 0 ? null : flags;
        }

        private boolean safeEq(String x, String y) { return x == null ? y == null : x.equals(y); }
        private boolean longEq(Long x, Long y)      { return x == null ? y == null : x.equals(y); }
    };

    // ── v83: AsyncListDiffer — owns the list, runs diff on a bg thread ────────
    // v242: AsyncListDiffer's 2-arg constructor defaults to
    // ArchTaskExecutor.getIOThreadExecutor() — a single shared background
    // thread used app-wide for LiveData.postValue(), other AsyncListDiffer/
    // ListAdapter instances, etc. On the Chats tab specifically, Firebase's
    // ValueEventListener callbacks (v92 bounded live sync) and Room's own
    // query executor are separate pools already, but this shared IO executor
    // can still queue behind unrelated background work from elsewhere in the
    // app. A dedicated single-thread executor means a chat-list diff never
    // waits behind, or blocks, work that has nothing to do with this screen.
    private static final java.util.concurrent.Executor CHAT_DIFF_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ChatListDiffThread");
                t.setPriority(Thread.NORM_PRIORITY - 1); // slightly below default, never starve UI
                return t;
            });

    private final AsyncListDiffer<User> differ = new AsyncListDiffer<>(
            new androidx.recyclerview.widget.AdapterListUpdateCallback(this),
            new AsyncDifferConfig.Builder<>(DIFF_CALLBACK)
                    .setBackgroundThreadExecutor(CHAT_DIFF_EXECUTOR)
                    .build());

    // v92: Resources held for precompute() calls — width is now derived
    // per-user/per-call (screen width + that row's own badge/tick/time
    // state) instead of a single fixed estimate. See ChatListTextPrecompute.
    private android.content.res.Resources mRes;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        // Initialise TextPaint clones for the precompute cache.
        // Called once when the adapter is first attached; safe to call multiple times.
        mRes = recyclerView.getContext().getResources();
        ChatListTextPrecompute.init(mRes);
    }

    /**
     * Submit a new list. AsyncListDiffer computes the diff on a background
     * thread, then dispatches the minimal insert/remove/change operations to
     * this adapter on the main thread — the main thread never blocks.
     *
     * v89/v92: Also kicks off background text pre-computation for all items so
     * that onDraw() finds every ellipsized string already cached — using the
     * EXACT per-row width formula onDraw() will use (fixes the cache-key
     * mismatch that caused low hit ratios: see ChatListTextPrecompute).
     */
    public void submitList(List<User> newList) {
        submitList(newList, null);
    }

    /** @param onCommitted runs on the main thread once the diff has been dispatched (may be null). */
    public void submitList(List<User> newList, @Nullable Runnable onCommitted) {
        List<User> safe = newList == null ? Collections.emptyList() : newList;
        // v943: same User instances in the same order as what is already shown (tab reopen, a
        // no-op Firebase echo, a re-applied filter) — nothing for the differ to do, and nothing to
        // re-precompute or re-prefetch. Still honour the commit callback.
        if (isSameInstances(differ.getCurrentList(), safe)) {
            if (onCommitted != null) onCommitted.run();
            return;
        }
        differ.submitList(safe, onCommitted);
        // Batch-warm the verified-badge cache for this page of chats before
        // rows bind — avoids one Firebase read per row on a fast fling.
        List<String> uids = new ArrayList<>(safe.size());
        for (User u : safe) if (u.uid != null && !u.isGroup) uids.add(u.uid);
        com.callx.app.utils.VerifiedBadgeUtils.prefetch(uids);
        // Precompute runs on a background thread — main thread returns immediately.
        // By the time the diff result dispatches onBindViewHolder, most entries
        // will already be cached (precompute completes in ~10–50 ms for 100 rows).
        if (mRes != null) {
            ChatListTextPrecompute.precompute(safe, myUid, mRes, isSelecting);
        }
    }

    private static boolean isSameInstances(List<User> a, List<User> b) {
        if (a == b) return true;
        final int n = a.size();
        if (n != b.size() || n == 0) return false;
        for (int i = 0; i < n; i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    /** Returns the current snapshot (safe to read on the main thread). */
    public List<User> getCurrentList() {
        return differ.getCurrentList();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, Long> sLastPreloadAt = new ConcurrentHashMap<>();
    private static final long PRELOAD_COOLDOWN_MS = 30_000L;

    // PERF: how long a row has to stay bound before we actually attach its
    // typing listener / fire its preload — see onBindViewHolder(VH, int).
    private static final long BIND_SETTLE_DELAY_MS = 180L;

    /** Cancels this VH's deferred typing-attach/preload if it hasn't fired yet. */
    private void cancelPendingBindWork(VH h) {
        // v948 BUGFIX: this used to also set h.pendingBindRunnable = null. The runnable is created ONCE per VH
        // (VH ctor), and onBindViewHolder calls cancel -> post, so the very first bind nulled it and
        // postDelayed(null) did nothing: the typing listener / preloadChatIfDue / adjacent-avatar preload never ran.
        if (h.pendingBindRunnable != null) {
            h.itemView.removeCallbacks(h.pendingBindRunnable);
        }
    }

    /** v948: re-arms the settle work when a bound row is (re)attached — see onViewAttachedToWindow. */
    @Override
    public void onViewAttachedToWindow(@NonNull VH h) {
        super.onViewAttachedToWindow(h);
        if (h.boundUser != null && h.pendingBindRunnable != null) {
            h.itemView.removeCallbacks(h.pendingBindRunnable);
            h.itemView.postDelayed(h.pendingBindRunnable, BIND_SETTLE_DELAY_MS);
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull VH h) {
        super.onViewDetachedFromWindow(h);
        cancelPendingBindWork(h);   // fling-past rows never pay the typing listener / preload
    }

    private void preloadChatIfDue(Context ctx, User u) {
        if (u == null || u.uid == null || myUid == null) return;
        String chatId = u.isGroup ? u.groupId : chatIdFor(u.uid);
        if (chatId == null) return;
        long now = System.currentTimeMillis();
        Long last = sLastPreloadAt.get(chatId);
        if (last != null && (now - last) < PRELOAD_COOLDOWN_MS) return;
        sLastPreloadAt.put(chatId, now);
        ChatRepository.getInstance(ctx.getApplicationContext()).warmLastMessagesCache(chatId);
        // ChatActivity owns the live listener for the opened chat. Do not
        // start another pull from every recycled row; background preload
        // remains centralized in ChatRepository.preloadRecentChats().
    }

    public interface SelectionListener {
        void onSelectionStarted();
        void onSelectionChanged();
        void onSelectionCleared();
    }

    public interface OnAvatarClickListener {
        void onAvatarClick(User user);
    }

    /** @deprecated v21: Long-press now starts selection. */
    @Deprecated
    public interface OnLongPressListener {
        void onLongPress(User user, View anchor);
    }

    private final SelectionListener selectionListener;
    private OnAvatarClickListener avatarClickListener;

    /** kept for backward-compat; ChatsFragment v21 does NOT set this */
    @Deprecated
    private OnLongPressListener longPressListener;

    private Set<String> specialRequestSenders = new HashSet<>();

    private final String myUid = FirebaseUtils.getCurrentUid();

    private boolean isSelecting = false;
    private final Set<String> selectedUids = new HashSet<>();

    private static final String PAYLOAD_SELECTION = "payload_selection";
    // PERF FIX: dedicated payload for special-request badge flips — see
    // setSpecialRequestSenders() below.
    private static final String PAYLOAD_SPECIAL = "payload_special";
    // Partial-bind payload for a chat's mute state flipping (see setMutedUids()).
    private static final String PAYLOAD_MUTED = "payload_muted";
    // uids of chats the user muted (muted/{myUid}); private copy, never the caller's live set.
    private Set<String> mutedUids = new HashSet<>();

    // v389 WHATSAPP-LEVEL FIX: applySelectionVisuals()/updateReadStatusTicks()/
    // applyTypingRow() called ctx.getResources().getColor(...) up to 4-5
    // times on EVERY single bind (and rebind via payload) — a real
    // main-thread resource-resolution + theme lookup repeated for every row,
    // every time it scrolls into view, even though the resolved value is
    // identical on every call within one adapter's lifetime (the whole
    // Activity — and therefore this adapter — is recreated on a day/night
    // theme switch, so there's no stale-color risk in caching per instance).
    // Resolved ONCE, lazily, on the first bind that has a Context; every
    // later bind reads a plain int field instead of hitting Resources again.
    private int colorTextMuted, colorTextPrimary, colorTextSecondary,
                colorTickReadBlue, colorStatusTyping;
    private boolean colorsResolved = false;
    /** Unread accent for the time label — same green as ChatListUnreadBadgeView's pill (0xFF4CAF50). */
    private static final int COLOR_UNREAD_ACCENT = 0xFF4CAF50;

    private void ensureColorsResolved(Context ctx) {
        if (colorsResolved) return;
        colorTextMuted     = ctx.getResources().getColor(R.color.text_muted);
        colorTextPrimary   = ctx.getResources().getColor(R.color.text_primary);
        colorTextSecondary = ctx.getResources().getColor(R.color.text_secondary);
        colorTickReadBlue  = ctx.getResources().getColor(R.color.tick_read_blue);
        colorStatusTyping  = ctx.getResources().getColor(R.color.status_typing);
        colorsResolved = true;
    }

    // v83: constructor no longer takes a List<User> — caller uses submitList().
    public ChatListAdapter(SelectionListener listener) {
        this.selectionListener = listener;
        setHasStableIds(true);
    }

    // v85: resolve avatar decode size lazily from context (50dp avatar)
    // v93: package-visible so ChatsFragment's scroll-ahead avatar preloader
    // (see preloadUpcomingAvatars()) can request bytes with the EXACT same
    // override/format/transform signature this adapter uses — otherwise
    // Glide's disk-cache key wouldn't match and the preload would be wasted.
    private static int sAvatarSizePx = 0;
    static int getAvatarSizePx(Context ctx) {
        if (sAvatarSizePx == 0)
            sAvatarSizePx = Math.round(50f * ctx.getResources().getDisplayMetrics().density);
        return sAvatarSizePx;
    }

    /**
     * v90: Avatar decode format — API-level gate for Bitmap.Config.HARDWARE.
     *
     * On API 26+ (Android 8.0 Oreo):
     *   PREFER_ARGB_8888 → Glide automatically promotes the decoded+circleCropped
     *   bitmap to Bitmap.Config.HARDWARE after the transform. Hardware bitmaps live
     *   directly in GPU memory — compositing is a zero-copy GPU→GPU blit each frame
     *   instead of a CPU→GPU upload. This eliminates the last per-avatar GPU transfer
     *   cost on every draw pass.
     *
     * On API < 26:
     *   PREFER_RGB_565 — 2 bytes/pixel stays in RAM. No hardware bitmaps available.
     *
     * NOTE: Glide applies circleCrop() on a software bitmap first, THEN promotes
     * the result to HARDWARE. The transform pipeline is:
     *   [download] → [resize to override(px,px)] → [circleCrop on SW bitmap]
     *              → [promote to HARDWARE] → [cache HARDWARE bitmap in RESOURCE]
     * On subsequent cache hits the HARDWARE bitmap is served directly from
     * Glide's memory cache — zero decode + zero transform + zero GPU upload.
     */
    static final DecodeFormat AVATAR_FORMAT =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? DecodeFormat.PREFER_ARGB_8888   // → HARDWARE bitmap on API 26+
                    : DecodeFormat.PREFER_RGB_565;    // → software 16-bit on API < 26

    @Override
    public long getItemId(int position) {
        List<User> list = differ.getCurrentList();
        if (position < 0 || position >= list.size()) return RecyclerView.NO_ID;
        String uid = list.get(position).uid;
        return uid != null ? uid.hashCode() : position;
    }

    /**
     * PERF FIX (root cause of a big chunk of the reported scroll jank /
     * main-thread lag): this used to end with a blanket
     * {@code notifyItemRangeChanged(0, getItemCount())} — NO payload — which
     * forces every currently-attached row through the FULL
     * {@code onBindViewHolder(h, pos)} path: a fresh Glide request per
     * avatar, every click/long-click listener rebuilt, and (worst of all)
     * cancelPendingBindWork() + a brand-new 180ms postDelayed typing-
     * listener-attach/preload reschedule for EVERY visible row. This fires
     * on every single "specialRequests" Firebase update — completely
     * bypassing the partial-bind (payload) system the rest of this adapter
     * was built around — so if it landed mid-fling it was real, synchronous
     * main-thread work stacked on top of whatever the user was already
     * scrolling through.
     *
     * Fix: diff the old vs. new sender set and only touch rows whose
     * special-badge state actually FLIPPED, using the same lightweight
     * partial-bind path selection already uses (applySelectionVisuals only —
     * no avatar reload, no listener churn).
     */
    public void setSpecialRequestSenders(Set<String> set) {
        Set<String> newSet = set == null ? new HashSet<>() : set;
        Set<String> oldSet = this.specialRequestSenders;
        if (oldSet.equals(newSet)) return; // nothing actually changed — skip entirely
        this.specialRequestSenders = newSet;

        List<User> list = differ.getCurrentList();
        for (int i = 0; i < list.size(); i++) {
            String uid = list.get(i).uid;
            if (uid == null) continue;
            boolean wasSpecial = oldSet.contains(uid);
            boolean isSpecial  = newSet.contains(uid);
            if (wasSpecial != isSpecial) {
                notifyItemChanged(i, PAYLOAD_SPECIAL);
            }
        }
    }

    /**
     * Updates which chats are muted. Diffs old vs new and only re-binds rows whose
     * mute state actually flipped (lightweight applySelectionVisuals pass — no
     * avatar reload, no listener churn). Safe to call with the fragment's live set:
     * the adapter keeps its own copy.
     */
    public void setMutedUids(Set<String> set) {
        Set<String> newSet = set == null ? new HashSet<>() : new HashSet<>(set);
        Set<String> oldSet = this.mutedUids;
        if (oldSet.equals(newSet)) return;
        this.mutedUids = newSet;

        List<User> list = differ.getCurrentList();
        for (int i = 0; i < list.size(); i++) {
            String uid = list.get(i).uid;
            if (uid == null) continue;
            if (oldSet.contains(uid) != newSet.contains(uid)) {
                notifyItemChanged(i, PAYLOAD_MUTED);
            }
        }
    }

    public void setOnAvatarClickListener(OnAvatarClickListener listener) {
        this.avatarClickListener = listener;
    }

    @Deprecated
    public void setOnLongPressListener(OnLongPressListener listener) {
        this.longPressListener = listener;
    }

    // v95: optional background pre-inflation pool — see ChatRowPrewarmPool.
    // Set by ChatsFragment right after the adapter is created; onCreateViewHolder
    // polls it first and only pays a synchronous inflate if it's empty/unset.
    private ChatRowPrewarmPool prewarmPool;

    void setPrewarmPool(ChatRowPrewarmPool pool) {
        this.prewarmPool = pool;
    }

    // v242: item_chat.xml's root is layout_height="wrap_content" — but the
    // resolved wrap height NEVER varies (ChatRowContentView.onMeasure derives
    // its height purely from font-metric constants set once in its
    // constructor, not from the actual name/message text of any given row).
    // So the wrap_content walk (root FrameLayout asking its LinearLayout
    // child, which asks ITS children, to determine "how tall am I") produces
    // the exact same answer for all ~25 rows in the RecycledViewPool ceiling
    // — it's just being redundantly recomputed on every fresh VH instead of
    // once. Cache it after the first real measure, then force every
    // subsequent VH's root straight to that exact height (EXACTLY spec),
    // skipping the redundant wrap-content resolution walk entirely for every
    // VH after the first — which is also exactly the burst of VHs created
    // together when the Chats tab first opens (RecycledViewPool + prewarm
    // pool both front-load their inflation right there).
    private static volatile int sCachedRowHeightPx = -1;

    private void applyCachedRowHeight(View itemView, ViewGroup parent) {
        ViewGroup.LayoutParams lp = itemView.getLayoutParams();
        if (lp == null) lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        if (sCachedRowHeightPx > 0) {
            // Fast path: skip wrap_content resolution, go straight to EXACTLY.
            if (lp.height != sCachedRowHeightPx) {
                lp.height = sCachedRowHeightPx;
                itemView.setLayoutParams(lp);
            }
            return;
        }
        // Slow path (runs once, ever): let it wrap_content normally, then
        // read back and cache the result off this first real measure pass.
        int widthSpec = View.MeasureSpec.makeMeasureSpec(
                parent.getWidth() > 0 ? parent.getWidth() : getScreenWidthFallback(itemView),
                View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        itemView.measure(widthSpec, heightSpec);
        int measured = itemView.getMeasuredHeight();
        if (measured > 0) sCachedRowHeightPx = measured;
    }

    private int getScreenWidthFallback(View v) {
        return v.getResources().getDisplayMetrics().widthPixels;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = prewarmPool != null ? prewarmPool.poll() : null;
        if (v == null) {
            // Pool miss (cold start burst outrunning the background thread, or
            // prewarm disabled) — same synchronous inflate as before, so
            // correctness is never affected, only the fast-path cost.
            v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat, parent, false);
        }
        applyCachedRowHeight(v, parent);
        VH h = new VH(v, this);
        // v92: install every click/long-click listener ONCE per VH here,
        // instead of re-allocating them on every bind — see installStaticListeners().
        installStaticListeners(h);
        return h;
    }

    /**
     * v86 PARTIAL BIND — surgical canvas update instead of full row rebind.
     *
     * getChangePayload() returns an Integer bitmask of what actually changed.
     * Here we use those flags to update ONLY the canvas view(s) that need it:
     *
     *  • CHANGE_TIME only  → 1 drawText call in ChatListNameTimeView
     *  • CHANGE_LAST_MSG or CHANGE_UNREAD → applySelectionVisuals() updates
     *      badge + lastMsg + ticks + story ring  (3-4 views, not 6)
     *  • CHANGE_IDENTITY   → full bind (avatar reload can't be done safely
     *      without re-attaching click listeners — rare event, acceptable cost)
     *
     * Telegram's tick flip (sent→delivered→read on Firebase update) hits only
     * CHANGE_LAST_MSG → this method redraws ChatListLastMessageView only.
     * Name/time/avatar/badge do ZERO work.
     */
    @Override
    public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
        h.owner = this;   // v944
        if (payloads.isEmpty()) { onBindViewHolder(h, pos); return; }

        // Selection mode / special-badge flip are both a lightweight
        // applySelectionVisuals-only pass — no avatar reload, no listener
        // rebuild, no typing-listener reschedule.
        if (payloads.contains(PAYLOAD_SELECTION) || payloads.contains(PAYLOAD_SPECIAL)
                || payloads.contains(PAYLOAD_MUTED)) {
            applySelectionVisuals(h, differ.getCurrentList().get(pos));
            return;
        }

        // Accumulate bitmask (multiple payloads can arrive batched by DiffUtil)
        int flags = 0;
        for (Object p : payloads) {
            if (p instanceof Integer) flags |= (Integer) p;
        }
        if (flags == 0) { onBindViewHolder(h, pos); return; } // unknown payload — full bind

        // Identity change (name/photo) → needs avatar + listener re-bind → full bind
        if ((flags & CHANGE_IDENTITY) != 0) { onBindViewHolder(h, pos); return; }

        User u = differ.getCurrentList().get(pos);
        h.boundUser = u; // v92: keep listeners' view of "current row" fresh on partial binds

        if ((flags & CHANGE_TIME) != 0) {
            applyTime(h, u);
        }
        if ((flags & (CHANGE_LAST_MSG | CHANGE_UNREAD)) != 0) {
            // applySelectionVisuals: badge + lastMsg text + ticks + story ring
            // Skips name, time, avatar — exactly what we need for tick flips
            // and new-message unread increments
            applySelectionVisuals(h, u, false);
        }
    }

    // PERF MONITOR: real per-row bind timing, feeds the Chat List "Performance"
    // report (3-dot menu → Performance). Wrapping the whole method instead of
    // instrumenting inline avoids touching every early-return branch below.
    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        // v948: bind timing only in debuggable builds (release: no nanoTime x2 + stats add per bind).
        if (!sPerfTiming(h.itemView.getContext())) { onBindViewHolderTimed(h, pos); return; }
        long __t0 = com.callx.app.perf.PerformanceMonitor.get().beginBind();
        try {
            onBindViewHolderTimed(h, pos);
        } finally {
            com.callx.app.perf.PerformanceMonitor.get().endBind(__t0);
        }
    }

    private static int sPerfTimingState = -1;   // -1 unknown, 0 off, 1 on
    private static boolean sPerfTiming(Context ctx) {
        if (sPerfTimingState < 0) {
            sPerfTimingState = (ctx.getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 ? 1 : 0;
        }
        return sPerfTimingState == 1;
    }

    private void onBindViewHolderTimed(@NonNull VH h, int pos) {
        List<User> list = differ.getCurrentList();
        User u = list.get(pos);
        Context ctx = h.itemView.getContext();
        ensureColorsResolved(ctx);

        // v92 PERF FIX: click/long-click listeners used to be freshly
        // allocated (6-7 lambdas capturing u/ctx/hasStory) on EVERY full
        // bind — Ultra Diagnostics flagged high GC count/pause time and
        // traced it to exactly this pattern ("per-row allocations in
        // onBindViewHolder"). Listeners are now installed ONCE per VH (see
        // installStaticListeners(), called from onCreateViewHolder) and
        // simply read h.boundUser / h.hasStoryNow at click time — so a bind
        // is now a cheap field write instead of 6-7 allocations.
        h.boundUser = u;
        h.owner = this;   // v944

        h.nameTimeView.setName(u.name == null ? (u.isGroup ? "Group" : "User") : u.name);
        // Pin is now a canvas icon at the right of the message row (was a "📌 "
        // emoji prefix — ate name width, varied per device, and also made the
        // name ChatListTextPrecompute key miss for every pinned chat).
        ensureColorsResolved(h.itemView.getContext());
        h.nameTimeView.setPinned(u.localPinned, colorTextMuted);
        com.callx.app.utils.VerifiedBadgeUtils.bindForUid(h.nameTimeView, h.verifiedSetter,
                u.isGroup ? null : u.uid);

        applyTime(h, u);

        // v208 — PERF FIX: thumb-only policy for the chat list. Was
        // `thumbUrl != null ? thumbUrl : photoUrl` — if thumbUrl was ever
        // missing (older cached rows, sync gap) this silently fell back to
        // decoding the FULL-resolution profile photo just to show it at
        // 50dp. A full photo can be 10-50x the pixel data of the 100×100
        // WebP thumb — same decode+downsample+circleCrop cost paid for a
        // list row as for the full profile view, purely because the thumb
        // happened to be absent for that one row. WhatsApp's list never
        // touches the full-res image at all; a missing thumb is a
        // placeholder, not a promotion to full-res. Full photoUrl is loaded
        // ONLY where it's actually needed at full size — profile open,
        // avatar zoom (see showAvatarZoom below) — never in this row bind.
        //
        // FIX (deep avatar pipeline): the manual per-field Glide chain here
        // (raw flat-dp override, no tier bucketing, no CDN transform/format
        // param, no L2/L3 reuse) is now ChatAvatarBinder.bind() — same
        // AvatarSizeTier + density-bucketed WebP/AVIF CDN URL, L2-memory
        // fast path (survives TRIM_MEMORY_MODERATE), and L2/L3 write-through
        // on a real decode that AvatarPrefetcher/FollowAvatarBinder already
        // give reels and the follow lists. See ChatAvatarBinder class doc.
        // FIX (avatar delta-sync gap): u.avatarVersion is whatever this row's
        // Room/Firestore snapshot last carried -- it does NOT live-update while
        // the chat list sits on screen (no per-row AvatarVersionSyncManager.watch()
        // here, deliberately -- see resolveAvatarVersion() doc: N rows x a live
        // Firebase listener each is exactly what that manager exists to avoid).
        // Falling back through AvatarVersionSyncManager.getCachedVersion() means
        // that if the partner's avatar bump was ALREADY observed by any other
        // screen (their profile open, a reel/status of theirs), this row's next
        // bind picks up the fresh version -- same fallback StatusListAdapter's
        // resolveAvatarVersion() uses for its own carousel/list rows.
        // v947: the avatar is drawn by ChatRowAvatarView itself — bitmap straight from ChatAvatarBinder's
        // bitmap pipeline (same url / L1+L2 caches as the old Glide-into-CircleImageView path, no Glide Target,
        // no ImageView, no BitmapShader per row). The url string is memoised per VH (photo/version/group key),
        // so a rebind with unchanged inputs does no URL building and setAvatar() is a free no-op.
        final String avPhoto = u.isGroup ? u.photoUrl : u.thumbUrl;
        final long avVer = u.isGroup ? 0L : resolveAvatarVersion(ctx, u.uid, u.avatarVersion);
        if (!(h.avKeyValid && h.avGroup == u.isGroup && h.avVer == avVer && strEq(h.avPhoto, avPhoto))) {
            h.avUrl = (avPhoto == null || avPhoto.isEmpty()) ? null : ChatAvatarBinder.rowUrl(ctx, avPhoto, avVer);
            h.avPhoto = avPhoto; h.avVer = avVer; h.avGroup = u.isGroup; h.avKeyValid = true;
        }
        h.avatarView.setAvatar(ctx, h.avUrl, u.isGroup);
        // v85: pre-warm Glide decode for next contact — DEFERRED below along
        // with the typing listener (see BIND_SETTLE_DELAY_MS comment) instead
        // of firing synchronously on every bind. Building a second Glide
        // request on every single bind doubled Glide's main-thread request-
        // build cost per row; deferring it means a row that just flashes
        // past during a fast fling never pays this cost at all — only rows
        // the user actually settles on do.

        // v945: hasStoryNow is resolved once, by ringRefresh inside applySelectionVisuals() below
        // (this used to do the same two StatusCacheManager lookups here AND there).
        h.hasStoryNow = false;

        h.isTypingNow = false;
        applySelectionVisuals(h, u);

        // PERF FIX: attaching a live Firebase typing listener (and kicking
        // off preloadChatIfDue's delta sync) used to happen synchronously,
        // right here, on EVERY full bind. During a fast fling RecyclerView
        // binds+recycles rows continuously — each attach/detach of a real
        // ValueEventListener is genuine main-thread work, and it was firing
        // for rows the user's thumb blew straight past in under 100ms.
        // That churn was the main contributor to the reported scroll jank.
        //
        // Fix: defer both by BIND_SETTLE_DELAY_MS via the row's own
        // Handler (View#postDelayed, tied to the view's Looper — no extra
        // Handler object needed). onViewRecycled()/a fresh bind cancels
        // this via cancelPendingBindWork() if the row gets recycled again
        // before it fires — so a row that only flashes past during a fling
        // never pays either cost. A row the user actually stops on (list
        // idle, or just slow-scrolling past it) settles for 180ms and then
        // gets its typing listener + preload exactly as before.
        // WhatsApp-level fix: no per-bind Runnable/lambda allocation anymore.
        // h.pendingBindRunnable is created exactly ONCE per VH (see VH ctor)
        // and just reads h.boundUser / h.nextBoundUser at fire time, same as
        // installStaticListeners() does for click listeners. A bind now only
        // updates two plain fields and re-posts the SAME Runnable instance —
        // zero allocations on the hot scroll path, even during a fast fling.
        cancelPendingBindWork(h);
        h.nextBoundUser = (pos + 1 < list.size()) ? list.get(pos + 1) : null;
        // v948: an unattached view's postDelayed goes through HandlerActionQueue (allocates a HandlerAction per
        // call) — a not-yet-attached row is armed from onViewAttachedToWindow() instead.
        if (h.itemView.isAttachedToWindow()) {
            h.itemView.postDelayed(h.pendingBindRunnable, BIND_SETTLE_DELAY_MS);
        }

        // v92: call-buttons / avatar / item click+long-click listeners are
        // installed ONCE per VH — see installStaticListeners(), called from
        // onCreateViewHolder(). They read h.boundUser (set above) instead of
        // a per-bind captured `u`, so no lambdas are allocated here anymore.
    }

    /**
     * v92 PERF FIX: installs every click/long-click listener for a row ONCE,
     * when the VH is created, instead of re-allocating 6-7 lambdas on every
     * single bind (the previous behaviour). Each listener reads the row's
     * CURRENT state off the VH (h.boundUser, h.hasStoryNow) and current
     * adapter position (h.getAdapterPosition()) at click time, so it stays
     * correct across rebinds/recycling without needing to be reinstalled.
     *
     * This directly targets the "GC count/time traces back to per-row
     * allocations in onBindViewHolder" root cause the Ultra Diagnostics
     * screen flagged (42 ART GCs / 1798 ms total pause, 157 ms worst
     * main-thread lag) — a fast-scrolling list used to allocate 6-7 throwaway
     * lambda objects per row, per bind; a VH now allocates them exactly once
     * for its whole lifetime in the RecyclerView.
     */
    private void installStaticListeners(VH h) {
        // v944: listeners dispatch through h.owner (the adapter that most recently BOUND this VH), not
        // the adapter that happened to create it. VHs live in the Activity-scoped pool and are reused by
        // the next ChatsFragment instance's adapter, so capturing `this` here made selection mode,
        // avatar clicks and openChat act on a dead adapter (and pinned the old fragment in memory).
        // v947: ring + avatar are ONE view now, so one click / long-click pair (onStoryRingClick and
        // onAvatarTap resolved to the same actions anyway).
        h.metaView.setCallListeners(
                () -> h.owner.onCallClick(h, false),
                () -> h.owner.onCallClick(h, true));
        h.avatarView.setOnClickListener(v -> h.owner.onAvatarTap(h));
        h.avatarView.setOnLongClickListener(v -> h.owner.onAvatarLongPress(h));
        h.itemView.setOnClickListener(v -> h.owner.onRowClick(h));
        h.itemView.setOnLongClickListener(v -> h.owner.onRowLongClick(h));
    }

    private void onCallClick(VH h, boolean video) {
        User u = h.boundUser;
        if (u == null) return;
        if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
        Context ctx = h.itemView.getContext();
        Intent i = new Intent().setClassName(ctx.getPackageName(),
                "com.callx.app.call.CallActivity");
        i.putExtra("partnerUid", u.uid);
        i.putExtra("partnerName", u.name);
        i.putExtra("isCaller", true);
        i.putExtra("video", video);
        if (unwrapActivity(ctx) == null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }

    private void onAvatarTap(VH h) {
        User u = h.boundUser;
        if (u == null) return;
        Context ctx = h.itemView.getContext();
        if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
        // Story-first behavior: if user has active story/status, open it (Instagram style).
        // Only show contact bottom sheet when no story is available.
        if (u.isGroup) {
            openGroupChat(ctx, u);
        } else if (h.hasStoryNow) {
            openStatusOrChat(ctx, u);
        } else if (avatarClickListener != null) {
            avatarClickListener.onAvatarClick(u);
        } else {
            openChat(ctx, u);
        }
    }

    private boolean onAvatarLongPress(VH h) {
        User u = h.boundUser;
        if (u == null) return true;
        if (isSelecting) return true;
        showAvatarZoom(h.itemView.getContext(), h.avatarView, u.photoUrl, u.name);
        return true;
    }

    private void onRowClick(VH h) {
        User u = h.boundUser;
        if (u == null) return;
        if (isSelecting) toggleSelection(h.getAdapterPosition());
        else openChat(h.itemView.getContext(), u);
    }

    private boolean onRowLongClick(VH h) {
        User u = h.boundUser;
        if (u == null) return true;
        if (!isSelecting) {
            isSelecting = true;
            if (u.uid != null) selectedUids.add(u.uid);
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
            if (selectionListener != null) selectionListener.onSelectionStarted();
        } else {
            toggleSelection(h.getAdapterPosition());
        }
        return true;
    }

    /**
     * v948: per-VH memo of the timestamp the content view is already showing. ChatListTimeCache is an
     * LruCache<Long,String> (boxes a Long on every get) — an unchanged timestamp now skips it entirely.
     */
    private static void applyTime(VH h, User u) {
        final Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
        final long w = (when != null && when > 0) ? when : 0L;
        if (h.tmValid && h.tmWhen / 60_000L == w / 60_000L && (w == 0L) == (h.tmWhen == 0L)) return;
        h.nameTimeView.setTime(w > 0 ? ChatListTimeCache.getFormatted(w) : "");
        h.tmWhen = w; h.tmValid = true;
    }

    private static boolean strEq(String x, String y) { return x == null ? y == null : x.equals(y); }

    /**
     * v947: row tint (selected / special request). The row has NO static background any more; the tint is ONE
     * ColorDrawable per ViewHolder, attached only while a tint is active and recoloured in place — a bind with an
     * unchanged tint (every scroll bind) touches nothing, and a tint change never swaps drawables back and forth.
     */
    private static void applyRowBg(VH h, int rowBg) {
        if (h.rowBg == rowBg) return;
        if (rowBg == 0) {
            h.itemView.setBackground(null);
        } else {
            if (h.bgDrawable == null) h.bgDrawable = new android.graphics.drawable.ColorDrawable(rowBg);
            else h.bgDrawable.setColor(rowBg);
            if (h.itemView.getBackground() != h.bgDrawable) h.itemView.setBackground(h.bgDrawable);
        }
        h.rowBg = rowBg;
    }

    /** v945: preview string memo — previewFor() builds Strings (and "Sender: " concat for groups) per bind. */
    private static String previewMemo(VH h, User u) {
        if (h.pvValid && h.pvGroup == u.isGroup && h.pvMsg == u.lastMessage && h.pvType == u.lastMessageType
                && h.pvSender == u.lastSenderName && h.pvMembers == u.memberCount) {
            return h.pvText;
        }
        final String t = ChatListTextPrecompute.previewFor(u);
        h.pvGroup = u.isGroup; h.pvMsg = u.lastMessage; h.pvType = u.lastMessageType;
        h.pvSender = u.lastSenderName; h.pvMembers = u.memberCount; h.pvText = t; h.pvValid = true;
        return t;
    }

    private void applySelectionVisuals(VH h, User u) { applySelectionVisuals(h, u, true); }

    /** @param refreshRing false for message/unread partial binds: the story ring cannot have changed there
     *  (StoryRingRegistry repaints attached rings on any story/seen change), so skip the status-cache lookups. */
    private void applySelectionVisuals(VH h, User u, boolean refreshRing) {
        Context ctx = h.itemView.getContext();
        ensureColorsResolved(ctx);
        boolean selected  = u.uid != null && selectedUids.contains(u.uid);
        boolean isSpecial = u.uid != null && specialRequestSenders.contains(u.uid);

        // Mute: icon at the right of the message row + dimmed unread pill.
        // Lives here (not in the full-bind-only block) so the PAYLOAD_MUTED
        // partial bind and the full bind both pick it up.
        final boolean isMutedChat = u.isGroup ? u.localMuted
                : (u.uid != null && mutedUids.contains(u.uid));
        h.nameTimeView.setMuted(isMutedChat, colorTextMuted);
        h.metaView.setMutedStyle(isMutedChat);

        // v389: colors now read from the cached fields resolved once by
        // ensureColorsResolved() instead of hitting Resources on every bind
        // (was ctx.getResources().getColor(R.color.text_muted) etc. here).
        long unread = u.unread == null ? 0 : u.unread;
        int lastMsgColor;
        final boolean hasUnread = unread > 0 && !isSelecting;
        if (hasUnread) {
            h.metaView.setBadgeCount(unread);
            lastMsgColor = colorTextPrimary;
            h.nameTimeView.setNameColor(colorTextPrimary);
            // Unread: time picks up the badge's green (WhatsApp-style) …
            h.nameTimeView.setTimeColor(COLOR_UNREAD_ACCENT);
        } else {
            h.metaView.setBadgeCount(0);
            lastMsgColor = colorTextSecondary;
            h.nameTimeView.setNameColor(colorTextPrimary);
            h.nameTimeView.setTimeColor(colorTextMuted);
        }

        if (!h.isTypingNow) {
            if (isSpecial && !isSelecting) {
                h.lastMessageView.setMessageText("⭐ Special unblock request", 0xFFFF8F00, false);
            } else {
                String preview = previewMemo(h, u);
                // … and the preview turns bold (read rows stay regular).
                h.lastMessageView.setMessageText(preview, lastMsgColor, false, hasUnread);
            }
        }

        updateReadStatusTicks(h, u, isSelecting, isSpecial);

        if (u.isGroup) {
            // Groups never have a story ring. Re-register a plain "no ring"
            // callback so a recycled ring view that last belonged to a user row
            // can't get repainted by StoryRingRegistry with that old uid's state.
            // v943: the callback is one per-VH object registered once per mode change
            // (was: a fresh lambda + a registry put on EVERY bind / partial bind).
            h.hasStoryNow = false;
            h.avatarView.setRingState(ChatRowAvatarView.STATE_NONE);
            if (h.ringMode != VH.RING_GROUP) {
                StoryRingRegistry.register(ctx, h.avatarView, h.ringNoneRefresh);
                h.ringMode = VH.RING_GROUP;
            }
            h.ringUid = null;
        } else if (u.uid != null && (refreshRing || h.ringMode != VH.RING_USER || !u.uid.equals(h.ringUid))) {
            // Instagram-style instant propagation: ringRefresh is the row's own "repaint my ring"
            // logic. It runs now (initial bind) AND again, automatically, whenever ANY screen in the
            // app marks the uid's story seen — see StoryRingRegistry. It reads ringUid /
            // ringSelecting off the VH, so one instance serves every rebind of this row.
            h.ringUid = u.uid;
            h.ringSelecting = isSelecting;
            h.ringRefresh.run();
            if (h.ringMode != VH.RING_USER) {
                StoryRingRegistry.register(ctx, h.avatarView, h.ringRefresh);
                h.ringMode = VH.RING_USER;
            }
        }

        // v947: single reusable tint drawable, touched only when the tint actually changes.
        applyRowBg(h, selected ? 0x335B5BF6 : isSpecial ? 0x33FFC107 : 0x00000000);

        // v947: selection scrim + check circle are drawn by the avatar canvas view (was a 4-view ViewStub overlay).
        h.avatarView.setSelectionOverlay(isSelecting, selected);

        h.metaView.setCallsVisible(!(isSelecting || u.isGroup));
    }

    /**
     * v85: Pre-warm Glide decode for the next contact's avatar so it is already
     * in memory/disk-cache before that row scrolls into view.
     *
     * v91: Now called from the deferred BIND_SETTLE_DELAY_MS runnable (same
     * settle-delay pattern as attachTypingListener/preloadChatIfDue) instead
     * of synchronously on every bind — see the call site in
     * onBindViewHolderTimed(). Takes the next User directly (captured at
     * bind time) rather than (list, pos), since the adapter's list can be
     * re-diffed during the 180ms settle window and a stale position would
     * silently preload the wrong row.
     */
    /**
     * v208 — single source of truth for "which URL does the CHAT LIST show
     * for this contact's avatar". Thumb-only, by design (see call site doc
     * in onBindViewHolderTimed): never falls back to the full-res photoUrl,
     * a missing thumb means placeholder, not a full-res decode. Package-
     * visible + static so ChatsFragment's Room-load batch preloader (see
     * ChatsFragment#preloadAvatarsForPage) resolves the EXACT same URL this
     * adapter will bind — otherwise the preload would warm the wrong Glide
     * cache key and bind time would still pay for a fresh decode.
     */
    static String resolveListAvatarUrl(User u) {
        return (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : null;
    }

    /**
     * v85: Pre-warm Glide decode for the next contact's avatar so it is already
     * in memory/disk-cache before that row scrolls into view.
     *
     * v91: Now called from the deferred BIND_SETTLE_DELAY_MS runnable (same
     * settle-delay pattern as attachTypingListener/preloadChatIfDue) instead
     * of synchronously on every bind — see the call site in
     * onBindViewHolderTimed(). Takes the next User directly (captured at
     * bind time) rather than (list, pos), since the adapter's list can be
     * re-diffed during the 180ms settle window and a stale position would
     * silently preload the wrong row.
     *
     * v208: uses resolveListAvatarUrl() — thumb-only, same as the actual
     * bind — so this never wastefully preloads a full-res photo that
     * onBindViewHolderTimed() was never going to load anyway.
     *
     * FIX (deep avatar pipeline): now delegates to ChatAvatarBinder's
     * tiered/versioned URL + DiskCacheStrategy.DATA (bytes only, no
     * speculative decode) — same shape as ChatAvatarBinder.prefetch()'s
     * per-row loop, so this one-ahead warm and the velocity-based window
     * prefetch (see ChatsFragment's scroll listener) never produce
     * mismatched cache keys for the same row.
     */
    private void preloadAdjacentAvatar(Context ctx, User adj) {
        if (adj != null && adj.isGroup) return;
        if (adj == null) return;
        String url = ChatAvatarBinder.url(ctx, adj.thumbUrl, resolveAvatarVersion(ctx, adj.uid, adj.avatarVersion));
        if (url == null || url.isEmpty()) return;
        Glide.with(ctx)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .preload();
    }

    /**
     * FIX (avatar delta-sync wiring — AvatarVersionSyncManager was reaching
     * every other avatar surface — UserProfileActivity, StatusListAdapter,
     * ReelUiController — except the chat list): prefers whatever version
     * this row's own model carries (0 if the chat-list data layer hasn't
     * resolved one yet), falling back to AvatarVersionSyncManager's
     * refcounted, targeted "avatarVersion" watch cache for this uid. A
     * real bump observed by ANY screen (partner's profile opened, one of
     * their reels/status viewed) is picked up on this row's very next
     * bind — without the chat list needing to attach its own per-row live
     * Firebase listener (would be N outstanding listeners for a scrolling
     * list of chats, exactly what AvatarVersionSyncManager's targeted/
     * refcounted design exists to avoid). Same fallback shape as
     * StatusListAdapter#resolveAvatarVersion.
     */
    private static long resolveAvatarVersion(Context ctx, String uid, long modelVersion) {
        if (modelVersion > 0) return modelVersion;
        if (uid == null || uid.isEmpty()) return 0L;
        return AvatarVersionSyncManager.getInstance(ctx).getCachedVersion(uid);
    }

    /**
     * v88: Called by RecyclerView when a VH is returned to the RecycledViewPool.
     *
     * TWO THINGS DONE HERE:
     *
     * ① Glide.clear(ivAvatar)
     *    Without this, a Glide request that was still loading when the row scrolled
     *    off-screen can fire its completion callback AFTER the VH has been rebound
     *    to a different contact — causing the wrong avatar to flash in for a frame.
     *    Clearing here cancels the request before the VH re-enters the pool.
     *
     * ② detachTypingListener(h)
     *    Each VH holds a Firebase ValueEventListener for the typing indicator.
     *    If not detached on recycle, the listener continues firing against a VH
     *    that now shows a different contact — a subtle but real data-leak / UI-glitch.
     *    detachTypingListener() is idempotent and already called in onBindViewHolder,
     *    but calling it here guarantees zero listeners survive the pool round-trip.
     */
    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        // v947: stop an in-flight avatar load (the bitmap itself is kept: same-user rebind = free no-op).
        if (h.avatarView != null) h.avatarView.cancelLoad();
        // Row never settled long enough to attach in the first place —
        // drop the pending work instead of letting it fire against a VH
        // that's about to show a different contact.
        cancelPendingBindWork(h);
        detachTypingListener(h);
        h.isTypingNow = false;
        h.typingLabel = null;
        h.pvValid = false;                    // v945: drop memo strings so a pooled VH holds no row data
        // v92: drop the stale user ref so a click that races the recycle
        // (view detached but tap already in flight) becomes a safe no-op
        // instead of acting on the wrong contact.
        h.boundUser = null;
    }

    private void updateReadStatusTicks(VH h, User u, boolean isSelecting, boolean isSpecial) {
        boolean iAmLastSender = myUid != null && u.uid != null
                && myUid.equals(u.lastMessageSenderUid);
        if (h.isTypingNow || isSelecting || isSpecial || !iAmLastSender || u.lastMessageStatus == null) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_NONE, 0);
            return;
        }
        // v389: cached colors — see ensureColorsResolved(). No Context/
        // Resources lookup needed here anymore.
        if ("read".equals(u.lastMessageStatus)) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_READ, colorTickReadBlue);
        } else if ("delivered".equals(u.lastMessageStatus)) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_DELIVERED, colorTextMuted);
        } else {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_SENT, colorTextMuted);
        }
    }


    // ── ULTRA DIAGNOSTICS: Firebase typing-listener leak counters ────────────
    // attachTypingListener/detachTypingListener are the only two places a
    // "typing" ValueEventListener ever gets added/removed for this adapter.
    // sActiveTypingListeners is the live net count (attaches − detaches) —
    // it should always stay bounded near the visible/cached row count
    // (≈ setItemViewCacheSize + pool size). If it keeps climbing while
    // scrolling, that's a real Firebase listener leak, not a guess.
    // Static because RecycledViewPool/ViewModel can outlive any one adapter
    // instance (v87 activity-scoped pool) — the count must track the whole
    // app session, not just one ChatListAdapter object.
    private static final AtomicInteger sActiveTypingListeners = new AtomicInteger(0);
    private static final AtomicInteger sTotalTypingAttaches   = new AtomicInteger(0);
    private static final AtomicInteger sTotalTypingDetaches   = new AtomicInteger(0);

    public static int getActiveTypingListenerCount() { return sActiveTypingListeners.get(); }
    public static int getTotalTypingAttaches()        { return sTotalTypingAttaches.get(); }
    public static int getTotalTypingDetaches()        { return sTotalTypingDetaches.get(); }

    private void attachTypingListener(VH h, User u) {
        detachTypingListener(h);
        if (u == null) return;
        if (u.isGroup) { attachGroupTypingListener(h, u); return; }
        if (u.uid == null || myUid == null) return;
        DatabaseReference ref = FirebaseUtils.db().getReference("typing")
                .child(chatIdFor(u.uid)).child(u.uid);
        // v945: ONE listener object per VH (reads h.typingUser at fire time) instead of a new anonymous
        // listener per attach.
        if (h.typing1 == null) {
            h.typing1 = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    final User tu = h.typingUser;
                    if (tu != null) h.owner.onOneToOneTyping(h, tu, snap);
                }
                @Override public void onCancelled(@NonNull DatabaseError error) {}
            };
        }
        h.typingUser = u;
        ref.addValueEventListener(h.typing1);
        h.typingRef = ref;
        h.typingListener = h.typing1;
        sActiveTypingListeners.incrementAndGet();
        sTotalTypingAttaches.incrementAndGet();
    }

    private void onOneToOneTyping(VH h, User u, DataSnapshot snap) {
        int adapterPos = h.getAdapterPosition();
        if (adapterPos == RecyclerView.NO_POSITION) return;
        List<User> current = differ.getCurrentList();
        if (adapterPos >= current.size()) return;
        if (!u.uid.equals(current.get(adapterPos).uid)) return;
        applyTypingRow(h, current.get(adapterPos),
                Boolean.TRUE.equals(snap.getValue(Boolean.class)));
    }

    /** Group typing: groupTyping/{groupId}/{uid} = name, same node the old Groups tab watched. */
    private void attachGroupTypingListener(VH h, User u) {
        if (u.groupId == null) return;
        DatabaseReference ref = FirebaseUtils.getGroupTypingRef(u.groupId);
        if (h.typingGroup == null) {
            h.typingGroup = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    final User tu = h.typingUser;
                    if (tu != null) h.owner.onGroupTyping(h, tu, snap);
                }
                @Override public void onCancelled(@NonNull DatabaseError error) {}
            };
        }
        h.typingUser = u;
        ref.addValueEventListener(h.typingGroup);
        h.typingRef = ref;
        h.typingListener = h.typingGroup;
        sActiveTypingListeners.incrementAndGet();
        sTotalTypingAttaches.incrementAndGet();
    }

    private void onGroupTyping(VH h, User u, DataSnapshot snap) {
        int adapterPos = h.getAdapterPosition();
        if (adapterPos == RecyclerView.NO_POSITION) return;
        List<User> current = differ.getCurrentList();
        if (adapterPos >= current.size()) return;
        User cur = current.get(adapterPos);
        if (!u.uid.equals(cur.uid)) return;
        int count = 0;
        String first = null;
        for (DataSnapshot child : snap.getChildren()) {
            if (myUid != null && myUid.equals(child.getKey())) continue;
            if (first == null) first = child.getValue(String.class);
            count++;
        }
        if (count > 0) {
            h.typingLabel = (first == null || first.isEmpty() ? "Someone" : first)
                    + (count > 1 ? " +" + (count - 1) : "") + " typing...";
            applyTypingRow(h, cur, true);
        } else {
            h.typingLabel = null;
            applyTypingRow(h, cur, false);
        }
    }

    // v945: chatId = f(myUid, uid) is a compareTo + 2 concats; the same uid hits it on every bind,
    // typing attach and preload. Main-thread only (bind / posted runnables).
    private final java.util.HashMap<String, String> chatIdByUid = new java.util.HashMap<>();
    private String chatIdFor(String uid) {
        String id = chatIdByUid.get(uid);
        if (id == null) {
            id = FirebaseUtils.getChatId(myUid, uid);
            if (chatIdByUid.size() > 512) chatIdByUid.clear();
            chatIdByUid.put(uid, id);
        }
        return id;
    }

    private void detachTypingListener(VH h) {
        if (h.typingRef != null && h.typingListener != null) {
            h.typingRef.removeEventListener(h.typingListener);
            sActiveTypingListeners.decrementAndGet();
            sTotalTypingDetaches.incrementAndGet();
        }
        h.typingRef = null;
        h.typingListener = null;
        h.typingUser = null;
    }

    private void applyTypingRow(VH h, User u, boolean isTyping) {
        h.isTypingNow = isTyping;
        if (isTyping) {
            // v389: cached color — see ensureColorsResolved(). This path is
            // reached from a live Firebase typing listener callback, not
            // onBindViewHolder, so resolve as a safety net in case no bind
            // has happened yet for this adapter instance (colorsResolved
            // guard makes this a no-op in the normal case).
            ensureColorsResolved(h.itemView.getContext());
            h.lastMessageView.setMessageText(
                    h.typingLabel != null ? h.typingLabel : "typing...", colorStatusTyping, true);
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_NONE, 0);
        } else {
            applySelectionVisuals(h, u);
        }
    }


    private static final long OPEN_CHAT_SAFETY_CAP_MS = 150L;

    /** Unwraps a Context to find the underlying Activity, if any (a Context
     *  passed here can be wrapped, e.g. ContextThemeWrapper). Returns null
     *  if this Context isn't (and doesn't wrap) an Activity — e.g. it's an
     *  Application or Service context. */
    private static android.app.Activity unwrapActivity(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof android.app.Activity) return (android.app.Activity) ctx;
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return (ctx instanceof android.app.Activity) ? (android.app.Activity) ctx : null;
    }

    private void openGroupChat(Context ctx, User u) {
        if (u.groupId == null) return;
        Intent i = new Intent(ctx, com.callx.app.group.GroupChatActivity.class);
        i.putExtra("groupId", u.groupId);
        i.putExtra("groupName", u.name);
        if (unwrapActivity(ctx) == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private void openChat(Context ctx, User u) {
        if (u.isGroup) { openGroupChat(ctx, u); return; }
        String chatId = (myUid != null && u.uid != null)
                ? FirebaseUtils.getChatId(myUid, u.uid) : null;
        Runnable navigate = () -> {
            // v8: hand off the docked mini reel player (if one is showing)
            // so it survives the hop into ChatActivity instead of just
            // disappearing behind it — ChatActivity picks it back up in
            // its own onResume().
            DockedOverlayRegistry.detachIfShowing();

            Intent i = new Intent(ctx, ChatActivity.class);
            i.putExtra("partnerUid",   u.uid);
            i.putExtra("partnerName",  u.name);
            i.putExtra("partnerPhoto", u.photoUrl != null ? u.photoUrl : "");
            i.putExtra("partnerThumb", u.thumbUrl != null ? u.thumbUrl : "");
            // Navigation here runs from an async callback (Room read
            // completion or the safety timer below), so by the time it
            // fires `ctx` isn't guaranteed to be an Activity context in
            // every caller of this adapter. startActivity() on a non-
            // Activity context requires FLAG_ACTIVITY_NEW_TASK or it
            // throws — add it whenever ctx doesn't resolve to an Activity.
            if (unwrapActivity(ctx) == null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            // Simplified to match GroupChatActivity's plain open (no custom
            // ActivityOptions anim) — was stacking with ChatActivity's own
            // Window.setEnterTransition(Slide) + postponeEnterTransition,
            // which made 1:1 open feel heavier than group's single default
            // transition. ChatActivity's own Slide transition still applies.
            ctx.startActivity(i);
        };
        if (chatId == null) { navigate.run(); return; }

        final boolean[] navigated = {false};
        android.os.Handler h2 = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable safety = () -> { if (!navigated[0]) { navigated[0] = true; navigate.run(); } };
        h2.postDelayed(safety, OPEN_CHAT_SAFETY_CAP_MS);
        ChatRepository.getInstance(ctx.getApplicationContext()).primeChatFromRoom(chatId, () -> {
            h2.removeCallbacks(safety);
            if (!navigated[0]) { navigated[0] = true; navigate.run(); }
        });
    }

    private void openStatusOrChat(Context ctx, User u) {
        if (u.isGroup || u.uid == null) { openChat(ctx, u); return; }
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        if (scm.hasUnseen(u.uid) || scm.hasStatus(u.uid)) {
            Intent si = new Intent().setClassName(ctx.getPackageName(),
                    "com.callx.app.viewer.StatusViewerActivity");
            si.putExtra("ownerUid",  u.uid);
            si.putExtra("ownerName", u.name != null ? u.name : "");
            if (unwrapActivity(ctx) == null) {
                si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(si);
        } else {
            openChat(ctx, u);
        }
    }

    private void toggleSelection(int pos) {
        List<User> list = differ.getCurrentList();
        if (pos < 0 || pos >= list.size()) return;
        User u = list.get(pos);
        if (u.uid == null) return;
        if (selectedUids.contains(u.uid)) selectedUids.remove(u.uid);
        else selectedUids.add(u.uid);

        if (selectedUids.isEmpty()) {
            isSelecting = false;
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
            if (selectionListener != null) selectionListener.onSelectionCleared();
        } else {
            notifyItemChanged(pos, PAYLOAD_SELECTION);
            if (selectionListener != null) selectionListener.onSelectionChanged();
        }
    }

    public void selectAll() {
        isSelecting = true;
        for (User u : differ.getCurrentList()) if (u.uid != null) selectedUids.add(u.uid);
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (selectionListener != null) selectionListener.onSelectionChanged();
    }

    public void clearSelection() {
        isSelecting = false;
        selectedUids.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (selectionListener != null) selectionListener.onSelectionCleared();
    }

    public boolean isSelecting() { return isSelecting; }
    public int getSelectedCount() { return selectedUids.size(); }

    public List<User> getSelectedItems() {
        List<User> sel = new ArrayList<>();
        for (User u : differ.getCurrentList())
            if (u.uid != null && selectedUids.contains(u.uid)) sel.add(u);
        return sel;
    }

    private void showAvatarZoom(Context ctx, android.view.View sourceView, String photoUrl, String name) {
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
                ctx, sourceView, photoUrl, name, R.drawable.ic_person, R.drawable.ic_close);
    }

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        // v90: nameTimeView + lastMessageView now both reference the SAME
        // merged ChatRowContentView instance (one view, one measure/layout/
        // draw pass) — kept as two field names purely so every existing
        // h.nameTimeView.xxx / h.lastMessageView.xxx call site below still
        // compiles unchanged; ChatRowContentView implements both APIs.
        ChatRowContentView nameTimeView;
        ChatRowContentView lastMessageView;
        // v947: avatar + story ring + selection overlay = ONE canvas view; unread badge + call buttons = ONE canvas view.
        ChatRowAvatarView avatarView;
        ChatRowMetaView   metaView;
        // typing
        DatabaseReference  typingRef;
        ValueEventListener typingListener;
        boolean isTypingNow = false;
        // PERF: deferred typing-attach/preload runnable — see
        // cancelPendingBindWork() / BIND_SETTLE_DELAY_MS below. Allocated
        // ONCE in the constructor (see below) instead of a new lambda per
        // bind — this is the field that used to hold a freshly-built
        // Runnable on every single onBindViewHolder() call.
        Runnable pendingBindRunnable;
        // v92: current row state, read by listeners installed ONCE in
        // installStaticListeners() instead of being re-captured every bind.
        User boundUser;
        String typingLabel; // group rows: "Name +2 typing..."
        boolean hasStoryNow = false;
        // WhatsApp-level fix: the row after this one, read by
        // pendingBindRunnable at fire time instead of being captured fresh
        // (as a final local) on every bind.
        User nextBoundUser;
        /** v944: adapter that last bound this VH; every listener / deferred runnable goes through it. */
        ChatListAdapter owner;

        // v943: per-VH reusable objects / state — nothing here is allocated per bind any more.
        static final int RING_NONE = 0, RING_USER = 1, RING_GROUP = 2;
        com.callx.app.utils.VerifiedBadgeUtils.BadgeSetter verifiedSetter;
        Runnable ringRefresh;
        Runnable ringNoneRefresh;
        String ringUid;
        boolean ringSelecting;
        int ringMode = RING_NONE;
        int rowBg = 0;                                   // 0 = no tint attached (matches the XML: no background)
        android.graphics.drawable.ColorDrawable bgDrawable;
        // v947: avatar url memo (photo/version/group -> row url) so rebinding an unchanged row builds no strings
        boolean avKeyValid, avGroup;
        long avVer;
        String avPhoto, avUrl;
        // v945: preview memo + reusable typing listeners
        boolean tmValid;                                 // v948: time memo (see applyTime)
        long tmWhen;
        boolean pvValid, pvGroup;
        String pvMsg, pvType, pvSender, pvText;
        int pvMembers;
        User typingUser;
        ValueEventListener typing1, typingGroup;

        VH(View v, ChatListAdapter adapter) {
            super(v);
            owner = adapter;
            ChatRowContentView rowContent = v.findViewById(R.id.view_row_content);
            nameTimeView    = rowContent;
            lastMessageView = rowContent;
            avatarView      = v.findViewById(R.id.view_avatar);
            metaView        = v.findViewById(R.id.view_meta);

            verifiedSetter = rowContent::setVerified;
            ringNoneRefresh = () -> {
                hasStoryNow = false;
                avatarView.setRingState(ChatRowAvatarView.STATE_NONE);
            };
            ringRefresh = () -> {
                final String uid = ringUid;
                if (uid == null) { avatarView.setRingState(ChatRowAvatarView.STATE_NONE); return; }
                StatusCacheManager scm = StatusCacheManager.getInstance(itemView.getContext());
                boolean unseen = scm.hasUnseen(uid);
                hasStoryNow = unseen || scm.hasStatus(uid);
                if (!ringSelecting && unseen) {
                    avatarView.setRingState(ChatRowAvatarView.STATE_UNSEEN);
                } else if (!ringSelecting && hasStoryNow) {
                    avatarView.setRingState(ChatRowAvatarView.STATE_SEEN);
                } else {
                    avatarView.setRingState(ChatRowAvatarView.STATE_NONE);
                }
            };

            // WhatsApp-level fix: built exactly once per VH (its whole
            // RecyclerView lifetime), not once per bind. Reads boundUser /
            // nextBoundUser off `this` at fire time, so it stays correct
            // across rebinds/recycling without ever being reallocated.
            pendingBindRunnable = () -> {
                Context ctx = itemView.getContext();
                final ChatListAdapter a = owner;
                a.attachTypingListener(this, boundUser);
                a.preloadChatIfDue(ctx, boundUser);
                a.preloadAdjacentAvatar(ctx, nextBoundUser);
            };
        }
    }
}
