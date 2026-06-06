package com.callx.app.comments;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.models.ReelComment;
import com.callx.app.utils.FirebaseUtils;
import android.content.Intent;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.cache.StatusCacheManager;

/**
 * ReelCommentsAdapter — full-featured comment list adapter.
 *
 * Advanced features:
 *  ✅ Avatar auto-fallback: fetches from Firebase users/{uid}/photo when ownerPhoto is empty
 *  ✅ Pin badge: shows "📌 Pinned" strip on isPinned comments
 *  ✅ Edited label: shows "(edited)" when isEdited == true
 *  ✅ Emoji reaction strip: top-3 aggregated reactions shown as pill chips
 *  ✅ Sort: sortByNewest() / sortByTop() (pinned always at top)
 *  ✅ Long-press context menu: Edit / Delete / Pin / React / Report
 *  ✅ Reply expand/collapse with avatar-aware reply rows
 */
public class ReelCommentsAdapter extends RecyclerView.Adapter<ReelCommentsAdapter.VH> {

    // ── Avatar cache (uid → photoUrl) — avoids repeated Firebase reads ─────
    private static final HashMap<String, String> avatarCache = new HashMap<>();

    // ── Listener ──────────────────────────────────────────────────────────
    public interface OnCommentActionListener {
        void onLikeComment(ReelComment comment, int position);
        void onReplyComment(ReelComment comment);
        void onLongPress(ReelComment comment, int position);
        void onAvatarClick(ReelComment comment);
        void onViewReplies(ReelComment comment, LinearLayout container, TextView tvToggle);
        void onEditComment(ReelComment comment, int position);
        void onPinComment(ReelComment comment);
        void onReportComment(ReelComment comment);
        void onReactComment(ReelComment comment, String emoji, int position);
    }

    // ── State ─────────────────────────────────────────────────────────────
    private final List<ReelComment> items = new ArrayList<>();
    private final String myUid;
    private String reelOwnerUid = "";
    private OnCommentActionListener listener;

    public ReelCommentsAdapter(String myUid) {
        this.myUid = myUid != null ? myUid : "";
    }

    public void setReelOwnerUid(String uid) {
        this.reelOwnerUid = uid != null ? uid : "";
    }

    public void setListener(OnCommentActionListener l) {
        this.listener = l;
    }

    // ── Data ops ──────────────────────────────────────────────────────────

