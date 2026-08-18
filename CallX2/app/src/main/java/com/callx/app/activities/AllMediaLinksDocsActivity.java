package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.R;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * AllMediaLinksDocsActivity
 *
 * WhatsApp jaisi "All Media" screen:
 * - Tab 1: Media  → images + videos (3-column grid)
 * - Tab 2: Links  → http/https wale text messages (list)
 * - Tab 3: Docs   → file/audio type messages (list)
 *
 * Open karo:
 *   Intent i = new Intent(this, AllMediaLinksDocsActivity.class);
 *   i.putExtra("chatId",      chatId);
 *   i.putExtra("partnerName", partnerName);
 *   i.putExtra("isGroup",     false);
 *   startActivity(i);
 */
public class AllMediaLinksDocsActivity extends AppCompatActivity {

    private static final Pattern URL_PATTERN =
        Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);

    private String chatId;
    private String partnerName;
    private boolean isGroup;

    private TabLayout   tabLayout;
    private RecyclerView recyclerView;
    private TextView    tvEmpty;

    private AppDatabase db;

    // ─── All data lists ──────────────────────────────────────────
    private final List<MessageEntity> mediaList = new ArrayList<>();
    private final List<MessageEntity> linksList  = new ArrayList<>();
    private final List<MessageEntity> docsList   = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_media_links_docs);

        chatId      = getIntent().getStringExtra("chatId");
        partnerName = getIntent().getStringExtra("partnerName");
        isGroup     = getIntent().getBooleanExtra("isGroup", false);
        if (chatId == null) { finish(); return; }

        // Toolbar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(partnerName != null ? partnerName : "Media");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tabLayout    = findViewById(R.id.tab_layout);
        recyclerView = findViewById(R.id.recycler_view);
        tvEmpty      = findViewById(R.id.tv_empty);

        db = AppDatabase.getInstance(this);

        loadData();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showTab(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    // ─── Load from Room DB ───────────────────────────────────────
    private void loadData() {
        Executors.newSingleThreadExecutor().execute(() -> {
            // Fetch all messages for this chatId from Room
            List<MessageEntity> all = db.messageDao().getMessagesPaged(chatId, 5000, 0);

            mediaList.clear();
            linksList.clear();
            docsList.clear();

            for (MessageEntity e : all) {
                if (Boolean.TRUE.equals(e.deleted)) continue;
                String type = e.type != null ? e.type : "";

                if (type.equals("image") || type.equals("video")) {
                    mediaList.add(e);
                } else if (type.equals("file") || type.equals("audio")) {
                    docsList.add(e);
                } else if (type.equals("text") && e.text != null
                           && URL_PATTERN.matcher(e.text).find()) {
                    linksList.add(e);
                }
            }

            // Also query Firebase once for freshness — Room is primary source here
            runOnUiThread(() -> showTab(0));
        });
    }

    // ─── Tab display ─────────────────────────────────────────────
    private void showTab(int position) {
        recyclerView.setAdapter(null);

        switch (position) {
            case 0: // Media
                if (mediaList.isEmpty()) {
                    showEmpty("Koi media nahi hai");
                } else {
                    hideEmpty();
                    recyclerView.setLayoutManager(new GridLayoutManager(this, 3));
                    recyclerView.setAdapter(new MediaGridAdapter(mediaList));
                }
                break;

            case 1: // Links
                if (linksList.isEmpty()) {
                    showEmpty("Koi link nahi hai");
                } else {
                    hideEmpty();
                    recyclerView.setLayoutManager(new LinearLayoutManager(this));
                    recyclerView.setAdapter(new LinksAdapter(linksList));
                }
                break;

            case 2: // Docs
                if (docsList.isEmpty()) {
                    showEmpty("Koi document nahi hai");
                } else {
                    hideEmpty();
                    recyclerView.setLayoutManager(new LinearLayoutManager(this));
                    recyclerView.setAdapter(new DocsAdapter(docsList));
                }
                break;
        }
    }

    private void showEmpty(String msg) {
        tvEmpty.setText(msg);
        tvEmpty.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }

    private void hideEmpty() {
        tvEmpty.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
    }

    // ─── Open media full screen ───────────────────────────────────
    private void openMedia(MessageEntity e) {
        Intent i = new Intent(this, MediaViewerActivity.class);
        i.putExtra("url",      e.mediaUrl);
        i.putExtra("thumbUrl", e.thumbnailUrl);
        i.putExtra("type",     e.type);
        startActivity(i);
    }

    // ─────────────────────────────────────────────────────────────
    // ADAPTER 1: Media Grid (images + videos)
    // ─────────────────────────────────────────────────────────────
    private class MediaGridAdapter extends RecyclerView.Adapter<MediaGridAdapter.VH> {
        private final List<MessageEntity> items;
        MediaGridAdapter(List<MessageEntity> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_all_media_grid, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MessageEntity e = items.get(pos);
            String url = "video".equals(e.type) && e.thumbnailUrl != null
                ? e.thumbnailUrl : e.mediaUrl;

            Glide.with(AllMediaLinksDocsActivity.this)
                .load(url)
                .centerCrop()
                .placeholder(R.drawable.ic_gallery)
                    .override(720, 720)
                .into(h.ivThumb);

            h.ivPlayIcon.setVisibility("video".equals(e.type) ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> openMedia(e));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivThumb, ivPlayIcon;
            VH(View v) {
                super(v);
                ivThumb   = v.findViewById(R.id.iv_thumb);
                ivPlayIcon = v.findViewById(R.id.iv_play_icon);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ADAPTER 2: Links List
    // ─────────────────────────────────────────────────────────────
    private class LinksAdapter extends RecyclerView.Adapter<LinksAdapter.VH> {
        private final List<MessageEntity> items;
        LinksAdapter(List<MessageEntity> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_all_link, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MessageEntity e = items.get(pos);
            // Extract first URL from text
            java.util.regex.Matcher m = URL_PATTERN.matcher(e.text != null ? e.text : "");
            String url = m.find() ? m.group() : e.text;
            h.tvUrl.setText(url);
            h.tvCaption.setText(e.text);
            h.itemView.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvUrl, tvCaption;
            VH(View v) {
                super(v);
                tvUrl     = v.findViewById(R.id.tv_link_url);
                tvCaption = v.findViewById(R.id.tv_link_caption);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ADAPTER 3: Docs List (file + audio)
    // ─────────────────────────────────────────────────────────────
    private class DocsAdapter extends RecyclerView.Adapter<DocsAdapter.VH> {
        private final List<MessageEntity> items;
        DocsAdapter(List<MessageEntity> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_all_doc, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MessageEntity e = items.get(pos);
            h.tvName.setText(e.fileName != null ? e.fileName : "File");
            String size = e.fileSize != null
                ? Formatter.formatShortFileSize(AllMediaLinksDocsActivity.this, e.fileSize)
                : "";
            h.tvSize.setText(size);

            boolean isAudio = "audio".equals(e.type);
            h.ivIcon.setImageResource(isAudio ? R.drawable.ic_audio : R.drawable.ic_file);

            h.itemView.setOnClickListener(v -> {
                if (e.mediaUrl != null) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.mediaUrl)));
                    } catch (Exception ignored) {}
                }
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView  tvName, tvSize;
            VH(View v) {
                super(v);
                ivIcon = v.findViewById(R.id.iv_doc_icon);
                tvName = v.findViewById(R.id.tv_doc_name);
                tvSize = v.findViewById(R.id.tv_doc_size);
            }
        }
    }
}
