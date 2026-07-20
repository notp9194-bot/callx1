package com.callx.app.group;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.chat.R;
import com.callx.app.models.GroupTopic;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.Arrays;
import java.util.List;

/**
 * CreateTopicActivity — Create or edit a Group Topic / Thread.
 *
 * Admin-only activity. Launched from GroupTopicsActivity's FAB.
 *
 * Features:
 *  - Topic name (required)
 *  - Emoji icon picker (tap any emoji chip)
 *  - Description (optional)
 *  - Close topic toggle (admins only can post)
 *  - Pin topic toggle (always shown at top)
 *  - Edit mode: pre-fills existing topic fields
 */
public class CreateTopicActivity extends AppCompatActivity {

    public static final String EXTRA_GROUP_ID  = "groupId";
    public static final String EXTRA_TOPIC_ID  = "topicId";   // null → create mode

    private static final List<String> EMOJI_CHIPS = Arrays.asList(
            "💬", "📢", "🎮", "📷", "🎵", "📚", "💡", "🏅",
            "🛠️", "🎉", "🌍", "❓", "📋", "🔥", "🤝", "👏"
    );

    private String groupId, topicId, currentUid;
    private boolean editMode = false;

    private EditText etName, etDesc;
    private TextView tvSelectedEmoji;
    private SwitchCompat swClosed, swPinned;
    private MaterialButton btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_topic);

        groupId   = getIntent().getStringExtra(EXTRA_GROUP_ID);
        topicId   = getIntent().getStringExtra(EXTRA_TOPIC_ID);
        editMode  = topicId != null;

        if (groupId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(editMode ? "Edit Topic" : "New Topic");
        }

        etName          = findViewById(R.id.et_topic_name);
        etDesc          = findViewById(R.id.et_topic_desc);
        tvSelectedEmoji = findViewById(R.id.tv_selected_emoji);
        swClosed        = findViewById(R.id.sw_topic_closed);
        swPinned        = findViewById(R.id.sw_topic_pinned);
        btnSave         = findViewById(R.id.btn_save_topic);

        setupEmojiChips();
        if (editMode) loadExistingTopic();

        btnSave.setOnClickListener(v -> saveTopic());
    }

    private void setupEmojiChips() {
        android.widget.GridLayout grid = findViewById(R.id.grid_emoji_chips);
        for (String emoji : EMOJI_CHIPS) {
            TextView chip = new TextView(this);
            chip.setText(emoji);
            chip.setTextSize(26);
            chip.setPadding(8, 8, 8, 8);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setBackground(obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless})
                    .getDrawable(0));
            chip.setOnClickListener(v -> tvSelectedEmoji.setText(emoji));
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.setMargins(4, 4, 4, 4);
            grid.addView(chip, lp);
        }
    }

    private void loadExistingTopic() {
        FirebaseUtils.getGroupsRef()
                .child(groupId).child("topics").child(topicId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String name  = snap.child("name").getValue(String.class);
                        String emoji = snap.child("emoji").getValue(String.class);
                        String desc  = snap.child("description").getValue(String.class);
                        Boolean closed = snap.child("closed").getValue(Boolean.class);
                        Boolean pinned = snap.child("pinned").getValue(Boolean.class);
                        if (name  != null) etName.setText(name);
                        if (desc  != null) etDesc.setText(desc);
                        if (!TextUtils.isEmpty(emoji)) tvSelectedEmoji.setText(emoji);
                        if (closed != null) swClosed.setChecked(closed);
                        if (pinned != null) swPinned.setChecked(pinned);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void saveTopic() {
        String name  = etName.getText().toString().trim();
        String desc  = etDesc.getText().toString().trim();
        String emoji = tvSelectedEmoji.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            etName.setError("Topic name is required");
            return;
        }
        btnSave.setEnabled(false);

        DatabaseReference topicsRef = FirebaseUtils.getGroupsRef().child(groupId).child("topics");
        DatabaseReference ref = editMode ? topicsRef.child(topicId) : topicsRef.push();
        String id = editMode ? topicId : ref.getKey();

        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("id",          id);
        map.put("name",        name);
        map.put("emoji",       TextUtils.isEmpty(emoji) ? "💬" : emoji);
        map.put("description", desc);
        map.put("closed",      swClosed.isChecked());
        map.put("pinned",      swPinned.isChecked());
        if (!editMode) {
            map.put("createdBy", currentUid);
            map.put("createdAt", System.currentTimeMillis());
            map.put("messageCount", 0);
        }

        ref.updateChildren(map, (e, r) -> {
            btnSave.setEnabled(true);
            if (e == null) {
                Toast.makeText(this, editMode ? "Topic updated" : "Topic created", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            } else {
                Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
