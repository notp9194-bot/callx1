package com.callx.app.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.databinding.ActivityAuthBinding;
import com.callx.app.utils.BiometricLoginManager;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.Constants;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.*;
import com.google.firebase.database.*;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.HashMap;
import java.util.Map;

public class AuthActivity extends AppCompatActivity {

    private ActivityAuthBinding binding;
    private FirebaseAuth auth;
    private GoogleSignInClient googleSignInClient;
    private boolean isLoginMode = true;
    private Uri pickedAvatarUri = null;
    private ActivityResultLauncher<String> avatarPicker;
    private ActivityResultLauncher<Intent> googleSignInLauncher;
    private BiometricLoginManager bioLoginManager;

    // ── Rate Limiting ──────────────────────────────────────────────────────
    private static final String PREFS_NAME      = "callx_auth_prefs";
    private static final String KEY_ATTEMPT_COUNT = "login_attempt_count";
    private static final String KEY_FIRST_ATTEMPT  = "login_first_attempt_time";
    private static final String KEY_LOCKOUT_UNTIL  = "login_lockout_until";
    private static final String KEY_REMEMBER_EMAIL = "saved_email";
    private static final int    MAX_ATTEMPTS    = 5;
    private static final long   WINDOW_MS       = 10 * 60 * 1000L;  // 10 min
    private static final long   LOCKOUT_MS      = 15 * 60 * 1000L;  // 15 min

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        auth  = FirebaseAuth.getInstance();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bioLoginManager = BiometricLoginManager.getInstance(this);

        // ── Remember Me: auto-fill saved email ────────────────────────────
        String savedEmail = prefs.getString(KEY_REMEMBER_EMAIL, "");
        if (!savedEmail.isEmpty()) {
            binding.etEmail.setText(savedEmail);
            binding.cbRememberMe.setChecked(true);
        }

        if (auth.getCurrentUser() != null) { goToMain(); return; }

        // ── Biometric login button: show only if enabled + hardware available ──
        if (bioLoginManager.isEnabled()
                && BiometricLoginManager.isHardwareAvailable(this)
                && !bioLoginManager.isTemporarilyDisabled()) {
            binding.btnBiometricLogin.setVisibility(View.VISIBLE);
            String savedBioEmail = bioLoginManager.getSavedEmail();
            if (savedBioEmail != null && !savedBioEmail.isEmpty()) {
                binding.btnBiometricLogin.setText("Fingerprint se login karo\n(" + savedBioEmail + ")");
            }
            binding.btnBiometricLogin.setOnClickListener(v -> launchBiometricLogin());
            // Auto-launch biometric on screen open
            launchBiometricLogin();
        } else if (bioLoginManager.isEnabled() && bioLoginManager.isTemporarilyDisabled()) {
            binding.btnBiometricLogin.setVisibility(View.VISIBLE);
            binding.btnBiometricLogin.setText("Biometric " + bioLoginManager.getRemainingDisableMinutes() + " min ke liye disabled");
            binding.btnBiometricLogin.setEnabled(false);
        } else {
            binding.btnBiometricLogin.setVisibility(View.GONE);
        }

