package com.callx.app.editor;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashMap;
import java.util.Map;

/**
 * ReelEditActivity — Edit caption/audience of a posted reel, or delete it.
 *
 * Features:
 *  ✅ Edit caption (pre-populated with current caption)
 *  ✅ Change audience: Everyone / Contacts Only
 *  ✅ Delete reel with confirmation dialog
 *  ✅ Cascade delete: removes from reels/, reelsByUser/, reelLikes/, reelComments/, reelSaves/
 *  ✅ Only the reel owner can access this screen
 */
public class ReelEditActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID       = "edit_reel_id";
    public static final String EXTRA_CAPTION       = "edit_caption";
    public static final String EXTRA_AUDIENCE_TYPE = "edit_audience";

    private TextInputEditText etCaption;
    private ChipGroup         chipAudience;
    private Button            btnSave;
    private Button            btnDelete;
    private ProgressBar       progressBar;

    private String reelId;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_edit);

        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);
        String currentCaption  = getIntent().getStringExtra(EXTRA_CAPTION);
        String currentAudience = getIntent().getStringExtra(EXTRA_AUDIENCE_TYPE);
        if (currentAudience == null) currentAudience = "everyone";

        if (reelId == null || reelId.isEmpty()) {
            finish();
            return;
        }

        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Edit Reel");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etCaption    = findViewById(R.id.et_edit_caption);
        chipAudience = findViewById(R.id.chip_edit_audience);
        btnSave      = findViewById(R.id.btn_save_reel_edit);
        btnDelete    = findViewById(R.id.btn_delete_reel);
        progressBar  = findViewById(R.id.progress_reel_edit);

        if (currentCaption != null) etCaption.setText(currentCaption);
        setAudienceChip(currentAudience);

        btnSave.setOnClickListener(v -> saveChanges());
        btnDelete.setOnClickListener(v -> confirmDelete());
    }

    private void setAudienceChip(String audience) {
        Chip chipEveryone = findViewById(R.id.chip_edit_everyone);
        Chip chipContacts = findViewById(R.id.chip_edit_contacts);
        if ("contacts".equals(audience)) {
            if (chipContacts != null) chipContacts.setChecked(true);
        } else {
            if (chipEveryone != null) chipEveryone.setChecked(true);
        }
    }

    private void saveChanges() {
        String newCaption = etCaption.getText() != null
            ? etCaption.getText().toString().trim() : "";

        int checkedId = chipAudience.getCheckedChipId();
        String newAudience = (checkedId == R.id.chip_edit_contacts) ? "contacts" : "everyone";

        progressBar.setVisibility(View.VISIBLE);
        btnSave.setEnabled(false);

        Map<String, Object> updates = new HashMap<>();
        updates.put("caption",      newCaption);
        updates.put("audienceType", newAudience);

        FirebaseUtils.getReelsRef().child(reelId).updateChildren(updates)
            .addOnSuccessListener(unused -> {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(this, "Reel updated!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            })
            .addOnFailureListener(e -> {
                progressBar.setVisibility(View.GONE);
                btnSave.setEnabled(true);
                Toast.makeText(this, "Update failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            });
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
            .setTitle("Delete Reel")
            .setMessage("This reel will be permanently deleted and removed from all feeds. This action cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> deleteReel())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteReel() {
        progressBar.setVisibility(View.VISIBLE);
        btnDelete.setEnabled(false);
        btnSave.setEnabled(false);

        FirebaseUtils.getReelsRef().child(reelId).removeValue()
            .addOnSuccessListener(u1 -> {
                FirebaseUtils.getReelsByUserRef(myUid).child(reelId).removeValue();
                FirebaseUtils.getReelLikesRef(reelId).removeValue();
                FirebaseUtils.getReelCommentsRef(reelId).removeValue();
                FirebaseUtils.getReelViewsRef(reelId).removeValue();
                FirebaseUtils.getReelReactionsRef(reelId).removeValue();
                progressBar.setVisibility(View.GONE);
                Toast.makeText(this, "Reel deleted", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            })
            .addOnFailureListener(e -> {
                progressBar.setVisibility(View.GONE);
                btnDelete.setEnabled(true);
                btnSave.setEnabled(true);
                Toast.makeText(this, "Delete failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            });
    }
}
