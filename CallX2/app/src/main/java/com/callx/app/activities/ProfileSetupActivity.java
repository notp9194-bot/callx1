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
    private boolean forceMigration = false;

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
        } else {
            String existingPhoto = user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;
            saveToFirebase(user, name, username, mobile, about, existingPhoto);
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
