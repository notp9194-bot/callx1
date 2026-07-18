package com.callx.app.music;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SoundDetailBottomSheet — Instagram-style sound detail bottom sheet.
 *
 * Peek state  (Screenshot 3): shows song info + buttons.
 * Expanded state (Screenshot 4): scrolled up → reels grid visible.
 *
 * Usage:
 *   SoundDetailBottomSheet sheet = SoundDetailBottomSheet.newInstance(
 *       soundId, soundTitle, artist, coverUrl, soundUrl, durationMs, reelCount, isTrending);
 *   sheet.show(getSupportFragmentManager(), "sound_detail");
 */
public class SoundDetailBottomSheet extends BottomSheetDialogFragment implements Player.Listener {

    // ── Argument keys ────────────────────────────────────────────────────────
    private static final String ARG_SOUND_ID    = "sound_id";
    private static final String ARG_TITLE       = "title";
    private static final String ARG_ARTIST      = "artist";
    private static final String ARG_COVER_URL   = "cover_url";
    private static final String ARG_SOUND_URL   = "sound_url";
    private static final String ARG_DURATION_MS = "duration_ms";
    private static final String ARG_REEL_COUNT  = "reel_count";
    private static final String ARG_TRENDING    = "is_trending";

    // ── Views ────────────────────────────────────────────────────────────────
    private ImageView     ivSoundCover;
    private ImageButton   btnPlayPause;
    private TextView      tvSoundTitle, tvSoundArtist, tvDuration, tvReelCount;
    private TextView      tvTrendingBadge;
    private ImageButton   btnSaveSound, btnShareSound, btnMore;
    private Button        btnAddToProfile, btnUseAudio;
    private RecyclerView  rvSoundReels;

    // ── Data ─────────────────────────────────────────────────────────────────
    private String  soundId, soundTitle, artist, coverUrl, soundUrl;
    private int     durationMs, reelCount;
    private boolean isTrending;

    // ── Reels grid ───────────────────────────────────────────────────────────
    private final List<SoundDetailActivity.ReelThumbItem> reelItems = new ArrayList<>();
    private SoundDetailActivity.ReelThumbAdapter reelAdapter;
    private static final int PAGE_SIZE = 12;
    private String  lastReelKey        = null;
    private boolean isLoadingMore      = false;
    private boolean hasMore            = true;

    // ── Audio player ─────────────────────────────────────────────────────────
    private ExoPlayer exoPlayer;
    private boolean   isPlaying = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────────────────

    public static SoundDetailBottomSheet newInstance(
            String soundId, String title, String artist,
            String coverUrl, String soundUrl,
            int durationMs, int reelCount, boolean isTrending) {
        SoundDetailBottomSheet f = new SoundDetailBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_SOUND_ID,    soundId);
        b.putString(ARG_TITLE,       title);
        b.putString(ARG_ARTIST,      artist);
        b.putString(ARG_COVER_URL,   coverUrl);
        b.putString(ARG_SOUND_URL,   soundUrl);
        b.putInt   (ARG_DURATION_MS, durationMs);
        b.putInt   (ARG_REEL_COUNT,  reelCount);
        b.putBoolean(ARG_TRENDING,   isTrending);
        f.setArguments(b);
        return f;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_sound_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Extract args
        Bundle b = getArguments();
        if (b != null) {
            soundId    = b.getString(ARG_SOUND_ID, "");
            soundTitle = b.getString(ARG_TITLE, "");
            artist     = b.getString(ARG_ARTIST, "");
            coverUrl   = b.getString(ARG_COVER_URL, "");
            soundUrl   = b.getString(ARG_SOUND_URL, "");
            durationMs = b.getInt(ARG_DURATION_MS, 0);
            reelCount  = b.getInt(ARG_REEL_COUNT, 0);
            isTrending = b.getBoolean(ARG_TRENDING, false);
        }

        bindViews(view);
        setupBottomSheetBehavior();
        populateData();
        setupReelsGrid();
        setupListeners();
        initPlayer();
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews(View v) {
        ivSoundCover    = v.findViewById(R.id.iv_sound_cover);
        btnPlayPause    = v.findViewById(R.id.btn_play_pause);
        tvSoundTitle    = v.findViewById(R.id.tv_sound_title);
        tvSoundArtist   = v.findViewById(R.id.tv_sound_artist);
        tvDuration      = v.findViewById(R.id.tv_sound_duration);
        tvReelCount     = v.findViewById(R.id.tv_sound_reel_count);
        tvTrendingBadge = v.findViewById(R.id.tv_trending_badge);
        btnSaveSound    = v.findViewById(R.id.btn_save_sound);
        btnShareSound   = v.findViewById(R.id.btn_share_sound);
        btnMore         = v.findViewById(R.id.btn_sound_more);
        btnAddToProfile = v.findViewById(R.id.btn_add_to_profile);
        btnUseAudio     = v.findViewById(R.id.btn_use_audio);
        rvSoundReels    = v.findViewById(R.id.rv_sound_reels);
    }

