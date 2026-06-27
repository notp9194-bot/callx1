package com.callx.app.library;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;

import java.util.List;

/**
 * WatchHistoryAdapter — RecyclerView adapter for watch history list
 *
 * Item layout: item_watch_history.xml
 * Displays: thumbnail, owner avatar, owner name, caption, time watched, watch count, % watched
 * Actions: tap → play reel, long-press → delete from history
 */
public class WatchHistoryAdapter extends RecyclerView.Adapter<WatchHistoryAdapter.VH> {

    public interface OnItemClickListener {
        void onPlay(WatchHistoryItem item, int position);
        void onDelete(WatchHistoryItem item, int position);
    }

    private final Context              ctx;
    private final List<WatchHistoryItem> data;
    private final OnItemClickListener  listener;

    public WatchHistoryAdapter(Context ctx,
                               List<WatchHistoryItem> data,
                               OnItemClickListener listener) {
        this.ctx      = ctx;
        this.data     = data;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_watch_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        WatchHistoryItem item = data.get(pos);

        // Thumbnail
        Glide.with(ctx)
            .load(item.thumbUrl)
            .placeholder(R.drawable.bg_reel_comment_btn)
            .centerCrop()
            .into(h.ivThumb);

        // Avatar
        Glide.with(ctx)
            .load(item.ownerPhoto)
            .placeholder(R.drawable.ic_person)
            .circleCrop()
            .into(h.ivAvatar);

        // Text
        h.tvOwnerName.setText("@" + (item.ownerName != null ? item.ownerName : ""));
        h.tvCaption.setText(item.caption != null ? item.caption : "");

        // Watch time (e.g. "2 hours ago")
        if (item.watchedAtMs > 0) {
            CharSequence ago = DateUtils.getRelativeTimeSpanString(
                item.watchedAtMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE);
            h.tvWatchedAt.setText(ago);
        } else {
            h.tvWatchedAt.setText("");
        }

        // Watch count
        if (item.watchCount > 1) {
            h.tvWatchCount.setVisibility(View.VISIBLE);
            h.tvWatchCount.setText("Watched " + item.watchCount + "×");
        } else {
            h.tvWatchCount.setVisibility(View.GONE);
        }

        // Completion bar
        h.pbCompletion.setProgress(item.percentWatched);
        h.tvPercent.setText(item.percentWatched + "%");

        // Media type badge
        boolean isPhoto = "photo_slideshow".equals(item.mediaType);
        h.tvMediaTypeBadge.setText(isPhoto ? "📷 Photos" : "▶ Video");
        h.tvMediaTypeBadge.setVisibility(View.VISIBLE);

        // Click → play
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPlay(item, h.getAdapterPosition());
        });

        // Long press → delete
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onDelete(item, h.getAdapterPosition());
            return true;
        });

        // Delete button
        h.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(item, h.getAdapterPosition());
        });
    }

    @Override public int getItemCount() { return data.size(); }

    public void removeAt(int pos) {
        if (pos >= 0 && pos < data.size()) {
            data.remove(pos);
            notifyItemRemoved(pos);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb, ivAvatar;
        TextView  tvOwnerName, tvCaption, tvWatchedAt, tvWatchCount,
                  tvPercent, tvMediaTypeBadge;
        ProgressBar pbCompletion;
        ImageButton btnDelete;

        VH(@NonNull View v) {
            super(v);
            ivThumb         = v.findViewById(R.id.iv_history_thumb);
            ivAvatar        = v.findViewById(R.id.iv_history_avatar);
            tvOwnerName     = v.findViewById(R.id.tv_history_owner);
            tvCaption       = v.findViewById(R.id.tv_history_caption);
            tvWatchedAt     = v.findViewById(R.id.tv_history_time);
            tvWatchCount    = v.findViewById(R.id.tv_history_watch_count);
            pbCompletion    = v.findViewById(R.id.pb_history_completion);
            tvPercent       = v.findViewById(R.id.tv_history_percent);
            tvMediaTypeBadge = v.findViewById(R.id.tv_history_media_type);
            btnDelete       = v.findViewById(R.id.btn_history_delete);
        }
    }
}
