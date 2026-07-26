package com.callx.app.compose;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.status.R;

/**
 * Horizontal row of the 6 layout-style buttons (grid/columns/big-left/...).
 * Lives on the "Choose layout" adjust screen (see {@link StatusLayoutAdjustActivity}),
 * which now sits on its own screen instead of appearing inline under the
 * "Start layout" selection grid.
 */
final class LayoutStyleAdapter extends RecyclerView.Adapter<LayoutStyleAdapter.VH> {

    interface OnStyleSelected { void onSelected(int style); }

    private static final int[] STYLES = {
            StatusLayoutPreviewView.STYLE_GRID_2X2,
            StatusLayoutPreviewView.STYLE_BIG_LEFT,
            StatusLayoutPreviewView.STYLE_COLUMNS_2,
            StatusLayoutPreviewView.STYLE_BIG_TOP,
            StatusLayoutPreviewView.STYLE_BIG_RIGHT,
            StatusLayoutPreviewView.STYLE_GRID_3,
    };

    private final OnStyleSelected listener;
    private int selectedStyle = StatusLayoutPreviewView.STYLE_GRID_2X2;

    LayoutStyleAdapter(OnStyleSelected listener) {
        this.listener = listener;
    }

    void setSelected(int style) {
        this.selectedStyle = style;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_layout_style_btn, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        int style = STYLES[pos];
        h.styleIcon.setImageResource(styleIconRes(style));
        boolean sel = (style == selectedStyle);
        h.itemView.setAlpha(sel ? 1f : 0.45f);
        h.indicator.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        h.itemView.setOnClickListener(v -> listener.onSelected(style));
    }

    @Override public int getItemCount() { return STYLES.length; }

    private int styleIconRes(int style) {
        switch (style) {
            case StatusLayoutPreviewView.STYLE_GRID_2X2:  return R.drawable.ic_layout_grid_2x2;
            case StatusLayoutPreviewView.STYLE_BIG_LEFT:  return R.drawable.ic_layout_big_left;
            case StatusLayoutPreviewView.STYLE_COLUMNS_2: return R.drawable.ic_layout_columns_2;
            case StatusLayoutPreviewView.STYLE_BIG_TOP:   return R.drawable.ic_layout_big_top;
            case StatusLayoutPreviewView.STYLE_BIG_RIGHT: return R.drawable.ic_layout_big_right;
            case StatusLayoutPreviewView.STYLE_GRID_3:    return R.drawable.ic_layout_grid_3;
            default: return R.drawable.ic_layout_grid_2x2;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView styleIcon;
        final View      indicator;

        VH(@NonNull View v) {
            super(v);
            styleIcon = v.findViewById(R.id.iv_layout_style_icon);
            indicator = v.findViewById(R.id.view_layout_style_selected);
        }
    }
}
