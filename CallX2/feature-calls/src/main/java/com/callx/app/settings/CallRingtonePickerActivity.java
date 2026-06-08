package com.callx.app.settings;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.utils.Constants;

/**
 * CallRingtonePickerActivity — User apna custom call ringtone set kar sakta hai.
 *
 * Usage:
 *   startActivity(new Intent(context, CallRingtonePickerActivity.class));
 *
 * Ya seedha settings mein ek button se:
 *   Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
 *   i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE);
 *
 * Selected ringtone URI SharedPrefs mein save hoti hai:
 *   prefs: PREF_CALL_SETTINGS
 *   key:   PREF_CALL_RINGTONE_URI
 *
 * IncomingRingService automatically yahi URI read karta hai call par.
 */
public class CallRingtonePickerActivity extends AppCompatActivity {

    private static final int REQ_RINGTONE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Current ringtone URI padho — preselect karo picker mein
        SharedPreferences prefs = getSharedPreferences(
            Constants.PREF_CALL_SETTINGS, MODE_PRIVATE);
        String currentUriStr = prefs.getString(Constants.PREF_CALL_RINGTONE_URI, null);

        Uri currentUri = null;
        if (currentUriStr != null && !currentUriStr.isEmpty()) {
            try { currentUri = Uri.parse(currentUriStr); }
            catch (Exception ignored) {}
        }
        if (currentUri == null) {
            currentUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }

        // System ringtone picker kholo
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Call Ringtone");
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri);
        startActivityForResult(intent, REQ_RINGTONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RINGTONE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri pickedUri = data.getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                SharedPreferences prefs = getSharedPreferences(
                    Constants.PREF_CALL_SETTINGS, MODE_PRIVATE);
                if (pickedUri != null) {
                    prefs.edit()
                        .putString(Constants.PREF_CALL_RINGTONE_URI, pickedUri.toString())
                        .apply();
                    Toast.makeText(this, "Call ringtone saved", Toast.LENGTH_SHORT).show();
                } else {
                    // "None" select kiya — default pe wapas
                    prefs.edit()
                        .remove(Constants.PREF_CALL_RINGTONE_URI)
                        .apply();
                    Toast.makeText(this, "Using default ringtone", Toast.LENGTH_SHORT).show();
                }
            }
            finish();
        }
    }
}
