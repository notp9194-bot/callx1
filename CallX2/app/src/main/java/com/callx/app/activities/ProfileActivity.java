package com.callx.app.activities;
import android.app.Dialog;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.R;
import com.callx.app.databinding.ActivityProfileBinding;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.UserEntity;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.AvatarUrlBuilder;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class ProfileActivity extends AppCompatActivity {
    private ActivityProfileBinding binding;
    private String currentUid;
    private String viewUid;       // uid being viewed (may differ from currentUid)
    private boolean isOwnProfile;
    private String currentPhoto = "";
    private ActivityResultLauncher<String> imagePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        currentUid = FirebaseUtils.getCurrentUid();
        String intentUid = getIntent().getStringExtra("uid");
        viewUid      = (intentUid != null && !intentUid.isEmpty()) ? intentUid : currentUid;
        isOwnProfile = viewUid.equals(currentUid);

        if (isOwnProfile) {
            // Own profile — edit mode
            imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAvatar(uri); });
            binding.btnChangeAvatar.setOnClickListener(v -> imagePicker.launch("image/*"));
            binding.btnSave.setOnClickListener(v -> save());
            setupCommunityEntryPoint();
        } else {
            // Someone else's profile — read-only
            binding.btnChangeAvatar.setVisibility(View.GONE);
            binding.btnSave.setVisibility(View.GONE);
            binding.etName.setEnabled(false);
            binding.etAbout.setEnabled(false);
            binding.toolbar.setTitle("Profile");
        }

        // Long press avatar → zoom full photo
        binding.ivAvatar.setOnLongClickListener(v -> {
            showAvatarZoom(currentPhoto);
            return true;
        });

        load();
    }

    private void load() {
        // Room cache — sirf apne profile ke liye (offline fallback)
        if (isOwnProfile) {
            Executors.newSingleThreadExecutor().execute(() -> {
                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                UserEntity cached = db.userDao().getUser(viewUid);
                if (cached != null) {
                    runOnUiThread(() -> {
                        binding.etName.setText(orEmpty(cached.name));
                        binding.etAbout.setText(orEmpty(cached.about));
                        binding.tvCallxId.setText(orEmpty(cached.callxId));
                        binding.tvEmail.setText(orEmpty(cached.email));
                        currentPhoto = orEmpty(cached.photoUrl);
                        String cachedThumb = orEmpty(cached.thumbUrl);
                        String cacheDisplayUrl = !cachedThumb.isEmpty() ? cachedThumb : currentPhoto;
                        if (!cacheDisplayUrl.isEmpty()) {
                            Glide.with(ProfileActivity.this)
                                .load(AvatarUrlBuilder.build(ProfileActivity.this, cacheDisplayUrl, 120))
                    .override(720, 720)
                                .into(binding.ivAvatar);
                        }
                    });
                }
            });
        }

        // Firebase se fresh data
        FirebaseUtils.getUserRef(viewUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    String name      = orEmpty(s.child("name").getValue(String.class));
                    String about     = orEmpty(s.child("about").getValue(String.class));
                    String bio       = orEmpty(s.child("bio").getValue(String.class));
                    String phone     = orEmpty(s.child("phone").getValue(String.class));
                    String whatsapp  = orEmpty(s.child("whatsapp").getValue(String.class));
                    String instagram = orEmpty(s.child("instagram").getValue(String.class));
                    String youtube   = orEmpty(s.child("youtube").getValue(String.class));
                    String otherLink = orEmpty(s.child("otherLink").getValue(String.class));
                    String callxId   = orEmpty(s.child("callxId").getValue(String.class));
                    String email     = isOwnProfile ? orEmpty(s.child("email").getValue(String.class)) : "";
                    String photo     = orEmpty(s.child("photoUrl").getValue(String.class));
                    String thumb     = orEmpty(s.child("thumbUrl").getValue(String.class));

                    binding.etName.setText(name);
                    binding.etAbout.setText(about);
                    if (binding.etBio      != null) binding.etBio.setText(bio);
                    if (binding.etPhone    != null) binding.etPhone.setText(phone);
                    if (binding.etWhatsapp != null) binding.etWhatsapp.setText(whatsapp);
                    if (binding.etInstagram!= null) binding.etInstagram.setText(instagram);
                    if (binding.etYoutube  != null) binding.etYoutube.setText(youtube);
                    if (binding.etOtherLink!= null) binding.etOtherLink.setText(otherLink);
                    binding.tvCallxId.setText(callxId);
                    if (isOwnProfile) binding.tvEmail.setText(email);
                    currentPhoto = photo;
                    String displayThumb = !thumb.isEmpty() ? thumb : photo;
                    if (!displayThumb.isEmpty()) {
                        Glide.with(ProfileActivity.this)
                            .load(AvatarUrlBuilder.build(ProfileActivity.this, displayThumb, 120))
                    .override(720, 720)
                            .into(binding.ivAvatar);
                    }

                    // Room cache update — sirf apne profile ke liye
                    if (isOwnProfile) {
                        Executors.newSingleThreadExecutor().execute(() -> {
                            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                            UserEntity u = db.userDao().getUser(viewUid);
                            if (u == null) u = new UserEntity();
                            u.uid     = viewUid;
                            u.name    = name;
                            u.about   = about;
                            u.callxId = callxId;
                            u.email   = email;
                            u.photoUrl = photo;
                            u.cachedAt = System.currentTimeMillis();
                            db.userDao().insertUser(u);
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private String orEmpty(String s) { return s == null ? "" : s; }
    private void uploadAvatar(Uri uri) {
        binding.avatarProgress.setVisibility(View.VISIBLE);
        CloudinaryUploader.uploadAvatar(this, uri,
            new CloudinaryUploader.AvatarUploadCallback() {

                // Step 1 done: thumbnail ready → Firebase thumbUrl save + UI
                @Override public void onThumbReady(String thumbUrl) {
                    FirebaseUtils.getUserRef(currentUid)
                        .child("thumbUrl").setValue(thumbUrl);
                    // Room cache update
                    Executors.newSingleThreadExecutor().execute(() -> {
                        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                        db.userDao().updateThumb(currentUid, thumbUrl);
                    });
                    // Profile screen mein bhi thumb dikhao (snap fast)
                    Glide.with(ProfileActivity.this)
                        .load(AvatarUrlBuilder.build(ProfileActivity.this, thumbUrl, 120))
                    .override(720, 720)
                        .into(binding.ivAvatar);
                }

                // Step 2 done: full photo ready → Firebase photoUrl save
                @Override public void onFullReady(String photoUrl) {
                    binding.avatarProgress.setVisibility(View.GONE);
                    currentPhoto = photoUrl;
                    FirebaseUtils.getUserRef(currentUid)
                        .child("photoUrl").setValue(photoUrl);
                    // Room cache update
                    Executors.newSingleThreadExecutor().execute(() -> {
                        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                        db.userDao().updatePhoto(currentUid, photoUrl);
                    });
                    // Profile screen par full photo reload karo
                    Glide.with(ProfileActivity.this)
                        .load(AvatarUrlBuilder.build(ProfileActivity.this, photoUrl, 120))
                    .override(720, 720)
                        .into(binding.ivAvatar);
                    Toast.makeText(ProfileActivity.this,
                        "Profile photo update ho gayi", Toast.LENGTH_SHORT).show();
                }

                @Override public void onError(String err) {
                    binding.avatarProgress.setVisibility(View.GONE);
                    Toast.makeText(ProfileActivity.this,
                        err == null ? "Upload fail" : err,
                        Toast.LENGTH_LONG).show();
                }
            });
    }
    private void save() {
        String name      = binding.etName.getText().toString().trim();
        String about     = binding.etAbout.getText().toString().trim();
        String bio       = binding.etBio       != null ? binding.etBio.getText().toString().trim() : "";
        String phone     = binding.etPhone     != null ? binding.etPhone.getText().toString().trim() : "";
        String whatsapp  = binding.etWhatsapp  != null ? binding.etWhatsapp.getText().toString().trim() : "";
        String instagram = binding.etInstagram != null ? binding.etInstagram.getText().toString().trim() : "";
        String youtube   = binding.etYoutube   != null ? binding.etYoutube.getText().toString().trim() : "";
        String otherLink = binding.etOtherLink != null ? binding.etOtherLink.getText().toString().trim() : "";

        if (name.isEmpty()) {
            Toast.makeText(this, "Naam khali nahi ho sakta",
                Toast.LENGTH_SHORT).show(); return;
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put("name",      name);
        updates.put("nameLower", name.toLowerCase(java.util.Locale.getDefault()));
        updates.put("about",     about);
        updates.put("bio",       bio);
        updates.put("phone",     phone);
        updates.put("whatsapp",  whatsapp);
        updates.put("instagram", instagram);
        updates.put("youtube",   youtube);
        updates.put("otherLink", otherLink);
        FirebaseUtils.getUserRef(currentUid).updateChildren(updates);
        FirebaseAuth.getInstance().getCurrentUser()
            .updateProfile(new UserProfileChangeRequest.Builder()
                .setDisplayName(name).build());
        Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showAvatarZoom(String photoUrl) {
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            this, photoUrl, R.drawable.ic_person, R.drawable.ic_close);
    }

    // ─────────────────────────────────────────────────────────────────────
    // COMMUNITY — opt-in "Enable"/"Manage" entry point on the user's own
    // profile. app module has a real Gradle dependency on :feature-chat
    // (see app/build.gradle), so CommunityRepository/CommunityActivity/
    // ManageCommunityActivity are referenced directly here — no reflection
    // needed, unlike the cross-module Reels/X/YouTube calls in ChatActivity.
    // ─────────────────────────────────────────────────────────────────────

    private String myCommunityId;

    private void setupCommunityEntryPoint() {
        binding.btnCommunity.setVisibility(View.VISIBLE);
        binding.btnCommunity.setText("Community");
        com.callx.app.repository.CommunityRepository.getInstance(this)
            .checkHasCommunity(currentUid, communityId -> runOnUiThread(() -> {
                myCommunityId = communityId;
                binding.btnCommunity.setText(communityId != null ? "Open Your Community" : "Enable Community");
            }));

        binding.btnCommunity.setOnClickListener(v -> {
            if (myCommunityId != null) {
                // Opens the Community hub (Feed / Announcements / Events /
                // Groups / Members / Gallery tabs + compose FAB) — this is
                // where the owner actually posts/does activity. Settings
                // (name, description, privacy, invite link) live one level
                // in, via the overflow menu's "Manage" action inside
                // CommunityActivity itself — not here.
                android.content.Intent i = new android.content.Intent(
                        this, com.callx.app.community.CommunityActivity.class);
                i.putExtra(com.callx.app.community.CommunityActivity.EXTRA_COMMUNITY_ID, myCommunityId);
                startActivity(i);
            } else {
                showCreateCommunityDialog();
            }
        });
    }

    private void showCreateCommunityDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        android.widget.EditText etName = new android.widget.EditText(this);
        etName.setHint("Community name");
        layout.addView(etName);

        android.widget.EditText etDescription = new android.widget.EditText(this);
        etDescription.setHint("Description (optional)");
        etDescription.setPadding(0, pad / 2, 0, 0);
        layout.addView(etDescription);

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enable Your Community")
            .setMessage("Create a Community — a combined feed and group-chat hub linked to your profile. " +
                    "Only contacts you choose to show it to (via \"View Community\") can find it.")
            .setView(layout)
            .setPositiveButton("Create", (d, w) -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "Name can't be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                String description = etDescription.getText().toString().trim();
                String myName = FirebaseAuth.getInstance().getCurrentUser() != null
                        ? FirebaseAuth.getInstance().getCurrentUser().getDisplayName() : "";
                com.callx.app.repository.CommunityRepository.getInstance(this)
                    .createCommunity(currentUid, myName, currentPhoto, name, description, null, newId -> {
                        runOnUiThread(() -> {
                            if (newId != null) {
                                myCommunityId = newId;
                                binding.btnCommunity.setText("Open Your Community");
                                Toast.makeText(this, "Community created", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "Failed to create community", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
