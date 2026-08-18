package com.callx.app.profile;
import com.callx.app.utils.FirebaseUtils;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.ReelCloudinaryUtils;
import com.callx.app.utils.ReelFirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

import de.hdodenhof.circleimageview.CircleImageView;

import androidx.annotation.NonNull;

/**
 * ReelEditProfileActivity — Existing Reel profile edit karo.
 *
 * Chat profile se BILKUL ALAG:
 *   Chat profile  → Firebase: users/{uid}       → ProfileActivity
 *   Reel profile  → Firebase: reels/users/{uid} → ReelEditProfileActivity  ← YE WALA
 *   X profile     → Firebase: x/users/{uid}     → XEditProfileActivity
 *   YT profile    → Firebase: youtube/channels/{uid} → YouTubeEditChannelActivity
 *
 * Cloudinary folders:
 *   Avatar thumb  → callx/reels/avatars/thumbs/
 *   Avatar full   → callx/reels/avatars/
 *   Banner        → callx/reels/banners/
 */
public class ReelEditProfileActivity extends AppCompatActivity {

    private CircleImageView ivAvatar;
    private ImageView       ivBanner;
    private EditText        etDisplayName, etHandle, etBio,
                            etCategory, etWebsite,
                            etInstagram, etTwitter, etYoutube;
    private Button          btnSave;
    private ProgressBar     pbAvatar, pbBanner;
    private View            btnChangeAvatar, btnChangeBanner;

    private String myUid;
    private String currentHandle = "";
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
        setContentView(R.layout.activity_reel_edit_profile);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseUtils.getCurrentUid() : "";

        ivAvatar      = findViewById(R.id.iv_reel_edit_avatar);
        ivBanner      = findViewById(R.id.iv_reel_edit_banner);
        etDisplayName = findViewById(R.id.et_reel_edit_name);
        etHandle      = findViewById(R.id.et_reel_edit_handle);
        etBio         = findViewById(R.id.et_reel_edit_bio);
        etCategory    = findViewById(R.id.et_reel_edit_category);
        etWebsite     = findViewById(R.id.et_reel_edit_website);
        etInstagram   = findViewById(R.id.et_reel_edit_instagram);
        etTwitter     = findViewById(R.id.et_reel_edit_twitter);
        etYoutube     = findViewById(R.id.et_reel_edit_youtube);
        btnSave       = findViewById(R.id.btn_reel_edit_save);
        pbAvatar      = findViewById(R.id.pb_reel_edit_avatar);
        pbBanner      = findViewById(R.id.pb_reel_edit_banner);
        btnChangeAvatar = findViewById(R.id.btn_reel_edit_change_avatar);
        btnChangeBanner = findViewById(R.id.btn_reel_edit_change_banner);

        View btnBack = findViewById(R.id.btn_reel_edit_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        if (btnChangeAvatar != null)
            btnChangeAvatar.setOnClickListener(v -> avatarPicker.launch("image/*"));
        if (ivAvatar != null)
            ivAvatar.setOnClickListener(v -> avatarPicker.launch("image/*"));
        if (btnChangeBanner != null)
            btnChangeBanner.setOnClickListener(v -> bannerPicker.launch("image/*"));
        if (ivBanner != null)
            ivBanner.setOnClickListener(v -> bannerPicker.launch("image/*"));

        btnSave.setOnClickListener(v -> save());

        loadCurrentProfile();
    }

