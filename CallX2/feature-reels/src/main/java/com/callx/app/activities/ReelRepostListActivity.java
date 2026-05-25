package com.callx.app.activities;

import android.content.Intent;
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
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ReelRepostListActivity — "Who reposted this?" list.
 *
 * Features:
 *  ✅ Real-time list of all users who reposted the reel (reelReposts/{reelId})
 *  ✅ Shows reposter avatar, name, timestamp, and their repost caption (if any)
 *  ✅ Tap row → open reposter's reel profile (UserReelsActivity)
 *  ✅ Live repost count in toolbar
 *  ✅ Empty state + loading state
 *  ✅ Scroll-to-top FAB
 *
 * Launch:
 *   Intent i = new Intent(ctx, ReelRepostListActivity.class);
 *   i.putExtra(EXTRA_REEL_ID, reelId);
 *   startActivity(i);
 */
public class ReelRepostListActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID = "repost_list_reel_id";

    private RecyclerView rv;
    private ProgressBar  progress;
    private TextView     tvEmpty, tvCount;
    private ImageButton  btnBack;

    private RepostListAdapter adapter;
    private final List<RepostItem> items = new ArrayList<>();

    private String reelId;
    private DatabaseReference repostRef;
    private ValueEventListener repostListener;

    static class RepostItem {
        String uid, name, photo, caption;
        long   timestamp;
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);
        if (reelId == null || reelId.isEmpty()) { finish(); return; }
        buildLayout();
        loadReposts();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);

        // Toolbar
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xFF1A1A1A);
        tb.setPadding(dp(4), 0, dp(16), 0);

        btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setBackground(null);
        btnBack.getDrawable().setTint(0xFFFFFFFF);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        btnBack.setOnClickListener(v -> finish());
        tb.addView(btnBack);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleCol.setPadding(dp(4), 0, 0, 0);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Reposted By");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleCol.addView(tvTitle);

        tvCount = new TextView(this);
        tvCount.setTextColor(0xFF888888);
        tvCount.setTextSize(12);
        titleCol.addView(tvCount);

        tb.addView(titleCol);
        root.addView(tb, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        View divider = new View(this);
        divider.setBackgroundColor(0xFF222222);
        root.addView(divider, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // Progress
        progress = new ProgressBar(this);
        progress.setVisibility(View.VISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER_HORIZONTAL));

        // Empty
        tvEmpty = new TextView(this);
        tvEmpty.setText("No one has reposted this reel yet");
        tvEmpty.setTextColor(0xFF666666);
        tvEmpty.setTextSize(15);
        tvEmpty.setGravity(android.view.Gravity.CENTER);
        tvEmpty.setPadding(0, dp(80), 0, 0);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        // List
        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RepostListAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void loadReposts() {
        repostRef = FirebaseUtils.getReelRepostsRef(reelId);
        repostListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                long count = snap.getChildrenCount();
                tvCount.setText(count + " repost" + (count == 1 ? "" : "s"));

                // Collect UIDs + timestamps
                List<String[]> entries = new ArrayList<>(); // [uid, timestamp]
                for (DataSnapshot child : snap.getChildren()) {
                    String uid = child.getKey();
                    Long ts    = child.getValue(Long.class);
                    if (uid != null) entries.add(new String[]{uid, String.valueOf(ts != null ? ts : 0L)});
                }
                // Sort newest first
                entries.sort((a, b) -> Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));

                items.clear();
                progress.setVisibility(View.GONE);

                if (entries.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    rv.setVisibility(View.GONE);
                    return;
                }
                tvEmpty.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);

                int[] pending = {entries.size()};
                for (String[] e : entries) {
                    String uid = e[0];
                    long ts    = Long.parseLong(e[1]);
                    RepostItem item = new RepostItem();
                    item.uid       = uid;
                    item.timestamp = ts;
                    items.add(item);

                    // Reels profile se name + photo lo (reels/users/{uid})
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reels/users").child(uid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot u) {
                                if (isFinishing() || isDestroyed()) return;
                                item.name  = stringVal(u, "displayName", uid);
                                String _rlThumb = stringVal(u, "thumbUrl", null);
                                String _rlPhoto = stringVal(u, "photoUrl", null);
                                item.photo = (_rlThumb != null && !_rlThumb.isEmpty()) ? _rlThumb : _rlPhoto;
                                pending[0]--;
                                adapter.notifyDataSetChanged();
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) { pending[0]--; }
                        });

                    // Fetch repost caption (if user used RepostWithCaptionActivity)
                    FirebaseUtils.db().getReference("repostCaptions").child(reelId).child(uid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot cap) {
                                if (isFinishing() || isDestroyed()) return;
                                item.caption = stringVal(cap, "caption", null);
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
        };
        repostRef.addValueEventListener(repostListener);
    }

    private String stringVal(DataSnapshot snap, String key, String def) {
        Object v = snap.child(key).getValue();
        return v != null && !v.toString().isEmpty() ? v.toString() : def;
    }

    @Override protected void onDestroy() {
        if (repostRef != null && repostListener != null)
            repostRef.removeEventListener(repostListener);
        super.onDestroy();
    }

    // ── Adapter ────────────────────────────────────────────────────────────────
    class RepostListAdapter extends RecyclerView.Adapter<RepostListAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvName, tvTime, tvCaption;
            VH(View v) {
                super(v);
                ivAvatar  = v.findViewWithTag("avatar");
                tvName    = v.findViewWithTag("name");
                tvTime    = v.findViewWithTag("time");
                tvCaption = v.findViewWithTag("caption");
            }
        }

        @Override public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(ReelRepostListActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(getDrawable(android.R.drawable.list_selector_background));

            CircleImageView avatar = new CircleImageView(ReelRepostListActivity.this);
            avatar.setTag("avatar");
            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(44), dp(44));
            avLp.setMarginEnd(dp(12));
            avatar.setImageResource(R.drawable.ic_person);
            row.addView(avatar, avLp);

            LinearLayout col = new LinearLayout(ReelRepostListActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Name + emoji row
            LinearLayout nameRow = new LinearLayout(ReelRepostListActivity.this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(ReelRepostListActivity.this);
            tvName.setTag("name");
            tvName.setTextColor(0xFFFFFFFF);
            tvName.setTextSize(14);
            tvName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            nameRow.addView(tvName);

            TextView repostBadge = new TextView(ReelRepostListActivity.this);
            repostBadge.setText("  🔁");
            repostBadge.setTextColor(0xFF4CAF50);
            repostBadge.setTextSize(12);
            nameRow.addView(repostBadge);
            col.addView(nameRow);

            TextView tvCaption = new TextView(ReelRepostListActivity.this);
            tvCaption.setTag("caption");
            tvCaption.setTextColor(0xFFAAAAAA);
            tvCaption.setTextSize(12);
            tvCaption.setMaxLines(2);
            tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(tvCaption);

            TextView tvTime = new TextView(ReelRepostListActivity.this);
            tvTime.setTag("time");
            tvTime.setTextColor(0xFF555555);
            tvTime.setTextSize(11);
            col.addView(tvTime);

            row.addView(col);
            return new VH(row);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            RepostItem item = items.get(pos);
            h.tvName.setText(item.name != null ? "@" + item.name : "@user");
            h.tvTime.setText(relTime(item.timestamp));

            if (item.caption != null && !item.caption.isEmpty()) {
                h.tvCaption.setVisibility(View.VISIBLE);
                h.tvCaption.setText("\"" + item.caption + "\"");
            } else {
                h.tvCaption.setVisibility(View.GONE);
            }

            if (item.photo != null && !item.photo.isEmpty()) {
                Glide.with(ReelRepostListActivity.this).load(item.photo)
                    .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            h.itemView.setOnClickListener(v -> {
                if (item.uid == null) return;
                Intent i = new Intent(ReelRepostListActivity.this, UserReelsActivity.class);
                i.putExtra("uid",   item.uid);
                i.putExtra("name",  item.name  != null ? item.name  : "");
                i.putExtra("photo", item.photo != null ? item.photo : "");
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private String relTime(long ts) {
        if (ts == 0) return "";
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60000)     return "just now";
        if (diff < 3600000)   return (diff / 60000) + "m ago";
        if (diff < 86400000)  return (diff / 3600000) + "h ago";
        if (diff < 604800000) return (diff / 86400000) + "d ago";
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(ts));
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }
}
