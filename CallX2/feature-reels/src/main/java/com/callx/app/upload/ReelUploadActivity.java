package com.callx.app.upload;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.editor.ReelAudioMixerActivity;
import com.callx.app.music.SoundDetailActivity;

import android.util.Log;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;
import com.callx.app.upload.ReelTagPeopleActivity;
import com.callx.app.upload.ReelLocationTagActivity;
import com.callx.app.settings.ReelPrivacySettingsActivity;
import com.callx.app.upload.ReelSchedulerActivity;
import com.callx.app.library.ReelDraftsActivity;
import com.callx.app.upload.ReelProductTagActivity;

import com.callx.app.music.AudioMixHelper;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.VideoCompressor;

import com.google.firebase.database.ServerValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import com.callx.app.utils.ReelCloudinaryUtils;
import com.callx.app.utils.VideoQualityPreferences;
import com.callx.app.utils.VideoUploader;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.lang.ref.WeakReference;
import java.util.UUID;

/**
 * ReelUploadActivity — Fully crash-safe production reel upload.
 *
 * Crash fixes:
 *  ✅ WeakReference in all background callbacks — no NPE on dead activity
 *  ✅ isFinishing()/isDestroyed() guard before every UI update
 *  ✅ READ_MEDIA_VIDEO permission request on Android 13+ (API 33)
 *  ✅ Empty thumbnail fallback — upload proceeds even if thumb extraction failed
 *  ✅ CompressedResult null-guard on every path
 *  ✅ Player released before new video selected
 *  ✅ Firebase user null-guard — never crashes if user is not logged in
 *
 * Advanced features:
 *  ✅ 4-tier quality selector (Low / Standard / HD / Full HD)
 *  ✅ Live compression progress + savings badge
 *  ✅ Audience selector (Everyone / Contacts Only)
 *  ✅ Hashtag auto-extraction from caption
 *  ✅ Video preview with ExoPlayer before upload
 */
public class ReelUploadActivity extends AppCompatActivity {

    // ── Extras accepted from ReelEditorActivity ───────────────────────────
    public static final String EXTRA_VIDEO_URI    = "upload_video_uri";
    public static final String EXTRA_IS_FILE_PATH = "upload_is_file_path";
    public static final String EXTRA_TRIM_START   = "upload_trim_start";
    public static final String EXTRA_TRIM_END     = "upload_trim_end";
    public static final String EXTRA_TEXT_OVERLAY = "upload_text_overlay";
    public static final String EXTRA_MUSIC_NAME   = "upload_music_name";
    // Sound pre-selected from SoundDetailActivity
    public static final String EXTRA_SOUND_ID    = "selected_sound_id";
    public static final String EXTRA_SOUND_TITLE = "selected_sound_title";
    public static final String EXTRA_SOUND_URL   = "selected_sound_url";
    // Fix 4 & 6 & 8: duet metadata
    public static final String EXTRA_IS_DUET          = "upload_is_duet";
    public static final String EXTRA_DUET_ORIGINAL_ID = "upload_duet_original_id";
    public static final String EXTRA_DUET_OWNER_UID   = "upload_duet_owner_uid";
    public static final String EXTRA_DUET_LABEL       = "upload_duet_label";
    public static final String EXTRA_DUET_ORIGINAL_URL= "upload_duet_original_url";

      // Duet Series fields — passed from ReelPostDetailsActivity
      public static final String EXTRA_SERIES_ID      = "upload_series_id";
      public static final String EXTRA_SERIES_TITLE   = "upload_series_title";
      public static final String EXTRA_EPISODE_NUMBER = "upload_episode_number";
  

    private static final int REQ_PICK_VIDEO   = 901;
    private static final int REQ_PERMISSION   = 902;
    private static final int REQ_PICK_PHOTOS  = 903;
    private static final int REQ_PERM_PHOTOS  = 904;
    private static final int MAX_PHOTOS       = 10;

    private PlayerView        playerPreview;
    private ImageView         ivThumbPreview;
    private View              layoutPickVideo, layoutCompression, layoutVideoInfo;
    private View              layoutUploadProgress;
    private ProgressBar       progressCompress, progressUpload;
    private TextView          tvCompressStatus, tvUploadStatus;
    private TextView          tvVideoInfo, tvCompressionSavings;
    private TextInputEditText etCaption, etMusic;
    private Button            btnPostReel;
    private ChipGroup         chipQuality, chipAudience, chipDuetLevel, chipStitchLevel;
    private View              btnTagPeople, btnLocationTag, btnPrivacySettings, btnSchedule, btnSaveDraft, btnProductTag;
    private android.widget.TextView tvSeriesPickerUpload;
    private TextView          tvTagSummary, tvLocationName, tvScheduleTime;
    private String            taggedUids = "", locationName = "", scheduleTime = "";

    // ── Photo Slideshow fields ────────────────────────────────────────────────
    private boolean               isPhotoMode              = false;
    private final ArrayList<Uri>  selectedPhotoUris        = new ArrayList<>();
    private View                  cardPhotos;
    private Button                btnMediaTypeVideo;
    private Button                btnMediaTypePhotos;
    private Button                btnPickPhotos;
    private LinearLayout          llPhotoPreviewContainer;
    private TextView              tvPhotoCount;

    private Uri                    selectedUri;
    private String                 preSelectedSoundId    = "";
    private String                 preSelectedSoundUrl   = "";
    private ExoPlayer              previewPlayer;
    private VideoCompressor.Result compressedResult;
    private boolean                compressionInProgress = false;

    // Audio mix settings received from ReelEditorActivity (via ReelAudioMixerActivity)
    private float  mixOrigVol       = 1.0f;
    private float  mixMusicVol      = 0.8f;
    private String mixVoiceoverPath = "";
    private float  mixVoiceoverVol  = 1.0f;
    private String mixedVideoPath   = null; // set after AudioMixHelper finishes

    // ✅ NEW: True when ReelCameraActivity already replaced mic audio at recording time.
    // If true, skip AudioMixHelper in handlePostReel() to avoid double-mixing.
    private boolean audioAlreadyReplaced = false;

