package com.callx.app.conversation.delegates;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.models.Message;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.ImageCompressor;
import com.callx.app.utils.VideoCompressor;
import com.callx.app.utils.VoiceRecorder;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * ChatMediaDelegate — Camera, pickers, Cloudinary upload (image/video/audio/file/GIF),
 *                     voice recording. Launchers registered in Activity then injected here.
 */
public class ChatMediaDelegate {

    public static final int REQ_AUDIO = 200, REQ_CAMERA = 300;

    public interface Callback {
        boolean isOnline();
        Message buildOutgoing();
        void pushMessage(Message m, String preview);
        void clearReply();
        void runOnUiThread(Runnable r);
    }

    public interface WallpaperPickedListener { void onWallpaperPicked(Uri uri); }

    private final Activity            activity;
    private final ActivityChatBinding binding;
    private final Callback            callback;

    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker, wallpaperPicker;
    private ActivityResultLauncher<Uri>    cameraCapturer;
    private WallpaperPickedListener        wallpaperPickedListener;

    public Uri cameraOutputUri;

    private final VoiceRecorder recorder   = new VoiceRecorder();
    private       boolean       isRecording = false;

    public ChatMediaDelegate(Activity activity, ActivityChatBinding binding, Callback callback) {
        this.activity = activity;
        this.binding  = binding;
        this.callback = callback;
    }

    public void setLaunchers(ActivityResultLauncher<String> img, ActivityResultLauncher<String> vid,
                             ActivityResultLauncher<String> aud, ActivityResultLauncher<String> file,
                             ActivityResultLauncher<Uri> cam, ActivityResultLauncher<String> wall) {
        imagePicker = img; videoPicker = vid; audioPicker = aud;
        filePicker = file; cameraCapturer = cam; wallpaperPicker = wall;
    }

    public void setWallpaperPickedListener(WallpaperPickedListener l) { wallpaperPickedListener = l; }

