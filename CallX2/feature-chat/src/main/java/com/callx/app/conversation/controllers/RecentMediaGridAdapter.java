package com.callx.app.conversation.controllers;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 4-column "Recents" grid shown when the attach sheet is dragged up to
 * STATE_EXPANDED (see ChatMediaController#setupRecentsGrid). Deliberately
 * plain View-based cells, not Canvas — this list is short-lived (only
 * inflated while the sheet is open) and RecyclerView recycling already
 * keeps it smooth; canvas-rendering it would add complexity for no
 * measurable gain here. Chat message list is where canvas rendering
 * actually pays off (persistent, long-lived, high scroll volume).
 */
public class RecentMediaGridAdapter extends RecyclerView.Adapter<RecentMediaGridAdapter.VH> {

    public interface Listener {
        void onMediaTapped(RecentMediaLoader.Item item);
    }

    private final Listener listener;
    private List<RecentMediaLoader.Item> items = Collections.emptyList();

    public RecentMediaGridAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<RecentMediaLoader.Item> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    /** Appends a page fetched by RecentMediaLoader#loadRecentPage — powers the
     *  grid's infinite scroll (see AttachSheetRecentMediaBinder's scroll listener). */
    public void append(List<RecentMediaLoader.Item> more) {
        if (more.isEmpty()) return;
        int start = items.size();
        List<RecentMediaLoader.Item> combined = new java.util.ArrayList<>(items);
        combined.addAll(more);
        items = combined;
        notifyItemRangeInserted(start, more.size());
    }

    public int getLoadedCount() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attach_grid_media, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        RecentMediaLoader.Item item = items.get(position);
        Glide.with(h.thumb).load(item.uri).centerCrop().into(h.thumb);
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
        return items.size();
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView duration;
        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            duration = v.findViewById(R.id.video_duration);
        }
    }
}
