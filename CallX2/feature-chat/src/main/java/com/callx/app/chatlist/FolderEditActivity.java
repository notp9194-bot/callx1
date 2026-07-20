package com.callx.app.chatlist;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.ChatFolderDao;
import com.callx.app.db.entity.ChatFolderEntity;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * FolderEditActivity — Create or edit a Telegram-style Chat Folder.
 *
 * Features:
 *  - Folder name input (max 20 chars)
 *  - Emoji icon picker (30 options in a 6-column grid)
 *  - Filter toggles: contacts / groups / unread-only / include-muted
 *  - Save (insert or update) + Delete (with confirm dialog)
 *
 * Launch with EXTRA_FOLDER_ID=-1 for a new folder, or pass an existing
 * folder id (int) to edit it.
 */
public class FolderEditActivity extends AppCompatActivity {

    public static final String EXTRA_FOLDER_ID = "folder_id";

    private static final List<String> EMOJI_OPTIONS = Arrays.asList(
        "📁", "💼", "👥", "👨‍👩‍👧", "⭐", "🔔", "🏠", "🎮", "📚", "💰",
        "🎵", "❤️", "🌟", "🚀", "💡", "🔥", "🎯", "🛒", "🏋", "✈️",
        "🌈", "🎉", "💎", "🔑", "📱", "💻", "🎓", "🏆", "🌍", "🎭"
    );

    private EditText etFolderName;
    private TextView tvSelectedEmoji;
    private CheckBox cbIncludeContacts, cbIncludeGroups, cbIncludeUnread, cbIncludeMuted;
    private Button   btnSave, btnDelete;

    private ChatFolderEntity editingFolder = null;
    private String selectedEmoji = "📁";
    private int    folderId      = -1;
    private ChatFolderDao dao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_edit);

        dao      = AppDatabase.getInstance(this).chatFolderDao();
        folderId = getIntent().getIntExtra(EXTRA_FOLDER_ID, -1);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(folderId >= 0 ? "Edit Folder" : "New Folder");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etFolderName      = findViewById(R.id.et_folder_name);
        tvSelectedEmoji   = findViewById(R.id.tv_selected_emoji);
        cbIncludeContacts = findViewById(R.id.cb_include_contacts);
        cbIncludeGroups   = findViewById(R.id.cb_include_groups);
        cbIncludeUnread   = findViewById(R.id.cb_include_unread);
        cbIncludeMuted    = findViewById(R.id.cb_include_muted);
        btnSave           = findViewById(R.id.btn_save_folder);
        btnDelete         = findViewById(R.id.btn_delete_folder);

        tvSelectedEmoji.setText(selectedEmoji);
        tvSelectedEmoji.setOnClickListener(v -> {
            // Tapping the big emoji preview scrolls RecyclerView into view —
            // same as tapping any emoji in the grid, just UX sugar.
            RecyclerView rv = findViewById(R.id.rv_emoji_picker);
            if (rv != null) rv.smoothScrollToPosition(0);
        });

        setupEmojiGrid();

        if (folderId >= 0) {
            loadFolder();
        } else {
            btnDelete.setVisibility(View.GONE);
            cbIncludeContacts.setChecked(true); // sensible default
        }

        btnSave.setOnClickListener(v -> saveFolder());
        btnDelete.setOnClickListener(v -> confirmDelete());
    }

    // ─── Emoji grid ──────────────────────────────────────────────────────────

    private void setupEmojiGrid() {
        RecyclerView rvEmoji = findViewById(R.id.rv_emoji_picker);
        if (rvEmoji == null) return;
        rvEmoji.setLayoutManager(new GridLayoutManager(this, 6));
        rvEmoji.setAdapter(new EmojiPickerAdapter(EMOJI_OPTIONS, emoji -> {
            selectedEmoji = emoji;
            tvSelectedEmoji.setText(emoji);
        }));
    }

    // ─── Load existing folder ────────────────────────────────────────────────

    private void loadFolder() {
        Executors.newSingleThreadExecutor().execute(() -> {
            editingFolder = dao.getFolder(folderId);
            if (editingFolder == null) { finish(); return; }
            runOnUiThread(() -> {
                etFolderName.setText(editingFolder.name);
                if (editingFolder.emoji != null) {
                    selectedEmoji = editingFolder.emoji;
                    tvSelectedEmoji.setText(selectedEmoji);
                }
                cbIncludeContacts.setChecked(editingFolder.includeContacts);
                cbIncludeGroups.setChecked(editingFolder.includeGroups);
                cbIncludeUnread.setChecked(editingFolder.includeUnreadOnly);
                cbIncludeMuted.setChecked(editingFolder.includeMuted);
                btnDelete.setVisibility(View.VISIBLE);
            });
        });
    }

    // ─── Save ────────────────────────────────────────────────────────────────

    private void saveFolder() {
        String name = etFolderName.getText() != null
                ? etFolderName.getText().toString().trim() : "";
        if (name.isEmpty()) {
            etFolderName.setError("Folder ka naam daalein");
            return;
        }

        ChatFolderEntity folder = editingFolder != null ? editingFolder : new ChatFolderEntity();
        folder.name            = name;
        folder.emoji           = selectedEmoji;
        folder.includeContacts = cbIncludeContacts.isChecked();
        folder.includeGroups   = cbIncludeGroups.isChecked();
        folder.includeUnreadOnly = cbIncludeUnread.isChecked();
        folder.includeMuted    = cbIncludeMuted.isChecked();
        if (folder.createdAt == 0) folder.createdAt = System.currentTimeMillis();

        btnSave.setEnabled(false);
        Executors.newSingleThreadExecutor().execute(() -> {
            if (editingFolder != null) {
                dao.updateFolder(folder);
            } else {
                folder.sortOrder = dao.getFolderCount() + 1;
                dao.insertFolder(folder);
            }
            runOnUiThread(this::finish);
        });
    }

    // ─── Delete ──────────────────────────────────────────────────────────────

    private void confirmDelete() {
        if (editingFolder == null) { finish(); return; }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Folder Delete Karein?")
            .setMessage("\"" + editingFolder.name + "\" folder delete ho jayega. Chats safe rahenge.")
            .setPositiveButton("Delete", (d, w) ->
                Executors.newSingleThreadExecutor().execute(() -> {
                    dao.deleteFolder(editingFolder);
                    runOnUiThread(this::finish);
                }))
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─── Inline emoji adapter ────────────────────────────────────────────────

    private static class EmojiPickerAdapter
            extends RecyclerView.Adapter<EmojiPickerAdapter.VH> {

        interface OnEmojiSelected { void onSelected(String emoji); }

        private final List<String>   emojis;
        private final OnEmojiSelected listener;

        EmojiPickerAdapter(List<String> emojis, OnEmojiSelected listener) {
            this.emojis   = emojis;
            this.listener = listener;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setTextSize(24f);
            tv.setGravity(android.view.Gravity.CENTER);
            float dp = parent.getContext().getResources().getDisplayMetrics().density;
            int p = (int)(10 * dp);
            tv.setPadding(p, p, p, p);
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            String emoji = emojis.get(pos);
            ((TextView) h.itemView).setText(emoji);
            h.itemView.setOnClickListener(v -> listener.onSelected(emoji));
        }

        @Override public int getItemCount() { return emojis.size(); }

        static class VH extends RecyclerView.ViewHolder {
            VH(View v) { super(v); }
        }
    }
}
