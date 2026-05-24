package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.Map;

/** Report video or comment with reason selector and optional description. */
public class YouTubeReportActivity extends AppCompatActivity {

    private RadioGroup rgReason;
    private EditText   etDetails;
    private String     videoId, commentId, reportType;
    private String     myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_report);

        videoId    = getIntent().getStringExtra("video_id");
        commentId  = getIntent().getStringExtra("comment_id");
        reportType = getIntent().getStringExtra("type");   // "video" | "comment"
        myUid      = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_report_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        rgReason  = findViewById(R.id.rg_yt_report_reason);
        etDetails = findViewById(R.id.et_yt_report_details);

        View btnSubmit = findViewById(R.id.btn_yt_report_submit);
        if (btnSubmit != null) btnSubmit.setOnClickListener(v -> submitReport());
    }

    private void submitReport() {
        if (rgReason == null) return;
        int selected = rgReason.getCheckedRadioButtonId();
        if (selected == -1) {
            Toast.makeText(this, "Please select a reason", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Object> report = new HashMap<>();
        report.put("reporterUid", myUid);
        report.put("videoId", videoId);
        report.put("commentId", commentId);
        report.put("type", reportType);
        report.put("reason", getReasonText(selected));
        report.put("details", etDetails != null ? etDetails.getText().toString().trim() : "");
        report.put("timestamp", System.currentTimeMillis());

        String reportId = YouTubeFirebaseUtils.reportsRef().push().getKey();
        if (reportId == null) return;

        YouTubeFirebaseUtils.reportsRef().child(reportId).setValue(report)
            .addOnSuccessListener(x -> {
                Toast.makeText(this, "Report submitted. Thank you.", Toast.LENGTH_SHORT).show();
                finish();
            });
    }

    private String getReasonText(int id) {
        if (id == R.id.rb_yt_report_spam)       return "Spam or misleading";
        if (id == R.id.rb_yt_report_hate)        return "Hateful or abusive content";
        if (id == R.id.rb_yt_report_violent)     return "Violent or graphic content";
        if (id == R.id.rb_yt_report_sexual)      return "Sexual content";
        if (id == R.id.rb_yt_report_dangerous)   return "Dangerous or harmful acts";
        if (id == R.id.rb_yt_report_child)       return "Child safety";
        if (id == R.id.rb_yt_report_privacy)     return "Privacy violation";
        if (id == R.id.rb_yt_report_copyright)   return "Copyright infringement";
        return "Other";
    }
}
