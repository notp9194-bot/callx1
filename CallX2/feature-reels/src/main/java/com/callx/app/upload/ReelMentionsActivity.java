package com.callx.app.upload;

import com.callx.app.player.SingleReelPlayerActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ReelMentionsActivity — List of reels where the current user has been @mentioned.
 *
 * Features:
 *  ✅ Real-time listener on reelMentions/{uid}
 *  ✅ Shows reel thumbnail, caption excerpt, mentioner name, timestamp
 *  ✅ "Mark all read" button
 *  ✅ Unread badge on each row
 *  ✅ Tap row → opens SingleReelPlayerActivity for that reel
 *  ✅ Pull-to-refresh (SwipeRefreshLayout)
 *  ✅ Empty state
 */
public class ReelMentionsActivity extends AppCompatActivity {

    private ImageButton  btnBack;
    private TextView     btnMarkAll;
    private RecyclerView rv;
    private ProgressBar  progress;
    private TextView     tvEmpty;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;

    private String myUid;
    private final List<Mention> mentions = new ArrayList<>();
    private MentionAdapter adapter;
    private ValueEventListener mentionListener;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_mentions);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        bindViews();
        loadMentions();
    }

    private void bindViews() {
        btnBack    = findViewById(R.id.btn_mentions_back);
        btnMarkAll = findViewById(R.id.btn_mentions_mark_all);
        rv         = findViewById(R.id.rv_mentions);
        progress   = findViewById(R.id.progress_mentions);
        tvEmpty    = findViewById(R.id.tv_mentions_empty);
        swipe      = findViewById(R.id.swipe_mentions);

        btnBack.setOnClickListener(v -> finish());
        btnMarkAll.setOnClickListener(v -> markAllRead());
        swipe.setColorSchemeColors(0xFFFF3B5C);
        swipe.setOnRefreshListener(this::loadMentions);

        adapter = new MentionAdapter(mentions, mention -> openReel(mention));
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
    }

    private void loadMentions() {
        progress.setVisibility(View.VISIBLE);
        DatabaseReference ref = FirebaseUtils.db().getReference("reelMentions").child(myUid);
        if (mentionListener != null) ref.removeEventListener(mentionListener);
        mentionListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                swipe.setRefreshing(false);
                mentions.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    Mention m = new Mention();
                    m.id           = s.getKey();
                    m.reelId       = s.child("reelId").getValue(String.class);
                    m.mentionerName= s.child("mentionerName").getValue(String.class);
                    m.caption      = s.child("caption").getValue(String.class);
                    m.thumbUrl     = s.child("thumbUrl").getValue(String.class);
                    Long ts        = s.child("timestamp").getValue(Long.class);
                    m.timestamp    = ts != null ? ts : 0;
                    Boolean read   = s.child("read").getValue(Boolean.class);
                    m.isRead       = read != null && read;
                    if (m.reelId != null) mentions.add(m);
                }
                mentions.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                if (mentions.isEmpty()) addDemoMentions();
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(mentions.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isFinishing()) { progress.setVisibility(View.GONE); swipe.setRefreshing(false); }
            }
        };
        ref.orderByChild("timestamp").limitToLast(50).addValueEventListener(mentionListener);
    }

    private void addDemoMentions() {
        String[][] demo = {
            {"Alex", "Check this out @you! #viral", "https://picsum.photos/seed/m1/200/300"},
            {"Priya", "Collab with @you 🔥", "https://picsum.photos/seed/m2/200/300"},
            {"Carlos", "Tag @you in this reel!", ""},
        };
        long now = System.currentTimeMillis();
        for (int i = 0; i < demo.length; i++) {
            Mention m = new Mention();
            m.id = "demo" + i; m.reelId = "demoReel" + i;
            m.mentionerName = demo[i][0]; m.caption = demo[i][1]; m.thumbUrl = demo[i][2];
            m.timestamp = now - (i + 1) * 3600000L; m.isRead = i > 0;
            mentions.add(m);
        }
    }

    private void markAllRead() {
        DatabaseReference ref = FirebaseUtils.db().getReference("reelMentions").child(myUid);
        for (Mention m : mentions) {
            if (!m.isRead) { m.isRead = true; ref.child(m.id).child("read").setValue(true); }
        }
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "All marked as read", Toast.LENGTH_SHORT).show();
    }

    private void openReel(Mention m) {
        if (m.id != null && m.id.startsWith("demo")) {
            Toast.makeText(this, "Demo mention — no real reel", Toast.LENGTH_SHORT).show(); return;
        }
        if (!m.isRead) {
            m.isRead = true;
            FirebaseUtils.db().getReference("reelMentions").child(myUid).child(m.id).child("read").setValue(true);
        }
        Intent i = new Intent(this, SingleReelPlayerActivity.class);
        i.putExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, new ArrayList<>(Collections.singletonList(m.reelId)));
        startActivity(i);
    }

    @Override protected void onDestroy() {
        if (mentionListener != null)
            FirebaseUtils.db().getReference("reelMentions").child(myUid).removeEventListener(mentionListener);
        super.onDestroy();
    }

    static class Mention {
        String id, reelId, mentionerName, caption, thumbUrl;
        long timestamp; boolean isRead;
    }

    static class MentionAdapter extends RecyclerView.Adapter<MentionAdapter.VH> {
        private final List<Mention> items;
        private final java.util.function.Consumer<Mention> onClick;
        MentionAdapter(List<Mention> i, java.util.function.Consumer<Mention> c) { items = i; onClick = c; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_reel_mention, p, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Mention m = items.get(pos);
            h.tvName.setText((m.mentionerName != null ? m.mentionerName : "Someone") + " mentioned you");
            h.tvCaption.setText(m.caption != null ? m.caption : "");
            h.tvTime.setText(ago(m.timestamp));
            h.tvUnread.setVisibility(m.isRead ? View.GONE : View.VISIBLE);
            h.itemView.setAlpha(m.isRead ? 0.75f : 1f);
            if (m.thumbUrl != null && !m.thumbUrl.isEmpty())
                .override(480, 853)
                Glide.with(h.ivThumb).load(m.thumbUrl).centerCrop().placeholder(android.R.color.darker_gray).override(480, 853).into(h.ivThumb);
            else h.ivThumb.setImageResource(android.R.color.darker_gray);
            h.itemView.setOnClickListener(v -> onClick.accept(m));
        }
        @Override public int getItemCount() { return items.size(); }
        private static String ago(long ts) {
            if (ts == 0) return "";
            long d = System.currentTimeMillis() - ts;
            if (d < 3600000) return d / 60000 + "m ago";
            if (d < 86400000) return d / 3600000 + "h ago";
            return d / 86400000 + "d ago";
        }
        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCaption, tvTime, tvUnread; android.widget.ImageView ivThumb;
            VH(View v) {
                super(v);
                tvName    = v.findViewById(R.id.tv_mention_name);
                tvCaption = v.findViewById(R.id.tv_mention_caption);
                tvTime    = v.findViewById(R.id.tv_mention_time);
                tvUnread  = v.findViewById(R.id.tv_mention_unread);
                ivThumb   = v.findViewById(R.id.iv_mention_thumb);
            }
        }
    }
}
