package com.callx.app.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.x.R;

public class XSettingsDisplayActivity extends AppCompatActivity {

    private static final String PREFS = "x_display_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings_display);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_x_display);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Display and sound");
        }

        // Font size
        View rowFont      = findViewById(R.id.row_x_font_size);
        TextView tvFontVal = findViewById(R.id.tv_x_font_val);
        String[] fontOpts = {"Small", "Default", "Large", "Largest"};
        if (tvFontVal != null) tvFontVal.setText(fontOpts[prefs.getInt("font_size", 1)]);
        if (rowFont != null)
            rowFont.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Font size")
                .setSingleChoiceItems(fontOpts, prefs.getInt("font_size", 1), (dlg, which) -> {
                    prefs.edit().putInt("font_size", which).apply();
                    tvFontVal.setText(fontOpts[which]);
                    dlg.dismiss();
                }).show());

        // Color theme
        View rowColor      = findViewById(R.id.row_x_color_theme);
        TextView tvColorVal = findViewById(R.id.tv_x_color_val);
        String[] colorOpts = {"Blue", "Yellow", "Red", "Purple", "Orange"};
        if (tvColorVal != null) tvColorVal.setText(colorOpts[prefs.getInt("color_theme", 0)]);
        if (rowColor != null)
            rowColor.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Colour")
                .setSingleChoiceItems(colorOpts, prefs.getInt("color_theme", 0), (dlg, which) -> {
                    prefs.edit().putInt("color_theme", which).apply();
                    tvColorVal.setText(colorOpts[which]);
                    dlg.dismiss();
                }).show());

        // Autoplay
        View rowAutoplay      = findViewById(R.id.row_x_autoplay);
        TextView tvAutoplayVal = findViewById(R.id.tv_x_autoplay_val);
        String[] autoplayOpts = {"On", "Wi-Fi only", "Off"};
        if (tvAutoplayVal != null) tvAutoplayVal.setText(autoplayOpts[prefs.getInt("autoplay", 0)]);
        if (rowAutoplay != null)
            rowAutoplay.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Autoplay")
                .setSingleChoiceItems(autoplayOpts, prefs.getInt("autoplay", 0), (dlg, which) -> {
                    prefs.edit().putInt("autoplay", which).apply();
                    tvAutoplayVal.setText(autoplayOpts[which]);
                    dlg.dismiss();
                }).show());

        // Image quality
        View rowImgQ      = findViewById(R.id.row_x_image_quality);
        TextView tvImgQVal = findViewById(R.id.tv_x_img_quality_val);
        String[] imgQOpts = {"Automatic", "High", "Medium"};
        if (tvImgQVal != null) tvImgQVal.setText(imgQOpts[prefs.getInt("image_quality", 0)]);
        if (rowImgQ != null)
            rowImgQ.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Image quality")
                .setSingleChoiceItems(imgQOpts, prefs.getInt("image_quality", 0), (dlg, which) -> {
                    prefs.edit().putInt("image_quality", which).apply();
                    tvImgQVal.setText(imgQOpts[which]);
                    dlg.dismiss();
                }).show());

        // Sensitive media
        SwitchCompat swSensitive = findViewById(R.id.sw_x_sensitive_media);
        if (swSensitive != null) {
            swSensitive.setChecked(prefs.getBoolean("sensitive_media", false));
            swSensitive.setOnCheckedChangeListener((b, c) ->
                prefs.edit().putBoolean("sensitive_media", c).apply());
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
