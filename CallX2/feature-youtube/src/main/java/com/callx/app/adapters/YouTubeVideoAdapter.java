package com.callx.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.sheets.YouTubeVideoOptionsSheet;
import com.callx.app.youtube.R;
import java.util.ArrayList;
import java.util.List;

public class YouTubeVideoAdapter extends RecyclerView.Adapter<YouTubeVideoAdapter.VH> {

    public interface OnVideoClick { void onClick(YouTubeVideo video); }
    public interface ShortsCallbacks {
        void onLike(YouTubeVideo video, ImageButton btn);
        void onComment(YouTubeVideo video);
        void onShare(YouTubeVideo video);
    }

    private final Context       ctx;
    private List<YouTubeVideo>  data;
    private final OnVideoClick  clickListener;
    private boolean             feedAutoplay    = false;
    private ShortsCallbacks     shortsCallbacks = null;
    private YouTubeVideoOptionsSheet.OptionsCallback optionsCallback = null;

    public YouTubeVideoAdapter(Context ctx, List<YouTubeVideo> data, OnVideoClick click) {
        this.ctx           = ctx;
        this.data          = data != null ? new ArrayList<>(data) : new ArrayList<>();
        this.clickListener = click;
    }

    public void setData(List<YouTubeVideo> d) {
        this.data = d != null ? new ArrayList<>(d) : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void removeAt(int pos) {
        if (pos < 0 || pos >= data.size()) return;
        data.remove(pos);
        notifyItemRemoved(pos);
    }

    @Nullable public YouTubeVideo getFirst() {
        return data.isEmpty() ? null : data.get(0);
    }

    public void setFeedAutoplay(boolean autoplay) { this.feedAutoplay = autoplay; }
    public void setShortsCallbacks(ShortsCallbacks cb) { this.shortsCallbacks = cb; }
    public void setOptionsCallback(YouTubeVideoOptionsSheet.OptionsCallback cb) { this.optionsCallback = cb; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_youtube_video, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        YouTubeVideo v = data.get(pos);

        if (h.tvTitle      != null) h.tvTitle.setText(v.title);
        if (h.tvChannel    != null) h.tvChannel.setText(v.uploaderName);
        if (h.tvMeta       != null) h.tvMeta.setText(formatCount(v.viewCount) + " views • " + formatAge(v.uploadedAt));
        if (h.tvDuration   != null) h.tvDuration.setText(formatDuration(v.duration));

        if (h.ivThumb != null)
            Glide.with(ctx).load(v.thumbnailUrl).centerCrop()
                .into(h.ivThumb);
        if (h.ivAvatar != null && v.uploaderPhotoUrl != null)
            Glide.with(ctx).load(v.uploaderPhotoUrl).circleCrop()
                .placeholder(R.drawable.ic_person).into(h.ivAvatar);

        h.itemView.setOnClickListener(x -> {
            if (clickListener != null) clickListener.onClick(v);
        });

        // 3-dot options
        if (h.btnMore != null) {
            h.btnMore.setOnClickListener(x -> {
                if (ctx instanceof FragmentActivity) {
                    YouTubeVideoOptionsSheet sheet = YouTubeVideoOptionsSheet.newInstance(v);
                    if (optionsCallback != null) sheet.setCallback(optionsCallback);
                    sheet.show(((FragmentActivity) ctx).getSupportFragmentManager(), "yt_opts");
                }
            });
        }

        // Shorts inline buttons
        if (h.btnShortLike != null && shortsCallbacks != null) {
            h.btnShortLike.setOnClickListener(x ->
                shortsCallbacks.onLike(v, h.btnShortLike));
        }
        if (h.btnShortComment != null && shortsCallbacks != null)
            h.btnShortComment.setOnClickListener(x -> shortsCallbacks.onComment(v));
        if (h.btnShortShare != null && shortsCallbacks != null)
            h.btnShortShare.setOnClickListener(x -> shortsCallbacks.onShare(v));
    }

    @Override public int getItemCount() { return data.size(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String formatAge(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000)        return "just now";
        if (diff < 3_600_000)     return (diff / 60_000)      + "m ago";
        if (diff < 86_400_000)    return (diff / 3_600_000)   + "h ago";
        if (diff < 2_592_000_000L)return (diff / 86_400_000)  + "d ago";
        if (diff < 31_536_000_000L)return (diff / 2_592_000_000L) + "mo ago";
        return (diff / 31_536_000_000L) + "y ago";
    }

    private String formatDuration(long secs) {
        if (secs <= 0) return "";
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        ImageView        ivThumb, ivAvatar;
        TextView         tvTitle, tvChannel, tvMeta, tvDuration;
        ImageButton      btnMore, btnShortLike, btnShortComment, btnShortShare;

        VH(View v) {
            super(v);
            ivThumb        = v.findViewById(R.id.iv_yt_thumb);
            ivAvatar       = v.findViewById(R.id.iv_yt_video_avatar);
            tvTitle        = v.findViewById(R.id.tv_yt_video_title);
            tvChannel      = v.findViewById(R.id.tv_yt_video_channel);
            tvMeta         = v.findViewById(R.id.tv_yt_video_meta);
            tvDuration     = v.findViewById(R.id.tv_yt_video_duration);
            btnMore        = v.findViewById(R.id.btn_yt_video_more);
            // Shorts-only buttons (null for regular items)
            btnShortLike    = v.findViewById(R.id.btn_yt_short_like);
            btnShortComment = v.findViewById(R.id.btn_yt_short_comment);
            btnShortShare   = v.findViewById(R.id.btn_yt_short_share);
        }
    }
}
