package com.callx.app.followers;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ReelCloseFriendsActivity — Manage Close Friends list for restricted reel sharing.
 *
 * Features:
 *  ✅ Shows current close friends with avatar + name
 *  ✅ Remove friend from close friends list
 *  ✅ Search all followers to add to close friends
 *  ✅ Add button on each search result
 *  ✅ Count badge (max 150 per Instagram convention)
 *  ✅ Persisted at users/{uid}/closeFriends/{friendUid}
 *  ✅ Real-time listener
 */
public class ReelCloseFriendsActivity extends AppCompatActivity {

    private static final int MAX_CLOSE_FRIENDS = 150;

    private ImageButton  btnBack;
    private EditText     etSearch;
    private RecyclerView rvCloseFriends, rvSearchResults;
    private ProgressBar  progress;
    private TextView     tvCount, tvSearchEmpty, tvCloseFriendsEmpty;

    private String myUid;
    private final Map<String, Friend> closeFriendsMap = new LinkedHashMap<>();
    private final List<Friend> searchResults = new ArrayList<>();
    private CloseFriendAdapter cfAdapter;
    private SearchAdapter      searchAdapter;
    private DatabaseReference  cfRef;
    private ValueEventListener cfListener;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_close_friends);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        cfRef = FirebaseUtils.getUserRef(myUid).child("closeFriends");
        bindViews();
        loadCloseFriends();
    }

    private void bindViews() {
        btnBack             = findViewById(R.id.btn_cf_back);
        etSearch            = findViewById(R.id.et_cf_search);
        rvCloseFriends      = findViewById(R.id.rv_close_friends);
        rvSearchResults     = findViewById(R.id.rv_cf_search_results);
        progress            = findViewById(R.id.progress_cf);
        tvCount             = findViewById(R.id.tv_cf_count);
        tvSearchEmpty       = findViewById(R.id.tv_cf_search_empty);
        tvCloseFriendsEmpty = findViewById(R.id.tv_cf_empty);

        btnBack.setOnClickListener(v -> finish());

        cfAdapter = new CloseFriendAdapter(new ArrayList<>(closeFriendsMap.values()), this::removeFriend);
        rvCloseFriends.setLayoutManager(new LinearLayoutManager(this));
        rvCloseFriends.setAdapter(cfAdapter);

        searchAdapter = new SearchAdapter(searchResults, this::addFriend);
        rvSearchResults.setLayoutManager(new LinearLayoutManager(this));
        rvSearchResults.setAdapter(searchAdapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().trim();
                if (q.length() >= 2) searchFollowers(q);
                else { searchResults.clear(); searchAdapter.notifyDataSetChanged(); rvSearchResults.setVisibility(View.GONE); }
            }
        });
    }

    private void loadCloseFriends() {
        progress.setVisibility(View.VISIBLE);
        cfListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                closeFriendsMap.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    Friend f = new Friend();
                    f.uid   = s.getKey();
                    f.name  = s.child("name").getValue(String.class);
                    f.photo = s.child("photo").getValue(String.class);
                    if (f.name != null) closeFriendsMap.put(f.uid, f);
                }
                refreshCFList();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isFinishing()) progress.setVisibility(View.GONE);
            }
        };
        cfRef.addValueEventListener(cfListener);
    }

    private void refreshCFList() {
        List<Friend> list = new ArrayList<>(closeFriendsMap.values());
        cfAdapter.setItems(list);
        tvCount.setText(list.size() + "/" + MAX_CLOSE_FRIENDS + " close friends");
        tvCloseFriendsEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        searchAdapter.setCloseFriendIds(closeFriendsMap.keySet());
    }

    private void searchFollowers(String query) {
        FirebaseUtils.db().getReference("reelFollowers").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    List<String> followerUids = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) followerUids.add(s.getKey());
                    if (followerUids.isEmpty()) {
                        searchResults.clear(); searchAdapter.notifyDataSetChanged();
                        tvSearchEmpty.setVisibility(View.VISIBLE); rvSearchResults.setVisibility(View.GONE); return;
                    }
                    searchUsersByName(query, followerUids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void searchUsersByName(String query, List<String> uids) {
        FirebaseUtils.db().getReference("users")
            .orderByChild("name").startAt(query).endAt(query + "\uf8ff").limitToFirst(20)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    searchResults.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        String uid = s.getKey();
                        if (!uids.contains(uid)) continue;
                        Friend f = new Friend();
                        f.uid   = uid;
                        f.name  = s.child("name").getValue(String.class);
                        String _thumb = s.child("thumbUrl").getValue(String.class);
                        String _photo = s.child("photoUrl").getValue(String.class);
                        f.photo = (_thumb != null && !_thumb.isEmpty()) ? _thumb : _photo;
                        if (f.name != null) searchResults.add(f);
                    }
                    searchAdapter.notifyDataSetChanged();
                    rvSearchResults.setVisibility(searchResults.isEmpty() ? View.GONE : View.VISIBLE);
                    tvSearchEmpty.setVisibility(searchResults.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void addFriend(Friend f) {
        if (closeFriendsMap.size() >= MAX_CLOSE_FRIENDS) {
            Toast.makeText(this, "Max " + MAX_CLOSE_FRIENDS + " close friends", Toast.LENGTH_SHORT).show(); return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("name", f.name); data.put("photo", f.photo != null ? f.photo : "");
        cfRef.child(f.uid).setValue(data);
        Toast.makeText(this, f.name + " added to Close Friends", Toast.LENGTH_SHORT).show();
    }

    private void removeFriend(Friend f) {
        cfRef.child(f.uid).removeValue();
        Toast.makeText(this, f.name + " removed", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        if (cfListener != null) cfRef.removeEventListener(cfListener);
        super.onDestroy();
    }

    static class Friend { String uid, name, photo; }

    static class CloseFriendAdapter extends RecyclerView.Adapter<CloseFriendAdapter.VH> {
        private List<Friend> items = new ArrayList<>();
        private final java.util.function.Consumer<Friend> onRemove;
        CloseFriendAdapter(List<Friend> i, java.util.function.Consumer<Friend> r) { items = i; onRemove = r; }
        void setItems(List<Friend> i) { items = new ArrayList<>(i); notifyDataSetChanged(); }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_close_friend, p, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Friend f = items.get(pos);
            h.tvName.setText(f.name);
            if (f.photo != null && !f.photo.isEmpty()) Glide.with(h.iv).load(f.photo).circleCrop().placeholder(R.drawable.ic_person).into(h.iv);
            h.btnRemove.setOnClickListener(v -> onRemove.accept(f));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            CircleImageView iv; TextView tvName; ImageButton btnRemove;
            VH(View v) { super(v); iv = v.findViewById(R.id.iv_cf_avatar); tvName = v.findViewById(R.id.tv_cf_name); btnRemove = v.findViewById(R.id.btn_cf_remove); }
        }
    }

    static class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.VH> {
        private final List<Friend> items;
        private final java.util.function.Consumer<Friend> onAdd;
        private Set<String> cfIds = new HashSet<>();
        SearchAdapter(List<Friend> i, java.util.function.Consumer<Friend> a) { items = i; onAdd = a; }
        void setCloseFriendIds(Set<String> ids) { cfIds = new HashSet<>(ids); notifyDataSetChanged(); }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_close_friend, p, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Friend f = items.get(pos);
            h.tvName.setText(f.name);
            if (f.photo != null && !f.photo.isEmpty()) Glide.with(h.iv).load(f.photo).circleCrop().placeholder(R.drawable.ic_person).into(h.iv);
            boolean isCf = cfIds.contains(f.uid);
            h.btnRemove.setImageResource(isCf ? R.drawable.ic_close : R.drawable.ic_person_add);
            h.btnRemove.setEnabled(!isCf);
            h.btnRemove.setOnClickListener(v -> { if (!isCf) onAdd.accept(f); });
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            CircleImageView iv; TextView tvName; ImageButton btnRemove;
            VH(View v) { super(v); iv = v.findViewById(R.id.iv_cf_avatar); tvName = v.findViewById(R.id.tv_cf_name); btnRemove = v.findViewById(R.id.btn_cf_remove); }
        }
    }
}
