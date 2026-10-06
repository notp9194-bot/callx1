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
import com.callx.app.cache.AvatarVersionSyncManager;

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
        /** New in v34 — bookmark (save) action from the engagement bar. Default no-op. */
        default void onBookmark(CommunityPostEntity post) {}
        /** New in v32 — tapped the author avatar/name. */
        default void onAuthorClick(CommunityPostEntity post) {}
        /** New in v32 — tapped an @mention span inside the post text. */
        default void onMentionClick(CommunityPostEntity post, String rawMention) {}
        /** Author ne "Edit post" chuna. Default no-op. */
        default void onEdit(CommunityPostEntity post) {}
        /** Admin/owner ne Pin/Unpin chuna (`pin` = naya state). Default no-op. */
        default void onPin(CommunityPostEntity post, boolean pin) {}
        /** Multi-photo post: tapped cell `index` (0-based). Default: single-media click. */
        default void onMediaCellClicked(CommunityPostEntity post, int index) { onMediaClicked(post); }
        /** Tapped the reaction count/summary — opens the "who reacted" detail sheet. */
        default void onReactionsDetail(CommunityPostEntity post) {}
        /** Tapped a web link inside post text. Default no-op. */
        default void onLinkClick(String url) {}
        /** Tapped a #hashtag inside post text. Default no-op. */
        default void onHashtagClick(String hashtag) {}
    }

    private static final DiffUtil.ItemCallback<CommunityPostEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityPostEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return !renderedBodyChanged(a, b)
                            && a.likeCount == b.likeCount
                            && a.commentCount == b.commentCount
                            && safeEq(a.reactionCountsJson, b.reactionCountsJson)
                            && safeEq(a.myReactionType, b.myReactionType)
                            && safeEq(a.pollJson, b.pollJson);
                }
                // FIX: pehle sirf like/comment/reaction/poll compare hote the — post edit, pin/unpin,
                // author name/photo ya media badalne par card refresh nahi hota tha. Ye wo saare
                // fields hain jo canvas card me actually draw hote hain. (share/view/bookmark count
                // draw nahi hote, isliye jaan-boojh kar bahar — warna har view-count bump par rebind.)
                private boolean renderedBodyChanged(CommunityPostEntity a, CommunityPostEntity b) {
                    return !safeEq(a.text, b.text)
                            || a.editedAt != b.editedAt
                            || a.pinned != b.pinned
                            || a.isAnnouncement != b.isAnnouncement
                            || !safeEq(a.authorName, b.authorName)
                            || !safeEq(a.authorPhoto, b.authorPhoto)
                            || !safeEq(a.mediaUrl, b.mediaUrl)
                            || !safeEq(a.mediaType, b.mediaType)
                            || !safeEq(a.mediaUrlsJson, b.mediaUrlsJson)
                            || !safeEq(a.mediaTypesJson, b.mediaTypesJson);
                }
                // PERF: partial rebind — a like/comment/reaction tap or a poll
                // vote from another member re-syncs this single post, but we
                // don't want to redo avatar/media Glide loads on every tap.
                // Body (text/pin/author/media) badla ho to null => full rebind.
                @Override
                public Object getChangePayload(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    if (renderedBodyChanged(a, b)) return null;
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
    static final String PAYLOAD_BOOKMARK   = "bookmark";

    private final AsyncListDiffer<CommunityPostEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;
    private final String currentUid;
    private boolean isAdminOrOwner = false;

    public CommunityPostAdapter(String currentUid, Listener listener) {
        this.currentUid = currentUid;
        this.listener = listener;
    }

    // ── Bookmark state cache ───────────────────────────────────────────────
    // FIX: pehle canvas har bind() me SharedPreferences se bookmark padhta tha, aur tap ke baad
    // row refresh nahi hoti thi (icon flip nahi hota tha). Ab postId -> bool cache hai: har post
    // ke liye prefs sirf ek baar padhta hai, aur setBookmarked() sirf us row ka glyph repaint karta hai.
    private final java.util.Map<String, Boolean> bookmarkCache = new java.util.HashMap<>();

    // "Read more" expand state — view recycle hone par scroll ke baad wapas collapse na ho.
    private final java.util.Set<String> expandedPostIds = new java.util.HashSet<>();

    private boolean bookmarkedState(android.content.Context ctx, CommunityPostEntity p) {
        Boolean cached = bookmarkCache.get(p.id);
        if (cached == null) {
            cached = CommunityBookmarksActivity.isBookmarked(ctx, p.communityId, p.id);
            bookmarkCache.put(p.id, cached);
        }
        return cached;
    }

    /** Fragment bookmark toggle ke baad call kare — sirf us row ka bookmark icon update hota hai. */
    public void setBookmarked(String postId, boolean bookmarked) {
        bookmarkCache.put(postId, bookmarked);
        List<CommunityPostEntity> list = differ.getCurrentList();
        for (int i = 0; i < list.size(); i++) {
            if (postId.equals(list.get(i).id)) { notifyItemChanged(i, PAYLOAD_BOOKMARK); return; }
        }
    }

    /** Bookmarks screen se wapas aane par — cache drop karke visible rows ka icon dobara sync. */
    public void refreshBookmarks() {
        bookmarkCache.clear();
        int n = differ.getCurrentList().size();
        if (n > 0) notifyItemRangeChanged(0, n, PAYLOAD_BOOKMARK);
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
            if (payloads.contains(PAYLOAD_BOOKMARK)) {
                h.canvasView.setBookmarked(bookmarkedState(h.canvasView.getContext(), p));
                // bookmark ke saath engagement bhi badla ho sakta hai (dono payload ek saath merge hote hain)
                if (payloads.contains(PAYLOAD_ENGAGEMENT)) h.canvasView.updateEngagementOnly(p);
                if (payloads.contains(PAYLOAD_POLL)) h.canvasView.updatePollOnly(p, currentUid);
                return;
            }
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
    private RequestOptions mediaRequestOptions;

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

        cv.bind(p, isAdminOrOwner, currentUid, bookmarkedState(cv.getContext(), p));
        cv.setTextExpanded(expandedPostIds.contains(p.id));
        wireClickListener(cv, p);

        // PERF: cancel whatever this (recycled) holder was still loading
        // before starting new requests — previously a brand-new anonymous
        // CustomTarget was created on every bind with nothing ever clearing
        // the old one, so a fast fling could leave several completed decodes
        // racing to land on a view that had already scrolled past them.
        if (h.avatarTarget != null) com.callx.app.cache.CommunityAvatarBinder.cancelBitmap(cv.getContext(), h.avatarTarget);
        if (h.mediaTarget != null) Glide.with(cv.getContext()).clear(h.mediaTarget);

        // FIX (avatar pipeline parity): was a hand-rolled dp*density
        // override with no tier bucketing (see CommunityAvatarBinder class
        // doc) — same author photo shown here AND in the member list
        // decoded/cached separately before this.
        //
        // FIX (avatar delta-sync gap): p.authorPhoto is a real user avatar
        // (carries an avatarVersion on the User model, unlike the community/
        // group ICON this binder also serves) — now resolves that version
        // via AvatarVersionSyncManager's cached fallback so a stale cached
        // photo refreshes once ANY screen (author's profile, a reel/status
        // of theirs) has observed their real bump. See CommunityAvatarBinder's
        // versioned url()/bindBitmap() overloads.
        long authorVersion = (p.authorUid != null && !p.authorUid.isEmpty())
                ? AvatarVersionSyncManager.getInstance(cv.getContext()).getCachedVersion(p.authorUid) : 0L;
        h.avatarTarget = com.callx.app.cache.CommunityAvatarBinder.bindBitmap(
                cv.getContext(), p.authorPhoto, com.callx.app.cache.CommunityAvatarBinder.TIER_POST_AUTHOR,
                authorVersion, bmp -> cv.setAuthorAvatarBitmap(p.id, bmp));

        // Multi-photo post: mediaUrlsJson me 2+ items => grid (pehle sirf pehli image dikhti thi).
        clearGroupTargets(h, cv.getContext());
        final java.util.List<String> groupUrls = parseJsonStrings(p.mediaUrlsJson);
        if (groupUrls.size() >= 2) {
            bindGroup(h, cv, p, groupUrls);
            h.mediaTarget = null;
            return;
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
                    .into(h.mediaTarget);
        } else {
            h.mediaTarget = null;
        }
    }

    // ── Multi-photo grid ───────────────────────────────────────────────────
    private static java.util.List<String> parseJsonStrings(String json) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (v != null && !v.isEmpty() && !"null".equals(v)) out.add(v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void clearGroupTargets(VH h, android.content.Context ctx) {
        for (int i = 0; i < h.groupTargets.length; i++) {
            if (h.groupTargets[i] != null) {
                Glide.with(ctx).clear(h.groupTargets[i]);
                h.groupTargets[i] = null;
            }
        }
    }

    private void bindGroup(VH h, CommunityPostCanvasView cv, CommunityPostEntity p,
                           java.util.List<String> urls) {
        final android.content.Context ctx = cv.getContext();
        final java.util.List<String> types = parseJsonStrings(p.mediaTypesJson);
        final int total = urls.size();
        final int cells = Math.min(total, 4);

        java.util.List<Bitmap> bitmaps = new java.util.ArrayList<>(cells);
        java.util.List<Boolean> videoFlags = new java.util.ArrayList<>(cells);
        for (int i = 0; i < cells; i++) {
            bitmaps.add(null);
            videoFlags.add(i < types.size() && "video".equals(types.get(i)));
        }
        cv.bindMediaGroup(bitmaps, videoFlags, total);

        final int fullW = Math.min(ctx.getResources().getDisplayMetrics().widthPixels, 1080);
        final int fullH = CommunityPostCanvasView.mediaHeightPx(ctx);
        for (int i = 0; i < cells; i++) {
            final int idx = i;
            int w, hh;
            if (cells == 2)      { w = fullW / 2;                     hh = fullH; }
            else if (cells == 3) { w = idx == 0 ? (int) (fullW * 0.6f) : (int) (fullW * 0.4f);
                                   hh = idx == 0 ? fullH : fullH / 2; }
            else                 { w = fullW / 2;                     hh = fullH / 2; }
            CustomTarget<Bitmap> t = new CustomTarget<Bitmap>() {
                @Override public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> tr) {
                    cv.setMediaGroupBitmap(p.id, idx, resource);
                }
                @Override public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                    cv.setMediaGroupBitmap(p.id, idx, null);
                }
            };
            h.groupTargets[i] = t;
            Glide.with(ctx).asBitmap()
                    .load(urls.get(i))
                    .apply(RequestOptions.centerCropTransform()
                            .override(w, hh)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.ALL))
                    .into(t);
        }
    }

    private void wireClickListener(CommunityPostCanvasView cv, CommunityPostEntity p) {
        cv.setOnPostClickListener(new OnPostClickListener() {
            // Card ka khali hissa tap => comments (pehle no-op tha).
            @Override public void onPostClick() { if (listener != null) listener.onComment(p); }
            // Card long-press => wahi options menu (Copy/Edit/Pin/Delete/Report).
            @Override public void onPostLongClick() { showPostOptions(cv, p); }
            @Override public void onAuthorClick() { if (listener != null) listener.onAuthorClick(p); }
            @Override public void onOptionsClick() { showPostOptions(cv, p); }
            @Override public void onMentionClick(String rawMention) { if (listener != null) listener.onMentionClick(p, rawMention); }
            @Override public void onMediaClick() { if (listener != null) listener.onMediaClicked(p); }
            @Override public void onTextExpandToggled(boolean expanded) {
                if (expanded) expandedPostIds.add(p.id); else expandedPostIds.remove(p.id);
            }
            @Override public void onMediaCellClick(int index) { if (listener != null) listener.onMediaCellClicked(p, index); }
            // "+N" cell: 4th photo (index 3) kholo — fullscreen viewer single-image hai.
            @Override public void onMediaGroupOverflowClick() { if (listener != null) listener.onMediaCellClicked(p, 3); }
            @Override public void onPollOptionClick(int optionIndex) { if (listener != null) listener.onPollVote(p, optionIndex); }
            @Override public void onReactionsClick() { if (listener != null) listener.onReactionsDetail(p); }
            @Override public void onLikeClick() { if (listener != null) listener.onLike(p); }
            @Override public void onLikeLongClick(android.view.View anchorView) {
                if (listener != null) listener.onLongPressLike(p, anchorView);
            }
            @Override public void onCommentClick()  { if (listener != null) listener.onComment(p);   }
            @Override public void onShareClick()    { if (listener != null) listener.onShare(p);    }
            @Override public void onBookmarkClick() { if (listener != null) listener.onBookmark(p); }
        });
    }

    private static final int MENU_COPY = 1, MENU_EDIT = 2, MENU_PIN = 3, MENU_DELETE = 4, MENU_REPORT = 5;

    private void showPostOptions(CommunityPostCanvasView anchor, CommunityPostEntity p) {
        final boolean isAuthor = currentUid != null && currentUid.equals(p.authorUid);
        final boolean hasText = p.text != null && !p.text.trim().isEmpty();
        android.widget.PopupMenu popup = new android.widget.PopupMenu(anchor.getContext(), anchor);
        if (hasText) popup.getMenu().add(0, MENU_COPY, 0, "Copy text");
        if (isAuthor) popup.getMenu().add(0, MENU_EDIT, 1, "Edit post");
        if (isAdminOrOwner) popup.getMenu().add(0, MENU_PIN, 2, p.pinned ? "Unpin post" : "Pin post");
        if (isAdminOrOwner || isAuthor) popup.getMenu().add(0, MENU_DELETE, 3, "Delete Post");
        if (!isAuthor) popup.getMenu().add(0, MENU_REPORT, 4, "Report Post");
        if (popup.getMenu().size() == 0) return;
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_COPY: {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            anchor.getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("post", p.text));
                        android.widget.Toast.makeText(anchor.getContext(), "Copied",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                case MENU_EDIT:   if (listener != null) listener.onEdit(p); return true;
                case MENU_PIN:    if (listener != null) listener.onPin(p, !p.pinned); return true;
                case MENU_DELETE: if (listener != null) listener.onDelete(p); return true;
                case MENU_REPORT: if (listener != null) listener.onReport(p); return true;
                default: return false;
            }
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
            com.callx.app.cache.CommunityAvatarBinder.cancelBitmap(h.canvasView.getContext(), h.avatarTarget);
            h.avatarTarget = null;
        }
        if (h.mediaTarget != null) {
            Glide.with(h.canvasView.getContext()).clear(h.mediaTarget);
            h.mediaTarget = null;
        }
        clearGroupTargets(h, h.canvasView.getContext());
    }

    public java.util.List<com.callx.app.db.entity.CommunityPostEntity> getCurrentList() { return differ.getCurrentList(); }
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final CommunityPostCanvasView canvasView;
        Target<Bitmap> avatarTarget;
        Target<Bitmap> mediaTarget;
        final Target<Bitmap>[] groupTargets = newTargetArray();
        @SuppressWarnings("unchecked")
        private static Target<Bitmap>[] newTargetArray() { return (Target<Bitmap>[]) new Target[4]; }
        VH(@NonNull CommunityPostCanvasView itemView) {
            super(itemView);
            this.canvasView = itemView;
        }
    }
}
