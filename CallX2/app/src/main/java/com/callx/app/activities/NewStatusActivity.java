package com.callx.app.activities;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.databinding.ActivityNewStatusBinding;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;
public class NewStatusActivity extends AppCompatActivity {
    private ActivityNewStatusBinding binding;
    private Uri pickedImage, pickedVideo;
    private ActivityResultLauncher<String> imagePicker, videoPicker;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNewStatusBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri == null) return;
                pickedImage = uri; pickedVideo = null;
                binding.ivPreview.setVisibility(View.VISIBLE);
                Glide.with(this).load(uri).into(binding.ivPreview);
            });
        videoPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri == null) return;
                pickedVideo = uri; pickedImage = null;
                binding.ivPreview.setVisibility(View.VISIBLE);
                binding.ivPreview.setImageResource(
                    com.callx.app.R.drawable.ic_video);
            });
        binding.btnPickImage.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnPickVideo.setOnClickListener(v -> videoPicker.launch("video/*"));
        binding.btnPost.setOnClickListener(v -> post());
    }
    private void post() {
        String txt = binding.etText.getText().toString().trim();
        if (pickedImage == null && pickedVideo == null && txt.isEmpty()) {
            Toast.makeText(this, "Kuch text ya media pick karo",
                Toast.LENGTH_SHORT).show();
            return;
        }
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String name = FirebaseUtils.getCurrentName();
        if (pickedImage != null) {
            uploadAndSave(pickedImage, "image", "image", txt, uid, name);
        } else if (pickedVideo != null) {
            uploadAndSave(pickedVideo, "video", "video", txt, uid, name);
        } else {
            saveStatus("text", null, txt, uid, name);
        }
    }
    private void uploadAndSave(Uri uri, String type, String rt, String txt,
                               String uid, String name) {
        binding.uploadProgress.setVisibility(View.VISIBLE);
        CloudinaryUploader.upload(this, uri, "callx/status", rt,
            new CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    binding.uploadProgress.setVisibility(View.GONE);
                    saveStatus(type, r.secureUrl, txt, uid, name);
                }
                @Override public void onError(String err) {
                    binding.uploadProgress.setVisibility(View.GONE);
                    Toast.makeText(NewStatusActivity.this,
                        err == null ? "Upload fail" : err,
                        Toast.LENGTH_LONG).show();
                }
            });
    }
    private void saveStatus(String type, String mediaUrl, String txt,
                            String uid, String name) {
        long now = System.currentTimeMillis();
        DatabaseReference ref = FirebaseUtils.getStatusRef().child(uid).push();
        Map<String, Object> s = new HashMap<>();
        s.put("id", ref.getKey());
        s.put("ownerUid", uid);
        s.put("ownerName", name);
        s.put("type", type);
        s.put("text", txt);
        s.put("mediaUrl", mediaUrl);
        s.put("timestamp", now);
        s.put("expiresAt", now + Constants.STATUS_TTL_MS);
        ref.setValue(s).addOnSuccessListener(x -> {
            PushNotify.notifyStatus(uid, name);
            Toast.makeText(this, "Status post ho gaya", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
