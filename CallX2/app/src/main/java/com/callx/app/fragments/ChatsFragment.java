package com.callx.app.fragments;

import android.app.AlertDialog;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.adapters.ChatListAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.*;
import java.util.concurrent.Executors;

/**
 * ChatsFragment v15 — Offline-First
 *
 * Flow:
 *   1. onCreateView → loadFromRoom() immediately (zero-latency offline display)
 *   2. loadContacts() → Firebase listener → saves to Room → UI refreshes via LiveData
 *   3. If offline: Room data already showing, no blank screen
 */
public class ChatsFragment extends Fragment implements ChatListAdapter.SelectionListener {

    private final List<User> contacts = new ArrayList<>();
    private ChatListAdapter adapter;
    private View emptyState;

    private LinearLayout llSelectionBar;
    private TextView tvSelectedCount;

    private final Set<String> specialRequestUids = new HashSet<>();

    // v15: track online state for UI
    private boolean isOnline = true;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);
        RecyclerView rv  = v.findViewById(R.id.rv_chats);
        emptyState       = v.findViewById(R.id.empty_state);
        llSelectionBar   = v.findViewById(R.id.ll_selection_bar);
        tvSelectedCount  = v.findViewById(R.id.tv_selected_count);

        View banner = v.findViewById(R.id.banner_requests);
        if (banner != null) banner.setVisibility(View.GONE);

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ChatListAdapter(contacts, this);
        rv.setAdapter(adapter);

        v.findViewById(R.id.btn_cancel_selection_chats).setOnClickListener(x -> {
            adapter.clearSelection();
            llSelectionBar.setVisibility(View.GONE);
        });
        v.findViewById(R.id.btn_select_all_chats).setOnClickListener(x -> {
            adapter.selectAll();
            updateSelectionCount();
        });
        v.findViewById(R.id.btn_delete_selected_chats).setOnClickListener(x ->
            confirmDeleteSelected());

        // v15 FIX 1: Pehle Room se load karo (offline ke liye instant display)
        loadFromRoom();

        // Phir Firebase listener lagao (online sync + Room update)
        loadContacts();
        loadSpecialRequests();
        return v;
    }

    // ─────────────────────────────────────────────────────────────
    // v15 FIX 1a: Room se offline-first load
    // ─────────────────────────────────────────────────────────────

    private void loadFromRoom() {
        if (getContext() == null) return;
        AppDatabase db = AppDatabase.getInstance(getContext());

        // Background thread pe Room query, UI thread pe update
        Executors.newSingleThreadExecutor().execute(() -> {
            List<ChatEntity> cached = db.chatDao().getAllChatsSync();
            if (cached == null || cached.isEmpty()) return;

            List<User> roomUsers = new ArrayList<>();
            for (ChatEntity e : cached) {
                User u = new User();
                u.uid      = e.partnerUid;
                u.name     = e.partnerName;
                u.photoUrl = e.partnerPhoto;
                u.lastMessageAt = e.lastMessageAt;
                // v18 IMPROVEMENT 6: Room.unread se badge — offline mein bhi sahi count
                u.unread   = e.unread;
                roomUsers.add(u);
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // Sirf tab Room data dikhao agar Firebase ne abhi tak kuch nahi diya
                    if (contacts.isEmpty()) {
                        contacts.addAll(roomUsers);
                        sortByLatestMessage();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        if (emptyState != null)
                            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Firebase listener — online sync + Room save
    // ─────────────────────────────────────────────────────────────

    private void loadContacts() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        FirebaseUtils.getContactsRef(uid)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    contacts.clear();
                    List<ChatEntity> toSave = new ArrayList<>();

                    for (DataSnapshot c : snap.getChildren()) {
                        User u = c.getValue(User.class);
                        if (u != null) {
                            if (u.uid == null) u.uid = c.getKey();
                            if ((u.name == null || u.name.isEmpty()
                                    || u.photoUrl == null) && u.uid != null) {
                                enrichContactFromUsers(u, uid);
                            }
                            contacts.add(u);

                            // v15 FIX 1b: Firebase se aaya → Room mein save karo
                            ChatEntity entity = new ChatEntity();
                            entity.chatId       = uid + "_contact_" + (u.uid != null ? u.uid : "");
                            entity.type         = "private";
                            entity.partnerUid   = u.uid;
                            entity.partnerName  = u.name;
                            entity.partnerPhoto = u.photoUrl;
                            entity.lastMessageAt = u.lastMessageAt;
                            entity.unread       = u.unread;  // v18 IMPROVEMENT 6: unread badge Room mein store karo
                            entity.syncedAt     = System.currentTimeMillis();
                            toSave.add(entity);
                        }
                    }

                    // Room mein background save karo
                    if (getContext() != null && !toSave.isEmpty()) {
                        AppDatabase db = AppDatabase.getInstance(getContext());
                        Executors.newSingleThreadExecutor().execute(() ->
                            db.chatDao().insertChats(toSave));
                    }

                    sortByLatestMessage();
                    if (adapter != null) adapter.notifyDataSetChanged();
                    if (emptyState != null)
                        emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // Change 5: fetch full user details when contact entry is incomplete
    private void enrichContactFromUsers(User u, String myUid) {
        if (u.uid == null) return;
        FirebaseUtils.getUserRef(u.uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String name  = snap.child("name").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    boolean changed = false;
                    if (name != null && !name.isEmpty() && (u.name == null || u.name.isEmpty())) {
                        u.name = name; changed = true;
                        FirebaseUtils.getContactsRef(myUid)
                            .child(u.uid).child("name").setValue(name);
                    }
                    if (photo != null && !photo.isEmpty() && u.photoUrl == null) {
                        u.photoUrl = photo; changed = true;
                        FirebaseUtils.getContactsRef(myUid)
                            .child(u.uid).child("photoUrl").setValue(photo);
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
                        sortByLatestMessage();
                        adapter.notifyDataSetChanged();
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // Change 10: sort contacts so latest message is always at top
    private void sortByLatestMessage() {
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

    @Override public void onSelectionStarted() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.VISIBLE);
        updateSelectionCount();
    }
    @Override public void onSelectionChanged() { updateSelectionCount(); }
    @Override public void onSelectionCleared() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    private void updateSelectionCount() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        if (tvSelectedCount != null) tvSelectedCount.setText(count + " selected");
    }

    private void confirmDeleteSelected() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        if (count == 0) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Delete " + count + " chat" + (count > 1 ? "s" : "") + "?")
            .setMessage("Selected conversations will be removed from your chat list.")
            .setPositiveButton("Delete", (d, w) -> deleteSelected())
            .setNegativeButton("Cancel", null).show();
    }

    private void deleteSelected() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        List<User> selected = adapter.getSelectedItems();
        for (User u : selected)
            if (u.uid != null) FirebaseUtils.getContactsRef(uid).child(u.uid).removeValue();
        contacts.removeAll(selected);
        adapter.clearSelection();
        adapter.notifyDataSetChanged();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        if (emptyState != null)
            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
