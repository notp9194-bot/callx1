package com.callx.app.community;

import android.graphics.Bitmap;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.community.canvas.CommunityPostCanvasView;
import com.callx.app.community.canvas.OnPostClickListener;
import com.callx.app.db.entity.CommunityPostEntity;

import java.util.Collections;
import java.util.List;

/**
 * v32: Feed post adapter — now backed by CommunityPostCanvasView (Canvas
 * rendering) instead of the inflated item_community_post.xml tree, mirroring
 * the chat module's MessageBubbleCanvasView migration: one custom View per
 * row draws its own header/text/media/poll/reactions/engagement bar, no
 * child-view inflate or measure/layout pass.
 *
 * Avatar and single-media bitmaps are still fetched with Glide (asBitmap +
 * CustomTarget), same as MessagePagingAdapter does for its canvas bubbles —
 * the canvas view only ever receives already-decoded Bitmaps, never a URL.
 */
public class CommunityPostAdapter extends RecyclerView.Adapter<CommunityPostAdapter.VH> {

    public interface Listener {
        void onLike(CommunityPostEntity post);
        void onComment(CommunityPostEntity post);
        void onLongPressLike(CommunityPostEntity post, android.view.View anchorView);
        void onReaction(CommunityPostEntity post, String reactionType);
        void onDelete(CommunityPostEntity post);
        void onReport(CommunityPostEntity post);
        void onPollVote(CommunityPostEntity post, int optionIndex);
        void onMediaClicked(CommunityPostEntity post);
        /** New in v32 — share action from the engagement bar. Default no-op so any
         *  pre-existing implementer of this interface keeps compiling unchanged. */
        default void onShare(CommunityPostEntity post) {}
        /** New in v32 — tapped the author avatar/name. */
        default void onAuthorClick(CommunityPostEntity post) {}
        /** New in v32 — tapped an @mention span inside the post text. */
        default void onMentionClick(CommunityPostEntity post, String rawMention) {}
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
                // PERF: partial rebind — a like/comment/reaction tap or a poll
                // vote from another member re-syncs this single post, but we
                // don't want to redo avatar/media Glide loads on every tap.
                // If ONLY the engagement fields (or ONLY the poll) changed,
                // return a payload so onBindViewHolder can skip straight to
                // the canvas view's updateEngagementOnly()/updatePollOnly().
                @Override
                public Object getChangePayload(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    boolean pollChanged = !safeEq(a.pollJson, b.pollJson);
                    boolean engagementChanged = a.likeCount != b.likeCount
                            || a.commentCount != b.commentCount
                            || !safeEq(a.reactionCountsJson, b.reactionCountsJson)
                            || !safeEq(a.myReactionType, b.myReactionType);

                    if (engagementChanged && !pollChanged) return PAYLOAD_ENGAGEMENT;
                    if (pollChanged && !engagementChanged) return PAYLOAD_POLL;
                    return null;
                }
                private boolean safeEq(String x, String y) { return x == null ? y == null : x.equals(y); }
            };

    static final String PAYLOAD_ENGAGEMENT = "engagement";
    static final String PAYLOAD_POLL       = "poll";

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
        CommunityPostCanvasView cv = new CommunityPostCanvasView(parent.getContext());
        cv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        return new VH(cv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
        CommunityPostEntity p = differ.getCurrentList().get(pos);
        if (!payloads.isEmpty()) {
            if (payloads.contains(PAYLOAD_ENGAGEMENT)) {
                h.canvasView.updateEngagementOnly(p);
                return;
            }
            if (payloads.contains(PAYLOAD_POLL)) {
                h.canvasView.updatePollOnly(p, currentUid);
                return;
            }
        }
        onBindViewHolder(h, pos);
    }

    // PERF: lazily-built, shared RequestOptions — override() constrains Glide's
    // decode to the actual on-screen pixel size instead of the previous
    // asBitmap()+CustomTarget default of Target.SIZE_ORIGINAL, which meant a
    // full-resolution camera photo (e.g. 4000x3000) was fully decoded into
    // memory just to render into a ~40dp avatar circle or a 200dp media card.
    // PREFER_RGB_565 halves per-pixel memory for photos that don't need an
    // alpha channel, which is true for both avatars and post media here.
    private RequestOptions avatarRequestOptions;
    private RequestOptions mediaRequestOptions;

