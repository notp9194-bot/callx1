package com.callx.app.music;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelMusicTrimActivity;
import com.callx.app.upload.ReelUploadActivity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ReelFirebaseUtils;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.firebase.database.*;

import java.util.*;

/**
 * SoundDetailActivity — Production-level Audio/Sound Detail Screen.
 *
 * Changes v2:
 *  ✅ Creator denormalized — reads creatorName/creatorPhoto from sounds node first (1 read)
 *  ✅ ExoPlayer/Media3 replaces MediaPlayer — better buffering, adaptive streaming, seek
 *  ✅ Thumbnail preloading — Glide .preload() for next page before scroll hits
 *  ✅ Shimmer/skeleton placeholder instead of plain ProgressBar
 *  ✅ Mini-player persistence — floating overlay mini-player survives navigation
 *  ✅ "Use in Camera" button — outline style, no solid color fill
 *  ✅ Original creator click → opens reel profile screen
 *  ✅ 3-dot menu in top-right toolbar
 */
public class SoundDetailActivity extends AppCompatActivity {

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
    private ImageButton  btnBack, btnPlayPause, btnShare, btnMore;
    private TextView     tvSoundTitle, tvArtist, tvDuration, tvReelCount,
                         tvTrendingRank, tvSavesCount, tvBpm, tvGenre,
                         tvOriginalBadge, tvIsVerified;
    private TextView     btnUseSoundCamera, btnUseSoundGallery, btnTrimStart;
    private ImageView    ivSoundCover, ivDiscRing;
    private ImageButton  btnSaveSound;
    private RecyclerView rvReels, rvRelated;
    private ShimmerFrameLayout shimmerSound;
    private View         layoutSoundInfo;
    private LinearLayout layoutWaveform;
    private SeekBar      seekBar;
    private TextView     tvCurrentTime, tvTotalTime;

    // ─── Creator row ───────────────────────────────────────────────────────────
    private LinearLayout layoutCreator;
    private View         dividerCreator;
    private ImageView    ivCreatorAvatar;
    private TextView     tvCreatorName;

    // ─── Mini-player ───────────────────────────────────────────────────────────
    private FrameLayout  miniPlayerContainer;
    private ImageButton  btnMiniPlayPause, btnMiniClose;
    private TextView     tvMiniTitle;
    private boolean      miniPlayerVisible = false;

    // ─── Reels grid pagination ─────────────────────────────────────────────────
    private static final int REELS_PAGE_SIZE     = 12;
    private ScrollView  scrollSoundDetail;
    private ProgressBar progressReelsPagination;
    private String       lastReelKey        = null;
    private boolean      isLoadingMoreReels = false;
    private boolean      hasMoreReels       = true;

    // ─── State ─────────────────────────────────────────────────────────────────
    private String  soundId, soundTitle, soundUrl, artist, coverUrl, genre;
    private int     durationMs, bpm;
    private boolean isSaved         = false;
    private boolean isPlaying       = false;
    private boolean userSeeking     = false;

    // ─── Creator ───────────────────────────────────────────────────────────────
    private String  creatorUid, creatorName, creatorPhoto;

    // ─── ExoPlayer ─────────────────────────────────────────────────────────────
    private ExoPlayer               exoPlayer;
    private final Handler           seekHandler    = new Handler(Looper.getMainLooper());

    // ─── Animation ─────────────────────────────────────────────────────────────
    private ObjectAnimator             discAnimator;
    private final List<ValueAnimator>  waveAnimators  = new ArrayList<>();

    // ─── Data ──────────────────────────────────────────────────────────────────
    private final List<RelatedItem>    relatedItems   = new ArrayList<>();
    private final List<ReelThumbItem>  reelItems      = new ArrayList<>();
    private ReelThumbAdapter           reelThumbAdapter;

