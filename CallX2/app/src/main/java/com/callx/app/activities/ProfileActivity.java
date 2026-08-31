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
    // Bumped locally + written to Firebase/Room on every successful avatar
    // upload (see uploadAvatar()) — passed to AvatarUrlBuilder so the
    // freshly-loaded avatar always gets a new Glide cache key, even before
    // any other cached photoUrl/thumbUrl string catches up. See
    // UserEntity#avatarVersion for the full rationale.
    private long currentAvatarVersion = 0;
    // Tracks the avatar URLs currently on Firebase/Room BEFORE a new upload
    // overwrites currentPhoto/uploadAvatar()'s state — needed at the end of
    // uploadAvatar() to tell CloudinaryUploader.invalidateAvatarEdgeCache()
    // which old CDN variants to purge server-side. See that method's doc.
    private String currentThumbUrl = "";
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
            setupVerificationEntryPoint();
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
            showAvatarZoom(v, currentPhoto);
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
                        currentAvatarVersion = cached.avatarVersion;
                        String cachedThumb = orEmpty(cached.thumbUrl);
                        currentThumbUrl = cachedThumb;
                        String cacheDisplayUrl = !cachedThumb.isEmpty() ? cachedThumb : currentPhoto;
                        if (!cacheDisplayUrl.isEmpty()) {
                            // PERF (deep avatar pipeline parity — see ProfileAvatarBinder):
                            // L2/L3 fast-path + blur-up thumbnail chain, instead of a bare
                            // analytics-only Glide load.
                            com.callx.app.cache.ProfileAvatarBinder.bind(ProfileActivity.this, binding.ivAvatar,
                                cacheDisplayUrl, currentAvatarVersion,
                                com.callx.app.cache.ProfileAvatarBinder.HERO_TIER, R.drawable.ic_person);
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
                    Long   avatarVer = s.child("avatarVersion").getValue(Long.class);

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
                    currentThumbUrl = thumb;
                    currentAvatarVersion = avatarVer != null ? avatarVer : 0;
                    String displayThumb = !thumb.isEmpty() ? thumb : photo;
                    if (!displayThumb.isEmpty()) {
                        // PERF (deep avatar pipeline parity — see ProfileAvatarBinder)
                        com.callx.app.cache.ProfileAvatarBinder.bind(ProfileActivity.this, binding.ivAvatar,
                            displayThumb, currentAvatarVersion,
                            com.callx.app.cache.ProfileAvatarBinder.HERO_TIER, R.drawable.ic_person);
                    }

                    // Room cache update — sirf apne profile ke liye
                    if (isOwnProfile) {
                        long avatarVerToCache = currentAvatarVersion;
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
                            u.avatarVersion = avatarVerToCache;
                            u.cachedAt = System.currentTimeMillis();
                            db.userDao().insertUser(u);
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private String orEmpty(String s) { return s == null ? "" : s; }

    /**
     * See the call site in onThumbReady above. Derives a tiny 32px Cloudinary
     * variant of the just-uploaded avatar thumbnail, decodes it, encodes a
     * BlurHash string, and patches it onto both Firebase copies of this
     * user's profile.
     */
    private static void generateAndAttachAvatarBlurHash(android.content.Context appCtx, String uid, String thumbUrl) {
        if (uid == null || uid.isEmpty() || thumbUrl == null || thumbUrl.isEmpty()) return;
        new Thread(() -> {
            try {
                String tinyUrl = CloudinaryUploader.deriveThumbUrl(thumbUrl, 32, "webp");
                android.graphics.Bitmap bmp = Glide.with(appCtx)
                        .asBitmap()
                        .load(tinyUrl)
                        .submit(32, 32)
                        .get();
                if (bmp == null) return;
                String hash = com.callx.app.utils.BlurHash.encode(bmp, 4, 3);
                if (hash == null || hash.isEmpty()) return;
                FirebaseUtils.getUserRef(uid).child("avatarBlurHash").setValue(hash);
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("reels/users").child(uid).child("avatarBlurHash").setValue(hash);
            } catch (Exception ignored) {
                // Non-critical — avatar already uploaded/saved successfully either way.
            }
        }).start();
    }

    private void uploadAvatar(Uri uri) {
        binding.avatarProgress.setVisibility(View.VISIBLE);
        // Captured BEFORE the new upload overwrites currentPhoto/
        // currentThumbUrl below — these are what's live at Cloudinary's CDN
        // edge right now, and what invalidateAvatarEdgeCache() needs once
        // the new avatar is confirmed. See that method's doc.
        final String oldPhotoUrl = currentPhoto;
        final String oldThumbUrl = currentThumbUrl;
        CloudinaryUploader.uploadAvatar(this, uri,
            new CloudinaryUploader.AvatarUploadCallback() {

                // Step 1 done: thumbnail ready → Firebase thumbUrl save + UI
                @Override public void onThumbReady(String thumbUrl) {
                    // Bump once per upload (not once per step) — thumb and
                    // full photo below are two steps of the SAME logical
                    // avatar change, so they share one new version. Written
                    // as a plain incremented literal (not ServerValue
                    // .increment) since currentAvatarVersion was already
                    // loaded from Firebase moments earlier in load() — this
                    // device already knows the authoritative current value.
                    currentAvatarVersion = currentAvatarVersion + 1;
                    currentThumbUrl = thumbUrl;
                    FirebaseUtils.getUserRef(currentUid)
                        .child("thumbUrl").setValue(thumbUrl);
                    FirebaseUtils.getUserRef(currentUid)
                        .child("avatarVersion").setValue(currentAvatarVersion);
                    // Room cache update
                    long newVersion = currentAvatarVersion;
                    Executors.newSingleThreadExecutor().execute(() -> {
                        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                        db.userDao().updateThumb(currentUid, thumbUrl);
                        db.userDao().updateAvatarVersion(currentUid, newVersion);
                    });
                    // Profile screen mein bhi thumb dikhao (snap fast)
                    // PERF (deep avatar pipeline parity — see ProfileAvatarBinder)
                    com.callx.app.cache.ProfileAvatarBinder.bind(ProfileActivity.this, binding.ivAvatar,
                        thumbUrl, currentAvatarVersion,
                        com.callx.app.cache.ProfileAvatarBinder.HERO_TIER, R.drawable.ic_person);

                    // LQIP: fire-and-forget BlurHash of the new avatar, same
                    // pattern as ReelUploadActivity#generateAndAttachBlurHash
                    // for reel thumbnails — lets ReelUiController show an
                    // instant blurred owner-avatar placeholder instead of a
                    // flat icon, no network needed to decode it. Written to
                    // BOTH users/{uid} (source of truth) and reels/users/{uid}
                    // (denormalized copy reels are posted with — see
                    // ReelUploadActivity/ReelModel#ownerAvatarBlurHash),
                    // mirroring how photoUrl/thumbUrl already live in both
                    // places. Never blocks/fails the avatar upload itself.
                    generateAndAttachAvatarBlurHash(getApplicationContext(), currentUid, thumbUrl);
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
                    // Profile screen par full photo reload karo — same
                    // currentAvatarVersion bumped in onThumbReady above,
                    // since this is step 2 of the same upload.
                    // PERF (deep avatar pipeline parity — see ProfileAvatarBinder)
                    com.callx.app.cache.ProfileAvatarBinder.bind(ProfileActivity.this, binding.ivAvatar,
                        photoUrl, currentAvatarVersion,
                        com.callx.app.cache.ProfileAvatarBinder.HERO_TIER, R.drawable.ic_person);
                    Toast.makeText(ProfileActivity.this,
                        "Profile photo update ho gayi", Toast.LENGTH_SHORT).show();

                    // FIX (CDN edge-side invalidation): both halves of this
                    // upload (thumb + full) are now confirmed live, so the
                    // OLD avatar's URLs are safe to purge from Cloudinary's
                    // edge cache — fire-and-forget, never blocks/fails the
                    // avatar change itself. See CloudinaryUploader
                    // .invalidateAvatarEdgeCache's doc.
                    java.util.List<String> oldUrls = new java.util.ArrayList<>();
                    if (oldPhotoUrl != null && !oldPhotoUrl.isEmpty()) oldUrls.add(oldPhotoUrl);
                    if (oldThumbUrl != null && !oldThumbUrl.isEmpty()) oldUrls.add(oldThumbUrl);
                    if (!oldUrls.isEmpty()) {
                        CloudinaryUploader.invalidateAvatarEdgeCache(
                            ProfileActivity.this, oldUrls.toArray(new String[0]));
                    }
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

    // FIX (Lifecycle-aware cancel — see ProfileAvatarBinder#cancel): stops
    // any in-flight avatar request and releases the L3 tag once this screen
    // is genuinely going away, instead of leaving it to keep decoding
    // toward a destroyed view.
    @Override
    protected void onDestroy() {
        super.onDestroy();
        com.callx.app.cache.ProfileAvatarBinder.cancel(this, binding.ivAvatar);
    }

    private void showAvatarZoom(View sourceView, String photoUrl) {
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            this, sourceView, photoUrl, R.drawable.ic_person, R.drawable.ic_close);
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

    /**
     * Verification badge request — own profile only. Reads current status
     * from Firebase (users/{uid}/isVerified, then verification_requests/{uid}/status)
     * and sets the button's label/enabled-state accordingly, then wires the
     * "Request Verification" tap to a simple reason dialog that writes a
     * pending request for an admin to review (see the :admin app module).
     */
    private void setupVerificationEntryPoint() {
        binding.btnRequestVerification.setVisibility(View.VISIBLE);
        binding.btnRequestVerification.setEnabled(false);
        binding.btnRequestVerification.setText("Checking status…");

        FirebaseUtils.getIsVerifiedRef(currentUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot verifiedSnap) {
                if (isFinishing() || isDestroyed()) return;
                boolean isVerified = Boolean.TRUE.equals(verifiedSnap.getValue(Boolean.class));
                if (isVerified) {
                    binding.btnRequestVerification.setText("Verified ✓");
                    binding.btnRequestVerification.setEnabled(false);
                    return;
                }
                FirebaseUtils.getVerificationRequestRef(currentUid).child("status")
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot statusSnap) {
                            if (isFinishing() || isDestroyed()) return;
                            String status = statusSnap.getValue(String.class);
                            if (FirebaseUtils.STATUS_PENDING.equals(status)) {
                                binding.btnRequestVerification.setText("Verification Pending");
                                binding.btnRequestVerification.setEnabled(false);
                            } else {
                                // null (never requested) or "rejected" — either way, allow (re-)requesting.
                                binding.btnRequestVerification.setText("Request Verification");
                                binding.btnRequestVerification.setEnabled(true);
                                binding.btnRequestVerification.setOnClickListener(v -> showRequestVerificationDialog());
                            }
                        }
                        @Override public void onCancelled(DatabaseError error) { }
                    });
            }
            @Override public void onCancelled(DatabaseError error) { }
        });
    }

    private void showRequestVerificationDialog() {
        android.widget.EditText etReason = new android.widget.EditText(this);
        etReason.setHint("Why should this account be verified?");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        etReason.setPadding(pad, pad, pad, pad);

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Request Verification")
            .setView(etReason)
            .setPositiveButton("Submit", (d, w) -> {
                String reason = etReason.getText().toString().trim();
                java.util.Map<String, Object> req = new HashMap<>();
                req.put("uid", currentUid);
                req.put("name", binding.etName.getText().toString().trim());
                req.put("photoUrl", currentPhoto);
                req.put("reason", reason);
                req.put("status", FirebaseUtils.STATUS_PENDING);
                req.put("submittedAt", ServerValue.TIMESTAMP);
                FirebaseUtils.getVerificationRequestRef(currentUid).setValue(req)
                    .addOnSuccessListener(unused -> {
                        Toast.makeText(this, "Request submitted", Toast.LENGTH_SHORT).show();
                        binding.btnRequestVerification.setText("Verification Pending");
                        binding.btnRequestVerification.setEnabled(false);
                    })
                    .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            })
            .setNegativeButton("Cancel", null)
            .show();
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
