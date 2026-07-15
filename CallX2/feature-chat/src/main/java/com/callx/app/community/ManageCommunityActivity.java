package com.callx.app.community;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.repository.CommunityRepository;
import com.callx.app.utils.CloudinaryUploader;
import com.google.firebase.auth.FirebaseAuth;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ManageCommunityActivity — edit name/description/icon; owner-only
 * "Disable Community" (destructive — confirmed via dialog, then finishes
 * with RESULT_FIRST_USER so CommunityActivity knows to close itself too).
 */
public class ManageCommunityActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";
    public static final String EXTRA_IS_OWNER = "isOwner";

    private String communityId;
    private boolean isOwner;
    private String currentUid;
    private Uri pickedIconUri;
    private String pendingIconUrl;

    private CircleImageView ivIcon;
    private EditText etName, etDescription;
    private android.widget.Button btnSave, btnDisable;

    private CommunityRepository repo;
    private ActivityResultLauncher<String> iconPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_community);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        isOwner = getIntent().getBooleanExtra(EXTRA_IS_OWNER, false);
        currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("Manage Community");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        ivIcon         = findViewById(R.id.iv_community_icon);
        etName         = findViewById(R.id.et_community_name);
        etDescription  = findViewById(R.id.et_community_description);
        btnSave        = findViewById(R.id.btn_save);
        btnDisable     = findViewById(R.id.btn_disable_community);
        View btnChangeIcon = findViewById(R.id.btn_change_icon);

        btnDisable.setVisibility(isOwner ? View.VISIBLE : View.GONE);

        iconPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            pickedIconUri = uri;
            Glide.with(this).load(uri).circleCrop().into(ivIcon);
        });
        btnChangeIcon.setOnClickListener(v -> iconPicker.launch("image/*"));

        if (communityId != null) {
            repo.observeCommunity(communityId).observe(this, this::populate);
        }

        btnSave.setOnClickListener(v -> save());
        btnDisable.setOnClickListener(v -> confirmDisable());
    }

    private void populate(CommunityEntity c) {
        if (c == null) return;
        if (pickedIconUri == null && c.iconUrl != null && !c.iconUrl.isEmpty()) {
            Glide.with(this).load(c.iconUrl).circleCrop()
                    .placeholder(R.drawable.ic_group).into(ivIcon);
        }
        if (etName.getText().length() == 0) etName.setText(c.name);
        if (etDescription.getText().length() == 0) etDescription.setText(c.description);
    }

    private void save() {
        String name = etName.getText().toString().trim();
        String description = etDescription.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Name can't be empty", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSave.setEnabled(false);

        if (pickedIconUri != null) {
            CloudinaryUploader.upload(this, pickedIconUri, "callx/community_icons", "image",
                    new CloudinaryUploader.UploadCallback() {
                        @Override public void onSuccess(CloudinaryUploader.Result result) {
                            pendingIconUrl = result.secureUrl;
                            runOnUiThread(() -> doSave(name, description, pendingIconUrl));
                        }
                        @Override public void onError(String message) {
                            runOnUiThread(() -> {
                                Toast.makeText(ManageCommunityActivity.this,
                                        "Icon upload failed: " + message, Toast.LENGTH_SHORT).show();
                                doSave(name, description, null);
                            });
                        }
                    });
        } else {
            doSave(name, description, null);
        }
    }

    private void doSave(String name, String description, String iconUrl) {
        repo.updateCommunityInfo(communityId, name, description, iconUrl, (success, error) -> {
            runOnUiThread(() -> {
                btnSave.setEnabled(true);
                if (success) {
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void confirmDisable() {
        new AlertDialog.Builder(this)
                .setTitle("Disable Community?")
                .setMessage("This permanently deletes the community (feed, announcements, members, " +
                        "and group links). Linked group chats themselves are NOT deleted. This can't be undone.")
                .setPositiveButton("Disable", (d, w) -> disable())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void disable() {
        if (currentUid == null) return;
        btnDisable.setEnabled(false);
        repo.disableCommunity(communityId, currentUid, (success, error) -> {
            runOnUiThread(() -> {
                if (success) {
                    setResult(RESULT_FIRST_USER);
                    finish();
                } else {
                    btnDisable.setEnabled(true);
                    Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}
