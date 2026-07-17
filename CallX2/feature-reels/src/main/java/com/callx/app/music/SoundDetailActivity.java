package com.callx.app.music;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelMusicTrimActivity;
import com.callx.app.upload.ReelUploadActivity;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.*;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Animator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ReelFirebaseUtils;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.firebase.database.*;
import com.google.firebase.auth.FirebaseAuth;

import java.util.*;

/**
 * SoundDetailActivity — Production-level Audio/Sound Detail Screen.
 *
 * Changes in this revision:
 *  ✅ ExoPlayer (Media3) replaces MediaPlayer — better buffering, adaptive streaming, proper seek
 *  ✅ Creator denormalised — reads creatorName/creatorPhoto directly from sounds/{id} (written at
 *     upload time), falls back to reels/users + users nodes only when the denormalised fields are absent
 *  ✅ Shimmer/skeleton placeholder replaces plain ProgressBar — shown until data arrives, then swapped
 *  ✅ Glide thumbnail preloading — next page's thumbnails are preloaded before the scroll hits the grid bottom
 *  ✅ Mini-player bar — stays visible (within this screen) when a related sound is tapped;
 *     full cross-Activity persistence requires a bound Service (left as TODO)
 *  ✅ "Use in Camera" button — now outline style (same as "Use with Video"), no fill colour
 *  ✅ Original creator click — opens UserProfileActivity (same className-based Intent as before)
 *  ✅ Top-right 3-dot overflow menu — PopupMenu; add real actions later
 */
public class SoundDetailActivity extends AppCompatActivity implements Player.Listener {

    // ─── Intent Extras ─────────────────────────────────────────────────────────
    public static final String EXTRA_SOUND_ID           = "sound_id";
    public static final String EXTRA_SOUND_TITLE        = "sound_title";
    public static final String EXTRA_SOUND_URL          = "sound_url";
    public static final String EXTRA_ARTIST             = "sound_artist";
    public static final String EXTRA_DURATION_MS        = "sound_duration_ms";
    public static final String EXTRA_COVER_URL          = "sound_cover_url";
    public static final String EXTRA_BPM                = "sound_bpm";
    public static final String EXTRA_GENRE              = "sound_genre";
    public static final String EXTRA_ORIGINAL_AUDIO_URL = "original_audio_url";
    public static final String EXTRA_CREATOR_UID        = "sound_creator_uid";

    // ─── Views ─────────────────────────────────────────────────────────────────
    private ImageButton  btnBack, btnShare, btnSaveSound, btnMore;
    private ImageButton  btnPlayPause;
    private TextView     tvSoundTitle, tvArtist, tvDuration, tvReelCount,
                         tvTrendingRank, tvSavesCount, tvBpm, tvGenre,
                         tvOriginalBadge, tvIsVerified;
    private TextView     btnUseSoundCamera, btnUseSoundGallery, btnTrimStart;
    private ImageView    ivSoundCover, ivDiscRing;
    private RecyclerView rvReels, rvRelated;
    private ProgressBar  progressBar;
    private View         layoutSoundInfo;
    private LinearLayout layoutWaveform;
    private SeekBar      seekBar;
    private TextView     tvCurrentTime, tvTotalTime;
    private ShimmerFrameLayout shimmerLayout;
    private View         layoutReelsHeader;

    // Creator row
    private LinearLayout layoutCreator;
    private View         dividerCreator;
    private ImageView    ivCreatorAvatar;
    private TextView     tvCreatorName;

    // Mini-player
    private View         layoutMiniPlayer;
    private ImageView    ivMiniCover;
    private TextView     tvMiniTitle;
    private ImageButton  btnMiniPlayPause, btnMiniClose;
    private boolean      miniPlayerActive = false;

    // ─── Reels grid pagination ─────────────────────────────────────────────────
    private static final int REELS_PAGE_SIZE     = 12;
    private ScrollView  scrollSoundDetail;
    private ProgressBar progressReelsPagination;
    private String       lastReelKey        = null;
    private boolean       isLoadingMoreReels = false;
    private boolean       hasMoreReels       = true;

    // ─── Request codes ─────────────────────────────────────────────────────────
    /** Gallery video picker launched from "Use in Video" button */
    private static final int REQ_GALLERY_VIDEO = 701;
    private static final int REQ_TRIM          = 702;

    // ─── State ─────────────────────────────────────────────────────────────────
    private String  soundId, soundTitle, soundUrl, artist, coverUrl, genre;
    private int     durationMs, bpm;
    private boolean isSaved         = false;
    private boolean isPlaying       = false;
    private boolean isPreparing     = false;
    private boolean userSeeking     = false;
    private boolean retried         = false;
    private int     musicStartMs    = 0;
    private int     musicEndMs      = 0;

