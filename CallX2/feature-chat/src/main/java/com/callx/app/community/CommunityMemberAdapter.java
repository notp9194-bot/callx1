package com.callx.app.community;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.community.CommunityRole;
import com.callx.app.db.entity.CommunityMemberEntity;

import java.util.Collections;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * CommunityMemberAdapter — RecyclerView adapter for the community member list.
 *
 * Shows avatar, name, role badge (OWNER/ADMIN chip, hidden for plain MEMBER).
 * Long-press on a non-owner member triggers an admin action popup (handled
 * by CommunityMembersFragment which sets the longPressListener).
 *
 * AsyncListDiffer pattern — no notifyDataSetChanged(), diff runs off main thread.
 */
public class CommunityMemberAdapter extends RecyclerView.Adapter<CommunityMemberAdapter.VH> {

    public interface OnMemberLongPressListener {
        void onLongPress(CommunityMemberEntity member);
    }

    // ── DiffUtil ───────────────────────────────────────────────────────────
    private static final DiffUtil.ItemCallback<CommunityMemberEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityMemberEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityMemberEntity a,
                                               @NonNull CommunityMemberEntity b) {
                    return a.uid.equals(b.uid) && a.communityId.equals(b.communityId);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityMemberEntity a,
                                                  @NonNull CommunityMemberEntity b) {
                    return safeEq(a.name, b.name)
                            && safeEq(a.photoUrl, b.photoUrl)
                            && safeEq(a.role, b.role);
                }
                private boolean safeEq(String x, String y) {
                    return x == null ? y == null : x.equals(y);
                }
            };

    private final AsyncListDiffer<CommunityMemberEntity> differ =
            new AsyncListDiffer<>(this, DIFF);

    private String currentUid;
    private OnMemberLongPressListener longPressListener;

    public CommunityMemberAdapter(String currentUid) {
        this.currentUid = currentUid;
        setHasStableIds(true);
    }

    public void submitList(List<CommunityMemberEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    public void setOnMemberLongPressListener(OnMemberLongPressListener l) {
        this.longPressListener = l;
    }

    @Override
    public long getItemId(int position) {
        CommunityMemberEntity m = differ.getCurrentList().get(position);
        return (m.communityId + m.uid).hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_member, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityMemberEntity m = differ.getCurrentList().get(pos);
        Context ctx = h.itemView.getContext();
        boolean isMe = currentUid != null && currentUid.equals(m.uid);

        // Name — append "(You)" for self
        String displayName = (m.name != null ? m.name : "Member");
        h.tvName.setText(isMe ? displayName + " (You)" : displayName);

        // Role badge: OWNER = gold, ADMIN = green, MEMBER = hidden
        if (CommunityRole.OWNER.equals(m.role)) {
            h.tvRoleBadge.setVisibility(View.VISIBLE);
            h.tvRoleBadge.setText("Owner");
            h.tvRoleBadge.setBackgroundResource(R.drawable.chip_selected);
            h.tvRoleBadge.setTextColor(0xFFD4AF37); // brand gold
        } else if (CommunityRole.ADMIN.equals(m.role)) {
            h.tvRoleBadge.setVisibility(View.VISIBLE);
            h.tvRoleBadge.setText("Admin");
            h.tvRoleBadge.setBackgroundResource(R.drawable.chip_selected);
            h.tvRoleBadge.setTextColor(0xFFFFFFFF);
        } else {
            h.tvRoleBadge.setVisibility(View.GONE);
        }

        // Avatar
        if (m.photoUrl != null && !m.photoUrl.isEmpty()) {
            Glide.with(ctx)
                    .load(m.photoUrl)
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        // Long-press → admin action (Fragment handles the popup)
        h.itemView.setOnLongClickListener(v -> {
            if (longPressListener != null) {
                longPressListener.onLongPress(m);
                return true;
            }
            return false;
        });

        // Options button — same as long-press for convenience
        h.btnOptions.setOnClickListener(v -> {
            if (longPressListener != null) {
                longPressListener.onLongPress(m);
            }
        });
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try {
            Glide.with(h.ivAvatar.getContext()).clear(h.ivAvatar);
        } catch (Exception ignored) {}
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView tvName;
        TextView tvRoleBadge;
        ImageView btnOptions;

        VH(@NonNull View itemView) {
            super(itemView);
            ivAvatar   = itemView.findViewById(R.id.iv_avatar);
            tvName     = itemView.findViewById(R.id.tv_member_name);
            tvRoleBadge = itemView.findViewById(R.id.tv_role_badge);
            btnOptions = itemView.findViewById(R.id.btn_member_options);
        }
    }
}
