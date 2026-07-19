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
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.callx.app.utils.FirebaseUtils;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * CreateChannelActivity v2 — WhatsApp-level architecture.
 *
 * CHANGED: Uses ChannelViewModel.createChannel() instead of ChannelManager directly.
 * Data flow: ChannelViewModel → ChannelRepository.createChannel → Firebase + Room.
 */
public class CreateChannelActivity extends AppCompatActivity {

    private CircleImageView  ivChannelIcon;
    private TextInputEditText etChannelName, etChannelDesc;
    private MaterialButton   btnCreate;
    private ProgressBar      progressBar;
    private Uri selectedImageUri;
    private ChannelViewModel viewModel;

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

        ivChannelIcon  = findViewById(R.id.iv_create_channel_icon);
        etChannelName  = findViewById(R.id.et_channel_name);
        etChannelDesc  = findViewById(R.id.et_channel_desc);
        btnCreate      = findViewById(R.id.btn_create_channel_submit);
        progressBar    = findViewById(R.id.progress_create_channel);

        ivChannelIcon.setOnClickListener(v -> pickImage.launch("image/*"));
        View ivCam = findViewById(R.id.iv_camera_badge);
        if (ivCam != null) ivCam.setOnClickListener(v -> pickImage.launch("image/*"));

        etChannelName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                btnCreate.setEnabled(s.toString().trim().length() > 0);
            }
        });
        btnCreate.setEnabled(false);
        btnCreate.setOnClickListener(v -> attemptCreate());

        // Observe loading state from ViewModel
        viewModel.loading.observe(this, loading -> {
            progressBar.setVisibility(loading != null && loading ? View.VISIBLE : View.GONE);
            btnCreate.setEnabled(loading == null || !loading);
        });
    }

    private void attemptCreate() {
        String name = etChannelName.getText() != null
            ? etChannelName.getText().toString().trim() : "";
        String desc = etChannelDesc.getText() != null
            ? etChannelDesc.getText().toString().trim() : "";
        if (name.isEmpty()) { etChannelName.setError("Channel name is required"); return; }

        if (selectedImageUri != null) {
            uploadIconAndCreate(name, desc);
        } else {
            createViaViewModel(name, desc, "");
        }
    }

    private void uploadIconAndCreate(String name, String desc) {
        progressBar.setVisibility(View.VISIBLE);
        btnCreate.setEnabled(false);
        String uid = FirebaseUtils.getCurrentUid();
        StorageReference iconRef = FirebaseStorage.getInstance().getReference()
            .child("channelIcons").child(uid + "_" + System.currentTimeMillis() + ".jpg");
        iconRef.putFile(selectedImageUri)
            .addOnSuccessListener(t -> iconRef.getDownloadUrl()
                .addOnSuccessListener(uri -> createViaViewModel(name, desc, uri.toString()))
                .addOnFailureListener(e -> createViaViewModel(name, desc, "")))
            .addOnFailureListener(e -> createViaViewModel(name, desc, ""));
    }

    private void createViaViewModel(String name, String desc, String iconUrl) {
        // Delegate everything to ChannelViewModel → ChannelRepository
        viewModel.createChannel(name, desc, iconUrl, new ChannelViewModel.CreateCallback() {
            @Override public void onCreated(com.callx.app.db.entity.ChannelEntity ch) {
                Toast.makeText(CreateChannelActivity.this,
                    "Channel \"" + name + "\" created!", Toast.LENGTH_LONG).show();
                Intent i = new Intent(CreateChannelActivity.this, ChannelViewerActivity.class);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ID,       ch.id);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_NAME,     ch.name);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ICON,     ch.iconUrl);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_VERIFIED, ch.verified);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_FOLLOWERS, ch.followers);
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
