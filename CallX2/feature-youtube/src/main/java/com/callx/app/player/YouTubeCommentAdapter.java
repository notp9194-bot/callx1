package com.callx.app.player;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeComment;
import com.callx.app.player.YouTubeCommentOptionsSheet;
import com.callx.app.youtube.R;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

public class YouTubeCommentAdapter
    extends RecyclerView.Adapter<YouTubeCommentAdapter.VH> {

    private final Context ctx;
    private List<YouTubeComment> data;

    public YouTubeCommentAdapter(Context ctx, List<YouTubeComment> data) {
        this.ctx  = ctx;
        this.data = data != null ? new ArrayList<>(data) : new ArrayList<>();
    }

    public void setData(List<YouTubeComment> data) {
        this.data = data != null ? new ArrayList<>(data) : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_youtube_comment, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        YouTubeComment c = data.get(pos);
        h.tvName.setText(c.authorName);
        h.tvText.setText(c.text);
        h.tvTime.setText(formatAge(c.timestamp));
        h.tvLikes.setText(c.likeCount > 0 ? String.valueOf(c.likeCount) : "");
        h.tvPinned.setVisibility(c.isPinned ? View.VISIBLE : View.GONE);

        Glide.with(ctx).load(c.authorPhotoUrl).circleCrop()
            .placeholder(R.drawable.ic_person).into(h.ivAvatar);

        // 3-dot comment options
        if (h.btnMore != null) {
            h.btnMore.setOnClickListener(v -> showCommentOptions(c, pos));
        }
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    private void showCommentOptions(YouTubeComment comment, int position) {
        if (!(ctx instanceof FragmentActivity)) return;
        FragmentActivity activity = (FragmentActivity) ctx;

        YouTubeCommentOptionsSheet sheet = YouTubeCommentOptionsSheet.newInstance(
            comment.videoId != null ? comment.videoId : "",
            comment.commentId,
            comment.authorUid != null ? comment.authorUid : ""
        );

        sheet.setCallback(commentId -> {
            // Remove from list when deleted
            for (int i = 0; i < data.size(); i++) {
                if (commentId.equals(data.get(i).commentId)) {
                    data.remove(i);
                    notifyItemRemoved(i);
                    break;
                }
            }
        });

        sheet.show(activity.getSupportFragmentManager(), "yt_comment_options");
    }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView tvName, tvText, tvTime, tvLikes, tvPinned;
        ImageButton btnMore;

        VH(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_yt_comment_avatar);
            tvName   = v.findViewById(R.id.tv_yt_comment_name);
            tvText   = v.findViewById(R.id.tv_yt_comment_text);
            tvTime   = v.findViewById(R.id.tv_yt_comment_time);
            tvLikes  = v.findViewById(R.id.tv_yt_comment_likes);
            tvPinned = v.findViewById(R.id.tv_yt_comment_pinned);
            btnMore  = v.findViewById(R.id.btn_yt_comment_more);
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
}
