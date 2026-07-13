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
import com.callx.app.models.Message;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.ImageCompressor;
import com.callx.app.utils.VideoCompressor;
import com.callx.app.utils.VideoUploader;
import com.callx.app.utils.VoiceRecorder;
import com.callx.app.conversation.MultiMediaPreviewDialog;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import android.util.DisplayMetrics;
import android.view.ViewTreeObserver;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
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
    private ActivityResultLauncher<Uri>     cameraCapturer;
    private ActivityResultLauncher<String>  wallpaperPicker;
    private ActivityResultLauncher<PickVisualMediaRequest> multiMediaPicker;

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
                        uploadAndSend(only, vid ? "video" : "image", vid ? "video" : "image", null);
                    } else {
                        MultiMediaPreviewDialog.show(activity, uris,
                            (selectedUris, perItemCaptions, caption) ->
                                uploadSequentially(selectedUris, perItemCaptions, caption, 0));
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
        View optCamera = v.findViewById(R.id.opt_camera);
        if (optCamera != null) {
            optCamera.setOnClickListener(x -> { sheet.dismiss(); launchCamera(); });
        }
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

    /**
     * Wires the WhatsApp-style compact strip (camera tile + recent gallery
     * thumbnails, right under the drag handle) and the expandable "Recents"
     * grid underneath it.
     *
     * Collapsed peek height = the height of top_content (drag handle +
     * strip + icon grid + camera row) — measured once the sheet is laid
     * out, via a one-shot ViewTreeObserver listener. Dragging past that
     * reveals the grid, which is fed off the SAME background MediaStore
     * query as the strip (one query, two RecyclerViews) so opening the
     * sheet never does more than one disk hit.
     */
    private void setupRecentMedia(BottomSheetDialog sheet, View sheetRoot) {
        RecyclerView strip = sheetRoot.findViewById(R.id.recent_media_strip);
        RecyclerView grid  = sheetRoot.findViewById(R.id.recents_grid);
        View recentsLabel  = sheetRoot.findViewById(R.id.recents_label);
        View recentsEmpty  = sheetRoot.findViewById(R.id.recents_empty);
        View topContent    = sheetRoot.findViewById(R.id.top_content);
        if (strip == null || grid == null || topContent == null) return; // older/replaced layout — skip silently

        RecentMediaStripAdapter.Listener stripListener = new RecentMediaStripAdapter.Listener() {
            @Override public void onCameraTapped() {
                sheet.dismiss();
                launchCamera();
            }
            @Override public void onMediaTapped(RecentMediaLoader.Item item) {
                sheet.dismiss();
                uploadAndSend(item.uri, item.isVideo ? "video" : "image",
                        item.isVideo ? "video" : "image", null);
            }
        };
        RecentMediaStripAdapter stripAdapter = new RecentMediaStripAdapter(stripListener);
        strip.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        strip.setAdapter(stripAdapter);

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int cellPx = dm.widthPixels / 4;
        RecentMediaGridAdapter.Listener gridListener = item -> {
            sheet.dismiss();
            uploadAndSend(item.uri, item.isVideo ? "video" : "image",
                    item.isVideo ? "video" : "image", null);
        };
        RecentMediaGridAdapter gridAdapter = new RecentMediaGridAdapter(gridListener) {
            // Force square cells sized to (screen width / 4) since GridLayoutManager
            // alone won't do that for us — cheaper than a custom ItemDecoration
            // for a grid that's only ever 4 columns.
            @Override public RecentMediaGridAdapter.VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                RecentMediaGridAdapter.VH h = super.onCreateViewHolder(parent, viewType);
                android.view.ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
                lp.width = cellPx;
                lp.height = cellPx;
                h.itemView.setLayoutParams(lp);
                return h;
            }
        };
        grid.setLayoutManager(new GridLayoutManager(activity, 4));
        grid.setAdapter(gridAdapter);

        // Only need permission-gated MediaStore reads for READ_MEDIA_IMAGES/
        // READ_MEDIA_VIDEO (API 33+) or READ_EXTERNAL_STORAGE (older) — if not
        // granted yet, just leave both rows empty; the icon-grid attach
        // options keep working regardless, and the system Gallery picker
        // (opt_gallery) requests it independently when tapped.
        boolean hasPerm = hasMediaReadPermission();
        if (hasPerm) {
            mediaQueryExecutor.execute(() -> {
                List<RecentMediaLoader.Item> items = RecentMediaLoader.loadRecent(activity, RECENT_MEDIA_LIMIT);
                activity.runOnUiThread(() -> {
                    stripAdapter.submit(items);
                    gridAdapter.submit(items);
                    if (recentsEmpty != null) recentsEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    if (recentsLabel != null) recentsLabel.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                    grid.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                });
            });
        } else {
            if (recentsLabel != null) recentsLabel.setVisibility(View.GONE);
            grid.setVisibility(View.GONE);
        }

        // Peek height = collapsed content only, so the sheet opens compact
        // (matches the reference screenshot) and dragging up is what reveals
        // the Recents grid below it.
        BottomSheetBehavior<android.widget.FrameLayout> behavior = sheet.getBehavior();
        behavior.setSkipCollapsed(false);
        behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        topContent.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (topContent.getHeight() > 0) {
                    behavior.setPeekHeight(topContent.getHeight());
                    topContent.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            }
        });
    }

    private boolean hasMediaReadPermission() {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED;
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

    /** All uploads attempted (or batch was cancelled mid-way) — push ONE grouped message if we have anything. */
    private void finishMultiUpload(List<java.util.Map<String, Object>> collected, String caption) {
        delegate.getBinding().uploadProgress.setVisibility(View.GONE);
        delegate.getBinding().uploadProgress.setOnClickListener(null);
        boolean wasCancelled = multiUploadCancelled;
        multiUploadCancelled = false;

        if (!collected.isEmpty()) {
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

            String preview = collected.size() == 1
                    ? "📷 Photo"
                    : "📷 " + collected.size() + " photos";
            delegate.pushMessage(m, preview);
            delegate.clearReply();
            if (wasCancelled) {
                Toast.makeText(activity, "Cancelled — " + collected.size() + " bhej di gayi", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(activity, wasCancelled ? "Cancelled" : "Sab files fail ho gayi", Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadSequentially(List<Uri> uris, List<String> perItemCaptions, String caption, int index) {
        multiUploadCancelled = false;
        uploadSequentially(uris, perItemCaptions, caption, index, new ArrayList<>());
    }

    /** Call to stop an in-progress multi-media batch (e.g. tap on the upload progress bar). */
    public void cancelMultiUpload() {
        if (multiUploadCancelled) return;
        multiUploadCancelled = true;
        Toast.makeText(activity, "Cancelling… bachi hui files send nahi hongi", Toast.LENGTH_SHORT).show();
    }

    private void uploadSequentially(List<Uri> uris, List<String> perItemCaptions, String caption, int index,
                                    List<java.util.Map<String, Object>> collected) {
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
        String rawMime = activity.getContentResolver().getType(uri);
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
            VideoCompressor.compress(activity, uri, new VideoCompressor.Callback() {
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
                            if (itemCaption != null && !itemCaption.isEmpty()) item.put("caption", itemCaption);
                            collected.add(item);
                            uploadSequentially(uris, perItemCaptions, caption, index + 1, collected);
                        }
                        @Override public void onError(Exception e) {
                            activity.runOnUiThread(() -> Toast.makeText(activity,
                                "Video " + (index + 1) + " fail: " + (e != null ? e.getMessage() : "Unknown"),
                                Toast.LENGTH_SHORT).show());
                            uploadSequentially(uris, perItemCaptions, caption, index + 1, collected);
                        }
                    });
                }
                @Override public void onError(Exception e) {
                    // Compression failed — fall back to raw upload rather than
                    // dropping the item entirely.
                    android.util.Log.w("ChatMediaController", "Multi-video compress failed, raw upload", e);
                    rawUploadGroupItem(uri, "video", mediaType, null, itemCaption,
                            uris, perItemCaptions, caption, index, collected);
                }
            });
            return;
        }

        // AUDIO / FILE — uploaded as 'raw' so they can sit inside the same
        // grouped message instead of being silently treated as images.
        String resType = (isAudio || isFile) ? "raw" : "image";
        rawUploadGroupItem(uri, resType, mediaType, fileName, itemCaption,
                uris, perItemCaptions, caption, index, collected);
    }

    /** image / audio / file: single-shot upload into the in-progress group. */
    private void rawUploadGroupItem(Uri uri, String resType, String mediaType, String fileName,
                                    String itemCaption,
                                    List<Uri> uris, List<String> perItemCaptions, String caption,
                                    int index, List<java.util.Map<String, Object>> collected) {
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
                    collected.add(item);

                    // Upload next item
                    uploadSequentially(uris, perItemCaptions, caption, index + 1, collected);
                }
                @Override public void onError(String err) {
                    activity.runOnUiThread(() ->
                        Toast.makeText(activity,
                            "File " + (index + 1) + " fail: " + (err != null ? err : "Unknown"),
                            Toast.LENGTH_SHORT).show());
                    // Continue with remaining files even if one fails
                    uploadSequentially(uris, perItemCaptions, caption, index + 1, collected);
                }
            });
    }

    /** image / video / audio / file classifier from a content:// mime type. */
    private static String classifyMediaType(String rawMime) {
        if (rawMime == null) return "file";
        if (rawMime.startsWith("video")) return "video";
        if (rawMime.startsWith("audio")) return "audio";
        if (rawMime.startsWith("image")) return "image";
        return "file";
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
        binding.uploadProgress.setVisibility(View.VISIBLE);

        // IMAGE: compress first, then dual upload (thumb + full)
        if ("image".equals(msgType)) {
            ImageCompressor.compress(activity, uri, new ImageCompressor.Callback() {
                @Override public void onSuccess(ImageCompressor.Result result) {
                    Uri fullUri  = Uri.fromFile(result.fullFile);
                    Uri thumbUri = Uri.fromFile(result.thumbFile);

                    CloudinaryUploader.upload(activity, thumbUri, "callx/thumb", "image",
                            new CloudinaryUploader.UploadCallback() {
                                @Override public void onSuccess(CloudinaryUploader.Result thumbResult) {
                                    String thumbUrl = thumbResult.secureUrl;
                                    CloudinaryUploader.upload(activity, fullUri, "callx/image", "image",
                                            new CloudinaryUploader.UploadCallback() {
                                                @Override public void onSuccess(CloudinaryUploader.Result fullResult) {
                                                    binding.uploadProgress.setVisibility(View.GONE);
                                                    result.thumbFile.delete();
                                                    result.fullFile.delete();
                                                    Message m      = delegate.buildOutgoing();
                                                    m.type         = "image";
                                                    m.mediaUrl     = fullResult.secureUrl;
                                                    m.imageUrl     = fullResult.secureUrl;
                                                    m.thumbnailUrl = thumbUrl;
                                                    m.fileSize     = fullResult.bytes;
                                                    m.mediaWidth   = result.fullWidth;
                                                    m.mediaHeight  = result.fullHeight;
                                                    delegate.pushMessage(m, "\uD83D\uDCF7 Photo");
                                                    delegate.clearReply();
                                                }
                                                @Override public void onError(String err) {
                                                    binding.uploadProgress.setVisibility(View.GONE);
                                                    result.thumbFile.delete();
                                                    result.fullFile.delete();
                                                    Toast.makeText(activity,
                                                            err != null ? err : "Upload failed",
                                                            Toast.LENGTH_LONG).show();
                                                }
                                            });
                                }
                                @Override public void onError(String err) {
                                    // Thumb upload failed — upload full image without thumb
                                    CloudinaryUploader.upload(activity, fullUri, "callx/image", "image",
                                            new CloudinaryUploader.UploadCallback() {
                                                @Override public void onSuccess(CloudinaryUploader.Result r) {
                                                    binding.uploadProgress.setVisibility(View.GONE);
                                                    result.thumbFile.delete();
                                                    result.fullFile.delete();
                                                    Message m  = delegate.buildOutgoing();
                                                    m.type     = "image";
                                                    m.mediaUrl = r.secureUrl;
                                                    m.imageUrl = r.secureUrl;
                                                    m.mediaWidth  = result.fullWidth;
                                                    m.mediaHeight = result.fullHeight;
                                                    delegate.pushMessage(m, "\uD83D\uDCF7 Photo");
                                                    delegate.clearReply();
                                                }
                                                @Override public void onError(String e) {
                                                    binding.uploadProgress.setVisibility(View.GONE);
                                                    result.thumbFile.delete();
                                                    result.fullFile.delete();
                                                    Toast.makeText(activity, "Upload failed", Toast.LENGTH_LONG).show();
                                                }
                                            });
                                }
                            });
                }
                @Override public void onError(Exception e) {
                    android.util.Log.w("ChatMediaController", "Compression failed, uploading original", e);
                    doUpload(uri, msgType, resourceType, fileName);
                }
            });
            return;
        }

        // VIDEO: compress → dual upload (thumb + video)
        if ("video".equals(msgType)) {
            binding.uploadProgress.setIndeterminate(false);
            binding.uploadProgress.setMax(100);
            binding.uploadProgress.setProgress(0);

            VideoCompressor.compress(activity, uri, new VideoCompressor.Callback() {
                @Override public void onProgress(int percent) {
                    binding.uploadProgress.setProgress(percent / 2);
                }
                @Override public void onSuccess(VideoCompressor.Result result) {
                    VideoUploader.upload(activity, result, new VideoUploader.UploadCallback() {
                        @Override public void onProgress(int percent) {
                            binding.uploadProgress.setProgress(50 + percent / 2);
                        }
                        @Override public void onSuccess(String thumbUrl, String videoUrl,
                                                        int durationMs, int width, int height) {
                            binding.uploadProgress.setVisibility(View.GONE);
                            Message m      = delegate.buildOutgoing();
                            m.type         = "video";
                            m.mediaUrl     = videoUrl;
                            m.thumbnailUrl = thumbUrl;
                            m.duration     = (long) durationMs;
                            m.mediaWidth   = width;
                            m.mediaHeight  = height;
                            delegate.pushMessage(m, "\uD83C\uDFAC Video");
                            delegate.clearReply();
                        }
                        @Override public void onError(Exception e) {
                            binding.uploadProgress.setVisibility(View.GONE);
                            Toast.makeText(activity,
                                    e != null ? e.getMessage() : "Video upload failed",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
                @Override public void onError(Exception e) {
                    android.util.Log.w("ChatMediaController", "Video compress failed, fallback", e);
                    doUpload(uri, msgType, resourceType, fileName);
                }
            });
            return;
        }

        // Audio / file: direct upload
        doUpload(uri, msgType, resourceType, fileName);
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
