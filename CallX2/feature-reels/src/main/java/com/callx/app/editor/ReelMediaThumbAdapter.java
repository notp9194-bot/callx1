package com.callx.app.editor;

import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import java.util.List;

/**
 * ReelMediaThumbAdapter — 3-column media grid for reel-related screens.
 * Each cell shows a square thumbnail loaded via Glide.
 */
public class ReelMediaThumbAdapter extends RecyclerView.Adapter<ReelMediaThumbAdapter.VH> {

    public interface OnMediaClickListener { void onClick(String url); }

    private final List<String>         urls;
    private final OnMediaClickListener listener;

    public ReelMediaThumbAdapter(List<String> urls, OnMediaClickListener listener) {
        this.urls     = urls;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
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
                .override(480, 853)
                .into(h.ivThumb);
        h.itemView.setOnClickListener(v -> listener.onClick(url));
    }

    @Override public int getItemCount() {
        return Math.min(urls.size(), 9);
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        VH(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.iv_media_thumb);
        }
    }
}
