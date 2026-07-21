package com.callx.app.conversation.controllers;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia;
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.db.AppDatabase;
import com.callx.app.models.Message;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.ImageCompressor;
import com.callx.app.utils.VideoCompressor;
import com.callx.app.utils.VideoQualityPreferences;
import com.callx.app.utils.VideoUploader;
import com.callx.app.utils.VoiceRecorder;
import com.callx.app.conversation.MultiMediaPreviewDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles media pickers (image/video/audio/file/camera/wallpaper),
 * media upload, GIF sending, and voice recording.
 */
public class ChatMediaController {

    public static final int REQ_AUDIO  = 200;
    public static final int REQ_CAMERA = 300;

    private final AppCompatActivity activity;
    private final ChatActivityDelegate delegate;

    private ActivityResultLauncher<String>  imagePicker;
    private ActivityResultLauncher<String>  stickerPicker;
    private ActivityResultLauncher<Intent>  gifPickerLauncher;
    private ActivityResultLauncher<String>  videoPicker;
    private ActivityResultLauncher<String>  audioPicker;
    private ActivityResultLauncher<String>  filePicker;
    private ActivityResultLauncher<Intent>  moreAppsChooser;
    private ActivityResultLauncher<Uri>     cameraCapturer;
    private ActivityResultLauncher<String>  wallpaperPicker;
    private ActivityResultLauncher<PickVisualMediaRequest> multiMediaPicker;
    private ActivityResultLauncher<Intent>  mediaEditLauncher;

    // Practical cap on a single multi-select grab (PickMultipleVisualMedia itself
    // has no max on API 34+ photo picker; OEM galleries below that vary).
    private static final int MAX_MULTI_PICK = 30;

    private Uri cameraOutputUri;

    // Recent-media strip/grid: one MediaStore query per sheet-open, shared by
    // both RecyclerViews (grid just gets a longer slice of the same list).
    private static final int RECENT_MEDIA_LIMIT = 60;
    private final ExecutorService mediaQueryExecutor = Executors.newSingleThreadExecutor();

    private final VoiceRecorder recorder = new VoiceRecorder();

    // ── Voice recording gesture state (WhatsApp-style press/hold) ──────────
    public interface RecordingStateListener {
        void onRecordingStateChanged(boolean recording);
    }
    private RecordingStateListener recordingListener;

    /** Fired from the SAME 100ms tick that already drives our own
     *  waveformRecording bar — zero extra polling cost, just forwards the
     *  amplitude sample that was computed anyway. See RecordingPreviewController. */
    public interface AmplitudeListener {
        void onAmplitudeSample(float level0to1);
    }
    private AmplitudeListener amplitudeListener;

    public void setAmplitudeListener(AmplitudeListener listener) {
        this.amplitudeListener = listener;
    }

    private static final long  AMPLITUDE_POLL_MS  = 100L;
    /** Below this hold duration, a release is treated as an accidental tap
     *  (finger never really settled into "recording" mode) — same as
     *  WhatsApp: it discards the clip and hints to hold instead of sending
     *  a near-zero-length voice note. */
    private static final long  MIN_RECORD_DURATION_MS = 1000L;
    private static final float CANCEL_THRESHOLD_DP = 110f;
    private static final float LOCK_THRESHOLD_DP   = 90f;
    private static final float MAX_CANCEL_DRAG_DP  = 140f;
    private static final float MAX_LOCK_DRAG_DP    = 96f;
    private static final float AXIS_DEADZONE_DP    = 12f;

    private final Handler recordHandler = new Handler(Looper.getMainLooper());
    private Runnable   recordTickRunnable;
    private ObjectAnimator dotBlinkAnim;

    /** Gesture lifecycle for the mic button. IDLE = finger not down /
     *  nothing recording. DRAGGING = finger down, recording, still free to
     *  slide left (cancel) or up (lock). LOCKED = hands-free, finger has
     *  been released; Delete/Send buttons drive it from here. */
    private enum RecordState { IDLE, DRAGGING, LOCKED }
    private RecordState recordState = RecordState.IDLE;

    /** Once the drag clears a small deadzone it commits to ONE axis
     *  (horizontal = cancel, vertical = lock) for the rest of the gesture,
     *  same as WhatsApp — stops a slightly-diagonal swipe from fighting
     *  between both animations at once. Null = not yet decided. */
    private enum DragAxis { HORIZONTAL, VERTICAL }
    private DragAxis dragAxis;

    private float micDownX, micDownY;
    private float cancelThresholdPx, lockThresholdPx, maxCancelDragPx, maxLockDragPx, axisDeadzonePx;

    public ChatMediaController(AppCompatActivity activity, ChatActivityDelegate delegate) {
        this.activity = activity;
        this.delegate = delegate;
    }

    // ── Register launchers (call from Activity.onCreate BEFORE super) ──────

