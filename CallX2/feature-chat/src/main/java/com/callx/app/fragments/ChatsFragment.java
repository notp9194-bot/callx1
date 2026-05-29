package com.callx.app.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
 * Selection state is communicated to MainActivity via SelectionHost interface.
 */
public class ChatsFragment extends Fragment implements ChatListAdapter.SelectionListener {

    // ── Interface for MainActivity ────────────────────────────────────────
    public interface SelectionHost {
        void onChatSelectionStarted(int count);
        void onChatSelectionChanged(int count);
        void onChatSelectionCleared();
    }

    private SelectionHost selectionHost;

    private final List<User> contacts = new ArrayList<>();
    private ChatListAdapter adapter;
    private View emptyState;

    private final Set<String> specialRequestUids = new HashSet<>();

    private DatabaseReference contactsRef;
    private ValueEventListener contactsListener;
    private DatabaseReference specialRequestsRef;
    private ValueEventListener specialRequestsListener;

    @Override
    public void onAttach(@NonNull Context ctx) {
        super.onAttach(ctx);
        if (ctx instanceof SelectionHost) selectionHost = (SelectionHost) ctx;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        selectionHost = null;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);

        RecyclerView rv = v.findViewById(R.id.rv_chats);
        emptyState = v.findViewById(R.id.empty_state);

        View banner = v.findViewById(R.id.banner_requests);
        if (banner != null) banner.setVisibility(View.GONE);

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ChatListAdapter(contacts, this);
        rv.setAdapter(adapter);

