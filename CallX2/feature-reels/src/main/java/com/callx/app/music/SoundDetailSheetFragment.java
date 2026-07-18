package com.callx.app.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.widget.NestedScrollView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ReelFirebaseUtils;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * SoundDetailSheetFragment — Instagram-style BottomSheetDialogFragment.
 *
 * SoundDetailActivity ka exact same content neche se slide-up sheet mein
 * dikhata hai. Instagram bhi yahi karta hai — fullscreen BottomSheetDialogFragment:
 *   fitToContents = false
 *   expandedOffset = 0       → poori screen cover karta hai
 *   STATE_EXPANDED by default → open hote hi fullscreen
 *   skipCollapsed = true     → swipe down se seedha dismiss
 *
 * Usage:
 *   SoundDetailSheetFragment sheet = SoundDetailSheetFragment.newInstance(
 *       soundId, soundTitle, artist, coverUrl, soundUrl, durationMs);
 *   sheet.show(getSupportFragmentManager(), "sound_detail_full");
 */
public class SoundDetailSheetFragment extends BottomSheetDialogFragment implements Player.Listener {

    // ── Argument keys ─────────────────────────────────────────────────────────
    private static final String ARG_SOUND_ID    = "sound_id";
    private static final String ARG_TITLE       = "title";
    private static final String ARG_ARTIST      = "artist";
    private static final String ARG_COVER_URL   = "cover_url";
    private static final String ARG_SOUND_URL   = "sound_url";
    private static final String ARG_DURATION_MS = "duration_ms";
    private static final String ARG_GENRE       = "genre";
    private static final String ARG_BPM         = "bpm";
    private static final String ARG_CREATOR_UID = "creator_uid";
    private static final String ARG_PREVIEW_URL = "preview_audio_url";

    private static final int REELS_PAGE_SIZE = 12;

    // ── State ─────────────────────────────────────────────────────────────────
    private String  soundId, soundTitle, soundUrl, artist, coverUrl, genre;
    private String  previewAudioUrl;
    private int     durationMs, bpm;
    private boolean isSaved         = false;
    private boolean isPlaying       = false;
    private boolean isPreparing     = false;
    private boolean userSeeking     = false;
    private boolean retried         = false;
    private int     musicStartMs    = 0;
    private int     musicEndMs      = 0;
    private boolean miniPlayerActive = false;

    // ── Creator ───────────────────────────────────────────────────────────────
    private String creatorUid, creatorName, creatorPhoto;

    // ── Pagination ────────────────────────────────────────────────────────────
    private String  lastReelKey        = null;
    private boolean isLoadingMoreReels = false;
    private boolean hasMoreReels       = true;
    private com.google.firebase.database.ChildEventListener soundReelsLiveListener = null;

    // ── Views ─────────────────────────────────────────────────────────────────
    private ImageButton  btnBack, btnShare, btnSaveSound, btnMore;
    private ImageButton  btnPlayPause;
    private TextView     tvSoundTitle, tvArtist, tvDuration, tvReelCount,
                         tvTrendingRank, tvSavesCount, tvBpm, tvGenre,
                         tvOriginalBadge, tvIsVerified;
    private TextView     btnUseSoundCamera, btnUseSoundGallery, btnTrimStart, btnAddToProfile;
    private ImageView    ivSoundCover, ivDiscRing;
    private RecyclerView rvReels, rvRelated;
    private ProgressBar  progressBar, progressReelsPagination;
    private View         layoutSoundInfo, layoutReelsHeader;
    private LinearLayout layoutWaveform;
    private SeekBar      seekBar;
    private TextView     tvCurrentTime, tvTotalTime;
    private ShimmerFrameLayout shimmerLayout;
    private LinearLayout layoutCreator;
    private View         dividerCreator;
    private ImageView    ivCreatorAvatar;
    private TextView     tvCreatorName;
    private View         layoutMiniPlayer;
    private ImageView    ivMiniCover;
    private TextView     tvMiniTitle;
    private ImageButton  btnMiniPlayPause, btnMiniClose;
    private View         layoutFloatingActions;
    private TextView     btnFloatingUseAudio, btnFloatingSave;
    private NestedScrollView scrollSoundDetail;

    // ── Data ──────────────────────────────────────────────────────────────────
    private final List<SoundDetailActivity.ReelThumbItem> reelItems    = new ArrayList<>();
    private final List<SoundDetailActivity.RelatedItem>   relatedItems = new ArrayList<>();
    private SoundDetailActivity.ReelThumbAdapter reelThumbAdapter;

    // ── Player ────────────────────────────────────────────────────────────────
    private ExoPlayer exoPlayer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler seekHandler = new Handler(Looper.getMainLooper());

    // ── Animations ────────────────────────────────────────────────────────────
    private ObjectAnimator            discAnimator;
    private final List<ValueAnimator> waveAnimators = new ArrayList<>();

