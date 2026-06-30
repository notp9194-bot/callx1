package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.UserEntity;
import com.callx.app.models.User;
import com.callx.app.models.Group;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.group.GroupChatActivity;

/**
 * ContactsActivity v18 — Forward Message Contact Picker.
 *
 * MULTI-SELECT FORWARD: Multiple contacts select karo aur ek bar mein
 * sab ko message forward karo.
 *
 * Flow:
 *   1. Room se contacts load karo (offline-first, instant)
 *   2. Firebase se sync karo (online update)
 *   3. User multiple contacts select kare → "Send to X" button press kare
 *   4. Har selected contact ke liye ChatActivity launch karo (forward payload ke saath)
 */
public class ContactsActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView     tvEmpty;
    private EditText     etSearch;
    private Button       btnSend;
    private TextView     tvSendCount;
    private ContactsAdapter adapter;

    private final List<Object> allContacts      = new ArrayList<>();
    private final List<Object> filtered         = new ArrayList<>();
    private final Set<String>  selectedUids     = new HashSet<>();
    // Backing lists merged into allContacts — kept separate so refreshing
    // contacts (Firebase) doesn't wipe out the groups list and vice versa.
    private final List<User>  userList  = new ArrayList<>();
    private final List<Group> groupList = new ArrayList<>();

    // Forward payload — single message
    private String forwardText;
    private String forwardType;
    private String forwardMedia;
    private String forwardMediaItemsJson;
    private String forwardCaption;
    private String forwardFileName;

    // ── Reel share forward extras ─────────────────────────────────────────
    private String forwardReelId;
    private String forwardReelShareUrl;
    private String forwardReelShareThumb;
    private String forwardReelShareCaption;
    private String forwardReelShareUsername;
    private String forwardReelShareOwnerPhoto;

    // Forward payload — multiple messages (JSON-serialised ArrayList)
    private ArrayList<String> forwardTexts;
    private ArrayList<String> forwardTypes;
    private ArrayList<String> forwardMedias;
    private ArrayList<String> forwardFileNames;
    private boolean isMultiForward = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Forward to…");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        // Single-message forward extras
        forwardText     = getIntent().getStringExtra("forwardText");
        forwardType     = getIntent().getStringExtra("forwardType");
        forwardMedia    = getIntent().getStringExtra("forwardMedia");
        forwardFileName = getIntent().getStringExtra("forwardFileName");

        // ── multi_media group forward (whole group or gallery-selected subset) ──
        forwardMediaItemsJson = getIntent().getStringExtra("forwardMediaItemsJson");
        forwardCaption        = getIntent().getStringExtra("forwardCaption");

        // ── Reel share card extras ─────────────────────────────────────────
        forwardReelId              = getIntent().getStringExtra("forwardReelId");
        forwardReelShareUrl        = getIntent().getStringExtra("forwardReelShareUrl");
        forwardReelShareThumb      = getIntent().getStringExtra("forwardReelShareThumb");
        forwardReelShareCaption    = getIntent().getStringExtra("forwardReelShareCaption");
        forwardReelShareUsername   = getIntent().getStringExtra("forwardReelShareUsername");
        forwardReelShareOwnerPhoto = getIntent().getStringExtra("forwardReelShareOwnerPhoto");

        // ── Handle ACTION_SEND from external apps (e.g. Instagram reel share) ──
        if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            String sharedText = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && !sharedText.isEmpty()) {
                java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                        .compile("https?://[^\\s]+").matcher(sharedText);
                String url = urlMatcher.find() ? urlMatcher.group() : null;
                boolean isInstagramReel = url != null
                        && (url.contains("instagram.com/reel/")
                        || url.contains("instagram.com/reels/")
                        || (url.contains("instagram.com/p/")
                                && sharedText.toLowerCase().contains("reel")));
                if (isInstagramReel) {
                    forwardType  = "reel_share";
                    forwardText  = sharedText;   // full text incl. URL; ChatActivity will parse
                    forwardMedia = null;
                } else {
                    forwardType  = "text";
                    forwardText  = sharedText;
                    forwardMedia = null;
                }
            }
        }

        // Multi-message forward extras
        forwardTexts      = getIntent().getStringArrayListExtra("forwardTexts");
        forwardTypes      = getIntent().getStringArrayListExtra("forwardTypes");
        forwardMedias     = getIntent().getStringArrayListExtra("forwardMedias");
        forwardFileNames  = getIntent().getStringArrayListExtra("forwardFileNames");

        isMultiForward = (forwardTexts != null && !forwardTexts.isEmpty());

        rv        = findViewById(R.id.rv_contacts);
        tvEmpty   = findViewById(R.id.tv_empty);
        etSearch  = findViewById(R.id.et_search_contacts);
        btnSend   = findViewById(R.id.btn_send_forward);
        tvSendCount = findViewById(R.id.tv_send_count);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactsAdapter(filtered, selectedUids, this::onTargetToggled);
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterContacts(s.toString().trim());
            }
        });

        btnSend.setOnClickListener(v -> sendToSelected());
        updateSendButton();

        loadFromRoom();
        loadFromFirebase();
    }

    private static String keyOf(Object target) {
        if (target instanceof User)  return "u:" + ((User) target).uid;
        if (target instanceof Group) return "g:" + ((Group) target).id;
        return "";
    }

    private void onTargetToggled(Object target) {
        String key = keyOf(target);
        if (selectedUids.contains(key)) {
            selectedUids.remove(key);
        } else {
            selectedUids.add(key);
        }
        adapter.notifyDataSetChanged();
        updateSendButton();
    }

    private void updateSendButton() {
        int count = selectedUids.size();
        if (count == 0) {
            btnSend.setVisibility(View.GONE);
            tvSendCount.setVisibility(View.GONE);
        } else {
            btnSend.setVisibility(View.VISIBLE);
            tvSendCount.setVisibility(View.VISIBLE);
            btnSend.setText("Send (" + count + ")");
            int msgCount = isMultiForward ? forwardTexts.size() : 1;
            tvSendCount.setText(msgCount + " message" + (msgCount > 1 ? "s" : "") +
                    " → " + count + " contact" + (count > 1 ? "s" : ""));
        }
    }

    private void sendToSelected() {
        if (selectedUids.isEmpty()) {
            Toast.makeText(this, "Koi contact select nahi kiya", Toast.LENGTH_SHORT).show();
            return;
        }

        // Sabhi selected targets (users + groups) dhundo
        List<User>  selectedUsers  = new ArrayList<>();
        List<Group> selectedGroups = new ArrayList<>();
        for (Object t : allContacts) {
            String key = keyOf(t);
            if (!selectedUids.contains(key)) continue;
            if (t instanceof User)  selectedUsers.add((User) t);
            else if (t instanceof Group) selectedGroups.add((Group) t);
        }

        if (isMultiForward) {
            // Multiple messages → multiple targets
            for (User u : selectedUsers)   openChatWithMultiForward(u);
            for (Group g : selectedGroups) openGroupWithMultiForward(g);
        } else {
            // Single message → multiple targets
            for (User u : selectedUsers)   openChatWithSingleForward(u);
            for (Group g : selectedGroups) openGroupWithSingleForward(g);
        }

        int targetCount = selectedUsers.size() + selectedGroups.size();
        int msgCount = isMultiForward ? forwardTexts.size() : 1;
        Toast.makeText(this,
                msgCount + " message" + (msgCount > 1 ? "s" : "") +
                " forwarded to " + targetCount + " chat" +
                (targetCount > 1 ? "s" : ""),
                Toast.LENGTH_SHORT).show();
        finish();
    }

    private void openChatWithSingleForward(User u) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("partnerUid",      u.uid);
        i.putExtra("partnerName",     u.name     != null ? u.name     : "");
        i.putExtra("partnerPhoto",    u.photoUrl != null ? u.photoUrl : "");
        i.putExtra("partnerThumb",    u.thumbUrl != null ? u.thumbUrl : "");
        i.putExtra("forwardText",     forwardText);
        i.putExtra("forwardType",     forwardType);
        i.putExtra("forwardMedia",    forwardMedia);
        i.putExtra("forwardFileName", forwardFileName);
        // ── multi_media group forward — whole group or gallery-selected subset ──
        if (forwardMediaItemsJson != null && !forwardMediaItemsJson.isEmpty()) {
            i.putExtra("forwardMediaItemsJson", forwardMediaItemsJson);
            i.putExtra("forwardCaption",        forwardCaption);
        }
        // ── Reel share card extras — thumbnail + avatar ke liye zaroori ──
        if ("reel_share".equals(forwardType)) {
            if (forwardReelId              != null) i.putExtra("forwardReelId",              forwardReelId);
            if (forwardReelShareUrl        != null) i.putExtra("forwardReelShareUrl",        forwardReelShareUrl);
            if (forwardReelShareThumb      != null) i.putExtra("forwardReelShareThumb",      forwardReelShareThumb);
            if (forwardReelShareCaption    != null) i.putExtra("forwardReelShareCaption",    forwardReelShareCaption);
            if (forwardReelShareUsername   != null) i.putExtra("forwardReelShareUsername",   forwardReelShareUsername);
            if (forwardReelShareOwnerPhoto != null) i.putExtra("forwardReelShareOwnerPhoto", forwardReelShareOwnerPhoto);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void openChatWithMultiForward(User u) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("partnerUid",       u.uid);
        i.putExtra("partnerName",      u.name     != null ? u.name     : "");
        i.putExtra("partnerPhoto",     u.photoUrl != null ? u.photoUrl : "");
        i.putExtra("partnerThumb",     u.thumbUrl != null ? u.thumbUrl : "");
        i.putStringArrayListExtra("forwardTexts",     forwardTexts);
        i.putStringArrayListExtra("forwardTypes",     forwardTypes);
        i.putStringArrayListExtra("forwardMedias",    forwardMedias);
        i.putStringArrayListExtra("forwardFileNames", forwardFileNames);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** Group counterpart of {@link #openChatWithSingleForward(User)} — same
     * payload, just routed to GroupChatActivity so multi_media forwards
     * into a group render as a proper gallery instead of dropping the
     * grouping (see GroupChatActivity#handleIncomingForward). */
    private void openGroupWithSingleForward(Group g) {
        Intent i = new Intent(this, GroupChatActivity.class);
        i.putExtra("groupId",         g.id);
        i.putExtra("groupName",       g.name    != null ? g.name    : "");
        i.putExtra("groupPhoto",      g.iconUrl != null ? g.iconUrl : "");
        i.putExtra("forwardText",     forwardText);
        i.putExtra("forwardType",     forwardType);
        i.putExtra("forwardMedia",    forwardMedia);
        i.putExtra("forwardFileName", forwardFileName);
        if (forwardMediaItemsJson != null && !forwardMediaItemsJson.isEmpty()) {
            i.putExtra("forwardMediaItemsJson", forwardMediaItemsJson);
            i.putExtra("forwardCaption",        forwardCaption);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void openGroupWithMultiForward(Group g) {
        Intent i = new Intent(this, GroupChatActivity.class);
        i.putExtra("groupId",    g.id);
        i.putExtra("groupName",  g.name    != null ? g.name    : "");
        i.putExtra("groupPhoto", g.iconUrl != null ? g.iconUrl : "");
        i.putStringArrayListExtra("forwardTexts",     forwardTexts);
        i.putStringArrayListExtra("forwardTypes",     forwardTypes);
        i.putStringArrayListExtra("forwardMedias",    forwardMedias);
        i.putStringArrayListExtra("forwardFileNames", forwardFileNames);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void loadFromRoom() {
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            List<UserEntity> cached = db.userDao().getAllUsersSync();
            if (cached == null || cached.isEmpty()) return;
            List<User> roomUsers = new ArrayList<>();
            for (UserEntity e : cached) {
                if (myUid.equals(e.uid)) continue;
                User u = new User();
                u.uid      = e.uid;
                u.name     = e.name;
                u.photoUrl = e.photoUrl;
                u.thumbUrl = e.thumbUrl;
                roomUsers.add(u);
            }
            runOnUiThread(() -> {
                if (userList.isEmpty()) {
                    userList.addAll(roomUsers);
                    rebuildAllContacts();
                }
            });
        });
    }

    private void loadFromFirebase() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseUtils.getContactsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<User> fbList = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        User u = c.getValue(User.class);
                        if (u == null) continue;
                        if (u.uid == null) u.uid = c.getKey();
                        if (myUid.equals(u.uid)) continue;
                        fbList.add(u);
                    }
                    if (fbList.isEmpty()) return;
                    userList.clear();
                    userList.addAll(fbList);
                    rebuildAllContacts();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
        loadGroupsFromFirebase(myUid);
    }

    /** Loads the groups this user is a member of, so they can also be picked
     * as a forward destination (mirrors GroupsFragment's load pattern). */
    private void loadGroupsFromFirebase(String myUid) {
        FirebaseUtils.getUserGroupsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!snap.hasChildren()) return;
                    List<Group> fetched = new ArrayList<>();
                    final int[] pending = {(int) snap.getChildrenCount()};
                    for (DataSnapshot gSnap : snap.getChildren()) {
                        String gid = gSnap.getKey();
                        FirebaseUtils.getGroupsRef().child(gid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot ds) {
                                    Group g = ds.getValue(Group.class);
                                    if (g != null) {
                                        if (g.id == null) g.id = ds.getKey();
                                        fetched.add(g);
                                    }
                                    if (--pending[0] == 0) {
                                        groupList.clear();
                                        groupList.addAll(fetched);
                                        rebuildAllContacts();
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    if (--pending[0] == 0) {
                                        groupList.clear();
                                        groupList.addAll(fetched);
                                        rebuildAllContacts();
                                    }
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void rebuildAllContacts() {
        allContacts.clear();
        allContacts.addAll(userList);
        allContacts.addAll(groupList);
        filterContacts(etSearch.getText().toString().trim());
    }

    private void filterContacts(String query) {
        filtered.clear();
        if (query.isEmpty()) {
            filtered.addAll(allContacts);
        } else {
            String lq = query.toLowerCase(java.util.Locale.getDefault());
            for (Object t : allContacts) {
                String name = (t instanceof User) ? ((User) t).name
                            : (t instanceof Group) ? ((Group) t).name : null;
                if (name != null && name.toLowerCase(
                        java.util.Locale.getDefault()).contains(lq)) {
                    filtered.add(t);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateEmpty();
    }

    private void updateEmpty() {
        tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Inline RecyclerView Adapter ───────────────────────────────────────
    private static class ContactsAdapter
            extends RecyclerView.Adapter<ContactsAdapter.VH> {

        interface OnToggle { void on(Object target); }

        private final List<Object> list;
        private final Set<String>  selectedUids;
        private final OnToggle     cb;

        ContactsAdapter(List<Object> list, Set<String> selectedUids, OnToggle cb) {
            this.list         = list;
            this.selectedUids = selectedUids;
            this.cb           = cb;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_member_select, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Object t = list.get(pos);
            boolean isGroup = t instanceof Group;
            String name      = isGroup ? ((Group) t).name      : ((User) t).name;
            String avatarUrl = isGroup ? ((Group) t).iconUrl
                              : (((User) t).thumbUrl != null && !((User) t).thumbUrl.isEmpty()
                                      ? ((User) t).thumbUrl : ((User) t).photoUrl);
            String key = keyOf(t);

            h.tvName.setText(isGroup ? ("\uD83D\uDC65 " + (name != null ? name : "Group"))
                                      : (name != null ? name : "User"));

            boolean selected = selectedUids.contains(key);
            h.cbSelect.setChecked(selected);
            // Highlight selected row
            h.itemView.setAlpha(selected ? 1.0f : 0.85f);
            h.itemView.setBackgroundResource(selected
                    ? android.R.color.holo_blue_light
                    : android.R.color.transparent);

            // thumbUrl → 100px WebP, fast load in contact list
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                Glide.with(h.ivAvatar.getContext())
                    .load(avatarUrl)
                    .placeholder(isGroup ? R.drawable.ic_person : R.drawable.ic_person)
                    .circleCrop()
                    .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            h.itemView.setOnClickListener(v -> cb.on(t));
            h.cbSelect.setOnClickListener(v -> cb.on(t));
        }

        @Override public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar;
            TextView  tvName;
            CheckBox  cbSelect;
            VH(@NonNull View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_avatar);
                tvName   = v.findViewById(R.id.tv_name);
                cbSelect = v.findViewById(R.id.cb_select);
            }
        }
    }
}
