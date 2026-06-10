package com.callx.app.social.collab;

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
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CollabDuetInviteActivity — Pick a follower to invite for a Collab Duet.
 *
 * Flow:
 *  1. Shows paginated list of current user's followers
 *  2. User selects a partner → session created in Firebase RTDB
 *  3. FCM push sent to partner (server-side via collabDuetSessions listener)
 *  4. Opens CollabDuetSessionActivity as HOST
 */
public class CollabDuetInviteActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID       = "collab_invite_reel_id";
    public static final String EXTRA_VIDEO_URL     = "collab_invite_video_url";
    public static final String EXTRA_THUMB_URL     = "collab_invite_thumb_url";
    public static final String EXTRA_CAPTION       = "collab_invite_caption";
    public static final String EXTRA_DURATION_MS   = "collab_invite_duration_ms";

    private String reelId, videoUrl, thumbUrl, caption;
    private long   durationMs;

    private EditText         etSearch;
    private ProgressBar      progressLoad;
    private TextView         tvEmpty;
    private RecyclerView     rvFollowers;
    private FollowerAdapter  adapter;

    private final List<FollowerItem> allFollowers      = new ArrayList<>();
    private final List<FollowerItem> filteredFollowers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collab_duet_invite);

        reelId     = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl   = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        thumbUrl   = getIntent().getStringExtra(EXTRA_THUMB_URL);
        caption    = getIntent().getStringExtra(EXTRA_CAPTION);
        durationMs = getIntent().getLongExtra(EXTRA_DURATION_MS, 30_000);

        ImageButton btnBack = findViewById(R.id.btn_collab_invite_back);
        etSearch    = findViewById(R.id.et_collab_search);
        progressLoad = findViewById(R.id.progress_collab_invite);
        tvEmpty     = findViewById(R.id.tv_collab_invite_empty);
        rvFollowers = findViewById(R.id.rv_collab_followers);

        btnBack.setOnClickListener(v -> finish());
        rvFollowers.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FollowerAdapter(filteredFollowers, this::onFollowerSelected);
        rvFollowers.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadFollowers();
    }

    private void loadFollowers() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) { showEmpty(true); return; }

        progressLoad.setVisibility(View.VISIBLE);
        // reelFollowers/{myUid}/{followerUid} = true — load up to 200 followers
        FirebaseUtils.getReelFollowersRef(myUid)
            .limitToFirst(200)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    progressLoad.setVisibility(View.GONE);
                    for (DataSnapshot ds : snap.getChildren()) {
                        String followerUid = ds.getKey();
                        if (followerUid == null) continue;
                        // Fetch user info
                        FirebaseUtils.getUserRef(followerUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot u) {
                                    String name  = getString(u, "name", followerUid);
                                    String photo = getString(u, "photo", "");
                                    FollowerItem item = new FollowerItem(followerUid, name, photo);
                                    allFollowers.add(item);
                                    filterList(etSearch.getText().toString());
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                    }
                    if (snap.getChildrenCount() == 0) showEmpty(true);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progressLoad.setVisibility(View.GONE);
                    showEmpty(true);
                }
            });
    }

    private void filterList(String query) {
        filteredFollowers.clear();
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (FollowerItem item : allFollowers) {
            if (q.isEmpty() || item.name.toLowerCase(Locale.ROOT).contains(q)) {
                filteredFollowers.add(item);
            }
        }
        adapter.notifyDataSetChanged();
        showEmpty(filteredFollowers.isEmpty() && !allFollowers.isEmpty() && !q.isEmpty());
    }

    private void onFollowerSelected(FollowerItem partner) {
        String myUid  = FirebaseUtils.getCurrentUid();
        String myName = FirebaseUtils.getCurrentName();
        if (myUid == null || reelId == null) return;

        // Create session in Firebase RTDB
        DatabaseReference sessRef = FirebaseUtils.getCollabDuetSessionsRef().push();
        String sessionId = sessRef.getKey();
        if (sessionId == null) return;

        CollabDuetSession session = new CollabDuetSession();
        session.sessionId   = sessionId;
        session.status      = CollabDuetSession.STATUS_WAITING;
        session.createdAt   = System.currentTimeMillis();
        session.reelId      = reelId;
        session.reelVideoUrl  = videoUrl != null ? videoUrl : "";
        session.reelThumbUrl  = thumbUrl != null ? thumbUrl : "";
        session.reelCaption   = caption != null ? caption : "";
        session.hostUid     = myUid;
        session.hostName    = myName != null ? myName : "";
        session.partnerUid  = partner.uid;
        session.partnerName = partner.name;
        session.durationMs  = durationMs;

        sessRef.setValue(session, (err, ref) -> {
            if (err != null) {
                Toast.makeText(this, "Failed to create session", Toast.LENGTH_SHORT).show();
                return;
            }
            // Open session screen as HOST
            Intent i = new Intent(this, CollabDuetSessionActivity.class);
            i.putExtra(CollabDuetSessionActivity.EXTRA_SESSION_ID, sessionId);
            i.putExtra(CollabDuetSessionActivity.EXTRA_IS_HOST, true);
            startActivity(i);
            finish();
        });
    }

    private void showEmpty(boolean show) {
        tvEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        rvFollowers.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private static String getString(DataSnapshot ds, String key, String def) {
        Object v = ds.child(key).getValue();
        return v instanceof String ? (String) v : def;
    }

    // ── Data model ────────────────────────────────────────────────────────────

    static class FollowerItem {
        String uid, name, photo;
        FollowerItem(String uid, String name, String photo) {
            this.uid = uid; this.name = name; this.photo = photo;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    static class FollowerAdapter extends RecyclerView.Adapter<FollowerAdapter.VH> {
        interface OnClick { void onClick(FollowerItem item); }
        private final List<FollowerItem> items;
        private final OnClick listener;
        FollowerAdapter(List<FollowerItem> items, OnClick listener) {
            this.items = items; this.listener = listener;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_follower_collab_invite, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            FollowerItem item = items.get(pos);
            h.tvName.setText("@" + item.name);
            if (item.photo != null && !item.photo.isEmpty()) {
                Glide.with(h.ivAvatar.getContext())
                    .load(item.photo).circleCrop().into(h.ivAvatar);
            }
            h.btnInvite.setOnClickListener(v -> listener.onClick(item));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar;
            TextView  tvName;
            View      btnInvite;
            VH(View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_follower_avatar);
                tvName    = v.findViewById(R.id.tv_follower_name);
                btnInvite = v.findViewById(R.id.btn_invite_to_collab);
            }
        }
    }
}
