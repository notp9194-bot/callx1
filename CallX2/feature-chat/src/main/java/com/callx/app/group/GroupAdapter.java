package com.callx.app.group;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;
import com.callx.app.chatlist.canvas.ChatListLastMessageView;
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
 * GroupAdapter v24 — Canvas ticks + live typing (group-list parity with
 * ChatListAdapter):
 *
 *  1. Read-receipt ticks (✓ sent / ✓✓ delivered / blue ✓✓ read) — group
 *     analogue of ChatListAdapter's ticks. lastMessageStatus here is the
 *     AGGREGATE status GroupMessageStatusSync computes (delivered = every
 *     other member has it, read = every other member has seen it), so a
 *     blue double-tick means "everyone's read it", matching WhatsApp group
 *     semantics. Only shown when the current user sent the group's last
 *     message.
 *  2. Live "typing..." indicator — mirrors groups/{groupId}/typing/{uid}
 *     (uid → display name), attached/detached with the row's bind/recycle
 *     lifecycle so it never leaks a listener while scrolling. Multiple
 *     simultaneous typers are summarized the same way the in-chat typing
 *     strip does: "Alice typing...", "Alice, Bob typing...", or
 *     "Alice +2 typing..." for 3+.
 *  3. Both of the above are rendered by ChatListLastMessageView — the same
 *     Canvas-based view the 1:1 chat list uses — instead of a plain
 *     TextView, so a group row pays the same reduced per-bind cost.
 */
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
    private final List<Group> groups;
    private final String myUid = FirebaseAuth.getInstance().getUid();

    public GroupAdapter(List<Group> groups) {
        this.groups = groups;
        // PERF: stable IDs — same fix as ChatListAdapter — lets DiffUtil-driven
        // updates (see GroupsFragment#diffUpdateGroups) avoid unnecessary rebinds
        // for rows whose position didn't change.
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
        h.tvName.setText(g.name == null ? "Group" : g.name);
        int n = g.members != null ? g.members.size() : 0;
        h.tvMembers.setText(n + " members");

        // Load group avatar if available, else show default icon
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

        // v24: reset any stale "typing..." flag left over from a recycled
        // row — bindGroupRowContent() below always re-drives text/ticks from
        // this row's real data (mirrors ChatListAdapter's onBindViewHolder).
        h.isTypingNow = false;
        bindGroupRowContent(h, g);

        // v24: live "typing..." indicator — attached/detached with this
        // row's bind/recycle lifecycle, same pattern as ChatListAdapter's
        // attachTypingListener/onViewRecycled, so it never leaks a listener
        // onto a row that's since scrolled away and been rebound.
        attachTypingListener(h, g);

        h.itemView.setOnClickListener(v -> {
            if (g.id == null || g.id.isEmpty()) return;
            Intent i = new Intent(ctx, GroupChatActivity.class);
            i.putExtra("groupId", g.id);
            i.putExtra("groupName", g.name);
            ctx.startActivity(i);
        });
    }

    // Everything that renders when this row is NOT showing a live
    // "typing..." state — last-message text + ticks. Split out so
    // applyTypingRow(false) can redraw just this part once typing stops,
    // same shape as ChatListAdapter#applySelectionVisuals.
    private void bindGroupRowContent(VH h, Group g) {
        Context ctx = h.itemView.getContext();
        String preview = ChatListPreviewUtil.buildPreview(g.lastMessageType, g.lastMessage, "Group ready");
        h.lastMessageView.setMessageText(preview, ctx.getResources().getColor(R.color.text_secondary), false);
        updateReadStatusTicks(h, g);
    }

    // ── v24: Read-status ticks ───────────────────────────────────────────
    // ✓ (sent, grey) / ✓✓ (delivered, grey) / ✓✓ (read, blue) — only shown
    // when the CURRENT USER sent the group's last message, same rule as 1:1.
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
            // "sent" (or any other/unknown non-null status)
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_SENT,
                    ctx.getResources().getColor(R.color.text_muted));
        }
    }

    // ── v24: Live "typing..." indicator ──────────────────────────────────
    // Mirrors groups/{groupId}/typing/{uid} = displayName (the same node
    // GroupChatActivity's own in-screen typing strip reads) — here we watch
    // the whole node per-row so the GROUP LIST can also show "typing..." in
    // place of the last message while one or more members are composing.
    //
    // PERF: attach/detach is tied to this row's bind/recycle lifecycle,
    // exactly like ChatListAdapter's attachTypingListener/onViewRecycled —
    // it only ever listens for rows currently on/near screen, and is
    // detached the instant a row is recycled, so scrolling never
    // accumulates orphaned listeners.
    private void attachTypingListener(VH h, Group g) {
        detachTypingListener(h);
        if (g.id == null) return;
        DatabaseReference ref = FirebaseUtils.getGroupTypingRef(g.id);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= groups.size()) return;
                Group current = groups.get(pos);
                // Row may have been recycled to a different group while this
                // listener's callback was in flight — verify first.
                if (current.id == null || !current.id.equals(g.id)) return;

                List<String> names = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    if (myUid != null && myUid.equals(child.getKey())) continue; // skip my own typing echo
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
            h.lastMessageView.setMessageText(label, 0xFF0F4C3A, true); // brand_primary, italic — matches typing strip in-chat
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_NONE, 0);
        } else {
            // Typing stopped — redraw this row's normal state (last message
            // text + ticks) without touching avatar/member-count/click listener.
            bindGroupRowContent(h, g);
        }
    }

    @Override public int getItemCount() { return groups.size(); }

    // v24: detach the per-row typing listener the instant a row leaves the
    // screen and goes back into the recycled pool — mirrors ChatListAdapter's
    // onViewRecycled so scrolling never leaks listeners.
    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        detachTypingListener(h);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvMembers;
        CircleImageView ivAvatar;

        // v24: last-message text + read-receipt ticks + typing indicator,
        // Canvas-rendered — replaces the old tv_group_last TextView (see
        // ChatListLastMessageView for why).
        ChatListLastMessageView lastMessageView;

        // v24: live typing indicator — listener ref for detach-on-recycle,
        // plus a flag so bindGroupRowContent() knows not to clobber the
        // "typing..." text mid-payload-bind (mirrors ChatListAdapter.VH).
        DatabaseReference typingRef;
        ValueEventListener typingListener;
        boolean isTypingNow = false;

        VH(View v) {
            super(v);
            tvName          = v.findViewById(R.id.tv_group_name);
            lastMessageView = v.findViewById(R.id.view_group_last_message);
            tvMembers       = v.findViewById(R.id.tv_group_members);
            ivAvatar        = v.findViewById(R.id.iv_group_avatar);
        }
    }
}
