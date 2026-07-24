package com.callx.app.music;

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
import androidx.fragment.app.Fragment;
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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
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
 * SoundDetailFragment — Single source of truth for Sound Detail screen.
 *
 * Ek hi Fragment, do jagah use hota hai:
 *   • SoundDetailActivity    (isSheet = false → back arrow, no drag handle)
 *   • SoundDetailSheetFragment (isSheet = true  → X icon, drag handle visible)
 *
 * Koi duplicate logic nahi — sab kuch yahan hai.
 */
public class SoundDetailFragment extends Fragment implements Player.Listener {

    // ── Args ──────────────────────────────────────────────────────────────────
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
    private static final String ARG_IS_SHEET    = "is_sheet";

    private static final int REELS_PAGE_SIZE = 12;
    private static final int REQUEST_TRIM_SOUND = 702;

    // ── Host callback (Activity → finish, Sheet → dismiss) ────────────────────
    private Runnable onCloseListener;

    /** Activity ya Sheet parent set karta hai — close action batane ke liye */
    public void setOnCloseListener(Runnable listener) { this.onCloseListener = listener; }

    // ── State ─────────────────────────────────────────────────────────────────
    private String  soundId, soundTitle, soundUrl, artist, coverUrl, genre, previewAudioUrl;
    private int     durationMs, bpm;
    private int     trimStartMs = 0, trimEndMs = 0;   // ✂ user-picked range from ReelMusicTrimActivity
    private boolean isSheet       = false;
    private boolean isSaved       = false;
    private boolean isPlaying     = false;
    private boolean isPreparing   = false;
    private boolean userSeeking   = false;
    private boolean retried       = false;
    private boolean miniPlayerActive = false;

    private String  creatorUid, creatorName, creatorPhoto;

    // ── Pagination ────────────────────────────────────────────────────────────
    private String  lastReelKey        = null;
    private boolean isLoadingMoreReels = false;
    private boolean hasMoreReels       = true;
    private ChildEventListener soundReelsLiveListener = null;

    // ── Views ─────────────────────────────────────────────────────────────────
    private View         viewDragHandle;
    private ImageButton  btnBack, btnShare, btnSaveSound, btnMore, btnPlayPause;
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

    /** Activity mode (isSheet = false) */
    public static SoundDetailFragment newInstance(
            String soundId, String title, String artist, String coverUrl,
            String soundUrl, int durationMs, String genre, int bpm,
            String creatorUid, String previewAudioUrl) {
        return newInstance(soundId, title, artist, coverUrl, soundUrl,
                durationMs, genre, bpm, creatorUid, previewAudioUrl, false);
    }

