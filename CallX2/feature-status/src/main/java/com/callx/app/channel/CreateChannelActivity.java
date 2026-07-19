package com.callx.app.channel;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.callx.app.utils.FirebaseUtils;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * CreateChannelActivity — WhatsApp-level channel creation.
 *
 * Features:
 *   • Channel name (required, 3-40 chars)
 *   • Description (optional, max 200 chars)
 *   • Icon upload (Firebase Storage)
 *   • Category selection (chip group)
 *   • Channel type toggle: Public / Private (invite-link only)
 */
public class CreateChannelActivity extends AppCompatActivity {

    private CircleImageView   ivChannelIcon;
    private TextInputEditText etChannelName, etChannelDesc;
    private MaterialButton    btnCreate;
    private ProgressBar       progressBar;
    private TextView          tvNameCount, tvDescCount;
    private ChipGroup         chipGroupCategory;
    private SwitchMaterial    switchPrivate;
    private TextView          tvPrivacyDesc;
    private Uri selectedImageUri;
    private ChannelViewModel viewModel;
    private String selectedCategory = "General";

    private static final String[] CATEGORIES = {
        "General", "Entertainment", "Sports", "News",
        "Education", "Technology", "Music", "Business",
        "Health", "Lifestyle", "Food", "Travel"
    };

    private final ActivityResultLauncher<String> pickImage =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                selectedImageUri = uri;
                ivChannelIcon.setImageURI(uri);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_channel);

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_create_channel);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Create channel");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        ivChannelIcon    = findViewById(R.id.iv_create_channel_icon);
        etChannelName    = findViewById(R.id.et_channel_name);
        etChannelDesc    = findViewById(R.id.et_channel_desc);
        btnCreate        = findViewById(R.id.btn_create_channel_submit);
        progressBar      = findViewById(R.id.progress_create_channel);
        tvNameCount      = findViewById(R.id.tv_name_count);
        tvDescCount      = findViewById(R.id.tv_desc_count);
        chipGroupCategory= findViewById(R.id.chip_group_create_category);
        switchPrivate    = findViewById(R.id.switch_private_channel);
        tvPrivacyDesc    = findViewById(R.id.tv_privacy_description);

        // Icon picker
        ivChannelIcon.setOnClickListener(v -> pickImage.launch("image/*"));
        View ivCam = findViewById(R.id.iv_camera_badge);
        if (ivCam != null) ivCam.setOnClickListener(v -> pickImage.launch("image/*"));

        // Name watcher
        etChannelName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                int len = s.toString().trim().length();
                if (tvNameCount != null) tvNameCount.setText(len + "/40");
                boolean valid = len >= 3 && len <= 40;
                if (!valid && len > 0) {
                    etChannelName.setError(len < 3 ? "Min 3 characters" : "Max 40 characters");
                } else {
                    etChannelName.setError(null);
                }
                btnCreate.setEnabled(len >= 3 && len <= 40);
            }
        });

        // Desc watcher
        if (etChannelDesc != null) {
            etChannelDesc.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    int len = s.toString().length();
                    if (tvDescCount != null) tvDescCount.setText(len + "/200");
                }
            });
        }

        // Category chips
        setupCategoryChips();

        // Privacy toggle
        if (switchPrivate != null) {
            switchPrivate.setOnCheckedChangeListener((btn, checked) -> {
                if (tvPrivacyDesc != null) {
                    tvPrivacyDesc.setText(checked
                        ? "Only people with the invite link can join"
                        : "Anyone can discover and follow this channel");
                }
            });
        }

        btnCreate.setEnabled(false);
        btnCreate.setOnClickListener(v -> attemptCreate());

        viewModel.loading.observe(this, loading -> {
            progressBar.setVisibility(loading != null && loading ? View.VISIBLE : View.GONE);
            btnCreate.setEnabled(loading == null || !loading);
        });
    }

    private void setupCategoryChips() {
        if (chipGroupCategory == null) return;
        for (String cat : CATEGORIES) {
            Chip chip = new Chip(this);
            chip.setText(cat);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setChipBackgroundColorResource(R.color.chip_bg_selector);
            chip.setTextColor(getResources().getColorStateList(R.color.chip_text_selector));
            if ("General".equals(cat)) chip.setChecked(true);
            chip.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) selectedCategory = cat;
            });
            chipGroupCategory.addView(chip);
        }
    }

    private void attemptCreate() {
        String name = etChannelName.getText() != null ? etChannelName.getText().toString().trim() : "";
        String desc = etChannelDesc.getText() != null ? etChannelDesc.getText().toString().trim() : "";
        if (name.length() < 3) { etChannelName.setError("Min 3 characters"); return; }
        if (name.length() > 40) { etChannelName.setError("Max 40 characters"); return; }

        boolean isPrivate = switchPrivate != null && switchPrivate.isChecked();

        if (selectedImageUri != null) {
            uploadIconAndCreate(name, desc, isPrivate);
        } else {
            createViaViewModel(name, desc, "", isPrivate);
        }
    }

    private void uploadIconAndCreate(String name, String desc, boolean isPrivate) {
        progressBar.setVisibility(View.VISIBLE);
        btnCreate.setEnabled(false);
        String uid = FirebaseUtils.getCurrentUid();
        StorageReference iconRef = FirebaseStorage.getInstance().getReference()
            .child("channelIcons").child(uid + "_" + System.currentTimeMillis() + ".jpg");
        iconRef.putFile(selectedImageUri)
            .addOnSuccessListener(t -> iconRef.getDownloadUrl()
                .addOnSuccessListener(uri -> createViaViewModel(name, desc, uri.toString(), isPrivate))
                .addOnFailureListener(e -> createViaViewModel(name, desc, "", isPrivate)))
            .addOnFailureListener(e -> createViaViewModel(name, desc, "", isPrivate));
    }

    private void createViaViewModel(String name, String desc, String iconUrl, boolean isPrivate) {
        viewModel.createChannel(name, desc, iconUrl, selectedCategory, isPrivate,
            new ChannelViewModel.CreateCallback() {
                @Override public void onCreated(com.callx.app.db.entity.ChannelEntity ch) {
                    Toast.makeText(CreateChannelActivity.this,
                        "Channel \"" + name + "\" created!", Toast.LENGTH_LONG).show();
                    Intent i = new Intent(CreateChannelActivity.this, ChannelViewerActivity.class);
                    i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ID,       ch.id);
                    i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_NAME,     ch.name);
                    i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ICON,     ch.iconUrl);
                    i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_VERIFIED, ch.verified);
                    i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_FOLLOWERS,ch.followers);
                    startActivity(i);
                    finish();
                }
                @Override public void onFailed() {
                    Toast.makeText(CreateChannelActivity.this,
                        "Failed to create channel. Try again.", Toast.LENGTH_SHORT).show();
                }
            });
    }
}
