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

import com.callx.app.conversation.ChatActivity;
import com.callx.app.models.User;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.cache.StatusCacheManager;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatListAdapter v21
 *
 * CHANGES v21 — Delete / Delete-All system:
 *  1. Long-press pe directly selection mode start hota hai.
 *     PrivacyDirectDialog ab DOUBLE long-press ya dedicated method se trigger hoga
 *     (ChatsFragment mein showPrivacyDirectDialog() ko context menu se call karo).
 *  2. Selection overlay: checkmark circle avatar ke upar dikhta hai.
 *  3. Call buttons + story ring selection mode mein hide hote hain.
 *  4. setOnLongPressListener() is now used ONLY for external callers who
 *     still want the old behavior; if NOT set, long-press starts selection.
 *     ChatsFragment v21 mein longPressListener set nahi karta —
 *     PrivacyDirectDialog ab selection bar ke 3-dot menu se accessible hai.
 */
public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {

    // PERF FIX: preload trigger moved here from ChatActivity. Earlier,
    // ChatRepository.preloadRecentChats() only ran 3s AFTER a chat was
    // already open — so the NEXT chat you tapped had zero preload benefit.
    // Now, the moment a row is actually visible in the list (onBindViewHolder
    // fires only for visible/soon-visible items), we kick off its delta sync
    // in the background. By the time the user taps it, Room already has the
    // latest messages and ChatActivity's Pager attaches instantly — no
    // Firebase round-trip wait on the chat screen itself.
    // Cooldown map avoids re-triggering a Firebase sync on every scroll-driven
    // rebind of the same row (RecyclerView rebinds recycled views often).
    private static final ConcurrentHashMap<String, Long> sLastPreloadAt = new ConcurrentHashMap<>();
    private static final long PRELOAD_COOLDOWN_MS = 30_000L;

    private void preloadChatIfDue(Context ctx, User u) {
        if (u == null || u.uid == null) return;
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) return;
        String chatId = FirebaseUtils.getChatId(myUid, u.uid);
        long now = System.currentTimeMillis();
        Long last = sLastPreloadAt.get(chatId);
        if (last != null && (now - last) < PRELOAD_COOLDOWN_MS) return;
        sLastPreloadAt.put(chatId, now);
        ChatRepository repo = ChatRepository.getInstance(ctx.getApplicationContext());
        // Local disk read (Room persists across app-kills) — warms the
        // in-memory render cache with ZERO network wait. This is what makes
        // ChatActivity's warmCacheHit fast path fire on the very FIRST tap,
        // not just on a chat's 2nd+ open.
        repo.warmLastMessagesCache(chatId);
        // Network delta sync — keeps Room itself up to date in the background.
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

    private boolean isSelecting = false;
    private final Set<String> selectedUids = new HashSet<>();

    public ChatListAdapter(List<User> contacts, SelectionListener listener) {
        this.contacts          = contacts;
        this.selectionListener = listener;
        // PERF FIX: stable IDs allow RecyclerView to animate diffs correctly
        // and avoid unnecessary rebind calls for unchanged items.
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

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        User u = contacts.get(pos);
        Context ctx = h.itemView.getContext();

        // PERF FIX: preload this chat's messages the moment its row becomes
        // visible, well before the user taps it. See preloadChatIfDue() above.
        preloadChatIfDue(ctx, u);

        h.tvName.setText(u.name == null ? "User" : u.name);

        // thumbUrl → 100px WebP, fast load. Fallback: photoUrl
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

        bindPresenceAndMute(h, u);

        // ── Story ring ──────────────────────────────────────────────────────
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        boolean hasStory = u.uid != null && (scm.hasUnseen(u.uid) || scm.hasStatus(u.uid));

        if (h.ivStoryRing != null && u.uid != null) {
            if (!isSelecting && scm.hasUnseen(u.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_unseen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else if (!isSelecting && scm.hasStatus(u.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_seen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else {
                h.ivStoryRing.setVisibility(View.GONE);
            }
            h.ivStoryRing.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                if (avatarClickListener != null) avatarClickListener.onAvatarClick(u);
                else openStatusOrChat(ctx, u);
            });
        }

        // ── Last message / time / unread ────────────────────────────────────
        if (u.lastMessage != null && !u.lastMessage.isEmpty())
            h.tvLastMessage.setText(u.lastMessage);
        else
            h.tvLastMessage.setText("Tap karke chat karo");

        Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
        h.tvTime.setText((when != null && when > 0) ? fmt.format(new Date(when)) : "");

        long unread = u.unread == null ? 0 : u.unread;
        if (unread > 0 && !isSelecting) {
            h.tvUnread.setText(unread > 99 ? "99+" : String.valueOf(unread));
            h.tvUnread.setVisibility(View.VISIBLE);
            h.tvLastMessage.setTextColor(ctx.getResources().getColor(R.color.text_primary));
        } else {
            h.tvUnread.setVisibility(View.GONE);
            h.tvLastMessage.setTextColor(ctx.getResources().getColor(R.color.text_secondary));
        }

        boolean isSpecial = u.uid != null && specialRequestSenders.contains(u.uid);
        if (isSpecial && !isSelecting) {
            h.tvLastMessage.setText("⭐ Special unblock request");
            h.tvLastMessage.setTextColor(0xFFFF8F00);
        }

        // ── Selection state ────────────────────────────────────────────────
        boolean selected = u.uid != null && selectedUids.contains(u.uid);

        // Card background highlight
        h.itemView.setBackgroundColor(
            selected      ? 0x335B5BF6 :
            isSpecial     ? 0x33FFC107 : 0x00000000);

        // Selection overlay on avatar
        if (h.flSelectOverlay != null) {
            if (isSelecting) {
                h.flSelectOverlay.setVisibility(View.VISIBLE);
                if (h.vCheckRing != null)  h.vCheckRing.setVisibility(selected ? View.VISIBLE : View.GONE);
                if (h.ivCheck != null)     h.ivCheck.setVisibility(View.INVISIBLE);  // ring handles it
            } else {
                h.flSelectOverlay.setVisibility(View.GONE);
            }
        }

        // Call buttons: hide during selection mode
        if (h.llCallBtns != null) {
            h.llCallBtns.setVisibility(isSelecting ? View.GONE : View.VISIBLE);
        }

        // ── Click listeners ─────────────────────────────────────────────────

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

        // ── Long-press: START selection mode (v21) ──────────────────────────
        h.itemView.setOnLongClickListener(v -> {
            if (!isSelecting) {
                // Start selection mode
                isSelecting = true;
                if (u.uid != null) selectedUids.add(u.uid);
                notifyDataSetChanged();
                if (selectionListener != null) selectionListener.onSelectionStarted();
            } else {
                toggleSelection(h.getAdapterPosition());
            }
            return true;
        });

        // Call buttons
        if (h.btnCall != null) {
            h.btnCall.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid", u.uid);
                i.putExtra("partnerName", u.name);
                i.putExtra("isCaller", true);
                i.putExtra("video", false);
                ctx.startActivity(i);
            });
        }

        if (h.btnVideoCall != null) {
            h.btnVideoCall.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid", u.uid);
                i.putExtra("partnerName", u.name);
                i.putExtra("isCaller", true);
                i.putExtra("video", true);
                ctx.startActivity(i);
            });
        }
    }

    // Safety cap only — normal Room reads finish in a few ms (indexed,
    // LIMIT-20, local disk). This just stops a tap from ever feeling stuck
    // on an unusually slow device or a brand-new/never-synced chat.
    private static final long OPEN_CHAT_SAFETY_CAP_MS = 150L;

    private void openChat(Context ctx, User u) {
        String myUid = FirebaseAuth.getInstance().getUid();
        String chatId = (myUid != null && u.uid != null) ? FirebaseUtils.getChatId(myUid, u.uid) : null;

        Runnable navigate = () -> {
            Intent i = new Intent(ctx, ChatActivity.class);
            i.putExtra("partnerUid",   u.uid);
            i.putExtra("partnerName",  u.name);
            i.putExtra("partnerPhoto", u.photoUrl != null ? u.photoUrl : "");
            i.putExtra("partnerThumb", u.thumbUrl != null ? u.thumbUrl : "");
            ctx.startActivity(i);
        };

        if (chatId == null) {
            navigate.run(); // can't resolve a chatId — nothing to prime, just open
            return;
        }

        // WhatsApp-style open: read this chat's recent messages from Room
        // (local disk, no network) and only THEN start ChatActivity, so the
        // screen arrives with content already in it instead of arriving
        // blank and having messages pop in afterward. If this chat is
        // already warm from earlier this session, the callback fires
        // synchronously-ish (posted immediately) with no real wait.
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
        notifyItemChanged(pos);
        if (selectedUids.isEmpty()) {
            isSelecting = false;
            if (selectionListener != null) selectionListener.onSelectionCleared();
        } else {
            if (selectionListener != null) selectionListener.onSelectionChanged();
        }
    }

    public void selectAll() {
        isSelecting = true;
        for (User u : contacts) if (u.uid != null) selectedUids.add(u.uid);
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged();
    }

    public void clearSelection() {
        isSelecting = false;
        selectedUids.clear();
        notifyDataSetChanged();
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

    // ── Avatar Zoom Dialog ────────────────────────────────────────────────
    private void showAvatarZoom(Context ctx, String photoUrl, String name) {
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            ctx, photoUrl, name, R.drawable.ic_person, R.drawable.ic_close);
    }

    @Override public int getItemCount() { return contacts.size(); }

    // ── Live presence/mute (per-row, not part of the DiffUtil content model) ───
    //
    // Attached in onBindViewHolder, detached in onViewRecycled. Only rows
    // actually on screen ever hold a listener — RecyclerView recycling takes
    // care of the rest, so this scales to any list length without extra work
    // here (unlike a naive "listen to all N contacts up front" approach).

    private void bindPresenceAndMute(VH h, User u) {
        detachPresenceAndMute(h);
        if (u.uid == null || u.uid.isEmpty()) return;
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) return;
        final String uid = u.uid;
        h.boundUidForPresence = uid;

        h.presenceRef = FirebaseUtils.getUserRef(uid).child("online");
        h.presenceListener = h.presenceRef.addValueEventListener(
            new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot s) {
                    // Guard against a listener firing after this row was recycled
                    // and rebound to a different user (async Firebase callback).
                    if (!uid.equals(h.boundUidForPresence) || h.vOnlineDot == null) return;
                    boolean online = Boolean.TRUE.equals(s.getValue(Boolean.class));
                    h.vOnlineDot.setVisibility(online ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });

        h.muteRef = FirebaseUtils.db().getReference("muted").child(myUid).child(uid);
        h.muteListener = h.muteRef.addValueEventListener(
            new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot s) {
                    if (!uid.equals(h.boundUidForPresence) || h.ivMuteIcon == null) return;
                    boolean muted = Boolean.TRUE.equals(s.getValue(Boolean.class));
                    h.ivMuteIcon.setVisibility(muted ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });
    }

    private void detachPresenceAndMute(VH h) {
        if (h.presenceRef != null && h.presenceListener != null) {
            h.presenceRef.removeEventListener(h.presenceListener);
        }
        if (h.muteRef != null && h.muteListener != null) {
            h.muteRef.removeEventListener(h.muteListener);
        }
        h.presenceRef = null; h.presenceListener = null;
        h.muteRef = null; h.muteListener = null;
        h.boundUidForPresence = null;
        if (h.vOnlineDot != null) h.vOnlineDot.setVisibility(View.GONE);
        if (h.ivMuteIcon != null) h.ivMuteIcon.setVisibility(View.GONE);
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        detachPresenceAndMute(h);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvLastMessage, tvTime, tvUnread;
        CircleImageView ivAvatar;
        android.widget.ImageView ivStoryRing, ivCheck;
        View flSelectOverlay, vCheckRing, llCallBtns;
        ImageButton btnCall, btnVideoCall;
        View vOnlineDot;
        android.widget.ImageView ivMuteIcon;

        // ── Live per-row presence/mute state ────────────────────────────────
        // Bound in onBindViewHolder, detached in the adapter's onViewRecycled().
        // Deliberately NOT routed through notifyItemChanged()/DiffUtil — these
        // flip a single small View's visibility directly from the Firebase
        // callback, so a presence/mute change never triggers a list rebind,
        // never re-runs Glide/StaticLayout work for the row, and only ever
        // costs anything for the ~10-15 rows actually on screen (listeners are
        // torn down the moment a row recycles off-screen).
        String boundUidForPresence;
        com.google.firebase.database.DatabaseReference presenceRef;
        com.google.firebase.database.ValueEventListener presenceListener;
        com.google.firebase.database.DatabaseReference muteRef;
        com.google.firebase.database.ValueEventListener muteListener;

        VH(View v) {
            super(v);
            tvName          = v.findViewById(R.id.tv_name);
            tvLastMessage   = v.findViewById(R.id.tv_last_message);
            tvTime          = v.findViewById(R.id.tv_time);
            tvUnread        = v.findViewById(R.id.tv_unread_badge);
            ivAvatar        = v.findViewById(R.id.iv_avatar);
            ivStoryRing     = v.findViewById(R.id.iv_story_ring);
            flSelectOverlay = v.findViewById(R.id.fl_select_overlay);
            ivCheck         = v.findViewById(R.id.iv_check);
            vCheckRing      = v.findViewById(R.id.v_check_ring);
            llCallBtns      = v.findViewById(R.id.ll_call_btns);
            btnCall         = v.findViewById(R.id.btn_call);
            btnVideoCall    = v.findViewById(R.id.btn_video_call);
            vOnlineDot      = v.findViewById(R.id.v_online_dot);
            ivMuteIcon      = v.findViewById(R.id.iv_mute_icon);
        }
    }
}
