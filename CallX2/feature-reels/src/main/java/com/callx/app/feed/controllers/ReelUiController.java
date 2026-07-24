package com.callx.app.feed.controllers;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.HorizontalScrollView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.explore.HashtagReelsActivity;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.AvatarUrlBuilder;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Manages static UI population (owner info, captions, duet series chip, music ticker,
 * hashtag chips, duet/stitch chips, follow button, liker avatar row UI),
 * click listener wiring for root + all buttons, cinema mode, and disc animation.
 */
public class ReelUiController {

    private final ReelPlayerDelegate delegate;

    // ── Cinema mode ───────────────────────────────────────────────────────
    // Static so it survives ViewPager2 recycling
    private static final Set<String> cinemaHiddenReels = new HashSet<>();
    private boolean isUiHidden = false;

    // ── Owned views ───────────────────────────────────────────────────────
    private CircleImageView ivOwnerAvatar;
    private ImageView       ivOwnerStoryRing;
    private TextView        tvOwnerName;
    private TextView        tvCaption;
    private TextView        tvMusicName;
    private ImageView       ivMusicDisc;
    private android.widget.ImageButton btnCreateAudio;
    private LinearLayout    layoutMusicTicker;
    private LinearLayout    containerHashtags;
    private HorizontalScrollView scrollHashtags;
    private LinearLayout    llSeriesChip;
    private TextView        tvSeriesChipLabel;
    private TextView        tvRepostAttribution;
    private android.widget.ImageButton btnMore;
    private android.widget.ImageButton btnComment;
    private android.widget.ImageButton btnShare;
    private android.widget.ImageButton btnDownload;
    private View reelPinnedCommentContainer;
    private TextView tvPinnedAuthor, tvPinnedText, tvPinnedLikes;
    private CircleImageView ivPinnedAvatar;

    // ── Fragment root view ─────────────────────────────────────────────────
    private View fragmentView;

    // ── Disc animation ────────────────────────────────────────────────────
    private ObjectAnimator discAnimator;

    // ── uiHandler for reactions auto-hide ────────────────────────────────
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public ReelUiController(ReelPlayerDelegate delegate) {
        this.delegate = delegate;
    }

    // ── View binding ──────────────────────────────────────────────────────

