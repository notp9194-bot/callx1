package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.activities.YouTubeReportActivity;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ServerValue;
import java.util.List;

/**
 * Generic video card adapter used in Home, Subscriptions, Search, Related.
 * Each card shows:
 * - Thumbnail, title, channel name, view count, relative time
 * - Three-dot overflow menu: Save to Watch Later, Add to Playlist,
 *   Not Interested, Don't Recommend Channel, Report, Share, Copy Link
 */
public class YouTubeVideoAdapter
    extends RecyclerView.Adapter<YouTubeVideoAdapter.VideoVH> {

    public interface OnVideoClick { void onVideoClick(YouTubeVideo video); }

    private final Context      ctx;
    private       List<YouTubeVideo> data;
    private final OnVideoClick click;
    private final String       myUid;

    public YouTubeVideoAdapter(Context ctx, List<YouTubeVideo> data, OnVideoClick click) {
        this.ctx   = ctx;
        this.data  = data;
        this.click = click;
        this.myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
    }

    public void setData(List<YouTubeVideo> d) { data = d; notifyDataSetChanged(); }
    public boolean isEmpty()    { return data == null || data.isEmpty(); }
    public YouTubeVideo getFirst() { return (data != null && !data.isEmpty()) ? data.get(0) : null; }

    @NonNull @Override
    public VideoVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_yt_video, p, false);
        return new VideoVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoVH h, int pos) {
        YouTubeVideo v = data.get(pos);

        Glide.with(ctx).load(v.thumbnailUrl).placeholder(R.drawable.bg_yt_placeholder)
            .centerCrop().into(h.ivThumb);
        h.tvTitle.setText(v.title);
        h.tvChannel.setText(v.uploaderName);
        h.tvMeta.setText(formatCount(v.viewCount) + " views • " + relativeTime(v.uploadedAt));

        if (h.tvDuration != null)
            h.tvDuration.setText(formatDuration(v.duration));

        // Live badge
        if (h.tvLiveBadge != null)
            h.tvLiveBadge.setVisibility(v.isLive ? View.VISIBLE : View.GONE);

        // Shorts badge
        if (h.tvShortsBadge != null)
            h.tvShortsBadge.setVisibility(v.isShort ? View.VISIBLE : View.GONE);

        h.itemView.setOnClickListener(x -> click.onVideoClick(v));

        // Three-dot menu
        if (h.btnMore != null) h.btnMore.setOnClickListener(x -> showMenu(v, h.btnMore));
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    private void showMenu(YouTubeVideo v, View anchor) {
        PopupMenu popup = new PopupMenu(ctx, anchor);
        popup.getMenu().add(0, 1, 0, "Save to Watch Later");
        popup.getMenu().add(0, 2, 0, "Add to playlist");
        popup.getMenu().add(0, 3, 0, "Not interested");
        popup.getMenu().add(0, 4, 0, "Don't recommend channel");
        popup.getMenu().add(0, 5, 0, "Report");
        popup.getMenu().add(0, 6, 0, "Share");
        popup.getMenu().add(0, 7, 0, "Copy link");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    if (!myUid.isEmpty()) {
                        YouTubeFirebaseUtils.watchLaterRef(myUid).child(v.videoId)
                            .setValue(System.currentTimeMillis());
                        Toast.makeText(ctx, "Saved to Watch Later", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case 2:
                    ctx.startActivity(new Intent(ctx,
                        com.callx.app.activities.YouTubePlaylistPickerActivity.class)
                        .putExtra("video_id", v.videoId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case 3:
                    if (!myUid.isEmpty()) {
                        YouTubeFirebaseUtils.notInterestedRef(myUid).child(v.videoId).setValue(true);
                        data.remove(v);
                        notifyDataSetChanged();
                        Toast.makeText(ctx, "Noted", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case 4:
                    if (!myUid.isEmpty() && v.uploaderUid != null) {
                        YouTubeFirebaseUtils.blockedChannelsRef(myUid).child(v.uploaderUid)
                            .setValue(true);
                        data.removeIf(x -> v.uploaderUid.equals(x.uploaderUid));
                        notifyDataSetChanged();
                        Toast.makeText(ctx, "Channel blocked", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case 5:
                    ctx.startActivity(new Intent(ctx, YouTubeReportActivity.class)
                        .putExtra("video_id", v.videoId).putExtra("type", "video")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case 6: {
                    Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT,
                        v.title + "\ncallx://youtube/video/" + v.videoId);
                    ctx.startActivity(Intent.createChooser(share, "Share Video")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                }
                case 7:
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "link", "callx://youtube/video/" + v.videoId));
                    Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show();
                    break;
            }
            return true;
        });
        popup.show();
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────
    static class VideoVH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView  tvTitle, tvChannel, tvMeta, tvDuration, tvLiveBadge, tvShortsBadge;
        ImageButton btnMore;
        VideoVH(View v) {
            super(v);
            ivThumb       = v.findViewById(R.id.iv_yt_thumb);
            tvTitle       = v.findViewById(R.id.tv_yt_video_title);
            tvChannel     = v.findViewById(R.id.tv_yt_channel_name);
            tvMeta        = v.findViewById(R.id.tv_yt_video_meta);
            tvDuration    = v.findViewById(R.id.tv_yt_duration);
            tvLiveBadge   = v.findViewById(R.id.tv_yt_live_badge);
            tvShortsBadge = v.findViewById(R.id.tv_yt_shorts_badge);
            btnMore       = v.findViewById(R.id.btn_yt_video_more);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String formatDuration(long secs) {
        if (secs <= 0) return "";
        long m = secs / 60, s = secs % 60;
        if (m >= 60) return (m / 60) + ":" + pad(m % 60) + ":" + pad(s);
        return m + ":" + pad(s);
    }
    private String pad(long n) { return n < 10 ? "0" + n : String.valueOf(n); }

    private String relativeTime(long ms) {
        long diff = System.currentTimeMillis() - ms;
        long s    = diff / 1000, m = s / 60, h = m / 60, d = h / 24;
        if (d >= 365) return (d / 365) + " year" + (d / 365 > 1 ? "s" : "") + " ago";
        if (d >= 30)  return (d / 30)  + " month"+ (d / 30  > 1 ? "s" : "") + " ago";
        if (d >= 7)   return (d / 7)   + " week" + (d / 7   > 1 ? "s" : "") + " ago";
        if (d >= 1)   return d          + " day"  + (d        > 1 ? "s" : "") + " ago";
        if (h >= 1)   return h          + " hour" + (h        > 1 ? "s" : "") + " ago";
        if (m >= 1)   return m          + " min"  + (m        > 1 ? "s" : "") + " ago";
        return "Just now";
    }
}
