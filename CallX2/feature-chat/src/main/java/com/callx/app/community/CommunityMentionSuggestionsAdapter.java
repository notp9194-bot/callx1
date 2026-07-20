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

/**
 * v31: @mention autocomplete suggestions adapter shown in the post composer.
 */
public class CommunityMentionSuggestionsAdapter
        extends RecyclerView.Adapter<CommunityMentionSuggestionsAdapter.VH> {

    public interface OnMentionClickListener {
        void onMentionClicked(CommunityMemberEntity member);
    }

    private static final DiffUtil.ItemCallback<CommunityMemberEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityMemberEntity>() {
                @Override public boolean areItemsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return a.uid.equals(b.uid);
                }
                @Override public boolean areContentsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return a.uid.equals(b.uid);
                }
            };

    private final AsyncListDiffer<CommunityMemberEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final OnMentionClickListener clickListener;

    public CommunityMentionSuggestionsAdapter(OnMentionClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setMembers(List<CommunityMemberEntity> list) { submitList(list); }
    public void submitList(List<CommunityMemberEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mention_suggestion, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityMemberEntity m = differ.getCurrentList().get(pos);
        h.tvName.setText("@" + (m.name != null ? m.name : "Member"));
        if (m.photoUrl != null && !m.photoUrl.isEmpty()) {
            Glide.with(h.ivAvatar.getContext()).load(m.photoUrl)
                    .override(96, 96)
                    .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }
        h.itemView.setOnClickListener(v -> { if (clickListener != null) clickListener.onMentionClicked(m); });
    }

    @Override public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try { Glide.with(h.ivAvatar.getContext()).clear(h.ivAvatar); } catch (Exception ignored) {}
    }

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView tvName;
        VH(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_avatar);
            tvName   = v.findViewById(R.id.tv_name);
        }
    }
}