    // ─── Creator ───────────────────────────────────────────────────────────────
    private String  creatorUid, creatorName, creatorPhoto;

    // ─── ExoPlayer (Media3) ────────────────────────────────────────────────────
    private ExoPlayer                  exoPlayer;
    private final Handler              mainHandler    = new Handler(Looper.getMainLooper());

    // ─── Animation ─────────────────────────────────────────────────────────────
    private ObjectAnimator             discAnimator;
    private final List<ValueAnimator>  waveAnimators  = new ArrayList<>();
    private final Handler              seekHandler    = new Handler(Looper.getMainLooper());

    // ─── Data ──────────────────────────────────────────────────────────────────
    private final List<RelatedItem>    relatedItems   = new ArrayList<>();
    private final List<ReelThumbItem>  reelItems      = new ArrayList<>();
    private ReelThumbAdapter           reelThumbAdapter;

    // ─── Seek runnable ─────────────────────────────────────────────────────────
    private final Runnable seekUpdateRunnable = new Runnable() {
        @Override public void run() {
            if (exoPlayer != null && isPlaying && !userSeeking) {
                long pos = exoPlayer.getCurrentPosition();
                long dur = exoPlayer.getDuration();
                if (dur > 0 && seekBar != null)
                    seekBar.setProgress((int)(100L * pos / dur));
                if (tvCurrentTime != null) tvCurrentTime.setText(formatMs((int)pos));
            }
            if (isPlaying) seekHandler.postDelayed(this, 300);
        }
    };

    // ───────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ───────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_detail);

        soundId    = getIntent().getStringExtra(EXTRA_SOUND_ID);
        soundTitle = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
        soundUrl   = getIntent().getStringExtra(EXTRA_SOUND_URL);
        artist     = getIntent().getStringExtra(EXTRA_ARTIST);
        durationMs = getIntent().getIntExtra(EXTRA_DURATION_MS, 0);
        coverUrl   = getIntent().getStringExtra(EXTRA_COVER_URL);
        bpm        = getIntent().getIntExtra(EXTRA_BPM, 0);
        genre      = getIntent().getStringExtra(EXTRA_GENRE);
        creatorUid = getIntent().getStringExtra(EXTRA_CREATOR_UID);

        String originalAudioUrl = getIntent().getStringExtra(EXTRA_ORIGINAL_AUDIO_URL);
        if (originalAudioUrl != null && !originalAudioUrl.isEmpty()) soundUrl = originalAudioUrl;
        if (soundUrl == null || soundUrl.isEmpty()) {
            String reelVideoUrl = getIntent().getStringExtra("reel_video_url");
            if (reelVideoUrl != null && !reelVideoUrl.isEmpty()) soundUrl = reelVideoUrl;
        }

        bindViews();
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
    protected void onPause() {
        super.onPause();
        if (isPlaying) pausePlayback();
    }

