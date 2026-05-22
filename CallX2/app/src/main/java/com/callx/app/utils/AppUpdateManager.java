package com.callx.app.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.callx.app.BuildConfig;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * AppUpdateManager — Firebase se version check karke in-app update dialog dikhata hai.
 *
 * ── Firebase mein yeh structure banao ─────────────────────────────────────────
 * /app_config/
 *     latest_version_code : 105          (int  — current app ka versionCode se compare hota hai)
 *     latest_version_name : "3.105"      (String — dialog mein dikhata hai)
 *     apk_download_url    : "https://..."  (String — APK download link)
 *     force_update        : false         (boolean — true hone par dialog cancel nahi hoga)
 *     update_message      : "Bug fixes aur nayi features"  (optional — dialog body)
 *
 * ── Usage (MainActivity.onCreate mein) ────────────────────────────────────────
 *     AppUpdateManager.check(this);
 *
 * ── Kaise kaam karta hai ──────────────────────────────────────────────────────
 * 1. Firebase se /app_config/ read karta hai.
 * 2. latest_version_code ko BuildConfig.VERSION_CODE se compare karta hai.
 * 3. Agar naya version available hai → AlertDialog dikhata hai.
 * 4. "Update Karo" button → browser mein APK URL khulta hai.
 * 5. force_update=true hone par dialog dismiss nahi hoti (user ko update karna hi hoga).
 * 6. force_update=false hone par "Baad Mein" button dikhta hai.
 */
public class AppUpdateManager {

    private static final String NODE_APP_CONFIG = "app_config";

    /** Check karo aur agar naya version ho toh dialog dikhao. */
    public static void check(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        DatabaseReference ref = FirebaseDatabase
                .getInstance(Constants.DB_URL)
                .getReference(NODE_APP_CONFIG);

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                if (!snap.exists()) return;

                Long latestCode   = snap.child("latest_version_code").getValue(Long.class);
                String latestName = snap.child("latest_version_name").getValue(String.class);
                String apkUrl     = snap.child("apk_download_url").getValue(String.class);
                Boolean force     = snap.child("force_update").getValue(Boolean.class);
                String message    = snap.child("update_message").getValue(String.class);

                if (latestCode == null || apkUrl == null || apkUrl.isEmpty()) return;

                int currentCode = BuildConfig.VERSION_CODE;
                boolean isForce = Boolean.TRUE.equals(force);

                if (latestCode > currentCode) {
                    // UI thread pe dialog dikhao
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!activity.isFinishing()) {
                            showUpdateDialog(activity, latestName, apkUrl, isForce, message);
                        }
                    });
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Silently ignore — update check fail hone par app normal chalta rahe
            }
        });
    }

    private static void showUpdateDialog(Activity activity, String versionName,
                                          String apkUrl, boolean force, String message) {

        String title = "Naya Update Available! \uD83C\uDF89";
        String vName = (versionName != null && !versionName.isEmpty())
                ? versionName : "Latest";
        String body  = (message != null && !message.isEmpty())
                ? message
                : "Version " + vName + " available hai.\nAbhi update karo nayi features aur bug fixes ke liye!";

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(body)
                .setCancelable(!force) // force update mein cancel nahi hoga
                .setPositiveButton("Update Karo \u2B07\uFE0F", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(activity,
                                "Browser open nahi ho raha. URL: " + apkUrl,
                                Toast.LENGTH_LONG).show();
                    }
                    if (force) {
                        // Force update: app ko background mein daalo
                        activity.moveTaskToBack(true);
                    }
                });

        if (!force) {
            builder.setNegativeButton("Baad Mein", (dialog, which) -> dialog.dismiss());
        }

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!force);
        dialog.show();
    }
}
