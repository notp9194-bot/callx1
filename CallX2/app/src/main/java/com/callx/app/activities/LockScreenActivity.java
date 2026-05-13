package com.callx.app.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import com.andrognito.patternlockview.PatternLockView;
import com.andrognito.patternlockview.listener.PatternLockViewListener;
import com.andrognito.patternlockview.utils.PatternLockUtils;
import com.callx.app.R;
import com.callx.app.databinding.ActivityLockScreenBinding;
import com.callx.app.utils.AppLockManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Lock screen — app background se resume hone pe dikhta hai.
 *
 * FIXES Applied:
 *   FIX-LS1: Biometric onAuthenticationFailed → AppLockManager.recordBiometricFailure()
 *             call hoga ab. Pehle infinite wrong attempts possible the.
 *   FIX-LS2: Fingerprint fallback infinite loop fix —
 *             cancel pe sirf device credential prompt dikhao, recursion nahi.
 *   FIX-LS3: FIX-AL1 se: AppLockManager.getInstance() use karo, new nahi.
 *   FIX-LS4: Pattern wrong ke baad immediate clearPattern() — UX fix.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String LOCK_PREFS   = "callx_lock_state";
    private static final String KEY_UNLOCKED = "session_unlocked";

    public static void markUnlocked(android.content.Context ctx) {
        ctx.getSharedPreferences(LOCK_PREFS, android.content.Context.MODE_PRIVATE)
           .edit().putBoolean(KEY_UNLOCKED, true).apply();
    }

    public static boolean isUnlocked(android.content.Context ctx) {
        return ctx.getSharedPreferences(LOCK_PREFS, android.content.Context.MODE_PRIVATE)
                  .getBoolean(KEY_UNLOCKED, false);
    }

    public static void resetUnlock(android.content.Context ctx) {
        ctx.getSharedPreferences(LOCK_PREFS, android.content.Context.MODE_PRIVATE)
           .edit().putBoolean(KEY_UNLOCKED, false).apply();
    }

    private ActivityLockScreenBinding binding;
    private AppLockManager lockManager;
    private StringBuilder pinInput = new StringBuilder();
    private List<View> pinDots = new ArrayList<>();
    private static final int PIN_LENGTH = AppLockManager.PIN_LENGTH;

    private CountDownTimer countDownTimer;
    private TextView countdownTv;
    private TextView attemptsTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // FIX-LS3: Singleton use karo
        lockManager = AppLockManager.getInstance(this);
        binding.tvLockType.setText("Locked — " + lockManager.getLockTypeLabel());

        attemptsTv  = injectAttemptsView();
        countdownTv = injectCountdownView();

        if (lockManager.isLockedOut()) {
            showLockoutState();
            return;
        }

        showUnlockUI();
        updateAttemptsDisplay();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) countDownTimer.cancel();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void showUnlockUI() {
        String lockType = lockManager.getLockType();
        switch (lockType) {
            case AppLockManager.PIN:
                showPinUI();
                break;
            case AppLockManager.PATTERN:
                showPatternUI();
                break;
            case AppLockManager.FINGERPRINT:
                showFingerprintUI();
                break;
            default:
                unlock();
                break;
        }
    }

    // ── Lockout state ─────────────────────────────────────────────────────

    private void showLockoutState() {
        binding.layoutPin.setVisibility(View.GONE);
        binding.layoutPattern.setVisibility(View.GONE);
        binding.layoutFingerprint.setVisibility(View.GONE);
        startLockoutCountdown();
    }

    private void startLockoutCountdown() {
        long remaining = lockManager.getRemainingLockoutMs();
        if (remaining <= 0) {
            countdownTv.setVisibility(View.GONE);
            showUnlockUI();
            updateAttemptsDisplay();
            return;
        }

        countdownTv.setVisibility(View.VISIBLE);
        attemptsTv.setVisibility(View.GONE);

        if (countDownTimer != null) countDownTimer.cancel();
        countDownTimer = new CountDownTimer(remaining, 1000) {
            @Override public void onTick(long ms) {
                long sec = ms / 1000;
                String text = sec >= 60
                    ? String.format(Locale.getDefault(), "Try again in %d min %02d sec", sec / 60, sec % 60)
                    : String.format(Locale.getDefault(), "Try again in %d seconds", sec);
                countdownTv.setText(text);
            }
            @Override public void onFinish() {
                countdownTv.setVisibility(View.GONE);
                attemptsTv.setVisibility(View.VISIBLE);
                showUnlockUI();
                updateAttemptsDisplay();
            }
        }.start();
    }

    private void updateAttemptsDisplay() {
        int remaining = lockManager.getRemainingAttempts();
        int failed    = lockManager.getFailedAttempts();
        if (failed > 0) {
            String txt = remaining == 0
                ? "Account temporarily locked"
                : remaining + " attempt" + (remaining == 1 ? "" : "s") + " remaining";
            attemptsTv.setText(txt);
            attemptsTv.setTextColor(remaining <= 2 ? 0xFFEF4444 : 0xFFD0E4FF);
            attemptsTv.setVisibility(View.VISIBLE);
        } else {
            attemptsTv.setVisibility(View.GONE);
        }
    }

    // ── Injected views ────────────────────────────────────────────────────

    private TextView injectAttemptsView() {
        TextView tv = new TextView(this);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFD0E4FF);
        tv.setTextSize(13);
        tv.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dpToPx(8), 0, dpToPx(8));
        tv.setLayoutParams(lp);
        ViewGroup parent = (ViewGroup) binding.tvLockType.getParent();
        int idx = parent.indexOfChild(binding.tvLockType);
        parent.addView(tv, idx + 1);
        return tv;
    }

    private TextView injectCountdownView() {
        TextView tv = new TextView(this);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFFF8080);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dpToPx(12), 0, dpToPx(12));
        tv.setLayoutParams(lp);
        ViewGroup parent = (ViewGroup) binding.tvLockType.getParent();
        int idx = parent.indexOfChild(binding.tvLockType);
        parent.addView(tv, idx + 2);
        return tv;
    }

    // ── PIN ───────────────────────────────────────────────────────────────

    private void showPinUI() {
        binding.layoutPin.setVisibility(View.VISIBLE);
        buildPinDots();
        buildNumpad();
    }

    private void buildPinDots() {
        binding.pinDots.removeAllViews();
        pinDots.clear();
        for (int i = 0; i < PIN_LENGTH; i++) {
            View dot = new View(this);
            int size = dpToPx(16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dpToPx(10), 0, dpToPx(10), 0);
            dot.setLayoutParams(lp);
            dot.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_circle_white));
            if (dot.getBackground() != null) dot.getBackground().setAlpha(80);
            pinDots.add(dot);
            binding.pinDots.addView(dot);
        }
    }

    private void updatePinDots() {
        for (int i = 0; i < pinDots.size(); i++) {
            View dot = pinDots.get(i);
            if (dot.getBackground() != null)
                dot.getBackground().setAlpha(i < pinInput.length() ? 255 : 80);
        }
    }

    private void buildNumpad() {
        android.widget.GridLayout grid = binding.gridNumpad;
        grid.removeAllViews();
        String[] keys = {"1","2","3","4","5","6","7","8","9","","0","⌫"};
        for (String k : keys) grid.addView(buildNumKey(k));
    }

    private View buildNumKey(String label) {
        TextView tv = new TextView(this);
        int size = dpToPx(60);
        android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
        lp.width = size; lp.height = size;
        lp.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        tv.setLayoutParams(lp);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(20);
        tv.setTextColor(0xFFFFFFFF);
        if (!label.isEmpty()) {
            tv.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_circle_white));
            if (tv.getBackground() != null) tv.getBackground().setAlpha(60);
            tv.setOnClickListener(v -> onNumKey(label));
        }
        return tv;
    }

    private void onNumKey(String key) {
        if (lockManager.isLockedOut()) { showLockoutState(); return; }
        if ("⌫".equals(key)) {
            if (pinInput.length() > 0) pinInput.deleteCharAt(pinInput.length() - 1);
        } else if (pinInput.length() < PIN_LENGTH) {
            pinInput.append(key);
        }
        updatePinDots();
        if (pinInput.length() == PIN_LENGTH) {
            String pin = pinInput.toString();
            pinInput = new StringBuilder();
            updatePinDots();
            if (lockManager.checkPin(pin)) {
                unlock();
            } else {
                if (lockManager.isLockedOut()) {
                    showLockoutState();
                } else {
                    updateAttemptsDisplay();
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // ── Pattern ───────────────────────────────────────────────────────────

    private void showPatternUI() {
        binding.layoutPattern.setVisibility(View.VISIBLE);
        binding.patternLockView.clearPattern();
        binding.patternLockView.addPatternLockListener(new PatternLockViewListener() {
            @Override public void onStarted() {}
            @Override public void onProgress(List<PatternLockView.Dot> dots) {}
            @Override public void onComplete(List<PatternLockView.Dot> dots) {
                if (lockManager.isLockedOut()) { showLockoutState(); return; }
                if (dots.size() < 4) {
                    binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.WRONG);
                    binding.tvPatternHint.setText("Pattern bahut chhota hai (min 4 points)");
                    // FIX-LS4: immediate clear on error
                    binding.patternLockView.postDelayed(
                        () -> binding.patternLockView.clearPattern(), 500);
                    return;
                }
                String key = PatternLockUtils.patternToString(binding.patternLockView, dots);
                if (lockManager.checkPattern(key)) {
                    binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.CORRECT);
                    unlock();
                } else {
                    binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.WRONG);
                    if (lockManager.isLockedOut()) {
                        showLockoutState();
                    } else {
                        updateAttemptsDisplay();
                        binding.tvPatternHint.setText("Wrong pattern. Try again.");
                        // FIX-LS4: clear after brief wrong animation
                        binding.patternLockView.postDelayed(
                            () -> binding.patternLockView.clearPattern(), 500);
                    }
                }
            }
            @Override public void onCleared() {}
        });
    }

    // ── Fingerprint ───────────────────────────────────────────────────────

    private void showFingerprintUI() {
        binding.layoutFingerprint.setVisibility(View.VISIBLE);
        binding.btnBiometric.setOnClickListener(v -> launchBiometricPrompt());
        launchBiometricPrompt();
    }

    private void launchBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    unlock();
                }
                @Override public void onAuthenticationError(int code, CharSequence msg) {
                    if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                            || code == BiometricPrompt.ERROR_USER_CANCELED) {
                        // FIX-LS2: Infinite loop fix —
                        // "PIN/Pattern use karo" press kiya → seedha device credential prompt,
                        // NO recursive showFingerprintUI() ya showUnlockUI() call.
                        showDeviceCredentialFallback();
                    } else if (code == BiometricPrompt.ERROR_LOCKOUT
                            || code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                        // System biometric lockout — device credential only
                        showDeviceCredentialFallback();
                    }
                    // Other errors: silently ignore (user can tap button again)
                }
                @Override public void onAuthenticationFailed() {
                    // FIX-LS1: Record biometric failure — lockout trigger hoga 5 attempts pe
                    lockManager.recordBiometricFailure();
                    if (lockManager.isLockedOut()) {
                        showLockoutState();
                    } else {
                        updateAttemptsDisplay();
                        binding.tvFpHint.setText("Pehchana nahi — dobara try karo ("
                            + lockManager.getRemainingAttempts() + " attempts bache)");
                    }
                }
            });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("CallX Unlock Karo")
            .setSubtitle("Apna biometric credential use karo")
            // FIX-LS2: BIOMETRIC_STRONG only — DEVICE_CREDENTIAL nahi yahan
            // NegativeButton tab valid hai
            .setNegativeButtonText(lockManager.isBiometricFallbackEnabled()
                ? "PIN/Pattern use karo" : "Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build();
        prompt.authenticate(info);
    }

    /**
     * FIX-LS2: Device credential only prompt — BIOMETRIC_STRONG + DEVICE_CREDENTIAL.
     * NegativeButton yahan nahi hoga (not allowed with DEVICE_CREDENTIAL).
     * Ye infinite loop fix karta hai.
     */
    private void showDeviceCredentialFallback() {
        // Hide fingerprint layout — show plain screen
        binding.layoutFingerprint.setVisibility(View.GONE);

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    unlock();
                }
                @Override public void onAuthenticationError(int code, CharSequence msg) {
                    // User ne cancel kiya — app minimize karo
                    moveTaskToBack(true);
                }
                @Override public void onAuthenticationFailed() {
                    // Device credential failure — system handles lockout
                }
            });

        // FIX-LS2: BIOMETRIC_STRONG | DEVICE_CREDENTIAL — NO NegativeButtonText
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("CallX Unlock Karo")
            .setSubtitle("Device PIN / Pattern use karo")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build();
        prompt.authenticate(info);
    }

    // ── Unlock ────────────────────────────────────────────────────────────

    private void unlock() {
        markUnlocked(this);
        finish();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
