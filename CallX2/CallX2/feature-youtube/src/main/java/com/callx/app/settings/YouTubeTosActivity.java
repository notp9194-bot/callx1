package com.callx.app.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;

/**
 * YouTubeTosActivity — YouTube Terms of Service (summary display).
 */
public class YouTubeTosActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_tos);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_tos);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Terms of Service");
        }

        TextView tvContent = findViewById(R.id.tv_yt_tos_content);
        if (tvContent != null) {
            tvContent.setText(TOS_TEXT);
        }

        TextView tvLastUpdated = findViewById(R.id.tv_yt_tos_last_updated);
        if (tvLastUpdated != null) {
            tvLastUpdated.setText("Last updated: January 5, 2024");
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private static final String TOS_TEXT =
        "YOUTUBE TERMS OF SERVICE — SUMMARY\n\n" +

        "1. YOUR RELATIONSHIP WITH YOUTUBE\n" +
        "Aap YouTube ki services use kar rahe hain jo Google LLC provide karti hai. " +
        "In Terms of Service ko padhein aur samjhein kyunki ye aap aur YouTube ke beech ek legal agreement hai.\n\n" +

        "2. WHO MAY USE THE SERVICE\n" +
        "YouTube use karne ke liye aapki umar 13 saal ya usse zyada honi chahiye. " +
        "Agar aap 18 saal se kam hain, apne parents ya guardian ki permission se use karein.\n\n" +

        "3. YOUR USE OF THE SERVICE\n" +
        "Aap YouTube ko only legal purposes ke liye use kar sakte hain. " +
        "Aap koi bhi content upload nahi kar sakte jo:\n" +
        "• Copyright infringe karta ho\n" +
        "• Hateful, violent ya sexually explicit ho\n" +
        "• Misleading ya deceptive ho\n" +
        "• Spam ya malware ho\n" +
        "• Doosron ki privacy violate karta ho\n\n" +

        "4. YOUR CONTENT AND CONDUCT\n" +
        "Jo content aap upload karte hain, uski responsibility aapki hai. " +
        "Aap YouTube ko apni content use karne ka worldwide license dete hain service provide karne ke liye.\n\n" +

        "5. ACCOUNT SUSPENSION AND TERMINATION\n" +
        "YouTube Terms violate karne par YouTube aapka account suspend ya terminate kar sakta hai, " +
        "bina advance notice ke.\n\n" +

        "6. ABOUT SOFTWARE IN THE SERVICE\n" +
        "Ye app software include karta hai jo Google ki taraf se license kiya gaya hai. " +
        "Aap is software ko copy, modify, ya distribute nahi kar sakte.\n\n" +

        "7. OTHER LEGAL TERMS\n" +
        "YOUTUBE SERVICES ARE PROVIDED \"AS IS\" WITHOUT WARRANTY. " +
        "YouTube is not liable for any indirect, incidental, or consequential damages.\n\n" +

        "8. CHANGES TO THIS AGREEMENT\n" +
        "YouTube in terms ko kabhi bhi update kar sakta hai. " +
        "App continue use karna means aap updated terms accept kar rahe hain.\n\n" +

        "Full terms: youtube.com/t/terms\n" +
        "Privacy Policy: youtube.com/t/privacy";
}
