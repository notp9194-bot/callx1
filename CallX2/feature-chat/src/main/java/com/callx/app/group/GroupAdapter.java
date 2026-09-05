package com.callx.app.group;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;
import com.callx.app.chatlist.ChatListTimeCache;
import com.callx.app.chatlist.canvas.ChatListLastMessageView;
import com.callx.app.chatlist.canvas.ChatListNameTimeView;
import com.callx.app.chatlist.canvas.ChatListUnreadBadgeView;
import com.callx.app.models.Group;
import com.callx.app.utils.ChatListPreviewUtil;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Groups list adapter. It intentionally follows the Chats adapter contract:
 * AsyncListDiffer for background diffs, stable IDs, unread badge, real message
 * time, and long-press selection for batch list actions.
 */
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {

    // PERF FIX — predictive pre-warm (mirrors ChatListAdapter's identical
    // mechanism for 1:1 chats): while the user scrolls the groups list,
    // once a row settles on screen for BIND_SETTLE_DELAY_MS (i.e. it wasn't
    // just flung past), kick off a background Room read of that group's
    // last messages and seed LastMessagesCache with it — so by the time the
    // user actually taps the group, GroupChatActivity's onCreate() already
    // has real data ready instead of racing Paging/Firebase from a blank
    // screen. Same 180ms settle window and 30s per-chat cooldown as the
    // 1:1 list, so a fast fling still costs nothing.
    private static final long BIND_SETTLE_DELAY_MS = 180L;
    private static final ConcurrentHashMap<String, Long> sLastPreloadAt = new ConcurrentHashMap<>();
    private static final long PRELOAD_COOLDOWN_MS = 30_000L;

    private void preloadGroupIfDue(Context ctx, Group g) {
        if (g == null || g.id == null) return;
        long now = System.currentTimeMillis();
        Long last = sLastPreloadAt.get(g.id);
        if (last != null && (now - last) < PRELOAD_COOLDOWN_MS) return;
        sLastPreloadAt.put(g.id, now);
        com.callx.app.repository.ChatRepository.getInstance(ctx.getApplicationContext())
                .warmLastMessagesCache(g.id);
    }

    public interface SelectionListener {
        void onSelectionStarted();
        void onSelectionChanged();
        void onSelectionCleared();
    }

    private static final String PAYLOAD_SELECTION = "group_selection";

    public static final DiffUtil.ItemCallback<Group> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Group>() {
                @Override public boolean areItemsTheSame(@NonNull Group a, @NonNull Group b) {
                    return a.id != null && a.id.equals(b.id);
                }

                @Override public boolean areContentsTheSame(@NonNull Group a, @NonNull Group b) {
                    return eq(a.name, b.name) && eq(a.iconUrl, b.iconUrl)
                            && eq(a.lastMessage, b.lastMessage)
                            && eq(a.lastSenderName, b.lastSenderName)
                            && eq(a.lastMessageType, b.lastMessageType)
                            && eq(a.lastMessageStatus, b.lastMessageStatus)
                            && eq(a.lastMessageSenderUid, b.lastMessageSenderUid)
                            && eq(a.lastMessageAt, b.lastMessageAt)
                            && unread(a) == unread(b)
                            && a.localPinned == b.localPinned
                            && a.localArchived == b.localArchived
                            && a.localMuted == b.localMuted
                            && memberCount(a) == memberCount(b);
                }

                @Override public Object getChangePayload(@NonNull Group a, @NonNull Group b) {
                    int flags = 0;
                    if (!eq(a.name, b.name) || !eq(a.iconUrl, b.iconUrl)
                            || a.localPinned != b.localPinned) flags |= 1;
                    if (!eq(a.lastMessage, b.lastMessage)
                            || !eq(a.lastSenderName, b.lastSenderName)
                            || !eq(a.lastMessageType, b.lastMessageType)
                            || !eq(a.lastMessageStatus, b.lastMessageStatus)
                            || !eq(a.lastMessageSenderUid, b.lastMessageSenderUid)
                            || a.localMuted != b.localMuted) flags |= 2;
                    if (!eq(a.lastMessageAt, b.lastMessageAt)) flags |= 4;
                    if (unread(a) != unread(b)) flags |= 8;
                    if (a.localArchived != b.localArchived
                            || memberCount(a) != memberCount(b)) flags |= 16;
                    return flags == 0 ? null : flags;
                }

                private boolean eq(Object a, Object b) {
                    return a == null ? b == null : a.equals(b);
                }
                private int memberCount(Group g) {
                    return g.members == null ? 0 : g.members.size();
                }
                private long unread(Group g) {
                    if (g.unread == null || FirebaseUtils.getCurrentUid() == null) return 0;
                    Long value = g.unread.get(FirebaseUtils.getCurrentUid());
                    return value == null ? 0 : Math.max(0, value);
                }
            };

    private final AsyncListDiffer<Group> differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);
    private final String myUid = FirebaseUtils.getCurrentUid();
    private final SelectionListener selectionListener;
    private final Set<String> selectedIds = new HashSet<>();
    private boolean selecting;

    public GroupAdapter(SelectionListener listener) {
        selectionListener = listener;
        setHasStableIds(true);
    }

    public void submitList(List<Group> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    public List<Group> getCurrentList() {
        return differ.getCurrentList();
    }

    public boolean isSelecting() { return selecting; }
    public int getSelectedCount() { return selectedIds.size(); }

    public List<Group> getSelectedItems() {
        java.util.ArrayList<Group> result = new java.util.ArrayList<>();
        for (Group g : differ.getCurrentList()) {
            if (g.id != null && selectedIds.contains(g.id)) result.add(g);
        }
        return result;
    }

    public void selectAll() {
        selecting = true;
        selectedIds.clear();
        for (Group g : differ.getCurrentList()) if (g.id != null) selectedIds.add(g.id);
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (selectionListener != null) selectionListener.onSelectionChanged();
    }

    public void clearSelection() {
        selecting = false;
        selectedIds.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (selectionListener != null) selectionListener.onSelectionCleared();
    }

    private void toggleSelection(int position) {
        if (position == RecyclerView.NO_POSITION) return;
        Group g = differ.getCurrentList().get(position);
        if (g.id == null) return;
        if (!selecting) {
            selecting = true;
            selectedIds.add(g.id);
            if (selectionListener != null) selectionListener.onSelectionStarted();
        } else if (!selectedIds.add(g.id)) {
            selectedIds.remove(g.id);
            if (selectedIds.isEmpty()) {
                clearSelection();
                return;
            }
            if (selectionListener != null) selectionListener.onSelectionChanged();
        } else if (selectionListener != null) {
            selectionListener.onSelectionChanged();
        }
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }

    @Override public long getItemId(int position) {
        List<Group> list = differ.getCurrentList();
        if (position < 0 || position >= list.size()) return RecyclerView.NO_ID;
        String id = list.get(position).id;
        return id == null ? position : id.hashCode();
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int position) {
        bind(h, differ.getCurrentList().get(position));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int position,
                                           @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(h, position);
        } else {
            Group g = differ.getCurrentList().get(position);
            h.boundGroup = g;
            applySelection(h, g);
            if ((payloads.contains(PAYLOAD_SELECTION))) return;
            bind(h, g);
        }
    }

    private void bind(VH h, Group g) {
        Context ctx = h.itemView.getContext();
        h.boundGroup = g;
        h.nameTimeView.setName((g.localPinned ? "📌 " : "") +
                (g.name == null || g.name.isEmpty() ? "Group" : g.name));
        Long when = g.lastMessageAt != null ? g.lastMessageAt : g.createdAt;
        h.nameTimeView.setTime(when != null && when > 0
                ? ChatListTimeCache.getFormatted(when) : "");

        if (g.iconUrl != null && !g.iconUrl.isEmpty()) {
            int px = Math.round(50f * ctx.getResources().getDisplayMetrics().density);
            Glide.with(ctx).load(g.iconUrl).dontAnimate().override(px, px)
                    .format(android.os.Build.VERSION.SDK_INT >= 26
                            ? DecodeFormat.PREFER_ARGB_8888 : DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_group).error(R.drawable.ic_group)
                    .into(h.avatar);
            h.avatar.setPadding(0, 0, 0, 0);
        } else {
            h.avatar.setImageResource(R.drawable.ic_group);
            int pad = (int) (ctx.getResources().getDisplayMetrics().density * 12);
            h.avatar.setPadding(pad, pad, pad, pad);
        }

        h.typingNow = false;
        bindContent(h, g);
        attachTypingListener(h, g);
        applySelection(h, g);

        // Predictive pre-warm — see BIND_SETTLE_DELAY_MS doc above. Cancel
        // any not-yet-fired pre-warm from this VH's previous bind first
        // (row got recycled/rebound before settling).
        if (h.pendingPrewarmRunnable != null) {
            h.itemView.removeCallbacks(h.pendingPrewarmRunnable);
        }
        final Group boundGroup = g;
        h.pendingPrewarmRunnable = () -> preloadGroupIfDue(ctx, boundGroup);
        h.itemView.postDelayed(h.pendingPrewarmRunnable, BIND_SETTLE_DELAY_MS);
    }

    private void bindContent(VH h, Group g) {
        Context ctx = h.itemView.getContext();
        String preview = ChatListPreviewUtil.buildPreview(
                g.lastMessageType, g.lastMessage,
                memberCount(g) + " members");
        if (g.lastSenderName != null && !g.lastSenderName.isEmpty()
                && g.lastMessage != null && !g.lastMessage.isEmpty()) {
            preview = g.lastSenderName + ": " + preview;
        }
        h.lastMessage.setMessageText(preview,
                ctx.getResources().getColor(R.color.text_secondary), false);
        updateTicks(h, g);
        h.unread.setBadgeCount(unread(g));
    }

    private void applySelection(VH h, Group g) {
        boolean selected = g.id != null && selectedIds.contains(g.id);
        h.selectionOverlay.setVisibility(selecting ? View.VISIBLE : View.GONE);
        h.selectionCheck.setVisibility(selecting && selected ? View.VISIBLE : View.INVISIBLE);
        h.itemView.setAlpha(selecting && !selected ? 0.78f : 1f);
    }

    private void updateTicks(VH h, Group g) {
        boolean mine = myUid != null && myUid.equals(g.lastMessageSenderUid);
        if (h.typingNow || !mine || g.lastMessageStatus == null) {
            h.lastMessage.setTicks(ChatListLastMessageView.TICK_NONE, 0);
        } else if ("read".equals(g.lastMessageStatus)) {
            h.lastMessage.setTicks(ChatListLastMessageView.TICK_READ,
                    h.itemView.getResources().getColor(R.color.tick_read_blue));
        } else if ("delivered".equals(g.lastMessageStatus)) {
            h.lastMessage.setTicks(ChatListLastMessageView.TICK_DELIVERED,
                    h.itemView.getResources().getColor(R.color.text_muted));
        } else {
            h.lastMessage.setTicks(ChatListLastMessageView.TICK_SENT,
                    h.itemView.getResources().getColor(R.color.text_muted));
        }
    }

    private void attachTypingListener(VH h, Group g) {
        detachTypingListener(h);
        if (g.id == null) return;
        DatabaseReference ref = FirebaseUtils.getGroupTypingRef(g.id);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                int p = h.getBindingAdapterPosition();
                if (p == RecyclerView.NO_POSITION || p >= differ.getCurrentList().size()) return;
                Group current = differ.getCurrentList().get(p);
                if (!g.id.equals(current.id)) return;
                int count = 0;
                String first = null;
                for (DataSnapshot child : snap.getChildren()) {
                    if (myUid != null && myUid.equals(child.getKey())) continue;
                    if (first == null) first = child.getValue(String.class);
                    count++;
                }
                h.typingNow = count > 0;
                if (count > 0) {
                    String label = (first == null || first.isEmpty() ? "Someone" : first)
                            + (count > 1 ? " +" + (count - 1) : "") + " typing...";
                    h.lastMessage.setMessageText(label, 0xFF0F4C3A, true);
                    h.lastMessage.setTicks(ChatListLastMessageView.TICK_NONE, 0);
                } else {
                    bindContent(h, current);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        ref.addValueEventListener(listener);
        h.typingRef = ref;
        h.typingListener = listener;
    }

    private void detachTypingListener(VH h) {
        if (h.typingRef != null && h.typingListener != null)
            h.typingRef.removeEventListener(h.typingListener);
        h.typingRef = null;
        h.typingListener = null;
    }

    @Override public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        detachTypingListener(h);
        if (h.pendingPrewarmRunnable != null) {
            h.itemView.removeCallbacks(h.pendingPrewarmRunnable);
            h.pendingPrewarmRunnable = null;
        }
        try { Glide.with(h.avatar.getContext()).clear(h.avatar); } catch (Exception ignored) { }
        h.typingNow = false;
    }

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

    class VH extends RecyclerView.ViewHolder {
        final ChatListNameTimeView nameTimeView;
        final ChatListLastMessageView lastMessage;
        final ChatListUnreadBadgeView unread;
        final CircleImageView avatar;
        final View selectionOverlay;
        final View selectionCheck;
        DatabaseReference typingRef;
        ValueEventListener typingListener;
        Group boundGroup;
        boolean typingNow;
        Runnable pendingPrewarmRunnable;

        VH(View v) {
            super(v);
            nameTimeView = v.findViewById(R.id.view_group_name_members);
            lastMessage = v.findViewById(R.id.view_group_last_message);
            avatar = v.findViewById(R.id.iv_group_avatar);
            unread = v.findViewById(R.id.view_group_unread_badge);
            selectionOverlay = v.findViewById(R.id.fl_group_select_overlay);
            selectionCheck = v.findViewById(R.id.iv_group_check);
            v.setOnClickListener(x -> {
                if (boundGroup == null) return;
                if (selecting) {
                    toggleSelection(getBindingAdapterPosition());
                } else {
                    Intent i = new Intent(x.getContext(), GroupChatActivity.class);
                    i.putExtra("groupId", boundGroup.id);
                    i.putExtra("groupName", boundGroup.name);
                    x.getContext().startActivity(i);
                }
            });
            v.setOnLongClickListener(x -> {
                if (boundGroup == null) return true;
                toggleSelection(getBindingAdapterPosition());
                return true;
            });
        }
    }

    private static int memberCount(Group g) {
        return g.members == null ? 0 : g.members.size();
    }

    private static long unread(Group g) {
        if (g.unread == null) return 0;
        String uid = FirebaseUtils.getCurrentUid();
        Long count = uid == null ? null : g.unread.get(uid);
        return count == null ? 0 : Math.max(0, count);
    }
}