    public void setComments(List<ReelComment> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public void addComment(ReelComment c) {
        items.add(c);
        notifyItemInserted(items.size() - 1);
    }

    public void updateComment(int position, ReelComment c) {
        if (position >= 0 && position < items.size()) {
            items.set(position, c);
            notifyItemChanged(position);
        }
    }

    public void removeComment(int position) {
        if (position >= 0 && position < items.size()) {
            items.remove(position);
            notifyItemRemoved(position);
        }
    }

    public ReelComment getComment(int position) {
        if (position >= 0 && position < items.size()) return items.get(position);
        return null;
    }

    public int getCommentCount() { return items.size(); }

    // ── Sort ──────────────────────────────────────────────────────────────

    /** Sort newest-first, pinned always at top. */
    public void sortByNewest() {
        Collections.sort(items, (a, b) -> {
            if (a.isPinned && !b.isPinned) return -1;
            if (!a.isPinned && b.isPinned) return 1;
            return Long.compare(b.timestamp, a.timestamp);
        });
        notifyDataSetChanged();
    }

    /** Sort by most-liked, pinned always at top. */
    public void sortByTop() {
        Collections.sort(items, (a, b) -> {
            if (a.isPinned && !b.isPinned) return -1;
            if (!a.isPinned && b.isPinned) return 1;
            return Integer.compare(b.likesCount, a.likesCount);
        });
        notifyDataSetChanged();
    }

    // ── RecyclerView ──────────────────────────────────────────────────────

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_reel_comment, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ReelComment c = items.get(position);
        Context ctx = h.itemView.getContext();

        // ── Pin badge ───────────────────────────────────────────────────
        if (h.rowPin != null) {
            h.rowPin.setVisibility(c.isPinned ? View.VISIBLE : View.GONE);
        }

        // ── Avatar ──────────────────────────────────────────────────────
        bindAvatar(ctx, h.ivAvatar, c.uid, c.ownerPhoto);

        // ── Story ring (unseen status indicator) ─────────────────────
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        boolean hasStory = c.uid != null && (scm.hasUnseen(c.uid) || scm.hasStatus(c.uid));

        if (h.ivStoryRing != null && c.uid != null) {
            if (scm.hasUnseen(c.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_unseen);
                h.ivStoryRing.setVisibility(android.view.View.VISIBLE);
            } else if (scm.hasStatus(c.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_seen);
                h.ivStoryRing.setVisibility(android.view.View.VISIBLE);
            } else {
                h.ivStoryRing.setVisibility(android.view.View.GONE);
            }
            h.ivStoryRing.setOnClickListener(v -> openCommentStatus(ctx, c));
        }

        // ── Name ────────────────────────────────────────────────────────
        h.tvName.setText(c.ownerName != null && !c.ownerName.isEmpty()
            ? c.ownerName : "User");

        // ── Time + Edited ───────────────────────────────────────────────
        h.tvTime.setText(DateUtils.getRelativeTimeSpanString(
            c.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE));

        if (h.tvEdited != null) {
            h.tvEdited.setVisibility(c.isEdited ? View.VISIBLE : View.GONE);
        }

        // ── Comment text ────────────────────────────────────────────────
        h.tvText.setText(c.text != null ? c.text : "");

        // ── Emoji reaction strip ────────────────────────────────────────
        if (h.layoutReactions != null) {
            bindReactions(ctx, h.layoutReactions, c);
        }

        // ── Like button ─────────────────────────────────────────────────
        boolean liked = c.isLikedBy(myUid);
        h.tvLikes.setText(c.likesCount > 0 ? String.valueOf(c.likesCount) : "");
        h.btnLike.setImageResource(liked
            ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        h.btnLike.setColorFilter(liked
            ? ctx.getResources().getColor(android.R.color.holo_red_light)
            : ctx.getResources().getColor(android.R.color.darker_gray));

        // ── Reply count ─────────────────────────────────────────────────
        if (c.replyCount > 0) {
            h.tvViewReplies.setVisibility(View.VISIBLE);
            boolean expanded = h.containerReplies.getVisibility() == View.VISIBLE;
            h.tvViewReplies.setText(expanded
                ? "Hide replies"
                : "View " + c.replyCount + (c.replyCount == 1 ? " reply" : " replies"));
        } else {
            h.tvViewReplies.setVisibility(View.GONE);
            h.containerReplies.setVisibility(View.GONE);
            h.containerReplies.removeAllViews();
        }

        // ── Click listeners ─────────────────────────────────────────────
        // (ring click already set above)

        h.ivAvatar.setOnClickListener(v -> {
            if (hasStory) openCommentStatus(ctx, c);
            else if (listener != null) listener.onAvatarClick(c);
        });

        h.btnLike.setOnClickListener(v -> {
            if (listener != null) listener.onLikeComment(c, h.getAdapterPosition());
        });

        h.btnReply.setOnClickListener(v -> {
            if (listener != null) listener.onReplyComment(c);
        });

        h.tvViewReplies.setOnClickListener(v -> {
            if (listener != null)
                listener.onViewReplies(c, h.containerReplies, h.tvViewReplies);
        });

        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) showContextMenu(ctx, c, h.getAdapterPosition());
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    // ── Avatar with Firebase fallback ──────────────────────────────────────

    private void openCommentStatus(Context ctx, ReelComment c) {
        if (c.uid == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.viewer.StatusViewerActivity");
            Intent si = new Intent(ctx, cls);
            si.putExtra("ownerUid",  c.uid);
            si.putExtra("ownerName", c.ownerName != null ? c.ownerName : "");
            ctx.startActivity(si);
        } catch (ClassNotFoundException e) {
            if (listener != null) listener.onAvatarClick(c);
        }
    }

    private void bindAvatar(Context ctx, CircleImageView iv, String uid, String photoUrl) {
        if (photoUrl != null && !photoUrl.isEmpty()) {
            avatarCache.put(uid, photoUrl);
            loadAvatarInto(ctx, iv, photoUrl);
            return;
        }

        // Check cache first
        String cached = uid != null ? avatarCache.get(uid) : null;
        if (cached != null && !cached.isEmpty()) {
            loadAvatarInto(ctx, iv, cached);
            return;
        }

        // Placeholder while we fetch
        iv.setImageResource(R.drawable.ic_person);

        if (uid == null || uid.isEmpty()) return;

        // Reels profile se avatar fetch karo (reels/users/{uid})
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    String thumb = s.child("thumbUrl").getValue(String.class);
                    String photo = s.child("photoUrl").getValue(String.class);
                    String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (p != null && !p.isEmpty()) {
                        avatarCache.put(uid, p);
                        loadAvatarInto(ctx, iv, p);
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadAvatarInto(Context ctx, CircleImageView iv, String url) {
        try {
            Glide.with(ctx).load(url)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(iv);
        } catch (Exception ignored) {}
    }

    // ── Emoji reaction strip ──────────────────────────────────────────────

    private void bindReactions(Context ctx, LinearLayout layout, ReelComment c) {
        layout.removeAllViews();

        if (c.reactions == null || c.reactions.isEmpty()) {
            layout.setVisibility(View.GONE);
            return;
        }

        // Aggregate: emoji → count
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String emoji : c.reactions.values()) {
            counts.merge(emoji, 1, Integer::sum);
        }

        // Sort by count desc, take top 3
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        Collections.sort(sorted, (a, b) -> b.getValue().compareTo(a.getValue()));

        int max = Math.min(3, sorted.size());
        int dp4 = dp(ctx, 4);
        int dp8 = dp(ctx, 8);
        int dp2 = dp(ctx, 2);

        for (int i = 0; i < max; i++) {
            Map.Entry<String, Integer> entry = sorted.get(i);

            TextView chip = new TextView(ctx);
            chip.setText(entry.getKey() + " " + entry.getValue());
            chip.setTextSize(11f);
            chip.setTextColor(0xFF5B5BF6);
            chip.setBackgroundResource(R.drawable.bg_reaction_chip);
            chip.setPadding(dp8, dp2, dp8, dp2);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp4, dp4, 0);
            chip.setLayoutParams(lp);

            // Tap chip → react with same emoji
            final String emoji = entry.getKey();
            chip.setOnClickListener(v -> {
                int pos = getAdapterPositionOf(c);
                if (listener != null && pos >= 0)
                    listener.onReactComment(c, emoji, pos);
            });

            layout.addView(chip);
        }

        layout.setVisibility(View.VISIBLE);
    }

    private int getAdapterPositionOf(ReelComment c) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).commentId != null
                && items.get(i).commentId.equals(c.commentId)) return i;
        }
        return -1;
    }

    private int dp(Context ctx, int dp) {
        return (int)(dp * ctx.getResources().getDisplayMetrics().density);
    }

    // ── Context menu (long press) ─────────────────────────────────────────

    private void showContextMenu(Context ctx, ReelComment c, int position) {
        boolean isOwn   = myUid.equals(c.uid);
        boolean isReelOwner = myUid.equals(reelOwnerUid);

        // Build option list
        List<String> opts = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        // React (everyone)
        opts.add("React with emoji");
        actions.add(() -> showEmojiPanel(ctx, c, position));

        if (isOwn) {
            // Edit own comment
            opts.add("Edit comment");
            actions.add(() -> { if (listener != null) listener.onEditComment(c, position); });
        }

        if (isReelOwner) {
            // Pin / Unpin
            opts.add(c.isPinned ? "Unpin comment" : "Pin comment");
            actions.add(() -> { if (listener != null) listener.onPinComment(c); });
        }

        if (!isOwn) {
            // Report
            opts.add("Report comment");
            actions.add(() -> { if (listener != null) listener.onReportComment(c); });
        }

        if (isOwn || isReelOwner) {
            // Delete
            opts.add("Delete comment");
            actions.add(() -> { if (listener != null) listener.onLongPress(c, position); });
        }

        String[] optsArray = opts.toArray(new String[0]);
        new android.app.AlertDialog.Builder(ctx)
            .setItems(optsArray, (d, which) -> actions.get(which).run())
            .show();
    }

    private void showEmojiPanel(Context ctx, ReelComment c, int position) {
        String[] emojis  = {"❤️", "😂", "😮", "😢", "👏", "🔥"};
        String myReaction = c.getMyReaction(myUid);

        new android.app.AlertDialog.Builder(ctx)
            .setTitle("React to comment")
            .setItems(emojis, (d, which) -> {
                String selected = emojis[which];
                // Toggle: if already reacted with same emoji, remove
                String send = selected.equals(myReaction) ? null : selected;
                if (listener != null) listener.onReactComment(c, send, position);
            })
            .show();
    }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        android.widget.ImageView ivStoryRing;
        TextView tvName, tvText, tvTime, tvLikes, btnReply, tvViewReplies;
        TextView tvEdited;
        ImageButton btnLike;
        LinearLayout containerReplies;
        LinearLayout layoutReactions;
        View rowPin;

        VH(@NonNull View v) {
            super(v);
            rowPin          = v.findViewById(R.id.row_pin);
            ivAvatar        = v.findViewById(R.id.iv_avatar);
            ivStoryRing     = v.findViewById(R.id.iv_story_ring);
            tvName          = v.findViewById(R.id.tv_name);
            tvText          = v.findViewById(R.id.tv_comment_text);
            tvTime          = v.findViewById(R.id.tv_time);
            tvEdited        = v.findViewById(R.id.tv_edited);
            tvLikes         = v.findViewById(R.id.tv_likes_count);
            btnLike         = v.findViewById(R.id.btn_like_comment);
            btnReply        = v.findViewById(R.id.btn_reply);
            tvViewReplies   = v.findViewById(R.id.tv_view_replies);
            containerReplies= v.findViewById(R.id.container_replies);
            layoutReactions = v.findViewById(R.id.layout_reactions);
        }
    }
}
