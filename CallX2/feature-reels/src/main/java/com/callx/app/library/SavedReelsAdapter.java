package com.callx.app.library;

import com.callx.app.player.SingleReelPlayerActivity;

import android.content.Context;
import android.content.Intent;
  import android.content.Context;
  import java.util.ArrayList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
  import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.models.ReelModel;

import java.util.List;
import java.util.Locale;

/**
 * SavedReelsAdapter — 3-column grid of saved reel thumbnails.
 *
 * Each cell shows:
 *  ✅ Thumbnail image (from thumbUrl via Glide)
 *  ✅ Duration badge (e.g. "0:14")
 *  ✅ Tap → opens reel in ReelPlayerActivity (or future deep-link)
 */
public class SavedReelsAdapter extends RecyclerView.Adapter<SavedReelsAdapter.VH> {

    private final Context         context;
    private final List<ReelModel> reels;

    public SavedReelsAdapter(Context context, List<ReelModel> reels) {
        this.context = context;
        this.reels   = reels;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_saved_reel, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ReelModel reel = reels.get(position);

        if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
            Glide.with(context)
                .load(reel.thumbUrl)
                .centerCrop()
                .placeholder(R.drawable.ic_reels)
                .into(h.ivThumb);
        } else {
            h.ivThumb.setImageResource(R.drawable.ic_reels);
        }

        if (reel.duration > 0) {
            int secs  = (reel.duration / 1000) % 60;
            int mins  = reel.duration / 60000;
            h.tvDuration.setText(String.format(Locale.getDefault(), "%d:%02d", mins, secs));
            h.tvDuration.setVisibility(View.VISIBLE);
        } else {
            h.tvDuration.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            // TODO: open single-reel player at this reel's index
            // Intent i = new Intent(context, ReelPlayerActivity.class);
            // i.putExtra("reel_id", reel.reelId);
            // context.startActivity(i);
        });
    }

    /**
     * Makes each grid cell a perfect square by matching its height to its
     * measured width. This replaces the invalid android:layout_aspectRatio
     * attribute (which only works inside ConstraintLayout) that was removed
     * from item_saved_reel.xml.
     */
    @Override
    public void onViewAttachedToWindow(@NonNull VH holder) {
        super.onViewAttachedToWindow(holder);
        holder.itemView.post(() -> {
            int width = holder.itemView.getWidth();
            if (width > 0) {
                android.view.ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                lp.height = width;
                holder.itemView.setLayoutParams(lp);
            }
        });
    }

    @Override
    public int getItemCount() { return reels.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView  tvDuration;
        VH(@NonNull View itemView) {
            super(itemView);
            ivThumb    = itemView.findViewById(R.id.iv_thumb);
            tvDuration = itemView.findViewById(R.id.tv_duration);
        }
    }
}
