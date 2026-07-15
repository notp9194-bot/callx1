package com.callx.app.community;

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
import com.callx.app.db.entity.CommunityMemberEntity;

import java.util.Collections;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/** v31: Adapter for member search results. */
public class CommunityMemberSearchAdapter
        extends RecyclerView.Adapter<CommunityMemberSearchAdapter.VH> {

    private static final DiffUtil.ItemCallback<CommunityMemberEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityMemberEntity>() {
                @Override public boolean areItemsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return a.uid.equals(b.uid);
                }
                @Override public boolean areContentsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return a.uid.equals(b.uid) && a.role != null && a.role.equals(b.role);
                }
            };

    private final AsyncListDiffer<CommunityMemberEntity> differ = new AsyncListDiffer<>(this, DIFF);

    public void submitList(List<CommunityMemberEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_search_result_member, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityMemberEntity m = differ.getCurrentList().get(pos);
        h.tvName.setText(m.name != null ? m.name : "Member");
        String roleLabel = CommunityRole.OWNER.equals(m.role) ? "Owner"
                : CommunityRole.ADMIN.equals(m.role) ? "Admin" : "Member";
        h.tvRole.setText(roleLabel);
        if (m.photoUrl != null && !m.photoUrl.isEmpty()) {
            Glide.with(h.ivAvatar.getContext()).load(m.photoUrl)
                    .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }
    }

    @Override public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try { Glide.with(h.ivAvatar.getContext()).clear(h.ivAvatar); } catch (Exception ignored) {}
    }

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView tvName, tvRole;
        VH(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_avatar);
            tvName   = v.findViewById(R.id.tv_member_name);
            tvRole   = v.findViewById(R.id.tv_member_role);
        }
    }
}
