package com.callx.app.activities;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
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
    private String currentPhoto = "";
    private ActivityResultLauncher<String> imagePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        currentUid = FirebaseUtils.getCurrentUid();
        imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) uploadAvatar(uri); });
        binding.btnChangeAvatar.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnSave.setOnClickListener(v -> save());
        load();
    }

    private void load() {
        // v18 IMPROVEMENT 3: Room se pehle load karo — offline fallback
        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            UserEntity cached = db.userDao().getUser(currentUid);
            if (cached != null) {
                runOnUiThread(() -> {
                    binding.etName.setText(orEmpty(cached.name));
                    binding.etAbout.setText(orEmpty(cached.about));
                    binding.tvCallxId.setText(orEmpty(cached.callxId));
                    binding.tvEmail.setText(orEmpty(cached.email));
                    currentPhoto = orEmpty(cached.photoUrl);
                    if (!currentPhoto.isEmpty()) {
                        Glide.with(ProfileActivity.this).load(currentPhoto)
                            .into(binding.ivAvatar);
                    }
                });
            }
        });

        // Firebase se fresh data load karo (online hone par overwrite karega)
        FirebaseUtils.getUserRef(currentUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    String name  = orEmpty(s.child("name").getValue(String.class));
                    String about = orEmpty(s.child("about").getValue(String.class));
                    String callxId = orEmpty(s.child("callxId").getValue(String.class));
                    String email = orEmpty(s.child("email").getValue(String.class));
                    String photo = orEmpty(s.child("photoUrl").getValue(String.class));

                    binding.etName.setText(name);
                    binding.etAbout.setText(about);
                    binding.tvCallxId.setText(callxId);
                    binding.tvEmail.setText(email);
                    currentPhoto = photo;
                    if (!photo.isEmpty()) {
                        Glide.with(ProfileActivity.this).load(photo)
                            .into(binding.ivAvatar);
                    }

                    // v18 IMPROVEMENT 3: Room mein bhi update karo for next offline visit
                    Executors.newSingleThreadExecutor().execute(() -> {
                        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                        UserEntity u = db.userDao().getUser(currentUid);
                        if (u == null) u = new UserEntity();
                        u.uid     = currentUid;
                        u.name    = name;
                        u.about   = about;
                        u.callxId = callxId;
                        u.email   = email;
                        u.photoUrl = photo;
                        u.cachedAt = System.currentTimeMillis();
                        db.userDao().insertUser(u);
                    });
                }
                @Override public void onCancelled(DatabaseError e) {
                    // Offline hai — Room fallback already loaded, kuch nahi karna
                }
            });
    }
    private String orEmpty(String s) { return s == null ? "" : s; }
    private void uploadAvatar(Uri uri) {
        binding.avatarProgress.setVisibility(View.VISIBLE);
        CloudinaryUploader.upload(this, uri, "callx/avatars", "image",
            new CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    binding.avatarProgress.setVisibility(View.GONE);
                    currentPhoto = r.secureUrl;
                    FirebaseUtils.getUserRef(currentUid)
                        .child("photoUrl").setValue(r.secureUrl);
                    Glide.with(ProfileActivity.this).load(r.secureUrl)
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
        String name  = binding.etName.getText().toString().trim();
        String about = binding.etAbout.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Naam khali nahi ho sakta",
                Toast.LENGTH_SHORT).show(); return;
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put("name", name);
        updates.put("about", about);
        FirebaseUtils.getUserRef(currentUid).updateChildren(updates);
        FirebaseAuth.getInstance().getCurrentUser()
            .updateProfile(new UserProfileChangeRequest.Builder()
                .setDisplayName(name).build());
        Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show();
        finish();
    }
}
