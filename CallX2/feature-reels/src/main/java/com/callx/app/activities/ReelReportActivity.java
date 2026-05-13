package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * ReelReportActivity — Report a reel for policy violations.
 *
 * Features:
 *  ✅ 6 report categories (radio group)
 *  ✅ Optional additional details text
 *  ✅ Submits to reelReports/{reelId}/{uid}
 *  ✅ Prevents duplicate reports (one per user per reel)
 *  ✅ Success dialog with thank-you message
 */
public class ReelReportActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID         = "report_reel_id";
    public static final String EXTRA_REEL_UID        = "report_reel_uid";
    public static final String EXTRA_REEL_OWNER_NAME = "report_owner_name";

    private RadioGroup  rgReportReason;
    private EditText    etAdditionalInfo;
    private Button      btnSubmitReport;
    private ProgressBar progressBar;

    private String reelId;
    private String reelUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_report);

        reelId  = getIntent().getStringExtra(EXTRA_REEL_ID);
        reelUid = getIntent().getStringExtra(EXTRA_REEL_UID);
        String ownerName = getIntent().getStringExtra(EXTRA_REEL_OWNER_NAME);

        if (reelId == null || reelId.isEmpty()) {
            finish();
            return;
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Report Reel");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rgReportReason   = findViewById(R.id.rg_report_reason);
        etAdditionalInfo = findViewById(R.id.et_report_details);
        btnSubmitReport  = findViewById(R.id.btn_submit_report);
        progressBar      = findViewById(R.id.progress_report);

        btnSubmitReport.setOnClickListener(v -> submitReport());
    }

    private void submitReport() {
        int selectedId = rgReportReason.getCheckedRadioButtonId();
        if (selectedId == -1) {
            Toast.makeText(this, "Please select a reason", Toast.LENGTH_SHORT).show();
            return;
        }

        RadioButton selected = findViewById(selectedId);
        String reason = selected.getText().toString();
        String details = etAdditionalInfo.getText() != null
            ? etAdditionalInfo.getText().toString().trim() : "";

        String myUid;
        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        btnSubmitReport.setEnabled(false);

        Map<String, Object> report = new HashMap<>();
        report.put("reason",    reason);
        report.put("details",   details);
        report.put("reportedBy",myUid);
        report.put("reelUid",   reelUid != null ? reelUid : "");
        report.put("timestamp", System.currentTimeMillis());

        FirebaseUtils.getReelReportsRef(reelId).child(myUid).setValue(report)
            .addOnSuccessListener(unused -> {
                progressBar.setVisibility(View.GONE);
                showSuccessDialog();
            })
            .addOnFailureListener(e -> {
                progressBar.setVisibility(View.GONE);
                btnSubmitReport.setEnabled(true);
                Toast.makeText(this, "Failed to submit: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            });
    }

    private void showSuccessDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Report Submitted")
            .setMessage("Thank you for keeping CallX safe. We'll review this reel and take action if it violates our community guidelines.")
            .setPositiveButton("Done", (d, w) -> finish())
            .setCancelable(false)
            .show();
    }
}