    // ── Seek runnable ─────────────────────────────────────────────────────────
    private final Runnable seekUpdateRunnable = new Runnable() {
        @Override public void run() {
            if (exoPlayer != null && isPlaying && !userSeeking) {
                long pos = exoPlayer.getCurrentPosition();
                long dur = exoPlayer.getDuration();
                if (dur > 0 && seekBar != null)
                    seekBar.setProgress((int)(100L * pos / dur));
                if (tvCurrentTime != null) tvCurrentTime.setText(formatMs((int) pos));
            }
            if (isPlaying) seekHandler.postDelayed(this, 300);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Factory
    // ─────────────────────────────────────────────────────────────────────────

    public static SoundDetailSheetFragment newInstance(
            String soundId, String title, String artist,
            String coverUrl, String soundUrl, int durationMs) {
        return newInstance(soundId, title, artist, coverUrl, soundUrl, durationMs,
                null, 0, null, null);
    }

    public static SoundDetailSheetFragment newInstance(
            String soundId, String title, String artist,
            String coverUrl, String soundUrl, int durationMs,
            String genre, int bpm, String creatorUid, String previewAudioUrl) {
        SoundDetailSheetFragment f = new SoundDetailSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SOUND_ID,    soundId    != null ? soundId    : "");
        b.putString(ARG_TITLE,       title      != null ? title      : "");
        b.putString(ARG_ARTIST,      artist     != null ? artist     : "");
        b.putString(ARG_COVER_URL,   coverUrl   != null ? coverUrl   : "");
        b.putString(ARG_SOUND_URL,   soundUrl   != null ? soundUrl   : "");
        b.putInt   (ARG_DURATION_MS, durationMs);
        b.putString(ARG_GENRE,       genre      != null ? genre      : "");
        b.putInt   (ARG_BPM,         bpm);
        b.putString(ARG_CREATOR_UID, creatorUid != null ? creatorUid : "");
        b.putString(ARG_PREVIEW_URL, previewAudioUrl != null ? previewAudioUrl : "");
        f.setArguments(b);
        return f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BottomSheet: fullscreen config — Instagram ka yehi approach hai
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onStart() {
        super.onStart();
        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        if (dialog == null) return;
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;

        // Sheet ki height poori screen ke barabar rakho (behavior height control karega)
        sheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        sheet.requestLayout();

        int screenH = requireContext().getResources().getDisplayMetrics().heightPixels;

        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setFitToContents(false);

        // ── 60% pe open → user scroll kare → 80% tak expand ──
        // peekHeight  = screen ka 60%  → initial collapsed state
        // expandedOffset = screen ka 20% → expanded state = 80% height
        behavior.setPeekHeight((int)(screenH * 0.60f), true);
        behavior.setExpandedOffset((int)(screenH * 0.20f));

        behavior.setSkipCollapsed(false);  // 60% state skip mat karo
        behavior.setState(BottomSheetBehavior.STATE_COLLAPSED); // pehle 60% dikhao
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_sound_detail_full, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Args extract karo
        Bundle b = getArguments();
        if (b != null) {
            soundId         = b.getString(ARG_SOUND_ID, "");
            soundTitle      = b.getString(ARG_TITLE, "");
            artist          = b.getString(ARG_ARTIST, "");
            coverUrl        = b.getString(ARG_COVER_URL, "");
            soundUrl        = b.getString(ARG_SOUND_URL, "");
            durationMs      = b.getInt(ARG_DURATION_MS, 0);
            genre           = b.getString(ARG_GENRE, "");
            bpm             = b.getInt(ARG_BPM, 0);
            creatorUid      = b.getString(ARG_CREATOR_UID, "");
            previewAudioUrl = b.getString(ARG_PREVIEW_URL, "");
        }

        bindViews(view);
        showShimmer(true);
        populateSoundInfo();
        loadSoundData();
        loadReelsForSound();
        loadRelatedSounds();
        loadCreatorProfile();
        setupClickListeners();
        checkIfSaved();
    }

    @Override
    public void onDestroyView() {
        seekHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        stopDiscAnimation();
        stopWaveAnimation();
        releasePlayer();

        // Live listener hatao — memory/network leak rokne ke liye
        if (soundReelsLiveListener != null && soundId != null && !soundId.isEmpty()) {
            try {
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("sounds").child(soundId).child("reels")
                    .removeEventListener(soundReelsLiveListener);
            } catch (Exception ignored) {}
            soundReelsLiveListener = null;
        }
        super.onDestroyView();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews(View v) {
        btnBack           = v.findViewById(R.id.btn_sound_back);
        btnPlayPause      = v.findViewById(R.id.btn_sound_play_pause);
        btnShare          = v.findViewById(R.id.btn_sound_share);
        btnMore           = v.findViewById(R.id.btn_sound_more);
        tvSoundTitle      = v.findViewById(R.id.tv_sound_title);
        tvArtist          = v.findViewById(R.id.tv_sound_artist);
        tvDuration        = v.findViewById(R.id.tv_sound_duration);
        tvReelCount       = v.findViewById(R.id.tv_sound_reel_count);
        tvTrendingRank    = v.findViewById(R.id.tv_sound_trending_rank);
        tvSavesCount      = v.findViewById(R.id.tv_sound_saves_count);
        tvBpm             = v.findViewById(R.id.tv_sound_bpm);
        tvGenre           = v.findViewById(R.id.tv_sound_genre);
        tvOriginalBadge   = v.findViewById(R.id.tv_sound_original_badge);
        tvIsVerified      = v.findViewById(R.id.tv_sound_verified_badge);
        btnUseSoundCamera = v.findViewById(R.id.btn_use_sound_camera);
        btnUseSoundGallery= v.findViewById(R.id.btn_use_sound_gallery);
        btnTrimStart      = v.findViewById(R.id.btn_trim_start);
        btnAddToProfile   = v.findViewById(R.id.btn_add_to_profile);
        ivSoundCover      = v.findViewById(R.id.iv_sound_cover);
        ivDiscRing        = v.findViewById(R.id.iv_disc_ring);
        btnSaveSound      = v.findViewById(R.id.btn_save_sound);
        rvReels           = v.findViewById(R.id.rv_sound_reels);
        rvRelated         = v.findViewById(R.id.rv_related_sounds);
        progressBar       = v.findViewById(R.id.progress_sound);
        layoutSoundInfo   = v.findViewById(R.id.layout_sound_info);
        layoutWaveform    = v.findViewById(R.id.layout_sound_waveform);
        seekBar           = v.findViewById(R.id.seekbar_sound);
        tvCurrentTime     = v.findViewById(R.id.tv_sound_current_time);
        tvTotalTime       = v.findViewById(R.id.tv_sound_total_time);
        shimmerLayout     = v.findViewById(R.id.shimmer_sound_detail);
        layoutReelsHeader = v.findViewById(R.id.layout_reels_header);
        layoutCreator     = v.findViewById(R.id.layout_sound_creator);
        dividerCreator    = v.findViewById(R.id.divider_creator);
        ivCreatorAvatar   = v.findViewById(R.id.iv_creator_avatar);
        tvCreatorName     = v.findViewById(R.id.tv_creator_name);
        layoutMiniPlayer  = v.findViewById(R.id.layout_mini_player);
        ivMiniCover       = v.findViewById(R.id.iv_mini_cover);
        tvMiniTitle       = v.findViewById(R.id.tv_mini_title);
        btnMiniPlayPause  = v.findViewById(R.id.btn_mini_play_pause);
        btnMiniClose      = v.findViewById(R.id.btn_mini_close);
        layoutFloatingActions = v.findViewById(R.id.layout_floating_sound_actions);
        btnFloatingUseAudio   = v.findViewById(R.id.btn_floating_use_audio);
        btnFloatingSave       = v.findViewById(R.id.btn_floating_save);
        scrollSoundDetail     = v.findViewById(R.id.scroll_sound_detail);

        if (rvReels   != null) rvReels.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        if (rvRelated != null) rvRelated.setLayoutManager(
            new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));

        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && tvCurrentTime != null && exoPlayer != null) {
                        long dur = exoPlayer.getDuration();
                        if (dur > 0) tvCurrentTime.setText(formatMs((int)(dur * progress / 100L)));
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    userSeeking = false;
                    if (exoPlayer != null && !isPreparing) {
                        long dur = exoPlayer.getDuration();
                        if (dur > 0) exoPlayer.seekTo(dur * sb.getProgress() / 100);
                    }
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shimmer helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void showShimmer(boolean show) {
        if (shimmerLayout != null) {
            shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) shimmerLayout.startShimmer();
            else      shimmerLayout.stopShimmer();
        }
        if (layoutSoundInfo   != null) layoutSoundInfo.setVisibility(show ? View.GONE : View.VISIBLE);
        if (layoutReelsHeader != null) layoutReelsHeader.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Populate static info
    // ─────────────────────────────────────────────────────────────────────────

    private void populateSoundInfo() {
        if (tvSoundTitle != null) tvSoundTitle.setText(soundTitle != null ? soundTitle : "Unknown Sound");
        if (tvArtist     != null) tvArtist.setText(artist != null && !artist.isEmpty() ? "• " + artist : "• Original Audio");

        if (durationMs > 0) {
            String dur = formatMs(durationMs);
            if (tvDuration  != null) tvDuration.setText(dur);
            if (tvTotalTime != null) tvTotalTime.setText(dur);
        }

        if (tvBpm != null) {
            if (bpm > 0) { tvBpm.setText(bpm + " BPM"); tvBpm.setVisibility(View.VISIBLE); }
            else           tvBpm.setVisibility(View.GONE);
        }

        if (tvGenre != null) {
            if (genre != null && !genre.isEmpty()) { tvGenre.setText(genre); tvGenre.setVisibility(View.VISIBLE); }
            else tvGenre.setVisibility(View.GONE);
        }

        if (ivSoundCover != null) {
            if (coverUrl != null && !coverUrl.isEmpty()) {
                Glide.with(requireContext()).load(coverUrl)
                    .transform(new CircleCrop())
                    .placeholder(R.drawable.ic_music_note)
                    .override(720, 720)
                    .into(ivSoundCover);
            } else {
                ivSoundCover.setImageResource(R.drawable.ic_music_note);
            }
        }

        buildStaticWaveform();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Waveform animation
    // ─────────────────────────────────────────────────────────────────────────

    private void buildStaticWaveform() {
        if (layoutWaveform == null || !isAdded()) return;
        layoutWaveform.removeAllViews();
        stopWaveAnimation();
        Random rng = new Random(soundTitle != null ? soundTitle.hashCode() : 0);
        float density = requireContext().getResources().getDisplayMetrics().density;
        int barW = (int)(4 * density);
        int gap  = (int)(3 * density);
        for (int i = 0; i < 36; i++) {
            View bar = new View(requireContext());
            int h = (int)((10 + rng.nextInt(30)) * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(barW, h);
            lp.setMargins(gap, 0, gap, 0);
            lp.gravity = android.view.Gravity.BOTTOM;
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(0x44FFFFFF);
            bar.setTag("waveBar");
            layoutWaveform.addView(bar);
        }
    }

    private void startWaveAnimation() {
        if (layoutWaveform == null || !isAdded()) return;
        stopWaveAnimation();
        float density = requireContext().getResources().getDisplayMetrics().density;
        int minH = (int)(8  * density);
        int maxH = (int)(38 * density);
        for (int i = 0; i < layoutWaveform.getChildCount(); i++) {
            final View bar = layoutWaveform.getChildAt(i);
            if (!"waveBar".equals(bar.getTag())) continue;
            bar.setBackgroundColor(0xFFFF3B5C);
            int duration = 400 + (i % 5) * 80;
            ValueAnimator anim = ValueAnimator.ofInt(minH, minH + (int)((maxH - minH) * (0.4f + (i % 7) * 0.08f)));
            anim.setDuration(duration);
            anim.setRepeatMode(ValueAnimator.REVERSE);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            anim.addUpdateListener(a -> {
                if (bar.getParent() == null) return;
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
                lp.height = (int) a.getAnimatedValue();
                bar.setLayoutParams(lp);
            });
            anim.start();
            waveAnimators.add(anim);
        }
    }

    private void stopWaveAnimation() {
        for (ValueAnimator a : waveAnimators) a.cancel();
        waveAnimators.clear();
        if (layoutWaveform != null) {
            for (int i = 0; i < layoutWaveform.getChildCount(); i++) {
                View bar = layoutWaveform.getChildAt(i);
                if ("waveBar".equals(bar.getTag())) bar.setBackgroundColor(0x44FFFFFF);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Disc animation
    // ─────────────────────────────────────────────────────────────────────────

    private void startDiscAnimation() {
        if (ivSoundCover == null) return;
        if (discAnimator != null && discAnimator.isRunning()) return;
        float startAngle = ivSoundCover.getRotation();
        discAnimator = ObjectAnimator.ofFloat(ivSoundCover, "rotation", startAngle, startAngle + 360f);
        discAnimator.setDuration(6000);
        discAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        discAnimator.setRepeatMode(ObjectAnimator.RESTART);
        discAnimator.setInterpolator(new LinearInterpolator());
        discAnimator.addUpdateListener(a -> {
            if (ivDiscRing != null) ivDiscRing.setRotation((float) a.getAnimatedValue());
        });
        discAnimator.start();
    }

    private void stopDiscAnimation() {
        if (discAnimator != null) { discAnimator.cancel(); discAnimator = null; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase data loading
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isGone() { return !isAdded() || getContext() == null; }

    private void loadSoundData() {
        if (soundId == null || soundId.isEmpty()) {
            showShimmer(false);
            updatePlayButtonState();
            return;
        }

        FirebaseUtils.db().getReference("sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) return;

                    Long    count       = snap.child("reel_count").getValue(Long.class);
                    Long    rank        = snap.child("trending_rank").getValue(Long.class);
                    Long    saves       = snap.child("total_saves").getValue(Long.class);
                    Boolean orig        = snap.child("is_original").getValue(Boolean.class);
                    Boolean ver         = snap.child("is_verified").getValue(Boolean.class);
                    Boolean trendingFlg = snap.child("is_trending").getValue(Boolean.class);

                    if (creatorUid == null || creatorUid.isEmpty()) {
                        String uid = snap.child("creatorUid").getValue(String.class);
                        if (uid != null && !uid.isEmpty()) creatorUid = uid;
                    }
                    String denormName  = snap.child("creatorName").getValue(String.class);
                    String denormPhoto = snap.child("creatorPhoto").getValue(String.class);
                    if (denormName  != null && !denormName.isEmpty())  creatorName  = denormName;
                    if (denormPhoto != null && !denormPhoto.isEmpty()) creatorPhoto = denormPhoto;

                    if (creatorUid != null && !creatorUid.isEmpty()
                            && creatorName != null && !creatorName.isEmpty()) {
                        bindCreatorRow(creatorUid, creatorName, creatorPhoto);
                        sortAndApplyReelItems();
                    }

                    if (soundUrl == null || soundUrl.isEmpty()) {
                        String u = snap.child("audioUrl").getValue(String.class);
                        if (u == null) u = snap.child("audio_url").getValue(String.class);
                        if (u == null) u = snap.child("url").getValue(String.class);
                        if (u != null && !u.isEmpty()) soundUrl = u;
                    }

                    if (previewAudioUrl == null || previewAudioUrl.isEmpty()) {
                        String pu = snap.child("previewAudioUrl").getValue(String.class);
                        if (pu != null && !pu.isEmpty()) previewAudioUrl = pu;
                    }

                    if (coverUrl == null || coverUrl.isEmpty()) {
                        String c = snap.child("coverUrl").getValue(String.class);
                        if (c == null) c = snap.child("cover_url").getValue(String.class);
                        if (c != null && !c.isEmpty() && ivSoundCover != null) {
                            coverUrl = c;
                            Glide.with(requireContext()).load(coverUrl)
                                .transform(new CircleCrop())
                                .placeholder(R.drawable.ic_music_note)
                                .override(720, 720)
                                .into(ivSoundCover);
                        }
                    }

                    if (durationMs <= 0) {
                        Long d = snap.child("duration_ms").getValue(Long.class);
                        if (d == null) d = snap.child("durationMs").getValue(Long.class);
                        if (d != null && d > 0) {
                            durationMs = d.intValue();
                            String durStr = formatMs(durationMs);
                            if (tvDuration  != null) tvDuration.setText(durStr);
                            if (tvTotalTime != null) tvTotalTime.setText(durStr);
                        }
                    }

                    if (count == null) count = 0L;
                    if (rank  == null) rank  = 0L;
                    if (saves == null) saves = 0L;

                    if (tvReelCount  != null) tvReelCount.setText(formatCount(count) + " Reels");
                    if (tvSavesCount != null) {
                        tvSavesCount.setText(formatCount(saves) + " Saves");
                        tvSavesCount.setVisibility(View.VISIBLE);
                    }

                    if (tvTrendingRank != null) {
                        if (rank > 0 && rank <= 50) {
                            tvTrendingRank.setVisibility(View.VISIBLE);
                            tvTrendingRank.setText("#" + rank + " Trending");
                        } else if (Boolean.TRUE.equals(trendingFlg)) {
                            tvTrendingRank.setVisibility(View.VISIBLE);
                            tvTrendingRank.setText("🔥 Trending");
                        } else {
                            tvTrendingRank.setVisibility(View.GONE);
                        }
                    }

                    if (tvOriginalBadge != null)
                        tvOriginalBadge.setVisibility(Boolean.TRUE.equals(orig) ? View.VISIBLE : View.GONE);
                    if (tvIsVerified != null)
                        tvIsVerified.setVisibility(Boolean.TRUE.equals(ver) ? View.VISIBLE : View.GONE);

                    showShimmer(false);
                    updatePlayButtonState();
                    if (scrollSoundDetail != null)
                        scrollSoundDetail.post(SoundDetailSheetFragment.this::updateFloatingActionsVisibility);
                }

                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isGone()) { showShimmer(false); updatePlayButtonState(); }
                }
            });
    }

    private void loadRelatedSounds() {
        if (genre == null || genre.isEmpty()) return;
        FirebaseUtils.getMusicLibraryRef()
            .orderByChild("genre").equalTo(genre)
            .limitToFirst(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) return;
                    relatedItems.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        String id    = s.getKey();
                        String title = s.child("title").getValue(String.class);
                        if (title == null) title = s.child("name").getValue(String.class);
                        String art   = s.child("artist").getValue(String.class);
                        String cover = s.child("coverUrl").getValue(String.class);
                        String url   = s.child("audioUrl").getValue(String.class);
                        if (title != null && !title.isEmpty()
                                && (id == null || !id.equals(soundId))) {
                            relatedItems.add(new SoundDetailActivity.RelatedItem(
                                id != null ? id : "", title,
                                art   != null ? art   : "",
                                cover != null ? cover : "",
                                url   != null ? url   : ""));
                        }
                    }
                    if (rvRelated != null && !relatedItems.isEmpty()) {
                        rvRelated.setAdapter(new SoundDetailActivity.RelatedAdapter(relatedItems, item -> {
                            showMiniPlayer();
                            // Related sound bhi sheet me kholte hain
                            SoundDetailSheetFragment next = SoundDetailSheetFragment.newInstance(
                                item.id, item.title, item.artist, item.coverUrl, item.audioUrl, 0,
                                genre, 0, null, null);
                            next.show(requireActivity().getSupportFragmentManager(), "sound_detail_full");
                            dismiss();
                        }));
                        View sec = requireView().findViewById(R.id.layout_related_sounds_section);
                        if (sec != null) sec.setVisibility(View.VISIBLE);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadReelsForSound() {
        if (soundId == null || soundId.isEmpty() || rvReels == null) return;
        reelThumbAdapter = new SoundDetailActivity.ReelThumbAdapter(reelItems, position -> {
            if (isGone()) return;
            ArrayList<String> ids = new ArrayList<>();
            for (SoundDetailActivity.ReelThumbItem r : reelItems) ids.add(r.reelId);
            Intent i = new Intent(requireContext(), SingleReelPlayerActivity.class);
            i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
            i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, position);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SHOW_SOUND_ACTIONS, true);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_ID,    soundId);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_TITLE, soundTitle);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_URL,   soundUrl);
            startActivity(i);
        });
        rvReels.setAdapter(reelThumbAdapter);

        if (scrollSoundDetail != null) {
            scrollSoundDetail.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldX, oldY) -> {
                updateFloatingActionsVisibility();
                if (scrollY <= oldY || rvReels == null || isLoadingMoreReels || !hasMoreReels) return;
                int contentH = v.getChildAt(0) != null ? v.getChildAt(0).getHeight() : 0;
                if (scrollY + v.getHeight() >= contentH - 600) loadMoreReelsForSound();
            });
        }
        loadMoreReelsForSound();
    }

