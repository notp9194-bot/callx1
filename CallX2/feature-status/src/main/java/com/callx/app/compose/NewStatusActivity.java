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
    // Link preview state
    private String detectedLinkUrl;
    private StatusLinkPreviewHelper.LinkPreview fetchedPreview;
    private ActivityResultLauncher<String>  imagePicker;
    private ActivityResultLauncher<String>  videoPicker;
    private ActivityResultLauncher<Uri>     cameraCapture;
    private ActivityResultLauncher<String>  cameraVideoCapture;
    // Result of the full-screen editor opened from the attach-sheet's "Edit"
    // action — see showStatusAddSheet()'s onMediaEdit callback.
    private ActivityResultLauncher<Intent>  statusMediaEditLauncher;
    // v216: Layout picker result launcher — receives selected URIs + layout style
    private ActivityResultLauncher<Intent>  layoutPickerLauncher;

    /** FrameLayout that holds the status preview + sticker overlays */
    private android.widget.FrameLayout stickerOverlayFrame;
    /** JSON array of all added sticker configs (serialised for post metadata) */
    private final java.util.List<String> addedStickerJsons = new java.util.ArrayList<>();
    /** Small/Medium/Large size-control row shown above whichever sticker was last tapped */
    private LinearLayout stickerSizeBar;
    private com.callx.app.stickers.StatusStickerOverlayView selectedSticker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNewStatusBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setupToolbar();
        setupMediaPickers();
        setupCameraCapture();
        statusMediaEditLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                    java.util.ArrayList<String> uriStrings = result.getData().getStringArrayListExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.RESULT_URIS);
                    if (uriStrings == null || uriStrings.isEmpty()) return;
                    java.util.List<Uri> uris = new java.util.ArrayList<>();
                    java.util.List<Boolean> videoFlags = new java.util.ArrayList<>();
                    for (String s : uriStrings) {
                        Uri u = Uri.parse(s);
                        uris.add(u);
                        String mime = getContentResolver().getType(u);
                        videoFlags.add(mime != null && mime.startsWith("video"));
                    }
                    String caption = result.getData().getStringExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.RESULT_CAPTION);
                    postStatusBatch(uris, videoFlags, caption == null || caption.isEmpty() ? null : caption);
                });
        setupBgColorPicker();
        setupFontStylePicker();
        setupPrivacyButton();
        setupExpiryButton();
        setupCloseFriendsToggle();
        setupTextAlignButtons();
        setupTextInput();
        setupStickerOverlayFrame();
        // v216: Layout picker launcher — receives URIs + layoutStyle from StatusLayoutPickerActivity,
        //       then forwards to MediaEditActivity for cropping/filters/text before posting.
        layoutPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                    java.util.ArrayList<String> uriStrings = result.getData().getStringArrayListExtra(
                            StatusLayoutPickerActivity.EXTRA_RESULT_URIS);
                    java.util.ArrayList<Integer> videoFlagInts = result.getData().getIntegerArrayListExtra(
                            StatusLayoutPickerActivity.EXTRA_RESULT_IS_VIDEO);
                    if (uriStrings == null || uriStrings.isEmpty()) return;
                    // Forward to MediaEditActivity for editing before posting
                    Intent editIntent = new Intent(this,
                            com.callx.app.conversation.controllers.MediaEditActivity.class);
                    editIntent.putStringArrayListExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_URIS, uriStrings);
                    editIntent.putIntegerArrayListExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_IS_VIDEO, videoFlagInts);
                    editIntent.putExtra(com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_CAPTION, "");
                    editIntent.putExtra(com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_HD, false);
                    statusMediaEditLauncher.launch(editIntent);
                });
        restoreDraft();
        // v216: "Upload" button now opens the WhatsApp-style "Add status" sheet
        //       (Text / Music / Layout / Voice / AI Images + Recents grid) instead
        //       of the old plain Camera/Gallery alert dialog.
        binding.btnPickImage.setOnClickListener(v -> showStatusAddSheet());
        binding.btnPost.setOnClickListener(v -> post());
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
        lp.topMargin = dp * (16 + addedStickerJsons.size() * 12);
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
    }

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
                        captureFromCamera();
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
                        java.util.List<Uri> uris = new java.util.ArrayList<>();
                        java.util.List<Boolean> videoFlags = new java.util.ArrayList<>();
                        for (com.callx.app.conversation.controllers.RecentMediaLoader.Item item : items) {
                            uris.add(item.uri);
                            videoFlags.add(item.isVideo);
                        }
                        postStatusBatch(uris, videoFlags, caption == null || caption.isEmpty() ? null : caption);
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
    private void showImagePreview(Uri uri) {
        binding.ivPreview.setVisibility(View.VISIBLE);
        binding.ivVideoHint.setVisibility(View.GONE);
        binding.btnDiscardMedia.setVisibility(View.VISIBLE);
        // Also show the overlay frame (so stickers are visible over the image)
        if (stickerOverlayFrame != null) stickerOverlayFrame.setVisibility(View.VISIBLE);
        Glide.with(this).load(uri).centerCrop().override(480, 853).into(binding.ivPreview);
        binding.captionGroup.setVisibility(View.VISIBLE);
        hideBgColorPicker();
    }
    private void showVideoPreview(Uri uri) {
        binding.ivPreview.setVisibility(View.VISIBLE);
        binding.ivVideoHint.setVisibility(View.VISIBLE);
        binding.btnDiscardMedia.setVisibility(View.VISIBLE);
        if (stickerOverlayFrame != null) stickerOverlayFrame.setVisibility(View.VISIBLE);
        Glide.with(this).load(uri).centerCrop().override(480, 853).into(binding.ivPreview);
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
        String uid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
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

    private void postStatusBatch(java.util.List<Uri> uris, java.util.List<Boolean> isVideoFlags, String caption) {
        if (uris == null || uris.isEmpty()) return;
        pendingBatchUris    = uris;
        pendingBatchIsVideo = isVideoFlags;
        pendingBatchCaption = caption;
        pendingBatchIndex   = 0;
        postNextBatchItem();
    }

    private void postNextBatchItem() {
        if (pendingBatchUris == null || pendingBatchIndex >= pendingBatchUris.size()) {
            setPosting(false);
            toast("Status posted!");
            finish();
            return;
        }
        setPosting(true);
        setHint("Posting " + (pendingBatchIndex + 1) + " of " + pendingBatchUris.size() + "…");
        Uri uri          = pendingBatchUris.get(pendingBatchIndex);
        boolean isVideo  = pendingBatchIsVideo.get(pendingBatchIndex);
        String uid       = FirebaseAuth.getInstance().getCurrentUser().getUid();
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
        item.expiryHours    = selectedExpiryHours;
        item.timestamp      = now;
        item.expiresAt      = StatusCustomExpiryHelper.computeExpiresAt(selectedExpiryHours);
        item.deleted        = false;
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