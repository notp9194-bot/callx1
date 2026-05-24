package com.callx.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.youtube.R;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.List;

public class YouTubeVideoAdapter
    extends RecyclerView.Adapter<YouTubeVideoAdapter.VH> {

    public interface OnVideoClickListener { void onClick(YouTubeVideo video); }

    private final Context ctx;
    private List<YouTubeVideo> data;
    private final OnVideoClickListener listener;

    public YouTubeVideoAdapter(Context ctx, List<YouTubeVideo> data,
                               OnVideoClickListener listener) {
        this.ctx      = ctx;
        this.data     = data;
        this.listener = listener;
    }

    public void setData(List<YouTubeVideo> data) {
        this.data = data;
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

        if (video.uploaderPhotoUrl != null)
            Glide.with(ctx).load(video.uploaderPhotoUrl).circleCrop().into(h.ivAvatar);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(video);
        });
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView       ivThumbnail;
        CircleImageView ivAvatar;
        TextView        tvTitle, tvChannel, tvMeta, tvDuration;

        VH(@NonNull View v) {
            super(v);
            ivThumbnail = v.findViewById(R.id.iv_yt_thumb);
            ivAvatar    = v.findViewById(R.id.iv_yt_video_avatar);
            tvTitle     = v.findViewById(R.id.tv_yt_video_title);
            tvChannel   = v.findViewById(R.id.tv_yt_video_channel);
            tvMeta      = v.findViewById(R.id.tv_yt_video_meta);
            tvDuration  = v.findViewById(R.id.tv_yt_video_duration);
        }
    }

    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String formatDuration(long seconds) {
        long m = seconds / 60, s = seconds % 60;
        if (m >= 60) { long h = m / 60; m %= 60; return h + ":" + pad(m) + ":" + pad(s); }
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
