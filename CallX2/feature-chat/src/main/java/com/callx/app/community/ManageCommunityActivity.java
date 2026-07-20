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
import android.widget.ImageView;
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
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * v34: Manage community — updated with:
 *  - Banner/cover image picker (1200×400 landscape) + Cloudinary upload
 *  - Community Rules editor (multi-line text, saved to Firebase)
 *  - Category selector chip-group
 *  - isVerified toggle (owner only)
 */
public class ManageCommunityActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";
    public static final String EXTRA_IS_OWNER     = "isOwner";

    private static final String[] CATEGORIES =
            {"Tech","Sports","Gaming","Music","Art","Food","Health","Education","Other"};

    private String communityId;
    private boolean isOwner;
    private String currentUid;

    // Icon picker
    private Uri pickedIconUri;
    // Banner picker
    private Uri pickedBannerUri;

    private String currentInviteToken;

    // Views
    private CircleImageView ivIcon;
    private ImageView ivBanner;
    private View btnChangeBanner, btnPickBannerOverlay;
    private EditText etName, etDescription, etRules;
    private Switch switchPrivate;
    private android.widget.Spinner spinnerCategory;
    private android.widget.Button btnSave, btnDisable, btnGenerateInvite;
    private View layoutInvite;
    private TextView tvInviteLink;

    private CommunityRepository repo;
    private ActivityResultLauncher<String> iconPicker, bannerPicker;

    // Current state loaded from Firebase
    private String currentIconUrl, currentBannerUrl, currentCategory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_community_v2);

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

        bindViews();
        setupPickers();
        setupSpinner();
        loadCurrentData();

        btnSave.setOnClickListener(v -> saveAll());
        btnDisable.setVisibility(isOwner ? View.VISIBLE : View.GONE);
        btnDisable.setOnClickListener(v -> confirmDisable());
        btnGenerateInvite.setOnClickListener(v -> generateOrShowInvite());
    }

    private void bindViews() {
        ivIcon            = findViewById(R.id.iv_community_icon);
        ivBanner          = findViewById(R.id.iv_community_banner);
        btnChangeBanner   = findViewById(R.id.btn_change_banner);
        etName            = findViewById(R.id.et_community_name);
        etDescription     = findViewById(R.id.et_community_description);
        etRules           = findViewById(R.id.et_community_rules);
        switchPrivate     = findViewById(R.id.switch_private);
        spinnerCategory   = findViewById(R.id.spinner_community_category);
        btnSave           = findViewById(R.id.btn_save);
        btnDisable        = findViewById(R.id.btn_disable_community);
        btnGenerateInvite = findViewById(R.id.btn_generate_invite);
        layoutInvite      = findViewById(R.id.layout_invite_link);
        tvInviteLink      = findViewById(R.id.tv_invite_link_value);
    }

    private void setupPickers() {
        iconPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            pickedIconUri = uri;
            Glide.with(this).load(uri).circleCrop().override(192,192).into(ivIcon);
        });
        ivIcon.setOnClickListener(v -> iconPicker.launch("image/*"));

        bannerPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            pickedBannerUri = uri;
            ivBanner.setVisibility(View.VISIBLE);
            Glide.with(this).load(uri).centerCrop().override(800,260).into(ivBanner);
        });
        if (btnChangeBanner != null)
            btnChangeBanner.setOnClickListener(v -> bannerPicker.launch("image/*"));
        if (ivBanner != null)
            ivBanner.setOnClickListener(v -> bannerPicker.launch("image/*"));
    }

    private void setupSpinner() {
        android.widget.ArrayAdapter<String> catAdapter =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, CATEGORIES);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(catAdapter);
    }

    private void loadCurrentData() {
        repo.observeCommunityOnce(communityId, community -> {
            if (community == null) return;
            runOnUiThread(() -> {
                etName.setText(community.name != null ? community.name : "");
                etDescription.setText(community.description != null ? community.description : "");
                switchPrivate.setChecked(community.isPrivate);
                currentInviteToken = community.inviteToken;
                currentIconUrl     = community.iconUrl;
                currentBannerUrl   = community.bannerUrl;
                currentCategory    = community.category;

                if (community.iconUrl != null && !community.iconUrl.isEmpty())
                    Glide.with(this).load(community.iconUrl).circleCrop()
                            .placeholder(R.drawable.ic_group).into(ivIcon);

                if (community.bannerUrl != null && !community.bannerUrl.isEmpty()) {
                    ivBanner.setVisibility(View.VISIBLE);
                    Glide.with(this).load(community.bannerUrl).centerCrop()
                            .override(800,260).into(ivBanner);
                }

                // Load rules from Firebase (stored as flat string under communities/{id}/rules)
                FirebaseDatabase.getInstance().getReference("communities")
                        .child(communityId).child("rules")
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot s) {
                                String rules = s.getValue(String.class);
                                if (rules != null) runOnUiThread(() -> etRules.setText(rules));
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });

                // Set spinner position
                if (community.category != null) {
                    for (int i = 0; i < CATEGORIES.length; i++) {
                        if (CATEGORIES[i].equals(community.category)) {
                            spinnerCategory.setSelection(i); break;
                        }
                    }
                }
            });
        });
    }

    private void saveAll() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Community name cannot be empty", Toast.LENGTH_SHORT).show(); return;
        }
        btnSave.setEnabled(false);
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();

        String desc       = etDescription.getText().toString().trim();
        String rules      = etRules.getText().toString().trim();
        boolean isPrivate = switchPrivate.isChecked();
        String category   = CATEGORIES[spinnerCategory.getSelectedItemPosition()];

        int uploadCount = (pickedIconUri != null ? 1 : 0) + (pickedBannerUri != null ? 1 : 0);
        if (uploadCount == 0) {
            saveToFirebase(name, desc, rules, currentIconUrl, currentBannerUrl, isPrivate, category);
            return;
        }

        // Use an array to hold the upload results
        final String[] iconUrl   = {currentIconUrl};
        final String[] bannerUrl = {currentBannerUrl};
        final int[] done = {0};

        if (pickedIconUri != null) {
            new CloudinaryUploader().uploadFile(this, pickedIconUri, "callx/community_icons",
                    new CloudinaryUploader.UploadCallback() {
                        @Override public void onSuccess(CloudinaryUploader.Result r) {
                            iconUrl[0] = r.secureUrl;
                            done[0]++;
                            if (done[0] >= uploadCount)
                                runOnUiThread(() -> saveToFirebase(name, desc, rules, iconUrl[0], bannerUrl[0], isPrivate, category));
                        }
                        @Override public void onError(String msg) {
                            runOnUiThread(() -> {
                                btnSave.setEnabled(true);
                                Toast.makeText(ManageCommunityActivity.this,
                                        "Icon upload failed: " + msg, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        }

        if (pickedBannerUri != null) {
            new CloudinaryUploader().uploadFile(this, pickedBannerUri, "callx/community_banners",
                    new CloudinaryUploader.UploadCallback() {
                        @Override public void onSuccess(CloudinaryUploader.Result r) {
                            bannerUrl[0] = r.secureUrl;
                            done[0]++;
                            if (done[0] >= uploadCount)
                                runOnUiThread(() -> saveToFirebase(name, desc, rules, iconUrl[0], bannerUrl[0], isPrivate, category));
                        }
                        @Override public void onError(String msg) {
                            runOnUiThread(() -> {
                                btnSave.setEnabled(true);
                                Toast.makeText(ManageCommunityActivity.this,
                                        "Banner upload failed: " + msg, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        }
    }

    private void saveToFirebase(String name, String desc, String rules,
                                String iconUrl, String bannerUrl,
                                boolean isPrivate, String category) {
        repo.updateCommunityInfo(communityId, name, desc, iconUrl, isPrivate,
                bannerUrl, category, rules, (success, error) -> runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    if (success) {
                        Toast.makeText(this, "Community updated!", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private void confirmDisable() {
        new AlertDialog.Builder(this)
                .setTitle("Disable Community?")
                .setMessage("This will deactivate the community for all members. This cannot be undone easily.")
                .setPositiveButton("Disable", (d, w) -> {
                    repo.disableCommunity(communityId, currentUid, (s, e) ->
                            runOnUiThread(() -> {
                                if (s) { Toast.makeText(this, "Community disabled", Toast.LENGTH_SHORT).show(); finish(); }
                                else Toast.makeText(this, "Error: " + e, Toast.LENGTH_SHORT).show();
                            }));
                })
                .setNegativeButton("Cancel", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void generateOrShowInvite() {
        if (currentInviteToken != null && !currentInviteToken.isEmpty()) {
            showInviteLink("https://callx.app/community/join/" + communityId + "?t=" + currentInviteToken);
        } else {
            repo.generateInviteLink(communityId, (link, error) -> runOnUiThread(() -> {
                if (link != null) {
                    currentInviteToken = link;
                    showInviteLink("https://callx.app/community/join/" + communityId + "?t=" + link);
                } else {
                    Toast.makeText(this, "Error: " + error, Toast.LENGTH_SHORT).show();
                }
            }));
        }
    }

    private void showInviteLink(String link) {
        layoutInvite.setVisibility(View.VISIBLE);
        tvInviteLink.setText(link);
        tvInviteLink.setOnLongClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Invite Link", link));
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }
}
