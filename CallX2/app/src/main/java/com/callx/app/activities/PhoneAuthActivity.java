package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.databinding.ActivityPhoneAuthBinding;
import com.callx.app.utils.Constants;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.*;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class PhoneAuthActivity extends AppCompatActivity {

    private ActivityPhoneAuthBinding binding;
    private FirebaseAuth auth;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private String verificationId;
    private boolean otpSent = false;
    private CountDownTimer resendTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPhoneAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        auth = FirebaseAuth.getInstance();

        if (auth.getCurrentUser() != null) { goToMain(); return; }

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        showPhoneScreen();

        binding.btnSendOtp.setOnClickListener(v -> {
            if (!otpSent) {
                sendOtp();
            } else {
                verifyOtp();
            }
        });

        binding.tvResendOtp.setOnClickListener(v -> {
            if (resendToken != null) resendOtp();
        });

        binding.tvChangeNumber.setOnClickListener(v -> {
            otpSent = false;
            if (resendTimer != null) resendTimer.cancel();
            showPhoneScreen();
        });
    }

    private void showPhoneScreen() {
        binding.layoutPhone.setVisibility(View.VISIBLE);
        binding.layoutOtp.setVisibility(View.GONE);
        binding.btnSendOtp.setText("OTP Bhejo");
        binding.tvTitle.setText("Phone se Login karo");
        binding.tvSubtitle.setText("Apna mobile number daalo");
    }

    private void showOtpScreen(String phone) {
        binding.layoutPhone.setVisibility(View.GONE);
        binding.layoutOtp.setVisibility(View.VISIBLE);
        binding.btnSendOtp.setText("Verify karo");
        binding.tvTitle.setText("OTP daalo");
        binding.tvSubtitle.setText(phone + " pe OTP bheja gaya");
        startResendTimer();
    }

    private void sendOtp() {
        String countryCode = binding.etCountryCode.getText().toString().trim();
        String phone = binding.etPhone.getText().toString().trim();

        if (phone.isEmpty()) { showError("Phone number daalo"); return; }
        if (phone.length() < 10) { showError("Sahi number daalo"); return; }

        String fullPhone = "+" + countryCode.replaceAll("[^0-9]", "") + phone;
        showLoading("OTP bhej raha hoon...");

        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(fullPhone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                @Override
                public void onVerificationCompleted(PhoneAuthCredential credential) {
                    showLoading("Auto-verify ho raha hai...");
                    signInWithCredential(credential, fullPhone);
                }
                @Override
                public void onVerificationFailed(FirebaseException e) {
                    showError("OTP fail: " + e.getMessage());
                }
                @Override
                public void onCodeSent(String vId, PhoneAuthProvider.ForceResendingToken token) {
                    verificationId = vId;
                    resendToken = token;
                    otpSent = true;
                    showError("");
                    showOtpScreen(fullPhone);
                }
            }).build();

        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    private void resendOtp() {
        String countryCode = binding.etCountryCode.getText().toString().trim();
        String phone = binding.etPhone.getText().toString().trim();
        String fullPhone = "+" + countryCode.replaceAll("[^0-9]", "") + phone;

        showLoading("OTP dobara bhej raha hoon...");

        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(fullPhone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setForceResendingToken(resendToken)
            .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                @Override
                public void onVerificationCompleted(PhoneAuthCredential credential) {
                    signInWithCredential(credential, fullPhone);
                }
                @Override
                public void onVerificationFailed(FirebaseException e) {
                    showError("OTP fail: " + e.getMessage());
                }
                @Override
                public void onCodeSent(String vId, PhoneAuthProvider.ForceResendingToken token) {
                    verificationId = vId;
                    resendToken = token;
                    showError("");
                    startResendTimer();
                    Toast.makeText(PhoneAuthActivity.this, "OTP dobara bhej diya!", Toast.LENGTH_SHORT).show();
                }
            }).build();

        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    private void verifyOtp() {
        String otp = binding.etOtp.getText().toString().trim();
        if (otp.length() != 6) { showError("6 digit OTP daalo"); return; }
        if (verificationId == null) { showError("Pehle OTP bhejo"); return; }

        showLoading("Verify ho raha hai...");
        PhoneAuthCredential credential = PhoneAuthProvider.getCredential(verificationId, otp);
        String countryCode = binding.etCountryCode.getText().toString().trim();
        String phone = binding.etPhone.getText().toString().trim();
        signInWithCredential(credential, "+" + countryCode.replaceAll("[^0-9]", "") + phone);
    }

    private void signInWithCredential(PhoneAuthCredential credential, String phone) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener(result -> {
                FirebaseUser user = result.getUser();
                if (user == null) return;
                boolean isNew = result.getAdditionalUserInfo() != null
                    && result.getAdditionalUserInfo().isNewUser();
                if (isNew) {
                    savePhoneProfile(user, phone);
                } else {
                    saveFcmToken();
                    goToMain();
                }
            })
            .addOnFailureListener(e -> showError("Galat OTP: " + e.getMessage()));
    }

    private void savePhoneProfile(FirebaseUser user, String phone) {
        String mobile = phone.replaceAll("[^0-9]", "");
        Map<String, Object> data = new HashMap<>();
        data.put("uid", user.getUid());
        data.put("name", "CallX User");
        data.put("emoji", "😊");
        data.put("callxId", mobile);
        data.put("mobile", phone);
        data.put("loginType", "phone");
        data.put("about", "Hey, I'm on CallX!");
        data.put("lastSeen", System.currentTimeMillis());

        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(user.getUid())
            .setValue(data)
            .addOnSuccessListener(x -> {
                saveFcmToken();
                // Phone users ke liye profile setup screen
                Intent i = new Intent(this, ProfileSetupActivity.class);
                i.putExtra("isNewUser", true);
                startActivity(i);
                finish();
            })
            .addOnFailureListener(e -> showError(e.getMessage()));
    }

    private void startResendTimer() {
        binding.tvResendOtp.setEnabled(false);
        binding.tvResendOtp.setText("60s baad resend karo");
        if (resendTimer != null) resendTimer.cancel();
        resendTimer = new CountDownTimer(60000, 1000) {
            @Override public void onTick(long ms) {
                binding.tvResendOtp.setText(ms / 1000 + "s baad resend karo");
            }
            @Override public void onFinish() {
                binding.tvResendOtp.setEnabled(true);
                binding.tvResendOtp.setText("OTP dobara bhejo");
            }
        }.start();
    }

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
                .getReference("users").child(user.getUid())
                .child("fcmToken").setValue(token);
        });
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (resendTimer != null) resendTimer.cancel();
    }
}
