package com.callx.app.profile;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import com.bumptech.glide.Glide;
import com.callx.app.models.XProfile;
import com.callx.app.utils.XCloudinaryUtils;
import com.callx.app.utils.XProfileManager;
import com.callx.app.x.R;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.Calendar;

/**
 * XEditProfileActivity — Edit X profile.
 * Uses new XProfile model + XProfileManager.
 * Avatar → XCloudinaryUtils.uploadXAvatar() → XProfileManager.updateAvatar()
 * Banner → XCloudinaryUtils.uploadXBanner()  → XProfileManager.updateBanner()
 */
public class XEditProfileActivity extends AppCompatActivity {

    private static final int BIO_MAX = 160;

    private String myUid;
    private XProfile xProfile;
    private String originalHandle = "";

    private CircleImageView ivAvatar;
    private ImageView ivBanner;
    private TextInputEditText etName, etHandle, etBio, etWebsite, etLocation;
    private TextInputLayout tilHandle, tilBio;
    private TextView tvBioCount, tvBirthday;
    private Spinner spGender;
    private Switch swPrivate;
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

        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            myUid = auth.getCurrentUser().getUid();
            initViews();
        } else {
            auth.addAuthStateListener(new FirebaseAuth.AuthStateListener() {
                @Override public void onAuthStateChanged(@NonNull FirebaseAuth fa) {
                    fa.removeAuthStateListener(this);
                    if (fa.getCurrentUser() == null) {
                        Toast.makeText(XEditProfileActivity.this,
                            "Please login first", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        myUid = fa.getCurrentUser().getUid();
                        initViews();
                    }
                }
            });
        }
    }

    private void initViews() {
        ivAvatar    = findViewById(R.id.iv_edit_avatar);
        ivBanner    = findViewById(R.id.iv_edit_banner);
        etName      = findViewById(R.id.et_edit_name);
        etHandle    = findViewById(R.id.et_edit_handle);
        tilHandle   = findViewById(R.id.til_edit_handle);
        etBio       = findViewById(R.id.et_edit_bio);
        tilBio      = findViewById(R.id.til_edit_bio);
        tvBioCount  = findViewById(R.id.tv_bio_count);
        etWebsite   = findViewById(R.id.et_edit_website);
        etLocation  = findViewById(R.id.et_edit_location);
        tvBirthday  = findViewById(R.id.tv_edit_birthday);
        spGender    = findViewById(R.id.sp_edit_gender);
        swPrivate   = findViewById(R.id.sw_edit_private);
        pbSave      = findViewById(R.id.pb_edit_save);

        // Gender spinner
        String[] genders = {"Prefer not to say", "Male", "Female", "Other"};
        ArrayAdapter<String> ga = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, genders);
        ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGender.setAdapter(ga);

        // Bio counter
        etBio.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int len = s.length();
                tvBioCount.setText(len + "/" + BIO_MAX);
                tvBioCount.setTextColor(len > BIO_MAX
                    ? 0xFFD32F2F
                    : getResources().getColor(R.color.x_text_secondary, getTheme()));
            }
        });

        tvBirthday.setOnClickListener(v -> showDatePicker());

        XEditProfileActivity self = this;
        findViewById(R.id.btn_edit_close).setOnClickListener(v -> self.finish());
        findViewById(R.id.btn_edit_save).setOnClickListener(v -> self.saveProfile());
        ivAvatar.setOnClickListener(v -> { pickingAvatar = true;  pickImage(); });
        ivBanner.setOnClickListener(v -> { pickingAvatar = false; pickImage(); });

        loadProfile();
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        i.setType("image/*");
        imagePicker.launch(i);
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        if (xProfile != null && xProfile.birthday != null && xProfile.birthday.contains("/")) {
            try {
                String[] parts = xProfile.birthday.split("/");
                cal.set(Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[1]) - 1,
                        Integer.parseInt(parts[0]));
            } catch (Exception ignored) {}
        }
        new DatePickerDialog(this, (view, year, month, day) -> {
            String dob = String.format("%02d/%02d/%04d", day, month + 1, year);
            tvBirthday.setText(dob);
            tvBirthday.setTextColor(
                getResources().getColor(R.color.x_text_primary, getTheme()));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
           cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    private void loadProfile() {
        XProfileManager.load(myUid, profile -> {
            if (profile == null) return;
            xProfile      = profile;
            originalHandle = profile.handle != null ? profile.handle : "";

            etName.setText(profile.name);
            etHandle.setText(profile.handle != null ? profile.handle : "");
            etBio.setText(profile.bio != null ? profile.bio : "");
            etWebsite.setText(profile.website  != null ? profile.website  : "");
            etLocation.setText(profile.location != null ? profile.location : "");

            if (profile.birthday != null && !profile.birthday.isEmpty()) {
                tvBirthday.setText(profile.birthday);
                tvBirthday.setTextColor(
                    getResources().getColor(R.color.x_text_primary, getTheme()));
            }

            String[] genders = {"Prefer not to say", "Male", "Female", "Other"};
            if (profile.gender != null) {
                for (int i = 0; i < genders.length; i++) {
                    if (genders[i].equals(profile.gender)) {
                        spGender.setSelection(i); break;
                    }
                }
            }
            swPrivate.setChecked(profile.privateAccount);

            // Avatar
            String avatarUrl = profile.avatarForList();
            if (avatarUrl != null && !avatarUrl.isEmpty())
                Glide.with(this).load(avatarUrl).circleCrop().override(240, 240).into(ivAvatar);

            // Banner
            if (profile.bannerUrl != null && !profile.bannerUrl.isEmpty())
                Glide.with(this).load(profile.bannerUrl).centerCrop().override(720, 720).into(ivBanner);
        });
    }

    // ── Upload ───────────────────────────────────────────────────────────────

    private void uploadImage(Uri uri, boolean isAvatar) {
        pbSave.setVisibility(View.VISIBLE);
        XCloudinaryUtils.XUploadListener cb = new XCloudinaryUtils.XUploadListener() {
            @Override public void onSuccess(String publicId, String url) {
                runOnUiThread(() -> {
                    pbSave.setVisibility(View.GONE);
                    if (isAvatar) {
                        Glide.with(XEditProfileActivity.this).load(url).circleCrop().override(240, 240).into(ivAvatar);
                        // Save to Firebase via XProfileManager
                        XProfileManager.updateAvatar(myUid, url, null);
                    } else {
                        Glide.with(XEditProfileActivity.this).load(url).centerCrop().override(720, 720).into(ivBanner);
                        XProfileManager.updateBanner(myUid, url);
                    }
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    pbSave.setVisibility(View.GONE);
                    Toast.makeText(XEditProfileActivity.this,
                        "Upload failed: " + msg, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onProgress(int pct) {}
        };
        if (isAvatar) XCloudinaryUtils.uploadXAvatar(this, uri, cb);
        else          XCloudinaryUtils.uploadXBanner(this, uri, cb);
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private void saveProfile() {
        String name   = getText(etName);
        String handle = getText(etHandle).toLowerCase().replaceAll("[^a-z0-9_]", "");
        String bio    = getText(etBio);

        if (name.isEmpty())   { etName.setError("Name required"); return; }
        if (handle.isEmpty()) { tilHandle.setError("Handle required"); return; }
        if (handle.length() < 3) { tilHandle.setError("Min 3 characters"); return; }
        if (bio.length() > BIO_MAX) {
            tilBio.setError("Bio too long (" + bio.length() + "/" + BIO_MAX + ")"); return;
        }

        pbSave.setVisibility(View.VISIBLE);

        // Build updated XProfile
        if (xProfile == null) xProfile = new XProfile();
        xProfile.name           = name;
        xProfile.handle         = handle;
        xProfile.bio            = bio;
        xProfile.website        = getText(etWebsite);
        xProfile.location       = getText(etLocation);
        xProfile.gender         = spGender.getSelectedItem().toString();
        String bday             = tvBirthday.getText().toString();
        xProfile.birthday       = bday.equals("Tap to set birthday") ? "" : bday;
        xProfile.privateAccount = swPrivate.isChecked();

        if (!handle.equals(originalHandle)) {
            // Check handle availability first
            XProfileManager.checkHandleAvailable(handle, myUid, available -> {
                if (!available) {
                    pbSave.setVisibility(View.GONE);
                    tilHandle.setError("Handle already taken");
                } else {
                    persist();
                }
            });
        } else {
            persist();
        }
    }

    private void persist() {
        XProfileManager.save(myUid, xProfile, originalHandle,
            new XProfileManager.SaveCallback() {
                @Override public void onSuccess() {
                    pbSave.setVisibility(View.GONE);
                    Toast.makeText(XEditProfileActivity.this,
                        "Profile updated", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                }
                @Override public void onError(String error) {
                    pbSave.setVisibility(View.GONE);
                    Toast.makeText(XEditProfileActivity.this,
                        "Save failed: " + error, Toast.LENGTH_SHORT).show();
                }
            });
    }

    private String getText(TextInputEditText field) {
        return field.getText() != null ? field.getText().toString().trim() : "";
    }
}
