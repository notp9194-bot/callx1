package com.callx.app.library;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * WatchHistoryGroupedAdapter — Production date-grouped RecyclerView adapter
 *
 * Section headers:
 *   • Today
 *   • Yesterday
 *   • This Week  (Mon–Sun of current ISO week)
 *   • This Month
 *   • Earlier
 *
 * View types:
 *   TYPE_HEADER  (0) — date section header
 *   TYPE_ITEM    (1) — reel card with thumbnail, owner, completion bar, watch count
 *
 * Features:
 *   ✅ Section-collapsed items (groups under date headers)
 *   ✅ Watch % completion progress bar per item
 *   ✅ Watch count badge ("Watched 3×")
 *   ✅ Media type badge (📷 Photos / ▶ Video)
 *   ✅ Delete button + long-press
 *   ✅ Tap-to-play
 *   ✅ Efficient: uses List<Object> (String header or WatchHistoryItem) internally
 */
public class WatchHistoryGroupedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnItemActionListener {
        void onPlay(WatchHistoryItem item);
        void onDelete(WatchHistoryItem item);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM   = 1;

    private final Context             ctx;
    private final OnItemActionListener listener;

    // Flat list: mix of String (header) and WatchHistoryItem
    private final List<Object> displayList = new ArrayList<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public WatchHistoryGroupedAdapter(Context ctx, OnItemActionListener listener) {
        this.ctx      = ctx;
        this.listener = listener;
    }

    // ── Data update ───────────────────────────────────────────────────────────

    /**
     * Replace dataset with a new sorted list (newest first).
     * Groups items into date sections automatically.
     */
    public void submitList(List<WatchHistoryItem> items) {
        displayList.clear();

        String lastHeader = null;
        for (WatchHistoryItem item : items) {
            String header = getDateHeader(item.watchedAtMs);
            if (!header.equals(lastHeader)) {
                displayList.add(header);
                lastHeader = header;
            }
            displayList.add(item);
        }
        notifyDataSetChanged();
    }

    /** Remove a single item from the displayed list. */
    public void removeItem(WatchHistoryItem target) {
        int idx = displayList.indexOf(target);
        if (idx < 0) return;
        displayList.remove(idx);
        notifyItemRemoved(idx);

        // Remove header if it has no more items following it
        if (idx > 0 && displayList.get(idx - 1) instanceof String) {
            boolean hasFollowingItem = idx < displayList.size()
                && displayList.get(idx) instanceof WatchHistoryItem;
            if (!hasFollowingItem) {
                displayList.remove(idx - 1);
                notifyItemRemoved(idx - 1);
            }
        }
    }

    public int getItemsCount() {
        int count = 0;
        for (Object o : displayList) if (o instanceof WatchHistoryItem) count++;
        return count;
    }

    // ── RecyclerView.Adapter ──────────────────────────────────────────────────

    @Override public int getItemViewType(int position) {
        return displayList.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
    }

