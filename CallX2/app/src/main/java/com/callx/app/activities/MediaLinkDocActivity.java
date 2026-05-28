package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.models.Message;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * MediaLinkDocActivity — Chat ke Media, Links aur Documents ek jagah.
 *
 * 3 tabs:
 *   📷 Media  — type=image ya type=video messages (grid view)
 *   🔗 Links  — type=text messages jisme http/https URL hai (list view)
 *   📄 Docs   — type=file messages (list view)
 *
 * Firebase se load karta hai: chats/{chatId}/messages
 * Click:
 *   Media → MediaViewerActivity
 *   Links → Browser intent
 *   Docs  → Browser intent (download)
 */
public class MediaLinkDocActivity extends AppCompatActivity {

    // ── UI ────────────────────────────────────────────────────────────────
    private Toolbar    toolbar;
    private TabLayout  tabLayout;
    private RecyclerView recyclerView;
    private TextView   tvEmpty;
    private View       progressBar;

    // ── Data ──────────────────────────────────────────────────────────────
    private String chatId;
    private String partnerName;
    private boolean isGroup;

    private final List<Message> mediaList = new ArrayList<>();
    private final List<Message> linkList  = new ArrayList<>();
    private final List<Message> docList   = new ArrayList<>();

    private int currentTab = 0; // 0=Media, 1=Links, 2=Docs

