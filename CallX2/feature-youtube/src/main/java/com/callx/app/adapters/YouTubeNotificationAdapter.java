package com.callx.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.youtube.R;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.List;

public class YouTubeNotificationAdapter
    extends RecyclerView.Adapter<YouTubeNotificationAdapter.VH> {

    public interface OnNotifClickListener { void onClick(YouTubeNotification notif); }

    private final Context ctx;
    private List<YouTubeNotification> data;
    private final OnNotifClickListener listener;

    public YouTubeNotificationAdapter(Context ctx, List<YouTubeNotification> data,
                                      OnNotifClickListener listener) {
        this.ctx      = ctx;
        this.data     = data;
        this.listener = listener;
    }

    public void setData(List<YouTubeNotification> data) {
        this.data = data;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(
            R.layout.item_youtube_notification, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        YouTubeNotification n = data.get(pos);
        h.tvText.setText(buildText(n));
        h.tvTime.setText(formatAge(n.timestamp));
        h.itemView.setAlpha(n.read ? 0.6f : 1.0f);
        Glide.with(ctx).load(n.fromPhotoUrl).circleCrop()
            .placeholder(R.drawable.ic_person).into(h.ivAvatar);
        if (n.thumbnailUrl != null)
            Glide.with(ctx).load(n.thumbnailUrl).centerCrop().into(h.ivThumb);
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(n); });
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    private String buildText(YouTubeNotification n) {
        String name = n.fromName != null ? n.fromName : "Someone";
        switch (n.type != null ? n.type : "") {
            case "new_video":  return name + " uploaded: " + n.videoTitle;
            case "comment":    return name + " commented on your video";
            case "reply":      return name + " replied to your comment";
            case "subscribe":  return name + " subscribed to your channel";
            case "like":       return name + " liked your video";
            case "live":       return name + " is live now!";
            default:           return name + " interacted with your content";
        }
    }

    private String formatAge(long ts) {
        long diff = System.currentTimeMillis() - ts;
        long mins = diff / 60000;
        if (mins < 60)   return mins + "m ago";
        long hrs  = mins / 60;
        if (hrs  < 24)   return hrs  + "h ago";
        long days = hrs  / 24;
        if (days < 30)   return days + "d ago";
        return (days / 30) + "mo ago";
    }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        android.widget.ImageView ivThumb;
        TextView tvText, tvTime;
        VH(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_yt_notif_avatar);
            ivThumb  = v.findViewById(R.id.iv_yt_notif_thumb);
            tvText   = v.findViewById(R.id.tv_yt_notif_text);
            tvTime   = v.findViewById(R.id.tv_yt_notif_time);
        }
    }
}
