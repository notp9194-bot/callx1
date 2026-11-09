package com.callx.app.social;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v790 — Screen 8: a profile's Collabs (userCollabPosts/{uid}/{reelId} = timestamp). Posts where the person is
 * the creator or an accepted co-creator. Removed collaborations drop out because the index entry is deleted
 * (see CollabControl) and the reel is re-checked for an accepted collabMap entry here.
 */
public class UserCollabsActivity extends AppCompatActivity {

    public static final String EXTRA_UID = "collabs_uid";

    private static final class Row { String reelId, thumb; long ts; }

    private final List<Row> rows = new ArrayList<>();
    private RecyclerView rv;
    private ProgressBar progress;
    private TextView tvEmpty;
    private boolean destroyed;

    /** Opens the screen for a profile. */
    public static void open(Context c, String uid) {
        c.startActivity(new Intent(c, UserCollabsActivity.class).putExtra(EXTRA_UID, uid));
    }

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_user_collabs);
        final String uid = getIntent().getStringExtra(EXTRA_UID);
        rv = findViewById(R.id.rv);
        progress = findViewById(R.id.progress);
        tvEmpty = findViewById(R.id.tv_empty);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        rv.setAdapter(new Adapter());
        if (uid == null || uid.isEmpty()) { progress.setVisibility(View.GONE); tvEmpty.setVisibility(View.VISIBLE); return; }
        load(uid);
    }

    @Override protected void onDestroy() { destroyed = true; super.onDestroy(); }

    private void load(final String uid) {
        FirebaseUtils.db().getReference("userCollabPosts").child(uid).orderByValue().limitToLast(120)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        final List<Row> idx = new ArrayList<>();
                        for (DataSnapshot c : s.getChildren()) {
                            Row r = new Row();
                            r.reelId = c.getKey();
                            Long t = c.getValue(Long.class);
                            r.ts = t == null ? 0L : t;
                            if (r.reelId != null) idx.add(r);
                        }
                        if (idx.isEmpty()) { done(idx); return; }
                        final List<Row> ok = Collections.synchronizedList(new ArrayList<Row>());
                        final int[] left = { idx.size() };
                        for (final Row r : idx) {
                            FirebaseUtils.getReelsRef().child(r.reelId).addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot rs) {
                                    if (rs.exists() && stillCollab(rs, uid)) {
                                        String th = rs.child("thumbUrl").getValue(String.class);
                                        if (th == null || th.isEmpty()) th = rs.child("thumbnailUrl").getValue(String.class);
                                        r.thumb = th == null ? "" : th;
                                        ok.add(r);
                                    }
                                    if (--left[0] == 0) done(new ArrayList<>(ok));
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    if (--left[0] == 0) done(new ArrayList<>(ok));
                                }
                            });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { done(new ArrayList<>()); }
                });
    }

    /** Creator, or a co-creator whose collabMap entry is still "accepted". */
    private static boolean stillCollab(DataSnapshot reel, String uid) {
        if (uid.equals(reel.child("uid").getValue(String.class))) return true;
        return "accepted".equals(reel.child("collabMap").child(uid).child("status").getValue(String.class));
    }

    private void done(List<Row> list) {
        if (destroyed || isFinishing()) return;
        Collections.sort(list, (a, c) -> Long.compare(c.ts, a.ts));
        rows.clear();
        rows.addAll(list);
        progress.setVisibility(View.GONE);
        ((TextView) findViewById(R.id.tv_title)).setText(getString(R.string.rc_collabs_title) + " (" + rows.size() + ")");
        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        rv.getAdapter().notifyDataSetChanged();
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_user_collab, p, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int i) {
            final Row r = rows.get(i);
            if (r.thumb != null && !r.thumb.isEmpty()) Glide.with(h.thumb).load(r.thumb).centerCrop().into(h.thumb);
            else h.thumb.setImageDrawable(null);
            h.itemView.setOnClickListener(v -> startActivity(new Intent(UserCollabsActivity.this, SingleReelPlayerActivity.class)
                    .putExtra(SingleReelPlayerActivity.EXTRA_REEL_ID, r.reelId)));
        }
        @Override public int getItemCount() { return rows.size(); }
        final class VH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            VH(@NonNull View v) { super(v); thumb = v.findViewById(R.id.iv_thumb); }
        }
    }
}
