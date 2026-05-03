package com.callx.app.activities;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import com.callx.app.databinding.ActivityAppLockBinding;
import com.callx.app.utils.AppLockManager;
import java.util.List;
import java.util.concurrent.Executor;

public class AppLockActivity extends AppCompatActivity {

    public static final String EXTRA_MODE  = "mode";
    public static final String EXTRA_SETUP = "setup"; // true = setup, false = unlock

    public static final String MODE_PIN         = AppLockManager.PIN;
    public static final String MODE_PATTERN     = AppLockManager.PATTERN;
    public static final String MODE_FINGERPRINT = AppLockManager.FINGERPRINT;

    private ActivityAppLockBinding binding;
    private AppLockManager lockManager;
    private String mode;
    private boolean isSetup;

    // PIN state
    private StringBuilder pinInput = new StringBuilder();
    private String firstPin = null; // for confirmation step
    private boolean confirmingPin = false;
    private List<View> pinDots;

    // Pattern state
    private String firstPattern = null;
    private boolean confirmingPattern = false;

    private static final int PIN_LENGTH = AppLockManager.PIN_LENGTH;
    private static final int DOT_SIZE_DP = 16;
    private static final int DOT_MARGIN_DP = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppLockBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        lockManager = new AppLockManager(this);
        mode    = getIntent().getStringExtra(EXTRA_MODE);
        isSetup = getIntent().getBooleanExtra(EXTRA_SETUP, true);

        if (mode == null) mode = MODE_PIN;

