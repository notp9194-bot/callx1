package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.models.XUser;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.XCloudinaryUtils;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.HashMap;
import java.util.Map;

/**
 * XEditProfileActivity — X (Twitter-like) profile editor.
 *
 * Fixes applied:
 *  1. NULL CRASH — loadProfile() now seeds /x/users from /users if xUser is missing.
 *  2. CROSS-SYSTEM SYNC — saveProfile() writes to BOTH:
 *       • /x/users/{uid}  (X feature profile)
 *       • /users/{uid}    (main app — chat, reels, status all read from here)
 *  3. PHOTO SYNC — avatar upload updates both nodes so chat/reels show fresh photo.
 *  4. RESULT propagation — sets RESULT_OK so XProfileActivity refreshes immediately.
 */
public class XEditProfileActivity extends AppCompatActivity {

    private String myUid;
    private XUser  xUser;

    private CircleImageView ivAvatar;
    private ImageView       ivBanner;
    private EditText        etName, etBio, etWebsite, etLocation;
    private ProgressBar     pbSave;
    private boolean         pickingAvatar;

    // ── Image picker ──────────────────────────────────────────────────────────
    private final ActivityResultLauncher<Intent> imagePicker =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) uploadImage(uri, pickingAvatar);
            }
        });

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_edit_profile);

        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        myUid = firebaseUser != null ? firebaseUser.getUid() : "";
        if (myUid.isEmpty()) { finish(); return; }

        ivAvatar   = findViewById(R.id.iv_edit_avatar);
        ivBanner   = findViewById(R.id.iv_edit_banner);
        etName     = findViewById(R.id.et_edit_name);
        etBio      = findViewById(R.id.et_edit_bio);
        etWebsite  = findViewById(R.id.et_edit_website);
        etLocation = findViewById(R.id.et_edit_location);
        pbSave     = findViewById(R.id.pb_edit_save);

        findViewById(R.id.btn_edit_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_edit_save).setOnClickListener(v -> saveProfile());
        ivAvatar.setOnClickListener(v -> { pickingAvatar = true;  pickImage(); });
        ivBanner.setOnClickListener(v -> { pickingAvatar = false; pickImage(); });

        loadProfile();
    }

    // ── Load profile — FIX 1: seed from /users if /x/users is empty ──────────
    private void loadProfile() {
        XFirebaseUtils.xUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot xSnap) {
                xUser = xSnap.getValue(XUser.class);

                if (xUser == null) {
                    // User exists in /users but hasn't used X yet → seed XUser from main profile
                    seedXUserFromMainProfile();
                } else {
                    // Ensure null maps don't cause NPE in helper methods
                    if (xUser.followers == null) xUser.followers = new HashMap<>();
                    if (xUser.muted     == null) xUser.muted     = new HashMap<>();
                    if (xUser.blocked   == null) xUser.blocked   = new HashMap<>();
                    xUser.uid = myUid;
                    bindFields();
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Toast.makeText(XEditProfileActivity.this,
                    "Failed to load profile", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Reads /users/{uid} (main app node) and creates a matching XUser
     * in /x/users/{uid} so the X feature works even for users who haven't tweeted.
     */
    private void seedXUserFromMainProfile() {
        FirebaseUtils.getUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    xUser = new XUser();
                    xUser.uid      = myUid;
                    xUser.name     = snap.child("name").getValue(String.class);
                    xUser.photoUrl = snap.child("photoUrl").getValue(String.class);
                    xUser.thumbUrl = snap.child("thumbUrl").getValue(String.class);
                    xUser.bio      = snap.child("about").getValue(String.class);
                    // Derive handle from callxId/mobile, else uid prefix
                    String callxId = snap.child("callxId").getValue(String.class);
                    xUser.handle = (callxId != null && !callxId.isEmpty())
                        ? callxId.replaceAll("[^a-zA-Z0-9_]", "")
                        : myUid.substring(0, Math.min(10, myUid.length()));
                    if (xUser.name == null)   xUser.name   = "User";
                    if (xUser.bio  == null)   xUser.bio    = "";
                    xUser.joinedTs = System.currentTimeMillis();
                    xUser.followers = new HashMap<>();
                    xUser.muted     = new HashMap<>();
                    xUser.blocked   = new HashMap<>();
                    // Persist seed to /x/users
                    XFirebaseUtils.xUserRef(myUid).setValue(xUser);
                    bindFields();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    // Fallback: create minimal XUser so UI doesn't crash
                    xUser = new XUser();
                    xUser.uid       = myUid;
                    xUser.name      = "User";
                    xUser.handle    = myUid.substring(0, Math.min(10, myUid.length()));
                    xUser.followers = new HashMap<>();
                    xUser.muted     = new HashMap<>();
                    xUser.blocked   = new HashMap<>();
                    bindFields();
                }
            });
    }

    private void bindFields() {
        etName.setText(xUser.name     != null ? xUser.name     : "");
        etBio.setText (xUser.bio      != null ? xUser.bio      : "");
        etWebsite.setText(xUser.website  != null ? xUser.website  : "");
        etLocation.setText(xUser.location != null ? xUser.location : "");

        if (xUser.photoUrl != null && !xUser.photoUrl.isEmpty())
            Glide.with(this).load(xUser.avatarUrl()).circleCrop()
                .placeholder(R.drawable.ic_person).into(ivAvatar);
        if (xUser.bannerUrl != null && !xUser.bannerUrl.isEmpty())
            Glide.with(this).load(xUser.bannerUrl).centerCrop().into(ivBanner);
    }

    // ── Image pick ────────────────────────────────────────────────────────────
    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        i.setType("image/*");
        imagePicker.launch(i);
    }

    // ── Upload — FIX 3: sync avatar to BOTH /x/users AND /users ──────────────
    private void uploadImage(Uri uri, boolean isAvatar) {
        pbSave.setVisibility(android.view.View.VISIBLE);
        XCloudinaryUtils.XUploadListener cb = new XCloudinaryUtils.XUploadListener() {
            @Override public void onSuccess(String publicId, String url) {
                runOnUiThread(() -> {
                    pbSave.setVisibility(android.view.View.GONE);
                    if (isAvatar) {
                        // Derive thumbnail URL by inserting Cloudinary transformation
                        String thumbUrl = toCloudinaryThumbUrl(url);
                        Glide.with(XEditProfileActivity.this).load(thumbUrl)
                            .circleCrop().into(ivAvatar);
                        // Sync photoUrl + thumbUrl to X users node
                        XFirebaseUtils.xUserRef(myUid).child("photoUrl").setValue(url);
                        XFirebaseUtils.xUserRef(myUid).child("thumbUrl").setValue(thumbUrl);
                        // FIX 3: sync to main /users node so chat & reels see the new photo
                        FirebaseUtils.getUserRef(myUid).child("photoUrl").setValue(url);
                        FirebaseUtils.getUserRef(myUid).child("thumbUrl").setValue(thumbUrl);
                        if (xUser != null) { xUser.photoUrl = url; xUser.thumbUrl = thumbUrl; }
                    } else {
                        Glide.with(XEditProfileActivity.this).load(url)
                            .centerCrop().into(ivBanner);
                        XFirebaseUtils.xUserRef(myUid).child("bannerUrl").setValue(url);
                        if (xUser != null) xUser.bannerUrl = url;
                    }
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    pbSave.setVisibility(android.view.View.GONE);
                    Toast.makeText(XEditProfileActivity.this,
                        "Upload failed: " + msg, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onProgress(int pct) {}
        };
        XCloudinaryUtils.uploadTweetImage(this, uri, cb);
    }

    // ── Save — FIX 2 & 4: write to both nodes + RESULT_OK ────────────────────
    private void saveProfile() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) { etName.setError("Name required"); return; }

        pbSave.setVisibility(android.view.View.VISIBLE);

        String bio      = etBio.getText().toString().trim();
        String website  = etWebsite.getText().toString().trim();
        String location = etLocation.getText().toString().trim();

        // ── 1. Update /x/users/{uid} (X feature data) ──────────────────────
        Map<String, Object> xUpdates = new HashMap<>();
        xUpdates.put("name",     name);
        xUpdates.put("bio",      bio);
        xUpdates.put("website",  website);
        xUpdates.put("location", location);

        // ── 2. Update /users/{uid} (main app — chat bubbles, reels, status) ─
        //       Only fields that cross-feature components read:
        //         • chat    → name, photoUrl, nameLower
        //         • reels   → name, photoUrl
        //         • status  → name, photoUrl
        Map<String, Object> mainUpdates = new HashMap<>();
        mainUpdates.put("name",      name);
        mainUpdates.put("nameLower", name.toLowerCase(java.util.Locale.getDefault()));
        mainUpdates.put("about",     bio.isEmpty() ? "Hey, I'm on CallX!" : bio);

        XFirebaseUtils.xUserRef(myUid)
            .updateChildren(xUpdates)
            .addOnSuccessListener(unused -> {
                // Write to main node after X node succeeds
                FirebaseUtils.getUserRef(myUid)
                    .updateChildren(mainUpdates)
                    .addOnCompleteListener(task -> {
                        pbSave.setVisibility(android.view.View.GONE);
                        if (task.isSuccessful()) {
                            Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
                            setResult(RESULT_OK);   // FIX 4: let XProfileActivity refresh
                            finish();
                        } else {
                            Toast.makeText(this, "Save failed (main sync)",
                                Toast.LENGTH_SHORT).show();
                        }
                    });
            })
            .addOnFailureListener(e -> {
                pbSave.setVisibility(android.view.View.GONE);
                Toast.makeText(this, "Save failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            });
    }

    /**
     * Derives a thumbnail URL from a Cloudinary full-photo URL by inserting
     * transformation parameters (100×100, crop to thumb, WebP, 60% quality).
     * E.g.: .../upload/v123/folder/file.jpg
     *     → .../upload/w_100,h_100,c_thumb,q_60,f_webp/v123/folder/file.jpg
     * Falls back to the original url if it's not a recognisable Cloudinary URL.
     */
    private static String toCloudinaryThumbUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        final String marker = "/upload/";
        int idx = url.indexOf(marker);
        if (idx < 0) return url; // not a Cloudinary URL, return as-is
        return url.substring(0, idx + marker.length())
            + "w_100,h_100,c_thumb,q_60,f_webp/"
            + url.substring(idx + marker.length());
    }
}
