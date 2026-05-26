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
import com.callx.app.models.YouTubeNotification;
import com.callx.app.youtube.R;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

public class YouTubeNotificationAdapter
    extends RecyclerView.Adapter<YouTubeNotificationAdapter.VH> {

    public interface OnNotifClick { void onClick(YouTubeNotification n); }

    private final Context ctx;
    private List<YouTubeNotification> data;
    private final OnNotifClick clickListener;

    public YouTubeNotificationAdapter(Context ctx, List<YouTubeNotification> data,
                                      OnNotifClick clickListener) {
        this.ctx           = ctx;
        this.data          = data != null ? new ArrayList<>(data) : new ArrayList<>();
        this.clickListener = clickListener;
    }

    public void setData(List<YouTubeNotification> d) {
        this.data = d != null ? new ArrayList<>(d) : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_youtube_notification, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        YouTubeNotification n = data.get(pos);

        if (h.tvText   != null) h.tvText.setText(buildText(n));
        if (h.tvTime   != null) h.tvTime.setText(formatAge(n.timestamp));
        if (h.ivUnread != null) h.ivUnread.setVisibility(n.read ? View.GONE : View.VISIBLE);

        if (h.ivAvatar != null)
            Glide.with(ctx).load(n.fromPhotoUrl).circleCrop()
                .placeholder(R.drawable.ic_person).into(h.ivAvatar);

        if (h.ivThumb != null && n.thumbnailUrl != null)
            Glide.with(ctx).load(n.thumbnailUrl).centerCrop().into(h.ivThumb);

        h.itemView.setAlpha(n.read ? 0.75f : 1f);
        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onClick(n);
        });
    }

    @Override public int getItemCount() { return data.size(); }

    private String buildText(YouTubeNotification n) {
        String name = n.fromName != null ? n.fromName : "Someone";
        switch (n.type != null ? n.type : "") {
            case "new_video":
                return name + " posted a new video" +
                    (n.videoTitle != null ? ": " + n.videoTitle : "");
            case "comment":
                return name + " commented" +
                    (n.commentText != null && !n.commentText.isEmpty()
                        ? ": " + n.commentText : " on your video");
            case "reply":
                return name + " replied to your comment";
            case "like":
                return name + " liked your video" +
                    (n.videoTitle != null ? ": " + n.videoTitle : "");
            case "subscribe":
                return name + " subscribed to your channel";
            case "mention":
                return name + " mentioned you in a comment";
            case "live":
                return name + " is now live!";
            case "like_milestone":
                return "Your video just reached " + n.videoTitle + " likes!";
            default:
                return name + " sent you a notification";
        }
    }

    private String formatAge(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000)         return "just now";
        if (diff < 3_600_000)      return (diff / 60_000) + "m ago";
        if (diff < 86_400_000)     return (diff / 3_600_000) + "h ago";
        if (diff < 604_800_000L)   return (diff / 86_400_000) + "d ago";
        return (diff / 604_800_000L) + "w ago";
    }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        ImageView       ivThumb, ivUnread;
        TextView        tvText, tvTime;

        VH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_yt_notif_avatar);
            ivThumb  = v.findViewById(R.id.iv_yt_notif_thumb);
            ivUnread = v.findViewById(R.id.iv_yt_notif_dot);
            tvText   = v.findViewById(R.id.tv_yt_notif_text);
            tvTime   = v.findViewById(R.id.tv_yt_notif_time);
        }
    }
}
