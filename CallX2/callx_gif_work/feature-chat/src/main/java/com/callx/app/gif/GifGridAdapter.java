package com.callx.app.gif;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;
import com.giphy.sdk.core.models.Media;

import java.util.ArrayList;
import java.util.List;

/**
 * GifGridAdapter — 2-column grid of GIFs.
 * Loads animated GIFs via Glide (asGif() — memory + disk cache automatic).
 * Offline: Glide serves from disk cache; new GIFs won't appear.
 */
public class GifGridAdapter extends RecyclerView.Adapter<GifGridAdapter.GifVH> {

    public interface OnGifClickListener {
        void onGifClick(Media gif);
    }

    private final Context           ctx;
    private final OnGifClickListener listener;
    private final List<Media>        gifs = new ArrayList<>();

    public GifGridAdapter(Context ctx, OnGifClickListener listener) {
        this.ctx      = ctx;
        this.listener = listener;
    }

    public void setGifs(List<Media> newGifs) {
        gifs.clear();
        if (newGifs != null) gifs.addAll(newGifs);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public GifVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx)
                .inflate(R.layout.item_gif_grid, parent, false);
        return new GifVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull GifVH h, int pos) {
        Media gif = gifs.get(pos);

        // Prefer previewGif URL (smaller) for thumbnail in grid
        String thumbUrl = "";
        try {
            if (gif.getImages() != null && gif.getImages().getPreviewGif() != null
                    && gif.getImages().getPreviewGif().getGifUrl() != null) {
                thumbUrl = gif.getImages().getPreviewGif().getGifUrl();
            } else if (gif.getImages() != null && gif.getImages().getFixedWidthSmall() != null
                    && gif.getImages().getFixedWidthSmall().getGifUrl() != null) {
                thumbUrl = gif.getImages().getFixedWidthSmall().getGifUrl();
            }
        } catch (Exception ignored) {}

        // Load animated GIF — DiskCacheStrategy.ALL caches for offline
        Glide.with(ctx)
                .asGif()
                .load(thumbUrl)
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.bg_date_chip)
                        .error(R.drawable.bg_date_chip))
                .into(h.ivGif);

        h.itemView.setOnClickListener(v -> listener.onGifClick(gif));
    }

    @Override public int getItemCount() { return gifs.size(); }

    static class GifVH extends RecyclerView.ViewHolder {
        ImageView ivGif;
        GifVH(View v) {
            super(v);
            ivGif = v.findViewById(R.id.iv_gif_item);
        }
    }
}
