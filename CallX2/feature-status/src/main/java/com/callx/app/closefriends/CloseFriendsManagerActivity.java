package com.callx.app.closefriends;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusCloseFriendsManager;
import com.google.firebase.database.*;
import java.util.*;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * CloseFriendsManagerActivity v27 — NEW
 *
 * Manage your Close Friends list (Instagram-style ⭐).
 * Features:
 *   ✅ Live contact search
 *   ✅ Toggle add/remove close friend with ⭐ button
 *   ✅ Shows current close friends at top (starred section)
 *   ✅ Firebase sync + local SharedPreferences cache
 *   ✅ Contact avatar + name + phone
 *   ✅ Count badge in toolbar ("X friends")
 */
public class CloseFriendsManagerActivity extends AppCompatActivity {

    private EditText    etSearch;
    private RecyclerView rvContacts;
    private TextView    tvEmpty;
    private ProgressBar progress;
    private ContactAdapter adapter;

    private final List<Contact> allContacts  = new ArrayList<>();
    private final List<Contact> filtered     = new ArrayList<>();
    private Set<String>         cfSet        = new HashSet<>();
    private String              myUid;

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { finish(); return; }

        // Build UI programmatically
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("Close Friends");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setBackgroundColor(Color.parseColor("#6200EE"));
        toolbar.setTitleTextColor(Color.WHITE);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Info banner
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setBackgroundColor(Color.parseColor("#F3E5FF"));
        banner.setPadding(dp(16), dp(12), dp(16), dp(12));
        banner.setGravity(Gravity.CENTER_VERTICAL);
        TextView bannerTv = new TextView(this);
        bannerTv.setText("⭐ Close Friends statuses are only visible to people on this list.");
        bannerTv.setTextSize(13);
        bannerTv.setTextColor(Color.parseColor("#6200EE"));
        banner.addView(bannerTv);
        root.addView(banner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Search bar
        etSearch = new EditText(this);
        etSearch.setHint("🔍  Search contacts…");
        etSearch.setSingleLine(true);
        etSearch.setBackgroundColor(Color.parseColor("#F5F5F5"));
        etSearch.setPadding(dp(16), dp(10), dp(16), dp(10));
        etSearch.setTextSize(15);
        root.addView(etSearch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Progress
        progress = new ProgressBar(this);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0f) {{
            gravity = Gravity.CENTER_HORIZONTAL;
            topMargin = dp(24);
        }});

