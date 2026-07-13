package com.callx.app.conversation.controllers;

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
import com.callx.app.chat.R;

import java.util.List;
import java.util.Locale;

/**
 * Rows for the "Recents ▾" dropdown popup (see AttachSheetFolderPicker) —
 * one per real/synthetic on-device folder from {@link RecentMediaLoader#loadFolders}.
 * Plain single-select list: tapping a row fires Listener#onFolderPicked and
 * the popup dismisses itself.
 */
final class AttachFolderAdapter extends RecyclerView.Adapter<AttachFolderAdapter.VH> {

    interface Listener {
        void onFolderPicked(RecentMediaLoader.Folder folder);
    }

    private final List<RecentMediaLoader.Folder> folders;
    private final Listener listener;
    private final RequestOptions thumbOptions;
    private String selectedKey; // Folder#filterKey of the currently active folder ("" stands in for null/Recents)

    AttachFolderAdapter(List<RecentMediaLoader.Folder> folders, String selectedFilterKey, Listener listener) {
        this.folders = folders;
        this.listener = listener;
        this.selectedKey = selectedFilterKey == null ? "" : selectedFilterKey;
        this.thumbOptions = new RequestOptions()
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attach_folder, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        RecentMediaLoader.Folder folder = folders.get(position);
        h.name.setText(folder.name);

        if (folder.isAction) {
            h.count.setVisibility(View.GONE);
            h.check.setVisibility(View.GONE);
            h.chevron.setVisibility(View.VISIBLE);
            // "More apps" → blue folder glyph, "See more" → grey stacked-folders
            // glyph — same two tiles as the reference screenshot, built from a
            // plain icon on the card's background color instead of a photo.
            boolean isMoreApps = RecentMediaLoader.ACTION_MORE_APPS.equals(folder.filterKey);
            h.thumbCard.setCardBackgroundColor(isMoreApps ? 0xFF1E88E5 : 0xFFE3E5E8);
            h.thumb.setPadding(h.iconPaddingPx, h.iconPaddingPx, h.iconPaddingPx, h.iconPaddingPx);
            h.thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            h.thumb.setBackground(null);
            h.thumb.setImageResource(isMoreApps ? R.drawable.ic_more_apps_folder : R.drawable.ic_more_apps_stack);
        } else {
            h.count.setVisibility(View.VISIBLE);
            h.count.setText(formatCount(folder.itemCount));
            boolean isSelected = (folder.filterKey == null ? "" : folder.filterKey).equals(selectedKey);
            h.check.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            h.chevron.setVisibility(View.GONE);
            h.thumbCard.setCardBackgroundColor(0xFF2A2A2A);
            h.thumb.setPadding(0, 0, 0, 0);
            h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (folder.coverUri != null) {
                Glide.with(h.thumb.getContext())
                        .load(folder.coverUri)
                        .apply(thumbOptions)
                        .into(h.thumb);
            } else {
                h.thumb.setImageDrawable(null);
            }
        }

        h.itemView.setOnClickListener(x -> listener.onFolderPicked(folder));
    }

    @Override
    public int getItemCount() {
        return folders.size();
    }

    private static String formatCount(int count) {
        return String.format(Locale.US, "%,d item%s", count, count == 1 ? "" : "s");
    }

    static final class VH extends RecyclerView.ViewHolder {
        final com.google.android.material.card.MaterialCardView thumbCard;
        final ImageView thumb;
        final TextView name;
        final TextView count;
        final ImageView check;
        final ImageView chevron;
        final int iconPaddingPx;

        VH(@NonNull View itemView) {
            super(itemView);
            thumbCard = itemView.findViewById(R.id.folder_thumb_card);
            thumb = itemView.findViewById(R.id.folder_thumb);
            name = itemView.findViewById(R.id.folder_name);
            count = itemView.findViewById(R.id.folder_count);
            check = itemView.findViewById(R.id.folder_check);
            chevron = itemView.findViewById(R.id.folder_chevron);
            iconPaddingPx = Math.round(12f * itemView.getResources().getDisplayMetrics().density);
        }
    }
}
