package com.callx.app.channel;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.lifecycle.ViewModelProvider;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ChannelPostComposerActivity — WhatsApp-level full post composer (v3).
 *
 * Fully supports ALL post types:
 *   • Text post (with 1000-char counter)
 *   • Image post (gallery picker → Firebase Storage → channel post)
 *   • Video post (gallery picker → Firebase Storage → channel post)
 *   • Link post (paste URL → real OG metadata fetch → preview card)
 *   • Poll post (question + 2–10 options, multi-select, expiry)
 *   • Audio post (record in-app voice note OR pick from files)
 *   • Document post (any file: PDF, DOCX, XLS, ZIP, etc.)
 *   • Schedule post (pick date + time → WorkManager auto-publish)
 *   • Draft auto-save on pause / restore on open
 *   • Upload progress indicator (percentage)
 */
public class ChannelPostComposerActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";
    public static final String EXTRA_EDIT_POST_ID  = "editPostId";
    public static final String EXTRA_EDIT_POST_TEXT= "editPostText";

    private static final int MAX_CHARS       = 1000;
    private static final int MAX_POLLS       = 10;
    private static final int MIN_POLLS       = 2;
    private static final int REQUEST_AUDIO_PERM = 201;

    private ChannelViewModel viewModel;
    private String channelId, channelName;
    private boolean isEditMode = false;
    private String editPostId;

    // ── Views ──────────────────────────────────────────────────────────────
    private TextInputEditText etText;
    private TextView          tvCharCount;
    private MaterialButton    btnPost;
    private ProgressBar       progressBar;
    private TextView          tvUploadPercent;
    private ChipGroup         chipGroupTypes;

    // Media preview
    private View      layoutMediaPreview;
    private ImageView ivMediaPreview;
    private TextView  tvMediaName;
    private ImageButton btnRemoveMedia;

    // Link preview
    private View      layoutLinkPreview;
    private TextView  tvLinkTitle, tvLinkUrl, tvLinkDomain;
    private ImageView ivLinkThumb;
    private ImageButton btnRemoveLink;
    private ProgressBar progressLinkFetch;

    // Poll section
    private LinearLayout layoutPollOptions;
    private View         layoutPollSection;
    private MaterialButton btnAddPollOption;
    private Switch       switchMultiSelect;
    private TextView     tvPollExpiry;
    private long         pollExpiresAt = 0;

    // Audio section
    private View         layoutAudioSection;
    private TextView     tvAudioDuration;
    private ImageButton  btnRecordAudio, btnStopAudio, btnPlayAudio;
    private boolean      isRecording = false;
    private MediaRecorder mediaRecorder;
    private File          recordedAudioFile;
    private Uri           selectedAudioUri;
    private long          audioDurationMs = 0;
    private Handler       audioTimerHandler = new Handler(Looper.getMainLooper());
    private long          audioRecordStart = 0;

    // Document section
    private View      layoutDocSection;
    private TextView  tvDocName, tvDocSize;
    private ImageView ivDocIcon;
    private ImageButton btnRemoveDoc;
    private Uri       selectedDocUri;
    private String    selectedDocName;
    private long      selectedDocSize;
    private String    selectedDocMime;

    // Schedule
    private MaterialButton btnSchedule;
    private TextView       tvScheduledTime;
    private long           scheduledAtMs = 0;

    // Composer state
    private String currentMode = "text";
    private Uri    selectedMediaUri;
    private String selectedMediaType;
    private String linkUrl, linkTitle, linkDescription, linkImageUrl;
    private final List<TextInputEditText> pollOptionViews = new ArrayList<>();

    // ── Launchers ─────────────────────────────────────────────────────────
    private final ActivityResultLauncher<String> pickImage =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) copyToCacheThen(uri, ".jpg", local -> attachMedia(local, "image"));
        });

    private final ActivityResultLauncher<String> pickVideo =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) copyToCacheThen(uri, ".mp4", local -> attachMedia(local, "video"));
        });

    private final ActivityResultLauncher<String> pickAudio =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) copyToCacheThen(uri, ".m4a", this::attachAudioFile);
        });

    private final ActivityResultLauncher<String[]> pickDocument =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) attachDocument(uri);
        });

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_post_composer);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        editPostId  = getIntent().getStringExtra(EXTRA_EDIT_POST_ID);
        String editText = getIntent().getStringExtra(EXTRA_EDIT_POST_TEXT);
        isEditMode  = editPostId != null;

        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_post_composer);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(isEditMode ? "Edit post" : "New post");
        }
        toolbar.setNavigationOnClickListener(v -> confirmDiscard());

        bindViews();
        setupChipGroup();
        setupTextWatcher();
        observeViewModel();

        if (isEditMode && editText != null) {
            etText.setText(editText);
            if (chipGroupTypes != null) chipGroupTypes.setVisibility(View.GONE);
            if (btnSchedule    != null) btnSchedule.setVisibility(View.GONE);
        } else {
            // Restore draft if any
            loadDraft();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) saveDraft();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        audioTimerHandler.removeCallbacksAndMessages(null);
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); } catch (Exception ignored) {}
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private void bindViews() {
        etText           = findViewById(R.id.et_post_text);
        tvCharCount      = findViewById(R.id.tv_char_count);
        btnPost          = findViewById(R.id.btn_post_submit);
        progressBar      = findViewById(R.id.progress_post_upload);
        tvUploadPercent  = findViewById(R.id.tv_upload_percent);
        chipGroupTypes   = findViewById(R.id.chip_group_post_types);

        layoutMediaPreview = findViewById(R.id.layout_media_preview);
        ivMediaPreview     = findViewById(R.id.iv_media_preview);
        tvMediaName        = findViewById(R.id.tv_media_name);
        btnRemoveMedia     = findViewById(R.id.btn_remove_media);

        layoutLinkPreview  = findViewById(R.id.layout_link_preview);
        tvLinkTitle        = findViewById(R.id.tv_link_title);
        tvLinkUrl          = findViewById(R.id.tv_link_url);
        tvLinkDomain       = findViewById(R.id.tv_link_domain);
        ivLinkThumb        = findViewById(R.id.iv_link_thumb);
        btnRemoveLink      = findViewById(R.id.btn_remove_link);
        progressLinkFetch  = findViewById(R.id.progress_link_fetch);

        layoutPollSection  = findViewById(R.id.layout_poll_section);
        layoutPollOptions  = findViewById(R.id.layout_poll_options);
        btnAddPollOption   = findViewById(R.id.btn_add_poll_option);
        switchMultiSelect  = findViewById(R.id.switch_poll_multiselect);
        tvPollExpiry       = findViewById(R.id.tv_poll_expiry);

        layoutAudioSection = findViewById(R.id.layout_audio_section);
        tvAudioDuration    = findViewById(R.id.tv_audio_duration);
        btnRecordAudio     = findViewById(R.id.btn_record_audio);
        btnStopAudio       = findViewById(R.id.btn_stop_audio);
        btnPlayAudio       = findViewById(R.id.btn_play_audio);

        layoutDocSection   = findViewById(R.id.layout_doc_section);
        tvDocName          = findViewById(R.id.tv_doc_name);
        tvDocSize          = findViewById(R.id.tv_doc_size);
        ivDocIcon          = findViewById(R.id.iv_doc_icon);
        btnRemoveDoc       = findViewById(R.id.btn_remove_doc);

        btnSchedule        = findViewById(R.id.btn_schedule_post);
        tvScheduledTime    = findViewById(R.id.tv_scheduled_time);

        if (btnPost != null)          btnPost.setOnClickListener(v -> onPostClicked());
        if (btnRemoveMedia != null)   btnRemoveMedia.setOnClickListener(v -> clearMedia());
        if (btnRemoveLink  != null)   btnRemoveLink.setOnClickListener(v -> clearLink());
        if (btnRemoveDoc   != null)   btnRemoveDoc.setOnClickListener(v -> clearDoc());
        if (btnAddPollOption != null) btnAddPollOption.setOnClickListener(v -> addPollOption());
        if (btnRecordAudio != null)   btnRecordAudio.setOnClickListener(v -> onRecordAudioClicked());
        if (btnStopAudio   != null)   btnStopAudio.setOnClickListener(v -> stopRecording());
        if (btnSchedule    != null)   btnSchedule.setOnClickListener(v -> showSchedulePicker());
        if (tvPollExpiry   != null)   tvPollExpiry.setOnClickListener(v -> showPollExpiryPicker());
    }

    private void setupChipGroup() {
        if (chipGroupTypes == null) return;
        String[] types  = { "Text", "Image", "Video", "Link", "Poll", "Audio", "Document" };
        String[] values = { "text","image","video","link","poll","audio","document" };
        for (int i = 0; i < types.length; i++) {
            final String val = values[i];
            Chip chip = new Chip(this);
            chip.setText(types[i]);
            chip.setCheckable(true);
            chip.setChecked(i == 0);
            chip.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) switchMode(val);
            });
            chipGroupTypes.addView(chip);
        }
    }

    private void setupTextWatcher() {
        if (etText == null) return;
        etText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                int len = s.length();
                if (tvCharCount != null) tvCharCount.setText(len + "/" + MAX_CHARS);
                updatePostEnabled();
            }
        });
    }

    private void observeViewModel() {
        viewModel.uploadProgress.observe(this, uploading -> {
            if (progressBar != null) progressBar.setVisibility(uploading ? View.VISIBLE : View.GONE);
            if (btnPost     != null) btnPost.setEnabled(!uploading);
        });

        viewModel.uploadPercent.observe(this, pct -> {
            if (tvUploadPercent != null)
                tvUploadPercent.setText(pct > 0 && pct < 100 ? pct + "%" : "");
        });

        viewModel.postSuccess.observe(this, ok -> {
            if (ok != null && ok) {
                clearDraft();
                if (scheduledAtMs > 0) {
                    // Ensure the WorkManager periodic job is running
                    ChannelScheduledPostWorker.schedulePeriodicWork(this);
                }
                Toast.makeText(this, scheduledAtMs > 0 ? "Post scheduled!" : "Posted!",
                        Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    // ── Draft auto-save ────────────────────────────────────────────────────

    private SharedPreferences draftPrefs() {
        return getSharedPreferences("channel_draft_" + channelId, MODE_PRIVATE);
    }

    private void saveDraft() {
        if (isEditMode) return;
        String text = etText != null && etText.getText() != null
                ? etText.getText().toString() : "";
        if (text.trim().isEmpty()) return; // Nothing to save

        draftPrefs().edit()
                .putString("draft_text", text)
                .putString("draft_mode", currentMode)
                .putLong("draft_saved_at", System.currentTimeMillis())
                .apply();
    }

    private void loadDraft() {
        if (isEditMode) return;
        SharedPreferences prefs = draftPrefs();
        String text = prefs.getString("draft_text", "");
        if (text == null || text.trim().isEmpty()) return;

        if (etText != null) etText.setText(text);

        // Show snackbar with dismiss action
        View root = findViewById(android.R.id.content);
        if (root != null) {
            Snackbar.make(root, "Draft restored", Snackbar.LENGTH_LONG)
                    .setAction("Dismiss", v -> clearDraft())
                    .show();
        }
    }

    private void clearDraft() {
        draftPrefs().edit().clear().apply();
    }

    // ── Mode switching ─────────────────────────────────────────────────────

    private void switchMode(String mode) {
        currentMode = mode;
        if (layoutMediaPreview != null) layoutMediaPreview.setVisibility(View.GONE);
        if (layoutLinkPreview  != null) layoutLinkPreview.setVisibility(View.GONE);
        if (layoutPollSection  != null) layoutPollSection.setVisibility(View.GONE);
        if (layoutAudioSection != null) layoutAudioSection.setVisibility(View.GONE);
        if (layoutDocSection   != null) layoutDocSection.setVisibility(View.GONE);

        clearMedia(); clearLink(); clearDoc();

        switch (mode) {
            case "image":
                pickImage.launch("image/*");
                break;
            case "video":
                pickVideo.launch("video/*");
                break;
            case "link":
                showLinkInputDialog();
                break;
            case "poll":
                if (layoutPollSection != null) layoutPollSection.setVisibility(View.VISIBLE);
                if (pollOptionViews.isEmpty()) { addPollOption(); addPollOption(); }
                break;
            case "audio":
                if (layoutAudioSection != null) layoutAudioSection.setVisibility(View.VISIBLE);
                showAudioPickerDialog();
                break;
            case "document":
                pickDocument.launch(new String[]{"*/*"});
                break;
        }
        updatePostEnabled();
    }

    // ── Attachment helpers ─────────────────────────────────────────────────

    /**
     * Immediately copies a picked content:// URI into this app's private cache
     * directory on a background thread, then hands the resulting local file
     * URI to {@code callback} on the main thread.
     *
     * Why: the raw URI returned by the system picker only carries a
     * short-lived read grant (and on some OEM ROMs — MIUI/ColorOS — the
     * backing temp file can be cleared under memory pressure well before the
     * user finishes composing and hits Post). Uploading straight from that
     * URI later intermittently failed with Firebase Storage's
     * "Object does not exist at location", because by upload time the source
     * was already gone. Snapshotting it into our own cache the moment it's
     * picked removes that dependency entirely.
     */
    private void copyToCacheThen(Uri source, String fallbackExt, Consumer<Uri> callback) {
        new Thread(() -> {
            File out = null;
            try {
                String mime = getContentResolver().getType(source);
                String ext = fallbackExt;
                if (mime != null) {
                    String fromMime = android.webkit.MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(mime);
                    if (fromMime != null) ext = "." + fromMime;
                }
                out = new File(getCacheDir(), "post_" + System.currentTimeMillis() + ext);
                try (InputStream in = getContentResolver().openInputStream(source);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    if (in == null) throw new java.io.IOException("Unable to open picked file");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                }
            } catch (Exception e) {
                out = null;
            }
            final Uri localUri = out != null ? Uri.fromFile(out) : null;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (localUri != null) {
                    callback.accept(localUri);
                } else {
                    Toast.makeText(this, "Couldn't read the selected file. Please try again.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void attachMedia(Uri uri, String type) {
        selectedMediaUri  = uri;
        selectedMediaType = type;
        if (layoutMediaPreview != null) layoutMediaPreview.setVisibility(View.VISIBLE);
        if (ivMediaPreview != null) {
            if ("image".equals(type)) Glide.with(this).load(uri).into(ivMediaPreview);
            else ivMediaPreview.setImageResource(R.drawable.ic_channel_broadcast);
        }
        if (tvMediaName != null) tvMediaName.setText("image".equals(type) ? "Image selected" : "Video selected");
        updatePostEnabled();
    }

    private void clearMedia() {
        selectedMediaUri  = null;
        selectedMediaType = null;
        if (layoutMediaPreview != null) layoutMediaPreview.setVisibility(View.GONE);
    }

    private void attachAudioFile(Uri uri) {
        selectedAudioUri = uri;
        audioDurationMs  = 0;
        if (layoutAudioSection != null) layoutAudioSection.setVisibility(View.VISIBLE);
        if (tvAudioDuration != null) tvAudioDuration.setText("Audio file selected");
        updatePostEnabled();
    }

    private void clearDoc() {
        selectedDocUri  = null;
        selectedDocName = null;
        selectedDocSize = 0;
        selectedDocMime = null;
        if (layoutDocSection != null) layoutDocSection.setVisibility(View.GONE);
    }

    private void attachDocument(Uri uri) {
        selectedDocUri = uri;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                selectedDocName = nameIdx >= 0 ? cursor.getString(nameIdx) : "document";
                selectedDocSize = sizeIdx >= 0 ? cursor.getLong(sizeIdx) : 0;
            }
        } catch (Exception ignored) { selectedDocName = "document"; }
        selectedDocMime = getContentResolver().getType(uri);
        if (selectedDocMime == null) selectedDocMime = "application/octet-stream";

        if (layoutDocSection != null) layoutDocSection.setVisibility(View.VISIBLE);
        if (tvDocName != null) tvDocName.setText(selectedDocName);
        if (tvDocSize != null) tvDocSize.setText(formatFileSize(selectedDocSize));
        updatePostEnabled();
    }

    // ── Link input + OG fetch ──────────────────────────────────────────────

    private void showLinkInputDialog() {
        EditText et = new EditText(this);
        et.setHint("Paste URL (https://...)");
        et.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        et.setPadding(40, 20, 40, 20);
        new AlertDialog.Builder(this)
            .setTitle("Add link")
            .setView(et)
            .setPositiveButton("Add", (d, w) -> {
                String url = et.getText().toString().trim();
                if (!url.isEmpty()) {
                    if (!url.startsWith("http")) url = "https://" + url;
                    fetchOgMetadata(url);
                }
            })
            .setNegativeButton("Cancel", (d, w) -> switchMode("text"))
            .show();
    }

    /**
     * Fetch Open Graph metadata from a URL in a background thread.
     * Parses og:title, og:description, og:image from the HTML response.
     */
    private void fetchOgMetadata(final String url) {
        if (progressLinkFetch != null) progressLinkFetch.setVisibility(View.VISIBLE);

        new Thread(() -> {
            String ogTitle = "";
            String ogDesc  = "";
            String ogImage = "";

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Android) AppleWebKit/537.36");
                conn.connect();

                if (conn.getResponseCode() == 200) {
                    InputStream is  = conn.getInputStream();
                    byte[] buf      = new byte[32768]; // read up to 32KB (enough for <head>)
                    int    read     = is.read(buf);
                    String html     = read > 0 ? new String(buf, 0, read, "UTF-8") : "";
                    is.close();

                    ogTitle = extractOgTag(html, "og:title");
                    ogDesc  = extractOgTag(html, "og:description");
                    ogImage = extractOgTag(html, "og:image");

                    // Fallback: <title> tag
                    if (ogTitle.isEmpty()) {
                        Matcher m = Pattern.compile("<title[^>]*>([^<]+)</title>",
                                Pattern.CASE_INSENSITIVE).matcher(html);
                        if (m.find()) ogTitle = m.group(1).trim();
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}

            final String finalTitle = ogTitle;
            final String finalDesc  = ogDesc;
            final String finalImage = ogImage;

            new Handler(Looper.getMainLooper()).post(() -> {
                if (progressLinkFetch != null) progressLinkFetch.setVisibility(View.GONE);
                showLinkPreview(url,
                        finalTitle.isEmpty() ? url : finalTitle,
                        finalDesc,
                        finalImage);
            });
        }).start();
    }

    /** Extract content attribute from a meta og: tag. */
    private String extractOgTag(String html, String property) {
        try {
            // <meta property="og:title" content="...">  or  content="..." property="og:title"
            Pattern p = Pattern.compile(
                "<meta[^>]+property=[\"']" + Pattern.quote(property) + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            if (m.find()) return m.group(1).trim();
            // Try reverse order
            Pattern p2 = Pattern.compile(
                "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']" + Pattern.quote(property) + "[\"']",
                Pattern.CASE_INSENSITIVE);
            Matcher m2 = p2.matcher(html);
            if (m2.find()) return m2.group(1).trim();
        } catch (Exception ignored) {}
        return "";
    }

    private void showLinkPreview(String url, String title, String desc, String imageUrl) {
        linkTitle       = title;
        linkDescription = desc;
        linkImageUrl    = imageUrl;
        linkUrl         = url;
        if (layoutLinkPreview != null) layoutLinkPreview.setVisibility(View.VISIBLE);
        if (tvLinkUrl    != null) tvLinkUrl.setText(url);
        if (tvLinkTitle  != null) tvLinkTitle.setText(title.isEmpty() ? url : title);
        if (tvLinkDomain != null) {
            try {
                tvLinkDomain.setText(new java.net.URL(url).getHost().replaceAll("^www\\.", ""));
            } catch (Exception e) { tvLinkDomain.setText(url); }
        }
        if (!imageUrl.isEmpty() && ivLinkThumb != null)
            Glide.with(this).load(imageUrl).into(ivLinkThumb);
        updatePostEnabled();
    }

    private void clearLink() {
        linkUrl = linkTitle = linkDescription = linkImageUrl = null;
        if (layoutLinkPreview != null) layoutLinkPreview.setVisibility(View.GONE);
    }

    // ── Poll helpers ───────────────────────────────────────────────────────

    private void addPollOption() {
        if (pollOptionViews.size() >= MAX_POLLS) {
            Toast.makeText(this, "Maximum " + MAX_POLLS + " options", Toast.LENGTH_SHORT).show();
            return;
        }
        if (layoutPollOptions == null) return;
        TextInputEditText et = new TextInputEditText(this);
        int n = pollOptionViews.size() + 1;
        et.setHint("Option " + n);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 0);
        et.setLayoutParams(lp);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { updatePostEnabled(); }
        });
        layoutPollOptions.addView(et);
        pollOptionViews.add(et);
    }

    private void showPollExpiryPicker() {
        String[] options = { "No expiry", "1 hour", "6 hours", "1 day", "3 days", "7 days" };
        long[]   offsets = { 0, 3600000L, 21600000L, 86400000L, 259200000L, 604800000L };
        new AlertDialog.Builder(this)
            .setTitle("Poll expires in")
            .setItems(options, (d, which) -> {
                pollExpiresAt = offsets[which] > 0
                        ? System.currentTimeMillis() + offsets[which] : 0;
                if (tvPollExpiry != null)
                    tvPollExpiry.setText(which == 0 ? "No expiry" : "Expires: " + options[which]);
            }).show();
    }

    // ── Audio ──────────────────────────────────────────────────────────────

    private void showAudioPickerDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Audio post")
            .setItems(new String[]{ "Record voice note", "Choose from files" }, (d, which) -> {
                if (which == 0) startAudioRecording();
                else pickAudio.launch("audio/*");
            }).show();
    }

    private void onRecordAudioClicked() {
        if (isRecording) {
            stopRecording();
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.RECORD_AUDIO }, REQUEST_AUDIO_PERM);
            } else {
                startAudioRecording();
            }
        }
    }

    private void startAudioRecording() {
        try {
            recordedAudioFile = new File(getCacheDir(), "voice_" + System.currentTimeMillis() + ".aac");
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setOutputFile(recordedAudioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording      = true;
            audioRecordStart = System.currentTimeMillis();
            if (btnRecordAudio != null) btnRecordAudio.setImageResource(android.R.drawable.ic_media_pause);
            if (btnStopAudio   != null) btnStopAudio.setVisibility(View.VISIBLE);
            startAudioTimer();
        } catch (Exception e) {
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        if (!isRecording || mediaRecorder == null) return;
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder    = null;
            isRecording      = false;
            audioDurationMs  = System.currentTimeMillis() - audioRecordStart;
            selectedAudioUri = Uri.fromFile(recordedAudioFile);
            audioTimerHandler.removeCallbacksAndMessages(null);
            if (btnRecordAudio  != null) btnRecordAudio.setImageResource(android.R.drawable.ic_btn_speak_now);
            if (btnStopAudio    != null) btnStopAudio.setVisibility(View.GONE);
            if (tvAudioDuration != null) tvAudioDuration.setText("Recorded: " + formatDuration(audioDurationMs));
            updatePostEnabled();
        } catch (Exception e) {
            Toast.makeText(this, "Stop failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startAudioTimer() {
        audioTimerHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isRecording) return;
                long elapsed = System.currentTimeMillis() - audioRecordStart;
                if (tvAudioDuration != null) tvAudioDuration.setText("● " + formatDuration(elapsed));
                audioTimerHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO_PERM && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAudioRecording();
        } else {
            Toast.makeText(this, "Microphone permission is required to record voice notes.",
                Toast.LENGTH_SHORT).show();
        }
    }

    // ── Schedule ───────────────────────────────────────────────────────────

    private void showSchedulePicker() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, day) -> {
            new TimePickerDialog(this, (tView, hour, minute) -> {
                cal.set(year, month, day, hour, minute, 0);
                scheduledAtMs = cal.getTimeInMillis();
                if (scheduledAtMs <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Schedule time must be in the future.", Toast.LENGTH_SHORT).show();
                    scheduledAtMs = 0;
                    if (tvScheduledTime != null) tvScheduledTime.setText("Not scheduled");
                } else {
                    String formatted = android.text.format.DateFormat
                        .getDateFormat(this).format(cal.getTime()) + " "
                        + android.text.format.DateFormat.getTimeFormat(this).format(cal.getTime());
                    if (tvScheduledTime != null) tvScheduledTime.setText("Scheduled: " + formatted);
                    if (btnPost != null) btnPost.setText("Schedule");
                }
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ── Submit ─────────────────────────────────────────────────────────────

    private void onPostClicked() {
        if (isEditMode) {
            String text = etText.getText() != null ? etText.getText().toString().trim() : "";
            if (text.isEmpty()) { Toast.makeText(this, "Post cannot be empty.", Toast.LENGTH_SHORT).show(); return; }
            viewModel.editPost(channelId, editPostId, text);
            return;
        }

        String text = etText.getText() != null ? etText.getText().toString().trim() : "";

        // scheduledAtMs > 0 means the user picked a future time — route all post types through
        // the scheduling path so the post is saved to channelScheduled/ instead of published now.
        switch (currentMode) {
            case "text":
                if (text.isEmpty()) { Toast.makeText(this, "Write something first.", Toast.LENGTH_SHORT).show(); return; }
                viewModel.createTextPost(channelId, text, scheduledAtMs);
                break;

            case "image":
            case "video":
                if (selectedMediaUri == null) { Toast.makeText(this, "Select a media file first.", Toast.LENGTH_SHORT).show(); return; }
                viewModel.createMediaPost(channelId, selectedMediaUri, selectedMediaType, text, this, scheduledAtMs);
                break;

            case "link":
                if (linkUrl == null || linkUrl.isEmpty()) { Toast.makeText(this, "Add a link first.", Toast.LENGTH_SHORT).show(); return; }
                viewModel.createLinkPost(channelId, text, linkUrl,
                        linkTitle != null ? linkTitle : "",
                        linkDescription != null ? linkDescription : "",
                        linkImageUrl != null ? linkImageUrl : "",
                        scheduledAtMs);
                break;

            case "poll":
                TextInputEditText etQ = layoutPollSection != null
                        ? (TextInputEditText) layoutPollSection.findViewById(R.id.et_poll_question) : null;
                String question = etQ != null && etQ.getText() != null ? etQ.getText().toString().trim() : "";
                if (question.isEmpty()) { Toast.makeText(this, "Enter a poll question.", Toast.LENGTH_SHORT).show(); return; }
                List<String> options = new ArrayList<>();
                for (TextInputEditText et : pollOptionViews) {
                    String opt = et.getText() != null ? et.getText().toString().trim() : "";
                    if (!opt.isEmpty()) options.add(opt);
                }
                if (options.size() < MIN_POLLS) { Toast.makeText(this, "Add at least 2 options.", Toast.LENGTH_SHORT).show(); return; }
                boolean multiSelect = switchMultiSelect != null && switchMultiSelect.isChecked();
                viewModel.createPollPost(channelId, text, question, options, multiSelect, pollExpiresAt, scheduledAtMs);
                break;

            case "audio":
                if (selectedAudioUri == null) { Toast.makeText(this, "Record or select an audio file first.", Toast.LENGTH_SHORT).show(); return; }
                viewModel.createAudioPost(channelId, selectedAudioUri, audioDurationMs, text, this, scheduledAtMs);
                break;

            case "document":
                if (selectedDocUri == null) { Toast.makeText(this, "Select a document first.", Toast.LENGTH_SHORT).show(); return; }
                viewModel.createDocumentPost(channelId, selectedDocUri, selectedDocName,
                        selectedDocSize, selectedDocMime, text, this, scheduledAtMs);
                break;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void updatePostEnabled() {
        if (btnPost == null) return;
        switch (currentMode) {
            case "image":
            case "video":    btnPost.setEnabled(selectedMediaUri != null); break;
            case "link":     btnPost.setEnabled(linkUrl != null && !linkUrl.isEmpty()); break;
            case "audio":    btnPost.setEnabled(selectedAudioUri != null); break;
            case "document": btnPost.setEnabled(selectedDocUri != null); break;
            case "poll":
                int filledOptions = 0;
                for (TextInputEditText et : pollOptionViews) {
                    if (et.getText() != null && !et.getText().toString().trim().isEmpty()) filledOptions++;
                }
                btnPost.setEnabled(filledOptions >= MIN_POLLS);
                break;
            default:
                String text = etText.getText() != null ? etText.getText().toString() : "";
                btnPost.setEnabled(!text.trim().isEmpty() && text.length() <= MAX_CHARS);
        }
    }

    private void confirmDiscard() {
        String text = etText.getText() != null ? etText.getText().toString() : "";
        boolean hasContent = !text.isEmpty() || selectedMediaUri != null
            || selectedAudioUri != null || selectedDocUri != null || linkUrl != null;
        if (hasContent) {
            new AlertDialog.Builder(this)
                .setTitle("Discard post?")
                .setMessage("Your changes will be lost.")
                .setPositiveButton("Discard", (d, w) -> { clearDraft(); finish(); })
                .setNegativeButton("Keep editing", null)
                .show();
        } else {
            clearDraft();
            finish();
        }
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "0:00";
        long secs = ms / 1000;
        return String.format("%d:%02d", secs / 60, secs % 60);
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024)       return bytes + " B";
        if (bytes < 1024*1024)  return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0*1024));
    }
}
