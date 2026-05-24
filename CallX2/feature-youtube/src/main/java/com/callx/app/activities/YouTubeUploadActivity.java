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
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeCloudinaryUtils;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.workers.YouTubeUploadNotificationWorker;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

/**
 * Video Upload with:
 * - Title / Description / Tags / Category / Visibility
 * - IsShort checkbox
 * - Cloudinary video + thumbnail upload with progress
 * - Saves to Firebase + global feed + category feed
 * - WorkManager fan-out notification to all subscribers
 * - Quick link to Schedule Upload
 */
public class YouTubeUploadActivity extends AppCompatActivity {

    private static final int REQ_PICK_VIDEO = 1001;
    private static final int REQ_PICK_THUMB = 1002;

    private Uri     videoUri, thumbUri;
    private String  uploadedVideoUrl = "", uploadedThumbUrl = "";
    private long    uploadedVideoDuration = 0;

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

        View btnBack     = findViewById(R.id.btn_yt_upload_back);
        View btnSchedule = findViewById(R.id.btn_yt_upload_schedule);
        if (btnBack     != null) btnBack.setOnClickListener(v -> finish());
        if (btnSchedule != null) btnSchedule.setOnClickListener(v ->
            startActivity(new Intent(this, YouTubeScheduleUploadActivity.class)));

        etTitle        = findViewById(R.id.et_yt_title);
        etDescription  = findViewById(R.id.et_yt_description);
        etTags         = findViewById(R.id.et_yt_tags);
        spCategory     = findViewById(R.id.sp_yt_category);
        spVisibility   = findViewById(R.id.sp_yt_visibility);
        cbIsShort      = findViewById(R.id.cb_yt_is_short);
        ivThumbPreview = findViewById(R.id.iv_yt_thumb_preview);
        btnUpload      = findViewById(R.id.btn_yt_upload_submit);
        progressBar    = findViewById(R.id.pb_yt_upload);
        btnPickVideo   = findViewById(R.id.btn_yt_pick_video);
        btnPickThumb   = findViewById(R.id.btn_yt_pick_thumb);

