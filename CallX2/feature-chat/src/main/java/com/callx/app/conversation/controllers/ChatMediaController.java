package com.callx.app.conversation.controllers;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * Handles media pickers (image/video/audio/file/camera/wallpaper),
 * media upload, GIF sending, and voice recording.
 */
public class ChatMediaController {

    public static final int REQ_AUDIO  = 200;
    public static final int REQ_CAMERA = 300;

    private final AppCompatActivity activity;
    private final ChatActivityDelegate delegate;

    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<String> videoPicker;
    private ActivityResultLauncher<String> audioPicker;
    private ActivityResultLauncher<String> filePicker;
    private ActivityResultLauncher<Uri>    cameraCapturer;
    private ActivityResultLauncher<String> wallpaperPicker;

    private Uri cameraOutputUri;

    private final VoiceRecorder recorder = new VoiceRecorder();

    public ChatMediaController(AppCompatActivity activity, ChatActivityDelegate delegate) {
        this.activity = activity;
        this.delegate = delegate;
    }

    // ── Register launchers (call from Activity.onCreate BEFORE super) ──────

    public void registerPickers() {
        imagePicker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image", "image", null); });

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
    }

    // ── Launch wallpaper picker (called via delegate) ─────────────────────

    public void launchWallpaperPicker() {
        wallpaperPicker.launch("image/*");
    }

    // ── Attach sheet ──────────────────────────────────────────────────────

    public void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        View v = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_attach, null);
        v.findViewById(R.id.opt_gallery)
                .setOnClickListener(x -> { sheet.dismiss(); imagePicker.launch("image/*"); });
        v.findViewById(R.id.opt_video)
                .setOnClickListener(x -> { sheet.dismiss(); videoPicker.launch("video/*"); });
        v.findViewById(R.id.opt_audio)
                .setOnClickListener(x -> { sheet.dismiss(); audioPicker.launch("audio/*"); });
        v.findViewById(R.id.opt_file)
                .setOnClickListener(x -> { sheet.dismiss(); filePicker.launch("*/*"); });
        View optPoll = v.findViewById(R.id.opt_poll);
        if (optPoll != null) {
            optPoll.setOnClickListener(x -> { sheet.dismiss(); delegate.launchPollCreator(); });
        }
        sheet.setContentView(v);
        sheet.show();
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

    // ── Voice recording ───────────────────────────────────────────────────

    public void toggleRecording() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        ActivityChatBinding binding = delegate.getBinding();
        if (!delegate.isRecording()) {
            if (recorder.start(activity)) {
                delegate.setRecording(true);
                binding.btnMic.setBackgroundResource(R.drawable.circle_reject);
                Toast.makeText(activity, "Recording\u2026 tap again to stop", Toast.LENGTH_SHORT).show();
            }
        } else {
            delegate.setRecording(false);
            binding.btnMic.setBackgroundResource(R.drawable.circle_primary);
            Uri uri = recorder.stop(activity);
            if (uri != null) uploadAndSend(uri, "audio", "raw", null);
            else Toast.makeText(activity, "Recording was empty", Toast.LENGTH_SHORT).show();
        }
    }
}
