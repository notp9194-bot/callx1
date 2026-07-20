package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.models.ReelModel;
import com.callx.app.notifications.CollabRepostNotificationHelper;
import com.callx.app.reels.R;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * CollabPostInviteActivity — Invite a collaborator to JOINTLY author a new reel.
 *
 * Instagram-style joint authorship: both creators appear as co-authors in feed.
 * Reel stays in PENDING state until collaborator accepts; then goes live on both feeds.
 *
 * Launch with:
 *   Intent i = new Intent(ctx, CollabPostInviteActivity.class);
 *   i.putExtra(EXTRA_REEL_ID,    pendingReelId);
 *   i.putExtra(EXTRA_THUMB_URL,  thumbUrl);
 *   i.putExtra(EXTRA_VIDEO_URL,  videoUrl);
 *   i.putExtra(EXTRA_CAPTION,    myCaption);
 *   startActivity(i);
 */
public class CollabPostInviteActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID   = "cpi_reel_id";
    public static final String EXTRA_THUMB_URL = "cpi_thumb_url";
    public static final String EXTRA_VIDEO_URL = "cpi_video_url";
    public static final String EXTRA_CAPTION   = "cpi_caption";

    private static final int    SEARCH_LIMIT  = 20;
    private static final long   RATE_LIMIT_MS = 3000L;

    // ── UI ──────────────────────────────────────────────────────────────────
    private ImageView       ivThumb;
    private TextView        tvCaption;
    private EditText        etSearch;
    private RecyclerView    rvSearch;
    private LinearLayout    llSelectedChip;
    private CircleImageView ivCollabAvatar;
    private TextView        tvCollabName;
    private Button          btnSendInvite;
    private ProgressBar     progressBar;
    private TextView        tvSearchHint;

    // ── State ────────────────────────────────────────────────────────────────
    private String myUid, myName, myPhoto;
    private String reelId, thumbUrl, videoUrl, myCaption;
    private String selectedCollabUid, selectedCollabName, selectedCollabPhoto;
    private long   lastSendMs = 0L;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingSearch;

    private final List<CollabUserItem> searchResults = new ArrayList<>();
    private CollabUserSearchAdapter    searchAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collab_post_invite);

        reelId    = getIntent().getStringExtra(EXTRA_REEL_ID);
        thumbUrl  = getIntent().getStringExtra(EXTRA_THUMB_URL);
        videoUrl  = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        myCaption = getIntent().getStringExtra(EXTRA_CAPTION);
        if (reelId == null) { finish(); return; }

        myUid  = FirebaseUtils.getCurrentUid();
        myName = FirebaseUtils.getCurrentName();
        if (myUid == null) { finish(); return; }

        loadMyPhoto();
        bindViews();
        setupSearch();
    }

    private void loadMyPhoto() {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (s.exists()) {
                        Object p = s.child("photoUrl").getValue();
                        myPhoto = p != null ? p.toString() : "";
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void bindViews() {
        ivThumb        = findViewById(R.id.iv_collab_invite_thumb);
        tvCaption      = findViewById(R.id.tv_collab_invite_caption);
        etSearch       = findViewById(R.id.et_collab_search);
        rvSearch       = findViewById(R.id.rv_collab_search);
        llSelectedChip = findViewById(R.id.ll_collab_selected_chip);
        ivCollabAvatar = findViewById(R.id.iv_collab_selected_avatar);
        tvCollabName   = findViewById(R.id.tv_collab_selected_name);
        btnSendInvite  = findViewById(R.id.btn_send_collab_invite);
        progressBar    = findViewById(R.id.progress_collab_invite);
        tvSearchHint   = findViewById(R.id.tv_collab_search_hint);

        // Toolbar back
        View btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Thumbnail
        if (thumbUrl != null && !thumbUrl.isEmpty() && ivThumb != null) {
            Glide.with(this).load(thumbUrl).centerCrop().into(ivThumb);
        }
        if (tvCaption != null) tvCaption.setText(myCaption != null ? myCaption : "");

        // RecyclerView
        searchAdapter = new CollabUserSearchAdapter(searchResults, item -> selectCollaborator(item));
        rvSearch.setLayoutManager(new LinearLayoutManager(this));
        rvSearch.setAdapter(searchAdapter);

        llSelectedChip.setVisibility(View.GONE);
        btnSendInvite.setEnabled(false);
        btnSendInvite.setOnClickListener(v -> sendInvite());

        // Remove selected collab
        View btnRemove = findViewById(R.id.btn_remove_collab);
        if (btnRemove != null) btnRemove.setOnClickListener(v -> clearSelection());
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
                String q = s.toString().trim().toLowerCase(Locale.ROOT);
                if (q.isEmpty()) {
                    searchResults.clear();
                    searchAdapter.notifyDataSetChanged();
                    if (tvSearchHint != null) tvSearchHint.setVisibility(View.VISIBLE);
                    return;
                }
                if (tvSearchHint != null) tvSearchHint.setVisibility(View.GONE);
                pendingSearch = () -> runSearch(q);
                searchHandler.postDelayed(pendingSearch, 350);
            }
        });
    }

    private void runSearch(String query) {
        DatabaseReference ref = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reels/users");

        // Search by displayName prefix
        Query q1 = ref.orderByChild("displayNameLower")
            .startAt(query).endAt(query + "\uf8ff").limitToFirst(SEARCH_LIMIT);

        q1.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                Set<String> seenUids = new HashSet<>();
                List<CollabUserItem> results = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String uid = child.getKey();
                    if (uid == null || uid.equals(myUid)) continue;
                    if (seenUids.contains(uid)) continue;
                    seenUids.add(uid);
                    String name  = getString(child, "displayName");
                    String handle= getString(child, "handle");
                    String photo = getString(child, "photoUrl");
                    results.add(new CollabUserItem(uid, name, handle, photo));
                }

                // Also search by handle prefix
                Query q2 = ref.orderByChild("handleLower")
                    .startAt(query).endAt(query + "\uf8ff").limitToFirst(SEARCH_LIMIT);
                q2.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s2) {
                        for (DataSnapshot child : s2.getChildren()) {
                            String uid = child.getKey();
                            if (uid == null || uid.equals(myUid)) continue;
                            if (seenUids.contains(uid)) continue;
                            seenUids.add(uid);
                            String name  = getString(child, "displayName");
                            String handle= getString(child, "handle");
                            String photo = getString(child, "photoUrl");
                            results.add(new CollabUserItem(uid, name, handle, photo));
                        }
                        searchResults.clear();
                        searchResults.addAll(results);
                        searchAdapter.notifyDataSetChanged();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void selectCollaborator(CollabUserItem item) {
        selectedCollabUid   = item.uid;
        selectedCollabName  = item.displayName;
        selectedCollabPhoto = item.photoUrl;

        llSelectedChip.setVisibility(View.VISIBLE);
        if (tvCollabName != null) tvCollabName.setText("@" + (item.handle != null && !item.handle.isEmpty() ? item.handle : item.displayName));
        if (ivCollabAvatar != null && item.photoUrl != null && !item.photoUrl.isEmpty()) {
            Glide.with(this).load(item.photoUrl).circleCrop().into(ivCollabAvatar);
        }
        btnSendInvite.setEnabled(true);
        etSearch.setText("");
        searchResults.clear();
        searchAdapter.notifyDataSetChanged();
    }

    private void clearSelection() {
        selectedCollabUid = selectedCollabName = selectedCollabPhoto = null;
        llSelectedChip.setVisibility(View.GONE);
        btnSendInvite.setEnabled(false);
    }

    private void sendInvite() {
        if (selectedCollabUid == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSendMs < RATE_LIMIT_MS) {
            Toast.makeText(this, "Please wait before sending another invite.", Toast.LENGTH_SHORT).show();
            return;
        }
        lastSendMs = now;
        progressBar.setVisibility(View.VISIBLE);
        btnSendInvite.setEnabled(false);

        String inviteId = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("collabPostInvites").push().getKey();
        if (inviteId == null) { progressBar.setVisibility(View.GONE); btnSendInvite.setEnabled(true); return; }

        Map<String, Object> invite = new HashMap<>();
        invite.put("inviteId",         inviteId);
        invite.put("reelId",           reelId);
        invite.put("initiatorUid",     myUid);
        invite.put("initiatorName",    myName != null ? myName : "");
        invite.put("initiatorPhoto",   myPhoto != null ? myPhoto : "");
        invite.put("initiatorCaption", myCaption != null ? myCaption : "");
        invite.put("collaboratorUid",  selectedCollabUid);
        invite.put("thumbUrl",         thumbUrl != null ? thumbUrl : "");
        invite.put("videoUrl",         videoUrl != null ? videoUrl : "");
        invite.put("status",           "pending");
        invite.put("createdAt",        now);

        DatabaseReference root = FirebaseDatabase.getInstance(Constants.DB_URL).getReference();
        Map<String, Object> updates = new HashMap<>();
        // Write invite under collaborator's inbox
        updates.put("collabPostInvites/" + selectedCollabUid + "/" + inviteId, invite);
        // Write sent copy under initiator's outbox
        updates.put("collabPostInvitesSent/" + myUid + "/" + inviteId, invite);
        // Mark reel as pending collab
        updates.put("reels/" + reelId + "/isCollabPending", true);
        updates.put("reels/" + reelId + "/isCollabPost",    false);
        updates.put("reels/" + reelId + "/collabInviteId",  inviteId);
        updates.put("reels/" + reelId + "/collabUid",       selectedCollabUid);
        updates.put("reels/" + reelId + "/collabDisplayName", selectedCollabName != null ? selectedCollabName : "");
        updates.put("reels/" + reelId + "/collabAvatarUrl",  selectedCollabPhoto != null ? selectedCollabPhoto : "");

        root.updateChildren(updates, (error, ref) -> {
            progressBar.setVisibility(View.GONE);
            if (error != null) {
                btnSendInvite.setEnabled(true);
                Toast.makeText(this, "Failed to send invite. Try again.", Toast.LENGTH_SHORT).show();
            } else {
                // Push notification to collaborator
                sendPushNotification(inviteId);
                Toast.makeText(this, "Collab invite sent to @" + selectedCollabName + "!", Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
                finish();
            }
        });
    }

    private void sendPushNotification(String inviteId) {
        try {
            CollabRepostNotificationHelper.notifyCollabInvite(
                this, selectedCollabUid, myUid,
                myName != null ? myName : "Someone",
                reelId, inviteId, thumbUrl != null ? thumbUrl : ""
            );
        } catch (Exception ignored) {}
    }

    private static String getString(DataSnapshot s, String key) {
        Object v = s.child(key).getValue();
        return v != null ? v.toString() : "";
    }

    // ── Inner: User item ──────────────────────────────────────────────────
    public static class CollabUserItem {
        public final String uid, displayName, handle, photoUrl;
        CollabUserItem(String uid, String displayName, String handle, String photoUrl) {
            this.uid = uid; this.displayName = displayName; this.handle = handle; this.photoUrl = photoUrl;
        }
    }

    // ── Inner: Search adapter ──────────────────────────────────────────────
    private static class CollabUserSearchAdapter
            extends RecyclerView.Adapter<CollabUserSearchAdapter.VH> {
        interface OnSelectListener { void onSelect(CollabUserItem item); }
        private final List<CollabUserItem> items;
        private final OnSelectListener     listener;
        CollabUserSearchAdapter(List<CollabUserItem> items, OnSelectListener l) {
            this.items = items; this.listener = l;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int type) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collab_invite, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            CollabUserItem item = items.get(pos);
            h.tvName.setText(item.displayName);
            h.tvHandle.setText("@" + (item.handle != null ? item.handle : item.displayName));
            if (item.photoUrl != null && !item.photoUrl.isEmpty())
                Glide.with(h.itemView.getContext()).load(item.photoUrl).circleCrop().into(h.ivAvatar);
            else
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            h.itemView.setOnClickListener(v -> listener.onSelect(item));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            final CircleImageView ivAvatar;
            final TextView tvName, tvHandle;
            VH(View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_collab_user_avatar);
                tvName   = v.findViewById(R.id.tv_collab_user_name);
                tvHandle = v.findViewById(R.id.tv_collab_user_handle);
            }
        }
    }
}
