package com.callx.app.group;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;
import com.callx.app.chatlist.canvas.ChatListLastMessageView;
import com.callx.app.chatlist.canvas.ChatListNameTimeView;
import com.callx.app.group.GroupChatActivity;
import com.callx.app.models.Group;
import com.callx.app.utils.ChatListPreviewUtil;
import com.callx.app.utils.FirebaseUtils;
import de.hdodenhof.circleimageview.CircleImageView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * GroupAdapter v82
 *
 * CHANGES v82 — Canvas row parity with ChatListAdapter:
 *  1. CardView root → plain FrameLayout with bg_chat_row (removes CardView
 *     measure/layout overhead).
 *  2. tv_group_name (TextView) + tv_group_members (TextView) → single
 *     ChatListNameTimeView that draws group name bold-left and member count
 *     muted-right — same savings as the 1:1 chat list gained in v82.
 *  3. ChatListLastMessageView — UNCHANGED (already canvas since v24).
 *  4. iv_group_avatar (CircleImageView) — UNCHANGED; Glide pipeline kept.
 *
 * CHANGES v24 — Canvas ticks + live typing (group-list parity with
 * ChatListAdapter):
 *  • Read-receipt ticks (aggregate: delivered = all got it, read = all saw it).
 *  • Live "typing..." indicator per row, attached/detached with bind/recycle.
 */
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
    private final List<Group> groups;
    private final String myUid = FirebaseAuth.getInstance().getUid();

    public GroupAdapter(List<Group> groups) {
        this.groups = groups;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= groups.size()) return RecyclerView.NO_ID;
        String id = groups.get(position).id;
        return id != null ? id.hashCode() : position;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_group, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Group g = groups.get(pos);
        Context ctx = h.itemView.getContext();

        // v82: name drawn by ChatListNameTimeView (left = name, right = members)
        h.nameMembersView.setName(g.name == null ? "Group" : g.name);
        int n = g.members != null ? g.members.size() : 0;
        h.nameMembersView.setTime(n + " members");

        // Avatar via Glide — unchanged
        if (h.ivAvatar != null) {
            if (g.iconUrl != null && !g.iconUrl.isEmpty()) {
                Glide.with(ctx)
                    .load(g.iconUrl)
                    .dontAnimate()
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_group)
                    .error(R.drawable.ic_group)
                    .into(h.ivAvatar);
                h.ivAvatar.setPadding(0, 0, 0, 0);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_group);
                int pad = (int)(ctx.getResources().getDisplayMetrics().density * 12);
                h.ivAvatar.setPadding(pad, pad, pad, pad);
            }
        }

        h.isTypingNow = false;
        bindGroupRowContent(h, g);
        attachTypingListener(h, g);

        h.itemView.setOnClickListener(v -> {
            if (g.id == null || g.id.isEmpty()) return;
            Intent i = new Intent(ctx, GroupChatActivity.class);
            i.putExtra("groupId", g.id);
            i.putExtra("groupName", g.name);
            ctx.startActivity(i);
        });
    }

    private void bindGroupRowContent(VH h, Group g) {
        Context ctx = h.itemView.getContext();
        String preview = ChatListPreviewUtil.buildPreview(g.lastMessageType, g.lastMessage, "Group ready");
        h.lastMessageView.setMessageText(preview, ctx.getResources().getColor(R.color.text_secondary), false);
        updateReadStatusTicks(h, g);
    }

    private void updateReadStatusTicks(VH h, Group g) {
        boolean iAmLastSender = myUid != null && g.id != null
                && myUid.equals(g.lastMessageSenderUid);
        if (h.isTypingNow || !iAmLastSender || g.lastMessageStatus == null) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_NONE, 0);
            return;
        }
        Context ctx = h.itemView.getContext();
        if ("read".equals(g.lastMessageStatus)) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_READ,
                    ctx.getResources().getColor(R.color.tick_read_blue));
        } else if ("delivered".equals(g.lastMessageStatus)) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_DELIVERED,
                    ctx.getResources().getColor(R.color.text_muted));
        } else {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_SENT,
                    ctx.getResources().getColor(R.color.text_muted));
        }
    }

    private void attachTypingListener(VH h, Group g) {
        detachTypingListener(h);
        if (g.id == null) return;
        DatabaseReference ref = FirebaseUtils.getGroupTypingRef(g.id);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= groups.size()) return;
                Group current = groups.get(pos);
                if (current.id == null || !current.id.equals(g.id)) return;

                List<String> names = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    if (myUid != null && myUid.equals(child.getKey())) continue;
                    String name = child.getValue(String.class);
                    names.add(name != null && !name.isEmpty() ? name : "Someone");
                }
                applyTypingRow(h, current, names);
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

    private void applyTypingRow(VH h, Group g, List<String> typingNames) {
        boolean isTyping = !typingNames.isEmpty();
        h.isTypingNow = isTyping;
        if (isTyping) {
            String label;
            int n = typingNames.size();
            if (n == 1) {
                label = typingNames.get(0) + " typing...";
            } else if (n == 2) {
                label = typingNames.get(0) + ", " + typingNames.get(1) + " typing...";
            } else {
                label = typingNames.get(0) + " +" + (n - 1) + " typing...";
            }
            h.lastMessageView.setMessageText(label, 0xFF0F4C3A, true);
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_NONE, 0);
        } else {
            bindGroupRowContent(h, g);
        }
    }

    @Override public int getItemCount() { return groups.size(); }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        detachTypingListener(h);
    }

    static class VH extends RecyclerView.ViewHolder {
        // v82: name+members in one canvas view
        ChatListNameTimeView  nameMembersView;
        // v24: last-message + ticks — canvas
        ChatListLastMessageView lastMessageView;
        // unchanged
        CircleImageView ivAvatar;
        // typing listener
        DatabaseReference typingRef;
        ValueEventListener typingListener;
        boolean isTypingNow = false;

        VH(View v) {
            super(v);
            nameMembersView = v.findViewById(R.id.view_group_name_members);
            lastMessageView = v.findViewById(R.id.view_group_last_message);
            ivAvatar        = v.findViewById(R.id.iv_group_avatar);
        }
    }
}
