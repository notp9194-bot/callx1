package com.callx.app.adapters;

import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import java.util.List;

/**
 * MediaThumbAdapter — 3-column media grid for GroupInfoActivity.
 * Each cell shows a square thumbnail loaded via Glide.
 */
public class MediaThumbAdapter extends RecyclerView.Adapter<MediaThumbAdapter.VH> {

    public interface OnMediaClickListener { void onClick(String url); }

    private final List<String>        urls;
    private final OnMediaClickListener listener;

    public MediaThumbAdapter(List<String> urls, OnMediaClickListener listener) {
        this.urls     = urls;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Square cell — dynamically sized to 1/3 of screen width
        int size = parent.getMeasuredWidth() / 3;
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media_thumb, parent, false);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
        v.setLayoutParams(lp);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        String url = urls.get(pos);
        Glide.with(h.ivThumb.getContext())
                .load(url)
                .centerCrop()
                .placeholder(R.drawable.ic_gallery)
                .into(h.ivThumb);
        h.itemView.setOnClickListener(v -> listener.onClick(url));
    }

    @Override public int getItemCount() {
        return Math.min(urls.size(), 9); // Show max 9 in info screen
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        VH(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.iv_media_thumb);
        }
    }
}
