package com.callx.app.conversation.controllers;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Small horizontal strip of everything currently picked, shown inside the
 * attach sheet's selection bar. Shows the whole MediaSelectionState, so a
 * pick made in another folder stays visible after a folder switch. Tap a
 * thumbnail to deselect it (goes through the same MediaSelectionState#toggle
 * as the grid, so grid badges re-number via the existing ToggleListener).
 */
final class SelectedPreviewStripAdapter extends RecyclerView.Adapter<SelectedPreviewStripAdapter.VH> {

    interface Listener {
        void onRemove(RecentMediaLoader.Item item);
    }

    private final Listener listener;
    private final RequestOptions thumbOptions;
    private List<RecentMediaLoader.Item> items = new ArrayList<>();

    SelectedPreviewStripAdapter(int thumbPx, Listener listener) {
        this.listener = listener;
        this.thumbOptions = new RequestOptions()
                .centerCrop()
                .override(Math.max(1, thumbPx))
                .format(DecodeFormat.PREFER_RGB_565)
                .dontAnimate();
        setHasStableIds(true);
    }

    /** Diffs by uri so adds/removes animate and unchanged thumbs don't reload. */
    void submit(List<RecentMediaLoader.Item> next) {
        final List<RecentMediaLoader.Item> old = items;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return old.get(o).uri.equals(next.get(n).uri);
            }
            @Override public boolean areContentsTheSame(int o, int n) { return true; }
        });
        items = next;
        diff.dispatchUpdatesTo(this);
    }

    int indexOfLast() { return items.size() - 1; }

    @Override public long getItemId(int position) { return items.get(position).uri.hashCode(); }
    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attach_selected_thumb, parent, false);
        VH h = new VH(v);
        v.setOnClickListener(x -> { if (h.bound != null) listener.onRemove(h.bound); });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        RecentMediaLoader.Item item = items.get(position);
        h.bound = item;
        Glide.with(h.thumb).load(item.uri).apply(thumbOptions).into(h.thumb);
        h.videoBadge.setVisibility(item.isVideo ? View.VISIBLE : View.GONE);
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final ImageView videoBadge;
        RecentMediaLoader.Item bound;
        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.strip_thumb);
            videoBadge = v.findViewById(R.id.strip_video_badge);
        }
    }
}
