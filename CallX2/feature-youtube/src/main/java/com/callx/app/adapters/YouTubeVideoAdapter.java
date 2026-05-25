package com.callx.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.sheets.YouTubeVideoOptionsSheet;
import com.callx.app.utils.YouTubeDownloadManager;
import com.callx.app.youtube.R;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeVideoAdapter
 *
 * FIX: 3-dot (more options) button properly wired to YouTubeVideoOptionsSheet
 * — BottomSheetDialogFragment with Watch Later, Share, Channel, Report options.
 */
public class YouTubeVideoAdapter
    extends RecyclerView.Adapter<YouTubeVideoAdapter.VH> {

    public interface OnVideoClickListener { void onClick(YouTubeVideo video); }

    private final Context ctx;
    private List<YouTubeVideo> data;
    private final OnVideoClickListener listener;

    // Optional callback for feed-level actions (not interested / deleted)
    private YouTubeVideoOptionsSheet.OptionsCallback optionsCallback;

    // Playback-in-feeds setting (controlled by YouTubeHomeFragment)
    private boolean feedAutoplay = false;

    public YouTubeVideoAdapter(Context ctx, List<YouTubeVideo> data,
                               OnVideoClickListener listener) {
        this.ctx      = ctx;
        this.data     = data != null ? new ArrayList<>(data) : new ArrayList<>();
        this.listener = listener;
    }

    public void setOptionsCallback(YouTubeVideoOptionsSheet.OptionsCallback cb) {
        this.optionsCallback = cb;
    }

    /**
     * Control muted autoplay of video thumbnails in the feed.
     * Called from YouTubeHomeFragment based on "Playback in feeds" setting.
     * true = autoplay muted previews on scroll; false = static thumbnails.
     */
    public void setFeedAutoplay(boolean enable) {
        this.feedAutoplay = enable;
        notifyDataSetChanged();
    }

    public boolean isFeedAutoplay() { return feedAutoplay; }

    public void setData(List<YouTubeVideo> data) {
        this.data = data != null ? new ArrayList<>(data) : new ArrayList<>();
        notifyDataSetChanged();
    }

    public boolean isEmpty() { return data == null || data.isEmpty(); }

    public YouTubeVideo getFirst() { return (data != null && !data.isEmpty()) ? data.get(0) : null; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_youtube_video, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        YouTubeVideo video = data.get(pos);

        Glide.with(ctx).load(video.thumbnailUrl)
            .placeholder(R.drawable.bg_yt_thumb_placeholder)
            .centerCrop().into(h.ivThumbnail);

        h.tvTitle.setText(video.title);
        h.tvChannel.setText(video.uploaderName);
        h.tvMeta.setText(formatCount(video.viewCount) + " views · " +
            formatAge(video.uploadedAt));
        h.tvDuration.setText(formatDuration(video.duration));

        if (video.uploaderPhotoUrl != null && !video.uploaderPhotoUrl.isEmpty())
            Glide.with(ctx).load(video.uploaderPhotoUrl).circleCrop().into(h.ivAvatar);
        else
            h.ivAvatar.setImageResource(R.drawable.ic_person);

        // Video click → play
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(video);
        });

        // Downloaded badge
        if (h.tvDownloadedBadge != null) {
            h.tvDownloadedBadge.setVisibility(
                YouTubeDownloadManager.isDownloaded(ctx, video.videoId)
                    ? android.view.View.VISIBLE : android.view.View.GONE);
        }

        // 3-dot click → open BottomSheet options
        h.btnMore.setOnClickListener(v -> showOptionsSheet(video, pos));
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    private void removeVideoById(String vid) {
        for (int i = 0; i < data.size(); i++) {
            if (vid != null && vid.equals(data.get(i).videoId)) {
                data.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    // ── Options Sheet ─────────────────────────────────────────────────────────

    private void showOptionsSheet(YouTubeVideo video, int position) {
        // Need FragmentManager → ctx must be FragmentActivity
        if (!(ctx instanceof FragmentActivity)) return;
        FragmentManager fm = ((FragmentActivity) ctx).getSupportFragmentManager();

        YouTubeVideoOptionsSheet sheet = YouTubeVideoOptionsSheet.newInstance(video);

        // Wire callbacks → remove from adapter list on Not Interested or Delete
        sheet.setCallback(new YouTubeVideoOptionsSheet.OptionsCallback() {
            @Override
            public void onNotInterested(String vid) {
                removeVideoById(vid);
                if (optionsCallback != null) optionsCallback.onNotInterested(vid);
            }
            @Override
            public void onVideoDeleted(String vid) {
                removeVideoById(vid);
                if (optionsCallback != null) optionsCallback.onVideoDeleted(vid);
            }
        });

        sheet.show(fm, "yt_video_options");
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        ImageView       ivThumbnail;
        CircleImageView ivAvatar;
        TextView        tvTitle, tvChannel, tvMeta, tvDuration;
        ImageButton     btnMore;
        android.widget.TextView tvDownloadedBadge;

        VH(@NonNull View v) {
            super(v);
            ivThumbnail = v.findViewById(R.id.iv_yt_thumb);
            ivAvatar    = v.findViewById(R.id.iv_yt_video_avatar);
            tvTitle     = v.findViewById(R.id.tv_yt_video_title);
            tvChannel   = v.findViewById(R.id.tv_yt_video_channel);
            tvMeta      = v.findViewById(R.id.tv_yt_video_meta);
            tvDuration  = v.findViewById(R.id.tv_yt_video_duration);
            btnMore          = v.findViewById(R.id.btn_yt_video_more);
            tvDownloadedBadge = v.findViewById(R.id.tv_yt_downloaded_badge);
        }
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String formatDuration(long seconds) {
        long m = seconds / 60, s = seconds % 60;
        if (m >= 60) { long hr = m / 60; m %= 60; return hr + ":" + pad(m) + ":" + pad(s); }
        return m + ":" + pad(s);
    }

    private String pad(long n) { return n < 10 ? "0" + n : String.valueOf(n); }

    private String formatAge(long ts) {
        long diff = System.currentTimeMillis() - ts;
        long mins = diff / 60000;
        if (mins < 60)   return mins + "m ago";
        long hrs  = mins / 60;
        if (hrs  < 24)   return hrs  + "h ago";
        long days = hrs  / 24;
        if (days < 30)   return days + "d ago";
        long months = days / 30;
        if (months < 12) return months + "mo ago";
        return (months / 12) + "y ago";
    }
}
