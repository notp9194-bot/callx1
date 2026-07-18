package com.callx.app.music;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.ServerValue;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageException;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
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
 * ── PRODUCTION HARDENING (this version) ───────────────────────────────────
 *  1. Pre-flight checks — signed-in + network connectivity are verified
 *     BEFORE the (potentially long) download/mix step runs, instead of
 *     discovering "not signed in" only after the user waited through mixing.
 *  2. Duration cap — AudioMixHelper caps each source to 60s of audio before
 *     mixing, preventing OutOfMemoryError on long songs on low-end devices.
 *  3. Resilient downloads — AudioMixHelper's downloader now validates HTTP
 *     status codes, follows redirects, detects truncated downloads, and
 *     retries transient failures instead of silently caching a corrupt file.
 *  4. Real upload progress — Firebase Storage's addOnProgressListener drives
 *     a determinate progress bar instead of a generic spinner.
 *  5. Categorized upload errors — StorageException error codes are mapped to
 *     specific, actionable messages (auth, permission, quota, network,
 *     cancelled) instead of a generic "Upload failed".
 *  6. Smart retry — if the Storage upload fails, the already-mixed local file
 *     is kept and "Create Remix" turns into "Retry Upload", which re-uses it
 *     directly (no re-download, no re-mix). If the upload succeeds but the
 *     Realtime Database metadata write fails, retry re-uses the already
 *     uploaded audioUrl too — nothing is ever uploaded twice.
 *  7. Lifecycle-safe callbacks — every async callback checks isFinishing()/
 *     isDestroyed() before touching views, preventing crashes if the user
 *     navigates away mid-upload.
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

    /** Remixes are capped to this length — keeps memory bounded & output short-form. */
    private static final int MAX_REMIX_DURATION_MS = 60_000; // 60s

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

    // ── Retry state — lets us resume from wherever the last attempt failed ────
    // instead of re-downloading/re-mixing/re-uploading work that already succeeded.
    private String  pendingUploadPath  = null; // locally mixed file, kept until fully saved
    private String  pendingUploadTitle = null;
    private String  pendingRemixId     = null; // reserved sounds/{id} key, stable across retries
    private String  pendingAudioUrl    = null; // set once Storage upload succeeds
    private boolean uploadFailedAwaitingRetry   = false; // failed at the Storage upload step
    private boolean metadataFailedAwaitingRetry = false; // upload OK, RTDB write failed

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
            btnPickSoundB.setOnClickListener(v -> {
                // Picking a new Sound B invalidates any pending retry state from a
                // previous remix attempt — start fresh.
                clearPendingRemixState();
                startActivityForResult(
                    new Intent(this, ReelTrendingAudioActivity.class), REQ_PICK_SOUND_B);
            });

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
     * If the previous attempt already produced a mixed file / uploaded audio,
     * this resumes from there instead of redoing completed work (see the
     * "Smart retry" section in the class doc).
     */
    private void createRemix() {
        // ── Resume from a previous partial failure, if any ────────────────────
        if (metadataFailedAwaitingRetry && pendingRemixId != null && pendingAudioUrl != null) {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, "No internet connection. Check your network and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            retryMetadataWrite();
            return;
        }
        if (uploadFailedAwaitingRetry && pendingUploadPath != null && new File(pendingUploadPath).exists()) {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, "No internet connection. Check your network and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            saveRemixToFirebase(pendingUploadPath, pendingUploadTitle);
            return;
        }

        // ── Validation ──────────────────────────────────────────────────────
        if (soundAUrl.isEmpty()) {
            Toast.makeText(this, "Sound A not available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (soundBUrl.isEmpty()) {
            Toast.makeText(this, "Please pick Sound B first", Toast.LENGTH_SHORT).show();
            return;
        }

        // ── Pre-flight: signed in? online? (fail fast, before the long mix step) ──
        String uidCheck;
        try { uidCheck = FirebaseUtils.getCurrentUid(); } catch (Exception e) { uidCheck = null; }
        if (uidCheck == null || uidCheck.isEmpty()) {
            Toast.makeText(this, "Please log in to create a remix", Toast.LENGTH_LONG).show();
            return;
        }
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No internet connection. Check your network and try again.", Toast.LENGTH_LONG).show();
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

        // Fresh remix attempt → reset all retry state so we don't accidentally
        // reuse a stale id/path from a previous, unrelated remix.
        clearPendingRemixState();

        // ── UI → loading state ───────────────────────────────────────────────
        setUiEnabled(false);
        setRetryMode(false);
        if (pbRemix != null) {
            pbRemix.setIndeterminate(true);
            pbRemix.setVisibility(View.VISIBLE);
        }
        setStatus("Downloading and blending sounds…");

        // ── Mix — AudioMixHelper runs this on its own background thread ──────
        AudioMixHelper.mixTwoAudioFiles(
            this,
            soundAUrl, effectiveVolA,   // Sound A URL + volume
            soundBUrl, effectiveVolB,   // Sound B URL + volume
            1000,                       // 1-second fade-out at end
            MAX_REMIX_DURATION_MS,      // cap each source so mixing can't OOM on long songs
            new AudioMixHelper.MixCallback() {

                @Override
                public void onProgress(int pct) {
                    if (isFinishing() || isDestroyed()) return;
                    setStatus(pct < 30  ? "Downloading sounds… " + pct + "%" :
                              pct < 70  ? "Mixing tracks… "     + pct + "%" :
                                          "Encoding remix… "    + pct + "%");
                }

                @Override
                public void onSuccess(String outPath) {
                    if (isFinishing() || isDestroyed()) {
                        new File(outPath).delete();
                        return;
                    }
                    setStatus("Uploading remix…");
                    saveRemixToFirebase(outPath, finalTitle);
                }

                @Override
                public void onError(Exception e) {
                    if (isFinishing() || isDestroyed()) return;
                    setUiEnabled(true);
                    if (pbRemix != null) pbRemix.setVisibility(View.GONE);
                    String msg = friendlyMixError(e);
                    setStatus("Remix failed: " + msg);
                    Toast.makeText(SoundRemixActivity.this,
                        "Could not create remix. " + msg, Toast.LENGTH_LONG).show();
                }
            }
        );
    }

    // ── Firebase upload & metadata ───────────────────────────────────────────

    /**
     * Uploads the locally mixed audio file to Firebase Storage, then chains
     * into {@link #writeRemixMetadata} once the download URL is available.
     * On failure, the local file and reserved remix id are KEPT so a retry
     * can resume without re-downloading or re-mixing anything.
     */
    private void saveRemixToFirebase(String audioPath, String title) {
        if (isFinishing() || isDestroyed()) return;

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
        if (myUid == null || myUid.isEmpty()) {
            setUiEnabled(true);
            if (pbRemix != null) pbRemix.setVisibility(View.GONE);
            setStatus("Not signed in. Please log in and try again.");
            return;
        }

        File localFile = new File(audioPath);
        if (!localFile.exists() || localFile.length() == 0) {
            setUiEnabled(true);
            if (pbRemix != null) pbRemix.setVisibility(View.GONE);
            setStatus("Remix file is missing. Please create the remix again.");
            clearPendingRemixState();
            return;
        }

        pendingUploadPath  = audioPath;
        pendingUploadTitle = title;

        // Reserve (or reuse, on retry) a stable id under "sounds/" so repeated
        // retries don't create duplicate/orphaned sound entries.
        if (pendingRemixId == null) {
            String newId = FirebaseUtils.db().getReference("sounds").push().getKey();
            pendingRemixId = newId != null ? newId : UUID.randomUUID().toString();
        }
        final String finalId   = pendingRemixId;
        final String finalUid  = myUid;
        final String finalName = myName;

        setUiEnabled(false);
        setRetryMode(false);
        setStatus("Uploading remix… 0%");
        if (pbRemix != null) {
            pbRemix.setIndeterminate(false);
            pbRemix.setMax(100);
            pbRemix.setProgress(0);
            pbRemix.setVisibility(View.VISIBLE);
        }

        StorageMetadata metadata = new StorageMetadata.Builder()
            .setContentType("audio/mp4")
            .build();

        StorageReference storageRef = FirebaseStorage.getInstance()
            .getReference("sounds").child(finalId + ".mp4");

        storageRef.putFile(Uri.fromFile(localFile), metadata)
            .addOnProgressListener(snapshot -> {
                if (isFinishing() || isDestroyed()) return;
                long total = snapshot.getTotalByteCount();
                long sent  = snapshot.getBytesTransferred();
                int pct = total > 0 ? (int) Math.min(100, (sent * 100L) / total) : 0;
                if (pbRemix != null) pbRemix.setProgress(pct);
                setStatus("Uploading remix… " + pct + "%");
            })
            .addOnSuccessListener(snapshot ->
                storageRef.getDownloadUrl()
                    .addOnSuccessListener(uri -> {
                        pendingAudioUrl = uri.toString();
                        uploadFailedAwaitingRetry = false;
                        writeRemixMetadata(finalId, finalUid, finalName, pendingAudioUrl, title, audioPath);
                    })
                    .addOnFailureListener(e -> handleUploadFailure(e))
            )
            .addOnFailureListener(this::handleUploadFailure);
    }

    /** Retries only the RTDB metadata write after a successful upload — no re-upload. */
    private void retryMetadataWrite() {
        if (isFinishing() || isDestroyed()) return;
        if (pendingRemixId == null || pendingAudioUrl == null || pendingUploadTitle == null) {
            // Shouldn't happen, but fall back to a full fresh attempt if state is inconsistent.
            clearPendingRemixState();
            Toast.makeText(this, "Please try creating the remix again.", Toast.LENGTH_SHORT).show();
            return;
        }
        String myUid, myName;
        try {
            myUid  = FirebaseUtils.getCurrentUid();
            myName = nvl(FirebaseUtils.getCurrentName());
        } catch (Exception e) {
            Toast.makeText(this, "Not signed in. Please log in and try again.", Toast.LENGTH_LONG).show();
            return;
        }
        if (myUid == null || myUid.isEmpty()) {
            Toast.makeText(this, "Not signed in. Please log in and try again.", Toast.LENGTH_LONG).show();
            return;
        }
        setUiEnabled(false);
        setRetryMode(false);
        if (pbRemix != null) {
            pbRemix.setIndeterminate(true);
            pbRemix.setVisibility(View.VISIBLE);
        }
        writeRemixMetadata(pendingRemixId, myUid, myName, pendingAudioUrl, pendingUploadTitle, pendingUploadPath);
    }

    private void writeRemixMetadata(String finalId, String myUid, String finalName,
                                     String audioUrl, String title, String audioPath) {
        if (isFinishing() || isDestroyed()) return;
        setStatus("Saving remix details…");
        long nowMs = System.currentTimeMillis();

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

        FirebaseUtils.getMusicLibraryRef().child(finalId).updateChildren(lib)
            .addOnSuccessListener(v1 ->
                FirebaseUtils.db().getReference("sounds").child(finalId).updateChildren(snd)
                    .addOnSuccessListener(v2 -> onRemixFullyComplete(finalId, title, audioUrl, finalName, audioPath))
                    .addOnFailureListener(e -> handleMetadataFailure(e))
            )
            .addOnFailureListener(e -> handleMetadataFailure(e));
    }

    private void handleMetadataFailure(Exception e) {
        if (isFinishing() || isDestroyed()) return;
        // Audio is already safely uploaded — only the metadata write failed.
        // Keep pendingAudioUrl/pendingRemixId so retry skips the upload entirely.
        metadataFailedAwaitingRetry = true;
        uploadFailedAwaitingRetry   = false;
        setUiEnabled(true);
        setRetryMode(true);
        if (pbRemix != null) pbRemix.setVisibility(View.GONE);
        String msg = e.getMessage() != null ? e.getMessage() : "Couldn't save remix details";
        setStatus("Save failed: " + msg);
        Toast.makeText(this, "Remix uploaded, but saving details failed. Tap Retry.", Toast.LENGTH_LONG).show();
    }

    private void onRemixFullyComplete(String finalId, String title, String audioUrl,
                                       String finalName, String audioPath) {
        if (isFinishing() || isDestroyed()) return;

        if (pbRemix != null) pbRemix.setVisibility(View.GONE);
        setUiEnabled(true);

        // Delete the local mixed file now that everything is safely persisted.
        new File(audioPath).delete();
        clearPendingRemixState();

        Toast.makeText(this, "Remix created!", Toast.LENGTH_SHORT).show();

        // Open the new remix in SoundDetailActivity
        Intent i = new Intent(SoundRemixActivity.this, SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    finalId);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, title);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   audioUrl);
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      finalName);
        i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   soundACover);
        startActivity(i);
        finish();
    }

    /**
     * Called when the Storage upload itself fails. The mixed local file is
     * deliberately NOT deleted here so "Retry Upload" can re-use it directly.
     */
    private void handleUploadFailure(Exception e) {
        if (isFinishing() || isDestroyed()) return;
        uploadFailedAwaitingRetry   = true;
        metadataFailedAwaitingRetry = false;
        setUiEnabled(true);
        setRetryMode(true);
        if (pbRemix != null) pbRemix.setVisibility(View.GONE);
        String msg = friendlyStorageError(e);
        setStatus("Upload failed: " + msg);
        Toast.makeText(this, "Upload failed. " + msg, Toast.LENGTH_LONG).show();
    }

    /** Maps StorageException error codes to specific, actionable messages. */
    private static String friendlyStorageError(Exception e) {
        if (e instanceof StorageException) {
            StorageException se = (StorageException) e;
            switch (se.getErrorCode()) {
                case StorageException.ERROR_NOT_AUTHENTICATED:
                    return "You're signed out. Please log in and try again.";
                case StorageException.ERROR_NOT_AUTHORIZED:
                    return "You don't have permission to upload sounds.";
                case StorageException.ERROR_QUOTA_EXCEEDED:
                    return "Storage quota exceeded. Please try again later.";
                case StorageException.ERROR_RETRY_LIMIT_EXCEEDED:
                    return "Network is too slow or unstable. Check your connection and retry.";
                case StorageException.ERROR_CANCELED:
                    return "Upload was cancelled.";
                case StorageException.ERROR_UNKNOWN:
                default:
                    return se.getMessage() != null ? se.getMessage() : "Unknown upload error.";
            }
        }
        return e.getMessage() != null ? e.getMessage() : "Unknown error";
    }

    /** Friendlier text for mix/download errors surfaced from AudioMixHelper. */
    private static String friendlyMixError(Exception e) {
        String msg = e.getMessage();
        return (msg != null && !msg.trim().isEmpty()) ? msg : "Unknown error";
    }

    private void clearPendingRemixState() {
        pendingUploadPath = null;
        pendingUploadTitle = null;
        pendingRemixId = null;
        pendingAudioUrl = null;
        uploadFailedAwaitingRetry = false;
        metadataFailedAwaitingRetry = false;
        setRetryMode(false);
    }

    /** Swaps the Create button's label depending on whether a retry is pending. */
    private void setRetryMode(boolean retry) {
        if (btnCreateRemix == null) return;
        btnCreateRemix.setText(retry ? "🔁  Retry Upload" : "🎵  Create Remix");
    }

    // ── Connectivity ──────────────────────────────────────────────────────────

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true; // fail open — don't block the user on a lookup error
            android.net.Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        } catch (Exception e) {
            return true; // fail open
        }
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