        // Google Sign-In
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(com.callx.app.R.string.default_web_client_id))
                .requestEmail().build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        googleSignInLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                try {
                    GoogleSignInAccount account = task.getResult(ApiException.class);
                    firebaseAuthWithGoogle(account.getIdToken());
                } catch (ApiException e) {
                    showError("Google login fail hua: " + e.getMessage());
                }
            });

        avatarPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pickedAvatarUri = uri;
                    Glide.with(this).load(uri).into(binding.ivAvatarPreview);
                    binding.tvAvatarHint.setText("Photo selected");
                }
            });

        binding.tilName.setVisibility(View.GONE);
        binding.tilMobile.setVisibility(View.GONE);
        binding.flAvatarPicker.setVisibility(View.GONE);
        binding.tvAvatarHint.setVisibility(View.GONE);
        binding.layoutPasswordStrength.setVisibility(View.GONE);
        binding.layoutRememberMe.setVisibility(View.VISIBLE);

        binding.flAvatarPicker.setOnClickListener(v -> avatarPicker.launch("image/*"));
        binding.btnLogin.setOnClickListener(v -> handleEmailPasswordAction());
        binding.btnSignup.setOnClickListener(v -> toggleSignupMode());
        binding.btnGoogle.setOnClickListener(v -> {
            showError("");
            googleSignInLauncher.launch(googleSignInClient.getSignInIntent());
        });
        binding.btnPhone.setOnClickListener(v ->
            startActivity(new Intent(this, PhoneAuthActivity.class)));
        binding.tvForgotPassword.setOnClickListener(v -> showForgotPasswordDialog());

        // ── Password strength watcher (signup mode only) ───────────────────
        binding.etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (!isLoginMode) updatePasswordStrength(s.toString());
            }
        });
    }

    private void toggleSignupMode() {
        isLoginMode = !isLoginMode;
        binding.btnLogin.setText(isLoginMode ? "Login" : "Sign Up");
        binding.btnSignup.setText(isLoginMode ? "Naya account banao" : "Wapas Login pe");
        int sv = isLoginMode ? View.GONE : View.VISIBLE;
        binding.tilName.setVisibility(sv);
        binding.tilMobile.setVisibility(sv);
        binding.flAvatarPicker.setVisibility(sv);
        binding.tvAvatarHint.setVisibility(sv);
        binding.layoutPasswordStrength.setVisibility(sv);
        binding.layoutRememberMe.setVisibility(isLoginMode ? View.VISIBLE : View.GONE);
        if (isLoginMode) showError("");
    }

    // ── Password Strength Meter ────────────────────────────────────────────
    private void updatePasswordStrength(String pwd) {
        int score = 0;
        if (pwd.length() >= 8)                             score++;
        if (pwd.matches(".*[A-Z].*"))                      score++;
        if (pwd.matches(".*[0-9].*"))                      score++;
        if (pwd.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}].*")) score++;

        int barColor;
        String label;
        int progress = (score * 25);

        if (score <= 1)      { barColor = getResources().getColor(com.callx.app.R.color.strength_weak);   label = "Bahut kamzor"; }
        else if (score == 2) { barColor = getResources().getColor(com.callx.app.R.color.strength_fair);   label = "Theek hai"; }
        else if (score == 3) { barColor = getResources().getColor(com.callx.app.R.color.strength_good);   label = "Achha hai"; }
        else                 { barColor = getResources().getColor(com.callx.app.R.color.strength_strong); label = "Strong!"; }

        binding.progressPasswordStrength.setProgress(progress);
        binding.progressPasswordStrength.setIndicatorColor(barColor);
        binding.tvPasswordStrengthLabel.setText(label);
        binding.tvPasswordStrengthLabel.setTextColor(barColor);
    }

    // ── Biometric Login ────────────────────────────────────────────────────

    private void launchBiometricLogin() {
        showLoading("Biometric verify ho raha hai...");
        bioLoginManager.showBiometricPrompt(this, new BiometricLoginManager.BiometricLoginCallback() {

            @Override
            public void onSuccess(@NonNull FirebaseUser user) {
                if (!user.isEmailVerified()) {
                    showEmailVerificationDialog(user);
                    return;
                }
                saveFcmToken();
                goToMain();
            }

            @Override
            public void onNeedsReauth(@NonNull String savedUid, @NonNull String provider) {
                // FIX-BL3/BL4: provider-aware reauth
                showError(""); // loading clear karo
                if (BiometricLoginManager.PROVIDER_GOOGLE.equals(provider)) {
                    // Google user — re-launch Google sign-in
                    bioLoginManager.disable();
                    binding.btnBiometricLogin.setVisibility(android.view.View.GONE);
                    showError("Google session expire ho gaya. Google se dobara login karo.");
                    binding.btnGoogle.setVisibility(android.view.View.VISIBLE);
                } else {
                    // Email user — prefill email, focus password
                    bioLoginManager.disable();
                    binding.btnBiometricLogin.setVisibility(android.view.View.GONE);
                    showError("Session expire ho gaya. Password se dobara login karo.");
                    binding.etEmail.requestFocus();
                    String savedEmail = bioLoginManager.getSavedEmail();
                    if (savedEmail != null && !savedEmail.isEmpty()) {
                        binding.etEmail.setText(savedEmail);
                        binding.etPassword.requestFocus();
                    }
                }
            }

            @Override
            public void onUserCancelled() {
                showError("");
            }

            @Override
            public void onAuthFailed(int remainingAttempts) {
                if (remainingAttempts > 0) {
                    showError("Biometric match nahi hua. " + remainingAttempts + " attempts bache.");
                }
            }

            @Override
            public void onTemporarilyDisabled(long remainingMinutes) {
                String msg = remainingMinutes > 0
                        ? "Bahut zyada galat attempts! " + remainingMinutes + " minute baad try karo."
                        : "Biometric temporarily disabled. Email se login karo.";
                showError(msg);
                binding.btnBiometricLogin.setEnabled(false);
                binding.btnBiometricLogin.setText("Biometric disabled — email use karo");
            }

            @Override
            public void onError(@NonNull String message) {
                showError(message);
            }
        });
    }

    /**
     * Fix #5: Enable karte waqt biometric scan confirm karo — seedha YES se enable mat karo.
     * Fix #6: Multi-account — pehle disable karo toh purana UID clear ho jaaye.
     */
    private void showEnableBiometricDialog(@NonNull FirebaseUser user) {
        if (!BiometricLoginManager.isHardwareAvailable(this)) return;
        if (bioLoginManager.isEnabled()) {
            // Fix #6: Check karo kya same user hai
            String savedUid = bioLoginManager.getSavedUid();
            if (savedUid != null && savedUid.equals(user.getUid())) return; // Already enabled for this user
            // Different user — disable first
            bioLoginManager.disable();
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Biometric Login Enable Karo?")
                .setMessage("Agli baar fingerprint ya face se seedha login kar sakte ho.\nSecure aur fast hota hai.")
                .setPositiveButton("Enable Karo", (d, w) -> {
                    // Fix #5: Dialog ke baad actual biometric scan karo confirm ke liye
                    confirmBiometricBeforeEnable(user);
                })
                .setNegativeButton("Baad Mein", null)
                .setCancelable(true)
                .show();
    }

    /**
     * Fix #5: Biometric scan se confirm karo toh enable karo.
     * Prevents someone else pressing "Enable" button.
     */
    private void confirmBiometricBeforeEnable(@NonNull FirebaseUser user) {
        androidx.biometric.BiometricPrompt.PromptInfo promptInfo =
                new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Biometric Confirm Karo")
                        .setSubtitle("Apna fingerprint ya face scan karo enable karne ke liye")
                        .setNegativeButtonText("Cancel")
                        .setAllowedAuthenticators(
                                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setConfirmationRequired(true)
                        .build();

        androidx.biometric.BiometricPrompt prompt = new androidx.biometric.BiometricPrompt(
                this,
                androidx.core.content.ContextCompat.getMainExecutor(this),
                new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                        // Scan success — ab enable karo
                        String email = user.getEmail() != null ? user.getEmail() : "";
                        bioLoginManager.enable(user.getUid(), email);
                        // Login screen pe button update karo
                        binding.btnBiometricLogin.setVisibility(android.view.View.VISIBLE);
                        binding.btnBiometricLogin.setText("Fingerprint se login karo\n(" + email + ")");
                        android.widget.Toast.makeText(AuthActivity.this,
                                "Biometric login enable ho gaya!", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errMsg) {
                        // User ne cancel kiya — silently ignore
                    }
                    @Override
                    public void onAuthenticationFailed() {
                        android.widget.Toast.makeText(AuthActivity.this,
                                "Biometric match nahi hua. Enable nahi kiya.",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
        prompt.authenticate(promptInfo);
    }

    /**
     * FIX-BL4: Google login ke liye biometric enable — PROVIDER_GOOGLE save karo.
     * Email field nahi hoti Google users ke liye biometric reauth mein,
     * isliye caller Google re-auth trigger kar sakta hai correctly.
     */
    private void showEnableBiometricDialogForGoogle(@NonNull FirebaseUser user) {
        if (!BiometricLoginManager.isHardwareAvailable(this)) {
            goToMain();
            return;
        }
        if (bioLoginManager.isEnabled()) {
            String savedUid = bioLoginManager.getSavedUid();
            if (savedUid != null && savedUid.equals(user.getUid())) {
                goToMain();
                return;
            }
            bioLoginManager.disable();
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Biometric Login Enable Karo?")
                .setMessage("Agli baar Google account ke sath fingerprint ya face se seedha login kar sakte ho.")
                .setPositiveButton("Enable Karo", (d, w) -> confirmBiometricBeforeEnableGoogle(user))
                .setNegativeButton("Baad Mein", (d, w) -> goToMain())
                .setCancelable(false)
                .show();
    }

    private void confirmBiometricBeforeEnableGoogle(@NonNull FirebaseUser user) {
        androidx.biometric.BiometricPrompt.PromptInfo promptInfo =
                new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Biometric Confirm Karo")
                        .setSubtitle("Apna fingerprint ya face scan karo enable karne ke liye")
                        .setNegativeButtonText("Cancel")
                        .setAllowedAuthenticators(
                                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setConfirmationRequired(true)
                        .build();

        androidx.biometric.BiometricPrompt prompt = new androidx.biometric.BiometricPrompt(
                this,
                androidx.core.content.ContextCompat.getMainExecutor(this),
                new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                        // FIX-BL4: PROVIDER_GOOGLE save karo
                        String email = user.getEmail() != null ? user.getEmail() : "";
                        bioLoginManager.enable(user.getUid(), email, BiometricLoginManager.PROVIDER_GOOGLE);
                        binding.btnBiometricLogin.setVisibility(android.view.View.VISIBLE);
                        binding.btnBiometricLogin.setText("Fingerprint se login karo\n(" + email + ")");
                        android.widget.Toast.makeText(AuthActivity.this,
                                "Biometric login enable ho gaya!", android.widget.Toast.LENGTH_SHORT).show();
                        goToMain();
                    }
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errMsg) {
                        goToMain(); // Cancel kiya — app mein jaane do
                    }
                    @Override
                    public void onAuthenticationFailed() {
                        android.widget.Toast.makeText(AuthActivity.this,
                                "Biometric match nahi hua. Enable nahi kiya.",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
        prompt.authenticate(promptInfo);
    }


    private void firebaseAuthWithGoogle(String idToken) {
        showLoading("Google account verify ho raha hai...");
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        auth.signInWithCredential(credential)
            .addOnSuccessListener(result -> {
                FirebaseUser user = result.getUser();
                if (user == null) return;
                boolean isNew = result.getAdditionalUserInfo() != null
                    && result.getAdditionalUserInfo().isNewUser();
                if (isNew) {
                    saveGoogleProfile(user);
                } else {
                    saveFcmToken();
                    // FIX-BL4: Google login pe biometric enable karo PROVIDER_GOOGLE ke saath
                    if (BiometricLoginManager.isHardwareAvailable(this)
                            && !bioLoginManager.isEnabled()) {
                        showEnableBiometricDialogForGoogle(user);
                    } else {
                        goToMain();
                    }
                }
            })
            .addOnFailureListener(e -> showError("Google login fail: " + e.getMessage()));
    }

    private void saveGoogleProfile(FirebaseUser user) {
        String name     = user.getDisplayName() != null ? user.getDisplayName() : "CallX User";
        String email    = user.getEmail()       != null ? user.getEmail()       : "";
        String photoUrl = user.getPhotoUrl()    != null ? user.getPhotoUrl().toString() : null;
        Map<String, Object> data = new HashMap<>();
        data.put("uid",       user.getUid());
        data.put("email",     email);
        data.put("name",      name);
        data.put("emoji",     "😊");
        data.put("callxId",   "");
        data.put("mobile",    "");
        data.put("loginType", "google");
        data.put("about",     "Hey, I'm on CallX!");
        data.put("lastSeen",  System.currentTimeMillis());
        if (photoUrl != null) data.put("photoUrl", photoUrl);
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(user.getUid()).setValue(data)
            .addOnSuccessListener(x -> {
                saveFcmToken();
                Intent i = new Intent(this, ProfileSetupActivity.class);
                i.putExtra("isNewUser", true);
                startActivity(i);
                finish();
            })
            .addOnFailureListener(e -> showError(e.getMessage()));
    }

    // ── Email / Password Auth ──────────────────────────────────────────────
    private void handleEmailPasswordAction() {
        String email    = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();
        if (email.isEmpty() || password.isEmpty()) { showError("Email aur password fill karo"); return; }

        if (isLoginMode) {
            // ── Rate limit check ──────────────────────────────────────────
            long lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0);
            long now = System.currentTimeMillis();
            if (now < lockoutUntil) {
                long remaining = (lockoutUntil - now) / 1000 / 60;
                showError("Bahut zyada attempts! " + remaining + " minute baad try karo.");
                return;
            }

            showLoading("Login ho raha hai...");
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(r -> {
                    // Reset rate limit on success
                    prefs.edit()
                        .remove(KEY_ATTEMPT_COUNT)
                        .remove(KEY_FIRST_ATTEMPT)
                        .remove(KEY_LOCKOUT_UNTIL)
                        .apply();

                    // ── Remember Me ───────────────────────────────────────
                    if (binding.cbRememberMe.isChecked()) {
                        prefs.edit().putString(KEY_REMEMBER_EMAIL, email).apply();
                    } else {
                        prefs.edit().remove(KEY_REMEMBER_EMAIL).apply();
                    }

                    // ── Email Verification Check ──────────────────────────
                    FirebaseUser user = r.getUser();
                    if (user != null && !user.isEmailVerified()) {
                        showEmailVerificationDialog(user);
                    } else if (user != null) {
                        saveFcmToken();
                        // ── Biometric Login Enable Dialog ─────────────────
                        if (BiometricLoginManager.isHardwareAvailable(this)
                                && !bioLoginManager.isEnabled()) {
                            showEnableBiometricDialog(user);
                        }
                        goToMain();
                    }
                })
                .addOnFailureListener(e -> {
                    recordFailedAttempt();
                    showFriendlyError(e.getMessage());
                });
        } else {
            // ── Signup validation ─────────────────────────────────────────
            String name   = binding.etName.getText().toString().trim();
            String mobile = binding.etMobile.getText().toString().trim().replaceAll("[^0-9]", "");
            if (name.isEmpty())   { showError("Naam bhi daalo"); return; }
            if (mobile.length() < 10 || mobile.length() > 15) {
                showError("Sahi mobile number daalo (10-15 digits)"); return;
            }
            // Password strength — min 6 chars required, 8 recommended
            if (password.length() < 6) {
                showError("Password kam se kam 6 characters ka hona chahiye"); return;
            }
            checkMobileAvailable(mobile, available -> {
                if (!available) { showError("Ye mobile number pehle se registered hai"); return; }
                doSignup(email, password, name, mobile);
            });
        }
    }

    // ── Rate Limiting helpers ──────────────────────────────────────────────
    private void recordFailedAttempt() {
        long now   = System.currentTimeMillis();
        long first = prefs.getLong(KEY_FIRST_ATTEMPT, now);
        int  count = prefs.getInt(KEY_ATTEMPT_COUNT, 0) + 1;

        if (now - first > WINDOW_MS) {
            // Window expired — reset
            first = now;
            count = 1;
        }

        prefs.edit()
            .putInt(KEY_ATTEMPT_COUNT, count)
            .putLong(KEY_FIRST_ATTEMPT, first)
            .apply();

        if (count >= MAX_ATTEMPTS) {
            long lockUntil = now + LOCKOUT_MS;
            prefs.edit().putLong(KEY_LOCKOUT_UNTIL, lockUntil).apply();
            showError("Bahut zyada galat attempts! 15 minute ke liye block kar diya.");
        } else {
            int remaining = MAX_ATTEMPTS - count;
            if (remaining <= 2) showError("Galat password! Sirf " + remaining + " attempt bache hain.");
        }
    }

    private void showFriendlyError(String raw) {
        if (raw == null) { showError("Login fail hua, dobara try karo"); return; }
        String msg;
        if (raw.contains("no user record") || raw.contains("user-not-found"))
            msg = "Ye email registered nahi hai";
        else if (raw.contains("wrong-password") || raw.contains("invalid-credential"))
            msg = "Password galat hai";
        else if (raw.contains("too-many-requests"))
            msg = "Bahut zyada attempts! Thodi der baad try karo";
        else if (raw.contains("network"))
            msg = "Internet connection check karo";
        else if (raw.contains("disabled"))
            msg = "Ye account disable ho gaya hai";
        else
            msg = "Login fail hua: " + raw;
        showError(msg);
    }

    // ── Email Verification Dialog ──────────────────────────────────────────
    private void showEmailVerificationDialog(FirebaseUser user) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("Email Verify Nahi Hua")
            .setMessage("Aapka email verify nahi hua hai. Inbox ya Spam folder check karo.\n\nVerification email dobara bhejein?")
            .setPositiveButton("Dobara Bhejo", (d, w) -> {
                user.sendEmailVerification()
                    .addOnSuccessListener(x -> Toast.makeText(this,
                        "Verification email bhej diya! Inbox check karo.", Toast.LENGTH_LONG).show())
                    .addOnFailureListener(e -> Toast.makeText(this,
                        "Email nahi bheja: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                auth.signOut();
            })
            .setNegativeButton("Baad Mein", (d, w) -> auth.signOut())
            .setCancelable(false)
            .show();
    }

    // ── Forgot Password ────────────────────────────────────────────────────
    private void showForgotPasswordDialog() {
        View dialogView = getLayoutInflater().inflate(com.callx.app.R.layout.dialog_forgot_password, null);
        TextInputEditText etResetEmail = dialogView.findViewById(com.callx.app.R.id.et_reset_email);
        String typed = binding.etEmail.getText().toString().trim();
        if (!typed.isEmpty()) etResetEmail.setText(typed);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("Password Reset")
            .setView(dialogView)
            .setPositiveButton("Link Bhejo", null)
            .setNegativeButton("Cancel", null)
            .create();

        dialog.setOnShowListener(dlg -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String resetEmail = etResetEmail.getText().toString().trim();
                    if (resetEmail.isEmpty()) {
                        etResetEmail.setError("Email daalo pehle"); return;
                    }
                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(resetEmail).matches()) {
                        etResetEmail.setError("Sahi email format daalo"); return;
                    }
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    auth.sendPasswordResetEmail(resetEmail)
                        .addOnSuccessListener(x -> {
                            dialog.dismiss();
                            Toast.makeText(this,
                                "Reset link bhej diya! Email aur Spam/Junk folder check karo",
                                Toast.LENGTH_LONG).show();
                        })
                        .addOnFailureListener(e -> {
                            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            String msg = e.getMessage();
                            if (msg != null && msg.contains("no user record"))
                                etResetEmail.setError("Ye email registered nahi hai");
                            else if (msg != null && msg.contains("badly formatted"))
                                etResetEmail.setError("Sahi email daalo");
                            else
                                Toast.makeText(this, "Error: " + msg, Toast.LENGTH_LONG).show();
                        });
                });
        });
        dialog.show();
    }

    // ── Signup helpers ─────────────────────────────────────────────────────
    private interface AvailableCb { void onResult(boolean available); }

    private void checkMobileAvailable(String callxId, AvailableCb cb) {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").orderByChild("callxId").equalTo(callxId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) { cb.onResult(!snap.exists()); }
                @Override public void onCancelled(DatabaseError e)    { cb.onResult(true); }
            });
    }

    private void doSignup(String email, String password, String name, String callxId) {
        showLoading("Account bana raha hoon...");
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener(r -> {
                FirebaseUser user = r.getUser();
                if (user == null) return;
                user.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(name).build());
                user.sendEmailVerification();
                if (pickedAvatarUri != null) {
                    showLoading("Photo upload ho rahi hai...");
                    CloudinaryUploader.uploadAvatar(this, pickedAvatarUri,
                        new CloudinaryUploader.AvatarUploadCallback() {
                            @Override public void onThumbReady(String t)          { showLoading("Compress ho rahi hai..."); }
                            @Override public void onFullReady(String photoUrl)    { saveProfile(user, email, name, callxId, photoUrl); }
                            @Override public void onError(String err)             { saveProfile(user, email, name, callxId, null); }
                        });
                } else {
                    saveProfile(user, email, name, callxId, null);
                }
            })
            .addOnFailureListener(e -> showError(e.getMessage()));
    }

    private void saveProfile(FirebaseUser user, String email, String name, String callxId, String photoUrl) {
        Map<String, Object> data = new HashMap<>();
        data.put("uid",           user.getUid());
        data.put("email",         email);
        data.put("name",          name);
        data.put("emoji",         "😊");
        data.put("callxId",       callxId);
        data.put("mobile",        callxId);
        data.put("loginType",     "email");
        data.put("emailVerified", false);
        data.put("about",         "Hey, I'm on CallX!");
        data.put("lastSeen",      System.currentTimeMillis());
        if (photoUrl != null) data.put("photoUrl", photoUrl);
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(user.getUid()).setValue(data)
            .addOnSuccessListener(x -> {
                Toast.makeText(this,
                    "Account ready! Verification email bheja gaya. Inbox check karo.",
                    Toast.LENGTH_LONG).show();
                saveFcmToken();
                // After signup — go to main but show verify banner
                goToMain();
            })
            .addOnFailureListener(e -> showError(e.getMessage()));
    }

    // ── Utilities ──────────────────────────────────────────────────────────
    private void showError(String msg) {
        if (msg == null || msg.isEmpty()) { binding.tvError.setVisibility(View.GONE); return; }
        binding.tvError.setVisibility(View.VISIBLE);
        binding.tvError.setTextColor(getResources().getColor(com.callx.app.R.color.action_danger));
        binding.tvError.setText(msg);
    }

    private void showLoading(String msg) {
        binding.tvError.setVisibility(View.VISIBLE);
        binding.tvError.setTextColor(getResources().getColor(com.callx.app.R.color.text_secondary));
        binding.tvError.setText(msg);
    }

    private void saveFcmToken() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (token == null) return;
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("users").child(user.getUid()).child("fcmToken").setValue(token);
        });
    }

    private void goToMain() {
        // Mark user online immediately after login
        com.callx.app.utils.PresenceManager.getInstance().onLogin();
        // Sync privacy settings to Firebase on login
        try { new com.callx.app.utils.SecurityManager(this).syncAllPrivacyToFirebase(); } catch (Exception ignored) {}
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
