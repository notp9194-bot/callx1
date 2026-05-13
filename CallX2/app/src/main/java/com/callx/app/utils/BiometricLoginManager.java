package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.Executor;

/**
 * BiometricLoginManager — Production-grade Biometric Login system for CallX.
 *
 * Ye class App Lock se ALAG hai:
 *   - App Lock  → app background se aane pe screen guard
 *   - Bio Login → logout ke baad / fresh start pe Firebase re-authentication
 *
 * FIXES Applied:
 *   FIX-BL1: setNegativeButtonText + DEVICE_CREDENTIAL crash fix —
 *             BIOMETRIC_STRONG only use karo prompt mein, DEVICE_CREDENTIAL
 *             sirf hardware availability check mein. NegativeButton safe hai.
 *   FIX-BL2: isHardwareAvailable() — BIOMETRIC_STRONG alone check karo
 *             (DEVICE_CREDENTIAL wala check misleading tha, login prompt mein
 *              STRONG only chahiye)
 *   FIX-BL3: onNeedsReauth — biometric disable nahi karo seedha.
 *             Callback mein caller decide karega. Silent disable karne se
 *             Google-login users ka UID mismatch silently break hota tha.
 *   FIX-BL4: Google login UID safety — enable() pe provider type save karo
 *             taaki Google re-auth ke waqt caller aware ho.
 */
public class BiometricLoginManager {

    private static final String TAG        = "BiometricLoginMgr";
    private static final String PREFS_NAME = "callx_bio_login_v1";

    // Preference keys
    private static final String KEY_ENABLED         = "bio_login_enabled";
    private static final String KEY_SAVED_UID        = "bio_saved_uid";
    private static final String KEY_SAVED_EMAIL      = "bio_saved_email";
    private static final String KEY_SAVED_PROVIDER   = "bio_saved_provider"; // FIX-BL4
    private static final String KEY_FAIL_COUNT       = "bio_fail_count";
    private static final String KEY_DISABLED_UNTIL   = "bio_disabled_until_ms";

    // Provider constants
    public static final String PROVIDER_EMAIL  = "email";
    public static final String PROVIDER_GOOGLE = "google";

    // Max consecutive bio-login failures before disabling
    private static final int  MAX_BIO_FAILURES  = 3;
    private static final long DISABLE_PERIOD_MS = 30 * 60 * 1000L; // 30 min

    private final SharedPreferences prefs;

    // ── Singleton ──────────────────────────────────────────────────────────

    private static BiometricLoginManager sInstance;

    public static synchronized BiometricLoginManager getInstance(@NonNull Context ctx) {
        if (sInstance == null) sInstance = new BiometricLoginManager(ctx.getApplicationContext());
        return sInstance;
    }

    private BiometricLoginManager(@NonNull Context ctx) {
        SharedPreferences sp;
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            sp = EncryptedSharedPreferences.create(
                    ctx, PREFS_NAME, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPreferences init failed, using plain fallback: " + e.getMessage());
            sp = ctx.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
        }
        this.prefs = sp;
    }

    // ── State checks ───────────────────────────────────────────────────────

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public boolean isTemporarilyDisabled() {
        long until = prefs.getLong(KEY_DISABLED_UNTIL, 0);
        return System.currentTimeMillis() < until;
    }

    @Nullable
    public String getSavedUid() {
        return prefs.getString(KEY_SAVED_UID, null);
    }

    @Nullable
    public String getSavedEmail() {
        return prefs.getString(KEY_SAVED_EMAIL, null);
    }

    /** FIX-BL4: Provider type — PROVIDER_EMAIL ya PROVIDER_GOOGLE */
    @NonNull
    public String getSavedProvider() {
        return prefs.getString(KEY_SAVED_PROVIDER, PROVIDER_EMAIL);
    }

    public long getRemainingDisableMinutes() {
        long until = prefs.getLong(KEY_DISABLED_UNTIL, 0);
        long remaining = until - System.currentTimeMillis();
        return remaining > 0 ? (remaining / 1000 / 60) + 1 : 0;
    }

    // ── Enable / Disable ──────────────────────────────────────────────────

    /**
     * Email/password login ke baad call karo.
     */
    public void enable(@NonNull String uid, @NonNull String email) {
        enable(uid, email, PROVIDER_EMAIL);
    }