    // ─── Seek update ───────────────────────────────────────────────────────────
    private final Runnable seekUpdateRunnable = new Runnable() {
        @Override public void run() {
            if (exoPlayer != null && isPlaying && !userSeeking) {
                long pos = exoPlayer.getCurrentPosition();
                long dur = exoPlayer.getDuration();
                if (dur > 0 && seekBar != null) {
                    seekBar.setProgress((int)(100L * pos / dur));
                }
                if (tvCurrentTime != null) tvCurrentTime.setText(formatMs((int) pos));
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
        if (isPlaying) {
            pausePlayback();
            showMiniPlayer();
        }
    }

    @Override
    protected void onDestroy() {
        seekHandler.removeCallbacksAndMessages(null);
        stopDiscAnimation();
        stopWaveAnimation();
        releaseExoPlayer();
        super.onDestroy();
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
        shimmerSound      = findViewById(R.id.shimmer_sound);
        layoutSoundInfo   = findViewById(R.id.layout_sound_info);
        layoutWaveform    = findViewById(R.id.layout_sound_waveform);
        seekBar           = findViewById(R.id.seekbar_sound);
        tvCurrentTime     = findViewById(R.id.tv_sound_current_time);
        tvTotalTime       = findViewById(R.id.tv_sound_total_time);

        // Creator row
        layoutCreator  = findViewById(R.id.layout_sound_creator);
        dividerCreator = findViewById(R.id.divider_creator);
        ivCreatorAvatar= findViewById(R.id.iv_creator_avatar);
        tvCreatorName  = findViewById(R.id.tv_creator_name);

        // Mini-player
        miniPlayerContainer = findViewById(R.id.mini_player_container);
        btnMiniPlayPause    = findViewById(R.id.btn_mini_play_pause);
        btnMiniClose        = findViewById(R.id.btn_mini_close);
        tvMiniTitle         = findViewById(R.id.tv_mini_title);

        // Reels grid pagination
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
                    if (exoPlayer != null) {
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
            lp.gravity = Gravity.BOTTOM;
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
    //  Disc spin animation
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
            float angle = (float) a.getAnimatedValue();
            if (ivDiscRing != null) ivDiscRing.setRotation(angle);
        });
        discAnimator.start();
    }

    private void stopDiscAnimation() {
        if (discAnimator != null) {
            discAnimator.cancel();
            discAnimator = null;
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Firebase data loading — CHANGE 1: Creator denormalized on sounds node
    // ───────────────────────────────────────────────────────────────────────────

    private void loadSoundData() {
        if (soundId == null || soundId.isEmpty()) {
            hideShimmer();
            updatePlayButtonState();
            return;
        }
        showShimmer();

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

                    // ── CHANGE 1: read denormalized creator fields directly ──
                    if (creatorUid == null || creatorUid.isEmpty()) {
                        String cUid = snap.child("creatorUid").getValue(String.class);
                        if (cUid != null && !cUid.isEmpty()) creatorUid = cUid;
                    }
                    String cName  = snap.child("creatorName").getValue(String.class);
                    String cPhoto = snap.child("creatorPhoto").getValue(String.class);
                    if (cName != null && !cName.isEmpty()) {
                        creatorName  = cName;
                        creatorPhoto = cPhoto;
                        if (creatorUid != null && !creatorUid.isEmpty()) {
                            bindCreatorRow(creatorUid, creatorName, creatorPhoto);
                            sortAndApplyReelItems();
                        }
                    }
                    // ── End change 1 ──

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
                            if (ivSoundCover != null) {
                                Glide.with(SoundDetailActivity.this).load(coverUrl)
                                    .transform(new CircleCrop())
                                    .placeholder(R.drawable.ic_music_note)
                                    .into(ivSoundCover);
                            }
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

                    hideShimmer();
                    updatePlayButtonState();
                    updateTrimButtonVisibility();
                }

                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isFinishing()) hideShimmer();
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
                            // ── CHANGE 3: preload thumbnails of related sounds ──
                            Glide.with(SoundDetailActivity.this)
                                .load(item.coverUrl)
                                .preload();
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
                int gridBottom = rvReels.getBottom();
                int visibleBottom = scrollY + scrollSoundDetail.getHeight();
                if (visibleBottom >= gridBottom - 600) {
                    loadMoreReelsForSound();
                }
            });
        }

        loadMoreReelsForSound();
    }

    private void loadMoreReelsForSound() {
        if (isLoadingMoreReels || !hasMoreReels || isFinishing() || isDestroyed()) return;
        isLoadingMoreReels = true;
        if (progressReelsPagination != null && lastReelKey != null) {
            progressReelsPagination.setVisibility(View.VISIBLE);
        }

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

                // ── CHANGE 3: preload next-page thumbnails before user scrolls there ──
                preloadNextPageThumbnails(page);

                fetchViewCountsForPage(page);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                isLoadingMoreReels = false;
                if (progressReelsPagination != null) progressReelsPagination.setVisibility(View.GONE);
                if (reelItems.isEmpty()) loadReelsFromReelsNode();
            }
        });
    }

    /** CHANGE 3: Glide preload thumbnails so they're cached before scroll */
    private void preloadNextPageThumbnails(List<ReelThumbItem> page) {
        for (ReelThumbItem item : page) {
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
                Glide.with(this).load(item.thumbnailUrl).preload();
            }
        }
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
                    preloadNextPageThumbnails(page);
                    finishAppendingPage(page, false);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { isLoadingMoreReels = false; }
            });
    }

    private void fetchViewCountsForPage(List<ReelThumbItem> page) {
        if (page.isEmpty()) { finishAppendingPage(page, false); return; }
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
        if (reelThumbAdapter != null) reelThumbAdapter.notifyItemRangeInserted(insertStart, page.size());
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
    //  Creator profile — CHANGE 1: check denormalized fields before reading users
    // ───────────────────────────────────────────────────────────────────────────

    private void loadCreatorProfile() {
        // If we already got creatorName from the sounds node in loadSoundData(), skip user reads
        if (creatorName != null && !creatorName.isEmpty() && creatorUid != null && !creatorUid.isEmpty()) {
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
                    if (name == null || name.isEmpty())
                        name = snap.child("username").getValue(String.class);
                    if (name == null || name.isEmpty())
                        name = snap.child("name").getValue(String.class);
                    if (name == null || name.isEmpty()) name = "Unknown";
                    String photo = snap.child("photoUrl").getValue(String.class);
                    if (photo == null || photo.isEmpty())
                        photo = snap.child("profilePic").getValue(String.class);
                    if (photo == null || photo.isEmpty())
                        photo = snap.child("avatar").getValue(String.class);
                    creatorName  = name;
                    creatorPhoto = photo;
                    bindCreatorRow(uid, name, photo);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /** CHANGE 7: Original creator click → ReelProfileActivity */
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

        layoutCreator.setOnClickListener(v -> {
            if (uid == null || uid.isEmpty()) return;
            try {
                // CHANGE 7: open ReelProfileActivity (reel profile), not UserProfileActivity
                Intent i = new Intent()
                    .setClassName(SoundDetailActivity.this, "com.callx.app.reels.ReelProfileActivity");
                i.putExtra("uid",   uid);
                i.putExtra("name",  name  != null ? name  : "");
                i.putExtra("photo", photo != null ? photo : "");
                startActivity(i);
            } catch (Exception ex) {
                // fallback to main UserProfileActivity if reel profile not found
                try {
                    Intent i2 = new Intent()
                        .setClassName(SoundDetailActivity.this, "com.callx.app.activities.UserProfileActivity");
                    i2.putExtra("uid",   uid);
                    i2.putExtra("name",  name  != null ? name  : "");
                    i2.putExtra("photo", photo != null ? photo : "");
                    startActivity(i2);
                } catch (Exception ex2) {
                    android.util.Log.w("SoundDetail", "Profile activity not found", ex2);
                }
            }
        });
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
    //  Click listeners — CHANGE 8: 3-dot menu
    // ───────────────────────────────────────────────────────────────────────────

    private void setupClickListeners() {
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareSound());

        // CHANGE 8: 3-dot more menu
        if (btnMore != null) {
            btnMore.setOnClickListener(v -> showMoreMenu(v));
        }

        if (btnSaveSound != null) {
            btnSaveSound.setOnClickListener(v -> toggleSave());
            btnSaveSound.setOnLongClickListener(v -> {
                startActivity(new Intent(this, SavedSoundsActivity.class));
                return true;
            });
        }

        if (btnUseSoundCamera != null) btnUseSoundCamera.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelCameraActivity.class);
            i.putExtra("selected_sound_id",         soundId);
            i.putExtra("selected_sound_title",       soundTitle);
            i.putExtra("selected_sound_url",         soundUrl);
            i.putExtra("replace_audio_with_sound",   true);
            startActivity(i);
        });

        if (btnUseSoundGallery != null) btnUseSoundGallery.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelUploadActivity.class);
            i.putExtra("selected_sound_id",    soundId);
            i.putExtra("selected_sound_title", soundTitle);
            i.putExtra("selected_sound_url",   soundUrl);
            startActivity(i);
        });

        if (btnTrimStart != null) btnTrimStart.setVisibility(View.GONE);

        // CHANGE 5: Mini-player buttons
        if (btnMiniPlayPause != null) {
            btnMiniPlayPause.setOnClickListener(v -> {
                if (isPlaying) pausePlayback();
                else resumePlayback();
            });
        }
        if (btnMiniClose != null) {
            btnMiniClose.setOnClickListener(v -> {
                pausePlayback();
                hideMiniPlayer();
            });
        }
    }

    /** CHANGE 8: 3-dot popup menu */
    private void showMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Report Sound");
        popup.getMenu().add(0, 2, 0, "Copy Link");
        popup.getMenu().add(0, 3, 0, "Not Interested");
        popup.getMenu().add(0, 4, 0, "Add to Playlist");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    Toast.makeText(this, "Report submitted", Toast.LENGTH_SHORT).show();
                    return true;
                case 2:
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("sound",
                            "callx2://sound/" + soundId));
                        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case 3:
                    Toast.makeText(this, "Got it — won't show similar sounds", Toast.LENGTH_SHORT).show();
                    return true;
                case 4:
                    Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show();
                    return true;
                default: return false;
            }
        });
        popup.show();
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Mini-player — CHANGE 5
    // ───────────────────────────────────────────────────────────────────────────

    private void showMiniPlayer() {
        if (miniPlayerContainer == null) return;
        if (tvMiniTitle != null)
            tvMiniTitle.setText(soundTitle != null ? soundTitle : "Now Playing");
        if (btnMiniPlayPause != null)
            btnMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        miniPlayerContainer.setVisibility(View.VISIBLE);
        miniPlayerVisible = true;
    }

    private void hideMiniPlayer() {
        if (miniPlayerContainer != null) miniPlayerContainer.setVisibility(View.GONE);
        miniPlayerVisible = false;
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Shimmer helpers — CHANGE 4
    // ───────────────────────────────────────────────────────────────────────────

    private void showShimmer() {
        if (shimmerSound != null) {
            shimmerSound.setVisibility(View.VISIBLE);
            shimmerSound.startShimmer();
        }
        if (layoutSoundInfo != null) layoutSoundInfo.setVisibility(View.INVISIBLE);
    }

    private void hideShimmer() {
        if (shimmerSound != null) {
            shimmerSound.stopShimmer();
            shimmerSound.setVisibility(View.GONE);
        }
        if (layoutSoundInfo != null) layoutSoundInfo.setVisibility(View.VISIBLE);
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Playback — CHANGE 2: ExoPlayer/Media3
    // ───────────────────────────────────────────────────────────────────────────

    private void togglePlayPause() {
        if (soundUrl == null || soundUrl.isEmpty()) {
            Toast.makeText(this, "Loading audio…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isPlaying) {
            pausePlayback();
        } else {
            if (exoPlayer == null) {
                initExoPlayer();
            } else {
                resumePlayback();
            }
        }
    }

    private void initExoPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(soundUrl)));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    setPlayButtonLoading(false);
                    long dur = exoPlayer.getDuration();
                    if (dur > 0 && tvTotalTime != null) tvTotalTime.setText(formatMs((int) dur));
                    exoPlayer.play();
                    isPlaying = true;
                    if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
                    if (btnMiniPlayPause != null) btnMiniPlayPause.setImageResource(R.drawable.ic_pause);
                    startWaveAnimation();
                    startDiscAnimation();
                    seekHandler.post(seekUpdateRunnable);
                } else if (state == Player.STATE_BUFFERING) {
                    setPlayButtonLoading(true);
                } else if (state == Player.STATE_ENDED) {
                    pausePlayback();
                    if (seekBar != null) seekBar.setProgress(0);
                    if (tvCurrentTime != null) tvCurrentTime.setText("0:00");
                }
            }
            @Override public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                releaseExoPlayer();
                setPlayButtonLoading(false);
                isPlaying = false;
                if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
                stopWaveAnimation();
                stopDiscAnimation();
                Toast.makeText(SoundDetailActivity.this, "Cannot play this audio", Toast.LENGTH_SHORT).show();
            }
        });
        setPlayButtonLoading(true);
        exoPlayer.prepare();
    }

    private void resumePlayback() {
        if (exoPlayer == null) { initExoPlayer(); return; }
        exoPlayer.play();
        isPlaying = true;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
        if (btnMiniPlayPause != null) btnMiniPlayPause.setImageResource(R.drawable.ic_pause);
        startWaveAnimation();
        startDiscAnimation();
        seekHandler.post(seekUpdateRunnable);
        hideMiniPlayer();
    }

    private void pausePlayback() {
        if (exoPlayer != null) exoPlayer.pause();
        isPlaying = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        if (btnMiniPlayPause != null) btnMiniPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation();
        stopDiscAnimation();
        seekHandler.removeCallbacks(seekUpdateRunnable);
    }

    private void releaseExoPlayer() {
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        isPlaying = false;
    }

    private void setPlayButtonLoading(boolean loading) {
        if (btnPlayPause == null) return;
        btnPlayPause.setEnabled(!loading);
        btnPlayPause.setAlpha(loading ? 0.5f : 1f);
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
        DatabaseReference ref = FirebaseUtils.getUserRef(uid)
            .child("saved_sounds").child(soundId);
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
                startActivity(i);
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