    private RequestOptions avatarRequestOptions(android.content.Context ctx) {
        if (avatarRequestOptions == null) {
            int px = CommunityPostCanvasView.avatarPx(ctx);
            avatarRequestOptions = RequestOptions.circleCropTransform()
                    .override(px, px)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL);
        }
        return avatarRequestOptions;
    }

    private RequestOptions mediaRequestOptions(android.content.Context ctx) {
        if (mediaRequestOptions == null) {
            int h = CommunityPostCanvasView.mediaHeightPx(ctx);
            int w = Math.min(ctx.getResources().getDisplayMetrics().widthPixels, 1080);
            mediaRequestOptions = RequestOptions.centerCropTransform()
                    .override(w, h)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL);
        }
        return mediaRequestOptions;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityPostEntity p = differ.getCurrentList().get(pos);
        CommunityPostCanvasView cv = h.canvasView;

        cv.bind(p, isAdminOrOwner, currentUid);
        wireClickListener(cv, p);

        // PERF: cancel whatever this (recycled) holder was still loading
        // before starting new requests — previously a brand-new anonymous
        // CustomTarget was created on every bind with nothing ever clearing
        // the old one, so a fast fling could leave several completed decodes
        // racing to land on a view that had already scrolled past them.
        if (h.avatarTarget != null) Glide.with(cv.getContext()).clear(h.avatarTarget);
        if (h.mediaTarget != null) Glide.with(cv.getContext()).clear(h.mediaTarget);

        if (p.authorPhoto != null && !p.authorPhoto.isEmpty()) {
            h.avatarTarget = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                    cv.setAuthorAvatarBitmap(p.id, resource);
                }
                @Override
                public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                    cv.setAuthorAvatarBitmap(p.id, null);
                }
            };
            Glide.with(cv.getContext()).asBitmap()
                    .load(p.authorPhoto)
                    .apply(avatarRequestOptions(cv.getContext()))
                    .override(720, 720)
                    .into(h.avatarTarget);
        } else {
            h.avatarTarget = null;
        }

        if (p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
            h.mediaTarget = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                    cv.setMediaBitmap(p.id, resource);
                }
                @Override
                public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                    cv.setMediaBitmap(p.id, null);
                }
            };
            Glide.with(cv.getContext()).asBitmap()
                    .load(p.mediaUrl)
                    .apply(mediaRequestOptions(cv.getContext()))
                    .override(720, 720)
                    .into(h.mediaTarget);
        } else {
            h.mediaTarget = null;
        }
    }

    private void wireClickListener(CommunityPostCanvasView cv, CommunityPostEntity p) {
        cv.setOnPostClickListener(new OnPostClickListener() {
            @Override public void onPostClick() { /* no-op: whole-card tap has no default action */ }
            @Override public void onPostLongClick() { /* no-op: no card-level context menu yet */ }
            @Override public void onAuthorClick() { if (listener != null) listener.onAuthorClick(p); }
            @Override public void onOptionsClick() { showPostOptions(cv, p); }
            @Override public void onMentionClick(String rawMention) { if (listener != null) listener.onMentionClick(p, rawMention); }
            @Override public void onMediaClick() { if (listener != null) listener.onMediaClicked(p); }
            @Override public void onPollOptionClick(int optionIndex) { if (listener != null) listener.onPollVote(p, optionIndex); }
            @Override public void onReactionsClick() { /* no-op: reaction-details sheet not wired yet, same as legacy layoutReactions (no click listener) */ }
            @Override public void onLikeClick() { if (listener != null) listener.onLike(p); }
            @Override public void onLikeLongClick(android.view.View anchorView) {
                if (listener != null) listener.onLongPressLike(p, anchorView);
            }
            @Override public void onCommentClick() { if (listener != null) listener.onComment(p); }
            @Override public void onShareClick() { if (listener != null) listener.onShare(p); }
        });
    }

    private void showPostOptions(CommunityPostCanvasView anchor, CommunityPostEntity p) {
        boolean isAuthor = currentUid != null && currentUid.equals(p.authorUid);
        android.widget.PopupMenu popup = new android.widget.PopupMenu(anchor.getContext(), anchor);
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
        // PERF: cancel any still-in-flight avatar/media loads for this row
        // now that it's leaving the screen, instead of letting them finish
        // decoding in the background only to be discarded by the currentUid
        // guard in setAuthorAvatarBitmap()/setMediaBitmap(). Frees up Glide's
        // decode executor for the rows actually becoming visible.
        if (h.avatarTarget != null) {
            Glide.with(h.canvasView.getContext()).clear(h.avatarTarget);
            h.avatarTarget = null;
        }
        if (h.mediaTarget != null) {
            Glide.with(h.canvasView.getContext()).clear(h.mediaTarget);
            h.mediaTarget = null;
        }
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final CommunityPostCanvasView canvasView;
        Target<Bitmap> avatarTarget;
        Target<Bitmap> mediaTarget;
        VH(@NonNull CommunityPostCanvasView itemView) {
            super(itemView);
            this.canvasView = itemView;
        }
    }
}
