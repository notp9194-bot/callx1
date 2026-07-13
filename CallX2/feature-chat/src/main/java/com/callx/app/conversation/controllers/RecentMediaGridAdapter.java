package com.callx.app.conversation.controllers;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 4-column "Recents" grid shown when the attach sheet is dragged up to
 * STATE_EXPANDED (see ChatMediaController#setupRecentsGrid). Deliberately
 * plain View-based cells, not Canvas — this list is short-lived (only
 * inflated while the sheet is open) and RecyclerView recycling already
 * keeps it smooth; canvas-rendering it would add complexity for no
 * measurable gain here. Chat message list is where canvas rendering
 * actually pays off (persistent, long-lived, high scroll volume).
 *
 * Performance pass:
 *  - Selection taps used to notifyDataSetChanged() the whole grid, which
 *    re-issued a Glide load for every bound cell on every tap. Now the
 *    grid listens directly to MediaSelectionState's per-uri
 *    ToggleListener and does a targeted notifyItemChanged(pos, PAYLOAD)
 *    so onBindViewHolder can skip the Glide call entirely and just flip
 *    the selection scrim/badge (see onBindViewHolder(payloads) below).
 *  - Thumbnails are decoded pre-downsampled to the actual cell size
 *    (.override()) instead of full MediaStore resolution, and use
 *    RGB_565 (no alpha needed for photos) to halve per-bitmap memory —
 *    both cut CPU/GC pressure while flinging.
 *  - A single RequestOptions instance is built once and reused for every
 *    bind instead of Glide re-building a fresh options chain per cell.
 *  - Implements Glide's ListPreloader.PreloadModelProvider so the next
 *    ~12 thumbnails below the fold are already warm in Glide's cache
 *    before they scroll into view (see AttachSheetRecentMediaBinder,
 *    which wires a RecyclerViewPreloader against this adapter).
 */
public class RecentMediaGridAdapter extends RecyclerView.Adapter<RecentMediaGridAdapter.VH>
        implements ListPreloader.PreloadModelProvider<Uri> {

    private static final Object PAYLOAD_SELECTION = new Object();

    public interface Listener {
        /** Tap toggles selection now (see MediaSelectionState) instead of sending immediately. */
        void onMediaToggled(RecentMediaLoader.Item item);
    }

    private final Context context;
    private final Listener listener;
    private final MediaSelectionState selection;
    private final int cellSizePx;
    private final RequestOptions thumbOptions;
    private List<RecentMediaLoader.Item> items = Collections.emptyList();
    /** uri -> adapter position, rebuilt on submit()/append() for O(1) targeted notifyItemChanged. */
    private final Map<Uri, Integer> positionByUri = new HashMap<>();

    public RecentMediaGridAdapter(Context context, Listener listener, MediaSelectionState selection, int cellSizePx) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.selection = selection;
        this.cellSizePx = cellSizePx;
        this.thumbOptions = new RequestOptions()
                .centerCrop()
                .override(Math.max(1, cellSizePx))
                .format(DecodeFormat.PREFER_RGB_565)
                .dontAnimate();
        setHasStableIds(true);
        selection.addToggleListener(this::notifyUriChanged);
    }

    public void submit(List<RecentMediaLoader.Item> newItems) {
        this.items = newItems;
        reindex();
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
        for (int i = start; i < items.size(); i++) positionByUri.put(items.get(i).uri, i);
        notifyItemRangeInserted(start, more.size());
    }

    private void reindex() {
        positionByUri.clear();
        for (int i = 0; i < items.size(); i++) positionByUri.put(items.get(i).uri, i);
    }

    /** Targeted refresh triggered by MediaSelectionState.ToggleListener — no full rebind. */
    private void notifyUriChanged(Uri uri) {
        Integer pos = positionByUri.get(uri);
        if (pos != null) notifyItemChanged(pos, PAYLOAD_SELECTION);
    }

    public int getLoadedCount() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).uri.hashCode();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attach_grid_media, parent, false);
        if (cellSizePx > 0) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            lp.width = cellSizePx;
            lp.height = cellSizePx;
            v.setLayoutParams(lp);
        }
        VH h = new VH(v);
        // Click listener registered once per ViewHolder instead of a fresh
        // lambda allocation on every onBindViewHolder call; the holder
        // always dispatches whatever item is currently bound to it.
        h.itemView.setOnClickListener(x -> {
            if (h.boundItem != null) listener.onMediaToggled(h.boundItem);
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        onBindViewHolder(h, position, Collections.emptyList());
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        RecentMediaLoader.Item item = items.get(position);
        h.boundItem = item;

        boolean selectionOnly = !payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION);
        if (!selectionOnly) {
            Glide.with(h.thumb).load(item.uri).apply(thumbOptions).into(h.thumb);
            if (item.isVideo) {
                h.duration.setVisibility(View.VISIBLE);
                h.duration.setText(formatDuration(item.durationMs));
            } else {
                h.duration.setVisibility(View.GONE);
            }
        }

        boolean isSelected = selection.isSelected(item.uri);
        h.selectionScrim.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        h.selectionBadge.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        if (isSelected) h.selectionBadgeText.setText(String.valueOf(selection.orderOf(item.uri)));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    // --- ListPreloader.PreloadModelProvider: warms upcoming thumbnails ahead of scroll ---

    @NonNull @Override
    public List<Uri> getPreloadItems(int position) {
        if (position < 0 || position >= items.size()) return Collections.emptyList();
        return Collections.singletonList(items.get(position).uri);
    }

    @Override
    public RequestBuilder<?> getPreloadRequestBuilder(@NonNull Uri uri) {
        RequestManager rm = Glide.with(context);
        return rm.asBitmap().load(uri).apply(thumbOptions);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView duration;
        final View selectionScrim;
        final View selectionBadge;
        final TextView selectionBadgeText;
        RecentMediaLoader.Item boundItem;
        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            duration = v.findViewById(R.id.video_duration);
            selectionScrim = v.findViewById(R.id.selection_scrim);
            selectionBadge = v.findViewById(R.id.selection_badge);
            selectionBadgeText = v.findViewById(R.id.selection_badge_text);
        }
    }
}
