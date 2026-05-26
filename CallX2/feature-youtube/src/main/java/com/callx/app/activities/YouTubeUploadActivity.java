package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeChannel;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.Constants;
import com.callx.app.utils.YouTubeCloudinaryUtils;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.concurrent.Executors;
import okhttp3.*;

/**
 * Production video/short upload activity.
 * Cloudinary upload → Firebase RTDB → globalFeedRef + userVideosRef + category feed.
 * Notifies all subscribers via /notify/youtube endpoint.
 */
public class YouTubeUploadActivity extends AppCompatActivity {

    private Uri      videoUri, thumbUri;
    private EditText etTitle, etDescription, etTags, etLocation;
    private Spinner  spCategory, spVisibility;
    private CheckBox cbIsShort;
    private ImageView ivThumbPreview;
    private ProgressBar pbUpload;
    private TextView tvUploadProgress;
    private Button   btnSelectVideo, btnSelectThumb, btnUpload;
    private String   myUid, myName, myPhoto;

    private final ActivityResultLauncher<String> pickVideo =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                videoUri = uri;
                btnUpload.setEnabled(true);
                if (btnSelectVideo != null) btnSelectVideo.setText("Video Selected");
            }
        });

    private final ActivityResultLauncher<String> pickThumb =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                thumbUri = uri;
                if (ivThumbPreview != null)
                    Glide.with(this).load(uri).centerCrop().into(ivThumbPreview);
            }
        });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_upload);

        var user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { finish(); return; }
        myUid = user.getUid();

        View btnBack = findViewById(R.id.btn_yt_upload_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        etTitle        = findViewById(R.id.et_yt_upload_title);
        etDescription  = findViewById(R.id.et_yt_upload_description);
        etTags         = findViewById(R.id.et_yt_upload_tags);
        etLocation     = findViewById(R.id.et_yt_upload_location);
        spCategory     = findViewById(R.id.sp_yt_upload_category);
        spVisibility   = findViewById(R.id.sp_yt_upload_visibility);
        cbIsShort      = findViewById(R.id.cb_yt_is_short);
        ivThumbPreview = findViewById(R.id.iv_yt_upload_thumb_preview);
        pbUpload       = findViewById(R.id.pb_yt_upload);
        tvUploadProgress = findViewById(R.id.tv_yt_upload_progress);
        btnSelectVideo = findViewById(R.id.btn_yt_select_video);
        btnSelectThumb = findViewById(R.id.btn_yt_select_thumb);
        btnUpload      = findViewById(R.id.btn_yt_upload);

        String[] categories = {"Music","Gaming","News","Sports","Movies","Tech","Education","Comedy","Travel","Food","Fashion","Other"};
        String[] visibilities = {"public","unlisted","private"};
        if (spCategory   != null) spCategory.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, categories));
        if (spVisibility != null) spVisibility.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, visibilities));

        if (btnSelectVideo != null) btnSelectVideo.setOnClickListener(v -> pickVideo.launch("video/*"));
        if (btnSelectThumb != null) btnSelectThumb.setOnClickListener(v -> pickThumb.launch("image/*"));
        if (btnUpload      != null) {
            btnUpload.setEnabled(false);
            btnUpload.setOnClickListener(v -> startUpload());
        }

        loadMyInfo();
    }

    private void loadMyInfo() {
        YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    myName  = snap.child("channelName").getValue(String.class);
                    myPhoto = snap.child("photoUrl").getValue(String.class);
                    if (myName == null) myName = FirebaseAuth.getInstance().getCurrentUser() != null
                        ? FirebaseAuth.getInstance().getCurrentUser().getDisplayName() : "Creator";
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void startUpload() {
        String title = etTitle != null ? etTitle.getText().toString().trim() : "";
        if (title.isEmpty()) { if (etTitle != null) etTitle.setError("Title required"); return; }
        if (videoUri == null) { Toast.makeText(this, "Select a video first", Toast.LENGTH_SHORT).show(); return; }

        setUploading(true);
        String category   = spCategory   != null ? (String) spCategory.getSelectedItem()   : "Other";
        String visibility = spVisibility != null ? (String) spVisibility.getSelectedItem() : "public";

        // 1. Upload thumbnail if selected, then upload video
        if (thumbUri != null) {
            YouTubeCloudinaryUtils.uploadImage(this, thumbUri, myUid + "/thumbs/" + System.currentTimeMillis(),
                new YouTubeCloudinaryUtils.UploadCallback() {
                    @Override public void onProgress(int p) { updateProgress("Thumbnail: " + p + "%"); }
                    @Override public void onSuccess(String thumbUrl, String pid, long d) {
                        uploadVideo(title, thumbUrl, category, visibility);
                    }
                    @Override public void onError(String e) { uploadVideo(title, null, category, visibility); }
                });
        } else {
            uploadVideo(title, null, category, visibility);
        }
    }

    private void uploadVideo(String title, String thumbUrl, String category, String visibility) {
        String desc     = etDescription != null ? etDescription.getText().toString().trim() : "";
        String tags     = etTags        != null ? etTags.getText().toString().trim() : "";
        String location = etLocation    != null ? etLocation.getText().toString().trim() : "";
        boolean isShort = cbIsShort     != null && cbIsShort.isChecked();

        YouTubeCloudinaryUtils.uploadVideo(this, videoUri, myUid + "/videos",
            new YouTubeCloudinaryUtils.UploadCallback() {
                @Override public void onProgress(int p) { updateProgress("Video: " + p + "%"); }
                @Override public void onSuccess(String videoUrl, String publicId, long durationSecs) {
                    saveToFirebase(title, desc, tags, location, videoUrl, thumbUrl,
                        category, visibility, isShort, durationSecs);
                }
                @Override public void onError(String e) {
                    setUploading(false);
                    Toast.makeText(YouTubeUploadActivity.this,
                        "Upload failed: " + e, Toast.LENGTH_LONG).show();
                }
            });
    }

    private void saveToFirebase(String title, String desc, String tags, String location,
                                String videoUrl, String thumbUrl, String category,
                                String visibility, boolean isShort, long durationSecs) {
        String videoId = YouTubeFirebaseUtils.videosRef().push().getKey();
        if (videoId == null) { setUploading(false); return; }

        long now = System.currentTimeMillis();
        YouTubeVideo video = new YouTubeVideo(videoId, myUid, myName, myPhoto,
            title, desc, videoUrl, thumbUrl, category, durationSecs, now, isShort);
        video.tags        = tags;
        video.location    = location;
        video.visibility  = visibility;
        video.computeTrendingScore();

        YouTubeFirebaseUtils.videoRef(videoId).setValue(video);
        if ("public".equals(visibility)) {
            YouTubeFirebaseUtils.globalFeedRef().child(videoId).setValue(video);
            YouTubeFirebaseUtils.categoryFeedRef(category).child(videoId).setValue(video);
        }
        if (isShort) YouTubeFirebaseUtils.userShortsRef(myUid).child(videoId).setValue(now);
        else         YouTubeFirebaseUtils.userVideosRef(myUid).child(videoId).setValue(now);

        // Update channel stats
        YouTubeFirebaseUtils.channelRef(myUid).child("videoCount").setValue(ServerValue.increment(1));

        // Notify subscribers
        if ("public".equals(visibility)) notifySubscribers(video);

        updateProgress("Done!");
        setUploading(false);
        Toast.makeText(this, "Video uploaded!", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void notifySubscribers(YouTubeVideo video) {
        YouTubeFirebaseUtils.subscribersRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren()) {
                        String subUid = ds.getKey();
                        if (subUid == null) continue;
                        String nKey = YouTubeFirebaseUtils.notificationsRef(subUid).push().getKey();
                        if (nKey == null) continue;
                        com.callx.app.models.YouTubeNotification n =
                            new com.callx.app.models.YouTubeNotification(nKey, subUid,
                                myUid, myName, myPhoto, "new_video",
                                video.videoId, video.title, video.thumbnailUrl);
                        YouTubeFirebaseUtils.notificationsRef(subUid).child(nKey).setValue(n);
                    }
                    // Batch FCM push
                    Executors.newSingleThreadExecutor().execute(() -> {
                        try {
                            String json = "{\"uploaderUid\":\"" + myUid + "\","
                                + "\"uploaderName\":\"" + esc(myName) + "\","
                                + "\"videoId\":\"" + video.videoId + "\","
                                + "\"videoTitle\":\"" + esc(video.title) + "\","
                                + "\"type\":\"new_video\"}";
                            new OkHttpClient().newCall(new Request.Builder()
                                .url(Constants.SERVER_URL + "/notify/youtube")
                                .post(RequestBody.create(json, MediaType.parse("application/json")))
                                .build()).execute().close();
                        } catch (Exception ignored) {}
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void setUploading(boolean uploading) {
        runOnUiThread(() -> {
            if (pbUpload   != null) pbUpload.setVisibility(uploading ? View.VISIBLE : View.GONE);
            if (btnUpload  != null) btnUpload.setEnabled(!uploading);
            if (tvUploadProgress != null) tvUploadProgress.setVisibility(uploading ? View.VISIBLE : View.GONE);
        });
    }

    private void updateProgress(String msg) {
        runOnUiThread(() -> {
            if (tvUploadProgress != null) tvUploadProgress.setText(msg);
        });
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
    }
}
