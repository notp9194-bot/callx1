package com.callx.app.chatlist;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
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
 * ChatListAdapter v82
 *
 * CHANGES v82 — Full canvas row (perf):
 *  1. CardView root replaced by plain FrameLayout (cardElevation=0dp so no
 *     visual change; removes CardView's extra measure/layout pass).
 *  2. tv_name + tv_time (two TextViews) → ChatListNameTimeView: a single
 *     canvas View that draws name bold-left and time muted-right in one
 *     draw call, with ellipsis only on the name side.
 *  3. tv_unread_badge (TextView + GradientDrawable) → ChatListUnreadBadgeView:
 *     canvas pill + count with no Drawable inflation; self-measures to zero
 *     when count is 0, so no visibility toggle required.
 *  4. iv_story_ring (ImageView + background drawable swap) →
 *     ChatListStoryRingView: canvas arc drawn directly with Paint.STROKE;
 *     STATE_NONE skips draw entirely (no GONE/VISIBLE toggle needed).
 *  5. ll_call_btns LinearLayout + two ImageButtons → ChatListCallButtonsView:
 *     both icons (camera + phone) drawn via Path on canvas; touch regions
 *     split at the midpoint — identical UX, no nested clickable children.
 *  6. iv_avatar (CircleImageView) — UNCHANGED; Glide caching kept.
 *  7. fl_select_overlay / ivCheck / vCheckRing — UNCHANGED.
 *
 * CHANGES v23 — Canvas rendering for last-message row (perf):
 *  Nested LinearLayout (ImageView tick + TextView last-msg) replaced by
 *  ChatListLastMessageView (canvas).
 *
 * CHANGES v22 — Read receipts, media labels, live typing.
 * CHANGES v21 — Delete / Delete-All system, selection mode.
 */
public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {

    private static final ConcurrentHashMap<String, Long> sLastPreloadAt = new ConcurrentHashMap<>();
    private static final long PRELOAD_COOLDOWN_MS = 30_000L;

    private void preloadChatIfDue(Context ctx, User u) {
        if (u == null || u.uid == null) return;
        if (myUid == null) return;
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

    /** @deprecated v21: Long-press now starts selection. Use context-menu instead. */
    @Deprecated
    public interface OnLongPressListener {
        void onLongPress(User user, View anchor);
    }

    private final List<User> contacts;
    private final SelectionListener selectionListener;
    private OnAvatarClickListener avatarClickListener;

    /** kept for backward-compat but ChatsFragment v21 does NOT set this */
    private OnLongPressListener longPressListener;

    private final SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private Set<String> specialRequestSenders = new HashSet<>();

    private final String myUid = FirebaseAuth.getInstance().getUid();

    private boolean isSelecting = false;
    private final Set<String> selectedUids = new HashSet<>();

    private static final String PAYLOAD_SELECTION = "payload_selection";

    public ChatListAdapter(List<User> contacts, SelectionListener listener) {
        this.contacts          = contacts;
        this.selectionListener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= contacts.size()) return RecyclerView.NO_ID;
        String uid = contacts.get(position).uid;
        return uid != null ? uid.hashCode() : position;
    }

    public void setSpecialRequestSenders(Set<String> set) {
        this.specialRequestSenders = set == null ? new HashSet<>() : set;
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setOnAvatarClickListener(OnAvatarClickListener listener) {
        this.avatarClickListener = listener;
    }

    /** @deprecated v21 — long-press now triggers selection mode. */
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

    @Override public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            User u = contacts.get(pos);
            applySelectionVisuals(h, u);
            return;
        }
        super.onBindViewHolder(h, pos, payloads);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        User u = contacts.get(pos);
        Context ctx = h.itemView.getContext();

        preloadChatIfDue(ctx, u);

        // v82: name drawn by ChatListNameTimeView (canvas)
        h.nameTimeView.setName(u.name == null ? "User" : u.name);

        // v82: time drawn by same ChatListNameTimeView
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

        // v82: story ring — canvas arc, STATE_NONE skips draw
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

        // v82: call buttons — canvas, listeners set once per full bind
        if (h.callButtonsView != null) {
            h.callButtonsView.setListeners(
                () -> {
                    if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                    Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.call.CallActivity");
                    i.putExtra("partnerUid", u.uid);
                    i.putExtra("partnerName", u.name);
                    i.putExtra("isCaller", true);
                    i.putExtra("video", false);
                    ctx.startActivity(i);
                },
                () -> {
                    if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                    Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.call.CallActivity");
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
            if (avatarClickListener != null) {
                avatarClickListener.onAvatarClick(u);
            } else {
                if (hasStory) openStatusOrChat(ctx, u);
                else openChat(ctx, u);
            }
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

        // v82: unread badge — canvas, self-sizes to 0 when count=0
        long unread = u.unread == null ? 0 : u.unread;
        int lastMsgColor;
        if (unread > 0 && !isSelecting) {
            h.unreadBadgeView.setBadgeCount(unread);
            lastMsgColor = ctx.getResources().getColor(R.color.text_primary);
            // Bold name when unread
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

        // v82: story ring — canvas STATE, no drawable swap
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

        // Row background highlight
        h.itemView.setBackgroundColor(
            selected  ? 0x335B5BF6 :
            isSpecial ? 0x33FFC107 : 0x00000000);

        // Selection overlay
        if (h.flSelectOverlay != null) {
            if (isSelecting) {
                h.flSelectOverlay.setVisibility(View.VISIBLE);
                if (h.vCheckRing != null)  h.vCheckRing.setVisibility(selected ? View.VISIBLE : View.GONE);
                if (h.ivCheck != null)     h.ivCheck.setVisibility(View.INVISIBLE);
            } else {
                h.flSelectOverlay.setVisibility(View.GONE);
            }
        }

        // v82: call buttons canvas view — hide during selection
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
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= contacts.size()) return;
                User current = contacts.get(pos);
                if (current.uid == null || !current.uid.equals(u.uid)) return;
                applyTypingRow(h, current, Boolean.TRUE.equals(snap.getValue(Boolean.class)));
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
        String chatId = (myUid != null && u.uid != null) ? FirebaseUtils.getChatId(myUid, u.uid) : null;

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
        android.os.Handler safetyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable safetyFallback = () -> {
            if (!navigated[0]) { navigated[0] = true; navigate.run(); }
        };
        safetyHandler.postDelayed(safetyFallback, OPEN_CHAT_SAFETY_CAP_MS);

        ChatRepository.getInstance(ctx.getApplicationContext()).primeChatFromRoom(chatId, () -> {
            safetyHandler.removeCallbacks(safetyFallback);
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
        if (pos < 0 || pos >= contacts.size()) return;
        User u = contacts.get(pos);
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
        for (User u : contacts) if (u.uid != null) selectedUids.add(u.uid);
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
        for (User u : contacts)
            if (u.uid != null && selectedUids.contains(u.uid)) sel.add(u);
        return sel;
    }

    private void showAvatarZoom(Context ctx, String photoUrl, String name) {
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            ctx, photoUrl, name, R.drawable.ic_person, R.drawable.ic_close);
    }

    @Override public int getItemCount() { return contacts.size(); }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        detachTypingListener(h);
    }

    static class VH extends RecyclerView.ViewHolder {
        // v82: new canvas views
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

        // typing listener
        DatabaseReference typingRef;
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