    /** isSheet = true → sheet mode (drag handle + X button) */
    public static SoundDetailFragment newInstance(
            String soundId, String title, String artist, String coverUrl,
            String soundUrl, int durationMs, String genre, int bpm,
            String creatorUid, String previewAudioUrl, boolean isSheet) {
        SoundDetailFragment f = new SoundDetailFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SOUND_ID,    n(soundId));
        b.putString(ARG_TITLE,       n(title));
        b.putString(ARG_ARTIST,      n(artist));
        b.putString(ARG_COVER_URL,   n(coverUrl));
        b.putString(ARG_SOUND_URL,   n(soundUrl));
        b.putInt   (ARG_DURATION_MS, durationMs);
        b.putString(ARG_GENRE,       n(genre));
        b.putInt   (ARG_BPM,         bpm);
        b.putString(ARG_CREATOR_UID, n(creatorUid));
        b.putString(ARG_PREVIEW_URL, n(previewAudioUrl));
        b.putBoolean(ARG_IS_SHEET,   isSheet);
        f.setArguments(b);
        return f;
    }

    private static String n(String s) { return s != null ? s : ""; }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sound_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle b = getArguments();
        if (b != null) {
            soundId         = b.getString(ARG_SOUND_ID,    "");
            soundTitle      = b.getString(ARG_TITLE,       "");
            artist          = b.getString(ARG_ARTIST,      "");
            coverUrl        = b.getString(ARG_COVER_URL,   "");
            soundUrl        = b.getString(ARG_SOUND_URL,   "");
            durationMs      = b.getInt(ARG_DURATION_MS, 0);
            genre           = b.getString(ARG_GENRE,       "");
            bpm             = b.getInt(ARG_BPM, 0);
            creatorUid      = b.getString(ARG_CREATOR_UID, "");
            previewAudioUrl = b.getString(ARG_PREVIEW_URL, "");
            isSheet         = b.getBoolean(ARG_IS_SHEET, false);
        }

        bindViews(view);
        applyMode();          // drag handle + close icon based on isSheet
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
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TRIM_SOUND && resultCode == android.app.Activity.RESULT_OK && data != null) {
            trimStartMs = data.getIntExtra(com.callx.app.editor.ReelMusicTrimActivity.RESULT_START_MS, 0);
            trimEndMs   = data.getIntExtra(com.callx.app.editor.ReelMusicTrimActivity.RESULT_END_MS, durationMs);
            if (btnTrimStart != null && !isGone()) {
                btnTrimStart.setText("✂ " + formatMs(trimStartMs) + " – " + formatMs(trimEndMs));
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isPlaying) pausePlayback();
    }

    @Override
    public void onDestroyView() {
        seekHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        stopDiscAnimation();
        stopWaveAnimation();
        releasePlayer();
        detachLiveListener();
        super.onDestroyView();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mode: Activity vs Sheet
    // ─────────────────────────────────────────────────────────────────────────

    private void applyMode() {
        if (isSheet) {
            // Sheet: drag handle dikhao + X icon
            if (viewDragHandle != null) viewDragHandle.setVisibility(View.VISIBLE);
            if (btnBack != null) btnBack.setImageResource(R.drawable.ic_close);
        } else {
            // Activity: drag handle chhupao + back arrow
            if (viewDragHandle != null) viewDragHandle.setVisibility(View.GONE);
            if (btnBack != null) btnBack.setImageResource(R.drawable.ic_arrow_back);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews(View v) {
        viewDragHandle    = v.findViewById(R.id.view_drag_handle);
        btnBack           = v.findViewById(R.id.btn_sound_back);
        btnPlayPause      = v.findViewById(R.id.btn_sound_play_pause);
        btnShare          = v.findViewById(R.id.btn_sound_share);
        btnMore           = v.findViewById(R.id.btn_sound_more);
        btnSaveSound      = v.findViewById(R.id.btn_save_sound);
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
        rvReels           = v.findViewById(R.id.rv_sound_reels);
        rvRelated         = v.findViewById(R.id.rv_related_sounds);
        progressBar       = v.findViewById(R.id.progress_sound);
        progressReelsPagination = v.findViewById(R.id.progress_reels_pagination);
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

        if (rvReels   != null) {
            rvReels.setLayoutManager(new GridLayoutManager(requireContext(), 3));
            // Match UserReelsActivity's bordered grid: white RV background + 1dp
            // padding/gap decoration so each square thumbnail shows a thin
            // white border, same as the profile reels grid.
            if (rvReels.getItemDecorationCount() == 0) {
                rvReels.addItemDecoration(
                    new com.callx.app.profile.ReelGridAdapter.WhiteGridDecoration(requireContext()));
            }
        }
        if (rvRelated != null) rvRelated.setLayoutManager(
            new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));

        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (fromUser && exoPlayer != null && tvCurrentTime != null) {
                        long dur = exoPlayer.getDuration();
                        if (dur > 0) tvCurrentTime.setText(formatMs((int)(dur * p / 100L)));
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
    // Guard
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isGone() { return !isAdded() || getContext() == null; }

    // ─────────────────────────────────────────────────────────────────────────
    // Shimmer
    // ─────────────────────────────────────────────────────────────────────────

    private void showShimmer(boolean show) {
        if (shimmerLayout != null) {
            shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) shimmerLayout.startShimmer(); else shimmerLayout.stopShimmer();
        }
        if (layoutSoundInfo   != null) layoutSoundInfo.setVisibility(show ? View.GONE : View.VISIBLE);
        if (layoutReelsHeader != null) layoutReelsHeader.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Populate static info
    // ─────────────────────────────────────────────────────────────────────────

    private void populateSoundInfo() {
        if (tvSoundTitle != null) tvSoundTitle.setText(soundTitle.isEmpty() ? "Unknown Sound" : soundTitle);
        if (tvArtist     != null) tvArtist.setText(artist.isEmpty() ? "• Original Audio" : "• " + artist);

        if (durationMs > 0) {
            String dur = formatMs(durationMs);
            if (tvDuration  != null) tvDuration.setText(dur);
            if (tvTotalTime != null) tvTotalTime.setText(dur);
        }
        if (tvBpm != null) {
            tvBpm.setVisibility(bpm > 0 ? View.VISIBLE : View.GONE);
            if (bpm > 0) tvBpm.setText(bpm + " BPM");
        }
        if (tvGenre != null) {
            tvGenre.setVisibility(!genre.isEmpty() ? View.VISIBLE : View.GONE);
            if (!genre.isEmpty()) tvGenre.setText(genre);
        }
        loadCoverImage(coverUrl);
        buildStaticWaveform();
    }

    private void loadCoverImage(String url) {
        if (ivSoundCover == null || isGone()) return;
        if (url != null && !url.isEmpty()) {
            Glide.with(requireContext()).load(url)
                .transform(new CircleCrop())
                .placeholder(R.drawable.ic_music_note)
                .override(720, 720).into(ivSoundCover);
        } else {
            ivSoundCover.setImageResource(R.drawable.ic_music_note);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Waveform
    // ─────────────────────────────────────────────────────────────────────────

    private void buildStaticWaveform() {
        if (layoutWaveform == null || isGone()) return;
        layoutWaveform.removeAllViews();
        stopWaveAnimation();
        float dp = requireContext().getResources().getDisplayMetrics().density;
        Random rng = new Random(soundTitle.hashCode());
        for (int i = 0; i < 36; i++) {
            View bar = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (int)(4 * dp), (int)((10 + rng.nextInt(30)) * dp));
            lp.setMargins((int)(3 * dp), 0, (int)(3 * dp), 0);
            lp.gravity = android.view.Gravity.BOTTOM;
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(0x44FFFFFF);
            bar.setTag("waveBar");
            layoutWaveform.addView(bar);
        }
    }

    private void startWaveAnimation() {
        if (layoutWaveform == null || isGone()) return;
        stopWaveAnimation();
        float dp = requireContext().getResources().getDisplayMetrics().density;
        int minH = (int)(8 * dp), maxH = (int)(38 * dp);
        for (int i = 0; i < layoutWaveform.getChildCount(); i++) {
            final View bar = layoutWaveform.getChildAt(i);
            if (!"waveBar".equals(bar.getTag())) continue;
            bar.setBackgroundColor(0xFFFF3B5C);
            int dur = 400 + (i % 5) * 80;
            int target = minH + (int)((maxH - minH) * (0.4f + (i % 7) * 0.08f));
            ValueAnimator a = ValueAnimator.ofInt(minH, target);
            a.setDuration(dur);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            a.addUpdateListener(anim -> {
                if (bar.getParent() == null) return;
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
                lp.height = (int) anim.getAnimatedValue();
                bar.setLayoutParams(lp);
            });
            a.start();
            waveAnimators.add(a);
        }
    }

    private void stopWaveAnimation() {
        for (ValueAnimator a : waveAnimators) a.cancel();
        waveAnimators.clear();
        if (layoutWaveform != null)
            for (int i = 0; i < layoutWaveform.getChildCount(); i++) {
                View bar = layoutWaveform.getChildAt(i);
                if ("waveBar".equals(bar.getTag())) bar.setBackgroundColor(0x44FFFFFF);
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Disc animation
    // ─────────────────────────────────────────────────────────────────────────

    private void startDiscAnimation() {
        if (ivSoundCover == null) return;
        if (discAnimator != null && discAnimator.isRunning()) return;
        float start = ivSoundCover.getRotation();
        discAnimator = ObjectAnimator.ofFloat(ivSoundCover, "rotation", start, start + 360f);
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
    // Firebase — Sound data
    // ─────────────────────────────────────────────────────────────────────────

    private void loadSoundData() {
        if (soundId.isEmpty()) { showShimmer(false); updatePlayButtonState(); return; }

        FirebaseUtils.db().getReference("sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) return;

                    Long    count    = snap.child("reel_count").getValue(Long.class);
                    Long    rank     = snap.child("trending_rank").getValue(Long.class);
                    Long    saves    = snap.child("total_saves").getValue(Long.class);
                    Boolean orig     = snap.child("is_original").getValue(Boolean.class);
                    Boolean ver      = snap.child("is_verified").getValue(Boolean.class);
                    Boolean trending = snap.child("is_trending").getValue(Boolean.class);

                    // Creator denormalized
                    if (creatorUid.isEmpty()) {
                        String uid = snap.child("creatorUid").getValue(String.class);
                        if (uid != null) creatorUid = uid;
                    }
                    String dn = snap.child("creatorName").getValue(String.class);
                    String dp = snap.child("creatorPhoto").getValue(String.class);
                    if (dn != null && !dn.isEmpty()) creatorName = dn;
                    if (dp != null && !dp.isEmpty()) creatorPhoto = dp;

                    if (!creatorUid.isEmpty() && creatorName != null && !creatorName.isEmpty()) {
                        bindCreatorRow(creatorUid, creatorName, creatorPhoto);
                        sortAndApplyReelItems();
                    }

                    // Audio URL fallback
                    if (soundUrl.isEmpty()) {
                        for (String key : new String[]{"audioUrl","audio_url","url"}) {
                            String u = snap.child(key).getValue(String.class);
                            if (u != null && !u.isEmpty()) { soundUrl = u; break; }
                        }
                    }
                    if (previewAudioUrl.isEmpty()) {
                        String pu = snap.child("previewAudioUrl").getValue(String.class);
                        if (pu != null) previewAudioUrl = pu;
                    }
                    if (coverUrl.isEmpty()) {
                        for (String key : new String[]{"coverUrl","cover_url"}) {
                            String c = snap.child(key).getValue(String.class);
                            if (c != null && !c.isEmpty()) { coverUrl = c; loadCoverImage(coverUrl); break; }
                        }
                    }
                    if (durationMs <= 0) {
                        for (String key : new String[]{"duration_ms","durationMs"}) {
                            Long d = snap.child(key).getValue(Long.class);
                            if (d != null && d > 0) {
                                durationMs = d.intValue();
                                String s = formatMs(durationMs);
                                if (tvDuration  != null) tvDuration.setText(s);
                                if (tvTotalTime != null) tvTotalTime.setText(s);
                                break;
                            }
                        }
                    }

                    if (tvReelCount  != null) tvReelCount.setText(formatCount(count  != null ? count  : 0) + " Reels");
                    if (tvSavesCount != null) { tvSavesCount.setText(formatCount(saves != null ? saves : 0) + " Saves"); tvSavesCount.setVisibility(View.VISIBLE); }

                    if (tvTrendingRank != null) {
                        if (rank != null && rank > 0 && rank <= 50) { tvTrendingRank.setVisibility(View.VISIBLE); tvTrendingRank.setText("#" + rank + " Trending"); }
                        else if (Boolean.TRUE.equals(trending))      { tvTrendingRank.setVisibility(View.VISIBLE); tvTrendingRank.setText("🔥 Trending"); }
                        else                                           tvTrendingRank.setVisibility(View.GONE);
                    }
                    if (tvOriginalBadge != null) tvOriginalBadge.setVisibility(Boolean.TRUE.equals(orig) ? View.VISIBLE : View.GONE);
                    if (tvIsVerified    != null) tvIsVerified.setVisibility(Boolean.TRUE.equals(ver)  ? View.VISIBLE : View.GONE);

                    showShimmer(false);
                    updatePlayButtonState();
                    if (scrollSoundDetail != null) scrollSoundDetail.post(SoundDetailFragment.this::updateFloatingActionsVisibility);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isGone()) { showShimmer(false); updatePlayButtonState(); }
                }
            });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase — Reels
    // ─────────────────────────────────────────────────────────────────────────

    private void loadReelsForSound() {
        if (soundId.isEmpty() || rvReels == null) return;
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
            scrollSoundDetail.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (sv, scrollX, scrollY, oldX, oldY) -> {
                    updateFloatingActionsVisibility();
                    if (scrollY <= oldY || isLoadingMoreReels || !hasMoreReels) return;
                    int contentH = sv.getChildAt(0) != null ? sv.getChildAt(0).getHeight() : 0;
                    if (scrollY + sv.getHeight() >= contentH - 600) loadMoreReelsForSound();
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
        q = lastReelKey != null ? q.startAfter(lastReelKey).limitToFirst(REELS_PAGE_SIZE)
                                : q.limitToFirst(REELS_PAGE_SIZE);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isGone()) return;
                List<SoundDetailActivity.ReelThumbItem> page = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) {
                    String rid   = s.getKey();
                    String thumb = firstOf(s, "thumbnailUrl", "thumbnail");
                    String vid   = s.child("videoUrl").getValue(String.class);
                    String uid   = s.child("ownerUid").getValue(String.class);
                    if (rid != null) {
                        SoundDetailActivity.ReelThumbItem item =
                            new SoundDetailActivity.ReelThumbItem(rid, n(thumb), n(vid));
                        item.uid = uid;
                        page.add(item);
                        lastReelKey = rid;
                    }
                }
                if (page.size() < REELS_PAGE_SIZE) hasMoreReels = false;
                if (page.isEmpty() && reelItems.isEmpty() && lastReelKey == null) {
                    hasMoreReels = false; loadReelsFromReelsNode(); return;
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
        if (soundId.isEmpty() || isGone()) { isLoadingMoreReels = false; return; }
        FirebaseUtils.db().getReference("reels")
            .orderByChild("musicId").equalTo(soundId)
            .limitToFirst(REELS_PAGE_SIZE)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) { isLoadingMoreReels = false; return; }
                    List<SoundDetailActivity.ReelThumbItem> page = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String rid   = s.getKey();
                        String thumb = firstOf(s, "thumbnailUrl", "thumbnail");
                        String vid   = s.child("videoUrl").getValue(String.class);
                        String uid   = s.child("uid").getValue(String.class);
                        Long   views = s.child("viewsCount").getValue(Long.class);
                        if (rid != null) {
                            SoundDetailActivity.ReelThumbItem item =
                                new SoundDetailActivity.ReelThumbItem(rid, n(thumb), n(vid));
                            item.uid = uid;
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
        for (SoundDetailActivity.ReelThumbItem item : page) {
            if (!item.thumbnailUrl.isEmpty() && isAdded())
                Glide.with(requireContext()).load(item.thumbnailUrl)
                    .apply(new RequestOptions().centerCrop().override(300, 533)).preload();
        }
        final int total = page.size();
        final int[] done = {0};
        for (SoundDetailActivity.ReelThumbItem item : page) {
            FirebaseUtils.getReelsRef().child(item.reelId).child("viewsCount")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Long v = snap.getValue(Long.class);
                        item.viewsCount = v != null ? v : 0L;
                        if (++done[0] >= total) finishAppendingPage(page, true);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (++done[0] >= total) finishAppendingPage(page, true);
                    }
                });
        }
    }

    private void finishAppendingPage(List<SoundDetailActivity.ReelThumbItem> page, boolean sort) {
        isLoadingMoreReels = false;
        if (progressReelsPagination != null) progressReelsPagination.setVisibility(View.GONE);
        if (isGone() || page.isEmpty()) return;
        if (sort) {
            for (SoundDetailActivity.ReelThumbItem item : page)
                item.isOriginalCreator = !creatorUid.isEmpty() && creatorUid.equals(item.uid);
            page.sort((a, b) -> {
                if (a.isOriginalCreator != b.isOriginalCreator) return a.isOriginalCreator ? -1 : 1;
                return Long.compare(b.viewsCount, a.viewsCount);
            });
        }
        int start = reelItems.size();
        reelItems.addAll(page);
        if (reelThumbAdapter != null) {
            reelThumbAdapter.notifyItemRangeInserted(start, page.size());
            if (rvReels != null) rvReels.post(() -> {
                if (rvReels != null) rvReels.requestLayout();
                if (scrollSoundDetail != null) scrollSoundDetail.requestLayout();
            });
        }
        attachSoundReelsLiveListener();
    }

    private void attachSoundReelsLiveListener() {
        if (soundReelsLiveListener != null || soundId.isEmpty() || isGone()) return;
        Query liveQ = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("sounds").child(soundId).child("reels").orderByKey();
        if (lastReelKey != null) liveQ = liveQ.startAfter(lastReelKey);

        soundReelsLiveListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, @Nullable String prev) {
                if (isGone()) return;
                String rid = snap.getKey();
                if (rid == null) return;
                for (SoundDetailActivity.ReelThumbItem e : reelItems) if (rid.equals(e.reelId)) return;
                String thumb = firstOf(snap, "thumbnailUrl", "thumbnail");
                String vid   = snap.child("videoUrl").getValue(String.class);
                SoundDetailActivity.ReelThumbItem item =
                    new SoundDetailActivity.ReelThumbItem(rid, n(thumb), n(vid));
                item.uid = snap.child("ownerUid").getValue(String.class);
                reelItems.add(0, item);
                if (reelThumbAdapter != null) {
                    reelThumbAdapter.notifyItemInserted(0);
                    if (rvReels != null) rvReels.post(() -> rvReels.scrollToPosition(0));
                }
                lastReelKey = rid;
            }
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
            @Override public void onChildChanged(@NonNull DataSnapshot s, @Nullable String p) {}
            @Override public void onChildMoved(@NonNull DataSnapshot s,  @Nullable String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        liveQ.addChildEventListener(soundReelsLiveListener);
    }

    private void detachLiveListener() {
        if (soundReelsLiveListener == null || soundId.isEmpty()) return;
        try {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("sounds").child(soundId).child("reels")
                .removeEventListener(soundReelsLiveListener);
        } catch (Exception ignored) {}
        soundReelsLiveListener = null;
    }

    private void sortAndApplyReelItems() {
        if (isGone() || creatorUid.isEmpty() || reelItems.isEmpty()) return;
        boolean changed = false;
        for (SoundDetailActivity.ReelThumbItem item : reelItems) {
            boolean o = creatorUid.equals(item.uid);
            if (o != item.isOriginalCreator) changed = true;
            item.isOriginalCreator = o;
        }
        if (!changed) return;
        reelItems.sort((a, b) -> Boolean.compare(!a.isOriginalCreator, !b.isOriginalCreator));
        if (reelThumbAdapter != null) reelThumbAdapter.notifyDataSetChanged();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase — Related sounds
    // ─────────────────────────────────────────────────────────────────────────

    private void loadRelatedSounds() {
        if (genre.isEmpty()) return;
        FirebaseUtils.getMusicLibraryRef().orderByChild("genre").equalTo(genre).limitToFirst(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) return;
                    relatedItems.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        String id    = s.getKey();
                        String title = firstOf(s, "title", "name");
                        String art   = s.child("artist").getValue(String.class);
                        String cover = s.child("coverUrl").getValue(String.class);
                        String url   = s.child("audioUrl").getValue(String.class);
                        if (title != null && (id == null || !id.equals(soundId)))
                            relatedItems.add(new SoundDetailActivity.RelatedItem(
                                n(id), title, n(art), n(cover), n(url)));
                    }
                    if (rvRelated != null && !relatedItems.isEmpty()) {
                        rvRelated.setAdapter(new SoundDetailActivity.RelatedAdapter(relatedItems, item -> {
                            showMiniPlayer();
                            SoundDetailFragment next = SoundDetailFragment.newInstance(
                                item.id, item.title, item.artist, item.coverUrl, item.audioUrl,
                                0, genre, 0, null, null, isSheet);
                            next.setOnCloseListener(onCloseListener);
                            requireActivity().getSupportFragmentManager()
                                .beginTransaction()
                                .replace(requireView().getId(), next)
                                .addToBackStack(null)
                                .commit();
                        }));
                        View sec = getView() != null ? getView().findViewById(R.id.layout_related_sounds_section) : null;
                        if (sec != null) sec.setVisibility(View.VISIBLE);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase — Creator
    // ─────────────────────────────────────────────────────────────────────────

    private void loadCreatorProfile() {
        if (!creatorUid.isEmpty() && creatorName != null && !creatorName.isEmpty()) {
            bindCreatorRow(creatorUid, creatorName, creatorPhoto); return;
        }
        if (!creatorUid.isEmpty()) { fetchCreatorUserData(creatorUid); return; }
        if (soundId.isEmpty()) return;

        FirebaseUtils.db().getReference("sounds").child(soundId).child("creatorUid")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) return;
                    String uid = snap.getValue(String.class);
                    if (uid != null && !uid.isEmpty()) {
                        creatorUid = uid; fetchCreatorUserData(uid); sortAndApplyReelItems();
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void fetchCreatorUserData(String uid) {
        ReelFirebaseUtils.reelUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isGone()) return;
                String name  = firstOf(snap, "displayName", "handle");
                String photo = firstOf(snap, "photoUrl", "thumbUrl");
                if (name != null && !name.isEmpty()) {
                    creatorName = name; creatorPhoto = photo;
                    bindCreatorRow(uid, name, photo);
                } else fetchCreatorFromMainUsersNode(uid);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { fetchCreatorFromMainUsersNode(uid); }
        });
    }

    private void fetchCreatorFromMainUsersNode(String uid) {
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isGone()) return;
                String name = firstOf(snap, "displayName", "username", "name");
                String photo = firstOf(snap, "photoUrl", "profilePic", "avatar");
                creatorName  = name  != null ? name  : "Unknown";
                creatorPhoto = photo;
                bindCreatorRow(uid, creatorName, photo);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void bindCreatorRow(String uid, String name, String photo) {
        if (layoutCreator == null || isGone()) return;
        if (tvCreatorName != null) tvCreatorName.setText("@" + name);
        if (ivCreatorAvatar != null) {
            if (photo != null && !photo.isEmpty())
                Glide.with(requireContext()).load(photo).transform(new CircleCrop())
                    .placeholder(R.drawable.ic_person).error(R.drawable.ic_person)
                    .override(720, 720).into(ivCreatorAvatar);
            else ivCreatorAvatar.setImageResource(R.drawable.ic_person);
        }
        layoutCreator.setVisibility(View.VISIBLE);
        if (dividerCreator != null) dividerCreator.setVisibility(View.VISIBLE);
        layoutCreator.setOnClickListener(v -> openUserProfile(uid, name, photo));
    }

    private void openUserProfile(String uid, String name, String photo) {
        if (uid.isEmpty() || isGone()) return;
        try {
            Intent i = new Intent().setClassName(requireContext(), "com.callx.app.activities.UserProfileActivity");
            i.putExtra("uid", uid);
            i.putExtra("name",  n(name));
            i.putExtra("photo", n(photo));
            startActivity(i);
        } catch (Exception ex) { android.util.Log.w("SoundDetailFrag", "UserProfileActivity not found", ex); }
    }

    private void checkIfSaved() {
        String uid = null;
        try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
        if (uid == null || soundId.isEmpty()) return;
        FirebaseUtils.getUserRef(uid).child("saved_sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) { isSaved = snap.exists(); updateSaveButton(); }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Click listeners
    // ─────────────────────────────────────────────────────────────────────────

    private void setupClickListeners() {
        // Close: Activity → finish(), Sheet → dismiss() — callback handles it
        if (btnBack != null) btnBack.setOnClickListener(v -> {
            if (onCloseListener != null) onCloseListener.run();
        });

        if (btnShare     != null) btnShare.setOnClickListener(v -> shareSound());
        if (btnSaveSound != null) {
            btnSaveSound.setOnClickListener(v -> toggleSave());
            btnSaveSound.setOnLongClickListener(v -> { startActivity(new Intent(requireContext(), SavedSoundsActivity.class)); return true; });
        }
        if (btnFloatingSave     != null) btnFloatingSave.setOnClickListener(v -> toggleSave());
        if (btnFloatingUseAudio != null) btnFloatingUseAudio.setOnClickListener(v -> showUseAudioDialog());
        if (btnPlayPause        != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());

        if (btnUseSoundCamera != null) btnUseSoundCamera.setOnClickListener(v -> {
            if (isGone()) return;
            Intent i = new Intent(requireContext(), com.callx.app.camera.ReelCameraActivity.class);
            i.putExtra("selected_sound_id",        soundId);
            i.putExtra("selected_sound_title",     soundTitle);
            i.putExtra("selected_sound_url",       soundUrl);
            // ✅ FIX: carry the sound's cover art forward so the reel's
            // right-rail music disc shows the ORIGINAL sound's photo
            // immediately, instead of depending only on the later async
            // Firebase patch in registerOrLinkSound().
            i.putExtra("selected_sound_cover",     coverUrl);
            i.putExtra("selected_sound_artist",    artist);
            i.putExtra("replace_audio_with_sound", true);
            if (trimEndMs > trimStartMs) {
                i.putExtra("selected_sound_start_ms", trimStartMs);
                i.putExtra("selected_sound_end_ms",   trimEndMs);
            }
            startActivity(i);
            if (onCloseListener != null) onCloseListener.run();
        });

        if (btnUseSoundGallery != null) btnUseSoundGallery.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            pick.setType("video/*");
            requireActivity().startActivityForResult(pick, 701);
        });

        // ✂ Trim chip — was left unwired after the UI rework; sound has a URL to trim
        if (btnTrimStart != null) {
            btnTrimStart.setVisibility(soundUrl != null && !soundUrl.isEmpty() ? View.VISIBLE : View.GONE);
            btnTrimStart.setOnClickListener(v -> {
                if (isGone()) return;
                Intent i = new Intent(requireContext(), com.callx.app.editor.ReelMusicTrimActivity.class);
                i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_ID,       soundId);
                i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_TITLE,    soundTitle);
                i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_URL,      soundUrl);
                i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_DURATION_MS,    durationMs);
                startActivityForResult(i, REQUEST_TRIM_SOUND);
            });
        }

        if (btnMore != null) btnMore.setOnClickListener(v -> showMoreMenu());

        // Add to profile
        String myUid = FirebaseAuth.getInstance().getUid();
        if (btnAddToProfile != null && myUid != null) {
            btnAddToProfile.setVisibility(View.VISIBLE);
            btnAddToProfile.setOnClickListener(v -> {
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("soundId", soundId); data.put("title", soundTitle);
                data.put("artist", artist);   data.put("coverUrl", coverUrl);
                data.put("soundUrl", soundUrl); data.put("durationMs", durationMs);
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("reels/users").child(myUid).child("profileSong").setValue(data)
                    .addOnSuccessListener(u -> { if (btnAddToProfile != null) btnAddToProfile.setText("✓  Added to profile"); })
                    .addOnFailureListener(e -> { if (isAdded()) Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show(); });
            });
        }

        if (btnMiniPlayPause != null) btnMiniPlayPause.setOnClickListener(v -> togglePlayPause());
        if (btnMiniClose     != null) btnMiniClose.setOnClickListener(v -> { pausePlayback(); hideMiniPlayer(); });
    }

    private void showMoreMenu() {
        if (isGone()) return;
        PopupMenu popup = new PopupMenu(requireContext(), btnMore);
        popup.getMenu().add(0, 1, 0, "Report sound");
        popup.getMenu().add(0, 2, 1, "Add to playlist");
        popup.getMenu().add(0, 3, 2, "Copy link");
        popup.getMenu().add(0, 4, 3, "Not interested");
        popup.getMenu().add(0, 7, 4, "🔍 Search Sounds");
        popup.getMenu().add(0, 8, 5, "🎚 Remix this Sound");
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid != null && myUid.equals(creatorUid)) {
            popup.getMenu().add(0, 5, 6, "Upload Sound");
            popup.getMenu().add(0, 6, 7, "View Analytics");
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 2: { Intent i = new Intent(requireContext(), SoundPlaylistActivity.class);
                    i.putExtra("sound_id", soundId); i.putExtra("sound_title", soundTitle);
                    i.putExtra("sound_url", soundUrl); startActivity(i); return true; }
                case 3: shareSound(); return true;
                case 5: { Intent i = new Intent(requireContext(), SoundUploadActivity.class);
                    i.putExtra("sound_id", soundId); i.putExtra("sound_title", soundTitle);
                    startActivity(i); return true; }
                case 6: { Intent i = new Intent(requireContext(), SoundAnalyticsActivity.class);
                    i.putExtra(SoundAnalyticsActivity.EXTRA_SOUND_ID, soundId);
                    i.putExtra(SoundAnalyticsActivity.EXTRA_SOUND_TITLE, soundTitle);
                    startActivity(i); return true; }
                case 7: startActivity(new Intent(requireContext(), SoundSearchActivity.class)); return true;
                case 8: { Intent i = new Intent(requireContext(), SoundRemixActivity.class);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_ID,    soundId);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_TITLE, soundTitle);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_URL,   soundUrl);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_COVER, coverUrl);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_ARTIST,artist);
                    startActivity(i); return true; }
                default: return true;
            }
        });
        popup.show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mini-player
    // ─────────────────────────────────────────────────────────────────────────

    private void showMiniPlayer() {
        if (layoutMiniPlayer == null || miniPlayerActive || isGone()) return;
        miniPlayerActive = true;
        if (tvMiniTitle != null) tvMiniTitle.setText(soundTitle.isEmpty() ? "Now Playing" : soundTitle);
        if (ivMiniCover != null && !coverUrl.isEmpty())
            Glide.with(requireContext()).load(coverUrl).transform(new CircleCrop())
                .placeholder(R.drawable.ic_music_note).override(720, 720).into(ivMiniCover);
        layoutMiniPlayer.setVisibility(View.VISIBLE);
        updateMiniPlayButton();
    }

    private void hideMiniPlayer() {
        if (layoutMiniPlayer == null) return;
        miniPlayerActive = false;
        layoutMiniPlayer.setVisibility(View.GONE);
    }

    private void updateMiniPlayButton() {
        if (btnMiniPlayPause != null)
            btnMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ExoPlayer
    // ─────────────────────────────────────────────────────────────────────────

    private String getPlaybackUrl() {
        return !previewAudioUrl.isEmpty() ? previewAudioUrl : soundUrl;
    }

    private void togglePlayPause() {
        String url = getPlaybackUrl();
        if (url.isEmpty()) { if (isAdded()) Toast.makeText(requireContext(), "Loading audio…", Toast.LENGTH_SHORT).show(); return; }
        if (isPreparing) return;
        if (isPlaying) pausePlayback(); else { if (exoPlayer == null) initAndStartPlayer(); else resumePlayback(); }
    }

    private void initAndStartPlayer() {
        if (isGone()) return;
        isPreparing = true; setPlayButtonLoading(true);
        DefaultTrackSelector ts = new DefaultTrackSelector(requireContext());
        ts.setParameters(ts.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true));
        exoPlayer = new ExoPlayer.Builder(requireContext())
            .setTrackSelector(ts)
            .setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(4_000, 12_000, 1_500, 2_000).build())
            .build();
        exoPlayer.addListener(this);
        exoPlayer.setMediaItem(MediaItem.fromUri(getPlaybackUrl()));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.prepare();
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY && isPreparing) {
            isPreparing = false; setPlayButtonLoading(false);
            long dur = exoPlayer.getDuration();
            if (dur > 0 && tvTotalTime != null) tvTotalTime.setText(formatMs((int) dur));
            exoPlayer.play(); isPlaying = true;
            if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
            startWaveAnimation(); startDiscAnimation();
            seekHandler.post(seekUpdateRunnable); updateMiniPlayButton();
        } else if (state == Player.STATE_ENDED) {
            pausePlayback();
            if (seekBar != null) seekBar.setProgress(0);
            if (tvCurrentTime != null) tvCurrentTime.setText("0:00");
        }
    }

    @Override public void onPlayerError(@NonNull PlaybackException error) {
        releasePlayer(); setPlayButtonLoading(false);
        isPlaying = false; isPreparing = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation(); stopDiscAnimation(); seekHandler.removeCallbacks(seekUpdateRunnable);
        if (!retried) { retried = true; mainHandler.postDelayed(this::initAndStartPlayer, 800); }
        else if (isAdded()) Toast.makeText(requireContext(), "Cannot play this audio", Toast.LENGTH_SHORT).show();
    }

    private void resumePlayback() {
        if (exoPlayer == null) { initAndStartPlayer(); return; }
        exoPlayer.play(); isPlaying = true;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
        startWaveAnimation(); startDiscAnimation();
        seekHandler.post(seekUpdateRunnable); updateMiniPlayButton();
    }

    private void pausePlayback() {
        if (exoPlayer != null) exoPlayer.pause();
        isPlaying = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation(); stopDiscAnimation();
        seekHandler.removeCallbacks(seekUpdateRunnable); updateMiniPlayButton();
    }

    private void setPlayButtonLoading(boolean loading) {
        if (btnPlayPause == null) return;
        if (progressBar  != null) progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnPlayPause.setEnabled(!loading); btnPlayPause.setAlpha(loading ? 0.5f : 1f);
    }

    private void releasePlayer() {
        if (exoPlayer != null) { exoPlayer.removeListener(this); exoPlayer.stop(); exoPlayer.release(); exoPlayer = null; }
        isPlaying = false; isPreparing = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save / Share
    // ─────────────────────────────────────────────────────────────────────────

    private void toggleSave() {
        String uid = null;
        try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
        if (uid == null || soundId.isEmpty()) return;
        isSaved = !isSaved; updateSaveButton();
        DatabaseReference ref = FirebaseUtils.getUserRef(uid).child("saved_sounds").child(soundId);
        if (isSaved) { ref.setValue(soundTitle); incrementSoundSaves(1); if (isAdded()) Toast.makeText(requireContext(), "Sound saved", Toast.LENGTH_SHORT).show(); }
        else         { ref.removeValue(); incrementSoundSaves(-1); if (isAdded()) Toast.makeText(requireContext(), "Sound removed", Toast.LENGTH_SHORT).show(); }
    }

    private void incrementSoundSaves(int delta) {
        if (soundId.isEmpty()) return;
        FirebaseUtils.db().getReference("sounds").child(soundId).child("total_saves")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class); d.setValue(Math.max(0, (v != null ? v : 0) + delta)); return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
            });
    }

    private void shareSound() {
        if (isGone()) return;
        String text = "Check out this sound: " + soundTitle + (artist.isEmpty() ? "" : " by " + artist);
        Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Share sound via"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Floating bar
    // ─────────────────────────────────────────────────────────────────────────

    private void updateFloatingActionsVisibility() {
        if (layoutFloatingActions == null || btnUseSoundCamera == null || scrollSoundDetail == null) return;
        if (btnUseSoundCamera.getVisibility() != View.VISIBLE) { layoutFloatingActions.setVisibility(View.GONE); return; }
        int[] bl = new int[2], sl = new int[2];
        btnUseSoundCamera.getLocationOnScreen(bl); scrollSoundDetail.getLocationOnScreen(sl);
        layoutFloatingActions.setVisibility((bl[1] + btnUseSoundCamera.getHeight()) < sl[1] ? View.VISIBLE : View.GONE);
    }

    private void showUseAudioDialog() {
        if (isGone()) return;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(soundTitle.isEmpty() ? "Use this sound" : soundTitle)
            .setItems(new String[]{"🎥  Use in Camera", "🎬  Use in Video"}, (d, which) -> {
                if (which == 0 && btnUseSoundCamera != null)  btnUseSoundCamera.performClick();
                else if (which == 1 && btnUseSoundGallery != null) btnUseSoundGallery.performClick();
            }).setNegativeButton("Cancel", null).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updatePlayButtonState() {
        if (btnPlayPause == null) return;
        boolean has = !getPlaybackUrl().isEmpty();
        btnPlayPause.setAlpha(has ? 1f : 0.4f); btnPlayPause.setEnabled(has);
    }

    private void updateSaveButton() {
        if (btnSaveSound    != null) btnSaveSound.setImageResource(isSaved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        if (btnFloatingSave != null) btnFloatingSave.setText(isSaved ? "✅  Saved" : "🔖  Save");
    }

    private String formatMs(int ms) { int s = ms/1000; return String.format(Locale.US, "%d:%02d", s/60, s%60); }
    private String formatCount(long c) {
        if (c >= 1_000_000) return String.format(Locale.US, "%.1fM", c/1_000_000.0);
        if (c >= 1_000)     return String.format(Locale.US, "%.1fK", c/1_000.0);
        return String.valueOf(c);
    }

    /** DataSnapshot se pehla non-null value lata hai given keys mein se */
    private static String firstOf(DataSnapshot snap, String... keys) {
        for (String k : keys) { String v = snap.child(k).getValue(String.class); if (v != null && !v.isEmpty()) return v; }
        return null;
    }
}
