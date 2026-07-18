package com.callx.app.music;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.ServerValue;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * SoundRemixActivity — Feature 5: Sound Remix / Mashup.
 *
 * Lets the user blend two sounds into a new "Remix":
 *  ✅ Sound A — pre-selected from context (e.g. SoundDetailFragment 3-dot menu)
 *  ✅ Sound B — picked via ReelTrendingAudioActivity (Trending Sound screen)
 *  ✅ Blend slider: 0% = only A, 100% = only B, 50% = equal mix
 *  ✅ Volume knobs per track (0–100%)
 *  ✅ "Create Remix" — mixes via AudioMixHelper.mixTwoAudioFiles() (audio-only,
 *       no video muxer — avoids "No video track found" crash)
 *  ✅ Remix track title: auto-generated as "Remix: A + B"
 *  ✅ On success → uploads to Firebase, opens SoundDetailActivity for new remix
 *
 * BUGS FIXED vs. previous version:
 *  1. CRASH — "No video track found": old code passed audio URL to
 *     AudioMixHelper.mixAndExportWithConfig which internally calls muxVideoAndAudio().
 *     That muxer requires a VIDEO track, but sound files are audio-only.
 *     Fix: use AudioMixHelper.mixTwoAudioFiles() which is audio-only; no muxing.
 *
 *  2. RACE CONDITION — temp files deleted before mix completes:
 *     old code called fileA.delete()/fileB.delete() immediately after submitting
 *     async mix work, so files vanished before the mixer read them.
 *     Fix: AudioMixHelper.mixTwoAudioFiles() manages its own downloads via
 *     downloadToCache(); SoundRemixActivity no longer owns any temp files.
 *
 *  3. DOUBLE DOWNLOAD — old cfg.musicUrl = soundBUrl caused AudioMixHelper to
 *     re-download Sound B even though it was already downloaded manually.
 *     Fix: removed; AudioMixHelper.mixTwoAudioFiles() downloads once, caches by URL hash.
 *
 * Firebase paths written:
 *   musicLibrary/{newId}: title, artist, audioUrl, coverUrl, bpm, genre="Remix", addedAt
 *   sounds/{newId}:       title, artist, audioUrl, coverUrl, creatorUid, reel_count=0, is_remix=true
 */
public class SoundRemixActivity extends AppCompatActivity {

    // ── Intent extras (public — callers like SoundDetailFragment use these) ──
    public static final String EXTRA_SOUND_A_ID     = "remix_sound_a_id";
    public static final String EXTRA_SOUND_A_TITLE  = "remix_sound_a_title";
    public static final String EXTRA_SOUND_A_URL    = "remix_sound_a_url";
    public static final String EXTRA_SOUND_A_COVER  = "remix_sound_a_cover";
    public static final String EXTRA_SOUND_A_ARTIST = "remix_sound_a_artist";

    private static final int REQ_PICK_SOUND_B = 820;

    // ── Sound A (from intent) ─────────────────────────────────────────────────
    private String soundAId, soundATitle, soundAUrl, soundACover, soundAArtist;

    // ── Sound B (picked by user via ReelTrendingAudioActivity) ────────────────
    private String soundBId     = "";
    private String soundBTitle  = "";
    private String soundBUrl    = "";
    private String soundBCover  = "";   // captured but not shown in current layout
    private String soundBArtist = "";

    // ── Blend settings ────────────────────────────────────────────────────────
    private int blendPercent = 50; // 50 = equal A+B
    private int volumeA      = 80; // percent
    private int volumeB      = 80;

    // ── Views ─────────────────────────────────────────────────────────────────
    private ImageButton btnBack;
    private TextView    tvSoundAName, tvSoundBName, tvBlendLabel, tvStatus;
    private View        btnPickSoundB;
    private SeekBar     sbBlend, sbVolA, sbVolB;
    private Button      btnCreateRemix;
    private ProgressBar pbRemix;
    private EditText    etRemixTitle;

