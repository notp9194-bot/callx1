package com.callx.app.library;

import com.callx.app.player.SingleReelPlayerActivity;

import android.content.Context;
import android.content.Intent;
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
import com.callx.app.models.ReelModel;

import java.util.List;

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
                .override(480, 853)
                .into(h.ivThumb);
        } else {
            h.ivThumb.setImageResource(R.drawable.ic_reels);
        }

        // item_saved_reel.xml no longer has a duration badge (views pill only),
        // so bind the views count into tv_views_overlay instead of tv_duration.
        if (h.tvViews != null) {
            h.tvViews.setText(formatCount(reel.viewsCount));
        }

        h.itemView.setOnClickListener(v -> {
            if (reel.reelId == null || reel.reelId.isEmpty()) return;
            ArrayList<String> ids = new ArrayList<>();
            // Pass starting reel + up to 30 surrounding saved reels for swipe-through experience
            int start = 0;
            for (int i = 0; i < reels.size(); i++) {
                ids.add(reels.get(i).reelId);
                if (reels.get(i).reelId != null && reels.get(i).reelId.equals(reel.reelId)) {
                    start = i;
                }
            }
            Intent intent = new Intent(context, SingleReelPlayerActivity.class);
            intent.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
            intent.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, start);
            intent.putExtra(SingleReelPlayerActivity.EXTRA_TITLE, "Saved Reels");
            context.startActivity(intent);
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

    private static String formatCount(int n) {
        if (n >= 1_000_000) return scaled(n, 1_000_000, 'M');
        if (n >= 1_000)     return scaled(n, 1_000, 'K');
        return String.valueOf(n);
    }

    private static String scaled(int n, int unit, char suffix) {
        int tenths = Math.round(n * 10f / unit);
        int whole = tenths / 10, frac = tenths % 10;
        return frac == 0 ? whole + "" + suffix : whole + "." + frac + suffix;
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView  tvViews;
        VH(@NonNull View itemView) {
            super(itemView);
            ivThumb    = itemView.findViewById(R.id.iv_thumb);
            tvViews    = itemView.findViewById(R.id.tv_views_overlay);
        }
    }
}
