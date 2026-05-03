package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

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
import java.util.List;
import java.util.concurrent.Executors;

/**
 * ContactsActivity v18 — Forward Message Contact Picker.
 *
 * HIGH IMPACT FIX: Pehle yeh Activity sirf finish() call karta tha —
 * forward feature completely broken tha. Ab properly kaam karta hai.
 *
 * Flow:
 *   1. Room se contacts load karo (offline-first, instant)
 *   2. Firebase se sync karo (online update)
 *   3. User contact select kare → ChatActivity open karo forward payload ke saath
 */
public class ContactsActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView     tvEmpty;
    private EditText     etSearch;
    private ContactsAdapter adapter;

    private final List<User> allContacts = new ArrayList<>();
    private final List<User> filtered    = new ArrayList<>();

    private String forwardText;
    private String forwardType;
    private String forwardMedia;
    private String forwardFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Forward to\u2026");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        forwardText     = getIntent().getStringExtra("forwardText");
        forwardType     = getIntent().getStringExtra("forwardType");
        forwardMedia    = getIntent().getStringExtra("forwardMedia");
        forwardFileName = getIntent().getStringExtra("forwardFileName");

        rv       = findViewById(R.id.rv_contacts);
        tvEmpty  = findViewById(R.id.tv_empty);
        etSearch = findViewById(R.id.et_search_contacts);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactsAdapter(filtered, this::onContactSelected);
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterContacts(s.toString().trim());
            }
        });

        loadFromRoom();
        loadFromFirebase();
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

    private void onContactSelected(User u) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("partnerUid",      u.uid);
        i.putExtra("partnerName",     u.name != null ? u.name : "");
        i.putExtra("partnerPhoto",    u.photoUrl != null ? u.photoUrl : "");
        i.putExtra("forwardText",     forwardText);
        i.putExtra("forwardType",     forwardType);
        i.putExtra("forwardMedia",    forwardMedia);
        i.putExtra("forwardFileName", forwardFileName);
        startActivity(i);
        finish();
    }

    // ── Inline RecyclerView Adapter ───────────────────────────────────────
    private static class ContactsAdapter
            extends RecyclerView.Adapter<ContactsAdapter.VH> {

        interface OnClick { void on(User u); }

        private final List<User> list;
        private final OnClick    cb;

        ContactsAdapter(List<User> list, OnClick cb) {
            this.list = list;
            this.cb   = cb;
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
            if (u.photoUrl != null && !u.photoUrl.isEmpty()) {
                Glide.with(h.ivAvatar.getContext())
                    .load(u.photoUrl)
                    .placeholder(R.drawable.ic_person)
                    .circleCrop()
                    .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }
            h.itemView.setOnClickListener(v -> cb.on(u));
        }

        @Override public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar;
            TextView  tvName;
            VH(@NonNull View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_member_avatar);
                tvName   = v.findViewById(R.id.tv_member_name);
            }
        }
    }
}
