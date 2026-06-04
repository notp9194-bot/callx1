package com.callx.app.fragments;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.cache.ReelCacheManager;
import com.callx.app.activities.ReelCommentActivity;
import com.callx.app.activities.HashtagReelsActivity;
import com.callx.app.activities.ReelAnalyticsActivity;
import com.callx.app.activities.ReelEditActivity;
import com.callx.app.activities.ReelReportActivity;
import com.callx.app.activities.ReelShareSheetFragment;
import com.callx.app.activities.RepostWithCaptionActivity;
import com.callx.app.activities.ReelRepostListActivity;
import com.callx.app.activities.DuetReelActivity;
import com.callx.app.activities.StitchReelActivity;
import com.callx.app.activities.ReelVideoReplyActivity;
import com.callx.app.activities.UserReelsActivity;
import com.callx.app.activities.SoundDetailActivity;
import com.callx.app.activities.ReelBookmarkCollectionsActivity;
import com.callx.app.activities.ReelCollabRequestActivity;
import com.callx.app.activities.ReelQRCodeActivity;
import com.callx.app.activities.ReelPinnedCommentsActivity;
import com.callx.app.activities.ReelShareToStoryActivity;

import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.workers.ReelRepostWorker;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.cache.StatusCacheManager;
import com.google.firebase.database.Transaction;

import java.util.Arrays;
import java.util.List;

