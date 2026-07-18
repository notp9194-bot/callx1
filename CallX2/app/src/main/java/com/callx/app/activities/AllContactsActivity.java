package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.R;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.UserEntity;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

import java.util.*;
import java.util.concurrent.Executors;

/**
 * AllContactsActivity — Calls tab ke "View contacts" button se open hota hai.
 * Saare contacts dikhata hai jahan se directly voice/video call kar sako.
 */
public class AllContactsActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView tvEmpty;
    private EditText etSearch;
    private ContactsCallAdapter adapter;

    private final List<User> allContacts = new ArrayList<>();
    private final List<User> filtered    = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_contacts);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Contacts");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        rv       = findViewById(R.id.rv_all_contacts);
        tvEmpty  = findViewById(R.id.tv_empty_contacts);
        etSearch = findViewById(R.id.et_search_all_contacts);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactsCallAdapter(filtered, this::onVoiceCall, this::onVideoCall);
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int i, int b, int c) {
                filterContacts(s.toString().trim());
            }
        });

        loadFromRoom();
        loadFromFirebase();
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    private void onVoiceCall(User u) {
        Intent i = new Intent().setClassName(this, "com.callx.app.call.CallActivity");
        i.putExtra("partnerUid",  u.uid);
        i.putExtra("partnerName", u.name != null ? u.name : "");
        i.putExtra("isCaller",    true);
        i.putExtra("video",       false);
        startActivity(i);
    }

    private void onVideoCall(User u) {
        Intent i = new Intent().setClassName(this, "com.callx.app.call.CallActivity");
        i.putExtra("partnerUid",  u.uid);
        i.putExtra("partnerName", u.name != null ? u.name : "");
        i.putExtra("isCaller",    true);
        i.putExtra("video",       true);
        startActivity(i);
    }

    private void loadFromRoom() {
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            List<UserEntity> cached = db.userDao().getAllUsersSync();
            if (cached == null || cached.isEmpty()) return;
            List<User> list = new ArrayList<>();
            for (UserEntity e : cached) {
                if (myUid.equals(e.uid)) continue;
                User u = new User();
                u.uid      = e.uid;
                u.name     = e.name;
                u.photoUrl = e.photoUrl;
                u.thumbUrl = e.thumbUrl;
                list.add(u);
            }
            runOnUiThread(() -> {
                if (allContacts.isEmpty()) {
                    allContacts.addAll(list);
                    filtered.addAll(list);
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
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        String uid = c.getKey();
                        if (uid != null && !uid.equals(myUid)) uids.add(uid);
                    }
                    if (uids.isEmpty()) return;
                    List<User> fbList = new ArrayList<>();
                    final int[] done = {0};
                    for (String uid : uids) {
                        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(
                            new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot us) {
                                    User u = us.getValue(User.class);
                                    if (u != null) {
                                        if (u.uid == null) u.uid = uid;
                                        fbList.add(u);
                                    }
                                    done[0]++;
                                    if (done[0] >= uids.size()) {
                                        fbList.sort((a, b) -> {
                                            String na = a.name != null ? a.name : "";
                                            String nb = b.name != null ? b.name : "";
                                            return na.compareToIgnoreCase(nb);
                                        });
                                        runOnUiThread(() -> {
                                            allContacts.clear();
                                            allContacts.addAll(fbList);
                                            filterContacts(etSearch.getText().toString().trim());
                                        });
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    done[0]++;
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void filterContacts(String q) {
        filtered.clear();
        if (q.isEmpty()) {
            filtered.addAll(allContacts);
        } else {
            String lq = q.toLowerCase(Locale.getDefault());
            for (User u : allContacts) {
                if (u.name != null && u.name.toLowerCase(Locale.getDefault()).contains(lq))
                    filtered.add(u);
            }
        }
        adapter.notifyDataSetChanged();
        updateEmpty();
    }

    private void updateEmpty() {
        tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Adapter ────────────────────────────────────────────────────────────
    static class ContactsCallAdapter extends RecyclerView.Adapter<ContactsCallAdapter.VH> {

        interface OnCall { void call(User u); }

        private final List<User> list;
        private final OnCall onVoice, onVideo;

        ContactsCallAdapter(List<User> list, OnCall onVoice, OnCall onVideo) {
            this.list    = list;
            this.onVoice = onVoice;
            this.onVideo = onVideo;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact_call, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            User u = list.get(pos);
            h.tvName.setText(u.name != null ? u.name : "User");

            String avatar = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            if (avatar != null && !avatar.isEmpty()) {
                Glide.with(h.ivAvatar.getContext()).load(avatar)
                    .placeholder(R.drawable.ic_person).circleCrop()
                    .override(96, 96).into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            h.btnVoice.setOnClickListener(v -> onVoice.call(u));
            h.btnVideo.setOnClickListener(v -> onVideo.call(u));
            h.itemView.setOnClickListener(v -> onVoice.call(u));
        }

        @Override public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvName;
            ImageView btnVoice, btnVideo;
            VH(@NonNull View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_avatar);
                tvName   = v.findViewById(R.id.tv_name);
                btnVoice = v.findViewById(R.id.btn_voice_call);
                btnVideo = v.findViewById(R.id.btn_video_call);
            }
        }
    }
}
