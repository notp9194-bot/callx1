package com.callx.app.starred;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.SavedMessageDao;
import com.callx.app.db.entity.SavedMessageEntity;
import de.hdodenhof.circleimageview.CircleImageView;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * GlobalSavedMessagesActivity — Telegram-style "Saved Messages" screen.
 *
 * Shows ALL messages the user has bookmarked from ANY chat, newest first.
 * Searchable. Each row shows:
 *   - Source chat name (e.g. "From: Rahul" or "From: Dev Team")
 *   - Sender avatar + name
 *   - Message text / media label
 *   - Original timestamp
 *   - Personal note (if any)
 *   - Unsave (delete) button
 *
 * Entry points:
 *   - Three-dot menu in ChatsFragment → "Saved Messages"
 *   - (Future) bottom nav shortcut
 */
public class GlobalSavedMessagesActivity extends AppCompatActivity {

    private RecyclerView rv;
    private LinearLayout emptyState;
    private TextView     tvCount;
    private EditText     etSearch;

    private SavedAdapter adapter;
    private List<SavedMessageEntity> allItems  = new ArrayList<>();
    private List<SavedMessageEntity> filtered  = new ArrayList<>();
    private SavedMessageDao dao;

    private static final SimpleDateFormat FMT_DATE =
            new SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_global_saved_messages);

        dao = AppDatabase.getInstance(this).savedMessageDao();

        // ── Toolbar ───────────────────────────────────────────────────────────
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Saved Messages");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // ── Views ─────────────────────────────────────────────────────────────
        rv         = findViewById(R.id.rv_saved);
        emptyState = findViewById(R.id.empty_state);
        tvCount    = findViewById(R.id.tv_count);
        etSearch   = findViewById(R.id.et_search);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SavedAdapter();
        rv.setAdapter(adapter);

        // ── Search ────────────────────────────────────────────────────────────
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable e) {}
        });

        loadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData(); // Refresh after returning from chat (might have saved more)
    }

    // ─── Data ─────────────────────────────────────────────────────────────────

    private void loadData() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<SavedMessageEntity> list = dao.getAllSavedSync();
            runOnUiThread(() -> {
                allItems.clear();
                allItems.addAll(list);
                String query = etSearch.getText() != null ? etSearch.getText().toString() : "";
                applyFilter(query);
            });
        });
    }

    private void applyFilter(String query) {
        filtered.clear();
        if (query == null || query.trim().isEmpty()) {
            filtered.addAll(allItems);
        } else {
            String q = query.toLowerCase(Locale.getDefault()).trim();
            for (SavedMessageEntity e : allItems) {
                if ((e.text != null && e.text.toLowerCase(Locale.getDefault()).contains(q))
                        || (e.chatName != null && e.chatName.toLowerCase(Locale.getDefault()).contains(q))
                        || (e.senderName != null && e.senderName.toLowerCase(Locale.getDefault()).contains(q))
                        || (e.note != null && e.note.toLowerCase(Locale.getDefault()).contains(q))) {
                    filtered.add(e);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState(filtered.isEmpty());
        if (!filtered.isEmpty()) {
            tvCount.setVisibility(View.VISIBLE);
            tvCount.setText(filtered.size() + " saved message" + (filtered.size() != 1 ? "s" : ""));
        } else {
            tvCount.setVisibility(View.GONE);
        }
    }

    private void updateEmptyState(boolean empty) {
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ─── Unsave ───────────────────────────────────────────────────────────────

    private void unsave(SavedMessageEntity item) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Remove from Saved?")
            .setMessage("Yeh message saved list se hata diya jayega.")
            .setPositiveButton("Remove", (d, w) -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    dao.deleteSaved(item.id);
                    runOnUiThread(() -> {
                        allItems.remove(item);
                        String query = etSearch.getText() != null ? etSearch.getText().toString() : "";
                        applyFilter(query);
                        Toast.makeText(this, "Removed from saved", Toast.LENGTH_SHORT).show();
                    });
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─── Add/edit note ────────────────────────────────────────────────────────

    private void editNote(SavedMessageEntity item) {
        EditText et = new EditText(this);
        et.setText(item.note);
        et.setHint("Personal note (optional)");
        et.setMaxLines(3);
        int p = (int)(16 * getResources().getDisplayMetrics().density);
        et.setPadding(p, p, p, p);

        new android.app.AlertDialog.Builder(this)
            .setTitle("Add Note")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String note = et.getText().toString().trim();
                Executors.newSingleThreadExecutor().execute(() -> {
                    dao.updateNote(item.id, note.isEmpty() ? null : note);
                    item.note = note.isEmpty() ? null : note;
                    runOnUiThread(adapter::notifyDataSetChanged);
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────

    private class SavedAdapter extends RecyclerView.Adapter<SavedAdapter.VH> {

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_saved_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            SavedMessageEntity item = filtered.get(position);

            // Source chat label
            String from = item.chatName != null ? item.chatName : "Unknown chat";
            h.tvFrom.setText("From: " + from);

            // Sender name
            h.tvSenderName.setText(item.senderName != null ? item.senderName : "");

            // Avatar
            if (item.senderPhoto != null && !item.senderPhoto.isEmpty()) {
                Glide.with(h.ivAvatar.getContext())
                    .load(item.senderPhoto)
                    .placeholder(R.drawable.ic_person)
                    .circleCrop()
                    .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            // Message content
            String preview = buildPreview(item);
            h.tvMessage.setText(preview);

            // Note
            if (item.note != null && !item.note.isEmpty()) {
                h.tvNote.setVisibility(View.VISIBLE);
                h.tvNote.setText("📝 " + item.note);
            } else {
                h.tvNote.setVisibility(View.GONE);
            }

            // Timestamp
            if (item.origTimestamp != null && item.origTimestamp > 0) {
                h.tvTime.setText(FMT_DATE.format(new Date(item.origTimestamp)));
            } else if (item.savedAt != null && item.savedAt > 0) {
                h.tvTime.setText("Saved " + FMT_DATE.format(new Date(item.savedAt)));
            }

            // Note button
            h.btnNote.setOnClickListener(v -> editNote(item));

            // Unsave button
            h.btnUnsave.setOnClickListener(v -> unsave(item));
        }

        private String buildPreview(SavedMessageEntity item) {
            if (item.type == null || "text".equals(item.type)) {
                return item.text != null ? item.text : "";
            }
            switch (item.type) {
                case "image":    return "📷 Photo";
                case "video":    return "📹 Video";
                case "audio":    return "🎙 Voice message";
                case "file":
                case "document": return "📎 " + (item.fileName != null ? item.fileName : "File");
                case "location": return "📍 Location";
                case "contact":  return "👤 Contact";
                case "sticker":  return "😊 Sticker";
                default:
                    return item.text != null ? item.text : item.type;
            }
        }

        @Override public int getItemCount() { return filtered.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvFrom, tvSenderName, tvMessage, tvNote, tvTime;
            ImageButton btnUnsave, btnNote;

            VH(View v) {
                super(v);
                ivAvatar    = v.findViewById(R.id.iv_avatar);
                tvFrom      = v.findViewById(R.id.tv_from);
                tvSenderName = v.findViewById(R.id.tv_sender_name);
                tvMessage   = v.findViewById(R.id.tv_message);
                tvNote      = v.findViewById(R.id.tv_note);
                tvTime      = v.findViewById(R.id.tv_time);
                btnUnsave   = v.findViewById(R.id.btn_unsave);
                btnNote     = v.findViewById(R.id.btn_note);
            }
        }
    }
}