    private void loadCurrentProfile() {
        ReelFirebaseUtils.reelUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    String name     = orEmpty(snap.child("displayName").getValue(String.class));
                    String handle   = orEmpty(snap.child("handle").getValue(String.class));
                    String bio      = orEmpty(snap.child("bio").getValue(String.class));
                    String category = orEmpty(snap.child("category").getValue(String.class));
                    String website  = orEmpty(snap.child("website").getValue(String.class));
                    String instagram= orEmpty(snap.child("instagramHandle").getValue(String.class));
                    String twitter  = orEmpty(snap.child("twitterHandle").getValue(String.class));
                    String youtube  = orEmpty(snap.child("youtubeChannelUrl").getValue(String.class));
                    String photo    = orEmpty(snap.child("photoUrl").getValue(String.class));
                    String thumb    = orEmpty(snap.child("thumbUrl").getValue(String.class));
                    String banner   = orEmpty(snap.child("bannerUrl").getValue(String.class));

                    currentHandle = handle;
                    pendingPhoto  = photo;
                    pendingThumb  = thumb;
                    pendingBanner = banner;

                    if (etDisplayName != null) etDisplayName.setText(name);
                    if (etHandle      != null) etHandle.setText(handle);
                    if (etBio         != null) etBio.setText(bio);
                    if (etCategory    != null) etCategory.setText(category);
                    if (etWebsite     != null) etWebsite.setText(website);
                    if (etInstagram   != null) etInstagram.setText(instagram);
                    if (etTwitter     != null) etTwitter.setText(twitter);
                    if (etYoutube     != null) etYoutube.setText(youtube);

                    // Avatar load
                    String displayUrl = !thumb.isEmpty() ? thumb : photo;
                    if (!displayUrl.isEmpty() && ivAvatar != null)
                        Glide.with(ReelEditProfileActivity.this).load(displayUrl).override(480, 853).into(ivAvatar);

                    // Banner load
                    if (!banner.isEmpty() && ivBanner != null)
                        Glide.with(ReelEditProfileActivity.this).load(banner).override(480, 853).into(ivBanner);
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Toast.makeText(ReelEditProfileActivity.this,
                        "Load error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void uploadAvatar(Uri uri) {
        if (pbAvatar != null) pbAvatar.setVisibility(View.VISIBLE);
        avatarUploading = true;
        updateSaveBtn();

        ReelCloudinaryUtils.uploadReelAvatar(this, uri,
            new ReelCloudinaryUtils.AvatarUploadCallback() {
                @Override public void onThumbReady(String thumbUrl) {
                    pendingThumb = thumbUrl;
                    // Firebase mein turant thumb save karo
                    ReelFirebaseUtils.reelUserRef(myUid).child("thumbUrl").setValue(thumbUrl);
                    if (ivAvatar != null)
                        Glide.with(ReelEditProfileActivity.this).load(thumbUrl).override(480, 853).into(ivAvatar);
                }
                @Override public void onFullReady(String photoUrl) {
                    pendingPhoto = photoUrl;
                    if (pbAvatar != null) pbAvatar.setVisibility(View.GONE);
                    avatarUploading = false;
                    updateSaveBtn();
                    // Firebase mein full photo save karo
                    ReelFirebaseUtils.reelUserRef(myUid).child("photoUrl").setValue(photoUrl);
                    if (ivAvatar != null)
                        Glide.with(ReelEditProfileActivity.this).load(photoUrl).override(480, 853).into(ivAvatar);
                    Toast.makeText(ReelEditProfileActivity.this,
                        "Avatar updated!", Toast.LENGTH_SHORT).show();
                }
                @Override public void onError(String msg) {
                    if (pbAvatar != null) pbAvatar.setVisibility(View.GONE);
                    avatarUploading = false;
                    updateSaveBtn();
                    Toast.makeText(ReelEditProfileActivity.this,
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
                    // Firebase mein banner save karo
                    ReelFirebaseUtils.reelUserRef(myUid).child("bannerUrl").setValue(url);
                    if (ivBanner != null)
                        Glide.with(ReelEditProfileActivity.this).load(url).override(480, 853).into(ivBanner);
                }
                @Override public void onError(String msg) {
                    if (pbBanner != null) pbBanner.setVisibility(View.GONE);
                    bannerUploading = false;
                    updateSaveBtn();
                    Toast.makeText(ReelEditProfileActivity.this,
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
        String bio      = etBio      != null ? etBio.getText().toString().trim() : "";
        String category = etCategory != null ? etCategory.getText().toString().trim() : "";
        String website  = etWebsite  != null ? etWebsite.getText().toString().trim() : "";
        String instagram= etInstagram!= null ? etInstagram.getText().toString().trim() : "";
        String twitter  = etTwitter  != null ? etTwitter.getText().toString().trim() : "";
        String youtube  = etYoutube  != null ? etYoutube.getText().toString().trim() : "";

        if (name.isEmpty()) {
            etDisplayName.setError("Name required");
            return;
        }
        if (handle.isEmpty() || handle.length() < 3) {
            etHandle.setError("Valid handle required (min 3 chars)");
            return;
        }

        btnSave.setEnabled(false);
        btnSave.setText("Saving...");

        // Handle changed? Check uniqueness
        if (!handle.equals(currentHandle)) {
            ReelFirebaseUtils.reelHandleRef(handle)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        if (snap.exists() && !myUid.equals(snap.getValue(String.class))) {
                            etHandle.setError("Handle already taken");
                            etHandle.requestFocus();
                            btnSave.setEnabled(true);
                            btnSave.setText("Save");
                            return;
                        }
                        performSave(name, handle, bio, category, website,
                            instagram, twitter, youtube);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        btnSave.setEnabled(true);
                        btnSave.setText("Save");
                    }
                });
        } else {
            performSave(name, handle, bio, category, website, instagram, twitter, youtube);
        }
    }

    private void performSave(String name, String handle, String bio,
                             String category, String website,
                             String instagram, String twitter, String youtube) {
        // BUG FIX: previously this built a nested Map and put it at the
        // single key "reels/users/{myUid}", then called updateChildren() on
        // the ROOT reference. Firebase's multi-path update() treats that as
        // a SET (full replace) of everything AT that path — not a merge —
        // so any sibling fields already sitting under reels/users/{myUid}
        // that weren't part of this map (profileSongStripColor,
        // profileBioChipColors/*, gridAccentColors/*, profileBioStripColor,
        // etc.) were being silently wiped back to default on every bio/
        // profile save. Fix: flatten each field to its own full path
        // ("reels/users/{myUid}/bio", ...) so updateChildren() performs a
        // real per-leaf merge and leaves every other child untouched.
        String base = "reels/users/" + myUid + "/";
        Map<String, Object> rootUpdates = new HashMap<>();
        rootUpdates.put(base + "displayName",       name);
        rootUpdates.put(base + "handle",            handle);
        rootUpdates.put(base + "bio",               bio);
        rootUpdates.put(base + "category",          category);
        rootUpdates.put(base + "website",           website);
        rootUpdates.put(base + "instagramHandle",   instagram);
        rootUpdates.put(base + "twitterHandle",     twitter);
        rootUpdates.put(base + "youtubeChannelUrl", youtube);
        rootUpdates.put(base + "photoUrl",          pendingPhoto);
        rootUpdates.put(base + "thumbUrl",          pendingThumb);
        rootUpdates.put(base + "bannerUrl",         pendingBanner);
        rootUpdates.put(base + "updatedAt",         System.currentTimeMillis());

        // If handle changed: update handle index too
        if (!handle.equals(currentHandle)) {
            // Remove old handle index
            if (!currentHandle.isEmpty())
                rootUpdates.put("reels/handles/" + currentHandle, null);
            rootUpdates.put("reels/handles/" + handle, myUid);
        }

        com.google.firebase.database.FirebaseDatabase.getInstance(
            com.callx.app.utils.Constants.DB_URL)
            .getReference()
            .updateChildren(rootUpdates)
            .addOnSuccessListener(v -> {
                currentHandle = handle;
                Toast.makeText(this, "Profile saved!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            })
            .addOnFailureListener(e -> {
                btnSave.setEnabled(true);
                btnSave.setText("Save");
                Toast.makeText(this, "Save failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            });
    }

    private String orEmpty(String s) { return s == null ? "" : s; }
}
