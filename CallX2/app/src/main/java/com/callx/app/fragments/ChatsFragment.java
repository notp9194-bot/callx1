package com.callx.app.fragments;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.adapters.ChatListAdapter;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

public class ChatsFragment extends Fragment {

    private final List<User> contacts = new ArrayList<>();
    private ChatListAdapter adapter;
    private View emptyState, bannerRequests;
    private final Set<String> specialRequestUids = new HashSet<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_chats);
        emptyState     = v.findViewById(R.id.empty_state);
        bannerRequests = v.findViewById(R.id.banner_requests);
        if (bannerRequests != null) bannerRequests.setVisibility(View.GONE);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ChatListAdapter(contacts);
        rv.setAdapter(adapter);
        loadContacts();
        loadSpecialRequests();
        return v;
    }

    private void loadContacts() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseUtils.getContactsRef(uid)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    contacts.clear();
                    for (DataSnapshot c : snap.getChildren()) {
                        User u = c.getValue(User.class);
                        if (u != null) {
                            if (u.uid == null) u.uid = c.getKey();
                            // Enrich: if name/photo missing, fetch from users node
                            if ((u.name == null || u.name.isEmpty()) && u.uid != null)
                                enrichContact(u, uid);
                            contacts.add(u);
                        }
                    }
                    sortLatestFirst();
                    if (adapter != null) adapter.notifyDataSetChanged();
                    if (emptyState != null)
                        emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // Fix: fetch user name/photo if contact entry is incomplete (e.g. after delete+new msg)
    private void enrichContact(User u, String myUid) {
        if (u.uid == null) return;
        FirebaseUtils.getUserRef(u.uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String name  = snap.child("name").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    boolean changed = false;
                    if (name != null && !name.isEmpty() && (u.name == null || u.name.isEmpty())) {
                        u.name = name; changed = true;
                        FirebaseUtils.getContactsRef(myUid).child(u.uid).child("name").setValue(name);
                    }
                    if (photo != null && !photo.isEmpty() && u.photoUrl == null) {
                        u.photoUrl = photo; changed = true;
                        FirebaseUtils.getContactsRef(myUid).child(u.uid).child("photoUrl").setValue(photo);
                    }
                    if (changed && adapter != null) adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void loadSpecialRequests() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseUtils.db().getReference("specialRequests").child(uid)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    specialRequestUids.clear();
                    for (DataSnapshot c : snap.getChildren())
                        if (c.getKey() != null) specialRequestUids.add(c.getKey());
                    if (adapter != null) {
                        adapter.setSpecialRequestSenders(specialRequestUids);
                        sortLatestFirst();
                        adapter.notifyDataSetChanged();
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // Change 5: sort latest message at TOP
    private void sortLatestFirst() {
        Collections.sort(contacts, (a, b) -> {
            boolean aS = a.uid != null && specialRequestUids.contains(a.uid);
            boolean bS = b.uid != null && specialRequestUids.contains(b.uid);
            if (aS != bS) return aS ? -1 : 1;
            long la = a.lastMessageAt != null ? a.lastMessageAt :
                      (a.lastSeen != null ? a.lastSeen : 0L);
            long lb = b.lastMessageAt != null ? b.lastMessageAt :
                      (b.lastSeen != null ? b.lastSeen : 0L);
            return Long.compare(lb, la);
        });
    }
}
