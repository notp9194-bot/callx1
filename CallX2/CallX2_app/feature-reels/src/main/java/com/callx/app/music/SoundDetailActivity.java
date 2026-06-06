package com.callx.app.music;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelMusicTrimActivity;
import com.callx.app.upload.ReelUploadActivity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.*;

/**
 * SoundDetailActivity — Production-level Audio/Sound Detail Screen.
 *
 * Features:
 *  ✅ Sound info: title, artist, duration, BPM, mood
 *  ✅ Cover art loaded via Glide
 *  ✅ Animated waveform bar (random bars that animate while playing)
 *  ✅ Save/unsave sound with live saves count
 *  ✅ Use this sound in Camera or Gallery
 *  ✅ Share sound via system share sheet
 *  ✅ All reels using this sound (grid — top 12)
 *  ✅ Related/similar sounds section (same genre)
 *  ✅ Trending rank badge
 *  ✅ Play/Pause audio preview
 *  ✅ Total reel count using this sound
 *  ✅ Total saves count
 *  ✅ Original audio vs remix indicator
 *  ✅ Start-trim chip: tap to go to ReelMusicTrimActivity
 */
public class SoundDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SOUND_ID           = "sound_id";
    public static final String EXTRA_SOUND_TITLE        = "sound_title";
    public static final String EXTRA_SOUND_URL          = "sound_url";
    public static final String EXTRA_ARTIST             = "sound_artist";
    public static final String EXTRA_DURATION_MS        = "sound_duration_ms";
    public static final String EXTRA_COVER_URL          = "sound_cover_url";
    public static final String EXTRA_BPM                = "sound_bpm";
    public static final String EXTRA_GENRE              = "sound_genre";
    /**
     * Pass this extra when opening SoundDetailActivity for a reel's own "Original Audio".
     * It holds the Cloudinary URL of the extracted audio track uploaded at post-time.
     * Has higher priority than EXTRA_SOUND_URL and reel_video_url.
     */
    public static final String EXTRA_ORIGINAL_AUDIO_URL = "original_audio_url";

    private ImageButton btnBack, btnPlayPause, btnShare;
    private TextView    tvSoundTitle, tvArtist, tvDuration, tvReelCount,
                        tvTrendingRank, tvSavesCount, tvBpm, tvGenre,
                        tvOriginalBadge, tvIsVerified;
    private TextView    btnUseSoundCamera, btnUseSoundGallery, btnTrimStart;
    private ImageView   ivSoundCover;
    private ImageButton btnSaveSound;
    private RecyclerView rvReels;
    private RecyclerView rvRelated;
    private ProgressBar progressBar;
    private View        layoutSoundInfo;
    private LinearLayout layoutWaveform;

    private String soundId, soundTitle, soundUrl, artist, coverUrl, genre;
    private int    durationMs, bpm;
    private boolean isSaved   = false;
    private boolean isPlaying = false;
    private android.media.MediaPlayer mediaPlayer;
    private final Handler waveHandler = new Handler(Looper.getMainLooper());

    private final List<String>        reelIds      = new ArrayList<>();
    private final List<RelatedItem>  relatedItems = new ArrayList<>();
    private final List<ReelThumbItem> reelItems   = new ArrayList<>();
    private ReelThumbAdapter          reelThumbAdapter;

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

        // Priority 1: originalAudioUrl — clean extracted audio (Cloudinary, uploaded at post-time)
        String originalAudioUrl = getIntent().getStringExtra(EXTRA_ORIGINAL_AUDIO_URL);
        if (originalAudioUrl != null && !originalAudioUrl.isEmpty()) {
            soundUrl = originalAudioUrl;
        }
        // Priority 2: if still no soundUrl, fall back to reel's video URL (plays video as audio)
        if (soundUrl == null || soundUrl.isEmpty()) {
            String reelVideoUrl = getIntent().getStringExtra("reel_video_url");
            if (reelVideoUrl != null && !reelVideoUrl.isEmpty()) {
                soundUrl = reelVideoUrl;
            }
        }

        bindViews();
        populateSoundInfo();
        loadSoundData();
        loadReelsForSound();
        loadRelatedSounds();
        setupClickListeners();
        checkIfSaved();
    }

    private void bindViews() {
        btnBack              = findViewById(R.id.btn_sound_back);
        btnPlayPause         = findViewById(R.id.btn_sound_play_pause);
        btnShare             = findViewById(R.id.btn_sound_share);
        tvSoundTitle         = findViewById(R.id.tv_sound_title);
        tvArtist             = findViewById(R.id.tv_sound_artist);
        tvDuration           = findViewById(R.id.tv_sound_duration);
        tvReelCount          = findViewById(R.id.tv_sound_reel_count);
        tvTrendingRank       = findViewById(R.id.tv_sound_trending_rank);
        tvSavesCount         = findViewById(R.id.tv_sound_saves_count);
        tvBpm                = findViewById(R.id.tv_sound_bpm);
        tvGenre              = findViewById(R.id.tv_sound_genre);
        tvOriginalBadge      = findViewById(R.id.tv_sound_original_badge);
        tvIsVerified         = findViewById(R.id.tv_sound_verified_badge);
        btnUseSoundCamera    = findViewById(R.id.btn_use_sound_camera);
        btnUseSoundGallery   = findViewById(R.id.btn_use_sound_gallery);
        btnTrimStart         = findViewById(R.id.btn_trim_start);
        ivSoundCover         = findViewById(R.id.iv_sound_cover);
        btnSaveSound         = findViewById(R.id.btn_save_sound);
        rvReels              = findViewById(R.id.rv_sound_reels);
        rvRelated            = findViewById(R.id.rv_related_sounds);
        progressBar          = findViewById(R.id.progress_sound);
        layoutSoundInfo      = findViewById(R.id.layout_sound_info);
        layoutWaveform       = findViewById(R.id.layout_sound_waveform);

        if (rvReels   != null) rvReels.setLayoutManager(new GridLayoutManager(this, 3));
        if (rvRelated != null) rvRelated.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
    }

    private void populateSoundInfo() {
        if (tvSoundTitle != null) tvSoundTitle.setText(soundTitle != null ? soundTitle : "Unknown Sound");
        if (tvArtist     != null) tvArtist.setText(artist != null ? "• " + artist : "• Original Audio");

        if (durationMs > 0 && tvDuration != null) {
            int sec = durationMs / 1000;
            tvDuration.setText(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));
        }

        if (tvBpm != null) {
            if (bpm > 0) {
                tvBpm.setText(bpm + " BPM");
                tvBpm.setVisibility(View.VISIBLE);
            } else {
                tvBpm.setVisibility(View.GONE);
            }
        }

        if (tvGenre != null) {
            if (genre != null && !genre.isEmpty()) {
                tvGenre.setText(genre);
                tvGenre.setVisibility(View.VISIBLE);
            } else {
                tvGenre.setVisibility(View.GONE);
            }
        }

        if (ivSoundCover != null) {
            if (coverUrl != null && !coverUrl.isEmpty()) {
                Glide.with(this).load(coverUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(ivSoundCover);
            } else {
                ivSoundCover.setImageResource(R.drawable.ic_music_note);
            }
        }

        drawStaticWaveform();
    }

    private void drawStaticWaveform() {
        if (layoutWaveform == null) return;
        layoutWaveform.removeAllViews();
        Random rng = new Random(soundTitle != null ? soundTitle.hashCode() : 0);
        int bars = 32;
        int barW = (int)(5 * getResources().getDisplayMetrics().density);
        int gap  = (int)(3 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < bars; i++) {
            View bar = new View(this);
            int h = (int)((12 + rng.nextInt(36)) * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(barW, h);
            lp.setMargins(gap, 0, gap, 0);
            lp.gravity = android.view.Gravity.BOTTOM;
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(isPlaying ? 0xFFFF3B5C : 0x88FFFFFF);
            bar.setTag("waveBar");
            layoutWaveform.addView(bar);
        }
    }

    private final Runnable waveAnimRunnable = new Runnable() {
        @Override public void run() {
            if (!isPlaying || layoutWaveform == null) return;
            Random rng = new Random();
            for (int i = 0; i < layoutWaveform.getChildCount(); i++) {
                View bar = layoutWaveform.getChildAt(i);
                if ("waveBar".equals(bar.getTag())) {
                    int newH = (int)((12 + rng.nextInt(36)) * getResources().getDisplayMetrics().density);
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
                    lp.height = newH;
                    bar.setLayoutParams(lp);
                    bar.setBackgroundColor(0xFFFF3B5C);
                }
            }
            waveHandler.postDelayed(this, 120);
        }
    };

    private void startWaveAnimation() {
        waveHandler.removeCallbacks(waveAnimRunnable);
        waveHandler.post(waveAnimRunnable);
    }

    private void stopWaveAnimation() {
        waveHandler.removeCallbacks(waveAnimRunnable);
        drawStaticWaveform();
    }

    private void loadSoundData() {
        // soundId may be null or empty for pure "Original Audio" reels
        if (soundId == null || soundId.isEmpty()) {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (layoutSoundInfo != null) layoutSoundInfo.setVisibility(View.VISIBLE);
            // soundUrl may still be valid (reel's own audio URL passed via intent)
            updatePlayButtonState();
            return;
        }
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

        FirebaseUtils.db().getReference("sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;

                    Long count   = snap.child("reel_count").getValue(Long.class);
                    Long rank    = snap.child("trending_rank").getValue(Long.class);
                    Long saves   = snap.child("total_saves").getValue(Long.class);
                    Boolean orig = snap.child("is_original").getValue(Boolean.class);
                    Boolean ver  = snap.child("is_verified").getValue(Boolean.class);

                    // ✅ FIX: fetch audioUrl from Firebase and update soundUrl if it was missing
                    if (soundUrl == null || soundUrl.isEmpty()) {
                        String fetchedUrl = snap.child("audioUrl").getValue(String.class);
                        if (fetchedUrl == null || fetchedUrl.isEmpty())
                            fetchedUrl = snap.child("audio_url").getValue(String.class);
                        if (fetchedUrl == null || fetchedUrl.isEmpty())
                            fetchedUrl = snap.child("url").getValue(String.class);
                        if (fetchedUrl != null && !fetchedUrl.isEmpty()) {
                            soundUrl = fetchedUrl;
                        }
                    }

                    // Also update coverUrl if missing
                    if (coverUrl == null || coverUrl.isEmpty()) {
                        String fetchedCover = snap.child("coverUrl").getValue(String.class);
                        if (fetchedCover == null || fetchedCover.isEmpty())
                            fetchedCover = snap.child("cover_url").getValue(String.class);
                        if (fetchedCover != null && !fetchedCover.isEmpty()) {
                            coverUrl = fetchedCover;
                            if (ivSoundCover != null) {
                                com.bumptech.glide.Glide.with(SoundDetailActivity.this)
                                    .load(coverUrl)
                                    .placeholder(R.drawable.ic_music_note)
                                    .centerCrop()
                                    .into(ivSoundCover);
                            }
                        }
                    }

                    if (count == null) count = 0L;
                    if (rank  == null) rank  = 0L;
                    if (saves == null) saves = 0L;

                    if (tvReelCount != null)
                        tvReelCount.setText(formatCount(count) + " Reels");

                    if (tvSavesCount != null) {
                        tvSavesCount.setText(formatCount(saves) + " Saves");
                        tvSavesCount.setVisibility(View.VISIBLE);
                    }

                    if (tvTrendingRank != null) {
                        if (rank > 0 && rank <= 50) {
                            tvTrendingRank.setVisibility(View.VISIBLE);
                            tvTrendingRank.setText("#" + rank + " Trending");
                        } else {
                            tvTrendingRank.setVisibility(View.GONE);
                        }
                    }

                    if (tvOriginalBadge != null)
                        tvOriginalBadge.setVisibility(
                            Boolean.TRUE.equals(orig) ? View.VISIBLE : View.GONE);

                    if (tvIsVerified != null)
                        tvIsVerified.setVisibility(
                            Boolean.TRUE.equals(ver) ? View.VISIBLE : View.GONE);

                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (layoutSoundInfo != null) layoutSoundInfo.setVisibility(View.VISIBLE);

                    // ✅ Update play button & trim button visibility after URL is resolved
                    updatePlayButtonState();
                    updateTrimButtonVisibility();
                }

                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isFinishing() && progressBar != null)
                        progressBar.setVisibility(View.GONE);
                    updatePlayButtonState();
                }
            });
    }

    /** Shows/hides play button hint based on whether a playable URL is available. */
    private void updatePlayButtonState() {
        if (btnPlayPause == null) return;
        boolean hasUrl = soundUrl != null && !soundUrl.isEmpty();
        btnPlayPause.setAlpha(hasUrl ? 1f : 0.45f);
        btnPlayPause.setEnabled(hasUrl);
    }

    /** Re-evaluates trim button visibility after soundUrl may have been fetched. */
    private void updateTrimButtonVisibility() {
        if (btnTrimStart == null) return;
        boolean hasUrl = soundUrl != null && !soundUrl.isEmpty();
        btnTrimStart.setVisibility(hasUrl ? View.VISIBLE : View.GONE);
        if (hasUrl) {
            btnTrimStart.setOnClickListener(v -> {
                android.content.Intent i = new android.content.Intent(
                    this, ReelMusicTrimActivity.class);
                i.putExtra(ReelMusicTrimActivity.EXTRA_SOUND_ID,    soundId);
                i.putExtra(ReelMusicTrimActivity.EXTRA_SOUND_TITLE, soundTitle);
                i.putExtra(ReelMusicTrimActivity.EXTRA_SOUND_URL,   soundUrl);
                i.putExtra(ReelMusicTrimActivity.EXTRA_DURATION_MS, durationMs);
                startActivity(i);
            });
        }
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
                                id != null ? id : "",
                                title, art != null ? art : "",
                                cover != null ? cover : "",
                                url   != null ? url   : ""));
                        }
                    }
                    if (rvRelated != null && !relatedItems.isEmpty()) {
                        rvRelated.setAdapter(new RelatedAdapter(relatedItems,
                            item -> {
                                Intent i = new Intent(SoundDetailActivity.this,
                                    SoundDetailActivity.class);
                                i.putExtra(EXTRA_SOUND_ID,    item.id);
                                i.putExtra(EXTRA_SOUND_TITLE, item.title);
                                i.putExtra(EXTRA_ARTIST,      item.artist);
                                i.putExtra(EXTRA_SOUND_URL,   item.audioUrl);
                                i.putExtra(EXTRA_COVER_URL,   item.coverUrl);
                                i.putExtra(EXTRA_GENRE,       genre);
                                startActivity(i);
                            }));
                        View relatedSection = findViewById(R.id.layout_related_sounds_section);
                        if (relatedSection != null) relatedSection.setVisibility(View.VISIBLE);
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
                                thumb != null ? thumb : "",
                                vid   != null ? vid   : ""));
                    }
                    if (!reelItems.isEmpty()) {
                        reelThumbAdapter.notifyDataSetChanged();
                    } else {
                        loadReelsFromReelsNode();
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    loadReelsFromReelsNode();
                }
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
                                thumb != null ? thumb : "",
                                vid   != null ? vid   : ""));
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

    private void updateSaveButton() {
        if (btnSaveSound != null) {
            btnSaveSound.setImageResource(isSaved
                ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        }
    }

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
            i.putExtra("selected_sound_id",    soundId);
            i.putExtra("selected_sound_title", soundTitle);
            i.putExtra("selected_sound_url",   soundUrl);
            // ✅ NEW: Replace mic audio with selected sound URL during recording
            i.putExtra("replace_audio_with_sound", true);
            startActivity(i);
        });

        if (btnUseSoundGallery != null) btnUseSoundGallery.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelUploadActivity.class);
            i.putExtra("selected_sound_id",    soundId);
            i.putExtra("selected_sound_title", soundTitle);
            i.putExtra("selected_sound_url",   soundUrl);
            startActivity(i);
        });

        // btnTrimStart visibility managed by updateTrimButtonVisibility() after soundUrl resolves
        if (btnTrimStart != null) btnTrimStart.setVisibility(View.GONE);
    }

    private void togglePlayPause() {
        if (soundUrl == null || soundUrl.isEmpty()) {
            Toast.makeText(this, "Loading audio, please wait\u2026", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mediaPlayer == null) {
            if (btnPlayPause != null) btnPlayPause.setEnabled(false);
            mediaPlayer = new android.media.MediaPlayer();
            try {
                mediaPlayer.setDataSource(soundUrl);
                mediaPlayer.setLooping(true);
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Cannot play this audio", Toast.LENGTH_SHORT).show();
                        releaseMediaPlayer();
                        if (btnPlayPause != null) {
                            btnPlayPause.setEnabled(true);
                            btnPlayPause.setImageResource(R.drawable.ic_play);
                        }
                        stopWaveAnimation();
                    });
                    return true;
                });
                mediaPlayer.prepareAsync();
                mediaPlayer.setOnPreparedListener(mp -> {
                    if (btnPlayPause != null) btnPlayPause.setEnabled(true);
                    mp.start();
                    isPlaying = true;
                    if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
                    startWaveAnimation();
                });
            } catch (Exception e) {
                Toast.makeText(this, "Cannot play this audio", Toast.LENGTH_SHORT).show();
                releaseMediaPlayer();
                if (btnPlayPause != null) btnPlayPause.setEnabled(true);
            }
        } else if (isPlaying) {
            mediaPlayer.pause();
            isPlaying = false;
            if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
            stopWaveAnimation();
        } else {
            mediaPlayer.start();
            isPlaying = true;
            if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
            startWaveAnimation();
        }
    }

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
        DatabaseReference savesRef = FirebaseUtils.db()
            .getReference("sounds").child(soundId).child("total_saves");
        savesRef.runTransaction(new Transaction.Handler() {
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
        String shareText = "Check out this sound: " + (soundTitle != null ? soundTitle : "")
            + (artist != null && !artist.isEmpty() ? " by " + artist : "");
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(shareIntent, "Share sound via"));
    }

    private String formatCount(long count) {
        if (count >= 1_000_000) return String.format(Locale.US, "%.1fM", count / 1_000_000.0);
        if (count >= 1_000)     return String.format(Locale.US, "%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isPlaying = false;
    }

    @Override
    protected void onDestroy() {
        waveHandler.removeCallbacksAndMessages(null);
        releaseMediaPlayer();
        super.onDestroy();
    }

    static class ReelThumbItem {
        String reelId, thumbnailUrl, videoUrl;
        ReelThumbItem(String r, String t, String v) { reelId = r; thumbnailUrl = t; videoUrl = v; }
    }

    static class ReelThumbAdapter extends RecyclerView.Adapter<ReelThumbAdapter.VH> {
        interface OnClick { void click(ReelThumbItem item); }
        private final List<ReelThumbItem> items;
        private final OnClick onClick;
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
                com.bumptech.glide.Glide.with(h.ivThumb)
                    .load(item.thumbnailUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(h.ivThumb);
            } else {
                h.ivThumb.setImageResource(R.drawable.ic_play);
                h.ivThumb.setColorFilter(android.graphics.Color.argb(100, 255, 255, 255));
            }
            h.itemView.setOnClickListener(v -> onClick.click(item));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView ivThumb;
            VH(View v) { super(v); ivThumb = v.findViewById(R.id.iv_media_thumb); }
        }
    }

    static class RelatedItem {
        String id, title, artist, coverUrl, audioUrl;
        RelatedItem(String i, String t, String a, String c, String u) {
            id = i; title = t; artist = a; coverUrl = c; audioUrl = u;
        }
    }

    static class RelatedAdapter extends RecyclerView.Adapter<RelatedAdapter.VH> {
        interface OnClick { void click(RelatedItem item); }
        private final List<RelatedItem> items;
        private final OnClick onClick;
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
            h.tvArtist.setText(item.artist);
            if (item.coverUrl != null && !item.coverUrl.isEmpty()) {
                Glide.with(h.ivCover).load(item.coverUrl)
                    .placeholder(R.drawable.ic_music_note).centerCrop().into(h.ivCover);
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
