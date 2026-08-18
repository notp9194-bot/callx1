package com.callx.app.compose;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.status.R;

import java.util.List;

/**
 * 3-column gallery grid with circle-checkbox selection — shared by
 * {@link StatusLayoutPickerActivity} (initial "Start layout" selection
 * screen) and {@link StatusLayoutAdjustActivity} (the separate "Choose
 * layout" screen the picker now hands off to instead of showing the
 * preview inline on the same screen).
 */
final class LayoutMediaGridAdapter extends RecyclerView.Adapter<LayoutMediaGridAdapter.VH> {

    interface OnToggle { void toggle(LayoutMediaItem item); }

    private final List<LayoutMediaItem> items;
    private final OnToggle              listener;

    LayoutMediaGridAdapter(List<LayoutMediaItem> items, OnToggle listener) {
        this.items    = items;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_layout_picker_media, parent, false);
        // item_layout_picker_media.xml's root has layout_height="0dp" with no
        // weight — that only resolves via LinearLayout's weight mechanism, which
        // a plain RecyclerView/GridLayoutManager cell doesn't have. Give each
        // cell an explicit square height (screenWidth / spanCount), same
        // fixed-cellPx approach RecentMediaGridAdapter uses for the 4-col
        // chat/status attach-sheet grid.
        int cellPx = parent.getContext().getResources().getDisplayMetrics().widthPixels / 3;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.height = cellPx;
        v.setLayoutParams(lp);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        LayoutMediaItem item = items.get(pos);
        int cellPx = h.itemView.getContext().getResources().getDisplayMetrics().widthPixels / 3;
        Glide.with(h.thumb.getContext())
                .load(item.uri)
                .centerCrop()
                // v229 perf: this grid can hold up to 300 items — decoding
                // every camera photo at full resolution just to show a
                // ~120dp thumbnail is the single biggest cost on this screen.
                // Capping to the actual cell size cuts decode time and
                // memory dramatically with zero visible quality loss.
                .override(cellPx, cellPx)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .into(h.thumb);
        h.checkCircle.setVisibility(View.VISIBLE);
        if (item.selected) {
            h.checkCircle.setBackgroundResource(R.drawable.bg_layout_check_selected);
            h.tvOrder.setVisibility(View.VISIBLE);
            h.tvOrder.setText(String.valueOf(item.selectionOrder));
            h.selectionOverlay.setVisibility(View.VISIBLE);
        } else {
            h.checkCircle.setBackgroundResource(R.drawable.bg_layout_check_empty);
            h.tvOrder.setVisibility(View.GONE);
            h.selectionOverlay.setVisibility(View.GONE);
        }
        h.itemView.setOnClickListener(v -> listener.toggle(item));
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView   thumb;
        final FrameLayout checkCircle;
        final TextView    tvOrder;
        final View        selectionOverlay;

        VH(@NonNull View v) {
            super(v);
            thumb            = v.findViewById(R.id.iv_layout_media_thumb);
            checkCircle      = v.findViewById(R.id.fl_layout_check_circle);
            tvOrder          = v.findViewById(R.id.tv_layout_selection_order);
            selectionOverlay = v.findViewById(R.id.layout_selection_overlay);
        }
    }
}
