package com.callx.app.community;

import android.graphics.Color;
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
import com.callx.app.db.entity.CommunityMemberEntity;

import java.util.Collections;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * v31: Member list adapter — shows role badges + member badges (emoji + color).
 * Muted members shown with reduced opacity. Banned members filtered out upstream.
 */
public class CommunityMemberAdapter extends RecyclerView.Adapter<CommunityMemberAdapter.VH> {

    public interface OnMemberLongPressListener {
        void onLongPress(CommunityMemberEntity member);
    }

    private static final DiffUtil.ItemCallback<CommunityMemberEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityMemberEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return a.uid.equals(b.uid);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return safeEq(a.role, b.role) && safeEq(a.badge, b.badge)
                            && a.isMuted == b.isMuted;
                }
                private boolean safeEq(String x, String y) {
                    return x == null ? y == null : x.equals(y);
                }
            };

    private final AsyncListDiffer<CommunityMemberEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final String currentUid;
    private String myRole = CommunityRole.MEMBER;
    private OnMemberLongPressListener longPressListener;

    public CommunityMemberAdapter(String currentUid) {
        this.currentUid = currentUid;
    }

    public void setMyRole(String role) {
        this.myRole = role != null ? role : CommunityRole.MEMBER;
    }

    public void setOnMemberLongPressListener(OnMemberLongPressListener l) {
        this.longPressListener = l;
    }

    public void submitList(List<CommunityMemberEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
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

        // Name — include badge emoji if any
        String badgeEmoji = CommunityBadge.getEmojiIcon(m.badge);
        String displayName = m.name != null ? m.name : "Member";
        if (!badgeEmoji.isEmpty()) displayName = badgeEmoji + " " + displayName;
        h.tvName.setText(displayName);

        // Role badge chip
        boolean showRole = CommunityRole.OWNER.equals(m.role) || CommunityRole.ADMIN.equals(m.role);
        if (showRole) {
            h.tvRoleBadge.setVisibility(View.VISIBLE);
            h.tvRoleBadge.setText(CommunityRole.OWNER.equals(m.role) ? "Owner" : "Admin");
        } else {
            h.tvRoleBadge.setVisibility(View.GONE);
        }

        // Member badge chip (shown in addition to role badge)
        if (!CommunityBadge.isNone(m.badge) && h.tvMemberBadge != null) {
            h.tvMemberBadge.setVisibility(View.VISIBLE);
            h.tvMemberBadge.setText(CommunityBadge.getDisplayName(m.badge));
            try {
                h.tvMemberBadge.setTextColor(Color.parseColor(CommunityBadge.getBadgeColor(m.badge)));
            } catch (Exception ignored) {}
        } else if (h.tvMemberBadge != null) {
            h.tvMemberBadge.setVisibility(View.GONE);
        }

        // Muted indicator
        h.itemView.setAlpha(m.isMuted ? 0.6f : 1.0f);
        if (h.tvMutedBadge != null) {
            h.tvMutedBadge.setVisibility(m.isMuted ? View.VISIBLE : View.GONE);
        }

        // Avatar
        if (m.photoUrl != null && !m.photoUrl.isEmpty()) {
            Glide.with(h.ivAvatar.getContext()).load(m.photoUrl)
                    .override(96, 96)
                    .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        // Options button
        boolean isAdminOrOwner = CommunityRole.isAdminOrOwner(myRole);
        boolean isSelf = currentUid != null && currentUid.equals(m.uid);
        h.btnOptions.setVisibility(isAdminOrOwner && !isSelf ? View.VISIBLE : View.GONE);

        h.itemView.setOnLongClickListener(v -> {
            if (longPressListener != null && isAdminOrOwner && !isSelf) {
                longPressListener.onLongPress(m);
                return true;
            }
            return false;
        });
        h.btnOptions.setOnClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(m);
        });
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try { Glide.with(h.ivAvatar.getContext()).clear(h.ivAvatar); } catch (Exception ignored) {}
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView tvName, tvRoleBadge;
        TextView tvMemberBadge;  // may be null if layout doesn't have it
        TextView tvMutedBadge;   // may be null
        ImageView btnOptions;

        VH(@NonNull View itemView) {
            super(itemView);
            ivAvatar      = itemView.findViewById(R.id.iv_avatar);
            tvName        = itemView.findViewById(R.id.tv_member_name);
            tvRoleBadge   = itemView.findViewById(R.id.tv_role_badge);
            tvMemberBadge = itemView.findViewById(R.id.tv_member_badge);
            tvMutedBadge  = itemView.findViewById(R.id.tv_muted_badge);
            btnOptions    = itemView.findViewById(R.id.btn_member_options);
        }
    }
}
