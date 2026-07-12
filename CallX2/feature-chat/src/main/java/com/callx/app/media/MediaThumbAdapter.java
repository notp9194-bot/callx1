package com.callx.app.media;

import android.view.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.media.canvas.MediaThumbCanvasView;
import java.util.List;
import com.callx.app.group.GroupInfoActivity;

/**
 * MediaThumbAdapter — 3-column media grid for GroupInfoActivity.
 *
 * v2 — Canvas cell (perf): iv_media_thumb went from a
 * FrameLayout>ImageView pair (Glide.into(ImageView), centerCrop() transform
 * run on every load) to a single MediaThumbCanvasView that decodes the raw
 * Bitmap once via Glide.asBitmap() and draws it with a cached center-crop
 * Matrix — see MediaThumbCanvasView's class doc for the full rationale.
 * onBindViewHolder() itself is unchanged in shape (still just "set the URL,
 * set the click listener"); only what's underneath the row changed.
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
        h.thumbView.setImageUrl(url);
        h.thumbView.setOnThumbClickListener(() -> listener.onClick(url));
    }

    @Override public int getItemCount() {
        return Math.min(urls.size(), 9); // Show max 9 in info screen
    }

    static class VH extends RecyclerView.ViewHolder {
        MediaThumbCanvasView thumbView;
        VH(@NonNull View itemView) {
            super(itemView);
            thumbView = itemView.findViewById(R.id.iv_media_thumb);
        }
    }
}
