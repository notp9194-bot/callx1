package com.callx.app.activities;

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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.models.XUser;
import com.callx.app.utils.XCloudinaryUtils;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public class XEditProfileActivity extends AppCompatActivity {

    private static final int BIO_MAX = 160;

    private String myUid;
    private XUser xUser;
    private CircleImageView ivAvatar;
    private ImageView ivBanner;

    private TextInputEditText etName, etHandle, etBio, etWebsite, etLocation;
    private TextInputLayout tilHandle, tilBio;
    private TextView tvBioCount, tvBirthday;
    private Spinner spGender;
    private Switch swPrivate;
    private ProgressBar pbSave;
    private boolean pickingAvatar;

    // FIX: launcher must be registered at field level, not inside a method
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

        // FIX: null user guard — prevents Firebase invalid-path crash
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

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

        // Bio character counter
        etBio.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int len = s.length();
                tvBioCount.setText(len + "/" + BIO_MAX);
                tvBioCount.setTextColor(len > BIO_MAX
                    ? 0xFFD32F2F : getResources().getColor(R.color.x_text_secondary, getTheme()));
            }
        });

        // Birthday picker
        tvBirthday.setOnClickListener(v -> showDatePicker());

        // Toolbar buttons
        // FIX: Use XEditProfileActivity.this explicitly — avoids "this" capture issue in lambdas
        //      that caused crashes when activity was recreated mid-launch
        XEditProfileActivity self = this;
        findViewById(R.id.btn_edit_close).setOnClickListener(v -> self.finish());
        findViewById(R.id.btn_edit_save).setOnClickListener(v -> self.saveProfile());
        ivAvatar.setOnClickListener(v -> { pickingAvatar = true; pickImage(); });
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
        // Pre-fill if birthday already set
        if (xUser != null && xUser.birthday != null && xUser.birthday.contains("/")) {
            try {
                String[] parts = xUser.birthday.split("/");
                cal.set(Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[1]) - 1,
                        Integer.parseInt(parts[0]));
            } catch (Exception ignored) {}
        }
        new DatePickerDialog(this, (view, year, month, day) -> {
            String dob = String.format("%02d/%02d/%04d", day, month + 1, year);
            tvBirthday.setText(dob);
            tvBirthday.setTextColor(getResources().getColor(R.color.x_text_primary, getTheme()));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void loadProfile() {
        XFirebaseUtils.xUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                xUser = snap.getValue(XUser.class);
                if (xUser == null) return;

                etName.setText(xUser.name);
                etHandle.setText(xUser.handle != null ? xUser.handle : "");
                etBio.setText(xUser.bio != null ? xUser.bio : "");
                etWebsite.setText(xUser.website != null ? xUser.website : "");
                etLocation.setText(xUser.location != null ? xUser.location : "");

                // Birthday
                if (xUser.birthday != null && !xUser.birthday.isEmpty()) {
                    tvBirthday.setText(xUser.birthday);
                    tvBirthday.setTextColor(
                        getResources().getColor(R.color.x_text_primary, getTheme()));
                }

                // Gender spinner
                String[] genders = {"Prefer not to say", "Male", "Female", "Other"};
                if (xUser.gender != null) {
                    for (int i = 0; i < genders.length; i++) {
                        if (genders[i].equals(xUser.gender)) { spGender.setSelection(i); break; }
                    }
                }

                // Private account
                swPrivate.setChecked(xUser.privateAccount);

                // Avatar / banner
                if (xUser.photoUrl != null && !xUser.photoUrl.isEmpty()) {
                    String url = (xUser.thumbUrl != null && !xUser.thumbUrl.isEmpty())
                        ? xUser.thumbUrl : xUser.photoUrl;
                    Glide.with(XEditProfileActivity.this).load(url).circleCrop().into(ivAvatar);
                }
                if (xUser.bannerUrl != null && !xUser.bannerUrl.isEmpty())
                    Glide.with(XEditProfileActivity.this).load(xUser.bannerUrl)
                        .centerCrop().into(ivBanner);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void uploadImage(Uri uri, boolean isAvatar) {
        pbSave.setVisibility(View.VISIBLE);
        XCloudinaryUtils.XUploadListener cb = new XCloudinaryUtils.XUploadListener() {
            @Override public void onSuccess(String publicId, String url) {
                runOnUiThread(() -> {
                    pbSave.setVisibility(View.GONE);
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
                    pbSave.setVisibility(View.GONE);
                    Toast.makeText(XEditProfileActivity.this, "Upload failed: " + msg,
                        Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onProgress(int pct) {}
        };
        XCloudinaryUtils.uploadTweetImage(this, uri, cb);
    }

    private void saveProfile() {
        String name   = getText(etName);
        String handle = getText(etHandle).toLowerCase().replaceAll("[^a-z0-9_]", "");
        String bio    = getText(etBio);

        if (name.isEmpty()) { etName.setError("Name required"); return; }
        if (handle.isEmpty()) { tilHandle.setError("Handle required"); return; }
        if (handle.length() < 3) { tilHandle.setError("Min 3 characters"); return; }
        if (bio.length() > BIO_MAX) {
            tilBio.setError("Bio too long (" + bio.length() + "/" + BIO_MAX + ")"); return;
        }

        pbSave.setVisibility(View.VISIBLE);

        // If handle changed, check uniqueness first
        String oldHandle = (xUser != null && xUser.handle != null) ? xUser.handle : "";
        if (!handle.equals(oldHandle)) {
            XFirebaseUtils.xHandlesRef().child(handle)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        if (snap.exists() && !myUid.equals(snap.getValue(String.class))) {
                            pbSave.setVisibility(View.GONE);
                            tilHandle.setError("Handle already taken");
                        } else {
                            persistProfile(handle, oldHandle, name, bio);
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        pbSave.setVisibility(View.GONE);
                        Toast.makeText(XEditProfileActivity.this, "Error checking handle",
                            Toast.LENGTH_SHORT).show();
                    }
                });
        } else {
            persistProfile(handle, oldHandle, name, bio);
        }
    }

    private void persistProfile(String handle, String oldHandle, String name, String bio) {
        String gender   = spGender.getSelectedItem().toString();
        String birthday = tvBirthday.getText().toString();
        boolean priv    = swPrivate.isChecked();

        Map<String, Object> updates = new HashMap<>();
        updates.put("name",           name);
        updates.put("handle",         handle);
        updates.put("bio",            bio);
        updates.put("website",        getText(etWebsite));
        updates.put("location",       getText(etLocation));
        updates.put("gender",         gender);
        updates.put("birthday",       birthday.equals("Tap to set birthday") ? "" : birthday);
        updates.put("privateAccount", priv);

        XFirebaseUtils.xUserRef(myUid).updateChildren(updates).addOnCompleteListener(t -> {
            if (t.isSuccessful()) {
                // Update handles index
                if (!handle.equals(oldHandle)) {
                    if (!oldHandle.isEmpty())
                        XFirebaseUtils.xHandlesRef().child(oldHandle).removeValue();
                    XFirebaseUtils.xHandlesRef().child(handle).setValue(myUid);
                }
                pbSave.setVisibility(View.GONE);
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            } else {
                pbSave.setVisibility(View.GONE);
                Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String getText(TextInputEditText field) {
        return field.getText() != null ? field.getText().toString().trim() : "";
    }
}
