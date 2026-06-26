package com.callx.app.music;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelMusicTrimActivity;
import com.callx.app.upload.ReelUploadActivity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.*;

/**
 * SoundDetailActivity — Production-level Audio/Sound Detail Screen.
 *
 * Features:
 *  ✅ Sound info: title, artist, duration, BPM, mood, genre
 *  ✅ Spinning vinyl disc animation (cover rotates while playing)
 *  ✅ SeekBar with real-time current/total time display
 *  ✅ Seek gesture support on SeekBar
 *  ✅ Animated waveform bars (smooth ValueAnimator, not random jitter)
 *  ✅ AudioFocusRequest (API 26+) + legacy AudioFocus for older devices
 *  ✅ AudioAttributes for proper media streaming classification
 *  ✅ MediaPlayer pause on onPause / release on onDestroy
 *  ✅ Buffering state with progress indicator during prepareAsync
 *  ✅ Error handling + retry once on player error
 *  ✅ Save/unsave sound with live saves count (Firebase transaction)
 *  ✅ Use this sound in Camera or Gallery
 *  ✅ Share sound via system share sheet
 *  ✅ All reels using this sound (grid — top 12, falls back to reels node)
 *  ✅ Related/similar sounds section (same genre, horizontal scroll)
 *  ✅ Trending rank badge + is_trending fallback flag
 *  ✅ Original audio vs remix indicator + Verified badge
 *  ✅ Start-trim chip: tap to go to ReelMusicTrimActivity
 *  ✅ Proper lifecycle management (no memory leaks)
 */
