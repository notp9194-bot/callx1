package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeCloudinaryUtils;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;

public class YouTubeUploadActivity extends AppCompatActivity {

    private static final int REQ_PICK_VIDEO = 1001;
    private static final int REQ_PICK_THUMB = 1002;

    private Uri     videoUri, thumbUri;
    private String  uploadedVideoUrl, uploadedThumbUrl;
    private long    uploadedVideoDuration = 0; // seconds, from Cloudinary response

    private EditText  etTitle, etDescription, etTags;
    private Spinner   spCategory, spVisibility;
    private CheckBox  cbIsShort;
    private ImageView ivThumbPreview;
    private Button    btnUpload;
    private ProgressBar progressBar;
    private View      btnPickVideo, btnPickThumb;

    private String myUid, myName, myPhotoUrl;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_upload);

        var user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { finish(); return; }
        myUid = user.getUid();

        View btnBack = findViewById(R.id.btn_yt_upload_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        etTitle       = findViewById(R.id.et_yt_title);
        etDescription = findViewById(R.id.et_yt_description);
        etTags        = findViewById(R.id.et_yt_tags);
        spCategory    = findViewById(R.id.sp_yt_category);
        spVisibility  = findViewById(R.id.sp_yt_visibility);
        cbIsShort     = findViewById(R.id.cb_yt_is_short);
        ivThumbPreview= findViewById(R.id.iv_yt_thumb_preview);
        btnUpload     = findViewById(R.id.btn_yt_upload_submit);
        progressBar   = findViewById(R.id.pb_yt_upload);
        btnPickVideo  = findViewById(R.id.btn_yt_pick_video);
        btnPickThumb  = findViewById(R.id.btn_yt_pick_thumb);

        btnPickVideo.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("video/*");
            startActivityForResult(i, REQ_PICK_VIDEO);
        });

        btnPickThumb.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
            startActivityForResult(i, REQ_PICK_THUMB);
        });

        btnUpload.setOnClickListener(v -> startUpload());

        // Load channel info
        YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
            new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    myName     = snap.child("channelName").getValue(String.class);
                    myPhotoUrl = snap.child("photoUrl").getValue(String.class);
                    if (myName == null) myName = user.getDisplayName() != null
                        ? user.getDisplayName() : "Creator";
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });
    }

    @Override protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_PICK_VIDEO) {
            videoUri = data.getData();
            Toast.makeText(this, "Video selected", Toast.LENGTH_SHORT).show();
        } else if (req == REQ_PICK_THUMB) {
            thumbUri = data.getData();
            Glide.with(this).load(thumbUri).into(ivThumbPreview);
        }
    }

    private void startUpload() {
        String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) { etTitle.setError("Title required"); return; }
        if (videoUri == null) { Toast.makeText(this, "Pick a video first", Toast.LENGTH_SHORT).show(); return; }

        setLoading(true);
        progressBar.setMax(100);
        progressBar.setProgress(0);

        // Step 1: Compress + Upload video (progress 0–100% handled inside utils)
        YouTubeCloudinaryUtils.uploadVideo(this, videoUri, myUid,
            new YouTubeCloudinaryUtils.UploadCallback() {
                @Override public void onProgress(int p) {
                    progressBar.setProgress(p / 2); // video = first half
                }
                @Override public void onSuccess(String url, String pid, long durationSecs) {
                    uploadedVideoUrl = url;
                    uploadedVideoDuration = durationSecs;
                    if (thumbUri != null) uploadThumbnail();
                    else saveToFirebase();
                }
                @Override public void onError(String err) {
                    setLoading(false);
                    Toast.makeText(YouTubeUploadActivity.this,
                        "Video upload failed: " + err, Toast.LENGTH_LONG).show();
                }
            });
    }

    private void uploadThumbnail() {
        YouTubeCloudinaryUtils.uploadImage(this, thumbUri, myUid + "/thumbs",
            new YouTubeCloudinaryUtils.UploadCallback() {
                @Override public void onProgress(int p) { progressBar.setProgress(50 + p / 2); } // thumb = second half
                @Override public void onSuccess(String url, String pid, long durationSecs) {
                    uploadedThumbUrl = url;
                    saveToFirebase();
                }
                @Override public void onError(String err) {
                    uploadedThumbUrl = "";
                    saveToFirebase();
                }
            });
    }

    private void saveToFirebase() {
        String title = etTitle.getText().toString().trim();
        String desc  = etDescription.getText().toString().trim();
        String tags  = etTags != null ? etTags.getText().toString().trim() : "";
        String cat   = spCategory != null ? spCategory.getSelectedItem().toString() : "General";
        String vis   = spVisibility != null ? spVisibility.getSelectedItem().toString() : "public";
        boolean isShort = cbIsShort != null && cbIsShort.isChecked();

        DatabaseReference ref = YouTubeFirebaseUtils.videosRef().push();
        String videoId = ref.getKey();
        if (videoId == null) { setLoading(false); return; }

        YouTubeVideo video = new YouTubeVideo(
            videoId, myUid, myName, myPhotoUrl,
            title, desc, uploadedVideoUrl, uploadedThumbUrl,
            cat, uploadedVideoDuration, System.currentTimeMillis(), isShort);
        video.tags       = tags;
        video.visibility = vis.toLowerCase();

        ref.setValue(video).addOnSuccessListener(v2 -> {
            // Push to global feed and user's video list
            YouTubeFirebaseUtils.globalFeedRef().child(videoId)
                .setValue(video);
            YouTubeFirebaseUtils.userVideosRef(myUid).child(videoId)
                .setValue(System.currentTimeMillis());
            if (isShort)
                YouTubeFirebaseUtils.userShortsRef(myUid).child(videoId)
                    .setValue(System.currentTimeMillis());

            // Update channel video count
            YouTubeFirebaseUtils.channelRef(myUid).child("videoCount")
                .setValue(com.google.firebase.database.ServerValue.increment(1));

            // Notify subscribers
            notifySubscribers(videoId, title);

            setLoading(false);
            Toast.makeText(this, "Video uploaded successfully!", Toast.LENGTH_SHORT).show();
            finish();
        }).addOnFailureListener(e -> {
            setLoading(false);
            Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void notifySubscribers(String videoId, String videoTitle) {
        YouTubeFirebaseUtils.subscribersRef(myUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    for (com.google.firebase.database.DataSnapshot ds : snap.getChildren()) {
                        String subUid = ds.getKey();
                        if (subUid == null) continue;
                        String nKey = YouTubeFirebaseUtils.notificationsRef(subUid).push().getKey();
                        if (nKey == null) continue;
                        com.callx.app.models.YouTubeNotification n =
                            new com.callx.app.models.YouTubeNotification(
                                nKey, subUid, myUid, myName, myPhotoUrl,
                                "new_video", videoId, videoTitle, uploadedThumbUrl);
                        YouTubeFirebaseUtils.notificationsRef(subUid).child(nKey).setValue(n);
                    }
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });
    }

    private void setLoading(boolean loading) {
        btnUpload.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
