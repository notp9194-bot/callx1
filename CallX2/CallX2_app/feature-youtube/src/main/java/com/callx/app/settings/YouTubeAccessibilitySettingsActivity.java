package com.callx.app.settings;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;
public class YouTubeAccessibilitySettingsActivity extends AppCompatActivity {
    private static final String PREFS = "yt_a11y_prefs";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_accessibility_settings);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Toolbar toolbar = findViewById(R.id.toolbar_yt_accessibility);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_accessibility);
        }
        SwitchCompat swHideGesture = findViewById(R.id.sw_yt_hide_gesture_edu);
        if (swHideGesture != null) {
            swHideGesture.setChecked(prefs.getBoolean("hide_gesture_edu", false));
            swHideGesture.setOnCheckedChangeListener((b,c) -> prefs.edit().putBoolean("hide_gesture_edu",c).apply());
        }
        SwitchCompat swReduceAnim = findViewById(R.id.sw_yt_reduce_animations);
        if (swReduceAnim != null) {
            swReduceAnim.setChecked(prefs.getBoolean("reduce_animations", false));
            swReduceAnim.setOnCheckedChangeListener((b,c) -> prefs.edit().putBoolean("reduce_animations",c).apply());
        }
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
