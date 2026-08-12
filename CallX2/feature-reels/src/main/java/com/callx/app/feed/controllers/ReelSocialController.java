package com.callx.app.feed.controllers;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.callx.app.models.ReelModel;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.workers.ReelRepostWorker;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages social actions: like, save, follow, repost, emoji reactions,
 * Firebase real-time listeners, view count recording, liker avatar row,
 * and live reaction counts strip.
 */
public class ReelSocialController {

    private static final long REPOST_RATE_LIMIT_MS = 2_000L;

    private final ReelPlayerDelegate delegate;

    // ── Owned views ───────────────────────────────────────────────────────
    private ImageButton btnLike;
    private ImageButton btnSave;
    private ImageButton btnRepost;
    private TextView    tvRepostCount;
    private TextView    tvLikesCount;
    private TextView    tvCommentsCount;
    private TextView    tvSharesCount;
    private TextView    tvFollowBtn;
    private TextView    tvCollabFollowBtn;
    private View        btnFollowOverlay;
    private LinearLayout layoutReactions;
    private LinearLayout layoutLiveReactions;
    private ImageView   ivLikeAnim;
    private com.callx.app.reaction.FloatingReactionOverlayView reactionBurstOverlay;
    // Guards the Instagram-style floating-emoji replay to once per bind
    // (loadLiveReactionCounts' listener re-fires on every reaction change,
    // which is correct for the live count chips but would otherwise
    // replay the burst repeatedly for the same viewing session).
    private boolean reactionBurstPlayed = false;

    // Floating liker avatars
    private FrameLayout      llLikersAvatarRow;
    private View             bottomInfoColumn;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener bottomInfoLayoutListener;
    private CircleImageView  ivLiker1, ivLiker2, ivLiker3;
    private TextView         tvHeart1, tvHeart2, tvHeart3;
    private View             flLiker1, flLiker2, flLiker3;
    private ObjectAnimator   floatAnim1, floatAnim2, floatAnim3;
    private String[]         likerUidCache = new String[3];
    // Instagram-level liker-row correctness:
    //  - fetchGeneration: bumped every time a fresh top-3 snapshot arrives.
    //    Each per-user async avatar fetch captures the generation it started
    //    with and checks it's still current before touching a view — kills
    //    the race where a stale rapid-like/unlike response lands after a
    //    newer one and overwrites the correct avatar.
    //  - loadedGeneration/loadedCount: AtomicInteger-style completion tally
    //    for the *current* generation only, so the float animation fires
    //    once all (and only all) of *this* batch's avatars have actually
    //    loaded — not keyed to array index, which doesn't reflect real
    //    async completion order.
    private int               fetchGeneration = 0;
    private final java.util.concurrent.atomic.AtomicInteger loadedCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    // ── Owned state ───────────────────────────────────────────────────────
    private boolean isLiked           = false;
    private boolean isSaved           = false;
    private boolean isFollowing       = false;
    private boolean followCheckLoaded = false;
    private boolean isReposted        = false;
    private boolean reactionsVisible  = false;
    private long    lastRepostActionMs = 0L;

    // FIX: Guard — double-start aur double-remove se bachao.
    // startFirebaseListeners() ab applyVisibleState(true) se call hota hai.
    // Agar bina remove ke dobara call ho (e.g. prewarm race) to duplicate
    // Firebase connections ban jaayenge. Guard se ensure hota hai ki sirf
    // ek baar start hota hai aur sirf ek baar remove hota hai.
    private boolean listenersActive = false;

    // Firebase listeners
    private ValueEventListener likeListener;
    private ValueEventListener saveListener;
    private ValueEventListener followListener;
    private ValueEventListener countListener;
    private ValueEventListener repostListener;
    private ValueEventListener reactionsListener;
    private ValueEventListener likersListener;
    // The exact Query the likersListener is attached to (orderByValue().limitToLast(3)),
    // not just the base ref — removeEventListener requires the same Query
    // instance/equivalent to actually detach a listener registered on a query.
    private com.google.firebase.database.Query likersQuery;

    public ReelSocialController(ReelPlayerDelegate delegate) {
        this.delegate = delegate;
    }

    // ── View binding ──────────────────────────────────────────────────────

    public void bindViews(View root) {
        btnLike           = root.findViewById(R.id.btn_like);
        btnSave           = root.findViewById(R.id.btn_save);
        btnRepost         = root.findViewById(R.id.btn_repost);
        tvRepostCount     = root.findViewById(R.id.tv_repost_count);
        tvLikesCount      = root.findViewById(R.id.tv_likes_count);
        tvCommentsCount   = root.findViewById(R.id.tv_comments_count);
        tvSharesCount     = root.findViewById(R.id.tv_shares_count);
        tvFollowBtn       = root.findViewById(R.id.tv_follow_btn);
        tvCollabFollowBtn = root.findViewById(R.id.tv_collab_follow_btn);
        btnFollowOverlay  = root.findViewById(R.id.btn_follow_overlay);
        layoutReactions   = root.findViewById(R.id.layout_reactions);
        layoutLiveReactions = root.findViewById(R.id.layout_live_reactions);
        ivLikeAnim        = root.findViewById(R.id.iv_like_anim);

        llLikersAvatarRow = root.findViewById(R.id.ll_likers_avatar_row);
        bottomInfoColumn  = root.findViewById(R.id.bottom_info);
        ivLiker1          = root.findViewById(R.id.iv_liker_1);
        ivLiker2          = root.findViewById(R.id.iv_liker_2);
        ivLiker3          = root.findViewById(R.id.iv_liker_3);
        tvHeart1          = root.findViewById(R.id.tv_heart_1);
        tvHeart2          = root.findViewById(R.id.tv_heart_2);
        tvHeart3          = root.findViewById(R.id.tv_heart_3);
        flLiker1          = root.findViewById(R.id.fl_liker_1);
        flLiker2          = root.findViewById(R.id.fl_liker_2);
        flLiker3          = root.findViewById(R.id.fl_liker_3);
        reactionBurstOverlay = root.findViewById(R.id.reel_reaction_burst_overlay);

        attachDynamicLikerRowSpacing();
    }

