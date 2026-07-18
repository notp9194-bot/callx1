package com.callx.app.library;

import com.callx.app.player.SingleReelPlayerActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ReelBookmarkCollectionsActivity — Organize saved reels into named collections.
 *
 * Features:
 *  ✅ List of named collections with reel count + cover thumbnail
 *  ✅ Create new collection (dialog with name input)
 *  ✅ Rename collection (long-press)
 *  ✅ Delete collection (with confirmation)
 *  ✅ Tap collection → opens its reels in SingleReelPlayerActivity
 *  ✅ "All Saved" default collection always shown at top
 *  ✅ Persisted at users/{uid}/bookmarkCollections/{collId}
 */
public class ReelBookmarkCollectionsActivity extends AppCompatActivity {

    private ImageButton    btnBack;
    private TextView       btnCreate;
    private RecyclerView   rv;
    private ProgressBar    progress;
    private TextView       tvEmpty;

    private final List<Collection> collections = new ArrayList<>();
    private CollectionAdapter adapter;
    private String myUid;
    private DatabaseReference collRef;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_bookmark_collections);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        collRef = FirebaseUtils.getUserRef(myUid).child("bookmarkCollections");
        bindViews();
        loadCollections();
    }

    private void bindViews() {
        btnBack   = findViewById(R.id.btn_collections_back);
        btnCreate = findViewById(R.id.btn_create_collection);
        rv        = findViewById(R.id.rv_collections);
        progress  = findViewById(R.id.progress_collections);
        tvEmpty   = findViewById(R.id.tv_collections_empty);

        btnBack.setOnClickListener(v -> finish());
        btnCreate.setOnClickListener(v -> showCreateDialog(null));

        adapter = new CollectionAdapter(collections,
            coll -> openCollection(coll),
            coll -> showCreateDialog(coll),
            coll -> deleteCollection(coll));
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
    }

    private void loadCollections() {
        progress.setVisibility(View.VISIBLE);
        collRef.addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                collections.clear();
                Collection allSaved = new Collection();
                allSaved.id = "all"; allSaved.name = "All Saved"; allSaved.isDefault = true;
                FirebaseUtils.getUserRef(myUid).child("savedReels")
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            allSaved.count = (int) s.getChildrenCount();
                            for (DataSnapshot child : s.getChildren()) {
                                String t = child.child("thumbUrl").getValue(String.class);
                                if (t != null && !t.isEmpty()) { allSaved.coverThumb = t; break; }
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
                collections.add(allSaved);
                for (DataSnapshot cs : snap.getChildren()) {
                    Collection c = new Collection();
                    c.id         = cs.getKey();
                    c.name       = cs.child("name").getValue(String.class);
                    c.coverThumb = cs.child("cover").getValue(String.class);
                    Long cnt     = cs.child("count").getValue(Long.class);
                    c.count      = cnt != null ? cnt.intValue() : 0;
                    if (c.name != null) collections.add(c);
                }
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(collections.size() <= 1 ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isFinishing()) progress.setVisibility(View.GONE);
            }
        });
    }

    private void showCreateDialog(Collection existing) {
        boolean isEdit = existing != null && !existing.isDefault;
        EditText et = new EditText(this);
        et.setHint("Collection name");
        if (isEdit) et.setText(existing.name);
        et.setPadding(48, 24, 48, 24);
        new android.app.AlertDialog.Builder(this)
            .setTitle(isEdit ? "Rename Collection" : "New Collection")
            .setView(et)
            .setPositiveButton(isEdit ? "Rename" : "Create", (d, w) -> {
                String name = et.getText() != null ? et.getText().toString().trim() : "";
                if (name.isEmpty()) { Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show(); return; }
                if (isEdit) {
                    collRef.child(existing.id).child("name").setValue(name);
                } else {
                    String id = collRef.push().getKey();
                    if (id == null) return;
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", name); m.put("count", 0); m.put("createdAt", System.currentTimeMillis());
                    collRef.child(id).setValue(m);
                }
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void deleteCollection(Collection coll) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Delete \"" + coll.name + "\"?")
            .setMessage("All saved reels in this collection will be removed from it (not deleted).")
            .setPositiveButton("Delete", (d, w) -> {
                collRef.child(coll.id).removeValue();
                Toast.makeText(this, "Collection deleted", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void openCollection(Collection coll) {
        if (coll.isDefault) {
            startActivity(new Intent(this, SavedReelsActivity.class));
        } else {
            Intent i = new Intent(this, SavedReelsActivity.class);
            i.putExtra("collection_id", coll.id);
            i.putExtra("collection_name", coll.name);
            startActivity(i);
        }
    }

    static class Collection {
        String id, name, coverThumb; int count; boolean isDefault;
    }

    interface Action<T> { void run(T t); }

    static class CollectionAdapter extends RecyclerView.Adapter<CollectionAdapter.VH> {
        private final List<Collection> items;
        private final Action<Collection> onOpen, onRename, onDelete;
        CollectionAdapter(List<Collection> i, Action<Collection> open, Action<Collection> rename, Action<Collection> delete) {
            items = i; onOpen = open; onRename = rename; onDelete = delete;
        }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_bookmark_collection, p, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Collection c = items.get(pos);
            h.tvName.setText(c.name);
            h.tvCount.setText(c.count + " reels");
            if (c.coverThumb != null && !c.coverThumb.isEmpty())
                com.bumptech.glide.Glide.with(h.ivCover).load(c.coverThumb)
                    .override(480, 853)
                    .placeholder(android.R.color.darker_gray).centerCrop().into(h.ivCover);
            h.itemView.setOnClickListener(v -> onOpen.run(c));
            h.itemView.setOnLongClickListener(v -> {
                if (!c.isDefault) {
                    String[] opts = {"Rename", "Delete"};
                    new android.app.AlertDialog.Builder(h.itemView.getContext())
                        .setItems(opts, (d, w) -> { if (w == 0) onRename.run(c); else onDelete.run(c); })
                        .show();
                }
                return true;
            });
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCount; android.widget.ImageView ivCover;
            VH(View v) { super(v); tvName = v.findViewById(R.id.tv_coll_name); tvCount = v.findViewById(R.id.tv_coll_count); ivCover = v.findViewById(R.id.iv_coll_cover); }
        }
    }
}
