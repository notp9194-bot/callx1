// ═══════════════════════════════════════════════════════════════════
// ChatActivity.java — Media Preview Integration
// Replace your existing image/video picker callbacks with these
// ═══════════════════════════════════════════════════════════════════

// ── Step 1: Add constant ─────────────────────────────────────────────
private static final int REQ_MEDIA_PREVIEW = 502;

// ── Step 2: Replace imagePicker callback ─────────────────────────────

// BEFORE (old — sends immediately without preview):
//   imagePicker = registerForActivityResult(
//       new ActivityResultContracts.GetContent(),
//       uri -> { if (uri != null) uploadAndSendImage(uri); }
//   );

// AFTER (new — opens MediaPreviewActivity first):
imagePicker = registerForActivityResult(
    new ActivityResultContracts.GetContent(),
    uri -> {
        if (uri == null) return;
        openMediaPreview(uri, MediaPreviewActivity.TYPE_IMAGE);
    }
);

// ── Step 3: Replace videoPicker callback ─────────────────────────────

videoPicker = registerForActivityResult(
    new ActivityResultContracts.GetContent(),
    uri -> {
        if (uri == null) return;
        openMediaPreview(uri, MediaPreviewActivity.TYPE_VIDEO);
    }
);

// ── Step 4: Replace cameraCapturer callback ───────────────────────────

cameraCapturer = registerForActivityResult(
    new ActivityResultContracts.TakePicture(),
    success -> {
        if (Boolean.TRUE.equals(success) && cameraOutputUri != null) {
            openMediaPreview(cameraOutputUri, MediaPreviewActivity.TYPE_IMAGE);
        }
    }
);

// ── Step 5: Add openMediaPreview() helper ─────────────────────────────

/**
 * Open MediaPreviewActivity for any media URI before sending.
 * Passes reply context if user is currently replying to a message.
 */
private void openMediaPreview(Uri uri, String type) {
    Intent i = new Intent(this, MediaPreviewActivity.class);
    i.putExtra(MediaPreviewActivity.EXTRA_URI,          uri.toString());
    i.putExtra(MediaPreviewActivity.EXTRA_TYPE,         type);
    i.putExtra(MediaPreviewActivity.EXTRA_PARTNER_NAME, partnerName);

    // Pass reply context if active
    if (replyingTo != null) {
        i.putExtra(MediaPreviewActivity.EXTRA_REPLY_TEXT,   replyingTo.text);
        i.putExtra(MediaPreviewActivity.EXTRA_REPLY_SENDER, replyingTo.senderName);
    }

    startActivityForResult(i, REQ_MEDIA_PREVIEW);
}

// ── Step 6: Handle preview result ────────────────────────────────────

// In onActivityResult() — add this case:
if (requestCode == REQ_MEDIA_PREVIEW && resultCode == RESULT_OK && data != null) {
    String uriStr     = data.getStringExtra(MediaPreviewActivity.RESULT_URI);
    String caption    = data.getStringExtra(MediaPreviewActivity.RESULT_CAPTION);
    String type       = data.getStringExtra(MediaPreviewActivity.RESULT_TYPE);
    boolean compressed = data.getBooleanExtra(MediaPreviewActivity.RESULT_COMPRESSED, true);

    if (uriStr == null) return;
    Uri mediaUri = Uri.parse(uriStr);

    // Build message with caption as text
    Message msg = buildOutgoing();
    msg.type    = type;
    msg.text    = (caption != null && !caption.isEmpty()) ? caption : null;

    String preview;
    switch (type != null ? type : "image") {
        case "video": preview = "🎬 Video" + (msg.text != null ? " — " + msg.text : ""); break;
        case "audio": preview = "🎤 Voice message"; break;
        default:      preview = "📷 Photo" + (msg.text != null ? " — " + msg.text : ""); break;
    }

    // Upload then send
    uploadAndSend(mediaUri, msg, preview);
    return;
}

// ── Step 7: uploadAndSend() helper ───────────────────────────────────

/**
 * Upload media to Cloudinary then push message to Firebase.
 * Shows progress in send button while uploading.
 */
private void uploadAndSend(Uri mediaUri, Message msg, String preview) {
    // Show upload progress
    binding.btnSendMessage.setEnabled(false);

    String resourceType = "video".equals(msg.type) ? "video"
                        : "audio".equals(msg.type) ? "raw" : "image";

    CloudinaryUploader.upload(this, mediaUri, resourceType,
        cdnUrl -> {
            // Upload success
            msg.mediaUrl = cdnUrl;
            if ("image".equals(msg.type)) msg.imageUrl = cdnUrl;

            // Push to Firebase via ViewModel (or pushMessage if not yet on ViewModel)
            runOnUiThread(() -> {
                binding.btnSendMessage.setEnabled(true);
                if (viewModel != null) {
                    viewModel.sendMessage(msg, preview);
                } else {
                    pushMessage(msg, preview);
                }
                clearReply();
            });
        },
        error -> {
            runOnUiThread(() -> {
                binding.btnSendMessage.setEnabled(true);
                // Offline — save locally for SyncWorker retry
                msg.mediaLocalPath    = mediaUri.toString();
                msg.mediaResourceType = resourceType;
                msg.status            = "pending";

                if (viewModel != null) {
                    viewModel.sendMessage(msg, preview);
                } else {
                    pushMessage(msg, preview);
                }

                // Schedule WorkManager retry
                SyncWorker.schedule(getApplicationContext());
                Snackbar.make(binding.getRoot(),
                        "Saved offline — will send when connected",
                        Snackbar.LENGTH_LONG).show();
            });
        }
    );
}

// ═══════════════════════════════════════════════════════════════════
// AndroidManifest.xml — Add MediaPreviewActivity
// (in feature-chat/src/main/AndroidManifest.xml)
// ═══════════════════════════════════════════════════════════════════

/*
<activity
    android:name="com.callx.app.activities.MediaPreviewActivity"
    android:theme="@style/Theme.AppCompat.NoActionBar"
    android:windowSoftInputMode="adjustResize"
    android:exported="false"
    android:screenOrientation="portrait" />
*/

// ═══════════════════════════════════════════════════════════════════
// Optional: Add PhotoView for pinch-zoom (image only)
// In feature-chat/build.gradle:
// ═══════════════════════════════════════════════════════════════════

/*
implementation 'com.github.chrisbanes:PhotoView:2.3.0'
// Then replace ImageView in activity_media_preview.xml with:
// <com.github.chrisbanes.photoview.PhotoView
//     android:id="@+id/iv_preview"
//     android:layout_width="match_parent"
//     android:layout_height="match_parent"/>
*/
