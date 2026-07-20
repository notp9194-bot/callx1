package com.callx.app.community;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * v34: Community Rules / Guidelines display screen.
 *
 * - Members see numbered list of rules
 * - Owner/Admin can edit rules via ✏ menu item
 * - Rules stored under communities/{communityId}/rules (plain text, newline-separated)
 * - Empty state shows "No rules set yet"
 */
public class CommunityRulesActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";
    public static final String EXTRA_IS_ADMIN     = "isAdmin";

    private String communityId;
    private boolean isAdmin;
    private String currentRules = "";

    private LinearLayout layoutRules;
    private TextView tvEmpty;
    private View progressBar;
    private CommunityRepository repo;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_rules);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        isAdmin     = getIntent().getBooleanExtra(EXTRA_IS_ADMIN, false);
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Community Rules");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        layoutRules = findViewById(R.id.layout_rules_list);
        tvEmpty     = findViewById(R.id.tv_rules_empty);
        progressBar = findViewById(R.id.progress_rules);

        loadRules();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (isAdmin) menu.add(0, 1, 0, "Edit Rules").setIcon(android.R.drawable.ic_menu_edit)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) { openEditDialog(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void loadRules() {
        progressBar.setVisibility(View.VISIBLE);
        FirebaseDatabase.getInstance().getReference("communities").child(communityId)
                .child("rules")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        String rules = s != null ? s.getValue(String.class) : null;
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            currentRules = rules != null ? rules : "";
                            renderRules(currentRules);
                        });
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {
                        runOnUiThread(() -> progressBar.setVisibility(View.GONE));
                    }
                });
    }

    private void renderRules(String rules) {
        layoutRules.removeAllViews();
        if (rules == null || rules.trim().isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            layoutRules.setVisibility(View.GONE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        layoutRules.setVisibility(View.VISIBLE);

        String[] lines = rules.split("\n");
        int ruleNumber = 1;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Build rule card
            View card = getLayoutInflater().inflate(R.layout.item_community_rule, layoutRules, false);
            TextView tvNum  = card.findViewById(R.id.tv_rule_number);
            TextView tvText = card.findViewById(R.id.tv_rule_text);
            tvNum.setText(String.valueOf(ruleNumber));
            tvText.setText(trimmed);
            layoutRules.addView(card);
            ruleNumber++;
        }
    }

    private void openEditDialog() {
        EditText et = new EditText(this);
        et.setText(currentRules);
        et.setLines(8);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setHint("Enter rules, one per line…");
        et.setPadding(24, 16, 24, 16);

        new AlertDialog.Builder(this)
                .setTitle("Edit Community Rules")
                .setMessage("One rule per line. Members will see a numbered list.")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> saveRules(et.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveRules(String newRules) {
        FirebaseDatabase.getInstance().getReference("communities").child(communityId)
                .child("rules").setValue(newRules)
                .addOnSuccessListener(v -> {
                    currentRules = newRules;
                    runOnUiThread(() -> {
                        renderRules(newRules);
                        Toast.makeText(this, "Rules updated!", Toast.LENGTH_SHORT).show();
                    });
                })
                .addOnFailureListener(e ->
                        runOnUiThread(() -> Toast.makeText(this, "Failed: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show()));
    }
}