    public void bindViews(View root) {
        this.fragmentView = root;
        ivOwnerAvatar      = root.findViewById(R.id.iv_owner_avatar);
        ivOwnerStoryRing   = root.findViewById(R.id.iv_owner_story_ring);
        tvOwnerName        = root.findViewById(R.id.tv_owner_name);
        tvCaption          = root.findViewById(R.id.tv_caption);
        tvMusicName        = root.findViewById(R.id.tv_music_name);
        ivMusicDisc        = root.findViewById(R.id.iv_music_disc);
        btnCreateAudio     = root.findViewById(R.id.btn_create_audio);
        layoutMusicTicker  = root.findViewById(R.id.layout_music_ticker);
        containerHashtags  = root.findViewById(R.id.container_hashtags);
        scrollHashtags     = root.findViewById(R.id.scroll_hashtags);
        llSeriesChip       = root.findViewById(R.id.ll_series_chip);
        tvSeriesChipLabel  = root.findViewById(R.id.tv_series_chip_label);
        tvRepostAttribution = root.findViewWithTag("tv_repost_attribution");
        btnMore            = root.findViewById(R.id.btn_more);
        btnComment         = root.findViewById(R.id.btn_comment);
        btnShare           = root.findViewById(R.id.btn_share);
        btnDownload        = root.findViewById(R.id.btn_download);
        reelPinnedCommentContainer = root.findViewById(R.id.reel_pinned_comment_container);
        tvPinnedAuthor = root.findViewById(R.id.reel_pinned_comment_author);
        tvPinnedText   = root.findViewById(R.id.reel_pinned_comment_text);
        tvPinnedLikes  = root.findViewById(R.id.reel_pinned_comment_likes);
        ivPinnedAvatar = root.findViewById(R.id.reel_pinned_comment_avatar);

        // Edge-to-edge insets
        View rightActions = root.findViewById(R.id.right_actions);
        if (rightActions != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rightActions, (view, insets) -> {
                int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                int basePx = (int)(8 * view.getResources().getDisplayMetrics().density);
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), basePx + navBarHeight);
                return insets;
            });
        }
        View bottomInfo = root.findViewById(R.id.bottom_info);
        if (bottomInfo != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomInfo, (view, insets) -> {
                int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), navBarHeight);
                return insets;
            });
        }
    }

    // ── Static data population ────────────────────────────────────────────

    public void populateStaticData() {
        if ("close_friends".equals(delegate.getReel().audienceType)) {
            View avatarContainer = fragmentView.findViewById(R.id.avatar_container);
            if (avatarContainer != null) avatarContainer.setBackgroundResource(R.drawable.bg_close_friends_ring);
            TextView label = fragmentView.findViewById(R.id.tv_close_friends_label);
            if (label != null) label.setVisibility(View.VISIBLE);
        }
        ReelModel reel = delegate.getReel();
        if (reel == null) return;

        // Owner name + caption
        if (tvOwnerName != null) tvOwnerName.setText(reel.ownerName != null ? "@" + reel.ownerName : "@user");

        // ── Collab Joint-Author display ──────────────────────────────────────
        View llCollabAuthors = fragmentView.findViewById(R.id.ll_collab_second_author);
        if (llCollabAuthors != null) {
            if (reel.isCollabPost && reel.collabUid != null && !reel.collabUid.isEmpty()) {
                llCollabAuthors.setVisibility(View.VISIBLE);
                TextView tvCollabName = fragmentView.findViewById(R.id.tv_collab_author_name);
                if (tvCollabName != null) tvCollabName.setText("@" + (!reel.collabDisplayName.isEmpty() ? reel.collabDisplayName : reel.collabUid));
                de.hdodenhof.circleimageview.CircleImageView ivCollabAvatar = fragmentView.findViewById(R.id.iv_collab_author_avatar);
                if (ivCollabAvatar != null && reel.collabAvatarUrl != null && !reel.collabAvatarUrl.isEmpty() && delegate.isAdded()) {
                    Glide.with(delegate.requireContext()).load(reel.collabAvatarUrl).circleCrop().placeholder(R.drawable.ic_person).into(ivCollabAvatar);
                }
            } else {
                llCollabAuthors.setVisibility(View.GONE);
            }
        }
        String captionText = reel.caption != null ? reel.caption : "";
        if (reel.duetOf != null && !reel.duetOf.isEmpty()) captionText = "🔀 Duet · " + captionText;
        if (tvCaption != null) tvCaption.setText(captionText);

        // Duet Series chip
        if (llSeriesChip != null) {
            if (reel.seriesId != null && !reel.seriesId.isEmpty()) {
                String label = "Part " + reel.seriesEpisodeNumber + " of " +
                    (reel.seriesTitle != null && !reel.seriesTitle.isEmpty() ? reel.seriesTitle : "Series");
                if (tvSeriesChipLabel != null) tvSeriesChipLabel.setText(label);
                llSeriesChip.setVisibility(View.VISIBLE);
                llSeriesChip.setOnClickListener(v -> delegate.openDuetSeries());

                final String finalSeriesId    = reel.seriesId;
                final String finalSeriesTitle = reel.seriesTitle != null ? reel.seriesTitle : "Series";
                llSeriesChip.setOnLongClickListener(v -> {
                    String myUid = FirebaseUtils.getCurrentUid();
                    if (myUid == null || myUid.isEmpty()) {
                        android.widget.Toast.makeText(delegate.requireContext(),
                            "Login required to subscribe", android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    com.google.firebase.database.DatabaseReference subRef =
                        FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                            .getReference("duetSeriesSubscriptions").child(finalSeriesId).child(myUid);
                    com.google.firebase.database.DatabaseReference userRef =
                        FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                            .getReference("userSubscribedSeries").child(myUid).child(finalSeriesId);
                    subRef.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override
                        public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                            if (!delegate.isAdded()) return;
                            if (snap.exists()) {
                                subRef.removeValue(); userRef.removeValue();
                                FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                                    .getReference("duetSeries").child(finalSeriesId).child("subscriberCount")
                                    .setValue(ServerValue.increment(-1));
                                android.widget.Toast.makeText(delegate.requireContext(),
                                    "Unsubscribed from " + finalSeriesTitle, android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                subRef.setValue(true); userRef.setValue(true);
                                FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                                    .getReference("duetSeries").child(finalSeriesId).child("subscriberCount")
                                    .setValue(ServerValue.increment(1));
                                android.widget.Toast.makeText(delegate.requireContext(),
                                    "Subscribed to " + finalSeriesTitle + "! 🎬", android.widget.Toast.LENGTH_SHORT).show();
                            }
                        }
                        @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                    });
                    return true;
                });
            } else {
                llSeriesChip.setVisibility(View.GONE);
            }
        }

        // Music ticker
        String musicDisplay = reel.musicName != null && !reel.musicName.isEmpty()
            ? reel.musicName : "Original Audio";
        if (reel.musicArtist != null && !reel.musicArtist.isEmpty()
                && !musicDisplay.contains(reel.musicArtist)) {
            musicDisplay = musicDisplay + " · " + reel.musicArtist;
        }
        if (tvMusicName != null) {
            tvMusicName.setText(musicDisplay);
            tvMusicName.setSingleLine(true);
            tvMusicName.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            tvMusicName.setMarqueeRepeatLimit(-1);
            tvMusicName.setSelected(true);
            tvMusicName.setHorizontallyScrolling(true);
        }

        // The right-rail audio tile replaces the old bottom-left audio ticker.
        // It always uses the existing sound-detail flow: for original audio,
        // ReelDuetController resolves the deterministic orig_{reelId} sound.
        String rawMusicName = reel.musicName == null ? "" : reel.musicName.trim();
        if (btnCreateAudio != null) {
            btnCreateAudio.setVisibility(View.VISIBLE);
            btnCreateAudio.setOnClickListener(v -> delegate.openSoundDetail());
            btnCreateAudio.setContentDescription(
                rawMusicName.isEmpty() ? "Original audio" : rawMusicName);

            // ✅ FIX: for OLD reels (posted before musicCoverUrl was saved on
            // reused-sound reels), fetch the cover from sounds/{musicId}
            // once at render time instead of silently showing no photo.
            if (TextUtils.isEmpty(reel.musicCoverUrl) && !TextUtils.isEmpty(reel.musicId)) {
                fetchAndBindMissingSoundCover(reel);
            }
            String audioImageUrl = !TextUtils.isEmpty(reel.musicCoverUrl)
                ? reel.musicCoverUrl
                : reel.ownerPhoto;
            if (delegate.isAdded() && delegate.getContext() != null
                    && !TextUtils.isEmpty(audioImageUrl)) {
                Glide.with(delegate.requireContext())
                    .load(audioImageUrl)
                    .apply(new RequestOptions().centerCrop()
                        .placeholder(R.drawable.ic_audio))
                    .into(btnCreateAudio);
            } else {
                btnCreateAudio.setImageResource(R.drawable.ic_audio);
            }
        }
        if (layoutMusicTicker != null) {
            layoutMusicTicker.setVisibility(View.GONE);
        }

        // Music disc cover art
        startDiscAnimation();
        if (ivMusicDisc != null && delegate.isAdded() && delegate.getContext() != null) {
            // ✅ FIX: same fallback as btnCreateAudio — own thumbnail for a
            // brand-new original, else generic icon while the DB fetch runs.
            String coverUrl = !TextUtils.isEmpty(reel.musicCoverUrl)
                ? reel.musicCoverUrl
                : reel.ownerPhoto;
            if (coverUrl != null && !coverUrl.isEmpty()) {
                Glide.with(delegate.requireContext())
                    .load(coverUrl)
                    .apply(new RequestOptions().circleCrop().placeholder(R.drawable.ic_music_note))
                    .into(ivMusicDisc);
            } else {
                ivMusicDisc.setImageResource(R.drawable.ic_music_note);
            }
        }

        // Owner avatar + story ring
        // PERF FIX: routed through the central AvatarUrlBuilder (exact
        // size, 2x retina, auto-format Cloudinary variant) instead of
        // loading the raw stored ownerPhoto URL, and .override() pins the
        // Glide decode size so recycling never decodes more than needed.
        if (ivOwnerAvatar != null && delegate.isAdded() && delegate.getContext() != null) {
            String photoUrl = reel.ownerPhoto;
            if (photoUrl != null && !photoUrl.isEmpty()) {
                android.content.Context avatarCtx = delegate.requireContext();
                int sizePx = AvatarUrlBuilder.dpToPx(avatarCtx, 34) * 2; // 34dp view, 2x retina
                String resizedUrl = AvatarUrlBuilder.build(avatarCtx, photoUrl, 34);
                Glide.with(avatarCtx)
                    .load(resizedUrl)
                    .apply(new RequestOptions()
                        .circleCrop()
                        .override(sizePx, sizePx)
                        .placeholder(R.drawable.ic_person))
                    .into(ivOwnerAvatar);
            } else {
                ivOwnerAvatar.setImageResource(R.drawable.ic_person);
            }
        }
        if (ivOwnerStoryRing != null) {
            // Show story ring if owner has an active status
            try {
                boolean hasStatus = com.callx.app.cache.StatusCacheManager
                        .getInstance(delegate.requireContext()).hasStatus(reel.uid);
                ivOwnerStoryRing.setVisibility(hasStatus ? View.VISIBLE : View.GONE);
            } catch (Exception ignored) {
                ivOwnerStoryRing.setVisibility(View.GONE);
            }
        }

        // Repost attribution
        if (tvRepostAttribution != null && reel.repostedFromName != null && !reel.repostedFromName.isEmpty()) {
            tvRepostAttribution.setVisibility(View.VISIBLE);
            tvRepostAttribution.setText("↻ Reposted by @" + reel.repostedFromName);
        }

        // Hashtags
        renderHashtags();
        addViewDuetButton();
        addViewStitchesButton();
    }

    // ── Follow UI (called by SocialController) ────────────────────────────

    public void updateFollowUI(boolean following) {
        // Delegated to SocialController — handled there directly.
        // This method exists for cases where UiController needs to update follow state.
    }

    // ── Hashtag chips ─────────────────────────────────────────────────────

    private void renderHashtags() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.caption == null || reel.caption.isEmpty()) return;
        List<String> tags = ReelModel.extractHashtags(reel.caption);
        if (tags.isEmpty() || containerHashtags == null) return;

        if (scrollHashtags != null) scrollHashtags.setVisibility(View.VISIBLE);
        containerHashtags.removeAllViews();
        int dp8 = delegate.dpToPx(8);
        int dp4 = delegate.dpToPx(4);

        for (String tag : tags) {
            TextView chip = new TextView(delegate.requireContext());
            chip.setText("#" + tag);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(12f);
            chip.setBackgroundResource(R.drawable.bg_speed_chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp8, 0);
            chip.setLayoutParams(lp);
            chip.setPadding(dp8, dp4, dp8, dp4);
            chip.setClickable(true);
            chip.setFocusable(true);
            final String finalTag = tag;
            chip.setOnClickListener(cv -> {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                Intent intent = new Intent(delegate.requireContext(), HashtagReelsActivity.class);
                intent.putExtra(HashtagReelsActivity.EXTRA_HASHTAG, finalTag);
                delegate.getFragment().startActivity(intent);
            });
            containerHashtags.addView(chip);
        }
    }

    private void addViewDuetButton() {
        if (!delegate.isAdded() || delegate.getContext() == null || containerHashtags == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        int count = reel.duetCount;
        if (count <= 0) return;

        TextView duetBtn = new TextView(delegate.requireContext());
        String label = "🔀 " + delegate.formatCount(count) + " Duet" + (count == 1 ? "" : "s") + "  ›";
        duetBtn.setText(label);
        duetBtn.setTextColor(android.graphics.Color.WHITE);
        duetBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        duetBtn.setAlpha(0.85f);
        duetBtn.setPadding(20, 8, 20, 8);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(40f);
        bg.setColor(0x33FFFFFF);
        bg.setStroke(1, 0x66FFFFFF);
        duetBtn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 16, 0);
        duetBtn.setLayoutParams(lp);

        duetBtn.setOnClickListener(v -> {
            if (!delegate.isAdded() || delegate.getActivity() == null) return;
            Intent i = new Intent(delegate.getActivity(), com.callx.app.social.DuetsByReelActivity.class);
            i.putExtra(com.callx.app.social.DuetsByReelActivity.EXTRA_REEL_ID,    reel.reelId);
            i.putExtra(com.callx.app.social.DuetsByReelActivity.EXTRA_OWNER_NAME, reel.ownerName);
            delegate.getFragment().startActivity(i);
        });

        containerHashtags.addView(duetBtn, 0);
        if (scrollHashtags != null) scrollHashtags.setVisibility(View.VISIBLE);
    }

    private void addViewStitchesButton() {
        if (!delegate.isAdded() || delegate.getContext() == null || containerHashtags == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        int count = reel.stitchCount;
        if (count <= 0) return;

        TextView stitchBtn = new TextView(delegate.requireContext());
        String label = "✂️ " + delegate.formatCount(count) + " Stitch" + (count == 1 ? "" : "es") + "  ›";
        stitchBtn.setText(label);
        stitchBtn.setTextColor(android.graphics.Color.WHITE);
        stitchBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        stitchBtn.setAlpha(0.85f);
        stitchBtn.setPadding(20, 8, 20, 8);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(40f);
        bg.setColor(0x2200CFFF);
        bg.setStroke(1, 0x6600CFFF);
        stitchBtn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 16, 0);
        stitchBtn.setLayoutParams(lp);

        stitchBtn.setOnClickListener(v -> {
            if (!delegate.isAdded() || delegate.getActivity() == null) return;
            Intent i = new Intent(delegate.getActivity(), com.callx.app.social.StitchesByReelActivity.class);
            i.putExtra(com.callx.app.social.StitchesByReelActivity.EXTRA_REEL_ID,    reel.reelId);
            i.putExtra(com.callx.app.social.StitchesByReelActivity.EXTRA_OWNER_NAME, reel.ownerName);
            delegate.getFragment().startActivity(i);
        });

        int insertAt = (containerHashtags.getChildCount() > 0) ? 1 : 0;
        containerHashtags.addView(stitchBtn, insertAt);
        if (scrollHashtags != null) scrollHashtags.setVisibility(View.VISIBLE);
    }

    // ── Click listener wiring ─────────────────────────────────────────────

    public void setupClickListeners(View root) {
        // Root: single tap → play/pause, long press → cinema, double-tap → like
        root.setOnClickListener(v -> {
            if (delegate.isAdded()) {
                // if reactions are visible, just hide them
                delegate.hideReactions();
                delegate.togglePlayPause();
            }
        });
        root.setOnLongClickListener(v -> {
            openCinemaSheet();
            return true;
        });
        root.setOnTouchListener(new DoubleTapListener(() -> {
            if (!delegate.isLiked()) delegate.toggleLike();
            delegate.showLikeAnimation();
        }));

        // Buttons
        if (btnComment  != null) btnComment.setOnClickListener(v -> delegate.openComments());
        if (btnShare    != null) btnShare.setOnClickListener(v -> delegate.shareReel());
        if (btnMore     != null) btnMore.setOnClickListener(v -> delegate.showMoreOptions());
        if (btnDownload != null) btnDownload.setOnClickListener(v -> delegate.downloadReel());
        if (tvMusicName != null) tvMusicName.setOnClickListener(v -> delegate.openSoundDetail());
        if (ivMusicDisc != null) ivMusicDisc.setOnClickListener(v -> delegate.openSoundDetail());
        if (ivOwnerAvatar != null) ivOwnerAvatar.setOnClickListener(v -> delegate.openUserReels());
        if (tvOwnerName   != null) tvOwnerName.setOnClickListener(v -> delegate.openUserReels());
        if (tvCaption     != null) tvCaption.setOnClickListener(v -> showReelDetailsCard());
        if (ivOwnerStoryRing != null) ivOwnerStoryRing.setOnClickListener(v -> delegate.openOwnerStatus());
    }

    /**
     * Opens the compact reel-details card from the tappable reel name/caption.
     * The values come from ReelModel so old and newly uploaded reels behave
     * consistently without a second database request.
     */
    private void showReelDetailsCard() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;

        String title = !TextUtils.isEmpty(reel.caption)
            ? reel.caption.split("\\R", 2)[0].trim()
            : "Untitled reel";
        String description = !TextUtils.isEmpty(reel.caption)
            ? reel.caption : "No description";
        String uploaded = reel.timestamp > 0
            ? new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(new Date(reel.timestamp))
            : "Not available";
        String audio = !TextUtils.isEmpty(reel.musicName)
            ? reel.musicName
            : "Original audio";
        String duration = reel.duration > 0
            ? String.format(Locale.getDefault(), "%d:%02d",
                reel.duration / 60, reel.duration % 60)
            : "Not available";
        String size = reel.width > 0 && reel.height > 0
            ? reel.width + " × " + reel.height : "Not available";

        String details = "Description\n" + description
            + "\n\nUploaded\n" + uploaded
            + "\n\nAudio\n" + audio
            + "\n\nDuration\n" + duration
            + "\n\nSize\n" + size
            + "\n\nViews  " + delegate.formatCount(reel.viewsCount)
            + "   Likes  " + delegate.formatCount(reel.likesCount)
            + "\nComments  " + delegate.formatCount(reel.commentsCount)
            + "   Shares  " + delegate.formatCount(reel.sharesCount);

        TextView detailsText = new TextView(delegate.requireContext());
        detailsText.setText(details);
        detailsText.setTextColor(0xFF222222);
        detailsText.setTextSize(15);
        detailsText.setLineSpacing(0f, 1.12f);
        detailsText.setPadding(24, 8, 24, 8);

        ScrollView detailsScroll = new ScrollView(delegate.requireContext());
        detailsScroll.setFillViewport(true);
        detailsScroll.addView(detailsText, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxDialogHeight = (int) (delegate.requireContext().getResources()
            .getDisplayMetrics().heightPixels * 0.48f);
        detailsScroll.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxDialogHeight));

        new AlertDialog.Builder(delegate.requireContext())
            .setTitle("@" + (TextUtils.isEmpty(reel.ownerName) ? "user" : reel.ownerName)
                + " · " + title)
            .setView(detailsScroll)
            .setPositiveButton("Close", null)
            .show();
    }

    // ── Legacy sound-cover backfill ─────────────────────────────────────
    // Reels posted before the musicCoverUrl fix have that field missing in
    // Firebase even though they DO have a valid musicId. Rather than a full
    // data migration, fetch the cover once from sounds/{musicId} at render
    // time and bind it in — cached per musicId so repeat reels with the same
    // sound don't re-fetch.
    private static final java.util.Map<String, String> soundCoverCache = new java.util.HashMap<>();
    private static final Set<String> soundCoverFetchInFlight = new HashSet<>();

    private void fetchAndBindMissingSoundCover(ReelModel reel) {
        String musicId = reel.musicId;
        String cached = soundCoverCache.get(musicId);
        if (cached != null) {
            if (!cached.isEmpty()) bindMusicCover(reel, cached);
            return;
        }
        if (!soundCoverFetchInFlight.add(musicId)) return; // already fetching

        FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
            .getReference("sounds").child(musicId).child("coverUrl")
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    String coverUrl = snap.getValue(String.class);
                    soundCoverCache.put(musicId, coverUrl != null ? coverUrl : "");
                    soundCoverFetchInFlight.remove(musicId);
                    if (coverUrl != null && !coverUrl.isEmpty()) {
                        bindMusicCover(reel, coverUrl);
                    }
                }
                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                    soundCoverFetchInFlight.remove(musicId);
                }
            });
    }

    /** Sets reel.musicCoverUrl (so future binds don't re-fetch) and refreshes both music views if still on screen. */
    private void bindMusicCover(ReelModel reel, String coverUrl) {
        reel.musicCoverUrl = coverUrl;
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        if (btnCreateAudio != null) {
            Glide.with(delegate.requireContext()).load(coverUrl)
                .apply(new RequestOptions().centerCrop().placeholder(R.drawable.ic_audio))
                .into(btnCreateAudio);
        }
        if (ivMusicDisc != null) {
            Glide.with(delegate.requireContext()).load(coverUrl)
                .apply(new RequestOptions().circleCrop().placeholder(R.drawable.ic_music_note))
                .into(ivMusicDisc);
        }
    }

    // ── Music disc animation ──────────────────────────────────────────────

    public void startDiscAnimation() {
        if (ivMusicDisc == null) return;
        if (discAnimator != null) { discAnimator.cancel(); discAnimator = null; }
        discAnimator = ObjectAnimator.ofFloat(ivMusicDisc, "rotation", 0f, 360f);
        discAnimator.setDuration(3000);
        discAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        discAnimator.setRepeatMode(ObjectAnimator.RESTART);
        discAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        discAnimator.start();
    }

    public void stopDiscAnimation() {
        if (discAnimator != null) discAnimator.pause();
    }

    // ── Cinema Mode ───────────────────────────────────────────────────────

    private void openCinemaSheet() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        boolean currentlyHidden = reel.reelId != null && cinemaHiddenReels.contains(reel.reelId);
        com.callx.app.feed.ReelCinemaSheet sheet = com.callx.app.feed.ReelCinemaSheet.newInstance(currentlyHidden);
        sheet.setListener(this::toggleCinemaMode);
        sheet.show(delegate.getChildFragmentManager(), com.callx.app.feed.ReelCinemaSheet.TAG);
    }

    private void toggleCinemaMode() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;
        if (cinemaHiddenReels.contains(reel.reelId)) {
            cinemaHiddenReels.remove(reel.reelId);
        } else {
            cinemaHiddenReels.add(reel.reelId);
        }
        View root = delegate.getFragment().getView();
        if (root != null) applyCinemaState(root);
    }

    public void applyCinemaState(View root) {
        if (root == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;
        isUiHidden = cinemaHiddenReels.contains(reel.reelId);
        int vis = isUiHidden ? View.INVISIBLE : View.VISIBLE;

        View rightActions = root.findViewById(R.id.right_actions);
        View bottomInfo   = root.findViewById(R.id.bottom_info);
        View topControls  = root.findViewById(R.id.top_controls);
        View progressVid  = root.findViewById(R.id.progress_video);
        View repostAttr   = root.findViewById(R.id.ll_repost_attribution);
        View repostChip   = root.findViewById(R.id.ll_repost_count_chip);
        View seriesChip   = root.findViewById(R.id.ll_series_chip);
        View likers       = root.findViewById(R.id.ll_likers_avatar_row);

        if (rightActions != null) rightActions.setVisibility(vis);
        if (bottomInfo   != null) bottomInfo.setVisibility(vis);
        if (topControls  != null) topControls.setVisibility(vis);
        if (progressVid  != null) progressVid.setVisibility(vis);
        if (repostAttr != null && repostAttr.getVisibility() != View.GONE) repostAttr.setVisibility(vis);
        if (repostChip != null && repostChip.getVisibility() != View.GONE) repostChip.setVisibility(vis);
        if (seriesChip != null && seriesChip.getVisibility() != View.GONE) seriesChip.setVisibility(vis);
        if (likers     != null && likers.getVisibility()     != View.GONE) likers.setVisibility(vis);
    }

    // ── Release ───────────────────────────────────────────────────────────

    // Logic for pinned comments added here

    public void setupPinnedComment(ReelModel reel) {
        if (reelPinnedCommentContainer == null) return;
        if (reel.pinnedCommentId == null || reel.pinnedCommentId.isEmpty()) {
            reelPinnedCommentContainer.setVisibility(View.GONE);
            return;
        }

        if (!reel.pinnedCommentText.isEmpty()) {
            populatePinnedCommentUi(reel);
        } else {
            // Fetch from Firebase
            FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                .getReference("reelComments").child(reel.reelId).child(reel.pinnedCommentId)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                        if (!delegate.isAdded()) return;
                        com.callx.app.models.ReelComment c = snap.getValue(com.callx.app.models.ReelComment.class);
                        if (c != null) {
                            reel.pinnedCommentText = c.text;
                            reel.pinnedCommentAuthorName = c.ownerName;
                            reel.pinnedCommentAuthorAvatar = c.ownerPhoto;
                            reel.pinnedCommentLikes = c.likesCount;
                            populatePinnedCommentUi(reel);
                        }
                    }
                    @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                });
        }

        reelPinnedCommentContainer.setOnClickListener(v -> {
            Intent intent = new Intent(delegate.requireContext(), com.callx.app.comments.ReelCommentActivity.class);
            intent.putExtra(com.callx.app.comments.ReelCommentActivity.EXTRA_REEL_ID, reel.reelId);
            intent.putExtra(com.callx.app.comments.ReelCommentActivity.EXTRA_REEL_UID, reel.uid);
            intent.putExtra("EXTRA_HIGHLIGHT_COMMENT_ID", reel.pinnedCommentId);
            delegate.getFragment().startActivity(intent);
        });
    }

    private void populatePinnedCommentUi(ReelModel reel) {
        reelPinnedCommentContainer.setVisibility(View.VISIBLE);
        if (tvPinnedAuthor != null) tvPinnedAuthor.setText(reel.pinnedCommentAuthorName);
        if (tvPinnedText != null) tvPinnedText.setText(reel.pinnedCommentText);
        if (tvPinnedLikes != null) tvPinnedLikes.setText(String.valueOf(reel.pinnedCommentLikes));
        if (ivPinnedAvatar != null && delegate.isAdded()) {
            Glide.with(delegate.requireContext())
                .load(reel.pinnedCommentAuthorAvatar)
                .apply(new RequestOptions().circleCrop().placeholder(R.drawable.ic_person))
                .into(ivPinnedAvatar);
        }
    }
    public void release() {
        uiHandler.removeCallbacksAndMessages(null);
        if (discAnimator != null) { discAnimator.cancel(); discAnimator = null; }
    }

    // ── Inner: DoubleTapListener ──────────────────────────────────────────

    private static class DoubleTapListener implements View.OnTouchListener {
        private static final long DOUBLE_TAP_TIMEOUT = 300;
        private final Runnable action;
        private int tapCount = 0;
        private final Handler handler = new Handler(Looper.getMainLooper());

        DoubleTapListener(Runnable action) { this.action = action; }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                tapCount++;
                if (tapCount == 1) {
                    handler.postDelayed(() -> tapCount = 0, DOUBLE_TAP_TIMEOUT);
                } else if (tapCount >= 2) {
                    tapCount = 0;
                    handler.removeCallbacksAndMessages(null);
                    action.run();
                    return true;
                }
            }
            return false;
        }
    }
}
