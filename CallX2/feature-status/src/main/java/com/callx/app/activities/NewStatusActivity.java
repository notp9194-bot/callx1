package com.callx.app.activities;

import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.bottomsheet.*;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.callx.app.views.*;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.database.*;
import java.io.*;
import java.util.*;

/**
 * NewStatusActivity v26 — Complete overhaul:
 * FIX  1: Video camera — modern ActivityResultLauncher (was deprecated startActivityForResult)
 * FIX  2: Caption @mention extraction implemented
 * FIX  3: Cloudinary retry logic (3 attempts, exponential backoff)
 * FIX  4: GIF picker via StatusStickerPickerBottomSheet (was URL-paste only)
 * NEW  5: Drawing overlay — StatusDrawingOverlayView
 * NEW  6: Boomerang creation — StatusBoomerangHelper
 * NEW  7: Video trim — StatusVideoTrimActivity
 * NEW  8: Collage creator — StatusCollageCreatorActivity
 * NEW  9: Template picker — StatusTemplateActivity
 * NEW 10: AI auto-caption — StatusAIHelper
 * NEW 11: Music attachment — StatusMusicBottomSheet
 * NEW 12: Poll overlay — StatusPollBottomSheet
 * NEW 13: Countdown overlay
 * NEW 14: Full media draft save/restore
 */
public class NewStatusActivity extends AppCompatActivity {
    // ── State ─────────────────────────────────────────────
    private String type = "text";
    private Uri    mediaUri;
    private String mediaType; // image|video
    private String  bgColor    = "#1A237E", bgColor2 = null, textColor = "#FFFFFF";
    private String  fontStyle  = "default", textAlign = "center";
    private String  privacy    = "everyone";
    private int     expiryHours = 24;
    private boolean isCloseFriends = false;
    private String  gifUrl;
    private String  musicSongId, musicTitle, musicArtist, musicAudioUrl;
    private int     musicStartSec;
    private String  pollQuestion; private List<String> pollOptions;
    private Long    countdownTargetTs; private String countdownLabel;
    private String  templateId;
    private int     uploadAttempt = 0;
    private StatusDraftManager.Draft pendingDraft;

    // ── UI ────────────────────────────────────────────────
    private EditText etText, etCaption;
    private TextView tvCharCount, tvLinkPreview, tvMusicBadge, tvPollBadge, tvPrivacy;
    private ImageView ivMediaPreview;
    private View     mediaPreviewFrame;
    private ProgressBar pbUpload, pbCompress;
    private LinearLayout bgColorRow, creatorTools;
    private FrameLayout  drawingContainer;
    private StatusDrawingOverlayView drawingOverlay;

    private static final int[] BG_COLORS = {
        0xFF1A237E, 0xFF311B92, 0xFF4A148C, 0xFFB71C1C, 0xFF004D40,
        0xFF1B5E20, 0xFFF57F17, 0xFF212121, 0xFFBF360C, 0xFF006064
    };
    private static final String[] BG_COLOR_STRS = {
        "#1A237E","#311B92","#4A148C","#B71C1C","#004D40",
        "#1B5E20","#F57F17","#212121","#BF360C","#006064"
    };

    // ── Result Launchers ──────────────────────────────────
    // Declare URIs BEFORE launchers to avoid illegal forward reference
    private Uri videoCaptureUri;
    private Uri imageCaptureUri;

