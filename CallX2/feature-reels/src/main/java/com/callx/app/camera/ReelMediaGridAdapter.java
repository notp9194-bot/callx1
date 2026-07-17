package com.callx.app.camera;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReelMediaGridAdapter — 4-column media grid for the camera's media-picker sheet.
 *
 * Features:
 *  • Photos + Videos in a merged, date-sorted grid
 *  • Video duration badge (bottom-right)
 *  • Multi-select with ordered number badges (WhatsApp-style)
 *  • Selection scrim overlay
 *  • Targeted payloads — selection taps don't re-trigger Glide loads
 */
public class ReelMediaGridAdapter
        extends RecyclerView.Adapter<ReelMediaGridAdapter.VH> {

    private static final Object PAYLOAD_SELECTION = new Object();

    public interface OnToggleListener {
        void onToggle(ReelMediaLoader.Item item);
    }

    private final Context            ctx;
    private final OnToggleListener   listener;
    private final int                cellSizePx;
    private final RequestOptions     thumbOpts;

    private List<ReelMediaLoader.Item>    items    = Collections.emptyList();
    /** Ordered selection: uri → 1-based order */
    private final Map<Uri, Integer>       selOrder = new LinkedHashMap<>();
    private int                           selCount = 0;
    private final Map<Uri, Integer>       posCache = new java.util.HashMap<>();

    public ReelMediaGridAdapter(Context ctx, OnToggleListener listener, int cellSizePx) {
        this.ctx        = ctx;
        this.listener   = listener;
        this.cellSizePx = cellSizePx;
        this.thumbOpts  = new RequestOptions()
            .override(cellSizePx, cellSizePx)
            .centerCrop()
            .format(DecodeFormat.PREFER_RGB_565);
        setHasStableIds(true);
    }

    public void setItems(List<ReelMediaLoader.Item> list) {
        this.items = list != null ? list : Collections.emptyList();
        posCache.clear();
        for (int i = 0; i < items.size(); i++) posCache.put(items.get(i).uri, i);
        notifyDataSetChanged();
    }

    /** Returns selected items in tap order. */
    public List<ReelMediaLoader.Item> getSelected() {
        // Rebuild in order
        ReelMediaLoader.Item[] arr = new ReelMediaLoader.Item[selCount];
        for (ReelMediaLoader.Item item : items) {
            Integer ord = selOrder.get(item.uri);
            if (ord != null && ord >= 1 && ord <= selCount) arr[ord - 1] = item;
        }
        List<ReelMediaLoader.Item> result = new ArrayList<>();
        for (ReelMediaLoader.Item it : arr) if (it != null) result.add(it);
        return result;
    }

    public int getSelectedCount() { return selCount; }

    /** Toggle selection; returns new selected count. */
    public int toggle(ReelMediaLoader.Item item) {
        if (selOrder.containsKey(item.uri)) {
            int removedOrder = selOrder.remove(item.uri);
            selCount--;
            // Decrement badges of items selected after the removed one
            for (Map.Entry<Uri, Integer> e : selOrder.entrySet()) {
                if (e.getValue() > removedOrder) e.setValue(e.getValue() - 1);
            }
        } else {
            selOrder.put(item.uri, ++selCount);
        }
        // Targeted refresh
        Integer pos = posCache.get(item.uri);
        if (pos != null) notifyItemChanged(pos, PAYLOAD_SELECTION);
        // Also refresh all others to resequence badges
        if (!selOrder.isEmpty()) notifyItemRangeChanged(0, items.size(), PAYLOAD_SELECTION);
        return selCount;
    }

    public void clearSelection() {
        selOrder.clear();
        selCount = 0;
        notifyItemRangeChanged(0, items.size(), PAYLOAD_SELECTION);
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx)
            .inflate(R.layout.item_reel_media_cell, parent, false);
        // Force square cells
        v.getLayoutParams().height = cellSizePx;
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            // Selection-only update — skip Glide reload
            bindSelection(h, items.get(pos));
            return;
        }
        onBindViewHolder(h, pos);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        ReelMediaLoader.Item item = items.get(pos);
        h.boundItem = item;

        Glide.with(ctx).load(item.uri).apply(thumbOpts).into(h.thumb);

        if (item.isVideo && item.durationMs > 0) {
            h.duration.setVisibility(View.VISIBLE);
            h.duration.setText(ReelMediaLoader.formatDuration(item.durationMs));
        } else {
            h.duration.setVisibility(View.GONE);
        }

        bindSelection(h, item);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onToggle(item);
        });
    }

    private void bindSelection(@NonNull VH h, ReelMediaLoader.Item item) {
        Integer order = selOrder.get(item.uri);
        boolean selected = order != null;
        h.scrim.setVisibility(selected ? View.VISIBLE : View.GONE);
        h.badge.setVisibility(selected ? View.VISIBLE : View.GONE);
        if (selected) h.badgeText.setText(String.valueOf(order));
    }

    @Override public int getItemCount() { return items.size(); }

    @Override public long getItemId(int pos) {
        return items.isEmpty() ? 0 : items.get(pos).uri.hashCode();
    }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView  duration;
        final View      scrim;
        final View      badge;
        final TextView  badgeText;
        ReelMediaLoader.Item boundItem;

        VH(@NonNull View v) {
            super(v);
            thumb     = v.findViewById(R.id.iv_media_thumb);
            duration  = v.findViewById(R.id.tv_video_duration);
            scrim     = v.findViewById(R.id.v_selection_scrim);
            badge     = v.findViewById(R.id.fl_selection_badge);
            badgeText = v.findViewById(R.id.tv_selection_num);
        }
    }
}
