package com.callx.app.activities;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.SearchResultAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.UserEntity;
import com.callx.app.databinding.ActivitySearchBinding;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * v18: Search by CallX ID  -or-  name.
 * Shows multi-result list (like Instagram search).
 * Tap a result → profile card with Chat / Audio / Video actions.
 */
public class SearchActivity extends AppCompatActivity {

    private ActivitySearchBinding binding;
    private SearchResultAdapter adapter;

    // selected user (for action buttons in detail card)
    private String foundUid, foundName, foundPhoto, foundThumb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // RecyclerView setup
        adapter = new SearchResultAdapter();
        binding.rvResults.setLayoutManager(new LinearLayoutManager(this));
        binding.rvResults.setAdapter(adapter);
        adapter.setListener((uid, name, photo, thumb, callxId) -> {
            // Save selection and show detail card
            foundUid   = uid;
            foundName  = name;
            foundPhoto = photo;
            foundThumb = thumb;
            showDetail(name, callxId, photo);
        });

        // Search button
        binding.btnSearch.setOnClickListener(v -> search());

        // Keyboard search action
        binding.etSearchId.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO) {
                search(); return true;
            }
            return false;
        });

        // Auto-search as user types (debounced 350ms)
        binding.etSearchId.addTextChangedListener(new TextWatcher() {
            private final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
            private final Runnable r = SearchActivity.this::search;
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                h.removeCallbacks(r);
                binding.llResult.setVisibility(View.GONE);
                if (s.toString().trim().length() >= 2) {
                    h.postDelayed(r, 350);
                } else {
                    binding.rvResults.setVisibility(View.GONE);
                    binding.tvStatus.setVisibility(View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Detail card action buttons
        binding.btnOpenChat.setOnClickListener(v -> openChat());
        binding.btnAudioCall.setOnClickListener(v -> startCall(false));
        binding.btnVideoCall.setOnClickListener(v -> startCall(true));
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void search() {
        String query = binding.etSearchId.getText().toString().trim().toLowerCase();
        if (query.isEmpty()) return;

        binding.llResult.setVisibility(View.GONE);
        binding.tvStatus.setVisibility(View.VISIBLE);
        binding.rvResults.setVisibility(View.GONE);

        if (!isOnline()) {
            binding.tvStatus.setText("Offline — local cache mein dhundh raha hoon...");
            searchInRoom(query);
            return;
        }

        binding.tvStatus.setText("Dhundh raha hoon...");

        // 1. Search by callxId (exact)
        // 2. Search by nameLower (if index exists)
        // 3. Fallback: search by name field directly (for users registered without nameLower)
        List<SearchResultAdapter.UserResult> merged = new ArrayList<>();
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        DatabaseReference usersRef = FirebaseUtils.db().getReference("users");

        // Range end for prefix search
        String queryEnd = query.length() > 0
            ? query.substring(0, query.length() - 1)
              + (char)(query.charAt(query.length() - 1) + 1)
            : query;

        // Query 1: by callxId exact match
        usersRef.orderByChild("callxId").equalTo(query)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    for (DataSnapshot c : snap.getChildren()) {
                        SearchResultAdapter.UserResult r = snapToResult(c);
                        if (r != null && !r.uid.equals(myUid) && !containsUid(merged, r.uid))
                            merged.add(r);
                    }

                    // Query 2: by nameLower prefix (for users who have this field)
                    usersRef.orderByChild("nameLower").startAt(query).endAt(queryEnd + "\uf8ff")
                        .limitToFirst(20)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot snap2) {
                                for (DataSnapshot c : snap2.getChildren()) {
                                    SearchResultAdapter.UserResult r = snapToResult(c);
                                    if (r != null && !r.uid.equals(myUid) && !containsUid(merged, r.uid))
                                        merged.add(r);
                                }

                                // Query 3: by name field directly (fallback for users without nameLower)
                                // Use capitalized version too since names are often stored as "Rahul" not "rahul"
                                String queryCapitalized = query.length() > 0
                                    ? Character.toUpperCase(query.charAt(0)) + query.substring(1)
                                    : query;
                                String queryCapEnd = queryCapitalized.length() > 0
                                    ? queryCapitalized.substring(0, queryCapitalized.length() - 1)
                                      + (char)(queryCapitalized.charAt(queryCapitalized.length() - 1) + 1)
                                    : queryCapitalized;

                                usersRef.orderByChild("name").startAt(queryCapitalized).endAt(queryCapEnd + "\uf8ff")
                                    .limitToFirst(20)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override public void onDataChange(DataSnapshot snap3) {
                                            for (DataSnapshot c : snap3.getChildren()) {
                                                SearchResultAdapter.UserResult r = snapToResult(c);
                                                if (r != null && !r.uid.equals(myUid) && !containsUid(merged, r.uid))
                                                    merged.add(r);
                                            }
                                            // Also try lowercase name prefix search
                                            usersRef.orderByChild("name").startAt(query).endAt(queryEnd + "\uf8ff")
                                                .limitToFirst(20)
                                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                                    @Override public void onDataChange(DataSnapshot snap4) {
                                                        for (DataSnapshot c : snap4.getChildren()) {
                                                            SearchResultAdapter.UserResult r = snapToResult(c);
                                                            if (r != null && !r.uid.equals(myUid) && !containsUid(merged, r.uid))
                                                                merged.add(r);
                                                        }
                                                        showResults(merged);
                                                    }
                                                    @Override public void onCancelled(DatabaseError e) {
                                                        showResults(merged);
                                                    }
                                                });
                                        }
                                        @Override public void onCancelled(DatabaseError e) {
                                            showResults(merged);
                                        }
                                    });
                            }
                            @Override public void onCancelled(DatabaseError e) {
                                // nameLower index missing — still run name search
                                usersRef.orderByChild("name").startAt(query).endAt(queryEnd + "\uf8ff")
                                    .limitToFirst(20)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override public void onDataChange(DataSnapshot snap3) {
                                            for (DataSnapshot c : snap3.getChildren()) {
                                                SearchResultAdapter.UserResult r = snapToResult(c);
                                                if (r != null && !r.uid.equals(myUid) && !containsUid(merged, r.uid))
                                                    merged.add(r);
                                            }
                                            showResults(merged);
                                        }
                                        @Override public void onCancelled(DatabaseError e2) {
                                            showResults(merged);
                                        }
                                    });
                            }
                        });
                }
                @Override public void onCancelled(DatabaseError e) {
                    searchInRoom(query);
                }
            });
    }

    private SearchResultAdapter.UserResult snapToResult(DataSnapshot c) {
        String uid   = c.child("uid").getValue(String.class);
        String name  = c.child("name").getValue(String.class);
        String cxId  = c.child("callxId").getValue(String.class);
        String photo = c.child("photoUrl").getValue(String.class);
        String thumb = c.child("thumbUrl").getValue(String.class);
        if (uid == null) return null;
        return new SearchResultAdapter.UserResult(uid, name, cxId, photo, thumb);
    }

    private boolean containsUid(List<SearchResultAdapter.UserResult> list, String uid) {
        for (SearchResultAdapter.UserResult r : list) if (r.uid.equals(uid)) return true;
        return false;
    }

    private void showResults(List<SearchResultAdapter.UserResult> results) {
        binding.tvStatus.setVisibility(View.GONE);
        if (results == null || results.isEmpty()) {
            binding.tvStatus.setText("Koi user nahi mila");
            binding.tvStatus.setVisibility(View.VISIBLE);
            binding.rvResults.setVisibility(View.GONE);
            return;
        }
        adapter.setResults(results);
        binding.rvResults.setVisibility(View.VISIBLE);
    }

    // Offline fallback using Room
    private void searchInRoom(String query) {
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            List<UserEntity> entities = db.userDao().searchByIdOrName(query);
            String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
            List<SearchResultAdapter.UserResult> results = new ArrayList<>();
            if (entities != null) {
                for (UserEntity u : entities) {
                    if (!u.uid.equals(myUid))
                        results.add(new SearchResultAdapter.UserResult(
                            u.uid, u.name, u.callxId, u.photoUrl, u.thumbUrl));
                }
            }
            runOnUiThread(() -> showResults(results));
        });
    }

    private void showDetail(String name, String id, String photo) {
        binding.tvResultName.setText(name != null ? name : "User");
        binding.tvResultId.setText(id != null ? id : "");
        if (photo != null && !photo.isEmpty()) {
            Glide.with(this).load(photo).into(binding.ivResultAvatar);
        }
        binding.rvResults.setVisibility(View.GONE);
        binding.llResult.setVisibility(View.VISIBLE);
        binding.tvStatus.setVisibility(View.GONE);
    }

    private void linkContacts() {
        if (foundUid == null) return;
        String myUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String myName = FirebaseUtils.getCurrentName();
        java.util.Map<String, Object> them = new java.util.HashMap<>();
        them.put("uid", foundUid);
        them.put("name", foundName != null ? foundName : "User");
        if (foundPhoto != null) them.put("photoUrl", foundPhoto);
        if (foundThumb != null) them.put("thumbUrl", foundThumb);
        them.put("at", System.currentTimeMillis());
        FirebaseUtils.getContactsRef(myUid).child(foundUid).updateChildren(them);
        java.util.Map<String, Object> me = new java.util.HashMap<>();
        me.put("uid", myUid);
        me.put("name", myName);
        me.put("at", System.currentTimeMillis());
        FirebaseUtils.getContactsRef(foundUid).child(myUid).updateChildren(me);
    }

    private void openChat() {
        if (foundUid == null) return;
        if (isOnline()) linkContacts();
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("partnerUid",   foundUid);
        i.putExtra("partnerName",  foundName);
        i.putExtra("partnerPhoto", foundPhoto != null ? foundPhoto : "");
        i.putExtra("partnerThumb", foundThumb != null ? foundThumb : "");
        startActivity(i);
        finish();
    }

    private void startCall(boolean video) {
        if (foundUid == null) return;
        if (isOnline()) linkContacts();
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra("partnerUid",  foundUid);
        i.putExtra("partnerName", foundName);
        i.putExtra("isCaller",    true);
        i.putExtra("video",       video);
        startActivity(i);
        finish();
    }
}
