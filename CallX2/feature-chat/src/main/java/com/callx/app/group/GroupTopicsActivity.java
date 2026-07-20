package com.callx.app.group;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.models.GroupTopic;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GroupTopicsActivity — Telegram-style Topics / Threads list for a group.
 *
 * Features:
 *  - List all topics (pinned first, then alphabetical by name)
 *  - Real-time Firebase listener (live unread badge updates)
 *  - Tap topic → opens GroupTopicChatActivity filtered to that topic
 *  - Long-press topic (admin) → Edit / Delete / Pin / Close options
 *  - FAB (admin only) → CreateTopicActivity
 *  - Empty state with prompt to create first topic
 */
public class GroupTopicsActivity extends AppCompatActivity implements GroupTopicAdapter.Listener {

    public static final String EXTRA_GROUP_ID   = "groupId";
    public static final String EXTRA_GROUP_NAME = "groupName";

    private String groupId, groupName, currentUid;
    private boolean isAdmin = false;

    private RecyclerView rv;
    private GroupTopicAdapter adapter;
    private FloatingActionButton fab;
    private View emptyState;
    private ValueEventListener topicsListener;
    private ActivityResultLauncher<Intent> createTopicLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_topics);

        groupId   = getIntent().getStringExtra(EXTRA_GROUP_ID);
        groupName = getIntent().getStringExtra(EXTRA_GROUP_NAME);

        if (groupId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(groupName != null ? groupName + " · Topics" : "Topics");
        }

        rv         = findViewById(R.id.rv_topics);
        fab        = findViewById(R.id.fab_create_topic);
        emptyState = findViewById(R.id.empty_state);

        adapter = new GroupTopicAdapter(this, currentUid);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        createTopicLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> { /* listener handles refresh automatically */ });

        checkAdminStatus();
        listenToTopics();
    }

    private void checkAdminStatus() {
        FirebaseUtils.getGroupsRef().child(groupId).child("admins").child(currentUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        isAdmin = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                        fab.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        fab.setOnClickListener(v -> openCreateTopic(null));
    }

    private void listenToTopics() {
        DatabaseReference topicsRef = FirebaseUtils.getGroupsRef().child(groupId).child("topics");
        topicsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<GroupTopic> topics = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    try {
                        GroupTopic t = child.getValue(GroupTopic.class);
                        if (t != null && !t.deleted) {
                            if (t.id == null) t.id = child.getKey();
                            topics.add(t);
                        }
                    } catch (Exception ignored) {}
                }
                // Sort: pinned first, then by lastMessageAt desc
                Collections.sort(topics, (a, b) -> {
                    if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                    return Long.compare(b.lastMessageAt, a.lastMessageAt);
                });
                adapter.submitList(new ArrayList<>(topics));
                emptyState.setVisibility(topics.isEmpty() ? View.VISIBLE : View.GONE);
                rv.setVisibility(topics.isEmpty() ? View.GONE : View.VISIBLE);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        topicsRef.addValueEventListener(topicsListener);
    }

    // ── GroupTopicAdapter.Listener ────────────────────────────────────────
    @Override
    public void onTopicClick(GroupTopic topic) {
        // Mark unread as 0
        if (topic.unread != null && topic.unread.containsKey(currentUid)) {
            FirebaseUtils.getGroupsRef().child(groupId).child("topics")
                    .child(topic.id).child("unread").child(currentUid).setValue(0);
        }
        Intent i = new Intent(this, GroupTopicChatActivity.class);
        i.putExtra(GroupTopicChatActivity.EXTRA_GROUP_ID,   groupId);
        i.putExtra(GroupTopicChatActivity.EXTRA_TOPIC_ID,   topic.id);
        i.putExtra(GroupTopicChatActivity.EXTRA_TOPIC_NAME, topic.name);
        i.putExtra(GroupTopicChatActivity.EXTRA_TOPIC_EMOJI,topic.emoji);
        i.putExtra(GroupTopicChatActivity.EXTRA_TOPIC_CLOSED, topic.closed);
        startActivity(i);
    }

    @Override
    public void onTopicLongClick(GroupTopic topic) {
        if (!isAdmin) return;
        String[] options = {"Edit Topic", topic.pinned ? "Unpin Topic" : "Pin Topic",
                topic.closed ? "Open Topic" : "Close Topic", "Delete Topic"};
        new AlertDialog.Builder(this)
                .setTitle(topic.name)
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: openCreateTopic(topic.id);   break;
                        case 1: togglePin(topic);            break;
                        case 2: toggleClose(topic);          break;
                        case 3: confirmDelete(topic);        break;
                    }
                }).show();
    }

    private void openCreateTopic(String editTopicId) {
        Intent i = new Intent(this, CreateTopicActivity.class);
        i.putExtra(CreateTopicActivity.EXTRA_GROUP_ID, groupId);
        if (editTopicId != null) i.putExtra(CreateTopicActivity.EXTRA_TOPIC_ID, editTopicId);
        createTopicLauncher.launch(i);
    }

    private void togglePin(GroupTopic t) {
        FirebaseUtils.getGroupsRef().child(groupId).child("topics")
                .child(t.id).child("pinned").setValue(!t.pinned);
    }

    private void toggleClose(GroupTopic t) {
        FirebaseUtils.getGroupsRef().child(groupId).child("topics")
                .child(t.id).child("closed").setValue(!t.closed);
        Toast.makeText(this, t.closed ? "Topic opened" : "Topic closed — only admins can post", Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(GroupTopic t) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Topic")
                .setMessage("Delete \"" + t.name + "\"? Messages in this topic will be removed.")
                .setPositiveButton("Delete", (d, w) -> {
                    FirebaseUtils.getGroupsRef().child(groupId).child("topics")
                            .child(t.id).child("deleted").setValue(true);
                    Toast.makeText(this, "Topic deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (topicsListener != null)
            FirebaseUtils.getGroupsRef().child(groupId).child("topics")
                    .removeEventListener(topicsListener);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