/**
 * ReelPlayerFragment — Full-screen single-reel player.
 *
 * Changes in this version:
 *  ✅ FIX #1: Progress bar is now at the very bottom (0dp margin) via the layout XML
 *  ✅ FIX #5: Repost button + repost count wired to Firebase + WorkManager (background-kill-safe)
 *      - Repost writes: reelReposts/{reelId}/{uid}, userReposts/{uid}/{reelId}
 *      - Increments repostCount via Firebase transaction
 *      - Dispatches in-app notification + FCM push to original creator
 *      - Uses ReelRepostWorker (WorkManager) so repost is guaranteed even if app is killed
 *      - Shows local confirmation notification via ReelRepostNotificationHelper
 *  ✅ Speed control, download, auto-advance, emoji reactions, hashtag chips
 */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public class ReelPlayerFragment extends Fragment
        implements com.callx.app.ui.ReelMoreBottomSheet.OnItemClickListener {

    private static final float[] SPEED_STEPS  = {0.5f, 1.0f, 1.5f, 2.0f};
    /** Rate-limit repost button: 2 seconds between actions (anti-spam). */
    private static final long REPOST_RATE_LIMIT_MS = 2_000L;
    private long lastRepostActionMs = 0L;
    /** Attribution banner shown when reel.repostedFromName is set. */
    private TextView tvRepostAttribution;
    private static final String[] SPEED_LABELS = {"0.5×", "1×", "1.5×", "2×"};
    private int speedIndex = 1;

    // ── Views ──────────────────────────────────────────────────────────────
    private PlayerView      playerView;
    private ImageView       ivThumb, ivLikeAnim;
    /** Play/Pause visual indicator — fades in/out on tap */
    private ImageView       ivPlayPauseIndicator;
    private CircleImageView ivOwnerAvatar;
    private ImageView       ivOwnerStoryRing;
    private TextView        tvOwnerName, tvCaption, tvMusicName;
    private TextView        tvLikesCount, tvCommentsCount, tvSharesCount;
    private TextView        tvFollowBtn;
    private ImageButton     btnLike, btnComment, btnShare, btnSave, btnMute, btnMore, btnDownload;
    private ImageButton     btnRepost;       // FIX #5: Repost button
    private TextView        tvRepostCount;   // FIX #5: Repost count label
    private TextView        btnSpeed;
    private LinearLayout    containerHashtags;
    private HorizontalScrollView scrollHashtags;
    private LinearLayout    layoutReactions;
    // Feature 11: Music disc
    private ImageView       ivMusicDisc;
    private LinearLayout    layoutMusicTicker;
    private ObjectAnimator  discAnimator;
    // Feature 12: Live reaction counts strip
    private LinearLayout    layoutLiveReactions;
    private ValueEventListener reactionsListener;

    /**
     * CRITICAL FIX: Declared as View (common base), NOT ImageButton.
     * The XML layout has btn_follow_overlay as a <TextView>, not an ImageButton.
     */
    private View            btnFollowOverlay;

    private ProgressBar     progressVideo, progressBuffering;

    // ── Floating Liker Avatars ─────────────────────────────────────────────
    private android.widget.FrameLayout llLikersAvatarRow;
    private CircleImageView ivLiker1, ivLiker2, ivLiker3;
    private TextView        tvHeart1, tvHeart2, tvHeart3;
    private View            flLiker1, flLiker2, flLiker3;
    private String[]        likerUidCache = new String[3]; // for click → UserReelsActivity
    private ValueEventListener likersListener;
    private ObjectAnimator  floatAnim1, floatAnim2, floatAnim3;

    // ── State ──────────────────────────────────────────────────────────────
    private ReelModel reel;
    private ExoPlayer player;
    private boolean   isMuted      = false;
    private boolean   isLiked      = false;
    private boolean   isSaved      = false;
    private boolean   isFollowing  = false;
    private boolean   isReposted   = false;  // FIX #5
    private final java.util.Set<String> blockedUids = new java.util.HashSet<>();
    private boolean   isVisible    = false;
    private boolean   reactionsVisible = false;

    private ValueEventListener likeListener;
    private ValueEventListener saveListener;
    private ValueEventListener followListener;
    private ValueEventListener countListener;
    private ValueEventListener repostListener;  // FIX #5

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ── Factory ───────────────────────────────────────────────────────────

    public static ReelPlayerFragment newInstance(ReelModel reel) {
        ReelPlayerFragment f = new ReelPlayerFragment();
        Bundle args = new Bundle();
        args.putString("reel_id",    reel.reelId);
        args.putString("reel_uid",   reel.uid);
        args.putString("owner_name", reel.ownerName);
        args.putString("owner_photo",reel.ownerPhoto);
        args.putString("video_url",  reel.videoUrl);
        args.putString("thumb_url",  reel.thumbUrl);
        args.putString("caption",    reel.caption);
        args.putString("music_name",      reel.musicName);
        args.putString("music_cover_url", reel.musicCoverUrl != null ? reel.musicCoverUrl : "");
        args.putString("music_artist",    reel.musicArtist   != null ? reel.musicArtist   : "");
        args.putInt("music_start_sec",    reel.musicStartSec);
        args.putLong("timestamp",         reel.timestamp);
        args.putInt("duration",      reel.duration);
        args.putInt("width",         reel.width);
        args.putInt("height",        reel.height);
        args.putInt("likes",         reel.likesCount);
        args.putInt("comments",      reel.commentsCount);
        args.putInt("shares",        reel.sharesCount);
        args.putInt("views",         reel.viewsCount);
        args.putInt("reposts",       reel.repostCount);  // FIX #5
        args.putString("original_audio_url", reel.originalAudioUrl != null ? reel.originalAudioUrl : "");
        f.setArguments(args);
        return f;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            reel = new ReelModel();
            reel.reelId        = getArguments().getString("reel_id");
            reel.uid           = getArguments().getString("reel_uid");
            reel.ownerName     = getArguments().getString("owner_name");
            reel.ownerPhoto    = getArguments().getString("owner_photo");
            reel.videoUrl      = getArguments().getString("video_url");
            reel.thumbUrl      = getArguments().getString("thumb_url");
            reel.caption       = getArguments().getString("caption");
            reel.musicName     = getArguments().getString("music_name");
            reel.musicCoverUrl = getArguments().getString("music_cover_url", "");
            reel.musicArtist   = getArguments().getString("music_artist",    "");
            reel.musicStartSec = getArguments().getInt("music_start_sec",    0);
            reel.timestamp     = getArguments().getLong("timestamp");
            reel.duration      = getArguments().getInt("duration");
            reel.width         = getArguments().getInt("width");
            reel.height        = getArguments().getInt("height");
            reel.likesCount    = getArguments().getInt("likes");
            reel.commentsCount = getArguments().getInt("comments");
            reel.sharesCount   = getArguments().getInt("shares");
            reel.viewsCount    = getArguments().getInt("views");
            reel.repostCount   = getArguments().getInt("reposts");  // FIX #5
            reel.originalAudioUrl = getArguments().getString("original_audio_url", "");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_reel_player, container, false);
        bindViews(v);
        populateStaticData();
        setupClickListeners(v);
        startFirebaseListeners();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        // FIX: Do NOT start playback here directly.
        // ReelsFragment controls playback exclusively via setUserVisibleHint().
        // If we call startPlayback() here, reels play in the background whenever
        // ANY activity is dismissed — even when Reels tab is not active.
        // Playback is only allowed when ReelsFragment.isTabActive == true.
    }

    @Override
    public void onPause() {
        pausePlayback();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        stopProgressTracking();
        uiHandler.removeCallbacksAndMessages(null);
        removeFirebaseListeners();
        releasePlayer();
        // Cancel liker float animations
        if (floatAnim1 != null) { floatAnim1.cancel(); floatAnim1 = null; }
        if (floatAnim2 != null) { floatAnim2.cancel(); floatAnim2 = null; }
        if (floatAnim3 != null) { floatAnim3.cancel(); floatAnim3 = null; }
        super.onDestroyView();
    }

    // ── Called by ReelsFragment to control playback ───────────────────────

    public void setUserVisibleHint(boolean visible) {
        isVisible = visible;
        if (visible) {
            startPlayback();
            recordView();
            markReelNotificationsRead();
        } else {
            pausePlayback();
        }
    }

    // ── View binding ──────────────────────────────────────────────────────

    private void bindViews(View v) {
        playerView        = v.findViewById(R.id.player_view);
        ivThumb           = v.findViewById(R.id.iv_thumb);
        ivLikeAnim        = v.findViewById(R.id.iv_like_anim);
        ivPlayPauseIndicator = v.findViewById(R.id.iv_play_pause_indicator);

        // ── Edge-to-edge insets for right_actions and bottom_info ─────────
        // right_actions: paddingBottom grows by navBarHeight so action buttons
        //   stay above the bottom nav bar on gesture-navigation devices.
        View rightActions = v.findViewById(R.id.right_actions);
        if (rightActions != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rightActions, (view, insets) -> {
                int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                // base 8dp bottom padding + navBarHeight
                int basePx = (int)(8 * view.getResources().getDisplayMetrics().density);
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), basePx + navBarHeight);
                return insets;
            });
        }
        // bottom_info: paddingBottom = navBarHeight only — strip sits at very bottom of screen
        View bottomInfo = v.findViewById(R.id.bottom_info);
        if (bottomInfo != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomInfo, (view, insets) -> {
                int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), navBarHeight);
                return insets;
            });
        }
        ivOwnerAvatar     = v.findViewById(R.id.iv_owner_avatar);
        ivOwnerStoryRing  = v.findViewById(R.id.iv_owner_story_ring);
        tvOwnerName       = v.findViewById(R.id.tv_owner_name);
        tvCaption         = v.findViewById(R.id.tv_caption);
        tvMusicName       = v.findViewById(R.id.tv_music_name);
        tvLikesCount      = v.findViewById(R.id.tv_likes_count);
        tvCommentsCount   = v.findViewById(R.id.tv_comments_count);
        tvSharesCount     = v.findViewById(R.id.tv_shares_count);
        tvFollowBtn       = v.findViewById(R.id.tv_follow_btn);

        // Likers avatar row
        llLikersAvatarRow = v.findViewById(R.id.ll_likers_avatar_row);
        ivLiker1          = v.findViewById(R.id.iv_liker_1);
        ivLiker2          = v.findViewById(R.id.iv_liker_2);
        ivLiker3          = v.findViewById(R.id.iv_liker_3);
        tvHeart1          = v.findViewById(R.id.tv_heart_1);
        tvHeart2          = v.findViewById(R.id.tv_heart_2);
        tvHeart3          = v.findViewById(R.id.tv_heart_3);
        flLiker1          = v.findViewById(R.id.fl_liker_1);
        flLiker2          = v.findViewById(R.id.fl_liker_2);
        flLiker3          = v.findViewById(R.id.fl_liker_3);

        // FIXED: View, not ImageButton
        btnFollowOverlay  = v.findViewById(R.id.btn_follow_overlay);

        btnLike           = v.findViewById(R.id.btn_like);
        btnComment        = v.findViewById(R.id.btn_comment);
        btnShare          = v.findViewById(R.id.btn_share);
        btnMute           = v.findViewById(R.id.btn_mute);
        btnMore           = v.findViewById(R.id.btn_more);
        btnRepost         = v.findViewById(R.id.btn_repost);
        tvRepostCount     = v.findViewById(R.id.tv_repost_count);
        tvRepostAttribution = v.findViewWithTag("tv_repost_attribution");

        containerHashtags = v.findViewById(R.id.container_hashtags);
        scrollHashtags    = v.findViewById(R.id.scroll_hashtags);
        layoutReactions    = v.findViewById(R.id.layout_reactions);
        ivMusicDisc        = v.findViewById(R.id.iv_music_disc);
        layoutMusicTicker  = v.findViewById(R.id.layout_music_ticker);
        layoutLiveReactions = v.findViewById(R.id.layout_live_reactions);
        progressVideo     = v.findViewById(R.id.progress_video);
        progressBuffering = v.findViewById(R.id.progress_buffering);
    }

    private void populateStaticData() {
        if (reel == null) return;

        tvOwnerName.setText(reel.ownerName != null ? "@" + reel.ownerName : "@user");
        tvCaption.setText(reel.caption != null ? reel.caption : "");
        // Bottom music ticker: title with artist suffix
        String musicDisplay = reel.musicName != null && !reel.musicName.isEmpty()
            ? reel.musicName : "Original Audio";
        if (reel.musicArtist != null && !reel.musicArtist.isEmpty()
                && !musicDisplay.contains(reel.musicArtist)) {
            musicDisplay = musicDisplay + " · " + reel.musicArtist;
        }
        tvMusicName.setText(musicDisplay);
        // Enable marquee scrolling so long titles scroll automatically
        tvMusicName.setSingleLine(true);
        tvMusicName.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        tvMusicName.setMarqueeRepeatLimit(-1);
        tvMusicName.setSelected(true);
        tvMusicName.setHorizontallyScrolling(true);
        // Feature 11: start rotating disc + load cover art
        startDiscAnimation();
        // Load cover art onto the rotating music disc (like TikTok/Reels)
        if (ivMusicDisc != null && isAdded() && getContext() != null) {
            String coverUrl = reel.musicCoverUrl;
            if (coverUrl != null && !coverUrl.isEmpty()) {
                Glide.with(requireContext())
                    .load(coverUrl)
                    .apply(new RequestOptions()
                        .circleCrop()
                        .placeholder(R.drawable.ic_music_note))
                    .into(ivMusicDisc);
            } else {
                ivMusicDisc.setImageResource(R.drawable.ic_music_note);
            }
        }
        tvLikesCount.setText(formatCount(reel.likesCount));
        tvCommentsCount.setText(formatCount(reel.commentsCount));
        tvSharesCount.setText(formatCount(reel.sharesCount));

        // FIX #5: initial repost count
        if (tvRepostCount != null)
            tvRepostCount.setText(formatCount(reel.repostCount));

        // Attribution banner — shown when this feed entry is a repost
        if (tvRepostAttribution != null) {
            if (reel.repostedFromName != null && !reel.repostedFromName.isEmpty()) {
                tvRepostAttribution.setText("🔁 Reposted from @" + reel.repostedFromName);
                tvRepostAttribution.setVisibility(android.view.View.VISIBLE);
            } else {
                tvRepostAttribution.setVisibility(android.view.View.GONE);
            }
        }

        if (reel.ownerPhoto != null && !reel.ownerPhoto.isEmpty() && isAdded() && getContext() != null) {
            Glide.with(requireContext()).load(reel.ownerPhoto)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .into(ivOwnerAvatar);
        }
        // Story ring + avatar click — unified logic
        if (isAdded() && getContext() != null && reel.uid != null) {
            StatusCacheManager scm = StatusCacheManager.getInstance(requireContext());
            boolean hasStory = scm.hasUnseen(reel.uid) || scm.hasStatus(reel.uid);

            if (ivOwnerStoryRing != null) {
                if (scm.hasUnseen(reel.uid)) {
                    ivOwnerStoryRing.setBackgroundResource(R.drawable.circle_status_unseen);
                    ivOwnerStoryRing.setVisibility(View.VISIBLE);
                } else if (scm.hasStatus(reel.uid)) {
                    ivOwnerStoryRing.setBackgroundResource(R.drawable.circle_status_seen);
                    ivOwnerStoryRing.setVisibility(View.VISIBLE);
                } else {
                    ivOwnerStoryRing.setVisibility(View.GONE);
                }
                ivOwnerStoryRing.setOnClickListener(v -> openOwnerStatus());
            }

            if (ivOwnerAvatar != null) {
                ivOwnerAvatar.setOnClickListener(v -> {
                    if (hasStory) openOwnerStatus();
                    else openUserReels();
                });
            }
        } else if (ivOwnerAvatar != null) {
            ivOwnerAvatar.setOnClickListener(v -> openUserReels());
        }
        if (tvOwnerName != null && reel.uid != null) {
            tvOwnerName.setOnClickListener(v -> openUserReels());
        }
        if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty() && isAdded() && getContext() != null) {
            Glide.with(requireContext()).load(reel.thumbUrl).centerCrop().into(ivThumb);
        }

        String myUid = safeMyUid();
        if (myUid != null && myUid.equals(reel.uid)) {
            tvFollowBtn.setVisibility(View.GONE);
            if (btnFollowOverlay != null) btnFollowOverlay.setVisibility(View.GONE);
        }

        renderHashtags();

        // Load likers avatars (Instagram-style) below owner name
        if (reel.reelId != null) fetchLikerAvatars();
    }

    // ── Hashtag rendering ─────────────────────────────────────────────────

    private void renderHashtags() {
        if (reel == null || reel.caption == null || reel.caption.isEmpty()) return;
        List<String> tags = ReelModel.extractHashtags(reel.caption);
        if (tags.isEmpty() || containerHashtags == null) return;

        scrollHashtags.setVisibility(View.VISIBLE);
        containerHashtags.removeAllViews();

        int dp8 = dpToPx(8);
        int dp4 = dpToPx(4);

        for (String tag : tags) {
            TextView chip = new TextView(requireContext());
            chip.setText("#" + tag);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(12f);
            chip.setBackgroundResource(R.drawable.bg_speed_chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp8, 0);
            chip.setLayoutParams(lp);
            chip.setPadding(dp8, dp4, dp8, dp4);
            chip.setClickable(true);
            chip.setFocusable(true);
            final String finalTag = tag;
            chip.setOnClickListener(cv -> {
                Intent intent = new Intent(requireContext(), HashtagReelsActivity.class);
                intent.putExtra(HashtagReelsActivity.EXTRA_HASHTAG, finalTag);
                startActivity(intent);
            });
            containerHashtags.addView(chip);
        }
    }

    // ── Likers Avatar Row ─────────────────────────────────────────────────

    /**
     * Fetches up to 3 recent likers from Firebase and shows their circular
     * profile pictures in the Instagram-style avatar row above the owner name.
     */
    private void fetchLikerAvatars() {
        if (!isAdded() || getContext() == null || reel == null || reel.reelId == null) return;

        // Remove any previous listener
        if (likersListener != null) {
            FirebaseUtils.getReelLikesRef(reel.reelId)
                .removeEventListener(likersListener);
            likersListener = null;
        }

        DatabaseReference likesRef = FirebaseUtils.getReelLikesRef(reel.reelId);

        likersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                if (!isAdded() || getContext() == null) return;

                long total = snapshot.getChildrenCount();
                if (total == 0) {
                    if (llLikersAvatarRow != null) llLikersAvatarRow.setVisibility(View.GONE);
                    return;
                }

                // Collect up to 3 liker UIDs
                java.util.List<String> likerUids = new java.util.ArrayList<>();
                for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
                    likerUids.add(child.getKey());
                    if (likerUids.size() == 3) break;
                }

                // Show the floating container
                if (llLikersAvatarRow != null) llLikersAvatarRow.setVisibility(View.VISIBLE);

                CircleImageView[] avatarViews = {ivLiker1, ivLiker2, ivLiker3};
                for (CircleImageView av : avatarViews) {
                    if (av != null) av.setVisibility(View.GONE);
                }

                // Cache UIDs for click handlers
                likerUidCache = new String[3];
                for (int i = 0; i < likerUids.size(); i++) {
                    likerUidCache[i] = likerUids.get(i);
                }

                // Fetch each liker's thumbUrl and load into floating avatar views
                for (int i = 0; i < likerUids.size(); i++) {
                    final CircleImageView targetView = avatarViews[i];
                    if (targetView == null) continue;
                    final boolean isLast = (i == likerUids.size() - 1);

                    FirebaseUtils.getUserRef(likerUids.get(i)).child("thumbUrl")
                        .get().addOnSuccessListener(ds -> {
                            if (!isAdded() || getContext() == null) return;
                            targetView.setVisibility(View.VISIBLE);
                            String url = ds.getValue(String.class);
                            if (url != null && !url.isEmpty()) {
                                Glide.with(requireContext())
                                    .load(url)
                                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                                    .placeholder(R.drawable.ic_person)
                                    .error(R.drawable.ic_person)
                                    .into(targetView);
                            } else {
                                targetView.setImageResource(R.drawable.ic_person);
                            }
                            if (isLast) startLikerFloatAnimations();
                        });
                }

                // Show hearts on all visible avatars
                if (tvHeart1 != null) tvHeart1.setVisibility(likerUids.size() >= 1 ? View.VISIBLE : View.GONE);
                if (tvHeart2 != null) tvHeart2.setVisibility(likerUids.size() >= 2 ? View.VISIBLE : View.GONE);
                if (tvHeart3 != null) tvHeart3.setVisibility(likerUids.size() >= 3 ? View.VISIBLE : View.GONE);

                // Per-avatar click → open that liker's UserReelsActivity
                View[] flViews = {flLiker1, flLiker2, flLiker3};
                for (int i = 0; i < flViews.length; i++) {
                    final int idx = i;
                    if (flViews[i] != null) {
                        flViews[i].setOnClickListener(v -> openLikerProfile(idx));
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                // silently ignore
            }
        };

        likesRef.addValueEventListener(likersListener);
    }

    /**
     * Starts gentle independent float (translateY) animations on each liker avatar.
     * Each avatar bobs up and down at a slightly different speed/offset — Instagram explore style.
     */
    private void startLikerFloatAnimations() {
        if (!isAdded() || getContext() == null) return;

        // Stop any existing animations
        if (floatAnim1 != null) floatAnim1.cancel();
        if (floatAnim2 != null) floatAnim2.cancel();
        if (floatAnim3 != null) floatAnim3.cancel();

        float amplitude = dpToPx(5); // float up/down by 5dp (container has 16dp top padding buffer)

        if (ivLiker1 != null && ivLiker1.getVisibility() == View.VISIBLE) {
            floatAnim1 = ObjectAnimator.ofFloat(ivLiker1, "translationY", 0f, -amplitude, 0f);
            floatAnim1.setDuration(2200);
            floatAnim1.setRepeatCount(ObjectAnimator.INFINITE);
            floatAnim1.setRepeatMode(ObjectAnimator.REVERSE);
            floatAnim1.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            floatAnim1.setStartDelay(0);
            floatAnim1.start();
        }
        if (ivLiker2 != null && ivLiker2.getVisibility() == View.VISIBLE) {
            floatAnim2 = ObjectAnimator.ofFloat(ivLiker2, "translationY", 0f, -amplitude, 0f);
            floatAnim2.setDuration(2600);
            floatAnim2.setRepeatCount(ObjectAnimator.INFINITE);
            floatAnim2.setRepeatMode(ObjectAnimator.REVERSE);
            floatAnim2.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            floatAnim2.setStartDelay(300);
            floatAnim2.start();
        }
        if (ivLiker3 != null && ivLiker3.getVisibility() == View.VISIBLE) {
            floatAnim3 = ObjectAnimator.ofFloat(ivLiker3, "translationY", 0f, -amplitude, 0f);
            floatAnim3.setDuration(2400);
            floatAnim3.setRepeatCount(ObjectAnimator.INFINITE);
            floatAnim3.setRepeatMode(ObjectAnimator.REVERSE);
            floatAnim3.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            floatAnim3.setStartDelay(600);
            floatAnim3.start();
        }
    }

    /**
     * Opens UserReelsActivity for the liker at the given index (0,1,2).
     * Fetches name + photo from Firebase before launching.
     */
    private void openLikerProfile(int idx) {
        if (!isAdded() || getContext() == null) return;
        if (likerUidCache == null || idx >= likerUidCache.length || likerUidCache[idx] == null) return;
        String uid = likerUidCache[idx];
        // Reels profile se name + photo lo (reels/users/{uid})
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(uid)
            .get().addOnSuccessListener(ds -> {
                if (!isAdded() || getContext() == null) return;
                String name  = ds.child("displayName").getValue(String.class);
                String thumb = ds.child("thumbUrl").getValue(String.class);
                String photo = ds.child("photoUrl").getValue(String.class);
                String resolvedPhoto = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                if (name == null) name = "";
                if (resolvedPhoto == null) resolvedPhoto = "";
                Intent intent = new Intent(getActivity(), UserReelsActivity.class);
                intent.putExtra(UserReelsActivity.EXTRA_UID,   uid);
                intent.putExtra(UserReelsActivity.EXTRA_NAME,  name);
                intent.putExtra(UserReelsActivity.EXTRA_PHOTO, resolvedPhoto);
                startActivity(intent);
            });
    }

    // ── Click listeners ───────────────────────────────────────────────────

    private void setupClickListeners(View root) {
        root.setOnClickListener(v -> {
            if (reactionsVisible) { hideReactions(); return; }
            togglePlayPause();
        });

        root.setOnTouchListener(new DoubleTapListener(() -> {
            if (!isLiked) toggleLike();
            showLikeAnimation();
        }));

        btnLike.setOnClickListener(v -> { hideReactions(); toggleLike(); });
        btnLike.setOnLongClickListener(v -> { toggleReactionPanel(); return true; });

        // Tap likes count → show "Likes and plays" bottom sheet
        tvLikesCount.setOnClickListener(v -> openLikesSheet());

        // Tap shares count → show "Shares and reposts" bottom sheet
        tvSharesCount.setOnClickListener(v -> openSharesSheet());

        // Tap comments COUNT → show "Comments" bottom sheet (icon still opens full activity)
        tvCommentsCount.setOnClickListener(v -> openCommentsSheet());

        btnComment.setOnClickListener(v -> openComments());
        btnShare.setOnClickListener(v -> shareReel());
        if (btnSave != null) btnSave.setOnClickListener(v -> toggleSave());
        if (btnSave != null) btnSave.setOnLongClickListener(v -> { openBookmarkCollections(); return true; });
        if (tvMusicName != null) tvMusicName.setOnClickListener(v -> openSoundDetail());
        if (ivMusicDisc != null) ivMusicDisc.setOnClickListener(v -> openSoundDetail());
        btnMute.setOnClickListener(v -> toggleMute());
        btnMore.setOnClickListener(v -> showMoreOptions());
        if (btnDownload != null) btnDownload.setOnClickListener(v -> downloadReel());
        if (btnSpeed != null) btnSpeed.setOnClickListener(v -> cycleSpeed());

        // FIX #5: Repost click
        if (btnRepost != null) btnRepost.setOnClickListener(v -> toggleRepost());

        if (tvFollowBtn != null) tvFollowBtn.setOnClickListener(v -> toggleFollow());
        if (btnFollowOverlay != null) btnFollowOverlay.setOnClickListener(v -> toggleFollow());

        setupReactionClick(root, R.id.react_fire,  "🔥");
        setupReactionClick(root, R.id.react_heart, "❤️");
        setupReactionClick(root, R.id.react_wow,   "😮");
        setupReactionClick(root, R.id.react_laugh, "😂");
        setupReactionClick(root, R.id.react_sad,   "😢");
        setupReactionClick(root, R.id.react_clap,  "👏");
    }

    private void setupReactionClick(View root, int id, String emoji) {
        View reactionView = root.findViewById(id);
        if (reactionView != null) {
            reactionView.setOnClickListener(v -> { sendReaction(emoji); hideReactions(); });
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────

    private void startPlayback() {
        if (!isAdded() || getContext() == null) return;
        if (reel == null || reel.videoUrl == null || reel.videoUrl.isEmpty()) return;
        // CRASH FIX: playerView is null if view hasn't been created yet
        // (setUserVisibleHint can fire before onCreateView in ViewPager2)
        if (playerView == null) return;

        if (player == null) {
            player = new ExoPlayer.Builder(requireContext()).build();
            playerView.setPlayer(player);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (!isAdded() || getContext() == null) return;
                    if (state == Player.STATE_BUFFERING) {
                        progressBuffering.setVisibility(View.VISIBLE);
                    } else {
                        progressBuffering.setVisibility(View.GONE);
                        if (state == Player.STATE_READY) {
                            ivThumb.setVisibility(View.GONE);
                            startProgressTracking();
                        }
                        if (state == Player.STATE_ENDED) autoAdvance();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean playing) {
                    if (!isAdded()) return;
                    if (playing) progressBuffering.setVisibility(View.GONE);
                    // Hide/show mute button and top gradient with the top bar
                    if (btnMute != null) {
                        btnMute.setVisibility(playing ? View.GONE : View.VISIBLE);
                    }

                    if (isVisible) {
                        Fragment parent = getParentFragment();
                        if (parent instanceof ReelsFragment) {
                            ((ReelsFragment) parent).onReelPlaybackStateChanged(playing);
                        }
                    }
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    if (!isAdded()) return;
                    progressBuffering.setVisibility(View.GONE);
                    ivThumb.setVisibility(View.VISIBLE);
                }
            });

            CacheDataSource.Factory cacheFactory = ReelCacheManager.getCacheDataSourceFactory();
            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(cacheFactory)
                .createMediaSource(MediaItem.fromUri(reel.videoUrl));
            player.setMediaSource(mediaSource);
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            player.setVolume(isMuted ? 0f : 1f);
            player.setPlaybackParameters(new PlaybackParameters(SPEED_STEPS[speedIndex]));
            player.prepare();
        }
        player.play();
    }

    private void pausePlayback() {
        if (player != null) player.pause();
        stopProgressTracking();
        stopDiscAnimation(); // Feature 11;
    }

    private void togglePlayPause() {
        if (player == null) { startPlayback(); showPlayPauseIndicator(true); return; }
        boolean nowPausing = player.isPlaying();
        if (nowPausing) player.pause();
        else player.play();
        showPlayPauseIndicator(!nowPausing);
    }

    /**
     * Briefly shows a play or pause icon in the centre of the screen,
     * then fades it out — identical UX to Instagram/TikTok.
     *
     * @param isPlay true = show play arrow; false = show pause bars
     */
    private void showPlayPauseIndicator(boolean isPlay) {
        if (ivPlayPauseIndicator == null) return;
        ivPlayPauseIndicator.setImageResource(
            isPlay ? R.drawable.ic_play : R.drawable.ic_pause);
        ivPlayPauseIndicator.animate().cancel();
        ivPlayPauseIndicator.setAlpha(0f);
        ivPlayPauseIndicator.setScaleX(0.7f);
        ivPlayPauseIndicator.setScaleY(0.7f);
        ivPlayPauseIndicator.animate()
            .alpha(0.85f).scaleX(1f).scaleY(1f)
            .setDuration(120)
            .withEndAction(() -> {
                if (ivPlayPauseIndicator == null) return;
                ivPlayPauseIndicator.animate()
                    .alpha(0f).scaleX(0.9f).scaleY(0.9f)
                    .setStartDelay(450)
                    .setDuration(200)
                    .start();
            })
            .start();
    }

    private void releasePlayer() {
        stopProgressTracking();
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void toggleMute() {
        isMuted = !isMuted;
        if (player != null) player.setVolume(isMuted ? 0f : 1f);
        btnMute.setImageResource(isMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEED_STEPS.length;
        float speed = SPEED_STEPS[speedIndex];
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
        if (btnSpeed != null) btnSpeed.setText(SPEED_LABELS[speedIndex]);
    }

    private void autoAdvance() {
        if (!isAdded() || getParentFragment() == null) return;
        Fragment parent = getParentFragment();
        if (parent instanceof ReelsFragment) ((ReelsFragment) parent).advanceToNext();
    }

    // ── Progress tracking ─────────────────────────────────────────────────

    private int lastSavedProgressPct = -1;

    private void startProgressTracking() {
        stopProgressTracking();
        lastSavedProgressPct = -1;
        progressRunnable = new Runnable() {
            @Override public void run() {
                if (!isAdded() || player == null) return;
                if (player.getDuration() > 0) {
                    int p = (int)(player.getCurrentPosition() * 1000 / player.getDuration());
                    progressVideo.setProgress(p);
                    int pct = (int)(player.getCurrentPosition() * 100 / player.getDuration());
                    int milestone = (pct / 10) * 10;
                    if (milestone != lastSavedProgressPct && milestone > 0) {
                        lastSavedProgressPct = milestone;
                        String uid = safeMyUid();
                        if (uid != null && reel != null && reel.reelId != null) {
                            FirebaseUtils.getReelWatchProgressRef(uid)
                                .child(reel.reelId).setValue(milestone);
                        }
                    }
                }
                progressHandler.postDelayed(this, 300);
            }
        };
        progressHandler.post(progressRunnable);
    }

    private void stopProgressTracking() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    // ── Mark notifications read ───────────────────────────────────────────

    private void markReelNotificationsRead() {
        String myUid = safeMyUid();
        if (myUid == null || reel == null || reel.reelId == null) return;
        final String targetReelId = reel.reelId;

        FirebaseUtils.db()
            .getReference("reel_notifications")
            .child(myUid)
            .orderByChild("reel_id")
            .equalTo(targetReelId)
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

    // ── Like ──────────────────────────────────────────────────────────────

    private void toggleLike() {
        String myUid = safeMyUid();
        if (myUid == null || reel == null || reel.reelId == null) return;

        DatabaseReference likeRef  = FirebaseUtils.getReelLikesRef(reel.reelId).child(myUid);
        DatabaseReference countRef = FirebaseUtils.getReelsRef().child(reel.reelId).child("likesCount");

        if (isLiked) {
            isLiked = false;
            btnLike.setImageResource(R.drawable.ic_heart);
            likeRef.removeValue();
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
            btnLike.setImageResource(R.drawable.ic_heart_filled);
            likeRef.setValue(true);
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
                long   nowMs  = System.currentTimeMillis();
                com.callx.app.utils.PushNotify.notifyReelLike(
                    reel.uid, myUid, myName,
                    reel.reelId, reel.thumbUrl != null ? reel.thumbUrl : "");
                // Reels profile se apna thumbUrl lo (reels/users/{uid})
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("reels/users").child(myUid)
                    .get().addOnSuccessListener(reelSnap -> {
                        String rThumb = reelSnap.child("thumbUrl").getValue(String.class);
                        String rPhoto = reelSnap.child("photoUrl").getValue(String.class);
                        String myThumb = (rThumb != null && !rThumb.isEmpty()) ? rThumb : rPhoto;
                        java.util.Map<String, Object> inApp = new java.util.HashMap<>();
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

    private void showLikeAnimation() {
        if (!isAdded()) return;
        ivLikeAnim.setVisibility(View.VISIBLE);
        AnimatorSet set = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(ivLikeAnim, "scaleX", 0.5f, 1.3f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(ivLikeAnim, "scaleY", 0.5f, 1.3f, 1.0f);
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(ivLikeAnim, "alpha", 0f, 1f, 1f, 0f);
        scaleX.setDuration(600); scaleY.setDuration(600); fadeIn.setDuration(700);
        set.playTogether(scaleX, scaleY, fadeIn);
        set.start();
        uiHandler.postDelayed(() -> { if (isAdded()) ivLikeAnim.setVisibility(View.GONE); }, 700);
    }

    // ── FIX: Repost state loader — reads Firebase on every reel bind ─────────
    /**
     * Checks reelReposts/{reelId}/{myUid} on Firebase and updates the repost
     * button color accordingly. Called from startFirebaseListeners() so the
     * button always reflects the persisted state, even after app restarts.
     */
    private void loadRepostState() {
        String myUid = safeMyUid();
        if (myUid == null || reel == null || reel.reelId == null) return;

        if (repostListener != null) return; // already attached

        DatabaseReference repostRef = FirebaseUtils.db()
            .getReference("reelReposts").child(reel.reelId).child(myUid);

        repostListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                isReposted = snap.exists();
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
        repostRef.addValueEventListener(repostListener);
    }

    // ── FIX #5: Repost ───────────────────────────────────────────────────

    /**
     * Toggles repost state for the current reel.
     *
     * On repost:
     *   1. Immediately updates UI (icon tint + count)
     *   2. Enqueues ReelRepostWorker via WorkManager — guaranteed to run even
     *      if the app is killed immediately after the tap.
     *
     * On un-repost:
     *   1. Immediately updates UI
     *   2. Removes the Firebase entry directly (no worker needed for deletions)
     */
    /** Long-press repost button → open who-reposted list. */
    private void openRepostList() {
        if (reel == null || reel.reelId == null || !isAdded() || getActivity() == null) return;
        Intent i = new Intent(getActivity(), ReelRepostListActivity.class);
        i.putExtra(ReelRepostListActivity.EXTRA_REEL_ID, reel.reelId);
        startActivity(i);
    }

    /** Tap repost → toggle; long-press → open repost-with-caption flow. */
    private void openRepostWithCaption() {
        if (reel == null || reel.reelId == null || !isAdded() || getActivity() == null) return;
        Intent i = new Intent(getActivity(), RepostWithCaptionActivity.class);
        i.putExtra(RepostWithCaptionActivity.EXTRA_REEL_ID,    reel.reelId);
        i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_UID,  reel.uid);
        i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_NAME, reel.ownerName);
        i.putExtra(RepostWithCaptionActivity.EXTRA_THUMB_URL,  reel.thumbUrl);
        i.putExtra(RepostWithCaptionActivity.EXTRA_VIDEO_URL,  reel.videoUrl);
        i.putExtra(RepostWithCaptionActivity.EXTRA_CAPTION,    reel.caption);
        startActivity(i);
    }

    private void toggleRepost() {
        String myUid = safeMyUid();
        if (myUid == null || reel == null || reel.reelId == null ||
                !isAdded() || getContext() == null) return;

        // Don't allow re-reposting your own content
        if (myUid.equals(reel.uid)) {
            Toast.makeText(requireContext(), "You can't repost your own reel", Toast.LENGTH_SHORT).show();
            return;
        }

        // Rate limit guard — prevents accidental double-tap spam
        long now = System.currentTimeMillis();
        if (now - lastRepostActionMs < REPOST_RATE_LIMIT_MS) {
            Toast.makeText(requireContext(), "Please wait…", Toast.LENGTH_SHORT).show();
            return;
        }
        lastRepostActionMs = now;

        // Check creator privacy setting
        if (!reel.allowReposts && !isReposted) {
            Toast.makeText(requireContext(), "This creator has disabled reposts", Toast.LENGTH_SHORT).show();
            return;
        }

        DatabaseReference repostRef = FirebaseUtils.db()
            .getReference("reelReposts").child(reel.reelId).child(myUid);

        if (isReposted) {
            // Un-repost
            isReposted = false;
            if (btnRepost != null) btnRepost.setColorFilter(null);
            reel.repostCount = Math.max(0, reel.repostCount - 1);
            if (tvRepostCount != null) tvRepostCount.setText(formatCount(reel.repostCount));

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
            // Repost — write to Firebase immediately AND enqueue WorkManager for reliability
            isReposted = true;
            if (btnRepost != null) btnRepost.setColorFilter(
                android.graphics.Color.parseColor("#4CAF50"),
                android.graphics.PorterDuff.Mode.SRC_IN);
            reel.repostCount++;
            if (tvRepostCount != null) tvRepostCount.setText(formatCount(reel.repostCount));

            long repostTs = System.currentTimeMillis();
            // Direct writes so data is immediately visible (WorkManager is backup for notifications)
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

            // WorkManager enqueue for notification dispatch (background-kill-safe)
            ReelRepostWorker.enqueue(
                requireContext(),
                reel.reelId,
                myUid,
                FirebaseUtils.getCurrentName(),
                reel.uid,
                reel.ownerName,
                reel.thumbUrl);

            Toast.makeText(requireContext(), "Reposted!", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Emoji reactions ───────────────────────────────────────────────────

    private void toggleReactionPanel() {
        if (layoutReactions == null) return;
        reactionsVisible = !reactionsVisible;
        layoutReactions.setVisibility(reactionsVisible ? View.VISIBLE : View.GONE);
        if (reactionsVisible) uiHandler.postDelayed(this::hideReactions, 4000);
    }

    private void hideReactions() {
        reactionsVisible = false;
        if (layoutReactions != null) layoutReactions.setVisibility(View.GONE);
    }

    private void sendReaction(String emoji) {
        String myUid = safeMyUid();
        if (myUid == null || reel == null || reel.reelId == null) return;
        FirebaseUtils.getReelReactionsRef(reel.reelId).child(myUid).setValue(emoji);
        if (isAdded() && getContext() != null)
            Toast.makeText(requireContext(), emoji, Toast.LENGTH_SHORT).show();
    }

    // ── Save / Bookmark ───────────────────────────────────────────────────

    private void toggleSave() {
        String myUid = safeMyUid();
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

    private void toggleFollow() {
        String myUid = safeMyUid();
        if (myUid == null || reel == null || reel.uid == null) return;
        if (myUid.equals(reel.uid)) return;

        DatabaseReference followRef = FirebaseUtils.getReelFollowsRef(myUid).child(reel.uid);
        if (isFollowing) {
            isFollowing = false;
            updateFollowUI(false);
            followRef.removeValue();
        } else {
            isFollowing = true;
            updateFollowUI(true);
            followRef.setValue(true);
        }
    }

    private void updateFollowUI(boolean following) {
        if (!isAdded()) return;
        if (following) {
            tvFollowBtn.setText("Following");
            tvFollowBtn.setAlpha(0.6f);
            if (btnFollowOverlay != null) btnFollowOverlay.setVisibility(View.GONE);
        } else {
            tvFollowBtn.setText("Follow");
            tvFollowBtn.setAlpha(1f);
            if (btnFollowOverlay != null) btnFollowOverlay.setVisibility(View.VISIBLE);
        }
    }

    // ── Share ─────────────────────────────────────────────────────────────

    private void shareReel() {
        if (reel == null || reel.reelId == null || !isAdded() || getActivity() == null) return;
        ReelShareSheetFragment sheet = ReelShareSheetFragment.newInstance(
                reel.reelId, reel.videoUrl, reel.thumbUrl,
                reel.caption, reel.uid, reel.allowReposts);
        showBottomSheet(sheet, "share_sheet");
    }

    // ── Download ──────────────────────────────────────────────────────────

    private void downloadReel() {
        if (reel == null || reel.videoUrl == null || !isAdded() || getContext() == null) return;
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(reel.videoUrl));
            req.setTitle("CallX Reel");
            req.setDescription("Downloading reel…");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES,
                "callx_reel_" + reel.reelId + ".mp4");
            req.allowScanningByMediaScanner();
            DownloadManager dm = (DownloadManager) requireContext()
                .getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                Toast.makeText(requireContext(), "Downloading reel…", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ── Likes bottom sheet ────────────────────────────────────────────────

    private void openLikesSheet() {
        if (reel == null || reel.reelId == null || !isAdded() || getActivity() == null) return;
        com.callx.app.ui.ReelLikesBottomSheet sheet =
                com.callx.app.ui.ReelLikesBottomSheet.newInstance(
                        reel.reelId, reel.likesCount, reel.viewsCount);
        showBottomSheet(sheet, com.callx.app.ui.ReelLikesBottomSheet.TAG);
    }

    // ── Shares bottom sheet ───────────────────────────────────────────────

    private void openSharesSheet() {
        if (reel == null || reel.reelId == null || !isAdded() || getActivity() == null) return;
        com.callx.app.ui.ReelSharesBottomSheet sheet =
                com.callx.app.ui.ReelSharesBottomSheet.newInstance(
                        reel.reelId, reel.sharesCount, reel.repostCount);
        showBottomSheet(sheet, com.callx.app.ui.ReelSharesBottomSheet.TAG);
    }

    // ── Comments bottom sheet ─────────────────────────────────────────────

    private void openCommentsSheet() {
        if (reel == null || reel.reelId == null || !isAdded() || getActivity() == null) return;
        com.callx.app.ui.ReelCommentsBottomSheet sheet =
                com.callx.app.ui.ReelCommentsBottomSheet.newInstance(
                        reel.reelId,
                        reel.uid != null ? reel.uid : "",
                        reel.commentsCount);
        showBottomSheet(sheet, com.callx.app.ui.ReelCommentsBottomSheet.TAG);
    }

    // ── Comments ──────────────────────────────────────────────────────────

    private void openComments() {
        if (reel == null || reel.reelId == null || !isAdded() || getContext() == null) return;
        try {
            Intent intent = new Intent(requireContext(), ReelCommentActivity.class);
            intent.putExtra(ReelCommentActivity.EXTRA_REEL_ID,  reel.reelId);
            intent.putExtra(ReelCommentActivity.EXTRA_REEL_UID, reel.uid != null ? reel.uid : "");
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    // ── More options ──────────────────────────────────────────────────────

    private void showMoreOptions() {
        if (!isAdded() || getContext() == null || reel == null) return;
        String  myUid      = safeMyUid();
        boolean isOwner    = myUid != null && myUid.equals(reel.uid);
        String  speedLabel = "Speed: " + SPEED_LABELS[speedIndex];
        boolean allowDuet   = reel.allowDuet;   // respect creator's privacy setting
        boolean allowStitch = true;              // can add allowStitch to ReelModel later

        com.callx.app.ui.ReelMoreBottomSheet sheet =
            com.callx.app.ui.ReelMoreBottomSheet.newInstance(isOwner, isSaved, speedLabel,
                                                              allowDuet, allowStitch);
        sheet.show(getChildFragmentManager(), com.callx.app.ui.ReelMoreBottomSheet.TAG);
    }

    @Override
    public void onMoreItemClick(String action) {
        switch (action) {
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_SAVE:
                toggleSave(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_BOOKMARK_COLLECTIONS:
                openBookmarkCollections(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_SPEED:
                showSpeedPicker(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_DOWNLOAD:
                downloadReel(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_DUET:
                openDuet(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_STITCH:
                openStitch(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_VIDEO_REPLY:
                openVideoReply(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_SHARE_TO_STORY:
                openShareToStory(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_COLLAB_REQUEST:
                openCollabRequest(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_NOT_INTERESTED:
                markNotInterested(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_COPY_LINK:
                copyReelLink(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_REPORT:
                openReelReport(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_EDIT:
                openReelEdit(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_ANALYTICS:
                openReelAnalytics(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_PINNED_COMMENTS:
                openPinnedComments(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_QR_CODE:
                openReelQRCode(); break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_BLOCK:
                blockReelOwner();
                break;
            case com.callx.app.ui.ReelMoreBottomSheet.ACTION_DELETE:
                confirmDeleteReel(); break;
        }
    }

    private void showSpeedPicker() {
        if (!isAdded() || getContext() == null) return;
        String[] speeds = {"0.5x", "1x (Normal)", "1.5x", "2x"};
        new android.app.AlertDialog.Builder(getContext())
            .setTitle("Playback Speed")
            .setItems(speeds, (d, which) -> {
                speedIndex = which;
                float speed = SPEED_STEPS[speedIndex];
                if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
            }).show();
    }

    private void openReelEdit() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), ReelEditActivity.class);
        i.putExtra(ReelEditActivity.EXTRA_REEL_ID, reel.reelId);
        i.putExtra(ReelEditActivity.EXTRA_CAPTION, reel.caption);
        i.putExtra(ReelEditActivity.EXTRA_AUDIENCE_TYPE, reel.audienceType);
        startActivity(i);
    }

    private void openReelAnalytics() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), ReelAnalyticsActivity.class);
        i.putExtra(ReelAnalyticsActivity.EXTRA_REEL_ID,      reel.reelId);
        i.putExtra(ReelAnalyticsActivity.EXTRA_REEL_DURATION, reel.duration);
        startActivity(i);
    }

    private void openStitch() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), StitchReelActivity.class);
        i.putExtra(StitchReelActivity.EXTRA_ORIGINAL_REEL_ID,  reel.reelId);
        i.putExtra(StitchReelActivity.EXTRA_ORIGINAL_REEL_URL, reel.videoUrl);
        startActivity(i);
    }

    private void openVideoReply() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), ReelVideoReplyActivity.class);
        i.putExtra(ReelVideoReplyActivity.EXTRA_REEL_ID, reel.reelId);
        startActivity(i);
    }

    private void openUserReels() {
        if (!isAdded() || getActivity() == null || reel == null || reel.uid == null) return;
        Intent i = new Intent(getActivity(), UserReelsActivity.class);
        i.putExtra(UserReelsActivity.EXTRA_UID,   reel.uid);
        i.putExtra(UserReelsActivity.EXTRA_NAME,  reel.ownerName);
        i.putExtra(UserReelsActivity.EXTRA_PHOTO, reel.ownerPhoto);
        startActivity(i);
    }

    private void openOwnerStatus() {
        if (!isAdded() || getActivity() == null || reel == null || reel.uid == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.StatusViewerActivity");
            Intent si = new Intent(getActivity(), cls);
            si.putExtra("ownerUid",  reel.uid);
            si.putExtra("ownerName", reel.ownerName != null ? reel.ownerName : "");
            startActivity(si);
        } catch (ClassNotFoundException e) {
            openUserReels(); // fallback
        }
    }

    private void openDuet() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        // Respect creator's privacy setting
        if (!reel.allowDuet) {
            Toast.makeText(getContext(), "This creator has disabled duets", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(getActivity(), DuetReelActivity.class);
        i.putExtra(DuetReelActivity.EXTRA_REEL_ID,        reel.reelId);
        i.putExtra(DuetReelActivity.EXTRA_VIDEO_URL,      reel.videoUrl);
        i.putExtra(DuetReelActivity.EXTRA_OWNER_NAME,     reel.ownerName);
        i.putExtra(DuetReelActivity.EXTRA_OWNER_UID,      reel.uid);
        i.putExtra(DuetReelActivity.EXTRA_DURATION_SEC,   reel.duration / 1000);
        startActivity(i);
    }

    private void openReelReport() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), ReelReportActivity.class);
        i.putExtra(ReelReportActivity.EXTRA_REEL_ID,         reel.reelId);
        i.putExtra(ReelReportActivity.EXTRA_REEL_UID,        reel.uid);
        i.putExtra(ReelReportActivity.EXTRA_REEL_OWNER_NAME, reel.ownerName);
        startActivity(i);
    }

    private void confirmDeleteReel() {
        if (!isAdded() || getContext() == null) return;
        new android.app.AlertDialog.Builder(getContext())
            .setTitle("Delete Reel?").setMessage("This cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> deleteReel())
            .setNegativeButton("Cancel", null).show();
    }

    private void deleteReel() {
        if (reel == null || reel.reelId == null) return;
        FirebaseUtils.getReelsRef().child(reel.reelId).removeValue();
        if (getActivity() != null) getActivity().onBackPressed();
    }

    private void copyReelLink() {
        if (!isAdded() || getContext() == null || reel == null) return;
        ClipboardManager cm = (ClipboardManager) requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Reel Link", com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/" + reel.reelId));
            Toast.makeText(requireContext(), "Link copied!", Toast.LENGTH_SHORT).show();
        }
    }

    private void markNotInterested() {
        if (!isAdded() || getContext() == null) return;
        Toast.makeText(requireContext(), "Got it! You'll see less like this.", Toast.LENGTH_SHORT).show();
    }

    private void openSoundDetail() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    reel.musicId    != null ? reel.musicId    : "");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, reel.musicName  != null ? reel.musicName  : "Original Audio");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   reel.musicUrl   != null ? reel.musicUrl   : "");
        i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   reel.musicCoverUrl != null ? reel.musicCoverUrl : "");
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      reel.musicArtist != null && !reel.musicArtist.isEmpty()
            ? reel.musicArtist : (reel.ownerName != null ? reel.ownerName : ""));

        // ── Original Audio: pass the clean extracted audio URL (highest priority) ──
        // originalAudioUrl is set by ReelUploadActivity after upload completes.
        // SoundDetailActivity will use this URL for play — no video stream needed.
        if (reel.originalAudioUrl != null && !reel.originalAudioUrl.isEmpty()) {
            i.putExtra(SoundDetailActivity.EXTRA_ORIGINAL_AUDIO_URL, reel.originalAudioUrl);
        } else {
            // Fallback: pass reel's own videoUrl so SoundDetailActivity can play audio from it
            i.putExtra("reel_video_url", reel.videoUrl != null ? reel.videoUrl : "");
        }
        startActivity(i);
    }

    private void openBookmarkCollections() {
        if (!isAdded() || getActivity() == null) return;
        Intent i = new Intent(getActivity(), ReelBookmarkCollectionsActivity.class);
        if (reel != null) i.putExtra("reel_id", reel.reelId);
        startActivity(i);
    }

    private void openCollabRequest() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), ReelCollabRequestActivity.class);
        i.putExtra("reel_id",    reel.reelId);
        i.putExtra("owner_uid",  reel.uid);
        i.putExtra("owner_name", reel.ownerName);
        startActivity(i);
    }

    private void openReelQRCode() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), ReelQRCodeActivity.class);
        i.putExtra("reel_id",  reel.reelId);
        i.putExtra("reel_url", com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/" + reel.reelId);
        startActivity(i);
    }

    private void openPinnedComments() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), ReelPinnedCommentsActivity.class);
        i.putExtra("reel_id", reel.reelId);
        startActivity(i);
    }

    private void openShareToStory() {
        if (!isAdded() || getActivity() == null || reel == null) return;
        Intent i = new Intent(getActivity(), ReelShareToStoryActivity.class);
        i.putExtra("reel_id",     reel.reelId);
        i.putExtra("reel_url",    reel.videoUrl);
        i.putExtra("reel_thumb",  reel.thumbnailUrl);
        i.putExtra("owner_name",  reel.ownerName);
        startActivity(i);
    }

    // ── Firebase listeners ────────────────────────────────────────────────

    private void startFirebaseListeners() {
        loadLiveReactionCounts(); // Feature 12
        String myUid = safeMyUid();
        if (myUid == null || reel == null || reel.reelId == null) return;

        // Like status
        likeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!isAdded() || getContext() == null) return;
                isLiked = s.exists();
                btnLike.setImageResource(isLiked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getReelLikesRef(reel.reelId).child(myUid).addValueEventListener(likeListener);

        // Save status
        saveListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!isAdded() || getContext() == null) return;
                isSaved = s.exists();
                if (btnSave != null) btnSave.setImageResource(isSaved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getReelSavesRef(myUid).child(reel.reelId).addValueEventListener(saveListener);

        // Follow status
        followListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!isAdded() || getContext() == null) return;
                isFollowing = s.exists();
                updateFollowUI(isFollowing);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        if (reel.uid != null && !reel.uid.equals(myUid)) {
            FirebaseUtils.getReelFollowsRef(myUid).child(reel.uid).addValueEventListener(followListener);
        }

        // FIX #5: Repost status listener
        repostListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!isAdded() || getContext() == null) return;
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

        // Live counts (includes repostCount)
        countListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!isAdded() || getContext() == null) return;
                Long likes    = s.child("likesCount").getValue(Long.class);
                Long comments = s.child("commentsCount").getValue(Long.class);
                Long shares   = s.child("sharesCount").getValue(Long.class);
                Long views    = s.child("viewsCount").getValue(Long.class);
                Long reposts  = s.child("repostCount").getValue(Long.class);  // FIX #5
                if (likes    != null) tvLikesCount.setText(formatCount(likes.intValue()));
                if (comments != null) tvCommentsCount.setText(formatCount(comments.intValue()));
                if (shares   != null) tvSharesCount.setText(formatCount(shares.intValue()));
                if (reposts  != null && tvRepostCount != null)
                    tvRepostCount.setText(formatCount(reposts.intValue()));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getReelsRef().child(reel.reelId).addValueEventListener(countListener);
    }

    private void removeFirebaseListeners() {
        removeLiveReactionsListener(); // Feature 12
        String myUid = safeMyUid();
        if (reel == null || reel.reelId == null) return;

        if (likeListener != null && myUid != null)
            FirebaseUtils.getReelLikesRef(reel.reelId).child(myUid).removeEventListener(likeListener);
        if (saveListener != null && myUid != null)
            FirebaseUtils.getReelSavesRef(myUid).child(reel.reelId).removeEventListener(saveListener);
        if (followListener != null && myUid != null && reel.uid != null)
            FirebaseUtils.getReelFollowsRef(myUid).child(reel.uid).removeEventListener(followListener);
        if (countListener != null)
            FirebaseUtils.getReelsRef().child(reel.reelId).removeEventListener(countListener);

        // FIX #5: remove repost listener
        if (repostListener != null && myUid != null)
            FirebaseUtils.db().getReference("reelReposts")
                .child(reel.reelId).child(myUid)
                .removeEventListener(repostListener);

        // Remove likers avatar listener
        if (likersListener != null && reel != null && reel.reelId != null)
            FirebaseUtils.getReelLikesRef(reel.reelId)
                .removeEventListener(likersListener);
        likersListener = null;
    }

    // ── View count ────────────────────────────────────────────────────────

    private void recordView() {
        String myUid = safeMyUid();
        if (myUid == null || reel == null || reel.reelId == null) return;

        DatabaseReference viewRef = FirebaseUtils.getReelViewsRef(reel.reelId).child(myUid);
        viewRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (s.exists()) return;
                viewRef.setValue(true);
                FirebaseUtils.getReelsRef().child(reel.reelId).child("viewsCount")
                    .runTransaction(new Transaction.Handler() {
                        @NonNull @Override
                        public Transaction.Result doTransaction(@NonNull MutableData d) {
                            Integer c = d.getValue(Integer.class);
                            d.setValue(c != null ? c + 1 : 1);
                            return Transaction.success(d);
                        }
                        @Override public void onComplete(@Nullable DatabaseError e, boolean b,
                                                         @Nullable DataSnapshot sn) {}
                    });
                FirebaseUtils.getReelWatchHistoryRef(myUid).child(reel.reelId)
                    .setValue(System.currentTimeMillis());
                FirebaseUtils.getReelWatchProgressRef(myUid).child(reel.reelId)
                    .setValue(0);

                // Write "🎬 Watched your reel" bubble into owner's chat with viewer.
                // Only fires on first view (s.exists() guard above ensures this).
                com.callx.app.utils.ReelSeenTracker.writeReelSeenToChat(
                        reel.uid,
                        reel.reelId,
                        reel.thumbUrl != null ? reel.thumbUrl : reel.thumbnailUrl);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @Nullable
    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { return null; }
    }


    /**
     * Safe show for BottomSheetDialogFragment.
     * DialogFragment MUST be shown via sheet.show(fm, tag) — not beginTransaction().add().
     * show() sets internal flags (mDismissed=false, mShownByMe=true) required for
     * the dialog window to actually appear.
     */
    private void showBottomSheet(androidx.fragment.app.DialogFragment sheet, String tag) {
        if (!isAdded()) return;
        androidx.fragment.app.FragmentManager fm = getChildFragmentManager();
        if (fm.isDestroyed()) return;
        androidx.fragment.app.Fragment existing = fm.findFragmentByTag(tag);
        if (existing != null) {
            try {
                fm.beginTransaction().remove(existing).commitAllowingStateLoss();
                fm.executePendingTransactions();
            } catch (Exception ignored) {}
        }
        try {
            sheet.show(fm, tag);
        } catch (Exception e) {
            try { sheet.showNow(fm, tag); } catch (Exception ignored) {}
        }
    }

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    private int dpToPx(int dp) {
        return (int)(dp * requireContext().getResources().getDisplayMetrics().density);
    }

    // ── Feature 11: Music disc rotation ────────────────────────────────────

    private void startDiscAnimation() {
        if (ivMusicDisc == null) return;
        if (discAnimator != null) { discAnimator.cancel(); discAnimator = null; }
        discAnimator = ObjectAnimator.ofFloat(ivMusicDisc, "rotation", 0f, 360f);
        discAnimator.setDuration(3000);
        discAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        discAnimator.setRepeatMode(ObjectAnimator.RESTART);
        discAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        discAnimator.start();
    }

    private void stopDiscAnimation() {
        if (discAnimator != null) { discAnimator.pause(); }
    }

    // ── Feature 12: Live reaction counts ─────────────────────────────────

    /**
     * Loads aggregated emoji reaction counts from
     * reelReactions/{reelId}/{uid} = "emoji"
     * and shows top-3 as pill chips in layout_live_reactions.
     */
    private void loadLiveReactionCounts() {
        if (reel == null || reel.reelId == null || layoutLiveReactions == null) return;
        DatabaseReference ref = FirebaseDatabase.getInstance()
            .getReference("reelReactions").child(reel.reelId);
        reactionsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded() || getContext() == null) return;
                java.util.Map<String,Integer> counts = new java.util.LinkedHashMap<>();
                for (DataSnapshot s : snap.getChildren()) {
                    String emoji = s.getValue(String.class);
                    if (emoji != null) counts.merge(emoji, 1, Integer::sum);
                }
                // Sort by count desc, take top 3
                java.util.List<java.util.Map.Entry<String,Integer>> list = new java.util.ArrayList<>(counts.entrySet());
                list.sort((a,b) -> b.getValue().compareTo(a.getValue()));
                displayLiveReactions(list.subList(0, Math.min(3, list.size())));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        ref.addValueEventListener(reactionsListener);
    }

    private void displayLiveReactions(java.util.List<java.util.Map.Entry<String,Integer>> top) {
        if (layoutLiveReactions == null || !isAdded() || getContext() == null) return;
        layoutLiveReactions.removeAllViews();
        if (top.isEmpty()) { layoutLiveReactions.setVisibility(View.GONE); return; }
        layoutLiveReactions.setVisibility(View.VISIBLE);
        int dp4 = dpToPx(4); int dp8 = dpToPx(8);
        for (java.util.Map.Entry<String,Integer> entry : top) {
            TextView chip = new TextView(requireContext());
            chip.setText(entry.getKey() + " " + formatCount(entry.getValue()));
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(12f);
            chip.setBackgroundResource(R.drawable.bg_music_ticker); // reuse pill bg
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp8, 0);
            chip.setLayoutParams(lp);
            chip.setPadding(dp8, dp4, dp8, dp4);
            layoutLiveReactions.addView(chip);
        }
    }

    private void removeLiveReactionsListener() {
        if (reactionsListener != null && reel != null && reel.reelId != null)
            FirebaseDatabase.getInstance().getReference("reelReactions")
                .child(reel.reelId).removeEventListener(reactionsListener);
        reactionsListener = null;
    }

    // ── Inner class: double-tap gesture ──────────────────────────────────

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
    // ── Block reel owner ──────────────────────────────────────────────────────
    private void blockReelOwner() {
        if (currentReel == null || currentReel.uid == null) return;
        String myUid = com.callx.app.utils.FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty() || myUid.equals(currentReel.uid)) return;

        String ownerName = currentReel.ownerName != null ? currentReel.ownerName : "this user";
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Block " + ownerName + "?")
            .setMessage("They won't be able to see your reels or contact you. Their content will be hidden from your feed.")
            .setPositiveButton("Block", (d, w) -> {
                com.callx.app.utils.FirebaseUtils.getBlocksRef(myUid)
                    .child(currentReel.uid).setValue(true)
                    .addOnSuccessListener(v -> {
                        if (getContext() != null)
                            android.widget.Toast.makeText(getContext(),
                                ownerName + " blocked", android.widget.Toast.LENGTH_SHORT).show();
                        // Remove this reel from feed
                        blockedUids.add(currentReel.uid);
                        List<com.callx.app.models.ReelModel> filtered = new java.util.ArrayList<>();
                        for (com.callx.app.models.ReelModel r : allReels)
                            if (!blockedUids.contains(r.uid)) filtered.add(r);
                        allReels.clear(); allReels.addAll(filtered);
                        if (adapter != null) adapter.setReels(allReels);
                    });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

}