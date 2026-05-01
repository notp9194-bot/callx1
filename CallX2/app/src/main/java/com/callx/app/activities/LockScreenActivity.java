package com.callx.app.activities;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
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
 * Lock screen — shown when app resumes from background and a lock is active.
 * Features:
 * - Brute-force protection display (remaining attempts + countdown timer)
 * - Progressive lockout: 30s → 60s → 2m → 5m → 30m
 * - Biometric fallback to PIN when fingerprint fails
 * - FLAG_SECURE prevents screenshot of lock screen
 */
public class LockScreenActivity extends AppCompatActivity {

    private static boolean sUnlocked = false;
    public static boolean isUnlocked()  { return sUnlocked; }
    public static void resetUnlock()    { sUnlocked = false; }

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

        lockManager = new AppLockManager(this);
        binding.tvLockType.setText("Locked — " + lockManager.getLockTypeLabel());

        // Brute-force UI elements (injected into existing layout)
        attemptsTv  = injectAttemptsView();
        countdownTv = injectCountdownView();

        if (lockManager.isLockedOut()) {
            showLockoutState();
            return;
        }

        switch (lockManager.getLockType()) {
            case AppLockManager.PIN:         showPinUI();         break;
            case AppLockManager.PATTERN:     showPatternUI();     break;
            case AppLockManager.FINGERPRINT: showFingerprintUI(); break;
            default: unlock(); break;
        }

        updateAttemptsDisplay();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) countDownTimer.cancel();
    }

    @Override public void onBackPressed() {
        moveTaskToBack(true);
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
        countdownTv.setVisibility(View.VISIBLE);
        attemptsTv.setVisibility(View.GONE);
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
                // Re-show unlock UI
                switch (lockManager.getLockType()) {
                    case AppLockManager.PIN:         showPinUI();         break;
                    case AppLockManager.PATTERN:     showPatternUI();     break;
                    case AppLockManager.FINGERPRINT: showFingerprintUI(); break;
                }
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
        // Add after tvLockType in the root layout
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
                        binding.patternLockView.clearPattern();
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
                    // Fallback to PIN if biometric fails and fallback is enabled
                    if (lockManager.isBiometricFallbackEnabled()) {
                        binding.layoutFingerprint.setVisibility(View.GONE);
                        binding.tvLockType.setText("Locked — PIN (fallback)");
                        showPinUI();
                    }
                }
                @Override public void onAuthenticationFailed() {
                    binding.tvFpHint.setText("Not recognised — try again");
                }
            });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock CallX")
            .setSubtitle("Use your biometric credential")
            .setNegativeButtonText("Use PIN instead")
            .build();
        prompt.authenticate(info);
    }

    // ── Unlock ────────────────────────────────────────────────────────────

    private void unlock() {
        sUnlocked = true;
        finish();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