    // ── Bottom sheet behavior ────────────────────────────────────────────────

    private void setupBottomSheetBehavior() {
        if (getDialog() == null) return;
        View sheetContainer = getDialog().findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheetContainer == null) return;

        // Start in collapsed (peek) state showing song info + buttons (~370dp)
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheetContainer);
        int peekPx = (int)(370 * getResources().getDisplayMetrics().density);
        behavior.setPeekHeight(peekPx, true);
        behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        behavior.setSkipCollapsed(false);
        behavior.setFitToContents(false);
        behavior.setHalfExpandedRatio(0.6f);
    }

    // ── Data population ───────────────────────────────────────────────────────

    private void populateData() {
        // Song cover thumbnail
        if (coverUrl != null && !coverUrl.isEmpty()) {
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.ic_music_disc)
                .into(ivSoundCover);
        }

        // Titles
        tvSoundTitle.setText(soundTitle != null ? soundTitle : "");
        tvSoundArtist.setText(artist != null ? artist : "");

        // Duration
        if (durationMs > 0) {
            int totalSec = durationMs / 1000;
            tvDuration.setText(String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60));
        } else {
            tvDuration.setVisibility(View.GONE);
        }

        // Reel count
        tvReelCount.setText(formatCount(reelCount) + " reels");

        // Trending badge
        tvTrendingBadge.setVisibility(isTrending ? View.VISIBLE : View.GONE);
    }

    // ── Reels grid ────────────────────────────────────────────────────────────

    private void setupReelsGrid() {
        GridLayoutManager gridLayoutManager = new GridLayoutManager(requireContext(), 3);
        rvSoundReels.setLayoutManager(gridLayoutManager);
        rvSoundReels.setNestedScrollingEnabled(false);

        reelAdapter = new SoundDetailActivity.ReelThumbAdapter(reelItems, position -> {
            // Open reel player
            List<String> ids = new ArrayList<>();
            for (SoundDetailActivity.ReelThumbItem r : reelItems) ids.add(r.reelId);
            Intent i = new Intent(requireContext(), SingleReelPlayerActivity.class);
            i.putExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, new ArrayList<>(ids));
            i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, position);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SHOW_SOUND_ACTIONS, true);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_ID,    soundId);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_TITLE, soundTitle);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_URL,   soundUrl);
            startActivity(i);
        });
        rvSoundReels.setAdapter(reelAdapter);

        // Cell height is calculated inside ReelThumbAdapter.onCreateViewHolder()
        // using displayMetrics.widthPixels / 3 * 16/9 — no extra setup needed.
        loadMoreReels();
    }

    private void loadMoreReels() {
        if (isLoadingMore || !hasMore || soundId == null || soundId.isEmpty()) return;
        isLoadingMore = true;

        com.google.firebase.database.Query q = FirebaseUtils.db()
            .getReference("sounds").child(soundId).child("reels").orderByKey();
        q = (lastReelKey != null) ? q.startAfter(lastReelKey).limitToFirst(PAGE_SIZE)
                                   : q.limitToFirst(PAGE_SIZE);

        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) { isLoadingMore = false; return; }
                List<SoundDetailActivity.ReelThumbItem> page = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) {
                    String rid   = s.getKey();
                    String thumb = s.child("thumbnailUrl").getValue(String.class);
                    if (thumb == null) thumb = s.child("thumbnail").getValue(String.class);
                    String vid   = s.child("videoUrl").getValue(String.class);
                    if (rid != null) {
                        page.add(new SoundDetailActivity.ReelThumbItem(rid,
                            thumb != null ? thumb : "", vid != null ? vid : ""));
                        lastReelKey = rid;
                    }
                }
                if (page.size() < PAGE_SIZE) hasMore = false;
                if (page.isEmpty()) { loadReelsFromReelsNode(); return; }
                appendPage(page);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { isLoadingMore = false; }
        });
    }

    /** Fallback: query "reels" node by musicId if sounds/{id}/reels is empty */
    private void loadReelsFromReelsNode() {
        if (soundId == null || !isAdded()) { isLoadingMore = false; return; }
        FirebaseUtils.db().getReference("reels")
            .orderByChild("musicId").equalTo(soundId)
            .limitToFirst(PAGE_SIZE)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded()) { isLoadingMore = false; return; }
                    List<SoundDetailActivity.ReelThumbItem> page = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String rid   = s.getKey();
                        String thumb = s.child("thumbnailUrl").getValue(String.class);
                        if (thumb == null) thumb = s.child("thumbnail").getValue(String.class);
                        String vid   = s.child("videoUrl").getValue(String.class);
                        if (rid != null) {
                            page.add(new SoundDetailActivity.ReelThumbItem(rid,
                                thumb != null ? thumb : "", vid != null ? vid : ""));
                        }
                    }
                    appendPage(page);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { isLoadingMore = false; }
            });
    }

    private void appendPage(List<SoundDetailActivity.ReelThumbItem> page) {
        if (!isAdded()) { isLoadingMore = false; return; }
        mainHandler.post(() -> {
            int start = reelItems.size();
            reelItems.addAll(page);
            reelAdapter.notifyItemRangeInserted(start, page.size());
            isLoadingMore = false;
        });
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private void setupListeners() {
        // Play / pause
        btnPlayPause.setOnClickListener(v -> togglePlayback());

        // Add to profile — write profileSong to Firebase
        btnAddToProfile.setOnClickListener(v -> {
            String uid = com.google.firebase.auth.FirebaseAuth.getInstance()
                .getCurrentUser() != null
                ? com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid()
                : null;
            if (uid == null) { Toast.makeText(requireContext(), "Not logged in", Toast.LENGTH_SHORT).show(); return; }

            java.util.Map<String, Object> songData = new java.util.HashMap<>();
            songData.put("soundId",    soundId);
            songData.put("title",      soundTitle);
            songData.put("artist",     artist);
            songData.put("coverUrl",   coverUrl);
            songData.put("soundUrl",   soundUrl);
            songData.put("durationMs", durationMs);
            FirebaseUtils.db().getReference("reels/users").child(uid)
                .child("profileSong").setValue(songData)
                .addOnSuccessListener(unused ->
                    Toast.makeText(requireContext(), "Song added to profile!", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                    Toast.makeText(requireContext(), "Failed to add song", Toast.LENGTH_SHORT).show());
        });

        // Use audio — open reel camera with this sound
        btnUseAudio.setOnClickListener(v -> {
            try {
                Intent i = new Intent(requireContext(), com.callx.app.camera.ReelCameraActivity.class);
                i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    soundId);
                i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, soundTitle);
                i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   soundUrl);
                i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      artist);
                i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   coverUrl);
                startActivity(i);
            } catch (Exception ex) {
                Toast.makeText(requireContext(), "Camera not available", Toast.LENGTH_SHORT).show();
            }
            dismiss();
        });

        // Share sound
        btnShareSound.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, soundTitle + " - " + artist);
            startActivity(Intent.createChooser(shareIntent, "Share sound"));
        });

        // Save / bookmark
        btnSaveSound.setOnClickListener(v ->
            Toast.makeText(requireContext(), "Sound saved!", Toast.LENGTH_SHORT).show());

        // 3-dot menu
        btnMore.setOnClickListener(v ->
            Toast.makeText(requireContext(), "More options", Toast.LENGTH_SHORT).show());
    }

    // ── Audio playback ────────────────────────────────────────────────────────

    private void initPlayer() {
        if (soundUrl == null || soundUrl.isEmpty()) return;
        exoPlayer = new ExoPlayer.Builder(requireContext()).build();
        exoPlayer.addListener(this);
        exoPlayer.setMediaItem(MediaItem.fromUri(soundUrl));
        exoPlayer.prepare();
    }

    private void togglePlayback() {
        if (exoPlayer == null) { initPlayer(); return; }
        if (isPlaying) {
            exoPlayer.pause();
        } else {
            exoPlayer.play();
        }
    }

    @Override
    public void onIsPlayingChanged(boolean playing) {
        isPlaying = playing;
        if (btnPlayPause == null || !isAdded()) return;
        btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatCount(int count) {
        if (count >= 1_000_000) return String.format(Locale.US, "%.1fM", count / 1_000_000f);
        if (count >= 1_000)     return String.format(Locale.US, "%.1fK", count / 1_000f);
        return String.valueOf(count);
    }

    // ── Lifecycle cleanup ─────────────────────────────────────────────────────

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
    }
}
