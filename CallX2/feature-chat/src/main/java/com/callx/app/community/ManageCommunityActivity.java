package com.callx.app.community;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
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
 * v31: Manage community — edit name/description/icon + privacy toggle + invite link.
 * Owner-only: Disable Community, Analytics link, Invite Link generator.
 */
public class ManageCommunityActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";
    public static final String EXTRA_IS_OWNER     = "isOwner";

    private String communityId;
    private boolean isOwner;
    private String currentUid;
    private Uri pickedIconUri;
    private String currentInviteToken;

    private CircleImageView ivIcon;
    private EditText etName, etDescription;
    private Switch switchPrivate;
    private android.widget.Button btnSave, btnDisable, btnGenerateInvite;
    private View layoutInvite;
    private TextView tvInviteLink;

    private CommunityRepository repo;
    private ActivityResultLauncher<String> iconPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_community);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        isOwner     = getIntent().getBooleanExtra(EXTRA_IS_OWNER, false);
        currentUid  = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("Manage Community");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        ivIcon        = findViewById(R.id.iv_community_icon);
        etName        = findViewById(R.id.et_community_name);
        etDescription = findViewById(R.id.et_community_description);
        switchPrivate = findViewById(R.id.switch_private);
        btnSave       = findViewById(R.id.btn_save);
        btnDisable    = findViewById(R.id.btn_disable_community);
        btnGenerateInvite = findViewById(R.id.btn_generate_invite);
        layoutInvite  = findViewById(R.id.layout_invite_link);
        tvInviteLink  = findViewById(R.id.tv_invite_link_value);

        btnDisable.setVisibility(isOwner ? View.VISIBLE : View.GONE);
        if (btnGenerateInvite != null) btnGenerateInvite.setVisibility(isOwner ? View.VISIBLE : View.GONE);

        iconPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            pickedIconUri = uri;
            .override(96, 96)
            Glide.with(this).load(uri).circleCrop().override(96, 96).into(ivIcon);
        });
        View btnChangeIcon = findViewById(R.id.btn_change_icon);
        if (btnChangeIcon != null) btnChangeIcon.setOnClickListener(v -> iconPicker.launch("image/*"));

        if (communityId != null) repo.observeCommunity(communityId).observe(this, this::populate);

        btnSave.setOnClickListener(v -> save());
        btnDisable.setOnClickListener(v -> confirmDisable());

        if (switchPrivate != null) {
            switchPrivate.setOnCheckedChangeListener((btn, checked) -> {
                repo.setCommunityPrivacy(communityId, checked, (success, error) -> {
                    if (!success) runOnUiThread(() ->
                            Toast.makeText(this, "Failed to update privacy", Toast.LENGTH_SHORT).show());
                });
            });
        }

        if (btnGenerateInvite != null) {
            btnGenerateInvite.setOnClickListener(v -> generateInviteLink());
        }
    }

    private void populate(CommunityEntity c) {
        if (c == null) return;
        currentInviteToken = c.inviteToken;
        if (pickedIconUri == null && c.iconUrl != null && !c.iconUrl.isEmpty()) {
            Glide.with(this).load(c.iconUrl).circleCrop()
                    .override(96, 96)
                    .placeholder(R.drawable.ic_group).into(ivIcon);
        }
        if (etName.getText().length() == 0) etName.setText(c.name);
        if (etDescription.getText().length() == 0) etDescription.setText(c.description);
        if (switchPrivate != null) {
            switchPrivate.setOnCheckedChangeListener(null);
            switchPrivate.setChecked(c.isPrivate);
            switchPrivate.setOnCheckedChangeListener((btn, checked) ->
                    repo.setCommunityPrivacy(communityId, checked, (success, error) -> {}));
        }
        // Show invite link if enabled
        if (c.inviteEnabled && c.inviteToken != null && layoutInvite != null) {
            layoutInvite.setVisibility(View.VISIBLE);
            String link = "callx://community/" + communityId + "?invite=" + c.inviteToken;
            if (tvInviteLink != null) tvInviteLink.setText(link);
        }
    }

    private void generateInviteLink() {
        repo.generateInviteToken(communityId, token -> {
            if (token == null) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to generate link", Toast.LENGTH_SHORT).show());
                return;
            }
            currentInviteToken = token;
            String link = "callx://community/" + communityId + "?invite=" + token;
            runOnUiThread(() -> showInviteLinkDialog(link));
        });
    }

    private void showInviteLinkDialog(String link) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_community_invite_link, null);
        TextView tvLink = dialogView.findViewById(R.id.tv_invite_link);
        if (tvLink != null) tvLink.setText(link);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        View btnCopy = dialogView.findViewById(R.id.btn_copy_link);
        View btnShare = dialogView.findViewById(R.id.btn_share_link);
        View btnRegen = dialogView.findViewById(R.id.btn_regenerate);

        if (btnCopy != null) btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Invite Link", link));
            Toast.makeText(this, "Link copied!", Toast.LENGTH_SHORT).show();
        });
        if (btnShare != null) btnShare.setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, "Join my community: " + link);
            startActivity(Intent.createChooser(share, "Share Invite Link"));
        });
        if (btnRegen != null) btnRegen.setOnClickListener(v -> {
            dialog.dismiss();
            generateInviteLink();
        });

        dialog.show();
    }

    private void save() {
        String name        = etName.getText().toString().trim();
        String description = etDescription.getText().toString().trim();
        if (name.isEmpty()) { Toast.makeText(this, "Name can't be empty", Toast.LENGTH_SHORT).show(); return; }
        btnSave.setEnabled(false);

        if (pickedIconUri != null) {
            CloudinaryUploader.upload(this, pickedIconUri, "callx/community_icons", "image",
                    new CloudinaryUploader.UploadCallback() {
                        @Override public void onSuccess(CloudinaryUploader.Result result) {
                            runOnUiThread(() -> doSave(name, description, result.secureUrl));
                        }
                        @Override public void onError(String message) {
                            runOnUiThread(() -> { doSave(name, description, null); });
                        }
                    });
        } else {
            doSave(name, description, null);
        }
    }

    private void doSave(String name, String description, String iconUrl) {
        repo.updateCommunityInfo(communityId, name, description, iconUrl, (success, error) ->
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    if (success) { Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); finish(); }
                    else Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                }));
    }

    private void confirmDisable() {
        new AlertDialog.Builder(this)
                .setTitle("Disable Community?")
                .setMessage("This permanently deletes the community. This can't be undone.")
                .setPositiveButton("Disable", (d, w) -> disable())
                .setNegativeButton("Cancel", null).show();
    }

    private void disable() {
        if (currentUid == null) return;
        btnDisable.setEnabled(false);
        repo.disableCommunity(communityId, currentUid, (success, error) -> runOnUiThread(() -> {
            if (success) { setResult(RESULT_FIRST_USER); finish(); }
            else { btnDisable.setEnabled(true); Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show(); }
        }));
    }
}
