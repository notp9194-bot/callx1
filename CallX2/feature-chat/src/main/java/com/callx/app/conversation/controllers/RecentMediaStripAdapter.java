package com.callx.app.conversation.controllers;

import android.content.Context;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Camera tile (position 0) + recent gallery thumbnails, horizontal —
 * the row that sits right under the drag handle in bottom_sheet_attach.xml,
 * matching the reference screenshot (camera followed by inline recents).
 * Backed by Glide with DiskCacheStrategy.NONE + overrideThumb sizing so
 * repeatedly opening the sheet doesn't thrash disk cache for content:// URIs
 * whose ids get reused; RecyclerView's own view-holder recycling is what
 * keeps this scrolling smooth, not bitmap caching.
 *
 * Performance pass: selection is now applied via a targeted
 * notifyItemChanged(pos, PAYLOAD_SELECTION) driven by
 * MediaSelectionState.ToggleListener instead of notifyDataSetChanged() on
 * every tap, so tapping a thumbnail no longer re-fires a Glide load for
 * every other visible cell in the strip. Thumbnails are downsampled to the
 * actual 76dp cell size and decoded as RGB_565 to cut decode cost/memory.
 */
public class RecentMediaStripAdapter extends RecyclerView.Adapter<RecentMediaStripAdapter.VH> {

    private static final Object PAYLOAD_SELECTION = new Object();
    private static final int STRIP_CELL_DP = 76;

    public interface Listener {
        void onCameraTapped();
        /** Tap toggles selection now (see MediaSelectionState) instead of sending immediately. */
        void onMediaToggled(RecentMediaLoader.Item item);
    }

    private final Listener listener;
    private final MediaSelectionState selection;
    private final RequestOptions thumbOptions;
    private List<RecentMediaLoader.Item> items = Collections.emptyList();
    /** uri -> adapter position (item index, NOT accounting for the +1 camera-tile offset). */
    private final Map<Uri, Integer> positionByUri = new HashMap<>();

    public RecentMediaStripAdapter(Context context, Listener listener, MediaSelectionState selection) {
        this.listener = listener;
        this.selection = selection;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int cellPx = Math.round(STRIP_CELL_DP * dm.density);
        this.thumbOptions = new RequestOptions()
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .override(cellPx)
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

    private void reindex() {
        positionByUri.clear();
        for (int i = 0; i < items.size(); i++) positionByUri.put(items.get(i).uri, i);
    }

    /** Targeted refresh triggered by MediaSelectionState.ToggleListener — no full rebind. */
    private void notifyUriChanged(Uri uri) {
        Integer idx = positionByUri.get(uri);
        if (idx != null) notifyItemChanged(idx + 1, PAYLOAD_SELECTION); // +1 for the camera tile at 0
    }

    @Override
    public long getItemId(int position) {
        if (position == 0) return -1L; // camera tile
        return items.get(position - 1).uri.hashCode();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attach_strip_media, parent, false);
        VH h = new VH(v);
        // One listener per holder (not per bind) — dispatches whatever is
        // currently bound, avoiding a fresh lambda allocation on every bind.
        h.itemView.setOnClickListener(x -> {
            if (h.isCamera) {
                listener.onCameraTapped();
            } else if (h.boundItem != null) {
                listener.onMediaToggled(h.boundItem);
            }
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        onBindViewHolder(h, position, Collections.emptyList());
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        boolean selectionOnly = !payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION);

        if (position == 0) {
            h.isCamera = true;
            h.boundItem = null;
            if (!selectionOnly) {
                h.cameraIcon.setVisibility(View.VISIBLE);
                h.duration.setVisibility(View.GONE);
                h.thumb.setImageDrawable(null);
                h.thumb.setBackgroundResource(R.drawable.bg_media_thumb_camera);
            }
            h.selectionScrim.setVisibility(View.GONE);
            h.selectionBadge.setVisibility(View.GONE);
            return;
        }

        h.isCamera = false;
        RecentMediaLoader.Item item = items.get(position - 1);
        h.boundItem = item;

        if (!selectionOnly) {
            h.cameraIcon.setVisibility(View.GONE);
            h.thumb.setBackground(null);
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
        final View selectionScrim;
        final View selectionBadge;
        final TextView selectionBadgeText;
        boolean isCamera;
        RecentMediaLoader.Item boundItem;
        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            cameraIcon = v.findViewById(R.id.camera_icon);
            duration = v.findViewById(R.id.video_duration);
            selectionScrim = v.findViewById(R.id.selection_scrim);
            selectionBadge = v.findViewById(R.id.selection_badge);
            selectionBadgeText = v.findViewById(R.id.selection_badge_text);
        }
    }
}
