package com.callx.app.messages;

import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.x.R;
import java.util.*;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * XGroupPreviewAdapter — RecyclerView adapter for the group DMs section
 * of XMessagesFragment.
 *
 * Each row shows:
 *   - Group icon (or a default group icon placeholder)
 *   - Group name
 *   - "{senderName}: {lastMessage}" preview
 *   - Relative timestamp
 *   - Unread dot indicator
 *   - Member count badge
 *
 * Tapping a row opens XGroupDMConversationActivity.
 */
public class XGroupPreviewAdapter extends RecyclerView.Adapter<XGroupPreviewAdapter.VH> {

    /** Data model for a single group DM preview row. */
    public static class GroupPreview {
        public String  groupId;
        public String  name;
        public String  iconUrl;
        public String  lastMessage;
        public String  lastSenderName;
        public long    lastTs;
        public boolean unread;
        public int     memberCount;
    }

    private final Context          ctx;
    private final List<GroupPreview> items = new ArrayList<>();

    public XGroupPreviewAdapter(Context ctx) {
        this.ctx = ctx;
        setHasStableIds(true);
    }

    @Override public long getItemId(int pos) {
        String id = items.get(pos).groupId;
        return id != null ? id.hashCode() : pos;
    }

    public void setItems(List<GroupPreview> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_x_group_preview, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(items.get(pos)); }
    @Override public int getItemCount() { return items.size(); }

    class VH extends RecyclerView.ViewHolder {
        CircleImageView ivIcon;
        TextView tvName, tvLastMsg, tvTime, tvMemberCount;
        View     vUnreadDot;

        VH(View v) {
            super(v);
            ivIcon       = v.findViewById(R.id.iv_group_preview_icon);
            tvName       = v.findViewById(R.id.tv_group_preview_name);
            tvLastMsg    = v.findViewById(R.id.tv_group_preview_last_msg);
            tvTime       = v.findViewById(R.id.tv_group_preview_time);
            tvMemberCount= v.findViewById(R.id.tv_group_preview_member_count);
            vUnreadDot   = v.findViewById(R.id.v_group_preview_unread);
        }

        void bind(GroupPreview g) {
            tvName.setText(g.name != null ? g.name : "Group");

            // Last message preview: "Name: text"
            if (g.lastMessage != null) {
                String preview = g.lastSenderName != null
                        ? g.lastSenderName.split(" ")[0] + ": " + g.lastMessage
                        : g.lastMessage;
                tvLastMsg.setText(preview);
            } else {
                tvLastMsg.setText("No messages yet");
            }

            // Time
            if (tvTime != null && g.lastTs > 0) {
                tvTime.setText(DateUtils.getRelativeTimeSpanString(
                        g.lastTs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE));
            }

            // Member count
            if (tvMemberCount != null) {
                tvMemberCount.setText(g.memberCount + " members");
            }

            // Unread
            if (vUnreadDot != null)
                vUnreadDot.setVisibility(g.unread ? View.VISIBLE : View.GONE);

            // Icon
            if (ivIcon != null) {
                if (g.iconUrl != null && !g.iconUrl.isEmpty()) {
                    Glide.with(ctx).load(g.iconUrl).circleCrop()
                            .placeholder(R.drawable.ic_group).into(ivIcon);
                } else {
                    ivIcon.setImageResource(R.drawable.ic_group);
                }
            }

            // Navigate to group conversation
            itemView.setOnClickListener(v ->
                    ctx.startActivity(new Intent(ctx, XGroupDMConversationActivity.class)
                            .putExtra("group_id",    g.groupId)
                            .putExtra("group_name",  g.name)
                            .putExtra("group_photo", g.iconUrl)));
        }
    }
}
