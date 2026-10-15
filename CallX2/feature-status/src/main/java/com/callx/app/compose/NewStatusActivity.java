package com.callx.app.compose;
import com.callx.app.utils.AlertDialogStyler;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.status.R;
import com.callx.app.status.databinding.ActivityNewStatusBinding;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
import com.callx.app.privacy.StatusPrivacyBottomSheet;
import com.callx.app.utils.StatusCustomExpiryHelper;
import com.callx.app.utils.StatusLinkPreviewHelper;
import com.callx.app.utils.StatusMentionHelper;
import com.callx.app.utils.StatusNotificationHelper;
import com.callx.app.utils.StatusPrivacyManager;
import com.callx.app.stickers.StatusStickerPickerSheet;
import com.callx.app.stickers.StatusStickerOverlayView;
import com.callx.app.viewer.StatusAudioFocusHelper;
import com.callx.app.viewer.StatusMusicClip;
import com.google.android.material.bottomsheet.BottomSheetDialog;
/**
 * NewStatusActivity v29 — Story gestures protected from parent scroll interception.
 *
 * ORIGINAL features:
 *   ✅ Text status + 10 bg color presets + font styles
 *   ✅ Image status (gallery pick) + caption
 *   ✅ Video status (gallery pick) + caption
 *   ✅ Privacy mode selector
 *   ✅ Upload progress display + draft save
 *
 * v25 features:
 *   ✅ Camera capture (photo + video) — direct capture without gallery
 *   ✅ Link preview — type/paste URL → OG card preview
 *   ✅ GIF / Sticker via URL input (extensible to Giphy)
 *   ✅ Custom expiry timer — 1h/3h/6h/12h/24h/48h/72h
 *   ✅ Close Friends toggle (post only to close friends list)
 *   ✅ @mention support in text and caption
 *   ✅ Gradient background for text statuses
 *   ✅ Text alignment options (left/center/right)
 *   ✅ Font size slider
 *   ✅ Privacy bottom sheet (full selector with contact picker)
 *   ✅ Image/Video compression with progress
 *   ✅ Cloudinary upload with retry
 *   ✅ Character counter (700 max)
 *
 * NEW v26 features — Interactive Stickers for Status/Stories:
 *   ✅ 🎵 Music Sticker   — show song + artist + animated equaliser bars
 *   ✅ ⏳ Countdown Timer — live ticking countdown card to any future date
 *   ✅ 🧠 Quiz Sticker    — multiple-choice question with one correct answer
 *   ✅ 💬 Question Box    — open-ended question box for viewer replies
 *   ✅ Stickers draggable on the status preview
 *   ✅ Long-press sticker to remove it
 *   ✅ Multiple stickers supported simultaneously
 *   ✅ Sticker JSON serialized and stored with the status post
 *
 * v29 story editor:
 *   ✅ 9:16 live canvas with a blurred/dimmed copy behind the sharp photo
 *   ✅ Stable full-canvas pinch/drag, pinch-centre anchoring and no lift jump
 *   ✅ Locks parent scrolling while photo gestures are active
 *   ✅ Pinch/drag transforms baked into the uploaded photo
 *   ✅ Right-side story tools for text, stickers/music, effects, mentions,
 *      drawing, gallery save, AI label and reset/clear actions
 */
public class NewStatusActivity extends AppCompatActivity {

    /** Extra: sticker JSON to auto-attach on open (used by the ➕ Add Yours chain flow). */
    public static final String EXTRA_PREFILL_STICKER_JSON = "prefillStickerJson";
    /** Extra: optional toast message to show once the prefilled sticker is attached. */
    public static final String EXTRA_PREFILL_TOAST = "prefillToast";
    private static final String PREFS_DRAFT = "status_draft";
    private static final String KEY_DRAFT   = "draft_text";
    private static final int[] BG_COLORS = {
        0xFF6200EE, 0xFF03DAC5, 0xFFE53935, 0xFF43A047,
        0xFF1E88E5, 0xFFFF6F00, 0xFF8E24AA, 0xFF00ACC1,
        0xFF6D4C41, 0xFF263238
    };
    private static final int[] TEXT_COLORS_FOR_BG = {
        0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF,
        0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
        0xFFFFFFFF, 0xFFFFFFFF
    };
    private ActivityNewStatusBinding binding;
    private Uri    pickedImage, pickedVideo, cameraImageUri;
    private int    selectedBgColor   = BG_COLORS[0];
    private int    selectedTextColor = TEXT_COLORS_FOR_BG[0];
    private String selectedFontStyle  = "default";
    private String selectedPrivacy    = StatusPrivacyManager.PRIVACY_CONTACTS;
    private Set<String> privacyUids   = new HashSet<>();
    private int    selectedExpiryHours = 24;
    private boolean isCloseFriends    = false;
    private String  selectedTextAlign  = "center";
    /** Whether viewers may repost this status to their own story (mirrors WA "Allow sharing"). Default: on. */
    private boolean allowSharing      = true;
    // Per-story creator control for the level-stabilizer (see
    // StorySpinViewController). OPT-IN, default OFF: only a story where the
    // creator explicitly flips this toggle on gets the stabilizer — every
    // other story stays off, and it's never carried into Highlights either
    // (StatusViewerActivity forces it off in highlight mode regardless).
    private boolean stabilizerEnabled = false;
    /** Custom avatar-ring color/mode for this status (picked via the same
     *  HighlightRingColorPickerBottomSheet used for Highlight albums).
     *  Null = use the app's default seen/unseen ring. */
    private String  selectedRingColor = null;
    private String  selectedRingMode  = null;
    // ── Scheduling (v27) ────────────────────────────────────────────────
    // > 0 when the user picked "Schedule" instead of posting immediately —
    // read once by saveStatus()/saveBatchStatus() and reset after use.
    private long    pendingScheduledAt = 0;
    // Link preview state
    private String detectedLinkUrl;
    private StatusLinkPreviewHelper.LinkPreview fetchedPreview;
    private ActivityResultLauncher<String>  imagePicker;
    private ActivityResultLauncher<String>  videoPicker;
    private ActivityResultLauncher<Uri>     cameraCapture;
    private ActivityResultLauncher<String>  cameraVideoCapture;
    // NEW: launches feature-reels' ReelCameraActivity (the same rich camera
    // used by the Reels tab's + / Create button — filters, effects, speed,
    // stickers, text, trending audio) and receives the finished video back.
    // Launched via class-name Intent (no compile dependency — feature-status
    // cannot depend on feature-reels since feature-reels already depends on
    // feature-status).
    private ActivityResultLauncher<Intent>  reelCameraLauncher;
    // Result of the full-screen editor opened from the attach-sheet's "Edit"
    // action — see showStatusAddSheet()'s onMediaEdit callback.
    private ActivityResultLauncher<Intent>  statusMediaEditLauncher;
    // NEW: caption typed in the attach sheet before tapping "Edit" on a
    // single picked video — reelCameraLauncher/handleReelCameraResult is
    // shared with the plain camera flow (which has no preset caption), so
    // this is only non-null while a pencil-edit-video round-trip through
    // ReelEditorActivity is in flight; handleReelCameraResult consumes and
    // clears it once used as a fallback for the editor's own text overlay.
    private String pendingSingleVideoEditCaption;
    /**
     * ✅ FIX: ReelEditorActivity's Thumbnail tool let the person pick a custom
     * cover frame (RESULT_THUMB_PATH → a real PNG already saved to disk), but
     * this screen always regenerated its own first-frame thumbnail on upload
     * regardless — the pick was accepted in the editor UI and then silently
     * thrown away. Carries the picked file from handleReelCameraResult()
     * through to uploadAndSave(); cleared in showVideoPreview()/discardMedia()
     * so a later unrelated video pick can't accidentally reuse a stale one.
     */
    private java.io.File pendingCustomThumbnailFile;
    // v216: Layout picker result launcher — receives selected URIs + layout style.
    private ActivityResultLauncher<Intent>  layoutPickerLauncher;
    // v237: Receives the result of editing the flattened layout collage in
    // MediaEditActivity — see openLayoutEditor()/its registration below.
    // Kept completely separate from statusMediaEditLauncher (which still
    // serves the plain multi-select attach-sheet "N separate photos" flow
    // via postStatusBatch) so the single-collage layout flow can never
    // accidentally get routed into the batch poster, and so a single
    // composed collage can never turn back into N individual photos.
    private ActivityResultLauncher<Intent>  layoutMediaEditLauncher;

    /** FrameLayout that holds the status preview + sticker overlays */
    private android.widget.FrameLayout stickerOverlayFrame;
    /** Instagram-style editor state for a picked photo. */
    private ImageView editorBlurBackground;
    private LinearLayout editorToolRail;
    private View mediaGestureOverlay;
    private StatusDrawOverlayView drawingOverlay;
    private TextView editorTextOverlay;
    private TextView editorAiLabel;
    private Uri editorMediaUri;
    private float editorMediaScale = 1f;
    private StorySafeAreaView editorSafeArea;      // dotted "what the viewer will cover" guide
    private boolean editorSafeAreaPinned;           // rail "Guide" toggle keeps it on

    private boolean editorPhotoMode;
    private boolean editorDrawMode;
    // v587: draw toolbar state
    private LinearLayout editorDrawBar;
    private TextView editorDrawIcon;
    private TextView editorUndoBtn, editorRedoBtn, editorEraserBtn;
    private final java.util.ArrayList<GradientDrawable> editorSwatchBgs = new java.util.ArrayList<>();
    private final java.util.ArrayList<TextView> editorSizeBtns = new java.util.ArrayList<>();
    private static final int[] DRAW_COLORS = {0xFFFFFFFF, 0xFF000000, 0xFFFF3B30,
            0xFFFFCC00, 0xFF34C759, 0xFF0A84FF, 0xFFFF2D92};
    private static final int[] DRAW_SIZES_DP = {3, 6, 12};
    private int editorDrawColorIdx = 0, editorDrawSizeIdx = 1;
    private boolean editorAiLabelEnabled;
    private boolean preserveStoryOverlaysOnNextPhoto;
    private ActivityResultLauncher<String> legacySavePermissionLauncher;
    /** JSON array of all added sticker configs (serialised for post metadata) */
    private final java.util.List<String> addedStickerJsons = new java.util.ArrayList<>();
    /** Small/Medium/Large size-control row shown above whichever sticker was last tapped */
    private LinearLayout stickerSizeBar;
    private com.callx.app.stickers.StatusStickerOverlayView selectedSticker;
    private LinearLayout stickerMusicRow;

    /** Reel-style three-step status composer state. */
    private int wizardStep = 0;
    /** Short step name shown in tv_step_name, next to the "Step X of Y" pill
     *  (all-caps via android:textAllCaps, so plain-case names are given here)
     *  — same pattern as Reel Editor / Reel Upload / Reel Photo Editor. */
    private static final String[] STEP_NAMES = {"Create", "Edit", "Publish"};
    private android.animation.ObjectAnimator activeStepRingSpin;
    /**
     * True only while a video picked on the Create step is being sent through
     * the "Advance Editing" round trip (ReelEditorActivity → its own
     * MediaEditActivity fallback). Set right before launching that intent and
     * consumed the moment the result lands back on the preview — see
     * btn_advance_editing's click listener and showSinglePickedPreview().
     * Kept separate from pendingSingleVideoEditCaption (which the attach
     * sheet's pencil-edit action also uses) so a normal pencil-edit round
     * trip never accidentally jumps the wizard to the Edit step.
     */
    private boolean pendingAdvanceEditWizardJump = false;

