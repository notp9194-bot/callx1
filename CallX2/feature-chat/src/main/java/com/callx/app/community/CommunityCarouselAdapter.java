package com.callx.app.community;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;

import java.util.ArrayList;
import java.util.List;

/**
 * v34: ViewPager2 / RecyclerView adapter for multi-media carousel posts.
 * Supports up to 5 items: images (Glide) or videos (thumbnail from Glide).
 * Used inside CommunityFullscreenMediaActivity for carousel swipe.
 */
public class CommunityCarouselAdapter extends RecyclerView.Adapter<CommunityCarouselAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(int position, String url, String type);
    }

    private List<String> mediaUrls  = new ArrayList<>();
    private List<String> mediaTypes = new ArrayList<>();
    private OnItemClickListener listener;

    public void setMedia(List<String> urls, List<String> types) {
        mediaUrls  = urls  != null ? urls  : new ArrayList<>();
        mediaTypes = types != null ? types : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener l) {
        this.listener = l;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_carousel_media, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        String url  = pos < mediaUrls.size()  ? mediaUrls.get(pos)  : "";
        String type = pos < mediaTypes.size() ? mediaTypes.get(pos) : "image";

        if ("video".equals(type)) {
            h.ivOverlay.setVisibility(View.VISIBLE); // play icon overlay
            Glide.with(h.ivImage.getContext())
                    .asBitmap()
                    .load(url)
                    .override(640, 640)
                    .centerCrop()
                    .placeholder(R.drawable.ic_video)
                    .into(h.ivImage);
        } else {
            h.ivOverlay.setVisibility(View.GONE);
            Glide.with(h.ivImage.getContext())
                    .load(url)
                    .override(640, 640)
                    .centerCrop()
                    .placeholder(R.drawable.ic_gallery)
                    .into(h.ivImage);
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(pos, url, type);
        });
    }

    @Override public int getItemCount() { return mediaUrls.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivImage, ivOverlay;
        VH(@NonNull View v) {
            super(v);
            ivImage   = v.findViewById(R.id.iv_carousel_image);
            ivOverlay = v.findViewById(R.id.iv_carousel_play_overlay);
        }
    }
}
