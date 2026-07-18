package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * DuetInviteActivity — Dual mode:
 *
 * Mode 1 (Normal duet invite):
 *   - Loads followers, sends duet_invite to target user's node
 *
 * Mode 2 (Multi-duet participant picker — EXTRA_MULTI_DUET_MODE = true):
 *   - Preloads ALL users once from Firebase
 *   - Client-side filter: searches by "name", "nameLower", "callxId"
 *   - Same fields as SearchActivity (name / nameLower / callxId / photoUrl / thumbUrl)
 */
public class DuetInviteActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID         = "invite_reel_id";
    public static final String EXTRA_REEL_THUMB      = "invite_reel_thumb";
    public static final String EXTRA_OWNER_NAME      = "invite_owner_name";
    public static final String EXTRA_VIDEO_URL       = "invite_video_url";
    public static final String EXTRA_OWNER_UID       = "invite_owner_uid";
    public static final String EXTRA_MULTI_DUET_MODE = "multi_duet_mode";

    // Result extras (multi-duet mode)
    public static final String RESULT_USER_UID      = "result_user_uid";
    public static final String RESULT_USER_NAME     = "result_user_name";
    public static final String RESULT_USER_PHOTO    = "result_user_photo";
    public static final String RESULT_USER_USERNAME = "result_user_username";

    private EditText     etSearch;
    private RecyclerView rvUsers;
    private ProgressBar  progress;
    private TextView     tvTitle, tvEmpty, tvSearchHint;

    private String  myUid;
    private String  reelId, reelThumb, ownerName, videoUrl, ownerUid;
    private boolean isMultiDuetMode;

    // Cache for multi-duet mode
    private final List<UserItem> allUsersCache = new ArrayList<>();
    private boolean allUsersLoaded = false;

    // Normal mode list
    private final List<UserItem> allUsers     = new ArrayList<>();
    private final List<UserItem> filteredList = new ArrayList<>();
    private UserAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_invite);

        reelId          = getIntent().getStringExtra(EXTRA_REEL_ID);
        reelThumb       = getIntent().getStringExtra(EXTRA_REEL_THUMB);
        ownerName       = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        videoUrl        = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerUid        = getIntent().getStringExtra(EXTRA_OWNER_UID);
        isMultiDuetMode = getIntent().getBooleanExtra(EXTRA_MULTI_DUET_MODE, false);

        myUid = FirebaseAuth.getInstance().getUid();

        tvTitle      = findViewById(R.id.tv_invite_title);
        etSearch     = findViewById(R.id.et_invite_search);
        rvUsers      = findViewById(R.id.rv_invite_users);
        progress     = findViewById(R.id.progress_invite);
        tvEmpty      = findViewById(R.id.tv_invite_empty);
        tvSearchHint = findViewById(R.id.tv_search_hint);

        tvTitle.setText(isMultiDuetMode ? "Add Participant" : "Invite to Duet");
        if (tvSearchHint != null) {
            tvSearchHint.setVisibility(isMultiDuetMode ? View.VISIBLE : View.GONE);
            tvSearchHint.setText("Type a name or CallX ID to search");
        }

        rvUsers.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UserAdapter(filteredList, this::onUserSelected);
        rvUsers.setAdapter(adapter);

        findViewById(R.id.btn_invite_back).setOnClickListener(v -> finish());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (isMultiDuetMode) {
                    filterCachedUsers(s.toString().trim());
                } else {
                    filter(s.toString());
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        if (isMultiDuetMode) {
            showEmpty(true);
            tvEmpty.setText("Type a name or CallX ID to search");
            preloadAllUsers();
        } else {
            loadFollowers();
        }
    }

    // ── Multi-duet: preload all users once ───────────────────────────────────

    private void preloadAllUsers() {
        progress.setVisibility(View.VISIBLE);
        FirebaseUtils.db().getReference("users")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    allUsersCache.clear();
                    for (DataSnapshot ds : snap.getChildren()) {
                        String uid = ds.getKey();
                        if (uid == null || uid.equals(myUid)) continue;

                        // Same field names as SearchActivity
                        String name    = ds.child("name").getValue(String.class);
                        String photo   = ds.child("photoUrl").getValue(String.class);
                        String thumb   = ds.child("thumbUrl").getValue(String.class);
                        String callxId = ds.child("callxId").getValue(String.class);

                        if (name != null && !name.isEmpty()) {
                            allUsersCache.add(new UserItem(
                                uid,
                                name,
                                callxId != null ? callxId : "",
                                photo   != null ? photo   : "",
                                thumb   != null ? thumb   : ""
                            ));
                        }
                    }
                    allUsersLoaded = true;
                    progress.setVisibility(View.GONE);

                    // User might have already typed while loading
                    String q = etSearch.getText().toString().trim();
                    if (!q.isEmpty()) filterCachedUsers(q);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                    tvEmpty.setText("Failed to load users. Try again.");
                    showEmpty(true);
                }
            });
    }

    private void filterCachedUsers(String query) {
        if (!allUsersLoaded) {
            tvEmpty.setText("Loading users...");
            showEmpty(true);
            return;
        }
        if (query.isEmpty()) {
            filteredList.clear();
            adapter.notifyDataSetChanged();
            tvEmpty.setText("Type a name or CallX ID to search");
            showEmpty(true);
            return;
        }

        String q = query.toLowerCase(Locale.getDefault());
        filteredList.clear();

        for (UserItem u : allUsersCache) {
            boolean nameMatch   = u.name.toLowerCase(Locale.getDefault()).contains(q);
            boolean callxMatch  = u.callxId.toLowerCase(Locale.getDefault()).contains(q);
            if (nameMatch || callxMatch) {
                filteredList.add(u);
            }
        }

        // Sort: starts-with first, then contains
        filteredList.sort((a, b) -> {
            boolean aStart = a.name.toLowerCase(Locale.getDefault()).startsWith(q)
                    || a.callxId.toLowerCase(Locale.getDefault()).startsWith(q);
            boolean bStart = b.name.toLowerCase(Locale.getDefault()).startsWith(q)
                    || b.callxId.toLowerCase(Locale.getDefault()).startsWith(q);
            if (aStart && !bStart) return -1;
            if (!aStart && bStart) return 1;
            return a.name.compareToIgnoreCase(b.name);
        });

        adapter.notifyDataSetChanged();
        tvEmpty.setText("No users found for \"" + query + "\"");
        showEmpty(filteredList.isEmpty());
    }

    // ── Normal mode: load followers ───────────────────────────────────────────

    private void loadFollowers() {
        progress.setVisibility(View.VISIBLE);
        FirebaseUtils.db().getReference("followers").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) uids.add(ds.getKey());
                    if (uids.isEmpty()) {
                        progress.setVisibility(View.GONE);
                        tvEmpty.setText("No followers found");
                        showEmpty(true);
                        return;
                    }
                    loadUserDetails(uids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                    showEmpty(true);
                }
            });
    }

    private void loadUserDetails(List<String> uids) {
        final int[] loaded = {0};
        for (String uid : uids) {
            FirebaseUtils.db().getReference("users").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot ds) {
                        String name    = ds.child("name").getValue(String.class);
                        String photo   = ds.child("photoUrl").getValue(String.class);
                        String thumb   = ds.child("thumbUrl").getValue(String.class);
                        String callxId = ds.child("callxId").getValue(String.class);
                        if (name != null) {
                            allUsers.add(new UserItem(
                                uid,
                                name,
                                callxId != null ? callxId : "",
                                photo   != null ? photo   : "",
                                thumb   != null ? thumb   : ""
                            ));
                        }
                        loaded[0]++;
                        if (loaded[0] == uids.size()) {
                            allUsers.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                            filteredList.addAll(allUsers);
                            adapter.notifyDataSetChanged();
                            progress.setVisibility(View.GONE);
                            showEmpty(filteredList.isEmpty());
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        loaded[0]++;
                        if (loaded[0] == uids.size()) progress.setVisibility(View.GONE);
                    }
                });
        }
    }

    private void filter(String query) {
        filteredList.clear();
        String q = query.toLowerCase(Locale.getDefault()).trim();
        for (UserItem u : allUsers) {
            if (q.isEmpty()
                    || u.name.toLowerCase(Locale.getDefault()).contains(q)
                    || u.callxId.toLowerCase(Locale.getDefault()).contains(q)) {
                filteredList.add(u);
            }
        }
        adapter.notifyDataSetChanged();
        showEmpty(filteredList.isEmpty());
    }

    // ── User selected ─────────────────────────────────────────────────────────

    private void onUserSelected(UserItem user) {
        if (isMultiDuetMode) {
            Intent result = new Intent();
            result.putExtra(RESULT_USER_UID,      user.uid);
            result.putExtra(RESULT_USER_NAME,     user.name);
            result.putExtra(RESULT_USER_PHOTO,    user.photoUrl);
            result.putExtra(RESULT_USER_USERNAME, user.callxId); // callxId as username
            setResult(RESULT_OK, result);
            finish();
        } else {
            sendInvite(user);
        }
    }

    private void sendInvite(UserItem user) {
        if (reelId == null || myUid == null) return;
        FirebaseUtils.db().getReference("users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot ds) {
                    String fromName  = ds.child("name").getValue(String.class);
                    String fromPhoto = ds.child("photoUrl").getValue(String.class);
                    if (fromName == null) fromName = "Someone";

                    Map<String, Object> invite = new HashMap<>();
                    invite.put("fromUid",   myUid);
                    invite.put("fromName",  fromName);
                    invite.put("fromPhoto", fromPhoto != null ? fromPhoto : "");
                    invite.put("reelId",    reelId);
                    invite.put("reelThumb", reelThumb != null ? reelThumb : "");
                    invite.put("videoUrl",  videoUrl  != null ? videoUrl  : "");
                    invite.put("ownerUid",  ownerUid  != null ? ownerUid  : "");
                    invite.put("ownerName", ownerName != null ? ownerName : "");
                    invite.put("sentAt",    com.google.firebase.database.ServerValue.TIMESTAMP);
                    invite.put("status",    "pending");

                    String inviteKey = FirebaseUtils.db().getReference("duet_invites")
                        .child(user.uid).push().getKey();
                    if (inviteKey == null) return;

                    FirebaseUtils.db().getReference("duet_invites")
                        .child(user.uid).child(inviteKey).setValue(invite)
                        .addOnSuccessListener(v -> {
                            Toast.makeText(DuetInviteActivity.this,
                                "Invite sent to " + user.name + " ✅", Toast.LENGTH_SHORT).show();
                            finish();
                        })
                        .addOnFailureListener(e ->
                            Toast.makeText(DuetInviteActivity.this,
                                "Failed to send invite", Toast.LENGTH_SHORT).show());
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void showEmpty(boolean show) {
        tvEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        rvUsers.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    // ── Data model ────────────────────────────────────────────────────────────
    static class UserItem {
        String uid, name, callxId, photoUrl, thumbUrl;
        UserItem(String uid, String name, String callxId, String photoUrl, String thumbUrl) {
            this.uid = uid; this.name = name; this.callxId = callxId;
            this.photoUrl = photoUrl; this.thumbUrl = thumbUrl;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    static class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {
        interface OnSelect { void onSelect(UserItem user); }
        private final List<UserItem> items;
        private final OnSelect listener;
        UserAdapter(List<UserItem> items, OnSelect listener) {
            this.items = items; this.listener = listener;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_duet_invite_user, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            UserItem u = items.get(pos);
            h.tvName.setText(u.name);
            h.tvUsername.setText(u.callxId.isEmpty() ? "" : "@" + u.callxId);
            String avatar = (!u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            if (!avatar.isEmpty()) {
                Glide.with(h.ivAvatar).load(avatar).circleCrop().override(96, 96).into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }
            h.btnInvite.setOnClickListener(v -> listener.onSelect(u));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar; TextView tvName, tvUsername; Button btnInvite;
            VH(View v) {
                super(v);
                ivAvatar   = v.findViewById(R.id.iv_invite_avatar);
                tvName     = v.findViewById(R.id.tv_invite_name);
                tvUsername = v.findViewById(R.id.tv_invite_username);
                btnInvite  = v.findViewById(R.id.btn_send_invite);
            }
        }
    }
}
