package com.callx.app.youtube.core.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.youtube.core.models.YouTubeVideo;
import com.callx.app.youtube.core.navigator.YTNavigatorProvider;
import com.callx.app.youtube.core.R;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeVideoAdapter — yt-core shared adapter.
 *
 * Deliberately has NO dependency on yt-player, yt-library, or any Activity
 * outside yt-core. Cross-module actions use:
 *   - YTNavigatorProvider for navigation (play, channel)
 *   - OnMoreClickListener callback so callers (yt-home, yt-search, etc.)
 *     can wire their own BottomSheet (e.g. YouTubeVideoOptionsSheet).
 */
public class YouTubeVideoAdapter
        extends RecyclerView.Adapter<YouTubeVideoAdapter.VH> {

    // ── Callbacks ─────────────────────────────────────────────────────────────

    /** Called when a video card is tapped (play intent). */
    public interface OnVideoClickListener {
        void onClick(YouTubeVideo video);
    }

    /** Called when the 3-dot (more) button is tapped. Caller shows the sheet. */
    public interface OnMoreClickListener {
        void onMore(YouTubeVideo video, int position);
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Context             ctx;
    private       List<YouTubeVideo>  data;
    private final OnVideoClickListener clickListener;
    private       OnMoreClickListener  moreListener;

    // ── Constructor ───────────────────────────────────────────────────────────

    public YouTubeVideoAdapter(Context ctx,
                               List<YouTubeVideo> data,
                               OnVideoClickListener clickListener) {
        this.ctx           = ctx;
        this.data          = data != null ? new ArrayList<>(data) : new ArrayList<>();
        this.clickListener = clickListener;
    }

    public void setMoreListener(OnMoreClickListener listener) {
        this.moreListener = listener;
    }

    // ── Data helpers ──────────────────────────────────────────────────────────

    public void setData(List<YouTubeVideo> data) {
        this.data = data != null ? new ArrayList<>(data) : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void removeById(String videoId) {
        for (int i = 0; i < data.size(); i++) {
            if (videoId != null && videoId.equals(data.get(i).videoId)) {
                data.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public boolean isEmpty()     { return data == null || data.isEmpty(); }

    public YouTubeVideo getFirst() {
        return (data != null && !data.isEmpty()) ? data.get(0) : null;
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx)
                .inflate(R.layout.item_youtube_video, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        YouTubeVideo video = data.get(pos);

        // Thumbnail
        Glide.with(ctx).load(video.thumbnailUrl)
                .placeholder(R.drawable.bg_yt_thumb_placeholder)
                .centerCrop().into(h.ivThumbnail);

        // Text
        h.tvTitle.setText(video.title);
        h.tvChannel.setText(video.uploaderName);
        h.tvMeta.setText(formatCount(video.viewCount) + " views \u00b7 "
                + formatAge(video.uploadedAt));
        h.tvDuration.setText(formatDuration(video.duration));

        // Avatar
        if (video.uploaderPhotoUrl != null && !video.uploaderPhotoUrl.isEmpty()) {
            Glide.with(ctx).load(video.uploaderPhotoUrl).circleCrop().into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        // Video click → play via navigator (no direct yt-player import needed)
        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onClick(video);
        });

        // Avatar click → open channel via navigator
        h.ivAvatar.setOnClickListener(v -> {
            YTNavigatorProvider.get().openChannel(ctx,
                    video.uploaderUid != null ? video.uploaderUid : "");
        });

        // 3-dot click → delegate to caller via OnMoreClickListener
        h.btnMore.setOnClickListener(v -> {
            if (moreListener != null) moreListener.onMore(video, pos);
        });
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        ImageView       ivThumbnail;
        CircleImageView ivAvatar;
        TextView        tvTitle, tvChannel, tvMeta, tvDuration;
        ImageButton     btnMore;

        VH(@NonNull View v) {
            super(v);
            ivThumbnail = v.findViewById(R.id.iv_yt_thumb);
            ivAvatar    = v.findViewById(R.id.iv_yt_video_avatar);
            tvTitle     = v.findViewById(R.id.tv_yt_video_title);
            tvChannel   = v.findViewById(R.id.tv_yt_video_channel);
            tvMeta      = v.findViewById(R.id.tv_yt_video_meta);
            tvDuration  = v.findViewById(R.id.tv_yt_video_duration);
            btnMore     = v.findViewById(R.id.btn_yt_video_more);
        }
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    private String formatCount(long n) {
        if (n >= 1_000_000_000L) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)      return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)          return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String formatDuration(long seconds) {
        long m = seconds / 60, s = seconds % 60;
        if (m >= 60) { long hr = m / 60; m %= 60;
            return hr + ":" + pad(m) + ":" + pad(s); }
        return m + ":" + pad(s);
    }

    private String pad(long n) { return n < 10 ? "0" + n : String.valueOf(n); }

    private String formatAge(long ts) {
        long diff   = System.currentTimeMillis() - ts;
        long mins   = diff / 60_000;
        if (mins  < 60)   return mins + "m ago";
        long hrs  = mins  / 60;
        if (hrs   < 24)   return hrs  + "h ago";
        long days = hrs   / 24;
        if (days  < 30)   return days + "d ago";
        long mon  = days  / 30;
        if (mon   < 12)   return mon  + "mo ago";
        return (mon / 12) + "y ago";
    }
}
