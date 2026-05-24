package com.callx.app.activities;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
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
import com.callx.app.utils.YouTubeCloudinaryUtils;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Schedule a video upload for a future date/time.
 * - Pick video file
 * - Set title, description, tags, category
 * - Pick publish date + time
 * - Upload via YouTubeCloudinaryUtils (project's own util, no sdk dependency)
 * - Saves to Firebase with visibility="scheduled" + scheduledAt timestamp
 */
public class YouTubeScheduleUploadActivity extends AppCompatActivity {

    private EditText  etTitle, etDescription, etTags, etCategory;
    private TextView  tvPickedDate, tvPickedVideo;
    private View      btnPickVideo, btnPickDate, btnSchedule, progressView;
    private Uri       selectedVideoUri;
    private Calendar  scheduledCal = Calendar.getInstance();
    private String    myUid, myName, myPhoto;

    private final ActivityResultLauncher<String> videoPicker =
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
        progressView  = findViewById(R.id.pb_yt_schedule);

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

        setLoading(true);
        Toast.makeText(this, "Uploading video…", Toast.LENGTH_SHORT).show();

        YouTubeCloudinaryUtils.uploadVideo(this, selectedVideoUri, myUid,
            new YouTubeCloudinaryUtils.UploadCallback() {
                @Override public void onProgress(int pct) { /* progress bar optional */ }
                @Override public void onSuccess(String secureUrl, String publicId, long durationSecs) {
                    saveScheduledVideo(title, secureUrl, durationSecs);
                }
                @Override public void onError(String errorMsg) {
                    setLoading(false);
                    Toast.makeText(YouTubeScheduleUploadActivity.this,
                        "Upload failed: " + errorMsg, Toast.LENGTH_LONG).show();
                }
            });
    }

    private void saveScheduledVideo(String title, String videoUrl, long durationSecs) {
        if (myUid.isEmpty()) return;
        String vidId = YouTubeFirebaseUtils.videosRef().push().getKey();
        if (vidId == null) { setLoading(false); return; }

        String desc = etDescription != null ? etDescription.getText().toString().trim() : "";
        String tags = etTags        != null ? etTags.getText().toString().trim()        : "";
        String cat  = etCategory    != null ? etCategory.getText().toString().trim()    : "";

        YouTubeVideo v = new YouTubeVideo(vidId, myUid, myName, myPhoto, title, desc,
            videoUrl, null, cat, durationSecs, System.currentTimeMillis(), false);
        v.visibility  = "scheduled";
        v.scheduledAt = scheduledCal.getTimeInMillis();
        v.tags        = tags;

        YouTubeFirebaseUtils.videoRef(vidId).setValue(v);
        YouTubeFirebaseUtils.userVideosRef(myUid).child(vidId)
            .setValue(System.currentTimeMillis());
        YouTubeFirebaseUtils.videoScheduledRef(myUid).child(vidId)
            .setValue(scheduledCal.getTimeInMillis());

        setLoading(false);
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());
        Toast.makeText(this, "Scheduled for " + sdf.format(scheduledCal.getTime()),
            Toast.LENGTH_LONG).show();
        finish();
    }

    private void setLoading(boolean on) {
        if (btnSchedule  != null) btnSchedule.setEnabled(!on);
        if (progressView != null) progressView.setVisibility(on ? View.VISIBLE : View.GONE);
    }
}
