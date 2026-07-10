package com.callx.app.group;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;
import com.callx.app.chatlist.canvas.ChatListLastMessageView;
import com.callx.app.chatlist.canvas.ChatListNameTimeView;
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
import java.util.Collections;
import java.util.List;

/**
 * GroupAdapter v83
 *
 * CHANGES v83 — AsyncListDiffer (background-thread diff):
 *  • DiffUtil.ItemCallback<Group> DIFF_CALLBACK defined as static constant —
 *    areItemsTheSame() compares group ID; areContentsTheSame() compares every
 *    field the row renders (name, icon, lastMessage, status, senderUid, time,
 *    lastMessageType) so only actually-changed rows rebind.
 *  • Internal list owned by AsyncListDiffer<Group>; submitList() is the sole
 *    write entry-point; diff runs on a background thread (no main-thread block).
 *  • GroupsFragment calls adapter.submitList(fetched) — diffUpdateGroups()
 *    logic removed from the fragment.
 *
 * CHANGES v82 — Canvas row (CardView → FrameLayout; tv_group_name + tv_group_members
 *   → ChatListNameTimeView; ChatListLastMessageView unchanged).
 *
 * CHANGES v24 — Canvas ticks + live typing indicator per row.
 */
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {

    // ── v83: DiffUtil.ItemCallback ────────────────────────────────────────────
    public static final DiffUtil.ItemCallback<Group> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Group>() {

        @Override
        public boolean areItemsTheSame(@NonNull Group a, @NonNull Group b) {
            return a.id != null && a.id.equals(b.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Group a, @NonNull Group b) {
            return safeEq(a.name, b.name)
                && safeEq(a.iconUrl, b.iconUrl)
                && safeEq(a.lastMessage, b.lastMessage)
                && safeEq(a.lastSenderName, b.lastSenderName)
                && safeEq(a.lastMessageType, b.lastMessageType)
                && safeEq(a.lastMessageStatus, b.lastMessageStatus)
                && safeEq(a.lastMessageSenderUid, b.lastMessageSenderUid)
                && longEq(a.lastMessageAt, b.lastMessageAt)
                && memberCountEq(a, b);
        }

        private boolean safeEq(String x, String y) {
            return x == null ? y == null : x.equals(y);
        }
        private boolean longEq(Long x, Long y) {
            return x == null ? y == null : x.equals(y);
        }
        private boolean memberCountEq(Group a, Group b) {
            int ca = a.members != null ? a.members.size() : 0;
            int cb = b.members != null ? b.members.size() : 0;
            return ca == cb;
        }
    };

    // ── v83: AsyncListDiffer ──────────────────────────────────────────────────
    private final AsyncListDiffer<Group> differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);

    /**
     * Submit a new list. Diff runs on a background thread; only changed rows
     * rebind. Pass null or empty list to clear.
     */
    public void submitList(List<Group> newList) {
        differ.submitList(newList == null ? Collections.emptyList() : newList);
    }

    /** Current snapshot — safe to read on the main thread. */
    public List<Group> getCurrentList() {
        return differ.getCurrentList();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final String myUid = FirebaseAuth.getInstance().getUid();

    public GroupAdapter() {
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        List<Group> list = differ.getCurrentList();
        if (position < 0 || position >= list.size()) return RecyclerView.NO_ID;
        String id = list.get(position).id;
        return id != null ? id.hashCode() : position;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        List<Group> list = differ.getCurrentList();
        Group g = list.get(pos);
        Context ctx = h.itemView.getContext();

        // v82: ChatListNameTimeView — name left, members count right
        h.nameMembersView.setName(g.name == null ? "Group" : g.name);
        int n = g.members != null ? g.members.size() : 0;
        h.nameMembersView.setTime(n + " members");

        // v85: Avatar — RGB_565 + exact size override + RESOURCE disk cache
        if (h.ivAvatar != null) {
            if (g.iconUrl != null && !g.iconUrl.isEmpty()) {
                int px = Math.round(50f * ctx.getResources().getDisplayMetrics().density);
                Glide.with(ctx)
                        .load(g.iconUrl)
                        .dontAnimate()
                        .override(px, px)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_group)
                        .error(R.drawable.ic_group)
                        .into(h.ivAvatar);
                h.ivAvatar.setPadding(0, 0, 0, 0);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_group);
                int pad = (int) (ctx.getResources().getDisplayMetrics().density * 12);
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
        String preview = ChatListPreviewUtil.buildPreview(
                g.lastMessageType, g.lastMessage, "Group ready");
        h.lastMessageView.setMessageText(
                preview, ctx.getResources().getColor(R.color.text_secondary), false);
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
                int adapterPos = h.getAdapterPosition();
                if (adapterPos == RecyclerView.NO_POSITION) return;
                List<Group> current = differ.getCurrentList();
                if (adapterPos >= current.size()) return;
                if (!g.id.equals(current.get(adapterPos).id)) return;

                List<String> names = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    if (myUid != null && myUid.equals(child.getKey())) continue;
                    String name = child.getValue(String.class);
                    names.add(name != null && !name.isEmpty() ? name : "Someone");
                }
                applyTypingRow(h, current.get(adapterPos), names);
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
            if (n == 1)      label = typingNames.get(0) + " typing...";
            else if (n == 2) label = typingNames.get(0) + ", " + typingNames.get(1) + " typing...";
            else             label = typingNames.get(0) + " +" + (n - 1) + " typing...";
            h.lastMessageView.setMessageText(label, 0xFF0F4C3A, true);
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_NONE, 0);
        } else {
            bindGroupRowContent(h, g);
        }
    }

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        detachTypingListener(h);
    }

    static class VH extends RecyclerView.ViewHolder {
        ChatListNameTimeView    nameMembersView;
        ChatListLastMessageView lastMessageView;
        CircleImageView         ivAvatar;
        DatabaseReference       typingRef;
        ValueEventListener      typingListener;
        boolean                 isTypingNow = false;

        VH(View v) {
            super(v);
            nameMembersView = v.findViewById(R.id.view_group_name_members);
            lastMessageView = v.findViewById(R.id.view_group_last_message);
            ivAvatar        = v.findViewById(R.id.iv_group_avatar);
        }
    }
}
