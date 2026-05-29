package com.callx.app.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * ChatsFragment — Offline-First chat list with WhatsApp-style selection toolbar.
 */
public class ChatsFragment extends Fragment implements ChatListAdapter.SelectionListener {

    private final List<User> contacts = new ArrayList<>();
    private ChatListAdapter adapter;
    private View emptyState;

    private LinearLayout llSelectionBar;
    private TextView tvSelectedCount;

    private final Set<String> specialRequestUids = new HashSet<>();

    private DatabaseReference contactsRef;
    private ValueEventListener contactsListener;
    private DatabaseReference specialRequestsRef;
    private ValueEventListener specialRequestsListener;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);

        RecyclerView rv = v.findViewById(R.id.rv_chats);
        emptyState      = v.findViewById(R.id.empty_state);
        llSelectionBar  = v.findViewById(R.id.ll_selection_bar);
        tvSelectedCount = v.findViewById(R.id.tv_selected_count);

        View banner = v.findViewById(R.id.banner_requests);
        if (banner != null) banner.setVisibility(View.GONE);

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ChatListAdapter(contacts, this);
        rv.setAdapter(adapter);

        // ── Selection toolbar buttons ──────────────────────────────
        v.findViewById(R.id.btn_cancel_selection_chats).setOnClickListener(x -> cancelSelection());
        v.findViewById(R.id.btn_delete_selected_chats).setOnClickListener(x -> confirmDelete());
        v.findViewById(R.id.btn_sel_pin).setOnClickListener(x -> handlePin());
        v.findViewById(R.id.btn_sel_mute).setOnClickListener(x -> handleMute());
        v.findViewById(R.id.btn_sel_archive).setOnClickListener(x -> handleArchive());
        v.findViewById(R.id.btn_sel_more).setOnClickListener(this::showMoreMenu);

        loadFromRoom();
        loadContacts();
        loadSpecialRequests();
        return v;
    }

    // ── SelectionListener callbacks ────────────────────────────────
    @Override public void onSelectionStarted() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.VISIBLE);
        refreshCount();
    }
    @Override public void onSelectionChanged() { refreshCount(); }
    @Override public void onSelectionCleared()  {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    private void refreshCount() {
        int n = adapter == null ? 0 : adapter.getSelectedCount();
        if (tvSelectedCount != null) tvSelectedCount.setText(String.valueOf(n));
    }

    private void cancelSelection() {
        if (adapter != null) adapter.clearSelection();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    // ── Selection Actions ──────────────────────────────────────────

    private void handlePin() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        Toast.makeText(getContext(),
            sel.size() == 1 ? "📌 Chat pinned" : "📌 Chats pinned",
            Toast.LENGTH_SHORT).show();
        cancelSelection();
    }

    private void handleMute() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        String myUid = myUid();
        if (myUid == null) return;
        for (User u : sel)
            if (u.uid != null)
                FirebaseUtils.db().getReference("mutes").child(myUid).child(u.uid).setValue(true);
        Toast.makeText(getContext(),
            sel.size() + " chat" + (sel.size() > 1 ? "s" : "") + " muted",
            Toast.LENGTH_SHORT).show();
        cancelSelection();
    }

    private void handleArchive() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        String myUid = myUid();
        if (myUid == null) return;
        for (User u : sel)
            if (u.uid != null)
                FirebaseUtils.db().getReference("archived").child(myUid).child(u.uid).setValue(true);
        contacts.removeAll(sel);
        cancelSelection();
        adapter.notifyDataSetChanged();
        if (emptyState != null)
            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
        Toast.makeText(getContext(),
            sel.size() + " chat" + (sel.size() > 1 ? "s" : "") + " archived",
            Toast.LENGTH_SHORT).show();
    }

    private void handleMarkUnread() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        String myUid = myUid();
        if (myUid == null) return;
        for (User u : sel) {
            if (u.uid == null) continue;
            if (u.unread == null || u.unread == 0) {
                u.unread = 1L;
                FirebaseUtils.getContactsRef(myUid).child(u.uid).child("unread").setValue(1);
            }
        }
        cancelSelection();
        adapter.notifyDataSetChanged();
    }

    private void handleViewContact() {
        if (adapter == null) return;
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
            i.putExtra("partnerName", u.name != null ? u.name : "");
            i.putExtra("photoUrl",    u.photoUrl != null ? u.photoUrl : "");
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(getContext(), "Profile nahi khul saka", Toast.LENGTH_SHORT).show();
        }
        cancelSelection();
    }

    private void handleClearChat() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        int n = sel.size();
        new AlertDialog.Builder(requireContext())
            .setTitle("Clear " + n + " chat" + (n > 1 ? "s" : "") + "?")
            .setMessage("Saare messages permanently delete ho jayenge.")
            .setPositiveButton("Clear", (d, w) -> {
                String myUid = myUid();
                if (myUid == null) return;
                AppDatabase db = AppDatabase.getInstance(requireContext());
                for (User u : sel) {
                    if (u.uid == null) continue;
                    String chatId = myUid.compareTo(u.uid) < 0
                        ? myUid + "_" + u.uid : u.uid + "_" + myUid;
                    Executors.newSingleThreadExecutor().execute(
                        () -> db.messageDao().deleteAllForChat(chatId));
                }
                cancelSelection();
                Toast.makeText(getContext(),
                    n + " chat" + (n > 1 ? "s" : "") + " cleared", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void handleBlock() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        int n = sel.size();
        new AlertDialog.Builder(requireContext())
            .setTitle("Block " + n + " contact" + (n > 1 ? "s" : "") + "?")
            .setMessage("Ye log aapko message nahi kar payenge.")
            .setPositiveButton("Block", (d, w) -> {
                String myUid = myUid();
                if (myUid == null) return;
                for (User u : sel)
                    if (u.uid != null)
                        FirebaseUtils.db().getReference("blocks")
                            .child(myUid).child(u.uid).setValue(true);
                contacts.removeAll(sel);
                cancelSelection();
                adapter.notifyDataSetChanged();
                if (emptyState != null)
                    emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete() {
        if (adapter == null) return;
        int n = adapter.getSelectedCount();
        if (n == 0) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Delete " + n + " chat" + (n > 1 ? "s" : "") + "?")
            .setMessage("Selected conversations will be removed from your chat list.")
            .setPositiveButton("Delete", (d, w) -> doDelete())
            .setNegativeButton("Cancel", null).show();
    }

    private void doDelete() {
        String myUid = myUid();
        if (myUid == null || adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        for (User u : sel)
            if (u.uid != null)
                FirebaseUtils.getContactsRef(myUid).child(u.uid).removeValue();
        contacts.removeAll(sel);
        cancelSelection();
        adapter.notifyDataSetChanged();
        if (emptyState != null)
            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── 3-dot popup menu ──────────────────────────────────────────
    private void showMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenuInflater().inflate(R.menu.chat_selection_menu, popup.getMenu());

        List<User> sel = adapter == null ? new ArrayList<>() : adapter.getSelectedItems();
        boolean single = sel.size() == 1;
        popup.getMenu().findItem(R.id.sel_view_contact).setVisible(single);
        popup.getMenu().findItem(R.id.sel_lock_chat).setVisible(single);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.sel_view_contact)  { handleViewContact();  return true; }
            if (id == R.id.sel_mark_unread)   { handleMarkUnread();   return true; }
            if (id == R.id.sel_select_all)    {
                if (adapter != null) { adapter.selectAll(); refreshCount(); }
                return true;
            }
            if (id == R.id.sel_lock_chat) {
                Toast.makeText(getContext(), "Lock chat (coming soon)", Toast.LENGTH_SHORT).show();
                cancelSelection(); return true;
            }
            if (id == R.id.sel_clear_chat) { handleClearChat(); return true; }
            if (id == R.id.sel_block)      { handleBlock();      return true; }
            return false;
        });
        popup.show();
    }

    // ── Helpers ───────────────────────────────────────────────────
    private String myUid() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return null;
        return FirebaseAuth.getInstance().getCurrentUser().getUid();
    }

    // ── Offline-first Room load ───────────────────────────────────
    private void loadFromRoom() {
        if (getContext() == null) return;
        AppDatabase db = AppDatabase.getInstance(getContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            List<ChatEntity> cached = db.chatDao().getAllChatsSync();
            if (cached == null || cached.isEmpty()) return;
            List<User> roomUsers = new ArrayList<>();
            for (ChatEntity e : cached) {
                User u = new User();
                u.uid = e.partnerUid;
                if (u.uid == null || u.uid.isEmpty()) continue;
                u.name          = e.partnerName;
                u.photoUrl      = e.partnerPhoto;
                u.thumbUrl      = e.partnerThumb;
                u.lastMessageAt = e.lastMessageAt;
                u.unread        = e.unread;
                roomUsers.add(u);
            }
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
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

    // ── Firebase contact listener ─────────────────────────────────
    private void loadContacts() {
        String uid = myUid();
        if (uid == null) return;
        contactsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                contacts.clear();
                List<ChatEntity> toSave = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    User u = c.getValue(User.class);
                    if (u == null) continue;
                    if (u.uid == null) u.uid = c.getKey();
                    if ((u.name == null || u.name.isEmpty() || u.photoUrl == null) && u.uid != null)
                        enrichContact(u, uid);
                    contacts.add(u);
                    ChatEntity entity    = new ChatEntity();
                    entity.chatId        = uid + "_contact_" + (u.uid != null ? u.uid : "");
                    entity.type          = "private";
                    entity.partnerUid    = u.uid;
                    entity.partnerName   = u.name;
                    entity.partnerPhoto  = u.photoUrl;
                    entity.partnerThumb  = u.thumbUrl;
                    entity.lastMessageAt = u.lastMessageAt;
                    entity.unread        = u.unread;
                    entity.syncedAt      = System.currentTimeMillis();
                    toSave.add(entity);
                }
                if (getContext() != null && !toSave.isEmpty()) {
                    AppDatabase db = AppDatabase.getInstance(getContext());
                    Executors.newSingleThreadExecutor().execute(() -> db.chatDao().insertChats(toSave));
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

    private void enrichContact(User u, String myUid) {
        if (u.uid == null) return;
        FirebaseUtils.getUserRef(u.uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String name  = snap.child("name").getValue(String.class);
                String photo = snap.child("photoUrl").getValue(String.class);
                String thumb = snap.child("thumbUrl").getValue(String.class);
                boolean changed = false;
                if (name  != null && !name.isEmpty()  && (u.name == null || u.name.isEmpty()))
                    { u.name = name;   changed = true; FirebaseUtils.getContactsRef(myUid).child(u.uid).child("name").setValue(name); }
                if (photo != null && !photo.isEmpty() && u.photoUrl == null)
                    { u.photoUrl = photo; changed = true; FirebaseUtils.getContactsRef(myUid).child(u.uid).child("photoUrl").setValue(photo); }
                if (thumb != null && !thumb.isEmpty() && u.thumbUrl == null)
                    { u.thumbUrl = thumb; changed = true; FirebaseUtils.getContactsRef(myUid).child(u.uid).child("thumbUrl").setValue(thumb); }
                if (changed && adapter != null) adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void loadSpecialRequests() {
        String uid = myUid();
        if (uid == null) return;
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

    @Override
    public void onDestroyView() {
        if (contactsRef != null && contactsListener != null) {
            contactsRef.removeEventListener(contactsListener);
            contactsRef = null; contactsListener = null;
        }
        if (specialRequestsRef != null && specialRequestsListener != null) {
            specialRequestsRef.removeEventListener(specialRequestsListener);
            specialRequestsRef = null; specialRequestsListener = null;
        }
        super.onDestroyView();
    }

    private void sortByLatestMessage() {
        Collections.sort(contacts, (a, b) -> {
            boolean aS = a.uid != null && specialRequestUids.contains(a.uid);
            boolean bS = b.uid != null && specialRequestUids.contains(b.uid);
            if (aS != bS) return aS ? -1 : 1;
            long la = a.lastMessageAt != null ? a.lastMessageAt : (a.lastSeen != null ? a.lastSeen : 0L);
            long lb = b.lastMessageAt != null ? b.lastMessageAt : (b.lastSeen != null ? b.lastSeen : 0L);
            return Long.compare(lb, la);
        });
    }
}