    @Override public int getItemCount() { return displayList.size(); }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View v = inf.inflate(R.layout.item_watch_history_header, parent, false);
            return new HeaderVH(v);
        } else {
            View v = inf.inflate(R.layout.item_watch_history, parent, false);
            return new ItemVH(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).bind((String) displayList.get(position));
        } else if (holder instanceof ItemVH) {
            ((ItemVH) holder).bind((WatchHistoryItem) displayList.get(position));
        }
    }

    // ── ViewHolder: Header ────────────────────────────────────────────────────

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;

        HeaderVH(@NonNull View v) {
            super(v);
            tvHeader = v.findViewById(R.id.tv_history_date_header);
        }

        void bind(String header) {
            tvHeader.setText(header);
        }
    }

    // ── ViewHolder: Item ──────────────────────────────────────────────────────

    class ItemVH extends RecyclerView.ViewHolder {
        ImageView   ivThumb, ivAvatar;
        TextView    tvOwnerName, tvCaption, tvWatchedAt, tvWatchCount,
                    tvPercent, tvMediaTypeBadge, tvDurationBadge;
        ProgressBar pbCompletion;
        ImageButton btnDelete;

        ItemVH(@NonNull View v) {
            super(v);
            ivThumb          = v.findViewById(R.id.iv_history_thumb);
            ivAvatar         = v.findViewById(R.id.iv_history_avatar);
            tvOwnerName      = v.findViewById(R.id.tv_history_owner);
            tvCaption        = v.findViewById(R.id.tv_history_caption);
            tvWatchedAt      = v.findViewById(R.id.tv_history_time);
            tvWatchCount     = v.findViewById(R.id.tv_history_watch_count);
            pbCompletion     = v.findViewById(R.id.pb_history_completion);
            tvPercent        = v.findViewById(R.id.tv_history_percent);
            tvMediaTypeBadge = v.findViewById(R.id.tv_history_media_type);
            tvDurationBadge  = v.findViewById(R.id.tv_history_duration);
            btnDelete        = v.findViewById(R.id.btn_history_delete);
        }

        void bind(WatchHistoryItem item) {
            // Thumbnail
            Glide.with(ctx)
                .load(item.thumbUrl)
                .placeholder(R.drawable.bg_reel_comment_btn)
                .centerCrop()
                .into(ivThumb);

            // Avatar
            Glide.with(ctx)
                .load(item.ownerPhoto)
                .placeholder(R.drawable.ic_person)
                .circleCrop()
                .into(ivAvatar);

            // Text
            tvOwnerName.setText("@" + (item.ownerName != null ? item.ownerName : "?"));
            tvCaption.setText(item.caption != null && !item.caption.isEmpty()
                ? item.caption : "No caption");

            // Relative time ("2 hours ago")
            if (item.watchedAtMs > 0) {
                tvWatchedAt.setText(DateUtils.getRelativeTimeSpanString(
                    item.watchedAtMs, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            } else {
                tvWatchedAt.setText("");
            }

            // Watch count badge
            if (item.watchCount > 1) {
                tvWatchCount.setVisibility(View.VISIBLE);
                tvWatchCount.setText("Watched " + item.watchCount + "×");
            } else {
                tvWatchCount.setVisibility(View.GONE);
            }

            // Completion bar
            pbCompletion.setProgress(item.percentWatched);
            tvPercent.setText(item.percentWatched + "%");

            // Media type badge
            boolean isPhoto = "photo_slideshow".equals(item.mediaType);
            tvMediaTypeBadge.setText(isPhoto ? "📷 Photos" : "▶ Video");

            // Duration badge
            if (tvDurationBadge != null && item.duration > 0) {
                tvDurationBadge.setVisibility(View.VISIBLE);
                tvDurationBadge.setText(formatDuration(item.duration));
            } else if (tvDurationBadge != null) {
                tvDurationBadge.setVisibility(View.GONE);
            }

            // Tap → play
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onPlay(item);
            });

            // Long press → delete
            itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onDelete(item);
                return true;
            });

            // Delete button
            if (btnDelete != null) {
                btnDelete.setOnClickListener(v -> {
                    if (listener != null) listener.onDelete(item);
                });
            }
        }

        private String formatDuration(int secs) {
            if (secs < 60) return secs + "s";
            return (secs / 60) + "m " + (secs % 60) + "s";
        }
    }

    // ── Date header logic ─────────────────────────────────────────────────────

    /**
     * Returns a human-readable section header for a timestamp.
     * Sections (in order, newest first):
     *   Today → Yesterday → This Week → This Month → [Month Year]
     */
    private String getDateHeader(long tsMs) {
        if (tsMs <= 0) return "Earlier";

        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(tsMs);

        // Today
        if (isSameDay(now, then)) return "Today";

        // Yesterday
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(yesterday, then)) return "Yesterday";

        // This week (within 7 days)
        long diffMs = now.getTimeInMillis() - then.getTimeInMillis();
        if (diffMs < 7L * 24 * 60 * 60 * 1000) return "This Week";

        // This month
        if (now.get(Calendar.YEAR)  == then.get(Calendar.YEAR) &&
            now.get(Calendar.MONTH) == then.get(Calendar.MONTH)) {
            return "This Month";
        }

        // Named month + year (e.g. "May 2025")
        String[] months = {"January","February","March","April","May","June",
                           "July","August","September","October","November","December"};
        int m = then.get(Calendar.MONTH);
        int y = then.get(Calendar.YEAR);
        return months[m] + (y != now.get(Calendar.YEAR) ? " " + y : "");
    }

    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR)         == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR)  == b.get(Calendar.DAY_OF_YEAR);
    }
}
