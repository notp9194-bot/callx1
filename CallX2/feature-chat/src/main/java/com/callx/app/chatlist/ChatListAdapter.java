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
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import com.callx.app.chatlist.canvas.ChatListCallButtonsView;
import com.callx.app.chatlist.canvas.ChatListLastMessageView;
import com.callx.app.chatlist.canvas.ChatListNameTimeView;
import com.callx.app.chatlist.canvas.ChatListStoryRingView;
import com.callx.app.chatlist.canvas.ChatListUnreadBadgeView;
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

import java.text.SimpleDateFormat;
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

    // ── v83: DiffUtil.ItemCallback ────────────────────────────────────────────
    // Static constant — one allocation for the lifetime of the process.
    // areContentsTheSame covers every field that onBindViewHolder reads so a
    // changed field always triggers a rebind of only that row.
    public static final DiffUtil.ItemCallback<User> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<User>() {

        @Override
        public boolean areItemsTheSame(@NonNull User a, @NonNull User b) {
            // Identity: same contact ↔ same UID
            return a.uid != null && a.uid.equals(b.uid);
        }

        @Override
        public boolean areContentsTheSame(@NonNull User a, @NonNull User b) {
            // Content: compare every field the row renders so a real change
            // triggers a rebind and a no-op update does NOT.
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

        private boolean safeEq(String x, String y) {
            return x == null ? y == null : x.equals(y);
        }
        private boolean longEq(Long x, Long y) {
            return x == null ? y == null : x.equals(y);
        }
    };

    // ── v83: AsyncListDiffer — owns the list, runs diff on a bg thread ────────
    private final AsyncListDiffer<User> differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);

    /**
     * Submit a new list. AsyncListDiffer computes the diff on a background
     * thread, then dispatches the minimal insert/remove/change operations to
     * this adapter on the main thread — the main thread never blocks.
     *
     * Pass an empty list or null to clear.
     */
    public void submitList(List<User> newList) {
        differ.submitList(newList == null ? Collections.emptyList() : newList);
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

    private final SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
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

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            User u = differ.getCurrentList().get(pos);
            applySelectionVisuals(h, u);
            return;
        }
        super.onBindViewHolder(h, pos, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        List<User> list = differ.getCurrentList();
        User u = list.get(pos);
        Context ctx = h.itemView.getContext();

        preloadChatIfDue(ctx, u);

        h.nameTimeView.setName(u.name == null ? "User" : u.name);

        Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
        h.nameTimeView.setTime((when != null && when > 0) ? fmt.format(new Date(when)) : "");

        // Avatar — Glide unchanged
        String avatarUrl = (u.thumbUrl != null && !u.thumbUrl.isEmpty())
                ? u.thumbUrl : u.photoUrl;
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(ctx)
                    .load(avatarUrl)
                    .dontAnimate()
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

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

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        detachTypingListener(h);
    }

    static class VH extends RecyclerView.ViewHolder {
        // v82: canvas views
        ChatListNameTimeView    nameTimeView;
        ChatListUnreadBadgeView unreadBadgeView;
        ChatListStoryRingView   storyRingView;
        ChatListCallButtonsView callButtonsView;
        // v23: canvas last-message + ticks
        ChatListLastMessageView lastMessageView;
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
            nameTimeView    = v.findViewById(R.id.view_name_time);
            lastMessageView = v.findViewById(R.id.view_last_message);
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
