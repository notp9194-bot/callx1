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
import java.util.Date;
import java.util.Locale;

/**
 * ImagePickerHelper — Easy image selection from Gallery or Camera
 *
 * Usage in Activity:
 *
 *   private ImagePickerHelper picker;
 *
 *   @Override
 *   protected void onCreate(...) {
 *       picker = new ImagePickerHelper(this, uri -> {
 *           // Got image URI — now compress + upload
 *           ImageCompressor.compress(this, uri, new ImageCompressor.Callback() {
 *               public void onSuccess(Result r)  { upload(r); }
 *               public void onError(Exception e) { showToast("Error"); }
 *           });
 *       });
 *   }
 *
 *   btnGallery.setOnClickListener(v -> picker.openGallery());
 *   btnCamera.setOnClickListener(v  -> picker.openCamera());
 */
public class ImagePickerHelper {

    public interface OnImagePicked { void onPicked(Uri imageUri); }

    private final AppCompatActivity activity;
    private final OnImagePicked      callback;

    private ActivityResultLauncher<Intent>   galleryLauncher;
    private ActivityResultLauncher<Uri>      cameraLauncher;
    private ActivityResultLauncher<String[]> permLauncher;

    private Uri    cameraOutputUri;
    private String pendingAction; // "gallery" or "camera"

    public ImagePickerHelper(AppCompatActivity activity, OnImagePicked callback) {
        this.activity = activity;
        this.callback = callback;
        registerLaunchers();
    }

    // ── Register Activity Result launchers ────────────────────────────────

    private void registerLaunchers() {

        // Gallery picker
        galleryLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK
                    && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) callback.onPicked(uri);
                }
            }
        );

        // Camera capture
        cameraLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
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
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        galleryLauncher.launch(intent);
    }

    private void launchCamera() {
        try {
            File photoFile = createTempImageFile();
            cameraOutputUri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                photoFile
            );
            cameraLauncher.launch(cameraOutputUri);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean checkAndRequestPermissions(String action) {
        String[] needed = getNeededPermissions(action);
        if (needed.length == 0) return true;
        permLauncher.launch(needed);
        return false;
    }

    private String[] getNeededPermissions(String action) {
        java.util.List<String> needed = new java.util.ArrayList<>();

        if ("gallery".equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_MEDIA_IMAGES);
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
        }

        return needed.toArray(new String[0]);
    }

    private File createTempImageFile() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(new Date());
        File storageDir = activity.getCacheDir();
        return File.createTempFile("IMG_" + timestamp, ".jpg", storageDir);
    }
}