    // ── Adapters ──────────────────────────────────────────────────────────
    private MediaGridAdapter  mediaAdapter;
    private LinkListAdapter   linkAdapter;
    private DocListAdapter    docAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_link_doc);

        chatId      = getIntent().getStringExtra("chatId");
        partnerName = getIntent().getStringExtra("partnerName");
        isGroup     = getIntent().getBooleanExtra("isGroup", false);

        if (chatId == null || chatId.isEmpty() ||
                FirebaseAuth.getInstance().getCurrentUser() == null) {
            Toast.makeText(this, "Chat not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // ── Views ──────────────────────────────────────────────────────
        toolbar      = findViewById(R.id.toolbar);
        tabLayout    = findViewById(R.id.tab_layout);
        recyclerView = findViewById(R.id.recycler_view);
        tvEmpty      = findViewById(R.id.tv_empty);
        progressBar  = findViewById(R.id.progress_bar);

        // ── Toolbar ────────────────────────────────────────────────────
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(
                partnerName != null && !partnerName.isEmpty()
                    ? partnerName
                    : "Media, Links & Docs");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // ── Tabs ───────────────────────────────────────────────────────
        tabLayout.addTab(tabLayout.newTab().setText("📷 Media"));
        tabLayout.addTab(tabLayout.newTab().setText("🔗 Links"));
        tabLayout.addTab(tabLayout.newTab().setText("📄 Docs"));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                showCurrentTab();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // ── Adapters ───────────────────────────────────────────────────
        mediaAdapter = new MediaGridAdapter(mediaList);
        linkAdapter  = new LinkListAdapter(linkList);
        docAdapter   = new DocListAdapter(docList);

        // ── Load from Firebase ─────────────────────────────────────────
        showProgress(true);
        loadMessages();
    }

    // ──────────────────────────────────────────────────────────────────────
    // FIREBASE LOAD
    // ──────────────────────────────────────────────────────────────────────

    private void loadMessages() {
        String path = isGroup ? "groupChats/" + chatId + "/messages"
                               : "chats/"    + chatId + "/messages";

        DatabaseReference ref = FirebaseDatabase.getInstance().getReference(path);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                mediaList.clear();
                linkList.clear();
                docList.clear();

                for (DataSnapshot ds : snapshot.getChildren()) {
                    Message m = ds.getValue(Message.class);
                    if (m == null) continue;

                    // Skip deleted messages
                    if (Boolean.TRUE.equals(m.deleted)) continue;

                    String type = m.type != null ? m.type : "";

                    switch (type) {
                        case "image":
                        case "video":
                            if (m.mediaUrl != null && !m.mediaUrl.isEmpty())
                                mediaList.add(m);
                            break;
                        case "file":
                            if (m.mediaUrl != null && !m.mediaUrl.isEmpty())
                                docList.add(m);
                            break;
                        case "text":
                            // Extract link from text
                            if (m.text != null && containsUrl(m.text))
                                linkList.add(m);
                            break;
                    }
                }

                showProgress(false);
                showCurrentTab();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                showProgress(false);
                Toast.makeText(MediaLinkDocActivity.this,
                    "Load failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                showEmptyState("Could not load data");
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // TAB SWITCHING
    // ──────────────────────────────────────────────────────────────────────

    private void showCurrentTab() {
        switch (currentTab) {
            case 0: // Media — grid
                recyclerView.setLayoutManager(new GridLayoutManager(this, 3));
                recyclerView.setAdapter(mediaAdapter);
                mediaAdapter.notifyDataSetChanged();
                if (mediaList.isEmpty()) showEmptyState("No media shared yet");
                else showList();
                break;

            case 1: // Links — list
                recyclerView.setLayoutManager(new LinearLayoutManager(this));
                recyclerView.setAdapter(linkAdapter);
                linkAdapter.notifyDataSetChanged();
                if (linkList.isEmpty()) showEmptyState("No links shared yet");
                else showList();
                break;

            case 2: // Docs — list
                recyclerView.setLayoutManager(new LinearLayoutManager(this));
                recyclerView.setAdapter(docAdapter);
                docAdapter.notifyDataSetChanged();
                if (docList.isEmpty()) showEmptyState("No documents shared yet");
                else showList();
                break;
        }
    }

    private void showEmptyState(String msg) {
        tvEmpty.setText(msg);
        tvEmpty.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }

    private void showList() {
        tvEmpty.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ──────────────────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────────────────

    private boolean containsUrl(String text) {
        return text.contains("http://") || text.contains("https://");
    }

    private String extractUrl(String text) {
        if (text == null) return null;
        String[] words = text.split("\\s+");
        for (String w : words) {
            if (w.startsWith("http://") || w.startsWith("https://")) return w;
        }
        return null;
    }

    private String formatTime(Long ts) {
        if (ts == null || ts == 0) return "";
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        return sdf.format(new Date(ts));
    }

    private String formatSize(Long bytes) {
        if (bytes == null || bytes == 0) return "";
        if (bytes < 1024)      return bytes + " B";
        if (bytes < 1048576)   return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576f);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADAPTER: Media Grid (images + videos)
    // ══════════════════════════════════════════════════════════════════════

    private class MediaGridAdapter extends RecyclerView.Adapter<MediaGridAdapter.VH> {
        final List<Message> data;
        MediaGridAdapter(List<Message> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media_grid, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Message m = data.get(pos);
            String url = m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty()
                ? m.thumbnailUrl : m.mediaUrl;

            Glide.with(h.iv.getContext())
                .load(url)
                .centerCrop()
                .placeholder(R.drawable.ic_person)
                .into(h.iv);

            h.ivPlay.setVisibility("video".equals(m.type) ? View.VISIBLE : View.GONE);

            h.iv.setOnClickListener(v -> {
                Intent intent = new Intent(MediaLinkDocActivity.this, MediaViewerActivity.class);
                intent.putExtra("url",      m.mediaUrl);
                intent.putExtra("thumbUrl", m.thumbnailUrl);
                intent.putExtra("type",     m.type);
                startActivity(intent);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView iv, ivPlay;
            VH(View v) {
                super(v);
                iv     = v.findViewById(R.id.iv_thumb);
                ivPlay = v.findViewById(R.id.iv_play);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADAPTER: Link List
    // ══════════════════════════════════════════════════════════════════════

    private class LinkListAdapter extends RecyclerView.Adapter<LinkListAdapter.VH> {
        final List<Message> data;
        LinkListAdapter(List<Message> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_link_doc, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Message m = data.get(pos);
            String url = extractUrl(m.text);
            h.tvTitle.setText(url != null ? url : m.text);
            h.tvMeta.setText(formatTime(m.timestamp));
            h.ivIcon.setImageResource(R.drawable.ic_link);

            h.itemView.setOnClickListener(v -> {
                if (url == null) return;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(MediaLinkDocActivity.this,
                        "Cannot open link", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView  tvTitle, tvMeta;
            VH(View v) {
                super(v);
                ivIcon  = v.findViewById(R.id.iv_icon);
                tvTitle = v.findViewById(R.id.tv_title);
                tvMeta  = v.findViewById(R.id.tv_meta);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADAPTER: Doc List
    // ══════════════════════════════════════════════════════════════════════

    private class DocListAdapter extends RecyclerView.Adapter<DocListAdapter.VH> {
        final List<Message> data;
        DocListAdapter(List<Message> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_link_doc, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Message m = data.get(pos);
            String name = m.fileName != null && !m.fileName.isEmpty()
                ? m.fileName : "Document";
            h.tvTitle.setText(name);
            h.tvMeta.setText(formatTime(m.timestamp)
                + (m.fileSize != null ? "  •  " + formatSize(m.fileSize) : ""));
            h.ivIcon.setImageResource(R.drawable.ic_file);

            h.itemView.setOnClickListener(v -> {
                if (m.mediaUrl == null) return;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(m.mediaUrl)));
                } catch (Exception e) {
                    Toast.makeText(MediaLinkDocActivity.this,
                        "Cannot open file", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView  tvTitle, tvMeta;
            VH(View v) {
                super(v);
                ivIcon  = v.findViewById(R.id.iv_icon);
                tvTitle = v.findViewById(R.id.tv_title);
                tvMeta  = v.findViewById(R.id.tv_meta);
            }
        }
    }
}
