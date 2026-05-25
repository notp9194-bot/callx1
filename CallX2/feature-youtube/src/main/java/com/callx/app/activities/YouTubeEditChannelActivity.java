package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.utils.YouTubeCloudinaryUtils;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import de.hdodenhof.circleimageview.CircleImageView;

public class YouTubeEditChannelActivity extends AppCompatActivity {

    private static final int REQ_AVATAR = 3001, REQ_BANNER = 3002;

    private EditText etName, etHandle, etBio;
    private CircleImageView ivAvatar;
    private ImageView ivBanner;
    private Button btnSave;
    private Uri avatarUri, bannerUri;
    private String myUid;
    private String existingAvatar, existingBanner;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_edit_channel);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_edit_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        etName   = findViewById(R.id.et_yt_channel_name);
        etHandle = findViewById(R.id.et_yt_channel_handle);
        etBio    = findViewById(R.id.et_yt_channel_bio);
        ivAvatar = findViewById(R.id.iv_yt_edit_avatar);
        ivBanner = findViewById(R.id.iv_yt_edit_banner);
        btnSave  = findViewById(R.id.btn_yt_save_channel);

        ivAvatar.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
            startActivityForResult(i, REQ_AVATAR);
        });
        ivBanner.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
            startActivityForResult(i, REQ_BANNER);
        });

        // Load current data
        YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
            new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    etName.setText(snap.child("channelName").getValue(String.class));
                    etHandle.setText(snap.child("handle").getValue(String.class));
                    etBio.setText(snap.child("bio").getValue(String.class));
                    existingAvatar = snap.child("photoUrl").getValue(String.class);
                    existingBanner = snap.child("bannerUrl").getValue(String.class);
                    Glide.with(YouTubeEditChannelActivity.this).load(existingAvatar)
                        .circleCrop().into(ivAvatar);
                    if (existingBanner != null)
                        Glide.with(YouTubeEditChannelActivity.this).load(existingBanner)
                            .centerCrop().into(ivBanner);
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });

        btnSave.setOnClickListener(v -> save());
    }

    @Override protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_AVATAR) {
            avatarUri = data.getData();
            Glide.with(this).load(avatarUri).circleCrop().into(ivAvatar);
        } else if (req == REQ_BANNER) {
            bannerUri = data.getData();
            Glide.with(this).load(bannerUri).centerCrop().into(ivBanner);
        }
    }

    private void save() {
        String name   = etName.getText().toString().trim();
        String handle = etHandle.getText().toString().trim().replaceAll("@", "");
        String bio    = etBio.getText().toString().trim();
        if (name.isEmpty()) { etName.setError("Name required"); return; }

        btnSave.setEnabled(false);

        if (avatarUri != null) {
            YouTubeCloudinaryUtils.uploadImage(this, avatarUri, myUid + "/avatar",
                new YouTubeCloudinaryUtils.UploadCallback() {
                    @Override public void onProgress(int p) {}
                    @Override public void onSuccess(String url, String pid, long durationSecs) {
                        existingAvatar = url;
                        if (bannerUri != null) uploadBannerThenSave(name, handle, bio);
                        else persistToFirebase(name, handle, bio);
                    }
                    @Override public void onError(String e) { persistToFirebase(name, handle, bio); }
                });
        } else if (bannerUri != null) {
            uploadBannerThenSave(name, handle, bio);
        } else {
            persistToFirebase(name, handle, bio);
        }
    }

    private void uploadBannerThenSave(String name, String handle, String bio) {
        YouTubeCloudinaryUtils.uploadImage(this, bannerUri, myUid + "/banner",
            new YouTubeCloudinaryUtils.UploadCallback() {
                @Override public void onProgress(int p) {}
                @Override public void onSuccess(String url, String pid, long durationSecs) {
                    existingBanner = url;
                    persistToFirebase(name, handle, bio);
                }
                @Override public void onError(String e) { persistToFirebase(name, handle, bio); }
            });
    }

    private void persistToFirebase(String name, String handle, String bio) {
        java.util.HashMap<String, Object> updates = new java.util.HashMap<>();
        updates.put("channelName", name);
        updates.put("handle", handle);
        updates.put("bio", bio);
        if (existingAvatar != null) updates.put("photoUrl", existingAvatar);
        if (existingBanner != null) updates.put("bannerUrl", existingBanner);

        YouTubeFirebaseUtils.channelRef(myUid).updateChildren(updates)
            .addOnSuccessListener(v2 -> {
                Toast.makeText(this, "Channel updated", Toast.LENGTH_SHORT).show();
                finish();
            })
            .addOnFailureListener(e -> {
                btnSave.setEnabled(true);
                Toast.makeText(this, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
    }
}
