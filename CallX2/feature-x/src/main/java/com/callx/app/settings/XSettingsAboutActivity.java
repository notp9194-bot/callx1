package com.callx.app.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.x.R;

public class XSettingsAboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings_about);

        Toolbar toolbar = findViewById(R.id.toolbar_x_about);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("About");
        }

        // Show app version
        TextView tvVersion = findViewById(R.id.tv_x_version);
        if (tvVersion != null) {
            try {
                String vName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
                tvVersion.setText("X for Android  v" + vName);
            } catch (Exception e) {
                tvVersion.setText("X for Android");
            }
        }

        // Terms of Service
        setupUrlRow(R.id.row_x_tos,            "https://twitter.com/en/tos");
        // Privacy Policy
        setupUrlRow(R.id.row_x_privacy_policy, "https://twitter.com/en/privacy");
        // Cookie Policy
        setupUrlRow(R.id.row_x_cookies,        "https://help.twitter.com/rules-and-policies/twitter-cookies");
        // Help Center
        setupUrlRow(R.id.row_x_help_center,    "https://help.twitter.com");

        // Feedback
        View rowFeedback = findViewById(R.id.row_x_feedback);
        if (rowFeedback != null)
            rowFeedback.setOnClickListener(v ->
                Toast.makeText(this, "Thank you for your feedback!", Toast.LENGTH_SHORT).show());
    }

    private void setupUrlRow(int rowId, String url) {
        View row = findViewById(rowId);
        if (row != null)
            row.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