    /**
     * FIX-BL4: Provider-aware enable — Google login ke liye PROVIDER_GOOGLE pass karo.
     */
    public void enable(@NonNull String uid, @NonNull String email, @NonNull String provider) {
        prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_SAVED_UID, uid)
                .putString(KEY_SAVED_EMAIL, email)
                .putString(KEY_SAVED_PROVIDER, provider)
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_DISABLED_UNTIL, 0)
                .apply();
        Log.d(TAG, "Biometric login enabled for UID: " + uid.substring(0, Math.min(6, uid.length())) + "... provider=" + provider);
    }

    public void disable() {
        prefs.edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_SAVED_UID)
                .remove(KEY_SAVED_EMAIL)
                .remove(KEY_SAVED_PROVIDER)
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_DISABLED_UNTIL, 0)
                .apply();
        Log.d(TAG, "Biometric login disabled.");
    }

    public boolean recordFailure() {
        int count = prefs.getInt(KEY_FAIL_COUNT, 0) + 1;
        if (count >= MAX_BIO_FAILURES) {
            long until = System.currentTimeMillis() + DISABLE_PERIOD_MS;
            prefs.edit()
                    .putInt(KEY_FAIL_COUNT, count)
                    .putLong(KEY_DISABLED_UNTIL, until)
                    .apply();
            Log.w(TAG, "Biometric login temporarily disabled due to " + count + " failures.");
            return false;
        }
        prefs.edit().putInt(KEY_FAIL_COUNT, count).apply();
        return true;
    }

    public void resetFailures() {
        prefs.edit()
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_DISABLED_UNTIL, 0)
                .apply();
    }

    // ── Hardware capability check ──────────────────────────────────────────

    /**
     * FIX-BL2: BIOMETRIC_STRONG alone check karo.
     * Login prompt mein DEVICE_CREDENTIAL allowed nahi (NegativeButton conflict).
     * Agar sirf device PIN hai aur fingerprint nahi, toh biometric login enable
     * nahi hona chahiye — isliye BIOMETRIC_STRONG strict check sahi hai.
     */
    public static boolean isHardwareAvailable(@NonNull Context ctx) {
        BiometricManager bm = BiometricManager.from(ctx);
        int result = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    @NonNull
    public static String getHardwareStatusMessage(@NonNull Context ctx) {
        BiometricManager bm = BiometricManager.from(ctx);
        switch (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return "available";
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                return "Is device mein biometric sensor nahi hai";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "Biometric sensor abhi available nahi";
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return "Device Settings mein fingerprint/face setup karo pehle";
            default:
                return "Biometric available nahi";
        }
    }

    // ── Biometric Prompt ──────────────────────────────────────────────────

    /**
     * FIX-BL1: setNegativeButtonText + DEVICE_CREDENTIAL = IllegalArgumentException crash.
     * Solution: BIOMETRIC_STRONG only use karo prompt mein (no DEVICE_CREDENTIAL).
     * NegativeButtonText tab safe hai.
     *
     * FIX-BL3: onNeedsReauth — callback mein caller decide karega disable/redirect.
     * Yahan seedha disable() nahi karo.
     */
    public void showBiometricPrompt(
            @NonNull FragmentActivity activity,
            @NonNull BiometricLoginCallback callback) {

        if (!isEnabled()) {
            callback.onError("Biometric login enabled nahi hai");
            return;
        }
        if (isTemporarilyDisabled()) {
            callback.onTemporarilyDisabled(getRemainingDisableMinutes());
            return;
        }

        String savedUid = getSavedUid();
        if (savedUid == null || savedUid.isEmpty()) {
            callback.onError("Saved account nahi mila. Email se login karo.");
            // FIX-BL3: disable yahan bhi theek hai — UID nahi hai matlab corrupted state
            disable();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(activity);
        BiometricPrompt prompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {

                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        resetFailures();
                        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
                        if (currentUser != null && currentUser.getUid().equals(savedUid)) {
                            // Force token refresh — expired token bhi handle hoga
                            currentUser.reload()
                                .addOnSuccessListener(v -> {
                                    currentUser.getIdToken(true)
                                        .addOnSuccessListener(tokenResult ->
                                            callback.onSuccess(currentUser))
                                        .addOnFailureListener(e -> {
                                            // FIX-BL3: disable nahi karo — caller handle karega
                                            callback.onNeedsReauth(savedUid, getSavedProvider());
                                        });
                                })
                                .addOnFailureListener(e -> {
                                    // FIX-BL3: network error ya user deleted
                                    // disable() caller decide karega
                                    callback.onNeedsReauth(savedUid, getSavedProvider());
                                });
                        } else {
                            // UID mismatch — different user logged in
                            // FIX-BL3: disable yahan safe hai (wrong UID = corrupted state)
                            disable();
                            callback.onNeedsReauth(savedUid, getSavedProvider());
                        }
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errMsg) {
                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                            callback.onUserCancelled();
                        } else if (errorCode == BiometricPrompt.ERROR_LOCKOUT
                                || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                            callback.onTemporarilyDisabled(0);
                        } else {
                            callback.onError("Biometric error: " + errMsg);
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        boolean canRetry = recordFailure();
                        if (!canRetry) {
                            callback.onTemporarilyDisabled(getRemainingDisableMinutes());
                        } else {
                            int remaining = MAX_BIO_FAILURES - prefs.getInt(KEY_FAIL_COUNT, 0);
                            callback.onAuthFailed(remaining);
                        }
                    }
                });

        String email = getSavedEmail();
        String subtitle = (email != null && !email.isEmpty())
                ? email
                : "Apna fingerprint ya face use karo";

        // FIX-BL1: BIOMETRIC_STRONG only — DEVICE_CREDENTIAL nahi.
        // setNegativeButtonText sirf tabhi valid hai jab DEVICE_CREDENTIAL NOT included.
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("CallX mein login karo")
                .setSubtitle(subtitle)
                .setDescription("Apna registered fingerprint ya face use karo")
                .setNegativeButtonText("Email se login karo")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build();

        prompt.authenticate(promptInfo);
    }

    // ── Callback interface ────────────────────────────────────────────────

    public interface BiometricLoginCallback {
        void onSuccess(@NonNull FirebaseUser user);

        /**
         * FIX-BL3/BL4: provider bhi pass karo taaki caller
         * Google vs Email re-auth alag handle kar sake.
         */
        void onNeedsReauth(@NonNull String savedUid, @NonNull String provider);

        void onUserCancelled();
        void onAuthFailed(int remainingAttempts);
        void onTemporarilyDisabled(long remainingMinutes);
        void onError(@NonNull String message);
    }
}
