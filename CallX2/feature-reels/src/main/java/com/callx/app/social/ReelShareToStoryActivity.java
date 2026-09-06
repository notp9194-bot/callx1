package com.callx.app.social;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.io.File;
import java.util.*;

/**
 * ReelShareToStoryActivity — Share a reel clip (up to 15s) as a Story/Status.
 *
 * Features:
 *  ✅ Shows reel thumbnail + ExoPlayer preview
 *  ✅ Clip trim to max 15s for story (status) format
 *  ✅ Add text sticker / emoji reaction on top
 *  ✅ Add caption/link-back attribution ("Originally posted by @username")
 *  ✅ Privacy selector: Close Friends / All Followers / Public
 *  ✅ Story duration: 15s / 30s / 60s (auto-loops if shorter)
 *  ✅ Tap "Share to Story" → pushes to status/{uid} in Firebase
 *  ✅ Shows existing stories count + expiry reminder (24h auto-expire)
 */
public class ReelShareToStoryActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID        = "reel_id";
    public static final String EXTRA_REEL_URL        = "reel_url";
    public static final String EXTRA_REEL_OWNER_NAME = "owner_name";
    public static final String EXTRA_PRIVACY_PRESET  = "privacy_preset";

    private ImageButton  btnBack;
    private PlayerView   playerView;
    private ExoPlayer    player;
    private ProgressBar  progress;
    private TextView     tvAttribution, tvExpiryNote, tvStoryCount;
    private EditText     etStoryCaption, etStickerText;
    private Button       btnShareToStory;
    private RadioGroup   rgStoryPrivacy, rgStoryDuration;
    private SeekBar      sbClipEnd;
    private TextView     tvClipEndLabel;
    private LinearLayout layoutStickerPreview;
    private TextView     tvStickerOnVideo;

    private String myUid, reelId, reelUrl, ownerName;
    // ✅ NEW: resolves the Stories-sized watermark variant to bake into the shared clip.
    private String watermarkOwnerUid, watermarkOwnerName;
    private Boolean watermarkPerReelOverride;
    private boolean watermarkCreditGiven;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_share_to_story);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        reelId    = getIntent().getStringExtra(EXTRA_REEL_ID);
        reelUrl   = getIntent().getStringExtra(EXTRA_REEL_URL);
        ownerName = getIntent().getStringExtra(EXTRA_REEL_OWNER_NAME);
        watermarkOwnerUid  = getIntent().getStringExtra("watermark_owner_uid");
        watermarkOwnerName = getIntent().getStringExtra("watermark_owner_name");
        watermarkPerReelOverride = getIntent().hasExtra("watermark_per_reel_override")
            ? getIntent().getBooleanExtra("watermark_per_reel_override", true) : null;
        watermarkCreditGiven = getIntent().getBooleanExtra("watermark_credit_given", false);
        bindViews();
        String preset = getIntent().getStringExtra(EXTRA_PRIVACY_PRESET);
        if ("close_friends".equals(preset)) {
            for (int i = 0; i < rgStoryPrivacy.getChildCount(); i++) {
                View child = rgStoryPrivacy.getChildAt(i);
                if (child instanceof RadioButton
                        && ((RadioButton) child).getText().toString().toLowerCase()
                            .contains("close")) {
                    ((RadioButton) child).setChecked(true);
                    break;
                }
            }
        }
        setupPlayer();
        loadMyStoryCount();
    }

    private void bindViews() {
        btnBack          = findViewById(R.id.btn_sts_back);
        playerView       = findViewById(R.id.player_story_preview);
        progress         = findViewById(R.id.progress_sts);
        tvAttribution    = findViewById(R.id.tv_sts_attribution);
        tvExpiryNote     = findViewById(R.id.tv_sts_expiry_note);
        tvStoryCount     = findViewById(R.id.tv_sts_story_count);
        etStoryCaption   = findViewById(R.id.et_sts_caption);
        etStickerText    = findViewById(R.id.et_sts_sticker_text);
        btnShareToStory  = findViewById(R.id.btn_sts_share);
        rgStoryPrivacy   = findViewById(R.id.rg_sts_privacy);
        rgStoryDuration  = findViewById(R.id.rg_sts_duration);
        sbClipEnd        = findViewById(R.id.sb_sts_clip_end);
        tvClipEndLabel   = findViewById(R.id.tv_sts_clip_label);
        tvStickerOnVideo = findViewById(R.id.tv_sts_sticker_on_video);

        btnBack.setOnClickListener(v -> finish());
        btnShareToStory.setOnClickListener(v -> shareToStory());

        tvAttribution.setText("Originally posted by @" + (ownerName != null ? ownerName : "Unknown"));
        tvExpiryNote.setText("Stories expire after 24 hours automatically.");

        sbClipEnd.setMax(14); sbClipEnd.setProgress(14);
        sbClipEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvClipEndLabel.setText("Clip length: " + (p + 1) + "s");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        etStickerText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                tvStickerOnVideo.setText(s.toString());
                tvStickerOnVideo.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void setupPlayer() {
        progress.setVisibility(View.VISIBLE);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        String url = reelUrl != null ? reelUrl
            : "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4";
        player.setMediaItem(MediaItem.fromUri(url));
        player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
        player.prepare();
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == ExoPlayer.STATE_READY) {
                    runOnUiThread(() -> progress.setVisibility(View.GONE));
                }
            }
        });
    }

    private void loadMyStoryCount() {
        FirebaseUtils.db().getReference("status").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    int count = (int) snap.getChildrenCount();
                    tvStoryCount.setText("You have " + count + " active stories");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void shareToStory() {
        String caption     = etCaption(etStoryCaption);
        String stickerText = etCaption(etStickerText);
        int clipLen        = sbClipEnd.getProgress() + 1;
        String privacyText = getSelectedRadioText(rgStoryPrivacy);
        // Persist the same privacy enum used by NewStatusActivity and the
        // status viewers; the human-readable radio label is only UI text.
        if (privacyText.toLowerCase().contains("close")) privacyText = "close_friends";
        else if (privacyText.toLowerCase().contains("public")) privacyText = "everyone";
        else privacyText = "followers";
        String durText     = getSelectedRadioText(rgStoryDuration);

        progress.setVisibility(View.VISIBLE);
        btnShareToStory.setEnabled(false);

        bakeStoryWatermarkThenPush(clipLen, caption, stickerText, privacyText, durText);
    }

    /**
     * ✅ NEW: bakes the Stories-sized watermark variant (see
     * ReelWatermarkSettingsActivity's "Customize for Stories") into the actual
     * clip being shared, instead of just re-pointing to the reel's already-
     * baked Feed/Reels master file (which — before this — was the ONLY
     * watermark size a Story share could ever show, hence "one setting for
     * everyone"). Mirrors ReelShareController#downloadReel()'s
     * download → export → (re-)upload pattern. Falls back to the plain
     * reelUrl at any step that fails, so a slow/broken bake never blocks the
     * share itself.
     */
    private void bakeStoryWatermarkThenPush(int clipLen, String caption, String stickerText,
                                             String privacyText, String durText) {
        if (reelUrl == null || reelUrl.isEmpty() || watermarkOwnerUid == null || watermarkOwnerUid.isEmpty()) {
            pushStoryEntry(reelUrl, caption, stickerText, privacyText, durText);
            return;
        }
        final Context appCtx = getApplicationContext();
        final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        new Thread(() -> {
            com.callx.app.editor.ReelVideoExportEngine.WatermarkSpec watermark =
                com.callx.app.editor.ReelVideoExportEngine.resolveWatermarkSpec(
                    appCtx, watermarkOwnerUid, watermarkOwnerName, watermarkPerReelOverride,
                    watermarkCreditGiven, com.callx.app.editor.ReelVideoExportEngine.WatermarkSurface.STORY);
            if (watermark == null) {
                // No watermark configured/enabled — share the original file, no bake needed.
                mainHandler.post(() -> pushStoryEntry(reelUrl, caption, stickerText, privacyText, durText));
                return;
            }
            File downloaded;
            try {
                downloaded = downloadToCacheFile(appCtx, reelUrl, reelId != null ? reelId : String.valueOf(System.currentTimeMillis()));
            } catch (Exception e) {
                mainHandler.post(() -> pushStoryEntry(reelUrl, caption, stickerText, privacyText, durText));
                return;
            }
            final File srcFile = downloaded;
            mainHandler.post(() -> com.callx.app.editor.ReelVideoExportEngine.export(
                appCtx, srcFile.getAbsolutePath(), null, 0f, 1f, 1f, null, watermark,
                0L, clipLen * 1000L,
                new com.callx.app.editor.ReelVideoExportEngine.ExportCallback() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onSuccess(String outputPath) {
                        com.callx.app.utils.CloudinaryUploader.upload(appCtx, Uri.fromFile(new File(outputPath)),
                            "story_clips", "video", new com.callx.app.utils.CloudinaryUploader.UploadCallback() {
                                @Override public void onSuccess(com.callx.app.utils.CloudinaryUploader.Result result) {
                                    //noinspection ResultOfMethodCallIgnored
                                    srcFile.delete();
                                    //noinspection ResultOfMethodCallIgnored
                                    new File(outputPath).delete();
                                    pushStoryEntry(result.secureUrl, caption, stickerText, privacyText, durText);
                                }
                                @Override public void onError(String message) {
                                    //noinspection ResultOfMethodCallIgnored
                                    srcFile.delete();
                                    //noinspection ResultOfMethodCallIgnored
                                    new File(outputPath).delete();
                                    // Upload of the baked clip failed — still share
                                    // something rather than losing the share entirely.
                                    pushStoryEntry(reelUrl, caption, stickerText, privacyText, durText);
                                }
                            });
                    }
                    @Override public void onError(Exception e) {
                        //noinspection ResultOfMethodCallIgnored
                        srcFile.delete();
                        pushStoryEntry(reelUrl, caption, stickerText, privacyText, durText);
                    }
                }));
        }).start();
    }

    /** Downloads {@code url} into the app's cache dir. Runs on a background thread. */
    private File downloadToCacheFile(Context appCtx, String url, String reelIdForName) throws Exception {
        File outDir = new File(appCtx.getCacheDir(), "story_share_src");
        if (!outDir.exists()) //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
        File out = new File(outDir, "callx_story_src_" + reelIdForName + "_" + System.currentTimeMillis() + ".mp4");
        java.net.URL u = new java.net.URL(url);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try (java.io.InputStream in = conn.getInputStream();
             java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        return out;
    }

    private void pushStoryEntry(String videoUrlToUse, String caption, String stickerText,
                                 String privacyText, String durText) {
        if (isFinishing() || isDestroyed()) return;
        int clipLen = sbClipEnd.getProgress() + 1;
        DatabaseReference storyRef = FirebaseUtils.db().getReference("status").child(myUid).push();
        String storyId = storyRef.getKey();
        if (storyId == null) { progress.setVisibility(View.GONE); btnShareToStory.setEnabled(true); return; }

        Map<String, Object> m = new HashMap<>();
        m.put("id",          storyId);
        m.put("type",        "reel_clip");
        m.put("reelId",      reelId != null ? reelId : "");
        m.put("videoUrl",    videoUrlToUse != null ? videoUrlToUse : "");
        m.put("caption",     caption);
        m.put("stickerText", stickerText);
        m.put("attribution", "@" + (ownerName != null ? ownerName : ""));
        m.put("clipLength",  clipLen);
        m.put("privacy",     privacyText);
        m.put("duration",    durText);
        m.put("timestamp",   System.currentTimeMillis());
        m.put("expiresAt",   System.currentTimeMillis() + 86400000L);
        m.put("ownerUid",    myUid);
        m.put("ownerName",   FirebaseUtils.getCurrentName());

        storyRef.setValue(m).addOnCompleteListener(t -> {
            if (!isFinishing()) {
                progress.setVisibility(View.GONE);
                btnShareToStory.setEnabled(true);
                if (t.isSuccessful()) {
                    Toast.makeText(this, "Shared to your story! ✓", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(this, "Share failed. Try again.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private String etCaption(EditText et) {
        return et != null && et.getText() != null ? et.getText().toString().trim() : "";
    }
    private String getSelectedRadioText(RadioGroup rg) {
        if (rg == null) return "";
        RadioButton rb = rg.findViewById(rg.getCheckedRadioButtonId());
        return rb != null ? rb.getText().toString() : "";
    }

    @Override protected void onPause()   { if (player != null) player.pause(); super.onPause(); }
    @Override protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
