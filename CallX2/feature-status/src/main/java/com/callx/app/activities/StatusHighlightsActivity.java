package com.callx.app.activities;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusHighlightsActivity v26 — FIX: Long-press now offers Delete AND Rename.
 * Also: media playback support for video thumbnails.
 */
public class StatusHighlightsActivity extends AppCompatActivity {
    private String myUid;
    private final List<Album> albums = new ArrayList<>();
    private AlbumAdapter adapter;

    static class Album {
        String id, name, coverUrl; int itemCount; long lastUpdated;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        myUid = FirebaseUtils.getCurrentUid();
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0,0,0,0);

        // Toolbar
        androidx.appcompat.widget.Toolbar tb = new androidx.appcompat.widget.Toolbar(this);
        tb.setTitle("Highlights"); tb.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        tb.setNavigationOnClickListener(v -> finish());
        root.addView(tb);

        // RecyclerView
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new AlbumAdapter();
        rv.setAdapter(adapter); root.addView(rv);

        // Empty state
        TextView tvEmpty = new TextView(this); tvEmpty.setText("No highlights yet. Add your favourite statuses!");
        tvEmpty.setVisibility(View.GONE); tvEmpty.setGravity(android.view.Gravity.CENTER);
        tvEmpty.setPadding(40,80,40,0); root.addView(tvEmpty);

        setContentView(root);
        loadAlbums(tvEmpty);
    }

    private void loadAlbums(TextView tvEmpty) {
        StatusHighlightManager.getHighlightsRef(myUid)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    albums.clear();
                    for (DataSnapshot albumSnap : snap.getChildren()) {
                        Album a = new Album(); a.id = albumSnap.getKey(); a.itemCount = 0;
                        for (DataSnapshot item : albumSnap.getChildren()) {
                            a.itemCount++;
                            String name = item.child("highlightAlbumName").getValue(String.class);
                            if (name != null && a.name == null) a.name = name;
                            String url = item.child("mediaUrl").getValue(String.class);
                            if (a.coverUrl == null && url != null) a.coverUrl = url;
                            Long ts = item.child("timestamp").getValue(Long.class);
                            if (ts != null && ts > a.lastUpdated) a.lastUpdated = ts;
                        }
                        if (a.name == null && a.id != null) a.name = a.id;
                        if (a.name != null) albums.add(a);
                    }
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    void showAlbumOptions(Album album) {
        new AlertDialog.Builder(this)
            .setTitle(album.name)
            // FIX: Now has both Delete AND Rename options
            .setItems(new String[]{"📝 Rename album", "🗑 Delete album"}, (dialog, which) -> {
                if (which == 0) showRenameDialog(album);
                else            confirmDeleteAlbum(album);
            })
            .show();
    }

    void showRenameDialog(Album album) {
        EditText et = new EditText(this); et.setText(album.name); et.selectAll();
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        et.setPadding(pad,pad,pad,pad);
        new AlertDialog.Builder(this).setTitle("Rename Album")
            .setView(et)
            .setPositiveButton("Rename", (d, w) -> {
                String newName = et.getText().toString().trim();
                if (!newName.isEmpty()) {
                    StatusHighlightManager.renameAlbum(myUid, album.id, newName);
                    Toast.makeText(this, "Album renamed", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    void confirmDeleteAlbum(Album album) {
        new AlertDialog.Builder(this).setTitle("Delete \"" + album.name + "\"?")
            .setMessage("This will remove all " + album.itemCount + " items from this highlight.")
            .setPositiveButton("Delete", (d, w) -> {
                StatusHighlightManager.getAlbumRef(myUid, album.id).removeValue();
                Toast.makeText(this, "Album deleted", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    void openAlbum(Album album) {
        Intent i = new Intent(this, StatusViewerActivity.class);
        i.putExtra("ownerUid", myUid);
        i.putExtra("highlightAlbumId", album.id);
        i.putExtra("highlightAlbumName", album.name);
        startActivity(i);
    }

    class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.VH> {
        @Override public int getItemCount() { return albums.size(); }
        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            FrameLayout fl = new FrameLayout(p.getContext()); int sz = p.getWidth()/2;
            fl.setLayoutParams(new RecyclerView.LayoutParams(sz, sz));
            return new VH(fl);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            Album a = albums.get(pos); FrameLayout fl = (FrameLayout) h.itemView; fl.removeAllViews();
            ImageView iv = new ImageView(fl.getContext());
            iv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (a.coverUrl != null) Glide.with(fl.getContext()).load(a.coverUrl).centerCrop().into(iv);
            else iv.setBackgroundColor(android.graphics.Color.DKGRAY);
            fl.addView(iv);
            // Overlay
            LinearLayout overlay = new LinearLayout(fl.getContext()); overlay.setOrientation(LinearLayout.VERTICAL);
            overlay.setGravity(android.view.Gravity.BOTTOM); overlay.setBackgroundColor(android.graphics.Color.parseColor("#55000000"));
            overlay.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            overlay.setPadding(12,0,12,12);
            TextView tvName = new TextView(fl.getContext()); tvName.setText(a.name); tvName.setTextColor(android.graphics.Color.WHITE); tvName.setTextSize(14); tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView tvCnt  = new TextView(fl.getContext()); tvCnt.setText(a.itemCount + " items"); tvCnt.setTextColor(android.graphics.Color.LTGRAY); tvCnt.setTextSize(11);
            overlay.addView(tvName); overlay.addView(tvCnt); fl.addView(overlay);
            fl.setOnClickListener(v -> openAlbum(a));
            fl.setOnLongClickListener(v -> { showAlbumOptions(a); return true; });
        }
        class VH extends RecyclerView.ViewHolder { VH(View v){super(v);} }
    }
}
