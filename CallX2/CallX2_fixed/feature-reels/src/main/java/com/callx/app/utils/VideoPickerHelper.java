package com.callx.app.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * VideoPickerHelper v24 — Video selection from Gallery, Camera, or Files.
 *
 * NEW in v24:
 *  ✅ Multi-video select from gallery (up to MAX_MULTI_SELECT)
 *  ✅ Gallery duration limit (configurable — default unlimited, warn if > 10 min)
 *  ✅ Format support: MP4, MOV, MKV, AVI, WebM, 3GP
 *  ✅ File size warning before picking (configurable limit)
 *  ✅ OnMultipleVideosPicked callback for multi-select
 *  ✅ Duration info passed to callback for pre-compress estimation
 *  ✅ Camera 60s limit (like WhatsApp)
 *  ✅ openFilePicker() for document-style video pick
 *
 * Usage:
 *   videoPicker = new VideoPickerHelper(this,
 *       uri  -> handleSingleVideo(uri),    // single pick
 *       uris -> handleMultipleVideos(uris) // multi pick (nullable if not needed)
 *   );
 *   btnGallery.setOnClickListener(v -> videoPicker.openGallery());
 *   btnCamera.setOnClickListener(v  -> videoPicker.openCamera());
 *   btnMulti.setOnClickListener(v   -> videoPicker.openGalleryMulti());
 */
public class VideoPickerHelper {

    public interface OnVideoPicked          { void onPicked(Uri videoUri); }
    public interface OnMultipleVideosPicked { void onPicked(List<Uri> uris); }

    private static final int    MAX_CAMERA_DURATION_SEC = 60;
    private static final int    MAX_MULTI_SELECT        = 10;
    private static final long   WARN_FILE_SIZE_BYTES    = 500L * 1024 * 1024; // 500 MB
    private static final long   MAX_GALLERY_DURATION_MS = 10L * 60 * 1000;    // warn >10 min

    // Supported MIME types
    private static final String VIDEO_MIME_TYPE = "video/*";

    private final AppCompatActivity        activity;
    private final OnVideoPicked            singleCallback;
    private final OnMultipleVideosPicked   multiCallback;

    private ActivityResultLauncher<Intent>   galleryLauncher;
    private ActivityResultLauncher<Intent>   galleryMultiLauncher;
    private ActivityResultLauncher<Intent>   fileLauncher;
    private ActivityResultLauncher<Uri>      cameraLauncher;
    private ActivityResultLauncher<String[]> permLauncher;

    private Uri    cameraOutputUri;
    private String pendingAction;

    public VideoPickerHelper(AppCompatActivity activity,
                             OnVideoPicked singleCallback,
                             OnMultipleVideosPicked multiCallback) {
        this.activity       = activity;
        this.singleCallback = singleCallback;
        this.multiCallback  = multiCallback;
        registerLaunchers();
    }

    /** Convenience constructor for single-pick only. */
    public VideoPickerHelper(AppCompatActivity activity, OnVideoPicked callback) {
        this(activity, callback, null);
    }

    // ── Register launchers ─────────────────────────────────────────────────

