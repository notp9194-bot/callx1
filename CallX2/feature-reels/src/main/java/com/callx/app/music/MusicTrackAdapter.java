package com.callx.app.music;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.models.MusicTrack;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MusicTrackAdapter — Production-level music list adapter.
 *
 * Features:
 *  ✅ Cover art via Glide (rounded square fallback to ic_music_note)
 *  ✅ Track name + artist + genre display
 *  ✅ Duration display (mm:ss)
 *  ✅ Usage count badge (e.g. "14.2K reels")
 *  ✅ Trending rank badge (shown when trendingRank ≤ 50)
 *  ✅ Play/pause preview toggle per row
 *  ✅ Save/bookmark button per row
 *  ✅ Select button returns track to caller
 *  ✅ Long-press opens SoundDetailActivity
 *  ✅ Original sound indicator badge
 */
public class MusicTrackAdapter
        extends RecyclerView.Adapter<MusicTrackAdapter.TrackVH> {

    public interface OnTrackActionListener {
        void onPreviewToggle(MusicTrack track, int position);
        void onSelect(MusicTrack track);
        void onSaveToggle(MusicTrack track, int position);
        void onLongPress(MusicTrack track);
    }

    private final List<MusicTrack>      tracks;
    private final OnTrackActionListener listener;
    private int                         playingPosition = -1;
    private final Set<String>           savedIds        = new HashSet<>();

    public MusicTrackAdapter(List<MusicTrack> tracks, OnTrackActionListener listener) {
        this.tracks   = tracks;
        this.listener = listener;
    }

    public void setPlayingPosition(int pos) {
        this.playingPosition = pos;
    }

    public void setSavedIds(Set<String> ids) {
        savedIds.clear();
        savedIds.addAll(ids);
    }

    public void toggleSavedId(String id) {
        if (savedIds.contains(id)) savedIds.remove(id);
        else savedIds.add(id);
    }

    @NonNull
    @Override
    public TrackVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_music_track, parent, false);
        return new TrackVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull TrackVH h, int pos) {
        MusicTrack track = tracks.get(pos);

        String displayTitle = track.getDisplayTitle();
        h.tvName.setText(displayTitle);
        h.tvArtist.setText(track.artist != null && !track.artist.isEmpty()
            ? track.artist : "Unknown Artist");
        h.tvArtist.setSingleLine(true);
        h.tvArtist.setEllipsize(TextUtils.TruncateAt.END);

        if (h.tvGenre != null) {
            if (track.genre != null && !track.genre.isEmpty()) {
                h.tvGenre.setText(track.genre);
                h.tvGenre.setVisibility(View.VISIBLE);
            } else {
                h.tvGenre.setVisibility(View.GONE);
            }
        }

        long durMs = track.getDurationMs();
        if (h.tvDuration != null) {
            if (durMs > 0) {
                int totalSec = (int)(durMs / 1000);
                h.tvDuration.setText(
                    String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60));
                h.tvDuration.setVisibility(View.VISIBLE);
            } else {
                h.tvDuration.setVisibility(View.GONE);
            }
        }

        if (h.tvUsageCount != null) {
            if (track.usageCount > 0) {
                h.tvUsageCount.setText(formatCount(track.usageCount) + " reels");
                h.tvUsageCount.setVisibility(View.VISIBLE);
            } else {
                h.tvUsageCount.setVisibility(View.GONE);
            }
        }

        if (h.tvTrendingBadge != null) {
            if (track.trendingRank > 0 && track.trendingRank <= 50) {
                h.tvTrendingBadge.setText("#" + track.trendingRank + " Trending");
                h.tvTrendingBadge.setVisibility(View.VISIBLE);
            } else {
                h.tvTrendingBadge.setVisibility(View.GONE);
            }
        }

        if (h.tvOriginalBadge != null) {
            h.tvOriginalBadge.setVisibility(track.isOriginalSound ? View.VISIBLE : View.GONE);
        }

        if (h.ivCover != null) {
            if (track.coverUrl != null && !track.coverUrl.isEmpty()) {
                Glide.with(h.ivCover.getContext())
                    .load(track.coverUrl)
                    .apply(new RequestOptions().centerCrop().placeholder(R.drawable.ic_music_note))
                    .into(h.ivCover);
            } else {
                h.ivCover.setImageResource(R.drawable.ic_music_note);
            }
        }

        boolean isPlaying = pos == playingPosition;
        h.btnPreview.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

        if (h.btnSave != null) {
            boolean isSaved = track.trackId != null && savedIds.contains(track.trackId);
            h.btnSave.setImageResource(isSaved
                ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
            h.btnSave.setOnClickListener(v -> {
                listener.onSaveToggle(track, h.getAdapterPosition());
                toggleSavedId(track.trackId != null ? track.trackId : "");
                notifyItemChanged(h.getAdapterPosition());
            });
        }

        h.btnPreview.setOnClickListener(v ->
            listener.onPreviewToggle(track, h.getAdapterPosition()));
        h.btnSelect.setOnClickListener(v ->
            listener.onSelect(track));
        h.itemView.setOnClickListener(v ->
            listener.onPreviewToggle(track, h.getAdapterPosition()));
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongPress(track);
            return true;
        });
    }

    @Override
    public int getItemCount() { return tracks.size(); }

    private static String formatCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    static class TrackVH extends RecyclerView.ViewHolder {
        ImageView   ivCover;
        TextView    tvName, tvArtist, tvGenre, tvDuration, tvUsageCount,
                    tvTrendingBadge, tvOriginalBadge;
        ImageButton btnPreview, btnSelect, btnSave;

        TrackVH(View v) {
            super(v);
            ivCover         = v.findViewById(R.id.iv_music_cover);
            tvName          = v.findViewById(R.id.tv_music_name);
            tvArtist        = v.findViewById(R.id.tv_music_artist);
            tvGenre         = v.findViewById(R.id.tv_music_genre);
            tvDuration      = v.findViewById(R.id.tv_music_duration);
            tvUsageCount    = v.findViewById(R.id.tv_music_usage_count);
            tvTrendingBadge = v.findViewById(R.id.tv_music_trending_badge);
            tvOriginalBadge = v.findViewById(R.id.tv_music_original_badge);
            btnPreview      = v.findViewById(R.id.btn_music_preview);
            btnSelect       = v.findViewById(R.id.btn_music_select);
            btnSave         = v.findViewById(R.id.btn_music_save);
        }
    }
}
