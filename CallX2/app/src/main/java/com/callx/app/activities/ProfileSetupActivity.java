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

    /** Step 4: forced one-time username-migration mode — launched from
     *  MainActivity for accounts whose username is still just the phone
     *  number. No skip, no back button; only asks for a username. */
    public static final String EXTRA_FORCE_USERNAME_MIGRATION = "forceUsernameMigration";

    private ActivityProfileSetupBinding binding;
    private Uri pickedAvatarUri = null;
    private ActivityResultLauncher<String> avatarPicker;
    private ActivityResultLauncher<Intent> cropLauncher;
    private boolean forceMigration = false;

    // Avatar source choice. Default = UNCHANGED (default avatar; nothing is auto-set from Google).
    // Google-login users can still opt in to their Google photo via the picker dialog.
    private static final int CHOICE_UNCHANGED = 0, CHOICE_GALLERY = 1, CHOICE_NONE = 2;
    private int     avatarChoice = CHOICE_UNCHANGED;
    private String  googlePhotoUrl = null;
    private boolean googlePhotoLoading = false;

    private final android.os.Handler usernameCheckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingUsernameCheck;
    private String lastCheckedUsername = null;
    private boolean usernameAvailable = false;
    private long usernameCheckToken = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        boolean isNewUser = getIntent().getBooleanExtra("isNewUser", false);
        forceMigration = getIntent().getBooleanExtra(EXTRA_FORCE_USERNAME_MIGRATION, false);

        // Pre-fill name from Google account if available
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && user.getDisplayName() != null && !user.getDisplayName().isEmpty()) {
            binding.etName.setText(user.getDisplayName());
        }
        // Remembered only for the optional "Google photo" choice — NOT shown/saved by default.
        if (user != null && user.getPhotoUrl() != null) {
            googlePhotoUrl = user.getPhotoUrl().toString();
        }
        if (isNewUser && !forceMigration) {
            binding.tvSetupSubtitle.setText("Photo add karo (optional) — dusre log aapko is se dhundh sakte hain");
        }

        // Pick → square crop → preview + upload on Save (only the framed square is uploaded)
        cropLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    String u = result.getData().getStringExtra("media_crop_result_uri");
                    if (u != null) {
                        pickedAvatarUri = Uri.parse(u);
                        avatarChoice = CHOICE_GALLERY;   // cropped square (from gallery OR Google photo)
                        Glide.with(this).load(pickedAvatarUri).circleCrop()
                            .override(240, 240).into(binding.ivAvatarPreview);
                    }
                }
            });
        avatarPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) launchAvatarCrop(uri);
            });

        binding.flAvatarPicker.setOnClickListener(v -> {
            if (googlePhotoUrl == null) avatarPicker.launch("image/*");   // email signup: gallery directly
            else showAvatarChoiceDialog();                                // Google login: let user choose
        });

        binding.etUsername.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String raw = s.toString();
                String normalized = raw.toLowerCase(java.util.Locale.getDefault())
                        .replaceAll("[^a-z0-9_]", "");
                if (!normalized.equals(raw)) {
                    s.replace(0, s.length(), normalized);
                    return; // afterTextChanged re-fires from this replace
                }
                scheduleUsernameCheck(normalized);
            }
        });

        binding.btnSave.setOnClickListener(v -> saveProfile());

        if (forceMigration) {
            // Mandatory, one-time — no skip, no back navigation, and we
            // don't ask them to redo the rest of the profile: avatar/mobile
            // stay hidden, name/about get silently prefilled from their
            // existing data (see prefillForMigration()) so saveProfile()'s
            // required-name check passes and about doesn't get clobbered
            // back to the default greeting.
            binding.tvSkip.setVisibility(View.GONE);
            binding.toolbar.setNavigationIcon(null);
            binding.flAvatarPicker.setVisibility(View.GONE);
            binding.tilMobile.setVisibility(View.GONE);
            binding.tvSetupTitle.setText("Ek username choose karo");
            binding.tvSetupSubtitle.setText("Aapka mobile number ab tak username tha — ab ek naya @handle chuno, yeh sabko dikhega");
            getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() { /* blocked — must choose a username */ }
            });
            prefillForMigration();
        } else if (isNewUser) {
            binding.tvSkip.setVisibility(View.VISIBLE);
            binding.tvSkip.setOnClickListener(v -> goToMain());
        } else {
            binding.tvSkip.setVisibility(View.GONE);
            binding.toolbar.setNavigationOnClickListener(v2 -> finish());
        }
    }

    /** Step 4: quietly pulls this account's existing name/about so the
     *  migration screen's saveProfile() doesn't require retyping the name
     *  or accidentally reset "about" back to the default greeting — the
     *  person only ever has to deal with the username field. */
    private void prefillForMigration() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(user.getUid())
            .get()
            .addOnSuccessListener(snap -> {
                String name = snap.child("name").getValue(String.class);
                String about = snap.child("about").getValue(String.class);
                if (name != null && !name.isEmpty()
                        && (binding.etName.getText() == null || binding.etName.getText().toString().trim().isEmpty())) {
                    binding.etName.setText(name);
                }
                if (about != null && !about.isEmpty()) {
                    binding.etAbout.setText(about);
                }
            });
    }

    private void scheduleUsernameCheck(String username) {
        usernameAvailable = false;
        if (pendingUsernameCheck != null) usernameCheckHandler.removeCallbacks(pendingUsernameCheck);

        if (username.length() < 3) {
            binding.tvUsernameStatus.setTextColor(getResources().getColor(com.callx.app.R.color.text_secondary));
            binding.tvUsernameStatus.setText("Kam se kam 3 characters (a-z, 0-9, _)");
            return;
        }

        binding.tvUsernameStatus.setTextColor(getResources().getColor(com.callx.app.R.color.text_secondary));
        binding.tvUsernameStatus.setText("Check kar rahe hain...");

        final long myToken = ++usernameCheckToken;
        pendingUsernameCheck = () -> checkUsernameAvailability(username, myToken);
        usernameCheckHandler.postDelayed(pendingUsernameCheck, 400);
    }

    /** Single-key lookup on usernames/{username} — O(1), unlike the old
     *  orderByChild("username").equalTo() scan used elsewhere in the app. */
    private void checkUsernameAvailability(String username, long token) {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("usernames").child(username)
            .get()
            .addOnSuccessListener(snap -> {
                if (token != usernameCheckToken) return; // stale — user kept typing
                lastCheckedUsername = username;
                FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
                boolean takenByOther = snap.exists()
                        && (me == null || !snap.getValue(String.class).equals(me.getUid()));
                usernameAvailable = !takenByOther;
                if (takenByOther) {
                    binding.tvUsernameStatus.setTextColor(getResources().getColor(com.callx.app.R.color.action_danger));
                    binding.tvUsernameStatus.setText("Yeh username already liya hua hai");
                } else {
                    binding.tvUsernameStatus.setTextColor(getResources().getColor(com.callx.app.R.color.status_online));
                    binding.tvUsernameStatus.setText("Available ✓");
                }
            })
            .addOnFailureListener(e -> {
                if (token != usernameCheckToken) return;
                binding.tvUsernameStatus.setTextColor(getResources().getColor(com.callx.app.R.color.action_danger));
                binding.tvUsernameStatus.setText("Check nahi ho paya, phir try karo");
            });
    }

    private void saveProfile() {
        String name = binding.etName.getText().toString().trim();
        String username = binding.etUsername.getText().toString().trim();
        String mobile = binding.etMobile.getText().toString().replaceAll("[^0-9]", "");
        String about = binding.etAbout.getText().toString().trim();

        if (name.isEmpty()) { showError("Naam zaroori hai"); return; }
        if (username.length() < 3) { showError("Username kam se kam 3 characters ka hona chahiye"); return; }
        if (!username.equals(lastCheckedUsername) || !usernameAvailable) {
            showError("Pehle username available hai ya nahi check hone do");
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        showLoading("Saving...");

        saveWithAvatar(user, name, username, mobile, about);
    }

    private void saveWithAvatar(FirebaseUser user, String name, String username,
                                String mobile, String about) {
        if (pickedAvatarUri != null) {
            CloudinaryUploader.uploadAvatar(this, pickedAvatarUri,
                new CloudinaryUploader.AvatarUploadCallback() {
                    @Override public void onThumbReady(String thumbUrl) {}
                    @Override public void onFullReady(String photoUrl) {
                        saveToFirebase(user, name, username, mobile, about, photoUrl);
                    }
                    @Override public void onError(String err) {
                        saveToFirebase(user, name, username, mobile, about, null);
                    }
                });
        } else if (!forceMigration && avatarChoice == CHOICE_NONE) {
            saveToFirebase(user, name, username, mobile, about, "");   // "" = clear photoUrl (default avatar)
        } else {
            // Untouched: leave users/{uid}/photoUrl as it is (no Google photo auto-copy)
            saveToFirebase(user, name, username, mobile, about, null);
        }
    }

    private void saveToFirebase(FirebaseUser user, String name, String username, String mobile,
                                String about, String photoUrl) {
        // Re-check + reserve the username atomically via a transaction on
        // usernames/{username} — closes the race where two people who both
        // saw "Available ✓" tap Save at the same moment.
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("usernames").child(username)
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @Override
                public com.google.firebase.database.Transaction.Result doTransaction(
                        com.google.firebase.database.MutableData data) {
                    Object current = data.getValue();
                    if (current != null && !current.equals(user.getUid())) {
                        return com.google.firebase.database.Transaction.abort();
                    }
                    data.setValue(user.getUid());
                    return com.google.firebase.database.Transaction.success(data);
                }
                @Override
                public void onComplete(com.google.firebase.database.DatabaseError error,
                        boolean committed, com.google.firebase.database.DataSnapshot snap) {
                    if (!committed) {
                        showError("Yeh username abhi kisi aur ne le liya, dusra try karo");
                        return;
                    }
                    finishSavingProfile(user, name, username, mobile, about, photoUrl);
                }
            });
    }

    private void finishSavingProfile(FirebaseUser user, String name, String username, String mobile,
                                String about, String photoUrl) {
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("name", name);
        updates.put("nameLower", name.toLowerCase(java.util.Locale.getDefault()));
        // Real Instagram-style handle — chosen by the user, reserved above
        // in usernames/{username}, independent of phone number.
        updates.put("username", username);
        if (!mobile.isEmpty()) {
            updates.put("mobile", mobile);
            updates.put("callxId", mobile);
        }
        updates.put("about", about.isEmpty() ? "Hey, I'm on CallX!" : about);
        if (photoUrl != null) updates.put("photoUrl", photoUrl.isEmpty() ? null : photoUrl);   // null value removes the key

        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(user.getUid())
            .updateChildren(updates)
            .addOnSuccessListener(x -> {
                com.callx.app.utils.AuthPhotoSync.sync();   // Auth photo = app avatar (clears Google photo if none chosen)
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

    private void showAvatarChoiceDialog() {
        String[] items = { "Google photo use karo", "Gallery se choose karo", "Default avatar" };
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Profile photo")
            .setItems(items, (d, which) -> {
                if (which == 0) pickGooglePhoto();
                else if (which == 1) avatarPicker.launch("image/*");
                else useDefaultAvatar();
            })
            .show();
    }

    private void useDefaultAvatar() {
        pickedAvatarUri = null;
        avatarChoice = CHOICE_NONE;
        binding.ivAvatarPreview.setImageResource(com.callx.app.R.drawable.ic_person);
    }

    /** Downloads the (upscaled) Google photo, then opens the same square crop screen. */
    private void pickGooglePhoto() {
        if (googlePhotoLoading || googlePhotoUrl == null) return;
        googlePhotoLoading = true;
        Toast.makeText(this, "Google photo load ho rahi hai…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.io.File f = downloadGooglePhoto();
            runOnUiThread(() -> {
                googlePhotoLoading = false;
                if (isFinishing() || isDestroyed()) return;
                if (f == null) {
                    Toast.makeText(this, "Google photo load nahi ho payi, gallery try karo", Toast.LENGTH_LONG).show();
                    return;
                }
                launchAvatarCrop(Uri.fromFile(f));
            });
        }).start();
    }

    /** Google gives a tiny (~96px) URL by default; ask for 1024px instead. */
    private static String upscaleGooglePhotoUrl(String u) {
        String r = u.replaceAll("=s\\d+(-c)?(?=$|[?&#])", "=s1024-c");
        r = r.replaceAll("/s\\d+(-c)?/", "/s1024-c/");
        return r;
    }

    /** Blocking — call off the main thread. Returns null on any failure. */
    private java.io.File downloadGooglePhoto() {
        if (googlePhotoUrl == null) return null;
        String big = upscaleGooglePhotoUrl(googlePhotoUrl);
        String[] candidates = big.equals(googlePhotoUrl)
                ? new String[] { googlePhotoUrl } : new String[] { big, googlePhotoUrl };
        for (String url : candidates) {
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) continue;
                java.io.File dir = new java.io.File(getCacheDir(), "media_crop");
                if (!dir.exists()) dir.mkdirs();
                java.io.File out = new java.io.File(dir, "google_" + System.currentTimeMillis() + ".jpg");
                try (java.io.InputStream in = c.getInputStream();
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                if (out.length() > 0) return out;
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    private void launchAvatarCrop(Uri src) {
        Intent ci = new Intent();
        ci.setClassName(getPackageName(), "com.callx.app.media.crop.MediaCropActivity");
        ci.putExtra("media_crop_uri", src.toString());
        ci.putExtra("media_crop_square_locked", true);
        ci.putExtra("media_crop_max_output_px", 1080);
        cropLauncher.launch(ci);
    }

    private void showLoading(String msg) {
        binding.tvError.setVisibility(View.VISIBLE);
        binding.tvError.setTextColor(getResources().getColor(com.callx.app.R.color.text_secondary));
        binding.tvError.setText(msg);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingUsernameCheck != null) usernameCheckHandler.removeCallbacks(pendingUsernameCheck);
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