        loadFromRoom();
        loadContacts();
        loadSpecialRequests();
        return v;
    }

    // ── SelectionListener callbacks ───────────────────────────────────────
    @Override public void onSelectionStarted() {
        if (selectionHost != null)
            selectionHost.onChatSelectionStarted(adapter != null ? adapter.getSelectedCount() : 0);
    }
    @Override public void onSelectionChanged() {
        if (selectionHost != null)
            selectionHost.onChatSelectionChanged(adapter != null ? adapter.getSelectedCount() : 0);
    }
    @Override public void onSelectionCleared() {
        if (selectionHost != null) selectionHost.onChatSelectionCleared();
    }

    // ── Public API called by MainActivity toolbar buttons ─────────────────
    public void doCancel()  { cancelSelection(); }
    public void doDelete()  { confirmDelete(); }
    public void doPin()     { handlePin(); }
    public void doMute()    { handleMute(); }
    public void doArchive() { handleArchive(); }
    public void showMoreMenuFromHost(View anchor) { showMoreMenu(anchor); }

    public void cancelSelection() {
        if (adapter != null) adapter.clearSelection();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PIN — Firebase write + Room update + User flag + sort to top
    // ─────────────────────────────────────────────────────────────────────
    private void handlePin() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        String myUid = myUid();
        if (myUid == null) return;

        // Determine toggle state from first selected item
        boolean willPin = !Boolean.TRUE.equals(sel.get(0).pinned);

        AppDatabase db = AppDatabase.getInstance(requireContext());

        for (User u : sel) {
            if (u.uid == null) continue;
            u.pinned = willPin;

            // Firebase: contacts/<myUid>/<uid>/pinned
            FirebaseUtils.getContactsRef(myUid)
                .child(u.uid).child("pinned").setValue(willPin);

            // Room: update pinned flag
            String chatId = makeChatId(myUid, u.uid);
            Executors.newSingleThreadExecutor().execute(
                () -> db.chatDao().updatePinned(chatId, willPin));
        }

        // Re-sort so pinned chats float to top, refresh UI
        sortByLatestMessage();
        adapter.notifyDataSetChanged();

        String msg = willPin
            ? (sel.size() == 1 ? "📌 Chat pinned" : "📌 " + sel.size() + " chats pinned")
            : (sel.size() == 1 ? "Chat unpinned" : sel.size() + " chats unpinned");
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        cancelSelection();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MUTE — Firebase write + Room update + User flag + UI mute icon
    // ─────────────────────────────────────────────────────────────────────
    private void handleMute() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        String myUid = myUid();
        if (myUid == null) return;

        boolean willMute = !Boolean.TRUE.equals(sel.get(0).muted);

        AppDatabase db = AppDatabase.getInstance(requireContext());

        for (User u : sel) {
            if (u.uid == null) continue;
            u.muted = willMute;

            // Firebase: contacts/<myUid>/<uid>/muted
            FirebaseUtils.getContactsRef(myUid)
                .child(u.uid).child("muted").setValue(willMute);

            // Also write to mutes/<myUid>/<uid> for quick lookup
            if (willMute) {
                FirebaseUtils.db().getReference("mutes").child(myUid).child(u.uid).setValue(true);
            } else {
                FirebaseUtils.db().getReference("mutes").child(myUid).child(u.uid).removeValue();
            }

            // Room
            String chatId = makeChatId(myUid, u.uid);
            Executors.newSingleThreadExecutor().execute(
                () -> db.chatDao().updateMuted(chatId, willMute));
        }

        adapter.notifyDataSetChanged();

        String msg = willMute
            ? sel.size() + " chat" + (sel.size() > 1 ? "s" : "") + " muted 🔇"
            : sel.size() + " chat" + (sel.size() > 1 ? "s" : "") + " unmuted 🔔";
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        cancelSelection();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ARCHIVE — Firebase write + remove from list
    // ─────────────────────────────────────────────────────────────────────
    private void handleArchive() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        String myUid = myUid();
        if (myUid == null) return;

        for (User u : sel) {
            if (u.uid == null) continue;
            FirebaseUtils.db().getReference("archived").child(myUid).child(u.uid).setValue(true);
            // Remove from contacts list so it doesn't show in main list
            FirebaseUtils.getContactsRef(myUid).child(u.uid).child("archived").setValue(true);
        }
        contacts.removeAll(sel);
        cancelSelection();
        adapter.notifyDataSetChanged();
        if (emptyState != null)
            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
        Toast.makeText(getContext(),
            sel.size() + " chat" + (sel.size() > 1 ? "s" : "") + " archived 📁",
            Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MARK AS UNREAD — Firebase + User model
    // ─────────────────────────────────────────────────────────────────────
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
        Toast.makeText(getContext(), "Marked as unread", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // VIEW CONTACT — open UserProfileActivity
    // ─────────────────────────────────────────────────────────────────────
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
            // Fallback: open ProfileActivity with same extras
            try {
                Class<?> cls = Class.forName("com.callx.app.activities.ProfileActivity");
                Intent i = new Intent(getContext(), cls);
                i.putExtra("uid", u.uid);
                startActivity(i);
            } catch (ClassNotFoundException ex) {
                Toast.makeText(getContext(), "Profile nahi khul saka", Toast.LENGTH_SHORT).show();
            }
        }
        cancelSelection();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADD CHAT SHORTCUT — Android launcher shortcut
    // ─────────────────────────────────────────────────────────────────────
    private void handleAddShortcut() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        if (sel.size() > 1) {
            Toast.makeText(getContext(), "Sirf ek chat ka shortcut ban sakta hai", Toast.LENGTH_SHORT).show();
            return;
        }
        User u = sel.get(0);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.content.pm.ShortcutManager sm =
                requireContext().getSystemService(android.content.pm.ShortcutManager.class);
            if (sm != null && sm.isRequestPinShortcutSupported()) {
                Intent chatIntent = new Intent(requireContext(),
                    com.callx.app.activities.ChatActivity.class);
                chatIntent.setAction(Intent.ACTION_VIEW);
                chatIntent.putExtra("partnerUid",   u.uid);
                chatIntent.putExtra("partnerName",  u.name != null ? u.name : "");
                chatIntent.putExtra("partnerPhoto", u.photoUrl != null ? u.photoUrl : "");
                chatIntent.putExtra("partnerThumb", u.thumbUrl != null ? u.thumbUrl : "");

                android.content.pm.ShortcutInfo shortcut =
                    new android.content.pm.ShortcutInfo.Builder(
                        requireContext(), "chat_shortcut_" + u.uid)
                        .setShortLabel(u.name != null ? u.name : "Chat")
                        .setLongLabel((u.name != null ? u.name : "Chat") + " — CallX")
                        .setIcon(android.graphics.drawable.Icon.createWithResource(
                            requireContext(), R.drawable.ic_person))
                        .setIntent(chatIntent)
                        .build();
                sm.requestPinShortcut(shortcut, null);
                // Toast shown by system shortcut dialog
            } else {
                Toast.makeText(getContext(), "Is device me shortcut support nahi hai", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(getContext(), "Shortcut ke liye Android 8+ chahiye", Toast.LENGTH_SHORT).show();
        }
        cancelSelection();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADD TO FAVORITES — Firebase + User flag
    // ─────────────────────────────────────────────────────────────────────
    private void handleAddFavorites() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;
        String myUid = myUid();
        if (myUid == null) return;

        boolean willFav = !Boolean.TRUE.equals(sel.get(0).favorite);

        for (User u : sel) {
            if (u.uid == null) continue;
            u.favorite = willFav;
            if (willFav) {
                FirebaseUtils.db().getReference("favorites").child(myUid).child(u.uid).setValue(true);
                FirebaseUtils.getContactsRef(myUid).child(u.uid).child("favorite").setValue(true);
            } else {
                FirebaseUtils.db().getReference("favorites").child(myUid).child(u.uid).removeValue();
                FirebaseUtils.getContactsRef(myUid).child(u.uid).child("favorite").removeValue();
            }
        }
        String msg = willFav
            ? sel.size() + " chat" + (sel.size() > 1 ? "s" : "") + " added to Favorites ⭐"
            : sel.size() + " chat" + (sel.size() > 1 ? "s" : "") + " removed from Favorites";
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        cancelSelection();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADD TO LIST — Firebase, ask for list name
    // ─────────────────────────────────────────────────────────────────────
    private void handleAddToList() {
        if (adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        if (sel.isEmpty()) return;

        android.widget.EditText et = new android.widget.EditText(requireContext());
        et.setHint("List ka naam likhो (e.g. Family, Work)");
        et.setPadding(48, 24, 48, 8);

        new AlertDialog.Builder(requireContext())
            .setTitle("Add to list")
            .setView(et)
            .setPositiveButton("Add", (d, w) -> {
                String listName = et.getText().toString().trim();
                if (listName.isEmpty()) {
                    Toast.makeText(getContext(), "List naam khali nahi ho sakta", Toast.LENGTH_SHORT).show();
                    return;
                }
                String myUid = myUid();
                if (myUid == null) return;
                // Firebase key-safe: replace illegal chars
                String safeList = listName.replaceAll("[.#$/\\[\\]]", "_");
                for (User u : sel)
                    if (u.uid != null)
                        FirebaseUtils.db().getReference("lists")
                            .child(myUid).child(safeList).child(u.uid).setValue(true);
                Toast.makeText(getContext(),
                    sel.size() + " chat" + (sel.size() > 1 ? "s" : "") + " added to \"" + listName + "\"",
                    Toast.LENGTH_SHORT).show();
                cancelSelection();
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // CLEAR CHAT — Room delete + Firebase delete
    // ─────────────────────────────────────────────────────────────────────
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
                    String chatId = makeChatId(myUid, u.uid);
                    // Room: delete all messages for this chat
                    Executors.newSingleThreadExecutor().execute(
                        () -> db.messageDao().deleteAllForChat(chatId));
                    // Firebase: delete messages node
                    FirebaseUtils.db().getReference("messages").child(chatId).removeValue();
                    // Reset last message in contacts node
                    FirebaseUtils.getContactsRef(myUid).child(u.uid).child("lastMessage").removeValue();
                    FirebaseUtils.getContactsRef(myUid).child(u.uid).child("lastMessageAt").removeValue();
                    // Update User object in memory
                    u.lastMessage   = null;
                    u.lastMessageAt = null;
                    u.unread        = 0L;
                }
                cancelSelection();
                adapter.notifyDataSetChanged();
                Toast.makeText(getContext(),
                    n + " chat" + (n > 1 ? "s" : "") + " cleared", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // BLOCK — Firebase blocks node + remove from list
    // ─────────────────────────────────────────────────────────────────────
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
                for (User u : sel) {
                    if (u.uid == null) continue;
                    // Write to blocks node
                    FirebaseUtils.db().getReference("blocks")
                        .child(myUid).child(u.uid).setValue(true);
                    // Remove from contacts so chat disappears
                    FirebaseUtils.getContactsRef(myUid).child(u.uid).removeValue();
                }
                contacts.removeAll(sel);
                cancelSelection();
                adapter.notifyDataSetChanged();
                if (emptyState != null)
                    emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                Toast.makeText(getContext(),
                    n + " contact" + (n > 1 ? "s" : "") + " blocked 🚫", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // DELETE — remove from Firebase contacts + Room + list
    // ─────────────────────────────────────────────────────────────────────
    private void confirmDelete() {
        if (adapter == null) return;
        int n = adapter.getSelectedCount();
        if (n == 0) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Delete " + n + " chat" + (n > 1 ? "s" : "") + "?")
            .setMessage("Selected conversations will be removed from your chat list.")
            .setPositiveButton("Delete", (d, w) -> doDeleteSelected())
            .setNegativeButton("Cancel", null).show();
    }

    private void doDeleteSelected() {
        String myUid = myUid();
        if (myUid == null || adapter == null) return;
        List<User> sel = adapter.getSelectedItems();
        AppDatabase db = AppDatabase.getInstance(requireContext());
        for (User u : sel) {
            if (u.uid == null) continue;
            FirebaseUtils.getContactsRef(myUid).child(u.uid).removeValue();
            String chatId = makeChatId(myUid, u.uid);
            Executors.newSingleThreadExecutor().execute(() -> db.chatDao().deleteChat(chatId));
        }
        contacts.removeAll(sel);
        cancelSelection();
        adapter.notifyDataSetChanged();
        if (emptyState != null)
            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3-dot popup menu
    // ─────────────────────────────────────────────────────────────────────
    private void showMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenuInflater().inflate(R.menu.chat_selection_menu, popup.getMenu());

        List<User> sel = adapter == null ? new ArrayList<>() : adapter.getSelectedItems();
        boolean single = sel.size() == 1;

        // Items only visible for single selection
        popup.getMenu().findItem(R.id.sel_view_contact).setVisible(single);
        popup.getMenu().findItem(R.id.sel_lock_chat).setVisible(single);
        popup.getMenu().findItem(R.id.sel_add_shortcut).setVisible(single);

        // Dynamic title for toggle actions
        if (!sel.isEmpty()) {
            boolean isPinned = Boolean.TRUE.equals(sel.get(0).pinned);
            boolean isMuted  = Boolean.TRUE.equals(sel.get(0).muted);
            boolean isFav    = Boolean.TRUE.equals(sel.get(0).favorite);
            // Note: pin/mute/archive are in the toolbar, not the 3-dot menu.
            // Favorites toggle title
            android.view.MenuItem favItem = popup.getMenu().findItem(R.id.sel_add_favorites);
            if (favItem != null) favItem.setTitle(isFav ? "Remove from Favorites" : "Add to Favorites");
        }

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.sel_add_shortcut)  { handleAddShortcut();  return true; }
            if (id == R.id.sel_view_contact)  { handleViewContact();  return true; }
            if (id == R.id.sel_mark_unread)   { handleMarkUnread();   return true; }
            if (id == R.id.sel_select_all) {
                if (adapter != null) {
                    adapter.selectAll();
                    if (selectionHost != null)
                        selectionHost.onChatSelectionChanged(adapter.getSelectedCount());
                }
                return true;
            }
            if (id == R.id.sel_lock_chat) {
                // Lock chat: redirect to AppLock setup for this contact
                List<User> s = adapter != null ? adapter.getSelectedItems() : new ArrayList<>();
                if (!s.isEmpty() && s.get(0).uid != null) {
                    try {
                        Class<?> cls = Class.forName("com.callx.app.activities.AppLockActivity");
                        Intent i = new Intent(getContext(), cls);
                        i.putExtra("lockForUid", s.get(0).uid);
                        i.putExtra("lockForName", s.get(0).name != null ? s.get(0).name : "");
                        startActivity(i);
                    } catch (ClassNotFoundException e) {
                        Toast.makeText(getContext(), "Lock chat (coming soon)", Toast.LENGTH_SHORT).show();
                    }
                }
                cancelSelection(); return true;
            }
            if (id == R.id.sel_add_favorites) { handleAddFavorites(); return true; }
            if (id == R.id.sel_add_list)      { handleAddToList();    return true; }
            if (id == R.id.sel_clear_chat)    { handleClearChat();    return true; }
            if (id == R.id.sel_block)         { handleBlock();        return true; }
            return false;
        });
        popup.show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private String myUid() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return null;
        return FirebaseAuth.getInstance().getCurrentUser().getUid();
    }

    /** Deterministic chatId: lexicographically smaller UID first */
    private String makeChatId(String uid1, String uid2) {
        return uid1.compareTo(uid2) < 0 ? uid1 + "_" + uid2 : uid2 + "_" + uid1;
    }

    // ── Offline-first Room load ───────────────────────────────────────────
    private void loadFromRoom() {
        if (getContext() == null) return;
        AppDatabase db = AppDatabase.getInstance(getContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            List<ChatEntity> cached = db.chatDao().getAllChatsSync();
            if (cached == null || cached.isEmpty()) return;
            List<User> roomUsers = new ArrayList<>();
            for (ChatEntity e : cached) {
                User u = new User();
                u.uid           = e.partnerUid;
                if (u.uid == null || u.uid.isEmpty()) continue;
                u.name          = e.partnerName;
                u.photoUrl      = e.partnerPhoto;
                u.thumbUrl      = e.partnerThumb;
                u.lastMessageAt = e.lastMessageAt;
                u.unread        = e.unread;
                u.pinned        = e.pinned;
                u.muted         = e.muted;
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

    // ── Firebase contact listener ─────────────────────────────────────────
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
                    entity.pinned        = u.pinned;
                    entity.muted         = u.muted;
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

    /**
     * Sort order (top to bottom):
     * 1. Special requests
     * 2. Pinned chats (by lastMessageAt DESC)
     * 3. Normal chats (by lastMessageAt DESC)
     */
    private void sortByLatestMessage() {
        Collections.sort(contacts, (a, b) -> {
            boolean aSpecial = a.uid != null && specialRequestUids.contains(a.uid);
            boolean bSpecial = b.uid != null && specialRequestUids.contains(b.uid);
            if (aSpecial != bSpecial) return aSpecial ? -1 : 1;

            boolean aPinned = Boolean.TRUE.equals(a.pinned);
            boolean bPinned = Boolean.TRUE.equals(b.pinned);
            if (aPinned != bPinned) return aPinned ? -1 : 1;

            long la = a.lastMessageAt != null ? a.lastMessageAt : (a.lastSeen != null ? a.lastSeen : 0L);
            long lb = b.lastMessageAt != null ? b.lastMessageAt : (b.lastSeen != null ? b.lastSeen : 0L);
            return Long.compare(lb, la);
        });
    }
}
