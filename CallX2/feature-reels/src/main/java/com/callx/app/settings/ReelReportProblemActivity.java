package com.callx.app.settings;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * ReelReportProblemActivity — SUPPORT ▸ "Report a problem" in
 * ReelPrivacyAndSecurityActivity. General app-bug / feedback report,
 * distinct from reporting another user or a specific reel (those already
 * write to the /reports/{targetUid} moderation queue elsewhere in the app —
 * see UserProfileActivity). This writes to /appProblemReports/{uid}/{id} so
 * it doesn't get mixed into that content-moderation queue.
 */
public class ReelReportProblemActivity extends AppCompatActivity {

    private static final String[] CATEGORIES = {
        "App crashed or froze", "Video won't upload / process", "Watermark not showing",
        "Notifications not working", "Something looks broken", "Other"
    };

    private Spinner spCategory;
    private EditText etDescription;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildToolbar());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView label1 = sectionLabel("What went wrong?");
        content.addView(label1);
        spCategory = new Spinner(this);
        spCategory.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CATEGORIES));
        content.addView(spCategory);

        TextView label2 = sectionLabel("Describe the problem");
        LinearLayout.LayoutParams l2lp = (LinearLayout.LayoutParams) label2.getLayoutParams();
        l2lp.topMargin = dp(20);
        content.addView(label2);

        etDescription = new EditText(this);
        etDescription.setHint("The more detail, the faster we can fix it…");
        etDescription.setMinLines(5);
        etDescription.setGravity(Gravity.TOP | Gravity.START);
        etDescription.setBackgroundResource(android.R.drawable.edit_text);
        etDescription.setPadding(dp(10), dp(10), dp(10), dp(10));
        content.addView(etDescription);

        android.widget.Button btnSubmit = new android.widget.Button(this);
        btnSubmit.setText("Submit report");
        btnSubmit.setAllCaps(false);
        btnSubmit.setTextColor(Color.WHITE);
        btnSubmit.setBackgroundColor(Color.parseColor("#FF3B5C"));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        blp.topMargin = dp(24);
        btnSubmit.setLayoutParams(blp);
        btnSubmit.setOnClickListener(v -> submit());
        content.addView(btnSubmit);

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(16);
        progress.setLayoutParams(plp);
        progress.setVisibility(View.GONE);
        content.addView(progress);

        root.addView(content);
        setContentView(root);
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#1C1C1E"));
        tv.setTextSize(14f);
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        bar.setPadding(dp(4), 0, dp(16), 0);

        android.widget.ImageButton back = new android.widget.ImageButton(this);
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("Report a problem");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1C1C1E"));
        bar.addView(title);

        View border = new View(this);
        border.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        border.setBackgroundColor(Color.parseColor("#E5E5EA"));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(bar);
        wrapper.addView(border);
        return wrapper;
    }

    private void submit() {
        String description = etDescription.getText() != null ? etDescription.getText().toString().trim() : "";
        if (description.isEmpty()) {
            etDescription.setError("Please describe the problem");
            return;
        }
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty()) {
            Toast.makeText(this, "Please sign in again and retry", Toast.LENGTH_SHORT).show();
            return;
        }
        progress.setVisibility(View.VISIBLE);
        String reportId = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("appProblemReports").child(myUid).push().getKey();
        if (reportId == null) {
            progress.setVisibility(View.GONE);
            Toast.makeText(this, "Couldn't submit, try again", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, Object> report = new HashMap<>();
        report.put("category", CATEGORIES[spCategory.getSelectedItemPosition()]);
        report.put("description", description);
        report.put("uid", myUid);
        report.put("appVersion", appVersion());
        report.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        report.put("androidVersion", android.os.Build.VERSION.RELEASE);
        report.put("timestamp", System.currentTimeMillis());
        report.put("status", "open");

        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("appProblemReports").child(myUid).child(reportId)
            .setValue(report)
            .addOnCompleteListener(t -> {
                progress.setVisibility(View.GONE);
                if (t.isSuccessful()) {
                    Toast.makeText(this, "Thanks — we'll look into it", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(this, "Couldn't submit, try again", Toast.LENGTH_SHORT).show();
                }
            });
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