        setupUI();
    }

    private void setupUI() {
        switch (mode) {
            case MODE_PIN:
                showPinUI();
                break;
            case MODE_PATTERN:
                showPatternUI();
                break;
            case MODE_FINGERPRINT:
                showFingerprintUI();
                break;
        }

        // Show remove lock button if a lock is already set
        if (isSetup && !AppLockManager.NONE.equals(lockManager.getLockType())) {
            binding.btnRemoveLock.setVisibility(View.VISIBLE);
            binding.btnRemoveLock.setOnClickListener(v -> {
                lockManager.clearLock();
                Toast.makeText(this, "App lock removed", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        }
    }

    // ── PIN ───────────────────────────────────────────────────────────────

    private void showPinUI() {
        binding.ivLockIcon.setImageResource(R.drawable.ic_send_fill);
        binding.tvLockTitle.setText(isSetup ? "Set PIN" : "Enter PIN");
        binding.tvLockDesc.setText(isSetup
            ? "Create a " + PIN_LENGTH + "-digit PIN to lock the app"
            : "Enter your PIN to unlock");
        binding.layoutPin.setVisibility(View.VISIBLE);

        buildPinDots();
        buildNumpad();
    }

    private void buildPinDots() {
        binding.pinDots.removeAllViews();
        pinDots = new java.util.ArrayList<>();
        int dp = DOT_SIZE_DP;
        int margin = DOT_MARGIN_DP;

        for (int i = 0; i < PIN_LENGTH; i++) {
            View dot = new View(this);
            int size = dpToPx(dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dpToPx(margin), 0, dpToPx(margin), 0);
            dot.setLayoutParams(lp);
            dot.setBackground(ContextCompat.getDrawable(this, R.drawable.circle_avatar_bg));
            pinDots.add(dot);
            binding.pinDots.addView(dot);
        }
        updatePinDots();
    }

    private void updatePinDots() {
        for (int i = 0; i < pinDots.size(); i++) {
            pinDots.get(i).getBackground().setTint(
                i < pinInput.length()
                    ? getColor(R.color.brand_primary)
                    : getColor(R.color.divider));
        }
    }

    private void buildNumpad() {
        GridLayout grid = binding.gridNumpad;
        grid.removeAllViews();
        String[] keys = {"1","2","3","4","5","6","7","8","9","","0","⌫"};
        for (String k : keys) {
            View btn = buildNumKey(k);
            grid.addView(btn);
        }
    }

    private View buildNumKey(String label) {
        TextView tv = new TextView(this);
        int size = dpToPx(64);
        GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
        glp.width  = size;
        glp.height = size;
        glp.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        tv.setLayoutParams(glp);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(20);
        tv.setTextColor(getColor(R.color.text_primary));

        if (!label.isEmpty()) {
            tv.setBackground(ContextCompat.getDrawable(this, R.drawable.circle_avatar_bg));
            tv.setOnClickListener(v -> onNumKey(label));
        }
        return tv;
    }

    private void onNumKey(String key) {
        if ("⌫".equals(key)) {
            if (pinInput.length() > 0)
                pinInput.deleteCharAt(pinInput.length() - 1);
        } else if (pinInput.length() < PIN_LENGTH) {
            pinInput.append(key);
        }
        updatePinDots();

        if (pinInput.length() == PIN_LENGTH) {
            onPinComplete(pinInput.toString());
            pinInput = new StringBuilder();
            updatePinDots();
        }
    }

    private void onPinComplete(String pin) {
        if (isSetup) {
            if (!confirmingPin) {
                firstPin = pin;
                confirmingPin = true;
                binding.tvLockTitle.setText("Confirm PIN");
                binding.tvLockDesc.setText("Enter the PIN again to confirm");
            } else {
                if (pin.equals(firstPin)) {
                    lockManager.setPin(pin);
                    Toast.makeText(this, "PIN set successfully!", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, "PINs don't match. Try again.", Toast.LENGTH_SHORT).show();
                    firstPin = null;
                    confirmingPin = false;
                    binding.tvLockTitle.setText("Set PIN");
                    binding.tvLockDesc.setText("Create a " + PIN_LENGTH + "-digit PIN");
                }
            }
        } else {
            if (lockManager.checkPin(pin)) {
                setResult(RESULT_OK);
                finish();
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ── Pattern ───────────────────────────────────────────────────────────

    private void showPatternUI() {
        binding.ivLockIcon.setImageResource(R.drawable.ic_group);
        binding.tvLockTitle.setText(isSetup ? "Set Pattern" : "Enter Pattern");
        binding.tvLockDesc.setText(isSetup
            ? "Draw a pattern to lock the app"
            : "Draw your unlock pattern");
        binding.layoutPattern.setVisibility(View.VISIBLE);

        binding.patternLockView.clearPattern();
        binding.patternLockView.addPatternLockListener(new PatternLockViewListener() {
            @Override public void onStarted() {
                binding.tvPatternHint.setText(isSetup
                    ? (confirmingPattern ? "Confirm your pattern" : "Draw your pattern")
                    : "Draw your pattern");
            }
            @Override public void onProgress(List<PatternLockView.Dot> dots) {}
            @Override public void onComplete(List<PatternLockView.Dot> dots) {
                String key = PatternLockUtils.patternToString(binding.patternLockView, dots);
                onPatternComplete(key);
            }
            @Override public void onCleared() {}
        });
    }

    private void onPatternComplete(String key) {
        if (key.length() < 4) {
            binding.tvPatternHint.setText("Pattern too short (min 4 points)");
            binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.WRONG);
            binding.patternLockView.clearPattern();
            return;
        }

        if (isSetup) {
            if (!confirmingPattern) {
                firstPattern = key;
                confirmingPattern = true;
                binding.tvPatternHint.setText("Draw the pattern again to confirm");
                binding.patternLockView.clearPattern();
            } else {
                if (key.equals(firstPattern)) {
                    lockManager.setPattern(key);
                    binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.CORRECT);
                    Toast.makeText(this, "Pattern set!", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    firstPattern = null;
                    confirmingPattern = false;
                    binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.WRONG);
                    binding.tvPatternHint.setText("Pattern didn't match. Draw again.");
                    binding.patternLockView.clearPattern();
                }
            }
        } else {
            if (lockManager.checkPattern(key)) {
                binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.CORRECT);
                setResult(RESULT_OK);
                finish();
            } else {
                binding.patternLockView.setViewMode(PatternLockView.PatternViewMode.WRONG);
                binding.tvPatternHint.setText("Wrong pattern. Try again.");
                binding.patternLockView.clearPattern();
            }
        }
    }

    // ── Fingerprint ───────────────────────────────────────────────────────

    private void showFingerprintUI() {
        binding.ivLockIcon.setImageResource(R.drawable.ic_person);
        binding.tvLockTitle.setText("Fingerprint / Face");
        binding.tvLockDesc.setText("Use biometric to lock the app");
        binding.layoutFingerprint.setVisibility(View.VISIBLE);

        BiometricManager bm = BiometricManager.from(this);
        int canAuth = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG |
            BiometricManager.Authenticators.DEVICE_CREDENTIAL);

        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            binding.tvFpStatus.setText("Your device supports biometric unlock");
            binding.btnEnableFp.setOnClickListener(v -> {
                if (isSetup) {
                    lockManager.setFingerprint();
                    Toast.makeText(this, "Fingerprint lock enabled!", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    launchBiometricPrompt();
                }
            });
        } else {
            binding.tvFpStatus.setText("Biometric not available on this device.\nSet up fingerprint in device Settings first.");
            binding.btnEnableFp.setEnabled(false);
        }
    }

    private void launchBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(
                        BiometricPrompt.AuthenticationResult result) {
                    setResult(RESULT_OK);
                    finish();
                }
                @Override public void onAuthenticationError(int code, CharSequence msg) {
                    Toast.makeText(AppLockActivity.this,
                        "Auth error: " + msg, Toast.LENGTH_SHORT).show();
                }
                @Override public void onAuthenticationFailed() {
                    Toast.makeText(AppLockActivity.this,
                        "Authentication failed", Toast.LENGTH_SHORT).show();
                }
            });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock CallX")
            .setSubtitle("Use your fingerprint or face to unlock")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build();

        prompt.authenticate(info);
    }

    // ── Utils ─────────────────────────────────────────────────────────────

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
