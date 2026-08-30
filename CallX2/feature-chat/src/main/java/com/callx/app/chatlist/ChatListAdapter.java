package com.callx.app.chatlist;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
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

import com.callx.app.chatlist.canvas.ChatListCallButtonsView;
import com.callx.app.chatlist.canvas.ChatListLastMessageView;
import com.callx.app.chatlist.canvas.ChatListStoryRingView;
import com.callx.app.chatlist.canvas.ChatListUnreadBadgeView;
import com.callx.app.chatlist.canvas.ChatRowContentView;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.docked.DockedOverlayRegistry;
import com.callx.app.models.User;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.cache.StatusCacheManager;
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
                && longEq(a.unread, b.unread);
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
                    || !safeEq(a.thumbUrl, b.thumbUrl))          flags |= CHANGE_IDENTITY;
            if (!safeEq(a.lastMessage, b.lastMessage)
                    || !safeEq(a.lastMessageType, b.lastMessageType)
                    || !safeEq(a.lastMessageStatus, b.lastMessageStatus)
                    || !safeEq(a.lastMessageSenderUid, b.lastMessageSenderUid)) flags |= CHANGE_LAST_MSG;
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
        List<User> safe = newList == null ? Collections.emptyList() : newList;
        differ.submitList(safe);
        // Precompute runs on a background thread — main thread returns immediately.
        // By the time the diff result dispatches onBindViewHolder, most entries
        // will already be cached (precompute completes in ~10–50 ms for 100 rows).
        if (mRes != null) {
            ChatListTextPrecompute.precompute(safe, myUid, mRes, isSelecting);
        }
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
        if (h.pendingBindRunnable != null) {
            h.itemView.removeCallbacks(h.pendingBindRunnable);
            h.pendingBindRunnable = null;
        }
    }

    private void preloadChatIfDue(Context ctx, User u) {
        if (u == null || u.uid == null || myUid == null) return;
        String chatId = FirebaseUtils.getChatId(myUid, u.uid);
        long now = System.currentTimeMillis();
        Long last = sLastPreloadAt.get(chatId);
        if (last != null && (now - last) < PRELOAD_COOLDOWN_MS) return;
        sLastPreloadAt.put(chatId, now);
        ChatRepository repo = ChatRepository.getInstance(ctx.getApplicationContext());
        repo.warmLastMessagesCache(chatId);
        repo.syncMessagesDelta(chatId);
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
        VH h = new VH(v);
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
        if (payloads.isEmpty()) { onBindViewHolder(h, pos); return; }

        // Selection mode / special-badge flip are both a lightweight
        // applySelectionVisuals-only pass — no avatar reload, no listener
        // rebuild, no typing-listener reschedule.
        if (payloads.contains(PAYLOAD_SELECTION) || payloads.contains(PAYLOAD_SPECIAL)) {
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
            Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
            h.nameTimeView.setTime((when != null && when > 0)
                    ? ChatListTimeCache.getFormatted(when) : "");
        }
        if ((flags & (CHANGE_LAST_MSG | CHANGE_UNREAD)) != 0) {
            // applySelectionVisuals: badge + lastMsg text + ticks + story ring
            // Skips name, time, avatar — exactly what we need for tick flips
            // and new-message unread increments
            applySelectionVisuals(h, u);
        }
    }

    // PERF MONITOR: real per-row bind timing, feeds the Chat List "Performance"
    // report (3-dot menu → Performance). Wrapping the whole method instead of
    // instrumenting inline avoids touching every early-return branch below.
    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        long __t0 = com.callx.app.perf.PerformanceMonitor.get().beginBind();
        try {
            onBindViewHolderTimed(h, pos);
        } finally {
            com.callx.app.perf.PerformanceMonitor.get().endBind(__t0);
        }
    }

    private void onBindViewHolderTimed(@NonNull VH h, int pos) {
        List<User> list = differ.getCurrentList();
        User u = list.get(pos);
        Context ctx = h.itemView.getContext();

        // v92 PERF FIX: click/long-click listeners used to be freshly
        // allocated (6-7 lambdas capturing u/ctx/hasStory) on EVERY full
        // bind — Ultra Diagnostics flagged high GC count/pause time and
        // traced it to exactly this pattern ("per-row allocations in
        // onBindViewHolder"). Listeners are now installed ONCE per VH (see
        // installStaticListeners(), called from onCreateViewHolder) and
        // simply read h.boundUser / h.hasStoryNow at click time — so a bind
        // is now a cheap field write instead of 6-7 allocations.
        h.boundUser = u;

        h.nameTimeView.setName(u.name == null ? "User" : u.name);

        Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
        // v85: ChatListTimeCache — LruCache keyed by minute, avoids SimpleDateFormat per bind
        h.nameTimeView.setTime((when != null && when > 0)
                ? ChatListTimeCache.getFormatted(when) : "");

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
        ChatAvatarBinder.bind(ctx, h.ivAvatar, u.thumbUrl, u.avatarVersion, R.drawable.ic_person);
        // v85: pre-warm Glide decode for next contact — DEFERRED below along
        // with the typing listener (see BIND_SETTLE_DELAY_MS comment) instead
        // of firing synchronously on every bind. Building a second Glide
        // request on every single bind doubled Glide's main-thread request-
        // build cost per row; deferring it means a row that just flashes
        // past during a fast fling never pays this cost at all — only rows
        // the user actually settles on do.

        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        boolean hasStory = u.uid != null && (scm.hasUnseen(u.uid) || scm.hasStatus(u.uid));
        h.hasStoryNow = hasStory; // read by the story-ring listener installed once in onCreateViewHolder

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
        cancelPendingBindWork(h);
        final User boundUser = u;
        final User nextUser = (pos + 1 < list.size()) ? list.get(pos + 1) : null;
        h.pendingBindRunnable = () -> {
            attachTypingListener(h, boundUser);
            preloadChatIfDue(ctx, boundUser);
            preloadAdjacentAvatar(ctx, nextUser);
        };
        h.itemView.postDelayed(h.pendingBindRunnable, BIND_SETTLE_DELAY_MS);

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
        if (h.storyRingView != null) {
            h.storyRingView.setOnClickListener(v -> {
                User u = h.boundUser;
                if (u == null || u.uid == null) return;
                Context ctx = h.itemView.getContext();
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                // Story ring click always opens story/status if available, else bottom sheet
                if (h.hasStoryNow) {
                    openStatusOrChat(ctx, u);
                } else if (avatarClickListener != null) {
                    avatarClickListener.onAvatarClick(u);
                } else {
                    openChat(ctx, u);
                }
            });
        }

        if (h.callButtonsView != null) {
            h.callButtonsView.setListeners(
                () -> {
                    User u = h.boundUser;
                    if (u == null) return;
                    if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                    Context ctx = h.itemView.getContext();
                    Intent i = new Intent().setClassName(ctx.getPackageName(),
                            "com.callx.app.call.CallActivity");
                    i.putExtra("partnerUid", u.uid);
                    i.putExtra("partnerName", u.name);
                    i.putExtra("isCaller", true);
                    i.putExtra("video", false);
                    if (unwrapActivity(ctx) == null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    ctx.startActivity(i);
                },
                () -> {
                    User u = h.boundUser;
                    if (u == null) return;
                    if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                    Context ctx = h.itemView.getContext();
                    Intent i = new Intent().setClassName(ctx.getPackageName(),
                            "com.callx.app.call.CallActivity");
                    i.putExtra("partnerUid", u.uid);
                    i.putExtra("partnerName", u.name);
                    i.putExtra("isCaller", true);
                    i.putExtra("video", true);
                    if (unwrapActivity(ctx) == null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    ctx.startActivity(i);
                }
            );
        }

        h.ivAvatar.setOnClickListener(v -> {
            User u = h.boundUser;
            if (u == null) return;
            Context ctx = h.itemView.getContext();
            if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
            // Story-first behavior: if user has active story/status, open it (Instagram style).
            // Only show contact bottom sheet when no story is available.
            if (h.hasStoryNow) {
                openStatusOrChat(ctx, u);
            } else if (avatarClickListener != null) {
                avatarClickListener.onAvatarClick(u);
            } else {
                openChat(ctx, u);
            }
        });

        h.ivAvatar.setOnLongClickListener(v -> {
            User u = h.boundUser;
            if (u == null) return true;
            if (isSelecting) return true;
            showAvatarZoom(h.itemView.getContext(), h.ivAvatar, u.photoUrl, u.name);
            return true;
        });

        h.itemView.setOnClickListener(v -> {
            User u = h.boundUser;
            if (u == null) return;
            if (isSelecting) toggleSelection(h.getAdapterPosition());
            else openChat(h.itemView.getContext(), u);
        });

        h.itemView.setOnLongClickListener(v -> {
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
        });
    }

    private void applySelectionVisuals(VH h, User u) {
        Context ctx = h.itemView.getContext();
        boolean selected  = u.uid != null && selectedUids.contains(u.uid);
        boolean isSpecial = u.uid != null && specialRequestSenders.contains(u.uid);

        // FIX: name/time were hardcoded to a near-black literal (0xFF0F172A)
        // regardless of theme — invisible against the dark-mode row
        // background (@color/surface_card resolves to near-black at night).
        // Both now resolve from theme-aware resources every bind, same as
        // lastMsgColor below, so the row is readable in both light and dark.
        h.nameTimeView.setTimeColor(ctx.getResources().getColor(R.color.text_muted));

        long unread = u.unread == null ? 0 : u.unread;
        int lastMsgColor;
        if (unread > 0 && !isSelecting) {
            h.unreadBadgeView.setBadgeCount(unread);
            lastMsgColor = ctx.getResources().getColor(R.color.text_primary);
            h.nameTimeView.setNameColor(ctx.getResources().getColor(R.color.text_primary));
        } else {
            h.unreadBadgeView.setBadgeCount(0);
            lastMsgColor = ctx.getResources().getColor(R.color.text_secondary);
            h.nameTimeView.setNameColor(ctx.getResources().getColor(R.color.text_primary));
        }

        if (!h.isTypingNow) {
            if (isSpecial && !isSelecting) {
                h.lastMessageView.setMessageText("⭐ Special unblock request", 0xFFFF8F00, false);
            } else {
                String preview = ChatListPreviewUtil.buildPreview(
                        u.lastMessageType, u.lastMessage, "Tap karke chat karo");
                h.lastMessageView.setMessageText(preview, lastMsgColor, false);
            }
        }

        updateReadStatusTicks(h, u, isSelecting, isSpecial);

        if (h.storyRingView != null && u.uid != null) {
            StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
            if (!isSelecting && scm.hasUnseen(u.uid)) {
                h.storyRingView.setState(ChatListStoryRingView.STATE_UNSEEN);
            } else if (!isSelecting && scm.hasStatus(u.uid)) {
                h.storyRingView.setState(ChatListStoryRingView.STATE_SEEN);
            } else {
                h.storyRingView.setState(ChatListStoryRingView.STATE_NONE);
            }
        }

        h.itemView.setBackgroundColor(
                selected  ? 0x335B5BF6 :
                isSpecial ? 0x33FFC107 : 0x00000000);

        if (h.flSelectOverlay != null) {
            if (isSelecting) {
                h.flSelectOverlay.setVisibility(View.VISIBLE);
                if (h.vCheckRing != null) h.vCheckRing.setVisibility(selected ? View.VISIBLE : View.GONE);
                if (h.ivCheck != null)    h.ivCheck.setVisibility(View.INVISIBLE);
            } else {
                h.flSelectOverlay.setVisibility(View.GONE);
            }
        }

        if (h.callButtonsView != null) {
            h.callButtonsView.setVisibility(isSelecting ? View.GONE : View.VISIBLE);
        }
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
        if (adj == null) return;
        String url = ChatAvatarBinder.url(ctx, adj.thumbUrl, adj.avatarVersion);
        if (url == null || url.isEmpty()) return;
        Glide.with(ctx)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .preload();
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
        if (h.ivAvatar != null) {
            // FIX (deep avatar pipeline): ChatAvatarBinder.cancel() —
            // same Glide.clear() this already did, now the shared choke
            // point every avatar list in the app uses (see
            // FollowAvatarBinder#cancel for the reels equivalent).
            ChatAvatarBinder.cancel(h.ivAvatar.getContext(), h.ivAvatar);
        }
        // Row never settled long enough to attach in the first place —
        // drop the pending work instead of letting it fire against a VH
        // that's about to show a different contact.
        cancelPendingBindWork(h);
        detachTypingListener(h);
        h.isTypingNow = false;
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
        Context ctx = h.itemView.getContext();
        if ("read".equals(u.lastMessageStatus)) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_READ,
                    ctx.getResources().getColor(R.color.tick_read_blue));
        } else if ("delivered".equals(u.lastMessageStatus)) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_DELIVERED,
                    ctx.getResources().getColor(R.color.text_muted));
        } else {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_SENT,
                    ctx.getResources().getColor(R.color.text_muted));
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
        if (u.uid == null || myUid == null) return;
        String chatId = FirebaseUtils.getChatId(myUid, u.uid);
        DatabaseReference ref = FirebaseUtils.db().getReference("typing")
                .child(chatId).child(u.uid);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                int adapterPos = h.getAdapterPosition();
                if (adapterPos == RecyclerView.NO_POSITION) return;
                List<User> current = differ.getCurrentList();
                if (adapterPos >= current.size()) return;
                if (!u.uid.equals(current.get(adapterPos).uid)) return;
                applyTypingRow(h, current.get(adapterPos),
                        Boolean.TRUE.equals(snap.getValue(Boolean.class)));
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        ref.addValueEventListener(listener);
        h.typingRef = ref;
        h.typingListener = listener;
        sActiveTypingListeners.incrementAndGet();
        sTotalTypingAttaches.incrementAndGet();
    }

    private void detachTypingListener(VH h) {
        if (h.typingRef != null && h.typingListener != null) {
            h.typingRef.removeEventListener(h.typingListener);
            sActiveTypingListeners.decrementAndGet();
            sTotalTypingDetaches.incrementAndGet();
        }
        h.typingRef = null;
        h.typingListener = null;
    }

    private void applyTypingRow(VH h, User u, boolean isTyping) {
        h.isTypingNow = isTyping;
        if (isTyping) {
            // FIX: was a hardcoded dark-green literal (0xFF0F4C3A) — low
            // contrast against the dark-mode row background. status_typing
            // (#4CAF50, bright green) is already defined identically in both
            // values/ and values-night/colors.xml, so it reads clearly either way.
            Context ctx = h.itemView.getContext();
            h.lastMessageView.setMessageText("typing...",
                    ctx.getResources().getColor(R.color.status_typing), true);
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

    private void openChat(Context ctx, User u) {
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
            // WhatsApp-style smooth push: chat screen slides in from the
            // right while the list parallaxes back underneath, instead of
            // the previous instant/no-anim swap.
            androidx.core.app.ActivityOptionsCompat opts =
                androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(
                    ctx, R.anim.chat_slide_in_right, R.anim.chat_slide_out_left);
            ctx.startActivity(i, opts.toBundle());
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
        if (u.uid == null) { openChat(ctx, u); return; }
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
        ChatListUnreadBadgeView unreadBadgeView;
        ChatListStoryRingView   storyRingView;
        ChatListCallButtonsView callButtonsView;
        // unchanged
        CircleImageView ivAvatar;
        android.widget.ImageView ivCheck;
        View flSelectOverlay, vCheckRing;
        // typing
        DatabaseReference  typingRef;
        ValueEventListener typingListener;
        boolean isTypingNow = false;
        // PERF: deferred typing-attach/preload runnable — see
        // cancelPendingBindWork() / BIND_SETTLE_DELAY_MS below.
        Runnable pendingBindRunnable;
        // v92: current row state, read by listeners installed ONCE in
        // installStaticListeners() instead of being re-captured every bind.
        User boundUser;
        boolean hasStoryNow = false;

        VH(View v) {
            super(v);
            ChatRowContentView rowContent = v.findViewById(R.id.view_row_content);
            nameTimeView    = rowContent;
            lastMessageView = rowContent;
            unreadBadgeView = v.findViewById(R.id.view_unread_badge);
            storyRingView   = v.findViewById(R.id.view_story_ring);
            callButtonsView = v.findViewById(R.id.view_call_buttons);
            ivAvatar        = v.findViewById(R.id.iv_avatar);
            flSelectOverlay = v.findViewById(R.id.fl_select_overlay);
            ivCheck         = v.findViewById(R.id.iv_check);
            vCheckRing      = v.findViewById(R.id.v_check_ring);
        }
    }
}
