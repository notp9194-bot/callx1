package com.callx.app.activities;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;
public class YouTubeCaptionsSettingsActivity extends AppCompatActivity {
    private static final String PREFS = "yt_captions_prefs";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_captions_settings);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Toolbar toolbar = findViewById(R.id.toolbar_yt_captions);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_captions);
        }
        SwitchCompat swCaptions = findViewById(R.id.sw_yt_captions_on);
        if (swCaptions != null) {
            swCaptions.setChecked(prefs.getBoolean("captions_on", false));
            swCaptions.setOnCheckedChangeListener((b,c) -> prefs.edit().putBoolean("captions_on",c).apply());
        }
        String[] sizes = {"Small", "Normal", "Large", "Largest"};
        View rowSize = findViewById(R.id.row_yt_caption_size);
        TextView tvSize = findViewById(R.id.tv_yt_caption_size_val);
        if (tvSize != null) tvSize.setText(sizes[prefs.getInt("caption_size", 1)]);
        if (rowSize != null) rowSize.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(R.string.yt_caption_text_size)
                .setSingleChoiceItems(sizes, prefs.getInt("caption_size", 1), (dlg, which) -> {
                    prefs.edit().putInt("caption_size", which).apply();
                    if (tvSize != null) tvSize.setText(sizes[which]);
                    dlg.dismiss();
                }).show();
        });
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
