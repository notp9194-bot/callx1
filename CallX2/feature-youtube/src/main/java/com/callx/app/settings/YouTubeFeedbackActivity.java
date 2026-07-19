package com.callx.app.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.Map;

/**
 * YouTubeFeedbackActivity — Real feedback form.
 * Users can select feedback type, enter description, and submit to Firebase.
 */
public class YouTubeFeedbackActivity extends AppCompatActivity {

    private Spinner  spFeedbackType;
    private EditText etFeedbackDesc;
    private Button   btnSubmit;
    private ProgressBar pbSubmit;
    private String myUid;

    private static final String[] FEEDBACK_TYPES = {
        "Bug / Glitch", "Feature Request", "Performance Issue",
        "Content / Video Problem", "Account Issue", "General Feedback"
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_feedback);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        Toolbar toolbar = findViewById(R.id.toolbar_yt_feedback);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Send Feedback");
        }

        spFeedbackType = findViewById(R.id.sp_yt_feedback_type);
        etFeedbackDesc = findViewById(R.id.et_yt_feedback_desc);
        btnSubmit      = findViewById(R.id.btn_yt_feedback_submit);
        pbSubmit       = findViewById(R.id.pb_yt_feedback);

        // Setup spinner
        ArrayAdapter<String> spAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, FEEDBACK_TYPES);
        spAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFeedbackType.setAdapter(spAdapter);

        btnSubmit.setOnClickListener(v -> submitFeedback());
    }

    private void submitFeedback() {
        String type = FEEDBACK_TYPES[spFeedbackType.getSelectedItemPosition()];
        String desc = etFeedbackDesc.getText().toString().trim();

        if (desc.isEmpty()) {
            etFeedbackDesc.setError("Please describe your feedback");
            etFeedbackDesc.requestFocus();
            return;
        }

        if (desc.length() < 10) {
            etFeedbackDesc.setError("Please provide more detail (at least 10 characters)");
            return;
        }

        btnSubmit.setEnabled(false);
        pbSubmit.setVisibility(View.VISIBLE);

        // Save to Firebase: youtube/feedback/{uid}/{timestamp}
        DatabaseReference fbRef = YouTubeFirebaseUtils.feedbackRef(myUid);
        String feedbackId = fbRef.push().getKey();
        if (feedbackId == null) {
            showError();
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("feedbackId", feedbackId);
        data.put("uid",        myUid);
        data.put("type",       type);
        data.put("description", desc);
        data.put("timestamp",  System.currentTimeMillis());
        data.put("appVersion", "v178");
        data.put("platform",   "android");

        fbRef.child(feedbackId).setValue(data)
            .addOnSuccessListener(unused -> {
                pbSubmit.setVisibility(View.GONE);
                Toast.makeText(this, "✅ Feedback submit ho gaya! Shukriya.", Toast.LENGTH_LONG).show();
                finish();
            })
            .addOnFailureListener(e -> {
                pbSubmit.setVisibility(View.GONE);
                btnSubmit.setEnabled(true);
                Toast.makeText(this, "❌ Submit fail: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
    }

    private void showError() {
        pbSubmit.setVisibility(View.GONE);
        btnSubmit.setEnabled(true);
        Toast.makeText(this, "Error, dobara try karo", Toast.LENGTH_SHORT).show();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
