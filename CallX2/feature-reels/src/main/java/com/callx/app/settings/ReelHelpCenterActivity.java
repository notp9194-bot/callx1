package com.callx.app.settings;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;

/**
 * ReelHelpCenterActivity — Support / FAQ screen reached from
 * ReelPrivacyAndSecurityActivity ▸ SUPPORT ▸ "Help Center".
 *
 * Self-contained (no cross-module dependency): a searchable-by-eye list of
 * expandable FAQ entries plus a "Contact support" action that opens the
 * user's mail app pre-filled with device/app info, same pattern used by
 * ReelReportProblemActivity.
 */
public class ReelHelpCenterActivity extends AppCompatActivity {

    private static final String[][] FAQS = {
        {"Why was my reel removed?",
         "Reels are removed when they trip community guidelines (copyright, "
         + "nudity, hate speech, spam) or a manual report is upheld by our "
         + "moderation team. Open Privacy & Safety ▸ Moderation to see any "
         + "active strikes on your account."},
        {"How do I get verified?",
         "Verification is reviewed manually based on account authenticity, "
         + "notability and activity. There's no in-app request form yet — "
         + "use Contact support below and our team will follow up."},
        {"Why is my video stuck processing?",
         "Large or long videos can take a few minutes to encode. If it's "
         + "been stuck for over 30 minutes, try re-uploading on Wi-Fi or a "
         + "shorter clip."},
        {"How do I add a watermark to my reels?",
         "Go to Privacy & Safety ▸ Watermark settings, turn it on, pick "
         + "username / custom text / logo, then Save. It'll appear on your "
         + "reels going forward."},
        {"How do I delete my account?",
         "Manage my account ▸ Delete account. This is permanent and can't "
         + "be undone."},
        {"Someone is impersonating me — what do I do?",
         "Open their profile ▸ Report ▸ Impersonation, and also use Contact "
         + "support below with a link to your real profile so we can "
         + "prioritise it."},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.WHITE);

        root.addView(buildToolbar());

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(24));
        scroll.addView(content);
        root.addView(scroll);

        for (String[] faq : FAQS) {
            content.addView(buildFaqRow(faq[0], faq[1]));
        }

        content.addView(buildContactButton());

        setContentView(root);
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
        title.setText("Help Center");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1C1C1E"));
        bar.addView(title);

        View border = new View(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        border.setLayoutParams(blp);
        border.setBackgroundColor(Color.parseColor("#E5E5EA"));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(bar);
        wrapper.addView(border);
        return wrapper;
    }

    private View buildFaqRow(String question, String answer) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        int[] attrs = new int[]{android.R.attr.selectableItemBackground};
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        row.setForeground(ta.getDrawable(0));
        ta.recycle();

        TextView tvQ = new TextView(this);
        tvQ.setText(question);
        tvQ.setTextColor(Color.parseColor("#1C1C1E"));
        tvQ.setTextSize(15f);
        tvQ.setTypeface(null, Typeface.BOLD);
        row.addView(tvQ);

        TextView tvA = new TextView(this);
        tvA.setText(answer);
        tvA.setTextColor(Color.parseColor("#6E6E73"));
        tvA.setTextSize(13.5f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(6);
        tvA.setLayoutParams(alp);
        tvA.setVisibility(View.GONE);
        row.addView(tvA);

        row.setOnClickListener(v ->
            tvA.setVisibility(tvA.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row);
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(Color.parseColor("#F0F0F0"));
        wrapper.addView(divider);
        return wrapper;
    }

    private View buildContactButton() {
        android.widget.Button btn = new android.widget.Button(this);
        btn.setText("Contact support");
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.parseColor("#FF3B5C"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(dp(16), dp(20), dp(16), 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> emailSupport());
        return btn;
    }

    private void emailSupport() {
        Intent mail = new Intent(Intent.ACTION_SENDTO);
        mail.setData(Uri.parse("mailto:"));
        mail.putExtra(Intent.EXTRA_EMAIL, new String[]{"support@callx.app"});
        mail.putExtra(Intent.EXTRA_SUBJECT, "CallX support request");
        mail.putExtra(Intent.EXTRA_TEXT, "Describe your issue here:\n\n\n---\n"
            + "App version: " + appVersion()
            + "\nDevice: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
            + "\nAndroid: " + android.os.Build.VERSION.RELEASE);
        try {
            startActivity(mail);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show();
        }
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
