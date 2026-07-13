package com.callx.app.chatlist;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import com.callx.app.chatlist.canvas.ChatListCallButtonsView;
import com.callx.app.chatlist.canvas.ChatListLastMessageView;
import com.callx.app.chatlist.canvas.ChatListStoryRingView;
import com.callx.app.chatlist.canvas.ChatListUnreadBadgeView;
import com.callx.app.chatlist.canvas.ChatRowContentView;
import com.callx.app.conversation.ChatActivity;
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
    private final AsyncListDiffer<User> differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);

    // v89: estimated field widths — set once in onAttachedToRecyclerView
    private int mEstimatedNameWidth = 0;
    private int mEstimatedMsgWidth  = 0;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        // Initialise TextPaint clones and width estimates for the precompute cache.
        // Called once when the adapter is first attached; safe to call multiple times.
        android.content.res.Resources res = recyclerView.getContext().getResources();
        ChatListTextPrecompute.init(res);
        mEstimatedNameWidth = ChatListTextPrecompute.estimateNameWidth(res);
        mEstimatedMsgWidth  = ChatListTextPrecompute.estimateMsgWidth(res);
    }

    /**
     * Submit a new list. AsyncListDiffer computes the diff on a background
     * thread, then dispatches the minimal insert/remove/change operations to
     * this adapter on the main thread — the main thread never blocks.
     *
     * v89: Also kicks off background text pre-computation for all items so
     * that onDraw() finds every ellipsized string already cached.
     */
    public void submitList(List<User> newList) {
        List<User> safe = newList == null ? Collections.emptyList() : newList;
        differ.submitList(safe);
        // Precompute runs on a background thread — main thread returns immediately.
        // By the time the diff result dispatches onBindViewHolder, most entries
        // will already be cached (precompute completes in ~10–50 ms for 100 rows).
        if (mEstimatedNameWidth > 0) {
            ChatListTextPrecompute.precompute(safe, mEstimatedNameWidth, mEstimatedMsgWidth);
        }
    }

    /** Returns the current snapshot (safe to read on the main thread). */
    public List<User> getCurrentList() {
        return differ.getCurrentList();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, Long> sLastPreloadAt = new ConcurrentHashMap<>();
    private static final long PRELOAD_COOLDOWN_MS = 30_000L;

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

    private final String myUid = FirebaseAuth.getInstance().getUid();

    private boolean isSelecting = false;
    private final Set<String> selectedUids = new HashSet<>();

    private static final String PAYLOAD_SELECTION = "payload_selection";

    // v83: constructor no longer takes a List<User> — caller uses submitList().
    public ChatListAdapter(SelectionListener listener) {
        this.selectionListener = listener;
        setHasStableIds(true);
    }

    // v85: resolve avatar decode size lazily from context (50dp avatar)
    private static int sAvatarSizePx = 0;
    private static int getAvatarSizePx(Context ctx) {
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
    private static final DecodeFormat AVATAR_FORMAT =
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

    public void setSpecialRequestSenders(Set<String> set) {
        this.specialRequestSenders = set == null ? new HashSet<>() : set;
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setOnAvatarClickListener(OnAvatarClickListener listener) {
        this.avatarClickListener = listener;
    }

    @Deprecated
    public void setOnLongPressListener(OnLongPressListener listener) {
        this.longPressListener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat, parent, false);
        return new VH(v);
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

        // Selection mode is always a full applySelectionVisuals pass
        if (payloads.contains(PAYLOAD_SELECTION)) {
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

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        List<User> list = differ.getCurrentList();
        User u = list.get(pos);
        Context ctx = h.itemView.getContext();

        preloadChatIfDue(ctx, u);

        h.nameTimeView.setName(u.name == null ? "User" : u.name);

        Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
        // v85: ChatListTimeCache — LruCache keyed by minute, avoids SimpleDateFormat per bind
        h.nameTimeView.setTime((when != null && when > 0)
                ? ChatListTimeCache.getFormatted(when) : "");

        // v90: Avatar — HARDWARE bitmap on API 26+ (GPU-resident, zero CPU→GPU upload per
        // frame) or RGB_565 on API < 26.  Glide applies circleCrop on a software bitmap
        // first, then promotes the result to Bitmap.Config.HARDWARE before caching.
        String avatarUrl = (u.thumbUrl != null && !u.thumbUrl.isEmpty())
                ? u.thumbUrl : u.photoUrl;
        int avatarPx = getAvatarSizePx(ctx);
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(ctx)
                    .load(avatarUrl)
                    .dontAnimate()
                    .override(avatarPx, avatarPx)
                    .format(AVATAR_FORMAT)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }
        // v85: pre-warm Glide decode for next contact so it's ready before the row scrolls in
        preloadAdjacentAvatar(ctx, list, pos);

        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        boolean hasStory = u.uid != null && (scm.hasUnseen(u.uid) || scm.hasStatus(u.uid));

        if (h.storyRingView != null && u.uid != null) {
            h.storyRingView.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                if (avatarClickListener != null) avatarClickListener.onAvatarClick(u);
                else openStatusOrChat(ctx, u);
            });
        }

        h.isTypingNow = false;
        applySelectionVisuals(h, u);
        attachTypingListener(h, u);

        if (h.callButtonsView != null) {
            h.callButtonsView.setListeners(
                () -> {
                    if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                    Intent i = new Intent().setClassName(ctx.getPackageName(),
                            "com.callx.app.call.CallActivity");
                    i.putExtra("partnerUid", u.uid);
                    i.putExtra("partnerName", u.name);
                    i.putExtra("isCaller", true);
                    i.putExtra("video", false);
                    ctx.startActivity(i);
                },
                () -> {
                    if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                    Intent i = new Intent().setClassName(ctx.getPackageName(),
                            "com.callx.app.call.CallActivity");
                    i.putExtra("partnerUid", u.uid);
                    i.putExtra("partnerName", u.name);
                    i.putExtra("isCaller", true);
                    i.putExtra("video", true);
                    ctx.startActivity(i);
                }
            );
        }

        h.ivAvatar.setOnClickListener(v -> {
            if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
            if (avatarClickListener != null) avatarClickListener.onAvatarClick(u);
            else if (hasStory) openStatusOrChat(ctx, u);
            else openChat(ctx, u);
        });

        h.ivAvatar.setOnLongClickListener(v -> {
            if (isSelecting) return true;
            showAvatarZoom(ctx, u.photoUrl, u.name);
            return true;
        });

        h.itemView.setOnClickListener(v -> {
            if (isSelecting) toggleSelection(h.getAdapterPosition());
            else openChat(ctx, u);
        });

        h.itemView.setOnLongClickListener(v -> {
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

        long unread = u.unread == null ? 0 : u.unread;
        int lastMsgColor;
        if (unread > 0 && !isSelecting) {
            h.unreadBadgeView.setBadgeCount(unread);
            lastMsgColor = ctx.getResources().getColor(R.color.text_primary);
            h.nameTimeView.setNameColor(0xFF0F172A);
        } else {
            h.unreadBadgeView.setBadgeCount(0);
            lastMsgColor = ctx.getResources().getColor(R.color.text_secondary);
            h.nameTimeView.setNameColor(0xFF0F172A);
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
     * in memory/disk-cache before that row scrolls into view. Only fires for
     * pos+1 (one step ahead) and is a no-op if the URL is null/empty.
     */
    private void preloadAdjacentAvatar(Context ctx, List<User> list, int pos) {
        int next = pos + 1;
        if (next >= list.size()) return;
        User adj = list.get(next);
        String url = (adj.thumbUrl != null && !adj.thumbUrl.isEmpty())
                ? adj.thumbUrl : adj.photoUrl;
        if (url == null || url.isEmpty()) return;
        int px = getAvatarSizePx(ctx);
        Glide.with(ctx)
                .load(url)
                .override(px, px)
                .format(AVATAR_FORMAT)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .apply(RequestOptions.circleCropTransform())
                .preload(px, px);
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
            try { Glide.with(h.ivAvatar.getContext()).clear(h.ivAvatar); }
            catch (Exception ignored) {}
        }
        detachTypingListener(h);
        h.isTypingNow = false;
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
    }

    private void detachTypingListener(VH h) {
        if (h.typingRef != null && h.typingListener != null) {
            h.typingRef.removeEventListener(h.typingListener);
        }
        h.typingRef = null;
        h.typingListener = null;
    }

    private void applyTypingRow(VH h, User u, boolean isTyping) {
        h.isTypingNow = isTyping;
        if (isTyping) {
            h.lastMessageView.setMessageText("typing...", 0xFF0F4C3A, true);
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_NONE, 0);
        } else {
            applySelectionVisuals(h, u);
        }
    }

    private static final long OPEN_CHAT_SAFETY_CAP_MS = 150L;

    private void openChat(Context ctx, User u) {
        String chatId = (myUid != null && u.uid != null)
                ? FirebaseUtils.getChatId(myUid, u.uid) : null;
        Runnable navigate = () -> {
            Intent i = new Intent(ctx, ChatActivity.class);
            i.putExtra("partnerUid",   u.uid);
            i.putExtra("partnerName",  u.name);
            i.putExtra("partnerPhoto", u.photoUrl != null ? u.photoUrl : "");
            i.putExtra("partnerThumb", u.thumbUrl != null ? u.thumbUrl : "");
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
        if (u.uid == null) { openChat(ctx, u); return; }
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        if (scm.hasUnseen(u.uid) || scm.hasStatus(u.uid)) {
            Intent si = new Intent().setClassName(ctx.getPackageName(),
                    "com.callx.app.viewer.StatusViewerActivity");
            si.putExtra("ownerUid",  u.uid);
            si.putExtra("ownerName", u.name != null ? u.name : "");
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

    private void showAvatarZoom(Context ctx, String photoUrl, String name) {
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
                ctx, photoUrl, name, R.drawable.ic_person, R.drawable.ic_close);
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
