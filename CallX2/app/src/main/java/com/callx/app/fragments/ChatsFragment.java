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
import java.util.ArrayList;
import java.util.List;
public class ChatsFragment extends Fragment {
    private final List<User> contacts = new ArrayList<>();
    private ChatListAdapter adapter;
    private View emptyState, bannerRequests;
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_chats);
        emptyState     = v.findViewById(R.id.empty_state);
        // Request system hata diya gaya hai — banner permanently chhupa do
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
                            contacts.add(u);
                        }
                    }
                    // (Feature 18) special-request senders sabse upar
                    sortSpecialFirst();
                    adapter.notifyDataSetChanged();
                    emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    // Feature 18/19 — listen to specialRequests/{me}/{anyone}
    private final java.util.Set<String> specialRequestUids =
        new java.util.HashSet<>();
    private void loadSpecialRequests() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseUtils.db().getReference("specialRequests").child(uid)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    specialRequestUids.clear();
                    for (DataSnapshot c : snap.getChildren()) {
                        if (c.getKey() != null) specialRequestUids.add(c.getKey());
                    }
                    if (adapter != null) {
                        adapter.setSpecialRequestSenders(specialRequestUids);
                        sortSpecialFirst();
                        adapter.notifyDataSetChanged();
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private void sortSpecialFirst() {
        java.util.Collections.sort(contacts, (a, b) -> {
            boolean aS = a.uid != null && specialRequestUids.contains(a.uid);
            boolean bS = b.uid != null && specialRequestUids.contains(b.uid);
            if (aS != bS) return aS ? -1 : 1;
            long la = a.lastMessageAt == null ? 0 : a.lastMessageAt;
            long lb = b.lastMessageAt == null ? 0 : b.lastMessageAt;
            return Long.compare(lb, la);
        });
    }
}