    @Override
    protected void onDestroy() {
        seekHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        stopDiscAnimation();
        stopWaveAnimation();
        releasePlayer();
        super.onDestroy();
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Shimmer helpers
    // ───────────────────────────────────────────────────────────────────────────

    /** Toggle shimmer/skeleton ↔ real content. */
    private void showShimmer(boolean show) {
        if (shimmerLayout != null) {
            shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) shimmerLayout.startShimmer();
            else      shimmerLayout.stopShimmer();
        }
        if (layoutSoundInfo   != null) layoutSoundInfo.setVisibility(show ? View.GONE : View.VISIBLE);
        if (layoutReelsHeader != null) layoutReelsHeader.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  View binding
    // ───────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        btnBack           = findViewById(R.id.btn_sound_back);
        btnPlayPause      = findViewById(R.id.btn_sound_play_pause);
        btnShare          = findViewById(R.id.btn_sound_share);
        btnMore           = findViewById(R.id.btn_sound_more);
        tvSoundTitle      = findViewById(R.id.tv_sound_title);
        tvArtist          = findViewById(R.id.tv_sound_artist);
        tvDuration        = findViewById(R.id.tv_sound_duration);
        tvReelCount       = findViewById(R.id.tv_sound_reel_count);
        tvTrendingRank    = findViewById(R.id.tv_sound_trending_rank);
        tvSavesCount      = findViewById(R.id.tv_sound_saves_count);
        tvBpm             = findViewById(R.id.tv_sound_bpm);
        tvGenre           = findViewById(R.id.tv_sound_genre);
        tvOriginalBadge   = findViewById(R.id.tv_sound_original_badge);
        tvIsVerified      = findViewById(R.id.tv_sound_verified_badge);
        btnUseSoundCamera = findViewById(R.id.btn_use_sound_camera);
        btnUseSoundGallery= findViewById(R.id.btn_use_sound_gallery);
        btnTrimStart      = findViewById(R.id.btn_trim_start);
        ivSoundCover      = findViewById(R.id.iv_sound_cover);
        ivDiscRing        = findViewById(R.id.iv_disc_ring);
        btnSaveSound      = findViewById(R.id.btn_save_sound);
        rvReels           = findViewById(R.id.rv_sound_reels);
        rvRelated         = findViewById(R.id.rv_related_sounds);
        progressBar       = findViewById(R.id.progress_sound);
        layoutSoundInfo   = findViewById(R.id.layout_sound_info);
        layoutWaveform    = findViewById(R.id.layout_sound_waveform);
        seekBar           = findViewById(R.id.seekbar_sound);
        tvCurrentTime     = findViewById(R.id.tv_sound_current_time);
        tvTotalTime       = findViewById(R.id.tv_sound_total_time);
        shimmerLayout     = findViewById(R.id.shimmer_sound_detail);
        layoutReelsHeader = findViewById(R.id.layout_reels_header);

        // Creator row
        layoutCreator  = findViewById(R.id.layout_sound_creator);
        dividerCreator = findViewById(R.id.divider_creator);
        ivCreatorAvatar= findViewById(R.id.iv_creator_avatar);
        tvCreatorName  = findViewById(R.id.tv_creator_name);

        // Mini-player
        layoutMiniPlayer = findViewById(R.id.layout_mini_player);
        ivMiniCover      = findViewById(R.id.iv_mini_cover);
        tvMiniTitle      = findViewById(R.id.tv_mini_title);
        btnMiniPlayPause = findViewById(R.id.btn_mini_play_pause);
        btnMiniClose     = findViewById(R.id.btn_mini_close);

        // Pagination
        scrollSoundDetail       = findViewById(R.id.scroll_sound_detail);
        progressReelsPagination = findViewById(R.id.progress_reels_pagination);

        if (rvReels   != null) rvReels.setLayoutManager(new GridLayoutManager(this, 3));
        if (rvRelated != null) rvRelated.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

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

    // ───────────────────────────────────────────────────────────────────────────
    //  Populate static info
    // ───────────────────────────────────────────────────────────────────────────

    private void populateSoundInfo() {
        if (tvSoundTitle != null) tvSoundTitle.setText(soundTitle != null ? soundTitle : "Unknown Sound");
        if (tvArtist     != null) tvArtist.setText(artist != null ? "• " + artist : "• Original Audio");

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
                Glide.with(this).load(coverUrl)
                    .transform(new CircleCrop())
                    .placeholder(R.drawable.ic_music_note)
                    .into(ivSoundCover);
            } else {
                ivSoundCover.setImageResource(R.drawable.ic_music_note);
            }
        }

        buildStaticWaveform();
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Waveform
    // ───────────────────────────────────────────────────────────────────────────

