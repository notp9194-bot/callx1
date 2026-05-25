package com.callx.app.activities;

import android.content.Intent;
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

public class XSettingsPrivacyActivity extends AppCompatActivity {

    private static final String PREFS = "x_privacy_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings_privacy);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_x_privacy);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Privacy and safety");
        }

        setupSwitch(R.id.sw_x_protect_posts, "protect_posts",   false);
        setupSwitch(R.id.sw_x_filter_dm,     "filter_dm",       true);
        setupSwitch(R.id.sw_x_find_by_email, "find_by_email",   true);
        setupSwitch(R.id.sw_x_find_by_phone, "find_by_phone",   true);

        // Photo tagging dialog
        View rowPhotoTag      = findViewById(R.id.row_x_photo_tagging);
        TextView tvPhotoTagVal = findViewById(R.id.tv_x_photo_tag_val);
        String[] tagOpts = {"Anyone", "People you follow", "Nobody"};
        if (tvPhotoTagVal != null) tvPhotoTagVal.setText(tagOpts[prefs.getInt("photo_tagging", 0)]);
        if (rowPhotoTag != null)
            rowPhotoTag.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Photo tagging")
                .setSingleChoiceItems(tagOpts, prefs.getInt("photo_tagging", 0), (dlg, which) -> {
                    prefs.edit().putInt("photo_tagging", which).apply();
                    tvPhotoTagVal.setText(tagOpts[which]);
                    dlg.dismiss();
                }).show());

        // DM allow dialog
        View rowDmAllow      = findViewById(R.id.row_x_dm_allow);
        TextView tvDmAllowVal = findViewById(R.id.tv_x_dm_allow_val);
        String[] dmOpts = {"Everyone", "People you follow", "Nobody"};
        if (tvDmAllowVal != null) tvDmAllowVal.setText(dmOpts[prefs.getInt("dm_allow", 0)]);
        if (rowDmAllow != null)
            rowDmAllow.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Allow message requests from")
                .setSingleChoiceItems(dmOpts, prefs.getInt("dm_allow", 0), (dlg, which) -> {
                    prefs.edit().putInt("dm_allow", which).apply();
                    tvDmAllowVal.setText(dmOpts[which]);
                    dlg.dismiss();
                }).show());

        // Blocked accounts
        View rowBlocked = findViewById(R.id.row_x_blocked);
        if (rowBlocked != null)
            rowBlocked.setOnClickListener(v ->
                startActivity(new Intent(this, XBlockedUsersActivity.class)));

        // Muted accounts
        View rowMuted = findViewById(R.id.row_x_muted);
        if (rowMuted != null)
            rowMuted.setOnClickListener(v ->
                startActivity(new Intent(this, XMutedUsersActivity.class)));
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