    // UI FIX: the liker-avatar row used a hardcoded 120dp bottom margin,
    // sized for a single-line caption. bottom_info (username, collab-author
    // row, caption, hashtag chips, music ticker) is a wrap_content column
    // whose real height varies per reel — e.g. a reel with the collab row
    // and music ticker visible is taller than one with neither. A fixed
    // margin meant the avatar row could sit *inside* that taller column
    // instead of clear above it. Instead, measure bottom_info live and pin
    // the avatar row a fixed gap above its actual top edge, every time its
    // layout changes (visibility toggles, text reflow, rotation, etc).
    private void attachDynamicLikerRowSpacing() {
        if (llLikersAvatarRow == null || bottomInfoColumn == null) return;
        final int gapPx = delegate.dpToPx(16);
        bottomInfoLayoutListener = () -> {
            if (llLikersAvatarRow == null || bottomInfoColumn == null) return;
            int desiredMargin = bottomInfoColumn.getHeight() + gapPx;
            ViewGroup.LayoutParams lpRaw = llLikersAvatarRow.getLayoutParams();
            if (!(lpRaw instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) lpRaw;
            if (lp.bottomMargin != desiredMargin) {
                lp.bottomMargin = desiredMargin;
                llLikersAvatarRow.setLayoutParams(lp);
            }
        };
        bottomInfoColumn.getViewTreeObserver().addOnGlobalLayoutListener(bottomInfoLayoutListener);
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public boolean isLiked()            { return isLiked; }
    public boolean isSaved()            { return isSaved; }
    public boolean isFollowing()        { return isFollowing; }
    public boolean isFollowCheckLoaded() { return followCheckLoaded; }
    public boolean isReposted()         { return isReposted; }
    public ImageButton getBtnLike()     { return btnLike; }
    public ImageButton getBtnSave()     { return btnSave; }
    public TextView getTvFollowBtn()    { return tvFollowBtn; }

    // ── Click listener wiring ─────────────────────────────────────────────

    public void setupClickListeners() {
        if (btnLike != null) {
            btnLike.setOnClickListener(v -> { delegate.hideReactions(); delegate.toggleLike(); });
            btnLike.setOnLongClickListener(v -> { delegate.toggleReactionPanel(); return true; });
        }
        if (tvLikesCount != null) tvLikesCount.setOnClickListener(v -> delegate.openLikesSheet());
        if (tvSharesCount != null) tvSharesCount.setOnClickListener(v -> delegate.openSharesSheet());
        if (tvCommentsCount != null) tvCommentsCount.setOnClickListener(v -> delegate.openCommentsSheet());
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> delegate.toggleSave());
            btnSave.setOnLongClickListener(v -> { delegate.openBookmarkCollections(); return true; });
        }
        if (btnRepost != null) btnRepost.setOnClickListener(v -> delegate.toggleRepost());
        if (tvFollowBtn != null) tvFollowBtn.setOnClickListener(v -> delegate.toggleFollow());
        if (tvCollabFollowBtn != null) tvCollabFollowBtn.setOnClickListener(v -> delegate.toggleFollow());
        if (btnFollowOverlay != null) btnFollowOverlay.setOnClickListener(v -> delegate.toggleFollow());

        // Emoji reaction buttons
        setupReactionClick(R.id.react_fire,  "🔥");
        setupReactionClick(R.id.react_heart, "❤️");
        setupReactionClick(R.id.react_wow,   "😮");
        setupReactionClick(R.id.react_laugh, "😂");
        setupReactionClick(R.id.react_sad,   "😢");
        setupReactionClick(R.id.react_clap,  "👏");
    }

    private void setupReactionClick(int id, String emoji) {
        if (!delegate.isAdded() || delegate.getFragment().getView() == null) return;
        View v = delegate.getFragment().getView().findViewById(id);
        if (v != null) {
            v.setOnClickListener(ev -> { delegate.sendReaction(emoji); hideReactions(); });
        }
    }

    // ── Initial data population ───────────────────────────────────────────

    public void populateCounts() {
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        // PERF advance — "precompute next reel's UI state": if this reel's
        // formatted counts were already computed ahead of time (see
        // ReelUiStatePrecomputer, driven from ReelsFragment.onPageSelected),
        // use them directly instead of re-running formatCount() on the
        // frame the swipe completes on.
        com.callx.app.cache.ReelUiStateCache.State precomputed =
            com.callx.app.cache.ReelUiStateCache.get(reel.reelId);
        if (precomputed != null) {
            if (tvLikesCount != null)    tvLikesCount.setText(precomputed.likesText);
            if (tvCommentsCount != null) tvCommentsCount.setText(precomputed.commentsText);
            if (tvSharesCount != null)   tvSharesCount.setText(precomputed.sharesText);
            if (tvRepostCount != null)   tvRepostCount.setText(precomputed.repostText);
            return;
        }
        if (tvLikesCount != null)    tvLikesCount.setText(delegate.formatCount(reel.likesCount));
        if (tvCommentsCount != null) tvCommentsCount.setText(delegate.formatCount(reel.commentsCount));
        if (tvSharesCount != null)   tvSharesCount.setText(delegate.formatCount(reel.sharesCount));
        if (tvRepostCount != null)   tvRepostCount.setText(delegate.formatCount(reel.repostCount));
    }

