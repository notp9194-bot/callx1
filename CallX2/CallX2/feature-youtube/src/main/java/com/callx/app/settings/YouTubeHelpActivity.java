package com.callx.app.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;

/**
 * YouTubeHelpActivity — YouTube Help Center with expandable FAQ.
 * Production-level: real questions organized in categories.
 */
public class YouTubeHelpActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_help);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_help);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Help");
        }

        LinearLayout container = findViewById(R.id.ll_yt_help_container);
        if (container == null) return;

        addCategory(container, "Videos & Playback");
        addFaq(container,
            "Video buffering ya rukh raha hai?",
            "• Internet connection check karo — WiFi ya mobile data\n• App restart karo\n• Settings → Data Saving → Data Saving Mode off karo\n• Video quality kam karo (player mein Settings button)\n• Cache clear karo: Settings → Storage → Clear Cache");

        addFaq(container,
            "Video play nahi ho raha?",
            "• App update karo Play Store se\n• Force stop karo phir reopen karo\n• Device restart karo\n• Agar specific video hai to report karo");

        addFaq(container,
            "Download ho raha hai lekin kahan gaya?",
            "Library tab → Downloads mein dekho. Downloads app ke private folder mein save hote hain — Gallery mein nahi dikhte. Sirf is app se hi play kar sakte ho.");

        addFaq(container,
            "Background mein play nahi ho raha?",
            "Player screen pe Mini Player button dabao (↓) phir app background mein jao. Ya phir Settings → Playback mein Background Play enable karo.");

        addCategory(container, "Account & Channel");
        addFaq(container,
            "Channel edit kaise karein?",
            "Library tab → apna avatar tap karo → Channel page → Edit Channel button. Wahan name, handle, bio, avatar, banner sab change kar sakte ho.");

        addFaq(container,
            "Subscribe/Unsubscribe kaise karein?",
            "Kisi bhi video ya channel page pe Subscribe button dabao. Subscriptions tab mein unke naye videos dikhenge.");

        addFaq(container,
            "Watch History kaise manage karein?",
            "Library tab → History. History clear karne ke liye, ya pause karne ke liye, Settings → General → Manage History jao.");

        addCategory(container, "Upload");
        addFaq(container,
            "Video upload kyun fail ho raha hai?",
            "• Internet connection stable hona chahiye (WiFi preferred)\n• Video format MP4 hona chahiye\n• File size 500MB se kam rakhein\n• Title dena zaroori hai\n• Dobara try karo");

        addFaq(container,
            "YouTube Short kaise banayein?",
            "Upload screen mein 'This is a YouTube Short' checkbox select karo. Short 60 seconds se kam hona chahiye aur vertical (9:16) format mein best dikh ta hai.");

        addFaq(container,
            "Thumbnail kaise add karein?",
            "Upload screen mein 'Pick Thumbnail' button se image select karo. Custom thumbnail video se better engagement deta hai.");

        addCategory(container, "Comments & Community");
        addFaq(container,
            "Comment delete kaise karein?",
            "Comment ke paas 3-dot (⋮) button dabao → Delete option aayega. Sirf apne comments delete kar sakte ho.");

        addFaq(container,
            "Comment pe reply kaise karein?",
            "Comment pe tap karo ya 'Reply' button — reply input field open hoga. @username automatically add ho jata hai.");

        addCategory(container, "Settings & Privacy");
        addFaq(container,
            "Dark mode kaise lagayein?",
            "Settings → General → Appearance → Dark mein set karo.");

        addFaq(container,
            "Notifications kaise band karein?",
            "Settings → Notifications mein specific types ke switches off karo. Ya Android Settings → Apps → [App] → Notifications se poora off karo.");

        addFaq(container,
            "Account ka data kaise download karein?",
            "Settings → Your Data in YouTube → Request a copy of your data. Processing mein kuch din lag sakte hain.");
    }

    private void addCategory(LinearLayout container, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xFFAAAAAA);
        tv.setTextSize(12f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(16), dp(20), dp(16), dp(8));
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.05f);
        container.addView(tv);

        // Divider
        View div = new View(this);
        div.setBackgroundColor(0xFF303030);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        container.addView(div, lp);
    }

    private void addFaq(LinearLayout container, String question, String answer) {
        // Question row (clickable)
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        int[] attrs = {android.R.attr.selectableItemBackground};
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        row.setBackground(ta.getDrawable(0));
        ta.recycle();

        TextView tvQ = new TextView(this);
        tvQ.setText(question);
        tvQ.setTextColor(0xFFFFFFFF);
        tvQ.setTextSize(14f);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvQ.setLayoutParams(qLp);
        row.addView(tvQ);

        TextView tvChevron = new TextView(this);
        tvChevron.setText("›");
        tvChevron.setTextColor(0xFF888888);
        tvChevron.setTextSize(20f);
        tvChevron.setPadding(dp(8), 0, 0, 0);
        row.addView(tvChevron);

        container.addView(row);

        // Answer (hidden by default)
        TextView tvA = new TextView(this);
        tvA.setText(answer);
        tvA.setTextColor(0xFFCCCCCC);
        tvA.setTextSize(13f);
        tvA.setPadding(dp(16), dp(4), dp(16), dp(14));
        tvA.setVisibility(View.GONE);
        tvA.setLineSpacing(dp(4), 1f);
        container.addView(tvA);

        row.setOnClickListener(v -> {
            boolean isVisible = tvA.getVisibility() == View.VISIBLE;
            tvA.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            tvChevron.setText(isVisible ? "›" : "∨");
            tvChevron.setRotation(0);
        });

        // Bottom divider
        View div = new View(this);
        div.setBackgroundColor(0xFF252525);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        container.addView(div, lp);
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
