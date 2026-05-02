package com.callx.app.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
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

public class ChatsFragment extends Fragment implements ChatListAdapter.SelectionListener {

    private final List<User> contacts = new ArrayList<>();
    private ChatListAdapter adapter;
    private View emptyState;
    private View bannerRequests;

    // Selection bar
    private LinearLayout llSelectionBar;
    private TextView tvSelectedCount;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_chats);
        emptyState     = v.findViewById(R.id.empty_state);
        bannerRequests = v.findViewById(R.id.banner_requests);
        if (bannerRequests != null) bannerRequests.setVisibility(View.GONE);

        llSelectionBar  = v.findViewById(R.id.ll_selection_bar);
        tvSelectedCount = v.findViewById(R.id.tv_selected_count);

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
                    sortSpecialFirst();
                    adapter.notifyDataSetChanged();
                    if (emptyState != null)
                        emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private final Set<String> specialRequestUids = new HashSet<>();

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
                        sortSpecialFirst();
                        adapter.notifyDataSetChanged();
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void sortSpecialFirst() {
        Collections.sort(contacts, (a, b) -> {
            boolean aS = a.uid != null && specialRequestUids.contains(a.uid);
            boolean bS = b.uid != null && specialRequestUids.contains(b.uid);
            if (aS != bS) return aS ? -1 : 1;
            long la = a.lastMessageAt == null ? 0 : a.lastMessageAt;
            long lb = b.lastMessageAt == null ? 0 : b.lastMessageAt;
            return Long.compare(lb, la);
        });
    }

    // ── Selection callbacks ────────────────────────────────────────────────
    @Override
    public void onSelectionStarted() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.VISIBLE);
        updateSelectionCount();
    }

    @Override
    public void onSelectionChanged() {
        updateSelectionCount();
    }

    @Override
    public void onSelectionCleared() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    private void updateSelectionCount() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        if (tvSelectedCount != null)
            tvSelectedCount.setText(count + " selected");
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
        for (User u : selected) {
            if (u.uid != null)
                FirebaseUtils.getContactsRef(uid).child(u.uid).removeValue();
        }
        contacts.removeAll(selected);
        adapter.clearSelection();
        adapter.notifyDataSetChanged();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        if (emptyState != null)
            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
