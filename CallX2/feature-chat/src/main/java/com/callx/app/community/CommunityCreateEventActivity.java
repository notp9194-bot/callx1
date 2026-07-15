package com.callx.app.community;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.chat.R;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * v31: Create community event — title, description, date/time picker, location.
 */
public class CommunityCreateEventActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId, currentUid, myName;
    private EditText etTitle, etDescription, etLocation;
    private Button btnPickDate, btnPickTime, btnCreate;
    private TextView tvSelectedDatetime;

    private Calendar selectedCal = Calendar.getInstance();
    private boolean datePicked = false;
    private boolean timePicked = false;

    private CommunityRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_create_event);

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

        etTitle         = findViewById(R.id.et_event_title);
        etDescription   = findViewById(R.id.et_event_description);
        etLocation      = findViewById(R.id.et_event_location);
        btnPickDate     = findViewById(R.id.btn_pick_date);
        btnPickTime     = findViewById(R.id.btn_pick_time);
        btnCreate       = findViewById(R.id.btn_create_event);
        tvSelectedDatetime = findViewById(R.id.tv_selected_datetime);

        btnPickDate.setOnClickListener(v -> showDatePicker());
        btnPickTime.setOnClickListener(v -> showTimePicker());
        btnCreate.setOnClickListener(v -> createEvent());
    }

    private void showDatePicker() {
        int year  = selectedCal.get(Calendar.YEAR);
        int month = selectedCal.get(Calendar.MONTH);
        int day   = selectedCal.get(Calendar.DAY_OF_MONTH);
        new DatePickerDialog(this, (dp, y, m, d) -> {
            selectedCal.set(Calendar.YEAR, y);
            selectedCal.set(Calendar.MONTH, m);
            selectedCal.set(Calendar.DAY_OF_MONTH, d);
            datePicked = true;
            updateDatetimeLabel();
        }, year, month, day).show();
    }

    private void showTimePicker() {
        int hour   = selectedCal.get(Calendar.HOUR_OF_DAY);
        int minute = selectedCal.get(Calendar.MINUTE);
        new TimePickerDialog(this, (tp, h, min) -> {
            selectedCal.set(Calendar.HOUR_OF_DAY, h);
            selectedCal.set(Calendar.MINUTE, min);
            timePicked = true;
            updateDatetimeLabel();
        }, hour, minute, false).show();
    }

    private void updateDatetimeLabel() {
        if (!datePicked && !timePicked) return;
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d yyyy 'at' h:mm a", Locale.getDefault());
        tvSelectedDatetime.setVisibility(View.VISIBLE);
        tvSelectedDatetime.setText(sdf.format(selectedCal.getTime()));
    }

    private void createEvent() {
        String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "Event title is required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!datePicked) {
            Toast.makeText(this, "Please pick a date", Toast.LENGTH_SHORT).show();
            return;
        }
        String description = etDescription.getText().toString().trim();
        String location    = etLocation.getText().toString().trim();
        long   startTimeMs = selectedCal.getTimeInMillis();

        btnCreate.setEnabled(false);
        repo.createEvent(communityId, title, description, location, startTimeMs, 0L,
                currentUid, myName != null ? myName : "Admin",
                (success, error) -> runOnUiThread(() -> {
                    btnCreate.setEnabled(true);
                    if (success) {
                        Toast.makeText(this, "Event created!", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                    }
                }));
    }
}
