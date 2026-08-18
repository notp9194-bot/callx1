package com.callx.app.group;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.media.MediaThumbAdapter;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.List;

/**
 * GroupMediaViewerActivity — View all shared media (images, videos, files) in a group.
 * 
 * Shows media in a 3-column grid layout with tabs for:
 * - Photos
 * - Videos  
 * - Documents/Files
 *
 * Clicking on any media opens full-screen viewer like in 1:1 chats.
 */
public class GroupMediaViewerActivity extends AppCompatActivity {

    // Intent extras
    public static final String EXTRA_GROUP_ID   = "groupId";
    public static final String EXTRA_GROUP_NAME = "groupName";

    // Views
    private Toolbar toolbar;
    private TextView tvTitle;
    private TabLayout tabMedia;
    private RecyclerView rvMedia;
    private ImageView ivNoMedia;
    private TextView tvNoMedia;

    // State
    private String groupId, groupName;
    private String currentMediaType = "image"; // image, video, file
    private final List<String> mediaUrls = new ArrayList<>();
    private final List<String> mediaTypes = new ArrayList<>();
    
    // Adapter
    private MediaThumbAdapter mediaAdapter;
    
    // Firebase listener
    private ValueEventListener mediaListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_media_viewer);

        groupId   = getIntent().getStringExtra(EXTRA_GROUP_ID);
        groupName = getIntent().getStringExtra(EXTRA_GROUP_NAME);

        if (groupId == null) {
            finish();
            return;
        }

        bindViews();
        setupToolbar();
        setupMediaTabs();
        setupMediaRecycler();
        loadMedia("image");
    }

    @Override
    protected void onDestroy() {
        if (mediaListener != null) {
            FirebaseUtils.getGroupMessagesRef(groupId).removeEventListener(mediaListener);
        }
        super.onDestroy();
    }

    // ── View Binding ──────────────────────────────────────────────────────
    private void bindViews() {
        toolbar = findViewById(R.id.toolbar);
        tvTitle = findViewById(R.id.tv_title);
        tabMedia = findViewById(R.id.tab_media);
        rvMedia = findViewById(R.id.rv_media);
        ivNoMedia = findViewById(R.id.iv_no_media);
        tvNoMedia = findViewById(R.id.tv_no_media);
    }

    // ── Toolbar ───────────────────────────────────────────────────────────
    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("");
        }
        tvTitle.setText("Shared Media");
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    // ── Media Tabs ─────────────────────────────────────────────────────────
    private void setupMediaTabs() {
        tabMedia.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                String type = "image"; // default
                if (pos == 1) type = "video";
                else if (pos == 2) type = "file";
                
                loadMedia(type);
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    // ── Media RecyclerView ─────────────────────────────────────────────────
    private void setupMediaRecycler() {
        mediaAdapter = new MediaThumbAdapter(mediaUrls, url -> {
            // Determine media type from URL or type list
            int index = mediaUrls.indexOf(url);
            String type = "image";
            if (index >= 0 && index < mediaTypes.size()) {
                type = mediaTypes.get(index);
            }

            // Open full media viewer
            Intent i = new Intent().setClassName(this, "com.callx.app.activities.MediaViewerActivity");
            i.putExtra("mediaUrl", url);
            i.putExtra("mediaType", type);
            i.putExtra("groupId", groupId);
            startActivity(i);
        });

        GridLayoutManager glm = new GridLayoutManager(this, 3);
        rvMedia.setLayoutManager(glm);
        rvMedia.setNestedScrollingEnabled(false);
        rvMedia.setAdapter(mediaAdapter);
    }

    // ── Load Media ─────────────────────────────────────────────────────────
    private void loadMedia(String type) {
        currentMediaType = type;
        mediaUrls.clear();
        mediaTypes.clear();

        Query q;
        if ("file".equals(type)) {
            // Files: document, pdf, etc
            q = FirebaseUtils.getGroupMessagesRef(groupId)
                    .orderByChild("type")
                    .limitToLast(100);
        } else {
            q = FirebaseUtils.getGroupMessagesRef(groupId)
                    .orderByChild("type")
                    .equalTo(type)
                    .limitToLast(100);
        }

        mediaListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                mediaUrls.clear();
                mediaTypes.clear();

                for (DataSnapshot c : snap.getChildren()) {
                    String msgType = c.child("type").getValue(String.class);
                    String url     = c.child("mediaUrl").getValue(String.class);

                    // Filter by current type
                    boolean include = false;
                    if ("file".equals(type)) {
                        include = "document".equals(msgType) || "pdf".equals(msgType) || "file".equals(msgType);
                    } else {
                        include = type.equals(msgType);
                    }

                    if (include && url != null && !url.isEmpty()) {
                        mediaUrls.add(url);
                        mediaTypes.add(msgType != null ? msgType : type);
                    }
                }

                // Show empty state if no media
                if (mediaUrls.isEmpty()) {
                    rvMedia.setVisibility(View.GONE);
                    ivNoMedia.setVisibility(View.VISIBLE);
                    tvNoMedia.setVisibility(View.VISIBLE);
                    tvNoMedia.setText("No " + type + "s shared yet");
                } else {
                    rvMedia.setVisibility(View.VISIBLE);
                    ivNoMedia.setVisibility(View.GONE);
                    tvNoMedia.setVisibility(View.GONE);
                }

                if (mediaAdapter != null) {
                    mediaAdapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onCancelled(DatabaseError e) {
                rvMedia.setVisibility(View.GONE);
                ivNoMedia.setVisibility(View.VISIBLE);
                tvNoMedia.setVisibility(View.VISIBLE);
                tvNoMedia.setText("Error loading media");
            }
        };
        q.addValueEventListener(mediaListener);
    }
}
