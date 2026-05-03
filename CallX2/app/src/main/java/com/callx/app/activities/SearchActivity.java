package com.callx.app.activities;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.UserEntity;
import com.callx.app.databinding.ActivitySearchBinding;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class SearchActivity extends AppCompatActivity {
    private ActivitySearchBinding binding;
    private String foundUid, foundName, foundPhoto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.btnSearch.setOnClickListener(v -> search());
        binding.etSearchId.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO) {
                search(); return true;
            }
            return false;
        });
        binding.etSearchId.addTextChangedListener(new TextWatcher() {
            private final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
            private final Runnable r = SearchActivity.this::search;
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                h.removeCallbacks(r);
                if (s.toString().trim().length() >= 3) h.postDelayed(r, 300);
                else binding.llResult.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
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
        String id = binding.etSearchId.getText().toString().trim().toLowerCase();
        if (id.isEmpty()) {
            Toast.makeText(this, "ID daalo", Toast.LENGTH_SHORT).show(); return;
        }
        binding.tvStatus.setVisibility(View.VISIBLE);
        binding.llResult.setVisibility(View.GONE);

        if (!isOnline()) {
            // v17: Offline — Room UserDao se dhundho
            binding.tvStatus.setText("Offline — local cache mein dhundh raha hoon...");
            searchInRoom(id);
            return;
        }

        binding.tvStatus.setText("Dhundh raha hoon...");
        FirebaseUtils.db().getReference("users").orderByChild("callxId").equalTo(id)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (!snap.exists()) {
                        binding.tvStatus.setText("Koi user nahi mila");
                        return;
                    }
                    for (DataSnapshot c : snap.getChildren()) {
                        foundUid   = c.child("uid").getValue(String.class);
                        foundName  = c.child("name").getValue(String.class);
                        foundPhoto = c.child("photoUrl").getValue(String.class);
                        String foundId = c.child("callxId").getValue(String.class);
                        String myUid = FirebaseAuth.getInstance()
                            .getCurrentUser().getUid();
                        if (myUid.equals(foundUid)) {
                            binding.tvStatus.setText("Ye to aapki hi ID hai 🙂");
                            return;
                        }
                        showResult(foundName, foundId, foundPhoto);
                        break;
                    }
                }
                @Override public void onCancelled(DatabaseError e) {
                    // Firebase fail hua — Room fallback
                    searchInRoom(id);
                }
            });
    }

    // v17: Room UserDao se search karo (offline fallback)
    private void searchInRoom(String callxId) {
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            List<UserEntity> results = db.userDao().searchByCallxId(callxId);
            runOnUiThread(() -> {
                if (results == null || results.isEmpty()) {
                    binding.tvStatus.setText("Nahi mila — online hoke try karo");
                    return;
                }
                UserEntity u = results.get(0);
                foundUid   = u.uid;
                foundName  = u.name;
                foundPhoto = u.photoUrl;
                String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
                if (myUid.equals(foundUid)) {
                    binding.tvStatus.setText("Ye to aapki hi ID hai 🙂");
                    return;
                }
                showResult(u.name, u.callxId, u.photoUrl);
            });
        });
    }

    private void showResult(String name, String id, String photo) {
        binding.tvResultName.setText(name == null ? "User" : name);
        binding.tvResultId.setText(id == null ? "" : id);
        if (photo != null && !photo.isEmpty()) {
            Glide.with(SearchActivity.this).load(photo).into(binding.ivResultAvatar);
        }
        binding.llResult.setVisibility(View.VISIBLE);
        binding.tvStatus.setVisibility(View.GONE);
    }

    private void linkContacts() {
        if (foundUid == null) return;
        String myUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String myName = FirebaseUtils.getCurrentName();
        Map<String, Object> them = new HashMap<>();
        them.put("uid", foundUid);
        them.put("name", foundName == null ? "User" : foundName);
        if (foundPhoto != null) them.put("photoUrl", foundPhoto);
        them.put("at", System.currentTimeMillis());
        FirebaseUtils.getContactsRef(myUid).child(foundUid).updateChildren(them);
        Map<String, Object> me = new HashMap<>();
        me.put("uid", myUid);
        me.put("name", myName);
        me.put("at", System.currentTimeMillis());
        FirebaseUtils.getContactsRef(foundUid).child(myUid).updateChildren(me);
    }

    private void openChat() {
        if (foundUid == null) return;
        if (isOnline()) linkContacts();
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("partnerUid", foundUid);
        i.putExtra("partnerName", foundName);
        startActivity(i);
        finish();
    }

    private void startCall(boolean video) {
        if (foundUid == null) return;
        if (isOnline()) linkContacts();
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra("partnerUid", foundUid);
        i.putExtra("partnerName", foundName);
        i.putExtra("isCaller", true);
        i.putExtra("video", video);
        startActivity(i);
        finish();
    }
}
public class SearchActivity extends AppCompatActivity {
    private ActivitySearchBinding binding;
    private String foundUid, foundName, foundPhoto;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.btnSearch.setOnClickListener(v -> search());
        // ENTER / Done press par bhi search ho jaye
        binding.etSearchId.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO) {
                search(); return true;
            }
            return false;
        });
        // Type karte hi (300ms debounce) auto-search
        binding.etSearchId.addTextChangedListener(new TextWatcher() {
            private final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
            private final Runnable r = SearchActivity.this::search;
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                h.removeCallbacks(r);
                if (s.toString().trim().length() >= 3) h.postDelayed(r, 300);
                else binding.llResult.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        binding.btnOpenChat.setOnClickListener(v -> openChat());
        binding.btnAudioCall.setOnClickListener(v -> startCall(false));
        binding.btnVideoCall.setOnClickListener(v -> startCall(true));
    }
    private void search() {
        String id = binding.etSearchId.getText().toString().trim().toLowerCase();
        if (id.isEmpty()) {
            Toast.makeText(this, "ID daalo", Toast.LENGTH_SHORT).show(); return;
        }
        binding.tvStatus.setVisibility(View.VISIBLE);
        binding.tvStatus.setText("Dhundh raha hoon...");
        binding.llResult.setVisibility(View.GONE);
        FirebaseUtils.db().getReference("users").orderByChild("callxId").equalTo(id)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (!snap.exists()) {
                        binding.tvStatus.setText("Koi user nahi mila");
                        return;
                    }
                    for (DataSnapshot c : snap.getChildren()) {
                        foundUid   = c.child("uid").getValue(String.class);
                        foundName  = c.child("name").getValue(String.class);
                        foundPhoto = c.child("photoUrl").getValue(String.class);
                        String foundId = c.child("callxId").getValue(String.class);
                        // Apni hi ID search kar liya — show mat karo
                        String myUid = FirebaseAuth.getInstance()
                            .getCurrentUser().getUid();
                        if (myUid.equals(foundUid)) {
                            binding.tvStatus.setText("Ye to aapki hi ID hai 🙂");
                            return;
                        }
                        binding.tvResultName.setText(foundName == null ? "User" : foundName);
                        binding.tvResultId.setText(foundId == null ? "" : foundId);
                        if (foundPhoto != null && !foundPhoto.isEmpty()) {
                            Glide.with(SearchActivity.this).load(foundPhoto)
                                .into(binding.ivResultAvatar);
                        }
                        binding.llResult.setVisibility(View.VISIBLE);
                        binding.tvStatus.setVisibility(View.GONE);
                        break;
                    }
                }
                @Override public void onCancelled(DatabaseError e) {
                    binding.tvStatus.setText("Error: " + e.getMessage());
                }
            });
    }
    // Dono users ke contacts me ek-dusre ka entry add karta hai
    private void linkContacts() {
        if (foundUid == null) return;
        String myUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String myName = FirebaseUtils.getCurrentName();
        Map<String, Object> them = new HashMap<>();
        them.put("uid", foundUid);
        them.put("name", foundName == null ? "User" : foundName);
        if (foundPhoto != null) them.put("photoUrl", foundPhoto);
        them.put("at", System.currentTimeMillis());
        FirebaseUtils.getContactsRef(myUid).child(foundUid).updateChildren(them);
        Map<String, Object> me = new HashMap<>();
        me.put("uid", myUid);
        me.put("name", myName);
        me.put("at", System.currentTimeMillis());
        FirebaseUtils.getContactsRef(foundUid).child(myUid).updateChildren(me);
    }
    private void openChat() {
        if (foundUid == null) return;
        linkContacts();
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("partnerUid", foundUid);
        i.putExtra("partnerName", foundName);
        startActivity(i);
        finish();
    }
    private void startCall(boolean video) {
        if (foundUid == null) return;
        linkContacts();
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra("partnerUid", foundUid);
        i.putExtra("partnerName", foundName);
        i.putExtra("isCaller", true);
        i.putExtra("video", video);
        startActivity(i);
        finish();
    }
}
