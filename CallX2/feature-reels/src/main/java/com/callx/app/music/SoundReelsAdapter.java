package com.callx.app.music;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import java.util.List;
import java.util.Locale;

public class SoundReelsAdapter extends RecyclerView.Adapter<SoundReelsAdapter.VH> {
    private final List<SoundDetailActivity.ReelThumbItem> items;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    public SoundReelsAdapter(List<SoundDetailActivity.ReelThumbItem> items, OnItemClickListener listener) {
        this.items = items;
        this.listener = listener;
        // ULTRA (UserReelsActivity pattern): stable IDs let DiffUtil and
        // RecyclerView skip unnecessary rebinds when the list is partially
        // refreshed — avoids Glide re-loading already-cached thumbnails.
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        // Use the reelId string's hash as the stable long ID. Two different
        // reelId values will practically never collide (and even if they did
        // the only cost is a spurious rebind, not a crash).
        String id = items.get(position).reelId;
        return id != null ? id.hashCode() : position;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reel_thumb, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        SoundDetailActivity.ReelThumbItem item = items.get(position);
        // ULTRA: explicit override so Glide never samples larger than the cell,
        // and DiskCacheStrategy.ALL caches both original + transformed so
        // subsequent binds (tab switch / re-scroll) are instant.
        Glide.with(holder.ivThumb)
             .load(item.thumbnailUrl)
             .centerCrop()
             .override(holder.ivThumb.getWidth() > 0 ? holder.ivThumb.getWidth() : 300,
                       holder.ivThumb.getHeight() > 0 ? holder.ivThumb.getHeight() : 300)
             .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
             .placeholder(R.drawable.ic_reel_placeholder)
             .into(holder.ivThumb);
        holder.tvViews.setText(formatCount(item.viewsCount));
        holder.itemView.setOnClickListener(v -> listener.onItemClick(position));
    }

    @Override
    public int getItemCount() { return items.size(); }

    /**
     * ULTRA: Glide RecyclerViewPreloader support — called by the preloader
     * to know which URL to warm for a given adapter position.
     */
    public String getPreloadUrl(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position).thumbnailUrl;
    }

    private String formatCount(long n) {
        if (n >= 1000000) return String.format(Locale.US, "%.1fM", n/1000000.0);
        if (n >= 1000) return String.format(Locale.US, "%.1fK", n/1000.0);
        return String.valueOf(n);
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView tvViews;
        VH(View v) {
            super(v);
            ivThumb = v.findViewById(R.id.iv_reel_thumb);
            tvViews = v.findViewById(R.id.tv_reel_views);
        }
    }
}