        if (btnPickVideo != null)
            btnPickVideo.setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_GET_CONTENT).setType("video/*"), REQ_PICK_VIDEO));
        if (btnPickThumb != null)
            btnPickThumb.setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK_THUMB));
        if (btnUpload != null)
            btnUpload.setOnClickListener(v -> startUpload());

        loadChannelInfo(user);
    }

    private void loadChannelInfo(com.google.firebase.auth.FirebaseUser user) {
        YouTubeFirebaseUtils.channelRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    myName     = snap.child("channelName").getValue(String.class);
                    myPhotoUrl = snap.child("photoUrl").getValue(String.class);
                    if (myName == null) myName = user.getDisplayName() != null
                        ? user.getDisplayName() : "Creator";
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    myName     = user.getDisplayName() != null ? user.getDisplayName() : "Creator";
                    myPhotoUrl = user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;
                }
            });
    }

    @Override protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_PICK_VIDEO) {
            videoUri = data.getData();
            Toast.makeText(this, "Video selected ✓", Toast.LENGTH_SHORT).show();
        } else if (req == REQ_PICK_THUMB) {
            thumbUri = data.getData();
            if (ivThumbPreview != null) Glide.with(this).load(thumbUri).into(ivThumbPreview);
        }
    }

    private void startUpload() {
        String title = etTitle != null ? etTitle.getText().toString().trim() : "";
        if (title.isEmpty()) { if (etTitle != null) etTitle.setError("Title required"); return; }
        if (videoUri == null) {
            Toast.makeText(this, "Pick a video first", Toast.LENGTH_SHORT).show(); return; }

        setLoading(true);
        if (progressBar != null) { progressBar.setMax(100); progressBar.setProgress(0); }

        YouTubeCloudinaryUtils.uploadVideo(this, videoUri, myUid,
            new YouTubeCloudinaryUtils.UploadCallback() {
                @Override public void onProgress(int p) {
                    if (progressBar != null) progressBar.setProgress(p / 2);
                }
                @Override public void onSuccess(String url, String pid, long durationSecs) {
                    uploadedVideoUrl      = url;
                    uploadedVideoDuration = durationSecs;
                    if (thumbUri != null) uploadThumbnail();
                    else saveToFirebase();
                }
                @Override public void onError(String err) {
                    setLoading(false);
                    Toast.makeText(YouTubeUploadActivity.this,
                        "Upload failed: " + err, Toast.LENGTH_LONG).show();
                }
            });
    }

    private void uploadThumbnail() {
        YouTubeCloudinaryUtils.uploadImage(this, thumbUri, myUid + "/thumbs",
            new YouTubeCloudinaryUtils.UploadCallback() {
                @Override public void onProgress(int p) {
                    if (progressBar != null) progressBar.setProgress(50 + p / 2);
                }
                @Override public void onSuccess(String url, String pid, long d) {
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
        String title    = etTitle       != null ? etTitle.getText().toString().trim()       : "";
        String desc     = etDescription != null ? etDescription.getText().toString().trim() : "";
        String tags     = etTags        != null ? etTags.getText().toString().trim()        : "";
        String cat      = spCategory    != null ? spCategory.getSelectedItem().toString()    : "General";
        String vis      = spVisibility  != null ? spVisibility.getSelectedItem().toString()  : "public";
        boolean isShort = cbIsShort     != null && cbIsShort.isChecked();

        String videoId = YouTubeFirebaseUtils.videosRef().push().getKey();
        if (videoId == null) { setLoading(false); return; }

        YouTubeVideo video = new YouTubeVideo(
            videoId, myUid, myName, myPhotoUrl,
            title, desc, uploadedVideoUrl, uploadedThumbUrl.isEmpty() ? null : uploadedThumbUrl,
            cat, uploadedVideoDuration, System.currentTimeMillis(), isShort);
        video.tags       = tags;
        video.visibility = vis.toLowerCase();

        YouTubeFirebaseUtils.videoRef(videoId).setValue(video)
            .addOnSuccessListener(v -> {
                // Write to all relevant feeds
                YouTubeFirebaseUtils.globalFeedRef().child(videoId).setValue(video);
                YouTubeFirebaseUtils.userVideosRef(myUid).child(videoId)
                    .setValue(System.currentTimeMillis());
                if (!cat.isEmpty())
                    YouTubeFirebaseUtils.categoryFeedRef(cat).child(videoId).setValue(video);
                if (isShort)
                    YouTubeFirebaseUtils.userShortsRef(myUid).child(videoId)
                        .setValue(System.currentTimeMillis());

                // Update channel stats
                YouTubeFirebaseUtils.channelRef(myUid).child("videoCount")
                    .setValue(ServerValue.increment(1));

                // Fan-out notifications via WorkManager
                Data workerData = new Data.Builder()
                    .putString("video_id",      videoId)
                    .putString("uploader_uid",  myUid)
                    .putString("uploader_name", myName)
                    .putString("video_title",   title)
                    .putString("thumbnail_url", uploadedThumbUrl)
                    .build();
                WorkManager.getInstance(YouTubeUploadActivity.this)
                    .enqueue(new OneTimeWorkRequest.Builder(
                        YouTubeUploadNotificationWorker.class)
                        .setInputData(workerData).build());

                setLoading(false);
                Toast.makeText(YouTubeUploadActivity.this,
                    "Video published!", Toast.LENGTH_SHORT).show();
                finish();
            })
            .addOnFailureListener(e -> {
                setLoading(false);
                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
    }

    private void setLoading(boolean loading) {
        if (btnUpload   != null) btnUpload.setEnabled(!loading);
        if (progressBar != null) progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