    public void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        View v = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_attach, null);
        v.findViewById(R.id.opt_gallery).setOnClickListener(x -> { sheet.dismiss(); imagePicker.launch("image/*"); });
        v.findViewById(R.id.opt_video).setOnClickListener(x -> { sheet.dismiss(); videoPicker.launch("video/*"); });
        v.findViewById(R.id.opt_audio).setOnClickListener(x -> { sheet.dismiss(); audioPicker.launch("audio/*"); });
        v.findViewById(R.id.opt_file).setOnClickListener(x -> { sheet.dismiss(); filePicker.launch("*/*"); });
        sheet.setContentView(v); sheet.show();
    }

    public void launchCamera() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, "callx_" + System.currentTimeMillis() + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cameraOutputUri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (cameraOutputUri != null) cameraCapturer.launch(cameraOutputUri);
    }

    public void toggleRecording() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (!isRecording) {
            if (recorder.start(activity)) {
                isRecording = true;
                binding.btnMic.setBackgroundResource(R.drawable.circle_reject);
                Toast.makeText(activity, "Recording\u2026 tap again to stop", Toast.LENGTH_SHORT).show();
            }
        } else {
            isRecording = false;
            binding.btnMic.setBackgroundResource(R.drawable.circle_primary);
            Uri uri = recorder.stop(activity);
            if (uri != null) uploadAndSend(uri, "audio", "raw", null);
            else Toast.makeText(activity, "Recording was empty", Toast.LENGTH_SHORT).show();
        }
    }

    public void showWallpaperPicker() { wallpaperPicker.launch("image/*"); }

    public void handleWallpaperPicked(Uri uri) {
        if (uri == null) return;
        try { activity.getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) {}
        if (wallpaperPickedListener != null) wallpaperPickedListener.onWallpaperPicked(uri);
    }

    public void sendGifMessage(Uri gifUri, androidx.core.view.inputmethod.InputContentInfoCompat info) {
        if (gifUri == null) { if (info != null) info.releasePermission(); return; }
        if (!callback.isOnline()) {
            if (info != null) info.releasePermission();
            Toast.makeText(activity, "No connection", Toast.LENGTH_SHORT).show(); return;
        }
        binding.uploadProgress.setVisibility(View.VISIBLE);
        CloudinaryUploader.upload(activity, gifUri, "callx/gif", "image", new CloudinaryUploader.UploadCallback() {
            @Override public void onSuccess(CloudinaryUploader.Result r) {
                if (info != null) info.releasePermission();
                binding.uploadProgress.setVisibility(View.GONE);
                Message m = callback.buildOutgoing(); m.type = "gif"; m.mediaUrl = r.secureUrl; m.imageUrl = r.secureUrl;
                callback.pushMessage(m, "\uD83C\uDFDE\uFE0F GIF"); callback.clearReply();
            }
            @Override public void onError(String err) {
                if (info != null) info.releasePermission();
                binding.uploadProgress.setVisibility(View.GONE);
                Toast.makeText(activity, err != null ? err : "GIF upload failed", Toast.LENGTH_LONG).show();
            }
        });
    }

    public void uploadAndSend(Uri uri, String msgType, String resourceType, String fileName) {
        if (!callback.isOnline()) {
            Toast.makeText(activity, "No connection — media send nahi ho sakta", Toast.LENGTH_LONG).show(); return;
        }
        binding.uploadProgress.setVisibility(View.VISIBLE);
        if ("image".equals(msgType)) { uploadImage(uri); return; }
        if ("video".equals(msgType)) { uploadVideo(uri); return; }
        doUpload(uri, msgType, resourceType, fileName);
    }

    private void uploadImage(Uri uri) {
        ImageCompressor.compress(activity, uri, new ImageCompressor.Callback() {
            @Override public void onSuccess(ImageCompressor.Result res) {
                Uri full = Uri.fromFile(res.fullFile), thumb = Uri.fromFile(res.thumbFile);
                CloudinaryUploader.upload(activity, thumb, "callx/thumb", "image", new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result tr) {
                        CloudinaryUploader.upload(activity, full, "callx/image", "image", new CloudinaryUploader.UploadCallback() {
                            @Override public void onSuccess(CloudinaryUploader.Result r) {
                                binding.uploadProgress.setVisibility(View.GONE);
                                res.thumbFile.delete(); res.fullFile.delete();
                                Message m = callback.buildOutgoing(); m.type = "image"; m.mediaUrl = r.secureUrl;
                                m.imageUrl = r.secureUrl; m.thumbnailUrl = tr.secureUrl; m.fileSize = r.bytes;
                                callback.pushMessage(m, "\uD83D\uDCF7 Photo"); callback.clearReply();
                            }
                            @Override public void onError(String e) {
                                binding.uploadProgress.setVisibility(View.GONE); res.thumbFile.delete(); res.fullFile.delete();
                                Toast.makeText(activity, e != null ? e : "Upload failed", Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                    @Override public void onError(String e) { doUpload(full, "image", "image", null); }
                });
            }
            @Override public void onError(Exception e) { doUpload(uri, "image", "image", null); }
        });
    }

    private void uploadVideo(Uri uri) {
        binding.uploadProgress.setIndeterminate(false); binding.uploadProgress.setMax(100);
        VideoCompressor.compress(activity, uri, new VideoCompressor.Callback() {
            @Override public void onSuccess(Uri comp, Uri thumb) {
                CloudinaryUploader.upload(activity, thumb, "callx/thumb", "image", new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result tr) {
                        CloudinaryUploader.upload(activity, comp, "callx/video", "video", new CloudinaryUploader.UploadCallback() {
                            @Override public void onSuccess(CloudinaryUploader.Result r) {
                                binding.uploadProgress.setVisibility(View.GONE);
                                Message m = callback.buildOutgoing(); m.type = "video"; m.mediaUrl = r.secureUrl;
                                m.thumbnailUrl = tr.secureUrl; m.fileSize = r.bytes; m.duration = r.durationMs;
                                callback.pushMessage(m, "\uD83C\uDFAC Video"); callback.clearReply();
                            }
                            @Override public void onError(String e) { binding.uploadProgress.setVisibility(View.GONE); Toast.makeText(activity, "Video upload failed", Toast.LENGTH_LONG).show(); }
                        });
                    }
                    @Override public void onError(String e) { doUpload(comp, "video", "video", null); }
                });
            }
            @Override public void onProgress(int pct) { binding.uploadProgress.setProgress(pct/2); }
            @Override public void onError(Exception e) { doUpload(uri, "video", "video", null); }
        });
    }

    public void doUpload(Uri uri, String msgType, String resourceType, String fileName) {
        long size = FileUtils.fileSize(activity, uri);
        long limit; String limitLabel;
        switch (msgType) {
            case "image": limit = 10L*1024*1024; limitLabel = "10 MB"; break;
            case "video": limit = 100L*1024*1024; limitLabel = "100 MB"; break;
            case "audio": limit = 25L*1024*1024; limitLabel = "25 MB"; break;
            default:      limit = 50L*1024*1024; limitLabel = "50 MB"; break;
        }
        if (size > limit) {
            String tn; switch(msgType){case"image":tn="Image";break;case"video":tn="Video";break;case"audio":tn="Audio";break;default:tn="File";}
            Toast.makeText(activity, tn + " too large — max " + limitLabel, Toast.LENGTH_LONG).show(); return;
        }
        CloudinaryUploader.upload(activity, uri, "callx/" + msgType, resourceType, new CloudinaryUploader.UploadCallback() {
            @Override public void onSuccess(CloudinaryUploader.Result r) {
                binding.uploadProgress.setVisibility(View.GONE);
                Message m = callback.buildOutgoing(); m.type = msgType; m.mediaUrl = r.secureUrl;
                m.imageUrl = "image".equals(msgType) ? r.secureUrl : null;
                m.fileName = fileName; m.fileSize = r.bytes != null ? r.bytes : size; m.duration = r.durationMs;
                callback.pushMessage(m, mediaPreview(msgType, fileName)); callback.clearReply();
            }
            @Override public void onError(String err) {
                binding.uploadProgress.setVisibility(View.GONE);
                Toast.makeText(activity, err != null ? err : "Upload failed", Toast.LENGTH_LONG).show();
            }
        });
    }

    public static String mediaPreview(String type, String fileName) {
        switch (type) {
            case "image": return "\uD83D\uDCF7 Photo";
            case "video": return "\uD83C\uDFAC Video";
            case "audio": return "\uD83C\uDFA4 Voice message";
            case "file":  return "\uD83D\uDCCE " + (fileName != null ? fileName : "File");
            default:      return "Media";
        }
    }
}
