package com.callx.app.linkeddevices;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

/** Small helpers for naming/identifying this physical install. */
public final class DeviceInfoUtil {

    private static final String PREFS = "device_info_prefs";
    private static final String KEY_INSTALL_ID = "install_id";

    private DeviceInfoUtil() {}

    /** Human-readable label shown in the Linked Devices list, e.g. "Redmi Note 12 Pro". */
    public static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        }
        return capitalize(manufacturer) + " " + model;
    }

    public static String getPlatform() {
        return "Android";
    }

    public static String getOsVersion() {
        return "Android " + Build.VERSION.RELEASE;
    }

    /**
     * A random id generated once per install and persisted — used only for local
     * bookkeeping (e.g. distinguishing "this device" in the list before Firebase
     * assigns the companion its own anonymous uid). Not a security token.
     */
    public static String getOrCreateInstallId(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_INSTALL_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_INSTALL_ID, id).apply();
        }
        return id;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
