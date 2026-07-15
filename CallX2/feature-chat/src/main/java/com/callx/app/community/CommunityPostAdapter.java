package com.callx.app.community;

import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
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
import com.callx.app.db.entity.CommunityPostEntity;

import java.util.Collections;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * CommunityPostAdapter — shared RecyclerView adapter for both
 * CommunityFeedFragment and CommunityAnnouncementsFragment (a post is
 * either kind, this adapter just renders whatever list it's given).
 *
 * Renders: author row, text, optional media image, optional
 * CommunityPollView (Canvas bar-chart, tap-to-vote), like/comment actions.
 * AsyncListDiffer — matches MessagePagingAdapter/GroupAdapter's
 * background-thread-diff convention, no notifyDataSetChanged().
 */
public class CommunityPostAdapter extends RecyclerView.Adapter<CommunityPostAdapter.VH> {

    public interface Listener {
        void onLikeClicked(CommunityPostEntity post);
        void onCommentClicked(CommunityPostEntity post);
        void onVote(CommunityPostEntity post, int optionIndex);
        /** Returns which option (if any) the current user already voted for this post's poll. */
        Integer myVoteFor(CommunityPostEntity post);
        /** Whether the current user has already liked this post — adapter has no local like-state cache. */
        boolean isLikedByMe(CommunityPostEntity post);
    }

    private static final DiffUtil.ItemCallback<CommunityPostEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityPostEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return a.likeCount == b.likeCount && a.commentCount == b.commentCount
                            && a.pinned == b.pinned && safeEq(a.pollJson, b.pollJson)
                            && safeEq(a.text, b.text);
                }
                private boolean safeEq(String x, String y) {
                    return x == null ? y == null : x.equals(y);
                }
            };

    private final AsyncListDiffer<CommunityPostEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;

    public CommunityPostAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submitList(List<CommunityPostEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @Override
    public long getItemId(int position) {
        return differ.getCurrentList().get(position).id.hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_post, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityPostEntity p = differ.getCurrentList().get(pos);
        Context ctx = h.itemView.getContext();

        h.tvPinnedBadge.setVisibility(p.pinned ? View.VISIBLE : View.GONE);
        h.tvAuthorName.setText(p.authorName != null ? p.authorName : "Member");
        h.tvTimestamp.setText(p.createdAt > 0
                ? DateUtils.getRelativeTimeSpanString(p.createdAt) : "");

        if (p.authorPhoto != null && !p.authorPhoto.isEmpty()) {
            Glide.with(ctx).load(p.authorPhoto).circleCrop()
                    .placeholder(R.drawable.ic_person).into(h.ivAuthorAvatar);
        } else {
            h.ivAuthorAvatar.setImageResource(R.drawable.ic_person);
        }

        // Text
        if (p.text != null && !p.text.isEmpty()) {
            h.tvPostText.setText(p.text);
            h.tvPostText.setVisibility(View.VISIBLE);
        } else {
            h.tvPostText.setVisibility(View.GONE);
        }

        // Media
        if (p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
            h.containerMedia.setVisibility(View.VISIBLE);
            Glide.with(ctx).load(p.mediaUrl).into(h.ivMedia);
            h.ivPlayOverlay.setVisibility("video".equals(p.mediaType) ? View.VISIBLE : View.GONE);
        } else {
            h.containerMedia.setVisibility(View.GONE);
        }

        // Poll
        CommunityPoll poll = CommunityPoll.fromJson(p.pollJson);
        if (poll != null) {
            h.viewPoll.setVisibility(View.VISIBLE);
            Integer myVote = listener != null ? listener.myVoteFor(p) : null;
            h.viewPoll.bind(poll, myVote, optionIndex -> {
                if (listener != null) listener.onVote(p, optionIndex);
            });
        } else {
            h.viewPoll.setVisibility(View.GONE);
        }

        // Like / comment counts
        h.tvLikeCount.setText(formatCount(p.likeCount));
        h.tvCommentCount.setText(formatCount(p.commentCount));
        // No separate "filled" star drawable exists — reuse ic_star_outline and
        // just swap the tint (gold when liked) to avoid adding a new asset.
        boolean likedByMe = listener != null && listener.isLikedByMe(p);
        h.ivLikeIcon.setColorFilter(likedByMe ? 0xFFD4AF37 : 0xFF64748B);

        h.btnLike.setOnClickListener(v -> { if (listener != null) listener.onLikeClicked(p); });
        h.btnComment.setOnClickListener(v -> { if (listener != null) listener.onCommentClicked(p); });
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try {
            Glide.with(h.ivAuthorAvatar.getContext()).clear(h.ivAuthorAvatar);
            Glide.with(h.ivMedia.getContext()).clear(h.ivMedia);
        } catch (Exception ignored) {}
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    private static String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvPinnedBadge, tvAuthorName, tvTimestamp, tvPostText, tvLikeCount, tvCommentCount;
        CircleImageView ivAuthorAvatar;
        ViewGroup containerMedia;
        ImageView ivMedia, ivPlayOverlay, ivLikeIcon;
        CommunityPollView viewPoll;
        View btnLike, btnComment;

        VH(@NonNull View itemView) {
            super(itemView);
            tvPinnedBadge   = itemView.findViewById(R.id.tv_pinned_badge);
            ivAuthorAvatar  = itemView.findViewById(R.id.iv_author_avatar);
            tvAuthorName    = itemView.findViewById(R.id.tv_author_name);
            tvTimestamp     = itemView.findViewById(R.id.tv_timestamp);
            tvPostText      = itemView.findViewById(R.id.tv_post_text);
            containerMedia  = itemView.findViewById(R.id.container_media);
            ivMedia         = itemView.findViewById(R.id.iv_media);
            ivPlayOverlay   = itemView.findViewById(R.id.iv_play_overlay);
            viewPoll        = itemView.findViewById(R.id.view_poll);
            btnLike         = itemView.findViewById(R.id.btn_like);
            ivLikeIcon      = itemView.findViewById(R.id.iv_like_icon);
            tvLikeCount     = itemView.findViewById(R.id.tv_like_count);
            btnComment      = itemView.findViewById(R.id.btn_comment);
            tvCommentCount  = itemView.findViewById(R.id.tv_comment_count);
        }
    }
}
