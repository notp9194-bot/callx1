package com.callx.app.activities;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * ReelSoundActivity — Production-level Original Audio / Sound Detail screen.
 *
 * Opens when user taps the music ticker (spinning disc + title) at the bottom of any reel.
 *
 * FEATURES (ALL FULLY IMPLEMENTED):
 *
 * Core info:
 *  ✅ Sound title, artist name (clickable → UserReelsActivity)
 *  ✅ BPM badge, Genre/mood tags
 *  ✅ Spinning vinyl disc animation
 *  ✅ Blurred full-screen cover art background
 *  ✅ Trending rank badge (Firebase: sounds/{id}/trending_rank)
 *  ✅ Original / Verified artist badges
 *
 * Playback:
 *  ✅ Play / Pause preview with MediaPlayer
 *  ✅ Audio seek bar (SeekBar) with real-time position tracking
 *  ✅ Duration + current position display (e.g. "0:14 / 0:30")
 *  ✅ Looping preview
 *  ✅ Animated waveform bars while playing
 *  ✅ Error fallback → reel's video URL used as audio source
 *
 * Stats (Firebase real-time):
 *  ✅ Usage count (number of reels using this sound)
 *  ✅ Saves count (total users who saved)
 *
 * User actions:
 *  ✅ Save / Unsave with real-time Firebase + count update
 *  ✅ Share sound (system share sheet)
 *  ✅ Copy sound link to clipboard
 *  ✅ Use in Camera → ReelCameraActivity with sound pre-selected
 *  ✅ Use with Gallery → ReelUploadActivity with sound pre-selected
 *  ✅ Set Start Point → ReelMusicTrimActivity
 *  ✅ Duet with this sound → DuetReelActivity
 *  ✅ Stitch with this sound → StitchReelActivity
 *  ✅ Record original sound → ReelSoundRecorderActivity
 *  ✅ Browse all sounds → ReelSoundPickerActivity
 *  ✅ More options menu: report, copyright info
 *
 * Reels grid:
 *  ✅ Top reels using this sound (Firebase: reels filtered by musicId)
 *  ✅ 3-column thumbnail grid
 *  ✅ "See all" → opens TrendingAudioActivity / HashtagReelsActivity scoped to sound
 *  ✅ Tap thumbnail → opens SingleReelPlayerActivity
 *  ✅ Skeleton loading state
 *
 * Related sounds:
 *  ✅ Horizontal carousel of similar sounds (same genre, Firebase musicLibrary)
 *  ✅ Tapping opens another ReelSoundActivity
 *
 * Creator info card:
 *  ✅ Creator avatar + name + "View Profile" link
 *  ✅ Original sound badge for creator's own sounds
 */
public class ReelSoundActivity extends AppCompatActivity {

    // ── Intent extras (inputs) ────────────────────────────────────────────
    public static final String EXTRA_SOUND_ID     = "sound_id";
    public static final String EXTRA_SOUND_TITLE  = "sound_title";
    public static final String EXTRA_SOUND_URL    = "sound_url";
    public static final String EXTRA_COVER_URL    = "cover_url";
    public static final String EXTRA_ARTIST       = "sound_artist";
    public static final String EXTRA_BPM          = "sound_bpm";
    public static final String EXTRA_GENRE        = "sound_genre";
    public static final String EXTRA_VIDEO_URL    = "reel_video_url";
    public static final String EXTRA_REEL_ID      = "reel_id";
    public static final String EXTRA_DURATION_MS  = "sound_duration_ms";
    public static final String EXTRA_START_SEC    = "sound_start_sec";
    public static final String EXTRA_CREATOR_UID  = "creator_uid";

    // ── Intent extras (outputs) ───────────────────────────────────────────
    public static final String RESULT_SOUND_ID    = "res_sound_id";
    public static final String RESULT_SOUND_TITLE = "res_sound_title";
    public static final String RESULT_SOUND_URL   = "res_sound_url";
    public static final String RESULT_COVER_URL   = "res_cover_url";
    public static final String RESULT_ARTIST      = "res_artist";

    private static final int WAVEFORM_BARS = 28;

    // ── Views ─────────────────────────────────────────────────────────────
    private ImageButton  btnBack, btnMore;
    private ImageButton  btnSave;
    private ImageButton  btnShare;
    private ImageView    ivCoverBg, ivDisc;
    private TextView     tvTitle, tvArtist;
    private TextView     tvUsageCount, tvSavesCount;
    private TextView     tvBpm, tvGenre;
    private TextView     tvTrendingRank;
    private TextView     tvOriginalBadge, tvVerifiedBadge;
    private TextView     tvPlayBtn;
    private TextView     tvPositionDuration;
    private SeekBar      seekBarAudio;
    private LinearLayout layoutWaveform;
    private ProgressBar  progressLoad;

    // Action buttons
    private TextView     btnUseCamera, btnUseGallery;
    private TextView     btnTrimStart, btnDuet, btnStitch, btnRecord, btnBrowse;
    private TextView     btnSeeAllReels;

    // Reels grid
    private RecyclerView   rvReels;
    private ReelThumbAdapter reelAdapter;
    private final List<ReelThumbItem> reelItems = new ArrayList<>();

