package com.callx.app.activities;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.databinding.ActivityAuthBinding;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.HashMap;
import java.util.Map;
public class AuthActivity extends AppCompatActivity {
    private ActivityAuthBinding binding;
    private FirebaseAuth auth;
    private boolean isLoginMode = true;
    private Uri pickedAvatarUri = null;
    private ActivityResultLauncher<String> avatarPicker;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) { goToMain(); return; }

        // Signup-only fields hidden by default (login mode)
        binding.tilName.setVisibility(View.GONE);
        binding.tilMobile.setVisibility(View.GONE);
        binding.flAvatarPicker.setVisibility(View.GONE);
        binding.tvAvatarHint.setVisibility(View.GONE);

        avatarPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pickedAvatarUri = uri;
                    Glide.with(this).load(uri).into(binding.ivAvatarPreview);
                    binding.tvAvatarHint.setText("Photo selected ✓");
                }
            });

        binding.flAvatarPicker.setOnClickListener(v ->
            avatarPicker.launch("image/*"));

        binding.btnLogin.setOnClickListener(v -> handleAction());
        binding.btnSignup.setOnClickListener(v -> {
            isLoginMode = !isLoginMode;
            binding.btnLogin.setText(isLoginMode ? "Login" : "Sign Up");
            binding.btnSignup.setText(isLoginMode ? "Naya account banao" : "Wapas Login pe");
            int sv = isLoginMode ? View.GONE : View.VISIBLE;
            binding.tilName.setVisibility(sv);
            binding.tilMobile.setVisibility(sv);
            binding.flAvatarPicker.setVisibility(sv);
            binding.tvAvatarHint.setVisibility(sv);
        });
    }
    private void handleAction() {
        String email = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();
        if (email.isEmpty() || password.isEmpty()) {
            showError("Email aur password fill karo"); return;
        }
        if (isLoginMode) {
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(r -> { saveFcmToken(); goToMain(); })
                .addOnFailureListener(e -> showError(e.getMessage()));
        } else {
            String name = binding.etName.getText().toString().trim();
            String mobileRaw = binding.etMobile.getText().toString().trim();
            String mobile = mobileRaw.replaceAll("[^0-9]", "");
            if (name.isEmpty()) { showError("Naam bhi daalo"); return; }
            if (mobile.length() < 10 || mobile.length() > 15) {
                showError("Sahi mobile number daalo (10-15 digits)"); return;
            }
            // CallX ID = mobile number (taaki dusre log mobile se search kar saken)
            final String callxId = mobile;
            // Step 1: check duplicate mobile (callxId) before signup
            checkMobileAvailable(callxId, available -> {
                if (!available) {
                    showError("Ye mobile number pehle se registered hai");
                    return;
                }
                doSignup(email, password, name, callxId);
            });
        }
    }
    private interface AvailableCb { void onResult(boolean available); }
    private void checkMobileAvailable(String callxId, AvailableCb cb) {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").orderByChild("callxId").equalTo(callxId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    cb.onResult(!snap.exists());
                }
                @Override public void onCancelled(DatabaseError e) {
                    cb.onResult(true); // network fail -> let signup try
                }
            });
    }
    private void doSignup(String email, String password,
                          String name, String callxId) {
        showError(""); // clear
        binding.tvError.setVisibility(View.VISIBLE);
        binding.tvError.setTextColor(ContextCompat.getColor(this, 
            com.callx.app.R.color.text_secondary));
        binding.tvError.setText("Account bana raha hoon...");
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener(r -> {
                FirebaseUser user = r.getUser();
                if (user == null) return;
                user.updateProfile(new UserProfileChangeRequest.Builder()
                    .setDisplayName(name).build());
                // If photo chosen -> upload first, then save profile
                if (pickedAvatarUri != null) {
                    binding.tvError.setText("Photo upload ho rahi hai...");
                    CloudinaryUploader.upload(this, pickedAvatarUri,
                        "callx/avatars", "image",
                        new CloudinaryUploader.UploadCallback() {
                            @Override public void onSuccess(
                                CloudinaryUploader.Result res) {
                                saveProfile(user, email, name, callxId,
                                    res.secureUrl);
                            }
                            @Override public void onError(String err) {
                                // photo failed -> still create profile without it
                                Toast.makeText(AuthActivity.this,
                                    "Photo upload fail, account banaya gaya",
                                    Toast.LENGTH_SHORT).show();
                                saveProfile(user, email, name, callxId, null);
                            }
                        });
                } else {
                    saveProfile(user, email, name, callxId, null);
                }
            })
            .addOnFailureListener(e -> showError(e.getMessage()));
    }
    private void saveProfile(FirebaseUser user, String email, String name,
                             String callxId, String photoUrl) {
        Map<String, Object> data = new HashMap<>();
        data.put("uid", user.getUid());
        data.put("email", email);
        data.put("name", name);
        data.put("emoji", "😊");
        data.put("callxId", callxId);     // mobile number
        data.put("mobile", callxId);      // bhi store karo for clarity
        if (photoUrl != null) data.put("photoUrl", photoUrl);
        data.put("about", "Hey, I'm on CallX!");
        data.put("lastSeen", System.currentTimeMillis());
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(user.getUid())
            .setValue(data)
            .addOnSuccessListener(x -> {
                Toast.makeText(this,
                    "Account ready!\nMobile / CallX ID: " + callxId,
                    Toast.LENGTH_LONG).show();
                saveFcmToken(); goToMain();
            })
            .addOnFailureListener(e -> showError(e.getMessage()));
    }
    private void showError(String msg) {
        if (msg == null || msg.isEmpty()) {
            binding.tvError.setVisibility(View.GONE);
            return;
        }
        binding.tvError.setVisibility(View.VISIBLE);
        binding.tvError.setTextColor(ContextCompat.getColor(this, 
            com.callx.app.R.color.action_danger));
        binding.tvError.setText(msg);
    }
    private void saveFcmToken() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();
        FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> {
                if (token == null) return;
                FirebaseDatabase.getInstance(Constants.DB_URL)
                    .getReference("users").child(uid)
                    .child("fcmToken").setValue(token);
            });
    }
    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
