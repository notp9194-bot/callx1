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

    private final List<User>   allContacts      = new ArrayList<>();
    private final List<User>   filtered         = new ArrayList<>();
    private final Set<String>  selectedUids     = new HashSet<>();

    // Forward payload — single message
    private String forwardText;
    private String forwardType;
    private String forwardMedia;
    private String forwardFileName;

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
        adapter = new ContactsAdapter(filtered, selectedUids, this::onContactToggled);
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

    private void onContactToggled(User u) {
        if (selectedUids.contains(u.uid)) {
            selectedUids.remove(u.uid);
        } else {
            selectedUids.add(u.uid);
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

        // Sabhi selected contacts ka User object dhundo
        List<User> selectedUsers = new ArrayList<>();
        for (User u : allContacts) {
            if (selectedUids.contains(u.uid)) selectedUsers.add(u);
        }

        if (isMultiForward) {
            // Multiple messages → multiple contacts
            for (User u : selectedUsers) {
                openChatWithMultiForward(u);
            }
        } else {
            // Single message → multiple contacts
            for (User u : selectedUsers) {
                openChatWithSingleForward(u);
            }
        }

        int msgCount = isMultiForward ? forwardTexts.size() : 1;
        Toast.makeText(this,
                msgCount + " message" + (msgCount > 1 ? "s" : "") +
                " forwarded to " + selectedUsers.size() + " contact" +
                (selectedUsers.size() > 1 ? "s" : ""),
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
                if (allContacts.isEmpty()) {
                    allContacts.addAll(roomUsers);
                    filtered.addAll(roomUsers);
                    adapter.notifyDataSetChanged();
                    updateEmpty();
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
                    allContacts.clear();
                    allContacts.addAll(fbList);
                    filterContacts(etSearch.getText().toString().trim());
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void filterContacts(String query) {
        filtered.clear();
        if (query.isEmpty()) {
            filtered.addAll(allContacts);
        } else {
            String lq = query.toLowerCase(java.util.Locale.getDefault());
            for (User u : allContacts) {
                if (u.name != null && u.name.toLowerCase(
                        java.util.Locale.getDefault()).contains(lq)) {
                    filtered.add(u);
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

        interface OnToggle { void on(User u); }

        private final List<User>  list;
        private final Set<String> selectedUids;
        private final OnToggle    cb;

        ContactsAdapter(List<User> list, Set<String> selectedUids, OnToggle cb) {
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
            User u = list.get(pos);
            h.tvName.setText(u.name != null ? u.name : "User");

            boolean selected = selectedUids.contains(u.uid);
            h.cbSelect.setChecked(selected);
            // Highlight selected row
            h.itemView.setAlpha(selected ? 1.0f : 0.85f);
            h.itemView.setBackgroundResource(selected
                    ? android.R.color.holo_blue_light
                    : android.R.color.transparent);

            // thumbUrl → 100px WebP, fast load in contact list
            String avatarUrl = (u.thumbUrl != null && !u.thumbUrl.isEmpty())
                ? u.thumbUrl : u.photoUrl;
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                Glide.with(h.ivAvatar.getContext())
                    .load(avatarUrl)
                    .placeholder(R.drawable.ic_person)
                    .circleCrop()
                    .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            h.itemView.setOnClickListener(v -> cb.on(u));
            h.cbSelect.setOnClickListener(v -> cb.on(u));
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
