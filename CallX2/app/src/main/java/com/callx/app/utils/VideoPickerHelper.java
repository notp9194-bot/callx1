package com.callx.app.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

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
 * VideoPickerHelper — Easy video selection from Gallery or Camera
 *
 * Usage in Activity:
 *
 *   private VideoPickerHelper videoPicker;
 *
 *   @Override protected void onCreate(...) {
 *       videoPicker = new VideoPickerHelper(this, uri -> {
 *           // Got video URI → compress → upload
 *           VideoCompressor.compress(this, uri, new VideoCompressor.Callback() {
 *               public void onProgress(int pct)  { progressBar.setProgress(pct); }
 *               public void onSuccess(Result r)  { uploadVideo(r); }
 *               public void onError(Exception e) { showError(); }
 *           });
 *       });
 *   }
 *
 *   btnGallery.setOnClickListener(v -> videoPicker.openGallery());
 *   btnCamera.setOnClickListener(v  -> videoPicker.openCamera());
 */
public class VideoPickerHelper {

    public interface OnVideoPicked { void onPicked(Uri videoUri); }

    // Max recording duration: 60 seconds (like WhatsApp)
    private static final int MAX_DURATION_SECONDS = 60;

    private final AppCompatActivity activity;
    private final OnVideoPicked     callback;

    private ActivityResultLauncher<Intent>   galleryLauncher;
    private ActivityResultLauncher<Uri>      cameraLauncher;
    private ActivityResultLauncher<String[]> permLauncher;

    private Uri    cameraOutputUri;
    private String pendingAction; // "gallery" or "camera"

    public VideoPickerHelper(AppCompatActivity activity, OnVideoPicked callback) {
        this.activity = activity;
        this.callback = callback;
        registerLaunchers();
    }

    // ── Register Activity Result launchers ────────────────────────────────

    private void registerLaunchers() {

        // Gallery video picker
        galleryLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK
                    && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        // Validate duration before proceeding
                        if (isValidDuration(uri)) {
                            callback.onPicked(uri);
                        } else {
                            showDurationError();
                        }
                    }
                }
            }
        );

        // Camera video recorder
        cameraLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.CaptureVideo(),
            success -> {
                if (success && cameraOutputUri != null) {
                    callback.onPicked(cameraOutputUri);
                }
            }
        );

        // Permission request
        permLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean granted = true;
                for (boolean v : result.values()) granted = granted && v;
                if (granted) {
                    if ("gallery".equals(pendingAction)) launchGallery();
                    else if ("camera".equals(pendingAction)) launchCamera();
                }
            }
        );
    }

    // ── Public Methods ────────────────────────────────────────────────────

    public void openGallery() {
        pendingAction = "gallery";
        if (checkAndRequestPermissions("gallery")) launchGallery();
    }

    public void openCamera() {
        pendingAction = "camera";
        if (checkAndRequestPermissions("camera")) launchCamera();
    }

    // ── Internal launchers ────────────────────────────────────────────────

    private void launchGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        intent.setType("video/*");
        // Limit to reasonable size
        intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, MAX_DURATION_SECONDS);
        galleryLauncher.launch(intent);
    }

    private void launchCamera() {
        try {
            File videoFile    = createTempVideoFile();
            cameraOutputUri   = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                videoFile
            );
            cameraLauncher.launch(cameraOutputUri);
        } catch (IOException e) {
            e.printStackTrace();
        }
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

        if ("gallery".equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_MEDIA_VIDEO);
                }
            } else {
                if (ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
            }
        }

        if ("camera".equals(action)) {
            if (ContextCompat.checkSelfPermission(activity,
                Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA);
            }
            if (ContextCompat.checkSelfPermission(activity,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
        }

        return needed.toArray(new String[0]);
    }

    // ── Validation ────────────────────────────────────────────────────────

    private boolean isValidDuration(Uri uri) {
        try {
            android.media.MediaMetadataRetriever mmr =
                new android.media.MediaMetadataRetriever();
            mmr.setDataSource(activity, uri);
            String durStr = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            mmr.release();
            if (durStr == null) return true;
            long durationMs = Long.parseLong(durStr);
            return durationMs <= MAX_DURATION_SECONDS * 1000L;
        } catch (Exception e) {
            return true; // allow on error
        }
    }

    private void showDurationError() {
        android.widget.Toast.makeText(
            activity,
            "Video must be under " + MAX_DURATION_SECONDS + " seconds",
            android.widget.Toast.LENGTH_SHORT
        ).show();
    }

    // ── Temp file ─────────────────────────────────────────────────────────

    private File createTempVideoFile() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(new Date());
        return File.createTempFile("VID_" + ts, ".mp4", activity.getCacheDir());
    }
}
