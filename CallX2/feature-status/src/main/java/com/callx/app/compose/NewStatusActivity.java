package com.callx.app.compose;
import com.callx.app.utils.AlertDialogStyler;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.*;
import android.view.View;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.*;
import com.bumptech.glide.Glide;
import com.callx.app.status.databinding.ActivityNewStatusBinding;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import androidx.annotation.NonNull;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;
/**
 * NewStatusActivity v26 — Fully comprehensive status creation + Interactive Stickers.
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
    /** JSON array of all added sticker configs (serialised for post metadata) */
    private final java.util.List<String> addedStickerJsons = new java.util.ArrayList<>();
    /** Small/Medium/Large size-control row shown above whichever sticker was last tapped */
    private LinearLayout stickerSizeBar;
    private com.callx.app.stickers.StatusStickerOverlayView selectedSticker;

    // ── Four-step status composer ──────────────────────────────────────────
    // The existing status controls stay intact; the wizard only changes their
    // containers and navigation so every existing upload/post path keeps using
    // the same bound views.
    private android.widget.ViewFlipper stepFlipper;
    private androidx.core.widget.NestedScrollView stepScrollContainer;
    private TextView tvStepTitle;
    private ProgressBar progressStep;
    private com.google.android.material.button.MaterialButton btnStepBack;
    private com.google.android.material.button.MaterialButton btnStepNext;
    private TextView[] stepDots;
    private TextView reviewSummary;
    private int currentStep = 0;
    private static final String[] STEP_TITLES = {
        "Step 1 of 4 · Content",
        "Step 2 of 4 · Style",
        "Step 3 of 4 · Privacy",
        "Step 4 of 4 · Share"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNewStatusBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setupStepWizard();
        setupToolbar();
        setupMediaPickers();
        setupCameraCapture();
        reelCameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handleReelCameraResult);
        statusMediaEditLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                    java.util.ArrayList<String> uriStrings = result.getData().getStringArrayListExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.RESULT_URIS);
                    if (uriStrings == null || uriStrings.isEmpty()) return;
                    String caption = result.getData().getStringExtra(
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
                    // edited photo as its own status.
                    postStatusBatch(uris, videoFlags, cap);
                });
        setupBgColorPicker();
        setupFontStylePicker();
        setupPrivacyButton();
        setupExpiryButton();
        setupCloseFriendsToggle();
        setupAllowSharingToggle();
        setupRingColorButton();
        setupTextAlignButtons();
        setupTextInput();
        setupStickerOverlayFrame();
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
        // GIF / Sticker button
        View btnGif = binding.getRoot().findViewWithTag("btn_gif");
        if (btnGif != null) btnGif.setOnClickListener(v -> showGifInputDialog());
        // ── NEW: Interactive Sticker picker button ────────────────────────
        View btnSticker = binding.getRoot().findViewWithTag("btn_add_sticker");
        if (btnSticker != null) btnSticker.setOnClickListener(v -> openStickerPicker());
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
        }
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

        String toastMsg = getIntent().getStringExtra(EXTRA_PREFILL_TOAST);
        if (toastMsg != null && !toastMsg.isEmpty()) {
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
        }
    }

    /** Open the sticker type picker sheet. */
    private void openStickerPicker() {
        StatusStickerPickerSheet.show(this, result -> {
            addedStickerJsons.add(result.json);
            addStickerOverlay(result.json);
            Toast.makeText(this,
                getStickerAddedLabel(result.type) + " added! Drag to reposition.",
                Toast.LENGTH_SHORT).show();
        });
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

    /**
     * Inflate the sticker view and add it to the overlay frame.
     * Falls back gracefully if there is no overlay frame in the layout.
     */
    private void addStickerOverlay(String stickerJson) {
        if (stickerOverlayFrame == null) return;

        StatusStickerOverlayView stickerView = StatusStickerOverlayView.fromJson(this, stickerJson);

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
        int gap = dp * 14;
        lp.topMargin = (stickerStackBottomPx < 0) ? (dp * 16) : (stickerStackBottomPx + gap);
        stickerOverlayFrame.addView(stickerView, lp);

        // Enable drag + pinch-resize + long-press-remove
        stickerView.attachDragToParent(stickerOverlayFrame);
        stickerView.setOnStickerTappedListener(this::showStickerSizeBar);
        stickerOverlayFrame.setOnClickListener(v -> hideStickerSizeBar());

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
        int dp = (int) getResources().getDisplayMetrics().density;

        if (stickerSizeBar == null) {
            stickerSizeBar = new LinearLayout(this);
            stickerSizeBar.setOrientation(LinearLayout.HORIZONTAL);
            stickerSizeBar.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp * 20);
            bg.setColor(0xDD1A1A1A);
            stickerSizeBar.setBackground(bg);
            stickerSizeBar.setPadding(dp * 6, dp * 6, dp * 6, dp * 6);

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
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp * 40, dp * 40);
                lp.leftMargin = i == 0 ? 0 : dp * 4;
                float scale = scales[i];
                btn.setOnClickListener(v -> {
                    if (selectedSticker != null) selectedSticker.animateToScale(scale);
                });
                stickerSizeBar.addView(btn, lp);
            }
            stickerOverlayFrame.addView(stickerSizeBar, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));
        }

        stickerSizeBar.setVisibility(View.VISIBLE);
        stickerSizeBar.bringToFront();
        // Position just above the tapped sticker (falls back near the top if it doesn't fit).
        stickerSizeBar.post(() -> {
            float top = sticker.getY() - stickerSizeBar.getHeight() - dp * 8;
            ((android.widget.FrameLayout.LayoutParams) stickerSizeBar.getLayoutParams()).topMargin =
                (int) Math.max(dp * 8, top);
            stickerSizeBar.requestLayout();
        });
    }

    private void hideStickerSizeBar() {
        selectedSticker = null;
        if (stickerSizeBar != null) stickerSizeBar.setVisibility(View.GONE);
    }

    // ── Status wizard UI ───────────────────────────────────────────────────

    /**
     * Converts the original long status form into four focused screens at
     * runtime. Views are moved, not recreated, so ActivityNewStatusBinding and
     * every existing listener continue to point at the same controls.
     */
    private void setupStepWizard() {
        stepScrollContainer = findViewById(com.callx.app.status.R.id.status_scroll);
        android.view.ViewGroup content =
                findViewById(com.callx.app.status.R.id.status_content);
        android.view.ViewGroup root = binding.getRoot();
        if (stepScrollContainer == null || content == null || !(root instanceof android.widget.LinearLayout)) {
            return;
        }

        final int dp = Math.max(1, (int) getResources().getDisplayMetrics().density);
        android.widget.LinearLayout rootLayout = (android.widget.LinearLayout) root;

        android.widget.LinearLayout header = createStepHeader(dp);
        rootLayout.addView(header, Math.min(1, rootLayout.getChildCount()));

        stepFlipper = new android.widget.ViewFlipper(this);
        stepFlipper.setAnimateFirstView(false);
        stepFlipper.setInAnimation(this, android.R.anim.fade_in);
        stepFlipper.setOutAnimation(this, android.R.anim.fade_out);
        stepFlipper.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.LinearLayout contentStep = createStepPane(dp);
        android.widget.LinearLayout styleStep = createStepPane(dp);
        android.widget.LinearLayout privacyStep = createStepPane(dp);
        android.widget.LinearLayout shareStep = createStepPane(dp);

        addStepIntro(contentStep, "CREATE", "Content",
                "Start with a thought, photo, video or sticker.");
        addStepIntro(styleStep, "PERSONALIZE", "Style",
                "Make your status feel like you.");
        addStepIntro(privacyStep, "CHOOSE YOUR AUDIENCE", "Privacy",
                "You are in control of who sees and shares it.");
        addStepIntro(shareStep, "ALMOST THERE", "Share",
                "Review your status, then post when it feels right.");

        // Step 1 — text, link, media and caption controls.
        moveTopLevelView(content, com.callx.app.status.R.id.til_text, contentStep);
        moveTopLevelView(content, com.callx.app.status.R.id.tv_char_count, contentStep);
        moveTopLevelView(content, com.callx.app.status.R.id.text_preview_card, contentStep);
        moveTag(content, "link_preview_card", contentStep);
        moveTopLevelView(content, com.callx.app.status.R.id.btn_pick_image, contentStep);
        moveTag(content, "btn_gif", contentStep);
        moveTag(content, "btn_add_sticker", contentStep);
        moveTopLevelView(content, com.callx.app.status.R.id.media_preview_frame, contentStep);
        moveTopLevelView(content, com.callx.app.status.R.id.caption_group, contentStep);

        // Step 2 — text appearance and avatar ring.
        moveTopLevelView(content, com.callx.app.status.R.id.bg_color_picker_row, styleStep);
        moveTopLevelView(content, com.callx.app.status.R.id.font_picker_scroll, styleStep);
        moveTopLevelView(content, com.callx.app.status.R.id.alignment_picker_row, styleStep);
        moveTopLevelView(content, com.callx.app.status.R.id.status_ring_row, styleStep);

        // Step 3 — expiry and visibility controls.
        moveTopLevelView(content, com.callx.app.status.R.id.status_expiry_row, privacyStep);
        moveTopLevelView(content, com.callx.app.status.R.id.status_privacy_row, privacyStep);
        moveTopLevelView(content, com.callx.app.status.R.id.status_allow_sharing_row, privacyStep);

        // Step 4 — review and the original post action.
        moveTopLevelView(content, com.callx.app.status.R.id.upload_progress, shareStep);
        moveTopLevelView(content, com.callx.app.status.R.id.tv_upload_hint, shareStep);
        android.view.View postButton =
                findViewById(com.callx.app.status.R.id.btn_post);
        if (postButton != null && postButton.getParent() == content) {
            content.removeView(postButton);
            shareStep.addView(postButton);
        }

        android.view.View reviewCard = createReviewCard(dp);
        shareStep.addView(reviewCard, 0);

        stepFlipper.addView(contentStep);
        stepFlipper.addView(styleStep);
        stepFlipper.addView(privacyStep);
        stepFlipper.addView(shareStep);

        // Remove the old empty host and replace it with the step flipper.
        stepScrollContainer.removeView(content);
        stepScrollContainer.setPadding(0, 0, 0, dp * 12);
        stepScrollContainer.addView(stepFlipper);

        android.widget.LinearLayout navigation = createStepNavigation(dp);
        rootLayout.addView(navigation);
        updateStepUi();
    }

    private android.widget.LinearLayout createStepHeader(int dp) {
        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.VERTICAL);
        header.setPadding(dp * 18, dp * 8, dp * 18, dp * 12);
        header.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        tvStepTitle = new TextView(this);
        tvStepTitle.setTextColor(android.graphics.Color.WHITE);
        tvStepTitle.setTextSize(13);
        tvStepTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvStepTitle.setLetterSpacing(.02f);
        header.addView(tvStepTitle, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp * 24));

        android.widget.LinearLayout tracker = new android.widget.LinearLayout(this);
        tracker.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        tracker.setGravity(android.view.Gravity.CENTER_VERTICAL);
        stepDots = new TextView[STEP_TITLES.length];
        for (int i = 0; i < STEP_TITLES.length; i++) {
            android.widget.LinearLayout item = new android.widget.LinearLayout(this);
            item.setOrientation(android.widget.LinearLayout.VERTICAL);
            item.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, 0, 0, 0);

            TextView dot = new TextView(this);
            dot.setText(String.valueOf(i + 1));
            dot.setTextColor(android.graphics.Color.WHITE);
            dot.setTextSize(11);
            dot.setGravity(android.view.Gravity.CENTER);
            dot.setTypeface(null, android.graphics.Typeface.BOLD);
            stepDots[i] = dot;
            item.addView(dot, new android.widget.LinearLayout.LayoutParams(dp * 28, dp * 28));

            TextView label = new TextView(this);
            label.setText(new String[]{"Content", "Style", "Privacy", "Share"}[i]);
            label.setTextColor(0xFF8E99A8);
            label.setTextSize(10);
            label.setGravity(android.view.Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams labelLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp * 20);
            labelLp.topMargin = dp * 2;
            item.addView(label, labelLp);

            android.widget.LinearLayout.LayoutParams itemLp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tracker.addView(item, itemLp);
        }
        header.addView(tracker, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        progressStep = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progressStep.setMax(STEP_TITLES.length);
        progressStep.setProgress(1);
        progressStep.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF4ADE80));
        android.widget.LinearLayout.LayoutParams progressLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp * 4);
        progressLp.topMargin = dp * 8;
        header.addView(progressStep, progressLp);
        return header;
    }

    private android.widget.LinearLayout createStepPane(int dp) {
        android.widget.LinearLayout pane = new android.widget.LinearLayout(this);
        pane.setOrientation(android.widget.LinearLayout.VERTICAL);
        pane.setPadding(dp * 16, dp * 8, dp * 16, dp * 16);
        return pane;
    }

    private void addStepIntro(android.widget.LinearLayout pane, String eyebrow,
                              String title, String description) {
        TextView overline = new TextView(this);
        overline.setText(eyebrow);
        overline.setTextColor(0xFF4ADE80);
        overline.setTextSize(10);
        overline.setTypeface(null, android.graphics.Typeface.BOLD);
        overline.setLetterSpacing(.12f);
        pane.addView(overline, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(android.graphics.Color.WHITE);
        heading.setTextSize(26);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        pane.addView(heading, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        TextView body = new TextView(this);
        body.setText(description);
        body.setTextColor(0xFF9BA6B5);
        body.setTextSize(13);
        android.widget.LinearLayout.LayoutParams bodyLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        bodyLp.bottomMargin = dp(10);
        pane.addView(body, bodyLp);
    }

    private android.widget.LinearLayout createStepNavigation(int dp) {
        android.widget.LinearLayout navigation = new android.widget.LinearLayout(this);
        navigation.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        navigation.setGravity(android.view.Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp * 16, dp * 8, dp * 16, dp * 10);
        android.graphics.drawable.GradientDrawable navBg = new android.graphics.drawable.GradientDrawable();
        navBg.setColor(0xFF11161E);
        navBg.setStroke(dp, 0xFF26303D);
        navigation.setBackground(navBg);
        navigation.setElevation(dp * 8);

        btnStepBack = new com.google.android.material.button.MaterialButton(this);
        btnStepBack.setText("Back");
        btnStepBack.setAllCaps(false);
        btnStepBack.setTextColor(0xFFE4EAF1);
        btnStepBack.setCornerRadius(dp * 12);
        btnStepBack.setStrokeWidth(dp);
        btnStepBack.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF384555));
        btnStepBack.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1A212B));
        btnStepBack.setOnClickListener(v -> goToStep(currentStep - 1));

        btnStepNext = new com.google.android.material.button.MaterialButton(this);
        btnStepNext.setText("Next  ›");
        btnStepNext.setAllCaps(false);
        btnStepNext.setTextColor(android.graphics.Color.WHITE);
        btnStepNext.setTypeface(null, android.graphics.Typeface.BOLD);
        btnStepNext.setCornerRadius(dp * 12);
        btnStepNext.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF36B968));
        btnStepNext.setOnClickListener(v -> goToStep(currentStep + 1));

        android.widget.LinearLayout.LayoutParams backLp =
                new android.widget.LinearLayout.LayoutParams(0, dp * 50, 1f);
        backLp.rightMargin = dp * 8;
        android.widget.LinearLayout.LayoutParams nextLp =
                new android.widget.LinearLayout.LayoutParams(0, dp * 50, 1f);
        navigation.addView(btnStepBack, backLp);
        navigation.addView(btnStepNext, nextLp);
        return navigation;
    }

    private android.view.View createReviewCard(int dp) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(dp * 18, dp * 16, dp * 18, dp * 16);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF104A37, 0xFF173A65});
        cardBg.setCornerRadius(dp * 18);
        card.setBackground(cardBg);

        TextView eyebrow = new TextView(this);
        eyebrow.setText("YOUR STATUS PREVIEW");
        eyebrow.setTextColor(0xFFB5F8CA);
        eyebrow.setTextSize(10);
        eyebrow.setTypeface(null, android.graphics.Typeface.BOLD);
        eyebrow.setLetterSpacing(.12f);
        card.addView(eyebrow);

        reviewSummary = new TextView(this);
        reviewSummary.setTextColor(android.graphics.Color.WHITE);
        reviewSummary.setTextSize(17);
        reviewSummary.setTypeface(null, android.graphics.Typeface.BOLD);
        reviewSummary.setMaxLines(2);
        reviewSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams summaryLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp * 50);
        summaryLp.topMargin = dp * 8;
        card.addView(reviewSummary, summaryLp);

        TextView meta = new TextView(this);
        meta.setText("Just now  ·  Ready to share");
        meta.setTextColor(0xFFD1E7DC);
        meta.setTextSize(12);
        card.addView(meta);
        return card;
    }

    private void moveTopLevelView(android.view.ViewGroup from, int id,
                                  android.view.ViewGroup to) {
        android.view.View view = from.findViewById(id);
        if (view != null && view.getParent() == from) {
            from.removeView(view);
            to.addView(view);
        }
    }

    private void moveTag(android.view.ViewGroup from, String tag,
                         android.view.ViewGroup to) {
        android.view.View view = from.findViewWithTag(tag);
        if (view != null && view.getParent() == from) {
            from.removeView(view);
            to.addView(view);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.Drawable stepCircle(int color) {
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(color);
        return circle;
    }

    private void goToStep(int step) {
        if (stepFlipper == null || step < 0 || step >= STEP_TITLES.length) return;
        currentStep = step;
        stepFlipper.setDisplayedChild(step);
        updateStepUi();
        if (stepScrollContainer != null) stepScrollContainer.scrollTo(0, 0);
    }

    private void updateStepUi() {
        if (tvStepTitle != null) tvStepTitle.setText(STEP_TITLES[currentStep]);
        if (progressStep != null) progressStep.setProgress(currentStep + 1);
        if (btnStepBack != null) {
            btnStepBack.setVisibility(currentStep == 0 ? View.INVISIBLE : View.VISIBLE);
        }
        if (btnStepNext != null) {
            btnStepNext.setVisibility(currentStep == STEP_TITLES.length - 1
                    ? View.GONE : View.VISIBLE);
        }
        if (stepDots != null) {
            for (int i = 0; i < stepDots.length; i++) {
                int color = i < currentStep ? 0xFF36B968
                        : (i == currentStep ? 0xFF2F80ED : 0xFF303A48);
                stepDots[i].setBackground(stepCircle(color));
            }
        }
        if (reviewSummary != null) {
            String text = binding.etText.getText() == null
                    ? "" : binding.etText.getText().toString().trim();
            if (!text.isEmpty()) {
                reviewSummary.setText(text);
            } else if (pickedImage != null) {
                reviewSummary.setText("Photo status ready to share");
            } else if (pickedVideo != null) {
                reviewSummary.setText("Video status ready to share");
            } else if (fetchedPreview != null) {
                reviewSummary.setText("Link status ready to share");
            } else {
                reviewSummary.setText("Your status is ready to share");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (stepFlipper != null && currentStep > 0) {
            goToStep(currentStep - 1);
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); saveDraft(); }
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
    private void showStatusAddSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(com.callx.app.chat.R.layout.bottom_sheet_attach, null);

        // Status has no poll/contact/location/document/payment/event
        // concepts, and its own AI-images entry point lives in the chip
        // row below — hide those chat-only chips from the icon grid so
        // the sheet stays focused on media, same as the old (unused)
        // openStatusAttachSheet() did.
        int[] chatOnlyOptionIds = {
                com.callx.app.chat.R.id.opt_document, com.callx.app.chat.R.id.opt_poll,
                com.callx.app.chat.R.id.opt_contact, com.callx.app.chat.R.id.opt_location,
                com.callx.app.chat.R.id.opt_payment, com.callx.app.chat.R.id.opt_event,
                com.callx.app.chat.R.id.opt_ai_images
        };
        for (int id : chatOnlyOptionIds) {
            View opt = v.findViewById(id);
            if (opt != null) opt.setVisibility(View.GONE);
        }
        View optGallery = v.findViewById(com.callx.app.chat.R.id.opt_gallery);
        if (optGallery != null) optGallery.setOnClickListener(x -> { sheet.dismiss(); imagePicker.launch("image/*"); });

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
                // supportsViewOnce=false — Status doesn't need this toggle
                // (already ephemeral/24h), so hide it entirely instead of
                // leaving a dead control sitting in the sheet.
                false,
                new com.callx.app.conversation.controllers.AttachSheetRecentMediaBinder.Callbacks() {
                    @Override public void onCameraTapped() {
                        sheet.dismiss();
                        openReelCamera();
                    }
                    @Override public void onMoreAppsRequested() {
                        sheet.dismiss();
                        imagePicker.launch("image/*");
                    }
                    @Override public void onSeeMoreRequested() {
                        sheet.dismiss();
                        imagePicker.launch("image/*");
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
    private void setupMediaPickers() {
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

    /** Handles the result bubbled back from ReelCameraActivity → ReelEditorActivity. */
    private void handleReelCameraResult(androidx.activity.result.ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
        Intent data = result.getData();

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
        showSinglePickedPreview(videoUri, true, caption);
        attachMusicStickerIfAny(data);
    }

    /**
     * If a trending-audio sound was picked in the camera, attach it as a
     * Music sticker on the status preview — same sticker/JSON format used
     * by StatusStickerPickerSheet's own "🎵 Music" option.
     */
    private void attachMusicStickerIfAny(Intent data) {
        String soundTitle = data.getStringExtra("selected_sound_title");
        if (soundTitle == null || soundTitle.isEmpty()) return;
        String soundId  = data.getStringExtra("selected_sound_id");
        String soundUrl = data.getStringExtra("selected_sound_url");
        try {
            org.json.JSONObject musicJson = new org.json.JSONObject();
            musicJson.put("type",     "music");
            musicJson.put("song",     soundTitle);
            musicJson.put("artist",   "");
            musicJson.put("soundId",  soundId  != null ? soundId  : "");
            musicJson.put("soundUrl", soundUrl != null ? soundUrl : "");
            String json = musicJson.toString();
            addedStickerJsons.add(json);
            addStickerOverlay(json);
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
        int gravity;
        switch (align) {
            case "left":  gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL; break;
            case "right": gravity = android.view.Gravity.END   | android.view.Gravity.CENTER_VERTICAL; break;
            default:      gravity = android.view.Gravity.CENTER;
        }
        binding.tvTextPreview.setGravity(gravity);
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
        if (pickedImage != null || pickedVideo != null) return;
        String text = binding.etText.getText().toString().trim();
        if (text.isEmpty()) { binding.textPreviewCard.setVisibility(View.GONE); return; }
        binding.textPreviewCard.setVisibility(View.VISIBLE);
        binding.textPreviewCard.setCardBackgroundColor(selectedBgColor);
        binding.tvTextPreview.setText(StatusMentionHelper.highlight(text));
        binding.tvTextPreview.setTextColor(selectedTextColor);
        applyFontStyle(binding.tvTextPreview, selectedFontStyle);
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
    }
    private void showImagePreview(Uri uri) {
        binding.ivPreview.setVisibility(View.VISIBLE);
        binding.ivVideoHint.setVisibility(View.GONE);
        binding.btnDiscardMedia.setVisibility(View.VISIBLE);
        // Also show the overlay frame (so stickers are visible over the image)
        if (stickerOverlayFrame != null) stickerOverlayFrame.setVisibility(View.VISIBLE);
        // v238 BUG FIX: .centerCrop() here forcibly cropped the bitmap itself
        // regardless of the ImageView's own scaleType — switched to
        // .fitCenter() so the full photo/layout collage is always visible,
        // matching the taller fitCenter box in the layout.
        Glide.with(this).load(uri).fitCenter().into(binding.ivPreview);
        binding.captionGroup.setVisibility(View.VISIBLE);
        hideBgColorPicker();
    }
    private void showVideoPreview(Uri uri) {
        binding.ivPreview.setVisibility(View.VISIBLE);
        binding.ivVideoHint.setVisibility(View.VISIBLE);
        binding.btnDiscardMedia.setVisibility(View.VISIBLE);
        if (stickerOverlayFrame != null) stickerOverlayFrame.setVisibility(View.VISIBLE);
        Glide.with(this).load(uri).fitCenter().into(binding.ivPreview);
        binding.captionGroup.setVisibility(View.VISIBLE);
        hideBgColorPicker();
    }
    private void discardMedia() {
        pickedImage = null; pickedVideo = null; cameraImageUri = null;
        binding.ivPreview.setVisibility(View.GONE);
        binding.ivVideoHint.setVisibility(View.GONE);
        binding.btnDiscardMedia.setVisibility(View.GONE);
        binding.captionGroup.setVisibility(View.GONE);
        showBgColorPicker();
    }

    /** Returns a JSON array string of all added sticker configs, or "" if none. */
    private String buildStickersJson() {
        if (stickerOverlayFrame == null) return "";
        java.util.List<String> jsons = new java.util.ArrayList<>();
        for (int i = 0; i < stickerOverlayFrame.getChildCount(); i++) {
            View child = stickerOverlayFrame.getChildAt(i);
            if (child instanceof com.callx.app.stickers.StatusStickerOverlayView) {
                jsons.add(((com.callx.app.stickers.StatusStickerOverlayView) child).toJsonWithScale());
            }
        }
        if (jsons.isEmpty()) return "";
        return "[" + android.text.TextUtils.join(",", jsons) + "]";
    }
    // ── Post ──────────────────────────────────────────────────────────────
    private void post() {
        String txt     = binding.etText.getText().toString().trim();
        String caption = binding.etCaption != null ? binding.etCaption.getText().toString().trim() : "";
        boolean isGif  = "gif".equals(binding.btnPickImage.getTag());
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
            compressAndUploadImage(pickedImage, caption, txt, uid, name, photo);
        } else if (pickedVideo != null) {
            compressAndUploadVideo(pickedVideo, caption, txt, uid, name, photo);
        } else {
            saveStatus("text", null, null, txt, caption, uid, name, photo);
        }
    }
    private void compressAndUploadImage(Uri uri, String caption, String txt, String uid, String name, String photo) {
        runOnUiThread(() -> setHint("Compressing image…"));
        ImageCompressor.compress(this, uri, new ImageCompressor.Callback() {
            @Override public void onSuccess(ImageCompressor.Result r) {
                runOnUiThread(() -> setHint("Uploading image…"));
                uploadAndSave(Uri.fromFile(r.fullFile), "image", caption, txt, uid, name, photo, r.thumbFile);
            }
            @Override public void onError(Exception e) {
                runOnUiThread(() -> setHint("Uploading image…"));
                uploadAndSave(uri, "image", caption, txt, uid, name, photo, null);
            }
        });
    }
    private void compressAndUploadVideo(Uri uri, String caption, String txt, String uid, String name, String photo) {
        runOnUiThread(() -> setHint("Compressing video… 0%"));
        VideoQualityPreferences.Quality quality = new VideoQualityPreferences(this).getGlobalQuality();
        VideoCompressor.compress(this, uri, quality, new VideoCompressor.Callback() {
            @Override public void onProgress(int pct) { runOnUiThread(() -> setHint("Compressing video… " + pct + "%")); }
            @Override public void onSuccess(VideoCompressor.Result r) {
                runOnUiThread(() -> setHint("Uploading video…"));
                uploadAndSave(Uri.fromFile(r.videoFile), "video", caption, txt, uid, name, photo, r.thumbFile);
            }
            @Override public void onError(Exception e) {
                runOnUiThread(() -> setHint("Uploading video…"));
                uploadAndSave(uri, "video", caption, txt, uid, name, photo, null);
            }
        });
    }
    private void uploadAndSave(Uri uri, String type, String caption, String txt,
                                String uid, String name, String photo, java.io.File thumbFile) {
        String rt = "video".equals(type) ? "video" : "image";
        CloudinaryUploader.upload(this, uri, "callx/status", rt, new CloudinaryUploader.UploadCallback() {
            @Override public void onSuccess(CloudinaryUploader.Result r) {
                runOnUiThread(() -> {
                    setPosting(false);
                    String thumbUrl = "video".equals(type) ? r.thumbnailUrl : null;
                    // FIX: Clean up compressed files
                    if (thumbFile != null) VideoCompressor.safeDelete(thumbFile);
                    saveStatus(type, r.secureUrl, thumbUrl, txt, caption, uid, name, photo);
                    // FIX v25: If thumb was locally generated and Cloudinary didn't return one, upload separately
                    if ("video".equals(type) && thumbUrl == null && thumbFile != null && thumbFile.exists()) {
                        uploadThumbAndPatch(thumbFile, uid, r.secureUrl);
                    }
                });
            }
            @Override public void onError(String err) {
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
        item.bgColor          = String.format("#%08X", selectedBgColor);
        item.fontStyle        = selectedFontStyle;
        item.textColor        = String.format("#%08X", selectedTextColor);
        item.textAlign        = selectedTextAlign;
        item.privacy          = selectedPrivacy;
        item.privacyList      = privacyUids.isEmpty() ? null : new ArrayList<>(privacyUids);
        item.isCloseFriends   = isCloseFriends;
        item.allowSharing     = allowSharing;
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
        // Mentions
        if (txt != null && !txt.isEmpty()) {
            StatusMentionHelper.MentionResult mentions = StatusMentionHelper.extract(txt);
            if (!mentions.mentionedNames.isEmpty()) {
                // Store mention names; UID resolution requires backend lookup
                item.mentionNames = new HashMap<>();
                for (String n : mentions.mentionedNames) item.mentionNames.put(n, "@" + n);
            }
        }
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
            ImageCompressor.compress(this, uri, new ImageCompressor.Callback() {
                @Override public void onSuccess(ImageCompressor.Result r) {
                    runOnUiThread(() -> setHint("Uploading image…"));
                    uploadBatchAndSave(Uri.fromFile(r.fullFile), "image", caption, uid, name, photo, r.thumbFile);
                }
                @Override public void onError(Exception e) {
                    runOnUiThread(() -> setHint("Uploading image…"));
                    uploadBatchAndSave(uri, "image", caption, uid, name, photo, null);
                }
            });
        }
    }

    private void uploadBatchAndSave(Uri uri, String type, String caption, String uid, String name,
                                     String photo, java.io.File thumbFile) {
        String rt = "video".equals(type) ? "video" : "image";
        CloudinaryUploader.upload(this, uri, "callx/status", rt, new CloudinaryUploader.UploadCallback() {
            @Override public void onSuccess(CloudinaryUploader.Result r) {
                runOnUiThread(() -> {
                    String thumbUrl = "video".equals(type) ? r.thumbnailUrl : null;
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
        item.bgColor        = String.format("#%08X", selectedBgColor);
        item.fontStyle      = selectedFontStyle;
        item.textColor      = String.format("#%08X", selectedTextColor);
        item.textAlign      = selectedTextAlign;
        item.privacy        = selectedPrivacy;
        item.privacyList    = privacyUids.isEmpty() ? null : new ArrayList<>(privacyUids);
        item.isCloseFriends = isCloseFriends;
        item.allowSharing   = allowSharing;
        item.ringColor      = selectedRingColor != null ? selectedRingColor : "";
        item.ringMode       = selectedRingMode  != null ? selectedRingMode  : "";
        item.expiryHours    = selectedExpiryHours;
        item.timestamp      = now;
        item.expiresAt      = StatusCustomExpiryHelper.computeExpiresAt(selectedExpiryHours);
        item.deleted        = false;
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
        binding.btnPost.setEnabled(!posting);
        binding.btnPost.setText(posting ? "Posting…" : "Post");
        if (binding.uploadProgress != null)
            binding.uploadProgress.setVisibility(posting ? View.VISIBLE : View.GONE);
    }
    private void setHint(String hint) {
        if (binding.tvUploadHint != null) binding.tvUploadHint.setText(hint);
    }
    private String safePhoto() {
        try { com.google.firebase.auth.FirebaseUser u = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser(); if (u != null && u.getPhotoUrl() != null) return u.getPhotoUrl().toString(); return ""; } catch (Exception e) { return ""; }
    }
    private String safeUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}