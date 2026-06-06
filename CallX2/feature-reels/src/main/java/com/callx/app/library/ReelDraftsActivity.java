package com.callx.app.library;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelEditorActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.library.ReelDraftAdapter;
import com.callx.app.models.ReelDraft;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * ReelDraftsActivity — View and resume saved reel drafts.
 *
 * Features:
 *  ✅ Loads drafts from Firebase reelDrafts/{uid}/
 *  ✅ 2-column grid with thumbnail + timestamp
 *  ✅ Tap → resumes draft in ReelEditorActivity
 *  ✅ Long-press → confirm delete dialog
 *  ✅ Empty state with CTA to create new reel
 */
public class ReelDraftsActivity extends AppCompatActivity
        implements ReelDraftAdapter.DraftActionListener {

    private RecyclerView      rvDrafts;
    private ProgressBar       progressBar;
    private View              layoutEmpty;

    private ReelDraftAdapter       adapter;
    private final List<ReelDraft>  drafts = new ArrayList<>();
    private String                 myUid;
    private ValueEventListener     draftsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_drafts);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Drafts");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        rvDrafts    = findViewById(R.id.rv_drafts);
        progressBar = findViewById(R.id.progress_drafts);
        layoutEmpty = findViewById(R.id.layout_drafts_empty);

        adapter = new ReelDraftAdapter(drafts, this);
        rvDrafts.setLayoutManager(new GridLayoutManager(this, 2));
        rvDrafts.setAdapter(adapter);

        View btnNewReel = findViewById(R.id.btn_create_reel);
        if (btnNewReel != null) {
            btnNewReel.setOnClickListener(v ->
                startActivity(new Intent(this, ReelCameraActivity.class)));
        }

        loadDrafts();
    }

    private void loadDrafts() {
        progressBar.setVisibility(View.VISIBLE);

        draftsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                drafts.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    ReelDraft draft = child.getValue(ReelDraft.class);
                    if (draft != null) {
                        draft.draftId = child.getKey();
                        drafts.add(0, draft);
                    }
                }
                adapter.notifyDataSetChanged();
                progressBar.setVisibility(View.GONE);
                layoutEmpty.setVisibility(drafts.isEmpty() ? View.VISIBLE : View.GONE);
                rvDrafts.setVisibility(drafts.isEmpty() ? View.GONE : View.VISIBLE);
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ReelDraftsActivity.this,
                    "Failed to load drafts: " + error.getMessage(),
                    Toast.LENGTH_SHORT).show();
            }
        };

        FirebaseUtils.getReelDraftsRef(myUid).addValueEventListener(draftsListener);
    }

    @Override
    public void onDraftClick(ReelDraft draft) {
        Intent intent = new Intent(this, ReelEditorActivity.class);
        intent.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,    draft.videoUri);
        intent.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH, false);
        startActivity(intent);
    }

    @Override
    public void onDraftLongClick(ReelDraft draft, int position) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Draft")
            .setMessage("This draft will be permanently deleted. Continue?")
            .setPositiveButton("Delete", (d, w) -> deleteDraft(draft, position))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteDraft(ReelDraft draft, int position) {
        FirebaseUtils.getReelDraftsRef(myUid).child(draft.draftId).removeValue()
            .addOnSuccessListener(unused -> {
                drafts.remove(position);
                adapter.notifyItemRemoved(position);
                if (drafts.isEmpty()) {
                    layoutEmpty.setVisibility(View.VISIBLE);
                    rvDrafts.setVisibility(View.GONE);
                }
            })
            .addOnFailureListener(e ->
                Toast.makeText(this, "Failed to delete: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        if (draftsListener != null && myUid != null) {
            FirebaseUtils.getReelDraftsRef(myUid).removeEventListener(draftsListener);
        }
        super.onDestroy();
    }
}
