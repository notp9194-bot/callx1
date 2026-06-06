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

public class XSettingsDataActivity extends AppCompatActivity {

    private static final String PREFS = "x_data_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings_data);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_x_data);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Data usage");
        }

        setupSwitch(R.id.sw_x_data_saver,      "data_saver",       false);
        setupSwitch(R.id.sw_x_autoplay_mobile,  "autoplay_mobile",  true);
        setupSwitch(R.id.sw_x_hq_wifi,          "hq_wifi_only",     false);

        // Upload quality dialog
        View rowUpload      = findViewById(R.id.row_x_upload_quality);
        TextView tvUploadVal = findViewById(R.id.tv_x_upload_quality_val);
        String[] uploadOpts  = {"Automatic", "High", "Medium", "Low"};
        if (tvUploadVal != null) tvUploadVal.setText(uploadOpts[prefs.getInt("upload_quality", 0)]);
        if (rowUpload != null)
            rowUpload.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Image upload quality")
                .setSingleChoiceItems(uploadOpts, prefs.getInt("upload_quality", 0), (dlg, which) -> {
                    prefs.edit().putInt("upload_quality", which).apply();
                    tvUploadVal.setText(uploadOpts[which]);
                    dlg.dismiss();
                }).show());
    }

    private void setupSwitch(int id, String key, boolean def) {
        SwitchCompat sw = findViewById(id);
        if (sw == null) return;
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean(key, c).apply());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
