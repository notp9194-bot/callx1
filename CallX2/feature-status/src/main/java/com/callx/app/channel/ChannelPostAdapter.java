package com.callx.app.channel;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.ChannelPost;   // ← now from core/models
import com.callx.app.status.R;
import java.util.*;

/**
 * ChannelPostAdapter v2 — WhatsApp-level architecture.
 *
 * CHANGED: Uses ChannelPost from com.callx.app.models (core) instead of the
 * old feature-status local model. ChannelViewerActivity converts
 * ChannelPostEntity → ChannelPost before passing to this adapter.
 */
public class ChannelPostAdapter extends RecyclerView.Adapter<ChannelPostAdapter.PostVH> {

    private final List<ChannelPost> posts = new ArrayList<>();
    private java.util.function.Consumer<ChannelPost> onForwardClick;
    private java.util.function.Consumer<ChannelPost> onReactionClick;

    public void setPosts(List<ChannelPost> list) {
        posts.clear();
        if (list != null) posts.addAll(list);
        notifyDataSetChanged();
    }

    public void addPost(ChannelPost post) {
        posts.add(0, post);
        notifyItemInserted(0);
    }

    public void setOnForwardClick(java.util.function.Consumer<ChannelPost> cb)  { this.onForwardClick  = cb; }
    public void setOnReactionClick(java.util.function.Consumer<ChannelPost> cb) { this.onReactionClick = cb; }

    @NonNull @Override
    public PostVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PostVH(LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_channel_post, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PostVH h, int pos) {
        ChannelPost post = posts.get(pos);
        Context ctx = h.itemView.getContext();

        // Text
        if (post.text != null && !post.text.isEmpty()) {
            h.tvText.setVisibility(View.VISIBLE);
            h.tvText.setText(post.text);
        } else { h.tvText.setVisibility(View.GONE); }

        // Media
        if (h.ivMedia != null) {
            boolean hasMedia = post.mediaUrl != null && !post.mediaUrl.isEmpty()
                    && ("image".equals(post.type) || "video".equals(post.type));
            if (hasMedia) {
                h.ivMedia.setVisibility(View.VISIBLE);
                String thumb = post.thumbnailUrl != null ? post.thumbnailUrl : post.mediaUrl;
                Glide.with(ctx).load(thumb).centerCrop().override(800, 600).into(h.ivMedia);
            } else { h.ivMedia.setVisibility(View.GONE); }
        }

        if (h.ivPlayOverlay != null)
            h.ivPlayOverlay.setVisibility("video".equals(post.type) ? View.VISIBLE : View.GONE);

        // Time
        if (h.tvTime != null && post.timestamp > 0) {
            h.tvTime.setText(DateUtils.getRelativeTimeSpanString(
                post.timestamp, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
        } else if (h.tvTime != null) { h.tvTime.setText(""); }

        // Reactions
        if (h.tvReactions != null) {
            if (post.reactions != null && !post.reactions.isEmpty()) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String emoji : post.reactions.values()) counts.merge(emoji, 1, Integer::sum);
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    sb.append(e.getKey());
                    if (e.getValue() > 1) sb.append(" ").append(e.getValue());
                    sb.append("  ");
                }
                h.tvReactions.setText(sb.toString().trim());
                h.tvReactions.setVisibility(View.VISIBLE);
            } else { h.tvReactions.setVisibility(View.GONE); }
        }

        if (h.tvViews   != null) h.tvViews.setText(post.viewCount > 0 ? post.viewCount + " views" : "");
        if (h.tvForwards!= null) h.tvForwards.setText(post.forwardCount > 0 ? String.valueOf(post.forwardCount) : "");

        if (h.btnReact   != null) h.btnReact.setOnClickListener(v -> { if (onReactionClick != null) onReactionClick.accept(post); });
        if (h.btnForward != null) h.btnForward.setOnClickListener(v -> { if (onForwardClick != null) onForwardClick.accept(post); });
    }

    @Override public int getItemCount() { return posts.size(); }

    static class PostVH extends RecyclerView.ViewHolder {
        TextView  tvText, tvTime, tvReactions, tvViews, tvForwards;
        ImageView ivMedia, ivPlayOverlay;
        ImageButton btnReact, btnForward;
        PostVH(View v) {
            super(v);
            tvText        = v.findViewById(R.id.tv_post_text);
            tvTime        = v.findViewById(R.id.tv_post_time);
            tvReactions   = v.findViewById(R.id.tv_post_reactions);
            tvViews       = v.findViewById(R.id.tv_post_views);
            tvForwards    = v.findViewById(R.id.tv_post_forwards);
            ivMedia       = v.findViewById(R.id.iv_post_media);
            ivPlayOverlay = v.findViewById(R.id.iv_post_play);
            btnReact      = v.findViewById(R.id.btn_post_react);
            btnForward    = v.findViewById(R.id.btn_post_forward);
        }
    }
}
