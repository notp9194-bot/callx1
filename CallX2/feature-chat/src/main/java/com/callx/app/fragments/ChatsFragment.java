package com.callx.app.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
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

    // FIX #MEM-3C: Listener references store karo taaki onDestroyView mein detach kar sakein.
    // Pehle anonymous listeners the — unhe remove karna impossible tha → memory leak.
    private DatabaseReference contactsRef;
    private ValueEventListener contactsListener;
    private DatabaseReference specialRequestsRef;
    private ValueEventListener specialRequestsListener;

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

        // Cancel / back
        v.findViewById(R.id.btn_cancel_selection_chats).setOnClickListener(x -> {
            adapter.clearSelection();
            llSelectionBar.setVisibility(View.GONE);
        });

        // Delete
        v.findViewById(R.id.btn_delete_selected_chats).setOnClickListener(x ->
            confirmDeleteSelected());

        // Pin (single-select only — show toast if multi)
        v.findViewById(R.id.btn_sel_pin).setOnClickListener(x ->
            handlePin());

        // Mute
        v.findViewById(R.id.btn_sel_mute).setOnClickListener(x ->
            handleMute());

        // Archive
        v.findViewById(R.id.btn_sel_archive).setOnClickListener(x ->
            handleArchive());

        // 3-dot more menu
        v.findViewById(R.id.btn_sel_more).setOnClickListener(x ->
            showSelectionMoreMenu(x));

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
                u.uid = e.partnerUid;
                if (u.uid == null || u.uid.isEmpty()) continue;
                u.name     = e.partnerName;
                u.photoUrl = e.partnerPhoto;
                u.thumbUrl = e.partnerThumb;
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

        // FIX #MEM-3C: Ref + listener fields mein store karo — onDestroyView mein remove honge
        contactsListener = new ValueEventListener() {
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
                        entity.partnerThumb = u.thumbUrl;
                        entity.lastMessageAt = u.lastMessageAt;
                        entity.unread       = u.unread;
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
        };
        contactsRef = FirebaseUtils.getContactsRef(uid);
        contactsRef.addValueEventListener(contactsListener);
    }

    // Change 5: fetch full user details when contact entry is incomplete
    private void enrichContactFromUsers(User u, String myUid) {
        if (u.uid == null) return;
        FirebaseUtils.getUserRef(u.uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String name     = snap.child("name").getValue(String.class);
                    String photo    = snap.child("photoUrl").getValue(String.class);
                    String thumb    = snap.child("thumbUrl").getValue(String.class);
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
                    if (thumb != null && !thumb.isEmpty() && u.thumbUrl == null) {
                        u.thumbUrl = thumb; changed = true;
                        FirebaseUtils.getContactsRef(myUid)
                            .child(u.uid).child("thumbUrl").setValue(thumb);
                    }
                    if (changed && adapter != null) adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void loadSpecialRequests() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // FIX #MEM-3C: Field mein store karo — onDestroyView mein remove hoga
        specialRequestsListener = new ValueEventListener() {
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
        };
        specialRequestsRef = FirebaseUtils.db().getReference("specialRequests").child(uid);
        specialRequestsRef.addValueEventListener(specialRequestsListener);
    }

    // FIX #MEM-3C: onDestroyView mein saare Firebase listeners detach karo.
    // Bina iske Firebase background mein data push karta rehta tha — memory + battery waste.
    @Override
    public void onDestroyView() {
        if (contactsRef != null && contactsListener != null) {
            contactsRef.removeEventListener(contactsListener);
            contactsRef = null;
            contactsListener = null;
        }
        if (specialRequestsRef != null && specialRequestsListener != null) {
            specialRequestsRef.removeEventListener(specialRequestsListener);
            specialRequestsRef = null;
            specialRequestsListener = null;
        }
        super.onDestroyView();
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

    // ─────────────────────────────────────────────────────────────
    // SELECTION TOOLBAR ACTIONS
    // ─────────────────────────────────────────────────────────────

    /** Pin / unpin selected chats (WhatsApp — only first 3 allowed) */
    private void handlePin() {
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        if (sel.size() > 1) {
            Toast.makeText(getContext(), "Pin ke liye ek chat select karo", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getContext(), "📌 Chat pinned (coming soon)", Toast.LENGTH_SHORT).show();
        adapter.clearSelection();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    /** Mute notifications for selected chats */
    private void handleMute() {
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        for (User u : sel) {
            if (u.uid == null) continue;
            FirebaseUtils.db().getReference("mutes")
                .child(myUid).child(u.uid).setValue(true);
        }
        int n = sel.size();
        Toast.makeText(getContext(),
            n + " chat" + (n > 1 ? "s" : "") + " muted", Toast.LENGTH_SHORT).show();
        adapter.clearSelection();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    /** Archive selected chats */
    private void handleArchive() {
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        for (User u : sel) {
            if (u.uid == null) continue;
            FirebaseUtils.db().getReference("archived")
                .child(myUid).child(u.uid).setValue(true);
        }
        contacts.removeAll(sel);
        adapter.clearSelection();
        adapter.notifyDataSetChanged();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        if (emptyState != null)
            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
        int n = sel.size();
        Toast.makeText(getContext(),
            n + " chat" + (n > 1 ? "s" : "") + " archived", Toast.LENGTH_SHORT).show();
    }

    /** Mark selected as unread */
    private void handleMarkUnread() {
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        for (User u : sel) {
            if (u.uid == null) continue;
            // Set unread = 1 if currently 0
            if (u.unread == null || u.unread == 0) {
                u.unread = 1L;
                FirebaseUtils.getContactsRef(myUid).child(u.uid).child("unread").setValue(1);
            }
        }
        adapter.clearSelection();
        adapter.notifyDataSetChanged();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    /** Select all contacts */
    private void handleSelectAll() {
        adapter.selectAll();
        updateSelectionCount();
    }

    /** Open UserProfileActivity for single selected contact */
    private void handleViewContact() {
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        if (sel.size() > 1) {
            Toast.makeText(getContext(), "Sirf ek contact select karo", Toast.LENGTH_SHORT).show();
            return;
        }
        User u = sel.get(0);
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.UserProfileActivity");
            Intent i = new Intent(getContext(), cls);
            i.putExtra("partnerUid",  u.uid);
            i.putExtra("partnerName", u.name);
            i.putExtra("photoUrl",    u.photoUrl != null ? u.photoUrl : "");
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(getContext(), "Profile nahi khul saka", Toast.LENGTH_SHORT).show();
        }
        adapter.clearSelection();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    /** Clear chat history for selected contacts */
    private void handleClearChat() {
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        int n = sel.size();
        new AlertDialog.Builder(requireContext())
            .setTitle("Clear " + n + " chat" + (n > 1 ? "s" : "") + "?")
            .setMessage("Saare messages permanently delete ho jayenge.")
            .setPositiveButton("Clear", (d, w) -> {
                if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
                String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
                AppDatabase db = AppDatabase.getInstance(requireContext());
                for (User u : sel) {
                    if (u.uid == null) continue;
                    String chatId = myUid.compareTo(u.uid) < 0
                        ? myUid + "_" + u.uid : u.uid + "_" + myUid;
                    Executors.newSingleThreadExecutor().execute(() ->
                        db.messageDao().deleteAllForChat(chatId));
                }
                adapter.clearSelection();
                if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
                Toast.makeText(getContext(), n + " chat" + (n > 1 ? "s" : "") + " cleared",
                    Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    /** Block selected contacts */
    private void handleBlock() {
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        int n = sel.size();
        new AlertDialog.Builder(requireContext())
            .setTitle("Block " + n + " contact" + (n > 1 ? "s" : "") + "?")
            .setMessage("Ye log aapko message nahi kar payenge.")
            .setPositiveButton("Block", (d, w) -> {
                if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
                String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
                for (User u : sel) {
                    if (u.uid == null) continue;
                    FirebaseUtils.db().getReference("blocks")
                        .child(myUid).child(u.uid).setValue(true);
                }
                contacts.removeAll(sel);
                adapter.clearSelection();
                adapter.notifyDataSetChanged();
                if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
                if (emptyState != null)
                    emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
            })
            .setNegativeButton("Cancel", null).show();
    }

    /** Show 3-dot popup menu anchored to the more button */
    private void showSelectionMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenuInflater().inflate(R.menu.chat_selection_menu, popup.getMenu());

        List<User> sel = adapter == null ? new ArrayList<>() : adapter.getSelectedItems();
        boolean single = sel.size() == 1;

        // Hide single-contact-only options when multiple selected
        popup.getMenu().findItem(R.id.sel_add_shortcut).setVisible(single);
        popup.getMenu().findItem(R.id.sel_view_contact).setVisible(single);
        popup.getMenu().findItem(R.id.sel_lock_chat).setVisible(single);
        popup.getMenu().findItem(R.id.sel_add_favorites).setVisible(single);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.sel_add_shortcut) {
                Toast.makeText(getContext(), "Shortcut added (coming soon)", Toast.LENGTH_SHORT).show();
                adapter.clearSelection();
                if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
                return true;
            }
            if (id == R.id.sel_view_contact)  { handleViewContact();  return true; }
            if (id == R.id.sel_mark_unread)   { handleMarkUnread();   return true; }
            if (id == R.id.sel_select_all)    { handleSelectAll();    return true; }
            if (id == R.id.sel_lock_chat) {
                Toast.makeText(getContext(), "Lock chat (coming soon)", Toast.LENGTH_SHORT).show();
                adapter.clearSelection();
                if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
                return true;
            }
            if (id == R.id.sel_add_favorites) {
                Toast.makeText(getContext(), "⭐ Added to Favorites", Toast.LENGTH_SHORT).show();
                adapter.clearSelection();
                if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
                return true;
            }
            if (id == R.id.sel_add_list) {
                Toast.makeText(getContext(), "Add to list (coming soon)", Toast.LENGTH_SHORT).show();
                adapter.clearSelection();
                if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
                return true;
            }
            if (id == R.id.sel_clear_chat)    { handleClearChat();    return true; }
            if (id == R.id.sel_block)         { handleBlock();        return true; }
            return false;
        });
        popup.show();
    }
}