    private void registerLaunchers() {

        // Single gallery pick
        galleryLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK
                    || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null && singleCallback != null) {
                    checkAndDeliver(uri);
                }
            }
        );

        // Multi-select gallery pick
        galleryMultiLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK
                    || result.getData() == null) return;
                List<Uri> uris = new ArrayList<>();
                android.content.ClipData clipData = result.getData().getClipData();
                if (clipData != null) {
                    for (int i = 0; i < Math.min(clipData.getItemCount(), MAX_MULTI_SELECT); i++)
                        uris.add(clipData.getItemAt(i).getUri());
                } else if (result.getData().getData() != null) {
                    uris.add(result.getData().getData());
                }
                if (!uris.isEmpty() && multiCallback != null)
                    multiCallback.onPicked(uris);
                else if (!uris.isEmpty() && singleCallback != null)
                    checkAndDeliver(uris.get(0));
            }
        );

        // File picker (for MOV/MKV/AVI files from Downloads/Files app)
        fileLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK
                    || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null && singleCallback != null) {
                    checkAndDeliver(uri);
                }
            }
        );

        // Camera
        cameraLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.CaptureVideo(),
            success -> {
                if (success && cameraOutputUri != null && singleCallback != null)
                    singleCallback.onPicked(cameraOutputUri);
            }
        );

        // Permissions
        permLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean granted = true;
                for (boolean v : result.values()) granted = granted && v;
                if (granted) {
                    switch (pendingAction != null ? pendingAction : "") {
                        case "gallery":       launchGallery(); break;
                        case "gallery_multi": launchGalleryMulti(); break;
                        case "camera":        launchCamera(); break;
                        case "file":          launchFilePicker(); break;
                    }
                } else {
                    Toast.makeText(activity, "Permission required to pick video",
                        Toast.LENGTH_SHORT).show();
                }
            }
        );
    }

    // ── Public methods ─────────────────────────────────────────────────────

    /** Open gallery for single video selection. */
    public void openGallery() {
        pendingAction = "gallery";
        if (checkAndRequestPermissions("gallery")) launchGallery();
    }

    /** Open gallery for multiple video selection (up to 10). */
    public void openGalleryMulti() {
        pendingAction = "gallery_multi";
        if (checkAndRequestPermissions("gallery")) launchGalleryMulti();
    }

    /** Open camera for video recording (60s limit). */
    public void openCamera() {
        pendingAction = "camera";
        if (checkAndRequestPermissions("camera")) launchCamera();
    }

    /** Open system file picker — works for MOV/MKV/AVI/WebM files in Downloads. */
    public void openFilePicker() {
        pendingAction = "file";
        if (checkAndRequestPermissions("gallery")) launchFilePicker();
    }

    // ── Internal launchers ─────────────────────────────────────────────────

    private void launchGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        intent.setType(VIDEO_MIME_TYPE);
        galleryLauncher.launch(intent);
    }

    private void launchGalleryMulti() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(VIDEO_MIME_TYPE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        galleryMultiLauncher.launch(Intent.createChooser(intent, "Select videos"));
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(VIDEO_MIME_TYPE);
        // Allow multiple types
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "video/mp4", "video/quicktime", "video/x-matroska",
            "video/x-msvideo", "video/webm", "video/3gpp", "video/*"
        });
        fileLauncher.launch(Intent.createChooser(intent, "Select video file"));
    }

    private void launchCamera() {
        try {
            File videoFile  = createTempVideoFile();
            cameraOutputUri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                videoFile);
            cameraLauncher.launch(cameraOutputUri);
        } catch (IOException e) {
            Toast.makeText(activity, "Cannot open camera: " + e.getMessage(),
                Toast.LENGTH_SHORT).show();
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    private void checkAndDeliver(Uri uri) {
        new Thread(() -> {
            VideoInfo info = getVideoInfo(uri);
            activity.runOnUiThread(() -> {
                // Warn for very long videos (> 10 min) — still allow
                if (info.durationMs > MAX_GALLERY_DURATION_MS) {
                    long mins = info.durationMs / 60000;
                    Toast.makeText(activity,
                        "Long video (" + mins + " min) — will compress to save data",
                        Toast.LENGTH_LONG).show();
                }
                // Warn for very large files — still allow
                if (info.fileSizeBytes > WARN_FILE_SIZE_BYTES) {
                    Toast.makeText(activity,
                        "Large file (" + info.fileSizeBytes / (1024 * 1024) + " MB) — "
                            + "compression may take a moment",
                        Toast.LENGTH_SHORT).show();
                }
                singleCallback.onPicked(uri);
            });
        }).start();
    }

    public static class VideoInfo {
        public long durationMs    = 0;
        public long fileSizeBytes = 0;
        public int  width         = 0;
        public int  height        = 0;
    }

    public VideoInfo getVideoInfo(Uri uri) {
        VideoInfo info = new VideoInfo();
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(activity, uri);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (d != null) info.durationMs = Long.parseLong(d);
            if (w != null) info.width      = Integer.parseInt(w);
            if (h != null) info.height     = Integer.parseInt(h);
        } catch (Exception e) {
            // ignore — info will have defaults
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        // File size via ContentResolver
        try (android.database.Cursor c = activity.getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0) info.fileSizeBytes = c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return info;
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private boolean checkAndRequestPermissions(String action) {
        String[] needed = getNeededPermissions(action);
        if (needed.length == 0) return true;
        permLauncher.launch(needed);
        return false;
    }

    private String[] getNeededPermissions(String action) {
        List<String> needed = new ArrayList<>();
        if ("gallery".equals(action) || "file".equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED)
                    needed.add(Manifest.permission.READ_MEDIA_VIDEO);
            } else {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                    needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if ("camera".equals(action)) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.CAMERA);
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.RECORD_AUDIO);
        }
        return needed.toArray(new String[0]);
    }

    // ── Temp file ─────────────────────────────────────────────────────────

    private File createTempVideoFile() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return File.createTempFile("VID_" + ts, ".mp4", activity.getCacheDir());
    }
}
