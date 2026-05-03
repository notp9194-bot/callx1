package com.callx.app.activities;

// ═══════════════════════════════════════════════════════════════════════════
// ChatActivity.java — IMAGE SYSTEM INTEGRATION GUIDE
// ═══════════════════════════════════════════════════════════════════════════
//
// Ye file tumhare existing ChatActivity me image system integrate karne ka
// COMPLETE GUIDE hai. Sirf relevant sections copy karo.
//
// SECTIONS:
//   A. Fields add karo
//   B. onCreate() me setup
//   C. Send image button click
//   D. Image send karne ka full flow
//   E. RecyclerView me image load (adapter me)
//   F. Full-screen viewer launch
//   G. Offline handling
// ═══════════════════════════════════════════════════════════════════════════

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.utils.GlideImageLoader;
import com.callx.app.utils.ImageCompressor;
import com.callx.app.utils.ImagePickerHelper;
import com.callx.app.utils.ImageUploader;
import com.callx.app.utils.NetworkUtils;
import com.callx.app.workers.ImageUploadWorker;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

public class ChatActivityImageGuide extends AppCompatActivity {

    // ──────────────────────────────────────────────────────────────────────
    // A. FIELDS — add these to your ChatActivity
    // ──────────────────────────────────────────────────────────────────────

    private ImagePickerHelper imagePicker;
    private ProgressBar       uploadProgressBar;  // show during upload
    private String            chatId;             // your existing chatId
    private DatabaseReference messagesRef;        // your existing messagesRef

    // ──────────────────────────────────────────────────────────────────────
    // B. onCreate() — add this after existing setup
    // ──────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // existing setup ...

        // ── Image picker setup ──────────────────────────────────────────
        imagePicker = new ImagePickerHelper(this, this::onImagePicked);

        // ── Attach button listeners ─────────────────────────────────────
        // Replace with your actual button IDs:
        // btnAttachGallery.setOnClickListener(v -> imagePicker.openGallery());
        // btnAttachCamera.setOnClickListener(v  -> imagePicker.openCamera());
    }

    // ──────────────────────────────────────────────────────────────────────
    // C. onImagePicked — called when user selects image
    // ──────────────────────────────────────────────────────────────────────

    private void onImagePicked(Uri imageUri) {
        // Show progress immediately
        uploadProgressBar.setVisibility(View.VISIBLE);
        uploadProgressBar.setProgress(0);

        // Compress on background thread (UI freeze nahi hoga)
        ImageCompressor.compress(this, imageUri, new ImageCompressor.Callback() {
            @Override
            public void onSuccess(ImageCompressor.Result result) {
                // Compression done — now upload
                onImageCompressed(result);
            }

            @Override
            public void onError(Exception e) {
                uploadProgressBar.setVisibility(View.GONE);
                Toast.makeText(ChatActivityImageGuide.this,
                    "Image process failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // D. Full image send flow
    // ──────────────────────────────────────────────────────────────────────

    private void onImageCompressed(ImageCompressor.Result result) {

        // 1. Create placeholder message in Firebase immediately
        //    (user sees "sending..." state right away)
        String senderUid = FirebaseAuth.getInstance().getUid();
        String messageId = messagesRef.push().getKey();

        Map<String, Object> pendingMsg = new HashMap<>();
        pendingMsg.put("type",      "image");
        pendingMsg.put("senderId",  senderUid);
        pendingMsg.put("timestamp", System.currentTimeMillis());
        pendingMsg.put("status",    "uploading");
        // No thumbUrl/fullUrl yet — will be set after upload
        pendingMsg.put("thumbUrl",  "");
        pendingMsg.put("fullUrl",   "");

        messagesRef.child(messageId).setValue(pendingMsg);

        // 2. Check network
        if (!NetworkUtils.isOnline(this)) {
            // Offline: save local path, enqueue WorkManager
            handleOfflineImageSend(result, chatId, messageId);
            uploadProgressBar.setVisibility(View.GONE);
            return;
        }

        // 3. Online: upload both files
        ImageUploader.upload(this, result, new ImageUploader.UploadCallback() {
            @Override
            public void onProgress(int percent) {
                uploadProgressBar.setProgress(percent);
            }

            @Override
            public void onSuccess(String thumbUrl, String fullUrl) {
                uploadProgressBar.setVisibility(View.GONE);

                // Update Firebase message with real URLs
                Map<String, Object> update = new HashMap<>();
                update.put("thumbUrl", thumbUrl);
                update.put("fullUrl",  fullUrl);
                update.put("status",   "sent");
                messagesRef.child(messageId).updateChildren(update);
            }

            @Override
            public void onError(Exception e) {
                uploadProgressBar.setVisibility(View.GONE);
                Toast.makeText(ChatActivityImageGuide.this,
                    "Upload failed", Toast.LENGTH_SHORT).show();

                // Mark message as failed
                messagesRef.child(messageId).child("status").setValue("failed");
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // E. Offline handling
    // ──────────────────────────────────────────────────────────────────────

    private void handleOfflineImageSend(ImageCompressor.Result result,
                                        String chatId, String messageId) {
        // Save local path in Firebase (Room DB column mediaLocalPath)
        // SyncWorker/WorkManager will retry when online

        String localPath = result.fullFile.getAbsolutePath();

        Map<String, Object> offlineUpdate = new HashMap<>();
        offlineUpdate.put("status",         "pending");
        offlineUpdate.put("mediaLocalPath",  localPath);
        messagesRef.child(messageId).updateChildren(offlineUpdate);

        // Enqueue WorkManager — will upload when network returns
        ImageUploadWorker.enqueue(this, localPath, chatId, messageId);

        Toast.makeText(this, "Will send when online", Toast.LENGTH_SHORT).show();
    }

    // ──────────────────────────────────────────────────────────────────────
    // F. In your RecyclerView Adapter — how to show image messages
    // ──────────────────────────────────────────────────────────────────────

    // In your MessageAdapter.onBindViewHolder():

    private void bindImageMessage(ImageView imageView,
                                  String thumbUrl,
                                  String fullUrl,
                                  String status) {

        if ("uploading".equals(status) || "pending".equals(status)) {
            // Show local placeholder / shimmer
            imageView.setImageResource(android.R.drawable.ic_menu_gallery);
            return;
        }

        // Progressive load: thumb instantly → full replaces
        GlideImageLoader.loadProgressive(this, thumbUrl, fullUrl, imageView);

        // Click → open full-screen viewer
        imageView.setOnClickListener(v -> openFullScreen(thumbUrl, fullUrl));
    }

    // Preload next image while user is reading current one
    private void preloadNextImage(String nextThumb, String nextFull) {
        GlideImageLoader.preload(this, nextThumb, nextFull);
    }

    // ──────────────────────────────────────────────────────────────────────
    // G. Full-screen image viewer
    // ──────────────────────────────────────────────────────────────────────

    private void openFullScreen(String thumbUrl, String fullUrl) {
        Intent intent = new Intent(this, ImageViewerActivity.class);
        intent.putExtra(ImageViewerActivity.EXTRA_THUMB_URL, thumbUrl);
        intent.putExtra(ImageViewerActivity.EXTRA_FULL_URL,  fullUrl);
        startActivity(intent);
    }
}
