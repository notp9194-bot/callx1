package com.callx.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeComment;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class YouTubeCommentAdapter extends RecyclerView.Adapter<YouTubeCommentAdapter.VH> {

    public interface OnReplyClick   { void onReply(String commentId, String authorName); }
    public interface OnLikeClick    { void onLike(String commentId); }
    public interface OnDeleteClick  { void onDelete(String commentId); }
    public interface OnPinClick     { void onPin(String commentId); }
    public interface OnHeartClick   { void onHeart(String commentId); }

    private final Context ctx;
    private final String  myUid;
    private String        videoOwnerUid;
    private List<YouTubeComment> data;

    private OnReplyClick   replyListener;
    private OnLikeClick    likeListener;
    private OnDeleteClick  deleteListener;
    private OnPinClick     pinListener;
    private OnHeartClick   heartListener;

    public YouTubeCommentAdapter(Context ctx, List<YouTubeComment> data, String myUid) {
        this.ctx   = ctx;
        this.myUid = myUid;
        this.data  = data != null ? new ArrayList<>(data) : new ArrayList<>();
    }

    public void setData(List<YouTubeComment> d) {
        this.data = d != null ? new ArrayList<>(d) : new ArrayList<>();
        notifyDataSetChanged();
    }
    public void setVideoOwnerUid(String uid) { this.videoOwnerUid = uid; }
    public void setOnReplyClickListener(OnReplyClick l)   { replyListener   = l; }
    public void setOnLikeClickListener(OnLikeClick l)     { likeListener    = l; }
    public void setOnDeleteClickListener(OnDeleteClick l)  { deleteListener  = l; }
    public void setOnPinClickListener(OnPinClick l)        { pinListener     = l; }
    public void setOnHeartClickListener(OnHeartClick l)    { heartListener   = l; }

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
        h.tvLikeCount.setText(c.likeCount > 0 ? String.valueOf(c.likeCount) : "");

        // Badges
        if (h.tvPinned  != null) h.tvPinned.setVisibility(c.isPinned    ? View.VISIBLE : View.GONE);
        if (h.ivHearted != null) h.ivHearted.setVisibility(c.isHearted  ? View.VISIBLE : View.GONE);
        if (h.tvEdited  != null) h.tvEdited.setVisibility(c.isEdited    ? View.VISIBLE : View.GONE);

        Glide.with(ctx).load(c.authorPhotoUrl).circleCrop()
            .placeholder(R.drawable.ic_person).into(h.ivAvatar);

        // Reply
        if (h.btnReply != null)
            h.btnReply.setOnClickListener(v -> {
                if (replyListener != null) replyListener.onReply(c.commentId, c.authorName);
            });

        // Like
        if (h.btnLike != null)
            h.btnLike.setOnClickListener(v -> {
                if (likeListener != null) likeListener.onLike(c.commentId);
            });

        // Show replies count
        if (h.tvRepliesCount != null) {
            if (c.replyCount > 0) {
                h.tvRepliesCount.setVisibility(View.VISIBLE);
                h.tvRepliesCount.setText(c.replyCount + " repl" + (c.replyCount == 1 ? "y" : "ies"));
                h.tvRepliesCount.setOnClickListener(v -> loadReplies(h, c.commentId, c.videoId));
            } else {
                h.tvRepliesCount.setVisibility(View.GONE);
            }
        }

        // 3-dot menu (owner/self actions)
        if (h.btnMore != null) {
            h.btnMore.setOnClickListener(v -> {
                android.widget.PopupMenu pop = new android.widget.PopupMenu(ctx, h.btnMore);
                if (myUid.equals(c.authorUid)) pop.getMenu().add("Delete").setOnMenuItemClickListener(item -> {
                    if (deleteListener != null) deleteListener.onDelete(c.commentId); return true;
                });
                if (myUid.equals(videoOwnerUid)) {
                    pop.getMenu().add(c.isPinned ? "Unpin" : "Pin").setOnMenuItemClickListener(item -> {
                        if (pinListener != null) pinListener.onPin(c.commentId); return true;
                    });
                    pop.getMenu().add(c.isHearted ? "Unheart" : "Heart").setOnMenuItemClickListener(item -> {
                        if (heartListener != null) heartListener.onHeart(c.commentId); return true;
                    });
                }
                if (pop.getMenu().size() > 0) pop.show();
            });
        }
    }

    @Override public int getItemCount() { return data.size(); }

    private void loadReplies(VH h, String commentId, String videoId) {
        if (h.rvReplies == null) return;
        h.rvReplies.setVisibility(View.VISIBLE);
        List<YouTubeComment> replies = new ArrayList<>();
        YouTubeCommentAdapter replyAdapter = new YouTubeCommentAdapter(ctx, replies, myUid);
        replyAdapter.setVideoOwnerUid(videoOwnerUid);
        h.rvReplies.setLayoutManager(new LinearLayoutManager(ctx));
        h.rvReplies.setAdapter(replyAdapter);

        YouTubeFirebaseUtils.commentRepliesRef(videoId, commentId)
            .orderByChild("timestamp")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeComment> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeComment r = ds.getValue(YouTubeComment.class);
                        if (r != null) list.add(r);
                    }
                    replyAdapter.setData(list);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private String formatAge(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000)    return "just now";
        if (diff < 3_600_000) return (diff / 60_000) + "m ago";
        if (diff < 86_400_000)return (diff / 3_600_000) + "h ago";
        if (diff < 604_800_000)return (diff / 86_400_000) + "d ago";
        return (diff / 604_800_000L) + "w ago";
    }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView        tvName, tvText, tvTime, tvLikeCount, tvPinned, tvEdited, tvRepliesCount;
        ImageView       ivHearted;
        ImageButton     btnLike, btnReply, btnMore;
        RecyclerView    rvReplies;

        VH(View v) {
            super(v);
            ivAvatar       = v.findViewById(R.id.iv_yt_comment_avatar);
            tvName         = v.findViewById(R.id.tv_yt_comment_author);
            tvText         = v.findViewById(R.id.tv_yt_comment_text);
            tvTime         = v.findViewById(R.id.tv_yt_comment_time);
            tvLikeCount    = v.findViewById(R.id.tv_yt_comment_likes);
            tvPinned       = v.findViewById(R.id.tv_yt_comment_pinned);
            ivHearted      = v.findViewById(R.id.iv_yt_comment_hearted);
            tvEdited       = v.findViewById(R.id.tv_yt_comment_edited);
            tvRepliesCount = v.findViewById(R.id.tv_yt_comment_replies);
            btnLike        = v.findViewById(R.id.btn_yt_comment_like);
            btnReply       = v.findViewById(R.id.btn_yt_comment_reply);
            btnMore        = v.findViewById(R.id.btn_yt_comment_more);
            rvReplies      = v.findViewById(R.id.rv_yt_comment_replies);
        }
    }
}