    private void buildStaticWaveform() {
        if (layoutWaveform == null) return;
        layoutWaveform.removeAllViews();
        stopWaveAnimation();
        Random rng = new Random(soundTitle != null ? soundTitle.hashCode() : 0);
        float density = getResources().getDisplayMetrics().density;
        int barW = (int)(4 * density);
        int gap  = (int)(3 * density);
        for (int i = 0; i < 36; i++) {
            View bar = new View(this);
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
        if (layoutWaveform == null) return;
        stopWaveAnimation();
        float density = getResources().getDisplayMetrics().density;
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

    // ───────────────────────────────────────────────────────────────────────────
    //  Disc animation
    // ───────────────────────────────────────────────────────────────────────────

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

    // ───────────────────────────────────────────────────────────────────────────
    //  Firebase data loading
    // ───────────────────────────────────────────────────────────────────────────

    private void loadSoundData() {
        if (soundId == null || soundId.isEmpty()) {
            showShimmer(false);
            updatePlayButtonState();
            return;
        }

        FirebaseUtils.db().getReference("sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;

                    Long    count       = snap.child("reel_count").getValue(Long.class);
                    Long    rank        = snap.child("trending_rank").getValue(Long.class);
                    Long    saves       = snap.child("total_saves").getValue(Long.class);
                    Boolean orig        = snap.child("is_original").getValue(Boolean.class);
                    Boolean ver         = snap.child("is_verified").getValue(Boolean.class);
                    Boolean trendingFlg = snap.child("is_trending").getValue(Boolean.class);

                    // ── Denormalised creator fields (written at upload time) ──
                    // These mean we can avoid the extra reels/users + users reads
                    // on every open. fetchCreatorUserData() is still called as
                    // fallback for sounds uploaded before denormalisation was added.
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
                        // Denormalised data complete — bind immediately, no extra reads
                        bindCreatorRow(creatorUid, creatorName, creatorPhoto);
                        sortAndApplyReelItems();
                    }
                    // else: loadCreatorProfile() (called from onCreate) will handle it

                    if (soundUrl == null || soundUrl.isEmpty()) {
                        String u = snap.child("audioUrl").getValue(String.class);
                        if (u == null) u = snap.child("audio_url").getValue(String.class);
                        if (u == null) u = snap.child("url").getValue(String.class);
                        if (u != null && !u.isEmpty()) soundUrl = u;
                    }

                    if (coverUrl == null || coverUrl.isEmpty()) {
                        String c = snap.child("coverUrl").getValue(String.class);
                        if (c == null) c = snap.child("cover_url").getValue(String.class);
                        if (c != null && !c.isEmpty()) {
                            coverUrl = c;
                            if (ivSoundCover != null)
                                Glide.with(SoundDetailActivity.this).load(coverUrl)
                                    .transform(new CircleCrop())
                                    .placeholder(R.drawable.ic_music_note)
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
                    updateTrimButtonVisibility();
                }

                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isFinishing()) showShimmer(false);
                    updatePlayButtonState();
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
                    if (isFinishing() || isDestroyed()) return;
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
                            relatedItems.add(new RelatedItem(
                                id != null ? id : "", title,
                                art   != null ? art   : "",
                                cover != null ? cover : "",
                                url   != null ? url   : ""));
                        }
                    }
                    if (rvRelated != null && !relatedItems.isEmpty()) {
                        rvRelated.setAdapter(new RelatedAdapter(relatedItems, item -> {
                            // ── Mini-player: keep audio alive when opening related sound ──
                            // Show the mini bar so the user can still control the current
                            // audio, then open the new SoundDetailActivity in front of it.
                            // TODO: for full cross-Activity persistence, bind a MediaService.
                            showMiniPlayer();

                            Intent i = new Intent(SoundDetailActivity.this, SoundDetailActivity.class);
                            i.putExtra(EXTRA_SOUND_ID,    item.id);
                            i.putExtra(EXTRA_SOUND_TITLE, item.title);
                            i.putExtra(EXTRA_ARTIST,      item.artist);
                            i.putExtra(EXTRA_SOUND_URL,   item.audioUrl);
                            i.putExtra(EXTRA_COVER_URL,   item.coverUrl);
                            i.putExtra(EXTRA_GENRE,       genre);
                            startActivity(i);
                        }));
                        View sec = findViewById(R.id.layout_related_sounds_section);
                        if (sec != null) sec.setVisibility(View.VISIBLE);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadReelsForSound() {
        if (soundId == null || soundId.isEmpty() || rvReels == null) return;
        reelThumbAdapter = new ReelThumbAdapter(reelItems, position -> {
            ArrayList<String> ids = new ArrayList<>();
            for (ReelThumbItem r : reelItems) ids.add(r.reelId);
            Intent i = new Intent(this, SingleReelPlayerActivity.class);
            i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
            i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, position);
            startActivity(i);
        });
        rvReels.setAdapter(reelThumbAdapter);

        if (scrollSoundDetail != null) {
            scrollSoundDetail.setOnScrollChangeListener((View.OnScrollChangeListener) (v, scrollX, scrollY, oldX, oldY) -> {
                if (scrollY <= oldY || rvReels == null || isLoadingMoreReels || !hasMoreReels) return;
                int gridBottom    = rvReels.getBottom();
                int visibleBottom = scrollY + scrollSoundDetail.getHeight();
                if (visibleBottom >= gridBottom - 600) loadMoreReelsForSound();
            });
        }

        loadMoreReelsForSound();
    }

    private void loadMoreReelsForSound() {
        if (isLoadingMoreReels || !hasMoreReels || isFinishing() || isDestroyed()) return;
        isLoadingMoreReels = true;
        if (progressReelsPagination != null && lastReelKey != null)
            progressReelsPagination.setVisibility(View.VISIBLE);

        Query q = FirebaseUtils.db().getReference("sounds").child(soundId).child("reels")
            .orderByKey();
        q = (lastReelKey != null) ? q.startAfter(lastReelKey).limitToFirst(REELS_PAGE_SIZE)
                                   : q.limitToFirst(REELS_PAGE_SIZE);

        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                List<ReelThumbItem> page = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) {
                    String rid   = s.getKey();
                    String thumb = s.child("thumbnailUrl").getValue(String.class);
                    if (thumb == null) thumb = s.child("thumbnail").getValue(String.class);
                    String vid   = s.child("videoUrl").getValue(String.class);
                    String uid   = s.child("ownerUid").getValue(String.class);
                    if (rid != null) {
                        ReelThumbItem item = new ReelThumbItem(rid,
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
        if (soundId == null || isFinishing() || isDestroyed()) {
            isLoadingMoreReels = false;
            return;
        }
        FirebaseUtils.db().getReference("reels")
            .orderByChild("soundId").equalTo(soundId)
            .limitToFirst(REELS_PAGE_SIZE)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) { isLoadingMoreReels = false; return; }
                    List<ReelThumbItem> page = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String rid   = s.getKey();
                        String thumb = s.child("thumbnailUrl").getValue(String.class);
                        if (thumb == null) thumb = s.child("thumbnail").getValue(String.class);
                        String vid   = s.child("videoUrl").getValue(String.class);
                        String uid   = s.child("uid").getValue(String.class);
                        Long   views = s.child("viewsCount").getValue(Long.class);
                        if (rid != null) {
                            ReelThumbItem item = new ReelThumbItem(rid,
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

    private void fetchViewCountsForPage(List<ReelThumbItem> page) {
        if (page.isEmpty()) { finishAppendingPage(page, false); return; }
        // ── Thumbnail preloading ──
        // Fire off Glide .preload() for each thumbnail while we wait for view-count
        // fetches — images start downloading now, so by the time they land in the
        // grid RecyclerView they're already in Glide's disk/memory cache.
        for (ReelThumbItem item : page) {
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
                Glide.with(this)
                    .load(item.thumbnailUrl)
                    .apply(new RequestOptions().centerCrop().override(300, 533))
                    .preload();
            }
        }

        final int total = page.size();
        final int[] done = {0};
        for (ReelThumbItem item : page) {
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

    private void finishAppendingPage(List<ReelThumbItem> page, boolean needsSort) {
        isLoadingMoreReels = false;
        if (progressReelsPagination != null) progressReelsPagination.setVisibility(View.GONE);
        if (isFinishing() || isDestroyed() || page.isEmpty()) return;

        if (needsSort) {
            for (ReelThumbItem item : page) {
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
            // Force ScrollView to remeasure after items are inserted.
            // wrap_content RecyclerView inside ScrollView sometimes clips the last
            // row because the parent measured before the adapter had all items.
            // requestLayout() + post ensures measurement runs after bind completes.
            if (rvReels != null) rvReels.post(() -> {
                if (rvReels != null) rvReels.requestLayout();
            });
        }
    }

    private void sortAndApplyReelItems() {
        if (isFinishing() || isDestroyed()) return;
        if (creatorUid == null || creatorUid.isEmpty() || reelItems.isEmpty()) return;
        boolean changed = false;
        for (ReelThumbItem item : reelItems) {
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

    // ───────────────────────────────────────────────────────────────────────────
    //  Creator profile — DENORMALISED fast path + fallback
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Called from onCreate. By the time loadSoundData() finishes, creatorName
     * may already be set from the denormalised fields — in that case bindCreatorRow
     * is called there and this method becomes a no-op for the avatar/name binding.
     * The uid resolution chain still runs so we have creatorUid for the grid sort.
     */
    private void loadCreatorProfile() {
        // If denormalised name already arrived via the intent extra, bind now.
        if (creatorUid != null && !creatorUid.isEmpty()
                && creatorName != null && !creatorName.isEmpty()) {
            bindCreatorRow(creatorUid, creatorName, creatorPhoto);
            return;
        }
        // Otherwise: if we at least have uid, skip to user-data fetch
        if (creatorUid != null && !creatorUid.isEmpty()) {
            fetchCreatorUserData(creatorUid);
            return;
        }
        if (soundId == null || soundId.isEmpty()) return;

        // Need to resolve uid from sounds node first
        FirebaseUtils.db().getReference("sounds").child(soundId).child("creatorUid")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    String uid = snap.getValue(String.class);
                    if (uid != null && !uid.isEmpty()) {
                        creatorUid = uid;
                        fetchCreatorUserData(uid);
                        sortAndApplyReelItems();
                    } else {
                        FirebaseUtils.getMusicLibraryRef().child(soundId).child("creatorUid")
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot s2) {
                                    if (isFinishing() || isDestroyed()) return;
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
                    if (isFinishing() || isDestroyed()) return;
                    String name = snap.child("displayName").getValue(String.class);
                    if (name == null || name.isEmpty())
                        name = snap.child("handle").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    if (photo == null || photo.isEmpty())
                        photo = snap.child("thumbUrl").getValue(String.class);

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
                    if (isFinishing() || isDestroyed()) return;
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

    /**
     * Binds creator avatar + name row, and opens UserProfileActivity on tap.
     * Uses className-based Intent to avoid circular dependency with :app module.
     */
    private void bindCreatorRow(final String uid, final String name, final String photo) {
        if (layoutCreator == null || tvCreatorName == null) return;

        tvCreatorName.setText("@" + name);

        if (photo != null && !photo.isEmpty() && ivCreatorAvatar != null) {
            Glide.with(SoundDetailActivity.this).load(photo)
                .transform(new CircleCrop())
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(ivCreatorAvatar);
        } else if (ivCreatorAvatar != null) {
            ivCreatorAvatar.setImageResource(R.drawable.ic_person);
        }

        layoutCreator.setVisibility(View.VISIBLE);
        if (dividerCreator != null) dividerCreator.setVisibility(View.VISIBLE);

        // ── Creator click — opens UserProfileActivity ──
        // Reels screen bottom username tap already navigates here via the same pattern.
        layoutCreator.setOnClickListener(v -> openUserProfile(uid, name, photo));
    }

    /** Opens UserProfileActivity via className (avoids circular module dependency). */
    private void openUserProfile(String uid, String name, String photo) {
        if (uid == null || uid.isEmpty()) return;
        try {
            Intent i = new Intent()
                .setClassName(SoundDetailActivity.this, "com.callx.app.activities.UserProfileActivity");
            i.putExtra("uid",   uid);
            i.putExtra("name",  name  != null ? name  : "");
            i.putExtra("photo", photo != null ? photo : "");
            startActivity(i);
        } catch (Exception ex) {
            android.util.Log.w("SoundDetail", "UserProfileActivity not found", ex);
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

    // ───────────────────────────────────────────────────────────────────────────
    //  Click listeners
    // ───────────────────────────────────────────────────────────────────────────

    private void setupClickListeners() {
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareSound());

        // ── 3-dot overflow menu ──
        if (btnMore != null) {
            btnMore.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(this, btnMore);

                // Common options — visible to everyone
                popup.getMenu().add(0, 1, 0, "Report sound");
                popup.getMenu().add(0, 2, 1, "Add to playlist");
                popup.getMenu().add(0, 3, 2, "Copy link");
                popup.getMenu().add(0, 4, 3, "Not interested");

                // Creator-only options — only shown if current user owns this sound
                String myUid = FirebaseAuth.getInstance().getUid();
                boolean isMySound = myUid != null
                        && creatorUid != null
                        && myUid.equals(creatorUid);
                if (isMySound) {
                    popup.getMenu().add(0, 5, 4, "Upload Sound");
                    popup.getMenu().add(0, 6, 5, "View Analytics");
                }

                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {

                        case 2: // Add to playlist
                            Intent pi = new Intent(this, SoundPlaylistActivity.class);
                            pi.putExtra("sound_id",    soundId);
                            pi.putExtra("sound_title", soundTitle);
                            pi.putExtra("sound_url",   soundUrl);
                            startActivity(pi);
                            return true;

                        case 3: // Copy link
                            shareSound();
                            return true;

                        case 5: // Upload Sound (creator only)
                            Intent ui = new Intent(this, SoundUploadActivity.class);
                            ui.putExtra("sound_id",    soundId);
                            ui.putExtra("sound_title", soundTitle);
                            startActivity(ui);
                            return true;

                        case 6: // View Analytics (creator only)
                            Intent ai = new Intent(this, SoundAnalyticsActivity.class);
                            ai.putExtra(SoundAnalyticsActivity.EXTRA_SOUND_ID, soundId);
                            startActivity(ai);
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
                startActivity(new Intent(this, SavedSoundsActivity.class));
                return true;
            });
        }

        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());

        if (btnUseSoundCamera != null) btnUseSoundCamera.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelCameraActivity.class);
            i.putExtra("selected_sound_id",       soundId);
            i.putExtra("selected_sound_title",     soundTitle);
            i.putExtra("selected_sound_url",       soundUrl);
            i.putExtra("replace_audio_with_sound", true);
            startActivity(i);
        });

        if (btnUseSoundGallery != null) btnUseSoundGallery.setOnClickListener(v -> {
            // Let user pick a video from the gallery first, then open editor with
            // the mixer auto-launched so they can balance original + reused sound.
            Intent pick = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            pick.setType("video/*");
            startActivityForResult(pick, REQ_GALLERY_VIDEO);
        });

        if (btnTrimStart != null) btnTrimStart.setVisibility(View.GONE);

        // ── Mini-player controls ──
        if (btnMiniPlayPause != null) {
            btnMiniPlayPause.setOnClickListener(v -> togglePlayPause());
        }
        if (btnMiniClose != null) {
            btnMiniClose.setOnClickListener(v -> {
                pausePlayback();
                hideMiniPlayer();
            });
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Activity result — gallery video picker ("Use in Video" flow)
    // ───────────────────────────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_GALLERY_VIDEO && resultCode == RESULT_OK && data != null) {
            android.net.Uri videoUri = data.getData();
            if (videoUri == null) {
                Toast.makeText(this, "Could not read selected video", Toast.LENGTH_SHORT).show();
                return;
            }
            // Open ReelEditorActivity with:
            //  • the gallery video
            //  • the reused sound pre-selected
            //  • open_audio_mixer=true  →  editor auto-launches the mixer so user
            //    can adjust "Original video volume" vs "Reused sound volume"
            Intent intent = new Intent(this, com.callx.app.editor.ReelEditorActivity.class);
            intent.putExtra(com.callx.app.editor.ReelEditorActivity.EXTRA_VIDEO_URI,
                    videoUri.toString());
            intent.putExtra(com.callx.app.editor.ReelEditorActivity.EXTRA_IS_FILE_PATH, false);
            intent.putExtra("selected_sound_id",    soundId    != null ? soundId    : "");
            intent.putExtra("selected_sound_title", soundTitle != null ? soundTitle : "");
            intent.putExtra("selected_sound_url",   soundUrl   != null ? soundUrl   : "");
            // Tell the editor to open the audio mixer immediately so the user can
            // balance the original video audio against the reused sound.
            intent.putExtra(com.callx.app.editor.ReelEditorActivity.EXTRA_OPEN_AUDIO_MIXER, true);
            if (musicStartMs > 0) intent.putExtra("music_start_ms", musicStartMs);
            if (musicEndMs   > 0) intent.putExtra("music_end_ms",   musicEndMs);
            startActivity(intent);

        } else if (requestCode == REQ_TRIM && resultCode == RESULT_OK && data != null) {
            musicStartMs = data.getIntExtra(
                com.callx.app.editor.ReelMusicTrimActivity.RESULT_START_MS, 0);
            musicEndMs   = data.getIntExtra(
                com.callx.app.editor.ReelMusicTrimActivity.RESULT_END_MS, 0);
            Toast.makeText(this, "Trim set: " + formatMs(musicStartMs)
                + " - " + formatMs(musicEndMs), Toast.LENGTH_SHORT).show();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Mini-player helpers
    // ───────────────────────────────────────────────────────────────────────────

    private void showMiniPlayer() {
        if (layoutMiniPlayer == null || miniPlayerActive) return;
        miniPlayerActive = true;
        if (tvMiniTitle != null)
            tvMiniTitle.setText(soundTitle != null ? soundTitle : "Now Playing");
        if (ivMiniCover != null && coverUrl != null && !coverUrl.isEmpty()) {
            Glide.with(this).load(coverUrl)
                .transform(new CircleCrop())
                .placeholder(R.drawable.ic_music_note)
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

    // ───────────────────────────────────────────────────────────────────────────
    //  Playback — ExoPlayer (Media3)
    // ───────────────────────────────────────────────────────────────────────────

    private void togglePlayPause() {
        if (soundUrl == null || soundUrl.isEmpty()) {
            Toast.makeText(this, "Loading audio…", Toast.LENGTH_SHORT).show();
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
        isPreparing = true;
        setPlayButtonLoading(true);

        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.addListener(this);

        MediaItem mediaItem = MediaItem.fromUri(soundUrl);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.prepare(); // async — onPlaybackStateChanged fires when ready
    }

    // ── Player.Listener callbacks ──

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
            // REPEAT_MODE_ONE handles looping, so this only fires if looping disabled
            pausePlayback();
            if (seekBar != null) seekBar.setProgress(0);
            if (tvCurrentTime != null) tvCurrentTime.setText("0:00");
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        releasePlayer();
        setPlayButtonLoading(false);
        isPlaying   = false;
        isPreparing = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation();
        stopDiscAnimation();
        seekHandler.removeCallbacks(seekUpdateRunnable);

        if (!retried) {
            retried = true;
            mainHandler.postDelayed(this::initAndStartPlayer, 800);
        } else {
            Toast.makeText(this, "Cannot play this audio", Toast.LENGTH_SHORT).show();
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
        isPlaying   = false;
        isPreparing = false;
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Save / Share
    // ───────────────────────────────────────────────────────────────────────────

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
            Toast.makeText(this, "Sound saved", Toast.LENGTH_SHORT).show();
        } else {
            ref.removeValue();
            incrementSoundSaves(-1);
            Toast.makeText(this, "Sound removed", Toast.LENGTH_SHORT).show();
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
        String text = "Check out this sound: " + (soundTitle != null ? soundTitle : "")
            + (artist != null && !artist.isEmpty() ? " by " + artist : "");
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(shareIntent, "Share sound via"));
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ───────────────────────────────────────────────────────────────────────────

    private void updatePlayButtonState() {
        if (btnPlayPause == null) return;
        boolean hasUrl = soundUrl != null && !soundUrl.isEmpty();
        btnPlayPause.setAlpha(hasUrl ? 1f : 0.4f);
        btnPlayPause.setEnabled(hasUrl);
    }

    private void updateTrimButtonVisibility() {
        if (btnTrimStart == null) return;
        boolean hasUrl = soundUrl != null && !soundUrl.isEmpty();
        btnTrimStart.setVisibility(hasUrl ? View.VISIBLE : View.GONE);
        if (hasUrl) {
            btnTrimStart.setOnClickListener(v -> {
                Intent i = new Intent(this, ReelMusicTrimActivity.class);
                i.putExtra(ReelMusicTrimActivity.EXTRA_SOUND_ID,    soundId);
                i.putExtra(ReelMusicTrimActivity.EXTRA_SOUND_TITLE, soundTitle);
                i.putExtra(ReelMusicTrimActivity.EXTRA_SOUND_URL,   soundUrl);
                i.putExtra(ReelMusicTrimActivity.EXTRA_DURATION_MS, durationMs);
                startActivityForResult(i, REQ_TRIM);
            });
        }
    }

    private void updateSaveButton() {
        if (btnSaveSound != null)
            btnSaveSound.setImageResource(isSaved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
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

    // ───────────────────────────────────────────────────────────────────────────
    //  Inner classes: ReelThumb
    // ───────────────────────────────────────────────────────────────────────────

    static class ReelThumbItem {
        String  reelId, thumbnailUrl, videoUrl;
        String  uid;
        long    viewsCount;
        boolean isOriginalCreator;
        ReelThumbItem(String r, String t, String v) { reelId = r; thumbnailUrl = t; videoUrl = v; }
    }

    static class ReelThumbAdapter extends RecyclerView.Adapter<ReelThumbAdapter.VH> {
        private static final int GRID_COLUMNS = 3;
        interface OnClick { void click(int position); }
        private final List<ReelThumbItem> items;
        private final OnClick             onClick;
        ReelThumbAdapter(List<ReelThumbItem> items, OnClick onClick) {
            this.items = items; this.onClick = onClick;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int vt) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sound_reel_thumb, parent, false);
            int screenWidth = parent.getResources().getDisplayMetrics().widthPixels;
            int colWidth    = screenWidth / GRID_COLUMNS;
            int height      = colWidth * 16 / 9;
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            lp.height = height;
            v.setLayoutParams(lp);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ReelThumbItem item = items.get(pos);
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
                Glide.with(h.ivThumb).load(item.thumbnailUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop().into(h.ivThumb);
            } else {
                h.ivThumb.setImageResource(R.drawable.ic_play);
            }
            h.tvOriginalStrip.setVisibility(item.isOriginalCreator ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                int adapterPos = h.getBindingAdapterPosition();
                if (adapterPos != RecyclerView.NO_POSITION) onClick.click(adapterPos);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView ivThumb;
            TextView                 tvOriginalStrip;
            VH(View v) {
                super(v);
                ivThumb         = v.findViewById(R.id.iv_media_thumb);
                tvOriginalStrip = v.findViewById(R.id.tv_original_strip);
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Inner classes: Related sounds
    // ───────────────────────────────────────────────────────────────────────────

    static class RelatedItem {
        String id, title, artist, coverUrl, audioUrl;
        RelatedItem(String i, String t, String a, String c, String u) {
            id = i; title = t; artist = a; coverUrl = c; audioUrl = u;
        }
    }

    static class RelatedAdapter extends RecyclerView.Adapter<RelatedAdapter.VH> {
        interface OnClick { void click(RelatedItem item); }
        private final List<RelatedItem> items;
        private final OnClick           onClick;
        RelatedAdapter(List<RelatedItem> i, OnClick c) { items = i; onClick = c; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup p, int vt) {
            View v = android.view.LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_related_sound, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            RelatedItem item = items.get(pos);
            h.tvTitle.setText(item.title);
            h.tvArtist.setText(item.artist.isEmpty() ? "Unknown" : item.artist);
            if (!item.coverUrl.isEmpty()) {
                Glide.with(h.ivCover).load(item.coverUrl)
                    .transform(new CircleCrop())
                    .placeholder(R.drawable.ic_music_note)
                    .into(h.ivCover);
            } else {
                h.ivCover.setImageResource(R.drawable.ic_music_note);
            }
            h.itemView.setOnClickListener(v -> onClick.click(item));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivCover; TextView tvTitle, tvArtist;
            VH(View v) {
                super(v);
                ivCover  = v.findViewById(R.id.iv_related_cover);
                tvTitle  = v.findViewById(R.id.tv_related_title);
                tvArtist = v.findViewById(R.id.tv_related_artist);
            }
        }
    }
}
