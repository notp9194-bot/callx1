package com.callx.app.activities;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * Schedule a video upload for a future date/time.
 * - Pick video file
 * - Set title, description, tags, category
 * - Pick publish date + time
 * - Upload to Cloudinary in background
 * - Saves to Firebase with visibility="scheduled" + scheduledAt timestamp
 */
public class YouTubeScheduleUploadActivity extends AppCompatActivity {

    private EditText   etTitle, etDescription, etTags, etCategory;
    private TextView   tvPickedDate, tvPickedVideo;
    private View       btnPickVideo, btnPickDate, btnSchedule;
    private Uri        selectedVideoUri;
    private Calendar   scheduledCal = Calendar.getInstance();
    private String     myUid, myName, myPhoto;

    private ActivityResultLauncher<String> videoPicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                selectedVideoUri = uri;
                if (tvPickedVideo != null) tvPickedVideo.setText("Video selected ✓");
            }
        });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_schedule_upload);

        var user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            myUid   = user.getUid();
            myName  = user.getDisplayName() != null ? user.getDisplayName() : "Channel";
            myPhoto = user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;
        } else { myUid = ""; myName = "Channel"; myPhoto = null; }

        View btnBack = findViewById(R.id.btn_yt_schedule_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        etTitle       = findViewById(R.id.et_yt_schedule_title);
        etDescription = findViewById(R.id.et_yt_schedule_desc);
        etTags        = findViewById(R.id.et_yt_schedule_tags);
        etCategory    = findViewById(R.id.et_yt_schedule_category);
        tvPickedDate  = findViewById(R.id.tv_yt_schedule_date);
        tvPickedVideo = findViewById(R.id.tv_yt_schedule_video);
        btnPickVideo  = findViewById(R.id.btn_yt_pick_video_schedule);
        btnPickDate   = findViewById(R.id.btn_yt_pick_date);
        btnSchedule   = findViewById(R.id.btn_yt_schedule_confirm);

        updateDateLabel();

        if (btnPickVideo != null) btnPickVideo.setOnClickListener(v -> videoPicker.launch("video/*"));
        if (btnPickDate  != null) btnPickDate.setOnClickListener(v  -> pickDateTime());
        if (btnSchedule  != null) btnSchedule.setOnClickListener(v  -> scheduleUpload());
    }

    private void pickDateTime() {
        new DatePickerDialog(this, (view, y, m, d) -> {
            scheduledCal.set(y, m, d);
            new TimePickerDialog(this, (tv, hour, min) -> {
                scheduledCal.set(Calendar.HOUR_OF_DAY, hour);
                scheduledCal.set(Calendar.MINUTE, min);
                updateDateLabel();
            }, scheduledCal.get(Calendar.HOUR_OF_DAY),
               scheduledCal.get(Calendar.MINUTE), true).show();
        }, scheduledCal.get(Calendar.YEAR),
           scheduledCal.get(Calendar.MONTH),
           scheduledCal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateDateLabel() {
        if (tvPickedDate == null) return;
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
        tvPickedDate.setText("Scheduled: " + sdf.format(scheduledCal.getTime()));
    }

    private void scheduleUpload() {
        String title = etTitle != null ? etTitle.getText().toString().trim() : "";
        if (title.isEmpty()) {
            Toast.makeText(this, "Enter a title", Toast.LENGTH_SHORT).show(); return; }
        if (selectedVideoUri == null) {
            Toast.makeText(this, "Pick a video first", Toast.LENGTH_SHORT).show(); return; }
        if (scheduledCal.getTimeInMillis() <= System.currentTimeMillis()) {
            Toast.makeText(this, "Schedule time must be in the future",
                Toast.LENGTH_SHORT).show(); return; }

        Toast.makeText(this, "Uploading video...", Toast.LENGTH_SHORT).show();

        MediaManager.get().upload(selectedVideoUri)
            .unsigned("callx_unsigned")
            .option("resource_type", "video")
            .callback(new UploadCallback() {
                @Override public void onStart(String reqId) {}
                @Override public void onProgress(String reqId, long bytes, long total) {}
                @Override public void onSuccess(String reqId, Map res) {
                    String videoUrl = (String) res.get("secure_url");
                    saveScheduledVideo(title, videoUrl);
                }
                @Override public void onError(String reqId, ErrorInfo err) {
                    runOnUiThread(() -> Toast.makeText(YouTubeScheduleUploadActivity.this,
                        "Upload error: " + err.getDescription(), Toast.LENGTH_LONG).show());
                }
                @Override public void onReschedule(String reqId, ErrorInfo err) {}
            }).dispatch();
    }

    private void saveScheduledVideo(String title, String videoUrl) {
        if (myUid.isEmpty()) return;
        String vidId = YouTubeFirebaseUtils.videosRef().push().getKey();
        if (vidId == null) return;

        String desc  = etDescription != null ? etDescription.getText().toString().trim() : "";
        String tags  = etTags        != null ? etTags.getText().toString().trim() : "";
        String cat   = etCategory    != null ? etCategory.getText().toString().trim() : "";

        YouTubeVideo v = new YouTubeVideo(vidId, myUid, myName, myPhoto, title, desc,
            videoUrl, null, cat, 0, System.currentTimeMillis(), false);
        v.visibility   = "scheduled";
        v.scheduledAt  = scheduledCal.getTimeInMillis();
        v.tags         = tags;

        YouTubeFirebaseUtils.videoRef(vidId).setValue(v);
        YouTubeFirebaseUtils.userVideosRef(myUid).child(vidId)
            .setValue(System.currentTimeMillis());
        YouTubeFirebaseUtils.videoScheduledRef(myUid).child(vidId)
            .setValue(scheduledCal.getTimeInMillis());

        runOnUiThread(() -> {
            Toast.makeText(this, "Video scheduled for "
                + new java.text.SimpleDateFormat("MMM dd, HH:mm",
                    java.util.Locale.getDefault()).format(scheduledCal.getTime()),
                Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