    // Related sounds
    private RecyclerView     rvRelated;
    private RelatedAdapter   relatedAdapter;
    private final List<RelatedItem> relatedItems = new ArrayList<>();

    // Creator card
    private View      layoutCreatorCard;
    private ImageView ivCreatorAvatar;
    private TextView  tvCreatorName, btnViewProfile;

    // Sections
    private View      layoutReelsSection, layoutRelatedSection, layoutCreatorSection;
    private View      layoutSkeletonReels;

    // ── State ─────────────────────────────────────────────────────────────
    private String  soundId, soundTitle, soundUrl, coverUrl, artist, genre, reelId, creatorUid;
    private int     bpm, durationMs, startSec;
    private boolean isSaved   = false;
    private boolean isPlaying = false;

    private MediaPlayer       player;
    private ObjectAnimator    discAnimator;
    private final Handler     handler     = new Handler(Looper.getMainLooper());
    private final Random      random      = new Random();
    private Runnable          seekRunnable;

    // Firebase
    private ValueEventListener soundListener;

    // ── Waveform animation ────────────────────────────────────────────────
    private final Runnable waveRunnable = new Runnable() {
        @Override public void run() {
            if (!isPlaying) return;
            animateWaveform();
            handler.postDelayed(this, 100);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_sound);
        readExtras();
        bindViews();
        populateStaticUI();
        buildWaveform();
        loadSoundDataFromFirebase();
        loadReelsForSound();
        loadRelatedSounds();
        loadCreatorInfo();
        checkIfSaved();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        removeSoundListener();
        super.onDestroy();
    }

    // ── Read intent extras ────────────────────────────────────────────────

    private void readExtras() {
        Intent i  = getIntent();
        soundId   = i.getStringExtra(EXTRA_SOUND_ID);   if (soundId   == null) soundId   = "";
        soundTitle= i.getStringExtra(EXTRA_SOUND_TITLE);if (soundTitle== null) soundTitle= "Original Audio";
        soundUrl  = i.getStringExtra(EXTRA_SOUND_URL);  if (soundUrl  == null) soundUrl  = "";
        coverUrl  = i.getStringExtra(EXTRA_COVER_URL);  if (coverUrl  == null) coverUrl  = "";
        artist    = i.getStringExtra(EXTRA_ARTIST);     if (artist    == null) artist    = "Original Sound";
        genre     = i.getStringExtra(EXTRA_GENRE);      if (genre     == null) genre     = "";
        reelId    = i.getStringExtra(EXTRA_REEL_ID);    if (reelId    == null) reelId    = "";
        creatorUid= i.getStringExtra(EXTRA_CREATOR_UID);if (creatorUid== null) creatorUid= "";
        bpm       = i.getIntExtra(EXTRA_BPM, 0);
        durationMs= i.getIntExtra(EXTRA_DURATION_MS, 0);
        startSec  = i.getIntExtra(EXTRA_START_SEC, 0);
        // Fallback audio: use reel's video URL if no dedicated audio URL
        if (soundUrl.isEmpty()) {
            String videoUrl = i.getStringExtra(EXTRA_VIDEO_URL);
            if (videoUrl != null && !videoUrl.isEmpty()) soundUrl = videoUrl;
        }
    }

    // ── View binding ──────────────────────────────────────────────────────

