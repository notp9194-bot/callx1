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
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * CollabPostInviteActivity — Invite MULTIPLE collaborators to JOINTLY author a new reel.
 *
 * ✅ MULTI-COLLABORATOR SUPPORT (Instagram-style "and N others" joint posts):
 * Up to {@link #MAX_COLLABORATORS} people can be selected and invited in one go.
 * Each selected person gets their own invite record + their own entry in the
 * reel's collabMap (status starts "pending" and flips to "accepted"/"declined"
 * independently per person via CollabPostAcceptActivity), so the post goes
 * live with whichever co-authors have accepted so far — it doesn't wait for
 * every invite to be answered.
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
    /**
     * ✅ MULTI-COLLABORATOR: pass true when launching from the upload/post-details
     * screen, BEFORE the reel exists in Firebase (no reelId yet). In this mode the
     * activity only lets the user pick up to {@link #MAX_COLLABORATORS} people and
     * returns their info via the RESULT_* extras below instead of writing invites —
     * the caller (ReelUploadActivity) sends the real invites once the reel is saved
     * and a reelId exists.
     */
    public static final String EXTRA_STAGING_MODE = "cpi_staging_mode";

    // ── Staging-mode result extras (parallel ArrayLists, one entry per picked user) ──
    public static final String RESULT_UIDS     = "cpi_result_uids";
    public static final String RESULT_NAMES    = "cpi_result_names";
    public static final String RESULT_HANDLES  = "cpi_result_handles";
    public static final String RESULT_AVATARS  = "cpi_result_avatars";

    private static final int    SEARCH_LIMIT      = 20;
    private static final long   RATE_LIMIT_MS     = 3000L;
    /** Owner + up to 4 collaborators = 5 total co-authors, matching common IG-style caps. */
    public  static final int    MAX_COLLABORATORS = 4;

    // ── UI ──────────────────────────────────────────────────────────────────
    private ImageView       ivThumb;
    private TextView        tvCaption;
    private EditText        etSearch;
    private RecyclerView    rvSearch;
    private View            selectedContainer;
    private RecyclerView    rvSelected;
    private TextView        tvSelectedCount;
    private Button          btnSendInvite;
    private ProgressBar     progressBar;
    private TextView        tvSearchHint;

    // ── State ────────────────────────────────────────────────────────────────
    private String myUid, myName, myPhoto;
    private String reelId, thumbUrl, videoUrl, myCaption;
    private boolean stagingMode;
    private long   lastSendMs = 0L;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingSearch;

    private final List<CollabUserItem> searchResults      = new ArrayList<>();
    private final List<CollabUserItem> selectedCollaborators = new ArrayList<>();
    private CollabUserSearchAdapter    searchAdapter;
    private SelectedCollabAdapter      selectedAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collab_post_invite);

        reelId      = getIntent().getStringExtra(EXTRA_REEL_ID);
        thumbUrl    = getIntent().getStringExtra(EXTRA_THUMB_URL);
        videoUrl    = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        myCaption   = getIntent().getStringExtra(EXTRA_CAPTION);
        stagingMode = getIntent().getBooleanExtra(EXTRA_STAGING_MODE, false);
        // Staging mode (upload screen, pre-reelId) doesn't need a reelId yet —
        // only the normal (existing-reel) flow does.
        if (!stagingMode && (reelId == null || reelId.isEmpty())) { finish(); return; }

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
        ivThumb           = findViewById(R.id.iv_collab_invite_thumb);
        tvCaption         = findViewById(R.id.tv_collab_invite_caption);
        etSearch          = findViewById(R.id.et_collab_search);
        rvSearch          = findViewById(R.id.rv_collab_search);
        selectedContainer = findViewById(R.id.ll_collab_selected_chip);
        rvSelected        = findViewById(R.id.rv_collab_selected);
        tvSelectedCount   = findViewById(R.id.tv_collab_selected_count);
        btnSendInvite     = findViewById(R.id.btn_send_collab_invite);
        progressBar       = findViewById(R.id.progress_collab_invite);
        tvSearchHint      = findViewById(R.id.tv_collab_search_hint);

        // Toolbar back
        View btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Thumbnail — not available yet in staging mode (reel isn't uploaded yet)
        if (thumbUrl != null && !thumbUrl.isEmpty() && ivThumb != null) {
            Glide.with(this).load(thumbUrl).centerCrop().into(ivThumb);
        } else if (stagingMode && ivThumb != null) {
            ivThumb.setVisibility(View.GONE);
        }
        if (tvCaption != null) tvCaption.setText(myCaption != null ? myCaption : "");

        // Search results RecyclerView
        searchAdapter = new CollabUserSearchAdapter(searchResults, this::toggleCollaborator, this::isSelected);
        rvSearch.setLayoutManager(new LinearLayoutManager(this));
        rvSearch.setAdapter(searchAdapter);

        // Selected collaborators chip row (horizontal, up to MAX_COLLABORATORS)
        selectedAdapter = new SelectedCollabAdapter(selectedCollaborators, this::removeCollaborator);
        if (rvSelected != null) {
            rvSelected.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            rvSelected.setAdapter(selectedAdapter);
        }

        if (selectedContainer != null) selectedContainer.setVisibility(View.GONE);
        btnSendInvite.setEnabled(false);
        if (stagingMode) btnSendInvite.setText("Add");
        btnSendInvite.setOnClickListener(v -> {
            if (stagingMode) returnStagedSelection(); else sendInvites();
        });
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
        // ✅ Fixed: real user profiles live at "users/{uid}" (see ProfileSetupActivity /
        // SearchActivity), not "reels/users" — and the indexed lowercase field is
        // "nameLower", not "displayNameLower"/"handleLower" (those never existed in the
        // schema, which is why search always returned empty).
        DatabaseReference ref = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users");

        // Search by name prefix
        Query q1 = ref.orderByChild("nameLower")
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
                    String name  = getString(child, "name");
                    String handle= getString(child, "username");
                    String photo = getString(child, "photoUrl");
                    results.add(new CollabUserItem(uid, name, handle, photo));
                }

                // Also search by username prefix
                Query q2 = ref.orderByChild("username")
                    .startAt(query).endAt(query + "\uf8ff").limitToFirst(SEARCH_LIMIT);
                q2.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s2) {
                        for (DataSnapshot child : s2.getChildren()) {
                            String uid = child.getKey();
                            if (uid == null || uid.equals(myUid)) continue;
                            if (seenUids.contains(uid)) continue;
                            seenUids.add(uid);
                            String name  = getString(child, "name");
                            String handle= getString(child, "username");
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

    private boolean isSelected(String uid) {
        for (CollabUserItem c : selectedCollaborators) if (c.uid.equals(uid)) return true;
        return false;
    }

    /** Tapping a search result toggles it in/out of the selected list (multi-select). */
    private void toggleCollaborator(CollabUserItem item) {
        if (isSelected(item.uid)) {
            removeCollaborator(item);
            return;
        }
        if (selectedCollaborators.size() >= MAX_COLLABORATORS) {
            Toast.makeText(this, "You can add up to " + MAX_COLLABORATORS + " collaborators.", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedCollaborators.add(item);
        refreshSelectedUi();
        searchAdapter.notifyDataSetChanged();
    }

    private void removeCollaborator(CollabUserItem item) {
        for (Iterator<CollabUserItem> it = selectedCollaborators.iterator(); it.hasNext(); ) {
            if (it.next().uid.equals(item.uid)) { it.remove(); break; }
        }
        refreshSelectedUi();
        searchAdapter.notifyDataSetChanged();
    }

    private void refreshSelectedUi() {
        boolean hasAny = !selectedCollaborators.isEmpty();
        if (selectedContainer != null) selectedContainer.setVisibility(hasAny ? View.VISIBLE : View.GONE);
        if (tvSelectedCount != null) {
            tvSelectedCount.setText(selectedCollaborators.size() + "/" + MAX_COLLABORATORS + " selected");
        }
        selectedAdapter.notifyDataSetChanged();
        btnSendInvite.setEnabled(hasAny);
        if (stagingMode) {
            btnSendInvite.setText("Add" + (hasAny ? " (" + selectedCollaborators.size() + ")" : ""));
        } else {
            btnSendInvite.setText(selectedCollaborators.size() > 1 ? "Send Collab Invites" : "Send Collab Invite");
        }
    }

    /** ✅ MULTI-COLLABORATOR staging mode: no reelId yet, so just hand the
     *  picked users back to the upload screen instead of writing invites. */
    private void returnStagedSelection() {
        ArrayList<String> uids = new ArrayList<>(), names = new ArrayList<>(),
                           handles = new ArrayList<>(), avatars = new ArrayList<>();
        for (CollabUserItem c : selectedCollaborators) {
            uids.add(c.uid);
            names.add(c.displayName != null ? c.displayName : "");
            handles.add(c.handle != null ? c.handle : "");
            avatars.add(c.photoUrl != null ? c.photoUrl : "");
        }
        Intent result = new Intent();
        result.putStringArrayListExtra(RESULT_UIDS,    uids);
        result.putStringArrayListExtra(RESULT_NAMES,   names);
        result.putStringArrayListExtra(RESULT_HANDLES, handles);
        result.putStringArrayListExtra(RESULT_AVATARS, avatars);
        setResult(RESULT_OK, result);
        finish();
    }

    /** Sends one invite per selected collaborator and seeds the reel's collabMap. */
    private void sendInvites() {
        if (selectedCollaborators.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastSendMs < RATE_LIMIT_MS) {
            Toast.makeText(this, "Please wait before sending another invite.", Toast.LENGTH_SHORT).show();
            return;
        }
        lastSendMs = now;
        progressBar.setVisibility(View.VISIBLE);
        btnSendInvite.setEnabled(false);

        DatabaseReference root = FirebaseDatabase.getInstance(Constants.DB_URL).getReference();
        Map<String, Object> updates = new HashMap<>();

        List<String> inviteIds = new ArrayList<>();
        for (CollabUserItem target : selectedCollaborators) {
            String inviteId = root.child("collabPostInvites").push().getKey();
            if (inviteId == null) continue;
            inviteIds.add(inviteId);

            Map<String, Object> invite = new HashMap<>();
            invite.put("inviteId",         inviteId);
            invite.put("reelId",           reelId);
            invite.put("initiatorUid",     myUid);
            invite.put("initiatorName",    myName != null ? myName : "");
            invite.put("initiatorPhoto",   myPhoto != null ? myPhoto : "");
            invite.put("initiatorCaption", myCaption != null ? myCaption : "");
            invite.put("collaboratorUid",  target.uid);
            invite.put("thumbUrl",         thumbUrl != null ? thumbUrl : "");
            invite.put("videoUrl",         videoUrl != null ? videoUrl : "");
            invite.put("status",           "pending");
            invite.put("createdAt",        now);

            updates.put("collabPostInvites/" + target.uid + "/" + inviteId, invite);
            updates.put("collabPostInvitesSent/" + myUid + "/" + inviteId, invite);

            // ✅ MULTI-COLLABORATOR: one entry per invited person, keyed by uid,
            // so each person's accept/decline only ever touches their own entry.
            Map<String, Object> collabEntry = new HashMap<>();
            collabEntry.put("uid",         target.uid);
            collabEntry.put("displayName", target.displayName != null ? target.displayName : "");
            collabEntry.put("handle",      target.handle != null ? target.handle : "");
            collabEntry.put("avatarUrl",   target.photoUrl != null ? target.photoUrl : "");
            collabEntry.put("status",      "pending");
            collabEntry.put("inviteId",    inviteId);
            collabEntry.put("invitedAt",   now);
            updates.put("reels/" + reelId + "/collabMap/" + target.uid, collabEntry);
        }

        // Reel-level flags + legacy single-collaborator mirror (first invitee),
        // kept for old builds that only read collabUid/collabDisplayName.
        CollabUserItem first = selectedCollaborators.get(0);
        updates.put("reels/" + reelId + "/isCollabPending",   true);
        updates.put("reels/" + reelId + "/isCollabPost",      false);
        updates.put("reels/" + reelId + "/collabInviteId",    inviteIds.isEmpty() ? "" : inviteIds.get(0));
        updates.put("reels/" + reelId + "/collabUid",         first.uid);
        updates.put("reels/" + reelId + "/collabDisplayName", first.displayName != null ? first.displayName : "");
        updates.put("reels/" + reelId + "/collabAvatarUrl",   first.photoUrl != null ? first.photoUrl : "");

        root.updateChildren(updates, (error, ref) -> {
            progressBar.setVisibility(View.GONE);
            if (error != null) {
                btnSendInvite.setEnabled(true);
                Toast.makeText(this, "Failed to send invite. Try again.", Toast.LENGTH_SHORT).show();
            } else {
                for (int i = 0; i < selectedCollaborators.size() && i < inviteIds.size(); i++) {
                    sendPushNotification(selectedCollaborators.get(i), inviteIds.get(i));
                }
                String msg = selectedCollaborators.size() == 1
                    ? "Collab invite sent to @" + selectedCollaborators.get(0).displayName + "!"
                    : "Collab invites sent to " + selectedCollaborators.size() + " people!";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
                finish();
            }
        });
    }

    private void sendPushNotification(CollabUserItem target, String inviteId) {
        try {
            // Local notification only (doesn't reach the collaborator's device
            // unless it's this same device / app instance).
            CollabRepostNotificationHelper.notifyCollabInvite(
                this, target.uid, myUid,
                myName != null ? myName : "Someone",
                reelId, inviteId, thumbUrl != null ? thumbUrl : ""
            );
        } catch (Exception ignored) {}
        try {
            // ✅ NEW — real cross-device FCM push through the server so the
            // invited collaborator is notified even when their app is closed.
            PushNotify.notifyCollabRequest(
                target.uid, myUid, myName != null ? myName : "Someone", "",
                reelId, thumbUrl != null ? thumbUrl : "", inviteId
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

    // ── Inner: Search adapter (multi-select — dims rows that are already selected) ──
    private static class CollabUserSearchAdapter
            extends RecyclerView.Adapter<CollabUserSearchAdapter.VH> {
        interface OnToggleListener { void onToggle(CollabUserItem item); }
        interface SelectedChecker { boolean isSelected(String uid); }
        private final List<CollabUserItem> items;
        private final OnToggleListener     listener;
        private final SelectedChecker      selectedChecker;
        CollabUserSearchAdapter(List<CollabUserItem> items, OnToggleListener l, SelectedChecker sc) {
            this.items = items; this.listener = l; this.selectedChecker = sc;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int type) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collab_user_row, parent, false);
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
            boolean selected = selectedChecker.isSelected(item.uid);
            h.itemView.setAlpha(selected ? 0.5f : 1f);
            h.itemView.setOnClickListener(v -> listener.onToggle(item));
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

    // ── Inner: Selected-collaborator chip adapter (horizontal row of chips) ──
    private static class SelectedCollabAdapter
            extends RecyclerView.Adapter<SelectedCollabAdapter.VH> {
        interface OnRemoveListener { void onRemove(CollabUserItem item); }
        private final List<CollabUserItem> items;
        private final OnRemoveListener     listener;
        SelectedCollabAdapter(List<CollabUserItem> items, OnRemoveListener l) {
            this.items = items; this.listener = l;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int type) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collab_selected_chip, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            CollabUserItem item = items.get(pos);
            h.tvName.setText(item.displayName != null ? item.displayName : "");
            if (item.photoUrl != null && !item.photoUrl.isEmpty())
                Glide.with(h.itemView.getContext()).load(item.photoUrl).circleCrop().into(h.ivAvatar);
            else
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            h.btnRemove.setOnClickListener(v -> listener.onRemove(item));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            final CircleImageView ivAvatar;
            final TextView tvName;
            final ImageButton btnRemove;
            VH(View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_selected_chip_avatar);
                tvName    = v.findViewById(R.id.tv_selected_chip_name);
                btnRemove = v.findViewById(R.id.btn_selected_chip_remove);
            }
        }
    }
}
