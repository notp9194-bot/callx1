package com.callx.app.comments;
import com.callx.app.utils.AlertDialogStyler;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.models.ReelComment;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.AvatarUrlBuilder;
import android.util.LruCache;
import android.content.Intent;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.widget.ImageView;
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
    // PERF FIX: was an unbounded HashMap that grew for the lifetime of the
    // process (every uid ever scrolled past, never evicted) — a long
    // session across many reels/comment threads could leak a meaningful
    // amount of memory. Bounded LruCache caps it at 200 uids and evicts the
    // least-recently-used entries automatically.
    private static final LruCache<String, String> avatarCache = new LruCache<>(200);

    // Avatar decode/target size in px — comment avatar is a fixed 36dp
    // circular tile (see item_reel_comment.xml), we request 2x for retina
    // sharpness and Cloudinary-side downscale so we never decode more
    // pixels than the view can show (see AvatarUrlBuilder.build below).
    private static final int AVATAR_SIZE_DP = 36;

    // Comment photo attachment decode size — iv_comment_image in
    // item_reel_comment.xml is a fixed 150dp square. Same reasoning as the
    // avatar above: cap the Glide decode to what the view can actually
    // show instead of decoding the source photo at full resolution.
    static final int COMMENT_IMAGE_SIZE_DP = 150;
    private RequestOptions commentImageRequestOptions;

    private RequestOptions commentImageRequestOptions(Context ctx) {
        if (commentImageRequestOptions == null) {
            int px = Math.round(COMMENT_IMAGE_SIZE_DP * ctx.getResources().getDisplayMetrics().density);
            commentImageRequestOptions = new RequestOptions()
                .override(px, px)
                .centerCrop()
                .placeholder(R.drawable.bg_comment_image_frame);
        }
        return commentImageRequestOptions;
    }

    // PERF: RequestOptions was rebuilt with `new RequestOptions().circleCrop()
    // .override(...)` on EVERY avatar bind. Since the target size is fixed
    // for every comment row, build it once lazily and reuse the same
    // instance for every Glide.load() call instead.
    // NOTE: no .circleCrop() here — the avatar is clipped to a CIRCLE via
    // @drawable/bg_comment_avatar_circle + android:clipToOutline="true" on
    // the ImageView itself (item_reel_comment.xml), not via a Glide bitmap
    // transform. circleCrop() would allocate + draw a fresh bitmap on every
    // decode/rebind; an outline clip is a free draw-time mask over the
    // already size-capped bitmap, so plain centerCrop here is both correct
    // and the fastest path.
    private static volatile RequestOptions avatarRequestOptions;

    private static RequestOptions avatarRequestOptions(Context ctx) {
        RequestOptions opts = avatarRequestOptions;
        if (opts == null) {
            int sizePx = AvatarUrlBuilder.dpToPx(ctx, AVATAR_SIZE_DP) * 2;
            opts = new RequestOptions().override(sizePx, sizePx);
            avatarRequestOptions = opts;
        }
        return opts;
    }

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
        /** Fired when the user taps a comment that's in the "failed" send
         *  state — re-attempt the same Firebase write with the same key. */
        void onRetryComment(ReelComment comment);
    }

    // ── State ─────────────────────────────────────────────────────────────
    // PERF: AsyncListDiffer computes the old-list/new-list diff on a
    // background thread pool and dispatches minimal notifyItem*() calls on
    // the main thread (insert/remove/change only what actually changed),
    // instead of the old notifyDataSetChanged() which force-rebound every
    // single visible row (avatars re-decoded, reaction chips rebuilt,
    // GestureDetectors reallocated) on *every* Firebase child event — the
    // main source of scroll jank/flicker during comment bursts.
    private final AsyncListDiffer<ReelComment> differ =
        new AsyncListDiffer<>(this, new DiffUtil.ItemCallback<ReelComment>() {
            @Override
            public boolean areItemsTheSame(@NonNull ReelComment a, @NonNull ReelComment b) {
                return a.commentId != null && a.commentId.equals(b.commentId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull ReelComment a, @NonNull ReelComment b) {
                return a.likesCount == b.likesCount
                    && a.replyCount == b.replyCount
                    && a.isPinned   == b.isPinned
                    && a.isEdited   == b.isEdited
                    && java.util.Objects.equals(a.sendState, b.sendState)
                    && java.util.Objects.equals(a.text, b.text)
                    && java.util.Objects.equals(a.imageUrl, b.imageUrl)
                    && java.util.Objects.equals(a.ownerName, b.ownerName)
                    && java.util.Objects.equals(a.ownerPhoto, b.ownerPhoto)
                    && mapSignature(a.likedBy).equals(mapSignature(b.likedBy))
                    && mapSignature(a.reactions).equals(mapSignature(b.reactions));
            }

            /** Cheap order-independent signature for likedBy/reactions maps,
             *  good enough to detect "did this map actually change". */
            private String mapSignature(Map<String, ?> m) {
                if (m == null || m.isEmpty()) return "";
                List<String> keys = new ArrayList<>(m.keySet());
                Collections.sort(keys);
                StringBuilder sb = new StringBuilder();
                for (String k : keys) sb.append(k).append('=').append(m.get(k)).append(';');
                return sb.toString();
            }
        });

    private final String myUid;
    private String reelOwnerUid = "";
    private OnCommentActionListener listener;

    /** Payload marker for a "like state only" partial rebind — skips avatar
     *  reload, mention span rebuild, and reaction-chip rebuild. */
    private static final String PAYLOAD_LIKE = "like_only";

    public ReelCommentsAdapter(String myUid) {
        this.myUid = myUid != null ? myUid : "";
        // Stable IDs let RecyclerView's default animator match items across
        // diff-driven updates by identity instead of position, which keeps
        // in-flight bind/animation state (like-button bounce, expanded
        // replies) correctly attached to the right row during a diff pass.
        setHasStableIds(true);
    }

    public void setReelOwnerUid(String uid) {
        this.reelOwnerUid = uid != null ? uid : "";
    }

    public void setListener(OnCommentActionListener l) {
        this.listener = l;
    }

    private List<ReelComment> items() { return differ.getCurrentList(); }

    // ── Data ops ──────────────────────────────────────────────────────────

    public void setComments(List<ReelComment> list) {
        differ.submitList(list != null ? new ArrayList<>(list) : new ArrayList<>());
    }

    public ReelComment getComment(int position) {
        List<ReelComment> items = items();
        if (position >= 0 && position < items.size()) return items.get(position);
        return null;
    }

    public int getCommentCount() { return items().size(); }

    @Override
    public long getItemId(int position) {
        ReelComment c = getComment(position);
        return (c != null && c.commentId != null) ? c.commentId.hashCode() : RecyclerView.NO_ID;
    }

    /** Optimistic, instant like-icon flip for the tapped row only — fired
     *  immediately on tap so the UI never waits on the Firebase round trip,
     *  then reconciled for real once the ChildEventListener echoes back. */
    public void notifyLikeChanged(String commentId) {
        List<ReelComment> items = items();
        for (int i = 0; i < items.size(); i++) {
            if (commentId != null && commentId.equals(items.get(i).commentId)) {
                notifyItemChanged(i, PAYLOAD_LIKE);
                break;
            }
        }
    }

    // ── Sort ──────────────────────────────────────────────────────────────

    /** Sort newest-first, pinned always at top. Caller passes the filtered
     *  list; sorting happens before submitList so the differ's background
     *  diff sees the final order directly (one diff pass, not sort-then-
     *  separately-diff). */
    public void sortByNewest() {
        List<ReelComment> sorted = new ArrayList<>(items());
        Collections.sort(sorted, NEWEST_FIRST);
        differ.submitList(sorted);
    }

    /** Sort by most-liked, pinned always at top. */
    public void sortByTop() {
        List<ReelComment> sorted = new ArrayList<>(items());
        Collections.sort(sorted, TOP_FIRST);
        differ.submitList(sorted);
    }

    /** Newest-first comparator (pinned always wins) — exposed so the
     *  fragment can sort a list BEFORE submitting it in one pass (see
     *  ReelCommentFragment.applyFilterAndSort()) instead of calling
     *  setComments() and sortByNewest() as two separate diffs. */
    public static final Comparator<ReelComment> NEWEST_FIRST = (a, b) -> {
        if (a.isPinned && !b.isPinned) return -1;
        if (!a.isPinned && b.isPinned) return 1;
        return Long.compare(b.timestamp, a.timestamp);
    };

    /** Most-liked-first comparator (pinned always wins) — see NEWEST_FIRST. */
    public static final Comparator<ReelComment> TOP_FIRST = (a, b) -> {
        if (a.isPinned && !b.isPinned) return -1;
        if (!a.isPinned && b.isPinned) return 1;
        return Integer.compare(b.likesCount, a.likesCount);
    };

    // ── RecyclerView ──────────────────────────────────────────────────────

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_reel_comment, parent, false);
        return new VH(v, this);
    }

    /**
     * Payload-aware partial bind — PERF: when only the like state changed
     * (the overwhelmingly common rapid-fire interaction), skip the entire
     * full bind (avatar Glide load, mention span rebuild, reaction chip
     * rebuild, story-ring lookup) and touch only the heart icon/count.
     * Falls back to a full bind for any other payload or a cold bind.
     */
    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_LIKE)) {
            ReelComment c = items().get(position);
            h.boundComment = c;
            bindLikeState(h, c);
            return;
        }
        super.onBindViewHolder(h, position, payloads);
    }

    private void bindLikeState(VH h, ReelComment c) {
        Context ctx = h.itemView.getContext();
        boolean liked = c.isLikedBy(myUid);
        h.tvLikes.setText(c.likesCount > 0 ? String.valueOf(c.likesCount) : "");
        h.btnLike.setImageResource(liked
            ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        h.btnLike.setColorFilter(liked
            ? ctx.getResources().getColor(android.R.color.holo_red_light)
            : ctx.getResources().getColor(android.R.color.darker_gray));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ReelComment c = items().get(position);
        Context ctx = h.itemView.getContext();
        h.boundComment = c;

        // ── Pin badge ───────────────────────────────────────────────────
        if (h.rowPin != null) {
            h.rowPin.setVisibility(c.isPinned ? View.VISIBLE : View.GONE);
        }

        // ── Avatar ──────────────────────────────────────────────────────
        bindAvatar(ctx, h.ivAvatar, c.uid, c.ownerPhoto);

        // ── Story ring (unseen status indicator) ─────────────────────
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        // PERF: was calling hasUnseen()/hasStatus() up to 3x per bind (once
        // for hasStory, then again inside the if/else below) — compute each
        // exactly once and reuse.
        boolean hasUnseenStory = c.uid != null && scm.hasUnseen(c.uid);
        boolean hasAnyStatus   = c.uid != null && scm.hasStatus(c.uid);
        boolean hasStory = hasUnseenStory || hasAnyStatus;
        h.hasStory = hasStory; // read by the constructor-level click listener

        if (h.ivStoryRing != null && c.uid != null) {
            // Instagram-style: gradient only while unseen; flat gray once
            // the whole story is seen; hidden with no active status.
            // BUG FIX: previously showed gradient whenever hasAnyStatus was
            // true, even after the story was fully seen — ring never
            // reflected the seen state coming back from the new story viewer.
            if (hasUnseenStory) {
                h.ivStoryRing.setBackground(com.callx.app.utils.StoryRingGradientDrawable
                        .withStrokeDp(2f, ctx.getResources().getDisplayMetrics().density));
                h.ivStoryRing.setImageDrawable(null);
                h.ivStoryRing.setVisibility(android.view.View.VISIBLE);
            } else if (hasAnyStatus) {
                h.ivStoryRing.setBackground(null);
                h.ivStoryRing.setImageResource(com.callx.app.core.R.drawable.circle_status_seen);
                h.ivStoryRing.setVisibility(android.view.View.VISIBLE);
            } else {
                h.ivStoryRing.setVisibility(android.view.View.GONE);
            }
        }

        // ── Name ────────────────────────────────────────────────────────
        h.tvName.setText(c.ownerName != null && !c.ownerName.isEmpty()
            ? c.ownerName : "User");

        // ── Time + Edited (+ local-first send state) ─────────────────────
        // Offline/retry: a comment created locally (see
        // ReelCommentFragment#postComment) shows "Sending…" the instant
        // it's typed, then either settles back to the normal relative-time
        // text on success or flips to a tap-to-retry affordance if the
        // Firebase write failed (offline, permission denied, etc.) — same
        // pattern as the chat module's pending/failed message bubbles,
        // instead of the comment silently vanishing or the input just
        // showing a one-off Toast with nothing left in the list to retry.
        if (ReelComment.SEND_STATE_SENDING.equals(c.sendState)) {
            h.tvTime.setText("Sending…");
            h.tvTime.setTextColor(ctx.getResources().getColor(R.color.text_muted));
            h.tvTime.setOnClickListener(null);
            h.tvTime.setClickable(false);
            h.itemView.setAlpha(0.6f);
        } else if (ReelComment.SEND_STATE_FAILED.equals(c.sendState)) {
            h.tvTime.setText("⚠ Failed — tap to retry");
            h.tvTime.setTextColor(0xFFFF3B5C);
            h.itemView.setAlpha(1f);
            h.tvTime.setClickable(true);
            h.tvTime.setOnClickListener(v -> {
                if (listener != null) listener.onRetryComment(c);
            });
        } else {
            h.tvTime.setText(DateUtils.getRelativeTimeSpanString(
                c.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE));
            h.tvTime.setTextColor(ctx.getResources().getColor(R.color.text_muted));
            h.tvTime.setOnClickListener(null);
            h.tvTime.setClickable(false);
            h.itemView.setAlpha(1f);
        }

        if (h.tvEdited != null) {
            h.tvEdited.setVisibility(c.isEdited ? View.VISIBLE : View.GONE);
        }

        // ── Comment text (with clickable @mentions) ─────────────────────
        MentionSpanUtils.bind(h.tvText, c.text, c.mentions);

        // ── Comment photo (Instagram-style attachment) ──────────────────
        if (h.ivCommentImage != null) {
            if (c.imageUrl != null && !c.imageUrl.isEmpty()) {
                h.ivCommentImage.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(c.imageUrl)
                    .apply(commentImageRequestOptions(ctx))
                    .into(h.ivCommentImage);
            } else {
                h.ivCommentImage.setVisibility(View.GONE);
                h.ivCommentImage.setImageDrawable(null);
            }
        }

        // ── Author badge (reel owner posted this comment) ──────────────
        if (h.tvAuthorBadge != null) {
            h.tvAuthorBadge.setVisibility(
                !reelOwnerUid.isEmpty() && reelOwnerUid.equals(c.uid) ? View.VISIBLE : View.GONE);
        }

        // ── "Liked by creator" badge ────────────────────────────────────
        if (h.tvCreatorLiked != null) {
            boolean likedByCreator = !reelOwnerUid.isEmpty() && c.isLikedBy(reelOwnerUid);
            h.tvCreatorLiked.setVisibility(likedByCreator ? View.VISIBLE : View.GONE);
        }

        // ── Emoji reaction strip ────────────────────────────────────────
        if (h.layoutReactions != null) {
            bindReactions(h, c);
        }

        // ── Like button ─────────────────────────────────────────────────
        bindLikeState(h, c);

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
        // PERF: all click/long-click listeners below are attached ONCE in
        // VH's constructor (they read h.boundComment / getAdapterPosition()
        // live) instead of a fresh lambda being allocated here on every
        // single bind — same fix as the double-tap GestureDetector already
        // gets. During a fast fling a recycled row rebinds constantly, so
        // this removes 5 allocations × every bind × every visible row.

        // ── Double-tap comment text to like (Instagram parity) ────────────
        // PERF: the actual GestureDetector is created ONCE in VH's
        // constructor and reused across every bind of this recycled row —
        // previously a new GestureDetector (+ its internal SimpleOnGestureListener
        // + GestureConfig lookups) was allocated on *every single bind*,
        // which fires constantly during fling/scroll as rows recycle. Here
        // we just refresh which comment the already-built detector should
        // act on.
        h.boundComment = c;
    }

    /** Called by VH's single, reused GestureDetector on a confirmed double-tap. */
    private void onDoubleTapLike(VH h, ReelComment c) {
        bounceLikeButton(h.btnLike);
        if (listener != null && !c.isLikedBy(myUid)) {
            listener.onLikeComment(c, h.getAdapterPosition());
        }
    }

    /** Quick scale-up/scale-down pulse on the heart icon — visual feedback
     *  for double-tap-to-like, mirroring Instagram's comment interaction. */
    private static void bounceLikeButton(ImageButton btnLike) {
        if (btnLike == null) return;
        btnLike.animate().cancel();
        btnLike.setScaleX(0.6f);
        btnLike.setScaleY(0.6f);
        btnLike.animate()
            .scaleX(1.15f).scaleY(1.15f)
            .setDuration(140)
            .withEndAction(() -> btnLike.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(120)
                .start())
            .start();
    }

    @Override
    public int getItemCount() { return items().size(); }

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

    private void bindAvatar(Context ctx, ImageView iv, String uid, String photoUrl) {
        // PERF/correctness: tag the row with which uid its avatar is FOR,
        // checked by the async Firebase fallback below before it applies a
        // result — otherwise a slow lookup for a row that's since been
        // recycled to a different comment can land the wrong avatar mid-fling.
        iv.setTag(R.id.tag_avatar_uid, uid);

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
                        // Stale-callback guard: only apply if this row is
                        // still showing the comment we fetched for.
                        if (uid.equals(iv.getTag(R.id.tag_avatar_uid))) {
                            loadAvatarInto(ctx, iv, p);
                        }
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadAvatarInto(Context ctx, ImageView iv, String url) {
        try {
            // PERF: skip the Glide call entirely if this exact URL is
            // already what's loaded/loading into this recycled row — avoids
            // redundant request-building work when a row rebinds with
            // unchanged avatar data (e.g. a like/reaction-only refresh that
            // still routes through a full bind for some other reason).
            Object lastUrl = iv.getTag(R.id.tag_avatar_url);
            if (url.equals(lastUrl)) return;
            iv.setTag(R.id.tag_avatar_url, url);

            // Routed through the central AvatarUrlBuilder — exact size,
            // 2x retina, auto-format Cloudinary variant — and .override()
            // (via the shared, cached RequestOptions) pins the Glide decode
            // size so RecyclerView recycling never decodes larger than the
            // 36dp circle needs.
            String resizedUrl = AvatarUrlBuilder.build(ctx, url, AVATAR_SIZE_DP);
            Glide.with(ctx).load(resizedUrl)
                .apply(avatarRequestOptions(ctx))
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(iv);
        } catch (Exception ignored) {}
    }

    // ── Emoji reaction strip ──────────────────────────────────────────────
    // PERF: reaction chips used to be built with `new TextView(ctx)` +
    // layout.removeAllViews()/addView() on EVERY full bind — allocating up
    // to 3 fresh Views (plus their measure/layout pass) each time a row with
    // reactions scrolls back on screen. VH now owns a fixed pool of 3 chip
    // TextViews created once in its constructor; binding just updates their
    // text/visibility/click target, no view creation or layout-tree churn.

    private void bindReactions(VH h, ReelComment c) {
        LinearLayout layout = h.layoutReactions;
        if (c.reactions == null || c.reactions.isEmpty()) {
            layout.setVisibility(View.GONE);
            for (TextView chip : h.reactionChips) chip.setVisibility(View.GONE);
            return;
        }

        // Aggregate: emoji → count
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String emoji : c.reactions.values()) {
            counts.merge(emoji, 1, Integer::sum);
        }

        // Sort by count desc, take top N (pool size)
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        Collections.sort(sorted, (a, b) -> b.getValue().compareTo(a.getValue()));

        int max = Math.min(h.reactionChips.length, sorted.size());
        for (int i = 0; i < h.reactionChips.length; i++) {
            TextView chip = h.reactionChips[i];
            if (i >= max) {
                chip.setVisibility(View.GONE);
                continue;
            }
            Map.Entry<String, Integer> entry = sorted.get(i);
            final String emoji = entry.getKey();
            chip.setText(emoji + " " + entry.getValue());
            chip.setVisibility(View.VISIBLE);
            // Tap chip → react with same emoji. h.getAdapterPosition() reads
            // the CURRENT position at click time (cheap, O(1)) instead of
            // the old getAdapterPositionOf() linear scan over every comment.
            chip.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (listener != null && pos != RecyclerView.NO_POSITION && h.boundComment != null)
                    listener.onReactComment(h.boundComment, emoji, pos);
            });
        }

        layout.setVisibility(View.VISIBLE);
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
        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setItems(optsArray, (d, which) -> actions.get(which).run())
            .create());
    }

    private void showEmojiPanel(Context ctx, ReelComment c, int position) {
        String[] emojis  = {"❤️", "😂", "😮", "😢", "👏", "🔥"};
        String myReaction = c.getMyReaction(myUid);

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("React to comment")
            .setItems(emojis, (d, which) -> {
                String selected = emojis[which];
                // Toggle: if already reacted with same emoji, remove
                String send = selected.equals(myReaction) ? null : selected;
                if (listener != null) listener.onReactComment(c, send, position);
            })
            .create());
    }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        android.widget.ImageView ivStoryRing;
        ImageView ivCommentImage;
        TextView tvName, tvText, tvTime, tvLikes, btnReply, tvViewReplies;
        TextView tvEdited, tvAuthorBadge, tvCreatorLiked;
        ImageButton btnLike;
        LinearLayout containerReplies;
        LinearLayout layoutReactions;
        View rowPin;

        /** Fixed pool of reaction chip views, created once and reused for
         *  the life of this recycled ViewHolder — see bindReactions(). */
        TextView[] reactionChips;

        /** Which comment this recycled row currently displays — refreshed
         *  every bind, read by every listener below (all attached once,
         *  here in the constructor, instead of freshly per bind). */
        ReelComment boundComment;
        /** Whether boundComment's author currently has an active/unseen
         *  status ring — refreshed every bind, read by the avatar/ring
         *  click listener below. */
        boolean hasStory;

        VH(@NonNull View v, @NonNull ReelCommentsAdapter adapter) {
            super(v);
            rowPin          = v.findViewById(R.id.row_pin);
            ivAvatar        = v.findViewById(R.id.iv_avatar);
            ivStoryRing     = v.findViewById(R.id.iv_story_ring);
            tvName          = v.findViewById(R.id.tv_name);
            tvText          = v.findViewById(R.id.tv_comment_text);
            ivCommentImage  = v.findViewById(R.id.iv_comment_image);
            tvTime          = v.findViewById(R.id.tv_time);
            tvEdited        = v.findViewById(R.id.tv_edited);
            tvAuthorBadge   = v.findViewById(R.id.tv_author_badge);
            tvCreatorLiked  = v.findViewById(R.id.tv_creator_liked);
            tvLikes         = v.findViewById(R.id.tv_likes_count);
            btnLike         = v.findViewById(R.id.btn_like_comment);
            btnReply        = v.findViewById(R.id.btn_reply);
            tvViewReplies   = v.findViewById(R.id.tv_view_replies);
            containerReplies= v.findViewById(R.id.container_replies);
            layoutReactions = v.findViewById(R.id.layout_reactions);

            // PERF: 3 reaction chip TextViews built ONCE here (not per bind)
            // — see bindReactions().
            Context ctx = v.getContext();
            int dp4 = adapter.dp(ctx, 4), dp8 = adapter.dp(ctx, 8), dp2 = adapter.dp(ctx, 2);
            reactionChips = new TextView[3];
            for (int i = 0; i < reactionChips.length; i++) {
                TextView chip = new TextView(ctx);
                chip.setTextSize(11f);
                chip.setTextColor(0xFF5B5BF6);
                chip.setBackgroundResource(R.drawable.bg_reaction_chip);
                chip.setPadding(dp8, dp2, dp8, dp2);
                chip.setVisibility(View.GONE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, dp4, dp4, 0);
                chip.setLayoutParams(lp);
                reactionChips[i] = chip;
                if (layoutReactions != null) layoutReactions.addView(chip);
            }

            // PERF: built ONCE per row (not per bind) and reused for the
            // life of this recycled ViewHolder — see onBindViewHolder note.
            final GestureDetector doubleTapDetector = new GestureDetector(
                v.getContext(), new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        if (boundComment != null) adapter.onDoubleTapLike(VH.this, boundComment);
                        return true;
                    }
                });
            tvText.setOnTouchListener((view, event) -> {
                doubleTapDetector.onTouchEvent(event);
                return false;
            });

            // PERF: every listener below is attached ONCE here rather than
            // reassigned on every onBindViewHolder call — during a fast
            // fling a recycled row rebinds constantly, so a fresh lambda
            // per bind per listener adds up fast. Each one reads the LIVE
            // boundComment / getAdapterPosition() at click time instead.
            if (ivStoryRing != null) {
                ivStoryRing.setOnClickListener(v2 -> {
                    if (boundComment != null) adapter.openCommentStatus(v2.getContext(), boundComment);
                });
            }

            ivAvatar.setOnClickListener(v2 -> {
                if (boundComment == null) return;
                if (hasStory) adapter.openCommentStatus(v2.getContext(), boundComment);
                else if (adapter.listener != null) adapter.listener.onAvatarClick(boundComment);
            });

            // Tap the attached photo → small centered zoom (70% bigger than
            // the 150dp thumbnail, own aspect ratio). NOT the avatar-zoom
            // dialog (that one is fullscreen + forces a circle, which is
            // right for avatars but wrong for a comment photo).
            if (ivCommentImage != null) {
                ivCommentImage.setOnClickListener(v2 -> {
                    if (boundComment != null && boundComment.imageUrl != null
                            && !boundComment.imageUrl.isEmpty()) {
                        com.callx.app.utils.DialogFullscreenHelper.showCommentPhotoZoom(
                            v2.getContext(), boundComment.imageUrl);
                    }
                });
            }

            btnLike.setOnClickListener(v2 -> {
                if (boundComment != null && adapter.listener != null)
                    adapter.listener.onLikeComment(boundComment, getAdapterPosition());
            });

            btnReply.setOnClickListener(v2 -> {
                if (boundComment != null && adapter.listener != null)
                    adapter.listener.onReplyComment(boundComment);
            });

            tvViewReplies.setOnClickListener(v2 -> {
                if (boundComment != null && adapter.listener != null)
                    adapter.listener.onViewReplies(boundComment, containerReplies, tvViewReplies);
            });

            itemView.setOnLongClickListener(v2 -> {
                if (boundComment != null && adapter.listener != null)
                    adapter.showContextMenu(v2.getContext(), boundComment, getAdapterPosition());
                return true;
            });
        }
    }
}
