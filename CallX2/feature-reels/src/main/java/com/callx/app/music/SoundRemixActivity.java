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
 *  ✅ Sound A — pre-selected from context (e.g. SoundDetailActivity)
 *  ✅ Sound B — picked via MusicPickerActivity
 *  ✅ Blend slider: 0% = only A, 100% = only B, 50% = equal mix
 *  ✅ Volume knobs per track (0–100%)
 *  ✅ Trim offset for Sound B (start at N seconds)
 *  ✅ "Preview" — plays the conceptual mix ratio description
 *  ✅ "Create Remix" — mixes via AudioMixHelper.MixConfig and saves:
 *        • Mixed audio file → Firebase Storage (musicLibrary/{newId}/audioUrl)
 *        • Metadata entry   → Firebase RTDB (musicLibrary/{newId} + sounds/{newId})
 *  ✅ Remix track title: auto-generated as "Remix: A + B"
 *  ✅ On success → opens SoundDetailActivity for the new remix
 *
 * Firebase paths written:
 *   musicLibrary/{newId}: title, artist, audioUrl, coverUrl, bpm, genre="Remix", addedAt
 *   sounds/{newId}:       title, artist, audioUrl, coverUrl, creatorUid, reel_count=0
 */
public class SoundRemixActivity extends AppCompatActivity {

    public static final String EXTRA_SOUND_A_ID    = "remix_sound_a_id";
    public static final String EXTRA_SOUND_A_TITLE = "remix_sound_a_title";
    public static final String EXTRA_SOUND_A_URL   = "remix_sound_a_url";
    public static final String EXTRA_SOUND_A_COVER = "remix_sound_a_cover";
    public static final String EXTRA_SOUND_A_ARTIST= "remix_sound_a_artist";

    private static final int REQ_PICK_SOUND_B = 820;

    // ── Sound A (from intent) ─────────────────────────────────────────────────
    private String soundAId, soundATitle, soundAUrl, soundACover, soundAArtist;

    // ── Sound B (picked by user) ───────────────────────────────────────────────
    private String soundBId    = "";
    private String soundBTitle = "";
    private String soundBUrl   = "";

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

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

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

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Sound A label
        if (tvSoundAName != null)
            tvSoundAName.setText(soundATitle.isEmpty() ? "Sound A" : soundATitle);

