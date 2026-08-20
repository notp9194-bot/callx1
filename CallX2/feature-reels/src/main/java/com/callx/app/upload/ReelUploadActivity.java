package com.callx.app.upload;
import com.callx.app.utils.AlertDialogStyler;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.social.MultiDuetActivity;
import com.callx.app.editor.ReelAudioMixerActivity;
import com.callx.app.editor.ReelPhotoMusicTrimActivity;
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
    // ✅ FIX: set by ReelEditorActivity only when it already burned the trim range
    // into the video file itself (its own hard-bake export). When this is false but
    // a real trim range is present, this screen bakes the trim itself before
    // compressing — previously EXTRA_TRIM_START/END were sent but never read here,
    // so any trim from a gallery-picked (non-file-path) video was silently dropped
    // and the full untrimmed clip got uploaded.
    public static final String EXTRA_TRIM_ALREADY_BAKED = "upload_trim_already_baked";
    public static final String EXTRA_TEXT_OVERLAY = "upload_text_overlay";
    public static final String EXTRA_MUSIC_NAME   = "upload_music_name";
    // Sound pre-selected from SoundDetailActivity
    public static final String EXTRA_SOUND_ID    = "selected_sound_id";
    public static final String EXTRA_SOUND_TITLE = "selected_sound_title";
    public static final String EXTRA_SOUND_URL   = "selected_sound_url";
    // ✅ FIX: cover/artist extras so the music disc shows the ORIGINAL
    // sound's photo immediately at post time, not just after the later
    // async Firebase patch in registerOrLinkSound().
    public static final String EXTRA_SOUND_COVER  = "selected_sound_cover";
    public static final String EXTRA_SOUND_ARTIST = "selected_sound_artist";
    // ✅ NEW: local file path of a photo captured via ReelCameraActivity's
    // Reels "+" flow (photo mode). When present, ReelUploadActivity switches
    // straight into photo mode with this single photo pre-loaded and opens
    // the full ReelPhotoEditorActivity tool set immediately.
    public static final String EXTRA_CAMERA_PHOTO_PATH = "camera_photo_path";
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
  

    private static final int REQ_PICK_VIDEO          = 901;
    private static final int REQ_PERMISSION          = 902;
    private static final int REQ_PICK_PHOTOS         = 903;
    private static final int REQ_PERM_PHOTOS         = 904;
    private static final int REQ_PHOTO_EDIT          = 905;
    /** ✅ NEW: open ReelTrendingAudioActivity to pick a sound from the upload screen */
    private static final int REQ_TRENDING_AUDIO      = 906;
    /** ✅ NEW: open ReelAudioMixerActivity to adjust volumes from the upload screen */
    private static final int REQ_AUDIO_MIXER_UPLOAD  = 907;
    /** ✅ NEW: open SoundDetailActivity for the currently selected sound */
    private static final int REQ_SOUND_DETAIL_UPLOAD = 908;
    /** NEW: open ReelPhotoMusicTrimActivity to adjust the trimmed range for a photo-reel track */
    private static final int REQ_PHOTO_MUSIC_TRIM     = 909;
    /** NEW: open ReelAudioMixerActivity right after the trim screen (video/photo both) */
    private static final int REQ_AUDIO_MIXER_AFTER_TRIM = 910;
    private static final int MAX_PHOTOS              = 10;
    /** Minimum reels-using-this-sound count before it's flagged "🔥 Trending". */
    private static final long TRENDING_REEL_THRESHOLD = 5L;

    private PlayerView        playerPreview;
    private ImageView         ivThumbPreview;
    private View              layoutPickVideo, layoutCompression, layoutVideoInfo;
    /** ✅ FIX: whole video-preview card (not just the inner "Tap to select video" hint) —
     *  needs to be hidden in Photo mode, otherwise its ImageView (iv_thumb_preview) stays
     *  visible+clickable underneath and taps meant for the photo card below it instead
     *  open the video picker. */
    private View              cardVideoPreview;
    private View              layoutUploadProgress;
    private ProgressBar       progressCompress, progressUpload;
    private TextView          tvCompressStatus, tvUploadStatus;
    private TextView          tvVideoInfo, tvCompressionSavings;
    private TextInputEditText etCaption, etMusic;
    private Button            btnPostReel;
    private ChipGroup         chipQuality, chipAudience, chipDuetLevel, chipStitchLevel;
    private View              btnTagPeople, btnLocationTag, btnPrivacySettings, btnSchedule, btnSaveDraft, btnProductTag, btnAddCollaborators;
    private android.widget.TextView tvCollabSummary;
    private static final int  REQ_ADD_COLLABORATORS = 506;
    private android.widget.TextView tvSeriesPickerUpload;
    private TextView          tvTagSummary, tvLocationName, tvScheduleTime;
    private String            taggedUids = "", locationName = "", scheduleTime = "";

    // ── Photo Slideshow fields ────────────────────────────────────────────────
    private boolean               isPhotoMode              = false;
    private final ArrayList<Uri>  selectedPhotoUris        = new ArrayList<>();
    private View                  cardPhotos;
    private View                  layoutPhotoEmptyState;
    private Button                btnMediaTypeVideo;
    private Button                btnMediaTypePhotos;
    private Button                btnPickPhotos;
    private LinearLayout          llPhotoPreviewContainer;
    private TextView              tvPhotoCount;
    // Advanced photo settings
    private android.widget.RadioGroup rgPhotoDuration;
    private android.widget.RadioGroup rgTransition;
    private android.widget.RadioGroup rgPhotoFilter;
    private int                   selectedDurationMs       = 3000;
    private String                selectedTransitionType   = "fade";
    private String                selectedFilter           = "normal";
    private int                   coverPhotoIndex          = 0;
    /** Per-photo captions, index-matched with selectedPhotoUris. "" = no caption. */
    private final java.util.ArrayList<String> photoCaptions        = new java.util.ArrayList<>();
    /** Per-photo filter overrides (e.g. "warm", "cool"). null = use global selectedFilter. */
    private final java.util.ArrayList<String> photoFilterList      = new java.util.ArrayList<>();
    /** Per-photo effect overrides (e.g. "vignette"). null = "none". */
    private final java.util.ArrayList<String> photoEffectList      = new java.util.ArrayList<>();
    /** Per-photo caption style JSON. "" = default. */
    private final java.util.ArrayList<String> photoCaptionStyleList = new java.util.ArrayList<>();
    /** Per-photo sticker JSON array. "" = none. */
    private final java.util.ArrayList<String> photoStickerJsonList  = new java.util.ArrayList<>();
    /** Per-photo Ken Burns direction. null = "random". */
    private final java.util.ArrayList<String> photoKbDirList        = new java.util.ArrayList<>();
    /** Per-photo duration override in ms. 0 = use global selectedDurationMs. */
    private final java.util.ArrayList<Integer> photoDurationList    = new java.util.ArrayList<>();
    /** Per-photo rotation (0/90/180/270). 0 = no rotation. */
    private final java.util.ArrayList<Float> photoRotationList      = new java.util.ArrayList<>();
    /** Index of photo being edited, used in onActivityResult. */
    private int photoEditIndex = -1;
    private androidx.appcompat.widget.SwitchCompat swAutoLoop;
    private Button                btnAddMorePhotos;

    // ── Audio section UI (injected programmatically in injectAudioSection()) ──
    /** Root card for the audio section; always visible. */
    private android.widget.LinearLayout layoutAudioCard;
    /** Shown when no audio is selected. */
    private android.widget.LinearLayout layoutAudioEmpty;
    /** Shown when an audio track is selected. */
    private android.widget.LinearLayout layoutAudioTrack;
    /** Displays selected track name. */
    private android.widget.TextView tvAudioTrackName;
    /** Displays artist name. */
    private android.widget.TextView tvAudioArtist;

    private Uri                    selectedUri;
    private String                 preSelectedSoundId    = "";
    private String                 preSelectedSoundUrl   = "";
    // ✅ FIX: carried alongside id/url so reel.musicCoverUrl can be set
    // synchronously at post time (artist reuses existing currentSoundArtist).
    private String                 preSelectedSoundCover  = "";
    /** Human-readable title of the currently selected sound. */
    private String                 currentSoundTitle     = "";
    /** Artist of the currently selected sound. */
    private String                 currentSoundArtist    = "";
    private ExoPlayer              previewPlayer;
    private VideoCompressor.Result compressedResult;
    private boolean                compressionInProgress = false;

    // ✅ FIX: trim range forwarded from ReelEditorActivity, applied here if the
    // editor didn't already bake it into the file (see EXTRA_TRIM_ALREADY_BAKED).
    private long    pendingTrimStartMs      = 0L;
    private long    pendingTrimEndMs        = 0L;
    private boolean pendingTrimAlreadyBaked = false;
    private boolean trimBakeInProgress      = false;
    /** Tracks whether selectedUri currently points at a local file path (vs a content:// URI), so the quality-chip listener can re-run the same pipeline. */
    private boolean lastVideoIsFilePath     = true;

    // Audio mix settings received from ReelEditorActivity (via ReelAudioMixerActivity)
    private float  mixOrigVol       = 1.0f;
    private float  mixMusicVol      = 0.8f;
    private String mixVoiceoverPath = "";
    private float  mixVoiceoverVol  = 1.0f;
    private String mixedVideoPath   = null; // set after AudioMixHelper finishes

    /**
     * Interactive sticker JSON array for a VIDEO reel, forwarded from
     * ReelEditorActivity's "sticker_json" extra (StatusStickerPickerSheet
     * output). Saved verbatim to ReelModel#stickerJson on upload — these are
     * NOT baked into the video pixels, so they stay tappable/votable in the
     * feed exactly like the photo-slideshow reel and Status flows.
     */
    private String videoStickerJson = "";

    // ✅ NEW: True when ReelCameraActivity already replaced mic audio at recording time.
    // If true, skip AudioMixHelper in handlePostReel() to avoid double-mixing.
    private boolean audioAlreadyReplaced  = false;
    private int   mixFadeInMs        = 0;
    private int   mixFadeOutMs       = 0;
    private float mixPitchSemitones  = 0f;
    /** ✅ NEW: peak-normalize flag forwarded from ReelAudioMixerActivity */
    private boolean mixNormalize     = false;
    private int   musicStartMs       = 0;
    private int   musicEndMs         = 0;

    // Fix 4 & 6 & 8: duet metadata
    private boolean isDuet          = false;
    private String  duetOriginalId  = "";
    private String  duetOwnerUid    = "";
    private String  duetLabel       = "";
    private String  duetOriginalUrl = "";
    private int     duetLayoutMode  = 0;  // ✅ FIX GAP #6: save layout mode to Firebase
    private String  duetRootId     = null; // ✅ FIX v9 (CHAIN DUET): root reel of the chain
    private String  multiDuetSessionId = ""; // ✅ Multi-duet session
    private int     multiDuetSlot      = -1;
    // ✅ FIX (multi-duet gap #2/#3): true ONLY for the final merged composite upload
    // (started by MultiDuetActivity/host-resume after everyone has recorded).
    // false = this is just one participant's raw clip → must NOT be posted publicly,
    // it's only stored on the session for later compositing.
    private boolean multiDuetFinal     = false;

    // ✅ FIX GAP #2: stitch metadata
    private boolean isStitch           = false;
    private String  stitchOriginalId   = "";
    private String  stitchOriginalUrl  = "";
    private String  stitchOwnerUid     = "";

      // ── Duet Series ───────────────────────────────────────────────────────────
      private String seriesId      = null;
      private String seriesTitle   = null;
      private int    episodeNumber = 0;
    /** UIDs mentioned via @Name in the caption — notified after upload. */
    private java.util.ArrayList<String> mentionedUids = new java.util.ArrayList<>();
    // ✅ MULTI-COLLABORATOR: staged from ReelPostDetailsActivity (picked pre-upload,
    // since no reelId existed yet); invites are sent once the reel is saved below.
    private java.util.ArrayList<String> pendingCollabUids    = new java.util.ArrayList<>();
    private java.util.ArrayList<String> pendingCollabNames   = new java.util.ArrayList<>();
    private java.util.ArrayList<String> pendingCollabHandles = new java.util.ArrayList<>();
    private java.util.ArrayList<String> pendingCollabAvatars = new java.util.ArrayList<>();
    private ReelCaptionMentionController uploadMentionController;

  
    private int     stitchDurationSec  = 3;

    // ── Step wizard (priority-ordered: Media → Caption & Sound →
    //    Audience & Permissions → Details & Post) ────────────────────────────
    private android.widget.ViewFlipper stepFlipper;
    private androidx.core.widget.NestedScrollView stepScrollContainer;
    private TextView                   tvStepTitle;
    // ✅ Short step name shown in tv_step_name, next to the "Step X of Y" pill
    //    (all-caps via android:textAllCaps, so plain-case names are given here)
    //    — same pattern as Reel Editor / Reel Photo Editor.
    private TextView                   tvStepName;
    // Dot-stepper (reused from Add Status's stepper UI — see updateStepDots()
    // below) — replaces the old thin horizontal progress_step ProgressBar.
    private TextView[]                 stepDots;
    private View[]                     stepLines;
    private ImageView[]                stepRings;
    private android.animation.ObjectAnimator activeStepRingSpin;
    private com.google.android.material.button.MaterialButton btnStepBack;
    private Button                     btnStepNext;
    private int                        currentStep = 0;
    private static final String[] STEP_TITLES = {
            "Step 1 of 4 · Media",
            "Step 2 of 4 · Caption & Sound",
            "Step 3 of 4 · Audience & Permissions",
            "Step 4 of 4 · Details & Post"
    };
    /** Short step name shown in tv_step_name, next to the "Step X of Y" pill. */
    private static final String[] STEP_NAMES = {
            "Media",
            "Caption & Sound",
            "Audience & Permissions",
            "Details & Post"
    };

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
        setupStepWizard();

        // ── Instagram-style @mention in caption ──────────────────────────────
        try {
            String _myUid = com.callx.app.utils.FirebaseUtils.getCurrentUid();
            if (_myUid != null && !_myUid.isEmpty() && etCaption != null) {
                uploadMentionController = new ReelCaptionMentionController(etCaption, _myUid);
                uploadMentionController.attach();
            }
        } catch (Exception _ignored) {}

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
        if (btnAddCollaborators != null) btnAddCollaborators.setOnClickListener(v -> {
            Intent ci = new Intent(this, com.callx.app.social.CollabPostInviteActivity.class);
            ci.putExtra(com.callx.app.social.CollabPostInviteActivity.EXTRA_STAGING_MODE, true);
            startActivityForResult(ci, REQ_ADD_COLLABORATORS);
        });
        if (tvSeriesPickerUpload != null) tvSeriesPickerUpload.setOnClickListener(v -> openSeriesPickerFromUpload());

        // Media type toggle
        if (btnMediaTypeVideo  != null) btnMediaTypeVideo.setOnClickListener(v  -> switchToVideoMode());
        if (btnMediaTypePhotos != null) btnMediaTypePhotos.setOnClickListener(v -> switchToPhotoMode());
        if (btnPickPhotos      != null) btnPickPhotos.setOnClickListener(v      -> checkPermissionAndPickPhotos());

        // Add More Photos button — re-opens picker in append mode
        if (btnAddMorePhotos != null) {
            btnAddMorePhotos.setOnClickListener(v -> checkPermissionAndPickPhotos());
        }

        // Inject the full audio/music section into the layout programmatically
        injectAudioSection();

        // If launched from ReelEditorActivity, pre-load the video + text overlay
        handleEditorExtras();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AUDIO / MUSIC SECTION  (Instagram-style: Add → Change / Mix / Remove)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Programmatically injects a full-featured audio card into the upload form,
     * positioned just before the existing etMusic text field.
     *
     * Layout (inside a styled card):
     *   Header: "🎵 Music"
     *   [State A — no audio selected]
     *     → Big "+ Add Music / Sound" button → ReelTrendingAudioActivity
     *   [State B — audio track selected]
     *     → Music icon | Track name | Artist
     *     → [Change] [Mix Audio] [Remove] buttons
     */
    private void injectAudioSection() {
        // Locate the TextInputLayout wrapping etMusic so we can insert just before it
        if (etMusic == null) return;
        android.view.ViewGroup tilMusic = (android.view.ViewGroup) etMusic.getParent(); // TextInputLayout
        if (tilMusic == null) return;
        android.view.ViewGroup rootLl = (android.view.ViewGroup) tilMusic.getParent(); // root LinearLayout
        if (rootLl == null) return;

        int insertIdx = -1;
        for (int i = 0; i < rootLl.getChildCount(); i++) {
            if (rootLl.getChildAt(i) == tilMusic) { insertIdx = i; break; }
        }
        if (insertIdx < 0) return;

        float dp = getResources().getDisplayMetrics().density;
        int dp4  = (int)(4  * dp);
        int dp8  = (int)(8  * dp);
        int dp12 = (int)(12 * dp);
        int dp14 = (int)(14 * dp);
        int dp16 = (int)(16 * dp);

        // ── Outer card ──────────────────────────────────────────────────────
        // ✅ FIX (light/dark mode): was hardcoded dark navy (0xFF1A1A2E) in
        // both themes — tokenized to trim_subcard_bg/trim_chip_stroke, same
        // day/night colors used by the rest of this screen's card panel.
        layoutAudioCard = new android.widget.LinearLayout(this);
        layoutAudioCard.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(androidx.core.content.ContextCompat.getColor(this,
                com.callx.app.core.R.color.trim_subcard_bg));
        cardBg.setCornerRadius(14 * dp);
        cardBg.setStroke((int)(1 * dp), androidx.core.content.ContextCompat.getColor(this,
                com.callx.app.core.R.color.trim_chip_stroke));
        layoutAudioCard.setBackground(cardBg);
        layoutAudioCard.setPadding(dp14, dp12, dp14, dp12);
        android.widget.LinearLayout.LayoutParams cardLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp12);
        layoutAudioCard.setLayoutParams(cardLp);

        // ── Section header ──────────────────────────────────────────────────
        android.widget.TextView tvHeader = new android.widget.TextView(this);
        tvHeader.setText("🎵  Music");
        tvHeader.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                com.callx.app.core.R.color.trim_text_primary));
        tvHeader.setTextSize(13f);
        tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        tvHeader.setPadding(0, 0, 0, dp8);
        layoutAudioCard.addView(tvHeader);

        // ══ STATE A — no audio selected ═════════════════════════════════════
        layoutAudioEmpty = new android.widget.LinearLayout(this);
        layoutAudioEmpty.setOrientation(android.widget.LinearLayout.VERTICAL);

        android.widget.Button btnAddMusic = new android.widget.Button(this);
        btnAddMusic.setText("+ Add Music / Sound");
        btnAddMusic.setTextColor(0xFFFFFFFF);
        btnAddMusic.setTextSize(14f);
        btnAddMusic.setAllCaps(false);
        android.graphics.drawable.GradientDrawable addBtnBg = new android.graphics.drawable.GradientDrawable();
        addBtnBg.setColor(0xFF5B5BF6);         // brand_primary purple
        addBtnBg.setCornerRadius(24 * dp);
        btnAddMusic.setBackground(addBtnBg);
        android.widget.LinearLayout.LayoutParams addBtnLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int)(48 * dp));
        btnAddMusic.setLayoutParams(addBtnLp);
        btnAddMusic.setOnClickListener(v -> openTrendingAudioPicker());
        layoutAudioEmpty.addView(btnAddMusic);

        layoutAudioCard.addView(layoutAudioEmpty);

        // ══ STATE B — audio track selected ══════════════════════════════════
        layoutAudioTrack = new android.widget.LinearLayout(this);
        layoutAudioTrack.setOrientation(android.widget.LinearLayout.VERTICAL);
        layoutAudioTrack.setVisibility(android.view.View.GONE);

        // Track info row: music note + name/artist stack
        android.widget.LinearLayout trackInfoRow = new android.widget.LinearLayout(this);
        trackInfoRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        trackInfoRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.TextView tvNote = new android.widget.TextView(this);
        tvNote.setText("♪");
        tvNote.setTextColor(0xFF7B7BFF);
        tvNote.setTextSize(28f);
        tvNote.setPadding(0, 0, dp12, 0);
        trackInfoRow.addView(tvNote);

        android.widget.LinearLayout trackTextCol = new android.widget.LinearLayout(this);
        trackTextCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams colLp =
            new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        trackTextCol.setLayoutParams(colLp);

        tvAudioTrackName = new android.widget.TextView(this);
        tvAudioTrackName.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                com.callx.app.core.R.color.trim_text_primary));
        tvAudioTrackName.setTextSize(14f);
        tvAudioTrackName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAudioTrackName.setMaxLines(1);
        tvAudioTrackName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trackTextCol.addView(tvAudioTrackName);

        tvAudioArtist = new android.widget.TextView(this);
        tvAudioArtist.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                com.callx.app.core.R.color.trim_text_secondary));
        tvAudioArtist.setTextSize(12f);
        tvAudioArtist.setMaxLines(1);
        tvAudioArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trackTextCol.addView(tvAudioArtist);

        trackInfoRow.addView(trackTextCol);

        // Tap the track row → open SoundDetailActivity
        trackInfoRow.setClickable(true);
        trackInfoRow.setFocusable(true);
        trackInfoRow.setOnClickListener(v -> openSoundDetailForSelected());

        layoutAudioTrack.addView(trackInfoRow);

        // Divider
        android.view.View divider = new android.view.View(this);
        divider.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,
                com.callx.app.core.R.color.trim_divider));
        android.widget.LinearLayout.LayoutParams divLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int)(1 * dp));
        divLp.setMargins(0, dp8, 0, dp8);
        divider.setLayoutParams(divLp);
        layoutAudioTrack.addView(divider);

        // Action buttons row: [Change] [Mix Audio] [Remove]
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.Button btnChange = makeAudioBtn("Change", 0xFF5B5BF6);
        btnChange.setOnClickListener(v -> openTrendingAudioPicker());

        android.widget.Button btnMix = makeAudioBtn("🎛 Mix", 0xFF2E7D32);
        btnMix.setOnClickListener(v -> openAudioMixerFromUpload());

        android.widget.Button btnRemove = makeAudioBtn("Remove", 0xFFB71C1C);
        btnRemove.setOnClickListener(v -> clearSelectedAudio());

        android.widget.LinearLayout.LayoutParams btnLp =
            new android.widget.LinearLayout.LayoutParams(
                0, (int)(38 * dp), 1f);
        btnLp.setMargins(0, 0, dp4, 0);
        android.widget.LinearLayout.LayoutParams btnLpLast =
            new android.widget.LinearLayout.LayoutParams(
                0, (int)(38 * dp), 1f);

        btnRow.addView(btnChange, btnLp);
        android.widget.LinearLayout.LayoutParams mixLp =
            new android.widget.LinearLayout.LayoutParams(0, (int)(38 * dp), 1f);
        mixLp.setMargins(0, 0, dp4, 0);
        btnRow.addView(btnMix, mixLp);
        btnRow.addView(btnRemove, btnLpLast);
        layoutAudioTrack.addView(btnRow);

        layoutAudioCard.addView(layoutAudioTrack);

        // Insert the card into the parent LinearLayout just before etMusic's TIL
        rootLl.addView(layoutAudioCard, insertIdx);
    }

    /** Builds a small rounded button for the audio action row. */
    private android.widget.Button makeAudioBtn(String label, int color) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        float dp = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(8 * dp);
        b.setBackground(bg);
        return b;
    }

    /**
     * Updates the audio card UI to reflect the current state of
     * preSelectedSoundUrl / currentSoundTitle / currentSoundArtist.
     * Call after any audio state change.
     */
    private void updateAudioUI() {
        if (layoutAudioEmpty == null || layoutAudioTrack == null) return;
        boolean hasAudio = preSelectedSoundUrl != null && !preSelectedSoundUrl.isEmpty();
        layoutAudioEmpty.setVisibility(hasAudio ? android.view.View.GONE  : android.view.View.VISIBLE);
        layoutAudioTrack.setVisibility(hasAudio ? android.view.View.VISIBLE : android.view.View.GONE);
        if (hasAudio) {
            if (tvAudioTrackName != null) {
                tvAudioTrackName.setText(
                    currentSoundTitle != null && !currentSoundTitle.isEmpty()
                        ? currentSoundTitle : "Unknown Track");
            }
            if (tvAudioArtist != null) {
                tvAudioArtist.setText(
                    currentSoundArtist != null && !currentSoundArtist.isEmpty()
                        ? currentSoundArtist : "—");
            }
            // Keep etMusic in sync for the upload metadata
            if (etMusic != null && (etMusic.getText() == null
                    || etMusic.getText().toString().isEmpty())) {
                etMusic.setText(currentSoundTitle);
            }
        }
    }

    /** Opens ReelTrendingAudioActivity so the user can pick or change the sound. */
    private void openTrendingAudioPicker() {
        startActivityForResult(
            new Intent(this, com.callx.app.music.ReelTrendingAudioActivity.class),
            REQ_TRENDING_AUDIO);
    }

    /**
     * Opens ReelAudioMixerActivity so the user can balance original audio vs
     * background music volumes and add a voiceover — all from the upload screen.
     * Only available for video upload (requires a local video file to mix).
     */
    private void openAudioMixerFromUpload() {
        if (isPhotoMode) {
            // Photo slideshows don't mix audio at upload time — the track is stored as metadata.
            Toast.makeText(this, "Audio mixing is applied at playback for photo reels",
                Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedUri == null) {
            Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        // Determine if we have a file path (local file) or content URI
        boolean isFilePath = false;
        String  videoPath  = null;
        try {
            String scheme = selectedUri.getScheme();
            if ("file".equals(scheme)) {
                isFilePath = true;
                videoPath  = selectedUri.getPath();
            } else {
                // content:// URI — pass as URI string; mixer will use isFilePath=false
                videoPath = selectedUri.toString();
            }
        } catch (Exception e) {
            videoPath = selectedUri.toString();
        }

        Intent i = new Intent(this, com.callx.app.editor.ReelAudioMixerActivity.class);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_VIDEO_URI,    videoPath);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_IS_FILE_PATH, isFilePath);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_MUSIC_URL,    preSelectedSoundUrl);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_MUSIC_TITLE,  currentSoundTitle);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_MUSIC_ARTIST, currentSoundArtist);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_SOUND_ID,     preSelectedSoundId);
        startActivityForResult(i, REQ_AUDIO_MIXER_UPLOAD);
    }

    /**
     * Opens ReelAudioMixerActivity right after ReelPhotoMusicTrimActivity ("Use" tapped),
     * for BOTH video and photo reel modes — unlike {@link #openAudioMixerFromUpload()}
     * (the standalone "Mix" button), which only supports video. Photo mode has no local
     * video file, so it's opened without a video preview; the mixer already handles a
     * missing video URI gracefully.
     */
    private void openAudioMixerAfterTrim() {
        boolean isFilePath = false;
        String  videoPath  = null;
        // ✅ FIX: photo mode has no video, so the mixer's preview card was blank.
        // Pass the cover photo (falls back to the first photo) so the mixer can
        // show a static image preview instead.
        String  photoPath  = null;
        if (!isPhotoMode && selectedUri != null) {
            try {
                String scheme = selectedUri.getScheme();
                if ("file".equals(scheme)) {
                    isFilePath = true;
                    videoPath  = selectedUri.getPath();
                } else {
                    videoPath = selectedUri.toString();
                }
            } catch (Exception e) {
                videoPath = selectedUri.toString();
            }
        } else if (isPhotoMode && !selectedPhotoUris.isEmpty()) {
            int idx = (coverPhotoIndex >= 0 && coverPhotoIndex < selectedPhotoUris.size())
                ? coverPhotoIndex : 0;
            photoPath = selectedPhotoUris.get(idx).toString();
        }

        Intent i = new Intent(this, com.callx.app.editor.ReelAudioMixerActivity.class);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_VIDEO_URI,    videoPath);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_IS_FILE_PATH, isFilePath);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_PHOTO_URI,    photoPath);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_MUSIC_URL,    preSelectedSoundUrl);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_MUSIC_TITLE,  currentSoundTitle);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_MUSIC_ARTIST, currentSoundArtist);
        i.putExtra(com.callx.app.editor.ReelAudioMixerActivity.EXTRA_SOUND_ID,     preSelectedSoundId);
        startActivityForResult(i, REQ_AUDIO_MIXER_AFTER_TRIM);
    }

    /** Reads ReelAudioMixerActivity's result extras into the mix state fields + UI. Shared by
     *  both mixer entry points (standalone "Mix" button and the post-trim auto-open). */
    private void applyMixerResult(Intent data) {
        mixOrigVol        = data.getFloatExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_ORIG_VOL,       1.0f);
        mixMusicVol       = data.getFloatExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_MUSIC_VOL,      0.8f);
        mixVoiceoverVol   = data.getFloatExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_VOICEOVER_VOL,  1.0f);
        mixFadeInMs       = data.getIntExtra  (com.callx.app.editor.ReelAudioMixerActivity.RESULT_FADE_IN_MS,     0);
        mixFadeOutMs      = data.getIntExtra  (com.callx.app.editor.ReelAudioMixerActivity.RESULT_FADE_OUT_MS,    0);
        mixPitchSemitones = data.getFloatExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_PITCH_SEMITONES,0f);
        mixNormalize      = data.getBooleanExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_NORMALIZE,    false);
        String vPath      = data.getStringExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_VOICEOVER_PATH);
        if (vPath != null) mixVoiceoverPath = vPath;

        // If user changed the track inside the mixer, propagate it
        String newUrl    = data.getStringExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_MUSIC_URL);
        String newId     = data.getStringExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_MUSIC_ID);
        String newTitle  = data.getStringExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_MUSIC_TITLE);
        String newArtist = data.getStringExtra(com.callx.app.editor.ReelAudioMixerActivity.RESULT_MUSIC_ARTIST);
        if (newUrl != null && !newUrl.isEmpty()) {
            preSelectedSoundUrl = newUrl;
            if (newId     != null && !newId.isEmpty())     preSelectedSoundId  = newId;
            if (newTitle  != null && !newTitle.isEmpty())  currentSoundTitle   = newTitle;
            if (newArtist != null && !newArtist.isEmpty()) currentSoundArtist  = newArtist;
            if (etMusic != null) etMusic.setText(currentSoundTitle);
        }
        updateAudioUI();
        Toast.makeText(this, "Mix settings saved ✓", Toast.LENGTH_SHORT).show();
    }

    /** Opens SoundDetailActivity for the currently selected sound. */
    private void openSoundDetailForSelected() {
        if (preSelectedSoundUrl == null || preSelectedSoundUrl.isEmpty()) return;
        Intent i = new Intent(this, com.callx.app.music.SoundDetailActivity.class);
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_ID,    preSelectedSoundId);
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_TITLE, currentSoundTitle);
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_ARTIST,      currentSoundArtist);
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_URL,   preSelectedSoundUrl);
        startActivityForResult(i, REQ_SOUND_DETAIL_UPLOAD);
    }

    /** Clears the selected audio track and resets the audio UI to the empty state. */
    private void clearSelectedAudio() {
        preSelectedSoundId    = "";
        preSelectedSoundUrl   = "";
        preSelectedSoundCover = "";
        currentSoundTitle     = "";
        currentSoundArtist    = "";
        mixMusicVol           = 0.8f;
        mixFadeInMs           = 0;
        mixFadeOutMs          = 0;
        musicStartMs          = 0;
        musicEndMs            = 0;
        if (etMusic != null) etMusic.setText("");
        updateAudioUI();
        Toast.makeText(this, "Music removed", Toast.LENGTH_SHORT).show();
    }

    private void bindViews() {
        playerPreview        = findViewById(R.id.player_preview);
        ivThumbPreview       = findViewById(R.id.iv_thumb_preview);
        layoutPickVideo      = findViewById(R.id.layout_pick_video);
        cardVideoPreview     = findViewById(R.id.card_video_preview);
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
        btnAddCollaborators  = findViewById(R.id.btn_add_collaborators);
        tvCollabSummary      = findViewById(R.id.tv_collab_summary);
        btnPrivacySettings   = findViewById(R.id.btn_privacy_settings);
        btnSchedule          = findViewById(R.id.btn_schedule);

        // ✅ FIX: previously picking a different quality chip after compression had
        // already started (or finished) with the default Standard tier did nothing —
        // getSelectedQuality() was only ever read once, at the moment compressVideo()
        // was first called. Now changing the chip re-runs compression with the newly
        // chosen quality, so the person actually gets to choose before posting.
        chipQuality.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (selectedUri == null) return;
            if (compressionInProgress || trimBakeInProgress) {
                Toast.makeText(this, "Video is still being processed…", Toast.LENGTH_SHORT).show();
                return;
            }
            compressedResult = null;
            tvCompressionSavings.setVisibility(View.GONE);
            startCompressionPipeline(selectedUri, lastVideoIsFilePath);
        });
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
        rgPhotoDuration         = findViewById(R.id.rg_photo_duration);
        rgTransition            = findViewById(R.id.rg_transition);
        rgPhotoFilter           = findViewById(R.id.rg_photo_filter);
        swAutoLoop              = findViewById(R.id.sw_auto_loop);
        btnAddMorePhotos        = findViewById(R.id.btn_add_more_photos);
        layoutPhotoEmptyState   = findViewById(R.id.layout_photo_empty_state);
    }

    /**
     * ✅ NEW: Wires up the priority-ordered step wizard so each option group
     * (Media → Caption & Sound → Audience & Permissions → Details & Post)
     * is shown as its own screen instead of everything on one page.
     * Every underlying view/field/click-listener from before is untouched —
     * this only controls which step of the ViewFlipper is visible.
     */
    private void setupStepWizard() {
        stepFlipper  = findViewById(R.id.step_flipper);
        stepScrollContainer = findViewById(R.id.step_scroll_container);
        tvStepTitle  = findViewById(R.id.tv_step_title);
        tvStepName   = findViewById(R.id.tv_step_name);
        stepDots  = new TextView[] {
                findViewById(R.id.step_dot_1), findViewById(R.id.step_dot_2),
                findViewById(R.id.step_dot_3), findViewById(R.id.step_dot_4)
        };
        // ✅ NEW: vibrant-green spinning ring shown behind whichever step dot
        // is currently in use (see updateStepDots()).
        stepRings = new ImageView[] {
                findViewById(R.id.step_ring_1), findViewById(R.id.step_ring_2),
                findViewById(R.id.step_ring_3), findViewById(R.id.step_ring_4)
        };
        stepLines = new View[] {
                findViewById(R.id.step_line_1), findViewById(R.id.step_line_2),
                findViewById(R.id.step_line_3)
        };
        btnStepBack  = findViewById(R.id.btn_step_back);
        btnStepNext  = findViewById(R.id.btn_step_next);

        if (stepFlipper == null) return; // layout not the wizard version — no-op safety

        if (btnStepBack != null) {
            btnStepBack.setOnClickListener(v -> goToStep(currentStep - 1));
        }
        if (btnStepNext != null) {
            btnStepNext.setOnClickListener(v -> {
                if (currentStep == 0 && !canLeaveMediaStep()) return;
                goToStep(currentStep + 1);
            });
        }
        updateStepUi();
    }

    /** Validates the Media step (Step 1) has a usable selection before advancing. */
    private boolean canLeaveMediaStep() {
        if (isPhotoMode) {
            if (selectedPhotoUris.isEmpty()) {
                Toast.makeText(this, "Please add at least one photo first", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }
        if (selectedUri == null) {
            Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (compressionInProgress || trimBakeInProgress) {
            Toast.makeText(this, "Video is still being processed…", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void goToStep(int step) {
        if (stepFlipper == null) return;
        int total = STEP_TITLES.length;
        if (step < 0 || step >= total) return;

        currentStep = step;
        // ViewFlipper doesn't support jumping directly to an index with a
        // direction-aware animation out of the box, so just select it.
        stepFlipper.setDisplayedChild(currentStep);
        updateStepUi();

        // Scroll the OUTER scroll container back to the top — not the step's
        // inner view (which isn't itself scrollable; the NestedScrollView
        // wrapping the whole ViewFlipper is the real scroll owner, and its
        // offset otherwise carries over from whichever step was scrolled
        // before, hiding the new step's top options above the viewport).
        if (stepScrollContainer != null) stepScrollContainer.scrollTo(0, 0);
    }

    private void updateStepUi() {
        // Pill (tv_step_title) shows just "Step X of Y"; the step name
        // (Media / Caption & Sound / etc.) is shown separately in tv_step_name,
        // uppercase, next to the pill — same pattern as Reel Editor / Reel Photo
        // Editor. STEP_TITLES[] left as-is since .length is still used for
        // step-count bounds checks elsewhere; STEP_NAMES holds the plain-case
        // names for the label.
        if (tvStepTitle != null) {
            tvStepTitle.setText(getString(R.string.editor_step_pill_format,
                    currentStep + 1, STEP_TITLES.length));
        }
        if (tvStepName != null && currentStep < STEP_NAMES.length) {
            tvStepName.setText(STEP_NAMES[currentStep]);
        }
        updateStepDots();
        if (btnStepBack != null) {
            btnStepBack.setVisibility(currentStep == 0 ? View.INVISIBLE : View.VISIBLE);
        }
        if (btnStepNext != null) {
            // Last step (Details & Post) already has its own Post/Save-draft
            // actions inside the step, so the shared Next button is hidden there.
            boolean isLastStep = currentStep == STEP_TITLES.length - 1;
            btnStepNext.setVisibility(isLastStep ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Reused from NewStatusActivity's updateWizardProgress() — same dot
     * stepper visuals (numbered circle turns brand-gradient once its step is
     * reached/passed, connecting line to its right fills solid, everything
     * ahead stays the neutral "not yet" grey) — just sized for this wizard's
     * 4 steps instead of Status's 3.
     */
    private void updateStepDots() {
        if (stepDots == null) return;
        for (int i = 0; i < stepDots.length; i++) {
            if (stepDots[i] == null) continue;
            boolean active = i <= currentStep;
            stepDots[i].setBackgroundResource(active
                    ? com.callx.app.core.R.drawable.bg_trim_gradient_button
                    : com.callx.app.core.R.drawable.bg_trim_circle_btn);
            stepDots[i].setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    active ? com.callx.app.core.R.color.white
                           : com.callx.app.core.R.color.trim_text_secondary));
        }
        if (stepLines == null) return;
        for (int i = 0; i < stepLines.length; i++) {
            if (stepLines[i] == null) continue;
            stepLines[i].setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,
                    currentStep >= i + 1
                            ? com.callx.app.core.R.color.trim_gradient_start
                            : com.callx.app.core.R.color.trim_divider));
        }
        updateActiveStepRing();
    }

    /**
     * ✅ NEW: spins a vibrant-green ring behind whichever step dot is the one
     * currently in use (currentStep) — not the "passed" ones, just the live
     * one — so it's obvious at a glance which step the person is on.
     */
    private void updateActiveStepRing() {
        if (stepRings == null) return;
        if (activeStepRingSpin != null) {
            activeStepRingSpin.cancel();
            activeStepRingSpin = null;
        }
        for (int i = 0; i < stepRings.length; i++) {
            if (stepRings[i] == null) continue;
            if (i == currentStep) {
                stepRings[i].setVisibility(View.VISIBLE);
                stepRings[i].setRotation(0f);
                activeStepRingSpin = android.animation.ObjectAnimator.ofFloat(
                        stepRings[i], View.ROTATION, 0f, 360f);
                activeStepRingSpin.setDuration(1400);
                activeStepRingSpin.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
                activeStepRingSpin.setInterpolator(new android.view.animation.LinearInterpolator());
                activeStepRingSpin.start();
            } else {
                stepRings[i].setVisibility(View.GONE);
            }
        }
    }

    /** Physical/gesture back navigates one wizard step at a time before exiting. */
    @Override
    public void onBackPressed() {
        if (stepFlipper != null && currentStep > 0) {
            goToStep(currentStep - 1);
            return;
        }
        super.onBackPressed();
    }

    private void setupChipDefaults() {
        Chip chipStandard = findViewById(R.id.chip_standard);
        if (chipStandard != null) chipStandard.setChecked(true);
        Chip chipEveryone = findViewById(R.id.chip_everyone);
        if (chipEveryone != null) chipEveryone.setChecked(true);

        // ── Photo chip groups: programmatic highlight (guaranteed to work) ──
        // Duration: default 3s
        refreshPhotoChipGroup(rgPhotoDuration, R.id.rb_dur_3);
        // Transition: default fade
        refreshPhotoChipGroup(rgTransition, R.id.rb_tr_fade);
        // Filter: default normal
        refreshPhotoChipGroup(rgPhotoFilter, R.id.rb_filter_normal);

        // Re-apply on every selection change
        if (rgPhotoDuration != null) {
            rgPhotoDuration.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.rb_dur_2)       selectedDurationMs = 2000;
                else if (checkedId == R.id.rb_dur_5)  selectedDurationMs = 5000;
                else if (checkedId == R.id.rb_dur_7)  selectedDurationMs = 7000;
                else if (checkedId == R.id.rb_dur_10) selectedDurationMs = 10000;
                else                                   selectedDurationMs = 3000;
                refreshPhotoChipGroup(group, checkedId);
            });
        }
        if (rgTransition != null) {
            rgTransition.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.rb_tr_slide)      selectedTransitionType = "slide";
                else if (checkedId == R.id.rb_tr_zoom)  selectedTransitionType = "zoom";
                else if (checkedId == R.id.rb_tr_none)  selectedTransitionType = "none";
                else                                     selectedTransitionType = "fade";
                refreshPhotoChipGroup(group, checkedId);
            });
        }
        if (rgPhotoFilter != null) {
            rgPhotoFilter.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.rb_filter_warm)       selectedFilter = "warm";
                else if (checkedId == R.id.rb_filter_cool)  selectedFilter = "cool";
                else if (checkedId == R.id.rb_filter_vivid) selectedFilter = "vivid";
                else if (checkedId == R.id.rb_filter_bw)    selectedFilter = "bw";
                else                                         selectedFilter = "normal";
                refreshPhotoChipGroup(group, checkedId);
            });
        }
    }

    /**
     * Programmatically updates background + text color of every RadioButton
     * in a photo chip group so the selected one is visually highlighted.
     * This is more reliable than XML drawable selectors on all API levels.
     */
    private void refreshPhotoChipGroup(android.widget.RadioGroup group, int checkedId) {
        if (group == null) return;
        int SELECTED_BG   = 0xFF5B5BF6; // brand_primary
        int UNSELECTED_BG = 0x1A5B5BF6; // 10% brand_primary
        int SELECTED_TEXT = android.graphics.Color.WHITE;
        // ✅ FIX (light/dark mode): was hardcoded 0xFF0F172A (light-mode
        // text_primary only) — invisible against the 10%-brand-tint wash
        // once that wash sits on a dark surface in night mode.
        int UNSELECTED_TEXT = androidx.core.content.ContextCompat.getColor(this,
                com.callx.app.core.R.color.trim_text_primary);

        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (!(child instanceof android.widget.RadioButton)) continue;
            android.widget.RadioButton rb = (android.widget.RadioButton) child;
            boolean selected = (rb.getId() == checkedId);

            // Background
            android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(60f); // pill
            bg.setColor(selected ? SELECTED_BG : UNSELECTED_BG);
            if (!selected) {
                bg.setStroke(2, 0x405B5BF6);
            }
            rb.setBackground(bg);
            rb.setTextColor(selected ? SELECTED_TEXT : UNSELECTED_TEXT);
        }
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
        String soundId     = i.getStringExtra(EXTRA_SOUND_ID);
        String soundTitle  = i.getStringExtra(EXTRA_SOUND_TITLE);
        String soundUrl    = i.getStringExtra(EXTRA_SOUND_URL);
        String soundCover  = i.getStringExtra(EXTRA_SOUND_COVER);
        String soundArtist = i.getStringExtra(EXTRA_SOUND_ARTIST);
        if (soundId     != null && !soundId.isEmpty())     preSelectedSoundId     = soundId;
        if (soundUrl    != null && !soundUrl.isEmpty())    preSelectedSoundUrl    = soundUrl;
        if (soundCover  != null && !soundCover.isEmpty())  preSelectedSoundCover  = soundCover;
        if (soundArtist != null && !soundArtist.isEmpty()) currentSoundArtist     = soundArtist;
        if (soundTitle != null && !soundTitle.isEmpty()) {
            currentSoundTitle = soundTitle;
            if (etMusic != null && (etMusic.getText() == null || etMusic.getText().toString().isEmpty())) {
                etMusic.setText(soundTitle);
            }
        }

        // Audio mix settings from ReelEditorActivity (camera flow only)
        mixOrigVol       = i.getFloatExtra("mix_orig_vol",        1.0f);
        mixMusicVol      = i.getFloatExtra("mix_music_vol",       0.8f);
        mixVoiceoverPath = i.getStringExtra("mix_voiceover_path");
        mixVoiceoverVol  = i.getFloatExtra("mix_voiceover_vol",   1.0f);
        if (mixVoiceoverPath == null) mixVoiceoverPath = "";

        // ✅ NEW: If ReelCameraActivity already replaced mic audio, skip mixing at upload time.
        audioAlreadyReplaced  = i.getBooleanExtra("audio_already_replaced", false);
        mixFadeInMs       = i.getIntExtra("mix_fade_in_ms",       0);
        mixFadeOutMs      = i.getIntExtra("mix_fade_out_ms",      0);
        mixPitchSemitones = i.getFloatExtra("mix_pitch_semitones", 0f);
        mixNormalize      = i.getBooleanExtra("mix_normalize",     false);
        musicStartMs      = i.getIntExtra("music_start_ms",       0);
        musicEndMs        = i.getIntExtra("music_end_ms",          0);

        // ── NEW: Camera-captured single photo (Reels camera "+" → Photo mode) ──
        // Reuses the exact same photo pipeline (selectedPhotoUris, per-photo
        // metadata lists, ReelPhotoEditorActivity via REQ_PHOTO_EDIT) that the
        // gallery "Pick Photos" flow already uses, so every photo editing tool
        // (filters, effects, caption, stickers, rotation, adjust, Ken Burns,
        // duration) works identically for camera-shot photos.
        String cameraPhotoPath = i.getStringExtra(EXTRA_CAMERA_PHOTO_PATH);
        if (cameraPhotoPath != null && !cameraPhotoPath.isEmpty()) {
            switchToPhotoMode();
            // ✅ FIX: cameraPhotoPath can be either a real filesystem path (actual
            // camera capture) OR a content:// gallery Uri string (photo picked from
            // the camera screen's recent-media sheet — CameraMediaSheetController
            // forwards uri.toString()). Treating a content:// string as a raw file
            // path via Uri.fromFile(new File(...)) produces an invalid
            // "file:///content:/..." Uri that fails to resolve, so the photo never
            // shows up on the next (photo editor) screen. Detect and parse each
            // form correctly instead of always assuming a file path.
            Uri photoUri = (cameraPhotoPath.startsWith("content://")
                    || cameraPhotoPath.startsWith("file://"))
                    ? Uri.parse(cameraPhotoPath)
                    : Uri.fromFile(new java.io.File(cameraPhotoPath));
            selectedPhotoUris.add(photoUri);
            addPhotoPreviewThumbnail(photoUri, 0);
            updatePhotoCountLabel();
            btnPostReel.setEnabled(true);
            // Immediately open the full photo editor so the camera → edit
            // handoff feels the same as the video flow's ReelEditorActivity.
            // If the user had already picked a track on the camera screen, pass
            // it so the editor auto-attaches a Music sticker on open.
            photoEditIndex = 0;
            String camSoundId     = i.getStringExtra("selected_sound_id");
            String camSoundTitle  = i.getStringExtra("selected_sound_title");
            String camSoundArtist = i.getStringExtra("selected_sound_artist");
            String camSoundUrl    = i.getStringExtra("selected_sound_url");
            String camSoundCover  = i.getStringExtra("selected_sound_cover");
            boolean hasPresetSound = camSoundTitle != null && !camSoundTitle.isEmpty();
            if (hasPresetSound) {
                com.callx.app.editor.ReelPhotoEditorActivity.startWithSound(
                    this, photoUri.toString(), 0, 1,
                    "", "", "", "", "", "", 0, 0f,
                    camSoundId   != null ? camSoundId   : "",
                    camSoundTitle,
                    camSoundArtist != null ? camSoundArtist : "",
                    camSoundUrl  != null ? camSoundUrl  : "",
                    camSoundCover!= null ? camSoundCover: "",
                    REQ_PHOTO_EDIT);
            } else {
                com.callx.app.editor.ReelPhotoEditorActivity.start(
                    this, photoUri.toString(), 0, 1,
                    "", "", "", "", "", "", 0, 0f, REQ_PHOTO_EDIT);
            }
            return;
        }

        // ── If no video URI, stop here (gallery flow: user picks video later) ──
        String videoUriStr = i.getStringExtra(EXTRA_VIDEO_URI);
        if (videoUriStr == null || videoUriStr.isEmpty()) return;

        boolean isFilePath = i.getBooleanExtra(EXTRA_IS_FILE_PATH, true);
        Uri uri = isFilePath
            ? android.net.Uri.fromFile(new java.io.File(videoUriStr))
            : Uri.parse(videoUriStr);
        selectedUri           = uri;
        lastVideoIsFilePath   = isFilePath;
        compressedResult      = null;
        compressionInProgress = false;

        // ✅ FIX: read the trim range instead of leaving it unused — see
        // EXTRA_TRIM_ALREADY_BAKED for why this matters.
        pendingTrimStartMs      = i.getLongExtra(EXTRA_TRIM_START, 0L);
        pendingTrimEndMs        = i.getLongExtra(EXTRA_TRIM_END, 0L);
        pendingTrimAlreadyBaked = i.getBooleanExtra(EXTRA_TRIM_ALREADY_BAKED, false);

        showVideoPreview(uri);
        startCompressionPipeline(uri, isFilePath);

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

        // Interactive stickers (music/poll/quiz/countdown/slider/question/…) from
        // ReelEditorActivity's full sticker sheet — kept live, not baked into pixels.
        String stickerJsonExtra = i.getStringExtra("sticker_json");
        if (stickerJsonExtra != null && !stickerJsonExtra.isEmpty()) {
            videoStickerJson = stickerJsonExtra;
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
        // ✅ Multi-duet session
        String mdsId = i.getStringExtra("multi_duet_session_id");
        if (mdsId != null && !mdsId.isEmpty()) {
            multiDuetSessionId = mdsId;
            multiDuetSlot      = i.getIntExtra("multi_duet_slot", -1);
            multiDuetFinal     = i.getBooleanExtra("multi_duet_final", false);
        }

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
        // ── @Mention UIDs (Instagram-style caption mentions) ─────────────────────
        java.util.ArrayList<String> mentionUidsFromIntent =
                i.getStringArrayListExtra(ReelPostDetailsActivity.RESULT_MENTION_UIDS);
        if (mentionUidsFromIntent != null && !mentionUidsFromIntent.isEmpty()) {
            mentionedUids.addAll(mentionUidsFromIntent);
        }

        // ── ✅ MULTI-COLLABORATOR: staged collaborators picked pre-upload ────────
        java.util.ArrayList<String> collabUidsFromIntent =
                i.getStringArrayListExtra(ReelPostDetailsActivity.RESULT_COLLAB_UIDS);
        if (collabUidsFromIntent != null && !collabUidsFromIntent.isEmpty()) {
            pendingCollabUids.addAll(collabUidsFromIntent);
            java.util.ArrayList<String> n = i.getStringArrayListExtra(ReelPostDetailsActivity.RESULT_COLLAB_NAMES);
            java.util.ArrayList<String> h = i.getStringArrayListExtra(ReelPostDetailsActivity.RESULT_COLLAB_HANDLES);
            java.util.ArrayList<String> av = i.getStringArrayListExtra(ReelPostDetailsActivity.RESULT_COLLAB_AVATARS);
            if (n  != null) pendingCollabNames.addAll(n);
            if (h  != null) pendingCollabHandles.addAll(h);
            if (av != null) pendingCollabAvatars.addAll(av);
        }

        if (sId2 != null && !sId2.isEmpty()) {
            seriesId      = sId2;
            seriesTitle   = sTitle != null ? sTitle : "";
            episodeNumber = sEp;
        }

        // Reflect any pre-selected sound in the audio card UI
        updateAudioUI();
    }

    // ── Media Type Toggle ─────────────────────────────────────────────────

    private void switchToVideoMode() {
        isPhotoMode = false;
        // ✅ FIX: show the whole video card again, not just the inner hint —
        // it's the one that got hidden in switchToPhotoMode() below.
        if (cardVideoPreview  != null) cardVideoPreview.setVisibility(View.VISIBLE);
        if (layoutPickVideo   != null) layoutPickVideo.setVisibility(View.VISIBLE);
        if (cardPhotos        != null) cardPhotos.setVisibility(View.GONE);
        if (btnMediaTypeVideo != null) {
            btnMediaTypeVideo.setBackgroundResource(com.callx.app.reels.R.color.brand_primary);
            btnMediaTypeVideo.setTextColor(android.graphics.Color.WHITE);
        }
        if (btnMediaTypePhotos != null) {
            btnMediaTypePhotos.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnMediaTypePhotos.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    com.callx.app.core.R.color.trim_text_secondary));
        }
    }

    private void switchToPhotoMode() {
        isPhotoMode = true;
        // ✅ FIX: hide the ENTIRE video-preview card — previously only the inner
        // "Tap to select video" hint (layoutPickVideo) was hidden, but iv_thumb_preview
        // (same size, match_parent) has its own click listener and stayed visible +
        // clickable in the same spot, so taps below the photo thumbnails were landing
        // on it and opening the video picker instead.
        if (cardVideoPreview   != null) cardVideoPreview.setVisibility(View.GONE);
        if (layoutPickVideo    != null) layoutPickVideo.setVisibility(View.GONE);
        if (cardPhotos         != null) cardPhotos.setVisibility(View.VISIBLE);
        if (playerPreview      != null) playerPreview.setVisibility(View.GONE);
        if (btnMediaTypePhotos != null) {
            btnMediaTypePhotos.setBackgroundResource(com.callx.app.reels.R.color.brand_primary);
            btnMediaTypePhotos.setTextColor(android.graphics.Color.WHITE);
        }
        if (btnMediaTypeVideo != null) {
            btnMediaTypeVideo.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnMediaTypeVideo.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    com.callx.app.core.R.color.trim_text_secondary));
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

    /**
     * FIX: a video picked here used to go straight into compressVideo(),
     * skipping the editor entirely — so trim/filters/text/stickers/music/speed
     * were never available for an uploaded (as opposed to camera-recorded)
     * clip. Now it takes the exact same detour a camera-recorded clip takes:
     * ReelEditorActivity first, which forwards the edited result back here
     * (EXTRA_VIDEO_URI/EXTRA_IS_FILE_PATH handling in handleEditorExtras())
     * where compression + post details continue as before. Mirrors ReelCameraActivity#openEditorForGalleryUri()'s video
     * branch, including any sound already picked here pre-launch.
     */
    private void openPickedVideoInEditor(Uri uri) {
        Intent intent = new Intent(this, ReelEditorActivity.class);
        intent.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI, uri.toString());
        // content:// gallery uri, not a filesystem path — must be false or the
        // editor tries Uri.fromFile(new File("content://…")) and fails to load it.
        intent.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH, false);
        if (!preSelectedSoundId.isEmpty())    intent.putExtra("selected_sound_id",    preSelectedSoundId);
        if (!currentSoundTitle.isEmpty())     intent.putExtra("selected_sound_title", currentSoundTitle);
        if (!preSelectedSoundUrl.isEmpty())   intent.putExtra("selected_sound_url",   preSelectedSoundUrl);
        if (!currentSoundArtist.isEmpty())    intent.putExtra("selected_sound_artist",currentSoundArtist);
        startActivity(intent);
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
        } else if (requestCode == REQ_ADD_COLLABORATORS && resultCode == Activity.RESULT_OK && data != null) {
            pendingCollabUids.clear();    pendingCollabNames.clear();
            pendingCollabHandles.clear(); pendingCollabAvatars.clear();
            java.util.ArrayList<String> u  = data.getStringArrayListExtra(com.callx.app.social.CollabPostInviteActivity.RESULT_UIDS);
            java.util.ArrayList<String> n  = data.getStringArrayListExtra(com.callx.app.social.CollabPostInviteActivity.RESULT_NAMES);
            java.util.ArrayList<String> h  = data.getStringArrayListExtra(com.callx.app.social.CollabPostInviteActivity.RESULT_HANDLES);
            java.util.ArrayList<String> av = data.getStringArrayListExtra(com.callx.app.social.CollabPostInviteActivity.RESULT_AVATARS);
            if (u  != null) pendingCollabUids.addAll(u);
            if (n  != null) pendingCollabNames.addAll(n);
            if (h  != null) pendingCollabHandles.addAll(h);
            if (av != null) pendingCollabAvatars.addAll(av);
            if (tvCollabSummary != null) {
                if (pendingCollabUids.isEmpty()) {
                    tvCollabSummary.setText("");
                } else {
                    String first = pendingCollabNames.isEmpty() ? "" : pendingCollabNames.get(0);
                    int others = pendingCollabUids.size() - 1;
                    tvCollabSummary.setText(others > 0 ? (first + " +" + others) : first);
                }
            }
        } else if (requestCode == 504 && resultCode == Activity.RESULT_OK && data != null) {
            scheduleTime = data.getStringExtra("scheduled_time");
            if (scheduleTime == null) scheduleTime = "";
            if (tvScheduleTime != null) tvScheduleTime.setText(scheduleTime);
        }
        // ── REQ_TRENDING_AUDIO: user picked a sound from the Trending Audio screen ──
        if (requestCode == REQ_TRENDING_AUDIO && resultCode == Activity.RESULT_OK && data != null) {
            String pickedId     = data.getStringExtra(com.callx.app.music.ReelTrendingAudioActivity.RESULT_AUDIO_ID);
            String pickedTitle  = data.getStringExtra(com.callx.app.music.ReelTrendingAudioActivity.RESULT_AUDIO_TITLE);
            String pickedArtist = data.getStringExtra(com.callx.app.music.ReelTrendingAudioActivity.RESULT_AUDIO_ARTIST);
            String pickedUrl    = data.getStringExtra(com.callx.app.music.ReelTrendingAudioActivity.RESULT_AUDIO_URL);
            String pickedCover  = data.getStringExtra(com.callx.app.music.ReelTrendingAudioActivity.RESULT_COVER_URL);
            if (pickedUrl != null && !pickedUrl.isEmpty()) {
                // Let the user preview + trim exactly how much of the track to use
                // before it's applied, instead of always defaulting to the start of
                // the track. Photo reels pass their slideshow photos so the trim
                // screen's live preview cycles through them; video reels pass the
                // selected video uri instead, so the trim screen's preview plays the
                // actual clip (muted) rather than just showing the track's cover art.
                ArrayList<String> uriStrs = new ArrayList<>();
                if (isPhotoMode && !selectedPhotoUris.isEmpty()) {
                    for (Uri u : selectedPhotoUris) uriStrs.add(u.toString());
                }

                String videoUriForTrim = null;
                boolean videoIsFilePathForTrim = false;
                if (!isPhotoMode && selectedUri != null) {
                    try {
                        if ("file".equals(selectedUri.getScheme())) {
                            videoIsFilePathForTrim = true;
                            videoUriForTrim = selectedUri.getPath();
                        } else {
                            videoUriForTrim = selectedUri.toString();
                        }
                    } catch (Exception e) {
                        videoUriForTrim = selectedUri.toString();
                    }
                }

                ReelPhotoMusicTrimActivity.start(
                    this, uriStrs, selectedDurationMs,
                    pickedId    != null ? pickedId    : "",
                    pickedTitle != null ? pickedTitle : "",
                    pickedArtist!= null ? pickedArtist: "",
                    pickedUrl,
                    pickedCover != null ? pickedCover : "",
                    0, 0, 0,
                    videoUriForTrim, videoIsFilePathForTrim,
                    REQ_PHOTO_MUSIC_TRIM);
            }
            return;
        }

        // ── REQ_PHOTO_MUSIC_TRIM: user previewed the reel and trimmed the track ──
        if (requestCode == REQ_PHOTO_MUSIC_TRIM && resultCode == Activity.RESULT_OK && data != null) {
            String rUrl    = data.getStringExtra(ReelPhotoMusicTrimActivity.RESULT_SOUND_URL);
            String rId     = data.getStringExtra(ReelPhotoMusicTrimActivity.RESULT_SOUND_ID);
            String rTitle  = data.getStringExtra(ReelPhotoMusicTrimActivity.RESULT_SOUND_TITLE);
            String rArtist = data.getStringExtra(ReelPhotoMusicTrimActivity.RESULT_SOUND_ARTIST);
            String rCover  = data.getStringExtra(ReelPhotoMusicTrimActivity.RESULT_SOUND_COVER);
            int    rStart  = data.getIntExtra(ReelPhotoMusicTrimActivity.RESULT_START_MS, 0);
            int    rEnd    = data.getIntExtra(ReelPhotoMusicTrimActivity.RESULT_END_MS, 0);
            if (rUrl != null && !rUrl.isEmpty()) {
                preSelectedSoundUrl   = rUrl;
                preSelectedSoundId    = rId     != null ? rId     : "";
                currentSoundTitle     = rTitle  != null ? rTitle  : "";
                currentSoundArtist    = rArtist != null ? rArtist : "";
                preSelectedSoundCover = rCover  != null ? rCover  : "";
                musicStartMs = rStart;
                musicEndMs   = rEnd;
                mixMusicVol  = 0.8f;
                mixFadeInMs  = 0;
                mixFadeOutMs = 0;
                if (etMusic != null) etMusic.setText(currentSoundTitle);
                updateAudioUI();
                Toast.makeText(this, "Sound added: " + currentSoundTitle, Toast.LENGTH_SHORT).show();
                // Trim confirmed ("Done") — open the Audio Mixer next (video/photo both),
                // instead of advancing the step directly. The mixer keeps the user on
                // Step 2 (Caption & Sound) once it returns.
                openAudioMixerAfterTrim();
            }
            return;
        }

        // ── REQ_AUDIO_MIXER_AFTER_TRIM: mixer opened right after the trim screen ──
        if (requestCode == REQ_AUDIO_MIXER_AFTER_TRIM && resultCode == Activity.RESULT_OK && data != null) {
            applyMixerResult(data);
            return;
        }
        if (requestCode == REQ_AUDIO_MIXER_AFTER_TRIM) {
            // Mixer cancelled/back — the trimmed sound picked earlier stays applied,
            // user simply remains on Step 2.
            return;
        }

        // ── REQ_AUDIO_MIXER_UPLOAD: user adjusted volumes/track in the mixer ──────
        if (requestCode == REQ_AUDIO_MIXER_UPLOAD && resultCode == Activity.RESULT_OK && data != null) {
            applyMixerResult(data);
            return;
        }

        // ── REQ_SOUND_DETAIL_UPLOAD: user tapped the track row → SoundDetailActivity ──
        if (requestCode == REQ_SOUND_DETAIL_UPLOAD && resultCode == Activity.RESULT_OK && data != null) {
            // SoundDetailActivity passes back the same extras it received, possibly updated
            String su = data.getStringExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_URL);
            String si = data.getStringExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_ID);
            String st = data.getStringExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_TITLE);
            String sa = data.getStringExtra(com.callx.app.music.SoundDetailActivity.EXTRA_ARTIST);
            if (su != null && !su.isEmpty()) {
                preSelectedSoundUrl = su;
                if (si != null && !si.isEmpty()) preSelectedSoundId   = si;
                if (st != null && !st.isEmpty()) currentSoundTitle    = st;
                if (sa != null && !sa.isEmpty()) currentSoundArtist   = sa;
                if (etMusic != null) etMusic.setText(currentSoundTitle);
                updateAudioUI();
            }
            return;
        }

        if (requestCode == REQ_PICK_VIDEO && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            openPickedVideoInEditor(data.getData());
        } else if (requestCode == REQ_PICK_PHOTOS && resultCode == Activity.RESULT_OK && data != null) {
            // Multi-select: data.getClipData() if multiple, data.getData() if single
            int remaining = MAX_PHOTOS - selectedPhotoUris.size();
            int firstNewIndex = selectedPhotoUris.size();
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

            // ✅ NEW: open the per-photo editor immediately for the first newly-picked
            // photo, instead of waiting for a manual tap on its thumbnail — same
            // handoff feel as the camera "+" → Photo mode flow, which already
            // auto-opens the editor right after capture.
            if (firstNewIndex < selectedPhotoUris.size()) {
                photoEditIndex = firstNewIndex;
                Uri firstUri = selectedPhotoUris.get(firstNewIndex);
                com.callx.app.editor.ReelPhotoEditorActivity.start(
                        this, firstUri.toString(), firstNewIndex,
                        selectedPhotoUris.size(),
                        getPhotoMeta(firstNewIndex, "filter"),
                        getPhotoMeta(firstNewIndex, "effect"),
                        getPhotoMeta(firstNewIndex, "caption"),
                        getPhotoMeta(firstNewIndex, "captionStyle"),
                        getPhotoMeta(firstNewIndex, "stickers"),
                        getPhotoMeta(firstNewIndex, "kbDir"),
                        getPhotoDuration(firstNewIndex),
                        getPhotoRotation(firstNewIndex),
                        REQ_PHOTO_EDIT);
            }
        } else if (requestCode == REQ_PHOTO_EDIT && resultCode == Activity.RESULT_OK && data != null) {
            int pos = photoEditIndex;
            if (pos < 0 || pos >= selectedPhotoUris.size()) return;

            // Read back all edited metadata from ReelPhotoEditorActivity
            // Crop tool (reused from :core's MediaCropActivity) may have replaced
            // the working photo with a new cropped file — swap it into the list.
            String editedUriStr = data.getStringExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_PHOTO_URI);
            if (editedUriStr != null && !editedUriStr.isEmpty()) {
                Uri editedUri = Uri.parse(editedUriStr);
                if (!editedUri.equals(selectedPhotoUris.get(pos))) {
                    selectedPhotoUris.set(pos, editedUri);
                }
            }
            setPhotoMeta(pos, "filter",       data.getStringExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_FILTER));
            setPhotoMeta(pos, "effect",       data.getStringExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_EFFECT));
            setPhotoMeta(pos, "caption",      data.getStringExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_CAPTION));
            setPhotoMeta(pos, "captionStyle", data.getStringExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_CAPTION_STYLE));
            setPhotoMeta(pos, "stickers",     data.getStringExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_STICKERS));
            setPhotoMeta(pos, "kbDir",        data.getStringExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_KB_DIRECTION));
            int durMs = data.getIntExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_DURATION_MS, 0);
            setPhotoDuration(pos, durMs);
            float rot = data.getFloatExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_ROTATION, 0f);
            setPhotoRotation(pos, rot);

            // Apply-all: propagate filter+effect to every photo
            if (data.getBooleanExtra(com.callx.app.editor.ReelPhotoEditorActivity.EXTRA_APPLY_ALL, false)) {
                String f = getPhotoMeta(pos, "filter");
                String e = getPhotoMeta(pos, "effect");
                for (int i = 0; i < selectedPhotoUris.size(); i++) {
                    setPhotoMeta(i, "filter", f);
                    setPhotoMeta(i, "effect", e);
                }
            }

            // Refresh thumbnail (rotation may have changed visual)
            rebuildPhotoPreview();
            photoEditIndex = -1;
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
        Glide.with(this).load(uri).centerCrop().override(480, 853).into(thumb);

        // Remove button (×) — top-right
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
                removePhotoMetaAt(pos);
                llPhotoPreviewContainer.removeView(frame);
                if (coverPhotoIndex >= selectedPhotoUris.size()) coverPhotoIndex = 0;
                refreshCoverIndicators();
                updatePhotoCountLabel();
                if (selectedPhotoUris.isEmpty()) btnPostReel.setEnabled(false);
            }
        });

        // Cover star (★) — bottom-left, tap to set this as cover photo
        TextView btnCover = new TextView(this);
        android.widget.FrameLayout.LayoutParams clp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        btnCover.setLayoutParams(clp);
        btnCover.setText(index == coverPhotoIndex ? "★" : "☆");
        btnCover.setTextColor(index == coverPhotoIndex ? 0xFFFFD700 : 0xAAFFFFFF);
        btnCover.setTextSize(14f);
        btnCover.setBackgroundColor(0xAA000000);
        btnCover.setPadding(4, 0, 4, 0);
        btnCover.setTag("cover_star");
        btnCover.setOnClickListener(v -> {
            int pos = llPhotoPreviewContainer.indexOfChild(frame);
            if (pos >= 0) {
                coverPhotoIndex = pos;
                refreshCoverIndicators();
                android.widget.Toast.makeText(this, "Cover photo set!", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        // Short-tap on the thumbnail → open per-photo editor
        thumb.setOnClickListener(v -> {
            int pos = llPhotoPreviewContainer.indexOfChild(frame);
            if (pos < 0 || pos >= selectedPhotoUris.size()) return;
            photoEditIndex = pos;
            com.callx.app.editor.ReelPhotoEditorActivity.start(
                    this, selectedPhotoUris.get(pos).toString(), pos,
                    selectedPhotoUris.size(),
                    getPhotoMeta(pos, "filter"),
                    getPhotoMeta(pos, "effect"),
                    getPhotoMeta(pos, "caption"),
                    getPhotoMeta(pos, "captionStyle"),
                    getPhotoMeta(pos, "stickers"),
                    getPhotoMeta(pos, "kbDir"),
                    getPhotoDuration(pos),
                    getPhotoRotation(pos),
                    REQ_PHOTO_EDIT);
        });

        // Long-press popup: Move Left / Move Right / Set Cover / Remove
        frame.setOnLongClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "◀  Move Left");
            popup.getMenu().add(0, 2, 0, "Move Right  ▶");
            popup.getMenu().add(0, 3, 0, "★  Set as Cover");
            popup.getMenu().add(0, 4, 0, "✕  Remove");
            popup.setOnMenuItemClickListener(item -> {
                int pos = llPhotoPreviewContainer.indexOfChild(v);
                if (pos < 0) return false;
                switch (item.getItemId()) {
                    case 1: movePhotoLeft(pos);  return true;
                    case 2: movePhotoRight(pos); return true;
                    case 3:
                        coverPhotoIndex = pos;
                        refreshCoverIndicators();
                        Toast.makeText(this, "Cover photo set!", Toast.LENGTH_SHORT).show();
                        return true;
                    case 4:
                        selectedPhotoUris.remove(pos);
                        removePhotoMetaAt(pos);
                        llPhotoPreviewContainer.removeView(v);
                        if (coverPhotoIndex >= selectedPhotoUris.size()) coverPhotoIndex = 0;
                        refreshCoverIndicators();
                        updatePhotoCountLabel();
                        if (selectedPhotoUris.isEmpty()) btnPostReel.setEnabled(false);
                        return true;
                }
                return false;
            });
            popup.show();
            return true;
        });

        // Edit badge (bottom-right): shows "✎" if this photo has any editor changes
        TextView tvCaptionBadge = new TextView(this);
        android.widget.FrameLayout.LayoutParams cbp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        cbp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        tvCaptionBadge.setLayoutParams(cbp);
        tvCaptionBadge.setText("✎");
        tvCaptionBadge.setTextColor(0xFFFFFFFF);
        tvCaptionBadge.setTextSize(10f);
        tvCaptionBadge.setBackgroundColor(0xCC5B5BF6);
        tvCaptionBadge.setPadding(5, 1, 5, 1);
        tvCaptionBadge.setTag("caption_badge");
        // Show badge if any editor metadata exists for this photo
        boolean hasEdits = !getPhotoMeta(index, "caption").isEmpty()
                || !getPhotoMeta(index, "filter").isEmpty()
                || !getPhotoMeta(index, "effect").isEmpty()
                || !getPhotoMeta(index, "stickers").isEmpty();
        tvCaptionBadge.setVisibility(hasEdits ? View.VISIBLE : View.GONE);

        // Ensure photoCaptions list stays in sync (grow if needed)
        while (photoCaptions.size() <= index) photoCaptions.add("");

        frame.addView(thumb);
        frame.addView(btnRemove);
        frame.addView(btnCover);
        frame.addView(tvCaptionBadge);
        llPhotoPreviewContainer.addView(frame);
    }

    /**
     * Shows an AlertDialog for the user to enter/edit the caption for photo at {@code pos}.
     * Saves the caption to photoCaptions and shows/hides the "T" badge.
     */
    private void showCaptionDialog(int pos) {
        if (pos < 0 || pos >= selectedPhotoUris.size()) return;
        while (photoCaptions.size() <= pos) photoCaptions.add("");
        String current = photoCaptions.get(pos);

        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Caption for photo " + (pos + 1) + "…");
        et.setText(current);
        et.setSingleLine(false);
        et.setMaxLines(3);
        et.setSelection(et.getText().length());
        et.setPadding(32, 24, 32, 8);

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(this)
            .setTitle("Photo Caption")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String newCaption = et.getText().toString().trim();
                photoCaptions.set(pos, newCaption);
                // Update the badge visibility on this thumbnail
                if (llPhotoPreviewContainer != null && pos < llPhotoPreviewContainer.getChildCount()) {
                    android.widget.FrameLayout thumbFrame =
                        (android.widget.FrameLayout) llPhotoPreviewContainer.getChildAt(pos);
                    View badge = thumbFrame.findViewWithTag("caption_badge");
                    if (badge != null) badge.setVisibility(newCaption.isEmpty() ? View.GONE : View.VISIBLE);
                }
                Toast.makeText(this, newCaption.isEmpty() ? "Caption cleared" : "Caption saved!", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("Clear", (d, w) -> {
                photoCaptions.set(pos, "");
                if (llPhotoPreviewContainer != null && pos < llPhotoPreviewContainer.getChildCount()) {
                    android.widget.FrameLayout thumbFrame =
                        (android.widget.FrameLayout) llPhotoPreviewContainer.getChildAt(pos);
                    View badge = thumbFrame.findViewWithTag("caption_badge");
                    if (badge != null) badge.setVisibility(View.GONE);
                }
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ── Per-photo metadata helpers ────────────────────────────────────────────

    /** Grows the list to at least size {@code index+1}, filling gaps with {@code def}. */
    private static <T> void ensureSize(java.util.ArrayList<T> list, int index, T def) {
        while (list.size() <= index) list.add(def);
    }

    private String getPhotoMeta(int pos, String key) {
        java.util.ArrayList<String> list = metaList(key);
        if (list == null || pos >= list.size()) return "";
        String v = list.get(pos);
        return v != null ? v : "";
    }

    private void setPhotoMeta(int pos, String key, String value) {
        java.util.ArrayList<String> list = metaList(key);
        if (list == null) return;
        ensureSize(list, pos, "");
        list.set(pos, value != null ? value : "");
        // Keep photoCaptions in sync when caption changes
        if ("caption".equals(key)) {
            ensureSize(photoCaptions, pos, "");
            photoCaptions.set(pos, value != null ? value : "");
        }
    }

    private java.util.ArrayList<String> metaList(String key) {
        switch (key) {
            case "filter":       return photoFilterList;
            case "effect":       return photoEffectList;
            case "caption":      return photoCaptions;
            case "captionStyle": return photoCaptionStyleList;
            case "stickers":     return photoStickerJsonList;
            case "kbDir":        return photoKbDirList;
            default:             return null;
        }
    }

    private int getPhotoDuration(int pos) {
        if (pos >= photoDurationList.size()) return 0;
        Integer v = photoDurationList.get(pos);
        return (v != null) ? v : 0;
    }

    private void setPhotoDuration(int pos, int ms) {
        ensureSize(photoDurationList, pos, 0);
        photoDurationList.set(pos, ms);
    }

    private float getPhotoRotation(int pos) {
        if (pos >= photoRotationList.size()) return 0f;
        Float v = photoRotationList.get(pos);
        return (v != null) ? v : 0f;
    }

    private void setPhotoRotation(int pos, float deg) {
        ensureSize(photoRotationList, pos, 0f);
        photoRotationList.set(pos, deg);
    }

    /**
     * Rebuilds the thumbnail strip from scratch (called after editor returns,
     * so rotation changes are reflected in the preview).
     */
    private void rebuildPhotoPreview() {
        if (llPhotoPreviewContainer == null) return;
        llPhotoPreviewContainer.removeAllViews();
        for (int i = 0; i < selectedPhotoUris.size(); i++) {
            addPhotoPreviewThumbnail(selectedPhotoUris.get(i), i);
        }
        updatePhotoCountLabel();
    }

    /** Swaps photo at pos with the one before it, then rebuilds thumbnails. */
    private void movePhotoLeft(int pos) {
        if (pos <= 0) {
            Toast.makeText(this, "Already the first photo", Toast.LENGTH_SHORT).show();
            return;
        }
        android.net.Uri tmp = selectedPhotoUris.get(pos);
        selectedPhotoUris.set(pos, selectedPhotoUris.get(pos - 1));
        selectedPhotoUris.set(pos - 1, tmp);
        if (coverPhotoIndex == pos)       coverPhotoIndex = pos - 1;
        else if (coverPhotoIndex == pos - 1) coverPhotoIndex = pos;
        swapPhotoMeta(pos, pos - 1);
        rebuildPhotoThumbnails();
    }

    /** Swaps photo at pos with the one after it, then rebuilds thumbnails. */
    private void movePhotoRight(int pos) {
        if (pos >= selectedPhotoUris.size() - 1) {
            Toast.makeText(this, "Already the last photo", Toast.LENGTH_SHORT).show();
            return;
        }
        android.net.Uri tmp = selectedPhotoUris.get(pos);
        selectedPhotoUris.set(pos, selectedPhotoUris.get(pos + 1));
        selectedPhotoUris.set(pos + 1, tmp);
        if (coverPhotoIndex == pos)       coverPhotoIndex = pos + 1;
        else if (coverPhotoIndex == pos + 1) coverPhotoIndex = pos;
        swapPhotoMeta(pos, pos + 1);
        rebuildPhotoThumbnails();
    }

    /** Swaps ALL per-photo metadata between two indices. */
    private void swapPhotoMeta(int a, int b) {
        swapInList(photoCaptions, a, b, "");
        swapInList(photoFilterList, a, b, "");
        swapInList(photoEffectList, a, b, "");
        swapInList(photoCaptionStyleList, a, b, "");
        swapInList(photoStickerJsonList, a, b, "");
        swapInList(photoKbDirList, a, b, "");
        swapInList(photoDurationList, a, b, 0);
        swapInList(photoRotationList, a, b, 0f);
    }

    private static <T> void swapInList(java.util.ArrayList<T> list, int a, int b, T def) {
        ensureSize(list, Math.max(a, b), def);
        T tmp = list.get(a);
        list.set(a, list.get(b));
        list.set(b, tmp);
    }

    /** Removes index {@code pos} from ALL per-photo metadata lists. */
    private void removePhotoMetaAt(int pos) {
        if (pos < photoCaptions.size())         photoCaptions.remove(pos);
        if (pos < photoFilterList.size())        photoFilterList.remove(pos);
        if (pos < photoEffectList.size())        photoEffectList.remove(pos);
        if (pos < photoCaptionStyleList.size())  photoCaptionStyleList.remove(pos);
        if (pos < photoStickerJsonList.size())   photoStickerJsonList.remove(pos);
        if (pos < photoKbDirList.size())         photoKbDirList.remove(pos);
        if (pos < photoDurationList.size())      photoDurationList.remove(pos);
        if (pos < photoRotationList.size())      photoRotationList.remove(pos);
    }

    /** Clears the thumbnail strip and rebuilds it from selectedPhotoUris. */
    private void rebuildPhotoThumbnails() {
        rebuildPhotoPreview();
    }

    private void updatePhotoCountLabel() {
        if (tvPhotoCount != null) {
            tvPhotoCount.setText(selectedPhotoUris.size() + " / " + MAX_PHOTOS);
        }
        // Show/hide empty state illustration
        if (layoutPhotoEmptyState != null) {
            layoutPhotoEmptyState.setVisibility(
                selectedPhotoUris.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /** Refreshes all cover star icons in the thumbnail row after cover index changes. */
    private void refreshCoverIndicators() {
        if (llPhotoPreviewContainer == null) return;
        for (int i = 0; i < llPhotoPreviewContainer.getChildCount(); i++) {
            android.view.ViewGroup frame =
                (android.view.ViewGroup) llPhotoPreviewContainer.getChildAt(i);
            if (frame == null) continue;
            for (int j = 0; j < frame.getChildCount(); j++) {
                android.view.View child = frame.getChildAt(j);
                if ("cover_star".equals(child.getTag()) && child instanceof TextView) {
                    boolean isCover = (i == coverPhotoIndex);
                    ((TextView) child).setText(isCover ? "★" : "☆");
                    ((TextView) child).setTextColor(isCover ? 0xFFFFD700 : 0xAAFFFFFF);
                }
            }
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

    /**
     * ✅ FIX: entry point that now actually honors the trim range forwarded from
     * ReelEditorActivity. Previously this screen went straight to compressVideo(uri)
     * regardless of EXTRA_TRIM_START/END, so a trim only ever survived when the
     * editor itself had already re-encoded it in — a gallery-picked (non file-path)
     * video, or any case where the editor's own bake step didn't run, silently
     * uploaded the full untrimmed clip. Now, if a real trim range is pending and
     * wasn't already baked upstream, it's baked here first.
     */
    private void startCompressionPipeline(Uri uri, boolean isFilePath) {
        boolean hasPendingTrim = pendingTrimEndMs > pendingTrimStartMs && !pendingTrimAlreadyBaked;
        if (!hasPendingTrim) {
            compressVideo(uri);
            return;
        }
        bakeTrimThenCompress(uri, isFilePath);
    }

    private void bakeTrimThenCompress(Uri uri, boolean isFilePath) {
        if (isFinishing() || isDestroyed()) return;

        trimBakeInProgress = true;
        layoutCompression.setVisibility(View.VISIBLE);
        progressCompress.setProgress(0);
        tvCompressStatus.setText("Trimming…");
        btnPostReel.setEnabled(false);

        WeakReference<ReelUploadActivity> ref = new WeakReference<>(this);

        // ReelVideoExportEngine.export needs a local file path — copy content:// URIs
        // to a temp file first (VideoCompressor already does this for compression).
        new Thread(() -> {
            String localPath;
            try {
                localPath = isFilePath
                    ? uri.getPath()
                    : VideoCompressor.copyUriToFile(this, uri).getAbsolutePath();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    ReelUploadActivity a = ref.get();
                    if (a == null || a.isFinishing() || a.isDestroyed()) return;
                    a.trimBakeInProgress = false;
                    Toast.makeText(a, "Couldn't trim video, uploading full clip.", Toast.LENGTH_LONG).show();
                    a.compressVideo(uri);
                });
                return;
            }

            runOnUiThread(() -> {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                com.callx.app.editor.ReelVideoExportEngine.export(a, localPath,
                    null, 0f, 1f, 1f, java.util.Collections.emptyList(),
                    pendingTrimStartMs, pendingTrimEndMs,
                    new com.callx.app.editor.ReelVideoExportEngine.ExportCallback() {
                        @Override public void onProgress(int percent) {
                            ReelUploadActivity aa = ref.get();
                            if (aa == null || aa.isFinishing() || aa.isDestroyed()) return;
                            if (percent >= 0) {
                                aa.progressCompress.setProgress(percent);
                                aa.tvCompressStatus.setText("Trimming… " + percent + "%");
                            }
                        }
                        @Override public void onSuccess(String outputPath) {
                            ReelUploadActivity aa = ref.get();
                            if (aa == null || aa.isFinishing() || aa.isDestroyed()) return;
                            aa.trimBakeInProgress = false;
                            aa.pendingTrimAlreadyBaked = true;
                            Uri trimmedUri = Uri.fromFile(new java.io.File(outputPath));
                            aa.selectedUri = trimmedUri;
                            aa.lastVideoIsFilePath = true;
                            aa.compressVideo(trimmedUri);
                        }
                        @Override public void onError(Exception e) {
                            ReelUploadActivity aa = ref.get();
                            if (aa == null || aa.isFinishing() || aa.isDestroyed()) return;
                            aa.trimBakeInProgress = false;
                            Toast.makeText(aa, "Couldn't trim video, uploading full clip.", Toast.LENGTH_LONG).show();
                            aa.compressVideo(uri);
                        }
                    });
            });
        }).start();
    }

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

        String caption = etCaption.getText() != null ? etCaption.getText().toString().trim() : "";
        // ── Resolve @mention UIDs from caption ────────────────────────────
        mentionedUids.clear();
        if (uploadMentionController != null) {
            mentionedUids.addAll(uploadMentionController.getMentionedUids(caption));
        }
        // Prefer the selected sound title; fall back to whatever the user typed in etMusic
        String musicName = (currentSoundTitle != null && !currentSoundTitle.isEmpty())
            ? currentSoundTitle
            : (etMusic.getText() != null ? etMusic.getText().toString().trim() : "");

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

        AudioMixHelper.MixConfig cfg = new AudioMixHelper.MixConfig();
        cfg.musicUrl      = preSelectedSoundUrl;
        cfg.voiceoverPath = mixVoiceoverPath != null ? mixVoiceoverPath : "";
        cfg.micVol        = mixOrigVol;
        cfg.musicVol      = mixMusicVol;
        cfg.voiceoverVol  = mixVoiceoverVol;
        cfg.musicStartMs  = musicStartMs;
        cfg.musicEndMs    = musicEndMs;
        cfg.fadeInMs      = mixFadeInMs;
        cfg.fadeOutMs     = mixFadeOutMs;
        cfg.pitchSemitones= mixPitchSemitones;
        cfg.normalize     = mixNormalize;
        AudioMixHelper.mixAndExportWithConfig(
            this,
            rawVideoPath,
            cfg,
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
            public void onSuccessWithHls(String thumbUrl, String videoUrl, String hlsManifestUrl,
                                  String video480, String video720, String video1080,
                                  int durationMs, int width, int height) {
                ReelUploadActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;

                // ✅ FIX (multi-duet gap #2): a participant's raw clip must NOT become
                // its own public reel — only the final merged composite should post.
                if (!a.multiDuetSessionId.isEmpty() && !a.multiDuetFinal) {
                    a.finishMultiDuetRawUpload(thumbUrl, videoUrl);
                    return;
                }

                a.saveReelToFirebase(thumbUrl, videoUrl, hlsManifestUrl,
                    video480, video720, video1080,
                    durationMs, width, height, caption, musicName, uploadResult, videoPath);
            }

            @Override
            public void onSuccessWithQualities(String thumbUrl, String videoUrl,
                                  String video480, String video720, String video1080,
                                  int durationMs, int width, int height) {
                // fallback — won't be called if onSuccessWithHls is overridden
            }

            @Override
            public void onSuccess(String thumbUrl, String videoUrl,
                                  int durationMs, int width, int height) {
                // fallback — won't be called if onSuccessWithHls is overridden
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

    // ── Multi-Duet raw clip handoff (FIX gap #2 + #3) ──────────────────────

    /**
     * A participant (host included) just finished recording+uploading their
     * OWN part of a multi-duet. This is NOT a public post — we only stash
     * the uploaded clip's URL on the session node, then check if everyone
     * else is done too.
     *
     * Previously every participant's raw clip was saved as its own public
     * reel via saveReelToFirebase() — that meant one multi-duet session
     * produced N+1 duplicate public posts. Now only the final merged video
     * (multiDuetFinal == true) ever gets posted.
     */
    private void finishMultiDuetRawUpload(String thumbUrl, String videoUrl) {
        layoutUploadProgress.setVisibility(View.GONE);
        final String uid = FirebaseUtils.getCurrentUid();
        final String sid = multiDuetSessionId;
        if (uid == null || uid.isEmpty() || sid.isEmpty()) { finish(); return; }

        final com.google.firebase.database.DatabaseReference sessionRef =
            FirebaseUtils.db().getReference("multi_duet_sessions").child(sid);

        Map<String, Object> update = new HashMap<>();
        update.put("status",   "recorded");
        update.put("videoUrl", videoUrl != null ? videoUrl : "");
        update.put("thumbUrl", thumbUrl != null ? thumbUrl : "");

        sessionRef.child("participants").child(uid).updateChildren(update)
            .addOnSuccessListener(v -> checkMultiDuetCompletion(sessionRef, sid, uid));

        Toast.makeText(this, "Recorded! Saving your part…", Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    /**
     * ✅ FIX (multi-duet gap #1 — unreliable composite trigger):
     * Previously "everyone recorded → merge" was only detected by a Firebase
     * listener living inside MultiDuetActivity, which got torn down
     * (onStop → removeEventListener) the moment the host navigated away to
     * record — i.e. exactly when it needed to keep listening. Since
     * participants record asynchronously (minutes/hours/days apart), that
     * listener was almost never alive at the right time and the session
     * would just get stuck forever.
     *
     * Fix: whichever participant's upload happens to be the LAST one in
     * checks completion itself, right here — independent of any screen
     * being open. A Firebase transaction guards against two participants
     * finishing at nearly the same time and double-triggering. Once
     * completion is claimed:
     *   • if THIS device is the host → jump straight into MultiDuetActivity
     *     in "resume" mode to run the merge now.
     *   • otherwise → send an FCM push so the HOST's device (which is the
     *     one that actually needs to download+merge all clips) opens
     *     MultiDuetActivity in resume mode and finishes it, even if the
     *     host's app was fully closed when the last clip came in.
     */
    private void checkMultiDuetCompletion(com.google.firebase.database.DatabaseReference sessionRef,
                                           String sessionId, String myUidLocal) {
        sessionRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String status = snap.child("status").getValue(String.class);
                if ("compositing".equals(status) || "completed".equals(status)
                        || "ready_to_finish".equals(status)) return;

                String hostUid = snap.child("hostUid").getValue(String.class);
                DataSnapshot pSnap = snap.child("participants");
                int active = 0, recorded = 0;
                for (DataSnapshot ds : pSnap.getChildren()) {
                    String st = ds.child("status").getValue(String.class);
                    if ("declined".equals(st)) continue; // excluded from completion count
                    active++;
                    if ("recorded".equals(st)) recorded++;
                }
                if (active < 2 || recorded < active) return; // still waiting on someone

                final String finalHostUid = hostUid;
                sessionRef.child("status").runTransaction(new Transaction.Handler() {
                    @NonNull @Override
                    public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                        Object cur = currentData.getValue();
                        if (cur != null && ("compositing".equals(cur) || "completed".equals(cur)
                                || "ready_to_finish".equals(cur))) {
                            return Transaction.abort();
                        }
                        currentData.setValue("ready_to_finish");
                        return Transaction.success(currentData);
                    }
                    @Override public void onComplete(DatabaseError error, boolean committed,
                                                      DataSnapshot snap2) {
                        if (!committed || finalHostUid == null || finalHostUid.isEmpty()) return;

                        if (finalHostUid.equals(myUidLocal)) {
                            // We ARE the host and just finished last — finalize right away.
                            Intent resume = new Intent(ReelUploadActivity.this, MultiDuetActivity.class);
                            resume.putExtra("resume_session_id", sessionId);
                            resume.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(resume);
                        } else {
                            com.callx.app.utils.PushNotify.notifyMultiDuetReady(finalHostUid, sessionId);
                        }
                    }
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ── Photo Slideshow Upload ────────────────────────────────────────────

    private void handlePostPhotoSlideshow() {
        if (selectedPhotoUris.isEmpty()) {
            Toast.makeText(this, "Please select at least one photo", Toast.LENGTH_SHORT).show();
            return;
        }
        String caption = etCaption.getText() != null ? etCaption.getText().toString().trim() : "";
        // ── Resolve @mention UIDs from caption (photo slideshow path) ─────
        mentionedUids.clear();
        if (uploadMentionController != null) {
            mentionedUids.addAll(uploadMentionController.getMentionedUids(caption));
        }
        // Prefer the selected sound title; fall back to whatever the user typed in etMusic
        String musicName = (currentSoundTitle != null && !currentSoundTitle.isEmpty())
            ? currentSoundTitle
            : (etMusic.getText() != null ? etMusic.getText().toString().trim() : "");
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

                    // Use cover photo (creator-selected or first) as thumbnail
                    int safeCover = (a.coverPhotoIndex >= 0 && a.coverPhotoIndex < photoUrls.size())
                        ? a.coverPhotoIndex : 0;
                    String thumbUrl = photoUrls.isEmpty() ? "" : photoUrls.get(safeCover);
                    int durMs  = a.selectedDurationMs > 0 ? a.selectedDurationMs : 3000;
                    String trType = a.selectedTransitionType != null
                        ? a.selectedTransitionType : "fade";

                    ReelModel reel = new ReelModel(
                        reelId, myUid, finalMyName, safePhoto,
                        "", thumbUrl, caption, musicName,
                        System.currentTimeMillis(), photoUrls.size() * durMs, 0, 0);

                    reel.mediaType       = "photo_slideshow";
                    reel.photoUrls       = photoUrls;
                    reel.photoDurationMs = durMs;
                    reel.transitionType  = trType;
                    reel.coverPhotoIndex = safeCover;
                    reel.photoFilter     = (a.selectedFilter != null) ? a.selectedFilter : "normal";
                    reel.autoLoop        = (a.swAutoLoop != null) && a.swAutoLoop.isChecked();
                    reel.showDotIndicator = true;
                    // Build photoCaptions list (null entries → empty string → omit if all blank)
                    java.util.List<String> caps = new java.util.ArrayList<>(a.photoCaptions);
                    while (caps.size() < photoUrls.size()) caps.add("");
                    boolean hasAnyCap = false;
                    for (String c : caps) if (c != null && !c.isEmpty()) { hasAnyCap = true; break; }
                    reel.photoCaptions   = hasAnyCap ? caps : null;

                    // ── Per-photo editor metadata ──────────────────────────
                    reel.photoFilterList      = nullIfAllDefault(a.photoFilterList,       photoUrls.size(), "");
                    reel.photoEffectList      = nullIfAllDefault(a.photoEffectList,       photoUrls.size(), "");
                    reel.photoCaptionStyleList= nullIfAllDefault(a.photoCaptionStyleList, photoUrls.size(), "");
                    reel.photoStickerJsonList = nullIfAllDefault(a.photoStickerJsonList,  photoUrls.size(), "");
                    reel.photoKenBurnsDirectionList = nullIfAllDefault(a.photoKbDirList,        photoUrls.size(), "");
                    // Per-photo duration overrides (0 = use global)
                    java.util.List<Integer> durOverrides = new java.util.ArrayList<>(a.photoDurationList);
                    while (durOverrides.size() < photoUrls.size()) durOverrides.add(0);
                    boolean hasAnyDurOverride = false;
                    for (Integer d : durOverrides) if (d != null && d > 0) { hasAnyDurOverride = true; break; }
                    reel.photoDurationList = hasAnyDurOverride ? durOverrides : null;
                    reel.audienceType    = audienceType;
                    reel.thumbUrl        = thumbUrl;

                    // Duet / Stitch permissions
                    String duetLevel   = a.getDuetLevel();
                    String stitchLevel = a.getStitchLevel();
                    reel.allowDuetLevel   = duetLevel;
                    reel.allowDuet        = !"off".equals(duetLevel);
                    reel.allowStitchLevel = stitchLevel;
                    reel.allowStitch      = !"off".equals(stitchLevel);

                    if (!a.preSelectedSoundId.isEmpty())  reel.musicId  = a.preSelectedSoundId;
                    if (!a.preSelectedSoundUrl.isEmpty()) reel.musicUrl = a.preSelectedSoundUrl;
                    // ✅ FIX: set musicCoverUrl/musicArtist right away (existing-sound
                    // case) instead of relying only on the later async patch in
                    // registerOrLinkSound(), which was silently skipped whenever
                    // that read raced/failed.
                    if (!a.preSelectedSoundCover.isEmpty()) reel.musicCoverUrl = a.preSelectedSoundCover;
                    if (!a.currentSoundArtist.isEmpty())    reel.musicArtist   = a.currentSoundArtist;
                    // ✅ FIX: persist trim range so the playback side can seek to the
                    // right position and loop within the trimmed window.
                    if (a.musicStartMs > 0) reel.musicStartMs = a.musicStartMs;
                    if (a.musicEndMs   > 0) reel.musicEndMs   = a.musicEndMs;

                    FirebaseUtils.getReelsRef().child(reelId).setValue(reel)
                        .addOnSuccessListener(unused -> {
                            ReelUploadActivity b = ref.get();
                            if (b == null || b.isFinishing() || b.isDestroyed()) return;
                            FirebaseUtils.getReelsByUserRef(myUid).child(reelId).setValue(true);
                            generateAndAttachBlurHash(b, reelId, reel.thumbUrl);
                            b.sendPendingCollabInvitesIfAny(reelId, reel.thumbUrl, myUid, finalMyName);
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

    /** ✅ MULTI-COLLABORATOR: fires the staged invites (from ReelPostDetailsActivity)
     *  once the reel has just been saved and a real reelId exists. No-op if none staged. */
    private void sendPendingCollabInvitesIfAny(String reelId, String thumbUrl, String myUid, String myName) {
        if (pendingCollabUids.isEmpty()) return;
        java.util.List<com.callx.app.social.CollabInviteHelper.StagedCollaborator> staged =
            com.callx.app.social.CollabInviteHelper.fromParallelLists(
                pendingCollabUids, pendingCollabNames, pendingCollabHandles, pendingCollabAvatars);
        com.callx.app.social.CollabInviteHelper.sendInvitesForNewReel(
            this, reelId, thumbUrl, staged, myUid, myName, null);
    }

    private void saveReelToFirebase(String thumbUrl, String videoUrl, String hlsManifestUrl,
                                    String video480, String video720, String video1080,
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
                // ✅ FIX: sync both thumbnail fields so Firebase has "thumbUrl" AND "thumbnailUrl"
                if (reel.thumbnailUrl == null || reel.thumbnailUrl.isEmpty()) {
                    reel.thumbnailUrl = reel.thumbUrl != null ? reel.thumbUrl : "";
                }
                reel.video480  = video480  != null ? video480  : "";
                reel.video720  = video720  != null ? video720  : "";
                reel.video1080 = video1080 != null ? video1080 : "";
                // ✅ HLS: single adaptive-streaming manifest — "" when Cloudinary's
                // Adaptive Streaming add-on isn't enabled on the account; player
                // falls back to video480/720/1080 above in that case.
                reel.hlsManifestUrl = hlsManifestUrl != null ? hlsManifestUrl : "";

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
                // ✅ FIX: set musicCoverUrl/musicArtist right away (existing-sound
                // case) instead of relying only on the later async patch in
                // registerOrLinkSound(), which was silently skipped whenever
                // that read raced/failed.
                if (!a.preSelectedSoundCover.isEmpty()) reel.musicCoverUrl = a.preSelectedSoundCover;
                if (!a.currentSoundArtist.isEmpty())    reel.musicArtist   = a.currentSoundArtist;
                // Interactive stickers (music/poll/quiz/countdown/slider/question/…)
                // from ReelEditorActivity's full sticker sheet — saved as live overlay
                // data, same as photoStickerJsonList, NOT baked into the video pixels.
                if (!a.videoStickerJson.isEmpty()) reel.stickerJson = a.videoStickerJson;
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
                    // ✅ Multi-duet: tag reel with session ID + slot
                    if (!a.multiDuetSessionId.isEmpty()) {
                        reel.multiDuetSessionId = a.multiDuetSessionId;
                        reel.multiDuetSlot      = a.multiDuetSlot;
                    }
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

                String aud = a != null ? a.getAudienceType() : "";
                reel.audienceType = aud;
                if ("close_friends".equals(aud)) {
                    FirebaseUtils.db().getReference("closeFriendReels").child(myUid).child(finalReelId).setValue(true);
                }
                  FirebaseUtils.getReelsRef().child(finalReelId).setValue(reel)
                    .addOnSuccessListener(unused -> {
                        ReelUploadActivity b = ref.get();
                        if (b == null || b.isFinishing() || b.isDestroyed()) return;

                        FirebaseUtils.getReelsByUserRef(myUid).child(finalReelId).setValue(true);
                        generateAndAttachBlurHash(b, finalReelId, reel.thumbUrl);
                        b.sendPendingCollabInvitesIfAny(finalReelId, reel.thumbUrl, myUid, myName);

                        Toast.makeText(b, "Reel posted! 🎉", Toast.LENGTH_SHORT).show();
                        // ── @Mention notifications (Instagram-style) ─────────────────────────
                        ReelMentionNotifier.notifyAll(
                                myUid, myName, finalReelId,
                                reel.thumbUrl, caption, b.mentionedUids);
                        b.setResult(RESULT_OK);

                        // ✅ FIX (multi-duet gap #2/#3): this branch of saveReelToFirebase()
                        // is now ONLY reached for the final merged composite (raw participant
                        // clips are intercepted earlier by finishMultiDuetRawUpload() and
                        // never create a public reel). Mark the session completed here, i.e.
                        // AFTER the merged reel is actually confirmed live — not optimistically
                        // beforehand.
                        if (!b.multiDuetSessionId.isEmpty() && b.multiDuetFinal) {
                            FirebaseUtils.db().getReference("multi_duet_sessions")
                                .child(b.multiDuetSessionId).child("status").setValue("completed");
                            FirebaseUtils.db().getReference("multi_duet_sessions")
                                .child(b.multiDuetSessionId).child("compositeReelId").setValue(finalReelId);
                        }

                        // Fix 6: increment duetCount on original reel
                        if (b.isDuet && !b.duetOriginalId.isEmpty()) {
                            FirebaseUtils.getReelsRef()
                                .child(b.duetOriginalId)
                                .child("duetCount")
                                .setValue(ServerValue.increment(1));

                            // Profile "Duet" grid tab index — mirrors userReposts, but keyed
                            // by the NEW duet reel (owned by the duet creator) rather than the
                            // original, since this reel already lives on the creator's own
                            // reelsByUser index — see UserReelsActivity's TAB_DUET.
                            FirebaseUtils.getUserDuetReelsRef(myUid)
                                .child(finalReelId)
                                .setValue(System.currentTimeMillis());

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
                        if (!b.preSelectedSoundId.isEmpty()) {
                            // ✅ OPTIMIZATION: the creator picked an EXISTING sound — we
                            // already know its canonical audioUrl (preSelectedSoundUrl,
                            // carried through Camera → Editor → Upload). Re-extracting the
                            // video's own audio track and re-uploading it as a brand-new
                            // Cloudinary file would just waste bandwidth/storage and create
                            // a duplicate copy of audio we already have. So skip extraction
                            // entirely here — just link this reel to the existing sound and
                            // bump its reel_count right away (no need to wait on extraction).
                            registerOrLinkSound(b, finalReelId, myUid, myName,
                                thumbUrl, videoUrl, b.preSelectedSoundUrl, "", b.preSelectedSoundId);
                        } else {
                            // True original audio — extract it from the recorded video and
                            // upload it once, then register it as a brand-new reusable sound.
                            java.io.File videoFileForAudio = new java.io.File(videoPath);
                            if (videoFileForAudio.exists()) {
                                VideoUploader.uploadOriginalAudio(b, videoFileForAudio, myUid, finalReelId,
                                    new VideoUploader.AudioUploadCallback() {
                                        @Override
                                        public void onSuccess(String audioUrl) {
                                            onSuccess(audioUrl, "");
                                        }
                                        @Override
                                        public void onSuccess(String audioUrl, String previewAudioUrl) {
                                            onSuccess(audioUrl, previewAudioUrl, "", false, "");
                                        }
                                        @Override
                                        public void onSuccess(String audioUrl, String previewAudioUrl,
                                                               String matchedSoundId, boolean matched,
                                                               String matchedOwnerUid, double offsetSec) {
                                            // Save originalAudioUrl to Firebase
                                            FirebaseUtils.getReelsRef()
                                                .child(finalReelId)
                                                .child("originalAudioUrl")
                                                .setValue(audioUrl);
                                            Log.d("ReelUpload",
                                                "originalAudioUrl saved: " + audioUrl
                                                + " previewAudioUrl: " + previewAudioUrl
                                                + " matched: " + matched + " soundId: " + matchedSoundId
                                                + " offsetSec: " + offsetSec);

                                            // ✅ Instagram-style: same audio was already posted by
                                            // someone (or by us) as a raw upload, even though we
                                            // never explicitly picked it from a sound page — reuse
                                            // that sound entity instead of minting a duplicate
                                            // "orig_{reelId}" so credit + reel-count land on the
                                            // ORIGINAL. Falls back to registering a brand-new
                                            // original whenever the fingerprint match couldn't
                                            // confirm one (no match found, or the match call itself
                                            // failed — same behaviour as before this feature existed).
                                            String soundIdToUse = (matched && matchedSoundId != null
                                                && !matchedSoundId.isEmpty()) ? matchedSoundId : "";
                                            registerOrLinkSound(b, finalReelId, myUid, myName,
                                                thumbUrl, videoUrl, audioUrl, previewAudioUrl, soundIdToUse);

                                            // ✅ NEW: this reel used a snippet/offset portion of the
                                            // matched original (not necessarily its very start) —
                                            // stash it so a future "jump to this part of the sound"
                                            // UI has something to read. Non-fatal, best-effort.
                                            if (matched && offsetSec > 0.05) {
                                                FirebaseUtils.getReelsRef()
                                                    .child(finalReelId)
                                                    .child("audioMatchOffsetSec")
                                                    .setValue(offsetSec);
                                            }
                                        }
                                        @Override
                                        public void onError(Exception e) {
                                            Log.w("ReelUpload",
                                                "Audio upload failed (non-fatal): " + e.getMessage());
                                        }
                                    });
                            }
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

    /**
     * ✅ Instagram-style audio reusability.
     *
     * Earlier, the extracted "original audio" was only ever saved on the reel
     * itself (reel.originalAudioUrl) — it was playable on that one reel's Sound
     * screen but never became a real, indexed "sounds/{soundId}" entity. So it
     * never showed up under "X Reels" / trending / "Use this sound" anywhere
     * else, and other users could never discover or reuse it.
     *
     * This now mirrors how Instagram treats audio:
     *  - If the creator did NOT pick an existing sound (soundChosenId empty),
     *    the just-extracted original audio is registered as a brand-new,
     *    standalone sound entity under sounds/{soundId} — with its own cover,
     *    artist, reel-count, and saves — so it becomes searchable/usable by
     *    anyone, exactly like a real Instagram "Original audio".
     *  - If the creator DID pick an existing sound, instead of silently doing
     *    nothing, we register this reel under that sound's reel list and bump
     *    its reel_count, so the sound's "Reels with this sound" grid + count
     *    actually reflect every reel that used it.
     *
     * Either way, reel.musicId ends up pointing at a real sounds/{soundId}
     * node, so SoundDetailActivity (and "Use this sound") work the same for
     * original audio as they do for picked/trending sounds.
     */
    private static void registerOrLinkSound(ReelUploadActivity activity,
                                              String reelId, String ownerUid, String ownerName,
                                              String thumbUrl, String videoUrl, String audioUrl,
                                              String previewAudioUrl, String soundChosenId) {
        // ⚠️ NOTE: by the time this callback fires, the activity has almost
        // always already called finish() — the audio extraction runs in the
        // background AFTER the reel post is confirmed, so the upload screen
        // is gone before extraction completes. This method must NOT depend
        // on activity.isFinishing()/isDestroyed() — it's pure Firebase writes,
        // no UI involved, so it must always run regardless of activity state.

        boolean usingExistingSound = soundChosenId != null && !soundChosenId.isEmpty();
        final String soundId = usingExistingSound ? soundChosenId : ("orig_" + reelId);

        com.google.firebase.database.DatabaseReference soundRef =
            FirebaseUtils.db().getReference("sounds").child(soundId);

        // Always link this reel → real sound entity (so SoundDetailActivity,
        // "Use this sound" etc. behave the same for original audio as for any
        // other sound).
        FirebaseUtils.getReelsRef().child(reelId).child("musicId").setValue(soundId);

        // Add this reel into the sound's own reel list (drives "Reels with
        // this sound" grid + total count on SoundDetailActivity).
        java.util.Map<String, Object> reelEntry = new java.util.HashMap<>();
        reelEntry.put("thumbnailUrl", thumbUrl != null ? thumbUrl : "");
        reelEntry.put("videoUrl",     videoUrl != null ? videoUrl : "");
        reelEntry.put("ownerUid",     ownerUid != null ? ownerUid : "");
        soundRef.child("reels").child(reelId).setValue(reelEntry);

        // Bump reel_count via transaction (works whether the sound node
        // already existed or is being created for the first time).
        soundRef.child("reel_count").runTransaction(new Transaction.Handler() {
            @NonNull @Override
            public Transaction.Result doTransaction(@NonNull MutableData d) {
                Long cur = d.getValue(Long.class);
                d.setValue((cur != null ? cur : 0) + 1);
                return Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {
                // ✅ Trending badge: trending_rank was always being written as 0
                // and nothing ever updated it, so the "🔥 Trending" badge could
                // never actually show. Instead of a precise global leaderboard
                // rank (which needs a real backend job to compute safely across
                // many concurrent writers), use a simple, race-safe threshold:
                // once a sound crosses TRENDING_REEL_THRESHOLD uses, flip a
                // boolean flag. SoundDetailActivity shows the badge off this
                // flag directly — no separate ranking computation needed.
                if (e == null && committed && s != null) {
                    Long count = s.getValue(Long.class);
                    if (count != null && count >= TRENDING_REEL_THRESHOLD) {
                        soundRef.child("is_trending").setValue(true);
                    }
                }
            }
        });

        if (usingExistingSound) {
            // Existing sound already has its own title/artist/cover — don't
            // clobber them, just fill in audioUrl if it was somehow missing.
            soundRef.child("audioUrl").runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    String cur = d.getValue(String.class);
                    if (cur == null || cur.isEmpty()) d.setValue(audioUrl);
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {}
            });
            // ✅ Back-fill previewAudioUrl too, in case this sound was registered
            // before the low-bitrate preview pipeline existed.
            if (previewAudioUrl != null && !previewAudioUrl.isEmpty()) {
                soundRef.child("previewAudioUrl").runTransaction(new Transaction.Handler() {
                    @NonNull @Override
                    public Transaction.Result doTransaction(@NonNull MutableData d) {
                        String cur = d.getValue(String.class);
                        if (cur == null || cur.isEmpty()) d.setValue(previewAudioUrl);
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {}
                });
            }

            // ✅ FIX: attribute this reel's bottom "sound" label to the sound's
            // ORIGINAL creator instead of whatever text happened to be sitting
            // in the upload screen's music field. That field gets pre-filled
            // with the sound's raw title — which, for reused "original audio"
            // sounds, is literally the string "Original audio" — so a reused
            // sound looked byte-identical to a brand-new original recording
            // and the real creator's name disappeared. Read title/artist
            // straight from the sound node (source of truth) and write those
            // onto THIS reel, resolving the creator's name if it wasn't
            // already denormalised onto the sound.
            soundRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    String title    = s.child("title").getValue(String.class);
                    String artist   = s.child("artist").getValue(String.class);
                    String coverUrl = s.child("coverUrl").getValue(String.class);
                    String soundCreatorUid = s.child("creatorUid").getValue(String.class);

                    java.util.Map<String, Object> reelUpdate = new java.util.HashMap<>();
                    reelUpdate.put("musicName",
                        title != null && !title.isEmpty() ? title : "Original audio");
                    // ✅ FIX Gap 3: populate musicCoverUrl from the sound node so
                    // the reused sound's artwork appears correctly in the feed.
                    if (coverUrl != null && !coverUrl.isEmpty()) {
                        reelUpdate.put("musicCoverUrl", coverUrl);
                    }

                    if (artist != null && !artist.isEmpty()) {
                        reelUpdate.put("musicArtist", artist);
                        FirebaseUtils.getReelsRef().child(reelId).updateChildren(reelUpdate);
                    } else if (soundCreatorUid != null && !soundCreatorUid.isEmpty()) {
                        FirebaseUtils.db().getReference("users").child(soundCreatorUid).child("name")
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(@NonNull DataSnapshot us) {
                                    String creatorName = us.getValue(String.class);
                                    if (creatorName != null && !creatorName.isEmpty()) {
                                        reelUpdate.put("musicArtist", creatorName);
                                    }
                                    FirebaseUtils.getReelsRef().child(reelId).updateChildren(reelUpdate);
                                }
                                @Override
                                public void onCancelled(@NonNull DatabaseError e) {
                                    FirebaseUtils.getReelsRef().child(reelId).updateChildren(reelUpdate);
                                }
                            });
                    } else {
                        FirebaseUtils.getReelsRef().child(reelId).updateChildren(reelUpdate);
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) { /* leave client-typed values as-is */ }
            });
            return;
        }

        // Brand-new original-audio entity — fill out full metadata once.
        java.util.Map<String, Object> soundData = new java.util.HashMap<>();
        soundData.put("audioUrl",        audioUrl != null ? audioUrl : "");
        // ✅ Low-bitrate mono preview — this is what SoundDetailActivity's play
        // button streams instead of the full-quality audioUrl above.
        soundData.put("previewAudioUrl", previewAudioUrl != null ? previewAudioUrl : "");
        soundData.put("title",        "Original audio");
        soundData.put("titleLower",   "original audio");
        soundData.put("artist",       ownerName != null ? ownerName : "");
        soundData.put("coverUrl",     thumbUrl != null ? thumbUrl : "");
        soundData.put("is_original",  true);
        soundData.put("is_verified",  false);
        soundData.put("total_saves",  0);
        soundData.put("trending_rank", 0);
        soundData.put("ownerUid",     ownerUid != null ? ownerUid : "");
        soundData.put("creatorUid",   ownerUid != null ? ownerUid : ""); // for SoundDetailActivity creator link
        soundData.put("sourceReelId", reelId);
        soundData.put("created_at",   ServerValue.TIMESTAMP);
        soundRef.updateChildren(soundData);

        // Reflect the new soundId + clean display fields back on the reel too,
        // so the feed's own "sound" row also points at the real entity.
        java.util.Map<String, Object> reelUpdate = new java.util.HashMap<>();
        reelUpdate.put("musicId",        soundId);
        reelUpdate.put("musicUrl",       audioUrl != null ? audioUrl : "");
        reelUpdate.put("musicCoverUrl",  thumbUrl != null ? thumbUrl : "");
        reelUpdate.put("musicArtist",    ownerName != null ? ownerName : "");
        reelUpdate.put("musicName",      "Original audio");
        FirebaseUtils.getReelsRef().child(reelId).updateChildren(reelUpdate);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private VideoQualityPreferences.Quality getSelectedQuality() {
        int id = chipQuality.getCheckedChipId();
        if (id == R.id.chip_low)    return VideoQualityPreferences.Quality.LOW;
        if (id == R.id.chip_hd)     return VideoQualityPreferences.Quality.HD;
        if (id == R.id.chip_fullhd) return VideoQualityPreferences.Quality.FULL_HD;
        return VideoQualityPreferences.Quality.STANDARD;
    }

    /**
     * Returns null if all entries equal {@code def} (saves Firebase bandwidth),
     * otherwise returns a padded copy of the list.
     */
    private static <T> java.util.List<T> nullIfAllDefault(
            java.util.ArrayList<T> src, int size, T def) {
        java.util.List<T> out = new java.util.ArrayList<>(src);
        while (out.size() < size) out.add(def);
        for (T v : out) if (v != null && !v.equals(def)) return out;
        return null;
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

    // ── BlurHash (grid placeholder) ─────────────────────────────────────

    /**
     * Fire-and-forget: derives a tiny (32px) Cloudinary variant of the
     * already-uploaded thumbUrl, decodes it, encodes a BlurHash string, and
     * patches it onto the reel's Firebase record. Runs entirely after the
     * post is already saved/visible, so a slow network or a failure here
     * never delays or blocks publishing — the grid just falls back to the
     * old icon placeholder for this reel until (if ever) it succeeds.
     */
    private static void generateAndAttachBlurHash(android.content.Context ctx, String reelId, String thumbUrl) {
        if (reelId == null || reelId.isEmpty() || thumbUrl == null || thumbUrl.isEmpty()) return;
        new Thread(() -> {
            try {
                String tinyUrl = com.callx.app.utils.CloudinaryUploader.deriveThumbUrl(thumbUrl, 32, "webp");
                android.graphics.Bitmap bmp = Glide.with(ctx.getApplicationContext())
                        .asBitmap()
                        .load(tinyUrl)
                        .submit(32, 32)
                        .get();
                if (bmp == null) return;
                String hash = com.callx.app.utils.BlurHash.encode(bmp, 4, 3);
                if (hash == null || hash.isEmpty()) return;
                FirebaseUtils.getReelsRef().child(reelId).child("blurHash").setValue(hash);
            } catch (Exception ignored) {
                // Non-critical — reel already posted successfully either way.
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (uploadMentionController != null) uploadMentionController.onDestroy();
        releasePreviewPlayer();
        if (activeStepRingSpin != null) activeStepRingSpin.cancel();
        super.onDestroy();
    }
}
