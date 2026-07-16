package com.callx.app.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import com.callx.app.utils.XFirebaseUtils;

/**
 * XSettingsSecurityActivity — v32 production upgrade.
 *
 * Previously:
 *   "Apps and sessions" → Toast "coming soon"
 *   "Logged-in devices" → Toast "coming soon"
 *
 * v32 Fix:
 *   Both rows now navigate to fully-implemented Activities:
 *     - XActiveSessionsActivity  (Apps and sessions)
 *     - XLoggedDevicesActivity   (Logged-in devices)
 *
 * Additionally, the 2FA and password protection switches now persist their
 * state to Firebase (x/users/{uid}/security) so the setting survives
 * re-installs and is visible to the server side for enforcement.
 */
public class XSettingsSecurityActivity extends AppCompatActivity {

    private static final String PREFS = "x_security_prefs";
    private SharedPreferences prefs;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings_security);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        Toolbar toolbar = findViewById(R.id.toolbar_x_security);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Security and account access");
        }

        // 2FA — persists to SharedPrefs + Firebase
        setupSecuritySwitch(R.id.sw_x_2fa, "two_factor", false, "twoFactorEnabled");

        // Password protection — persists to SharedPrefs + Firebase
        setupSecuritySwitch(R.id.sw_x_pwd_protection, "pwd_protection", false, "passwordProtected");

        // ── Apps and sessions — FULLY IMPLEMENTED ─────────────────────────
        View rowSessions = findViewById(R.id.row_x_apps_sessions);
        if (rowSessions != null) {
            rowSessions.setOnClickListener(v ->
                    startActivity(new Intent(this, XActiveSessionsActivity.class)));
        }

        // ── Logged-in devices — FULLY IMPLEMENTED ─────────────────────────
        View rowDevices = findViewById(R.id.row_x_devices);
        if (rowDevices != null) {
            rowDevices.setOnClickListener(v ->
                    startActivity(new Intent(this, XLoggedDevicesActivity.class)));
        }
    }

    /**
     * Sets up a SwitchCompat that persists to both SharedPrefs (instant)
     * and Firebase (cross-device sync).
     */
    private void setupSecuritySwitch(int viewId, String prefsKey, boolean def,
                                     String firebaseKey) {
        SwitchCompat sw = findViewById(viewId);
        if (sw == null) return;

        // Load from SharedPrefs immediately (no network wait)
        sw.setChecked(prefs.getBoolean(prefsKey, def));

        // Also load from Firebase for cross-device accuracy
        if (!myUid.isEmpty()) {
            XFirebaseUtils.xUserRef(myUid).child("security").child(firebaseKey)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            Boolean val = snap.getValue(Boolean.class);
                            if (val != null) {
                                sw.setChecked(val);
                                prefs.edit().putBoolean(prefsKey, val).apply();
                            }
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    });
        }

        sw.setOnCheckedChangeListener((button, isChecked) -> {
            // Persist locally
            prefs.edit().putBoolean(prefsKey, isChecked).apply();
            // Persist to Firebase (cross-device)
            if (!myUid.isEmpty()) {
                XFirebaseUtils.xUserRef(myUid).child("security")
                        .child(firebaseKey).setValue(isChecked);
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