    // ── Like ──────────────────────────────────────────────────────────────

    public void toggleLike() {
        String myUid = delegate.safeMyUid();
        ReelModel reel = delegate.getReel();
        if (myUid == null || reel == null || reel.reelId == null) return;

        DatabaseReference likeRef  = FirebaseUtils.getReelLikesRef(reel.reelId).child(myUid);
        DatabaseReference countRef = FirebaseUtils.getReelsRef().child(reel.reelId).child("likesCount");
        DatabaseReference likedByUserRef = FirebaseUtils.getReelLikedByUserRef(myUid).child(reel.reelId);

        if (isLiked) {
            isLiked = false;
            if (btnLike != null) btnLike.setImageResource(R.drawable.ic_heart);
            likeRef.removeValue();
            likedByUserRef.removeValue();
            countRef.runTransaction(new Transaction.Handler() {
                @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Integer c = d.getValue(Integer.class);
                    d.setValue(c != null && c > 0 ? c - 1 : 0);
                    return Transaction.success(d);
                }
                @Override public void onComplete(@Nullable DatabaseError e, boolean b, @Nullable DataSnapshot s) {}
            });
        } else {
            isLiked = true;
            if (btnLike != null) btnLike.setImageResource(R.drawable.ic_heart_filled);
            // FIX (Instagram-level liker row): value is now a timestamp, not a
            // bare `true`. reelLikes/{reelId}/{uid} doubles as an ordering key —
            // fetchLikerAvatars() runs orderByValue().limitToLast(3) on this same
            // node to get the *most recent* likers without downloading the whole
            // likes list. isLiked/likeListener checks below only ever used
            // s.exists(), so this is a safe value-type change.
            likeRef.setValue(System.currentTimeMillis());
            likedByUserRef.setValue(System.currentTimeMillis());
            countRef.runTransaction(new Transaction.Handler() {
                @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Integer c = d.getValue(Integer.class);
                    d.setValue(c != null ? c + 1 : 1);
                    return Transaction.success(d);
                }
                @Override public void onComplete(@Nullable DatabaseError e, boolean b, @Nullable DataSnapshot s) {}
            });