    // ── Threading (download fallback only; AudioMixHelper has its own executor) ──
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_remix);

        soundAId     = getIntent().getStringExtra(EXTRA_SOUND_A_ID);
        soundATitle  = nvl(getIntent().getStringExtra(EXTRA_SOUND_A_TITLE));
        soundAUrl    = nvl(getIntent().getStringExtra(EXTRA_SOUND_A_URL));
        soundACover  = nvl(getIntent().getStringExtra(EXTRA_SOUND_A_COVER));
        soundAArtist = nvl(getIntent().getStringExtra(EXTRA_SOUND_A_ARTIST));

        bindViews();
    }

    // ── View binding & listeners ───────────────────────────────────────────────

    private void bindViews() {
        btnBack        = findViewById(R.id.btn_remix_back);
        tvSoundAName   = findViewById(R.id.tv_remix_sound_a);
        tvSoundBName   = findViewById(R.id.tv_remix_sound_b);
        tvBlendLabel   = findViewById(R.id.tv_remix_blend_label);
        tvStatus       = findViewById(R.id.tv_remix_status);
        btnPickSoundB  = findViewById(R.id.btn_remix_pick_b);
        sbBlend        = findViewById(R.id.sb_remix_blend);
        sbVolA         = findViewById(R.id.sb_remix_vol_a);
        sbVolB         = findViewById(R.id.sb_remix_vol_b);
        btnCreateRemix = findViewById(R.id.btn_create_remix);
        pbRemix        = findViewById(R.id.pb_remix);
        etRemixTitle   = findViewById(R.id.et_remix_title);

        // Toolbar
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Sound A label
        if (tvSoundAName != null)
            tvSoundAName.setText(soundATitle.isEmpty() ? "Sound A" : soundATitle);

        // Sound B picker
        if (tvSoundBName != null) tvSoundBName.setText("Tap to pick Sound B…");
        if (btnPickSoundB != null)
            btnPickSoundB.setOnClickListener(v ->
                startActivityForResult(
                    new Intent(this, ReelTrendingAudioActivity.class), REQ_PICK_SOUND_B));

        // Blend slider: 0=all A, 100=all B
        if (sbBlend != null) {
            sbBlend.setMax(100);
            sbBlend.setProgress(blendPercent);
            sbBlend.setOnSeekBarChangeListener(simpleSeekBar(p -> {
                blendPercent = p;
                updateBlendLabel();
            }));
        }

        // Volume sliders
        if (sbVolA != null) {
            sbVolA.setMax(100);
            sbVolA.setProgress(volumeA);
            sbVolA.setOnSeekBarChangeListener(simpleSeekBar(p -> volumeA = p));
        }
        if (sbVolB != null) {
            sbVolB.setMax(100);
            sbVolB.setProgress(volumeB);
            sbVolB.setOnSeekBarChangeListener(simpleSeekBar(p -> volumeB = p));
        }

        updateBlendLabel();
        refreshRemixTitle();

        if (btnCreateRemix != null)
            btnCreateRemix.setOnClickListener(v -> createRemix());

        if (pbRemix != null) pbRemix.setVisibility(View.GONE);
        if (tvStatus != null) tvStatus.setText("");
    }

    // ── Blend label ──────────────────────────────────────────────────────────

    private void updateBlendLabel() {
        if (tvBlendLabel == null) return;
        int aPercent = 100 - blendPercent;
        int bPercent = blendPercent;
        tvBlendLabel.setText("A: " + aPercent + "%  ←→  B: " + bPercent + "%");
    }

    private void refreshRemixTitle() {
        if (etRemixTitle == null) return;
        String aName = soundATitle.isEmpty() ? "Sound A" : soundATitle;
        String bName = soundBTitle.isEmpty() ? "Sound B" : soundBTitle;
        etRemixTitle.setText("Remix: " + aName + " + " + bName);
    }

    // ── Sound B picked ───────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_SOUND_B && res == RESULT_OK && data != null) {
            soundBId     = nvl(data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_ID));
            soundBTitle  = nvl(data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_TITLE));
            soundBUrl    = nvl(data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_URL));
            soundBCover  = nvl(data.getStringExtra(ReelTrendingAudioActivity.RESULT_COVER_URL));
            soundBArtist = nvl(data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_ARTIST));

            if (tvSoundBName != null)
                tvSoundBName.setText(soundBTitle.isEmpty() ? "Sound B" : soundBTitle);
            refreshRemixTitle();

            // Auto-enable Create button & clear old status
            if (btnCreateRemix != null) btnCreateRemix.setEnabled(true);
            if (tvStatus != null) tvStatus.setText("");
        }
    }

    // ── Create Remix ─────────────────────────────────────────────────────────

    /**
     * Downloads both audio URLs, mixes them (audio-only — no video muxer),
     * uploads result to Firebase Storage, writes metadata to RTDB, then
     * opens SoundDetailActivity for the new remix.
     *
     * FIX 1: Uses AudioMixHelper.mixTwoAudioFiles() — audio-only pipeline,
     *         no muxVideoAndAudio() call → no "No video track found" crash.
     * FIX 2: AudioMixHelper owns the downloads → no race condition with
     *         manual file deletes.
     */
    private void createRemix() {
        // ── Validation ──────────────────────────────────────────────────────
        if (soundAUrl.isEmpty()) {
            Toast.makeText(this, "Sound A not available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (soundBUrl.isEmpty()) {
            Toast.makeText(this, "Please pick Sound B first", Toast.LENGTH_SHORT).show();
            return;
        }

        String rawTitle = etRemixTitle != null
            ? etRemixTitle.getText().toString().trim() : "";
        final String finalTitle = rawTitle.isEmpty()
            ? ("Remix: " + nvl(soundATitle.isEmpty() ? "Sound A" : soundATitle)
               + " + " + nvl(soundBTitle.isEmpty() ? "Sound B" : soundBTitle))
            : rawTitle;

        // ── Volume & blend weights ───────────────────────────────────────────
        // aWeight + bWeight represent the blend ratio (sum can be ≤ 1.0).
        // volumeA/B is per-track gain on top of that.
        final float aWeight = (100 - blendPercent) / 100f;
        final float bWeight = blendPercent / 100f;
        final float effectiveVolA = aWeight * (volumeA / 100f);
        final float effectiveVolB = bWeight * (volumeB / 100f);

        // ── UI → loading state ───────────────────────────────────────────────
        setUiEnabled(false);
        if (pbRemix != null) pbRemix.setVisibility(View.VISIBLE);
        setStatus("Downloading and blending sounds…");

        // ── Mix — AudioMixHelper runs this on its own background thread ──────
        AudioMixHelper.mixTwoAudioFiles(
            this,
            soundAUrl, effectiveVolA,   // Sound A URL + volume
            soundBUrl, effectiveVolB,   // Sound B URL + volume
            1000,                       // 1-second fade-out at end
            new AudioMixHelper.MixCallback() {

                @Override
                public void onProgress(int pct) {
                    // Already called on main thread by AudioMixHelper
                    setStatus(pct < 30  ? "Downloading sounds… " + pct + "%" :
                              pct < 70  ? "Mixing tracks… "     + pct + "%" :
                                          "Encoding remix… "    + pct + "%");
                }

                @Override
                public void onSuccess(String outPath) {
                    // Already on main thread
                    setStatus("Uploading remix…");
                    saveRemixToFirebase(outPath, finalTitle);
                }

                @Override
                public void onError(Exception e) {
                    // Already on main thread
                    setUiEnabled(true);
                    if (pbRemix != null) pbRemix.setVisibility(View.GONE);
                    String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    setStatus("Remix failed: " + msg);
                    Toast.makeText(SoundRemixActivity.this,
                        "Remix failed. Try again.", Toast.LENGTH_LONG).show();
                }
            }
        );
    }

    // ── Firebase upload & metadata ───────────────────────────────────────────

    private void saveRemixToFirebase(String audioPath, String title) {
        String myUid, myName;
        try {
            myUid  = FirebaseUtils.getCurrentUid();
            myName = nvl(FirebaseUtils.getCurrentName());
        } catch (Exception e) {
            setUiEnabled(true);
            if (pbRemix != null) pbRemix.setVisibility(View.GONE);
            setStatus("Not signed in. Please log in and try again.");
            return;
        }

        // Generate a new document ID under "sounds/"
        String newId = FirebaseUtils.db().getReference("sounds").push().getKey();
        if (newId == null) newId = UUID.randomUUID().toString();
        final String finalId   = newId;
        final String finalName = myName;

        // Upload mixed audio to Firebase Storage
        com.google.firebase.storage.StorageReference storageRef =
            com.google.firebase.storage.FirebaseStorage.getInstance()
                .getReference("sounds").child(finalId + ".mp4");

        storageRef.putFile(android.net.Uri.fromFile(new File(audioPath)))
            .addOnSuccessListener(snapshot ->
                storageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                    String audioUrl = uri.toString();
                    long   nowMs    = System.currentTimeMillis();

                    // ── musicLibrary/{id} ───────────────────────────────────
                    Map<String, Object> lib = new HashMap<>();
                    lib.put("title",           title);
                    lib.put("artist",          finalName);
                    lib.put("audioUrl",        audioUrl);
                    lib.put("coverUrl",        soundACover);
                    lib.put("genre",           "Remix");
                    lib.put("bpm",             0);
                    lib.put("addedAt",         nowMs);
                    lib.put("uploadedByUid",   myUid);
                    lib.put("uploadedByName",  finalName);
                    lib.put("usageCount",      0);
                    FirebaseUtils.getMusicLibraryRef().child(finalId).updateChildren(lib);

                    // ── sounds/{id} ─────────────────────────────────────────
                    Map<String, Object> snd = new HashMap<>();
                    snd.put("title",            title);
                    snd.put("artist",           finalName);
                    snd.put("audioUrl",         audioUrl);
                    snd.put("coverUrl",         soundACover);
                    snd.put("creatorUid",       myUid);
                    snd.put("reel_count",       0);
                    snd.put("total_saves",      0);
                    snd.put("is_remix",         true);
                    snd.put("remix_source_a",   soundAId != null ? soundAId : "");
                    snd.put("remix_source_b",   soundBId);
                    snd.put("created_at",       ServerValue.TIMESTAMP);
                    FirebaseUtils.db().getReference("sounds").child(finalId).updateChildren(snd);

                    // ── Cleanup & navigate ──────────────────────────────────
                    if (pbRemix != null) pbRemix.setVisibility(View.GONE);
                    setUiEnabled(true);
                    // Delete the local mixed file now that upload is done
                    new File(audioPath).delete();

                    Toast.makeText(SoundRemixActivity.this,
                        "Remix created!", Toast.LENGTH_SHORT).show();

                    // Open the new remix in SoundDetailActivity
                    Intent i = new Intent(SoundRemixActivity.this, SoundDetailActivity.class);
                    i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    finalId);
                    i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, title);
                    i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   audioUrl);
                    i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      finalName);
                    i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   soundACover);
                    startActivity(i);
                    finish();
                })
            )
            .addOnFailureListener(e -> {
                if (pbRemix != null) pbRemix.setVisibility(View.GONE);
                setUiEnabled(true);
                setStatus("Upload failed: " + e.getMessage());
                Toast.makeText(this, "Upload failed. Try again.", Toast.LENGTH_LONG).show();
                // Don't delete local file so user can retry
            });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void setUiEnabled(boolean enabled) {
        if (btnCreateRemix != null) btnCreateRemix.setEnabled(enabled);
        if (btnPickSoundB  != null) btnPickSoundB.setEnabled(enabled);
        if (sbBlend != null) sbBlend.setEnabled(enabled);
        if (sbVolA  != null) sbVolA.setEnabled(enabled);
        if (sbVolB  != null) sbVolB.setEnabled(enabled);
    }

    private void setStatus(String msg) {
        if (tvStatus != null) tvStatus.setText(msg);
    }

    private static SeekBar.OnSeekBarChangeListener simpleSeekBar(
            java.util.function.IntConsumer onChange) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                onChange.accept(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