public class SoundDetailActivity extends AppCompatActivity
        implements AudioManager.OnAudioFocusChangeListener {

    // ─── Intent Extras ─────────────────────────────────────────────────────────
    public static final String EXTRA_SOUND_ID           = "sound_id";
    public static final String EXTRA_SOUND_TITLE        = "sound_title";
    public static final String EXTRA_SOUND_URL          = "sound_url";
    public static final String EXTRA_ARTIST             = "sound_artist";
    public static final String EXTRA_DURATION_MS        = "sound_duration_ms";
    public static final String EXTRA_COVER_URL          = "sound_cover_url";
    public static final String EXTRA_BPM                = "sound_bpm";
    public static final String EXTRA_GENRE              = "sound_genre";
    /**
     * Pass when opening SoundDetailActivity for a reel's own "Original Audio".
     * Holds the Cloudinary URL of the extracted audio track uploaded at post-time.
     * Priority over EXTRA_SOUND_URL and reel_video_url.
     */
    public static final String EXTRA_ORIGINAL_AUDIO_URL = "original_audio_url";

    // ─── Views ─────────────────────────────────────────────────────────────────
    private ImageButton  btnBack, btnPlayPause, btnShare;
    private TextView     tvSoundTitle, tvArtist, tvDuration, tvReelCount,
                         tvTrendingRank, tvSavesCount, tvBpm, tvGenre,
                         tvOriginalBadge, tvIsVerified;
    private TextView     btnUseSoundCamera, btnUseSoundGallery, btnTrimStart;
    private ImageView    ivSoundCover, ivDiscRing;
    private ImageButton  btnSaveSound;
    private RecyclerView rvReels, rvRelated;
    private ProgressBar  progressBar;
    private View         layoutSoundInfo;
    private LinearLayout layoutWaveform;
    private SeekBar      seekBar;
    private TextView     tvCurrentTime, tvTotalTime;

    // ─── State ─────────────────────────────────────────────────────────────────
    private String  soundId, soundTitle, soundUrl, artist, coverUrl, genre;
    private int     durationMs, bpm;
    private boolean isSaved         = false;
    private boolean isPlaying       = false;
    private boolean isPreparing     = false;
    private boolean userSeeking     = false;
    private boolean hasAudioFocus   = false;
    private boolean retried         = false;

    // ─── Playback ──────────────────────────────────────────────────────────────
    private MediaPlayer                mediaPlayer;
    private AudioManager               audioManager;
    private AudioFocusRequest          audioFocusRequest; // API 26+
    private final Handler              mainHandler    = new Handler(Looper.getMainLooper());

    // ─── Animation ─────────────────────────────────────────────────────────────
    private ObjectAnimator             discAnimator;
    private final List<ValueAnimator>  waveAnimators  = new ArrayList<>();
    private final Handler              seekHandler    = new Handler(Looper.getMainLooper());

    // ─── Data ──────────────────────────────────────────────────────────────────
    private final List<RelatedItem>    relatedItems   = new ArrayList<>();
    private final List<ReelThumbItem>  reelItems      = new ArrayList<>();
    private ReelThumbAdapter           reelThumbAdapter;

    // ─── Runnables ─────────────────────────────────────────────────────────────
    private final Runnable seekUpdateRunnable = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && isPlaying && !userSeeking) {
                try {
                    int pos = mediaPlayer.getCurrentPosition();
                    int dur = mediaPlayer.getDuration();
                    if (dur > 0 && seekBar != null) {
                        seekBar.setProgress((int)(100L * pos / dur));
                    }
                    if (tvCurrentTime != null) tvCurrentTime.setText(formatMs(pos));
                } catch (IllegalStateException ignored) {}
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

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        soundId    = getIntent().getStringExtra(EXTRA_SOUND_ID);
        soundTitle = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
        soundUrl   = getIntent().getStringExtra(EXTRA_SOUND_URL);
        artist     = getIntent().getStringExtra(EXTRA_ARTIST);
        durationMs = getIntent().getIntExtra(EXTRA_DURATION_MS, 0);
        coverUrl   = getIntent().getStringExtra(EXTRA_COVER_URL);
        bpm        = getIntent().getIntExtra(EXTRA_BPM, 0);
        genre      = getIntent().getStringExtra(EXTRA_GENRE);

        // Priority 1: originalAudioUrl — clean extracted audio
        String originalAudioUrl = getIntent().getStringExtra(EXTRA_ORIGINAL_AUDIO_URL);
        if (originalAudioUrl != null && !originalAudioUrl.isEmpty()) soundUrl = originalAudioUrl;

        // Priority 2: fallback to reel video URL (plays video as audio)
        if (soundUrl == null || soundUrl.isEmpty()) {
            String reelVideoUrl = getIntent().getStringExtra("reel_video_url");
            if (reelVideoUrl != null && !reelVideoUrl.isEmpty()) soundUrl = reelVideoUrl;
        }

        bindViews();
        populateSoundInfo();
        loadSoundData();
        loadReelsForSound();
        loadRelatedSounds();
        setupClickListeners();
        checkIfSaved();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pause playback when leaving screen (background / other activity)
        if (isPlaying) pausePlayback();
    }

    @Override
    protected void onDestroy() {
        seekHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        stopDiscAnimation();
        stopWaveAnimation();
        releaseMediaPlayer();
        abandonAudioFocus();
        super.onDestroy();
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  View binding
    // ───────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        btnBack           = findViewById(R.id.btn_sound_back);
        btnPlayPause      = findViewById(R.id.btn_sound_play_pause);
        btnShare          = findViewById(R.id.btn_sound_share);
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

        if (rvReels   != null) rvReels.setLayoutManager(new GridLayoutManager(this, 3));
        if (rvRelated != null) rvRelated.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        // SeekBar listener
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && tvCurrentTime != null && mediaPlayer != null) {
                        try {
                            int dur = mediaPlayer.getDuration();
                            if (dur > 0) tvCurrentTime.setText(formatMs((int)(dur * progress / 100L)));
                        } catch (IllegalStateException ignored) {}
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    userSeeking = false;
                    if (mediaPlayer != null && !isPreparing) {
                        try {
                            int dur = mediaPlayer.getDuration();
                            if (dur > 0) mediaPlayer.seekTo(dur * sb.getProgress() / 100);
                        } catch (IllegalStateException ignored) {}
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

        // Load cover art as a circle crop
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
            // Stagger each bar so they don't all animate in sync
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
        // Reset bars to static grey
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

        // Animate both the cover art and the disc ring together
        View[] targets = ivDiscRing != null
            ? new View[]{ivSoundCover, ivDiscRing}
            : new View[]{ivSoundCover};

        // If paused mid-rotation, resume from current angle
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
    //  Firebase data loading
    // ───────────────────────────────────────────────────────────────────────────

    private void loadSoundData() {
        if (soundId == null || soundId.isEmpty()) {
            if (progressBar    != null) progressBar.setVisibility(View.GONE);
            if (layoutSoundInfo!= null) layoutSoundInfo.setVisibility(View.VISIBLE);
            updatePlayButtonState();
            return;
        }
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

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

                    // Resolve audio URL from Firebase if not provided via intent
                    if (soundUrl == null || soundUrl.isEmpty()) {
                        String u = snap.child("audioUrl").getValue(String.class);
                        if (u == null) u = snap.child("audio_url").getValue(String.class);
                        if (u == null) u = snap.child("url").getValue(String.class);
                        if (u != null && !u.isEmpty()) soundUrl = u;
                    }

                    // Resolve cover URL from Firebase if not provided via intent
                    if ((coverUrl == null || coverUrl.isEmpty())) {
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

                    // Also get duration from Firebase if not passed via intent
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

                    if (progressBar    != null) progressBar.setVisibility(View.GONE);
                    if (layoutSoundInfo!= null) layoutSoundInfo.setVisibility(View.VISIBLE);

                    updatePlayButtonState();
                    updateTrimButtonVisibility();
                }

                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isFinishing() && progressBar != null) progressBar.setVisibility(View.GONE);
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
        reelThumbAdapter = new ReelThumbAdapter(reelItems, item -> {
            Intent i = new Intent(this, SingleReelPlayerActivity.class);
            i.putExtra("reel_id", item.reelId);
            startActivity(i);
        });
        rvReels.setAdapter(reelThumbAdapter);

        FirebaseUtils.db().getReference("sounds").child(soundId).child("reels")
            .limitToFirst(12)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    reelItems.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        String rid   = s.getKey();
                        String thumb = s.child("thumbnailUrl").getValue(String.class);
                        if (thumb == null) thumb = s.child("thumbnail").getValue(String.class);
                        String vid   = s.child("videoUrl").getValue(String.class);
                        if (rid != null)
                            reelItems.add(new ReelThumbItem(rid,
                                thumb != null ? thumb : "", vid != null ? vid : ""));
                    }
                    if (!reelItems.isEmpty()) reelThumbAdapter.notifyDataSetChanged();
                    else loadReelsFromReelsNode();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { loadReelsFromReelsNode(); }
            });
    }

    private void loadReelsFromReelsNode() {
        if (soundId == null || isFinishing() || isDestroyed()) return;
        FirebaseUtils.db().getReference("reels")
            .orderByChild("soundId").equalTo(soundId)
            .limitToFirst(12)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    for (DataSnapshot s : snap.getChildren()) {
                        String rid   = s.getKey();
                        String thumb = s.child("thumbnailUrl").getValue(String.class);
                        if (thumb == null) thumb = s.child("thumbnail").getValue(String.class);
                        String vid   = s.child("videoUrl").getValue(String.class);
                        if (rid != null)
                            reelItems.add(new ReelThumbItem(rid,
                                thumb != null ? thumb : "", vid != null ? vid : ""));
                    }
                    if (reelThumbAdapter != null) reelThumbAdapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
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
    //  Click listeners
    // ───────────────────────────────────────────────────────────────────────────

    private void setupClickListeners() {
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareSound());

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

        // btnTrimStart visibility managed by updateTrimButtonVisibility()
        if (btnTrimStart != null) btnTrimStart.setVisibility(View.GONE);
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Playback
    // ───────────────────────────────────────────────────────────────────────────

    private void togglePlayPause() {
        if (soundUrl == null || soundUrl.isEmpty()) {
            Toast.makeText(this, "Loading audio…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isPreparing) return; // Debounce while buffering

        if (isPlaying) {
            pausePlayback();
        } else {
            if (mediaPlayer == null) {
                initAndStartPlayer();
            } else {
                resumePlayback();
            }
        }
    }

    private void initAndStartPlayer() {
        if (!requestAudioFocus()) return;

        isPreparing = true;
        setPlayButtonLoading(true);

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setDataSource(soundUrl);
            mediaPlayer.setLooping(true);
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                runOnUiThread(() -> onPlayerError());
                return true;
            });
            mediaPlayer.setOnPreparedListener(mp -> {
                isPreparing = false;
                setPlayButtonLoading(false);
                // Update total time from actual media duration
                int dur = mp.getDuration();
                if (dur > 0 && tvTotalTime != null)
                    tvTotalTime.setText(formatMs(dur));
                mp.start();
                isPlaying = true;
                if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
                startWaveAnimation();
                startDiscAnimation();
                seekHandler.post(seekUpdateRunnable);
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                // looping=true so this only fires if looping is disabled externally
                pausePlayback();
                if (seekBar != null) seekBar.setProgress(0);
                if (tvCurrentTime != null) tvCurrentTime.setText("0:00");
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            isPreparing = false;
            setPlayButtonLoading(false);
            onPlayerError();
        }
    }

    private void resumePlayback() {
        if (!requestAudioFocus()) return;
        if (mediaPlayer == null) { initAndStartPlayer(); return; }
        try {
            mediaPlayer.start();
            isPlaying = true;
            if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
            startWaveAnimation();
            startDiscAnimation();
            seekHandler.post(seekUpdateRunnable);
        } catch (IllegalStateException ignored) {}
    }

    private void pausePlayback() {
        if (mediaPlayer != null) {
            try { mediaPlayer.pause(); } catch (IllegalStateException ignored) {}
        }
        isPlaying = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation();
        stopDiscAnimation();
        seekHandler.removeCallbacks(seekUpdateRunnable);
    }

    private void onPlayerError() {
        releaseMediaPlayer();
        setPlayButtonLoading(false);
        isPlaying   = false;
        isPreparing = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation();
        stopDiscAnimation();
        seekHandler.removeCallbacks(seekUpdateRunnable);

        if (!retried) {
            // Retry once before showing error to user
            retried = true;
            mainHandler.postDelayed(this::initAndStartPlayer, 800);
        } else {
            Toast.makeText(this, "Cannot play this audio", Toast.LENGTH_SHORT).show();
        }
    }

    private void setPlayButtonLoading(boolean loading) {
        if (btnPlayPause == null) return;
        if (progressBar  != null) progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnPlayPause.setEnabled(!loading);
        btnPlayPause.setAlpha(loading ? 0.5f : 1f);
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Audio Focus
    // ───────────────────────────────────────────────────────────────────────────

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setOnAudioFocusChangeListener(this)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .build();
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            //noinspection deprecation
            result = audioManager.requestAudioFocus(this,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                     || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
        return result != AudioManager.AUDIOFOCUS_REQUEST_FAILED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            //noinspection deprecation
            audioManager.abandonAudioFocus(this);
        }
        hasAudioFocus = false;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // Regained focus — resume and restore volume
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(1f, 1f);
                    if (!isPlaying) resumePlayback();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying) pausePlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Duck volume (lower while other app speaks/plays briefly)
                if (mediaPlayer != null) mediaPlayer.setVolume(0.2f, 0.2f);
                break;
        }
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
    //  Helper: state management
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

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop();    } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isPlaying   = false;
        isPreparing = false;
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Formatters
    // ───────────────────────────────────────────────────────────────────────────

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
        String reelId, thumbnailUrl, videoUrl;
        ReelThumbItem(String r, String t, String v) { reelId = r; thumbnailUrl = t; videoUrl = v; }
    }

    static class ReelThumbAdapter extends RecyclerView.Adapter<ReelThumbAdapter.VH> {
        interface OnClick { void click(ReelThumbItem item); }
        private final List<ReelThumbItem> items;
        private final OnClick             onClick;
        ReelThumbAdapter(List<ReelThumbItem> items, OnClick onClick) {
            this.items = items; this.onClick = onClick;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int vt) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media_thumb, parent, false);
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            lp.height = (int)(110 * v.getResources().getDisplayMetrics().density);
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
            h.itemView.setOnClickListener(v -> onClick.click(item));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView ivThumb;
            VH(View v) { super(v); ivThumb = v.findViewById(R.id.iv_media_thumb); }
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
