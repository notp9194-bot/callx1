package com.callx.app.broadcast;

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
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CreateBroadcastActivity — Create a new broadcast list OR edit an existing one.
 *
 * Extras (for editing):
 *   broadcastListId   — existing list ID
 *   broadcastListName — existing list name
 */
public class CreateBroadcastActivity extends AppCompatActivity {

    // Max recipients per broadcast list (production guard)
    private static final int MAX_RECIPIENTS = 256;

    private EditText         etListName;
    private EditText         etSearch;
    private RecyclerView     rvContacts;
    private Button           btnSave;
    private TextView         tvSelectedCount;
    private View             tvEmpty;

    private RecipientSelectAdapter adapter;

    // Contact data
    private final List<ContactItem>  allContacts = new ArrayList<>();
    private final List<ContactItem>  filtered    = new ArrayList<>();
    private final Set<String>        selectedUids = new HashSet<>();

    // Edit mode
    private String editListId;
    private String editListName;
    private boolean isEditMode = false;

    private String          myUid;
    private DatabaseReference listsRef;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_broadcast);

        editListId   = getIntent().getStringExtra(BroadcastListsActivity.EXTRA_LIST_ID);
        editListName = getIntent().getStringExtra(BroadcastListsActivity.EXTRA_LIST_NAME);
        isEditMode   = editListId != null;

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(isEditMode ? "Edit Broadcast List" : "New Broadcast List");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        if (myUid == null) { finish(); return; }

        listsRef = FirebaseDatabase.getInstance()
                .getReference("broadcast_lists").child(myUid);

        etListName     = findViewById(R.id.et_broadcast_name);
        etSearch       = findViewById(R.id.et_search_recipients);
        rvContacts     = findViewById(R.id.rv_recipient_select);
        btnSave        = findViewById(R.id.btn_save_broadcast);
        tvSelectedCount = findViewById(R.id.tv_selected_count);
        tvEmpty        = findViewById(R.id.tv_empty_contacts);

        if (isEditMode && editListName != null) etListName.setText(editListName);

        rvContacts.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecipientSelectAdapter(filtered, selectedUids, uid -> {
            if (selectedUids.contains(uid)) selectedUids.remove(uid);
            else {
                if (selectedUids.size() >= MAX_RECIPIENTS) {
                    Toast.makeText(this,
                            "Maximum " + MAX_RECIPIENTS + " recipients allowed",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                selectedUids.add(uid);
            }
            updateSelectedCount();
            adapter.notifyDataSetChanged();
        });
        rvContacts.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterContacts(s.toString().trim());
            }
        });

        btnSave.setOnClickListener(v -> saveBroadcastList());

        loadContacts();
    }

    // ── Load contacts from Firebase ───────────────────────────────────────────
    private void loadContacts() {
        // Contacts jo maine block kiye hain unhe broadcast recipient list se hata do
        FirebaseUtils.getBlocksRef(myUid).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot blockSnap) {
                        java.util.Set<String> blockedByMe = new HashSet<>();
                        for (DataSnapshot b : blockSnap.getChildren()) {
                            if (Boolean.TRUE.equals(b.getValue(Boolean.class)) && b.getKey() != null)
                                blockedByMe.add(b.getKey());
                        }
                        loadContactsFiltered(blockedByMe);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        loadContactsFiltered(new HashSet<>());
                    }
                });
    }

    private void loadContactsFiltered(java.util.Set<String> blockedByMe) {
        FirebaseUtils.getContactsRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        allContacts.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            String uid = c.getKey();
                            if (myUid.equals(uid) || blockedByMe.contains(uid)) continue;
                            String name  = c.child("name").getValue(String.class);
                            String photo = c.child("thumbUrl").getValue(String.class);
                            if (photo == null || photo.isEmpty())
                                photo = c.child("photoUrl").getValue(String.class);
                            if (name == null || name.isEmpty()) name = "User";
                            allContacts.add(new ContactItem(uid, name, photo));
                        }
                        // Sort alphabetically
                        allContacts.sort((a, b) ->
                                a.name.compareToIgnoreCase(b.name));

                        // If editing, pre-select existing recipients
                        if (isEditMode) loadExistingRecipients();
                        else            filterContacts("");
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        Toast.makeText(CreateBroadcastActivity.this,
                                "Contacts load nahi hue", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void loadExistingRecipients() {
        listsRef.child(editListId).child("recipients")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        for (DataSnapshot r : snap.getChildren()) {
                            Boolean val = r.getValue(Boolean.class);
                            if (Boolean.TRUE.equals(val)) selectedUids.add(r.getKey());
                        }
                        filterContacts("");
                        updateSelectedCount();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        filterContacts("");
                    }
                });
    }

    // ── Filter ────────────────────────────────────────────────────────────────
    private void filterContacts(String query) {
        filtered.clear();
        if (query.isEmpty()) {
            filtered.addAll(allContacts);
        } else {
            String lq = query.toLowerCase(java.util.Locale.getDefault());
            for (ContactItem c : allContacts) {
                if (c.name.toLowerCase(java.util.Locale.getDefault()).contains(lq))
                    filtered.add(c);
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        rvContacts.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateSelectedCount() {
        int n = selectedUids.size();
        tvSelectedCount.setText(n + " selected");
        btnSave.setText("Save (" + n + ")");
        btnSave.setEnabled(n > 0 && !etListName.getText().toString().trim().isEmpty());
    }

    // ── Save / Update ─────────────────────────────────────────────────────────
    private void saveBroadcastList() {
        String name = etListName.getText().toString().trim();
        if (name.isEmpty()) {
            etListName.setError("List ka naam do");
            etListName.requestFocus();
            return;
        }
        if (selectedUids.isEmpty()) {
            Toast.makeText(this, "Kam se kam ek recipient select karo", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSave.setEnabled(false);
        btnSave.setText("Saving…");

        Map<String, Boolean> recipientMap = new HashMap<>();
        for (String uid : selectedUids) recipientMap.put(uid, true);

        if (isEditMode) {
            // Update existing
            Map<String, Object> updates = new HashMap<>();
            updates.put("name",       name);
            updates.put("recipients", recipientMap);
            updates.put("updatedAt",  System.currentTimeMillis());
            listsRef.child(editListId).updateChildren(updates)
                    .addOnSuccessListener(a -> {
                        Toast.makeText(this, "List update ho gayi ✓", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .addOnFailureListener(e -> {
                        btnSave.setEnabled(true);
                        btnSave.setText("Save (" + selectedUids.size() + ")");
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        } else {
            // Create new
            String listId = listsRef.push().getKey();
            if (listId == null) {
                btnSave.setEnabled(true);
                Toast.makeText(this, "Error: List ID generate nahi hui", Toast.LENGTH_SHORT).show();
                return;
            }
            long now = System.currentTimeMillis();
            BroadcastList bl = new BroadcastList(listId, name, now);
            bl.recipients = recipientMap;

            listsRef.child(listId).setValue(bl)
                    .addOnSuccessListener(a -> {
                        Toast.makeText(this,
                                "Broadcast list \"" + name + "\" bana di ✓",
                                Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .addOnFailureListener(e -> {
                        btnSave.setEnabled(true);
                        btnSave.setText("Save (" + selectedUids.size() + ")");
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ContactItem
    // ─────────────────────────────────────────────────────────────────────────
    static class ContactItem {
        String uid, name, photoUrl;
        ContactItem(String uid, String name, String photoUrl) {
            this.uid      = uid;
            this.name     = name;
            this.photoUrl = photoUrl;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecipientSelectAdapter
    // ─────────────────────────────────────────────────────────────────────────
    interface OnToggle { void toggle(String uid); }

    static class RecipientSelectAdapter
            extends RecyclerView.Adapter<RecipientSelectAdapter.VH> {

        private final List<ContactItem> list;
        private final Set<String>       selected;
        private final OnToggle          toggle;

        RecipientSelectAdapter(List<ContactItem> list, Set<String> selected, OnToggle toggle) {
            this.list     = list;
            this.selected = selected;
            this.toggle   = toggle;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recipient_select, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ContactItem c   = list.get(pos);
            boolean sel     = selected.contains(c.uid);

            h.tvName.setText(c.name);
            h.cbSelect.setChecked(sel);

            if (c.photoUrl != null && !c.photoUrl.isEmpty()) {
                Glide.with(h.ivAvatar.getContext())
                        .load(c.photoUrl)
                        .placeholder(R.drawable.ic_person)
                        .circleCrop()
                    .override(96, 96)
                        .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            h.itemView.setBackgroundResource(sel
                    ? R.color.broadcast_selected_bg
                    : android.R.color.transparent);

            h.itemView.setOnClickListener(v -> toggle.toggle(c.uid));
            h.cbSelect.setOnClickListener(v -> toggle.toggle(c.uid));
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
