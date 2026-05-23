package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.models.XUser;
import com.callx.app.utils.XCloudinaryUtils;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

public class XEditProfileActivity extends AppCompatActivity {

    private String myUid;
    private XUser xUser;
    private CircleImageView ivAvatar;
    private ImageView ivBanner;
    private EditText etName, etBio, etWebsite, etLocation;
    private ProgressBar pbSave;
    private boolean pickingAvatar;

    private final ActivityResultLauncher<Intent> imagePicker =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) uploadImage(uri, pickingAvatar);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_edit_profile);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        ivAvatar   = findViewById(R.id.iv_edit_avatar);
        ivBanner   = findViewById(R.id.iv_edit_banner);
        etName     = findViewById(R.id.et_edit_name);
        etBio      = findViewById(R.id.et_edit_bio);
        etWebsite  = findViewById(R.id.et_edit_website);
        etLocation = findViewById(R.id.et_edit_location);
        pbSave     = findViewById(R.id.pb_edit_save);

        findViewById(R.id.btn_edit_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_edit_save).setOnClickListener(v -> saveProfile());
        ivAvatar.setOnClickListener(v -> { pickingAvatar = true; pickImage(); });
        ivBanner.setOnClickListener(v -> { pickingAvatar = false; pickImage(); });

        loadProfile();
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        i.setType("image/*");
        imagePicker.launch(i);
    }

    private void loadProfile() {
        XFirebaseUtils.xUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                xUser = snap.getValue(XUser.class);
                if (xUser == null) return;
                etName.setText(xUser.name);
                etBio.setText(xUser.bio != null ? xUser.bio : "");
                etWebsite.setText(xUser.website != null ? xUser.website : "");
                etLocation.setText(xUser.location != null ? xUser.location : "");
                if (xUser.photoUrl != null && !xUser.photoUrl.isEmpty()) {
                    String displayUrl = (xUser.thumbUrl != null && !xUser.thumbUrl.isEmpty())
                        ? xUser.thumbUrl : xUser.photoUrl;
                    Glide.with(XEditProfileActivity.this).load(displayUrl)
                        .circleCrop().into(ivAvatar);
                }
                if (xUser.bannerUrl != null && !xUser.bannerUrl.isEmpty())
                    Glide.with(XEditProfileActivity.this).load(xUser.bannerUrl)
                        .centerCrop().into(ivBanner);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void uploadImage(Uri uri, boolean isAvatar) {
        pbSave.setVisibility(android.view.View.VISIBLE);
        XCloudinaryUtils.XUploadListener cb = new XCloudinaryUtils.XUploadListener() {
            @Override public void onSuccess(String publicId, String url) {
                runOnUiThread(() -> {
                    pbSave.setVisibility(android.view.View.GONE);
                    if (isAvatar) {
                        Glide.with(XEditProfileActivity.this).load(url).circleCrop().into(ivAvatar);
                        XFirebaseUtils.xUserRef(myUid).child("photoUrl").setValue(url);
                    } else {
                        Glide.with(XEditProfileActivity.this).load(url).centerCrop().into(ivBanner);
                        XFirebaseUtils.xUserRef(myUid).child("bannerUrl").setValue(url);
                    }
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    pbSave.setVisibility(android.view.View.GONE);
                    Toast.makeText(XEditProfileActivity.this, "Upload failed: " + msg,
                        Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onProgress(int pct) {}
        };
        XCloudinaryUtils.uploadTweetImage(this, uri, cb);
    }

    private void saveProfile() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            etName.setError("Name required"); return;
        }
        pbSave.setVisibility(android.view.View.VISIBLE);
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("name", name);
        updates.put("bio", etBio.getText().toString().trim());
        updates.put("website", etWebsite.getText().toString().trim());
        updates.put("location", etLocation.getText().toString().trim());
        XFirebaseUtils.xUserRef(myUid).updateChildren(updates).addOnCompleteListener(t -> {
            pbSave.setVisibility(android.view.View.GONE);
            if (t.isSuccessful()) {
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
