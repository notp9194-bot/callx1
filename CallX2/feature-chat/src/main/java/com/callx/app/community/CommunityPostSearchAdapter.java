package com.callx.app.community;

import android.text.format.DateUtils;
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
import com.callx.app.db.entity.CommunityPostEntity;

import java.util.Collections;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/** v31: Adapter for post search results. */
public class CommunityPostSearchAdapter
        extends RecyclerView.Adapter<CommunityPostSearchAdapter.VH> {

    private static final DiffUtil.ItemCallback<CommunityPostEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityPostEntity>() {
                @Override public boolean areItemsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return a.id.equals(b.id);
                }
                @Override public boolean areContentsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return a.id.equals(b.id);
                }
            };

    private final AsyncListDiffer<CommunityPostEntity> differ = new AsyncListDiffer<>(this, DIFF);

    public void submitList(List<CommunityPostEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_search_result_post, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityPostEntity p = differ.getCurrentList().get(pos);
        h.tvAuthor.setText(p.authorName != null ? p.authorName : "Member");
        String snippet = p.text != null ? p.text : (p.mediaUrl != null ? "📷 Media post" : "");
        h.tvSnippet.setText(snippet);
        h.tvTime.setText(p.createdAt > 0
                ? DateUtils.getRelativeTimeSpanString(p.createdAt,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) : "");
        if (p.authorPhoto != null && !p.authorPhoto.isEmpty()) {
            Glide.with(h.ivAvatar.getContext()).load(p.authorPhoto)
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
        TextView tvAuthor, tvSnippet, tvTime;
        VH(@NonNull View v) {
            super(v);
            ivAvatar  = v.findViewById(R.id.iv_author_avatar);
            tvAuthor  = v.findViewById(R.id.tv_result_author);
            tvSnippet = v.findViewById(R.id.tv_result_snippet);
            tvTime    = v.findViewById(R.id.tv_result_time);
        }
    }
}