        // Empty state
        tvEmpty = new TextView(this);
        tvEmpty.setText("No contacts found");
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setTextColor(Color.GRAY);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT) {{ topMargin = dp(24); }});

        // RecyclerView
        rvContacts = new RecyclerView(this);
        rvContacts.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactAdapter();
        rvContacts.setAdapter(adapter);
        root.addView(rvContacts, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // Search watcher
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Load data
        loadData();
    }

    // ── Data loading ────────────────────────────────────────────────────────
    private void loadData() {
        // Load close friends set from Firebase first
        StatusCloseFriendsManager.syncFromFirebase(this, myUid);
        cfSet = StatusCloseFriendsManager.getLocalList(this);

        // Then load all contacts
        FirebaseUtils.db().getReference("users")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    progress.setVisibility(View.GONE);
                    allContacts.clear();
                    for (DataSnapshot u : snap.getChildren()) {
                        String uid = u.getKey();
                        if (uid == null || uid.equals(myUid)) continue;
                        Object nameObj  = u.child("name").getValue();
                        Object photoObj = u.child("photoUrl").getValue();
                        Object phoneObj = u.child("phone").getValue();
                        String name  = nameObj  != null ? nameObj.toString()  : "Unknown";
                        String photo = photoObj != null ? photoObj.toString() : "";
                        String phone = phoneObj != null ? phoneObj.toString() : "";
                        allContacts.add(new Contact(uid, name, photo, phone));
                    }
                    // Sort: close friends first, then alphabetical
                    allContacts.sort((a, b) -> {
                        boolean aCf = cfSet.contains(a.uid);
                        boolean bCf = cfSet.contains(b.uid);
                        if (aCf != bCf) return aCf ? -1 : 1;
                        return a.name.compareToIgnoreCase(b.name);
                    });
                    applyFilter(etSearch.getText().toString());
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(CloseFriendsManagerActivity.this,
                            "Failed to load contacts", Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void applyFilter(String query) {
        filtered.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Contact c : allContacts) {
            if (q.isEmpty() || c.name.toLowerCase(Locale.ROOT).contains(q)
                    || c.phone.contains(q)) {
                filtered.add(c);
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        updateToolbarTitle();
    }

    private void updateToolbarTitle() {
        int count = 0;
        for (Contact c : allContacts) if (cfSet.contains(c.uid)) count++;
        // toolbar title update via setTitle on the first child Toolbar
        ViewGroup root = (ViewGroup) getWindow().getDecorView().getRootView();
        findToolbar(root, count);
    }

    private void findToolbar(ViewGroup vg, int count) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View v = vg.getChildAt(i);
            if (v instanceof Toolbar) {
                ((Toolbar) v).setTitle("Close Friends" + (count > 0 ? "  (" + count + ")" : ""));
                return;
            }
            if (v instanceof ViewGroup) findToolbar((ViewGroup) v, count);
        }
    }

    // ── Adapter ─────────────────────────────────────────────────────────────
    private class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new VH(buildRow(parent.getContext()));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Contact c = filtered.get(pos);
            boolean isCf = cfSet.contains(c.uid);

            h.tvName.setText(c.name);
            h.tvPhone.setText(c.phone.isEmpty() ? "" : c.phone);
            h.tvStar.setText(isCf ? "⭐" : "☆");
            h.tvStar.setTextColor(isCf
                    ? Color.parseColor("#FFB300")
                    : Color.parseColor("#CCCCCC"));
            h.tvCfLabel.setVisibility(isCf ? View.VISIBLE : View.GONE);

            if (!c.photo.isEmpty()) {
                Glide.with(CloseFriendsManagerActivity.this)
                        .load(c.photo)
                        .transform(new CircleCrop())
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(android.R.drawable.ic_menu_gallery);
            }

            h.root.setOnClickListener(v -> toggleCF(c, h));
            h.tvStar.setOnClickListener(v -> toggleCF(c, h));
        }

        @Override public int getItemCount() { return filtered.size(); }

        private void toggleCF(Contact c, VH h) {
            StatusCloseFriendsManager.toggle(
                    CloseFriendsManagerActivity.this, myUid, c.uid);
            cfSet = StatusCloseFriendsManager.getLocalList(
                    CloseFriendsManagerActivity.this);
            boolean isCf = cfSet.contains(c.uid);
            h.tvStar.setText(isCf ? "⭐" : "☆");
            h.tvStar.setTextColor(isCf
                    ? Color.parseColor("#FFB300")
                    : Color.parseColor("#CCCCCC"));
            h.tvCfLabel.setVisibility(isCf ? View.VISIBLE : View.GONE);
            Toast.makeText(CloseFriendsManagerActivity.this,
                    isCf ? c.name + " added to Close Friends ⭐"
                         : c.name + " removed from Close Friends",
                    Toast.LENGTH_SHORT).show();
            updateToolbarTitle();
        }

        class VH extends RecyclerView.ViewHolder {
            LinearLayout root;
            ImageView    ivAvatar;
            TextView     tvName, tvPhone, tvStar, tvCfLabel;
            VH(View v) {
                super(v);
                root     = (LinearLayout) v;
                ivAvatar = v.findViewWithTag("iv_avatar");
                tvName   = v.findViewWithTag("tv_name");
                tvPhone  = v.findViewWithTag("tv_phone");
                tvStar   = v.findViewWithTag("tv_star");
                tvCfLabel = v.findViewWithTag("tv_cf_label");
            }
        }

        private View buildRow(Context ctx) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackgroundResource(android.R.drawable.list_selector_background);

            ImageView avatar = new ImageView(ctx);
            avatar.setTag("iv_avatar");
            int sz = dp(46);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(sz, sz);
            ap.rightMargin = dp(14);
            row.addView(avatar, ap);

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(textCol, tcp);

            LinearLayout nameRow = new LinearLayout(ctx);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(ctx);
            tvName.setTag("tv_name");
            tvName.setTextSize(15);
            tvName.setTextColor(Color.BLACK);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            nameRow.addView(tvName, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView tvCfLabel = new TextView(ctx);
            tvCfLabel.setTag("tv_cf_label");
            tvCfLabel.setText(" ⭐ Friend");
            tvCfLabel.setTextSize(12);
            tvCfLabel.setTextColor(Color.parseColor("#FFB300"));
            tvCfLabel.setVisibility(View.GONE);
            nameRow.addView(tvCfLabel);

            textCol.addView(nameRow);

            TextView tvPhone = new TextView(ctx);
            tvPhone.setTag("tv_phone");
            tvPhone.setTextSize(13);
            tvPhone.setTextColor(Color.GRAY);
            textCol.addView(tvPhone);

            TextView tvStar = new TextView(ctx);
            tvStar.setTag("tv_star");
            tvStar.setTextSize(24);
            tvStar.setPadding(dp(8), 0, 0, 0);
            row.addView(tvStar);

            return row;
        }
    }

    // ── Contact model ────────────────────────────────────────────────────────
    static class Contact {
        final String uid, name, photo, phone;
        Contact(String uid, String name, String photo, String phone) {
            this.uid = uid; this.name = name;
            this.photo = photo; this.phone = phone;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
