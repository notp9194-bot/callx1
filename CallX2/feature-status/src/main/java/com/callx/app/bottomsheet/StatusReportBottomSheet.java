package com.callx.app.bottomsheet;

import android.content.Context;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.ServerValue;
import java.util.*;

/**
 * StatusReportBottomSheet v26 — Full report flow with reason picker → Firebase → moderation queue.
 * FIX: Was just Toast; now sends structured report to Firebase.
 */
public class StatusReportBottomSheet {
    private static final String[] REASONS = {
        "Spam or advertisement",
        "Inappropriate content",
        "Harassment or bullying",
        "Hate speech",
        "False information",
        "Violence or dangerous content",
        "Nudity or sexual content",
        "Other"
    };

    public static void show(Context ctx, StatusItem item, String reporterUid) {
        if (ctx == null || item == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx,20), dp(ctx,12), dp(ctx,20), dp(ctx,32));

        TextView title = tv(ctx, "🚩 Report Status", 17, true); root.addView(title);
        TextView sub   = tv(ctx, "Why are you reporting this status?", 13, false);
        sub.setPadding(0, 0, 0, dp(ctx,16)); sub.setTextColor(android.graphics.Color.GRAY); root.addView(sub);

        final int[] selectedIndex = {-1};
        final RadioGroup rg = new RadioGroup(ctx); rg.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < REASONS.length; i++) {
            RadioButton rb = new RadioButton(ctx); rb.setText(REASONS[i]); rb.setId(i + 100);
            rb.setPadding(0, dp(ctx,8), 0, dp(ctx,8)); rg.addView(rb);
        }
        root.addView(rg);

        // Additional details for "Other"
        EditText etDetails = new EditText(ctx); etDetails.setHint("Add details (optional)");
        etDetails.setMaxLines(3); etDetails.setVisibility(View.GONE);
        root.addView(etDetails);
        rg.setOnCheckedChangeListener((g, id) -> {
            selectedIndex[0] = id - 100;
            etDetails.setVisibility(selectedIndex[0] == REASONS.length - 1 ? View.VISIBLE : View.GONE);
        });

        Button btnReport = new Button(ctx); btnReport.setText("Submit Report");
        btnReport.setBackgroundColor(android.graphics.Color.parseColor("#E53935"));
        btnReport.setTextColor(android.graphics.Color.WHITE);
        root.addView(btnReport);

        btnReport.setOnClickListener(v -> {
            if (selectedIndex[0] < 0) { Toast.makeText(ctx,"Select a reason",Toast.LENGTH_SHORT).show(); return; }
            String reason  = REASONS[selectedIndex[0]];
            String details = etDetails.getText().toString().trim();
            submitReport(item, reporterUid, reason, details);
            Toast.makeText(ctx,"Report submitted. Thank you.",Toast.LENGTH_LONG).show();
            sheet.dismiss();
        });

        ScrollView sv = new ScrollView(ctx); sv.addView(root);
        sheet.setContentView(sv); sheet.show();
    }

    private static void submitReport(StatusItem item, String reporterUid, String reason, String details) {
        if (item == null || reporterUid == null) return;
        Map<String, Object> report = new HashMap<>();
        report.put("reporterUid",  reporterUid);
        report.put("ownerUid",     item.ownerUid);
        report.put("statusId",     item.id);
        report.put("statusType",   item.type);
        report.put("mediaUrl",     item.mediaUrl);
        report.put("reason",       reason);
        report.put("details",      details);
        report.put("status",       "pending");         // pending → reviewed → actioned
        report.put("timestamp",    ServerValue.TIMESTAMP);
        // Store in moderation queue
        FirebaseUtils.db().getReference("moderationQueue").push().setValue(report);
        // Also write to owner's report count (for auto-suspension logic)
        if (item.ownerUid != null) {
            FirebaseUtils.db().getReference("reportCounts").child(item.ownerUid)
                .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                    @Override public com.google.firebase.database.Transaction.Result doTransaction(
                            com.google.firebase.database.MutableData d) {
                        Long cur = d.getValue(Long.class); d.setValue(cur == null ? 1L : cur + 1);
                        return com.google.firebase.database.Transaction.success(d);
                    }
                    @Override public void onComplete(com.google.firebase.database.DatabaseError e,
                                                     boolean b, com.google.firebase.database.DataSnapshot s) {}
                });
        }
    }
    private static TextView tv(Context ctx, String t, int sz, boolean bold) {
        TextView v = new TextView(ctx); v.setText(t); v.setTextSize(sz);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD); return v;
    }
    private static int dp(Context ctx, int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }
}
