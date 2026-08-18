package com.callx.app.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;

/**
 * YouTubeAboutActivity — App info, version, open-source licenses.
 */
public class YouTubeAboutActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_about);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_about);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("About");
        }

        TextView tvVersion = findViewById(R.id.tv_yt_about_version);
        if (tvVersion != null) tvVersion.setText("YouTube Module v178\nCallX2 App");

        TextView tvBuild = findViewById(R.id.tv_yt_about_build);
        if (tvBuild != null) tvBuild.setText("Build: 178 (Production)\nTarget SDK: 34\nMin SDK: 23");

        View btnLicenses = findViewById(R.id.btn_yt_open_source);
        if (btnLicenses != null) {
            btnLicenses.setOnClickListener(v ->
                Toast.makeText(this,
                    "Open Source Libraries:\n• ExoPlayer (Apache 2.0)\n• Firebase SDK (Apache 2.0)\n" +
                    "• Glide (BSD, MIT, Apache 2.0)\n• OkHttp (Apache 2.0)\n• WorkManager (Apache 2.0)",
                    Toast.LENGTH_LONG).show());
        }

        View btnPrivacy = findViewById(R.id.btn_yt_privacy_policy);
        if (btnPrivacy != null) {
            btnPrivacy.setOnClickListener(v ->
                Toast.makeText(this,
                    "Privacy Policy: youtube.com/t/privacy",
                    Toast.LENGTH_SHORT).show());
        }

        View btnTos = findViewById(R.id.btn_yt_about_tos);
        if (btnTos != null) {
            btnTos.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, YouTubeTosActivity.class)));
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
