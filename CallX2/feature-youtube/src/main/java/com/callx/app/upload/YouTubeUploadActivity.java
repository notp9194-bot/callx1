package com.callx.app.upload;

import com.callx.app.models.YouTubeNotification;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
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

    private static final String TAG = "YT_UPLOAD_DEBUG";

    private static final int REQ_PICK_VIDEO = 1001;
    private static final int REQ_PICK_THUMB = 1002;

    private Uri     videoUri, thumbUri;
    private String  uploadedVideoUrl, uploadedThumbUrl;
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
        if (user == null) {
            Log.e(TAG, "❌ User not logged in — finishing activity");
            Toast.makeText(this, "❌ User logged in nahi hai!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        myUid = user.getUid();
        Log.d(TAG, "✅ onCreate — myUid: " + myUid);

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
            Log.d(TAG, "Video picker open kiya");
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("video/*");
            startActivityForResult(i, REQ_PICK_VIDEO);
        });

        btnPickThumb.setOnClickListener(v -> {
            Log.d(TAG, "Thumbnail picker open kiya");
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
            startActivityForResult(i, REQ_PICK_THUMB);
        });

        btnUpload.setOnClickListener(v -> startUpload());

        // Channel info load
        Log.d(TAG, "Channel info load kar raha hai Firebase se...");
        YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
            new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    myName     = snap.child("channelName").getValue(String.class);
                    myPhotoUrl = snap.child("photoUrl").getValue(String.class);
                    if (myName == null) myName = user.getDisplayName() != null
                        ? user.getDisplayName() : "Creator";
                    Log.d(TAG, "Channel info mila: name=" + myName + ", photo=" + myPhotoUrl);
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    Log.e(TAG, "❌ Channel info load fail: " + e.getMessage());
                }
            });
    }

    @Override protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) {
            Log.w(TAG, "onActivityResult: cancelled ya null — req=" + req);
            return;
        }
        if (req == REQ_PICK_VIDEO) {
            videoUri = data.getData();
            Log.d(TAG, "✅ Video URI select hua: " + videoUri);
            Toast.makeText(this, "✅ Video select hua:\n" + videoUri, Toast.LENGTH_SHORT).show();
        } else if (req == REQ_PICK_THUMB) {
            thumbUri = data.getData();
            Log.d(TAG, "✅ Thumbnail URI select hua: " + thumbUri);
            Toast.makeText(this, "✅ Thumbnail select hua", Toast.LENGTH_SHORT).show();
            Glide.with(this).load(thumbUri).override(720, 720).into(ivThumbPreview);
        }
    }

    private void startUpload() {
        String title = etTitle.getText().toString().trim();
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG, "▶ startUpload() CALLED");
        Log.d(TAG, "  title    : " + title);
        Log.d(TAG, "  videoUri : " + videoUri);
        Log.d(TAG, "  thumbUri : " + thumbUri);

        if (title.isEmpty()) {
            etTitle.setError("Title required");
            Log.w(TAG, "⚠️ Title empty — upload rok diya");
            return;
        }
        if (videoUri == null) {
            Toast.makeText(this, "⚠️ Pehle video pick karo!", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "⚠️ videoUri null — upload rok diya");
            return;
        }

        setLoading(true);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        Toast.makeText(this, "📤 Upload process shuru...\nVideo: " + videoUri, Toast.LENGTH_LONG).show();
        Log.d(TAG, "uploadVideo() call kar raha hai...");

        YouTubeCloudinaryUtils.uploadVideo(this, videoUri, myUid,
            new YouTubeCloudinaryUtils.UploadCallback() {
                @Override public void onProgress(int p) {
                    progressBar.setProgress(p / 2);
                    Log.v(TAG, "Video upload progress: " + p + "% (bar: " + (p/2) + "%)");
                }
                @Override public void onSuccess(String url, String pid, long durationSecs) {
                    uploadedVideoUrl      = url;
                    uploadedVideoDuration = durationSecs;
                    Log.d(TAG, "✅ Video upload success!");
                    Log.d(TAG, "  URL      : " + url);
                    Log.d(TAG, "  PublicID : " + pid);
                    Log.d(TAG, "  Duration : " + durationSecs + "s");
                    Toast.makeText(YouTubeUploadActivity.this,
                        "✅ Video Cloudinary pe gaya!\nURL: " + url, Toast.LENGTH_LONG).show();

                    if (thumbUri != null) {
                        Log.d(TAG, "Thumbnail bhi upload kar raha hai...");
                        uploadThumbnail();
                    } else {
                        Log.d(TAG, "Koi thumbnail nahi — seedha Firebase save...");
                        saveToFirebase();
                    }
                }
                @Override public void onError(String err) {
                    setLoading(false);
                    Log.e(TAG, "❌ Video upload FAILED: " + err);
                    Toast.makeText(YouTubeUploadActivity.this,
                        "❌ Video upload fail:\n" + err, Toast.LENGTH_LONG).show();
                }
            });
    }

    private void uploadThumbnail() {
        Log.d(TAG, "uploadThumbnail() call kar raha hai...");
        YouTubeCloudinaryUtils.uploadImage(this, thumbUri, myUid + "/thumbs",
            new YouTubeCloudinaryUtils.UploadCallback() {
                @Override public void onProgress(int p) {
                    progressBar.setProgress(50 + p / 2);
                }
                @Override public void onSuccess(String url, String pid, long durationSecs) {
                    uploadedThumbUrl = url;
                    Log.d(TAG, "✅ Thumbnail upload success: " + url);
                    Toast.makeText(YouTubeUploadActivity.this,
                        "✅ Thumbnail upload hua!\n" + url, Toast.LENGTH_SHORT).show();
                    saveToFirebase();
                }
                @Override public void onError(String err) {
                    Log.w(TAG, "⚠️ Thumbnail upload fail (ignore kar raha hai): " + err);
                    uploadedThumbUrl = "";
                    Toast.makeText(YouTubeUploadActivity.this,
                        "⚠️ Thumbnail fail (video phir bhi save hoga):\n" + err, Toast.LENGTH_LONG).show();
                    saveToFirebase();
                }
            });
    }

    private void saveToFirebase() {
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG, "▶ saveToFirebase() CALLED");
        Log.d(TAG, "  uploadedVideoUrl  : " + uploadedVideoUrl);
        Log.d(TAG, "  uploadedThumbUrl  : " + uploadedThumbUrl);
        Log.d(TAG, "  duration          : " + uploadedVideoDuration + "s");

        if (uploadedVideoUrl == null || uploadedVideoUrl.trim().isEmpty()) {
            setLoading(false);
            String msg = "❌ Firebase save rok diya — videoUrl null/empty hai.\nCloudinary upload check karo.";
            Log.e(TAG, msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }

        String title    = etTitle.getText().toString().trim();
        String desc     = etDescription.getText().toString().trim();
        String tags     = etTags != null ? etTags.getText().toString().trim() : "";
        String cat      = spCategory != null ? spCategory.getSelectedItem().toString() : "General";
        String vis      = spVisibility != null ? spVisibility.getSelectedItem().toString() : "public";
        boolean isShort = cbIsShort != null && cbIsShort.isChecked();

        Log.d(TAG, "  title    : " + title);
        Log.d(TAG, "  category : " + cat);
        Log.d(TAG, "  visibility: " + vis);
        Log.d(TAG, "  isShort  : " + isShort);

        DatabaseReference ref = YouTubeFirebaseUtils.videosRef().push();
        String videoId = ref.getKey();
        if (videoId == null) {
            setLoading(false);
            Log.e(TAG, "❌ Firebase push() se null key mila");
            Toast.makeText(this, "❌ Firebase key generate nahi hua!", Toast.LENGTH_LONG).show();
            return;
        }

        Log.d(TAG, "  videoId (Firebase key): " + videoId);
        Toast.makeText(this, "💾 Firebase me save ho raha hai...\nID: " + videoId, Toast.LENGTH_SHORT).show();

        YouTubeVideo video = new YouTubeVideo(
            videoId, myUid, myName, myPhotoUrl,
            title, desc, uploadedVideoUrl, uploadedThumbUrl,
            cat, uploadedVideoDuration, System.currentTimeMillis(), isShort);
        video.tags       = tags;
        video.visibility = vis.toLowerCase();

        ref.setValue(video).addOnSuccessListener(v2 -> {
            Log.d(TAG, "✅ Firebase save SUCCESS — videoId: " + videoId);
            Log.d(TAG, "   Pushing to globalFeed + userVideos...");

            YouTubeFirebaseUtils.globalFeedRef().child(videoId).setValue(video);
            YouTubeFirebaseUtils.userVideosRef(myUid).child(videoId)
                .setValue(System.currentTimeMillis());
            if (isShort)
                YouTubeFirebaseUtils.userShortsRef(myUid).child(videoId)
                    .setValue(System.currentTimeMillis());

            YouTubeFirebaseUtils.channelRef(myUid).child("videoCount")
                .setValue(com.google.firebase.database.ServerValue.increment(1));

            notifySubscribers(videoId, title);

            setLoading(false);
            Log.d(TAG, "✅✅ SAB KUCH COMPLETE — Video live hai! ID: " + videoId);
            Toast.makeText(this,
                "✅✅ Video upload & save COMPLETE!\nVideo ID: " + videoId,
                Toast.LENGTH_LONG).show();
            finish();
        }).addOnFailureListener(e -> {
            setLoading(false);
            Log.e(TAG, "❌ Firebase setValue FAIL: " + e.getMessage(), e);
            Toast.makeText(this,
                "❌ Firebase save FAIL:\n" + e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void notifySubscribers(String videoId, String videoTitle) {
        Log.d(TAG, "Subscribers ko notify kar raha hai...");
        YouTubeFirebaseUtils.subscribersRef(myUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    long subCount = snap.getChildrenCount();
                    Log.d(TAG, "Subscribers count: " + subCount);
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
                    Log.d(TAG, "✅ " + subCount + " subscribers ko notify kiya");
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    Log.e(TAG, "❌ Subscriber notify fail: " + e.getMessage());
                }
            });
    }

    private void setLoading(boolean loading) {
        btnUpload.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
