package com.callx.app.base;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.utils.FirebaseUtils;

/**
 * BaseActivity — common foundation for all CallX activities.
 *
 * WhatsApp-level patterns applied here:
 *   1. Auth guard  — if user is not signed in, redirect to login
 *   2. Presence    — mark online on resume, offline on pause
 *   3. Back press  — consistent behaviour across API levels
 *
 * Feature activities should extend this instead of AppCompatActivity.
 *
 * To opt out of the auth guard (e.g. LoginActivity itself), override
 * requiresAuth() and return false.
 */
public abstract class BaseActivity extends AppCompatActivity {

    /** Override to return false in LoginActivity / SplashActivity. */
    protected boolean requiresAuth() {
        return true;
    }

    /**
     * Return the Intent to launch when the user is not authenticated.
     * Override in the :app module or a dedicated :feature-auth module.
     * Returning null skips the redirect (useful during development).
     */
    @Nullable
    protected Intent getLoginIntent() {
        return null; // override in :app to point at your LoginActivity
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (requiresAuth() && !isAuthenticated()) {
            redirectToLogin();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isAuthenticated()) updatePresence(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isAuthenticated()) updatePresence(false);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    protected boolean isAuthenticated() {
        String uid = FirebaseUtils.getCurrentUid();
        return uid != null && !uid.isEmpty();
    }

    protected String myUid() {
        return FirebaseUtils.getCurrentUid();
    }

    protected String myName() {
        return FirebaseUtils.getCurrentName();
    }

    private void redirectToLogin() {
        Intent intent = getLoginIntent();
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        }
    }

    private void updatePresence(boolean online) {
        String uid = myUid();
        if (uid == null || uid.isEmpty()) return;
        try {
            FirebaseUtils.getUserRef(uid).child("online").setValue(online);
            if (!online) {
                FirebaseUtils.getUserRef(uid).child("lastSeen")
                    .setValue(System.currentTimeMillis());
            }
        } catch (Exception ignored) {}
    }
}