    private void loadMoreReelsForSound() {
        if (isLoadingMoreReels || !hasMoreReels || isGone()) return;
        isLoadingMoreReels = true;
        if (progressReelsPagination != null && lastReelKey != null)
            progressReelsPagination.setVisibility(View.VISIBLE);

        Query q = FirebaseUtils.db().getReference("sounds").child(soundId).child("reels").orderByKey();
        q = (lastReelKey != null) ? q.startAfter(lastReelKey).limitToFirst(REELS_PAGE_SIZE)
                                   : q.limitToFirst(REELS_PAGE_SIZE);

        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isGone()) return;
                List<SoundDetailActivity.ReelThumbItem> page = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) {
                    String rid   = s.getKey();
                    String thumb = s.child("thumbnailUrl").getValue(String.class);
                    if (thumb == null) thumb = s.child("thumbnail").getValue(String.class);
                    String vid   = s.child("videoUrl").getValue(String.class);
                    String uid   = s.child("ownerUid").getValue(String.class);
                    if (rid != null) {
                        SoundDetailActivity.ReelThumbItem item =
                            new SoundDetailActivity.ReelThumbItem(rid,
                                thumb != null ? thumb : "", vid != null ? vid : "");
                        item.uid = uid;
                        page.add(item);
                        lastReelKey = rid;
                    }
                }
                if (page.size() < REELS_PAGE_SIZE) hasMoreReels = false;
                if (page.isEmpty() && reelItems.isEmpty() && lastReelKey == null) {
                    hasMoreReels = false;
                    loadReelsFromReelsNode();
                    return;
                }
                fetchViewCountsForPage(page);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                isLoadingMoreReels = false;
                if (progressReelsPagination != null) progressReelsPagination.setVisibility(View.GONE);
                if (reelItems.isEmpty()) loadReelsFromReelsNode();
            }
        });
    }

    private void loadReelsFromReelsNode() {
        if (soundId == null || isGone()) { isLoadingMoreReels = false; return; }
        FirebaseUtils.db().getReference("reels")
            .orderByChild("musicId").equalTo(soundId)
            .limitToFirst(REELS_PAGE_SIZE)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) { isLoadingMoreReels = false; return; }
                    List<SoundDetailActivity.ReelThumbItem> page = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String rid   = s.getKey();
                        String thumb = s.child("thumbnailUrl").getValue(String.class);
                        if (thumb == null) thumb = s.child("thumbnail").getValue(String.class);
                        String vid   = s.child("videoUrl").getValue(String.class);
                        String uid   = s.child("uid").getValue(String.class);
                        Long   views = s.child("viewsCount").getValue(Long.class);
                        if (rid != null) {
                            SoundDetailActivity.ReelThumbItem item =
                                new SoundDetailActivity.ReelThumbItem(rid,
                                    thumb != null ? thumb : "", vid != null ? vid : "");
                            item.uid        = uid;
                            item.viewsCount = views != null ? views : 0L;
                            page.add(item);
                        }
                    }
                    finishAppendingPage(page, false);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { isLoadingMoreReels = false; }
            });
    }

    private void fetchViewCountsForPage(List<SoundDetailActivity.ReelThumbItem> page) {
        if (page.isEmpty()) { finishAppendingPage(page, false); return; }
        // Thumbnail preloading
        for (SoundDetailActivity.ReelThumbItem item : page) {
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty() && isAdded()) {
                Glide.with(requireContext())
                    .load(item.thumbnailUrl)
                    .apply(new RequestOptions().centerCrop().override(300, 533))
                    .preload();
            }
        }
        final int total = page.size();
        final int[] done = {0};
        for (SoundDetailActivity.ReelThumbItem item : page) {
            FirebaseUtils.getReelsRef().child(item.reelId).child("viewsCount")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Long v = snap.getValue(Long.class);
                        item.viewsCount = v != null ? v : 0L;
                        done[0]++;
                        if (done[0] >= total) finishAppendingPage(page, true);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        done[0]++;
                        if (done[0] >= total) finishAppendingPage(page, true);
                    }
                });
        }
    }

    private void finishAppendingPage(List<SoundDetailActivity.ReelThumbItem> page, boolean needsSort) {
        isLoadingMoreReels = false;
        if (progressReelsPagination != null) progressReelsPagination.setVisibility(View.GONE);
        if (isGone() || page.isEmpty()) return;

        if (needsSort) {
            for (SoundDetailActivity.ReelThumbItem item : page) {
                item.isOriginalCreator = creatorUid != null && !creatorUid.isEmpty()
                    && creatorUid.equals(item.uid);
            }
            page.sort((a, b) -> {
                if (a.isOriginalCreator != b.isOriginalCreator) return a.isOriginalCreator ? -1 : 1;
                return Long.compare(b.viewsCount, a.viewsCount);
            });
        }

        int insertStart = reelItems.size();
        reelItems.addAll(page);
        if (reelThumbAdapter != null) {
            reelThumbAdapter.notifyItemRangeInserted(insertStart, page.size());
            if (rvReels != null) rvReels.post(() -> {
                if (rvReels != null) rvReels.requestLayout();
                if (scrollSoundDetail != null) scrollSoundDetail.requestLayout();
            });
        }
        attachSoundReelsLiveListener();
    }

    private void attachSoundReelsLiveListener() {
        if (soundReelsLiveListener != null || soundId == null || isGone()) return;

        com.google.firebase.database.Query liveQ =
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("sounds").child(soundId).child("reels").orderByKey();
        if (lastReelKey != null) liveQ = liveQ.startAfter(lastReelKey);

        soundReelsLiveListener = new com.google.firebase.database.ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snap, @Nullable String prev) {
                if (isGone()) return;
                String rid   = snap.getKey();
                String thumb = snap.child("thumbnailUrl").getValue(String.class);
                String vid   = snap.child("videoUrl").getValue(String.class);
                String uid   = snap.child("ownerUid").getValue(String.class);
                if (rid == null) return;
                for (SoundDetailActivity.ReelThumbItem existing : reelItems) {
                    if (rid.equals(existing.reelId)) return;
                }
                SoundDetailActivity.ReelThumbItem item = new SoundDetailActivity.ReelThumbItem(
                    rid, thumb != null ? thumb : "", vid != null ? vid : "");
                item.uid = uid;
                reelItems.add(0, item);
                if (reelThumbAdapter != null) {
                    reelThumbAdapter.notifyItemInserted(0);
                    if (rvReels != null) rvReels.post(() -> rvReels.scrollToPosition(0));
                }
                lastReelKey = rid;
            }
            @Override public void onChildChanged(@NonNull DataSnapshot s, @Nullable String p) {}
            @Override public void onChildRemoved(@NonNull DataSnapshot snap) {
                if (isGone()) return;
                String rid = snap.getKey();
                for (int i = 0; i < reelItems.size(); i++) {
                    if (rid != null && rid.equals(reelItems.get(i).reelId)) {
                        reelItems.remove(i);
                        if (reelThumbAdapter != null) reelThumbAdapter.notifyItemRemoved(i);
                        break;
                    }
                }
            }
            @Override public void onChildMoved(@NonNull DataSnapshot s, @Nullable String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        liveQ.addChildEventListener(soundReelsLiveListener);
    }

    private void sortAndApplyReelItems() {
        if (isGone()) return;
        if (creatorUid == null || creatorUid.isEmpty() || reelItems.isEmpty()) return;
        boolean changed = false;
        for (SoundDetailActivity.ReelThumbItem item : reelItems) {
            boolean isOrig = creatorUid.equals(item.uid);
            if (isOrig != item.isOriginalCreator) changed = true;
            item.isOriginalCreator = isOrig;
        }
        if (!changed) return;
        reelItems.sort((a, b) -> {
            if (a.isOriginalCreator != b.isOriginalCreator) return a.isOriginalCreator ? -1 : 1;
            return 0;
        });
        if (reelThumbAdapter != null) reelThumbAdapter.notifyDataSetChanged();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Creator profile
    // ─────────────────────────────────────────────────────────────────────────

    private void loadCreatorProfile() {
        if (creatorUid != null && !creatorUid.isEmpty()
                && creatorName != null && !creatorName.isEmpty()) {
            bindCreatorRow(creatorUid, creatorName, creatorPhoto);
            return;
        }
        if (creatorUid != null && !creatorUid.isEmpty()) {
            fetchCreatorUserData(creatorUid);
            return;
        }
        if (soundId == null || soundId.isEmpty()) return;

        FirebaseUtils.db().getReference("sounds").child(soundId).child("creatorUid")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) return;
                    String uid = snap.getValue(String.class);
                    if (uid != null && !uid.isEmpty()) {
                        creatorUid = uid;
                        fetchCreatorUserData(uid);
                        sortAndApplyReelItems();
                    } else {
                        FirebaseUtils.getMusicLibraryRef().child(soundId).child("creatorUid")
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot s2) {
                                    if (isGone()) return;
                                    String uid2 = s2.getValue(String.class);
                                    if (uid2 != null && !uid2.isEmpty()) {
                                        creatorUid = uid2;
                                        fetchCreatorUserData(uid2);
                                        sortAndApplyReelItems();
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void fetchCreatorUserData(String uid) {
        ReelFirebaseUtils.reelUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) return;
                    String name = snap.child("displayName").getValue(String.class);
                    if (name == null || name.isEmpty()) name = snap.child("handle").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    if (photo == null || photo.isEmpty()) photo = snap.child("thumbUrl").getValue(String.class);
                    if ((name != null && !name.isEmpty()) || (photo != null && !photo.isEmpty())) {
                        creatorName  = (name != null && !name.isEmpty()) ? name : "Unknown";
                        creatorPhoto = photo;
                        bindCreatorRow(uid, creatorName, photo);
                    } else {
                        fetchCreatorFromMainUsersNode(uid);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    fetchCreatorFromMainUsersNode(uid);
                }
            });
    }

    private void fetchCreatorFromMainUsersNode(String uid) {
        FirebaseUtils.getUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) return;
                    String name = snap.child("displayName").getValue(String.class);
                    if (name == null || name.isEmpty()) name = snap.child("username").getValue(String.class);
                    if (name == null || name.isEmpty()) name = snap.child("name").getValue(String.class);
                    if (name == null || name.isEmpty()) name = "Unknown";
                    String photo = snap.child("photoUrl").getValue(String.class);
                    if (photo == null || photo.isEmpty()) photo = snap.child("profilePic").getValue(String.class);
                    if (photo == null || photo.isEmpty()) photo = snap.child("avatar").getValue(String.class);
                    creatorName  = name;
                    creatorPhoto = photo;
                    bindCreatorRow(uid, name, photo);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void bindCreatorRow(final String uid, final String name, final String photo) {
        if (layoutCreator == null || tvCreatorName == null || isGone()) return;
        tvCreatorName.setText("@" + name);
        if (photo != null && !photo.isEmpty() && ivCreatorAvatar != null) {
            Glide.with(requireContext()).load(photo)
                .transform(new CircleCrop())
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .override(720, 720)
                .into(ivCreatorAvatar);
        } else if (ivCreatorAvatar != null) {
            ivCreatorAvatar.setImageResource(R.drawable.ic_person);
        }
        layoutCreator.setVisibility(View.VISIBLE);
        if (dividerCreator != null) dividerCreator.setVisibility(View.VISIBLE);
        layoutCreator.setOnClickListener(v -> openUserProfile(uid, name, photo));
    }

    private void openUserProfile(String uid, String name, String photo) {
        if (uid == null || uid.isEmpty() || isGone()) return;
        try {
            Intent i = new Intent()
                .setClassName(requireContext(), "com.callx.app.activities.UserProfileActivity");
            i.putExtra("uid",   uid);
            i.putExtra("name",  name  != null ? name  : "");
            i.putExtra("photo", photo != null ? photo : "");
            startActivity(i);
        } catch (Exception ex) {
            android.util.Log.w("SoundDetailSheet", "UserProfileActivity not found", ex);
        }
    }

    private void checkIfSaved() {
        String uid = null;
        try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
        if (uid == null || soundId == null) return;
        FirebaseUtils.getUserRef(uid).child("saved_sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    isSaved = snap.exists();
                    updateSaveButton();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Click listeners
    // ─────────────────────────────────────────────────────────────────────────

    private void setupClickListeners() {
        // X button → sheet dismiss karo
        if (btnBack != null) btnBack.setOnClickListener(v -> dismiss());

        if (btnShare != null) btnShare.setOnClickListener(v -> shareSound());

        if (btnMore != null) {
            btnMore.setOnClickListener(v -> {
                if (isGone()) return;
                PopupMenu popup = new PopupMenu(requireContext(), btnMore);
                popup.getMenu().add(0, 1, 0, "Report sound");
                popup.getMenu().add(0, 2, 1, "Add to playlist");
                popup.getMenu().add(0, 3, 2, "Copy link");
                popup.getMenu().add(0, 4, 3, "Not interested");
                popup.getMenu().add(0, 7, 4, "🔍 Search Sounds");
                popup.getMenu().add(0, 8, 5, "🎚 Remix this Sound");

                String myUid = FirebaseAuth.getInstance().getUid();
                boolean isMySound = myUid != null && creatorUid != null && myUid.equals(creatorUid);
                if (isMySound) {
                    popup.getMenu().add(0, 5, 6, "Upload Sound");
                    popup.getMenu().add(0, 6, 7, "View Analytics");
                }

                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 2:
                            Intent pi = new Intent(requireContext(), SoundPlaylistActivity.class);
                            pi.putExtra("sound_id",    soundId);
                            pi.putExtra("sound_title", soundTitle);
                            pi.putExtra("sound_url",   soundUrl);
                            startActivity(pi);
                            return true;
                        case 3:
                            shareSound();
                            return true;
                        case 5:
                            Intent ui = new Intent(requireContext(), SoundUploadActivity.class);
                            ui.putExtra("sound_id",    soundId);
                            ui.putExtra("sound_title", soundTitle);
                            startActivity(ui);
                            return true;
                        case 6:
                            Intent ai = new Intent(requireContext(), SoundAnalyticsActivity.class);
                            ai.putExtra(SoundAnalyticsActivity.EXTRA_SOUND_ID, soundId);
                            ai.putExtra(SoundAnalyticsActivity.EXTRA_SOUND_TITLE, soundTitle);
                            startActivity(ai);
                            return true;
                        case 7:
                            startActivity(new Intent(requireContext(), SoundSearchActivity.class));
                            return true;
                        case 8:
                            Intent ri = new Intent(requireContext(), SoundRemixActivity.class);
                            ri.putExtra(SoundRemixActivity.EXTRA_SOUND_A_ID,    soundId    != null ? soundId    : "");
                            ri.putExtra(SoundRemixActivity.EXTRA_SOUND_A_TITLE, soundTitle != null ? soundTitle : "");
                            ri.putExtra(SoundRemixActivity.EXTRA_SOUND_A_URL,   soundUrl   != null ? soundUrl   : "");
                            ri.putExtra(SoundRemixActivity.EXTRA_SOUND_A_COVER, coverUrl   != null ? coverUrl   : "");
                            ri.putExtra(SoundRemixActivity.EXTRA_SOUND_A_ARTIST,artist     != null ? artist     : "");
                            startActivity(ri);
                            return true;
                        default:
                            return true;
                    }
                });
                popup.show();
            });
        }

        if (btnSaveSound != null) {
            btnSaveSound.setOnClickListener(v -> toggleSave());
            btnSaveSound.setOnLongClickListener(v -> {
                startActivity(new Intent(requireContext(), SavedSoundsActivity.class));
                return true;
            });
        }

        if (btnFloatingSave    != null) btnFloatingSave.setOnClickListener(v -> toggleSave());
        if (btnFloatingUseAudio!= null) btnFloatingUseAudio.setOnClickListener(v -> showUseAudioDialog());

        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());

        if (btnUseSoundCamera != null) btnUseSoundCamera.setOnClickListener(v -> {
            if (isGone()) return;
            Intent i = new Intent(requireContext(), com.callx.app.camera.ReelCameraActivity.class);
            i.putExtra("selected_sound_id",       soundId);
            i.putExtra("selected_sound_title",     soundTitle);
            i.putExtra("selected_sound_url",       soundUrl);
            i.putExtra("replace_audio_with_sound", true);
            startActivity(i);
            dismiss();
        });

        if (btnUseSoundGallery != null) btnUseSoundGallery.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            pick.setType("video/*");
            // Gallery picker result → editor open karo
            requireActivity().startActivityForResult(pick, 701);
        });

        if (btnTrimStart != null) btnTrimStart.setVisibility(View.GONE);

        // Add to profile
        String myUid = FirebaseAuth.getInstance().getUid();
        if (btnAddToProfile != null && myUid != null) {
            btnAddToProfile.setVisibility(View.VISIBLE);
            btnAddToProfile.setOnClickListener(v -> {
                java.util.Map<String, Object> songData = new java.util.HashMap<>();
                songData.put("soundId",    soundId    != null ? soundId    : "");
                songData.put("title",      soundTitle != null ? soundTitle : "");
                songData.put("artist",     artist     != null ? artist     : "");
                songData.put("coverUrl",   coverUrl   != null ? coverUrl   : "");
                songData.put("soundUrl",   soundUrl   != null ? soundUrl   : "");
                songData.put("durationMs", durationMs);
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("reels/users").child(myUid)
                    .child("profileSong").setValue(songData)
                    .addOnSuccessListener(unused -> {
                        if (btnAddToProfile != null) btnAddToProfile.setText("✓  Added to profile");
                        if (isAdded()) Toast.makeText(requireContext(), "Song added to your profile!", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> {
                        if (isAdded()) Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
            });
        }

        if (btnMiniPlayPause != null) btnMiniPlayPause.setOnClickListener(v -> togglePlayPause());
        if (btnMiniClose     != null) btnMiniClose.setOnClickListener(v -> {
            pausePlayback();
            hideMiniPlayer();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mini-player
    // ─────────────────────────────────────────────────────────────────────────

    private void showMiniPlayer() {
        if (layoutMiniPlayer == null || miniPlayerActive || isGone()) return;
        miniPlayerActive = true;
        if (tvMiniTitle != null) tvMiniTitle.setText(soundTitle != null ? soundTitle : "Now Playing");
        if (ivMiniCover != null && coverUrl != null && !coverUrl.isEmpty()) {
            Glide.with(requireContext()).load(coverUrl)
                .transform(new CircleCrop())
                .placeholder(R.drawable.ic_music_note)
                .override(720, 720)
                .into(ivMiniCover);
        }
        layoutMiniPlayer.setVisibility(View.VISIBLE);
        updateMiniPlayButton();
    }

    private void hideMiniPlayer() {
        if (layoutMiniPlayer == null) return;
        miniPlayerActive = false;
        layoutMiniPlayer.setVisibility(View.GONE);
    }

    private void updateMiniPlayButton() {
        if (btnMiniPlayPause == null) return;
        btnMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Playback — ExoPlayer (Media3)
    // ─────────────────────────────────────────────────────────────────────────

    private String getPlaybackUrl() {
        if (previewAudioUrl != null && !previewAudioUrl.isEmpty()) return previewAudioUrl;
        return soundUrl;
    }

    private void togglePlayPause() {
        String url = getPlaybackUrl();
        if (url == null || url.isEmpty()) {
            if (isAdded()) Toast.makeText(requireContext(), "Loading audio…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isPreparing) return;
        if (isPlaying) pausePlayback();
        else {
            if (exoPlayer == null) initAndStartPlayer();
            else                   resumePlayback();
        }
    }

    private void initAndStartPlayer() {
        if (isGone()) return;
        isPreparing = true;
        setPlayButtonLoading(true);

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(requireContext());
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true));

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 12_000, 1_500, 2_000)
            .build();

        exoPlayer = new ExoPlayer.Builder(requireContext())
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build();
        exoPlayer.addListener(this);
        exoPlayer.setMediaItem(MediaItem.fromUri(getPlaybackUrl()));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.prepare();
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY && isPreparing) {
            isPreparing = false;
            setPlayButtonLoading(false);
            long dur = exoPlayer.getDuration();
            if (dur > 0 && tvTotalTime != null) tvTotalTime.setText(formatMs((int) dur));
            exoPlayer.play();
            isPlaying = true;
            if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
            startWaveAnimation();
            startDiscAnimation();
            seekHandler.post(seekUpdateRunnable);
            updateMiniPlayButton();
        } else if (state == Player.STATE_ENDED) {
            pausePlayback();
            if (seekBar != null) seekBar.setProgress(0);
            if (tvCurrentTime != null) tvCurrentTime.setText("0:00");
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        releasePlayer();
        setPlayButtonLoading(false);
        isPlaying = false; isPreparing = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation();
        stopDiscAnimation();
        seekHandler.removeCallbacks(seekUpdateRunnable);
        if (!retried) {
            retried = true;
            mainHandler.postDelayed(this::initAndStartPlayer, 800);
        } else {
            if (isAdded()) Toast.makeText(requireContext(), "Cannot play this audio", Toast.LENGTH_SHORT).show();
        }
    }

    private void resumePlayback() {
        if (exoPlayer == null) { initAndStartPlayer(); return; }
        exoPlayer.play();
        isPlaying = true;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
        startWaveAnimation();
        startDiscAnimation();
        seekHandler.post(seekUpdateRunnable);
        updateMiniPlayButton();
    }

    private void pausePlayback() {
        if (exoPlayer != null) exoPlayer.pause();
        isPlaying = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation();
        stopDiscAnimation();
        seekHandler.removeCallbacks(seekUpdateRunnable);
        updateMiniPlayButton();
    }

    private void setPlayButtonLoading(boolean loading) {
        if (btnPlayPause == null) return;
        if (progressBar  != null) progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnPlayPause.setEnabled(!loading);
        btnPlayPause.setAlpha(loading ? 0.5f : 1f);
    }

    private void releasePlayer() {
        if (exoPlayer != null) {
            exoPlayer.removeListener(this);
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
        isPlaying = false; isPreparing = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save / Share
    // ─────────────────────────────────────────────────────────────────────────

    private void toggleSave() {
        String uid = null;
        try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
        if (uid == null || soundId == null) return;
        isSaved = !isSaved;
        updateSaveButton();
        DatabaseReference ref = FirebaseUtils.getUserRef(uid).child("saved_sounds").child(soundId);
        if (isSaved) {
            ref.setValue(soundTitle);
            incrementSoundSaves(1);
            if (isAdded()) Toast.makeText(requireContext(), "Sound saved", Toast.LENGTH_SHORT).show();
        } else {
            ref.removeValue();
            incrementSoundSaves(-1);
            if (isAdded()) Toast.makeText(requireContext(), "Sound removed", Toast.LENGTH_SHORT).show();
        }
    }

    private void incrementSoundSaves(int delta) {
        if (soundId == null) return;
        FirebaseUtils.db().getReference("sounds").child(soundId).child("total_saves")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class);
                    long cur = v != null ? v : 0;
                    d.setValue(Math.max(0, cur + delta));
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
            });
    }

    private void shareSound() {
        if (isGone()) return;
        String text = "Check out this sound: " + (soundTitle != null ? soundTitle : "")
            + (artist != null && !artist.isEmpty() ? " by " + artist : "");
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(shareIntent, "Share sound via"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Floating bar
    // ─────────────────────────────────────────────────────────────────────────

    private void updateFloatingActionsVisibility() {
        if (layoutFloatingActions == null || btnUseSoundCamera == null || scrollSoundDetail == null) return;
        if (btnUseSoundCamera.getVisibility() != View.VISIBLE) {
            layoutFloatingActions.setVisibility(View.GONE);
            return;
        }
        int[] btnLoc    = new int[2];
        int[] scrollLoc = new int[2];
        btnUseSoundCamera.getLocationOnScreen(btnLoc);
        scrollSoundDetail.getLocationOnScreen(scrollLoc);
        boolean scrolledAway = (btnLoc[1] + btnUseSoundCamera.getHeight()) < scrollLoc[1];
        layoutFloatingActions.setVisibility(scrolledAway ? View.VISIBLE : View.GONE);
    }

    private void showUseAudioDialog() {
        if (isGone()) return;
        String[] options = { "🎥  Use in Camera", "🎬  Use in Video" };
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(soundTitle != null && !soundTitle.isEmpty() ? soundTitle : "Use this sound")
            .setItems(options, (dialog, which) -> {
                if (which == 0 && btnUseSoundCamera != null) btnUseSoundCamera.performClick();
                else if (which == 1 && btnUseSoundGallery != null) btnUseSoundGallery.performClick();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updatePlayButtonState() {
        if (btnPlayPause == null) return;
        boolean hasUrl = getPlaybackUrl() != null && !getPlaybackUrl().isEmpty();
        btnPlayPause.setAlpha(hasUrl ? 1f : 0.4f);
        btnPlayPause.setEnabled(hasUrl);
    }

    private void updateSaveButton() {
        if (btnSaveSound != null)
            btnSaveSound.setImageResource(isSaved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        if (btnFloatingSave != null)
            btnFloatingSave.setText(isSaved ? "✅  Saved" : "🔖  Save");
    }

    private String formatMs(int ms) {
        int sec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private String formatCount(long count) {
        if (count >= 1_000_000) return String.format(Locale.US, "%.1fM", count / 1_000_000.0);
        if (count >= 1_000)     return String.format(Locale.US, "%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }
}
