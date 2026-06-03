package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;

/**
 * Feature: Custom Ringtone
 *
 * Ek simple helper jo custom ringtone URI ko SharedPreferences mein
 * save/load karta hai. IncomingRingService aur IncomingCallActivity
 * isey use karte hain.
 *
 * Settings screen mein:
 *   Uri uri = RingtoneSettingsHelper.getCustomRingtoneUri(ctx);
 *   Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
 *   i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, uri);
 *   i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
 *   i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,  false);
 *   startActivityForResult(i, REQ_RINGTONE);
 *
 * onActivityResult:
 *   if (requestCode == REQ_RINGTONE && resultCode == RESULT_OK) {
 *       Uri chosen = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
 *       RingtoneSettingsHelper.saveCustomRingtoneUri(ctx, chosen);
 *   }
 */
public class RingtoneSettingsHelper {

    private static final String PREFS       = "callx_call_settings";
    private static final String KEY_RINGTONE = "custom_ringtone_uri";

    /** Save the URI chosen by the user. Pass null to reset to system default. */
    public static void saveCustomRingtoneUri(Context ctx, Uri uri) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (uri != null) ed.putString(KEY_RINGTONE, uri.toString());
        else             ed.remove(KEY_RINGTONE);
        ed.apply();
    }

    /**
     * Returns the custom ringtone URI, or the system default ringtone URI
     * if no custom ringtone has been chosen.
     */
    public static Uri getCustomRingtoneUri(Context ctx) {
        String saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RINGTONE, null);
        if (saved != null && !saved.isEmpty()) {
            try { return Uri.parse(saved); } catch (Exception ignored) {}
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    }

    /** Returns true if user has set a custom ringtone (different from system default). */
    public static boolean hasCustomRingtone(Context ctx) {
        String saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RINGTONE, null);
        return saved != null && !saved.isEmpty();
    }
}
