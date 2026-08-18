package com.callx.app.community;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.group.GroupChatActivity;

import java.util.Collections;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * CommunityGroupAdapter — RecyclerView adapter for the community's linked
 * group list (CommunityGroupsFragment).
 *
 * Uses AsyncListDiffer (background-thread diff) matching GroupAdapter's
 * pattern — no notifyDataSetChanged(), minimal rebinds.
 */
public class CommunityGroupAdapter extends RecyclerView.Adapter<CommunityGroupAdapter.VH> {

    /** Listener for long-press actions (admin remove). */
    public interface OnGroupLongPressListener {
        void onLongPress(GroupEntity group);
    }

    /** v32: Community Access System — tap now routes through an access check before opening. */
    public interface OnGroupClickListener {
        void onGroupClick(GroupEntity group);
    }

    // ── DiffUtil callback ──────────────────────────────────────────────────
    private static final DiffUtil.ItemCallback<GroupEntity> DIFF =
            new DiffUtil.ItemCallback<GroupEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull GroupEntity a, @NonNull GroupEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull GroupEntity a, @NonNull GroupEntity b) {
                    return safeEq(a.name, b.name)
                            && safeEq(a.iconUrl, b.iconUrl)
                            && safeEq(a.lastMessage, b.lastMessage);
                }
                private boolean safeEq(String x, String y) {
                    return x == null ? y == null : x.equals(y);
                }
            };

    private final AsyncListDiffer<GroupEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private OnGroupLongPressListener longPressListener;
    private OnGroupClickListener clickListener;

    public CommunityGroupAdapter() {
        setHasStableIds(true);
    }

    public void submitList(List<GroupEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    public void setOnGroupLongPressListener(OnGroupLongPressListener l) {
        this.longPressListener = l;
    }

    public void setOnGroupClickListener(OnGroupClickListener l) {
        this.clickListener = l;
    }

    @Override
    public long getItemId(int position) {
        GroupEntity g = differ.getCurrentList().get(position);
        return g.id.hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_group, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        GroupEntity g = differ.getCurrentList().get(pos);
        Context ctx = h.itemView.getContext();

        h.tvGroupName.setText(g.name != null ? g.name : "Group");

        // Last message preview
        String preview = g.lastMessage != null ? g.lastMessage : "Group ready";
        h.tvLastMessage.setText(preview);

        // Avatar
        if (g.iconUrl != null && !g.iconUrl.isEmpty()) {
            Glide.with(ctx)
                    .load(g.iconUrl)
                    .circleCrop()
                    .placeholder(R.drawable.ic_group)
                    .error(R.drawable.ic_group)
                    .override(96, 96)
                    .into(h.ivGroupIcon);
            h.ivGroupIcon.setPadding(0, 0, 0, 0);
        } else {
            h.ivGroupIcon.setImageResource(R.drawable.ic_group);
            int pad = (int) (ctx.getResources().getDisplayMetrics().density * 12);
            h.ivGroupIcon.setPadding(pad, pad, pad, pad);
        }

        // Tap → v32: run through the Community Access System gate first
        // (community membership → group membership → OPEN auto-join /
        // ADMIN_ONLY ask-to-join), then open GroupChatActivity.
        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onGroupClick(g);
                return;
            }
            Intent i = new Intent(ctx, GroupChatActivity.class);
            i.putExtra("groupId", g.id);
            i.putExtra("groupName", g.name);
            ctx.startActivity(i);
        });

        // Long-press → admin remove flow (handled by Fragment)
        h.itemView.setOnLongClickListener(v -> {
            if (longPressListener != null) {
                longPressListener.onLongPress(g);
                return true;
            }
            return false;
        });
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try {
            Glide.with(h.ivGroupIcon.getContext()).clear(h.ivGroupIcon);
        } catch (Exception ignored) {}
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivGroupIcon;
        TextView tvGroupName;
        TextView tvLastMessage;

        VH(@NonNull View itemView) {
            super(itemView);
            ivGroupIcon   = itemView.findViewById(R.id.iv_group_icon);
            tvGroupName   = itemView.findViewById(R.id.tv_group_name);
            tvLastMessage = itemView.findViewById(R.id.tv_group_last_message);
        }
    }
}
