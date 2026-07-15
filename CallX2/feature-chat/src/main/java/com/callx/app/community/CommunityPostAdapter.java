package com.callx.app.community;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityPostEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * v31: Feed post adapter.
 *
 * New in v31:
 *  - Multi-emoji reaction row (shows reaction counts beneath each post)
 *  - Long-press on like button opens CommunityReactionPickerView
 *  - @mention spans rendered in blue
 *  - Admin post options menu (Delete Post, Report Post)
 *  - Muted/scheduled origin indicator
 */
public class CommunityPostAdapter extends RecyclerView.Adapter<CommunityPostAdapter.VH> {

    public interface Listener {
        void onLike(CommunityPostEntity post);
        void onComment(CommunityPostEntity post);
        void onLongPressLike(CommunityPostEntity post, View anchorView);
        void onReaction(CommunityPostEntity post, String reactionType);
        void onDelete(CommunityPostEntity post);
        void onReport(CommunityPostEntity post);
        void onPollVote(CommunityPostEntity post, int optionIndex);
        void onMediaClicked(CommunityPostEntity post);
    }

    private static final DiffUtil.ItemCallback<CommunityPostEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityPostEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return a.likeCount == b.likeCount
                            && a.commentCount == b.commentCount
                            && safeEq(a.reactionCountsJson, b.reactionCountsJson)
                            && safeEq(a.myReactionType, b.myReactionType)
                            && safeEq(a.pollJson, b.pollJson);
                }
                private boolean safeEq(String x, String y) { return x == null ? y == null : x.equals(y); }
            };

    private final AsyncListDiffer<CommunityPostEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;
    private final String currentUid;
    private boolean isAdminOrOwner = false;

    public CommunityPostAdapter(String currentUid, Listener listener) {
        this.currentUid = currentUid;
        this.listener = listener;
    }

    public void setAdminOrOwner(boolean adminOrOwner) {
        this.isAdminOrOwner = adminOrOwner;
    }

    public void submitList(List<CommunityPostEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_post, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityPostEntity p = differ.getCurrentList().get(pos);

        // Author
        h.tvAuthorName.setText(p.authorName != null ? p.authorName : "Member");
        h.tvTimestamp.setText(p.createdAt > 0
                ? DateUtils.getRelativeTimeSpanString(p.createdAt,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) : "");

        if (p.authorPhoto != null && !p.authorPhoto.isEmpty()) {
            Glide.with(h.ivAuthorAvatar.getContext()).load(p.authorPhoto)
                    .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAuthorAvatar);
        } else {
            h.ivAuthorAvatar.setImageResource(R.drawable.ic_person);
        }

        // Announcement banner
        h.tvAnnouncementBadge.setVisibility(p.isAnnouncement ? View.VISIBLE : View.GONE);

        // Post text with @mention highlight
        if (p.text != null && !p.text.isEmpty()) {
            h.tvPostText.setVisibility(View.VISIBLE);
            h.tvPostText.setText(buildMentionSpan(p.text, h));
        } else {
            h.tvPostText.setVisibility(View.GONE);
        }

        // Media
        if (p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
            h.ivMedia.setVisibility(View.VISIBLE);
            Glide.with(h.ivMedia.getContext()).load(p.mediaUrl)
                    .centerCrop().placeholder(R.drawable.ic_photo_library).into(h.ivMedia);
            h.ivMedia.setOnClickListener(v -> { if (listener != null) listener.onMediaClicked(p); });
            boolean isVideo = "video".equals(p.mediaType);
            h.ivPlayOverlay.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        } else {
            h.ivMedia.setVisibility(View.GONE);
            h.ivPlayOverlay.setVisibility(View.GONE);
        }

        // Poll
        if (h.layoutPoll != null) {
            if (p.pollJson != null && !p.pollJson.isEmpty()) {
                h.layoutPoll.setVisibility(View.VISIBLE);
                bindPoll(h, p);
            } else {
                h.layoutPoll.setVisibility(View.GONE);
            }
        }

        // Like count (likeCount used as total engagement summary)
        long totalReactions = getTotalReactions(p);
        long displayLikes = totalReactions > 0 ? totalReactions : p.likeCount;
        h.tvLikeCount.setText(displayLikes > 0 ? String.valueOf(displayLikes) : "");
        h.tvCommentCount.setText(p.commentCount > 0 ? String.valueOf(p.commentCount) : "");

        // Reaction row  (v31)
        if (h.layoutReactions != null) {
            bindReactionRow(h, p);
        }

        // My reaction — tint like button
        boolean myReacted = p.myReactionType != null && !p.myReactionType.isEmpty();
        h.btnLike.setImageResource(myReacted
                ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        int tintColor = myReacted
                ? ContextCompat.getColor(h.btnLike.getContext(), R.color.community_like_active)
                : ContextCompat.getColor(h.btnLike.getContext(), android.R.color.darker_gray);
        h.btnLike.setColorFilter(tintColor);

        // Like — tap = quick LIKE, long-press = reaction picker
        h.btnLike.setOnClickListener(v -> { if (listener != null) listener.onLike(p); });
        h.btnLike.setOnLongClickListener(v -> {
            if (listener != null) listener.onLongPressLike(p, v);
            return true;
        });
        h.btnComment.setOnClickListener(v -> { if (listener != null) listener.onComment(p); });

        // Options (admin/owner)
        boolean isAuthor = currentUid != null && currentUid.equals(p.authorUid);
        boolean canModify = isAdminOrOwner || isAuthor;
        if (h.btnOptions != null) {
            h.btnOptions.setVisibility(canModify ? View.VISIBLE : View.GONE);
            h.btnOptions.setOnClickListener(v -> showPostOptions(v, p, isAuthor));
        }
    }

    private CharSequence buildMentionSpan(String text, VH h) {
        if (!text.contains("@")) return text;
        SpannableString ss = new SpannableString(text);
        int mentionColor = ContextCompat.getColor(h.tvPostText.getContext(), R.color.colorPrimary);
        int start = 0;
        while (true) {
            int at = text.indexOf('@', start);
            if (at < 0) break;
            int end = at + 1;
            while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
            if (end > at + 1) {
                ss.setSpan(new ForegroundColorSpan(mentionColor), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end;
        }
        return ss;
    }

    private void bindReactionRow(VH h, CommunityPostEntity p) {
        Map<String, Long> counts = CommunityReaction.fromJson(p.reactionCountsJson);
        if (counts == null || counts.isEmpty()) {
            h.layoutReactions.setVisibility(View.GONE);
            return;
        }
        h.layoutReactions.setVisibility(View.VISIBLE);
        h.layoutReactions.removeAllViews();

        // Sort by count desc
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        for (Map.Entry<String, Long> entry : entries) {
            if (entry.getValue() <= 0) continue;
            String emoji = CommunityReaction.getEmoji(entry.getKey());
            TextView chip = new TextView(h.layoutReactions.getContext());
            chip.setText(emoji + " " + entry.getValue());
            chip.setTextSize(12f);
            chip.setPadding(12, 4, 12, 4);
            chip.setBackground(ContextCompat.getDrawable(chip.getContext(), R.drawable.bg_reaction_chip));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(6);
            chip.setLayoutParams(lp);
            h.layoutReactions.addView(chip);
        }
    }

    private long getTotalReactions(CommunityPostEntity p) {
        Map<String, Long> counts = CommunityReaction.fromJson(p.reactionCountsJson);
        if (counts == null) return 0L;
        long total = 0L;
        for (Long v : counts.values()) if (v != null) total += v;
        return total;
    }

    private void bindPoll(VH h, CommunityPostEntity p) {
        // Poll binding handled by CommunityPollView if inflated, otherwise skipped
    }

    private void showPostOptions(View anchor, CommunityPostEntity p, boolean isAuthor) {
        PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
        if (isAdminOrOwner || isAuthor) popup.getMenu().add(0, 1, 0, "Delete Post");
        popup.getMenu().add(0, 2, 0, "Report Post");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) { if (listener != null) listener.onDelete(p); return true; }
            if (item.getItemId() == 2) { if (listener != null) listener.onReport(p); return true; }
            return false;
        });
        popup.show();
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try { Glide.with(h.ivAuthorAvatar.getContext()).clear(h.ivAuthorAvatar); } catch (Exception ignored) {}
        try { Glide.with(h.ivMedia.getContext()).clear(h.ivMedia); } catch (Exception ignored) {}
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAuthorAvatar;
        TextView tvAuthorName, tvTimestamp, tvPostText, tvAnnouncementBadge;
        TextView tvLikeCount, tvCommentCount;
        ImageView ivMedia, ivPlayOverlay;
        ImageButton btnLike, btnComment;
        ImageView btnOptions;
        LinearLayout layoutReactions;
        View layoutPoll;

        VH(@NonNull View itemView) {
            super(itemView);
            ivAuthorAvatar      = itemView.findViewById(R.id.iv_author_avatar);
            tvAuthorName        = itemView.findViewById(R.id.tv_author_name);
            tvTimestamp         = itemView.findViewById(R.id.tv_timestamp);
            tvPostText          = itemView.findViewById(R.id.tv_post_text);
            tvAnnouncementBadge = itemView.findViewById(R.id.tv_announcement_badge);
            tvLikeCount         = itemView.findViewById(R.id.tv_like_count);
            tvCommentCount      = itemView.findViewById(R.id.tv_comment_count);
            ivMedia             = itemView.findViewById(R.id.iv_post_media);
            ivPlayOverlay       = itemView.findViewById(R.id.iv_play_icon);
            btnLike             = itemView.findViewById(R.id.btn_like);
            btnComment          = itemView.findViewById(R.id.btn_comment);
            btnOptions          = itemView.findViewById(R.id.btn_post_options);
            layoutReactions     = itemView.findViewById(R.id.layout_reactions);
            layoutPoll          = itemView.findViewById(R.id.layout_poll);
        }
    }
}