    /** Cropped / baked photo temps (cache/media_crop, cache/media_edit_out) are never deleted elsewhere.
     *  Age-based (>24h) so an in-progress draft's files are untouched; runs off the main thread. */
    private void purgeStaleEditTemps() {
        final java.io.File cache = getCacheDir();
        new Thread(() -> {
            final long cutoff = System.currentTimeMillis() - 24L * 3_600_000L;
            for (String dir : new String[]{"media_crop", "media_edit_out"}) {
                java.io.File[] fs = new java.io.File(cache, dir).listFiles();
                if (fs == null) continue;
                for (java.io.File f : fs) { try { if (f.isFile() && f.lastModified() < cutoff) f.delete(); } catch (Exception ignored) {} }
            }
        }, "status-temp-purge").start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        purgeStaleEditTemps();
        binding = ActivityNewStatusBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setupToolbar();
        setupMediaPickers();
        setupCameraCapture();
        legacySavePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) saveEditorPreviewToGallery();
                    else toast("Gallery permission denied");
                });
        // v543: @username suggestions under the status text + caption boxes
        try { com.callx.app.utils.MentionUi.attachAutocomplete(binding.etText); } catch (Exception ignored) {}
        try { if (binding.etCaption != null) com.callx.app.utils.MentionUi.attachAutocomplete(binding.etCaption); } catch (Exception ignored) {}
        reelCameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handleReelCameraResult);
        statusMediaEditLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleMediaEditActivityResult(result.getResultCode(), result.getData()));
        musicReeditAdjustLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> onMusicReeditAdjustResult(result.getResultCode(), result.getData()));
        musicReeditMixLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> onMusicReeditMixResult(result.getResultCode(), result.getData()));
        setupBgColorPicker();
        setupFontStylePicker();
        setupPrivacyButton();
        setupExpiryButton();
        setupCloseFriendsToggle();
        setupAllowSharingToggle();
        setupStabilizerToggle();
        setupRingColorButton();
        setupTextAlignButtons();
        setupTextInput();
        setupStickerOverlayFrame();
        setupWizard();
        // v216: Layout picker launcher — receives URIs + layoutStyle from StatusLayoutPickerActivity.
        //
        // BUG FIX (the original root cause of "layout badal ke ek-ek photo ho
        // jata hai / N photos = N status posts"): this used to forward the
        // picked URIs straight into MediaEditActivity — a generic per-photo
        // swipe editor with zero concept of a collage. That's exactly why
        // the arranged layout visibly fell apart into individual swipeable
        // photos the instant this screen opened, why the pinch/zoom/pan
        // arrangement never made it into the post (MediaEditActivity strips
        // it and hands back brand-new baked file:// Uris that don't match
        // the original Uris the arrangement was keyed by), and why the flow
        // through a screen built for "edit N separate photos" was
        // fundamentally the wrong detour for "post one collage".
        //
        // v237: the fix is NOT to skip MediaEditActivity — the person wants
        // to be able to edit the chosen layout too. The fix is to flatten
        // the collage FIRST (StatusLayoutComposer, WYSIWYG with the arrange
        // screen since it also takes the pinch/zoom/pan data) so there is
        // exactly ONE baked image file, and only THEN send that single file
        // into MediaEditActivity as a one-item list (see openLayoutEditor()
        // below). Because MediaEditActivity only ever sees one item:
        //   - there is nothing left for it to swipe between → the layout
        //     can never visibly fall apart into individual photos again,
        //   - its own "delete" action already refuses to delete the last
        //     remaining item, so the collage can't be discarded down to zero
        //     images either,
        //   - it can only ever return that same one edited image back out,
        //     so post() still only ever uploads ONE file.
        // The edited collage is then routed into the exact same pickedImage
        // + showImagePreview() pipeline every normal single-photo status
        // already uses, exactly as before — caption, privacy, stickers and
        // the existing Post button all just keep working unchanged.
        layoutPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                    java.util.ArrayList<String> uriStrings = result.getData().getStringArrayListExtra(
                            StatusLayoutPickerActivity.EXTRA_RESULT_URIS);
                    if (uriStrings == null || uriStrings.isEmpty()) return;
                    int layoutStyle = result.getData().getIntExtra(
                            StatusLayoutPickerActivity.EXTRA_RESULT_LAYOUT,
                            StatusLayoutPreviewView.STYLE_GRID_2X2);
                    float[] scales = result.getData().getFloatArrayExtra(StatusLayoutPickerActivity.EXTRA_RESULT_SCALE);
                    float[] panXs  = result.getData().getFloatArrayExtra(StatusLayoutPickerActivity.EXTRA_RESULT_PAN_X);
                    float[] panYs  = result.getData().getFloatArrayExtra(StatusLayoutPickerActivity.EXTRA_RESULT_PAN_Y);

                    java.util.List<Uri> uris = new java.util.ArrayList<>();
                    for (String s : uriStrings) uris.add(Uri.parse(s));

                    setPosting(true);
                    setHint("Creating layout…");
                    new Thread(() -> {
                        try {
                            java.io.File composed = StatusLayoutComposer.compose(
                                    this, uris, layoutStyle, scales, panXs, panYs);
                            Uri composedUri = androidx.core.content.FileProvider.getUriForFile(
                                    this, getPackageName() + ".fileprovider", composed);
                            runOnUiThread(() -> {
                                setPosting(false);
                                openLayoutEditor(composedUri);
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                setPosting(false);
                                toast("Failed to build layout: " + e.getMessage());
                            });
                        }
                    }).start();
                });
        // v237: MediaEditActivity result for the single flattened layout
        // collage — see openLayoutEditor(). Always exactly one URI in/out,
        // so this never touches postStatusBatch: it drops the (possibly
        // edited) collage into the normal single-image preview instead,
        // same as every other single-photo status.
        layoutMediaEditLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                    java.util.ArrayList<String> uriStrings = result.getData().getStringArrayListExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.RESULT_URIS);
                    if (uriStrings == null || uriStrings.isEmpty()) return;
                    // Only one item was ever sent in, and MediaEditActivity's own
                    // "delete" guard refuses to delete the last remaining item, so
                    // there will always be exactly one URI here — the edited (or
                    // untouched) layout collage, never split back into pieces.
                    Uri editedCollage = Uri.parse(uriStrings.get(0));
                    pickedImage = editedCollage;
                    pickedVideo = null;
                    showImagePreview(pickedImage);
                    String editedCaption = result.getData().getStringExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.RESULT_CAPTION);
                    if (editedCaption != null && !editedCaption.isEmpty() && binding.etCaption != null) {
                        binding.etCaption.setText(editedCaption);
                    }
                });
        restoreDraft();
        applyPrefillStickerIfAny();
        // v216: "Upload" button now opens the WhatsApp-style "Add status" sheet
        //       (Text / Music / Layout / Voice / AI Images + Recents grid) instead
        //       of the old plain Camera/Gallery alert dialog.
        binding.btnPickImage.setOnClickListener(v -> showStatusAddSheet());
        binding.btnPost.setOnClickListener(v -> post());
        // Long-press "Post" to schedule this status for later instead of
        // posting it immediately — mirrors the WhatsApp/Instagram pattern of
        // holding the send button for extra options.
        binding.btnPost.setOnLongClickListener(v -> { showScheduleDialog(); return true; });
        androidx.appcompat.widget.TooltipCompat.setTooltipText(binding.btnPost, "Hold to schedule for later");
        binding.btnDiscardMedia.setOnClickListener(v -> discardMedia());
        if (binding.btnAdvanceEditing != null) {
            binding.btnAdvanceEditing.setOnClickListener(v -> openAdvanceEditingForPickedMedia());
        }
        // GIF / Sticker button
        View btnGif = binding.getRoot().findViewWithTag("btn_gif");
        if (btnGif != null) btnGif.setOnClickListener(v -> showGifInputDialog());
        // ── NEW: Interactive Sticker picker button ────────────────────────
        View btnSticker = binding.getRoot().findViewWithTag("btn_add_sticker");
        if (btnSticker != null) btnSticker.setOnClickListener(v -> openStickerPicker());
    }

    // ── Reel-style status wizard ─────────────────────────────────────────

    /**
     * Keeps the editor options in clear steps without losing the live story
     * preview. The preview card is outside the step-specific controls, so it
     * remains visible for text-only, media, and publish screens alike.
     */
    private void setupWizard() {
        binding.btnStepBack.setOnClickListener(v -> {
            if (wizardStep > 0) showWizardStep(wizardStep - 1);
            else finish();
        });
        binding.btnStepNext.setOnClickListener(v -> {
            if (wizardStep < 2) {
                if (wizardStep == 0 && !hasComposerContent()) {
                    toast("Pehle text ya media add karo");
                    return;
                }
                showWizardStep(wizardStep + 1);
            } else {
                post();
            }
        });
        // ✅ NEW: tapping a step dot jumps back to an already-visited step;
        // tapping ahead does nothing — only btnStepNext may move forward
        // (and still runs the hasComposerContent() check as before). Shared
        // across every stepper screen — see StepDotsNavigationHelper.
        TextView[] stepDots = {binding.stepDot1, binding.stepDot2, binding.stepDot3};
        com.callx.app.utils.StepDotsNavigationHelper.bindStepDots(stepDots,
            new com.callx.app.utils.StepDotsNavigationHelper.StepNavigator() {
                @Override public int getCurrentStep() { return wizardStep; }
                @Override public void goToStep(int step) { showWizardStep(step); }
            });
        showWizardStep(0);
    }

    private boolean hasComposerContent() {
        String text = binding.etText.getText() == null
                ? "" : binding.etText.getText().toString().trim();
        return pickedImage != null || pickedVideo != null
                || !text.isEmpty() || fetchedPreview != null;
    }

    private void showWizardStep(int step) {
        wizardStep = Math.max(0, Math.min(2, step));

        for (int i = 0; i < binding.statusFormContent.getChildCount(); i++) {
            View child = binding.statusFormContent.getChildAt(i);
            String section = child.getTag() instanceof String
                    ? (String) child.getTag() : "";
            boolean visible = true;

            if ("wizard_step_text".equals(section)) {
                visible = wizardStep == 0;
            } else if ("wizard_step_media".equals(section)) {
                visible = wizardStep == 1;
            } else if ("wizard_step_publish".equals(section)) {
                visible = wizardStep == 2;
            } else if (child.getId() == R.id.media_preview_frame) {
                visible = wizardStep == 1;
            } else if ("btn_gif".equals(section) || "btn_add_sticker".equals(section)) {
                // These two standalone buttons are represented by the compact
                // right-side tool rail while editing media.
                visible = wizardStep == 0;
            } else if (child.getId() == R.id.btn_pick_image) {
                // Upload is the entry action on Create; the selected media
                // itself is reviewed on the following Edit step.
                visible = wizardStep == 0;
            } else if (child.getId() == R.id.status_step_preview_card) {
                // ✅ BUG FIX: this persistent preview card was showing on
                // EVERY step, including Edit (wizardStep == 1) — which has
                // its own full media_preview_frame (image/video + stickers +
                // discard button). That meant two previews of the same photo
                // stacked on screen right after upload. Hide the persistent
                // card only on the Edit step, where media_preview_frame is
                // already the single preview; keep it on Create (text-only
                // preview before media is picked) and Publish (final review).
                visible = wizardStep != 1;
            }

            // The link card keeps its own async visibility state, but belongs
            // to the Create step when it is actually shown.
            if ("link_preview_card".equals(section)) visible = wizardStep == 0;
            child.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (binding.statusWizardHeader != null) {
            // Give the story canvas more vertical room on the media-edit step.
            binding.statusWizardHeader.setVisibility(wizardStep == 1 ? View.GONE : View.VISIBLE);
        }
        if (wizardStep != 1 && drawingOverlay != null) drawingOverlay.setDrawEnabled(false);
        syncVideoPreviewPlayback();
        if (editorToolRail != null) {
            editorToolRail.setVisibility(wizardStep == 1 && (pickedImage != null || pickedVideo != null)
                    ? View.VISIBLE : View.GONE);
        }

        // ✅ tv_step_eyebrow now hosts the shared premium "Step X of Y" pill
        // (@drawable/bg_step_indicator_pill, same R.string.editor_step_pill_format
        // used by Reel Editor / Reel Upload) instead of the old
        // "CREATE YOUR STATUS" label + separate "1 of 3" counter TextView.
        String[] next = {"Continue", "Review", "Post now"};
        binding.tvStepEyebrow.setText(getString(R.string.editor_step_pill_format,
                wizardStep + 1, 3));
        if (binding.tvStepName != null && wizardStep < STEP_NAMES.length) {
            binding.tvStepName.setText(STEP_NAMES[wizardStep]);
        }
        binding.btnStepNext.setText(next[wizardStep]);
        binding.btnStepBack.setText(wizardStep == 0 ? "Cancel" : "Back");

        updateWizardProgress();
        refreshStepPreview();
        if (wizardStep == 0 && pickedImage == null && pickedVideo == null) {
            binding.bgColorPickerRow.setVisibility(View.VISIBLE);
        }

        binding.statusFormContent.post(() -> {
            View parent = (View) binding.statusFormContent.getParent();
            if (parent instanceof androidx.core.widget.NestedScrollView) {
                ((androidx.core.widget.NestedScrollView) parent).smoothScrollTo(0, 0);
            }
        });
    }

    private void updateWizardProgress() {
        TextView[] dots = {binding.stepDot1, binding.stepDot2, binding.stepDot3};
        for (int i = 0; i < dots.length; i++) {
            boolean active = i <= wizardStep;
            dots[i].setBackgroundResource(active
                    ? R.drawable.bg_trim_gradient_button
                    : R.drawable.bg_trim_circle_btn);
            dots[i].setTextColor(ContextCompat.getColor(this,
                    active ? R.color.white : R.color.trim_text_secondary));
        }
        binding.stepLine1.setBackgroundColor(ContextCompat.getColor(this,
                wizardStep >= 1 ? R.color.trim_gradient_start : R.color.trim_divider));
        binding.stepLine2.setBackgroundColor(ContextCompat.getColor(this,
                wizardStep >= 2 ? R.color.trim_gradient_start : R.color.trim_divider));
        updateActiveStepRing();
    }

    /**
     * ✅ NEW: spins a vibrant-green ring behind the step dot currently in
     * use (wizardStep) — shared :core drawable/color, same pattern used on
     * Reel Upload / Reel Editor / Reel Photo Editor.
     */
    private void updateActiveStepRing() {
        ImageView[] rings = {binding.stepRing1, binding.stepRing2, binding.stepRing3};
        if (activeStepRingSpin != null) {
            activeStepRingSpin.cancel();
            activeStepRingSpin = null;
        }
        for (int i = 0; i < rings.length; i++) {
            if (rings[i] == null) continue;
            if (i == wizardStep) {
                rings[i].setVisibility(View.VISIBLE);
                rings[i].setRotation(0f);
                activeStepRingSpin = android.animation.ObjectAnimator.ofFloat(
                        rings[i], View.ROTATION, 0f, 360f);
                activeStepRingSpin.setDuration(1400);
                activeStepRingSpin.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
                activeStepRingSpin.setInterpolator(new android.view.animation.LinearInterpolator());
                activeStepRingSpin.start();
            } else {
                rings[i].setVisibility(View.GONE);
            }
        }
    }

    /**
     * Render the current text/media state into the persistent wizard preview.
     * This is deliberately called from every input and media transition.
     */
    private void refreshStepPreview() {
        if (binding == null) return;

        // "Advance Editing" only makes sense on Create, and only once media
        // (photo or video) is actually picked — hidden everywhere else,
        // including text-only statuses. Re-checked here (not just in
        // showWizardStep()) because picking or discarding media updates
        // pickedImage/pickedVideo without itself changing wizardStep.
        if (binding.btnAdvanceEditing != null) {
            binding.btnAdvanceEditing.setVisibility(
                    wizardStep == 0 && (pickedImage != null || pickedVideo != null)
                            ? View.VISIBLE : View.GONE);
        }

        String text = binding.etText.getText() == null
                ? "" : binding.etText.getText().toString().trim();
        boolean hasMedia = pickedImage != null || pickedVideo != null;

        binding.ivStepPreviewMedia.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        binding.tvStepPreviewEmpty.setVisibility(!hasMedia && text.isEmpty()
                ? View.VISIBLE : View.GONE);
        binding.tvStepPreviewText.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);

        if (hasMedia) {
            Uri media = pickedVideo != null ? pickedVideo : pickedImage;
            Glide.with(this).load(media).fitCenter().into(binding.ivStepPreviewMedia);
            binding.statusStepPreviewFrame.setBackgroundResource(
                    R.drawable.bg_trim_photo_rounded);
            binding.tvStepPreviewText.setGravity(
                    android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            binding.tvStepPreviewText.setTextColor(Color.WHITE);
        } else if (!text.isEmpty()) {
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(selectedBgColor);
            bg.setCornerRadius(dp(18));
            binding.statusStepPreviewFrame.setBackground(bg);
            binding.tvStepPreviewText.setGravity(textAlignGravity());
            binding.tvStepPreviewText.setTextColor(selectedTextColor);
        } else {
            binding.statusStepPreviewFrame.setBackgroundResource(
                    R.drawable.bg_trim_photo_rounded);
        }

        if (!text.isEmpty()) {
            binding.tvStepPreviewText.setText(StatusMentionHelper.highlight(text));
            applyFontStyle(binding.tvStepPreviewText, selectedFontStyle);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int textAlignGravity() {
        if (selectedTextAlign == null) return android.view.Gravity.CENTER;
        switch (selectedTextAlign) {
            case "left":  return android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
            case "right": return android.view.Gravity.END   | android.view.Gravity.CENTER_VERTICAL;
            default:      return android.view.Gravity.CENTER;
        }
    }

    // ── Sticker overlay frame setup ───────────────────────────────────────

    /**
     * Finds or creates the FrameLayout that wraps the status preview so
     * sticker overlay cards can be layered on top of it.
     */
    private void setupStickerOverlayFrame() {
        View frame = binding.getRoot().findViewWithTag("sticker_overlay_frame");
        if (frame instanceof android.widget.FrameLayout) {
            stickerOverlayFrame = (android.widget.FrameLayout) frame;
            editorBlurBackground = binding.getRoot().findViewById(R.id.iv_preview_blur_bg);
            mediaGestureOverlay = new View(this);
            mediaGestureOverlay.setBackgroundColor(Color.TRANSPARENT);
            mediaGestureOverlay.setClickable(true);
            mediaGestureOverlay.setContentDescription("Pinch to resize, twist to rotate, drag to move, double tap to reset");
            stickerOverlayFrame.addView(mediaGestureOverlay, 2,
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            drawingOverlay = new StatusDrawOverlayView(this);
            drawingOverlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            stickerOverlayFrame.addView(drawingOverlay, 3, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            editorSafeArea = new StorySafeAreaView(this);
            stickerOverlayFrame.addView(editorSafeArea, 4, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            sizeStoryCanvas();
            setupEditorGestures();
            setupStatusEditorTools();
        }
    }

    /**
     * Keep the editor canvas at story ratio so its preview and exported image match.
     * v587: also cap the height to ~the visible screen (narrowing + centring the canvas)
     * so the whole canvas fits without scrolling the page — the root cause of the
     * NestedScrollView stealing draw / drag gestures.
     */
    private void sizeStoryCanvas() {
        if (stickerOverlayFrame == null) return;
        stickerOverlayFrame.post(() -> {
            if (stickerOverlayFrame == null) return;
            View host = stickerOverlayFrame.getParent() instanceof View
                    ? (View) stickerOverlayFrame.getParent() : null;
            if (host == null) return;
            int availW = host.getWidth() - host.getPaddingLeft() - host.getPaddingRight();
            ViewGroup.LayoutParams lp = stickerOverlayFrame.getLayoutParams();
            if (availW <= 0 || lp == null) return;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                availW -= ((ViewGroup.MarginLayoutParams) lp).leftMargin
                        + ((ViewGroup.MarginLayoutParams) lp).rightMargin;
            }
            int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.80f);
            int h = Math.round(availW * (16f / 9f));
            int w = ViewGroup.LayoutParams.MATCH_PARENT;
            if (h > maxH) {
                h = maxH;
                w = Math.round(h * (9f / 16f));
            }
            boolean changed = lp.height != h || lp.width != w;
            lp.height = h;
            lp.width = w;
            if (lp instanceof LinearLayout.LayoutParams)
                ((LinearLayout.LayoutParams) lp).gravity = android.view.Gravity.CENTER_HORIZONTAL;
            if (changed) stickerOverlayFrame.setLayoutParams(lp);
        });
    }

    private int editorGesturePointerCount;
    private float editorLastTouchX, editorLastTouchY;
    private float editorPinchStartDistance;
    private float editorPinchStartMidX, editorPinchStartMidY;
    private float editorPinchStartScale;
    private float editorPinchStartTranslationX, editorPinchStartTranslationY;
    // v589: photo rotate + double-tap reset + tap detection
    private float editorMediaRotation;
    private float editorPinchStartAngle, editorPinchStartRotation;
    private boolean editorRotationSnapped;
    private boolean editorGestureMoved;
    private long editorGestureDownAt;
    private float editorDownX, editorDownY;
    private android.view.GestureDetector editorDoubleTapDetector;

    /**
     * Gestures run on a full-canvas transparent layer rather than on the
     * transformed ImageView. That keeps pointer coordinates stable at every
     * zoom level and still lets users grab the photo after zooming it smaller.
     */
    private void setupEditorGestures() {
        if (mediaGestureOverlay == null || binding.ivPreview == null) return;
        binding.ivPreview.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        final int tapSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        editorDoubleTapDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                        if (editorPhotoMode) toggleEditorFitFill();
                        return true;
                    }
                });
        mediaGestureOverlay.setOnClickListener(v -> {
            hideStickerSizeBar();
            if (!editorPhotoMode) toggleVideoPreview(); // tap on a video = play / pause
        });
        mediaGestureOverlay.setOnTouchListener((view, event) -> {
            if (!editorPhotoMode) return false;
            editorDoubleTapDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    binding.ivPreview.animate().cancel(); // never fight a running reset animation
                    showSafeAreaGuide();
                    setEditorGestureScrollLock(true);
                    editorGesturePointerCount = 1;
                    editorLastTouchX = event.getX();
                    editorLastTouchY = event.getY();
                    editorDownX = event.getX();
                    editorDownY = event.getY();
                    editorGestureMoved = false;
                    editorGestureDownAt = event.getEventTime();
                    return true;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    setEditorGestureScrollLock(true);
                    editorGestureMoved = true;
                    if (event.getPointerCount() >= 2) beginEditorPinch(event);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() >= 2) {
                        if (editorGesturePointerCount != 2) beginEditorPinch(event);
                        updateEditorPinch(event);
                    } else if (event.getPointerCount() == 1) {
                        // Rebase when a pinch becomes a one-finger drag so
                        // lifting one finger never causes a sudden photo jump.
                        if (editorGesturePointerCount != 1) {
                            editorGesturePointerCount = 1;
                            editorLastTouchX = event.getX(0);
                            editorLastTouchY = event.getY(0);
                            return true;
                        }
                        if (Math.hypot(event.getX(0) - editorDownX, event.getY(0) - editorDownY) > tapSlop)
                            editorGestureMoved = true;
                        // v589: 1:1 finger tracking (was dx*0.98 → photo trailed the finger)
                        float dx = event.getX(0) - editorLastTouchX;
                        float dy = event.getY(0) - editorLastTouchY;
                        binding.ivPreview.setTranslationX(binding.ivPreview.getTranslationX() + dx);
                        binding.ivPreview.setTranslationY(binding.ivPreview.getTranslationY() + dy);
                        clampEditorTranslation();
                    }
                    if (event.getPointerCount() == 1) {
                        editorLastTouchX = event.getX(0);
                        editorLastTouchY = event.getY(0);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() == 2) {
                        int remaining = event.getActionIndex() == 0 ? 1 : 0;
                        editorGesturePointerCount = 1;
                        editorLastTouchX = event.getX(remaining);
                        editorLastTouchY = event.getY(remaining);
                    } else {
                        // Rebase safely if an extra pointer is also involved.
                        editorGesturePointerCount = 0;
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    editorGesturePointerCount = 0;
                    setEditorGestureScrollLock(false);
                    hideSafeAreaGuideLater();
                    // a plain tap on empty canvas dismisses the sticker size bar
                    // (frame click never fired: this full-canvas layer consumes every touch)
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                            && !editorGestureMoved
                            && event.getEventTime() - editorGestureDownAt < 300) hideStickerSizeBar();
                    return true;
                default:
                    return true;
            }
        });
    }

    /**
     * The story canvas sits inside a NestedScrollView. Keep its vertical
     * drag/pinch stream here until all fingers are lifted so the parent cannot
     * steal ACTION_MOVE events and freeze the media transform.
     */
    private void setEditorGestureScrollLock(boolean locked) {
        if (mediaGestureOverlay == null) return;
        android.view.ViewParent parent = mediaGestureOverlay.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(locked);
            parent = parent.getParent();
        }
    }

    private void beginEditorPinch(android.view.MotionEvent event) {
        if (event.getPointerCount() < 2 || stickerOverlayFrame == null) return;
        float x1 = event.getX(0), y1 = event.getY(0);
        float x2 = event.getX(1), y2 = event.getY(1);
        editorPinchStartDistance = (float) Math.hypot(x2 - x1, y2 - y1);
        if (editorPinchStartDistance < 1f) editorPinchStartDistance = 1f;
        editorPinchStartMidX = (x1 + x2) * 0.5f;
        editorPinchStartMidY = (y1 + y2) * 0.5f;
        editorPinchStartScale = editorMediaScale;
        editorPinchStartTranslationX = binding.ivPreview.getTranslationX();
        editorPinchStartTranslationY = binding.ivPreview.getTranslationY();
        editorPinchStartAngle = (float) Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
        editorPinchStartRotation = editorMediaRotation;
        editorRotationSnapped = false;
        editorGesturePointerCount = 2;
    }

    /** Scale from the original pinch baseline and keep the content under the midpoint anchored. */
    private void updateEditorPinch(android.view.MotionEvent event) {
        if (event.getPointerCount() < 2 || editorPinchStartDistance <= 0f
                || stickerOverlayFrame == null) return;
        float x1 = event.getX(0), y1 = event.getY(0);
        float x2 = event.getX(1), y2 = event.getY(1);
        float midpointX = (x1 + x2) * 0.5f;
        float midpointY = (y1 + y2) * 0.5f;
        float distance = (float) Math.hypot(x2 - x1, y2 - y1);
        float ratio = distance / editorPinchStartDistance;
        float nextScale = Math.max(0.15f, Math.min(5f, editorPinchStartScale * ratio));
        float effectiveRatio = nextScale / Math.max(0.01f, editorPinchStartScale);
        float centerX = stickerOverlayFrame.getWidth() * 0.5f;
        float centerY = stickerOverlayFrame.getHeight() * 0.5f;
        float startAnchorX = editorPinchStartMidX - centerX - editorPinchStartTranslationX;
        float startAnchorY = editorPinchStartMidY - centerY - editorPinchStartTranslationY;

        // v589: two-finger twist rotates the photo; snaps to 0/90/180/270 within 4°.
        float angle = (float) Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
        float rotation = editorPinchStartRotation + (angle - editorPinchStartAngle);
        float nearest = Math.round(rotation / 90f) * 90f;
        if (Math.abs(rotation - nearest) < 4f) {
            rotation = nearest;
            if (!editorRotationSnapped) {
                editorRotationSnapped = true;
                binding.ivPreview.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        } else {
            editorRotationSnapped = false;
        }
        double rad = Math.toRadians(rotation - editorPinchStartRotation);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        // keep the content point that started under the fingers' midpoint under it
        float rotAnchorX = cos * startAnchorX - sin * startAnchorY;
        float rotAnchorY = sin * startAnchorX + cos * startAnchorY;

        editorMediaScale = nextScale;
        editorMediaRotation = rotation;
        binding.ivPreview.setScaleX(nextScale);
        binding.ivPreview.setScaleY(nextScale);
        binding.ivPreview.setRotation(rotation);
        binding.ivPreview.setTranslationX(midpointX - centerX - effectiveRatio * rotAnchorX);
        binding.ivPreview.setTranslationY(midpointY - centerY - effectiveRatio * rotAnchorY);
        clampEditorTranslation();
    }

    // ── v589: real video preview in the status editor ─────────────────────
    private VideoView editorVideoView;
    private android.media.MediaPlayer editorVideoMp; // for Mix Audio original-volume preview
    private boolean editorVideoWantsPlay;
    private boolean editorVideoToolHintShown;
    private final java.util.HashMap<String, View> editorToolRows = new java.util.HashMap<>();

    /** Plays the picked video (muted, looping) on the canvas; the Glide thumbnail stays underneath as fallback. */
    private void startVideoPreview(Uri uri) {
        if (stickerOverlayFrame == null || uri == null) return;
        if (editorVideoView == null) {
            editorVideoView = new VideoView(this);
            editorVideoView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // index 1: above the blurred backdrop, below the thumbnail / gesture / sticker layers
            stickerOverlayFrame.addView(editorVideoView, 1, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER));
        }
        editorVideoView.setVisibility(View.VISIBLE);
        binding.ivPreview.animate().cancel();
        binding.ivPreview.setAlpha(1f);
        editorVideoWantsPlay = true;
        editorVideoView.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            editorVideoMp = mp;
            applyEditorVideoVolume(); // muted, unless a Mix Audio music sticker sets the original volume
            mp.setOnInfoListener((m, what, extra) -> {
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                    binding.ivPreview.animate().alpha(0f).setDuration(120).start(); // thumbnail → live frame
                return false;
            });
            syncVideoPreviewPlayback();
        });
        editorVideoView.setOnErrorListener((mp, what, extra) -> {
            stopVideoPreview(); // keep the static thumbnail
            binding.ivVideoHint.setVisibility(View.VISIBLE);
            return true;
        });
        try {
            editorVideoView.setVideoURI(uri);
        } catch (Exception e) {
            stopVideoPreview();
        }
        binding.ivVideoHint.setVisibility(View.VISIBLE); // until the first frame is playing
    }

    /**
     * Video preview volume. Muted by default (a Music sticker may be previewing at the same time);
     * with a Mix Audio music sticker that carries an original-volume choice (origVol >= 0) the
     * poster hears the video's own audio at that level, mixed with the song.
     */
    private void applyEditorVideoVolume() {
        if (editorVideoMp == null) return;
        float v = 0f;
        StatusStickerOverlayView st = musicStickerInComposer;
        if (st != null && st.hasMusicMix() && st.getMusicOrigVol() >= 0f) v = st.getMusicOrigVol();
        try { editorVideoMp.setVolume(v, v); } catch (Exception ignored) {}
    }

    private void stopVideoPreview() {
        editorVideoMp = null;
        editorVideoWantsPlay = false;
        if (editorVideoView != null) {
            try { editorVideoView.stopPlayback(); } catch (Exception ignored) {}
            editorVideoView.setVisibility(View.GONE);
        }
        if (binding != null && binding.ivPreview != null) {
            binding.ivPreview.animate().cancel();
            binding.ivPreview.setAlpha(1f);
        }
    }

    /** Play only while the screen is in front, on the Edit step, and the person hasn't paused it. */
    private void syncVideoPreviewPlayback() {
        if (editorVideoView == null || editorVideoView.getVisibility() != View.VISIBLE) return;
        boolean shouldPlay = editorVideoWantsPlay && composerResumed && wizardStep == 1;
        try {
            if (shouldPlay && !editorVideoView.isPlaying()) editorVideoView.start();
            else if (!shouldPlay && editorVideoView.isPlaying()) editorVideoView.pause();
        } catch (Exception ignored) {}
        if (binding != null && binding.ivVideoHint != null && pickedVideo != null)
            binding.ivVideoHint.setVisibility(editorVideoWantsPlay ? View.GONE : View.VISIBLE);
    }

    private void toggleVideoPreview() {
        if (pickedVideo == null || editorVideoView == null
                || editorVideoView.getVisibility() != View.VISIBLE) return;
        editorVideoWantsPlay = !editorVideoWantsPlay;
        syncVideoPreviewPlayback();
    }

    /** Photo-only tools (Save, Label AI) look disabled while a video is loaded. */
    private void updateEditorRailForMedia() {
        boolean video = pickedVideo != null;
        for (String name : new String[]{"Save", "Label AI"}) {
            View row = editorToolRows.get(name);
            if (row != null) row.setAlpha(video ? 0.4f : 1f);
        }
    }

    private void showSafeAreaGuide() { if (editorSafeArea != null) editorSafeArea.show(); }
    private void hideSafeAreaGuideLater() {
        if (editorSafeArea != null && !editorSafeAreaPinned) editorSafeArea.hideLater(900L);
    }

    /**
     * Double-tap: FIT (whole photo visible, blurred bars) <-> FILL (photo covers the whole 9:16 canvas).
     * Anything else (zoomed / moved / rotated) goes back to FIT first.
     */
    private void toggleEditorFitFill() {
        if (binding == null || binding.ivPreview == null || stickerOverlayFrame == null) return;
        android.graphics.drawable.Drawable d = binding.ivPreview.getDrawable();
        float fw = stickerOverlayFrame.getWidth(), fh = stickerOverlayFrame.getHeight();
        boolean atFit = Math.abs(editorMediaScale - 1f) < 0.02f
                && Math.abs(binding.ivPreview.getTranslationX()) < 2f
                && Math.abs(binding.ivPreview.getTranslationY()) < 2f
                && Math.abs(editorMediaRotation) < 0.5f;
        if (!atFit || d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0 || fw <= 0 || fh <= 0) {
            resetEditorMediaTransform(true);
            return;
        }
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        float fit  = Math.min(fw / dw, fh / dh);
        float fill = Math.max(fw / dw, fh / dh);
        float ratio = fill / fit;
        if (ratio < 1.03f) { toast("Photo pehle se poora frame bhar rahi hai"); return; }
        editorMediaScale = ratio;
        binding.ivPreview.animate().scaleX(ratio).scaleY(ratio).translationX(0f).translationY(0f)
                .setDuration(200).start();
        toast("Fill — kinare kat sakte hain. Dubara double-tap = poori photo");
    }

    /** Back to the untransformed photo (double-tap, More → Reset photo position). */
    private void resetEditorMediaTransform(boolean animate) {
        editorMediaScale = 1f;
        editorMediaRotation = 0f;
        editorRotationSnapped = false;
        if (binding == null || binding.ivPreview == null) return;
        if (animate) {
            binding.ivPreview.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                    .rotation(0f).setDuration(200).start();
        } else {
            binding.ivPreview.animate().cancel();
            binding.ivPreview.setScaleX(1f);
            binding.ivPreview.setScaleY(1f);
            binding.ivPreview.setTranslationX(0f);
            binding.ivPreview.setTranslationY(0f);
            binding.ivPreview.setRotation(0f);
        }
    }

    private void clampEditorTranslation() {
        if (binding == null || binding.ivPreview == null || stickerOverlayFrame == null) return;
        float maxX = stickerOverlayFrame.getWidth() * editorMediaScale * 0.5f;
        float maxY = stickerOverlayFrame.getHeight() * editorMediaScale * 0.5f;
        float x = Math.max(-maxX, Math.min(maxX, binding.ivPreview.getTranslationX()));
        float y = Math.max(-maxY, Math.min(maxY, binding.ivPreview.getTranslationY()));
        binding.ivPreview.setTranslationX(x);
        binding.ivPreview.setTranslationY(y);
    }

    /** Compact Instagram-style editor controls anchored to the right edge of the story canvas. */
    private void setupStatusEditorTools() {
        if (stickerOverlayFrame == null || editorToolRail != null) return;
        editorToolRail = new LinearLayout(this);
        editorToolRail.setOrientation(LinearLayout.VERTICAL);
        editorToolRail.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        editorToolRail.setPadding(0, dp(4), dp(2), dp(4));
        editorToolRail.setTag("status_editor_tool_rail");

        String[][] tools = {
                {"Text", "Aa"}, {"Stickers", "☺"}, {"Music", "♫"},
                {"Restyle", "✎"}, {"Effects", "✦"}, {"Mention", "@"},
                {"Draw", "〰"}, {"Guide", "▭"}, {"Save", "↓"}, {"Label AI", "▣"}, {"More", "···"}
        };
        for (String[] tool : tools) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
            row.setContentDescription(tool[0]);
            TextView label = new TextView(this);
            label.setText(tool[0]);
            label.setTextColor(Color.WHITE);
            label.setTextSize(10);
            label.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
            label.setShadowLayer(dp(2), 0, dp(1), 0xCC000000);
            label.setMaxLines(1);
            label.setPadding(0, 0, dp(5), 0);
            row.addView(label, new LinearLayout.LayoutParams(dp(57), -2));

            TextView icon = new TextView(this);
            icon.setText(tool[1]);
            icon.setTextColor(Color.WHITE);
            icon.setTextSize(tool[1].length() > 1 ? 13 : 20);
            icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            icon.setGravity(android.view.Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xCC25272B);
            circle.setStroke(dp(1), 0x66FFFFFF);
            icon.setBackground(circle);
            row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
            if ("Draw".equals(tool[0])) editorDrawIcon = icon;
            editorToolRows.put(tool[0], row);
            row.setOnClickListener(v -> onStatusEditorTool(tool[0], row));
            editorToolRail.addView(row, new LinearLayout.LayoutParams(-2, 0, 1f));
        }
        android.widget.FrameLayout.LayoutParams railLp =
                new android.widget.FrameLayout.LayoutParams(-2, -1,
                        android.view.Gravity.TOP | android.view.Gravity.END);
        stickerOverlayFrame.addView(editorToolRail, railLp);
        editorToolRail.bringToFront();
        buildDrawBar();
    }

    /** v587: highlighted (white) vs normal (dark) state for a rail icon. */
    private void setRailIconActive(TextView icon, boolean active) {
        if (icon == null) return;
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(active ? 0xFFFFFFFF : 0xCC25272B);
        circle.setStroke(dp(1), active ? 0xFFFFFFFF : 0x66FFFFFF);
        icon.setBackground(circle);
        icon.setTextColor(active ? 0xFF111111 : Color.WHITE);
    }

    private TextView drawBarButton(String label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(android.view.Gravity.CENTER);
        t.setMinWidth(dp(44));
        t.setMinHeight(dp(36));
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        t.setOnClickListener(click);
        t.setContentDescription(label);
        return t;
    }

    private void setDrawBarActive(TextView t, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(active ? 0x55FFFFFF : Color.TRANSPARENT);
        t.setBackground(bg);
    }

    /** v587: colour / size / eraser / undo-redo toolbar shown only while Draw mode is on. */
    private void buildDrawBar() {
        if (stickerOverlayFrame == null || drawingOverlay == null || editorDrawBar != null) return;
        editorDrawBar = new LinearLayout(this);
        editorDrawBar.setOrientation(LinearLayout.VERTICAL);
        editorDrawBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setCornerRadius(dp(18));
        barBg.setColor(0xE61A1A1A);
        editorDrawBar.setBackground(barBg);
        editorDrawBar.setVisibility(View.GONE);
        editorDrawBar.setClickable(true); // never let touches fall through to the canvas

        // Row 1 — colours
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        colors.setGravity(android.view.Gravity.CENTER_VERTICAL);
        editorSwatchBgs.clear();
        for (int i = 0; i < DRAW_COLORS.length; i++) {
            final int idx = i;
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(DRAW_COLORS[i]);
            sw.setBackground(g);
            editorSwatchBgs.add(g);
            sw.setContentDescription("Brush colour " + (i + 1));
            sw.setOnClickListener(v -> {
                editorDrawColorIdx = idx;
                drawingOverlay.setBrushColor(DRAW_COLORS[idx]);
                refreshDrawBar();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            colors.addView(sw, lp);
        }
        editorDrawBar.addView(colors, new LinearLayout.LayoutParams(-2, -2));

        // Row 2 — brush size + eraser
        LinearLayout sizes = new LinearLayout(this);
        sizes.setOrientation(LinearLayout.HORIZONTAL);
        sizes.setGravity(android.view.Gravity.CENTER_VERTICAL);
        editorSizeBtns.clear();
        String[] sizeLabels = {"S", "M", "L"};
        for (int i = 0; i < sizeLabels.length; i++) {
            final int idx = i;
            TextView b = drawBarButton(sizeLabels[i], v -> {
                editorDrawSizeIdx = idx;
                drawingOverlay.setBrushWidthPx(dp(DRAW_SIZES_DP[idx]));
                refreshDrawBar();
            });
            editorSizeBtns.add(b);
            sizes.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }
        editorEraserBtn = drawBarButton("Erase", v -> {
            drawingOverlay.setEraser(!drawingOverlay.isEraser());
            if (!drawingOverlay.isEraser()) drawingOverlay.setBrushColor(DRAW_COLORS[editorDrawColorIdx]);
            refreshDrawBar();
        });
        sizes.addView(editorEraserBtn, new LinearLayout.LayoutParams(-2, -2));
        editorDrawBar.addView(sizes, new LinearLayout.LayoutParams(-2, -2));

        // Row 3 — undo / redo / done
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        editorUndoBtn = drawBarButton("Undo", v -> drawingOverlay.undo());
        editorRedoBtn = drawBarButton("Redo", v -> drawingOverlay.redo());
        TextView done = drawBarButton("Done", v -> {
            editorDrawMode = false;
            drawingOverlay.setDrawEnabled(false);
        });
        actions.addView(editorUndoBtn, new LinearLayout.LayoutParams(-2, -2));
        actions.addView(editorRedoBtn, new LinearLayout.LayoutParams(-2, -2));
        actions.addView(done, new LinearLayout.LayoutParams(-2, -2));
        editorDrawBar.addView(actions, new LinearLayout.LayoutParams(-2, -2));

        android.widget.FrameLayout.LayoutParams barLp = new android.widget.FrameLayout.LayoutParams(
                -2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.START);
        barLp.setMargins(dp(8), 0, 0, dp(12));
        stickerOverlayFrame.addView(editorDrawBar, barLp);

        drawingOverlay.setBrushColor(DRAW_COLORS[editorDrawColorIdx]);
        drawingOverlay.setBrushWidthPx(dp(DRAW_SIZES_DP[editorDrawSizeIdx]));
        drawingOverlay.setListener(new StatusDrawOverlayView.Listener() {
            @Override public void onModeChanged(boolean enabled) {
                editorDrawMode = enabled;
                setRailIconActive(editorDrawIcon, enabled);
                if (editorDrawBar == null) return;
                editorDrawBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
                if (enabled) {
                    // keep the bar clear of the right-hand tool rail
                    editorDrawBar.bringToFront();
                    refreshDrawBar();
                }
            }
            @Override public void onHistoryChanged(boolean canUndo, boolean canRedo) {
                if (editorUndoBtn != null) editorUndoBtn.setAlpha(canUndo ? 1f : 0.35f);
                if (editorRedoBtn != null) editorRedoBtn.setAlpha(canRedo ? 1f : 0.35f);
            }
        });
        setRailIconActive(editorDrawIcon, false);
        refreshDrawBar();
        if (editorUndoBtn != null) editorUndoBtn.setAlpha(0.35f);
        if (editorRedoBtn != null) editorRedoBtn.setAlpha(0.35f);
    }

    private void refreshDrawBar() {
        if (drawingOverlay == null) return;
        boolean eraser = drawingOverlay.isEraser();
        for (int i = 0; i < editorSwatchBgs.size(); i++) {
            boolean sel = !eraser && i == editorDrawColorIdx;
            editorSwatchBgs.get(i).setStroke(sel ? dp(3) : dp(1), sel ? 0xFFFFFFFF : 0x66FFFFFF);
        }
        for (int i = 0; i < editorSizeBtns.size(); i++)
            setDrawBarActive(editorSizeBtns.get(i), i == editorDrawSizeIdx);
        if (editorEraserBtn != null) setDrawBarActive(editorEraserBtn, eraser);
    }

    private void onStatusEditorTool(String action, View anchor) {
        if (pickedImage == null && pickedVideo == null) {
            toast("Pehle photo ya video select karo");
            return;
        }
        if (pickedVideo != null) {
            if ("Text".equals(action) || "Mention".equals(action)
                    || "Restyle".equals(action) || "Effects".equals(action)
                    || "Draw".equals(action)) {
                // ReelEditor already bakes these edits into video output.
                if (!editorVideoToolHintShown) {
                    editorVideoToolHintShown = true;
                    toast("Video par text/draw/effects Reel editor me hote hain");
                }
                openAdvanceEditingForPickedVideo();
                return;
            }
            if ("Label AI".equals(action)) {
                toast("AI label abhi photo status ke liye available hai");
                return;
            }
        }
        switch (action) {
            case "Guide":
                editorSafeAreaPinned = !editorSafeAreaPinned;
                if (editorSafeArea != null) {
                    if (editorSafeAreaPinned) editorSafeArea.show(); else editorSafeArea.hideLater(0L);
                }
                View gRow = editorToolRows.get("Guide");
                if (gRow instanceof LinearLayout && ((LinearLayout) gRow).getChildCount() > 1
                        && ((LinearLayout) gRow).getChildAt(1) instanceof TextView)
                    setRailIconActive((TextView) ((LinearLayout) gRow).getChildAt(1), editorSafeAreaPinned);
                break;
            case "Text":
                showEditorTextDialog(false);
                break;
            case "Stickers":
            case "Music":
                openStickerPicker();
                break;
            case "Restyle":
            case "Effects":
                openAdvanceEditingForPickedMedia();
                break;
            case "Mention":
                showEditorTextDialog(true);
                break;
            case "Draw":
                if (drawingOverlay != null) {
                    editorDrawMode = !drawingOverlay.isDrawEnabled();
                    drawingOverlay.setDrawEnabled(editorDrawMode); // listener updates bar + icon
                }
                break;
            case "Save":
                saveEditorPreviewToGallery();
                break;
            case "Label AI":
                toggleAiLabel();
                break;
            case "More":
                showEditorMoreMenu(anchor);
                break;
        }
    }

    // ── v587: text tool style state ───────────────────────────────────────
    private int editorTextColorIdx = 0;
    private int editorTextFontIdx = 1;   // Bold (previous fixed look)
    private int editorTextAlignIdx = 0;  // 0 center, 1 left, 2 right
    private int editorTextBgMode = 0;    // 0 none, 1 solid, 2 dim
    private int editorTextSizeSp = 30;
    private static final String[] TEXT_FONT_NAMES = {"Classic", "Bold", "Serif", "Mono", "Italic"};
    private static final String[] TEXT_ALIGN_NAMES = {"Center", "Left", "Right"};
    private static final String[] TEXT_BG_NAMES = {"No bg", "Solid", "Dim"};

    private Typeface editorTextTypeface(int idx) {
        switch (idx) {
            case 0: return Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
            case 2: return Typeface.create(Typeface.SERIF, Typeface.BOLD);
            case 3: return Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            case 4: return Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC);
            default: return Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        }
    }

    private static boolean isLightColor(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f > 0.6f;
    }

    /** Applies the current text style to the on-canvas overlay or the dialog's live preview. */
    private void applyEditorTextStyle(TextView tv) {
        int color = DRAW_COLORS[editorTextColorIdx];
        tv.setTypeface(editorTextTypeface(editorTextFontIdx));
        tv.setTextSize(editorTextSizeSp);
        int hg = editorTextAlignIdx == 1 ? android.view.Gravity.START
                : editorTextAlignIdx == 2 ? android.view.Gravity.END
                : android.view.Gravity.CENTER_HORIZONTAL;
        tv.setGravity(android.view.Gravity.CENTER_VERTICAL | hg);
        if (editorTextBgMode == 1) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(dp(10));
            tv.setBackground(bg);
            tv.setTextColor(isLightColor(color) ? Color.BLACK : Color.WHITE);
            tv.setShadowLayer(0, 0, 0, 0);
        } else if (editorTextBgMode == 2) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x99000000);
            bg.setCornerRadius(dp(10));
            tv.setBackground(bg);
            tv.setTextColor(color);
            tv.setShadowLayer(0, 0, 0, 0);
        } else {
            tv.setBackground(null);
            tv.setTextColor(color);
            tv.setShadowLayer(dp(4), 0, dp(2), isLightColor(color) ? Color.BLACK : Color.WHITE);
        }
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
    }

    private void showEditorTextDialog(boolean mentionOnly) {
        // snapshot so Cancel / back restores the previous style
        final int sColor = editorTextColorIdx, sFont = editorTextFontIdx, sAlign = editorTextAlignIdx,
                sBg = editorTextBgMode, sSize = editorTextSizeSp;
        final Runnable restore = () -> {
            editorTextColorIdx = sColor; editorTextFontIdx = sFont; editorTextAlignIdx = sAlign;
            editorTextBgMode = sBg; editorTextSizeSp = sSize;
        };

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xFF1A1A1A);
        rootBg.setCornerRadius(dp(16));
        root.setBackground(rootBg);

        EditText input = new EditText(this);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setHint(mentionOnly ? "@username" : "Type something");
        input.setHintTextColor(0x88FFFFFF);
        String current = editorTextOverlay == null ? "" : editorTextOverlay.getText().toString();
        if (mentionOnly && !current.startsWith("@")) current = "";
        input.setText(current);
        input.setSelection(input.length());
        applyEditorTextStyle(input);
        root.addView(input, new LinearLayout.LayoutParams(-1, -2));

        // colours
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        colors.setGravity(android.view.Gravity.CENTER);
        final java.util.ArrayList<GradientDrawable> swatches = new java.util.ArrayList<>();
        final Runnable refreshSwatches = () -> {
            for (int i = 0; i < swatches.size(); i++) {
                boolean sel = i == editorTextColorIdx;
                swatches.get(i).setStroke(sel ? dp(3) : dp(1), sel ? 0xFFFFFFFF : 0x66FFFFFF);
            }
        };
        for (int i = 0; i < DRAW_COLORS.length; i++) {
            final int idx = i;
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(DRAW_COLORS[i]);
            sw.setBackground(g);
            swatches.add(g);
            sw.setContentDescription("Text colour " + (i + 1));
            sw.setOnClickListener(v -> {
                editorTextColorIdx = idx;
                applyEditorTextStyle(input);
                refreshSwatches.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.setMargins(dp(3), dp(10), dp(3), dp(6));
            colors.addView(sw, lp);
        }
        refreshSwatches.run();
        root.addView(colors, new LinearLayout.LayoutParams(-1, -2));

        // font / align / background cyclers
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(android.view.Gravity.CENTER);
        final TextView[] chip = new TextView[3];
        final Runnable refreshChips = () -> {
            chip[0].setText(TEXT_FONT_NAMES[editorTextFontIdx]);
            chip[1].setText(TEXT_ALIGN_NAMES[editorTextAlignIdx]);
            chip[2].setText(TEXT_BG_NAMES[editorTextBgMode]);
        };
        chip[0] = drawBarButton("", v -> {
            editorTextFontIdx = (editorTextFontIdx + 1) % TEXT_FONT_NAMES.length;
            applyEditorTextStyle(input);
            ((TextView) v).setText(TEXT_FONT_NAMES[editorTextFontIdx]);
        });
        chip[1] = drawBarButton("", v -> {
            editorTextAlignIdx = (editorTextAlignIdx + 1) % TEXT_ALIGN_NAMES.length;
            applyEditorTextStyle(input);
            ((TextView) v).setText(TEXT_ALIGN_NAMES[editorTextAlignIdx]);
        });
        chip[2] = drawBarButton("", v -> {
            editorTextBgMode = (editorTextBgMode + 1) % TEXT_BG_NAMES.length;
            applyEditorTextStyle(input);
            ((TextView) v).setText(TEXT_BG_NAMES[editorTextBgMode]);
        });
        for (TextView c : chip) {
            setDrawBarActive(c, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(4), 0, dp(4), 0);
            chips.addView(c, lp);
        }
        refreshChips.run();
        root.addView(chips, new LinearLayout.LayoutParams(-1, -2));

        // size
        SeekBar size = new SeekBar(this);
        size.setMax(40); // 16sp … 56sp
        size.setProgress(Math.max(0, Math.min(40, editorTextSizeSp - 16)));
        size.setContentDescription("Text size");
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                editorTextSizeSp = 16 + progress;
                applyEditorTextStyle(input);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(-1, -2);
        sizeLp.topMargin = dp(10);
        root.addView(size, sizeLp);

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(mentionOnly ? "Add mention" : "Add text")
                .setView(root)
                .setPositiveButton("Done", (dialog, which) -> {
                    String entered = input.getText() == null ? "" : input.getText().toString().trim();
                    if (mentionOnly && !entered.isEmpty() && !entered.startsWith("@")) entered = "@" + entered;
                    if (!entered.isEmpty()) {
                        if (mentionOnly) {
                            String existing = binding.etText.getText() == null
                                    ? "" : binding.etText.getText().toString().trim();
                            String combined = existing.isEmpty() ? entered : existing + " " + entered;
                            binding.etText.setText(combined); // keep server-side mention resolution working
                            setEditorTextOverlay(combined);
                        } else {
                            setEditorTextOverlay(entered);
                        }
                    } else {
                        restore.run();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> restore.run())
                .setOnCancelListener(dialog -> restore.run());
        if (editorTextOverlay != null && !mentionOnly) {
            builder.setNeutralButton("Remove", (dialog, which) -> setEditorTextOverlay(""));
        }
        AlertDialogStyler.showRounded(builder.create());
    }

    private void setEditorTextOverlay(String text) {
        if (stickerOverlayFrame == null) return;
        if (text == null || text.trim().isEmpty()) {
            if (editorTextOverlay != null) stickerOverlayFrame.removeView(editorTextOverlay);
            editorTextOverlay = null;
            return;
        }
        if (editorTextOverlay == null) {
            editorTextOverlay = new TextView(this);
            android.widget.FrameLayout.LayoutParams textLp =
                    new android.widget.FrameLayout.LayoutParams(-2, -2,
                            android.view.Gravity.CENTER);
            stickerOverlayFrame.addView(editorTextOverlay, textLp);
            editorTextOverlay.setContentDescription("Text overlay. Tap to edit, drag to move, pinch to resize and rotate.");
            attachEditorTextGestures(editorTextOverlay);
        }
        editorTextOverlay.setMaxWidth(Math.max(dp(120), stickerOverlayFrame.getWidth() - dp(105)));
        applyEditorTextStyle(editorTextOverlay);
        editorTextOverlay.setText(text);
        editorTextOverlay.setVisibility(View.VISIBLE);
        if (editorToolRail != null) editorToolRail.bringToFront();
        if (editorDrawBar != null && editorDrawBar.getVisibility() == View.VISIBLE) editorDrawBar.bringToFront();
    }

    /** Canvas-space (parent) position of pointer i — stays stable while the view itself rotates/scales. */
    private float[] textPointerInParent(View v, android.view.MotionEvent e, int i) {
        float[] pt = {e.getX(i), e.getY(i)};
        v.getMatrix().mapPoints(pt);
        pt[0] += v.getX();
        pt[1] += v.getY();
        return pt;
    }

    /** One finger: drag (tap = edit). Two fingers: pinch to resize + twist to rotate. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void attachEditorTextGestures(final TextView tv) {
        final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] startRaw = new float[2], startPos = new float[2];
        final float[] dist0 = {1f}, scale0 = {1f}, angle0 = {0f}, rot0 = {0f};
        final boolean[] moved = {false}, rebase = {false};
        final long[] downAt = {0L};
        tv.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    showSafeAreaGuide();
                    setEditorGestureScrollLock(true);
                    startRaw[0] = ev.getRawX(); startRaw[1] = ev.getRawY();
                    startPos[0] = view.getX();  startPos[1] = view.getY();
                    moved[0] = false; rebase[0] = false;
                    downAt[0] = ev.getEventTime();
                    return true;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    setEditorGestureScrollLock(true);
                    moved[0] = true;
                    rebase[0] = true;
                    if (ev.getPointerCount() >= 2) {
                        float[] a = textPointerInParent(view, ev, 0), b = textPointerInParent(view, ev, 1);
                        dist0[0] = Math.max(1f, (float) Math.hypot(b[0] - a[0], b[1] - a[1]));
                        angle0[0] = (float) Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
                        scale0[0] = view.getScaleX();
                        rot0[0] = view.getRotation();
                    }
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (ev.getPointerCount() >= 2) {
                        float[] a = textPointerInParent(view, ev, 0), b = textPointerInParent(view, ev, 1);
                        float dist = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                        float angle = (float) Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
                        float ns = Math.max(0.4f, Math.min(4f, scale0[0] * dist / dist0[0]));
                        view.setScaleX(ns);
                        view.setScaleY(ns);
                        view.setRotation(rot0[0] + (angle - angle0[0]));
                    } else if (ev.getPointerCount() == 1) {
                        if (rebase[0]) {
                            startRaw[0] = ev.getRawX(); startRaw[1] = ev.getRawY();
                            startPos[0] = view.getX();  startPos[1] = view.getY();
                            rebase[0] = false;
                        } else {
                            float dx = ev.getRawX() - startRaw[0], dy = ev.getRawY() - startRaw[1];
                            if (!moved[0] && Math.hypot(dx, dy) < slop) return true;
                            moved[0] = true;
                            view.setX(startPos[0] + dx);
                            view.setY(startPos[1] + dy);
                            clampEditorTextPosition(view);
                        }
                    }
                    return true;
                case android.view.MotionEvent.ACTION_POINTER_UP:
                    rebase[0] = true;
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    setEditorGestureScrollLock(false);
                    hideSafeAreaGuideLater();
                    if (!moved[0] && ev.getEventTime() - downAt[0] < 300) {
                        view.post(() -> showEditorTextDialog(false)); // tap → edit
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    setEditorGestureScrollLock(false);
                    hideSafeAreaGuideLater();
                    return true;
                default:
                    return true;
            }
        });
    }

    /** Keep the text's centre on the canvas and out of the right-hand tool-icon column. */
    private void clampEditorTextPosition(View v) {
        if (stickerOverlayFrame == null) return;
        float fw = stickerOverlayFrame.getWidth(), fh = stickerOverlayFrame.getHeight();
        float cx = v.getX() + v.getWidth() / 2f, cy = v.getY() + v.getHeight() / 2f;
        cx = Math.max(dp(24), Math.min(fw - dp(48), cx));
        cy = Math.max(dp(24), Math.min(fh - dp(24), cy));
        v.setX(cx - v.getWidth() / 2f);
        v.setY(cy - v.getHeight() / 2f);
    }

    private void toggleAiLabel() {
        editorAiLabelEnabled = !editorAiLabelEnabled;
        if (editorAiLabel == null && stickerOverlayFrame != null) {
            editorAiLabel = new TextView(this);
            editorAiLabel.setText("AI-generated");
            editorAiLabel.setTextColor(Color.WHITE);
            editorAiLabel.setTextSize(11);
            editorAiLabel.setPadding(dp(10), dp(6), dp(10), dp(6));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC202124);
            bg.setCornerRadius(dp(16));
            editorAiLabel.setBackground(bg);
            stickerOverlayFrame.addView(editorAiLabel,
                    new android.widget.FrameLayout.LayoutParams(-2, -2,
                            android.view.Gravity.BOTTOM | android.view.Gravity.START));
        }
        if (editorAiLabel != null) editorAiLabel.setVisibility(editorAiLabelEnabled ? View.VISIBLE : View.GONE);
        if (editorToolRail != null) editorToolRail.bringToFront();
        toast(editorAiLabelEnabled ? "AI label added to preview" : "AI label removed");
    }

    private void showEditorMoreMenu(View anchor) {
        if (anchor == null) anchor = editorToolRail;
        if (anchor == null) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add("Reset photo position");
        menu.getMenu().add("Clear drawing");
        menu.getMenu().add("Remove text");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Reset photo position".equals(title)) {
                resetEditorMediaTransform(true);
            } else if ("Clear drawing".equals(title) && drawingOverlay != null) {
                drawingOverlay.clearDrawing();
            } else if ("Remove text".equals(title)) {
                setEditorTextOverlay("");
            }
            return true;
        });
        menu.show();
    }

    private void saveEditorPreviewToGallery() {
        if (pickedImage == null || stickerOverlayFrame == null) {
            toast("Gallery save is available for photos");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 29
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            if (legacySavePermissionLauncher != null)
                legacySavePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        final Bitmap preview;
        try {
            preview = renderStoryCanvas(true);
        } catch (Exception e) {
            toast("Preview is still loading — try again in a moment");
            return;
        }
        new Thread(() -> {
            Uri savedUri = null;
            try {
                String name = "CallX_Status_" + System.currentTimeMillis() + ".jpg";
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/CallX/Statuses");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    savedUri = getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (savedUri == null) throw new java.io.IOException("Could not create gallery image");
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(savedUri)) {
                        if (out == null || !preview.compress(Bitmap.CompressFormat.JPEG, 94, out))
                            throw new java.io.IOException("Could not write gallery image");
                    }
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(savedUri, ready, null, null);
                } else {
                    String path = MediaStore.Images.Media.insertImage(
                            getContentResolver(), preview, name, "CallX status preview");
                    if (path == null) throw new java.io.IOException("Could not save gallery image");
                    savedUri = Uri.parse(path);
                }
            } catch (Exception e) {
                if (savedUri != null) {
                    try { getContentResolver().delete(savedUri, null, null); } catch (Exception ignored) {}
                }
            } finally {
                preview.recycle();
            }
            final Uri result = savedUri;
            runOnUiThread(() -> {
                if (result != null) toast("Saved to Pictures/CallX/Statuses");
                else toast("Could not save story preview");
            });
        }, "status-gallery-save").start();
    }

    /**
     * Snapshot the actual editor layers at story ratio. Tool buttons and
     * media controls are excluded; interactive stickers are excluded for
     * status uploads (they are stored as JSON and rendered by the viewer).
     */
    private Bitmap renderStoryCanvas(boolean includeStickers) {
        if (stickerOverlayFrame == null || stickerOverlayFrame.getWidth() <= 0
                || stickerOverlayFrame.getHeight() <= 0
                || binding.ivPreview.getDrawable() == null
                || editorBlurBackground == null || editorBlurBackground.getDrawable() == null) {
            throw new IllegalStateException("Editor layers are not ready");
        }
        final int outputWidth = 1080;
        final int outputHeight = Math.max(1, Math.round(outputWidth
                * stickerOverlayFrame.getHeight() / (float) stickerOverlayFrame.getWidth()));
        Bitmap output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.scale(outputWidth / (float) stickerOverlayFrame.getWidth(),
                outputHeight / (float) stickerOverlayFrame.getHeight());

        ArrayList<View> hidden = new ArrayList<>();
        ArrayList<Integer> oldVisibility = new ArrayList<>();
        int frameVisibility = stickerOverlayFrame.getVisibility();
        if (frameVisibility != View.VISIBLE) stickerOverlayFrame.setVisibility(View.VISIBLE);
        hideForSnapshot(editorToolRail, hidden, oldVisibility);
        hideForSnapshot(editorSafeArea, hidden, oldVisibility); // guide must never be burned into the photo
        hideForSnapshot(binding.btnDiscardMedia, hidden, oldVisibility);
        hideForSnapshot(binding.ivVideoHint, hidden, oldVisibility);
        if (!includeStickers) {
            for (int i = 0; i < stickerOverlayFrame.getChildCount(); i++) {
                View child = stickerOverlayFrame.getChildAt(i);
                if (child instanceof StatusStickerOverlayView)
                    hideForSnapshot(child, hidden, oldVisibility);
            }
        }
        hideForSnapshot(stickerSizeBar, hidden, oldVisibility);
        hideForSnapshot(editorDrawBar, hidden, oldVisibility);
        // Text kept as an overlay layer (see pendingTextOverlayJson) must not also be burned into the photo.
        if (!includeStickers && pendingTextOverlayJson != null) hideForSnapshot(editorTextOverlay, hidden, oldVisibility);
        try {
            stickerOverlayFrame.draw(canvas);
        } finally {
            for (int i = 0; i < hidden.size(); i++)
                hidden.get(i).setVisibility(oldVisibility.get(i));
            if (frameVisibility != View.VISIBLE)
                stickerOverlayFrame.setVisibility(frameVisibility);
        }
        return output;
    }

    private void hideForSnapshot(View view, ArrayList<View> hidden, ArrayList<Integer> oldVisibility) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        hidden.add(view);
        oldVisibility.add(view.getVisibility());
        view.setVisibility(View.GONE);
    }

    /**
     * If this activity was launched from a ➕ Add Yours sticker tap
     * (StatusViewerActivity.openAddYoursComposer), auto-attaches that same
     * sticker here so the viewer doesn't have to re-type the prompt — they
     * just add their media/text and post to continue the chain.
     */
    private void applyPrefillStickerIfAny() {
        String prefillJson = getIntent().getStringExtra(EXTRA_PREFILL_STICKER_JSON);
        if (prefillJson == null || prefillJson.isEmpty()) return;

        // Wait a frame so the overlay frame has real dimensions before the
        // sticker is measured/positioned (mirrors addStickerOverlay's own
        // width fallback, but this runs before any layout pass at all).
        if (stickerOverlayFrame != null) {
            stickerOverlayFrame.post(() -> {
                addedStickerJsons.add(prefillJson);
                addStickerOverlay(prefillJson);
            });
        }
        applyAddYoursTemplate(prefillJson); // v578: same look as the chain starter's story

        String toastMsg = getIntent().getStringExtra(EXTRA_PREFILL_TOAST);
        if (toastMsg != null && !toastMsg.isEmpty()) {
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
        }
    }

    /** Open the sticker type picker sheet. */
    private void openStickerPicker() {
        // Music sticker → after picking a song, Reels' "Adjust Music" screen opens over THIS
        // status's photo/video so the poster picks which part of the song plays.
        Uri musicMedia = pickedVideo != null ? pickedVideo : pickedImage;
        StatusStickerPickerSheet.MusicPreviewMedia previewMedia = (musicMedia != null
                && !"http".equalsIgnoreCase(musicMedia.getScheme())
                && !"https".equalsIgnoreCase(musicMedia.getScheme()))
                ? new StatusStickerPickerSheet.MusicPreviewMedia(musicMedia.toString(), pickedVideo != null)
                : null; // text-only / web-gif status: no media to preview over → old direct-add path
        StatusStickerPickerSheet.show(this, previewMedia, result -> {
            addedStickerJsons.add(result.json);
            addStickerOverlay(result.json);
            Toast.makeText(this,
                getStickerAddedLabel(result.type) + " added! Drag to reposition.",
                Toast.LENGTH_SHORT).show();
        });
    }

    // ── Music sticker re-edit (tap sticker → Adjust / Mix with the saved values) ──────────────
    private androidx.activity.result.ActivityResultLauncher<Intent> musicReeditAdjustLauncher, musicReeditMixLauncher;
    private com.callx.app.stickers.StatusStickerOverlayView reeditSticker;
    private String reId = "", reTitle = "", reArtist = "", reCover = "", reUrl = "";
    private int reStart, reEnd;
    private boolean reAdjusted = false;       // Adjust returned OK in this edit session
    private boolean musicReeditInPlace = false; // suppress the "replaced" toast while editing

    /** Local photo/video this status is built on, or null (text-only / web media → no re-edit). */
    private Uri musicEditMedia() {
        Uri m = pickedVideo != null ? pickedVideo : pickedImage;
        if (m == null) return null;
        String sc = m.getScheme();
        if ("http".equalsIgnoreCase(sc) || "https".equalsIgnoreCase(sc)) return null;
        return m;
    }

    private void startMusicReedit(com.callx.app.stickers.StatusStickerOverlayView st, boolean adjustFirst) {
        hideStickerSizeBar();
        String url = st.getMusicSoundUrl();
        if (url == null || url.isEmpty()) { toast("Is music sticker me edit karne layak audio nahi hai"); return; }
        if (musicEditMedia() == null) { toast("Is status par music edit available nahi"); return; }
        reeditSticker = st;
        reUrl    = url.replace("\\/", "/");
        reId     = nz(st.getMusicSoundId());
        reTitle  = nz(st.getMusicSong());
        reArtist = nz(st.getMusicArtist());
        reCover  = nz(st.getMusicCoverUrl());
        reStart  = st.getMusicStartMs();
        reEnd    = st.getMusicEndMs();
        reAdjusted = false;
        boolean ok = adjustFirst ? launchReeditAdjust() : launchReeditMixer();
        if (!ok) { reeditSticker = null; toast("Music editor khul nahi paya"); }
    }

    private static String nz(String s) { return s != null ? s : ""; }

    private boolean launchReeditAdjust() {
        try {
            Uri media = musicEditMedia();
            Intent i = new Intent(this, Class.forName("com.callx.app.editor.ReelPhotoMusicTrimActivity"));
            java.util.ArrayList<String> photos = new java.util.ArrayList<>();
            if (pickedVideo == null && media != null) photos.add(media.toString());
            i.putStringArrayListExtra("trim_photo_uris", photos);
            i.putExtra("trim_slide_ms", 3000);
            if (pickedVideo != null && media != null) {
                i.putExtra("trim_video_uri", media.toString());
                i.putExtra("trim_video_is_file_path", false);
            }
            i.putExtra("trim_sound_id",     reId);
            i.putExtra("trim_sound_title",  reTitle);
            i.putExtra("trim_sound_artist", reArtist);
            i.putExtra("trim_sound_url",    reUrl);
            i.putExtra("trim_sound_cover",  reCover);
            if (reEnd > reStart) {
                i.putExtra("trim_initial_start_ms", reStart);
                i.putExtra("trim_initial_end_ms",   reEnd);
            }
            musicReeditAdjustLauncher.launch(i);
            return true;
        } catch (Exception e) { return false; }
    }

    private void onMusicReeditAdjustResult(int resultCode, @Nullable Intent data) {
        if (reeditSticker == null) return;
        if (resultCode != RESULT_OK || data == null) { reeditSticker = null; return; } // cancelled → no change
        String id = data.getStringExtra("result_sound_id"), title = data.getStringExtra("result_sound_title");
        String artist = data.getStringExtra("result_sound_artist"), url = data.getStringExtra("result_sound_url");
        String cover = data.getStringExtra("result_sound_cover");
        boolean trackChanged = url != null && !url.isEmpty() && !url.replace("\\/", "/").equals(reUrl);
        if (url != null && !url.isEmpty()) reUrl = url;
        if (id != null && !id.isEmpty()) reId = id;
        if (title != null && !title.isEmpty()) reTitle = title;
        if (artist != null) reArtist = artist;
        if (cover != null && !cover.isEmpty()) reCover = cover; else if (trackChanged) reCover = "";
        reStart = data.getIntExtra("result_start_ms", 0);
        reEnd   = data.getIntExtra("result_end_ms", 0);
        reAdjusted = true;
        // Adjust → Done → Mix Audio (same chain as when the sticker was first added).
        if (!launchReeditMixer()) applyMusicReedit(null);
    }

    private boolean launchReeditMixer() {
        try {
            Uri media = musicEditMedia();
            com.callx.app.stickers.StatusStickerOverlayView st = reeditSticker;
            Intent i = new Intent(this, Class.forName("com.callx.app.editor.ReelAudioMixerActivity"));
            if (pickedVideo != null && media != null) {
                i.putExtra("mixer_video_uri", media.toString());
                i.putExtra("mixer_is_file", false);
            } else if (media != null) {
                i.putExtra("mixer_photo_uri", media.toString());
            }
            i.putExtra("mixer_music_url",    reUrl);
            i.putExtra("mixer_music_title",  reTitle);
            i.putExtra("mixer_music_artist", reArtist);
            i.putExtra("mixer_sound_id",     reId);
            i.putExtra("mixer_music_start_ms", reStart);
            i.putExtra("mixer_music_end_ms",   reEnd);
            i.putExtra("mixer_status_mode", true);
            if (st != null && st.hasMusicMix()) {            // saved values come back on screen
                if (st.getMusicOrigVol() >= 0f) i.putExtra("mixer_init_orig_vol",  st.getMusicOrigVol());
                if (st.getMusicMixVol()  >= 0f) i.putExtra("mixer_init_music_vol", st.getMusicMixVol());
                i.putExtra("mixer_init_fade_in_ms",  st.getMusicFadeInMs());
                i.putExtra("mixer_init_fade_out_ms", st.getMusicFadeOutMs());
                i.putExtra("mixer_init_pitch",       st.getMusicPitch());
            }
            musicReeditMixLauncher.launch(i);
            return true;
        } catch (Exception e) { return false; }
    }

    private void onMusicReeditMixResult(int resultCode, @Nullable Intent data) {
        if (reeditSticker == null) return;
        if (resultCode == RESULT_OK && data != null) {
            // "Change" inside the mixer may have swapped the track (then range comes back 0/0).
            String url = data.getStringExtra("result_music_url");
            if (url != null && !url.isEmpty()) {
                if (!url.replace("\\/", "/").equals(reUrl)) reCover = "";
                reUrl = url;
            }
            String id = data.getStringExtra("result_music_id");
            String title = data.getStringExtra("result_music_title");
            String artist = data.getStringExtra("result_music_artist");
            if (id != null && !id.isEmpty()) reId = id;
            if (title != null && !title.isEmpty()) reTitle = title;
            if (artist != null) reArtist = artist;
            reStart = data.getIntExtra("result_music_start_ms", reStart);
            reEnd   = data.getIntExtra("result_music_end_ms",   reEnd);
            float[] mix = {
                data.getFloatExtra("result_music_vol", 0.8f),
                pickedVideo != null ? data.getFloatExtra("result_orig_vol", 1.0f) : -1f,
                data.getIntExtra("result_fade_in_ms", 0),
                data.getIntExtra("result_fade_out_ms", 0),
                data.getFloatExtra("result_pitch_semitones", 0f)
            };
            applyMusicReedit(mix);
        } else if (reAdjusted) {
            applyMusicReedit(null); // Back from Mix: keep the new range, keep the old mix
        } else {
            reeditSticker = null;   // nothing changed
        }
    }

    /** Rebuilds the music sticker JSON (keeping its position/size) and swaps it in. mix = {musicVol, origVol, fadeIn, fadeOut, pitch} or null. */
    private void applyMusicReedit(@Nullable float[] mix) {
        com.callx.app.stickers.StatusStickerOverlayView st = reeditSticker;
        reeditSticker = null;
        if (st == null || st.getParent() != stickerOverlayFrame) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(st.toJsonWithScale());
            o.put("song", reTitle);
            o.put("artist", reArtist);
            o.put("albumArt", reCover);
            o.put("soundId", reId);
            o.put("soundUrl", reUrl);
            o.remove("startMs"); o.remove("endMs");
            if (reStart > 0) o.put("startMs", reStart);
            if (reEnd > reStart && reEnd > 0) o.put("endMs", reEnd);
            if (mix != null) {
                o.put("musicVol", Math.round(Math.max(0f, Math.min(1f, mix[0])) * 100f) / 100.0);
                o.remove("origVol");
                if (mix[1] >= 0f) o.put("origVol", Math.round(Math.min(1f, mix[1]) * 100f) / 100.0);
                o.remove("fadeInMs"); o.remove("fadeOutMs"); o.remove("pitch");
                if (mix[2] > 0) o.put("fadeInMs",  (int) mix[2]);
                if (mix[3] > 0) o.put("fadeOutMs", (int) mix[3]);
                if (mix[4] != 0f) o.put("pitch", Math.round(mix[4] * 10f) / 10.0);
            }
            musicReeditInPlace = true;
            try { addStickerOverlay(o.toString()); } finally { musicReeditInPlace = false; }
            toast("Music update ho gaya");
        } catch (Exception e) {
            toast("Music update nahi ho paya");
        }
    }

    private String getStickerAddedLabel(String type) {
        switch (type) {
            case "music":     return "🎵 Music sticker";
            case "countdown": return "⏳ Countdown";
            case "quiz":      return "🧠 Quiz sticker";
            case "question":  return "💬 Question box";
            case "poll":      return "🗳️ Poll sticker";
            case "slider":    return "🎚️ Slider sticker";
            case "mention":   return "👤 Mention sticker";
            case "hashtag":   return "#️⃣ Hashtag sticker";
            case "link":      return "🔗 Link sticker";
            case "addyours":  return "➕ Add Yours sticker";
            default:          return "✨ Sticker";
        }
    }

    // ── Music sticker: one-per-status + live preview (6a / 6b) ─────────────
    // The viewer plays the sticker's song the moment the status opens, but the
    // poster used to compose in total silence and could even add two music
    // stickers (only the last one ever played). Now: only ONE music sticker
    // exists at a time, and its song previews (looped) while composing.
    private StatusStickerOverlayView musicStickerInComposer;
    private android.media.MediaPlayer musicPreview;
    private boolean musicPreviewPrepared = false;
    private boolean composerResumed      = false; // preview only sounds while this screen is in front
    private boolean previewFocusLost     = false; // another app/call took audio focus
    private boolean previewDucked        = false;
    private StatusAudioFocusHelper previewFocus;  // lazily created
    private final StatusMusicClip previewClip = new StatusMusicClip(); // (5) same ~30s highlight the viewer will play
    private int previewStartMs = 0, previewEndMs = 0;

    { previewClip.setVolumeListener(() -> applyPreviewVolume()); } // fade-in/out ramp

    /** Duck × Mix Audio factor (music volume + current fade); same maths as the viewer. */
    private void applyPreviewVolume() {
        if (musicPreview == null) return;
        float v = (previewDucked ? 0.2f : 1f) * previewClip.volumeFactor();
        try { musicPreview.setVolume(v, v); } catch (Exception ignored) {}
    }

    private StatusAudioFocusHelper previewFocus() {
        if (previewFocus == null) {
            previewFocus = new StatusAudioFocusHelper(this, new StatusAudioFocusHelper.Listener() {
                @Override public void onFocusLost() {
                    previewFocusLost = true;
                    if (musicPreview != null) {
                        try { if (musicPreview.isPlaying()) musicPreview.pause(); } catch (Exception ignored) {}
                    }
                }
                @Override public void onFocusRegained() {
                    previewFocusLost = false;
                    resumeMusicPreview();
                }
                @Override public void onDuck(boolean ducked) {
                    previewDucked = ducked;
                    applyPreviewVolume();
                }
            });
        }
        return previewFocus;
    }

    /**
     * Removes every music sticker already on the canvas (there must be only one).
     * @return the removed sticker's top margin (so the new one can take its spot),
     *         or -1 if there was none.
     */
    private int removeExistingMusicStickers() {
        if (stickerOverlayFrame == null) return -1;
        int topMargin = -1;
        boolean removedAny = false;
        for (int i = stickerOverlayFrame.getChildCount() - 1; i >= 0; i--) {
            View child = stickerOverlayFrame.getChildAt(i);
            if (child instanceof StatusStickerOverlayView
                    && "music".equals(((StatusStickerOverlayView) child).getStickerType())) {
                if (child.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
                    topMargin = ((android.widget.FrameLayout.LayoutParams) child.getLayoutParams()).topMargin;
                }
                stickerOverlayFrame.removeViewAt(i);
                removedAny = true;
            }
        }
        if (removedAny) {
            // Old song's preview must go too (the new one starts right after this).
            if (musicStickerInComposer != null) musicStickerInComposer = null;
            stopMusicPreview();
            applyEditorVideoVolume(); // muted until the replacing sticker sets its own mix
            if (!musicReeditInPlace) toast("Pehla music sticker replace ho gaya");
        }
        return removedAny ? Math.max(topMargin, 0) : -1;
    }

    private void syncComposerMusicBars() {
        if (musicStickerInComposer == null) return;
        boolean playing = false;
        try { playing = musicPreview != null && musicPreview.isPlaying(); } catch (Exception ignored) {}
        musicStickerInComposer.setMusicPlaying(playing);
    }

    private void startMusicPreview(StatusStickerOverlayView sticker) {
        stopMusicPreview();
        // Mix Audio → same music volume / fades / pitch the viewer will apply; neutral if no mix.
        if (sticker.hasMusicMix()) {
            previewClip.setMix(sticker.getMusicMixVol(), sticker.getMusicFadeInMs(),
                    sticker.getMusicFadeOutMs(), sticker.getMusicPitch());
        } else {
            previewClip.setMix(-1f, 0, 0, 0f);
        }
        applyEditorVideoVolume(); // musicStickerInComposer is already this sticker
        String url = sticker.getMusicSoundUrl();
        if (url == null || url.isEmpty()) return; // sticker carries no playable URL → silent preview
        previewStartMs = sticker.getMusicStartMs();
        previewEndMs   = sticker.getMusicEndMs();
        // Same defensive un-escape as the viewer (org.json writes "/" as "\/").
        final String playUrl = url.replace("\\/", "/");
        try {
            final android.media.MediaPlayer mp = new android.media.MediaPlayer();
            musicPreview = mp;
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(playUrl);
            mp.setLooping(true);
            applyPreviewVolume();
            mp.setOnPreparedListener(p -> {
                if (musicPreview != p) return; // stale (sticker replaced/removed meanwhile)
                musicPreviewPrepared = true;
                previewClip.apply(p, previewStartMs, previewEndMs); // (5) poster hears the same clip viewers will
                resumeMusicPreview();          // starts only if screen is in front + focus granted
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (musicPreview != p) return true;
                stopMusicPreview();
                toast("Music preview play nahi ho paya (" + what + "/" + extra + ")");
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            stopMusicPreview();
            toast("Music preview play nahi ho paya");
        }
    }

    private void resumeMusicPreview() {
        if (musicPreview == null || !musicPreviewPrepared || previewFocusLost || !composerResumed) return;
        try {
            if (musicPreview.isPlaying()) return;
            if (!previewFocus().request()) return; // e.g. call active → stay silent
            previewClip.reapplyPitch(); // Mix Audio pitch (no-op when 0)
            musicPreview.start();
        } catch (Exception ignored) {}
        syncComposerMusicBars();
    }

    /** Screen left the foreground: pause + give audio focus back (other app's music resumes). */
    private void pauseMusicPreview() {
        if (musicPreview != null) {
            try { if (musicPreview.isPlaying()) musicPreview.pause(); } catch (Exception ignored) {}
        }
        syncComposerMusicBars();
        if (previewFocus != null) previewFocus.abandon();
    }

    private void stopMusicPreview() {
        previewClip.stop();
        if (musicPreview != null) {
            try { musicPreview.release(); } catch (Exception ignored) {}
            musicPreview = null;
        }
        musicPreviewPrepared = false;
        previewFocusLost = false;
        previewDucked = false;
        syncComposerMusicBars();
        if (previewFocus != null) previewFocus.abandon();
    }

    /**
     * Inflate the sticker view and add it to the overlay frame.
     * Falls back gracefully if there is no overlay frame in the layout.
     */
    private void addStickerOverlay(String stickerJson) {
        if (stickerOverlayFrame == null) return;

        StatusStickerOverlayView stickerView = StatusStickerOverlayView.fromJson(this, stickerJson);

        // (6b) ONE music sticker per status. The viewer can only play one song
        // (it used to silently play just the last one), so a second music
        // sticker REPLACES the first — and takes over its spot on the canvas.
        final boolean isMusicSticker = "music".equals(stickerView.getStickerType());
        final int replacedTopMargin = isMusicSticker ? removeExistingMusicStickers() : -1;

        // Position sticker near centre-top of frame
        int dp = (int) getResources().getDisplayMetrics().density;
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
            Math.min(stickerOverlayFrame.getWidth() > 0 ? stickerOverlayFrame.getWidth() - dp * 32 : dp * 280,
                dp * 280),
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        // BUG FIX: this used to be `dp * (16 + addedStickerJsons.size() * 12)` —
        // a flat 12dp increment per sticker no matter how tall the previous
        // card actually was. Music/Countdown/Quiz/Question cards range from
        // ~70dp to 160dp+ tall, so adding all four landed each new one almost
        // fully on top of the last, all centred, all TOP-anchored. Instead,
        // stack each new sticker just below the *real* measured bottom of the
        // previously-added one (tracked in stickerStackBottomPx) plus a fixed
        // gap, so 4 stickers end up spaced apart down the story canvas instead
        // of overlapping. User can still drag any of them afterward.
        int gap = dp(14); // composer-only placement → exact dp (width above stays in sync with StatusViewerActivity)
        lp.topMargin = (stickerStackBottomPx < 0) ? dp(16) : (stickerStackBottomPx + gap);
        if (replacedTopMargin >= 0) lp.topMargin = replacedTopMargin; // replaced music sticker → same spot
        stickerOverlayFrame.addView(stickerView, lp);
        if (editorToolRail != null) editorToolRail.bringToFront();

        // Enable drag + pinch-resize + long-press-remove
        stickerView.attachDragToParent(stickerOverlayFrame);
        if (stickerView.hasSavedPosition()) {
            // v578: Add Yours template → same spot as the original sticker (ratios of the overlay frame).
            final StatusStickerOverlayView sv = stickerView;
            sv.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    final int fw = stickerOverlayFrame.getWidth(), fh = stickerOverlayFrame.getHeight();
                    if (sv.getWidth() == 0 || fw == 0 || fh == 0) return;
                    sv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if (sv.getParent() != stickerOverlayFrame) return;
                    sv.setX(sv.getSavedPosXRatio() * fw);
                    sv.setY(sv.getSavedPosYRatio() * fh);
                }
            });
        }
        stickerView.setOnStickerTappedListener(this::showStickerSizeBar);
        stickerOverlayFrame.setOnClickListener(v -> hideStickerSizeBar());

        // (6a) Let the poster HEAR the song while composing (viewer will hear the same).
        if (isMusicSticker) {
            musicStickerInComposer = stickerView;
            // Long-press / drag-to-trash removal → stop the preview with it.
            stickerView.setOnStickerRemovedListener(removed -> {
                if (removed == musicStickerInComposer) {
                    musicStickerInComposer = null;
                    stopMusicPreview();
                    applyEditorVideoVolume(); // back to muted
                }
            });
            startMusicPreview(stickerView);
        }

        // Pop-in animation
        stickerView.setScaleX(0.3f);
        stickerView.setScaleY(0.3f);
        stickerView.setAlpha(0f);
        stickerView.animate().scaleX(stickerView.getStickerScale()).scaleY(stickerView.getStickerScale())
            .alpha(1f).setDuration(300)
            .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f)).start();

        // Once this card is actually measured/laid out, remember where its
        // bottom edge landed so the *next* sticker (if any) starts below it.
        final int appliedTopMargin = lp.topMargin;
        stickerView.post(() -> {
            if (stickerView.getHeight() > 0) {
                stickerStackBottomPx = appliedTopMargin + stickerView.getHeight();
            }
        });
    }

    /**
     * Bottom Y (px) of the last sticker card added to {@link #stickerOverlayFrame},
     * used to place the next sticker below it instead of on top of it.
     * -1 until the first sticker has been measured.
     */
    private int stickerStackBottomPx = -1;


    /**
     * Small floating Small/Medium/Large row shown just above a tapped sticker —
     * the explicit alternative to pinch-to-resize for setting sticker size.
     */
    private void showStickerSizeBar(com.callx.app.stickers.StatusStickerOverlayView sticker) {
        selectedSticker = sticker;

        if (stickerSizeBar == null) {
            stickerSizeBar = new LinearLayout(this);
            stickerSizeBar.setOrientation(LinearLayout.HORIZONTAL);
            stickerSizeBar.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(0xDD1A1A1A);
            stickerSizeBar.setBackground(bg);
            stickerSizeBar.setPadding(dp(6), dp(6), dp(6), dp(6));

            String[] labels = {"S", "M", "L"};
            float[]  scales = {
                com.callx.app.stickers.StatusStickerOverlayView.SCALE_SMALL,
                com.callx.app.stickers.StatusStickerOverlayView.SCALE_MEDIUM,
                com.callx.app.stickers.StatusStickerOverlayView.SCALE_LARGE
            };
            for (int i = 0; i < labels.length; i++) {
                TextView btn = new TextView(this);
                btn.setText(labels[i]);
                btn.setTextColor(Color.WHITE);
                btn.setTextSize(14);
                btn.setGravity(android.view.Gravity.CENTER);
                btn.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
                lp.leftMargin = i == 0 ? 0 : dp(4);
                float scale = scales[i];
                btn.setOnClickListener(v -> {
                    if (selectedSticker != null) selectedSticker.animateToScale(scale);
                });
                stickerSizeBar.addView(btn, lp);
            }

            // Music sticker only: re-open Adjust Music / Mix Audio with the saved values.
            stickerMusicRow = new LinearLayout(this);
            stickerMusicRow.setOrientation(LinearLayout.HORIZONTAL);
            stickerMusicRow.setGravity(android.view.Gravity.CENTER);
            String[] mLabels = {"✂ Adjust", "🎚 Mix"};
            for (int i = 0; i < mLabels.length; i++) {
                final boolean adjust = (i == 0);
                TextView mb = new TextView(this);
                mb.setText(mLabels[i]);
                mb.setTextColor(Color.WHITE);
                mb.setTextSize(13);
                mb.setTypeface(null, android.graphics.Typeface.BOLD);
                mb.setGravity(android.view.Gravity.CENTER);
                mb.setPadding(dp(10), 0, dp(10), 0);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
                mlp.leftMargin = dp(4);
                mb.setOnClickListener(v -> {
                    if (selectedSticker != null) startMusicReedit(selectedSticker, adjust);
                });
                stickerMusicRow.addView(mb, mlp);
            }
            stickerSizeBar.addView(stickerMusicRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            stickerOverlayFrame.addView(stickerSizeBar, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));
        }

        if (stickerMusicRow != null) {
            stickerMusicRow.setVisibility("music".equals(sticker.getStickerType()) ? View.VISIBLE : View.GONE);
        }
        stickerSizeBar.setVisibility(View.VISIBLE);
        stickerSizeBar.bringToFront();
        // Position just above the tapped sticker (falls back near the top if it doesn't fit).
        stickerSizeBar.post(() -> {
            float top = sticker.getY() - stickerSizeBar.getHeight() - dp(8);
            ((android.widget.FrameLayout.LayoutParams) stickerSizeBar.getLayoutParams()).topMargin =
                (int) Math.max(dp(8), top);
            stickerSizeBar.requestLayout();
        });
    }

    private void hideStickerSizeBar() {
        selectedSticker = null;
        if (stickerSizeBar != null) stickerSizeBar.setVisibility(View.GONE);
    }
    @Override protected void onPause() {
        super.onPause();
        saveDraft();
        composerResumed = false;
        syncVideoPreviewPlayback();
        pauseMusicPreview(); // (6a) never keep playing in the background
    }
    @Override protected void onResume() {
        super.onResume();
        composerResumed = true;
        syncVideoPreviewPlayback();
        resumeMusicPreview();
    }
    // ── Toolbar ───────────────────────────────────────────────────────────
    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
    }
    // ── v217: COMBO "Add status" sheet ─────────────────────────────────────
    // Merges what used to be two separate, half-working sheets into one:
    //   1. showStatusAddSheet()'s own 5 action chips (Text/Music/Layout/
    //      Voice/AI images) — kept exactly as before.
    //   2. The full chat-grade attach sheet (openStatusAttachSheet(), which
    //      was written but never actually wired to the Upload button) —
    //      recent-media strip, expandable 4-col grid, folder picker, HD
    //      toggle, multi-select send bar, Edit-before-post.
    // Previously the chips lived in their own bottom_sheet_status_add.xml
    // with a 3-col RecyclerView (rv_status_add_recents) that
    // AttachSheetRecentMediaBinder could never bind to (it looks for
    // R.id.recents_grid/top_content, which that layout didn't have — see
    // the binder's early-return guard), so that grid was silently dead.
    // Now we inflate feature-chat's real bottom_sheet_attach.xml (the one
    // the binder actually supports) and inject just the chip row into it.
    /**
     * Status flavour of the shared attach sheet: no chat-only chips (Audio file
     * send, Document, Poll, …), no view-once (statuses are already 24h), no HD
     * toggle (Status always posts photos at the HD tier — STATUS_PHOTO_HD).
     */
    private com.callx.app.conversation.controllers.AttachSheetRecentMediaBinder.Config statusSheetConfig() {
        return new com.callx.app.conversation.controllers.AttachSheetRecentMediaBinder.Config()
                .hide(com.callx.app.chat.R.id.opt_document, com.callx.app.chat.R.id.opt_poll,
                        com.callx.app.chat.R.id.opt_contact, com.callx.app.chat.R.id.opt_location,
                        com.callx.app.chat.R.id.opt_payment, com.callx.app.chat.R.id.opt_event,
                        com.callx.app.chat.R.id.opt_ai_images, com.callx.app.chat.R.id.opt_audio)
                .viewOnce(false)
                .hdToggle(false);
    }

    private void showStatusAddSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(com.callx.app.chat.R.layout.bottom_sheet_attach, null);

        // Status has no poll/contact/location/document/payment/event
        // concepts, and its own AI-images entry point lives in the chip
        // row below — hide those chat-only chips from the icon grid so
        // the sheet stays focused on media, same as the old (unused)
        // openStatusAttachSheet() did.
        // (hidden through the binder's Config below — see statusSheetConfig)
        View optGallery = v.findViewById(com.callx.app.chat.R.id.opt_gallery);
        if (optGallery != null) optGallery.setOnClickListener(x -> { sheet.dismiss(); launchStatusMediaPicker(); });

        // Inject the 5-chip action row (Text/Music/Layout/Voice/AI images)
        // right below the drag handle, above the icon grid/Recents strip —
        // its height simply folds into top_content's measured height, which
        // AttachSheetRecentMediaBinder already uses to compute the sheet's
        // collapsed peekHeight, so no extra plumbing is needed for it to
        // show correctly in both collapsed and expanded state.
        View topContentView = v.findViewById(com.callx.app.chat.R.id.top_content);
        android.view.ViewGroup topContent = topContentView instanceof android.view.ViewGroup
                ? (android.view.ViewGroup) topContentView : null;
        View actionRow = getLayoutInflater().inflate(
                com.callx.app.status.R.layout.bottom_sheet_status_add, topContent, false);
        if (topContent != null) topContent.addView(actionRow, 1);

        // Text → switch to text-only mode
        View btnText = actionRow.findViewById(com.callx.app.status.R.id.status_add_btn_text);
        if (btnText != null) btnText.setOnClickListener(x -> {
            sheet.dismiss();
            // Show text input area (same as typing a text status)
            if (binding.tilText != null) {
                binding.tilText.setVisibility(View.VISIBLE);
                binding.tilText.requestFocus();
            }
        });

        // Music → open music sticker picker
        View btnMusic = actionRow.findViewById(com.callx.app.status.R.id.status_add_btn_music);
        if (btnMusic != null) btnMusic.setOnClickListener(x -> {
            sheet.dismiss();
            openStickerPicker();
        });

        // Layout → open layout picker activity
        View btnLayout = actionRow.findViewById(com.callx.app.status.R.id.status_add_btn_layout);
        if (btnLayout != null) btnLayout.setOnClickListener(x -> {
            sheet.dismiss();
            openLayoutPicker();
        });

        // Voice → record voice note for status
        View btnVoice = actionRow.findViewById(com.callx.app.status.R.id.status_add_btn_voice);
        if (btnVoice != null) btnVoice.setOnClickListener(x -> {
            sheet.dismiss();
            Intent voiceIntent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
            if (voiceIntent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(voiceIntent, 9022);
            } else {
                toast("No audio recorder found on this device");
            }
        });

        // AI Images → show AI prompt input
        View btnAi = actionRow.findViewById(com.callx.app.status.R.id.status_add_btn_ai_images);
        if (btnAi != null) btnAi.setOnClickListener(x -> {
            sheet.dismiss();
            showAiImagePromptDialog();
        });

        // Recents strip + expandable grid — same shared binder ChatMediaController
        // and GroupChatActivity use, wired to Status's own posting/edit flow.
        com.callx.app.conversation.controllers.AttachSheetRecentMediaBinder.bind(
                this, sheet, v, statusAttachMediaExecutor,
                statusSheetConfig(),
                new com.callx.app.conversation.controllers.AttachSheetRecentMediaBinder.Callbacks() {
                    @Override public void onCameraTapped() {
                        sheet.dismiss();
                        openReelCamera();
                    }
                    @Override public void onMoreAppsRequested() {
                        sheet.dismiss();
                        launchStatusMoreAppsChooser();
                    }
                    @Override public void onSeeMoreRequested() {
                        sheet.dismiss();
                        launchStatusMediaPicker();
                    }
                    @Override public void onMediaSend(
                            java.util.List<com.callx.app.conversation.controllers.RecentMediaLoader.Item> items,
                            String caption, boolean isHD, boolean isViewOnce) {
                        if (items.isEmpty()) return;
                        sheet.dismiss();
                        String cap = caption == null || caption.isEmpty() ? null : caption;
                        // v238 BUG FIX: picking a single photo/video in the attach
                        // sheet and tapping "Send" used to upload + post it
                        // straight away (postStatusBatch) with zero chance to see
                        // it, add a caption, change privacy/expiry, or add
                        // stickers first — unlike every other single-media entry
                        // point (gallery pick, camera capture, layout) which all
                        // land back on THIS screen's own preview + Post button
                        // first. Route single selections through that same
                        // preview pipeline instead, so Post is still one manual
                        // tap on this screen — same as the layout flow.
                        if (items.size() == 1) {
                            com.callx.app.conversation.controllers.RecentMediaLoader.Item item = items.get(0);
                            showSinglePickedPreview(item.uri, item.isVideo, cap);
                            return;
                        }
                        java.util.List<Uri> uris = new java.util.ArrayList<>();
                        java.util.List<Boolean> videoFlags = new java.util.ArrayList<>();
                        for (com.callx.app.conversation.controllers.RecentMediaLoader.Item item : items) {
                            uris.add(item.uri);
                            videoFlags.add(item.isVideo);
                        }
                        postStatusBatch(uris, videoFlags, cap);
                    }
                    @Override public void onMediaEdit(
                            java.util.List<com.callx.app.conversation.controllers.RecentMediaLoader.Item> items,
                            String caption, boolean isHD) {
                        if (items.isEmpty()) return;
                        sheet.dismiss();

                        // NEW: a single picked VIDEO's pencil/Edit action now opens
                        // the real Reel Edit screen (feature-reels' ReelEditorActivity
                        // — filters/effects/speed/stickers/trim/sound, the same full
                        // editor Reels itself uses) instead of the limited chat-grade
                        // MediaEditActivity (trim-only for video, no filters/effects).
                        // Reused as-is, no duplicate editor code: launched by class
                        // name (feature-status can't depend on feature-reels) with
                        // EXTRA_TARGET_STATUS=true — the same flag/return-path
                        // ReelCameraActivity → ReelEditorActivity already uses for
                        // Status's own camera button, wired to reelCameraLauncher /
                        // handleReelCameraResult() so it lands back on THIS screen's
                        // preview (tapping the editor's back arrow or Done both just
                        // return here, exactly like every other single-media entry
                        // point on this screen).
                        if (items.size() == 1 && items.get(0).isVideo) {
                            Uri videoUri = items.get(0).uri;
                            Intent reelEditIntent = new Intent();
                            reelEditIntent.setClassName(getPackageName(), "com.callx.app.editor.ReelEditorActivity");
                            reelEditIntent.putExtra("editor_video_uri", videoUri.toString());
                            reelEditIntent.putExtra("is_file_path", false);
                            reelEditIntent.putExtra("target_status", true);
                            reelEditIntent.putExtra("allow_media_edit_fallback", true);
                            reelEditIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            if (reelEditIntent.resolveActivity(getPackageManager()) != null) {
                                pendingSingleVideoEditCaption = (caption == null || caption.isEmpty()) ? null : caption;
                                reelCameraLauncher.launch(reelEditIntent);
                                return;
                            }
                            // Reels module not present on this build — fall through
                            // to the chat-grade editor below, same fallback pattern
                            // openReelCamera() uses.
                        }

                        // Same idea as the video branch above, for a single picked
                        // PHOTO: pencil/Edit now opens the real Reel Photo Edit
                        // screen (feature-reels' ReelPhotoEditorActivity —
                        // filters/effects/caption/stickers/adjust, titled "Edit
                        // Status" via target_status) instead of the chat-grade
                        // MediaEditActivity directly. allow_media_edit_fallback
                        // still sends it on to MediaEditActivity afterwards (Back
                        // or Done), so the final result — and where it lands back
                        // on this screen — is identical either way; only the
                        // editor in front of it is richer now. Same
                        // reelCameraLauncher/handleReelCameraResult this screen's
                        // other entry points already share.
                        if (items.size() == 1 && !items.get(0).isVideo) {
                            Uri photoUri = items.get(0).uri;
                            Intent photoEditIntent = new Intent();
                            photoEditIntent.setClassName(getPackageName(), "com.callx.app.editor.ReelPhotoEditorActivity");
                            photoEditIntent.putExtra("photo_editor_uri", photoUri.toString());
                            photoEditIntent.putExtra("photo_editor_index", 0);
                            photoEditIntent.putExtra("photo_editor_count", 1);
                            photoEditIntent.putExtra("target_status", true);
                            photoEditIntent.putExtra("allow_media_edit_fallback", true);
                            photoEditIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            if (photoEditIntent.resolveActivity(getPackageManager()) != null) {
                                pendingSingleVideoEditCaption = (caption == null || caption.isEmpty()) ? null : caption;
                                reelCameraLauncher.launch(photoEditIntent);
                                return;
                            }
                            // Reels module not present on this build — fall through
                            // to the chat-grade editor below.
                        }

                        java.util.ArrayList<String> uriStrings = new java.util.ArrayList<>();
                        java.util.ArrayList<Integer> videoFlags = new java.util.ArrayList<>();
                        for (com.callx.app.conversation.controllers.RecentMediaLoader.Item item : items) {
                            uriStrings.add(item.uri.toString());
                            videoFlags.add(item.isVideo ? 1 : 0);
                        }
                        Intent intent = new Intent(NewStatusActivity.this,
                                com.callx.app.conversation.controllers.MediaEditActivity.class);
                        intent.putStringArrayListExtra(
                                com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_URIS, uriStrings);
                        intent.putIntegerArrayListExtra(
                                com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_IS_VIDEO, videoFlags);
                        intent.putExtra(com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_CAPTION, caption);
                        intent.putExtra(com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_HD, isHD);
                        statusMediaEditLauncher.launch(intent);
                    }
                });

        sheet.setContentView(v);
        sheet.show();
    }

    /** v216: Opens StatusLayoutPickerActivity to select 1-6 photos + layout style. */
    private void openLayoutPicker() {
        Intent intent = new Intent(this, StatusLayoutPickerActivity.class);
        layoutPickerLauncher.launch(intent);
    }

    /**
     * v237: Opens MediaEditActivity on the single already-flattened layout
     * collage file, so the person can crop/filter/draw/add text on their
     * chosen layout exactly like any other status photo — as ONE image,
     * never as the individual photos it was built from.
     */
    private void openLayoutEditor(Uri composedCollageUri) {
        java.util.ArrayList<String> uriStrings = new java.util.ArrayList<>();
        uriStrings.add(composedCollageUri.toString());
        java.util.ArrayList<Integer> videoFlags = new java.util.ArrayList<>();
        videoFlags.add(0); // the collage is always a flattened image, never a video

        Intent intent = new Intent(this, com.callx.app.conversation.controllers.MediaEditActivity.class);
        intent.putStringArrayListExtra(
                com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_URIS, uriStrings);
        intent.putIntegerArrayListExtra(
                com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_IS_VIDEO, videoFlags);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        layoutMediaEditLauncher.launch(intent);
    }

    /**
     * Shared MediaEditActivity result handler — used directly by
     * statusMediaEditLauncher (attach sheet's plain Edit action), AND by
     * handleReelCameraResult() when ReelEditorActivity forwards a result it
     * received FROM MediaEditActivity (see ReelEditorActivity's
     * allowMediaEditFallback / goBackOrToMediaEdit() — backing out of the
     * Reel Edit screen for an already-picked video opens this same media
     * editing screen, whose result is transparently passed through). Kept
     * as one method so both paths land on the exact same preview pipeline
     * instead of duplicating this logic.
     */
    private void handleMediaEditActivityResult(int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return;
        java.util.ArrayList<String> uriStrings = data.getStringArrayListExtra(
                com.callx.app.conversation.controllers.MediaEditActivity.RESULT_URIS);
        if (uriStrings == null || uriStrings.isEmpty()) return;
        String caption = data.getStringExtra(
                com.callx.app.conversation.controllers.MediaEditActivity.RESULT_CAPTION);
        String cap = caption == null || caption.isEmpty() ? null : caption;
        // v238 BUG FIX: editing a single photo/video from the attach
        // sheet used to auto-upload + post it immediately
        // (postStatusBatch) the moment MediaEditActivity returned —
        // with no preview, no chance to set privacy/expiry/stickers,
        // unlike every other single-media entry point on this screen
        // (gallery pick, camera, layout). A single edited item now
        // goes through that exact same pickedImage/pickedVideo +
        // showImagePreview()/showVideoPreview() pipeline instead, so
        // Post is still one manual tap on this screen — same as the
        // layout flow. Multi-item edits (2+) keep posting as a batch,
        // same as before.
        if (uriStrings.size() == 1) {
            Uri u = Uri.parse(uriStrings.get(0));
            String mime = getContentResolver().getType(u);
            boolean isVideo = mime != null && mime.startsWith("video");
            showSinglePickedPreview(u, isVideo, cap);
            return;
        }
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        java.util.List<Boolean> videoFlags = new java.util.ArrayList<>();
        for (String s : uriStrings) {
            Uri u = Uri.parse(s);
            uris.add(u);
            String mime = getContentResolver().getType(u);
            videoFlags.add(mime != null && mime.startsWith("video"));
        }
        // Layout no longer routes through MediaEditActivity (see
        // layoutPickerLauncher below), so multi-item results here are
        // always the plain multi-select attach-sheet flow — post each
        // edited photo as its own status. Advance Editing only ever sends
        // a single video in, but clear its flag defensively so a stray
        // multi-item result can never leave it armed for a later edit.
        pendingAdvanceEditWizardJump = false;
        postStatusBatch(uris, videoFlags, cap);
    }

    /** The server skips unknown usernames silently (and never reveals blocks), so tell the owner about
     *  @names that don't exist at all — checked against the usernames index after a successful post. */
    private void warnUnresolvedMentions(com.callx.app.models.StatusItem item) {
        final android.content.Context app = getApplicationContext();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("@([A-Za-z0-9_]{3,30})");
        for (String t : new String[]{item.text, item.caption}) {
            if (t == null) continue;
            java.util.regex.Matcher m = p.matcher(t);
            while (m.find()) names.add(m.group(1).toLowerCase());
        }
        if (item.stickersJson != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"type\"\\s*:\\s*\"mention\"[^}]*?\"username\"\\s*:\\s*\"([A-Za-z0-9_]{3,30})\"")
                    .matcher(item.stickersJson);
            while (m.find()) names.add(m.group(1).toLowerCase());
        }
        for (String n : names) {
            com.callx.app.utils.MentionUserSearch.exists(n, ok -> {
                if (!ok) android.widget.Toast.makeText(app, "@" + n + " not found \u2014 not mentioned",
                        android.widget.Toast.LENGTH_LONG).show();
            });
        }
    }

    /** v216: AI image prompt dialog — opens text input, result generates an AI image for status. */
    private void showAiImagePromptDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Describe the image you want…");
        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("AI Image for Status")
            .setView(et)
            .setPositiveButton("Generate", (d, w) -> {
                String prompt = et.getText().toString().trim();
                if (!prompt.isEmpty()) {
                    toast("AI image generation coming soon…");
                }
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ── Attach sheet (Gallery) — reuses feature-chat's shared sheet ────────
    // Same bottom_sheet_attach.xml + AttachSheetRecentMediaBinder that
    // ChatMediaController (1-1 chat) and GroupChatActivity already share, so
    // Status gets the same multi-select grid, caption field, Edit action and
    // view-once toggle instead of a 3rd hand-rolled picker.
    private final java.util.concurrent.ExecutorService statusAttachMediaExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // ── Media pickers (gallery) ───────────────────────────────────────────
    private ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> statusMediaPicker;
    private ActivityResultLauncher<Intent> statusMoreAppsChooser;

    /** Gallery / "See more": system photo picker for photos AND videos. */
    private void launchStatusMediaPicker() {
        statusMediaPicker.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                .build());
    }

    /** "More apps": system file chooser limited to image/video. */
    private void launchStatusMoreAppsChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        statusMoreAppsChooser.launch(Intent.createChooser(intent, "Open with"));
    }

    /** Single pick from either picker: route by MIME type to the right preview. */
    private void handleStatusPickedUri(Uri uri) {
        if (uri == null) return;
        String mime = getContentResolver().getType(uri);
        showSinglePickedPreview(uri, mime != null && mime.startsWith("video"), null);
    }

    private void setupMediaPickers() {
        statusMediaPicker = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::handleStatusPickedUri);
        statusMoreAppsChooser = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                        handleStatusPickedUri(r.getData().getData());
                    }
                });
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            pickedImage = uri; pickedVideo = null;
            showImagePreview(uri);
        });
        videoPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            pickedVideo = uri; pickedImage = null;
            showVideoPreview(uri);
        });
    }
    // ── Camera capture (NEW) ──────────────────────────────────────────────
    private void setupCameraCapture() {
        cameraCapture = registerForActivityResult(
            new ActivityResultContracts.TakePicture(), success -> {
                if (success && cameraImageUri != null) {
                    pickedImage = cameraImageUri; pickedVideo = null;
                    showImagePreview(cameraImageUri);
                }
            });
        cameraVideoCapture = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                // Video from camera intent — handled via system camera
                if (uri == null) return;
                pickedVideo = uri; pickedImage = null;
                showVideoPreview(uri);
            });
    }
    private void captureFromCamera() {
        if (!hasCameraPermission()) { requestCameraPermission(); return; }
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "status_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraImageUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (cameraImageUri != null) cameraCapture.launch(cameraImageUri);
        } catch (Exception e) {
            toast("Camera error: " + e.getMessage());
        }
    }

    // ── Reels camera (NEW) — same camera as Reels tab's + / Create button ──
    /**
     * Opens feature-reels' ReelCameraActivity — the full-feature camera
     * (filters, effects, speed control, stickers, text overlays, trending
     * audio, drafts, multi-clip) instead of the plain photo-only system
     * camera. Launched by class name (no compile-time module dependency)
     * since feature-status can't depend on feature-reels.
     *
     * The camera → editor chain is told target_status=true, which makes it
     * hand the finished video back here as an activity result (see
     * ReelCameraActivity / ReelEditorActivity) instead of continuing on
     * into the Reels upload/post flow.
     */
    /**
     * "Advance Editing" — Create step, media already picked. Routes to
     * whichever full-featured editor matches the picked media type, then —
     * because allow_media_edit_fallback is set on both — on to
     * MediaEditActivity ("media editing screen") either way, before landing
     * back on Add Status at the Edit step (step 2). Both branches reuse
     * reelCameraLauncher/handleReelCameraResult, the exact code the attach
     * sheet's pencil/Edit action already relies on, so the round trip is
     * identical to that path except for pendingAdvanceEditWizardJump, which
     * is what makes the result land on step 2 instead of staying on Create.
     */
    private void openAdvanceEditingForPickedMedia() {
        if (pickedVideo != null) {
            openAdvanceEditingForPickedVideo();
        } else if (pickedImage != null) {
            openAdvanceEditingForPickedImage();
        } else {
            toast("Pehle photo ya video select karo");
        }
    }

    /** Video branch — opens the real Reel Edit screen (feature-reels'
     *  ReelEditorActivity), same as the attach sheet's pencil/Edit action. */
    private void openAdvanceEditingForPickedVideo() {
        Intent reelEditIntent = new Intent();
        reelEditIntent.setClassName(getPackageName(), "com.callx.app.editor.ReelEditorActivity");
        reelEditIntent.putExtra("editor_video_uri", pickedVideo.toString());
        reelEditIntent.putExtra("is_file_path", false);
        reelEditIntent.putExtra("target_status", true);
        reelEditIntent.putExtra("allow_media_edit_fallback", true);
        reelEditIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (reelEditIntent.resolveActivity(getPackageManager()) == null) {
            toast("Advance editing is not available on this build");
            return;
        }
        String existingCaption = binding.etCaption != null && binding.etCaption.getText() != null
                ? binding.etCaption.getText().toString().trim() : null;
        pendingSingleVideoEditCaption = (existingCaption == null || existingCaption.isEmpty())
                ? null : existingCaption;
        pendingAdvanceEditWizardJump = true;
        reelCameraLauncher.launch(reelEditIntent);
    }

    /** Photo branch — opens the real Reel Photo Edit screen (feature-reels'
     *  ReelPhotoEditorActivity: filters/effects/caption/stickers/adjust),
     *  same "Edit Status"-titled screen the attach sheet's pencil/Edit
     *  action would open for a single picked photo. */
    private void openAdvanceEditingForPickedImage() {
        Intent photoEditIntent = new Intent();
        photoEditIntent.setClassName(getPackageName(), "com.callx.app.editor.ReelPhotoEditorActivity");
        photoEditIntent.putExtra("photo_editor_uri", pickedImage.toString());
        photoEditIntent.putExtra("photo_editor_index", 0);
        photoEditIntent.putExtra("photo_editor_count", 1);
        photoEditIntent.putExtra("target_status", true);
        photoEditIntent.putExtra("allow_media_edit_fallback", true);
        photoEditIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (photoEditIntent.resolveActivity(getPackageManager()) == null) {
            toast("Advance editing is not available on this build");
            return;
        }
        String existingCaption = binding.etCaption != null && binding.etCaption.getText() != null
                ? binding.etCaption.getText().toString().trim() : null;
        // Shared with the video branch — handleReelCameraResult()'s photo-mode
        // path (is_photo=true) doesn't currently read a preset caption back
        // in, but keeping this set costs nothing and matches the video branch
        // if that ever changes.
        pendingSingleVideoEditCaption = (existingCaption == null || existingCaption.isEmpty())
                ? null : existingCaption;
        pendingAdvanceEditWizardJump = true;
        preserveStoryOverlaysOnNextPhoto = true;
        reelCameraLauncher.launch(photoEditIntent);
    }

    private void openReelCamera() {
        try {
            Intent intent = new Intent();
            intent.setClassName(getPackageName(), "com.callx.app.camera.ReelCameraActivity");
            intent.putExtra("target_status", true);
            if (intent.resolveActivity(getPackageManager()) == null) {
                // Reels module not present on this build — fall back to the plain camera.
                captureFromCamera();
                return;
            }
            reelCameraLauncher.launch(intent);
        } catch (Exception e) {
            toast("Camera error: " + e.getMessage());
            captureFromCamera();
        }
    }

    /**
     * Handles the result bubbled back from ReelCameraActivity → ReelEditorActivity,
     * AND from ReelEditorActivity opened directly on an already-picked video via
     * the attach sheet's pencil/Edit action (see onMediaEdit() in showStatusAddSheet())
     * — both return the same result shape via ReelEditorActivity.finishForStatusResult().
     */
    private void handleReelCameraResult(androidx.activity.result.ActivityResult result) {
        // Only relevant to the pencil-edit-video path; always cleared here so a
        // later plain camera round-trip never picks up a stale caption.
        String presetCaption = pendingSingleVideoEditCaption;
        pendingSingleVideoEditCaption = null;
        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            // Cancelled out of the Reel Edit screen entirely (not the
            // allow_media_edit_fallback hop) — don't leave the Advance
            // Editing flag armed for some unrelated later result.
            pendingAdvanceEditWizardJump = false;
            preserveStoryOverlaysOnNextPhoto = false;
            return;
        }
        Intent data = result.getData();

        // NEW: backing out of the Reel Edit screen (opened directly on an
        // already-picked video — see allow_media_edit_fallback above)
        // forwards MediaEditActivity's own result through unchanged, rather
        // than a normal "finished editing in Reel Edit" result. Recognise
        // that shape (MediaEditActivity.RESULT_URIS) and hand it to the
        // exact same handler the plain attach-sheet Edit action uses, so
        // both paths land on the identical preview pipeline.
        if (data.hasExtra(com.callx.app.conversation.controllers.MediaEditActivity.RESULT_URIS)) {
            handleMediaEditActivityResult(result.getResultCode(), data);
            return;
        }

        // NEW: Photo mode — ReelCameraActivity bakes the filter/overlays itself
        // and returns the JPEG directly (no video editor in the loop).
        boolean isPhoto = data.getBooleanExtra("is_photo", false);
        String photoUriStr = data.getStringExtra("photo_uri");
        if (isPhoto && photoUriStr != null && !photoUriStr.isEmpty()) {
            Uri photoUri = Uri.fromFile(new java.io.File(photoUriStr));
            String caption = data.getStringExtra("text_overlay");
            showSinglePickedPreview(photoUri, false, caption);
            attachMusicStickerIfAny(data);
            return;
        }

        String videoUriStr = data.getStringExtra("video_uri");
        if (videoUriStr == null || videoUriStr.isEmpty()) return;
        boolean isFilePath  = data.getBooleanExtra("is_file_path", true);
        Uri videoUri = isFilePath
                ? Uri.fromFile(new java.io.File(videoUriStr))
                : Uri.parse(videoUriStr);

        String caption = data.getStringExtra("text_overlay");
        if ((caption == null || caption.isEmpty()) && presetCaption != null) caption = presetCaption;
        showSinglePickedPreview(videoUri, true, caption);
        attachMusicStickerIfAny(data);

        // ✅ FIX: pick up the custom cover frame from the Thumbnail tool (see
        // pendingCustomThumbnailFile javadoc) — must run AFTER
        // showSinglePickedPreview/showVideoPreview above, which clears it.
        String customThumbPath = data.getStringExtra("thumbnail_path");
        if (customThumbPath != null && !customThumbPath.isEmpty()) {
            java.io.File f = new java.io.File(customThumbPath);
            if (f.exists()) pendingCustomThumbnailFile = f;
        }
    }

    /**
     * If a trending-audio sound was picked in the camera, attach it as a
     * Music sticker on the status preview — same sticker/JSON format used
     * by StatusStickerPickerSheet's own "🎵 Music" option.
     */
    private void attachMusicStickerIfAny(Intent data) {
        final String soundTitle = data.getStringExtra("selected_sound_title");
        if (soundTitle == null || soundTitle.isEmpty()) return;
        final String soundId  = data.getStringExtra("selected_sound_id");
        final String soundUrl = data.getStringExtra("selected_sound_url");
        // Point 7: artist + cover now travel with the result (ReelEditorActivity
        // forwards them). Before, the card was built with artist "" and no
        // albumArt → blank art + no artist line.
        final String artist   = data.getStringExtra("selected_sound_artist");
        final String cover    = data.getStringExtra("selected_sound_cover");
        // (5) poster's trimmed range inside the song (ms) → sticker plays that part, not 0:00
        final int startMs     = data.getIntExtra("music_start_ms", 0);
        final int endMs       = data.getIntExtra("music_end_ms",   0);

        boolean haveAll = artist != null && !artist.isEmpty() && cover != null && !cover.isEmpty();
        if (haveAll || soundId == null || soundId.isEmpty()) {
            buildAndAddMusicSticker(soundTitle, artist, cover, soundId, soundUrl, startMs, endMs);
            return;
        }
        // Something is missing (older camera paths / tracks without cover) → try to
        // backfill from Firebase (sounds/{id}, then musicLibrary/{id}) BEFORE building
        // the sticker. Never blocks: if Firebase is slow/offline we build with what we
        // have after 2s. `done` makes sure the sticker is added exactly once.
        final boolean[] done = {false};
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            buildAndAddMusicSticker(soundTitle, artist, cover, soundId, soundUrl, startMs, endMs);
        };
        h.postDelayed(timeout, 2000L);
        lookupSoundMeta(FirebaseUtils.db().getReference("sounds").child(soundId), (a1, c1) -> {
            if ((a1 != null && !a1.isEmpty()) || (c1 != null && !c1.isEmpty())) {
                finishMusicBackfill(done, h, timeout, soundTitle, soundId, soundUrl, startMs, endMs, artist, cover, a1, c1);
            } else {
                lookupSoundMeta(FirebaseUtils.getMusicLibraryRef().child(soundId), (a2, c2) ->
                        finishMusicBackfill(done, h, timeout, soundTitle, soundId, soundUrl, startMs, endMs, artist, cover, a2, c2));
            }
        });
    }

    /** Merges the Firebase values into whatever the intent already had, then adds the sticker once. */
    private void finishMusicBackfill(boolean[] done, android.os.Handler h, Runnable timeout,
                                     String title, String soundId, String soundUrl,
                                     int startMs, int endMs,
                                     String artist, String cover, String fbArtist, String fbCover) {
        if (done[0]) return;           // the 2s timeout already added it
        done[0] = true;
        h.removeCallbacks(timeout);
        if (isFinishing() || isDestroyed()) return;
        String a = (artist != null && !artist.isEmpty()) ? artist : fbArtist; // intent value wins
        String c = (cover  != null && !cover.isEmpty())  ? cover  : fbCover;
        buildAndAddMusicSticker(title, a, c, soundId, soundUrl, startMs, endMs);
    }

    private interface SoundMetaCb { void onResult(String artist, String coverUrl); }

    /** Reads artist + coverUrl from a sounds/ or musicLibrary/ node (same keys both use). */
    private void lookupSoundMeta(DatabaseReference ref, SoundMetaCb cb) {
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String a = snap.exists() ? snap.child("artist").getValue(String.class) : null;
                String c = snap.exists() ? snap.child("coverUrl").getValue(String.class) : null;
                cb.onResult(a, c);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(null, null); }
        });
    }

    /** Same sticker JSON as StatusStickerPickerSheet's own Music option ("albumArt" = cover). */
    private void buildAndAddMusicSticker(String title, String artist, String cover,
                                         String soundId, String soundUrl, int startMs, int endMs) {
        try {
            org.json.JSONObject musicJson = new org.json.JSONObject();
            musicJson.put("type",     "music");
            musicJson.put("song",     title);
            musicJson.put("artist",   artist != null ? artist : "");
            musicJson.put("albumArt", cover  != null ? cover  : "");
            musicJson.put("soundId",  soundId  != null ? soundId  : "");
            musicJson.put("soundUrl", soundUrl != null ? soundUrl : "");
            if (startMs > 0) musicJson.put("startMs", startMs); // bare numbers → read back with jsonNum()
            if (endMs   > 0) musicJson.put("endMs",   endMs);
            String json = musicJson.toString();
            addedStickerJsons.add(json);
            addStickerOverlay(json); // (also enforces one-music-sticker + starts the preview)
        } catch (Exception ignored) {}
    }

    private void captureVideoFromCamera() {
        if (!hasCameraPermission()) { requestCameraPermission(); return; }
        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30); // 30s max
        intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, 9021);
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9021 && resultCode == RESULT_OK && data != null) {
            Uri videoUri = data.getData();
            if (videoUri != null) { pickedVideo = videoUri; pickedImage = null; showVideoPreview(videoUri); }
        }
    }
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }
    private void requestCameraPermission() {
        registerForActivityResult(new ActivityResultContracts.RequestPermission(),
            granted -> { if (!granted) toast("Camera permission denied"); })
            .launch(Manifest.permission.CAMERA);
    }
    // ── GIF / Sticker input (NEW) ─────────────────────────────────────────
    private void showGifInputDialog() {
        EditText et = new EditText(this);
        et.setHint("Paste GIF or sticker URL…");
        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add GIF / Sticker")
            .setView(et)
            .setPositiveButton("Preview", (d, w) -> {
                String url = et.getText().toString().trim();
                if (url.isEmpty()) return;
                Uri parsed = Uri.parse(url);
                String scheme = parsed.getScheme();
                if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                        || parsed.getHost() == null) {
                    toast("Enter a valid http(s) link");
                    return;
                }
                fetchedPreview = null;
                pickedImage = Uri.parse(url); pickedVideo = null;
                showImagePreview(pickedImage);
                // Tag as gif type
                binding.btnPickImage.setTag("gif");
            })
            .setNegativeButton("Cancel", null)
            .create());
    }
    // ── Privacy button (NEW bottom sheet) ────────────────────────────────
    private void setupPrivacyButton() {
        selectedPrivacy = StatusPrivacyManager.getPrivacyMode(this);
        updatePrivacyLabel();
        binding.btnPrivacy.setOnClickListener(v -> {
            String myUid = safeUid();
            if (myUid == null) return;
            StatusPrivacyBottomSheet.show(this, myUid, (mode, uids) -> {
                selectedPrivacy = mode;
                privacyUids     = uids;
                if ("close_friends".equals(mode)) isCloseFriends = true;
                updatePrivacyLabel();
            });
        });
    }
    private void updatePrivacyLabel() {
        String label;
        switch (selectedPrivacy) {
            case StatusPrivacyManager.PRIVACY_EVERYONE: label = "👁 Everyone"; break;
            case StatusPrivacyManager.PRIVACY_CONTACTS: label = "👥 My contacts"; break;
            case StatusPrivacyManager.PRIVACY_EXCEPT:   label = "👥 Contacts except…"; break;
            case StatusPrivacyManager.PRIVACY_ONLY:     label = "🔒 Only share with…"; break;
            case "close_friends":                       label = "⭐ Close friends"; break;
            default: label = "👥 My contacts";
        }
        binding.btnPrivacy.setText(label);
    }
    // ── Expiry button (NEW) ───────────────────────────────────────────────
    private void setupExpiryButton() {
        updateExpiryLabel();
        View btnExpiry = binding.getRoot().findViewWithTag("btn_expiry");
        if (btnExpiry instanceof Button) {
            ((Button) btnExpiry).setOnClickListener(v -> showExpiryPicker());
        }
    }
    private void showExpiryPicker() {
        String[] labels = StatusCustomExpiryHelper.getLabelOptions();
        int[] hours     = StatusCustomExpiryHelper.getHoursOptions();
        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Status expires in…")
            .setItems(labels, (d, which) -> {
                selectedExpiryHours = hours[which];
                updateExpiryLabel();
            }).create());
    }
    private void updateExpiryLabel() {
        View btn = binding.getRoot().findViewWithTag("btn_expiry");
        if (btn instanceof Button)
            ((Button) btn).setText("⏱ " + StatusCustomExpiryHelper.labelFor(selectedExpiryHours));
    }
    // ── Close friends toggle (NEW) ────────────────────────────────────────
    private void setupCloseFriendsToggle() {
        View toggle = binding.getRoot().findViewWithTag("toggle_close_friends");
        if (toggle instanceof CompoundButton) {
            ((CompoundButton) toggle).setOnCheckedChangeListener((btn, checked) -> {
                isCloseFriends = checked;
                if (checked) {
                    selectedPrivacy = "close_friends";
                    updatePrivacyLabel();
                }
            });
        }
    }
    // ── Allow sharing toggle (NEW — mirrors WhatsApp "Allow sharing") ────────
    /**
     * Looks for a CompoundButton with tag "toggle_allow_sharing" in the layout.
     * When the user turns it OFF, allowSharing=false is saved with the status
     * and the repost icon is hidden from viewers in StatusViewerActivity.
     * Default is ON (true) — same behaviour as WhatsApp.
     */
    private void setupAllowSharingToggle() {
        View toggle = binding.getRoot().findViewWithTag("toggle_allow_sharing");
        if (toggle instanceof CompoundButton) {
            ((CompoundButton) toggle).setChecked(true); // default: allow sharing
            ((CompoundButton) toggle).setOnCheckedChangeListener((btn, checked) -> {
                allowSharing = checked;
            });
        }
        // If toggle doesn't exist in the current layout, allowSharing stays true (safe default)
    }
    // ── Level-stabilizer toggle (per-story opt-IN) ────────────────────────
    /**
     * Looks for a CompoundButton with tag "toggle_stabilizer" in the layout.
     * OFF by default — the story only gets the level-stabilizer when the
     * creator explicitly switches this on for THIS post. Leaving it off (or
     * having no such toggle in the layout) means stabilizerEnabled stays
     * false, so ordinary stories never pick up the effect and it never
     * shows up in Highlights either.
     */
    private void setupStabilizerToggle() {
        View toggle = binding.getRoot().findViewWithTag("toggle_stabilizer");
        if (toggle instanceof CompoundButton) {
            ((CompoundButton) toggle).setChecked(false);
            ((CompoundButton) toggle).setOnCheckedChangeListener((btn, checked) -> {
                stabilizerEnabled = checked;
            });
        }
    }
    // ── Ring color button (NEW — reuses the Highlights ring-color-picker) ──
    /**
     * Looks for a Button/View tagged "btn_ring_color" in the layout. Tapping
     * it opens the exact same {@link com.callx.app.highlights.HighlightRingColorPickerBottomSheet}
     * used to color a Highlights album, so the picked color/mode is saved on
     * THIS status (item.ringColor / item.ringMode) and shows up as a custom
     * ring around the owner's avatar on every viewer's status-tab card.
     */
    private void setupRingColorButton() {
        View btn = binding.getRoot().findViewWithTag("btn_ring_color");
        if (btn == null) return;
        updateRingColorLabel(btn);
        btn.setOnClickListener(v ->
            com.callx.app.highlights.HighlightRingColorPickerBottomSheet.show(
                this, selectedRingColor, selectedRingMode, selectedRingColor != null,
                (colorHex, mode) -> {
                    selectedRingColor = colorHex;
                    selectedRingMode  = mode;
                    updateRingColorLabel(btn);
                }));
    }
    private void updateRingColorLabel(View btn) {
        if (btn instanceof Button) {
            ((Button) btn).setText(selectedRingColor != null ? "\u26AA Ring color set" : "\u26AA Ring color");
        }
    }
    // ── Text align buttons (NEW) ──────────────────────────────────────────
    private void setupTextAlignButtons() {
        View btnLeft   = binding.getRoot().findViewWithTag("btn_align_left");
        View btnCenter = binding.getRoot().findViewWithTag("btn_align_center");
        View btnRight  = binding.getRoot().findViewWithTag("btn_align_right");
        if (btnLeft   != null) btnLeft.setOnClickListener(v -> setTextAlign("left"));
        if (btnCenter != null) btnCenter.setOnClickListener(v -> setTextAlign("center"));
        if (btnRight  != null) btnRight.setOnClickListener(v -> setTextAlign("right"));
    }
    private void setTextAlign(String align) {
        selectedTextAlign = align;
        refreshStepPreview();
    }
    // ── Bg color picker ───────────────────────────────────────────────────
    private void setupBgColorPicker() {
        for (int i = 0; i < BG_COLORS.length; i++) {
            final int idx = i;
            View swatch = getBgSwatch(i);
            if (swatch == null) continue;
            swatch.setBackgroundColor(BG_COLORS[i]);
            swatch.setOnClickListener(v -> {
                selectedBgColor = BG_COLORS[idx]; selectedTextColor = TEXT_COLORS_FOR_BG[idx];
                updateTextStatusPreview(); highlightSwatch(idx);
            });
        }
        highlightSwatch(0);
    }
    private View getBgSwatch(int idx) {
        try {
            int id = getResources().getIdentifier("color_swatch_" + idx, "id", getPackageName());
            return id != 0 ? findViewById(id) : null;
        } catch (Exception e) { return null; }
    }
    private void highlightSwatch(int sel) {
        for (int i = 0; i < BG_COLORS.length; i++) {
            View v = getBgSwatch(i);
            if (v != null) { v.setScaleX(i == sel ? 1.3f : 1f); v.setScaleY(i == sel ? 1.3f : 1f); }
        }
    }
    private void hideBgColorPicker() { binding.bgColorPickerRow.setVisibility(View.GONE); }
    private void showBgColorPicker()  { binding.bgColorPickerRow.setVisibility(View.VISIBLE); updateTextStatusPreview(); }
    // ── Font style picker ─────────────────────────────────────────────────
    private void setupFontStylePicker() {
        binding.btnFontDefault.setOnClickListener(v     -> setFont("default"));
        binding.btnFontBold.setOnClickListener(v        -> setFont("bold"));
        binding.btnFontItalic.setOnClickListener(v      -> setFont("italic"));
        binding.btnFontHandwriting.setOnClickListener(v -> setFont("handwriting"));
        View btnCondensed = binding.getRoot().findViewWithTag("btn_font_condensed");
        if (btnCondensed != null) btnCondensed.setOnClickListener(v -> setFont("condensed"));
        View btnSerif = binding.getRoot().findViewWithTag("btn_font_serif");
        if (btnSerif != null) btnSerif.setOnClickListener(v -> setFont("serif"));
    }
    private void setFont(String style) {
        selectedFontStyle = style;
        binding.btnFontDefault.setSelected("default".equals(style));
        binding.btnFontBold.setSelected("bold".equals(style));
        binding.btnFontItalic.setSelected("italic".equals(style));
        binding.btnFontHandwriting.setSelected("handwriting".equals(style));
        updateTextStatusPreview();
    }
    // ── Text input + link detection + mention ────────────────────────────
    private void setupTextInput() {
        binding.etText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int len = s.length();
                binding.tvCharCount.setText(len + " / 700");
                if (len > 700) binding.tilText.setError("Max 700 characters");
                else           binding.tilText.setError(null);
                updateTextStatusPreview();
                detectAndFetchLinkPreview(s.toString());
            }
        });
    }
    // ── Link preview (NEW) ────────────────────────────────────────────────
    private void detectAndFetchLinkPreview(String text) {
        String url = StatusLinkPreviewHelper.extractUrl(text);
        if (url == null) {
            detectedLinkUrl = null; fetchedPreview = null;
            hideLinkPreview();
            return;
        }
        if (url.equals(detectedLinkUrl)) return;
        detectedLinkUrl = url;
        showLinkPreviewLoading();
        StatusLinkPreviewHelper.fetch(url, new StatusLinkPreviewHelper.Callback() {
            @Override public void onResult(StatusLinkPreviewHelper.LinkPreview preview) {
                fetchedPreview = preview;
                runOnUiThread(() -> {
                    if (preview.isValid()) showLinkPreview(preview);
                    else hideLinkPreview();
                });
            }
            @Override public void onError(String error) {
                fetchedPreview = null;
                runOnUiThread(() -> hideLinkPreview());
            }
        });
    }
    private void showLinkPreviewLoading() {
        View card = binding.getRoot().findViewWithTag("link_preview_card");
        if (card != null) card.setVisibility(View.VISIBLE);
        View pb = binding.getRoot().findViewWithTag("link_preview_progress");
        if (pb != null) pb.setVisibility(View.VISIBLE);
        View content = binding.getRoot().findViewWithTag("link_preview_content");
        if (content != null) content.setVisibility(View.GONE);
    }
    private void showLinkPreview(StatusLinkPreviewHelper.LinkPreview preview) {
        View card = binding.getRoot().findViewWithTag("link_preview_card");
        if (card == null) return;
        card.setVisibility(View.VISIBLE);
        View pb = binding.getRoot().findViewWithTag("link_preview_progress");
        if (pb != null) pb.setVisibility(View.GONE);
        View content = binding.getRoot().findViewWithTag("link_preview_content");
        if (content != null) content.setVisibility(View.VISIBLE);
        TextView tvTitle = binding.getRoot().findViewWithTag("link_preview_title");
        if (tvTitle != null) tvTitle.setText(preview.title);
        TextView tvDesc = binding.getRoot().findViewWithTag("link_preview_desc");
        if (tvDesc != null) tvDesc.setText(preview.description);
        TextView tvDomain = binding.getRoot().findViewWithTag("link_preview_domain");
        if (tvDomain != null) tvDomain.setText(preview.domain);
        android.widget.ImageView ivImage = binding.getRoot().findViewWithTag("link_preview_image");
        if (ivImage != null && preview.imageUrl != null)
            Glide.with(this).load(preview.imageUrl).override(480, 853).into(ivImage);
    }
    private void hideLinkPreview() {
        View card = binding.getRoot().findViewWithTag("link_preview_card");
        if (card != null) card.setVisibility(View.GONE);
    }
    // ── Preview card ──────────────────────────────────────────────────────
    private void updateTextStatusPreview() {
        // status_step_preview_card (bound via refreshStepPreview) is now the
        // single preview for every wizard step — the old duplicate inline
        // card has been removed from the layout entirely.
        refreshStepPreview();
        if (pickedImage != null || pickedVideo != null) return;
        String text = binding.etText.getText().toString().trim();
        if (text.isEmpty()) return;
    }
    private void applyFontStyle(android.widget.TextView tv, String style) {
        if (style == null) return;
        switch (style) {
            case "bold":        tv.setTypeface(null, android.graphics.Typeface.BOLD); break;
            case "italic":      tv.setTypeface(null, android.graphics.Typeface.ITALIC); break;
            case "handwriting": tv.setTypeface(android.graphics.Typeface.MONOSPACE); break;
            case "condensed":   tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); break;
            case "serif":       tv.setTypeface(android.graphics.Typeface.SERIF); break;
            default:            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }
    // ── Draft save/restore ────────────────────────────────────────────────
    private void saveDraft() {
        getSharedPreferences(PREFS_DRAFT, MODE_PRIVATE)
            .edit().putString(KEY_DRAFT, binding.etText.getText().toString()).apply();
    }
    private void restoreDraft() {
        String draft = getSharedPreferences(PREFS_DRAFT, MODE_PRIVATE).getString(KEY_DRAFT, "");
        if (draft != null && !draft.isEmpty()) binding.etText.setText(draft);
    }
    private void clearDraft() {
        getSharedPreferences(PREFS_DRAFT, MODE_PRIVATE).edit().remove(KEY_DRAFT).apply();
    }
    // ── Media preview helpers ─────────────────────────────────────────────
    /**
     * v238: Common landing spot for any single photo/video coming from the
     * attach sheet's "Send" or "Edit" chips — mirrors exactly what gallery
     * pick, camera capture, and the layout flow already do: set
     * pickedImage/pickedVideo, show the preview, optionally seed the
     * caption box, and leave posting to the person's own tap on Post.
     */
    private void showSinglePickedPreview(Uri uri, boolean isVideo, String caption) {
        if (isVideo) {
            pickedVideo = uri; pickedImage = null;
            showVideoPreview(uri);
        } else {
            pickedImage = uri; pickedVideo = null;
            showImagePreview(uri);
        }
        if (caption != null && !caption.isEmpty() && binding.etCaption != null) {
            binding.etCaption.setText(caption);
        }
        // Advance Editing round trip (Reel Edit → Media Editing) lands here
        // like any other single-media result — jump straight to the Edit
        // step (step 2) instead of leaving the person back on Create.
        if (pendingAdvanceEditWizardJump) {
            pendingAdvanceEditWizardJump = false;
            showWizardStep(1);
        }
    }
    private void showImagePreview(Uri uri) {
        stopVideoPreview();
        boolean changedMedia = editorMediaUri == null || !editorMediaUri.equals(uri);
        if (changedMedia) {
            editorMediaUri = uri;
            editorMediaScale = 1f;
            editorGesturePointerCount = 0;
            editorPinchStartDistance = 0f;
            editorPhotoMode = true;
            editorDrawMode = false;
            if (!preserveStoryOverlaysOnNextPhoto) {
                editorAiLabelEnabled = false;
                if (drawingOverlay != null) drawingOverlay.clearDrawing();
                if (editorTextOverlay != null) {
                    stickerOverlayFrame.removeView(editorTextOverlay);
                    editorTextOverlay = null;
                }
                if (editorAiLabel != null) {
                    stickerOverlayFrame.removeView(editorAiLabel);
                    editorAiLabel = null;
                }
            }
            preserveStoryOverlaysOnNextPhoto = false;
            if (drawingOverlay != null) drawingOverlay.setDrawEnabled(false);
        }
        binding.ivPreview.setVisibility(View.VISIBLE);
        binding.ivVideoHint.setVisibility(View.GONE);
        binding.btnDiscardMedia.setVisibility(View.VISIBLE);
        // Also show the overlay frame (so stickers are visible over the image)
        if (stickerOverlayFrame != null) stickerOverlayFrame.setVisibility(View.VISIBLE);
        if (editorBlurBackground != null) {
            editorBlurBackground.setVisibility(View.VISIBLE);
            Glide.with(this).load(uri)
                    .apply(new RequestOptions().transform(new CenterCrop(),
                            new StatusBlurTransformation(12)))
                    .into(editorBlurBackground);
        }
        // v587: decode larger than the view so pinch-zoom (up to 5x) and the 1080px export stay sharp.
        // AT_MOST never upscales small photos; hardware bitmaps are off because renderStoryCanvas()
        // draws into a software Canvas (hardware bitmaps throw there).
        Glide.with(this).load(uri).fitCenter()
                .downsample(com.bumptech.glide.load.resource.bitmap.DownsampleStrategy.AT_MOST)
                .override(1800, 3200)
                .disallowHardwareConfig()
                .into(binding.ivPreview);
        binding.ivPreview.setScaleX(1f);
        binding.ivPreview.setScaleY(1f);
        binding.ivPreview.setTranslationX(0f);
        binding.ivPreview.setTranslationY(0f);
        binding.ivPreview.setRotation(0f);
        editorMediaRotation = 0f;
        editorMediaScale = 1f;
        binding.captionGroup.setVisibility(View.VISIBLE);
        hideBgColorPicker();
        sizeStoryCanvas();
        String text = binding.etText.getText() == null ? "" : binding.etText.getText().toString().trim();
        if (editorTextOverlay != null) setEditorTextOverlay(editorTextOverlay.getText().toString());
        else if (!text.isEmpty()) setEditorTextOverlay(text);
        refreshStepPreview();
        updateEditorRailForMedia();
        if (wizardStep == 0) showWizardStep(1);
    }
    private void showVideoPreview(Uri uri) {
        // ✅ FIX: default-clear any earlier custom thumbnail pick — only
        // handleReelCameraResult() re-arms it, right after this call, when
        // the Reel Edit screen actually returned one for THIS video.
        pendingCustomThumbnailFile = null;
        boolean changedMedia = editorMediaUri == null || !editorMediaUri.equals(uri);
        if (changedMedia) {
            editorMediaUri = uri;
            editorMediaScale = 1f;
            editorDrawMode = false;
            if (drawingOverlay != null) {
                drawingOverlay.clearDrawing();
                drawingOverlay.setDrawEnabled(false);
            }
            if (editorTextOverlay != null) {
                stickerOverlayFrame.removeView(editorTextOverlay);
                editorTextOverlay = null;
            }
            if (editorAiLabel != null) {
                stickerOverlayFrame.removeView(editorAiLabel);
                editorAiLabel = null;
                editorAiLabelEnabled = false;
            }
        }
        editorPhotoMode = false; // video transforms are handled in the existing Reel editor
        binding.ivPreview.setVisibility(View.VISIBLE);
        binding.ivVideoHint.setVisibility(View.VISIBLE);
        binding.btnDiscardMedia.setVisibility(View.VISIBLE);
        if (stickerOverlayFrame != null) stickerOverlayFrame.setVisibility(View.VISIBLE);
        if (editorBlurBackground != null) {
            editorBlurBackground.setVisibility(View.VISIBLE);
            Glide.with(this).load(uri)
                    .apply(new RequestOptions().transform(new CenterCrop(),
                            new StatusBlurTransformation(12)))
                    .into(editorBlurBackground);
        }
        Glide.with(this).load(uri).fitCenter().into(binding.ivPreview);
        binding.ivPreview.setScaleX(1f);
        binding.ivPreview.setScaleY(1f);
        binding.ivPreview.setTranslationX(0f);
        binding.ivPreview.setTranslationY(0f);
        binding.ivPreview.setRotation(0f);
        editorMediaRotation = 0f;
        binding.captionGroup.setVisibility(View.VISIBLE);
        hideBgColorPicker();
        sizeStoryCanvas();
        refreshStepPreview();
        updateEditorRailForMedia();
        startVideoPreview(uri);
        if (wizardStep == 0) showWizardStep(1);
    }
    private void discardMedia() {
        pickedImage = null; pickedVideo = null; cameraImageUri = null;
        stopVideoPreview();
        updateEditorRailForMedia();
        editorMediaUri = null;
        editorPhotoMode = false;
        editorMediaScale = 1f;
        editorGesturePointerCount = 0;
        editorPinchStartDistance = 0f;
        editorDrawMode = false;
        editorAiLabelEnabled = false;
        if (drawingOverlay != null) {
            drawingOverlay.clearDrawing();
            drawingOverlay.setDrawEnabled(false);
        }
        if (editorTextOverlay != null) {
            stickerOverlayFrame.removeView(editorTextOverlay);
            editorTextOverlay = null;
        }
        if (editorAiLabel != null) {
            stickerOverlayFrame.removeView(editorAiLabel);
            editorAiLabel = null;
        }
        if (editorBlurBackground != null) {
            Glide.with(this).clear(editorBlurBackground);
            editorBlurBackground.setVisibility(View.GONE);
        }
        binding.btnPickImage.setTag(null);
        pendingCustomThumbnailFile = null;
        binding.ivPreview.setVisibility(View.GONE);
        binding.ivPreview.setScaleX(1f);
        binding.ivPreview.setScaleY(1f);
        binding.ivPreview.setTranslationX(0f);
        binding.ivPreview.setTranslationY(0f);
        binding.ivPreview.setRotation(0f);
        editorMediaRotation = 0f;
        binding.ivVideoHint.setVisibility(View.GONE);
        binding.btnDiscardMedia.setVisibility(View.GONE);
        binding.captionGroup.setVisibility(View.GONE);
        showBgColorPicker();
        refreshStepPreview();
        if (wizardStep == 1) showWizardStep(0);
    }

    /** Returns a JSON array string of all added sticker configs, or "" if none. */
    /**
     * Text overlay kept as DATA (Reels-style overlay layer) instead of being burned into the photo,
     * so it stays sharp after upload compression. Snapshotted right before the photo is baked;
     * consumed (reset) by buildStickersJson().
     */
    private volatile String pendingTextOverlayJson;

    /** null = nothing to keep as data (no text / not a baked photo / serialising failed) -> text is baked as before. */
    private String snapshotTextOverlayJson() {
        if (!editorPhotoMode || stickerOverlayFrame == null || editorTextOverlay == null
                || editorTextOverlay.getVisibility() != View.VISIBLE
                || editorTextOverlay.getText().toString().trim().isEmpty()
                || stickerOverlayFrame.getWidth() <= 0 || stickerOverlayFrame.getHeight() <= 0) return null;
        return com.callx.app.utils.StatusTextOverlayRenderer.toJson(this, editorTextOverlay,
                DRAW_COLORS[editorTextColorIdx], editorTextBgMode, editorTextSizeSp,
                editorTextFontIdx, editorTextAlignIdx,
                stickerOverlayFrame.getWidth(), stickerOverlayFrame.getHeight(),
                Math.max(dp(120), stickerOverlayFrame.getWidth() - dp(105)));
    }

    private String buildStickersJson() {
        if (stickerOverlayFrame == null) return "";
        java.util.List<String> jsons = new java.util.ArrayList<>();
        final String textOverlayJson = pendingTextOverlayJson;
        pendingTextOverlayJson = null;
        if (textOverlayJson != null) jsons.add(textOverlayJson);
        for (int i = 0; i < stickerOverlayFrame.getChildCount(); i++) {
            View child = stickerOverlayFrame.getChildAt(i);
            if (child instanceof com.callx.app.stickers.StatusStickerOverlayView) {
                com.callx.app.stickers.StatusStickerOverlayView sv =
                        (com.callx.app.stickers.StatusStickerOverlayView) child;
                String j = sv.toJsonWithScale();
                if ("addyours".equals(sv.getStickerType())) j = withAddYoursTemplate(j);
                jsons.add(j);
            }
        }
        if (jsons.isEmpty()) return "";
        return "[" + android.text.TextUtils.join(",", jsons) + "]";
    }

    /**
     * v578: the chain STARTER's Add Yours sticker (tpl=true, no originUid yet) gets a "template" snapshot baked in:
     * sticker size/rotation/position always, plus background / font / text colour / alignment when this is a text
     * status. Joiners get the same look pre-applied (applyAddYoursTemplate). "tpl" itself is dropped from the post.
     */
    private String withAddYoursTemplate(String stickerJson) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(stickerJson);
            boolean wantsTpl = o.optBoolean("tpl", false);
            o.remove("tpl");
            if (wantsTpl && o.optString("originUid", "").isEmpty() && !o.has("template")) {
                org.json.JSONObject t = new org.json.JSONObject();
                if (pickedImage == null && pickedVideo == null) { // text status: style is part of the look
                    t.put("bgColor",   String.format("#%08X", selectedBgColor));
                    t.put("textColor", String.format("#%08X", selectedTextColor));
                    t.put("fontStyle", selectedFontStyle != null ? selectedFontStyle : "default");
                    t.put("textAlign", selectedTextAlign != null ? selectedTextAlign : "center");
                }
                t.put("scale",       o.optDouble("scale", 1.0));
                t.put("rotationDeg", o.optDouble("rotationDeg", 0.0));
                if (o.has("posXRatio") && o.has("posYRatio")) {
                    t.put("posXRatio", o.optDouble("posXRatio", -1));
                    t.put("posYRatio", o.optDouble("posYRatio", -1));
                }
                o.put("template", t);
            }
            return o.toString();
        } catch (Exception e) {
            return stickerJson;
        }
    }

    /** v578: pre-applies the chain starter's text-status look (background, font, colour, alignment) for a joiner. */
    private void applyAddYoursTemplate(String stickerJson) {
        try {
            org.json.JSONObject t = new org.json.JSONObject(stickerJson).optJSONObject("template");
            if (t == null) return;
            String bg = t.optString("bgColor", "");
            if (!bg.isEmpty()) {
                int c = android.graphics.Color.parseColor(bg);
                for (int i = 0; i < BG_COLORS.length; i++) {
                    if (BG_COLORS[i] == c) {
                        selectedBgColor = c;
                        selectedTextColor = TEXT_COLORS_FOR_BG[i];
                        highlightSwatch(i);
                        break;
                    }
                }
            }
            String tc = t.optString("textColor", "");
            if (!tc.isEmpty()) selectedTextColor = android.graphics.Color.parseColor(tc);
            String font = t.optString("fontStyle", "");
            if (!font.isEmpty()) setFont(font);
            String align = t.optString("textAlign", "");
            if (!align.isEmpty()) setTextAlign(align);
            updateTextStatusPreview();
        } catch (Exception ignored) { /* bad/legacy template → plain composer */ }
    }
    // ── Post ──────────────────────────────────────────────────────────────
    private void post() {
        String txt     = binding.etText.getText().toString().trim();
        String caption = binding.etCaption != null ? binding.etCaption.getText().toString().trim() : "";
        // the "gif" tag is only trusted while pickedImage is still the pasted web URL — a
        // later gallery/camera pick must never be saved as a gif with a local content:// URI
        boolean isGif  = "gif".equals(binding.btnPickImage.getTag())
                && pickedImage != null
                && ("https".equalsIgnoreCase(pickedImage.getScheme()) || "http".equalsIgnoreCase(pickedImage.getScheme()));
        if (pickedImage == null && pickedVideo == null && txt.isEmpty() && fetchedPreview == null) {
            toast("Kuch text ya media add karo"); return;
        }
        if (txt.length() > 700) { toast("Text 700 characters se zyada nahi"); return; }
        setPosting(true);
        String uid  = FirebaseUtils.getCurrentUid();
        String name = FirebaseUtils.getCurrentName();
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String thumb = snap.child("thumbUrl").getValue(String.class);
                String full  = snap.child("photoUrl").getValue(String.class);
                String photo = (thumb != null && !thumb.isEmpty()) ? thumb : (full != null ? full : safePhoto());
                dispatchPost(txt, caption, uid, name, photo, isGif);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                dispatchPost(txt, caption, uid, name, safePhoto(), isGif);
            }
        });
    }
    private void dispatchPost(String txt, String caption, String uid, String name, String photo, boolean isGif) {
        if (fetchedPreview != null && pickedImage == null && pickedVideo == null) {
            // Link status
            saveStatus("link", fetchedPreview.imageUrl, null, txt, caption, uid, name, photo);
        } else if (isGif && pickedImage != null) {
            // GIF / sticker — store as sticker type
            saveStatus("gif", pickedImage.toString(), null, txt, caption, uid, name, photo);
        } else if (pickedImage != null) {
            prepareAndUploadEditedPhoto(pickedImage, caption, txt, uid, name, photo, 0);
        } else if (pickedVideo != null) {
            compressAndUploadVideo(pickedVideo, caption, txt, uid, name, photo);
        } else {
            saveStatus("text", null, null, txt, caption, uid, name, photo);
        }
    }

    /**
     * Wait for both Glide layers, then bake the exact photo canvas (blurred
     * background + transformed foreground + text/drawing) before compression
     * and upload. Sticker cards stay as their existing JSON overlays so the
     * status viewer does not render them twice.
     */
    private void prepareAndUploadEditedPhoto(Uri source, String caption, String txt,
                                             String uid, String name, String photo, int attempt) {
        pendingTextOverlayJson = null;
        if (!editorPhotoMode || stickerOverlayFrame == null) {
            compressAndUploadImage(source, caption, txt, uid, name, photo);
            return;
        }
        boolean ready = editorMediaUri != null && editorMediaUri.equals(source)
                && stickerOverlayFrame.getWidth() > 0 && stickerOverlayFrame.getHeight() > 0
                && binding.ivPreview.getDrawable() != null
                && editorBlurBackground != null && editorBlurBackground.getDrawable() != null;
        if (!ready) {
            if (attempt < 40 && stickerOverlayFrame != null) {
                stickerOverlayFrame.postDelayed(() ->
                        prepareAndUploadEditedPhoto(source, caption, txt, uid, name, photo, attempt + 1), 100);
                return;
            }
            setPosting(false);
            toast("Story preview load nahi hua — photo dubara select karke try karo");
            return;
        }

        final Bitmap composed;
        pendingTextOverlayJson = snapshotTextOverlayJson(); // non-null -> renderStoryCanvas leaves the text out of the pixels
        try {
            composed = renderStoryCanvas(false);
        } catch (Exception e) {
            pendingTextOverlayJson = null;
            setPosting(false);
            toast("Story preview prepare nahi hua: " + e.getMessage());
            return;
        }
        setHint("Preparing story image…");
        new Thread(() -> {
            // FAST PATH: encode the baked canvas straight to the final upload WebP (+ thumb) — no
            // intermediate JPEG file, no decode, no second lossy generation. Falls back to the
            // original JPEG -> ImageCompressor route below if anything throws.
            ImageCompressor.Result fastResult = null;
            try {
                fastResult = ImageCompressor.compressBitmapSync(this, composed, STATUS_PHOTO_HD);
            } catch (Throwable ignored) { /* fall through to the slow path */ }
            if (fastResult != null) {
                final ImageCompressor.Result fr = fastResult;
                composed.recycle();
                runOnUiThread(() -> {
                    if (!isFinishing() && pickedImage != null && source.equals(pickedImage)) {
                        pendingMediaW = fr.fullWidth; pendingMediaH = fr.fullHeight;
                        setHint("Uploading image…");
                        uploadAndSave(Uri.fromFile(fr.fullFile), "image", caption, txt, uid, name, photo, fr.thumbFile);
                    } else {
                        pendingTextOverlayJson = null;
                        fr.fullFile.delete();
                        fr.thumbFile.delete();
                        setPosting(false);
                    }
                });
                return;
            }
            java.io.File baked = null;
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "media_edit_out");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Cannot create cache folder");
                baked = new java.io.File(dir, "status_story_" + System.currentTimeMillis() + ".jpg");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(baked)) {
                    if (!composed.compress(Bitmap.CompressFormat.JPEG, 94, out))
                        throw new java.io.IOException("Could not encode story image");
                }
            } catch (Exception e) {
                if (baked != null) try { baked.delete(); } catch (Exception ignored) {}
                runOnUiThread(() -> {
                    pendingTextOverlayJson = null;
                    composed.recycle();
                    setPosting(false);
                    toast("Story image prepare nahi hua: " + e.getMessage());
                });
                return;
            }
            composed.recycle();
            java.io.File result = baked;
            runOnUiThread(() -> {
                if (!isFinishing() && pickedImage != null && source.equals(pickedImage))
                    compressAndUploadImage(Uri.fromFile(result), caption, txt, uid, name, photo);
                else {
                    pendingTextOverlayJson = null;
                    result.delete();
                    setPosting(false);
                }
            });
        }, "status-story-compose").start();
    }
    // Photo size from ImageCompressor → stored on the status (mediaWidth/mediaHeight) so
    // viewers can reserve the right aspect box. Consumed (reset) by saveStatus/saveBatchStatus.
    private int pendingMediaW = 0, pendingMediaH = 0;
    /** v557: ThumbHash of the media being uploaded (computed off-thread at upload start, consumed by saveStatus/saveBatchStatus). */
    private volatile String pendingThumbHash;
    private volatile int thumbHashGen;
    private static final int STATUS_PHOTO_THUMB_PX = 320;
    // Stories are viewed full-screen: use ImageCompressor's HD tier (1920x2560 @ q92, ~3MB cap)
    // instead of Standard (1280x1920 @ q80, 800KB cap) so photos stay as sharp as Instagram's.
    private static final boolean STATUS_PHOTO_HD = true;

    private void compressAndUploadImage(Uri uri, String caption, String txt, String uid, String name, String photo) {
        runOnUiThread(() -> setHint("Compressing image…"));
        ImageCompressor.compress(this, uri, STATUS_PHOTO_HD, new ImageCompressor.Callback() {
            @Override public void onSuccess(ImageCompressor.Result r) {
                pendingMediaW = r.fullWidth; pendingMediaH = r.fullHeight;
                runOnUiThread(() -> setHint("Uploading image…"));
                uploadAndSave(Uri.fromFile(r.fullFile), "image", caption, txt, uid, name, photo, r.thumbFile);
            }
            @Override public void onError(Exception e) {
                pendingMediaW = 0; pendingMediaH = 0;
                runOnUiThread(() -> setHint("Uploading image…"));
                uploadAndSave(uri, "image", caption, txt, uid, name, photo, null);
            }
        });
    }
    private void compressAndUploadVideo(Uri uri, String caption, String txt, String uid, String name, String photo) {
        runOnUiThread(() -> setHint("Compressing video… 0%"));
        // ✅ FIX: snapshot the Reel Edit screen's custom cover pick now — the
        // person picked it for THIS video, so it should win over whatever
        // frame VideoCompressor auto-grabs below (see pendingCustomThumbnailFile).
        java.io.File customThumb = pendingCustomThumbnailFile;
        VideoQualityPreferences.Quality quality = new VideoQualityPreferences(this).getGlobalQuality();
        VideoCompressor.compress(this, uri, quality, new VideoCompressor.Callback() {
            @Override public void onProgress(int pct) { runOnUiThread(() -> setHint("Compressing video… " + pct + "%")); }
            @Override public void onSuccess(VideoCompressor.Result r) {
                runOnUiThread(() -> setHint("Uploading video…"));
                if (customThumb != null && customThumb.exists()) {
                    // Discard the auto-generated frame, use the person's pick instead.
                    VideoCompressor.safeDelete(r.thumbFile);
                    uploadAndSave(Uri.fromFile(r.videoFile), "video", caption, txt, uid, name, photo, customThumb, true);
                } else {
                    uploadAndSave(Uri.fromFile(r.videoFile), "video", caption, txt, uid, name, photo, r.thumbFile, false);
                }
            }
            @Override public void onError(Exception e) {
                runOnUiThread(() -> setHint("Uploading video…"));
                boolean useCustom = customThumb != null && customThumb.exists();
                uploadAndSave(uri, "video", caption, txt, uid, name, photo,
                    useCustom ? customThumb : null, useCustom);
            }
        });
    }
    private void uploadAndSave(Uri uri, String type, String caption, String txt,
                                String uid, String name, String photo, java.io.File thumbFile) {
        uploadAndSave(uri, type, caption, txt, uid, name, photo, thumbFile, false);
    }
    private void uploadAndSave(Uri uri, String type, String caption, String txt,
                                String uid, String name, String photo, java.io.File thumbFile,
                                boolean forceCustomThumb) {
        String rt = "video".equals(type) ? "video" : "image";
        pendingThumbHash = null;
        final int hashGen = ++thumbHashGen; // a late result for an already-saved item must not leak into the next one
        StatusThumbHashHelper.computeAsync(this, uri, "video".equals(type), thumbFile,
                h -> { if (hashGen == thumbHashGen) pendingThumbHash = h; });
        CloudinaryUploader.upload(this, uri, "callx/status", rt, new CloudinaryUploader.UploadCallback() {
            @Override public void onSuccess(CloudinaryUploader.Result r) {
                runOnUiThread(() -> {
                    setPosting(false);
                    // ✅ FIX: Cloudinary's own r.thumbnailUrl is just its
                    // auto-extracted video frame, which used to always win
                    // over a custom pick from the Reel Edit screen's
                    // Thumbnail tool. When forceCustomThumb is set, hold off
                    // saving a thumbnailUrl until the person's own pick has
                    // uploaded and can patch it in below instead.
                    String thumbUrl = ("video".equals(type) && !forceCustomThumb) ? r.thumbnailUrl : null;
                    // Photos used to get NO thumbnailUrl at all → viewer had nothing to blur-up from
                    // (black flash) and tray/highlight covers loaded the full-size file. Derive a
                    // real aspect-preserving 320px variant from the Cloudinary URL (no extra upload).
                    if ("image".equals(type)) thumbUrl = CloudinaryUploader.deriveLimitThumbUrl(r.secureUrl, STATUS_PHOTO_THUMB_PX);
                    if (!forceCustomThumb) VideoCompressor.safeDelete(thumbFile);
                    saveStatus(type, r.secureUrl, thumbUrl, txt, caption, uid, name, photo);
                    // FIX v25: If thumb was locally generated and Cloudinary didn't return one, upload separately
                    if ("video".equals(type) && thumbUrl == null && thumbFile != null && thumbFile.exists()) {
                        uploadThumbAndPatch(thumbFile, uid, r.secureUrl);
                    }
                });
            }
            @Override public void onError(String err) {
                pendingTextOverlayJson = null;
                runOnUiThread(() -> { setPosting(false); toast(err != null ? err : "Upload failed, try again"); });
            }
        });
    }
    private void uploadThumbAndPatch(java.io.File thumbFile, String uid, String mediaUrl) {
        Uri thumbUri = Uri.fromFile(thumbFile);
        CloudinaryUploader.upload(this, thumbUri, "callx/status/thumb", "image",
            new CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    VideoCompressor.safeDelete(thumbFile);
                    // FIX v25: Patch thumbnailUrl on status node — find the status by mediaUrl
                    FirebaseUtils.getStatusRef().child(uid)
                        .orderByChild("mediaUrl").equalTo(mediaUrl).limitToLast(1)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                                for (DataSnapshot c : snap.getChildren())
                                    c.getRef().child("thumbnailUrl").setValue(r.secureUrl);
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onError(String err) { VideoCompressor.safeDelete(thumbFile); }
            });
    }
    private void saveStatus(String type, String mediaUrl, String thumbUrl,
                            String txt, String caption, String uid, String name, String photo) {
        long now = System.currentTimeMillis();
        DatabaseReference ref = FirebaseUtils.getStatusRef().child(uid).push();
        StatusItem item       = new StatusItem();
        item.id               = ref.getKey();
        item.ownerUid         = uid;
        item.ownerName        = name;
        item.ownerPhoto       = photo;
        item.type             = type;
        item.text             = txt.isEmpty() ? null : txt;
        item.caption          = caption.isEmpty() ? null : caption;
        item.mediaUrl         = mediaUrl;
        item.thumbnailUrl     = thumbUrl;
        if ("image".equals(type) || "video".equals(type)) item.thumbHash = pendingThumbHash;
        pendingThumbHash = null; thumbHashGen++;
        if ("image".equals(type) && pendingMediaW > 0 && pendingMediaH > 0) {
            item.mediaWidth = pendingMediaW; item.mediaHeight = pendingMediaH;
        }
        pendingMediaW = 0; pendingMediaH = 0;
        item.bgColor          = String.format("#%08X", selectedBgColor);
        item.fontStyle        = selectedFontStyle;
        item.textColor        = String.format("#%08X", selectedTextColor);
        item.textAlign        = selectedTextAlign;
        item.privacy          = selectedPrivacy;
        item.privacyList      = privacyUids.isEmpty() ? null : new ArrayList<>(privacyUids);
        item.isCloseFriends   = isCloseFriends;
        item.allowSharing     = allowSharing;
        item.stabilizerEnabled = stabilizerEnabled;
        item.ringColor        = selectedRingColor != null ? selectedRingColor : "";
        item.ringMode         = selectedRingMode  != null ? selectedRingMode  : "";
        item.expiryHours      = selectedExpiryHours;
        item.timestamp        = now;
        item.expiresAt        = StatusCustomExpiryHelper.computeExpiresAt(selectedExpiryHours);
        item.deleted          = false;
        // ── v26: Attach interactive sticker JSON ──────────────────────────
        String stickersJson = buildStickersJson();
        if (!stickersJson.isEmpty()) {
            item.stickersJson = stickersJson;
        }
        // Mentions: uid resolution + notification are done server-side (functions: onStatusCreatedMentions),
        // which writes status/{owner}/{id}/mentionNames/{username} = uid. The client must not write them.
        // Link metadata
        if ("link".equals(type) && fetchedPreview != null) {
            item.linkUrl         = fetchedPreview.url;
            item.linkTitle       = fetchedPreview.title;
            item.linkDescription = fetchedPreview.description;
            item.linkImageUrl    = fetchedPreview.imageUrl;
            item.linkDomain      = fetchedPreview.domain;
        }
        // ── Scheduled post? Route to statusScheduled instead of going live now ──
        if (pendingScheduledAt > 0) {
            long scheduledAt = pendingScheduledAt;
            pendingScheduledAt = 0;
            item.scheduledAt = scheduledAt;
            item.timestamp   = scheduledAt;
            // Expiry must count down from the actual publish time, not from
            // "now" (composition time) — otherwise a status scheduled a day
            // out with a 24h expiry would already be dead on arrival.
            item.expiresAt = scheduledAt + (long) selectedExpiryHours * 3_600_000L;
            com.callx.app.repository.StatusRepository.getInstance(this)
                .scheduleStatus(item, scheduledAt, ok -> runOnUiThread(() -> {
                    if (ok) {
                        clearDraft();
                        String fmt = new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                            .format(new Date(scheduledAt));
                        toast("Status scheduled for " + fmt);
                        finish();
                    } else {
                        setPosting(false);
                        toast("Failed to schedule status");
                    }
                }));
            return;
        }
        ref.setValue(item.toMap())
            .addOnSuccessListener(u -> {
                clearDraft();
                StatusNotificationHelper.scheduleStatusExpiryReminder(this, item.id, item.expiresAt);
                toast("Status posted!");
                warnUnresolvedMentions(item);
                finish();
            })
            .addOnFailureListener(e -> {
                setPosting(false);
                toast("Failed to post: " + e.getMessage());
            });
    }
    // ── Batch post (attach-sheet multi-select) ─────────────────────────────
    // Posts each selected item from the attach-sheet as its own status entry
    // (same as WhatsApp posting several picked photos back-to-back), sharing
    // one caption + the screen's current privacy/expiry/close-friends
    // settings across all of them. Kept separate from post()/saveStatus()
    // above so the existing single-item flow (with its own txt/link/gif
    // handling and single finish()) is untouched.
    private java.util.List<Uri>    pendingBatchUris;
    private java.util.List<Boolean> pendingBatchIsVideo;
    private int    pendingBatchIndex;
    private String pendingBatchCaption;

    // BUG FIX (double upload): the layout-picker → edit → post chain reaches
    // postStatusBatch() from an ActivityResult callback. If Done/Send got
    // double-tapped upstream (StatusLayoutPickerActivity / MediaEditActivity —
    // now guarded there too) and the result still arrived twice, this used to
    // re-run the whole batch and upload the same edited photos a second time.
    // One NewStatusActivity screen only ever posts once, so a simple one-shot
    // guard is enough.
    private boolean batchUploadStarted = false;

    private void postStatusBatch(java.util.List<Uri> uris, java.util.List<Boolean> isVideoFlags, String caption) {
        if (uris == null || uris.isEmpty()) return;
        if (batchUploadStarted) return;
        batchUploadStarted  = true;
        pendingBatchUris    = uris;
        pendingBatchIsVideo = isVideoFlags;
        pendingBatchCaption = caption;
        pendingBatchIndex   = 0;
        postNextBatchItem();
    }

    private void postNextBatchItem() {
        if (pendingBatchUris == null || pendingBatchIndex >= pendingBatchUris.size()) {
            setPosting(false);
            if (pendingScheduledAt > 0) {
                String fmt = new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    .format(new Date(pendingScheduledAt));
                toast("Statuses scheduled for " + fmt);
                pendingScheduledAt = 0;
            } else {
                toast("Status posted!");
            }
            finish();
            return;
        }
        setPosting(true);
        setHint("Posting " + (pendingBatchIndex + 1) + " of " + pendingBatchUris.size() + "…");
        Uri uri          = pendingBatchUris.get(pendingBatchIndex);
        boolean isVideo  = pendingBatchIsVideo.get(pendingBatchIndex);
        String uid       = FirebaseUtils.getCurrentUid();
        String name      = FirebaseUtils.getCurrentName();
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String thumb = snap.child("thumbUrl").getValue(String.class);
                String full  = snap.child("photoUrl").getValue(String.class);
                String photo = (thumb != null && !thumb.isEmpty()) ? thumb : (full != null ? full : safePhoto());
                uploadBatchItem(uri, isVideo, pendingBatchCaption, uid, name, photo);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                uploadBatchItem(uri, isVideo, pendingBatchCaption, uid, name, safePhoto());
            }
        });
    }

    private void uploadBatchItem(Uri uri, boolean isVideo, String caption, String uid, String name, String photo) {
        if (isVideo) {
            VideoQualityPreferences.Quality quality = new VideoQualityPreferences(this).getGlobalQuality();
            VideoCompressor.compress(this, uri, quality, new VideoCompressor.Callback() {
                @Override public void onProgress(int pct) { runOnUiThread(() -> setHint("Compressing video… " + pct + "%")); }
                @Override public void onSuccess(VideoCompressor.Result r) {
                    runOnUiThread(() -> setHint("Uploading video…"));
                    uploadBatchAndSave(Uri.fromFile(r.videoFile), "video", caption, uid, name, photo, r.thumbFile);
                }
                @Override public void onError(Exception e) {
                    runOnUiThread(() -> setHint("Uploading video…"));
                    uploadBatchAndSave(uri, "video", caption, uid, name, photo, null);
                }
            });
        } else {
            ImageCompressor.compress(this, uri, STATUS_PHOTO_HD, new ImageCompressor.Callback() {
                @Override public void onSuccess(ImageCompressor.Result r) {
                    pendingMediaW = r.fullWidth; pendingMediaH = r.fullHeight;
                    runOnUiThread(() -> setHint("Uploading image…"));
                    uploadBatchAndSave(Uri.fromFile(r.fullFile), "image", caption, uid, name, photo, r.thumbFile);
                }
                @Override public void onError(Exception e) {
                    pendingMediaW = 0; pendingMediaH = 0;
                    runOnUiThread(() -> setHint("Uploading image…"));
                    uploadBatchAndSave(uri, "image", caption, uid, name, photo, null);
                }
            });
        }
    }

    private void uploadBatchAndSave(Uri uri, String type, String caption, String uid, String name,
                                     String photo, java.io.File thumbFile) {
        String rt = "video".equals(type) ? "video" : "image";
        pendingThumbHash = null;
        final int hashGen = ++thumbHashGen; // a late result for an already-saved item must not leak into the next one
        StatusThumbHashHelper.computeAsync(this, uri, "video".equals(type), thumbFile,
                h -> { if (hashGen == thumbHashGen) pendingThumbHash = h; });
        CloudinaryUploader.upload(this, uri, "callx/status", rt, new CloudinaryUploader.UploadCallback() {
            @Override public void onSuccess(CloudinaryUploader.Result r) {
                runOnUiThread(() -> {
                    String thumbUrl = "video".equals(type) ? r.thumbnailUrl
                            : CloudinaryUploader.deriveLimitThumbUrl(r.secureUrl, STATUS_PHOTO_THUMB_PX);
                    if (thumbFile != null) VideoCompressor.safeDelete(thumbFile);
                    saveBatchStatus(type, r.secureUrl, thumbUrl, caption, uid, name, photo);
                });
            }
            @Override public void onError(String err) {
                runOnUiThread(() -> {
                    toast((err != null ? err : "Upload failed") + " — skipping item " + (pendingBatchIndex + 1));
                    pendingBatchIndex++;
                    postNextBatchItem();
                });
            }
        });
    }

    private void saveBatchStatus(String type, String mediaUrl, String thumbUrl, String caption,
                                  String uid, String name, String photo) {
        long now = System.currentTimeMillis();
        DatabaseReference ref = FirebaseUtils.getStatusRef().child(uid).push();
        StatusItem item     = new StatusItem();
        item.id             = ref.getKey();
        item.ownerUid       = uid;
        item.ownerName      = name;
        item.ownerPhoto     = photo;
        item.type           = type;
        item.text           = null;
        item.caption        = (caption == null || caption.isEmpty()) ? null : caption;
        item.mediaUrl       = mediaUrl;
        item.thumbnailUrl   = thumbUrl;
        if ("image".equals(type) || "video".equals(type)) item.thumbHash = pendingThumbHash;
        pendingThumbHash = null; thumbHashGen++;
        if ("image".equals(type) && pendingMediaW > 0 && pendingMediaH > 0) {
            item.mediaWidth = pendingMediaW; item.mediaHeight = pendingMediaH;
        }
        pendingMediaW = 0; pendingMediaH = 0;
        item.bgColor        = String.format("#%08X", selectedBgColor);
        item.fontStyle      = selectedFontStyle;
        item.textColor      = String.format("#%08X", selectedTextColor);
        item.textAlign      = selectedTextAlign;
        item.privacy        = selectedPrivacy;
        item.privacyList    = privacyUids.isEmpty() ? null : new ArrayList<>(privacyUids);
        item.isCloseFriends = isCloseFriends;
        item.allowSharing   = allowSharing;
        item.stabilizerEnabled = stabilizerEnabled;
        item.ringColor      = selectedRingColor != null ? selectedRingColor : "";
        item.ringMode       = selectedRingMode  != null ? selectedRingMode  : "";
        item.expiryHours    = selectedExpiryHours;
        item.timestamp      = now;
        item.expiresAt      = StatusCustomExpiryHelper.computeExpiresAt(selectedExpiryHours);
        item.deleted        = false;
        // ── v26: Attach interactive sticker JSON (was missing — batch-posted
        // statuses never carried stickersJson, so the viewer's audio-name
        // ticker under the owner name never rendered for these items) ──────
        String stickersJson = buildStickersJson();
        if (!stickersJson.isEmpty()) {
            item.stickersJson = stickersJson;
        }
        // ── Scheduled post? Route to statusScheduled instead of going live now ──
        if (pendingScheduledAt > 0) {
            long scheduledAt = pendingScheduledAt;
            item.scheduledAt = scheduledAt;
            item.timestamp   = scheduledAt;
            item.expiresAt   = scheduledAt + (long) selectedExpiryHours * 3_600_000L;
            com.callx.app.repository.StatusRepository.getInstance(this)
                .scheduleStatus(item, scheduledAt, ok -> runOnUiThread(() -> {
                    pendingBatchIndex++;
                    if (!ok) toast("Failed to schedule item " + pendingBatchIndex);
                    postNextBatchItem();
                }));
            return;
        }
        ref.setValue(item.toMap())
            .addOnSuccessListener(u -> {
                StatusNotificationHelper.scheduleStatusExpiryReminder(this, item.id, item.expiresAt);
                warnUnresolvedMentions(item);
                pendingBatchIndex++;
                postNextBatchItem();
            })
            .addOnFailureListener(e -> {
                toast("Failed to post item " + (pendingBatchIndex + 1) + ": " + e.getMessage());
                pendingBatchIndex++;
                postNextBatchItem();
            });
    }

    // ── Scheduling ────────────────────────────────────────────────────────

    /**
     * Long-press on "Post" → pick a future date, then a time → stash it in
     * pendingScheduledAt and run the normal post() flow, which now detects
     * the pending schedule and routes the write to statusScheduled instead
     * of the live status tree (see saveStatus()/saveBatchStatus()).
     */
    private void showScheduleDialog() {
        Calendar now = Calendar.getInstance();
        Calendar picked = Calendar.getInstance();

        android.app.DatePickerDialog dateDialog = new android.app.DatePickerDialog(
            this,
            (view, year, month, dayOfMonth) -> {
                picked.set(Calendar.YEAR, year);
                picked.set(Calendar.MONTH, month);
                picked.set(Calendar.DAY_OF_MONTH, dayOfMonth);

                android.app.TimePickerDialog timeDialog = new android.app.TimePickerDialog(
                    this,
                    (timeView, hourOfDay, minute) -> {
                        picked.set(Calendar.HOUR_OF_DAY, hourOfDay);
                        picked.set(Calendar.MINUTE, minute);
                        picked.set(Calendar.SECOND, 0);
                        picked.set(Calendar.MILLISECOND, 0);

                        if (picked.getTimeInMillis() <= System.currentTimeMillis()) {
                            toast("Pick a time in the future");
                            return;
                        }
                        pendingScheduledAt = picked.getTimeInMillis();
                        String fmt = new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                            .format(new Date(pendingScheduledAt));
                        toast("Will be scheduled for " + fmt);
                        post();
                    },
                    now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false);
                timeDialog.show();
            },
            now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        dateDialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dateDialog.show();
    }

    // ── UI helpers ────────────────────────────────────────────────────────
    private void setPosting(boolean posting) {
        if (posting) stopMusicPreview(); // (6a) upload started — preview has done its job
        binding.btnPost.setEnabled(!posting);
        binding.btnPost.setText(posting ? "Posting…" : "Post");
        if (binding.uploadProgress != null)
            binding.uploadProgress.setVisibility(posting ? View.VISIBLE : View.GONE);
    }
    private void setHint(String hint) {
        if (binding.tvUploadHint != null) binding.tvUploadHint.setText(hint);
    }

    @Override
    public void onBackPressed() {
        if (wizardStep > 0) {
            showWizardStep(wizardStep - 1);
            return;
        }
        super.onBackPressed();
    }
    private String safePhoto() {
        try { com.google.firebase.auth.FirebaseUser u = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser(); if (u != null && u.getPhotoUrl() != null) return u.getPhotoUrl().toString(); return ""; } catch (Exception e) { return ""; }
    }
    private String safeUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onDestroy() {
        if (activeStepRingSpin != null) activeStepRingSpin.cancel();
        stopMusicPreview(); // (6a) release MediaPlayer + audio focus
        stopVideoPreview(); // v589: release the editor's VideoView player
        super.onDestroy();
    }
}