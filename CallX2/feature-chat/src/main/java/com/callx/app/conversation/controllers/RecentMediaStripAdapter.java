package com.callx.app.conversation.controllers;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.chat.R;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Camera tile (position 0) + recent gallery thumbnails, horizontal —
 * the row that sits right under the drag handle in bottom_sheet_attach.xml,
 * matching the reference screenshot (camera followed by inline recents).
 * Backed by Glide with DiskCacheStrategy.NONE + overrideThumb sizing so
 * repeatedly opening the sheet doesn't thrash disk cache for content:// URIs
 * whose ids get reused; RecyclerView's own view-holder recycling is what
 * keeps this scrolling smooth, not bitmap caching.
 */
public class RecentMediaStripAdapter extends RecyclerView.Adapter<RecentMediaStripAdapter.VH> {

    public interface Listener {
        void onCameraTapped();
        void onMediaTapped(RecentMediaLoader.Item item);
    }

    private final Listener listener;
    private List<RecentMediaLoader.Item> items = Collections.emptyList();

    public RecentMediaStripAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    public void submit(List<RecentMediaLoader.Item> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attach_strip_media, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        if (position == 0) {
            h.cameraIcon.setVisibility(View.VISIBLE);
            h.duration.setVisibility(View.GONE);
            h.thumb.setImageDrawable(null);
            h.thumb.setBackgroundResource(R.drawable.bg_media_thumb_camera);
            h.itemView.setOnClickListener(x -> listener.onCameraTapped());
            return;
        }
        RecentMediaLoader.Item item = items.get(position - 1);
        h.cameraIcon.setVisibility(View.GONE);
        h.thumb.setBackground(null);
        Glide.with(h.thumb)
                .load(item.uri)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(h.thumb);
        if (item.isVideo) {
            h.duration.setVisibility(View.VISIBLE);
            h.duration.setText(formatDuration(item.durationMs));
        } else {
            h.duration.setVisibility(View.GONE);
        }
        h.itemView.setOnClickListener(x -> listener.onMediaTapped(item));
    }

    @Override
    public int getItemCount() {
        return 1 + items.size(); // camera tile + recents
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", min, sec);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final ImageView cameraIcon;
        final TextView duration;
        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            cameraIcon = v.findViewById(R.id.camera_icon);
            duration = v.findViewById(R.id.video_duration);
        }
    }
}
