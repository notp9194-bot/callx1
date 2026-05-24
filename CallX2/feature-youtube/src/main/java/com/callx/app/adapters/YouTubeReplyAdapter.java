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
import com.callx.app.models.YouTubeComment;
import com.callx.app.youtube.R;
import java.util.List;

/** Flat reply list used inside YouTubeCommentThreadAdapter. */
public class YouTubeReplyAdapter extends RecyclerView.Adapter<YouTubeReplyAdapter.RVH> {

    private final Context ctx;
    private final List<YouTubeComment> data;
    private final String videoId, myUid, videoOwnerUid;

    public YouTubeReplyAdapter(Context ctx, List<YouTubeComment> data,
                               String videoId, String myUid, String videoOwnerUid) {
        this.ctx = ctx; this.data = data; this.videoId = videoId;
        this.myUid = myUid; this.videoOwnerUid = videoOwnerUid;
    }

    @NonNull @Override
    public RVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_yt_reply, p, false);
        return new RVH(v);
    }

    @Override public void onBindViewHolder(@NonNull RVH h, int pos) {
        YouTubeComment c = data.get(pos);
        h.tvAuthor.setText(c.authorName);
        h.tvText.setText(c.text);
        h.tvTime.setText(relTime(c.timestamp));
        if (h.tvOwner != null)
            h.tvOwner.setVisibility(c.authorUid.equals(videoOwnerUid) ? View.VISIBLE : View.GONE);
        if (c.authorPhotoUrl != null)
            Glide.with(ctx).load(c.authorPhotoUrl).circleCrop().into(h.ivAvatar);
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    static class RVH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView  tvAuthor, tvText, tvTime, tvOwner;
        RVH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_yt_reply_avatar);
            tvAuthor = v.findViewById(R.id.tv_yt_reply_author);
            tvText   = v.findViewById(R.id.tv_yt_reply_text);
            tvTime   = v.findViewById(R.id.tv_yt_reply_time);
            tvOwner  = v.findViewById(R.id.tv_yt_reply_owner);
        }
    }

    private String relTime(long ms) {
        long d = (System.currentTimeMillis() - ms) / 86_400_000;
        if (d >= 1) return d + "d ago";
        long h = (System.currentTimeMillis() - ms) / 3_600_000;
        if (h >= 1) return h + "h ago";
        return "now";
    }
}
