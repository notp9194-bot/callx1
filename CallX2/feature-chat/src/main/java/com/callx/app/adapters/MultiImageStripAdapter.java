package com.callx.app.adapters;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.models.PendingImage;

import java.util.List;

/**
 * MultiImageStripAdapter — horizontal strip of image thumbnails.
 * - Focused image: border highlight (brand_primary color)
 * - Each thumbnail has an ✕ remove button
 * - Long-press drag handled by ItemTouchHelper in Activity
 *
 * Item layout: item_multi_image_thumb.xml
 */
public class MultiImageStripAdapter extends RecyclerView.Adapter<MultiImageStripAdapter.VH> {

    public interface OnThumbnailClick { void onClick(int index); }
    public interface OnRemoveClick    { void onClick(int index); }

    private final List<PendingImage> images;
    private final OnThumbnailClick   onThumbClick;
    private final OnRemoveClick      onRemoveClick;
    private int focusedIndex = 0;

    public MultiImageStripAdapter(List<PendingImage> images,
                                  OnThumbnailClick onThumbClick,
                                  OnRemoveClick onRemoveClick) {
        this.images       = images;
        this.onThumbClick = onThumbClick;
        this.onRemoveClick = onRemoveClick;
    }

    public void setFocusedIndex(int index) {
        int old = this.focusedIndex;
        this.focusedIndex = index;
        notifyItemChanged(old);
        notifyItemChanged(index);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_multi_image_thumb, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PendingImage img = images.get(position);

        Glide.with(h.itemView.getContext())
                .load(Uri.parse(img.uriString))
                .centerCrop()
                .placeholder(R.drawable.ic_gallery)
                .into(h.ivThumb);

        // Focused border
        boolean isFocused = (position == focusedIndex);
        h.cardThumb.setCardElevation(isFocused ? 8f : 2f);
        h.borderHighlight.setVisibility(isFocused ? View.VISIBLE : View.GONE);

        // Caption dot indicator
        boolean hasCaption = img.caption != null && !img.caption.isEmpty();
        h.dotCaption.setVisibility(hasCaption ? View.VISIBLE : View.GONE);

        h.cardThumb.setOnClickListener(v -> {
            if (onThumbClick != null) onThumbClick.onClick(h.getAdapterPosition());
        });
        h.btnRemove.setOnClickListener(v -> {
            if (onRemoveClick != null) onRemoveClick.onClick(h.getAdapterPosition());
        });
    }

    @Override public int getItemCount() { return images.size(); }

    static class VH extends RecyclerView.ViewHolder {
        CardView    cardThumb;
        ImageView   ivThumb;
        ImageButton btnRemove;
        View        borderHighlight;
        View        dotCaption;

        VH(View v) {
            super(v);
            cardThumb       = v.findViewById(R.id.card_thumb);
            ivThumb         = v.findViewById(R.id.iv_thumb);
            btnRemove       = v.findViewById(R.id.btn_remove);
            borderHighlight = v.findViewById(R.id.border_highlight);
            dotCaption      = v.findViewById(R.id.dot_caption);
        }
    }
}