            if (reel.uid != null && !reel.uid.equals(myUid)) {
                String myName = FirebaseUtils.getCurrentName();
                long nowMs = System.currentTimeMillis();
                com.callx.app.utils.PushNotify.notifyReelLike(
                    reel.uid, myUid, myName,
                    reel.reelId, reel.thumbUrl != null ? reel.thumbUrl : "");
                FirebaseDatabase.getInstance()
                    .getReference("reels/users").child(myUid)
                    .get().addOnSuccessListener(reelSnap -> {
                        String rThumb  = reelSnap.child("thumbUrl").getValue(String.class);
                        String rPhoto  = reelSnap.child("photoUrl").getValue(String.class);
                        String myThumb = (rThumb != null && !rThumb.isEmpty()) ? rThumb : rPhoto;
                        Map<String, Object> inApp = new java.util.HashMap<>();
                        inApp.put("type",        "like");
                        inApp.put("senderUid",   myUid);
                        inApp.put("senderName",  myName);
                        inApp.put("senderPhoto", myThumb != null ? myThumb : "");
                        inApp.put("reel_id",     reel.reelId);
                        inApp.put("message",     myName + " liked your reel");
                        inApp.put("timestamp",   nowMs);
                        inApp.put("read",        false);
                        FirebaseUtils.db().getReference("reel_notifications")
                            .child(reel.uid).push().setValue(inApp);
                    });
            }
        }
    }

    // ── Like animation ────────────────────────────────────────────────────

    public void showLikeAnimation() {
        if (ivLikeAnim == null || !delegate.isAdded()) return;
        ivLikeAnim.setVisibility(View.VISIBLE);
        ivLikeAnim.setAlpha(1f);
        ivLikeAnim.setScaleX(0.3f);
        ivLikeAnim.setScaleY(0.3f);

        AnimatorSet set = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(ivLikeAnim, "scaleX", 0.3f, 1.2f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(ivLikeAnim, "scaleY", 0.3f, 1.2f, 1.0f);
        ObjectAnimator alpha  = ObjectAnimator.ofFloat(ivLikeAnim, "alpha",  1f, 1f, 0f);
        alpha.setStartDelay(400);
        set.playTogether(scaleX, scaleY, alpha);
        set.setDuration(600);
        set.start();
    }

    // ── Save ──────────────────────────────────────────────────────────────

    public void toggleSave() {
        String myUid = delegate.safeMyUid();
        ReelModel reel = delegate.getReel();
        if (myUid == null || reel == null || reel.reelId == null) return;

        DatabaseReference saveRef = FirebaseUtils.getReelSavesRef(myUid).child(reel.reelId);
        if (isSaved) {
            isSaved = false;
            if (btnSave != null) btnSave.setImageResource(R.drawable.ic_bookmark);
            saveRef.removeValue();
        } else {
            isSaved = true;
            if (btnSave != null) btnSave.setImageResource(R.drawable.ic_bookmark_filled);
            saveRef.setValue(true);
        }
    }

    // ── Follow ────────────────────────────────────────────────────────────

    public void toggleFollow() {
        String myUid = delegate.safeMyUid();
        ReelModel reel = delegate.getReel();
        if (myUid == null || reel == null || reel.uid == null) return;
        if (myUid.equals(reel.uid)) return;

        DatabaseReference followRef = FirebaseUtils.getReelFollowsRef(myUid).child(reel.uid);
        if (isFollowing) {
            isFollowing = false;
            delegate.updateFollowUI(false);
            followRef.removeValue();
        } else {
            isFollowing = true;
            delegate.updateFollowUI(true);
            followRef.setValue(true);
        }
    }

    public void updateFollowUI(boolean following) {
        if (!delegate.isAdded()) return;
        // tv_collab_follow_btn mirrors tv_follow_btn's state — it's the same
        // logical button, just relocated onto the merged collab row for
        // collab posts (only one of the two is visible at a time).
        if (following) {
            if (tvFollowBtn != null) { tvFollowBtn.setText("Following"); tvFollowBtn.setAlpha(0.6f); }
            if (tvCollabFollowBtn != null) { tvCollabFollowBtn.setText("Following"); tvCollabFollowBtn.setAlpha(0.6f); }
            if (btnFollowOverlay != null) btnFollowOverlay.setVisibility(View.GONE);
        } else {
            if (tvFollowBtn != null) { tvFollowBtn.setText("Follow"); tvFollowBtn.setAlpha(1f); }
            if (tvCollabFollowBtn != null) { tvCollabFollowBtn.setText("Follow"); tvCollabFollowBtn.setAlpha(1f); }
            if (btnFollowOverlay != null) btnFollowOverlay.setVisibility(View.VISIBLE);
        }
    }

    // ── Repost ────────────────────────────────────────────────────────────

    public void toggleRepost() {
        String myUid = delegate.safeMyUid();
        ReelModel reel = delegate.getReel();
        if (myUid == null || reel == null || reel.reelId == null) return;

        long now = System.currentTimeMillis();
        if (now - lastRepostActionMs < REPOST_RATE_LIMIT_MS) return;
        lastRepostActionMs = now;

        if (!reel.allowReposts && !isReposted) {
            Toast.makeText(delegate.requireContext(),
                "This creator has disabled reposts", Toast.LENGTH_SHORT).show();
            return;
        }

        DatabaseReference repostRef = FirebaseUtils.db()
            .getReference("reelReposts").child(reel.reelId).child(myUid);

        if (isReposted) {
            isReposted = false;
            if (btnRepost != null) btnRepost.setColorFilter(null);
            reel.repostCount = Math.max(0, reel.repostCount - 1);
            if (tvRepostCount != null) tvRepostCount.setText(delegate.formatCount(reel.repostCount));
            repostRef.removeValue();
            FirebaseUtils.db().getReference("userReposts").child(myUid).child(reel.reelId).removeValue();
            FirebaseUtils.db().getReference("reels").child(reel.reelId).child("repostCount")
                .runTransaction(new Transaction.Handler() {
                    @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                        Integer c = d.getValue(Integer.class);
                        d.setValue(c != null && c > 0 ? c - 1 : 0);
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(@Nullable DatabaseError e, boolean b, @Nullable DataSnapshot s) {}
                });
        } else {
            isReposted = true;
            if (btnRepost != null) btnRepost.setColorFilter(
                android.graphics.Color.parseColor("#4CAF50"),
                android.graphics.PorterDuff.Mode.SRC_IN);
            reel.repostCount++;
            if (tvRepostCount != null) tvRepostCount.setText(delegate.formatCount(reel.repostCount));
            long repostTs = System.currentTimeMillis();
            repostRef.setValue(repostTs);
            FirebaseUtils.db().getReference("userReposts").child(myUid).child(reel.reelId).setValue(repostTs);
            FirebaseUtils.db().getReference("reels").child(reel.reelId).child("repostCount")
                .runTransaction(new Transaction.Handler() {
                    @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                        Integer c = d.getValue(Integer.class);
                        d.setValue(c != null ? c + 1 : 1);
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(@Nullable DatabaseError e, boolean b, @Nullable DataSnapshot s) {}
                });
            ReelRepostWorker.enqueue(
                delegate.requireContext(), reel.reelId, myUid,
                FirebaseUtils.getCurrentName(), reel.uid, reel.ownerName, reel.thumbUrl);
            Toast.makeText(delegate.requireContext(), "Reposted!", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Emoji reactions ───────────────────────────────────────────────────

    public void toggleReactionPanel() {
        if (layoutReactions == null) return;
        reactionsVisible = !reactionsVisible;
        layoutReactions.setVisibility(reactionsVisible ? View.VISIBLE : View.GONE);
        if (reactionsVisible) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::hideReactions, 4000);
        }
    }

    public void hideReactions() {
        reactionsVisible = false;
        if (layoutReactions != null) layoutReactions.setVisibility(View.GONE);
    }

    public void sendReaction(String emoji) {
        String myUid = delegate.safeMyUid();
        ReelModel reel = delegate.getReel();
        if (myUid == null || reel == null || reel.reelId == null) return;
        FirebaseUtils.getReelReactionsRef(reel.reelId).child(myUid).setValue(emoji);
        if (delegate.isAdded() && delegate.getContext() != null)
            Toast.makeText(delegate.requireContext(), emoji, Toast.LENGTH_SHORT).show();

        // BUG FIX: this used to only write the reelReactions/{reelId}/{uid}
        // node (for the live counts strip + burst replay) and never sent
        // anything to chat at all — so the reel owner never saw "you
        // reacted 😍 to their reel" show up as a message, unlike status
        // reactions (see StatusViewerActivity#sendReactionToChat). Mirrors
        // that same shape, using the "reel_"+reelId replyToId convention
        // ReelStickerReplyHelper#sendDm already uses for other reel DMs —
        // MessagePagingAdapter#isStoryReactionEmojiMessage recognizes both
        // prefixes, so this renders through the same big-badge bubble UI.
        if (!myUid.equals(reel.uid)) sendReactionToChat(myUid, reel, emoji);
    }

    private void sendReactionToChat(String myUid, ReelModel reel, String emoji) {
        String ownerUid = reel.uid;
        if (ownerUid == null || ownerUid.isEmpty()) return;
        String chatId = FirebaseUtils.getChatId(myUid, ownerUid);
        DatabaseReference msgRef = FirebaseUtils.getMessagesRef(chatId).push();
        String msgId = msgRef.getKey();
        if (msgId == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("id",                msgId);
        data.put("senderId",          myUid);
        data.put("text",              emoji);
        data.put("type",              "text");
        data.put("timestamp",         com.google.firebase.database.ServerValue.TIMESTAMP);
        data.put("seen",              false);
        data.put("replyToType",       "reel");
        data.put("replyToText",       "\uD83C\uDFA5 Reel"); // 🎥
        data.put("replyToSenderName", reel.ownerName != null ? reel.ownerName : "");
        data.put("replyToId",         "reel_" + reel.reelId);
        String thumb = reel.effectiveThumbUrl();
        if (thumb != null && !thumb.isEmpty()) data.put("replyToMediaUrl", thumb);
        msgRef.setValue(data);
    }

    // ── View count ────────────────────────────────────────────────────────

    public void recordView() {
        String myUid = delegate.safeMyUid();
        ReelModel reel = delegate.getReel();
        if (myUid == null || reel == null || reel.reelId == null) return;
        DatabaseReference viewRef = FirebaseUtils.getReelViewsRef(reel.reelId).child(myUid);
        viewRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                // BUG FIX: writeReelSeenToChat() used to live INSIDE the
                // "if (s.exists()) return;" branch below — so it only ever
                // fired on the viewer's very first-ever view of this reelId.
                // reelViews/{reelId}/{myUid} is a permanent marker (never
                // cleared), so every subsequent rewatch — recordView() fires
                // again each time the reel becomes visible, see
                // ReelPlayerFragment.applyVisibleState — hit s.exists()==true
                // and returned before ever reaching ReelSeenTracker. Net
                // effect: the "🎬 Watched your reel" chat bubble could only
                // ever fire once per viewer per reel, ever — and not at all
                // if that one-time view happened before the contacts/chat
                // relationship existed yet (ReelSeenTracker's own gate would
                // silently skip it, burning the only chance for good).
                // ReelSeenTracker already owns a 1-hour-per-reel dedup
                // (reelSeenDedup/{viewerUid}/{reelId}) specifically so
                // rewatches CAN re-bubble after that window — that logic was
                // simply unreachable. Fix: call it unconditionally on every
                // recordView(), outside the exists() gate, and let
                // ReelSeenTracker's own dedup decide if this rewatch bubbles.
                com.callx.app.utils.ReelSeenTracker.writeReelSeenToChat(
                    reel.uid, reel.reelId,
                    reel.thumbUrl != null ? reel.thumbUrl : reel.thumbnailUrl);

                if (s.exists()) return; // viewsCount / watch-history already recorded once — skip below
                viewRef.setValue(true);
                FirebaseUtils.getReelsRef().child(reel.reelId).child("viewsCount")
                    .runTransaction(new Transaction.Handler() {
                        @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                            Integer c = d.getValue(Integer.class);
                            d.setValue(c != null ? c + 1 : 1);
                            return Transaction.success(d);
                        }
                        @Override public void onComplete(@Nullable DatabaseError e, boolean b, @Nullable DataSnapshot sn) {}
                    });
                FirebaseUtils.getReelWatchHistoryRef(myUid).child(reel.reelId)
                    .setValue(System.currentTimeMillis());
                FirebaseUtils.getReelWatchProgressRef(myUid).child(reel.reelId).setValue(0);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ── Mark notifications read ───────────────────────────────────────────

    public void markReelNotificationsRead() {
        String myUid = delegate.safeMyUid();
        ReelModel reel = delegate.getReel();
        if (myUid == null || reel == null || reel.reelId == null) return;
        final String targetReelId = reel.reelId;
        FirebaseUtils.db().getReference("reel_notifications")
            .child(myUid).orderByChild("reel_id").equalTo(targetReelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot n : snap.getChildren()) {
                        Boolean read = n.child("read").getValue(Boolean.class);
                        if (read == null || !read) n.getRef().child("read").setValue(true);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Firebase real-time listeners ──────────────────────────────────────

    public void startFirebaseListeners() {
        // FIX: Double-start guard — prevent duplicate Firebase connections
        // if startFirebaseListeners is called twice without removeFirebaseListeners.
        if (listenersActive) return;
        listenersActive = true;

        loadLiveReactionCounts();
        String myUid = delegate.safeMyUid();
        ReelModel reel = delegate.getReel();
        if (myUid == null || reel == null || reel.reelId == null) return;

        likeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                isLiked = s.exists();
                if (btnLike != null) btnLike.setImageResource(
                    isLiked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getReelLikesRef(reel.reelId).child(myUid).addValueEventListener(likeListener);

        saveListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                isSaved = s.exists();
                if (btnSave != null) btnSave.setImageResource(
                    isSaved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getReelSavesRef(myUid).child(reel.reelId).addValueEventListener(saveListener);

        followListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                isFollowing       = s.exists();
                followCheckLoaded = true;
                delegate.updateFollowUI(isFollowing);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                followCheckLoaded = true;
            }
        };
        if (reel.uid != null && !reel.uid.equals(myUid)) {
            FirebaseUtils.getReelFollowsRef(myUid).child(reel.uid).addValueEventListener(followListener);
        }

        repostListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                isReposted = s.exists();
                if (btnRepost != null) {
                    if (isReposted) {
                        btnRepost.setColorFilter(
                            android.graphics.Color.parseColor("#4CAF50"),
                            android.graphics.PorterDuff.Mode.SRC_IN);
                    } else {
                        btnRepost.setColorFilter(null);
                    }
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("reelReposts")
            .child(reel.reelId).child(myUid)
            .addValueEventListener(repostListener);

        countListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                Long likes    = s.child("likesCount").getValue(Long.class);
                Long comments = s.child("commentsCount").getValue(Long.class);
                Long shares   = s.child("sharesCount").getValue(Long.class);
                Long reposts  = s.child("repostCount").getValue(Long.class);
                if (likes    != null && tvLikesCount    != null) tvLikesCount.setText(delegate.formatCount(likes.intValue()));
                if (comments != null && tvCommentsCount != null) tvCommentsCount.setText(delegate.formatCount(comments.intValue()));
                if (shares   != null && tvSharesCount   != null) tvSharesCount.setText(delegate.formatCount(shares.intValue()));
                if (reposts  != null && tvRepostCount   != null) tvRepostCount.setText(delegate.formatCount(reposts.intValue()));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getReelsRef().child(reel.reelId).addValueEventListener(countListener);

        fetchLikerAvatars();
    }

    public void removeFirebaseListeners() {
        // FIX: Guard — agar listeners already removed hain to dobara remove mat karo.
        // Ye important hai kyunki ab applyVisibleState(false) se bhi call hota hai
        // aur onDestroyView se bhi — dono baar safe hona chahiye.
        if (!listenersActive) return;
        listenersActive = false;

        removeLiveReactionsListener();
        String myUid = delegate.safeMyUid();
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;
        if (likeListener   != null && myUid != null) FirebaseUtils.getReelLikesRef(reel.reelId).child(myUid).removeEventListener(likeListener);
        if (saveListener   != null && myUid != null) FirebaseUtils.getReelSavesRef(myUid).child(reel.reelId).removeEventListener(saveListener);
        if (followListener != null && myUid != null && reel.uid != null) FirebaseUtils.getReelFollowsRef(myUid).child(reel.uid).removeEventListener(followListener);
        if (countListener  != null) FirebaseUtils.getReelsRef().child(reel.reelId).removeEventListener(countListener);
        if (repostListener != null && myUid != null)
            FirebaseUtils.db().getReference("reelReposts").child(reel.reelId).child(myUid).removeEventListener(repostListener);
        if (likersListener != null && likersQuery != null) likersQuery.removeEventListener(likersListener);

        // FIX: References null karo taaki next startFirebaseListeners() fresh banaye.
        likeListener    = null;
        saveListener    = null;
        followListener  = null;
        countListener   = null;
        repostListener  = null;
        reactionsListener = null;
        likersListener  = null;
        likersQuery     = null;
        fetchGeneration++; // invalidate any in-flight per-user avatar fetches from this session
    }

    // ── Liker avatar row ──────────────────────────────────────────────────

    public void fetchLikerAvatars() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;

        if (likersListener != null && likersQuery != null) {
            likersQuery.removeEventListener(likersListener);
            likersListener = null;
        }

        // PERF FIX (Instagram-level): windowed query instead of the whole
        // reelLikes/{reelId} node. orderByValue().limitToLast(3) only ever
        // transfers the 3 highest-value (= most recent, since like values
        // are now write-time timestamps — see toggleLike()) entries, no
        // matter how many total likes the reel has. Firebase keeps this
        // live-synced incrementally, so a like/unlike elsewhere in the list
        // that doesn't touch the top-3 window doesn't even trigger a callback.
        likersQuery = FirebaseUtils.getReelLikesRef(reel.reelId).orderByValue().limitToLast(3);

        likersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                if (snapshot.getChildrenCount() == 0) {
                    if (llLikersAvatarRow != null) llLikersAvatarRow.setVisibility(View.GONE);
                    likerUidCache = new String[3];
                    return;
                }

                // orderByValue() ascending order → most recent liker is LAST
                // in iteration. Reverse so slot 1 (ivLiker1) is always the
                // most recent liker, matching Instagram's convention.
                List<String> likerUids = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) likerUids.add(child.getKey());
                java.util.Collections.reverse(likerUids);

                // DIFF CHECK: skip re-fetching/re-animating if the top-3 set
                // (and order) is unchanged from last time — e.g. a like/unlike
                // elsewhere caused likesCount to change without this window
                // actually reordering, or this listener re-attached on resume.
                boolean sameAsBefore = likerUids.size() == 3
                    && likerUids.get(0).equals(likerUidCache[0])
                    && likerUids.get(1).equals(likerUidCache[1])
                    && likerUids.get(2).equals(likerUidCache[2]);
                if (sameAsBefore) {
                    if (llLikersAvatarRow != null) llLikersAvatarRow.setVisibility(View.VISIBLE);
                    return;
                }

                if (llLikersAvatarRow != null) llLikersAvatarRow.setVisibility(View.VISIBLE);

                CircleImageView[] avatarViews = {ivLiker1, ivLiker2, ivLiker3};
                for (CircleImageView av : avatarViews) if (av != null) av.setVisibility(View.GONE);

                likerUidCache = new String[3];
                for (int i = 0; i < likerUids.size(); i++) likerUidCache[i] = likerUids.get(i);

                // RACE FIX: bump generation for this fresh batch. Each async
                // per-user fetch below captures `myGeneration` and re-checks
                // it before touching a view or counting toward completion —
                // a slow/late response from a superseded batch (rapid
                // like/unlike, or fragment rebind) is dropped instead of
                // clobbering a newer avatar.
                final int myGeneration = ++fetchGeneration;
                loadedCount.set(0);
                final int expectedCount = likerUids.size();

                for (int i = 0; i < likerUids.size(); i++) {
                    final CircleImageView targetView = avatarViews[i];
                    if (targetView == null) continue;
                    FirebaseUtils.getUserRef(likerUids.get(i)).child("thumbUrl")
                        .get().addOnSuccessListener(ds -> {
                            if (myGeneration != fetchGeneration) return; // superseded — drop
                            if (!delegate.isAdded() || delegate.getContext() == null) return;
                            targetView.setVisibility(View.VISIBLE);
                            String url = ds.getValue(String.class);
                            if (url != null && !url.isEmpty()) {
                                Glide.with(delegate.requireContext())
                                    .load(url)
                                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                                    .placeholder(R.drawable.ic_person).error(R.drawable.ic_person)
                                    .override(96, 96)
                                    .into(targetView);
                            } else {
                                targetView.setImageResource(R.drawable.ic_person);
                            }
                            // AtomicInteger completion tally (not index-based
                            // "isLast") — fires once all of THIS batch's
                            // avatars are actually done, whichever order the
                            // network calls land in. A batch superseded
                            // mid-flight never reaches expectedCount, so it
                            // can never fire the animation late.
                            if (loadedCount.incrementAndGet() >= expectedCount) {
                                startLikerFloatAnimations();
                            }
                        })
                        .addOnFailureListener(e -> {
                            if (myGeneration != fetchGeneration) return;
                            if (!delegate.isAdded() || delegate.getContext() == null) return;
                            targetView.setVisibility(View.VISIBLE);
                            targetView.setImageResource(R.drawable.ic_person);
                            if (loadedCount.incrementAndGet() >= expectedCount) {
                                startLikerFloatAnimations();
                            }
                        });
                }
                if (tvHeart1 != null) tvHeart1.setVisibility(likerUids.size() >= 1 ? View.VISIBLE : View.GONE);
                if (tvHeart2 != null) tvHeart2.setVisibility(likerUids.size() >= 2 ? View.VISIBLE : View.GONE);
                if (tvHeart3 != null) tvHeart3.setVisibility(likerUids.size() >= 3 ? View.VISIBLE : View.GONE);
                View[] flViews = {flLiker1, flLiker2, flLiker3};
                for (int i = 0; i < flViews.length; i++) {
                    final int idx = i;
                    if (flViews[i] != null) flViews[i].setOnClickListener(v -> openLikerProfile(idx));
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        likersQuery.addValueEventListener(likersListener);
    }

    private void startLikerFloatAnimations() {
        // UI FIX: cancel any already-running float animators before starting
        // new ones. Without this, a fresh likers batch (top-3 window
        // changed) would stack a second ObjectAnimator on the same view on
        // top of one still running from the previous batch — two animators
        // fighting over the same translationY property makes the avatar
        // bounce faster/jittery instead of a clean single float. Previously
        // these were only ever cancelled in release() (fragment teardown).
        if (floatAnim1 != null) { floatAnim1.cancel(); floatAnim1 = null; }
        if (floatAnim2 != null) { floatAnim2.cancel(); floatAnim2 = null; }
        if (floatAnim3 != null) { floatAnim3.cancel(); floatAnim3 = null; }
        // Reset translation too — a cancelled animator can leave the view
        // mid-float (not at translationY=0), so the next animator would
        // otherwise start from that offset instead of a clean baseline.
        if (ivLiker1 != null) ivLiker1.setTranslationY(0f);
        if (ivLiker2 != null) ivLiker2.setTranslationY(0f);
        if (ivLiker3 != null) ivLiker3.setTranslationY(0f);

        if (ivLiker1 != null && ivLiker1.getVisibility() == View.VISIBLE) {
            floatAnim1 = ObjectAnimator.ofFloat(ivLiker1, "translationY", 0f, -18f, 0f);
            floatAnim1.setDuration(2200); floatAnim1.setRepeatCount(ObjectAnimator.INFINITE);
            floatAnim1.setRepeatMode(ObjectAnimator.REVERSE);
            floatAnim1.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            floatAnim1.start();
        }
        if (ivLiker2 != null && ivLiker2.getVisibility() == View.VISIBLE) {
            floatAnim2 = ObjectAnimator.ofFloat(ivLiker2, "translationY", 0f, -14f, 0f);
            floatAnim2.setDuration(2600); floatAnim2.setRepeatCount(ObjectAnimator.INFINITE);
            floatAnim2.setRepeatMode(ObjectAnimator.REVERSE);
            floatAnim2.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            floatAnim2.setStartDelay(300); floatAnim2.start();
        }
        if (ivLiker3 != null && ivLiker3.getVisibility() == View.VISIBLE) {
            floatAnim3 = ObjectAnimator.ofFloat(ivLiker3, "translationY", 0f, -20f, 0f);
            floatAnim3.setDuration(2400); floatAnim3.setRepeatCount(ObjectAnimator.INFINITE);
            floatAnim3.setRepeatMode(ObjectAnimator.REVERSE);
            floatAnim3.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            floatAnim3.setStartDelay(600); floatAnim3.start();
        }
    }

    private void openLikerProfile(int idx) {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        if (likerUidCache == null || idx >= likerUidCache.length || likerUidCache[idx] == null) return;
        String uid = likerUidCache[idx];
        FirebaseDatabase.getInstance().getReference("reels/users").child(uid)
            .get().addOnSuccessListener(ds -> {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                String name  = ds.child("displayName").getValue(String.class);
                String thumb = ds.child("thumbUrl").getValue(String.class);
                String photo = ds.child("photoUrl").getValue(String.class);
                String resolvedPhoto = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                if (name == null) name = "";
                if (resolvedPhoto == null) resolvedPhoto = "";
                Intent intent = new Intent(delegate.getActivity(), UserReelsActivity.class);
                intent.putExtra(UserReelsActivity.EXTRA_UID,   uid);
                intent.putExtra(UserReelsActivity.EXTRA_NAME,  name);
                intent.putExtra(UserReelsActivity.EXTRA_PHOTO, resolvedPhoto);
                delegate.getFragment().startActivity(intent);
            });
    }

    // ── Live reaction counts ──────────────────────────────────────────────

    private void loadLiveReactionCounts() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null || layoutLiveReactions == null) return;
        DatabaseReference ref = FirebaseDatabase.getInstance()
            .getReference("reelReactions").child(reel.reelId);
        reactionsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                Map<String, Integer> counts = new LinkedHashMap<>();
                List<String> allReactionEmojis = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) {
                    String emoji = s.getValue(String.class);
                    if (emoji != null) {
                        counts.merge(emoji, 1, Integer::sum);
                        allReactionEmojis.add(emoji); // one entry per reactor, not just per distinct emoji
                    }
                }
                List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
                list.sort((a, b) -> b.getValue().compareTo(a.getValue()));
                displayLiveReactions(list.subList(0, Math.min(3, list.size())));

                // Instagram-style one-shot floating replay: fires the first
                // time this reel's reactions load with at least one emoji
                // on it, per bind — not on every subsequent live update.
                // Pass one entry per reactor (allReactionEmojis) rather than
                // counts.keySet() (distinct emoji only) — the burst used to
                // look just as sparse for 1 reaction as for 30 of the same
                // emoji since only the distinct type was ever spawned.
                if (!reactionBurstPlayed && !allReactionEmojis.isEmpty() && reactionBurstOverlay != null) {
                    reactionBurstPlayed = true;
                    reactionBurstOverlay.playBurst(allReactionEmojis);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        ref.addValueEventListener(reactionsListener);
    }

    private void displayLiveReactions(List<Map.Entry<String, Integer>> top) {
        if (layoutLiveReactions == null || !delegate.isAdded() || delegate.getContext() == null) return;
        layoutLiveReactions.removeAllViews();
        if (top.isEmpty()) { layoutLiveReactions.setVisibility(View.GONE); return; }
        layoutLiveReactions.setVisibility(View.VISIBLE);
        int dp4 = delegate.dpToPx(4);
        int dp8 = delegate.dpToPx(8);
        for (Map.Entry<String, Integer> entry : top) {
            TextView chip = new TextView(delegate.requireContext());
            chip.setText(entry.getKey() + " " + delegate.formatCount(entry.getValue()));
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(12f);
            chip.setBackgroundResource(R.drawable.bg_music_ticker);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp8, 0);
            chip.setLayoutParams(lp);
            chip.setPadding(dp8, dp4, dp8, dp4);
            layoutLiveReactions.addView(chip);
        }
    }

    private void removeLiveReactionsListener() {
        ReelModel reel = delegate.getReel();
        if (reactionsListener != null && reel != null && reel.reelId != null)
            FirebaseDatabase.getInstance().getReference("reelReactions")
                .child(reel.reelId).removeEventListener(reactionsListener);
        reactionsListener = null;
        reactionBurstPlayed = false;
        if (reactionBurstOverlay != null) reactionBurstOverlay.stop();
    }

    // ── Release ───────────────────────────────────────────────────────────

    public void release() {
        if (floatAnim1 != null) { floatAnim1.cancel(); floatAnim1 = null; }
        if (floatAnim2 != null) { floatAnim2.cancel(); floatAnim2 = null; }
        if (floatAnim3 != null) { floatAnim3.cancel(); floatAnim3 = null; }
        // Detach the global layout listener — leaving it attached would leak
        // this controller (and its view refs) via bottomInfoColumn's
        // ViewTreeObserver for as long as that view tree is alive.
        if (bottomInfoColumn != null && bottomInfoLayoutListener != null) {
            bottomInfoColumn.getViewTreeObserver().removeOnGlobalLayoutListener(bottomInfoLayoutListener);
        }
        bottomInfoLayoutListener = null;
    }
}
