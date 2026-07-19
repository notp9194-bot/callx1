package com.callx.app.channel;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.util.Consumer;
import androidx.lifecycle.ViewModelProvider;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import de.hdodenhof.circleimageview.CircleImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * ChannelEditActivity — WhatsApp-level channel edit screen.
 *
 * Pre-filled with current channel data. Owner/admin can change:
 *   • Channel name (3–40 chars)
 *   • Description (0–200 chars)
 *   • Channel icon (photo picker → Firebase Storage)
 *   • Category (chip group)
 *   • Privacy toggle: Public ↔ Private
 *
 * Changes are written atomically to Firebase + Room via ChannelViewModel.editChannel().
 */
public class ChannelEditActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";
    public static final String EXTRA_CHANNEL_DESC = "channelDesc";
    public static final String EXTRA_CHANNEL_ICON = "channelIcon";
    public static final String EXTRA_CHANNEL_CATEGORY = "channelCategory";
    public static final String EXTRA_CHANNEL_PRIVATE   = "channelPrivate";

    private static final int MAX_NAME = 40;
    private static final int MAX_DESC = 200;

    private ChannelViewModel  viewModel;
    private String channelId;
    private String currentIconUrl;
    private Uri    newIconUri;

    private CircleImageView   ivIcon;
    private TextInputEditText etName, etDesc;
    private MaterialButton    btnSave;
    private ProgressBar       progressBar;
    private TextView          tvNameCount, tvDescCount;
    private ChipGroup         chipGroupCategory;
    private SwitchMaterial    switchPrivate;
    private TextView          tvPrivacyDesc;
    private String            selectedCategory = "General";

    private static final String[] CATEGORIES = {
        "General","Entertainment","Sports","News","Education","Technology",
        "Music","Business","Health","Lifestyle","Food","Travel"
    };

    private final ActivityResultLauncher<String> pickIcon =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) copyToCacheThen(uri, local -> {
                newIconUri = local;
                ivIcon.setImageURI(local);
            });
        });

    /**
     * Snapshots a picked content:// URI into this app's private cache
     * immediately, so the later icon upload (which happens only after the
     * user hits Save, possibly well after picking) doesn't depend on the
     * system picker's short-lived read grant. Without this, putFile()
     * intermittently failed with Firebase Storage's
     * "Object does not exist at location" once that grant/backing temp
     * file was gone by upload time.
     */
    private void copyToCacheThen(Uri source, Consumer<Uri> callback) {
        new Thread(() -> {
            File out = null;
            try {
                out = new File(getCacheDir(), "channel_icon_" + System.currentTimeMillis() + ".jpg");
                try (InputStream in = getContentResolver().openInputStream(source);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    if (in == null) throw new java.io.IOException("Unable to open picked file");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                }
            } catch (Exception e) {
                out = null;
            }
            final Uri localUri = out != null ? Uri.fromFile(out) : null;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (localUri != null) {
                    callback.accept(localUri);
                } else {
                    Toast.makeText(this, "Couldn't read the selected image. Please try again.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_channel); // reuse same layout

        channelId        = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        String name      = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        String desc      = getIntent().getStringExtra(EXTRA_CHANNEL_DESC);
        currentIconUrl   = getIntent().getStringExtra(EXTRA_CHANNEL_ICON);
        String category  = getIntent().getStringExtra(EXTRA_CHANNEL_CATEGORY);
        boolean isPrivate= getIntent().getBooleanExtra(EXTRA_CHANNEL_PRIVATE, false);

        if (channelId == null) { finish(); return; }
        if (category != null) selectedCategory = category;

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_create_channel);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Edit channel");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        ivIcon         = findViewById(R.id.iv_create_channel_icon);
        etName         = findViewById(R.id.et_channel_name);
        etDesc         = findViewById(R.id.et_channel_desc);
        btnSave        = findViewById(R.id.btn_create_channel_submit);
        progressBar    = findViewById(R.id.progress_create_channel);
        tvNameCount    = findViewById(R.id.tv_name_count);
        tvDescCount    = findViewById(R.id.tv_desc_count);
        chipGroupCategory = findViewById(R.id.chip_group_create_category);
        switchPrivate  = findViewById(R.id.switch_private_channel);
        tvPrivacyDesc  = findViewById(R.id.tv_privacy_description);

        // Pre-fill
        if (etName != null && name != null) etName.setText(name);
        if (etDesc != null && desc  != null) etDesc.setText(desc);
        if (switchPrivate != null) switchPrivate.setChecked(isPrivate);
        if (currentIconUrl != null && !currentIconUrl.isEmpty() && ivIcon != null)
            Glide.with(this).load(currentIconUrl).circleCrop().into(ivIcon);
        if (btnSave != null) btnSave.setText("Save changes");

        // Icon picker
        if (ivIcon != null) ivIcon.setOnClickListener(v -> pickIcon.launch("image/*"));
        View camBadge = findViewById(R.id.iv_camera_badge);
        if (camBadge != null) camBadge.setOnClickListener(v -> pickIcon.launch("image/*"));

        // Char counters
        setupCounter(etName, tvNameCount, MAX_NAME);
        setupCounter(etDesc, tvDescCount, MAX_DESC);

        // Category chips
        setupCategoryChips(chipGroupCategory, selectedCategory);

        // Privacy description
        if (switchPrivate != null) {
            updatePrivacyDesc(switchPrivate.isChecked());
            switchPrivate.setOnCheckedChangeListener((btn, checked) -> updatePrivacyDesc(checked));
        }

        if (btnSave != null) btnSave.setOnClickListener(v -> attemptSave());

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            if (msg != null && msg.contains("updated")) finish();
        });

        viewModel.loading.observe(this, loading -> {
            if (progressBar != null) progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
            if (btnSave     != null) btnSave.setEnabled(!loading);
        });
    }

    private void setupCounter(TextInputEditText et, TextView tv, int max) {
        if (et == null || tv == null) return;
        tv.setText(et.length() + "/" + max);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                tv.setText(s.length() + "/" + max);
                tv.setTextColor(s.length() > max ? 0xFFFF3B30 : 0xFF757575);
            }
        });
    }

    private void setupCategoryChips(ChipGroup chipGroup, String selected) {
        if (chipGroup == null) return;
        chipGroup.removeAllViews();
        for (String cat : CATEGORIES) {
            Chip chip = new Chip(this);
            chip.setText(cat);
            chip.setCheckable(true);
            chip.setChecked(cat.equals(selected));
            chip.setOnCheckedChangeListener((btn, checked) -> { if (checked) selectedCategory = cat; });
            chipGroup.addView(chip);
        }
    }

    private void updatePrivacyDesc(boolean isPrivate) {
        if (tvPrivacyDesc == null) return;
        tvPrivacyDesc.setText(isPrivate
            ? "Private — only people with the invite link can join."
            : "Public — anyone can find and follow this channel.");
    }

    private void attemptSave() {
        String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
        String desc = etDesc != null && etDesc.getText() != null ? etDesc.getText().toString().trim() : "";
        if (name.length() < 3)  { if (etName != null) etName.setError("Min 3 characters"); return; }
        if (name.length() > MAX_NAME) { if (etName != null) etName.setError("Max " + MAX_NAME + " characters"); return; }
        if (desc.length() > MAX_DESC) { if (etDesc != null) etDesc.setError("Max " + MAX_DESC + " characters"); return; }
        boolean isPrivate = switchPrivate != null && switchPrivate.isChecked();

        if (newIconUri != null) {
            uploadIconAndSave(name, desc, isPrivate);
        } else {
            viewModel.editChannel(channelId, name, desc,
                currentIconUrl != null ? currentIconUrl : "", selectedCategory, isPrivate);
        }
    }

    private void uploadIconAndSave(String name, String desc, boolean isPrivate) {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (btnSave    != null) btnSave.setEnabled(false);
        StorageReference ref = FirebaseStorage.getInstance().getReference()
            .child("channelIcons").child(channelId + "_" + System.currentTimeMillis() + ".jpg");
        ref.putFile(newIconUri)
            .addOnSuccessListener(t -> ref.getDownloadUrl()
                .addOnSuccessListener(uri -> {
                    currentIconUrl = uri.toString();
                    viewModel.editChannel(channelId, name, desc, currentIconUrl, selectedCategory, isPrivate);
                })
                .addOnFailureListener(e -> viewModel.editChannel(channelId, name, desc,
                    currentIconUrl != null ? currentIconUrl : "", selectedCategory, isPrivate)))
            .addOnFailureListener(e -> viewModel.editChannel(channelId, name, desc,
                currentIconUrl != null ? currentIconUrl : "", selectedCategory, isPrivate));
    }
}
