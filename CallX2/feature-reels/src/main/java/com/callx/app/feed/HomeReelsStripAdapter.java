package com.callx.app.feed;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.models.ReelModel;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.List;

/**
 * HomeReelsStripAdapter — horizontal mini-reel thumbnails in the "Reels for you"
 * strip card inside the home feed.
 *
 * Tap → opens SingleReelPlayerActivity at that reel position.
 */
public class HomeReelsStripAdapter extends RecyclerView.Adapter<HomeReelsStripAdapter.VH> {

    private final List<ReelModel> reels = new ArrayList<>();

    public void setReels(List<ReelModel> list) {
        reels.clear();
        reels.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_feed_mini_reel, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.bind(reels.get(position), position);
    }

    @Override
    public int getItemCount() { return reels.size(); }

    class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView  tvViews, tvDuration;
        View      ivPlayIcon;

        VH(@NonNull View v) {
            super(v);
            ivThumb    = v.findViewById(R.id.iv_mini_reel_thumb);
            tvViews    = v.findViewById(R.id.tv_mini_reel_views);
            tvDuration = v.findViewById(R.id.tv_mini_reel_duration);
            ivPlayIcon = v.findViewById(R.id.iv_mini_reel_play);
        }

        void bind(ReelModel r, int pos) {
            Context ctx = itemView.getContext();
            String thumb = r.effectiveThumbUrl();
            if (!thumb.isEmpty()) {
                Glide.with(ctx).load(thumb)
                     .placeholder(R.drawable.ic_video_orange)
                     .into(ivThumb);
            } else {
                ivThumb.setImageResource(R.drawable.ic_video_orange);
            }

            // Views count
            if (tvViews != null) {
                String vc = r.viewsCount >= 1000
                        ? String.format(java.util.Locale.US, "%.1fK", r.viewsCount / 1000f)
                        : String.valueOf(r.viewsCount);
                tvViews.setText(vc);
            }

            // Duration
            if (tvDuration != null && r.duration > 0) {
                int sec = r.duration;
                tvDuration.setText(String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60));
                tvDuration.setVisibility(View.VISIBLE);
            } else if (tvDuration != null) {
                tvDuration.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                Intent intent = new Intent(ctx, SingleReelPlayerActivity.class);
                intent.putExtra("reelId", r.reelId);
                intent.putExtra("startPosition", pos);
                ctx.startActivity(intent);
            });
        }
    }
}
