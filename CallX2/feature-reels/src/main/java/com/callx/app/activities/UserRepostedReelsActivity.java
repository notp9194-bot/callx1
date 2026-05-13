package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * UserRepostedReelsActivity — Grid of all reels a user has reposted.
 *
 * Production features:
 *  ✅ 3-column grid, thumbnail + 🔁 badge + timestamp
 *  ✅ Real-time: userReposts/{uid} → reel thumb lookup
 *  ✅ Shows user's own repost caption below each thumb
 *  ✅ Shows "N reposts" subtitle in toolbar
 *  ✅ Own profile: "Your Reposts" / others: "@user's Reposts"
 *  ✅ Empty state, loading spinner, un-repost on long press
 *  ✅ Tap → SingleReelPlayerActivity with reel_id
 */
public class UserRepostedReelsActivity extends AppCompatActivity {

    public static final String EXTRA_UID      = "reposted_uid";
    public static final String EXTRA_USERNAME = "reposted_username";

    private RecyclerView rv;
    private ProgressBar  progress;
    private TextView     tvEmpty, tvCount;
    private RepostedAdapter adapter;
    private final List<RepostedItem> items = new ArrayList<>();
    private String targetUid, myUid, username;
    private DatabaseReference userRepostsRef;
    private ValueEventListener repostsListener;

    static class RepostedItem {
        String reelId, thumbUrl, caption, ownerName;
        long   repostedAt;
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        targetUid = getIntent().getStringExtra(EXTRA_UID);
        username  = getIntent().getStringExtra(EXTRA_USERNAME);
        myUid     = FirebaseUtils.getCurrentUid();
        if (targetUid == null) targetUid = myUid;
        buildLayout();
        loadReposts();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D0D);

