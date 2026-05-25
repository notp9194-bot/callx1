package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.models.ReelProfile;
import com.callx.app.reels.R;
import com.callx.app.utils.ReelCloudinaryUtils;
import com.callx.app.utils.ReelFirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;

import java.util.HashMap;
import java.util.Map;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ReelProfileSetupActivity — First time Reel profile create karo.
 *
 * Chat profile se BILKUL ALAG:
 *   Chat profile → Firebase: users/{uid}
 *   Reel profile → Firebase: reels/users/{uid}
 *
 * Ye activity tab open hoti hai jab user pehli baar Reels tab pe jaata hai
 * aur reels/users/{uid} node exist nahi karta.
 *
 * Usage:
 *   startActivity(new Intent(ctx, ReelProfileSetupActivity.class));
 */
public class ReelProfileSetupActivity extends AppCompatActivity {

    private CircleImageView ivAvatar;
    private ImageView       ivBanner;
    private EditText        etDisplayName, etHandle, etBio, etCategory, etWebsite;
    private Button          btnSave;
    private ProgressBar     pbAvatar, pbBanner;
    private View            btnChangeAvatar, btnChangeBanner;

    private String myUid;
    private String pendingThumb = "", pendingPhoto = "", pendingBanner = "";
    private boolean avatarUploading = false, bannerUploading = false;

