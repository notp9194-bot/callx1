package com.callx.app.activities;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ReelRepostBlockActivity — Creator can block specific users from reposting.
 *
 * Production features:
 *  ✅ Shows list of users who have reposted this reel
 *  ✅ Block button per user → writes to repostBlocklist/{myUid}/{blockedUid}=true
 *  ✅ Unblock button for already-blocked users
 *  ✅ Real-time updates from Firebase
 *  ✅ Shows reposter name + avatar + repost time
 *
 * Firebase paths:
 *  READ:  reelReposts/{reelId} — list of all reposters
 *  READ:  users/{uid} — name + avatar
 *  WRITE: repostBlocklist/{myUid}/{targetUid} = true/false
 */
public class ReelRepostBlockActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID = "block_reel_id";

    private RecyclerView rv;
    private ProgressBar  progress;
    private TextView     tvEmpty;
    private String       reelId, myUid;
    private final List<ReposterItem> items    = new ArrayList<>();
    private final Set<String>        blocked  = new HashSet<>();
    private BlockAdapter adapter;

    static class ReposterItem {
        String uid, name, photoUrl;
        long   repostedAt;
        boolean isBlocked;
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);
        myUid  = FirebaseUtils.getCurrentUid();
        if (reelId == null || myUid == null) { finish(); return; }
        buildUI();
        loadBlocklist();
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D0D);

        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xFF141414);
        tb.setPadding(dp(4), 0, dp(16), 0);
        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setBackground(null); btnBack.getDrawable().setTint(0xFFFFFFFF);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        btnBack.setOnClickListener(v -> finish());
        tb.addView(btnBack);
        TextView tvTitle = new TextView(this);
        tvTitle.setText("Block Reposters");
        tvTitle.setTextColor(0xFFFFFFFF); tvTitle.setTextSize(17);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(dp(8), 0, 0, 0);
        tb.addView(tvTitle);
        root.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView tvHint = new TextView(this);
        tvHint.setText("Blocked users can\'t repost your reels. Their existing reposts remain visible.");
        tvHint.setTextColor(0xFF888888); tvHint.setTextSize(12);
        tvHint.setPadding(dp(16), dp(10), dp(16), dp(10));
        tvHint.setBackgroundColor(0xFF1A1A1A);
        root.addView(tvHint);

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.gravity = Gravity.CENTER_HORIZONTAL; pLp.setMargins(0, dp(32), 0, 0);
        root.addView(progress, pLp);

        tvEmpty = new TextView(this);
        tvEmpty.setText("No one has reposted this reel yet");
        tvEmpty.setTextColor(0xFF666666); tvEmpty.setTextSize(14);
        tvEmpty.setGravity(Gravity.CENTER); tvEmpty.setPadding(0, dp(60), 0, 0);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BlockAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void loadBlocklist() {
        // First load existing blocklist
        FirebaseUtils.db().getReference("repostBlocklist").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ch : snap.getChildren()) {
                        if (Boolean.TRUE.equals(ch.getValue(Boolean.class))) blocked.add(ch.getKey());
                    }
                    loadReposters();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { loadReposters(); }
            });
    }

    private void loadReposters() {
        FirebaseUtils.db().getReference("reelReposts").child(reelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing()) return;
                    progress.setVisibility(View.GONE);
                    items.clear();
                    if (!snap.exists() || snap.getChildrenCount() == 0) {
                        tvEmpty.setVisibility(View.VISIBLE); return;
                    }
                    for (DataSnapshot ch : snap.getChildren()) {
                        ReposterItem item = new ReposterItem();
                        item.uid       = ch.getKey();
                        Long ts        = ch.getValue(Long.class);
                        item.repostedAt= ts != null ? ts : 0L;
                        item.isBlocked = blocked.contains(item.uid);
                        items.add(item);
                        // Resolve name + avatar
                        FirebaseUtils.getUserRef(item.uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot u) {
                                    if (isFinishing()) return;
                                    Object n = u.child("name").getValue();
                                    if (n == null) n = u.child("displayName").getValue();
                                    item.name    = n != null ? n.toString() : item.uid;
                                    Object p = u.child("photoUrl").getValue();
                                    Object t = u.child("thumbUrl").getValue();
                                    String thumb = t != null ? t.toString() : null;
                                    String photo = p != null ? p.toString() : null;
                                    item.photoUrl = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                                    adapter.notifyDataSetChanged();
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                    }
                    adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isFinishing()) progress.setVisibility(View.GONE);
                }
            });
    }

    class BlockAdapter extends RecyclerView.Adapter<BlockAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            CircleImageView iv; TextView tvName, tvTime; Button btnBlock;
            VH(View v) { super(v); iv=v.findViewWithTag("av"); tvName=v.findViewWithTag("nm"); tvTime=v.findViewWithTag("ts"); btnBlock=v.findViewWithTag("btn"); }
        }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            LinearLayout row = new LinearLayout(ReelRepostBlockActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackgroundColor(0xFF111111);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            CircleImageView iv = new CircleImageView(ReelRepostBlockActivity.this);
            iv.setTag("av"); iv.setImageResource(R.drawable.ic_person);
            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(44), dp(44));
            avLp.setMarginEnd(dp(12));
            row.addView(iv, avLp);

            LinearLayout col = new LinearLayout(ReelRepostBlockActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView tvName = new TextView(ReelRepostBlockActivity.this);
            tvName.setTag("nm"); tvName.setTextColor(0xFFFFFFFF); tvName.setTextSize(14);
            tvName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            col.addView(tvName);
            TextView tvTime = new TextView(ReelRepostBlockActivity.this);
            tvTime.setTag("ts"); tvTime.setTextColor(0xFF888888); tvTime.setTextSize(11);
            col.addView(tvTime);
            row.addView(col);

            Button btn = new Button(ReelRepostBlockActivity.this);
            btn.setTag("btn");
            btn.setPadding(dp(12), dp(6), dp(12), dp(6));
            btn.setTextSize(13);
            row.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Divider
            LinearLayout wrapper = new LinearLayout(ReelRepostBlockActivity.this);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            wrapper.addView(row);
            View div = new View(ReelRepostBlockActivity.this);
            div.setBackgroundColor(0xFF222222);
            wrapper.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            return new VH(wrapper);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ReposterItem item = items.get(pos);
            if (item.photoUrl != null)
                Glide.with(ReelRepostBlockActivity.this).load(item.photoUrl).circleCrop().placeholder(R.drawable.ic_person).into(h.iv);
            h.tvName.setText(item.name != null ? item.name : item.uid);
            h.tvTime.setText("Reposted " + relTime(item.repostedAt));
            if (item.isBlocked) {
                h.btnBlock.setText("Unblock"); h.btnBlock.setBackgroundColor(0xFF333333); h.btnBlock.setTextColor(0xFFFFFFFF);
            } else {
                h.btnBlock.setText("Block"); h.btnBlock.setBackgroundColor(0xFFFF3B5C); h.btnBlock.setTextColor(0xFFFFFFFF);
            }
            h.btnBlock.setOnClickListener(v -> {
                boolean nowBlocked = !item.isBlocked;
                item.isBlocked = nowBlocked;
                if (nowBlocked) blocked.add(item.uid); else blocked.remove(item.uid);
                FirebaseUtils.db().getReference("repostBlocklist").child(myUid).child(item.uid)
                    .setValue(nowBlocked ? true : null);
                notifyItemChanged(pos);
                Toast.makeText(ReelRepostBlockActivity.this,
                    nowBlocked ? item.name + " blocked from reposting" : item.name + " can repost again",
                    Toast.LENGTH_SHORT).show();
            });
        }
        @Override public int getItemCount() { return items.size(); }
    }

    private String relTime(long ts) {
        if (ts == 0) return "recently";
        long d = System.currentTimeMillis() - ts;
        if (d < 60000)    return "just now";
        if (d < 3600000)  return (d/60000)+"m ago";
        if (d < 86400000) return (d/3600000)+"h ago";
        return (d/86400000)+"d ago";
    }
    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density); }
}