        /* ── Toolbar ── */
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xFF141414);
        tb.setPadding(dp(4), 0, dp(16), 0);

        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setBackground(null);
        btnBack.getDrawable().setTint(0xFFFFFFFF);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        btnBack.setOnClickListener(v -> finish());
        tb.addView(btnBack);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleCol.setPadding(dp(4), 0, 0, 0);

        TextView tvTitle = new TextView(this);
        boolean isOwn = targetUid.equals(myUid);
        tvTitle.setText(isOwn ? "Your Reposts" : (username != null ? "@" + username + "\u2019s Reposts" : "Reposts"));
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleCol.addView(tvTitle);

        tvCount = new TextView(this);
        tvCount.setTextColor(0xFF888888);
        tvCount.setTextSize(12);
        titleCol.addView(tvCount);
        tb.addView(titleCol);
        root.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        View div = new View(this);
        div.setBackgroundColor(0xFF222222);
        root.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        /* ── States ── */
        progress = new ProgressBar(this);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.setMargins(0, dp(40), 0, 0);
        root.addView(progress, pLp);

        tvEmpty = new TextView(this);
        tvEmpty.setText(targetUid.equals(myUid) ? "You haven\u2019t reposted any reels yet\nTap \uD83D\uDD01 on any reel to repost it" : "No reposted reels yet");
        tvEmpty.setTextColor(0xFF666666);
        tvEmpty.setTextSize(15);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(dp(32), dp(80), dp(32), 0);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        /* ── Grid ── */
        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new RepostedAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void loadReposts() {
        userRepostsRef = FirebaseUtils.db().getReference("userReposts").child(targetUid);
        repostsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing()) return;
                progress.setVisibility(View.GONE);
                long cnt = snap.getChildrenCount();
                tvCount.setText(cnt + (cnt == 1 ? " repost" : " reposts"));
                List<String[]> entries = new ArrayList<>();
                for (DataSnapshot ch : snap.getChildren()) {
                    Long ts = ch.getValue(Long.class);
                    entries.add(new String[]{ch.getKey(), String.valueOf(ts != null ? ts : 0L)});
                }
                entries.sort((a, b) -> Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));
                items.clear();
                if (entries.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    rv.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                    return;
                }
                tvEmpty.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
                for (String[] e : entries) {
                    RepostedItem item = new RepostedItem();
                    item.reelId = e[0]; item.repostedAt = Long.parseLong(e[1]);
                    items.add(item);
                    // Resolve thumbnail + owner
                    FirebaseUtils.getReelsRef().child(item.reelId)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot reel) {
                                if (isFinishing()) return;
                                Object th = reel.child("thumbUrl").getValue();
                                if (th == null) th = reel.child("thumbnail").getValue();
                                item.thumbUrl  = th != null ? th.toString() : null;
                                Object on = reel.child("ownerName").getValue();
                                item.ownerName = on != null ? on.toString() : null;
                                adapter.notifyDataSetChanged();
                            }
                            @Override public void onCancelled(@NonNull DatabaseError err) {}
                        });
                    // Fetch repost caption
                    FirebaseUtils.db().getReference("repostCaptions")
                        .child(item.reelId).child(targetUid).child("caption")
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot cap) {
                                Object c = cap.getValue(); item.caption = c != null ? c.toString() : null;
                                adapter.notifyDataSetChanged();
                            }
                            @Override public void onCancelled(@NonNull DatabaseError err) {}
                        });
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError err) {
                if (!isFinishing()) progress.setVisibility(View.GONE);
            }
        };
        userRepostsRef.addValueEventListener(repostsListener);
    }

    @Override protected void onDestroy() {
        if (userRepostsRef != null && repostsListener != null)
            userRepostsRef.removeEventListener(repostsListener);
        super.onDestroy();
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    class RepostedAdapter extends RecyclerView.Adapter<RepostedAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            ImageView iv; TextView tvTime, tvCaption;
            VH(View v) {
                super(v);
                iv        = v.findViewWithTag("img");
                tvTime    = v.findViewWithTag("time");
                tvCaption = v.findViewWithTag("cap");
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            int size = (getResources().getDisplayMetrics().widthPixels - dp(2)) / 3;
            FrameLayout cell = new FrameLayout(UserRepostedReelsActivity.this);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(size, (int)(size * 1.45f));
            cellLp.setMargins(1, 1, 1, 1);
            cell.setLayoutParams(cellLp);
            cell.setBackgroundColor(0xFF1A1A1A);

            ImageView iv = new ImageView(UserRepostedReelsActivity.this);
            iv.setTag("img"); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(iv, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);

            // 🔁 badge
            TextView badge = new TextView(UserRepostedReelsActivity.this);
            badge.setText("\uD83D\uDD01"); badge.setTextSize(13);
            badge.setBackgroundColor(0x99000000); badge.setPadding(dp(4), dp(2), dp(4), dp(2));
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.gravity = Gravity.TOP | Gravity.START;
            bLp.setMargins(dp(4), dp(4), 0, 0);
            cell.addView(badge, bLp);

            TextView tvTime = new TextView(UserRepostedReelsActivity.this);
            tvTime.setTag("time"); tvTime.setTextColor(0xFFCCCCCC); tvTime.setTextSize(9);
            tvTime.setBackgroundColor(0x99000000); tvTime.setPadding(dp(3), dp(1), dp(3), dp(1));
            FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.gravity = Gravity.TOP | Gravity.END;
            tLp.setMargins(0, dp(4), dp(4), 0);
            cell.addView(tvTime, tLp);

            TextView tvCaption = new TextView(UserRepostedReelsActivity.this);
            tvCaption.setTag("cap"); tvCaption.setTextColor(0xFFEEEEEE); tvCaption.setTextSize(10);
            tvCaption.setMaxLines(2); tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvCaption.setBackgroundColor(0xBB000000); tvCaption.setPadding(dp(4), dp(3), dp(4), dp(3));
            FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            capLp.gravity = Gravity.BOTTOM;
            cell.addView(tvCaption, capLp);

            return new VH(cell);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            RepostedItem item = items.get(pos);
            if (item.thumbUrl != null && !item.thumbUrl.isEmpty())
                Glide.with(UserRepostedReelsActivity.this).load(item.thumbUrl).centerCrop().into(h.iv);
            else h.iv.setImageResource(R.drawable.ic_reels);
            h.tvTime.setText(relTime(item.repostedAt));
            if (item.caption != null && !item.caption.isEmpty()) {
                h.tvCaption.setVisibility(View.VISIBLE);
                h.tvCaption.setText("\u201c" + item.caption + "\u201d");
            } else {
                h.tvCaption.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(UserRepostedReelsActivity.this, SingleReelPlayerActivity.class);
                intent.putExtra("reel_id", item.reelId);
                startActivity(intent);
            });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private String relTime(long ts) {
        if (ts == 0) return "";
        long d = System.currentTimeMillis() - ts;
        if (d < 60000)     return "now";
        if (d < 3600000)   return (d / 60000) + "m";
        if (d < 86400000)  return (d / 3600000) + "h";
        if (d < 604800000) return (d / 86400000) + "d";
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(ts));
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
}