    // Fix 4 & 6 & 8: duet metadata
    private boolean isDuet          = false;
    private String  duetOriginalId  = "";
    private String  duetOwnerUid    = "";
    private String  duetLabel       = "";
    private String  duetOriginalUrl = "";
    private int     duetLayoutMode  = 0;  // ✅ FIX GAP #6: save layout mode to Firebase
    private String  duetRootId     = null; // ✅ FIX v9 (CHAIN DUET): root reel of the chain

    // ✅ FIX GAP #2: stitch metadata
    private boolean isStitch           = false;
    private String  stitchOriginalId   = "";
    private String  stitchOriginalUrl  = "";
    private String  stitchOwnerUid     = "";

      // ── Duet Series ───────────────────────────────────────────────────────────
      private String seriesId      = null;
      private String seriesTitle   = null;
      private int    episodeNumber = 0;

  
    private int     stitchDurationSec  = 3;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_upload);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        bindViews();
        setupChipDefaults();

        layoutPickVideo.setOnClickListener(v -> checkPermissionAndPickVideo());
        playerPreview.setOnClickListener(v -> checkPermissionAndPickVideo());
        ivThumbPreview.setOnClickListener(v -> checkPermissionAndPickVideo());
        btnPostReel.setOnClickListener(v -> handlePostReel());
        if (btnTagPeople       != null) btnTagPeople.setOnClickListener(v       -> startActivityForResult(new Intent(this, ReelTagPeopleActivity.class), 501));
        if (btnLocationTag     != null) btnLocationTag.setOnClickListener(v     -> startActivityForResult(new Intent(this, ReelLocationTagActivity.class), 502));
        if (btnPrivacySettings != null) btnPrivacySettings.setOnClickListener(v -> startActivityForResult(new Intent(this, ReelPrivacySettingsActivity.class), 503));
        if (btnSchedule        != null) btnSchedule.setOnClickListener(v        -> startActivityForResult(new Intent(this, ReelSchedulerActivity.class), 504));
        if (btnSaveDraft       != null) btnSaveDraft.setOnClickListener(v -> startActivity(new Intent(this, ReelDraftsActivity.class)));
        if (btnProductTag      != null) btnProductTag.setOnClickListener(v      -> startActivityForResult(new Intent(this, ReelProductTagActivity.class), 505));
        if (tvSeriesPickerUpload != null) tvSeriesPickerUpload.setOnClickListener(v -> openSeriesPickerFromUpload());

        // Media type toggle
        if (btnMediaTypeVideo  != null) btnMediaTypeVideo.setOnClickListener(v  -> switchToVideoMode());
        if (btnMediaTypePhotos != null) btnMediaTypePhotos.setOnClickListener(v -> switchToPhotoMode());
        if (btnPickPhotos      != null) btnPickPhotos.setOnClickListener(v      -> checkPermissionAndPickPhotos());

        // If launched from ReelEditorActivity, pre-load the video + text overlay
        handleEditorExtras();
    }

    private void bindViews() {
        playerPreview        = findViewById(R.id.player_preview);
        ivThumbPreview       = findViewById(R.id.iv_thumb_preview);
        layoutPickVideo      = findViewById(R.id.layout_pick_video);
        layoutCompression    = findViewById(R.id.layout_compression);
        layoutVideoInfo      = findViewById(R.id.layout_video_info);
        layoutUploadProgress = findViewById(R.id.layout_upload_progress);
        progressCompress     = findViewById(R.id.progress_compress);
        progressUpload       = findViewById(R.id.progress_upload);
        tvCompressStatus     = findViewById(R.id.tv_compress_status);
        tvUploadStatus       = findViewById(R.id.tv_upload_status);
        tvVideoInfo          = findViewById(R.id.tv_video_info);
        tvCompressionSavings = findViewById(R.id.tv_compression_savings);
        etCaption            = findViewById(R.id.et_caption);
        etMusic              = findViewById(R.id.et_music);
        btnPostReel          = findViewById(R.id.btn_post_reel);
        chipQuality          = findViewById(R.id.chip_quality);
        chipAudience         = findViewById(R.id.chip_audience);
        chipDuetLevel        = findViewById(R.id.chip_duet_level);
        chipStitchLevel      = findViewById(R.id.chip_stitch_level);
        btnTagPeople         = findViewById(R.id.btn_tag_people);
        btnLocationTag       = findViewById(R.id.btn_location_tag);
        btnPrivacySettings   = findViewById(R.id.btn_privacy_settings);
        btnSchedule          = findViewById(R.id.btn_schedule);
        btnSaveDraft         = findViewById(R.id.btn_save_draft);
        btnProductTag        = findViewById(R.id.btn_product_tag);
        tvTagSummary            = findViewById(R.id.tv_tag_summary);
        tvLocationName          = findViewById(R.id.tv_location_name);
        tvScheduleTime          = findViewById(R.id.tv_schedule_time);
        tvSeriesPickerUpload    = findViewById(R.id.tv_series_picker);
        // Photo slideshow views
        cardPhotos              = findViewById(R.id.card_photos);
        btnMediaTypeVideo       = findViewById(R.id.btn_media_type_video);
        btnMediaTypePhotos      = findViewById(R.id.btn_media_type_photos);
        btnPickPhotos           = findViewById(R.id.btn_pick_photos);
        llPhotoPreviewContainer = findViewById(R.id.ll_photo_preview_container);
        tvPhotoCount            = findViewById(R.id.tv_photo_count);
    }

    private void setupChipDefaults() {
        Chip chipStandard = findViewById(R.id.chip_standard);
        if (chipStandard != null) chipStandard.setChecked(true);
        Chip chipEveryone = findViewById(R.id.chip_everyone);
        if (chipEveryone != null) chipEveryone.setChecked(true);
    }

    private void openSeriesPickerFromUpload() {
        com.callx.app.social.DuetSeriesPickerBottomSheet sheet =
            new com.callx.app.social.DuetSeriesPickerBottomSheet();
        sheet.setSeriesPickListener(new com.callx.app.social.DuetSeriesPickerBottomSheet.SeriesPickListener() {
            @Override
            public void onSeriesPicked(String id, String title, int nextEp) {
                seriesId      = id;
                seriesTitle   = title;
                episodeNumber = nextEp;
                if (tvSeriesPickerUpload != null)
                    tvSeriesPickerUpload.setText(title + "  (Part " + nextEp + ")");
            }
            @Override
            public void onSeriesCleared() {
                seriesId      = null;
                seriesTitle   = null;
                episodeNumber = 0;
                if (tvSeriesPickerUpload != null)
                    tvSeriesPickerUpload.setText("None");
            }
        });
        sheet.show(getSupportFragmentManager(), "series_picker_upload");
    }

    /**
     * If launched from ReelEditorActivity, auto-load the video file
     * and pre-populate any text overlay / music passed via intent extras.
     */
    private void handleEditorExtras() {
        Intent i = getIntent();
        if (i == null) return;

        // ── Read sound extras FIRST (before any early return) ─────────────
        // These are set by SoundDetailActivity (gallery flow) OR ReelEditorActivity (camera flow)
        String soundId    = i.getStringExtra(EXTRA_SOUND_ID);
        String soundTitle = i.getStringExtra(EXTRA_SOUND_TITLE);
        String soundUrl   = i.getStringExtra(EXTRA_SOUND_URL);
        if (soundId    != null && !soundId.isEmpty())    preSelectedSoundId  = soundId;
        if (soundUrl   != null && !soundUrl.isEmpty())   preSelectedSoundUrl = soundUrl;
        if (soundTitle != null && !soundTitle.isEmpty() && etMusic != null
                && (etMusic.getText() == null || etMusic.getText().toString().isEmpty())) {
            etMusic.setText(soundTitle);
        }

        // Audio mix settings from ReelEditorActivity (camera flow only)
        mixOrigVol       = i.getFloatExtra("mix_orig_vol",        1.0f);
        mixMusicVol      = i.getFloatExtra("mix_music_vol",       0.8f);
        mixVoiceoverPath = i.getStringExtra("mix_voiceover_path");
        mixVoiceoverVol  = i.getFloatExtra("mix_voiceover_vol",   1.0f);
        if (mixVoiceoverPath == null) mixVoiceoverPath = "";

        // ✅ NEW: If ReelCameraActivity already replaced mic audio, skip mixing at upload time.
        audioAlreadyReplaced = i.getBooleanExtra("audio_already_replaced", false);

        // ── If no video URI, stop here (gallery flow: user picks video later) ──
        String videoUriStr = i.getStringExtra(EXTRA_VIDEO_URI);
        if (videoUriStr == null || videoUriStr.isEmpty()) return;

        boolean isFilePath = i.getBooleanExtra(EXTRA_IS_FILE_PATH, true);
        Uri uri = isFilePath
            ? android.net.Uri.fromFile(new java.io.File(videoUriStr))
            : Uri.parse(videoUriStr);
        selectedUri           = uri;
        compressedResult      = null;
        compressionInProgress = false;
        showVideoPreview(uri);
        compressVideo(uri);

        // Pre-fill text overlay as initial caption if provided
        String textOverlay = i.getStringExtra(EXTRA_TEXT_OVERLAY);
        if (textOverlay != null && !textOverlay.isEmpty() && etCaption != null) {
            etCaption.setText(textOverlay);
        }

        // Pre-fill music name if provided
        String musicName = i.getStringExtra(EXTRA_MUSIC_NAME);
        if (musicName != null && !musicName.isEmpty() && etMusic != null) {
            etMusic.setText(musicName);
        }

        // Fix 4 & 6 & 8: read duet metadata
        isDuet         = i.getBooleanExtra(EXTRA_IS_DUET, false);
        String dOId    = i.getStringExtra(EXTRA_DUET_ORIGINAL_ID);
        String dOUid   = i.getStringExtra(EXTRA_DUET_OWNER_UID);
        String dLabel  = i.getStringExtra(EXTRA_DUET_LABEL);
        if (dOId   != null) duetOriginalId  = dOId;
        if (dOUid  != null) duetOwnerUid    = dOUid;
        if (dLabel != null) duetLabel       = dLabel;
        String dOrigUrl = i.getStringExtra(EXTRA_DUET_ORIGINAL_URL);
        if (dOrigUrl != null) duetOriginalUrl = dOrigUrl;
        // ✅ FIX GAP #6: read duet layout mode (was never read before → always 0 in Firebase)
        duetLayoutMode = i.getIntExtra("duet_layout_mode", 0);
          // ✅ FIX (CHAIN DUET): read duetRootId so it can be persisted to Firebase
          String dRootId = i.getStringExtra("duet_root_id");
          if (dRootId != null && !dRootId.isEmpty()) duetRootId = dRootId;

        // ✅ FIX GAP #2: read stitch metadata
        isStitch = i.getBooleanExtra("is_stitch", false);
        String sId      = i.getStringExtra("stitch_original_id");
        String sUrl     = i.getStringExtra("stitch_original_url");
        String sOwner   = i.getStringExtra("stitch_original_owner_uid");
        if (sId    != null) stitchOriginalId  = sId;
        if (sUrl   != null) stitchOriginalUrl = sUrl;
        if (sOwner != null) stitchOwnerUid    = sOwner;
        stitchDurationSec = i.getIntExtra("stitch_duration_sec", 3);

        // ── Duet Series ─────────────────────────────────────────────────────
        String sId2   = i.getStringExtra(ReelPostDetailsActivity.RESULT_SERIES_ID);
        String sTitle = i.getStringExtra(ReelPostDetailsActivity.RESULT_SERIES_TITLE);
        int    sEp    = i.getIntExtra(ReelPostDetailsActivity.RESULT_EPISODE_NUMBER, 0);
        if (sId2 != null && !sId2.isEmpty()) {
            seriesId      = sId2;
            seriesTitle   = sTitle != null ? sTitle : "";
            episodeNumber = sEp;
        }
    }

    // ── Media Type Toggle ─────────────────────────────────────────────────

    private void switchToVideoMode() {
        isPhotoMode = false;
        if (layoutPickVideo   != null) layoutPickVideo.setVisibility(View.VISIBLE);
        if (cardPhotos        != null) cardPhotos.setVisibility(View.GONE);
        if (btnMediaTypeVideo != null) {
            btnMediaTypeVideo.setBackgroundResource(com.callx.app.reels.R.color.brand_primary);
            btnMediaTypeVideo.setTextColor(android.graphics.Color.WHITE);
        }
        if (btnMediaTypePhotos != null) {
            btnMediaTypePhotos.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnMediaTypePhotos.setTextColor(0xFF888888);
        }
    }

    private void switchToPhotoMode() {
        isPhotoMode = true;
        if (layoutPickVideo    != null) layoutPickVideo.setVisibility(View.GONE);
        if (cardPhotos         != null) cardPhotos.setVisibility(View.VISIBLE);
        if (playerPreview      != null) playerPreview.setVisibility(View.GONE);
        if (btnMediaTypePhotos != null) {
            btnMediaTypePhotos.setBackgroundResource(com.callx.app.reels.R.color.brand_primary);
            btnMediaTypePhotos.setTextColor(android.graphics.Color.WHITE);
        }
        if (btnMediaTypeVideo != null) {
            btnMediaTypeVideo.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnMediaTypeVideo.setTextColor(0xFF888888);
        }
    }

    // ── Permission ────────────────────────────────────────────────────────

    private void checkPermissionAndPickVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_VIDEO}, REQ_PERMISSION);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMISSION);
                return;
            }
        }
        pickVideo();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickVideo();
            } else {
                Toast.makeText(this, "Storage permission required to select video",
                    Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_PERM_PHOTOS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickPhotos();
            } else {
                Toast.makeText(this, "Storage permission required to select photos",
                    Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void checkPermissionAndPickPhotos() {
        if (selectedPhotoUris.size() >= MAX_PHOTOS) {
            Toast.makeText(this, "Maximum " + MAX_PHOTOS + " photos allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_PERM_PHOTOS);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM_PHOTOS);
                return;
            }
        }
        pickPhotos();
    }

    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, "Select Photos"), REQ_PICK_PHOTOS);
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setType("video/*");
        startActivityForResult(i, REQ_PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 501 && resultCode == Activity.RESULT_OK && data != null) {
            taggedUids = data.getStringExtra(ReelTagPeopleActivity.RESULT_TAGGED_UIDS);
            if (taggedUids == null) taggedUids = "";
            if (tvTagSummary != null) tvTagSummary.setText(taggedUids.isEmpty() ? "" : "Tagged");
        } else if (requestCode == 502 && resultCode == Activity.RESULT_OK && data != null) {
            locationName = data.getStringExtra(ReelLocationTagActivity.RESULT_NAME);
            if (locationName == null) locationName = "";
            if (tvLocationName != null) tvLocationName.setText(locationName);
        } else if (requestCode == 504 && resultCode == Activity.RESULT_OK && data != null) {
            scheduleTime = data.getStringExtra("scheduled_time");
            if (scheduleTime == null) scheduleTime = "";
            if (tvScheduleTime != null) tvScheduleTime.setText(scheduleTime);
        }
        if (requestCode == REQ_PICK_VIDEO && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            selectedUri      = data.getData();
            compressedResult = null;
            compressionInProgress = false;
            showVideoPreview(selectedUri);
            compressVideo(selectedUri);
        } else if (requestCode == REQ_PICK_PHOTOS && resultCode == Activity.RESULT_OK && data != null) {
            // Multi-select: data.getClipData() if multiple, data.getData() if single
            int remaining = MAX_PHOTOS - selectedPhotoUris.size();
            if (data.getClipData() != null) {
                int count = Math.min(data.getClipData().getItemCount(), remaining);
                for (int idx = 0; idx < count; idx++) {
                    Uri uri = data.getClipData().getItemAt(idx).getUri();
                    selectedPhotoUris.add(uri);
                    addPhotoPreviewThumbnail(uri, selectedPhotoUris.size() - 1);
                }
            } else if (data.getData() != null && remaining > 0) {
                Uri uri = data.getData();
                selectedPhotoUris.add(uri);
                addPhotoPreviewThumbnail(uri, selectedPhotoUris.size() - 1);
            }
            updatePhotoCountLabel();
            btnPostReel.setEnabled(!selectedPhotoUris.isEmpty());
        }
    }

    /** Adds a small thumbnail ImageView for the given photo URI into the preview row. */
    private void addPhotoPreviewThumbnail(Uri uri, final int index) {
        if (llPhotoPreviewContainer == null || !isPhotoMode) return;
        int sizePx = (int)(80 * getResources().getDisplayMetrics().density);
        int marginPx = (int)(6 * getResources().getDisplayMetrics().density);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(sizePx, sizePx);
        flp.setMargins(0, 0, marginPx, 0);
        frame.setLayoutParams(flp);

        ImageView thumb = new ImageView(this);
        thumb.setLayoutParams(new android.widget.FrameLayout.LayoutParams(sizePx, sizePx));
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Glide.with(this).load(uri).centerCrop().into(thumb);

        // Small remove button (×) in top-right corner
        TextView btnRemove = new TextView(this);
        android.widget.FrameLayout.LayoutParams blp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        btnRemove.setLayoutParams(blp);
        btnRemove.setText("×");
        btnRemove.setTextColor(android.graphics.Color.WHITE);
        btnRemove.setTextSize(14f);
        btnRemove.setBackgroundColor(0xCC000000);
        btnRemove.setPadding(4, 0, 4, 0);
        btnRemove.setOnClickListener(v -> {
            int pos = llPhotoPreviewContainer.indexOfChild(frame);
            if (pos >= 0 && pos < selectedPhotoUris.size()) {
                selectedPhotoUris.remove(pos);
                llPhotoPreviewContainer.removeView(frame);
                updatePhotoCountLabel();
                if (selectedPhotoUris.isEmpty()) btnPostReel.setEnabled(false);
            }
        });

        frame.addView(thumb);
        frame.addView(btnRemove);
        llPhotoPreviewContainer.addView(frame);
    }

    private void updatePhotoCountLabel() {
        if (tvPhotoCount != null) {
            tvPhotoCount.setText(selectedPhotoUris.size() + " / " + MAX_PHOTOS);
        }
    }

    // ── Video preview ─────────────────────────────────────────────────────

    private void showVideoPreview(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        layoutPickVideo.setVisibility(View.GONE);
        ivThumbPreview.setVisibility(View.GONE);
        releasePreviewPlayer();
        try {
            previewPlayer = new ExoPlayer.Builder(this).build();
            playerPreview.setPlayer(previewPlayer);
            previewPlayer.setMediaItem(MediaItem.fromUri(uri));
            previewPlayer.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
            previewPlayer.prepare();
            previewPlayer.setPlayWhenReady(false);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot preview this video", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Compression ───────────────────────────────────────────────────────

    private void compressVideo(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        VideoQualityPreferences.Quality quality = getSelectedQuality();

        layoutCompression.setVisibility(View.VISIBLE);
        progressCompress.setProgress(0);
        tvCompressStatus.setText("Compressing… 0%");
        btnPostReel.setEnabled(false);
        compressionInProgress = true;

        WeakReference<ReelUploadActivity> ref = new WeakReference<>(this);

        VideoCompressor.compress(this, uri, quality, new VideoCompressor.Callback() {
            @Override
            public void onProgress(int percent) {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.progressCompress.setProgress(percent);
                a.tvCompressStatus.setText("Compressing… " + percent + "%");
            }

            @Override
            public void onSuccess(VideoCompressor.Result result) {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.compressionInProgress = false;
                a.compressedResult = result;
                a.layoutCompression.setVisibility(View.GONE);
                a.btnPostReel.setEnabled(true);

                a.layoutVideoInfo.setVisibility(View.VISIBLE);
                a.tvVideoInfo.setText(result.width + "×" + result.height
                    + "  •  " + (result.durationMs / 1000) + "s"
                    + "  •  " + String.format("%.1f MB", result.compressedBytes / 1_000_000f));
                float savings = result.savingsPercent();
                if (savings > 1f) {
                    a.tvCompressionSavings.setText(String.format("%.0f%% saved", savings));
                    a.tvCompressionSavings.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onError(Exception e) {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.compressionInProgress = false;
                a.layoutCompression.setVisibility(View.GONE);
                a.btnPostReel.setEnabled(true);
                String msg = e != null ? e.getMessage() : "Unknown error";
                Toast.makeText(a, "Compression failed: " + msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ── Upload logic ──────────────────────────────────────────────────────

    private void handlePostReel() {
        // ── Photo slideshow branch ─────────────────────────────────────────
        if (isPhotoMode) {
            handlePostPhotoSlideshow();
            return;
        }
        if (selectedUri == null) {
            Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (compressionInProgress) {
            Toast.makeText(this, "Video is still being compressed…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (compressedResult == null) {
            Toast.makeText(this, "Compression not complete. Please wait.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (compressedResult.videoFile == null || !compressedResult.videoFile.exists()
                || compressedResult.videoFile.length() == 0) {
            Toast.makeText(this, "Compressed video file is missing. Please try again.",
                Toast.LENGTH_SHORT).show();
            compressedResult = null;
            compressVideo(selectedUri);
            return;
        }

        String caption   = etCaption.getText() != null ? etCaption.getText().toString().trim() : "";
        String musicName = etMusic.getText()   != null ? etMusic.getText().toString().trim()   : "";

        // ── Instagram golden rule: "Record first, mix later" ──────────────
        // If user has selected background music, run AudioMixHelper before upload.
        boolean hasMusicTrack = preSelectedSoundUrl != null && !preSelectedSoundUrl.isEmpty();
        boolean hasVoiceover  = mixVoiceoverPath != null && !mixVoiceoverPath.isEmpty();

        // ✅ NEW: If audio was already replaced at camera recording stage, skip mixing.
        if (audioAlreadyReplaced) {
            uploadReel(caption, musicName, compressedResult.videoFile.getAbsolutePath());
        } else if (hasMusicTrack || hasVoiceover) {
            runAudioMixThenUpload(caption, musicName);
        } else {
            // No extra audio — upload compressed video directly
            uploadReel(caption, musicName, compressedResult.videoFile.getAbsolutePath());
        }
    }

    /**
     * Step: mix audio tracks with AudioMixHelper, then proceed to upload.
     */
    private void runAudioMixThenUpload(String caption, String musicName) {
        btnPostReel.setEnabled(false);
        layoutUploadProgress.setVisibility(View.VISIBLE);
        progressUpload.setProgress(0);
        tvUploadStatus.setText("Mixing audio…");

        String rawVideoPath = compressedResult.videoFile.getAbsolutePath();
        WeakReference<ReelUploadActivity> ref = new WeakReference<>(this);

        AudioMixHelper.mixAndExport(
            this,
            rawVideoPath,
            preSelectedSoundUrl,
            mixVoiceoverPath,
            mixOrigVol,
            mixMusicVol,
            mixVoiceoverVol,
            new AudioMixHelper.MixCallback() {
                @Override public void onProgress(int percent) {
                    ReelUploadActivity a = ref.get();
                    if (a == null || a.isFinishing() || a.isDestroyed()) return;
                    a.progressUpload.setProgress(percent / 2); // mix = 0–50%, upload = 50–100%
                    a.tvUploadStatus.setText("Mixing audio… " + percent + "%");
                }
                @Override public void onSuccess(String mixedPath) {
                    ReelUploadActivity a = ref.get();
                    if (a == null || a.isFinishing() || a.isDestroyed()) return;
                    a.mixedVideoPath = mixedPath;
                    a.tvUploadStatus.setText("Uploading reel…");
                    a.uploadReel(caption, musicName, mixedPath);
                }
                @Override public void onError(Exception e) {
                    ReelUploadActivity a = ref.get();
                    if (a == null || a.isFinishing() || a.isDestroyed()) return;
                    a.btnPostReel.setEnabled(true);
                    a.layoutUploadProgress.setVisibility(View.GONE);
                    // Fallback: upload without mixing
                    Toast.makeText(a,
                        "Audio mix failed, uploading original video. (" + e.getMessage() + ")",
                        Toast.LENGTH_LONG).show();
                    a.uploadReel(caption, musicName, rawVideoPath);
                }
            }
        );
    }

    private void uploadReel(String caption, String musicName, String videoPath) {
        btnPostReel.setEnabled(false);
        layoutUploadProgress.setVisibility(View.VISIBLE);
        progressUpload.setProgress(50);
        tvUploadStatus.setText("Uploading reel…");

        WeakReference<ReelUploadActivity> ref = new WeakReference<>(this);

        // If audio was mixed, we need to upload the mixed file.
        // VideoUploader accepts a Result — re-use compressedResult but override
        // the file by creating a thin wrapper that replaces only videoFile.
        final VideoCompressor.Result uploadResult = compressedResult;
        final java.io.File uploadFile = new java.io.File(videoPath);

        VideoUploader.upload(this, uploadResult, uploadFile, new VideoUploader.UploadCallback() {
            @Override
            public void onProgress(int percent) {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.progressUpload.setProgress(percent);
                a.tvUploadStatus.setText("Uploading… " + percent + "%");
            }

            @Override
            public void onSuccess(String thumbUrl, String videoUrl,
                                  int durationMs, int width, int height) {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.saveReelToFirebase(thumbUrl, videoUrl, durationMs, width, height,
                    caption, musicName, uploadResult, videoPath);
            }

            @Override
            public void onError(Exception e) {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.btnPostReel.setEnabled(true);
                a.layoutUploadProgress.setVisibility(View.GONE);
                String msg = e != null ? e.getMessage() : "Network error";
                Toast.makeText(a, "Upload failed: " + msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ── Photo Slideshow Upload ────────────────────────────────────────────

    private void handlePostPhotoSlideshow() {
        if (selectedPhotoUris.isEmpty()) {
            Toast.makeText(this, "Please select at least one photo", Toast.LENGTH_SHORT).show();
            return;
        }
        String caption   = etCaption.getText() != null ? etCaption.getText().toString().trim() : "";
        String musicName = etMusic.getText()   != null ? etMusic.getText().toString().trim()   : "";
        uploadPhotoSlideshow(caption, musicName);
    }

    /**
     * Uploads each selected photo to Firebase Storage at
     * reel_photos/{uid}/{reelId}/{index}.jpg, collects all download URLs,
     * then saves the photo reel to Firebase Realtime Database.
     */
    private void uploadPhotoSlideshow(String caption, String musicName) {
        if (isFinishing() || isDestroyed()) return;

        String myUid;
        try {
            myUid = FirebaseUtils.getCurrentUid();
            if (myUid == null || myUid.isEmpty()) throw new IllegalStateException("uid null");
        } catch (Exception e) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        btnPostReel.setEnabled(false);
        layoutUploadProgress.setVisibility(View.VISIBLE);
        progressUpload.setProgress(0);
        tvUploadStatus.setText("Uploading photos…");

        String reelId = FirebaseUtils.getReelsRef().push().getKey();
        if (reelId == null) reelId = UUID.randomUUID().toString();
        final String finalReelId = reelId;
        final String finalMyUid  = myUid;

        int totalPhotos = selectedPhotoUris.size();
        final String[] photoUrlsArr = new String[totalPhotos];
        final AtomicInteger doneCount = new AtomicInteger(0);
        final WeakReference<ReelUploadActivity> ref = new WeakReference<>(this);

        for (int i = 0; i < totalPhotos; i++) {
            final int index = i;
            ReelCloudinaryUtils.uploadReelSlideshowPhoto(this, selectedPhotoUris.get(i), index,
                new ReelCloudinaryUtils.ImageUploadCallback() {
                    @Override
                    public void onSuccess(String url) {
                        photoUrlsArr[index] = url;
                        int done = doneCount.incrementAndGet();
                        ReelUploadActivity a = ref.get();
                        if (a == null || a.isFinishing() || a.isDestroyed()) return;
                        a.progressUpload.setProgress((int)(done * 100f / totalPhotos));
                        a.tvUploadStatus.setText("Uploaded " + done + " / " + totalPhotos + " photos");
                        if (done == totalPhotos) {
                            List<String> urls = new ArrayList<>();
                            for (String u : photoUrlsArr) if (u != null) urls.add(u);
                            a.progressUpload.setProgress(100);
                            a.tvUploadStatus.setText("Saving reel…");
                            a.savePhotoReelToFirebase(urls, caption, musicName, finalReelId, finalMyUid);
                        }
                    }
                    @Override
                    public void onError(String message) {
                        int done = doneCount.incrementAndGet();
                        ReelUploadActivity a = ref.get();
                        if (a == null || a.isFinishing() || a.isDestroyed()) return;
                        if (done == totalPhotos) {
                            List<String> urls = new ArrayList<>();
                            for (String u : photoUrlsArr) if (u != null) urls.add(u);
                            if (!urls.isEmpty()) {
                                a.savePhotoReelToFirebase(urls, caption, musicName, finalReelId, finalMyUid);
                            } else {
                                a.btnPostReel.setEnabled(true);
                                a.layoutUploadProgress.setVisibility(View.GONE);
                                Toast.makeText(a, "Photo upload failed: " + message, Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                });
        }
    }

    private void savePhotoReelToFirebase(List<String> photoUrls, String caption,
                                          String musicName, String reelId, String myUid) {
        if (isFinishing() || isDestroyed()) return;

        String myName;
        try {
            myName = FirebaseUtils.getCurrentName();
        } catch (Exception e) {
            myName = "";
        }
        final String finalMyName = myName;

        String audienceType = getAudienceType();
        WeakReference<ReelUploadActivity> ref = new WeakReference<>(this);

        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    ReelUploadActivity a = ref.get();
                    if (a == null || a.isFinishing() || a.isDestroyed()) return;

                    String photo = snap.child("photoUrl").getValue(String.class);
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String safePhoto = (thumb != null && !thumb.isEmpty()) ? thumb : (photo != null ? photo : "");

                    // Use first photo as thumbnail
                    String thumbUrl = photoUrls.isEmpty() ? "" : photoUrls.get(0);

                    ReelModel reel = new ReelModel(
                        reelId, myUid, finalMyName, safePhoto,
                        "", thumbUrl, caption, musicName,
                        System.currentTimeMillis(), photoUrls.size() * 3000, 0, 0);

                    reel.mediaType      = "photo_slideshow";
                    reel.photoUrls      = photoUrls;
                    reel.photoDurationMs = 3000;
                    reel.audienceType   = audienceType;
                    reel.thumbUrl       = thumbUrl;

                    // Duet / Stitch permissions
                    String duetLevel   = a.getDuetLevel();
                    String stitchLevel = a.getStitchLevel();
                    reel.allowDuetLevel   = duetLevel;
                    reel.allowDuet        = !"off".equals(duetLevel);
                    reel.allowStitchLevel = stitchLevel;
                    reel.allowStitch      = !"off".equals(stitchLevel);

                    if (!a.preSelectedSoundId.isEmpty())  reel.musicId  = a.preSelectedSoundId;
                    if (!a.preSelectedSoundUrl.isEmpty()) reel.musicUrl = a.preSelectedSoundUrl;

                    FirebaseUtils.getReelsRef().child(reelId).setValue(reel)
                        .addOnSuccessListener(unused -> {
                            ReelUploadActivity b = ref.get();
                            if (b == null || b.isFinishing() || b.isDestroyed()) return;
                            FirebaseUtils.getReelsByUserRef(myUid).child(reelId).setValue(true);
                            Toast.makeText(b, "Photo reel posted! 🎉", Toast.LENGTH_SHORT).show();
                            b.setResult(RESULT_OK);
                            b.layoutUploadProgress.setVisibility(View.GONE);
                            b.finish();
                        })
                        .addOnFailureListener(ex -> {
                            ReelUploadActivity b = ref.get();
                            if (b == null || b.isFinishing() || b.isDestroyed()) return;
                            b.btnPostReel.setEnabled(true);
                            b.layoutUploadProgress.setVisibility(View.GONE);
                            Toast.makeText(b, "Failed to save reel: " + ex.getMessage(),
                                Toast.LENGTH_LONG).show();
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    ReelUploadActivity a = ref.get();
                    if (a == null) return;
                    a.btnPostReel.setEnabled(true);
                    a.layoutUploadProgress.setVisibility(View.GONE);
                }
            });
    }

    // ── Firebase save ─────────────────────────────────────────────────────

    private void saveReelToFirebase(String thumbUrl, String videoUrl,
                                    int durationMs, int width, int height,
                                    String caption, String musicName,
                                    VideoCompressor.Result result, String videoPath) {
        if (isFinishing() || isDestroyed()) return;

        String myUid, myName;
        try {
            myUid  = FirebaseUtils.getCurrentUid();
            myName = FirebaseUtils.getCurrentName();
            if (myUid == null || myUid.isEmpty()) throw new IllegalStateException("uid null");
        } catch (Exception e) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String reelId = FirebaseUtils.getReelsRef().push().getKey();
        if (reelId == null) reelId = UUID.randomUUID().toString();
        final String finalReelId = reelId;

        String audienceType = getAudienceType();

        WeakReference<ReelUploadActivity> ref = new WeakReference<>(this);

        // Reel post mein owner ka Reels profile avatar store karo (reels/users/{uid})
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;

                String photo = snap.child("photoUrl").getValue(String.class);
                String thumb = snap.child("thumbUrl").getValue(String.class);
                // Reels thumbUrl use karo — saves data for all viewers
                String safePhoto = (thumb != null && !thumb.isEmpty()) ? thumb : (photo != null ? photo : "");

                ReelModel reel = new ReelModel(
                    finalReelId, myUid, myName, safePhoto,
                    videoUrl, thumbUrl, caption, musicName,
                    System.currentTimeMillis(), durationMs, width, height);

                reel.audienceType = audienceType;

                // ✅ Duet & Stitch permission — set by creator at upload time
                String duetLevel   = a.getDuetLevel();
                String stitchLevel = a.getStitchLevel();
                reel.allowDuetLevel   = duetLevel;
                reel.allowDuet        = !"off".equals(duetLevel);   // legacy boolean
                reel.allowStitchLevel = stitchLevel;
                reel.allowStitch      = !"off".equals(stitchLevel); // legacy boolean

                // Attach pre-selected sound if provided
                if (!a.preSelectedSoundId.isEmpty())  reel.musicId  = a.preSelectedSoundId;
                if (!a.preSelectedSoundUrl.isEmpty()) reel.musicUrl = a.preSelectedSoundUrl;
                if (result != null) {
                    reel.compressionSummary = result.compressionSummary();
                    reel.savingsPercent     = result.savingsPercent();
                }
                // Fix 4 & 8: duet fields on the new reel
                if (a.isDuet && !a.duetOriginalId.isEmpty()) {
                    reel.duetOf           = a.duetOriginalId;
                    reel.duetOfOwnerUid   = a.duetOwnerUid;
                    reel.duetOriginalUrl  = a.duetOriginalUrl;
                    reel.duetLayoutMode   = a.duetLayoutMode; // ✅ FIX GAP #6: now saved
            reel.duetRootId    = duetRootId;    // ✅ FIX (CHAIN DUET): persist root ID
                    reel.caption       = a.duetLabel.isEmpty() ? reel.caption
                                         : (a.duetLabel + (reel.caption.isEmpty() ? "" : " – " + reel.caption));
                }

                // ✅ FIX GAP #2: stitch fields on the new reel (field names match ReelModel)
                if (a.isStitch && !a.stitchOriginalId.isEmpty()) {
                    reel.stitchOf         = a.stitchOriginalId;
                    reel.stitchOfOwnerUid = a.stitchOwnerUid;  // ReelModel field is stitchOfOwnerUid
                }

                
                  // ── Duet Series: tag the reel + enqueue subscriber notifications ─
                  if (a.seriesId != null && !a.seriesId.isEmpty() && a.episodeNumber > 0) {
                      reel.seriesId            = a.seriesId;
                      reel.seriesEpisodeNumber = a.episodeNumber;
                      reel.seriesTitle         = a.seriesTitle != null ? a.seriesTitle : "";
                      String creatorPhoto = "";
                      com.google.firebase.auth.FirebaseUser fu =
                          com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                      if (fu != null && fu.getPhotoUrl() != null)
                          creatorPhoto = fu.getPhotoUrl().toString();
                      com.callx.app.workers.DuetSeriesNotificationWorker.enqueue(
                          a,
                          a.seriesId,
                          a.seriesTitle != null ? a.seriesTitle : "",
                          a.episodeNumber,
                          finalReelId,
                          reel.thumbUrl != null ? reel.thumbUrl : "",
                          myUid,
                          reel.ownerName != null ? reel.ownerName : "",
                          creatorPhoto
                      );
                  }

                  FirebaseUtils.getReelsRef().child(finalReelId).setValue(reel)
                    .addOnSuccessListener(unused -> {
                        ReelUploadActivity b = ref.get();
                        if (b == null || b.isFinishing() || b.isDestroyed()) return;

                        FirebaseUtils.getReelsByUserRef(myUid).child(finalReelId).setValue(true);

                        Toast.makeText(b, "Reel posted! 🎉", Toast.LENGTH_SHORT).show();
                        b.setResult(RESULT_OK);

                        // Fix 6: increment duetCount on original reel
                        if (b.isDuet && !b.duetOriginalId.isEmpty()) {
                            FirebaseUtils.getReelsRef()
                                .child(b.duetOriginalId)
                                .child("duetCount")
                                .setValue(ServerValue.increment(1));

                            // ✅ FIXED: Full notification pipeline (FCM + in-app + queue fallback)
                            // Fires only AFTER reel is confirmed published to Firebase.
                            // DuetNotificationWorker → PushNotify.notifyReelDuet()
                            //   → SERVER/notify/reel → FCM → CallxMessagingService
                            //   → ReelFCMNotificationHandler TYPE_DUET (background/killed safe)
                            if (!b.duetOwnerUid.isEmpty() && !b.duetOwnerUid.equals(myUid)) {
                                com.callx.app.workers.DuetNotificationWorker.enqueue(
                                    b,
                                    b.duetOriginalId, // original reel being dueted
                                    myUid,            // duet creator UID
                                    myName,           // duet creator name
                                    safePhoto,        // duet creator avatar URL
                                    b.duetOwnerUid,   // original reel owner UID
                                    thumbUrl);        // new duet reel thumbnail
                            }
                        }

                        // ✅ FIX GAP #2: increment stitchCount + notify original creator
                        if (b.isStitch && !b.stitchOriginalId.isEmpty()) {
                            FirebaseUtils.getReelsRef()
                                .child(b.stitchOriginalId)
                                .child("stitchCount")
                                .setValue(com.google.firebase.database.ServerValue.increment(1));

                            if (!b.stitchOwnerUid.isEmpty() && !b.stitchOwnerUid.equals(myUid)) {
                                com.callx.app.workers.StitchNotificationWorker.enqueue(
                                    b,
                                    b.stitchOriginalId,
                                    myUid,
                                    myName,
                                    safePhoto,
                                    b.stitchOwnerUid,
                                    thumbUrl);
                            }
                        }

                        // ── Background: extract + upload original audio ───────
                        // This runs after the reel is already live, so user doesn't wait.
                        java.io.File videoFileForAudio = new java.io.File(videoPath);
                        if (videoFileForAudio.exists()) {
                            VideoUploader.uploadOriginalAudio(b, videoFileForAudio,
                                new VideoUploader.AudioUploadCallback() {
                                    @Override
                                    public void onSuccess(String audioUrl) {
                                        // Save originalAudioUrl to Firebase
                                        FirebaseUtils.getReelsRef()
                                            .child(finalReelId)
                                            .child("originalAudioUrl")
                                            .setValue(audioUrl);
                                        Log.d("ReelUpload",
                                            "originalAudioUrl saved: " + audioUrl);
                                    }
                                    @Override
                                    public void onError(Exception e) {
                                        Log.w("ReelUpload",
                                            "Audio upload failed (non-fatal): " + e.getMessage());
                                    }
                                });
                        }
                        // ─────────────────────────────────────────────────────

                        b.finish();
                    })
                    .addOnFailureListener(ex -> {
                        ReelUploadActivity b = ref.get();
                        if (b == null || b.isFinishing() || b.isDestroyed()) return;
                        b.btnPostReel.setEnabled(true);
                        b.layoutUploadProgress.setVisibility(View.GONE);
                        Toast.makeText(b, "Failed to save reel: " + ex.getMessage(),
                            Toast.LENGTH_LONG).show();
                    });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.btnPostReel.setEnabled(true);
                a.layoutUploadProgress.setVisibility(View.GONE);
                Toast.makeText(a, "Database error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private VideoQualityPreferences.Quality getSelectedQuality() {
        int id = chipQuality.getCheckedChipId();
        if (id == R.id.chip_low)    return VideoQualityPreferences.Quality.LOW;
        if (id == R.id.chip_hd)     return VideoQualityPreferences.Quality.HD;
        if (id == R.id.chip_fullhd) return VideoQualityPreferences.Quality.FULL_HD;
        return VideoQualityPreferences.Quality.STANDARD;
    }

    private String getAudienceType() {
        int id = chipAudience.getCheckedChipId();
        return id == R.id.chip_contacts_only ? "contacts" : "everyone";
    }

    /**
     * Returns "everyone" | "followers" | "off" based on the Duet chip selection.
     * Default (everyone) is pre-checked in layout XML.
     */
    private String getDuetLevel() {
        if (chipDuetLevel == null) return "everyone";
        int id = chipDuetLevel.getCheckedChipId();
        if (id == R.id.chip_duet_followers) return "followers";
        if (id == R.id.chip_duet_off)       return "off";
        return "everyone";
    }

    /**
     * Returns "everyone" | "followers" | "off" based on the Stitch chip selection.
     */
    private String getStitchLevel() {
        if (chipStitchLevel == null) return "everyone";
        int id = chipStitchLevel.getCheckedChipId();
        if (id == R.id.chip_stitch_followers) return "followers";
        if (id == R.id.chip_stitch_off)        return "off";
        return "everyone";
    }

    private void releasePreviewPlayer() {
        if (previewPlayer != null) {
            try { previewPlayer.stop(); } catch (Exception ignored) {}
            try { previewPlayer.release(); } catch (Exception ignored) {}
            previewPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        releasePreviewPlayer();
        super.onDestroy();
    }
}