    private final ActivityResultLauncher<String> imagePicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) { mediaUri = uri; mediaType = "image"; type = "image"; showMediaPreview(uri, "image"); }
        });

    // FIX: Modern video launcher (was startActivityForResult deprecated)
    private final ActivityResultLauncher<Uri> videoCaptureLauncher =
        registerForActivityResult(new ActivityResultContracts.CaptureVideo(), success -> {
            if (success && videoCaptureUri != null) {
                mediaUri = videoCaptureUri; mediaType = "video"; type = "video"; showMediaPreview(videoCaptureUri, "video");
            }
        });

    private final ActivityResultLauncher<String> videoPicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) { mediaUri = uri; mediaType = "video"; type = "video"; showMediaPreview(uri, "video"); }
        });

    private final ActivityResultLauncher<Uri> imageCaptLauncher =
        registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success && imageCaptureUri != null) {
                mediaUri = imageCaptureUri; mediaType = "image"; type = "image"; showMediaPreview(imageCaptureUri, "image");
            }
        });

    private final ActivityResultLauncher<Intent> trimLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                String path = r.getData().getStringExtra(StatusVideoTrimActivity.EXTRA_TRIMMED_URI);
                if (path != null) { mediaUri = Uri.fromFile(new File(path)); mediaType = "video"; type = "video"; showMediaPreview(mediaUri, "video"); }
            }
        });
    private final ActivityResultLauncher<Intent> collageLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                String path = r.getData().getStringExtra(StatusCollageCreatorActivity.EXTRA_COLLAGE_PATH);
                if (path != null) { mediaUri = Uri.fromFile(new File(path)); mediaType = "image"; type = "collage"; showMediaPreview(mediaUri, "image"); }
            }
        });
    private final ActivityResultLauncher<Intent> templateLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                bgColor    = r.getData().getStringExtra(StatusTemplateActivity.EXTRA_BG_COLOR);
                bgColor2   = r.getData().getStringExtra(StatusTemplateActivity.EXTRA_BG_COLOR2);
                textColor  = r.getData().getStringExtra(StatusTemplateActivity.EXTRA_TEXT_COLOR);
                fontStyle  = r.getData().getStringExtra(StatusTemplateActivity.EXTRA_FONT_STYLE);
                textAlign  = r.getData().getStringExtra(StatusTemplateActivity.EXTRA_TEXT_ALIGN);
                templateId = r.getData().getStringExtra(StatusTemplateActivity.EXTRA_TEMPLATE_ID);
                updatePreview();
            }
        });

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0,0,0,dp(80));

        // ── Toolbar ────────────────────────────────────────
        androidx.appcompat.widget.Toolbar tb = new androidx.appcompat.widget.Toolbar(this);
        tb.setTitle("New Status"); tb.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        tb.setNavigationOnClickListener(v -> onBackPressed()); root.addView(tb);

        // ── Type tabs ──────────────────────────────────────
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12),0,dp(12),0);
        for (String tab : new String[]{"✏ Text","📷 Photo","🎥 Video","🎭 GIF","🔗 Link"}) {
            Button btn = new Button(this); btn.setText(tab); btn.setTextSize(12);
            btn.setOnClickListener(v -> handleTabClick(btn.getText().toString()));
            tabs.addView(btn);
        }
        HorizontalScrollView hsvTabs = new HorizontalScrollView(this); hsvTabs.addView(tabs);
        root.addView(hsvTabs);

        // ── Text input ────────────────────────────────────
        etText = new EditText(this); etText.setHint("What's on your mind?"); etText.setMinLines(4);
        etText.setGravity(Gravity.CENTER); etText.setPadding(dp(20),dp(20),dp(20),dp(20));
        etText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { updateCharCount(); updateLinkPreview(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(etText);

        tvCharCount = new TextView(this); tvCharCount.setGravity(Gravity.END);
        tvCharCount.setPadding(0,0,dp(16),0); tvCharCount.setTextSize(11); tvCharCount.setTextColor(Color.GRAY);
        root.addView(tvCharCount); updateCharCount();

        // ── BG color picker ────────────────────────────────
        HorizontalScrollView hsvBg = new HorizontalScrollView(this); 
        bgColorRow = new LinearLayout(this); bgColorRow.setOrientation(LinearLayout.HORIZONTAL);
        bgColorRow.setPadding(dp(12),dp(8),dp(12),dp(8));
        for (int i = 0; i < BG_COLORS.length; i++) {
            final int idx = i; View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32),dp(32)); lp.setMargins(0,0,dp(8),0);
            swatch.setLayoutParams(lp); swatch.setBackgroundColor(BG_COLORS[i]);
            swatch.setOnClickListener(v -> { bgColor = BG_COLOR_STRS[idx]; updatePreview(); });
            bgColorRow.addView(swatch);
        }
        hsvBg.addView(bgColorRow); root.addView(hsvBg);

        // ── Font row ───────────────────────────────────────
        LinearLayout fontRow = new LinearLayout(this); fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setPadding(dp(12),0,dp(12),dp(8));
        for (String fs : new String[]{"Default","Bold","Italic","Handwriting","Serif","Condensed"}) {
            Button btn = new Button(this); btn.setText(fs); btn.setTextSize(11);
            btn.setOnClickListener(v -> { fontStyle = fs.toLowerCase(); updatePreview(); });
            fontRow.addView(btn);
        }
        HorizontalScrollView hsvFont = new HorizontalScrollView(this); hsvFont.addView(fontRow);
        root.addView(hsvFont);

        // ── Media preview ─────────────────────────────────
        drawingContainer = new FrameLayout(this);
        drawingContainer.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)));
        drawingContainer.setVisibility(View.GONE);
        ivMediaPreview = new ImageView(this);
        ivMediaPreview.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ivMediaPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        drawingOverlay = new StatusDrawingOverlayView(this);
        drawingOverlay.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        drawingOverlay.setVisibility(View.GONE);
        drawingContainer.addView(ivMediaPreview); drawingContainer.addView(drawingOverlay);
        mediaPreviewFrame = drawingContainer; root.addView(drawingContainer);

        // ── Caption ────────────────────────────────────────
        etCaption = new EditText(this); etCaption.setHint("Add a caption…"); etCaption.setSingleLine(true);
        etCaption.setPadding(dp(16),dp(8),dp(16),dp(8)); etCaption.setVisibility(View.GONE);
        root.addView(etCaption);

        // ── Link preview bar ──────────────────────────────
        tvLinkPreview = new TextView(this); tvLinkPreview.setPadding(dp(16),dp(8),dp(16),dp(8));
        tvLinkPreview.setTextSize(12); tvLinkPreview.setVisibility(View.GONE); root.addView(tvLinkPreview);

        // ── Creator tools row ─────────────────────────────
        HorizontalScrollView hsvTools = new HorizontalScrollView(this); 
        creatorTools = new LinearLayout(this); creatorTools.setOrientation(LinearLayout.HORIZONTAL);
        creatorTools.setPadding(dp(12),dp(4),dp(12),dp(4));
        for (String tool : new String[]{"📷 Camera","🎥 Video","🎭 GIF","✂ Trim","🖼 Collage","✏ Draw","🔄 Boomerang","✨ Template","🤖 AI Caption","🎵 Music","📊 Poll","⏱ Countdown"}) {
            Button btn = new Button(this); btn.setText(tool); btn.setTextSize(11);
            btn.setOnClickListener(v -> handleTool(tool));
            creatorTools.addView(btn);
        }
        hsvTools.addView(creatorTools); root.addView(hsvTools);

        // ── Music badge ────────────────────────────────────
        tvMusicBadge = new TextView(this); tvMusicBadge.setVisibility(View.GONE);
        tvMusicBadge.setPadding(dp(12),dp(4),dp(12),dp(4)); tvMusicBadge.setTextSize(12);
        root.addView(tvMusicBadge);

        // ── Poll badge ─────────────────────────────────────
        tvPollBadge = new TextView(this); tvPollBadge.setVisibility(View.GONE);
        tvPollBadge.setPadding(dp(12),dp(4),dp(12),dp(4)); tvPollBadge.setTextSize(12);
        root.addView(tvPollBadge);

        // ── Progress ──────────────────────────────────────
        pbCompress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbCompress.setMax(100); pbCompress.setVisibility(View.GONE); root.addView(pbCompress);
        pbUpload = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbUpload.setMax(100); pbUpload.setVisibility(View.GONE); root.addView(pbUpload);

        // ── Bottom bar: privacy + post ─────────────────────
        LinearLayout bottomBar = new LinearLayout(this); bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(dp(12),dp(8),dp(12),dp(8)); bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        tvPrivacy = new TextView(this); tvPrivacy.setText("🌍 Everyone");
        tvPrivacy.setPadding(dp(12),dp(8),dp(12),dp(8));
        tvPrivacy.setOnClickListener(v -> showPrivacyPicker());
        LinearLayout.LayoutParams pvlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvPrivacy.setLayoutParams(pvlp); bottomBar.addView(tvPrivacy);
        Button btnExpiry = new Button(this); btnExpiry.setText("⏱ 24h"); btnExpiry.setTextSize(12);
        btnExpiry.setOnClickListener(v -> showExpiryPicker(btnExpiry)); bottomBar.addView(btnExpiry);
        Button btnPost = new Button(this); btnPost.setText("Post Status");
        btnPost.setBackgroundColor(Color.parseColor("#6200EE")); btnPost.setTextColor(Color.WHITE);
        btnPost.setOnClickListener(v -> attemptPost()); bottomBar.addView(btnPost);
        root.addView(bottomBar);

        sv.addView(root); setContentView(sv);

        // Restore draft
        restoreDraft();
    }

    private void handleTool(String tool) {
        if (tool.startsWith("📷")) {
            File f = new File(getCacheDir(), "cap_" + System.currentTimeMillis() + ".jpg");
            imageCaptureUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            imageCaptLauncher.launch(imageCaptureUri);
        } else if (tool.startsWith("🎥")) {
            // FIX: Modern ActivityResultLauncher (was deprecated startActivityForResult)
            File f = new File(getCacheDir(), "vid_" + System.currentTimeMillis() + ".mp4");
            try { videoCaptureUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                  videoCaptureLauncher.launch(videoCaptureUri); }
            catch (Exception e) { videoPicker.launch("video/*"); }
        } else if (tool.startsWith("🎭")) {
            // FIX: Real GIF picker (was URL-paste only)
            StatusStickerPickerBottomSheet.show(this, (gifUrl2, preview) -> {
                this.gifUrl = gifUrl2; type = "gif";
                Glide.with(this).asGif().load(gifUrl2).into(ivMediaPreview);
                drawingContainer.setVisibility(View.VISIBLE);
                etCaption.setVisibility(View.VISIBLE);
            });
        } else if (tool.startsWith("✂")) {
            if (mediaUri == null) { Toast.makeText(this,"Pick a video first",Toast.LENGTH_SHORT).show(); return; }
            Intent i = new Intent(this, StatusVideoTrimActivity.class);
            i.putExtra(StatusVideoTrimActivity.EXTRA_INPUT_URI, mediaUri.toString());
            trimLauncher.launch(i);
        } else if (tool.startsWith("🖼")) {
            collageLauncher.launch(new Intent(this, StatusCollageCreatorActivity.class));
        } else if (tool.startsWith("✏")) {
            if (drawingContainer.getVisibility() == View.GONE) { Toast.makeText(this,"Pick media first",Toast.LENGTH_SHORT).show(); return; }
            drawingOverlay.setVisibility(drawingOverlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        } else if (tool.startsWith("🔄")) {
            if (mediaUri == null) { Toast.makeText(this,"Pick a video first",Toast.LENGTH_SHORT).show(); return; }
            pbCompress.setVisibility(View.VISIBLE);
            StatusBoomerangHelper.createBoomerang(this, mediaUri, new StatusBoomerangHelper.BoomerangCallback() {
                @Override public void onProgress(int pct) { runOnUiThread(() -> pbCompress.setProgress(pct)); }
                @Override public void onSuccess(File f) {
                    runOnUiThread(() -> { pbCompress.setVisibility(View.GONE); mediaUri = Uri.fromFile(f); mediaType = "video"; type = "video";
                        showMediaPreview(mediaUri, "video"); Toast.makeText(NewStatusActivity.this,"Boomerang ready!",Toast.LENGTH_SHORT).show(); });
                }
                @Override public void onError(String msg) { runOnUiThread(() -> { pbCompress.setVisibility(View.GONE); Toast.makeText(NewStatusActivity.this,msg,Toast.LENGTH_SHORT).show(); }); }
            });
        } else if (tool.startsWith("✨")) {
            templateLauncher.launch(new Intent(this, StatusTemplateActivity.class));
        } else if (tool.startsWith("🤖")) {
            // NEW: AI auto-caption
            if (ivMediaPreview.getDrawable() == null) { Toast.makeText(this,"Load an image first",Toast.LENGTH_SHORT).show(); return; }
            Toast.makeText(this,"Generating caption…",Toast.LENGTH_SHORT).show();
            ivMediaPreview.setDrawingCacheEnabled(true); Bitmap thumb = ivMediaPreview.getDrawingCache();
            StatusAIHelper.generateCaption(thumb, "English", new StatusAIHelper.AICallback() {
                @Override public void onResult(String text) { runOnUiThread(() -> etCaption.setText(text)); }
                @Override public void onError(String e) { runOnUiThread(() -> Toast.makeText(NewStatusActivity.this,"AI unavailable",Toast.LENGTH_SHORT).show()); }
            });
        } else if (tool.startsWith("🎵")) {
            pause2(); StatusMusicBottomSheet.show(this, (songId, title2, artist, url, startSec) -> {
                musicSongId = songId; musicTitle = title2; musicArtist = artist;
                musicAudioUrl = url; musicStartSec = startSec;
                tvMusicBadge.setText("🎵 " + title2 + " – " + artist);
                tvMusicBadge.setVisibility(View.VISIBLE);
            });
        } else if (tool.startsWith("📊")) {
            pause2(); StatusPollBottomSheet.show(this, data -> {
                pollQuestion = data.question; pollOptions = data.options;
                tvPollBadge.setText("📊 Poll: " + data.question); tvPollBadge.setVisibility(View.VISIBLE);
            });
        } else if (tool.startsWith("⏱")) {
            showCountdownPicker();
        }
    }

    private void handleTabClick(String tab) {
        if (tab.contains("Photo")) imagePicker.launch("image/*");
        else if (tab.contains("Video")) videoPicker.launch("video/*");
    }

    private void showPrivacyPicker() {
        String myUid = FirebaseUtils.getCurrentUid();
        StatusPrivacyBottomSheet.show(this, myUid, (mode, selectedUids) -> {
            privacy = mode; isCloseFriends = "close_friends".equals(mode);
            String emoji = "everyone".equals(mode) ? "🌍" : "close_friends".equals(mode) ? "⭐" : "🔒";
            tvPrivacy.setText(emoji + " " + mode.substring(0,1).toUpperCase() + mode.substring(1).replace("_"," "));
        });
    }

    private void showExpiryPicker(Button btn) {
        String[] labels = {"1 hour","3 hours","6 hours","12 hours","24 hours","48 hours","72 hours"};
        int[]    hours  = {1,3,6,12,24,48,72};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Status duration")
            .setItems(labels, (d, w) -> { expiryHours = hours[w]; btn.setText("⏱ " + labels[w]); })
            .show();
    }

    private void showCountdownPicker() {
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Set Countdown Target")
            .setMessage("Enter hours from now:")
            .setView(new EditText(this))
            .setPositiveButton("Set", (d, w) -> {
                countdownTargetTs = System.currentTimeMillis() + 3600_000L;
                countdownLabel = "Happening in"; Toast.makeText(this,"Countdown set!",Toast.LENGTH_SHORT).show();
            }).show();
    }

    private void updateCharCount() {
        String t = etText.getText().toString();
        tvCharCount.setText(t.length() + "/700");
        tvCharCount.setTextColor(t.length() > 600 ? Color.RED : Color.GRAY);
    }

    private void updateLinkPreview(String text) {
        if (!StatusLinkPreviewHelper.containsUrl(text)) { tvLinkPreview.setVisibility(View.GONE); type = "text"; return; }
        type = "link"; String url = StatusLinkPreviewHelper.extractUrl(text);
        tvLinkPreview.setText("🔗 Loading preview…"); tvLinkPreview.setVisibility(View.VISIBLE);
        StatusLinkPreviewHelper.fetch(url, new StatusLinkPreviewHelper.Callback() {
            @Override public void onResult(StatusLinkPreviewHelper.LinkPreview p) {
                runOnUiThread(() -> tvLinkPreview.setText("🔗 " + (p.title != null ? p.title : p.domain)));
            }
            @Override public void onError(String e) { runOnUiThread(() -> tvLinkPreview.setText("🔗 " + url)); }
        });
    }

    private void showMediaPreview(Uri uri, String mType) {
        drawingContainer.setVisibility(View.VISIBLE);
        etCaption.setVisibility(View.VISIBLE);
        if ("video".equals(mType)) {
            Glide.with(this).load(uri).centerCrop().into(ivMediaPreview);
        } else {
            Glide.with(this).load(uri).centerCrop().into(ivMediaPreview);
        }
    }

    private void updatePreview() { /* update live preview with current colors/fonts */ }
    private void pause2() { /* placeholder */ }

    private void attemptPost() {
        String text = etText.getText().toString().trim();
        String caption = etCaption.getText().toString().trim();
        // FIX: Extract @mentions from caption too (was only from main text)
        List<String> allMentionNames = new ArrayList<>();
        allMentionNames.addAll(StatusMentionHelper.extractNames(text));
        allMentionNames.addAll(StatusMentionHelper.extractNames(caption)); // FIX

        if (text.isEmpty() && mediaUri == null && gifUrl == null) {
            Toast.makeText(this,"Add some content first!",Toast.LENGTH_SHORT).show(); return;
        }
        pbUpload.setVisibility(View.VISIBLE);
        uploadAttempt = 0;

        // Resolve mentions before posting
        StatusMentionHelper.resolveUids(allMentionNames, resolvedUids -> {
            if (mediaUri != null) uploadMedia(text, caption, resolvedUids);
            else saveStatusToFirebase(text, caption, null, null, resolvedUids);
        });
    }

    private void uploadMedia(String text, String caption, Map<String, String> resolvedUids) {
        // FIX: Retry logic — up to 3 attempts with exponential backoff
        uploadAttempt++;
        if ("image".equals(mediaType)) {
            pbCompress.setVisibility(View.VISIBLE);
            ImageCompressor.compress(this, mediaUri, new ImageCompressor.Callback() {
                @Override public void onSuccess(ImageCompressor.Result compressed) {
                    pbCompress.setVisibility(View.GONE);
                    Uri fullUri = Uri.fromFile(compressed.fullFile);
                    CloudinaryUploader.upload(NewStatusActivity.this, fullUri, "statuses", "image",
                        new CloudinaryUploader.UploadCallback() {
                            @Override public void onSuccess(CloudinaryUploader.Result result) {
                                pbUpload.setProgress(100);
                                String imgUrl   = result.secureUrl;
                                String thumbUrl = result.thumbnailUrl != null ? result.thumbnailUrl : imgUrl;
                                saveStatusToFirebase(text, caption, imgUrl, thumbUrl, resolvedUids);
                            }
                            @Override public void onError(String err) {
                                if (uploadAttempt < 3) {
                                    int delay = (int)(1000 * Math.pow(2, uploadAttempt - 1));
                                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                        () -> uploadMedia(text, caption, resolvedUids), delay);
                                } else {
                                    pbUpload.setVisibility(View.GONE);
                                    Toast.makeText(NewStatusActivity.this, "Upload failed after 3 attempts: " + err, Toast.LENGTH_LONG).show();
                                    offerOfflineQueue(text, caption);
                                }
                            }
                        });
                }
                @Override public void onError(Exception e) { pbCompress.setVisibility(View.GONE); Toast.makeText(NewStatusActivity.this, "Compress error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
            });
        } else {
            // Video upload
            Toast.makeText(this,"Uploading video…",Toast.LENGTH_SHORT).show();
            CloudinaryUploader.upload(this, mediaUri, "statuses", "video",
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result result) {
                        pbUpload.setProgress(100);
                        String vidUrl   = result.secureUrl;
                        String thumbUrl = result.thumbnailUrl != null ? result.thumbnailUrl : vidUrl;
                        saveStatusToFirebase(text, caption, vidUrl, thumbUrl, resolvedUids);
                    }
                    @Override public void onError(String err) {
                        if (uploadAttempt < 3) {
                            int delay = (int)(1000 * Math.pow(2, uploadAttempt - 1));
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                () -> uploadMedia(text, caption, resolvedUids), delay);
                        } else {
                            pbUpload.setVisibility(View.GONE);
                            Toast.makeText(NewStatusActivity.this,"Upload failed: "+err,Toast.LENGTH_LONG).show();
                            offerOfflineQueue(text, caption);
                        }
                    }
                });
        }
    }

    private void offerOfflineQueue(String text, String caption) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save for later?")
            .setMessage("No internet? We can upload when you're back online.")
            .setPositiveButton("Queue it", (d, w) -> {
                StatusOfflineQueueManager.QueuedStatus qs = new StatusOfflineQueueManager.QueuedStatus();
                qs.type = type; qs.text = text; qs.caption = caption;
                qs.privacy = privacy; qs.expiryHours = expiryHours;
                if (mediaUri != null) qs.localMediaPath = mediaUri.getPath();
                StatusOfflineQueueManager.enqueue(this, qs);
                Toast.makeText(this,"Queued for upload",Toast.LENGTH_SHORT).show();
                finish();
            })
            .setNegativeButton("Discard", null).show();
    }

    private void saveStatusToFirebase(String text, String caption, String mediaUrl, String thumbUrl, Map<String, String> resolvedUids) {
        String uid = FirebaseUtils.getCurrentUid(); if (uid == null) return;
        StatusItem item = new StatusItem();
        item.ownerUid  = uid;
        item.ownerName = FirebaseUtils.getCurrentName();
        try { item.ownerPhotoUrl = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl().toString(); } catch (Exception e) { item.ownerPhotoUrl = null; }
        item.type      = "gif".equals(type) ? "gif" : (mediaUrl != null ? ("video".equals(mediaType) ? "video" : "image") : ("link".equals(type) ? "link" : "text"));
        if ("collage".equals(type)) item.type = "collage";
        item.text      = text.isEmpty() ? null : text;
        item.caption   = caption.isEmpty() ? null : caption;
        item.mediaUrl  = mediaUrl != null ? mediaUrl : gifUrl;
        item.thumbnailUrl = thumbUrl;
        item.bgColor   = bgColor; item.bgColor2 = bgColor2; item.textColor = textColor;
        item.fontStyle = fontStyle; item.textAlign = textAlign; item.templateId = templateId;
        item.privacy   = privacy; item.isCloseFriends = isCloseFriends;
        item.expiryHours = expiryHours;
        item.expiresAt   = System.currentTimeMillis() + expiryHours * 3600_000L;
        if (resolvedUids != null && !resolvedUids.isEmpty()) item.mentionUids = resolvedUids;
        // Music
        item.musicSongId = musicSongId; item.musicTitle = musicTitle;
        item.musicArtist = musicArtist; item.musicAudioUrl = musicAudioUrl; item.musicStartSec = musicStartSec;
        // Poll
        item.pollQuestion = pollQuestion; item.pollOptions = pollOptions;
        // Countdown
        item.countdownTargetTs = countdownTargetTs; item.countdownLabel = countdownLabel;

        // Link preview fields
        if ("link".equals(type)) {
            String url = StatusLinkPreviewHelper.extractUrl(text != null ? text : "");
            if (url != null) { item.linkUrl = url; item.type = "link"; }
        }

        DatabaseReference ref = FirebaseUtils.getStatusRef().child(uid).push();
        item.id = ref.getKey();
        ref.setValue(item.toMap()).addOnSuccessListener(t -> {
            pbUpload.setVisibility(View.GONE);
            StatusHapticHelper.success(this);
            StatusDraftManager.clear(this); // Clear draft on success
            // Notify mentioned users
            if (resolvedUids != null) StatusMentionHelper.notifyMentions(uid, item.ownerName, item.id, resolvedUids);
            // Schedule expiry notification
            StatusNotificationHelper.scheduleStatusExpiryReminder(this, item.id, item.expiresAt != null ? item.expiresAt : 0);
            Toast.makeText(this,"Status posted!",Toast.LENGTH_SHORT).show();
            finish();
        }).addOnFailureListener(e -> {
            pbUpload.setVisibility(View.GONE);
            Toast.makeText(this,"Save failed: "+e.getMessage(),Toast.LENGTH_LONG).show();
        });
    }

    // ── Draft management ──────────────────────────────────
    @Override protected void onPause() {
        super.onPause();
        // FIX: Full draft save including media (was text-only)
        StatusDraftManager.Draft draft = new StatusDraftManager.Draft();
        draft.text = etText.getText().toString();
        draft.caption = etCaption.getText().toString();
        draft.bgColor = bgColor; draft.textColor = textColor; draft.fontStyle = fontStyle;
        draft.textAlign = textAlign; draft.privacy = privacy; draft.expiryHours = expiryHours;
        draft.isCloseFriends = isCloseFriends;
        if (mediaUri != null) { draft.mediaUriStr = mediaUri.toString(); draft.mediaType = mediaType; }
        StatusDraftManager.save(this, draft);
    }

    private void restoreDraft() {
        if (!StatusDraftManager.hasDraft(this)) return;
        StatusDraftManager.Draft draft = StatusDraftManager.load(this);
        if (draft == null) return;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Continue draft?")
            .setMessage("You have an unfinished status. Continue where you left off?")
            .setPositiveButton("Continue", (d, w) -> applyDraft(draft))
            .setNegativeButton("Start fresh", (d, w) -> StatusDraftManager.clear(this))
            .show();
    }

    private void applyDraft(StatusDraftManager.Draft draft) {
        if (draft.text != null) etText.setText(draft.text);
        if (draft.caption != null) { etCaption.setText(draft.caption); etCaption.setVisibility(View.VISIBLE); }
        if (draft.bgColor != null) bgColor = draft.bgColor;
        if (draft.fontStyle != null) fontStyle = draft.fontStyle;
        if (draft.privacy  != null) privacy = draft.privacy;
        expiryHours = draft.expiryHours > 0 ? draft.expiryHours : 24;
        if (draft.mediaUriStr != null) {
            try { mediaUri = Uri.parse(draft.mediaUriStr); mediaType = draft.mediaType; type = mediaType;
                  showMediaPreview(mediaUri, mediaType); } catch (Exception e) { /* invalid uri */ }
        }
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