    public void registerPickers() {
        imagePicker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image", "image", null); });

        // Sticker — picks a single image straight from the gallery (no dedicated
        // pack UI yet) and sends it tagged "sticker" instead of "image", so it
        // renders via MessageBubbleCanvasView.bindSticker() (no caption, no
        // "GIF" badge).
        stickerPicker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) sendStickerMessage(uri); });

        // GIF — in-app Tenor search picker (ChatGifPickerActivity). Unlike
        // sendGifMessage() (fed by the keyboard's inline GIF share, which
        // hands over a content:// URI that must be uploaded to Cloudinary),
        // the picker returns an already-public Tenor CDN URL, so it's sent
        // directly with no upload step.
        gifPickerLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK
                            || result.getData() == null) return;
                    String url = result.getData().getStringExtra("gif_url");
                    if (url != null && !url.isEmpty()) sendTenorGif(url);
                });

        videoPicker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "video", "video", null); });

        audioPicker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "audio", "raw", null); });

        filePicker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    uploadAndSend(uri, "file", "raw", FileUtils.fileName(activity, uri));
                });

        // "More apps" row in the Recents ▾ dropdown — deliberately NOT reusing
        // filePicker's GetContent() contract. GetContent() just fires plain
        // ACTION_GET_CONTENT and lets Android resolve it however it normally
        // would — on devices where the user (or OEM) has set a default handler
        // for that action (commonly the system Files app), Android skips the
        // disambiguation dialog entirely and opens that default straight away
        // (the Files-app browse UI, NOT the app-picker list the reference
        // screenshot shows). Wrapping the same intent in Intent.createChooser()
        // forces that picker dialog every single time, regardless of any
        // previously-set default, which is what "More apps" is supposed to be.
        moreAppsChooser = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    uploadAndSend(uri, "file", "raw", FileUtils.fileName(activity, uri));
                });

        wallpaperPicker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    try {
                        activity.getContentResolver().takePersistableUriPermission(
                                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {}
                    delegate.refreshWallpaper();
                });

        cameraCapturer = activity.registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraOutputUri != null)
                        uploadAndSend(cameraOutputUri, "image", "image", null);
                });

        // Result of the full-screen editor opened from the attach sheet's
        // "Edit" action (see AttachSheetRecentMediaBinder#onMediaEdit below)
        // — RESULT_OK hands back the final edited/baked URIs + caption +
        // HD flag, which feed straight into the same sequential-upload
        // pipeline the Recents grid's own Send button uses.
        mediaEditLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                    java.util.ArrayList<String> uriStrings =
                            result.getData().getStringArrayListExtra(MediaEditActivity.RESULT_URIS);
                    if (uriStrings == null || uriStrings.isEmpty()) return;
                    List<Uri> uris = new ArrayList<>();
                    for (String s : uriStrings) uris.add(Uri.parse(s));
                    String caption = result.getData().getStringExtra(MediaEditActivity.RESULT_CAPTION);
                    boolean isHD = result.getData().getBooleanExtra(MediaEditActivity.RESULT_HD, false);
                    pendingMultiSendViewOnce = false;
                    uploadSequentially(uris, null, caption == null || caption.isEmpty() ? null : caption, 0, isHD);
                });

        multiMediaPicker = activity.registerForActivityResult(
                new PickMultipleVisualMedia(MAX_MULTI_PICK),
                uris -> {
                    if (uris == null || uris.isEmpty()) return;
                    // Persist read access — photo picker grants a short-lived URI
                    // permission, but our sequential upload may outlive it.
                    for (Uri u : uris) {
                        try {
                            activity.getContentResolver().takePersistableUriPermission(
                                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException ignored) {
                            // Photo Picker (system) URIs don't always support
                            // persistable grants — fine, they're valid for this session.
                        }
                    }
                    if (uris.size() == 1) {
                        Uri only = uris.get(0);
                        String m = activity.getContentResolver().getType(only);
                        boolean vid = m != null && m.startsWith("video");
                        // Both image and video go through uploadAndSend, which now
                        // uses the local-first bubble pipeline for both types.
                        uploadAndSend(only, vid ? "video" : "image", vid ? "video" : "image", null);
                    } else {
                        // Multiple items: each gets its own local bubble instantly —
                        // Pick→bubble→progress ring→real URL, same as single send.
                        MultiMediaPreviewDialog.show(activity, uris,
                            (selectedUris, perItemCaptions, caption) ->
                                sendEachWithLocalBubble(selectedUris, perItemCaptions, caption, false));
                    }
                });
    }

    // ── Launch wallpaper picker (called via delegate) ─────────────────────

    public void launchWallpaperPicker() {
        wallpaperPicker.launch("image/*");
    }

    // ── Attach sheet ──────────────────────────────────────────────────────

    public void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        View v = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_attach, null);

        setupRecentMedia(sheet, v);

        // Gallery — now opens the combined image+video picker (was image-only)
        // so this one chip covers what used to be Gallery + Video + Multi.
        v.findViewById(R.id.opt_gallery)
                .setOnClickListener(x -> {
                    sheet.dismiss();
                    multiMediaPicker.launch(new PickVisualMediaRequest.Builder()
                            .setMediaType(PickVisualMedia.ImageAndVideo.INSTANCE)
                            .build());
                });

        View optDocument = v.findViewById(R.id.opt_document);
        if (optDocument != null) {
            optDocument.setOnClickListener(x -> { sheet.dismiss(); filePicker.launch("*/*"); });
        }
        View optPoll = v.findViewById(R.id.opt_poll);
        if (optPoll != null) {
            optPoll.setOnClickListener(x -> { sheet.dismiss(); delegate.launchPollCreator(); });
        }
        View optContact = v.findViewById(R.id.opt_contact);
        if (optContact != null) {
            optContact.setOnClickListener(x -> {
                sheet.dismiss();
                delegate.launchContactSharePicker();
            });
        }
        View optLocation = v.findViewById(R.id.opt_location);
        if (optLocation != null) {
            optLocation.setOnClickListener(x -> {
                sheet.dismiss();
                delegate.launchLocationSharePicker();
            });
        }
        // Camera is now the first tile of the Recents grid (see setupRecentMedia /
        // AttachSheetRecentMediaBinder / RecentMediaGridAdapter) rather than a
        // separate opt_camera row.
        // Payment / Event / AI images — new chips, backend flow not wired up yet.
        // Kept as safe no-crash placeholders until those features ship.
        View optPayment = v.findViewById(R.id.opt_payment);
        if (optPayment != null) {
            optPayment.setOnClickListener(x -> {
                sheet.dismiss();
                android.widget.Toast.makeText(activity, "Payments coming soon", android.widget.Toast.LENGTH_SHORT).show();
            });
        }
        View optEvent = v.findViewById(R.id.opt_event);
        if (optEvent != null) {
            optEvent.setOnClickListener(x -> {
                sheet.dismiss();
                android.widget.Toast.makeText(activity, "Events coming soon", android.widget.Toast.LENGTH_SHORT).show();
            });
        }
        View optAiImages = v.findViewById(R.id.opt_ai_images);
        if (optAiImages != null) {
            optAiImages.setOnClickListener(x -> {
                sheet.dismiss();
                android.widget.Toast.makeText(activity, "AI images coming soon", android.widget.Toast.LENGTH_SHORT).show();
            });
        }
        sheet.setContentView(v);
        sheet.show();
    }

    /** Wires the bottom camera+gallery strip and expandable Recents grid — see AttachSheetRecentMediaBinder. */
    private void setupRecentMedia(BottomSheetDialog sheet, View sheetRoot) {
        AttachSheetRecentMediaBinder.bind(activity, sheet, sheetRoot, mediaQueryExecutor,
                new AttachSheetRecentMediaBinder.Callbacks() {
                    @Override public void onCameraTapped() {
                        sheet.dismiss();
                        launchCamera();
                    }
                    @Override public void onMoreAppsRequested() {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("*/*");
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        moreAppsChooser.launch(Intent.createChooser(intent, "Open with"));
                    }
                    @Override public void onSeeMoreRequested() {
                        multiMediaPicker.launch(new PickVisualMediaRequest.Builder()
                                .setMediaType(PickVisualMedia.ImageAndVideo.INSTANCE)
                                .build());
                    }
                    @Override public void onMediaSend(List<RecentMediaLoader.Item> items, String caption, boolean isHD, boolean isViewOnce) {
                        if (items.isEmpty()) return;
                        List<Uri> uris = new ArrayList<>();
                        for (RecentMediaLoader.Item item : items) uris.add(item.uri);
                        // Local-first bubble flow: each item gets its own bubble instantly
                        // from the local file, then compress+upload runs in the background
                        // with a per-bubble progress ring — same Pick→bubble→%→real URL
                        // experience as single-image send. isHD threads through to
                        // ImageCompressor for HD-tier caps.
                        // View-once still routes through the grouped pipeline because
                        // ChatViewOnceController's wipe logic is built around multi_media.
                        if (isViewOnce) {
                            pendingMultiSendViewOnce = true;
                            uploadSequentially(uris, null,
                                    caption == null || caption.isEmpty() ? null : caption, 0, isHD);
                        } else {
                            sendEachWithLocalBubble(uris, null,
                                    caption == null || caption.isEmpty() ? null : caption, isHD);
                        }
                    }
                    @Override public void onMediaEdit(List<RecentMediaLoader.Item> items, String caption, boolean isHD) {
                        launchMediaEditor(items, caption, isHD);
                    }
                });
    }

    /** Builds the MediaEditActivity intent from the attach sheet's current selection and launches it. */
    private void launchMediaEditor(List<RecentMediaLoader.Item> items, String caption, boolean isHD) {
        if (items.isEmpty()) return;
        java.util.ArrayList<String> uriStrings = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> videoFlags = new java.util.ArrayList<>();
        for (RecentMediaLoader.Item item : items) {
            uriStrings.add(item.uri.toString());
            videoFlags.add(item.isVideo ? 1 : 0);
        }
        Intent intent = new Intent(activity, MediaEditActivity.class);
        intent.putStringArrayListExtra(MediaEditActivity.EXTRA_URIS, uriStrings);
        intent.putIntegerArrayListExtra(MediaEditActivity.EXTRA_IS_VIDEO, videoFlags);
        intent.putExtra(MediaEditActivity.EXTRA_CAPTION, caption);
        intent.putExtra(MediaEditActivity.EXTRA_HD, isHD);
        mediaEditLauncher.launch(intent);
    }

    // ── Multi media: sequential upload, grouped into ONE message ─────────────

    /**
     * Uploads uris one by one (so progress can be shown), but instead of
     * sending each as its own bubble, collects them into a single
     * "multi_media" message with a mediaItems list. MessageAdapter +
     * MediaGroupLayoutHelper then render this as a WhatsApp-style grid
     * (1 → full width, 2 → side by side, 3 → top+2, 4 → 2×2, 5+ → 2×2 +"+N").
     */
    // Set while a multi-media batch is in flight; checked between items so a
    // mid-batch tap on the progress bar stops launching further uploads
    // (already-collected items still get sent as a smaller group).
    private volatile boolean multiUploadCancelled = false;

    /**
     * Set right before uploadSequentially() is kicked off from the attach
     * sheet's onMediaSend (view-once toggle in the selection bar). Read once
     * by finishMultiUpload() when the outgoing Message is built, then reset —
     * a per-batch one-shot flag, same lifecycle as the input bar's own
     * isViewOnceModeOn in ChatActivity.
     */
    private volatile boolean pendingMultiSendViewOnce = false;

    /** All uploads attempted (or batch was cancelled mid-way) — push ONE grouped message if we have anything. */
    private void finishMultiUpload(List<java.util.Map<String, Object>> collected, String caption) {
        delegate.getBinding().uploadProgress.setVisibility(View.GONE);
        delegate.getBinding().uploadProgress.setOnClickListener(null);
        boolean wasCancelled = multiUploadCancelled;
        multiUploadCancelled = false;
        // One-shot: read + clear so a later, unrelated multi-send never
        // accidentally inherits a stale ON from this batch.
        boolean sendAsViewOnce = pendingMultiSendViewOnce;
        pendingMultiSendViewOnce = false;

        if (!collected.isEmpty()) {
            // Attach-sheet single-item send (Recents grid, exactly 1 tile picked)
            // — build this as a normal single-media message instead of a
            // 1-item "multi_media" group. MediaGroupLayoutHelper always sizes a
            // 1-item group to a fixed 240x200dp box, which is why single photos
            // sent from the sheet looked wrong (square-ish/cropped) compared to
            // the system Gallery picker's single-select path (uploadAndSend),
            // which sizes the bubble from the real width/height via the canvas
            // renderer. View-once stays on the multi_media path since
            // ChatViewOnceController's wipe logic is already built around it.
            if (collected.size() == 1 && !sendAsViewOnce) {
                java.util.Map<String, Object> item = collected.get(0);
                Message m = delegate.buildOutgoing();
                Object mTypeObj = item.get("mediaType");
                m.type = (mTypeObj instanceof String) ? (String) mTypeObj : "image";

                Object url = item.get("url");
                if (url instanceof String) {
                    m.mediaUrl = (String) url;
                    if ("image".equals(m.type)) m.imageUrl = (String) url;
                }
                Object thumbUrl = item.get("thumbUrl");
                if (thumbUrl instanceof String) m.thumbnailUrl = (String) thumbUrl;

                Object w = item.get("width");
                Object h = item.get("height");
                if (w instanceof Integer) m.mediaWidth  = (Integer) w;
                if (h instanceof Integer) m.mediaHeight = (Integer) h;

                Object durationMs = item.get("durationMs");
                if (durationMs instanceof Integer) m.duration = ((Integer) durationMs).longValue();
                else if (durationMs instanceof Long) m.duration = (Long) durationMs;

                Object fileSize = item.get("fileSize");
                if (fileSize instanceof Long) m.fileSize = (Long) fileSize;

                Object fName = item.get("fileName");
                if (fName instanceof String) m.fileName = (String) fName;

                if (caption != null && !caption.isEmpty()) {
                    m.caption = caption;
                    m.text    = caption;
                } else {
                    Object itemCaption = item.get("caption");
                    if (itemCaption instanceof String) {
                        m.caption = (String) itemCaption;
                        m.text    = (String) itemCaption;
                    }
                }

                delegate.pushMessage(m, mediaPreview(m.type, m.fileName));
                delegate.clearReply();
                if (wasCancelled) {
                    Toast.makeText(activity, "Cancelled — 1 bhej di gayi", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            Message m       = delegate.buildOutgoing();
            m.type          = "multi_media";
            m.mediaItems    = collected;
            if (caption != null && !caption.isEmpty()) {
                m.caption = caption;
                m.text    = caption;
            }
            // Keep first item's url as a fallback preview field
            Object firstUrl = collected.get(0).get("url");
            if (firstUrl instanceof String) m.mediaUrl = (String) firstUrl;

            String preview;
            if (sendAsViewOnce) {
                // Tag before pushMessage — ChatViewOnceController's state
                // machine (opened/expired/revoked) already treats multi_media
                // exactly like single-media view-once (see FIELD_MEDIA_ITEMS
                // wipe in hardDeleteFromFirebase), so nothing else needs to
                // change on the receiving/rendering side.
                com.callx.app.conversation.controllers.ChatViewOnceController.tagMessageAsViewOnce(m);
                preview = "🔒 View Once";
            } else {
                preview = collected.size() == 1
                        ? "📷 Photo"
                        : "📷 " + collected.size() + " photos";
            }
            delegate.pushMessage(m, preview);
            delegate.clearReply();
            if (wasCancelled) {
                Toast.makeText(activity, "Cancelled — " + collected.size() + " bhej di gayi", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(activity, wasCancelled ? "Cancelled" : "Sab files fail ho gayi", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Sends a single already-edited media URI as a brand-new message —
     * used by MediaViewerActivity's "Edit" action (view a sent/received
     * photo in the chat, tweak it in MediaEditActivity, and resend), via
     * {@link com.callx.app.conversation.GalleryEditBridge}. Reuses the
     * exact same sequential-upload pipeline the attach sheet's own Edit
     * flow (see mediaEditLauncher above) already sends through.
     */
    public void sendEditedMedia(Uri uri, String caption, boolean isHD) {
        if (uri == null) return;
        List<Uri> uris = new ArrayList<>();
        uris.add(uri);
        uploadSequentially(uris, null, caption == null || caption.isEmpty() ? null : caption, 0, isHD);
    }

    private void uploadSequentially(List<Uri> uris, List<String> perItemCaptions, String caption, int index) {
        uploadSequentially(uris, perItemCaptions, caption, index, false);
    }

    /** @param isHD true = compress images at the WhatsApp-style HD cap (bigger, sharper) instead of Standard. */
    private void uploadSequentially(List<Uri> uris, List<String> perItemCaptions, String caption, int index, boolean isHD) {
        multiUploadCancelled = false;
        uploadSequentially(uris, perItemCaptions, caption, index, new ArrayList<>(), isHD);
    }

    /** Call to stop an in-progress multi-media batch (e.g. tap on the upload progress bar). */
    public void cancelMultiUpload() {
        if (multiUploadCancelled) return;
        multiUploadCancelled = true;
        Toast.makeText(activity, "Cancelling… bachi hui files send nahi hongi", Toast.LENGTH_SHORT).show();
    }

    private void uploadSequentially(List<Uri> uris, List<String> perItemCaptions, String caption, int index,
                                    List<java.util.Map<String, Object>> collected) {
        uploadSequentially(uris, perItemCaptions, caption, index, collected, false);
    }

    private void uploadSequentially(List<Uri> uris, List<String> perItemCaptions, String caption, int index,
                                    List<java.util.Map<String, Object>> collected, boolean isHD) {
        final int total = uris.size();

        if (index >= total) {
            activity.runOnUiThread(() -> finishMultiUpload(collected, caption));
            return;
        }

        // Cancelled mid-batch (user tapped progress bar) — stop launching new
        // uploads. Whatever made it into `collected` so far still gets sent
        // as a (smaller) group below, instead of being thrown away.
        if (multiUploadCancelled) {
            activity.runOnUiThread(() -> finishMultiUpload(collected, caption));
            return;
        }

        final Uri uri      = uris.get(index);
        // Per-item caption set by the user in MultiMediaPreviewDialog — looked
        // up by the original index, so it stays correctly attached to this
        // item even if an earlier item in the batch failed to upload.
        final String itemCaption = (perItemCaptions != null && index < perItemCaptions.size())
                ? perItemCaptions.get(index) : null;
        String rawMime = resolveMimeType(activity, uri);
        final String mediaType = classifyMediaType(rawMime);
        final boolean isVideo  = "video".equals(mediaType);
        final boolean isAudio  = "audio".equals(mediaType);
        final boolean isFile   = "file".equals(mediaType);
        final String fileName  = (isFile || isAudio) ? FileUtils.fileName(activity, uri) : null;

        // Show "Sending X / N" toast + let user cancel by tapping the bar
        activity.runOnUiThread(() -> {
            delegate.getBinding().uploadProgress.setVisibility(View.VISIBLE);
            delegate.getBinding().uploadProgress.setOnClickListener(v -> cancelMultiUpload());
            Toast.makeText(activity,
                "📤 Bhej raha hai " + (index + 1) + " / " + total
                    + (index == 0 ? " — tap bar to cancel" : ""),
                Toast.LENGTH_SHORT).show();
        });

        // VIDEO items go through the same compress → dual-upload pipeline as
        // single-video sends (previously multi-select skipped compression
        // entirely and uploaded the raw file — huge size/data cost).
        if (isVideo) {
            VideoQualityPreferences.Quality qual = isBakedOverlayVideo(uri)
                    ? VideoQualityPreferences.Quality.ORIGINAL
                    : VideoQualityPreferences.Quality.STANDARD;
            VideoCompressor.compress(activity, uri, qual, new VideoCompressor.Callback() {
                @Override public void onProgress(int percent) { /* batch-level toast only */ }
                @Override public void onSuccess(VideoCompressor.Result vr) {
                    VideoUploader.upload(activity, vr, new VideoUploader.UploadCallback() {
                        @Override public void onProgress(int percent) { }
                        @Override public void onSuccess(String thumbUrl, String videoUrl,
                                                        int durationMs, int width, int height) {
                            java.util.Map<String, Object> item = new java.util.HashMap<>();
                            item.put("url", videoUrl);
                            item.put("mediaType", "video");
                            if (thumbUrl != null && !thumbUrl.isEmpty()) item.put("thumbUrl", thumbUrl);
                            item.put("duration", formatDuration(durationMs));
                            item.put("durationMs", durationMs);
                            // Real pixel dimensions — needed so a single-item batch
                            // (see finishMultiUpload) can render with the same
                            // aspect-ratio-correct bubble sizing as uploadAndSend's
                            // single-video path, instead of a fixed grid cell.
                            if (width > 0 && height > 0) {
                                item.put("width", width);
                                item.put("height", height);
                            }
                            if (itemCaption != null && !itemCaption.isEmpty()) item.put("caption", itemCaption);
                            collected.add(item);
                            uploadSequentially(uris, perItemCaptions, caption, index + 1, collected, isHD);
                        }
                        @Override public void onError(Exception e) {
                            activity.runOnUiThread(() -> Toast.makeText(activity,
                                "Video " + (index + 1) + " fail: " + (e != null ? e.getMessage() : "Unknown"),
                                Toast.LENGTH_SHORT).show());
                            uploadSequentially(uris, perItemCaptions, caption, index + 1, collected, isHD);
                        }
                    });
                }
                @Override public void onError(Exception e) {
                    // Compression failed — fall back to raw upload rather than
                    // dropping the item entirely.
                    android.util.Log.w("ChatMediaController", "Multi-video compress failed, raw upload", e);
                    rawUploadGroupItem(uri, "video", mediaType, null, itemCaption,
                            uris, perItemCaptions, caption, index, collected, isHD, 0, 0);
                }
            });
            return;
        }

        // IMAGE — runs through ImageCompressor first (Standard cap by
        // default, HD cap when the sheet's HD toggle was ON), same as the
        // single-image send path already did; the multi-select group path
        // previously skipped this and uploaded the raw file straight to
        // Cloudinary, which was both slower and far heavier on data than
        // WhatsApp's actual behavior.
        if ("image".equals(mediaType)) {
            ImageCompressor.compress(activity, uri, isHD, new ImageCompressor.Callback() {
                @Override public void onSuccess(ImageCompressor.Result r) {
                    // Pass the compressed image's real pixel dimensions through —
                    // needed so a single-item batch (see finishMultiUpload) can
                    // render with the same aspect-ratio-correct bubble sizing as
                    // uploadAndSend's single-image path, instead of a fixed
                    // 240x200dp grid cell.
                    rawUploadGroupItem(Uri.fromFile(r.fullFile), "image", mediaType, fileName, itemCaption,
                            uris, perItemCaptions, caption, index, collected, isHD, r.fullWidth, r.fullHeight);
                }
                @Override public void onError(Exception e) {
                    // Compression failed — fall back to raw upload rather than
                    // dropping the item entirely.
                    android.util.Log.w("ChatMediaController", "Multi-image compress failed, raw upload", e);
                    rawUploadGroupItem(uri, "image", mediaType, fileName, itemCaption,
                            uris, perItemCaptions, caption, index, collected, isHD, 0, 0);
                }
            });
            return;
        }

        // AUDIO / FILE — uploaded as 'raw' so they can sit inside the same
        // grouped message instead of being silently treated as images.
        String resType = (isAudio || isFile) ? "raw" : "image";
        rawUploadGroupItem(uri, resType, mediaType, fileName, itemCaption,
                uris, perItemCaptions, caption, index, collected, isHD, 0, 0);
    }

    /** image / audio / file: single-shot upload into the in-progress group.
     *  @param width/height real pixel dimensions when known (0/0 if not —
     *  audio/file items, or an image whose compression fell back to raw). */
    private void rawUploadGroupItem(Uri uri, String resType, String mediaType, String fileName,
                                    String itemCaption,
                                    List<Uri> uris, List<String> perItemCaptions, String caption,
                                    int index, List<java.util.Map<String, Object>> collected, boolean isHD,
                                    int width, int height) {
        String folder = "callx/" + mediaType;
        CloudinaryUploader.upload(activity, uri, folder, resType,
            new CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    java.util.Map<String, Object> item = new java.util.HashMap<>();
                    item.put("url", r.secureUrl);
                    item.put("mediaType", mediaType);
                    if (r.thumbnailUrl != null && !r.thumbnailUrl.isEmpty())
                        item.put("thumbUrl", r.thumbnailUrl);
                    if (r.durationMs != null) {
                        item.put("duration", formatDuration(r.durationMs));
                        item.put("durationMs", r.durationMs);
                    }
                    if (r.bytes != null) item.put("fileSize", r.bytes);
                    if (fileName != null && !fileName.isEmpty()) item.put("fileName", fileName);
                    if (itemCaption != null && !itemCaption.isEmpty())
                        item.put("caption", itemCaption);
                    if (width > 0 && height > 0) {
                        item.put("width", width);
                        item.put("height", height);
                    }
                    collected.add(item);

                    // Upload next item
                    uploadSequentially(uris, perItemCaptions, caption, index + 1, collected, isHD);
                }
                @Override public void onError(String err) {
                    activity.runOnUiThread(() ->
                        Toast.makeText(activity,
                            "File " + (index + 1) + " fail: " + (err != null ? err : "Unknown"),
                            Toast.LENGTH_SHORT).show());
                    // Continue with remaining files even if one fails
                    uploadSequentially(uris, perItemCaptions, caption, index + 1, collected, isHD);
                }
            });
    }

    /** image / video / audio / file classifier from a content:// mime type. */
    // PERF (ultra): a video edited with stickers/text/drawing is already
    // fully re-encoded once by VideoOverlayBaker (Media3 Transformer) before
    // it ever reaches this controller — see MediaEditActivity.processItemForSend().
    // Running it through VideoCompressor's normal STANDARD-quality pass here
    // would decode + encode the WHOLE video a second time for no quality
    // benefit (the baker's output is already a clean H.264/AAC mp4), roughly
    // doubling processing time and battery for every overlay-edited video.
    // Detect that case via the baker's known output folder and pass
    // Quality.ORIGINAL, which VideoCompressor already special-cases as a
    // pure passthrough (metadata read + thumbnail only, no transcode) —
    // see VideoCompressor.compressSync()'s ORIGINAL branch.
    private static boolean isBakedOverlayVideo(Uri uri) {
        String s = uri.toString();
        return s.contains("media_edit_video_out");
    }

    private static String classifyMediaType(String rawMime) {
        if (rawMime == null) return "file";
        if (rawMime.startsWith("video")) return "video";
        if (rawMime.startsWith("audio")) return "audio";
        if (rawMime.startsWith("image")) return "image";
        return "file";
    }

    // BUG FIX: ContentResolver.getType(uri) reliably resolves MIME for
    // content:// URIs (queries the owning provider), but for file:// URIs —
    // which is exactly what MediaEditActivity/VideoOverlayBaker hand back
    // for an edited video (Uri.fromFile(output)) — it frequently returns
    // null. When that happened, classifyMediaType(null) fell through to
    // "file", so an edited video was uploaded via the generic raw-file
    // path (CloudinaryUploader, resource_type=raw) instead of the video
    // pipeline (VideoCompressor + VideoUploader) — raw uploads hit a much
    // lower Cloudinary size/type ceiling, so the edited (re-encoded, often
    // larger) video came back "Upload failed (400)" while an untouched
    // video — still a content:// URI, MIME resolves fine — went through
    // normally. Falling back to the file extension when getType() is null
    // fixes this without touching the (working) content:// path at all.
    private static String resolveMimeType(android.content.Context ctx, Uri uri) {
        String mime = ctx.getContentResolver().getType(uri);
        if (mime != null) return mime;
        String path = uri.toString();
        int dot = path.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        // Strip any trailing query string / fragment that snuck into the extension.
        int cut = ext.indexOf('?');
        if (cut >= 0) ext = ext.substring(0, cut);
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(java.util.Locale.US, "%d:%02d", min, sec);
    }

    // ── Camera ────────────────────────────────────────────────────────────

    public void launchCamera() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME,
                "callx_" + System.currentTimeMillis() + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cameraOutputUri = activity.getContentResolver()
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (cameraOutputUri != null) cameraCapturer.launch(cameraOutputUri);
    }

    // ── GIF ───────────────────────────────────────────────────────────────

    public void sendGifMessage(Uri gifUri, androidx.core.view.inputmethod.InputContentInfoCompat contentInfo) {
        if (gifUri == null) {
            if (contentInfo != null) contentInfo.releasePermission();
            return;
        }
        if (!delegate.isOnline()) {
            if (contentInfo != null) contentInfo.releasePermission();
            Toast.makeText(activity, "No connection — GIF send nahi ho sakta", Toast.LENGTH_SHORT).show();
            return;
        }
        ActivityChatBinding binding = delegate.getBinding();
        binding.uploadProgress.setVisibility(View.VISIBLE);
        Toast.makeText(activity, "GIF bhej raha hai...", Toast.LENGTH_SHORT).show();

        CloudinaryUploader.upload(activity, gifUri, "callx/gif", "image",
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result r) {
                        if (contentInfo != null) contentInfo.releasePermission();
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m = delegate.buildOutgoing();
                        m.type     = "gif";
                        m.mediaUrl = r.secureUrl;
                        m.imageUrl = r.secureUrl;
                        delegate.pushMessage(m, "\uD83C\uDEDF\uFE0F GIF");
                        delegate.clearReply();
                    }
                    @Override public void onError(String err) {
                        if (contentInfo != null) contentInfo.releasePermission();
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(activity,
                                err != null ? err : "GIF upload failed", Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ── Sticker ───────────────────────────────────────────────────────────
    // Mirrors sendGifMessage() exactly — same Cloudinary upload path, just
    // tagged as "sticker" so MessagePagingAdapter/MessageBubbleCanvasView
    // render it via bindSticker() (no GIF badge, no download-gate size
    // label other than "Sticker").

    public void sendStickerMessage(Uri stickerUri) {
        if (stickerUri == null) return;
        if (!delegate.isOnline()) {
            Toast.makeText(activity, "No connection — Sticker send nahi ho sakta", Toast.LENGTH_SHORT).show();
            return;
        }
        ActivityChatBinding binding = delegate.getBinding();
        binding.uploadProgress.setVisibility(View.VISIBLE);

        CloudinaryUploader.upload(activity, stickerUri, "callx/sticker", "image",
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result r) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m = delegate.buildOutgoing();
                        m.type     = "sticker";
                        m.mediaUrl = r.secureUrl;
                        m.imageUrl = r.secureUrl;
                        delegate.pushMessage(m, "\uD83C\uDFF7\uFE0F Sticker");
                        delegate.clearReply();
                    }
                    @Override public void onError(String err) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(activity,
                                err != null ? err : "Sticker upload failed", Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ── GIF picker (Tenor) — direct send, no Cloudinary upload ──────────────
    // The picker only ever hands back an already-hosted Tenor URL, so this
    // just builds the message straight away — same "gif" type/preview text
    // as sendGifMessage(), just skipping the upload step entirely.

    public void sendTenorGif(String gifUrl) {
        if (gifUrl == null || gifUrl.isEmpty()) return;
        Message m = delegate.buildOutgoing();
        m.type     = "gif";
        m.mediaUrl = gifUrl;
        m.imageUrl = gifUrl;
        delegate.pushMessage(m, "\uD83C\uDEDF\uFE0F GIF");
        delegate.clearReply();
    }

    // ── Upload & send ─────────────────────────────────────────────────────

    public void uploadAndSend(Uri uri, String msgType, String resourceType, String fileName) {
        if (!delegate.isOnline()) {
            Toast.makeText(activity,
                    "No connection — media send karne ke liye internet chahiye",
                    Toast.LENGTH_LONG).show();
            return;
        }

        ActivityChatBinding binding = delegate.getBinding();

        // IMAGE: WhatsApp-style local-first bubble — insert the bubble from
        // the local file IMMEDIATELY (correct aspect ratio, right away, no
        // global uploadProgress bar), then compress/upload in the
        // background and finalize the SAME row in place. See
        // ChatMessageSender#insertLocalPendingMedia/finalizeMediaMessage
        // and MessagePagingAdapter's local-pending render branch.
        if ("image".equals(msgType)) {
            int[] bounds = decodeLocalImageBounds(uri);

            Message pending = delegate.buildOutgoing();
            pending.type          = "image";
            pending.mediaLocalPath = uri.toString();
            pending.mediaWidth    = bounds[0] > 0 ? bounds[0] : null;
            pending.mediaHeight   = bounds[1] > 0 ? bounds[1] : null;

            String id = delegate.insertLocalPendingMedia(pending);
            if (id == null) {
                Toast.makeText(activity, "Couldn't queue photo — try again", Toast.LENGTH_SHORT).show();
                return;
            }
            delegate.clearReply();
            startImageUpload(uri, pending);
            return;
        }

        // VIDEO: WhatsApp-style local-first bubble — same as image path above.
        // Insert bubble immediately from the local file so the sender sees it
        // right away, then compress+upload in background with per-bubble
        // progress ring, finalize the SAME row with the real URL on success.
        if ("video".equals(msgType)) {
            Message pending        = delegate.buildOutgoing();
            pending.type           = "video";
            pending.mediaLocalPath = uri.toString();

            String id = delegate.insertLocalPendingMedia(pending);
            if (id == null) {
                Toast.makeText(activity, "Couldn't queue video — try again", Toast.LENGTH_SHORT).show();
                return;
            }
            delegate.clearReply();
            startVideoUploadLocalFirst(uri, pending);
            return;
        }

        binding.uploadProgress.setVisibility(View.VISIBLE);

        // Audio / file: direct upload
        doUpload(uri, msgType, resourceType, fileName);
    }

    // ── WhatsApp-style local-first image upload pipeline ────────────────────
    //
    // Split out of the old inline uploadAndSend() callback pyramid so the
    // same compress → dual-upload (thumb + full) sequence can be triggered
    // both right after the initial pick (uploadAndSend) and again from
    // retryFailedMediaUpload() without duplicating the callback chain.

    /** Cheap, synchronous bounds-only decode — just reads the image header,
     *  never allocates the actual bitmap. Lets the local-pending bubble size
     *  itself correctly on its very first layout pass. Returns {0,0} if the
     *  file can't be read (bubble falls back to a default ratio, same as any
     *  message sent before mediaWidth/mediaHeight existed). */
    private int[] decodeLocalImageBounds(Uri uri) {
        try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(in, null, opts);
            return new int[]{Math.max(0, opts.outWidth), Math.max(0, opts.outHeight)};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    /** Compress → upload thumb → upload full. `pending` is the SAME Message
     *  object already inserted as a local-pending bubble (has its Firebase
     *  key assigned) — this only ever updates it in place, never builds a
     *  new one. */
    private void startImageUpload(Uri uri, Message pending) {
        ImageCompressor.compress(activity, uri, new ImageCompressor.Callback() {
            @Override public void onSuccess(ImageCompressor.Result result) {
                Uri fullUri  = Uri.fromFile(result.fullFile);
                Uri thumbUri = Uri.fromFile(result.thumbFile);
                // Compressed dimensions are the ones actually uploaded —
                // prefer them over the raw local decode from uploadAndSend.
                pending.mediaWidth  = result.fullWidth;
                pending.mediaHeight = result.fullHeight;

                CloudinaryUploader.upload(activity, thumbUri, "callx/thumb", "image",
                        new CloudinaryUploader.UploadCallback() {
                            @Override public void onProgress(int percent) {
                                reportUploadProgress(pending, percent / 5); // thumb = first 20%
                            }
                            @Override public void onSuccess(CloudinaryUploader.Result thumbResult) {
                                uploadFullImage(fullUri, thumbResult.secureUrl, result, pending);
                            }
                            @Override public void onError(String err) {
                                // Thumb upload failed — upload full image without a thumb
                                uploadFullImage(fullUri, null, result, pending);
                            }
                        });
            }
            @Override public void onError(Exception e) {
                android.util.Log.w("ChatMediaController", "Compression failed, uploading original", e);
                uploadFullImage(uri, null, null, pending);
            }
        });
    }

    private void uploadFullImage(Uri fullUri, String thumbUrl, ImageCompressor.Result compressResult, Message pending) {
        CloudinaryUploader.upload(activity, fullUri, "callx/image", "image",
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onProgress(int percent) {
                        reportUploadProgress(pending, 20 + percent * 4 / 5); // full = remaining 80%
                    }
                    @Override public void onSuccess(CloudinaryUploader.Result fullResult) {
                        if (compressResult != null) {
                            compressResult.thumbFile.delete();
                            compressResult.fullFile.delete();
                        }
                        pending.mediaUrl     = fullResult.secureUrl;
                        pending.imageUrl     = fullResult.secureUrl;
                        pending.thumbnailUrl = thumbUrl;
                        pending.fileSize     = fullResult.bytes;
                        finishImageUploadSuccess(pending);
                    }
                    @Override public void onError(String err) {
                        if (compressResult != null) {
                            compressResult.thumbFile.delete();
                            compressResult.fullFile.delete();
                        }
                        finishImageUploadFailure(pending, err);
                    }
                });
    }

    private void reportUploadProgress(Message pending, int percent) {
        if (delegate.getPagingAdapter() == null) return;
        String id = pending.messageId != null ? pending.messageId : pending.id;
        delegate.getPagingAdapter().onMediaUploadProgress(id, Math.max(0, Math.min(100, percent)));
    }

    private void finishImageUploadSuccess(Message pending) {
        String id = pending.messageId != null ? pending.messageId : pending.id;
        if (delegate.getPagingAdapter() != null) delegate.getPagingAdapter().onMediaUploadFinished(id);
        delegate.finalizeMediaMessage(pending, "\uD83D\uDCF7 Photo");
    }

    private void finishImageUploadFailure(Message pending, String err) {
        String id = pending.messageId != null ? pending.messageId : pending.id;
        if (delegate.getPagingAdapter() != null) delegate.getPagingAdapter().onMediaUploadFinished(id);
        delegate.markMediaFailed(id);
        Toast.makeText(activity, err != null ? err : "Upload failed", Toast.LENGTH_LONG).show();
    }

    // ── WhatsApp-style local-first VIDEO upload pipeline ─────────────────────
    //
    // Mirror of startImageUpload / uploadFullImage for video. Compresses the
    // video first (STANDARD quality unless it is an overlay-baked clip), then
    // runs VideoUploader dual-upload (thumb + video). Progress is reported
    // directly on the already-visible local bubble — same spinner-ring-%
    // experience as the image local-first path.

    private void startVideoUploadLocalFirst(Uri uri, Message pending) {
        VideoQualityPreferences.Quality qual = isBakedOverlayVideo(uri)
                ? VideoQualityPreferences.Quality.ORIGINAL
                : VideoQualityPreferences.Quality.STANDARD;
        VideoCompressor.compress(activity, uri, qual, new VideoCompressor.Callback() {
            @Override public void onProgress(int percent) {
                // Compression = first half of the progress arc (0–50 %)
                reportUploadProgress(pending, percent / 2);
            }
            @Override public void onSuccess(VideoCompressor.Result vr) {
                VideoUploader.upload(activity, vr, new VideoUploader.UploadCallback() {
                    @Override public void onProgress(int percent) {
                        // Upload = second half (50–100 %)
                        reportUploadProgress(pending, 50 + percent / 2);
                    }
                    @Override public void onSuccess(String thumbUrl, String videoUrl,
                                                    int durationMs, int width, int height) {
                        pending.mediaUrl     = videoUrl;
                        pending.thumbnailUrl = thumbUrl;
                        pending.duration     = (long) durationMs;
                        if (width  > 0) pending.mediaWidth  = width;
                        if (height > 0) pending.mediaHeight = height;
                        finishVideoUploadSuccess(pending);
                    }
                    @Override public void onError(Exception e) {
                        finishVideoUploadFailure(pending,
                                e != null ? e.getMessage() : "Video upload failed");
                    }
                });
            }
            @Override public void onError(Exception e) {
                android.util.Log.w("ChatMediaController",
                        "Video compress failed for local-first bubble — raw fallback", e);
                // Compression failed: upload the raw file so the local bubble
                // resolves rather than getting stuck on failed.
                CloudinaryUploader.upload(activity, uri, "callx/video", "video",
                        new CloudinaryUploader.UploadCallback() {
                            @Override public void onProgress(int percent) {
                                reportUploadProgress(pending, percent);
                            }
                            @Override public void onSuccess(CloudinaryUploader.Result r) {
                                pending.mediaUrl = r.secureUrl;
                                finishVideoUploadSuccess(pending);
                            }
                            @Override public void onError(String err) {
                                finishVideoUploadFailure(pending, err);
                            }
                        });
            }
        });
    }

    private void finishVideoUploadSuccess(Message pending) {
        String id = pending.messageId != null ? pending.messageId : pending.id;
        if (delegate.getPagingAdapter() != null) delegate.getPagingAdapter().onMediaUploadFinished(id);
        delegate.finalizeMediaMessage(pending, "\uD83C\uDFAC Video");
    }

    private void finishVideoUploadFailure(Message pending, String err) {
        String id = pending.messageId != null ? pending.messageId : pending.id;
        if (delegate.getPagingAdapter() != null) delegate.getPagingAdapter().onMediaUploadFinished(id);
        delegate.markMediaFailed(id);
        activity.runOnUiThread(() ->
                Toast.makeText(activity, err != null ? err : "Video upload failed",
                        Toast.LENGTH_LONG).show());
    }

    // ── Local-first multi-media send ──────────────────────────────────────────
    //
    // Each URI gets its OWN local bubble inserted immediately (all thumbnails
    // appear right away), then compress+upload runs independently for each item
    // with a per-bubble progress ring — the same WhatsApp-style
    // Pick → bubble → spinner-% → real URL flow that single-image already had,
    // now applied to every item in a multi-select batch.
    //
    // Items upload independently (not sequentially) so a large video does not
    // block the image uploads queued after it.

    private void sendEachWithLocalBubble(List<Uri> uris, List<String> perItemCaptions,
                                         String caption, boolean isHD) {
        if (uris == null || uris.isEmpty()) return;
        if (!delegate.isOnline()) {
            Toast.makeText(activity,
                    "No connection — media send karne ke liye internet chahiye",
                    Toast.LENGTH_LONG).show();
            return;
        }

        for (int i = 0; i < uris.size(); i++) {
            final Uri uri = uris.get(i);
            // Per-item caption (set via MultiMediaPreviewDialog) falls back to the
            // shared group caption when the item has none of its own.
            final String rawPerItem = (perItemCaptions != null && i < perItemCaptions.size())
                    ? perItemCaptions.get(i) : null;
            final String itemCaption = (rawPerItem != null && !rawPerItem.isEmpty())
                    ? rawPerItem : caption;

            String rawMime  = resolveMimeType(activity, uri);
            String mimeType = classifyMediaType(rawMime);

            if ("video".equals(mimeType)) {
                // Video: local bubble first, then compress+upload with per-bubble ring.
                Message pending        = delegate.buildOutgoing();
                pending.type           = "video";
                pending.mediaLocalPath = uri.toString();
                if (itemCaption != null && !itemCaption.isEmpty()) {
                    pending.caption = itemCaption;
                    pending.text    = itemCaption;
                }
                String vid_id = delegate.insertLocalPendingMedia(pending);
                if (vid_id != null) startVideoUploadLocalFirst(uri, pending);

            } else {
                // Image: local bubble with decoded dimensions, then upload.
                int[] bounds = decodeLocalImageBounds(uri);
                Message pending        = delegate.buildOutgoing();
                pending.type           = "image";
                pending.mediaLocalPath = uri.toString();
                pending.mediaWidth     = bounds[0] > 0 ? bounds[0] : null;
                pending.mediaHeight    = bounds[1] > 0 ? bounds[1] : null;
                if (itemCaption != null && !itemCaption.isEmpty()) {
                    pending.caption = itemCaption;
                    pending.text    = itemCaption;
                }
                String img_id = delegate.insertLocalPendingMedia(pending);
                if (img_id != null) startImageUpload(uri, pending);
            }
        }
        delegate.clearReply();
    }

    /** Tap-to-retry entry point — called from ChatActivity's ActionListener#onRetry
     *  override when the failed message is a local-first image upload
     *  (mediaLocalPath set, mediaUrl still empty). Re-runs the SAME
     *  compress/upload pipeline against the same row instead of inserting a
     *  new bubble. */
    public void retryFailedMediaUpload(Message m) {
        if (m == null || m.id == null || m.mediaLocalPath == null || m.mediaLocalPath.isEmpty()) return;
        if (!delegate.isOnline()) {
            Toast.makeText(activity, "No connection — try again once you're online", Toast.LENGTH_SHORT).show();
            return;
        }
        m.status = "uploading";
        Executors.newSingleThreadExecutor().execute(() ->
                AppDatabase.getInstance(activity).messageDao().updateStatus(m.id, "uploading"));
        // Flip the bubble back to the spinner immediately instead of waiting
        // for the first onProgress tick.
        if (delegate.getPagingAdapter() != null) {
            delegate.getPagingAdapter().onMediaUploadProgress(m.id, -1);
        }
        startImageUpload(Uri.parse(m.mediaLocalPath), m);
    }

    private void doUpload(Uri uri, String msgType, String resourceType, String fileName) {
        ActivityChatBinding binding = delegate.getBinding();
        long size = FileUtils.fileSize(activity, uri);

        long limitBytes;
        String limitLabel;
        switch (msgType) {
            case "image": limitBytes = 10L  * 1024 * 1024; limitLabel = "10 MB";  break;
            case "video": limitBytes = 100L * 1024 * 1024; limitLabel = "100 MB"; break;
            case "audio": limitBytes = 25L  * 1024 * 1024; limitLabel = "25 MB";  break;
            default:      limitBytes = 50L  * 1024 * 1024; limitLabel = "50 MB";  break;
        }
        if (size > limitBytes) {
            String typeName;
            switch (msgType) {
                case "image": typeName = "Image"; break;
                case "video": typeName = "Video"; break;
                case "audio": typeName = "Audio"; break;
                default:      typeName = "File";  break;
            }
            Toast.makeText(activity,
                    typeName + " too large — max " + limitLabel + " allowed",
                    Toast.LENGTH_LONG).show();
            binding.uploadProgress.setVisibility(View.GONE);
            return;
        }

        CloudinaryUploader.upload(activity, uri, "callx/" + msgType, resourceType,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result r) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m  = delegate.buildOutgoing();
                        m.type     = msgType;
                        m.mediaUrl = r.secureUrl;
                        m.imageUrl = "image".equals(msgType) ? r.secureUrl : null;
                        m.fileName = fileName;
                        m.fileSize = r.bytes != null ? r.bytes : size;
                        m.duration = r.durationMs;
                        delegate.pushMessage(m, mediaPreview(msgType, fileName));
                        delegate.clearReply();
                    }
                    @Override public void onError(String err) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(activity,
                                err != null ? err : "Upload failed", Toast.LENGTH_LONG).show();
                    }
                });
    }

    private static String mediaPreview(String type, String fileName) {
        switch (type) {
            case "image": return "\uD83D\uDCF7 Photo";
            case "video": return "\uD83C\uDFAC Video";
            case "audio": return "\uD83C\uDFA4 Voice message";
            case "file":  return "\uD83D\uDCCE " + (fileName != null ? fileName : "File");
            default:      return "Media";
        }
    }

    // ── Voice recording — press & hold, WhatsApp style ──────────────────────
    //
    // ACTION_DOWN on the mic starts recording immediately (permission
    // permitting). While the finger is down, the first ~12dp of movement is
    // a deadzone; past that the drag commits to ONE axis for the rest of
    // the gesture:
    //   • LEFT (horizontal) past CANCEL_THRESHOLD_DP discards the recording
    //     mid-drag, no need to release.
    //   • UP (vertical) past LOCK_THRESHOLD_DP locks it hands-free; the mic
    //     can be released and Delete/Send buttons take over.
    // Releasing with neither triggered sends the recording.

    public void setRecordingListener(RecordingStateListener listener) {
        this.recordingListener = listener;
    }

    /** Wires the press-hold gesture onto the mic button. Call once, after
     *  the activity's binding is inflated (registerPickers() time is fine). */
    public void attachMicGesture() {
        ActivityChatBinding binding = delegate.getBinding();
        float density = activity.getResources().getDisplayMetrics().density;
        cancelThresholdPx = CANCEL_THRESHOLD_DP * density;
        lockThresholdPx   = LOCK_THRESHOLD_DP   * density;
        maxCancelDragPx   = MAX_CANCEL_DRAG_DP  * density;
        maxLockDragPx     = MAX_LOCK_DRAG_DP    * density;
        axisDeadzonePx    = AXIS_DEADZONE_DP    * density;

        binding.btnMic.setOnTouchListener(this::onMicTouch);
        binding.btnRecordDelete.setOnClickListener(v -> finishCancel());
        binding.btnRecordSend.setOnClickListener(v -> finishAndSend());
    }

    /** Called after the user grants RECORD_AUDIO from the permission dialog
     *  — the original press gesture is gone by then, so start straight away
     *  as the closest equivalent (tap mic again for the full hold gesture). */
    public void onAudioPermissionGranted() {
        beginRecording();
    }

    private boolean onMicTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onMicDown(v, event);
            case MotionEvent.ACTION_MOVE:
                if (recordState != RecordState.DRAGGING) return true;
                onMicDrag(event.getRawX() - micDownX, event.getRawY() - micDownY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (recordState != RecordState.DRAGGING) return true;
                releaseDragParent(v);
                if (recorder.getDuration() < MIN_RECORD_DURATION_MS) {
                    finishTooShort();
                } else {
                    finishAndSend();
                }
                return true;
            default:
                return false;
        }
    }

    private boolean onMicDown(View v, MotionEvent event) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return true;
        }
        // Guard against a stray second DOWN arriving while a gesture from a
        // previous pointer is still winding down (e.g. quick re-tap).
        if (recordState != RecordState.IDLE) return true;

        micDownX = event.getRawX();
        micDownY = event.getRawY();
        dragAxis = null;

        // Stop any scrolling ancestor (or a nested gesture-detecting
        // container) from intercepting the drag partway through — without
        // this, a slightly-vertical finger movement can get stolen before
        // it ever reaches CANCEL/LOCK threshold.
        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);

        beginRecording();
        return true;
    }

    private void releaseDragParent(View v) {
        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void beginRecording() {
        ActivityChatBinding binding = delegate.getBinding();
        if (!recorder.start(activity)) {
            Toast.makeText(activity, "Couldn't start recording", Toast.LENGTH_SHORT).show();
            return;
        }
        recordState = RecordState.DRAGGING;
        dragAxis = null;
        delegate.setRecording(true);

        binding.llInputRow.setVisibility(View.GONE);
        binding.llRecordingBar.setAlpha(0f);
        binding.llRecordingBar.setVisibility(View.VISIBLE);
        binding.llRecordingBar.animate().alpha(1f).setDuration(120).start();

        binding.llSlideCancel.setVisibility(View.VISIBLE);
        binding.llSlideCancel.setTranslationX(0f);
        binding.llSlideCancel.setAlpha(1f);
        binding.btnRecordDelete.setVisibility(View.GONE);
        binding.btnRecordSend.setVisibility(View.GONE);
        binding.tvRecordTimer.setText("00:00");
        binding.waveformRecording.reset();
        startDotBlink(binding.ivRecordDot);

        binding.cvRecordLock.setTranslationY(0f);
        binding.cvRecordLock.setAlpha(0f);
        binding.cvRecordLock.setVisibility(View.VISIBLE);
        binding.cvRecordLock.animate().alpha(1f).setDuration(120).start();
        binding.ivRecordLockIcon.setImageResource(R.drawable.ic_record_lock_open);

        binding.btnMic.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150)
                .setInterpolator(new OvershootInterpolator()).start();
        binding.btnMic.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

        excludeDragZoneFromSystemGestures(binding);

        if (recordingListener != null) recordingListener.onRecordingStateChanged(true);

        recordTickRunnable = () -> {
            if (recordState == RecordState.IDLE) return;
            binding.tvRecordTimer.setText(formatTimer(recorder.getDuration()));
            float level = normalizeAmplitude(recorder.getMaxAmplitudeSafe());
            binding.waveformRecording.pushLevel(level);
            // Same sample, forwarded to the partner-facing preview — no
            // second call to recorder.getMaxAmplitudeSafe(), no second timer.
            if (amplitudeListener != null) amplitudeListener.onAmplitudeSample(level);
            recordHandler.postDelayed(recordTickRunnable, AMPLITUDE_POLL_MS);
        };
        recordHandler.postDelayed(recordTickRunnable, AMPLITUDE_POLL_MS);
    }

    /** Handles one ACTION_MOVE sample. dx/dy are raw-screen deltas from the
     *  original ACTION_DOWN point (dx negative = left, dy negative = up). */
    private void onMicDrag(float dx, float dy) {
        if (dragAxis == null) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) < axisDeadzonePx) return; // still inside deadzone
            dragAxis = (-dy > Math.abs(dx)) ? DragAxis.VERTICAL : DragAxis.HORIZONTAL;
        }

        if (dragAxis == DragAxis.VERTICAL) {
            updateLockDrag(dy);
        } else {
            updateCancelDrag(dx);
        }
    }

    private void updateLockDrag(float dy) {
        ActivityChatBinding binding = delegate.getBinding();
        float clampedDy = clamp(dy, -maxLockDragPx, 0f);
        binding.cvRecordLock.setTranslationY(clampedDy);
        if (-dy >= lockThresholdPx) lockRecording();
    }

    private void updateCancelDrag(float dx) {
        ActivityChatBinding binding = delegate.getBinding();
        float clampedDx = clamp(dx, -maxCancelDragPx, 0f);
        binding.llSlideCancel.setTranslationX(clampedDx * 0.6f);
        float cancelProgress = Math.min(1f, -clampedDx / cancelThresholdPx);
        binding.llSlideCancel.setAlpha(1f - cancelProgress * 0.85f);
        float micShrink = 1.15f - cancelProgress * 0.15f;
        binding.btnMic.setScaleX(micShrink);
        binding.btnMic.setScaleY(micShrink);

        if (-dx >= cancelThresholdPx) {
            binding.btnMic.performHapticFeedback(HapticFeedbackConstants.REJECT);
            finishCancel();
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void lockRecording() {
        if (recordState == RecordState.LOCKED) return;
        recordState = RecordState.LOCKED;
        ActivityChatBinding binding = delegate.getBinding();
        binding.btnMic.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        binding.ivRecordLockIcon.setImageResource(R.drawable.ic_record_lock_closed);

        binding.cvRecordLock.animate()
                .alpha(0f)
                .translationY(-maxLockDragPx - (24f * activity.getResources().getDisplayMetrics().density))
                .setDuration(180)
                .withEndAction(() -> binding.cvRecordLock.setVisibility(View.GONE))
                .start();

        binding.btnMic.animate().scaleX(1f).scaleY(1f).setDuration(150)
                .setInterpolator(new OvershootInterpolator()).start();

        binding.llSlideCancel.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> binding.llSlideCancel.setVisibility(View.INVISIBLE)).start();

        binding.btnRecordDelete.setAlpha(0f);
        binding.btnRecordDelete.setVisibility(View.VISIBLE);
        binding.btnRecordDelete.animate().alpha(1f).setDuration(150).start();

        binding.btnRecordSend.setAlpha(0f);
        binding.btnRecordSend.setVisibility(View.VISIBLE);
        binding.btnRecordSend.animate().alpha(1f).setDuration(150).start();
    }

    private void finishAndSend() {
        ActivityChatBinding binding = delegate.getBinding();
        recordState = RecordState.IDLE;
        delegate.setRecording(false);
        stopDotBlink();
        recordHandler.removeCallbacksAndMessages(null);
        Uri uri = recorder.stop(activity);
        resetRecordingUi(binding);
        if (uri != null) uploadAndSend(uri, "audio", "raw", null);
        else Toast.makeText(activity, "Recording was empty", Toast.LENGTH_SHORT).show();
        if (recordingListener != null) recordingListener.onRecordingStateChanged(false);
    }

    /** Release happened before MIN_RECORD_DURATION_MS — treat like a
     *  cancel (discard the clip) but with a distinct hint, since the user
     *  likely tapped instead of holding rather than deliberately cancelling. */
    private void finishTooShort() {
        ActivityChatBinding binding = delegate.getBinding();
        recordState = RecordState.IDLE;
        delegate.setRecording(false);
        stopDotBlink();
        recordHandler.removeCallbacksAndMessages(null);
        recorder.cancel();
        resetRecordingUi(binding);
        binding.btnMic.performHapticFeedback(HapticFeedbackConstants.REJECT);
        Toast.makeText(activity, "Hold the mic to record a voice message", Toast.LENGTH_SHORT).show();
        if (recordingListener != null) recordingListener.onRecordingStateChanged(false);
    }

    private void finishCancel() {
        ActivityChatBinding binding = delegate.getBinding();
        recordState = RecordState.IDLE;
        delegate.setRecording(false);
        stopDotBlink();
        recordHandler.removeCallbacksAndMessages(null);
        recorder.cancel();
        resetRecordingUi(binding);
        Toast.makeText(activity, "Recording cancelled", Toast.LENGTH_SHORT).show();
        if (recordingListener != null) recordingListener.onRecordingStateChanged(false);
    }

    private void resetRecordingUi(ActivityChatBinding binding) {
        dragAxis = null;

        binding.btnMic.animate().scaleX(1f).scaleY(1f).setDuration(150)
                .setInterpolator(new OvershootInterpolator()).start();

        binding.llRecordingBar.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            binding.llRecordingBar.setVisibility(View.GONE);
            binding.llRecordingBar.setAlpha(1f);
            binding.llInputRow.setVisibility(View.VISIBLE);
        }).start();

        binding.cvRecordLock.animate().cancel();
        binding.cvRecordLock.setVisibility(View.GONE);
        binding.cvRecordLock.setTranslationY(0f);
        binding.cvRecordLock.setAlpha(0f);

        binding.llSlideCancel.setVisibility(View.VISIBLE);
        binding.llSlideCancel.setTranslationX(0f);
        binding.llSlideCancel.setAlpha(1f);

        binding.btnRecordDelete.setVisibility(View.GONE);
        binding.btnRecordSend.setVisibility(View.GONE);

        binding.waveformRecording.reset();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            binding.getRoot().setSystemGestureExclusionRects(java.util.Collections.emptyList());
        }
    }

    /** Reserves the mic's drag path (up for lock, left for cancel) so
     *  Android's edge/back gesture-nav can't intercept the swipe out from
     *  under the app — the lock gesture in particular is an upward swipe
     *  that starts very close to the bottom edge, right where system
     *  gesture nav also listens. No-op before API 29 (no such API). */
    private void excludeDragZoneFromSystemGestures(ActivityChatBinding binding) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return;
        View root = binding.getRoot();
        View mic  = binding.btnMic;
        if (mic.getWidth() == 0 || root.getWidth() == 0) return;

        int[] micLoc  = new int[2];
        int[] rootLoc = new int[2];
        mic.getLocationInWindow(micLoc);
        root.getLocationInWindow(rootLoc);

        int pad = (int) (16f * activity.getResources().getDisplayMetrics().density);
        int left   = micLoc[0] - rootLoc[0] - (int) maxCancelDragPx - pad;
        int top    = micLoc[1] - rootLoc[1] - (int) maxLockDragPx - pad;
        int right  = micLoc[0] - rootLoc[0] + mic.getWidth() + pad;
        int bottom = micLoc[1] - rootLoc[1] + mic.getHeight() + pad;

        android.graphics.Rect rect = new android.graphics.Rect(
                Math.max(0, left), Math.max(0, top), right, bottom);
        root.setSystemGestureExclusionRects(java.util.Collections.singletonList(rect));
    }

    private void startDotBlink(View dot) {
        stopDotBlink();
        dotBlinkAnim = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f);
        dotBlinkAnim.setDuration(600);
        dotBlinkAnim.setRepeatMode(ObjectAnimator.REVERSE);
        dotBlinkAnim.setRepeatCount(ObjectAnimator.INFINITE);
        dotBlinkAnim.start();
    }

    private void stopDotBlink() {
        if (dotBlinkAnim != null) { dotBlinkAnim.cancel(); dotBlinkAnim = null; }
    }

    private static String formatTimer(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    /** MediaRecorder.getMaxAmplitude() is linear 0..32767 — a log curve
     *  reads much closer to how loudness actually looks on a waveform. */
    private static float normalizeAmplitude(int amp) {
        if (amp <= 0) return 0.06f;
        double norm = Math.log10(1 + amp) / Math.log10(32767);
        return (float) Math.max(0.06, Math.min(1.0, norm));
    }
}
