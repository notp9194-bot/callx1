package com.callx.app.library;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelEditorActivity;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.util.List;

/**
 * ReelDraftsActivity — View, resume, and manage saved reel drafts.
 *
 * ✅ Local-first storage via LocalDraftsManager (no Firebase crash)
 * ✅ 2-column grid, 9:16 cards with local video thumbnails
 * ✅ Duration badge, age label, caption preview
 * ✅ Tap → resume draft in ReelEditorActivity (pre-fills video, trim, music, filter)
 * ✅ Swipe-left → delete with confirm dialog + undo snackbar
 * ✅ Long-press → quick-delete confirm dialog
 * ✅ Header "Delete All" option
 * ✅ Sort: newest / oldest toggle
 * ✅ Empty state with "Create Reel" CTA
 * ✅ Draft count badge in header
 */
public class ReelDraftsActivity extends AppCompatActivity
        implements ReelDraftAdapter.DraftActionListener {

    private RecyclerView     rvDrafts;
    private View             layoutEmpty;
    private TextView         tvDraftCount;
    private ImageButton      btnBack, btnSort, btnDeleteAll;

    private ReelDraftAdapter         adapter;
    private List<LocalDraftsManager.LocalDraft> drafts;
    private boolean                  sortNewest = true;  // newest first

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_drafts);

        bindViews();
        loadDrafts();
        attachSwipeToDelete();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh when returning from editor (user may have saved/deleted)
        loadDrafts();
    }

    // ─────────────────────────────────────────────────────────────────────
    private void bindViews() {
        rvDrafts      = findViewById(R.id.rv_drafts);
        layoutEmpty   = findViewById(R.id.layout_drafts_empty);
        tvDraftCount  = findViewById(R.id.tv_draft_count);
        btnBack       = findViewById(R.id.btn_drafts_back);
        btnSort       = findViewById(R.id.btn_drafts_sort);
        btnDeleteAll  = findViewById(R.id.btn_drafts_delete_all);

        if (btnBack      != null) btnBack.setOnClickListener(v -> finish());
        if (btnSort      != null) btnSort.setOnClickListener(v -> toggleSort());
        if (btnDeleteAll != null) btnDeleteAll.setOnClickListener(v -> confirmDeleteAll());

        View btnNewReel = findViewById(R.id.btn_create_reel);
        if (btnNewReel != null) {
            btnNewReel.setOnClickListener(v ->
                startActivity(new Intent(this, ReelCameraActivity.class)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private void loadDrafts() {
        drafts = LocalDraftsManager.getAll(this);

        if (!sortNewest) {
            java.util.Collections.reverse(drafts);
        }

        if (adapter == null) {
            adapter = new ReelDraftAdapter(drafts, this);
            rvDrafts.setLayoutManager(new GridLayoutManager(this, 2));
            rvDrafts.setAdapter(adapter);
        } else {
            adapter.setData(drafts);
        }

        updateUI();
    }

    private void updateUI() {
        boolean empty = (drafts == null || drafts.isEmpty());

        if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (rvDrafts    != null) rvDrafts.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (btnDeleteAll!= null) btnDeleteAll.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);

        int count = (drafts == null) ? 0 : drafts.size();
        if (tvDraftCount != null) {
            tvDraftCount.setText(count == 0 ? "No drafts"
                : count + (count == 1 ? " draft" : " drafts"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SORT
    // ─────────────────────────────────────────────────────────────────────
    private void toggleSort() {
        sortNewest = !sortNewest;
        if (btnSort != null) {
            btnSort.setImageResource(sortNewest
                ? R.drawable.ic_arrow_back     // reuse ↑ arrow as newest-first indicator
                : R.drawable.ic_arrow_forward); // reuse → arrow as oldest-first indicator
        }
        Toast.makeText(this, sortNewest ? "Newest first" : "Oldest first",
            Toast.LENGTH_SHORT).show();
        loadDrafts();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SWIPE TO DELETE
    // ─────────────────────────────────────────────────────────────────────
    private void attachSwipeToDelete() {
        ItemTouchHelper.SimpleCallback cb =
            new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

                private final ColorDrawable redBg = new ColorDrawable(0xFFFF3B5C);

                @Override public boolean onMove(@NonNull RecyclerView rv,
                        @NonNull RecyclerView.ViewHolder vh,
                        @NonNull RecyclerView.ViewHolder target) { return false; }

                @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                    int pos = vh.getAdapterPosition();
                    if (pos < 0 || pos >= drafts.size()) return;
                    LocalDraftsManager.LocalDraft swiped = drafts.get(pos);
                    confirmDeleteOne(swiped, pos);
                    // Restore item visually until confirmed
                    adapter.notifyItemChanged(pos);
                }

                @Override public void onChildDraw(@NonNull Canvas c,
                        @NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                        float dX, float dY, int state, boolean active) {
                    View v = vh.itemView;
                    redBg.setBounds(v.getRight() + (int)dX, v.getTop(), v.getRight(), v.getBottom());
                    redBg.draw(c);
                    super.onChildDraw(c, rv, vh, dX, dY, state, active);
                }
            };
        new ItemTouchHelper(cb).attachToRecyclerView(rvDrafts);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DELETE ACTIONS
    // ─────────────────────────────────────────────────────────────────────
    private void confirmDeleteOne(LocalDraftsManager.LocalDraft draft, int pos) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Draft")
            .setMessage("This draft will be permanently deleted.")
            .setPositiveButton("Delete", (d, w) -> {
                LocalDraftsManager.delete(this, draft.id);
                if (pos >= 0 && pos < drafts.size()) {
                    drafts.remove(pos);
                    adapter.notifyItemRemoved(pos);
                    updateUI();
                    Toast.makeText(this, "Draft deleted", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmDeleteAll() {
        int count = drafts == null ? 0 : drafts.size();
        if (count == 0) return;
        new AlertDialog.Builder(this)
            .setTitle("Delete All Drafts")
            .setMessage("All " + count + " drafts will be permanently deleted.")
            .setPositiveButton("Delete All", (d, w) -> {
                LocalDraftsManager.deleteAll(this);
                loadDrafts();
                Toast.makeText(this, "All drafts deleted", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ADAPTER CALLBACKS
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public void onDraftClick(LocalDraftsManager.LocalDraft draft) {
        if (draft.videoPath == null || draft.videoPath.isEmpty()) {
            Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!new java.io.File(draft.videoPath).exists()) {
            Toast.makeText(this, "Video file was deleted from device", Toast.LENGTH_SHORT).show();
            confirmDeleteOne(draft, drafts.indexOf(draft));
            return;
        }

        Intent intent = new Intent(this, ReelEditorActivity.class);
        intent.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,    draft.videoPath);
        intent.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH, true);
        intent.putExtra("draft_id",         draft.id);
        intent.putExtra("draft_caption",    draft.caption);
        intent.putExtra("draft_music_name", draft.musicName);
        intent.putExtra("draft_music_url",  draft.musicUrl);
        intent.putExtra("draft_trim_start", draft.trimStartMs);
        intent.putExtra("draft_trim_end",   draft.trimEndMs);
        intent.putExtra("draft_filter",     draft.filterName);
        intent.putExtra("draft_stickers",   draft.stickersJson);
        intent.putExtra("draft_speed",      draft.speedX);
        startActivity(intent);
    }

    @Override
    public void onDraftLongClick(LocalDraftsManager.LocalDraft draft, int position) {
        confirmDeleteOne(draft, position);
    }
}