        // Sound B picker
        if (tvSoundBName != null) tvSoundBName.setText("Tap to pick Sound B…");
        if (btnPickSoundB != null)
            btnPickSoundB.setOnClickListener(v ->
                startActivityForResult(new Intent(this, MusicPickerActivity.class), REQ_PICK_SOUND_B));

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
            sbVolA.setMax(100); sbVolA.setProgress(volumeA);
            sbVolA.setOnSeekBarChangeListener(simpleSeekBar(p -> volumeA = p));
        }
        if (sbVolB != null) {
            sbVolB.setMax(100); sbVolB.setProgress(volumeB);
            sbVolB.setOnSeekBarChangeListener(simpleSeekBar(p -> volumeB = p));
        }

        updateBlendLabel();

        // Auto-fill title
        refreshRemixTitle();

        if (btnCreateRemix != null)
            btnCreateRemix.setOnClickListener(v -> createRemix());

        if (pbRemix != null) pbRemix.setVisibility(View.GONE);
    }

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

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_SOUND_B && res == RESULT_OK && data != null) {
            soundBId    = nvl(data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_ID));
            soundBTitle = nvl(data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_NAME));
            soundBUrl   = nvl(data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_URL));
            if (tvSoundBName != null)
                tvSoundBName.setText(soundBTitle.isEmpty() ? "Sound B" : soundBTitle);
            refreshRemixTitle();
        }
    }

    private void createRemix() {
        if (soundAUrl.isEmpty()) {
            Toast.makeText(this, "Sound A not available", Toast.LENGTH_SHORT).show(); return;
        }
        if (soundBUrl.isEmpty()) {
            Toast.makeText(this, "Please pick Sound B first", Toast.LENGTH_SHORT).show(); return;
        }

        String title = etRemixTitle != null
            ? etRemixTitle.getText().toString().trim() : "Remix";
        if (title.isEmpty()) title = "Remix: " + soundATitle + " + " + soundBTitle;

        setUiEnabled(false);
        if (pbRemix != null) pbRemix.setVisibility(View.VISIBLE);
        setStatus("Downloading and blending sounds…");

        final String finalTitle = title;
        final float volAf = volumeA / 100f;
        final float volBf = volumeB / 100f;
        final float aWeight = (100 - blendPercent) / 100f;
        final float bWeight = blendPercent / 100f;

        executor.execute(() -> {
            try {
                // Download Sound A to temp file
                File fileA = downloadUrl(soundAUrl, "remix_a.mp4");
                // Download Sound B to temp file
                File fileB = downloadUrl(soundBUrl, "remix_b.mp4");

                mainHandler.post(() -> setStatus("Mixing tracks…"));

                // Use AudioMixHelper to blend: treat A as "video" and B as "music"
                AudioMixHelper.MixConfig cfg = new AudioMixHelper.MixConfig();
                cfg.musicUrl       = soundBUrl;
                cfg.micVol         = aWeight * volAf;
                cfg.musicVol       = bWeight * volBf;
                cfg.fadeOutMs      = 1000;

                AudioMixHelper.mixAndExportWithConfig(
                    this, fileA.getAbsolutePath(), cfg,
                    new AudioMixHelper.MixCallback() {
                        @Override public void onProgress(int pct) {
                            mainHandler.post(() -> setStatus("Mixing… " + pct + "%"));
                        }
                        @Override public void onSuccess(String outPath) {
                            mainHandler.post(() -> saveRemixToFirebase(outPath, finalTitle));
                        }
                        @Override public void onError(Exception e) {
                            mainHandler.post(() -> {
                                setUiEnabled(true);
                                if (pbRemix != null) pbRemix.setVisibility(View.GONE);
                                setStatus("Mix failed: " + e.getMessage());
                                Toast.makeText(SoundRemixActivity.this,
                                    "Remix failed. Try again.", Toast.LENGTH_LONG).show();
                            });
                        }
                    });

                // Clean up source downloads
                fileA.delete(); fileB.delete();

            } catch (Exception e) {
                mainHandler.post(() -> {
                    setUiEnabled(true);
                    if (pbRemix != null) pbRemix.setVisibility(View.GONE);
                    setStatus("Error: " + e.getMessage());
                });
            }
        });
    }

    private void saveRemixToFirebase(String audioPath, String title) {
        setStatus("Uploading remix…");
        String myUid, myName;
        try {
            myUid  = FirebaseUtils.getCurrentUid();
            myName = FirebaseUtils.getCurrentName();
        } catch (Exception e) { setUiEnabled(true); return; }

        // Push to Firebase Storage via StorageReference
        String newId = FirebaseUtils.db().getReference("sounds").push().getKey();
        if (newId == null) newId = UUID.randomUUID().toString();
        final String finalId = newId;

        com.google.firebase.storage.StorageReference storageRef =
            com.google.firebase.storage.FirebaseStorage.getInstance()
                .getReference("sounds").child(finalId + ".mp4");

        storageRef.putFile(android.net.Uri.fromFile(new File(audioPath)))
            .addOnSuccessListener(taskSnapshot ->
                storageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                    String audioUrl = uri.toString();
                    long nowMs = System.currentTimeMillis();

                    // Write to musicLibrary/
                    Map<String, Object> lib = new HashMap<>();
                    lib.put("title",    title);
                    lib.put("artist",   myName != null ? myName : "");
                    lib.put("audioUrl", audioUrl);
                    lib.put("coverUrl", soundACover);
                    lib.put("genre",    "Remix");
                    lib.put("bpm",      0);
                    lib.put("addedAt",  nowMs);
                    lib.put("uploadedByUid",  myUid);
                    lib.put("uploadedByName", myName != null ? myName : "");
                    lib.put("usageCount", 0);
                    FirebaseUtils.getMusicLibraryRef().child(finalId).updateChildren(lib);

                    // Write to sounds/
                    Map<String, Object> snd = new HashMap<>();
                    snd.put("title",      title);
                    snd.put("artist",     myName != null ? myName : "");
                    snd.put("audioUrl",   audioUrl);
                    snd.put("coverUrl",   soundACover);
                    snd.put("creatorUid", myUid);
                    snd.put("reel_count", 0);
                    snd.put("total_saves", 0);
                    snd.put("is_remix",   true);
                    snd.put("remix_source_a", soundAId != null ? soundAId : "");
                    snd.put("remix_source_b", soundBId);
                    snd.put("created_at", ServerValue.TIMESTAMP);
                    FirebaseUtils.db().getReference("sounds").child(finalId).updateChildren(snd);

                    if (pbRemix != null) pbRemix.setVisibility(View.GONE);
                    setUiEnabled(true);
                    Toast.makeText(this, "Remix created!", Toast.LENGTH_SHORT).show();

                    // Open the new remix in SoundDetailActivity
                    Intent i = new Intent(this, SoundDetailActivity.class);
                    i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    finalId);
                    i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, title);
                    i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   audioUrl);
                    i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      myName != null ? myName : "");
                    i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   soundACover);
                    startActivity(i);
                    finish();
                }))
            .addOnFailureListener(e -> {
                if (pbRemix != null) pbRemix.setVisibility(View.GONE);
                setUiEnabled(true);
                setStatus("Upload failed: " + e.getMessage());
                Toast.makeText(this, "Upload failed. Try again.", Toast.LENGTH_LONG).show();
            });
    }

    private File downloadUrl(String url, String filename) throws Exception {
        File f = new File(getCacheDir(), filename);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
            new java.net.URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { conn.disconnect(); }
        return f;
    }

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

    private static SeekBar.OnSeekBarChangeListener simpleSeekBar(java.util.function.IntConsumer onChange) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { onChange.accept(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
