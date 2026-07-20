package com.callx.app.community;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.community.worker.EventReminderWorker;
import com.callx.app.repository.CommunityRepository;
import com.callx.app.utils.CloudinaryUploader;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * v34: Create community event — upgraded with:
 *  - Cover/banner image picker (Cloudinary upload)
 *  - Event type selector: OFFLINE / ONLINE / HYBRID
 *  - Online meeting link field (shown for ONLINE/HYBRID)
 *  - End time picker
 *  - "Set Reminder" toggle — schedules EventReminderWorker 1h before
 */
public class CommunityCreateEventActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId, currentUid, myName;

    // Views
    private ImageView ivCoverPreview;
    private View btnPickCover;
    private EditText etTitle, etDescription, etLocation, etOnlineLink;
    private RadioGroup rgEventType;
    private RadioButton rbOffline, rbOnline, rbHybrid;
    private View layoutOnlineLink;
    private Button btnPickDate, btnPickTime, btnPickEndTime, btnCreate;
    private TextView tvSelectedDatetime, tvSelectedEndtime;
    private android.widget.Switch switchReminder;

    private Calendar startCal  = Calendar.getInstance();
    private Calendar endCal    = Calendar.getInstance();
    private boolean datePicked = false, timePicked = false, endTimePicked = false;

    private Uri pickedCoverUri;
    private String uploadedCoverUrl = null;

    private CommunityRepository repo;
    private ActivityResultLauncher<String> coverPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_create_event_v2);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        repo = CommunityRepository.getInstance(this);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            myName     = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
        }

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        ivCoverPreview  = findViewById(R.id.iv_event_cover_preview);
        btnPickCover    = findViewById(R.id.btn_pick_event_cover);
        etTitle         = findViewById(R.id.et_event_title);
        etDescription   = findViewById(R.id.et_event_description);
        etLocation      = findViewById(R.id.et_event_location);
        etOnlineLink    = findViewById(R.id.et_event_online_link);
        rgEventType     = findViewById(R.id.rg_event_type);
        rbOffline       = findViewById(R.id.rb_event_offline);
        rbOnline        = findViewById(R.id.rb_event_online);
        rbHybrid        = findViewById(R.id.rb_event_hybrid);
        layoutOnlineLink= findViewById(R.id.layout_event_online_link);
        btnPickDate     = findViewById(R.id.btn_pick_date);
        btnPickTime     = findViewById(R.id.btn_pick_time);
        btnPickEndTime  = findViewById(R.id.btn_pick_end_time);
        btnCreate       = findViewById(R.id.btn_create_event);
        tvSelectedDatetime  = findViewById(R.id.tv_selected_datetime);
        tvSelectedEndtime   = findViewById(R.id.tv_selected_endtime);
        switchReminder  = findViewById(R.id.switch_event_reminder);

        // Cover image picker
        coverPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            pickedCoverUri = uri;
            ivCoverPreview.setVisibility(View.VISIBLE);
            Glide.with(this).load(uri).centerCrop().override(800, 300).into(ivCoverPreview);
        });
        btnPickCover.setOnClickListener(v -> coverPicker.launch("image/*"));

        // Event type toggle
        rgEventType.setOnCheckedChangeListener((g, id) -> {
            boolean showOnline = (id == R.id.rb_event_online || id == R.id.rb_event_hybrid);
            layoutOnlineLink.setVisibility(showOnline ? View.VISIBLE : View.GONE);
        });

        btnPickDate.setOnClickListener(v -> showDatePicker());
        btnPickTime.setOnClickListener(v -> showTimePicker());
        btnPickEndTime.setOnClickListener(v -> showEndTimePicker());
        btnCreate.setOnClickListener(v -> createEvent());
    }

    private void showDatePicker() {
        int y = startCal.get(Calendar.YEAR), m = startCal.get(Calendar.MONTH),
                d = startCal.get(Calendar.DAY_OF_MONTH);
        new DatePickerDialog(this, (dp, yr, mo, da) -> {
            startCal.set(Calendar.YEAR, yr); startCal.set(Calendar.MONTH, mo);
            startCal.set(Calendar.DAY_OF_MONTH, da);
            endCal.set(Calendar.YEAR, yr); endCal.set(Calendar.MONTH, mo);
            endCal.set(Calendar.DAY_OF_MONTH, da);
            datePicked = true; updateLabels();
        }, y, m, d).show();
    }

    private void showTimePicker() {
        int h = startCal.get(Calendar.HOUR_OF_DAY), min = startCal.get(Calendar.MINUTE);
        new TimePickerDialog(this, (tp, hr, mn) -> {
            startCal.set(Calendar.HOUR_OF_DAY, hr); startCal.set(Calendar.MINUTE, mn);
            timePicked = true; updateLabels();
        }, h, min, false).show();
    }

    private void showEndTimePicker() {
        int h = endCal.get(Calendar.HOUR_OF_DAY), min = endCal.get(Calendar.MINUTE);
        new TimePickerDialog(this, (tp, hr, mn) -> {
            endCal.set(Calendar.HOUR_OF_DAY, hr); endCal.set(Calendar.MINUTE, mn);
            endTimePicked = true; updateLabels();
        }, h, min, false).show();
    }

    private void updateLabels() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d yyyy 'at' h:mm a", Locale.getDefault());
        if (datePicked || timePicked) {
            tvSelectedDatetime.setVisibility(View.VISIBLE);
            tvSelectedDatetime.setText("Starts: " + sdf.format(startCal.getTime()));
        }
        if (endTimePicked) {
            tvSelectedEndtime.setVisibility(View.VISIBLE);
            tvSelectedEndtime.setText("Ends: " + sdf.format(endCal.getTime()));
        }
    }

    private String getEventType() {
        int id = rgEventType.getCheckedRadioButtonId();
        if (id == R.id.rb_event_online) return "ONLINE";
        if (id == R.id.rb_event_hybrid) return "HYBRID";
        return "OFFLINE";
    }

    private void createEvent() {
        String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "Event title is required", Toast.LENGTH_SHORT).show(); return;
        }
        if (!datePicked) {
            Toast.makeText(this, "Please pick a date", Toast.LENGTH_SHORT).show(); return;
        }
        btnCreate.setEnabled(false);

        String desc       = etDescription.getText().toString().trim();
        String loc        = etLocation.getText().toString().trim();
        String onlineLink = etOnlineLink.getText().toString().trim();
        String eventType  = getEventType();
        long   startMs    = startCal.getTimeInMillis();
        long   endMs      = endTimePicked ? endCal.getTimeInMillis() : 0L;
        boolean setReminder = switchReminder != null && switchReminder.isChecked();

        if (pickedCoverUri != null) {
            Toast.makeText(this, "Uploading cover image…", Toast.LENGTH_SHORT).show();
            new CloudinaryUploader().uploadFile(this, pickedCoverUri, "callx/events",
                    new CloudinaryUploader.UploadCallback() {
                        @Override public void onSuccess(CloudinaryUploader.Result result) {
                            submitEvent(title, desc, loc, onlineLink, eventType, startMs, endMs,
                                    result.secureUrl, setReminder);
                        }
                        @Override public void onError(String msg) {
                            runOnUiThread(() -> {
                                btnCreate.setEnabled(true);
                                Toast.makeText(CommunityCreateEventActivity.this,
                                        "Cover upload failed: " + msg, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        } else {
            submitEvent(title, desc, loc, onlineLink, eventType, startMs, endMs, null, setReminder);
        }
    }

    private void submitEvent(String title, String desc, String loc, String onlineLink,
                             String eventType, long startMs, long endMs,
                             String coverUrl, boolean setReminder) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = new HashMap<>();
        event.put("id",            eventId);
        event.put("communityId",   communityId);
        event.put("title",         title);
        event.put("description",   desc);
        event.put("location",      loc);
        event.put("onlineLink",    onlineLink);
        event.put("eventType",     eventType);
        event.put("coverImageUrl", coverUrl != null ? coverUrl : "");
        event.put("createdByUid",  currentUid);
        event.put("createdByName", myName != null ? myName : "");
        event.put("startTimeMs",   startMs);
        event.put("endTimeMs",     endMs);
        event.put("rsvpCount",     0L);
        event.put("interestedCount", 0L);
        event.put("notGoingCount",  0L);
        event.put("createdAt",     System.currentTimeMillis());

        FirebaseDatabase.getInstance().getReference("communities")
                .child(communityId).child("events").child(eventId)
                .setValue(event)
                .addOnSuccessListener(v -> {
                    if (setReminder) {
                        EventReminderWorker.schedule(this, communityId, eventId, title, startMs);
                    }
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Event created!", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                })
                .addOnFailureListener(e -> runOnUiThread(() -> {
                    btnCreate.setEnabled(true);
                    Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }));
    }
}
