package com.callx.app.editor;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Horizontal clip-card list for ReelMultiClipTimelineActivity. Supports
 * drag-to-reorder (driven externally via ItemTouchHelper calling
 * {@link #onItemMove}), tap-to-trim, and per-card delete/add.
 */
public class TimelineClipAdapter extends RecyclerView.Adapter<TimelineClipAdapter.VH> {

    public interface Listener {
        void onClipTapped(int position);
        void onClipDeleted(int position);
        void onAddClipTapped();
        void onOrderChanged();
    }

    private final List<TimelineClip> clips;
    private final Listener listener;
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbCache = new LruCache<>(24);

    private static final int TYPE_CLIP = 0;
    private static final int TYPE_ADD  = 1;

    public TimelineClipAdapter(List<TimelineClip> clips, Listener listener) {
        this.clips = clips;
        this.listener = listener;
    }

    @Override public int getItemViewType(int position) {
        return position == clips.size() ? TYPE_ADD : TYPE_CLIP;
    }

    @Override public int getItemCount() { return clips.size() + 1; } // +1 for the trailing "Add" card

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_ADD ? R.layout.item_timeline_add_clip : R.layout.item_timeline_clip;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        if (getItemViewType(position) == TYPE_ADD) {
            h.itemView.setOnClickListener(v -> { if (listener != null) listener.onAddClipTapped(); });
            return;
        }
        TimelineClip clip = clips.get(position);
        if (h.tvIndex != null) h.tvIndex.setText(String.valueOf(position + 1));
        if (h.tvDuration != null) {
            h.tvDuration.setText(formatMs(clip.trimmedDurationMs()));
        }
        if (h.ivThumb != null) loadThumb(h, clip);
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClipTapped(h.getBindingAdapterPosition()); });
        if (h.btnDelete != null) {
            h.btnDelete.setVisibility(clips.size() > 1 ? View.VISIBLE : View.GONE);
            h.btnDelete.setOnClickListener(v -> { if (listener != null) listener.onClipDeleted(h.getBindingAdapterPosition()); });
        }
    }

    private void loadThumb(VH h, TimelineClip clip) {
        String key = clip.uriOrPath + "#" + clip.trimStartMs;
        Bitmap cached = thumbCache.get(key);
        if (cached != null) { h.ivThumb.setImageBitmap(cached); return; }
        h.ivThumb.setImageBitmap(null);
        thumbExecutor.execute(() -> {
            Bitmap bmp = null;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                if (clip.isFilePath) mmr.setDataSource(clip.uriOrPath);
                else mmr.setDataSource(h.itemView.getContext(), clip.toUri());
                bmp = mmr.getFrameAtTime(clip.trimStartMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception ignored) {
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
            if (bmp != null) {
                thumbCache.put(key, bmp);
                Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    if (h.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                        h.ivThumb.setImageBitmap(finalBmp);
                    }
                });
            }
        });
    }

    /** Called live while the user drags a card, from ItemTouchHelper.Callback#onMove. */
    public void onItemMove(int from, int to) {
        if (from < 0 || to < 0 || from >= clips.size() || to >= clips.size()) return;
        Collections.swap(clips, from, to);
        notifyItemMoved(from, to);
    }

    public void onDragFinished() {
        if (listener != null) listener.onOrderChanged();
    }

    public void shutdown() { thumbExecutor.shutdownNow(); }

    private static String formatMs(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView  tvDuration, tvIndex;
        View      btnDelete;
        VH(@NonNull View itemView) {
            super(itemView);
            ivThumb    = itemView.findViewById(R.id.iv_clip_thumb);
            tvDuration = itemView.findViewById(R.id.tv_clip_duration);
            tvIndex    = itemView.findViewById(R.id.tv_clip_index);
            btnDelete  = itemView.findViewById(R.id.btn_clip_delete);
        }
    }
}