    private final ActivityResultLauncher<String> avatarPicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) uploadAvatar(uri); });

    private final ActivityResultLauncher<String> bannerPicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) uploadBanner(uri); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_profile_setup);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        ivAvatar        = findViewById(R.id.iv_reel_setup_avatar);
        ivBanner        = findViewById(R.id.iv_reel_setup_banner);
        etDisplayName   = findViewById(R.id.et_reel_setup_name);
        etHandle        = findViewById(R.id.et_reel_setup_handle);
        etBio           = findViewById(R.id.et_reel_setup_bio);
        etCategory      = findViewById(R.id.et_reel_setup_category);
        etWebsite       = findViewById(R.id.et_reel_setup_website);
        btnSave         = findViewById(R.id.btn_reel_setup_save);
        pbAvatar        = findViewById(R.id.pb_reel_setup_avatar);
        pbBanner        = findViewById(R.id.pb_reel_setup_banner);
        btnChangeAvatar = findViewById(R.id.btn_reel_change_avatar);
        btnChangeBanner = findViewById(R.id.btn_reel_change_banner);

        // Back button
        View btnBack = findViewById(R.id.btn_reel_setup_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Avatar / Banner pickers
        if (btnChangeAvatar != null)
            btnChangeAvatar.setOnClickListener(v -> avatarPicker.launch("image/*"));
        if (ivAvatar != null)
            ivAvatar.setOnClickListener(v -> avatarPicker.launch("image/*"));
        if (btnChangeBanner != null)
            btnChangeBanner.setOnClickListener(v -> bannerPicker.launch("image/*"));
        if (ivBanner != null)
            ivBanner.setOnClickListener(v -> bannerPicker.launch("image/*"));

        // Pre-fill name from Chat profile (convenience)
        ReelFirebaseUtils.reelUserRef(myUid).addListenerForSingleValueEvent(
            new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    if (snap.exists()) {
                        // Existing reel profile → redirect to edit
                        startActivity(new Intent(ReelProfileSetupActivity.this,
                            ReelEditProfileActivity.class));
                        finish();
                    }
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });

        // Also try to pre-fill name from chat profile
        com.callx.app.utils.FirebaseUtils.getUserRef(myUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    String chatName = snap.child("name").getValue(String.class);
                    if (chatName != null && !chatName.isEmpty() && etDisplayName != null) {
                        etDisplayName.setText(chatName);
                        // Suggest handle from name
                        String suggested = chatName.toLowerCase()
                            .replaceAll("[^a-z0-9_]", "_")
                            .replaceAll("_{2,}", "_");
                        if (etHandle != null) etHandle.setText(suggested);
                    }
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });

        btnSave.setOnClickListener(v -> save());
    }

    private void uploadAvatar(Uri uri) {
        if (pbAvatar != null) pbAvatar.setVisibility(View.VISIBLE);
        avatarUploading = true;
        updateSaveBtn();

        ReelCloudinaryUtils.uploadReelAvatar(this, uri,
            new ReelCloudinaryUtils.AvatarUploadCallback() {
                @Override public void onThumbReady(String thumbUrl) {
                    pendingThumb = thumbUrl;
                    Glide.with(ReelProfileSetupActivity.this).load(thumbUrl).into(ivAvatar);
                }
                @Override public void onFullReady(String photoUrl) {
                    pendingPhoto = photoUrl;
                    if (pbAvatar != null) pbAvatar.setVisibility(View.GONE);
                    avatarUploading = false;
                    updateSaveBtn();
                    Glide.with(ReelProfileSetupActivity.this).load(photoUrl).into(ivAvatar);
                }
                @Override public void onError(String msg) {
                    if (pbAvatar != null) pbAvatar.setVisibility(View.GONE);
                    avatarUploading = false;
                    updateSaveBtn();
                    Toast.makeText(ReelProfileSetupActivity.this,
                        "Avatar upload failed: " + msg, Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void uploadBanner(Uri uri) {
        if (pbBanner != null) pbBanner.setVisibility(View.VISIBLE);
        bannerUploading = true;
        updateSaveBtn();

        ReelCloudinaryUtils.uploadReelBanner(this, uri,
            new ReelCloudinaryUtils.ImageUploadCallback() {
                @Override public void onSuccess(String url) {
                    pendingBanner = url;
                    if (pbBanner != null) pbBanner.setVisibility(View.GONE);
                    bannerUploading = false;
                    updateSaveBtn();
                    Glide.with(ReelProfileSetupActivity.this).load(url).into(ivBanner);
                }
                @Override public void onError(String msg) {
                    if (pbBanner != null) pbBanner.setVisibility(View.GONE);
                    bannerUploading = false;
                    updateSaveBtn();
                    Toast.makeText(ReelProfileSetupActivity.this,
                        "Banner upload failed: " + msg, Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void updateSaveBtn() {
        if (btnSave != null)
            btnSave.setEnabled(!avatarUploading && !bannerUploading);
    }

    private void save() {
        String name     = etDisplayName.getText().toString().trim();
        String handle   = ReelFirebaseUtils.cleanHandle(etHandle.getText().toString().trim());
        String bio      = etBio     != null ? etBio.getText().toString().trim() : "";
        String category = etCategory!= null ? etCategory.getText().toString().trim() : "";
        String website  = etWebsite != null ? etWebsite.getText().toString().trim() : "";

        if (name.isEmpty()) {
            etDisplayName.setError("Name required");
            etDisplayName.requestFocus();
            return;
        }
        if (handle.isEmpty()) {
            etHandle.setError("Handle required");
            etHandle.requestFocus();
            return;
        }
        if (handle.length() < 3) {
            etHandle.setError("Handle kam se kam 3 characters ka hona chahiye");
            etHandle.requestFocus();
            return;
        }

        btnSave.setEnabled(false);
        btnSave.setText("Saving...");

        // Check handle uniqueness
        ReelFirebaseUtils.reelHandleRef(handle).addListenerForSingleValueEvent(
            new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    if (snap.exists() && !myUid.equals(snap.getValue(String.class))) {
                        etHandle.setError("Ye handle already liya hua hai");
                        etHandle.requestFocus();
                        btnSave.setEnabled(true);
                        btnSave.setText("Create Profile");
                        return;
                    }
                    createProfile(name, handle, bio, category, website);
                }
                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    btnSave.setEnabled(true);
                    btnSave.setText("Create Profile");
                    Toast.makeText(ReelProfileSetupActivity.this,
                        "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void createProfile(String name, String handle, String bio,
                               String category, String website) {
        long now = System.currentTimeMillis();
        Map<String, Object> profile = new HashMap<>();
        profile.put("uid",         myUid);
        profile.put("displayName", name);
        profile.put("handle",      handle);
        profile.put("bio",         bio);
        profile.put("category",    category);
        profile.put("website",     website);
        profile.put("photoUrl",    pendingPhoto);
        profile.put("thumbUrl",    pendingThumb);
        profile.put("bannerUrl",   pendingBanner);
        profile.put("followerCount",  0L);
        profile.put("followingCount", 0L);
        profile.put("reelCount",      0L);
        profile.put("totalLikes",     0L);
        profile.put("verified",       false);
        profile.put("privateAccount", false);
        profile.put("allowDuet",      true);
        profile.put("allowStitch",    true);
        profile.put("allowComments",  true);
        profile.put("createdAt",   now);
        profile.put("updatedAt",   now);

        // Save profile + handle index atomically
        Map<String, Object> updates = new HashMap<>();
        updates.put("reels/users/" + myUid, profile);
        updates.put("reels/handles/" + handle, myUid);

        com.google.firebase.database.FirebaseDatabase.getInstance(
            com.callx.app.utils.Constants.DB_URL)
            .getReference()
            .updateChildren(updates)
            .addOnSuccessListener(v -> {
                Toast.makeText(this, "Reel profile ready!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            })
            .addOnFailureListener(e -> {
                btnSave.setEnabled(true);
                btnSave.setText("Create Profile");
                Toast.makeText(this, "Save failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            });
    }
}