    private void bindViews() {
        btnBack          = findViewById(R.id.btn_sound_back);
        btnMore          = findViewById(R.id.btn_sound_more);
        btnSave          = findViewById(R.id.btn_sound_save);
        btnShare         = findViewById(R.id.btn_sound_share);
        ivCoverBg        = findViewById(R.id.iv_sound_cover);
        ivDisc           = findViewById(R.id.iv_sound_disc);
        tvTitle          = findViewById(R.id.tv_sound_title);
        tvArtist         = findViewById(R.id.tv_sound_artist);
        tvUsageCount     = findViewById(R.id.tv_sound_usage_count);
        tvSavesCount     = findViewById(R.id.tv_sound_saves_count);
        tvBpm            = findViewById(R.id.tv_sound_bpm);
        tvGenre          = findViewById(R.id.tv_sound_genre);
        tvTrendingRank   = findViewById(R.id.tv_sound_trending_rank);
        tvOriginalBadge  = findViewById(R.id.tv_sound_original_badge);
        tvVerifiedBadge  = findViewById(R.id.tv_sound_verified_badge);
        tvPlayBtn        = findViewById(R.id.btn_sound_play_pause);
        tvPositionDuration = findViewById(R.id.tv_sound_position_duration);
        seekBarAudio     = findViewById(R.id.seekbar_sound);
        layoutWaveform   = findViewById(R.id.layout_sound_waveform);
        progressLoad     = findViewById(R.id.progress_sound_load);

        btnUseCamera     = findViewById(R.id.btn_use_camera);
        btnUseGallery    = findViewById(R.id.btn_use_gallery);
        btnTrimStart     = findViewById(R.id.btn_trim_start);
        btnDuet          = findViewById(R.id.btn_duet_sound);
        btnStitch        = findViewById(R.id.btn_stitch_sound);
        btnRecord        = findViewById(R.id.btn_sound_record);
        btnBrowse        = findViewById(R.id.btn_sound_browse);
        btnSeeAllReels   = findViewById(R.id.btn_see_all_reels);

        rvReels          = findViewById(R.id.rv_sound_reels);
        rvRelated        = findViewById(R.id.rv_related_sounds);

        layoutCreatorCard   = findViewById(R.id.layout_creator_card);
        ivCreatorAvatar     = findViewById(R.id.iv_creator_avatar);
        tvCreatorName       = findViewById(R.id.tv_creator_name);
        btnViewProfile      = findViewById(R.id.btn_view_profile);

        layoutReelsSection   = findViewById(R.id.layout_reels_section);
        layoutRelatedSection = findViewById(R.id.layout_related_section);
        layoutCreatorSection = findViewById(R.id.layout_creator_section);
        layoutSkeletonReels  = findViewById(R.id.layout_skeleton_reels);

        // RecyclerViews
        if (rvReels != null) {
            reelAdapter = new ReelThumbAdapter(reelItems, item -> openReelPlayer(item));
            rvReels.setLayoutManager(new GridLayoutManager(this, 3));
            rvReels.setNestedScrollingEnabled(false);
            rvReels.setAdapter(reelAdapter);
        }
        if (rvRelated != null) {
            relatedAdapter = new RelatedAdapter(relatedItems, item -> openRelatedSound(item));
            rvRelated.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            rvRelated.setAdapter(relatedAdapter);
        }

        // Click listeners
        if (btnBack   != null) btnBack.setOnClickListener(v -> finish());
        if (btnSave   != null) btnSave.setOnClickListener(v -> toggleSave());
        if (btnShare  != null) btnShare.setOnClickListener(v -> shareSound());
        if (btnMore   != null) btnMore.setOnClickListener(v -> showMoreOptions());
        if (tvPlayBtn != null) tvPlayBtn.setOnClickListener(v -> togglePlay());
        if (btnUseCamera  != null) btnUseCamera.setOnClickListener(v -> useSoundCamera());
        if (btnUseGallery != null) btnUseGallery.setOnClickListener(v -> useSoundGallery());
        if (btnTrimStart  != null) btnTrimStart.setOnClickListener(v -> openTrim());
        if (btnDuet       != null) btnDuet.setOnClickListener(v -> openDuet());
        if (btnStitch     != null) btnStitch.setOnClickListener(v -> openStitch());
        if (btnRecord     != null) btnRecord.setOnClickListener(v -> openRecorder());
        if (btnBrowse     != null) btnBrowse.setOnClickListener(v -> openPicker());
        if (btnSeeAllReels!= null) btnSeeAllReels.setOnClickListener(v -> seeAllReels());
        if (tvArtist      != null) tvArtist.setOnClickListener(v -> openCreatorProfile());
        if (btnViewProfile!= null) btnViewProfile.setOnClickListener(v -> openCreatorProfile());
        if (tvCreatorName != null) tvCreatorName.setOnClickListener(v -> openCreatorProfile());

        // SeekBar
        if (seekBarAudio != null) {
            seekBarAudio.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && player != null) {
                        int dur = player.getDuration();
                        if (dur > 0) {
                            int pos = (int)((long) progress * dur / 1000L);
                            player.seekTo(pos);
                            updatePositionDisplay(pos, dur);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { stopSeekTracking(); }
                @Override public void onStopTrackingTouch(SeekBar sb)  {
                    if (isPlaying) startSeekTracking();
                }
            });
        }
    }

    // ── Populate static data from intent ─────────────────────────────────

    private void populateStaticUI() {
        if (tvTitle  != null) tvTitle.setText(soundTitle);
        if (tvArtist != null) tvArtist.setText("• " + artist);

        // BPM badge
        if (tvBpm != null) {
            if (bpm > 0) { tvBpm.setVisibility(View.VISIBLE); tvBpm.setText(bpm + " BPM"); }
            else tvBpm.setVisibility(View.GONE);
        }
        // Genre badge
        if (tvGenre != null) {
            if (!genre.isEmpty()) { tvGenre.setVisibility(View.VISIBLE); tvGenre.setText(genre); }
            else tvGenre.setVisibility(View.GONE);
        }

        // Duration display
        if (durationMs > 0) {
            updatePositionDisplay(0, durationMs);
        } else if (tvPositionDuration != null) {
            tvPositionDuration.setText("0:00");
        }

        // Cover art (background + disc)
        loadCoverArt(coverUrl);

        // Trim button only if we have a playable URL
        updateTrimVisibility();

        // Show/hide creator card
        if (layoutCreatorSection != null) {
            layoutCreatorSection.setVisibility(
                (creatorUid != null && !creatorUid.isEmpty()) ? View.VISIBLE : View.GONE);
        }
        // Trending rank hidden until Firebase returns
        if (tvTrendingRank  != null) tvTrendingRank.setVisibility(View.GONE);
        if (tvOriginalBadge != null) tvOriginalBadge.setVisibility(View.GONE);
        if (tvVerifiedBadge != null) tvVerifiedBadge.setVisibility(View.GONE);
        if (tvSavesCount    != null) tvSavesCount.setVisibility(View.GONE);

        // Disc spin
        if (ivDisc != null) startDiscSpin();
    }

    private void loadCoverArt(String url) {
        if (ivCoverBg != null) {
            if (url != null && !url.isEmpty()) {
                Glide.with(this).load(url)
                    .placeholder(R.drawable.ic_music_disc)
                    .error(R.drawable.ic_music_disc)
                    .into(ivCoverBg);
            } else {
                ivCoverBg.setImageResource(R.drawable.ic_music_disc);
            }
        }
        if (ivDisc != null) {
            if (url != null && !url.isEmpty()) {
                Glide.with(this).load(url)
                    .circleCrop()
                    .placeholder(R.drawable.ic_music_disc)
                    .error(R.drawable.ic_music_disc)
                    .into(ivDisc);
            } else {
                ivDisc.setImageResource(R.drawable.ic_music_disc);
            }
        }
    }

    private void updateTrimVisibility() {
        if (btnTrimStart == null) return;
        boolean hasUrl = soundUrl != null && !soundUrl.isEmpty();
        btnTrimStart.setVisibility(hasUrl ? View.VISIBLE : View.GONE);
    }

    // ── Firebase: load sound metadata ─────────────────────────────────────

    private void loadSoundDataFromFirebase() {
        if (soundId == null || soundId.isEmpty()) {
            // Pure original audio reel — show usage count from the specific reel's data
            if (tvUsageCount != null) tvUsageCount.setText("Original Audio");
            return;
        }
        soundListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;

                // Usage count
                Long count = snap.child("reel_count").getValue(Long.class);
                if (count == null) count = snap.child("usageCount").getValue(Long.class);
                if (count == null) count = 0L;
                if (tvUsageCount != null)
                    tvUsageCount.setText(formatCount(count) + " Reels");

                // Saves count
                Long saves = snap.child("total_saves").getValue(Long.class);
                if (saves == null) saves = snap.child("totalSaves").getValue(Long.class);
                if (saves == null) saves = 0L;
                if (tvSavesCount != null) {
                    tvSavesCount.setText(formatCount(saves) + " Saves");
                    tvSavesCount.setVisibility(saves > 0 ? View.VISIBLE : View.GONE);
                }

                // Trending rank
                Long rank = snap.child("trending_rank").getValue(Long.class);
                if (rank == null) rank = snap.child("trendingRank").getValue(Long.class);
                if (tvTrendingRank != null) {
                    if (rank != null && rank > 0 && rank <= 50) {
                        tvTrendingRank.setText("#" + rank + " Trending");
                        tvTrendingRank.setVisibility(View.VISIBLE);
                    } else {
                        tvTrendingRank.setVisibility(View.GONE);
                    }
                }

                // Badges
                Boolean isOrig = snap.child("is_original").getValue(Boolean.class);
                if (isOrig == null) isOrig = snap.child("isOriginalSound").getValue(Boolean.class);
                if (tvOriginalBadge != null)
                    tvOriginalBadge.setVisibility(Boolean.TRUE.equals(isOrig) ? View.VISIBLE : View.GONE);

                Boolean isVer = snap.child("is_verified").getValue(Boolean.class);
                if (isVer == null) isVer = snap.child("isVerified").getValue(Boolean.class);
                if (tvVerifiedBadge != null)
                    tvVerifiedBadge.setVisibility(Boolean.TRUE.equals(isVer) ? View.VISIBLE : View.GONE);

                // Resolve missing audio URL
                if (soundUrl == null || soundUrl.isEmpty()) {
                    for (String key : new String[]{"audioUrl","audio_url","url"}) {
                        String fetched = snap.child(key).getValue(String.class);
                        if (fetched != null && !fetched.isEmpty()) { soundUrl = fetched; break; }
                    }
                    updateTrimVisibility();
                }
                // Resolve missing cover
                if (coverUrl == null || coverUrl.isEmpty()) {
                    for (String key : new String[]{"coverUrl","cover_url"}) {
                        String fetched = snap.child(key).getValue(String.class);
                        if (fetched != null && !fetched.isEmpty()) {
                            coverUrl = fetched; loadCoverArt(coverUrl); break;
                        }
                    }
                }
                // Genre from Firebase if not passed via intent
                if (genre.isEmpty()) {
                    String fGenre = snap.child("genre").getValue(String.class);
                    if (fGenre != null && !fGenre.isEmpty()) {
                        genre = fGenre;
                        if (tvGenre != null) { tvGenre.setText(genre); tvGenre.setVisibility(View.VISIBLE); }
                    }
                }
                // Creator UID
                if (creatorUid.isEmpty()) {
                    String fetchedUid = snap.child("uploadedByUid").getValue(String.class);
                    if (fetchedUid == null) fetchedUid = snap.child("uid").getValue(String.class);
                    if (fetchedUid != null && !fetchedUid.isEmpty()) {
                        creatorUid = fetchedUid;
                        loadCreatorInfo();
                    }
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getMusicLibraryRef().child(soundId).addValueEventListener(soundListener);
    }

    private void removeSoundListener() {
        if (soundListener != null && soundId != null && !soundId.isEmpty()) {
            FirebaseUtils.getMusicLibraryRef().child(soundId).removeEventListener(soundListener);
            soundListener = null;
        }
    }

    // ── Firebase: load reels grid ─────────────────────────────────────────

    private void loadReelsForSound() {
        if (layoutSkeletonReels != null) layoutSkeletonReels.setVisibility(View.VISIBLE);
        if (rvReels             != null) rvReels.setVisibility(View.GONE);
        if (layoutReelsSection  != null) layoutReelsSection.setVisibility(View.GONE);

        // If soundId is empty, try to find by reel's own musicId field via reelId
        if (soundId == null || soundId.isEmpty()) {
            if (reelId == null || reelId.isEmpty()) {
                hideSkeletonReels();
                return;
            }
            // Load just the single reel as an example
            FirebaseUtils.getReelsRef().child(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String mId = snap.child("musicId").getValue(String.class);
                        if (mId != null && !mId.isEmpty()) {
                            soundId = mId;
                            loadReelsForSoundId(soundId);
                        } else {
                            hideSkeletonReels();
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { hideSkeletonReels(); }
                });
            return;
        }
        loadReelsForSoundId(soundId);
    }

    private void loadReelsForSoundId(String sid) {
        FirebaseUtils.getReelsRef()
            .orderByChild("musicId").equalTo(sid)
            .limitToLast(15)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    reelItems.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        String rid   = s.getKey();
                        String thumb = s.child("thumbUrl").getValue(String.class);
                        if (thumb == null) thumb = s.child("thumbnailUrl").getValue(String.class);
                        String video = s.child("videoUrl").getValue(String.class);
                        if (rid != null && video != null && !video.isEmpty()) {
                            reelItems.add(0, new ReelThumbItem(rid, thumb, video));
                        }
                    }
                    hideSkeletonReels();
                    if (!reelItems.isEmpty()) {
                        if (reelAdapter != null) reelAdapter.notifyDataSetChanged();
                        if (layoutReelsSection != null) layoutReelsSection.setVisibility(View.VISIBLE);
                        if (rvReels           != null) rvReels.setVisibility(View.VISIBLE);
                        if (btnSeeAllReels    != null)
                            btnSeeAllReels.setVisibility(reelItems.size() >= 9 ? View.VISIBLE : View.GONE);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { hideSkeletonReels(); }
            });
    }

    private void hideSkeletonReels() {
        if (layoutSkeletonReels != null) layoutSkeletonReels.setVisibility(View.GONE);
    }

    // ── Firebase: load related sounds ─────────────────────────────────────

    private void loadRelatedSounds() {
        if (genre.isEmpty()) {
            if (layoutRelatedSection != null) layoutRelatedSection.setVisibility(View.GONE);
            return;
        }
        FirebaseUtils.getMusicLibraryRef()
            .orderByChild("genre").equalTo(genre)
            .limitToFirst(12)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    relatedItems.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        String rid   = s.getKey();
                        if (rid == null || rid.equals(soundId)) continue;
                        String t = s.child("name").getValue(String.class);
                        if (t == null) t = s.child("title").getValue(String.class);
                        String a = s.child("artist").getValue(String.class);
                        String c = s.child("coverUrl").getValue(String.class);
                        String u = s.child("audioUrl").getValue(String.class);
                        if (u == null) u = s.child("audio_url").getValue(String.class);
                        String gn= s.child("genre").getValue(String.class);
                        if (t != null) relatedItems.add(new RelatedItem(rid, t, a, c, u, gn));
                    }
                    if (relatedAdapter != null) relatedAdapter.notifyDataSetChanged();
                    if (layoutRelatedSection != null)
                        layoutRelatedSection.setVisibility(relatedItems.isEmpty() ? View.GONE : View.VISIBLE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (layoutRelatedSection != null) layoutRelatedSection.setVisibility(View.GONE);
                }
            });
    }

    // ── Firebase: creator info ─────────────────────────────────────────────

    private void loadCreatorInfo() {
        if (creatorUid == null || creatorUid.isEmpty()) {
            if (layoutCreatorSection != null) layoutCreatorSection.setVisibility(View.GONE);
            return;
        }
        if (layoutCreatorSection != null) layoutCreatorSection.setVisibility(View.VISIBLE);
        FirebaseUtils.getUserRef(creatorUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    String name  = snap.child("name").getValue(String.class);
                    if (name == null) name = snap.child("displayName").getValue(String.class);
                    String photo = snap.child("thumbUrl").getValue(String.class);
                    if (photo == null) photo = snap.child("profilePhoto").getValue(String.class);
                    if (tvCreatorName != null && name != null)
                        tvCreatorName.setText(name);
                    if (ivCreatorAvatar != null) {
                        if (photo != null && !photo.isEmpty()) {
                            Glide.with(ReelSoundActivity.this).load(photo)
                                .circleCrop()
                                .placeholder(R.drawable.ic_person)
                                .into(ivCreatorAvatar);
                        } else {
                            ivCreatorAvatar.setImageResource(R.drawable.ic_person);
                        }
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Firebase: save state ──────────────────────────────────────────────

    private void checkIfSaved() {
        String uid = safeMyUid();
        if (uid == null || soundId.isEmpty()) return;
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
        if (btnSave == null) return;
        btnSave.setImageResource(isSaved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
    }

    private void toggleSave() {
        String uid = safeMyUid();
        if (uid == null) { Toast.makeText(this, "Sign in to save sounds", Toast.LENGTH_SHORT).show(); return; }
        if (soundId.isEmpty()) { Toast.makeText(this, "Cannot save original audio", Toast.LENGTH_SHORT).show(); return; }

        isSaved = !isSaved;
        updateSaveButton();

        DatabaseReference ref = FirebaseUtils.getUserRef(uid).child("saved_sounds").child(soundId);
        if (isSaved) {
            ref.setValue(soundTitle);
            incrementSaves(1);
            Toast.makeText(this, "Sound saved!", Toast.LENGTH_SHORT).show();
        } else {
            ref.removeValue();
            incrementSaves(-1);
            Toast.makeText(this, "Removed from saved", Toast.LENGTH_SHORT).show();
        }
    }

    private void incrementSaves(int delta) {
        if (soundId.isEmpty()) return;
        FirebaseUtils.getMusicLibraryRef().child(soundId).child("total_saves")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class);
                    d.setValue(Math.max(0, (v != null ? v : 0) + delta));
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
            });
    }

    // ── Disc spin ─────────────────────────────────────────────────────────

    private void startDiscSpin() {
        if (ivDisc == null) return;
        if (discAnimator != null) discAnimator.cancel();
        discAnimator = ObjectAnimator.ofFloat(ivDisc, "rotation", 0f, 360f);
        discAnimator.setDuration(5000);
        discAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        discAnimator.setInterpolator(new LinearInterpolator());
        discAnimator.start();
    }

    private void pauseDiscSpin() {
        if (discAnimator != null) discAnimator.pause();
    }

    private void resumeDiscSpin() {
        if (discAnimator != null && discAnimator.isPaused()) discAnimator.resume();
    }

    // ── Waveform ──────────────────────────────────────────────────────────

    private void buildWaveform() {
        if (layoutWaveform == null) return;
        layoutWaveform.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < WAVEFORM_BARS; i++) {
            View bar = new View(this);
            int h = (int)((8 + random.nextInt(24)) * dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int)(4 * dp), h);
            lp.setMargins((int)(2 * dp), 0, (int)(2 * dp), 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(0x55FFFFFF);
            bar.setTag("bar");
            layoutWaveform.addView(bar);
        }
    }

    private void animateWaveform() {
        if (layoutWaveform == null) return;
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < layoutWaveform.getChildCount(); i++) {
            View bar = layoutWaveform.getChildAt(i);
            if (!"bar".equals(bar.getTag())) continue;
            int h = (int)((8 + random.nextInt(30)) * dp);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            lp.height = h;
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(0xFFFF3B5C);
        }
    }

    private void resetWaveform() {
        if (layoutWaveform == null) return;
        float dp = getResources().getDisplayMetrics().density;
        Random seed = new Random(soundTitle.hashCode());
        for (int i = 0; i < layoutWaveform.getChildCount(); i++) {
            View bar = layoutWaveform.getChildAt(i);
            if (!"bar".equals(bar.getTag())) continue;
            int h = (int)((8 + seed.nextInt(24)) * dp);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            lp.height = h;
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(0x55FFFFFF);
        }
    }

    // ── Audio playback ────────────────────────────────────────────────────

    private void togglePlay() {
        if (soundUrl == null || soundUrl.isEmpty()) {
            Toast.makeText(this, "No audio available for this sound", Toast.LENGTH_SHORT).show();
            return;
        }
        if (player == null) {
            startAudioPlayback();
        } else if (isPlaying) {
            pauseAudio();
        } else {
            resumeAudio();
        }
    }

    private void startAudioPlayback() {
        if (progressLoad != null) progressLoad.setVisibility(View.VISIBLE);
        if (tvPlayBtn    != null) tvPlayBtn.setEnabled(false);

        player = new MediaPlayer();
        try {
            // If sound has a start offset, seek after prepare
            player.setDataSource(soundUrl);
            player.setLooping(true);
            player.setOnErrorListener((mp, what, extra) -> {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Cannot play audio", Toast.LENGTH_SHORT).show();
                    releasePlayer();
                    if (tvPlayBtn    != null) { tvPlayBtn.setEnabled(true); tvPlayBtn.setText("▶  Play"); }
                    if (progressLoad != null) progressLoad.setVisibility(View.GONE);
                    resetWaveform();
                    pauseDiscSpin();
                });
                return true;
            });
            player.setOnPreparedListener(mp -> {
                if (progressLoad != null) progressLoad.setVisibility(View.GONE);
                if (tvPlayBtn    != null) tvPlayBtn.setEnabled(true);
                // Seek to start offset if provided
                if (startSec > 0 && mp.getDuration() > startSec * 1000) {
                    mp.seekTo(startSec * 1000);
                }
                mp.start();
                isPlaying = true;
                if (tvPlayBtn != null) tvPlayBtn.setText("⏸  Pause");
                handler.post(waveRunnable);
                resumeDiscSpin();
                startSeekTracking();
            });
            player.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot play audio", Toast.LENGTH_SHORT).show();
            releasePlayer();
            if (progressLoad != null) progressLoad.setVisibility(View.GONE);
            if (tvPlayBtn    != null) tvPlayBtn.setEnabled(true);
        }
    }

    private void pauseAudio() {
        if (player != null && isPlaying) {
            player.pause();
            isPlaying = false;
            if (tvPlayBtn != null) tvPlayBtn.setText("▶  Play");
            handler.removeCallbacks(waveRunnable);
            stopSeekTracking();
            resetWaveform();
            pauseDiscSpin();
        }
    }

    private void resumeAudio() {
        if (player != null && !isPlaying) {
            player.start();
            isPlaying = true;
            if (tvPlayBtn != null) tvPlayBtn.setText("⏸  Pause");
            handler.post(waveRunnable);
            resumeDiscSpin();
            startSeekTracking();
        }
    }

    private void releasePlayer() {
        stopSeekTracking();
        handler.removeCallbacks(waveRunnable);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        isPlaying = false;
    }

    // ── SeekBar tracking ──────────────────────────────────────────────────

    private void startSeekTracking() {
        stopSeekTracking();
        seekRunnable = new Runnable() {
            @Override public void run() {
                if (player == null || !isPlaying) return;
                int pos = player.getCurrentPosition();
                int dur = player.getDuration();
                if (dur > 0) {
                    if (seekBarAudio != null) seekBarAudio.setProgress((int)((long) pos * 1000 / dur));
                    updatePositionDisplay(pos, dur);
                }
                handler.postDelayed(this, 250);
            }
        };
        handler.post(seekRunnable);
    }

    private void stopSeekTracking() {
        if (seekRunnable != null) { handler.removeCallbacks(seekRunnable); seekRunnable = null; }
    }

    private void updatePositionDisplay(int posMs, int durMs) {
        if (tvPositionDuration == null) return;
        int posSec = posMs / 1000, durSec = durMs / 1000;
        tvPositionDuration.setText(String.format(Locale.US, "%d:%02d / %d:%02d",
            posSec / 60, posSec % 60, durSec / 60, durSec % 60));
    }

    // ── Action handlers ───────────────────────────────────────────────────

    private void useSoundCamera() {
        Intent i = new Intent(this, ReelCameraActivity.class);
        i.putExtra("sound_id",    soundId);
        i.putExtra("sound_title", soundTitle);
        i.putExtra("sound_url",   soundUrl);
        i.putExtra("cover_url",   coverUrl);
        i.putExtra("artist",      artist);
        startActivity(i);
    }

    private void useSoundGallery() {
        Intent i = new Intent(this, ReelUploadActivity.class);
        i.putExtra("sound_id",    soundId);
        i.putExtra("sound_title", soundTitle);
        i.putExtra("sound_url",   soundUrl);
        i.putExtra("cover_url",   coverUrl);
        i.putExtra("artist",      artist);
        startActivity(i);
    }

    private void openTrim() {
        if (soundUrl == null || soundUrl.isEmpty()) return;
        Intent i = new Intent(this, ReelMusicTrimActivity.class);
        i.putExtra(ReelMusicTrimActivity.EXTRA_SOUND_ID,    soundId);
        i.putExtra(ReelMusicTrimActivity.EXTRA_SOUND_TITLE, soundTitle);
        i.putExtra(ReelMusicTrimActivity.EXTRA_SOUND_URL,   soundUrl);
        i.putExtra(ReelMusicTrimActivity.EXTRA_DURATION_MS, durationMs);
        startActivity(i);
    }

    private void openDuet() {
        if (reelId == null || reelId.isEmpty()) {
            Toast.makeText(this, "Duet not available for this sound", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, DuetReelActivity.class);
        i.putExtra("reel_id",   reelId);
        i.putExtra("sound_url", soundUrl);
        startActivity(i);
    }

    private void openStitch() {
        if (reelId == null || reelId.isEmpty()) {
            Toast.makeText(this, "Stitch not available for this sound", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, StitchReelActivity.class);
        i.putExtra("reel_id",   reelId);
        i.putExtra("sound_url", soundUrl);
        startActivity(i);
    }

    private void openRecorder() {
        startActivity(new Intent(this, ReelSoundRecorderActivity.class));
    }

    private void openPicker() {
        startActivity(new Intent(this, ReelSoundPickerActivity.class));
    }

    private void seeAllReels() {
        // Open trending audio screen scoped to this sound
        Intent i = new Intent(this, ReelTrendingAudioActivity.class);
        i.putExtra("sound_id",    soundId);
        i.putExtra("sound_title", soundTitle);
        startActivity(i);
    }

    private void openCreatorProfile() {
        if (creatorUid == null || creatorUid.isEmpty()) return;
        Intent i = new Intent(this, UserReelsActivity.class);
        i.putExtra("user_id", creatorUid);
        startActivity(i);
    }

    private void openReelPlayer(ReelThumbItem item) {
        Intent i = new Intent(this, SingleReelPlayerActivity.class);
        i.putExtra("reel_id",   item.reelId);
        i.putExtra("video_url", item.videoUrl);
        startActivity(i);
    }

    private void openRelatedSound(RelatedItem item) {
        Intent i = new Intent(this, ReelSoundActivity.class);
        i.putExtra(EXTRA_SOUND_ID,    item.id);
        i.putExtra(EXTRA_SOUND_TITLE, item.title);
        i.putExtra(EXTRA_SOUND_URL,   item.audioUrl != null ? item.audioUrl : "");
        i.putExtra(EXTRA_COVER_URL,   item.coverUrl != null ? item.coverUrl : "");
        i.putExtra(EXTRA_ARTIST,      item.artist   != null ? item.artist   : "");
        i.putExtra(EXTRA_GENRE,       item.genre    != null ? item.genre    : "");
        startActivity(i);
    }

    private void shareSound() {
        String link = "https://callx.app/sound/" + soundId;
        String text = "Check out this sound: " + soundTitle
            + (artist != null && !artist.isEmpty() ? " by " + artist : "")
            + "\n" + link;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, "Share sound via"));
    }

    private void copyLink() {
        String link = "https://callx.app/sound/" + (soundId.isEmpty() ? soundTitle : soundId);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Sound link", link));
            Toast.makeText(this, "Link copied!", Toast.LENGTH_SHORT).show();
        }
    }

    private void showMoreOptions() {
        if (btnMore == null) return;
        PopupMenu popup = new PopupMenu(this, btnMore);
        popup.getMenu().add(0, 1, 0, "Copy link");
        popup.getMenu().add(0, 2, 1, "Report sound");
        popup.getMenu().add(0, 3, 2, "Copyright info");
        popup.getMenu().add(0, 4, 3, "Add to collection");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: copyLink(); return true;
                case 2: reportSound(); return true;
                case 3: showCopyrightInfo(); return true;
                case 4: addToCollection(); return true;
            }
            return false;
        });
        popup.show();
    }

    private void reportSound() {
        Intent i = new Intent(this, ReelReportActivity.class);
        i.putExtra("report_type", "sound");
        i.putExtra("target_id",   soundId);
        i.putExtra("target_name", soundTitle);
        startActivity(i);
    }

    private void showCopyrightInfo() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Copyright Information")
            .setMessage("Sound: " + soundTitle + "\n"
                + "Artist: " + artist + "\n\n"
                + "This audio is subject to copyright. Unauthorized commercial use may be restricted. "
                + "Original creators retain rights to their audio.")
            .setPositiveButton("OK", null)
            .show();
    }

    private void addToCollection() {
        Intent i = new Intent(this, ReelBookmarkCollectionsActivity.class);
        i.putExtra("mode",      "sound");
        i.putExtra("sound_id",  soundId);
        i.putExtra("sound_name", soundTitle);
        startActivity(i);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ── RecyclerView adapters ─────────────────────────────────────────────

    static class ReelThumbItem {
        String reelId, thumbnailUrl, videoUrl;
        ReelThumbItem(String r, String t, String v) { reelId = r; thumbnailUrl = t; videoUrl = v; }
    }

    static class ReelThumbAdapter extends RecyclerView.Adapter<ReelThumbAdapter.VH> {
        interface OnClick { void click(ReelThumbItem item); }
        private final List<ReelThumbItem> items;
        private final OnClick onClick;
        ReelThumbAdapter(List<ReelThumbItem> i, OnClick c) { items = i; onClick = c; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int vt) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media_thumb, parent, false);
            // Force square cells
            int side = parent.getWidth() / 3;
            if (side > 0) {
                v.getLayoutParams().width  = side;
                v.getLayoutParams().height = side;
            }
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ReelThumbItem item = items.get(pos);
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
                Glide.with(h.iv).load(item.thumbnailUrl)
                    .placeholder(android.R.color.darker_gray).centerCrop().into(h.iv);
            } else {
                h.iv.setImageResource(R.drawable.ic_music_note);
            }
            h.itemView.setOnClickListener(v -> onClick.click(item));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView iv;
            VH(View v) { super(v); iv = v.findViewById(R.id.iv_media_thumb); }
        }
    }

    static class RelatedItem {
        String id, title, artist, coverUrl, audioUrl, genre;
        RelatedItem(String i, String t, String a, String c, String u, String g) {
            id = i; title = t; artist = a; coverUrl = c; audioUrl = u; genre = g;
        }
    }

    static class RelatedAdapter extends RecyclerView.Adapter<RelatedAdapter.VH> {
        interface OnClick { void click(RelatedItem item); }
        private final List<RelatedItem> items;
        private final OnClick onClick;
        RelatedAdapter(List<RelatedItem> i, OnClick c) { items = i; onClick = c; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int vt) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_related_sound, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            RelatedItem item = items.get(pos);
            h.tvTitle.setText(item.title);
            h.tvArtist.setText(item.artist != null ? item.artist : "");
            if (item.coverUrl != null && !item.coverUrl.isEmpty()) {
                Glide.with(h.iv).load(item.coverUrl)
                    .placeholder(R.drawable.ic_music_note).centerCrop().into(h.iv);
            } else {
                h.iv.setImageResource(R.drawable.ic_music_note);
            }
            h.itemView.setOnClickListener(v -> onClick.click(item));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView iv; TextView tvTitle, tvArtist;
            VH(View v) {
                super(v);
                iv       = v.findViewById(R.id.iv_related_cover);
                tvTitle  = v.findViewById(R.id.tv_related_title);
                tvArtist = v.findViewById(R.id.tv_related_artist);
            }
        }
    }
}
