package com.callx.app.activities;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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

/**
 * Lock screen shown when app resumes from background and a lock is set.
 * Tracks session-level unlock so it's only shown once per foreground session.
 */
public class LockScreenActivity extends AppCompatActivity {

    // Session-level unlock flag — reset when app goes to background
    private static boolean sUnlocked = false;
    public static boolean isUnlocked()  { return sUnlocked; }
    public static void resetUnlock()    { sUnlocked = false; }

    private ActivityLockScreenBinding binding;
    private AppLockManager lockManager;
    private StringBuilder pinInput = new StringBuilder();
    private List<View> pinDots = new ArrayList<>();
    private static final int PIN_LENGTH = AppLockManager.PIN_LENGTH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        lockManager = new AppLockManager(this);
        binding.tvLockType.setText("Locked — " + lockManager.getLockTypeLabel());

        switch (lockManager.getLockType()) {
            case AppLockManager.PIN:         showPinUI();         break;
            case AppLockManager.PATTERN:     showPatternUI();     break;
            case AppLockManager.FINGERPRINT: showFingerprintUI(); break;
            default: unlock(); break;
        }
    }

    @Override public void onBackPressed() {
        moveTaskToBack(true); // can't dismiss — must authenticate
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
        GridLayout grid = binding.gridNumpad;
        grid.removeAllViews();
        String[] keys = {"1","2","3","4","5","6","7","8","9","","0","⌫"};
        for (String k : keys) grid.addView(buildNumKey(k));
    }

    private View buildNumKey(String label) {
        TextView tv = new TextView(this);
        int size = dpToPx(60);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
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
            if (lockManager.checkPin(pin)) unlock();
            else Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Pattern ───────────────────────────────────────────────────────────

    private void showPatternUI() {
        binding.layoutPattern.setVisibility(View.VISIBLE);
        binding.patternLockView.clearPattern();
        binding.patternLockView.addPatternLockListener(new PatternLockViewListener() {
            @Override public void onStarted() {}
            @Override public void onProgress(List<PatternLockView.Dot> d) {}
            @Override public void onComplete(List<PatternLockView.Dot> dots) {
                String key = PatternLockUtils.patternToString(binding.patternLockView, dots);
                if (lockManager.checkPattern(key)) {
                    binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.CORRECT);
                    unlock();
                } else {
                    binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.WRONG);
                    binding.tvPatternHint.setText("Wrong pattern — try again");
                    binding.patternLockView.clearPattern();
                }
            }
            @Override public void onCleared() {
                binding.tvPatternHint.setText("Draw pattern to unlock");
            }
        });
    }

    // ── Fingerprint ───────────────────────────────────────────────────────

    private void showFingerprintUI() {
        binding.layoutFingerprint.setVisibility(View.VISIBLE);
        binding.btnBiometric.setOnClickListener(v -> launchBiometric());
        launchBiometric();
    }

    private void launchBiometric() {
        new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(
                        BiometricPrompt.AuthenticationResult r) { unlock(); }
                @Override public void onAuthenticationError(int c, CharSequence m) {
                    binding.tvFpHint.setText("Error: " + m);
                }
                @Override public void onAuthenticationFailed() {
                    binding.tvFpHint.setText("Failed — touch sensor again");
                }
            })
        .authenticate(new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock CallX")
            .setSubtitle("Use fingerprint or face unlock")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build());
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
