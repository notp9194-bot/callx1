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
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.databinding.ActivityProfileSetupBinding;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

public class ProfileSetupActivity extends AppCompatActivity {

    private ActivityProfileSetupBinding binding;
    private Uri pickedAvatarUri = null;
    private ActivityResultLauncher<String> avatarPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        boolean isNewUser = getIntent().getBooleanExtra("isNewUser", false);

        // Pre-fill name from Google account if available
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && user.getDisplayName() != null && !user.getDisplayName().isEmpty()) {
            binding.etName.setText(user.getDisplayName());
        }
        if (user != null && user.getPhotoUrl() != null) {
            Glide.with(this).load(user.getPhotoUrl()).circleCrop()
                    .override(240, 240)
                .into(binding.ivAvatarPreview);
        }

        avatarPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pickedAvatarUri = uri;
                    Glide.with(this).load(uri).circleCrop()
                    .override(240, 240).into(binding.ivAvatarPreview);
                }
            });

        binding.flAvatarPicker.setOnClickListener(v -> avatarPicker.launch("image/*"));

        binding.btnSave.setOnClickListener(v -> saveProfile());

        if (isNewUser) {
            binding.tvSkip.setVisibility(View.VISIBLE);
            binding.tvSkip.setOnClickListener(v -> goToMain());
        } else {
            binding.tvSkip.setVisibility(View.GONE);
            binding.toolbar.setNavigationOnClickListener(v2 -> finish());
        }
    }

    private void saveProfile() {
        String name = binding.etName.getText().toString().trim();
        String mobile = binding.etMobile.getText().toString().replaceAll("[^0-9]", "");
        String about = binding.etAbout.getText().toString().trim();

        if (name.isEmpty()) { showError("Naam zaroori hai"); return; }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        showLoading("Saving...");

        if (pickedAvatarUri != null) {
            CloudinaryUploader.uploadAvatar(this, pickedAvatarUri,
                new CloudinaryUploader.AvatarUploadCallback() {
                    @Override public void onThumbReady(String thumbUrl) {}
                    @Override public void onFullReady(String photoUrl) {
                        saveToFirebase(user, name, mobile, about, photoUrl);
                    }
                    @Override public void onError(String err) {
                        saveToFirebase(user, name, mobile, about, null);
                    }
                });
        } else {
            String existingPhoto = user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;
            saveToFirebase(user, name, mobile, about, existingPhoto);
        }
    }

    private void saveToFirebase(FirebaseUser user, String name, String mobile,
                                String about, String photoUrl) {
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("name", name);
        updates.put("nameLower", name.toLowerCase(java.util.Locale.getDefault()));
        if (!mobile.isEmpty()) {
            updates.put("mobile", mobile);
            updates.put("callxId", mobile);
        }
        updates.put("about", about.isEmpty() ? "Hey, I'm on CallX!" : about);
        if (photoUrl != null) updates.put("photoUrl", photoUrl);

        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(user.getUid())
            .updateChildren(updates)
            .addOnSuccessListener(x -> {
                Toast.makeText(this, "Profile save ho gaya!", Toast.LENGTH_SHORT).show();
                goToMain();
            })
            .addOnFailureListener(e -> showError(e.getMessage()));
    }

    private void showError(String msg) {
        binding.tvError.setVisibility(View.VISIBLE);
        binding.tvError.setTextColor(getResources().getColor(com.callx.app.R.color.action_danger));
        binding.tvError.setText(msg);
    }

    private void showLoading(String msg) {
        binding.tvError.setVisibility(View.VISIBLE);
        binding.tvError.setTextColor(getResources().getColor(com.callx.app.R.color.text_secondary));
        binding.tvError.setText(msg);
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
