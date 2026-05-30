package com.callx.app.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.calls.R;

import com.callx.app.adapters.CallHistoryAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.CallLogEntity;
import com.callx.app.models.CallLog;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import com.google.firebase.auth.FirebaseAuth;
import de.hdodenhof.circleimageview.CircleImageView;

import java.util.*;
import java.util.concurrent.Executors;

public class CallsFragment extends Fragment implements CallHistoryAdapter.SelectionListener {

    private final List<CallLog> allLogs = new ArrayList<>();   // master list
    private final List<CallLog> logs    = new ArrayList<>();   // filtered/displayed
    private CallHistoryAdapter adapter;
    private View emptyState;

    private LinearLayout llOnlineUsers;
    private LinearLayout llOnlinePanel;
    private TextView tvNoOnline;
    private LinearLayout llSelectionBar;
    private TextView tvSelectedCount;

    // Filter chips
    private TextView chipAll, chipMissed, chipContacts, chipNonspam, chipSpam;
    private String activeFilter = "all";

    // Search
    private EditText etSearch;
    private String searchQuery = "";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_calls, parent, false);

        RecyclerView rv = v.findViewById(R.id.rv_calls);
        emptyState      = v.findViewById(R.id.empty_calls);
        llOnlineUsers   = v.findViewById(R.id.ll_online_users);
        llOnlinePanel   = v.findViewById(R.id.ll_online_panel);
        tvNoOnline      = v.findViewById(R.id.tv_no_online);
        llSelectionBar  = v.findViewById(R.id.ll_selection_bar);
        tvSelectedCount = v.findViewById(R.id.tv_selected_count);

        chipAll      = v.findViewById(R.id.chip_all);
        chipMissed   = v.findViewById(R.id.chip_missed);
        chipContacts = v.findViewById(R.id.chip_contacts);
        chipNonspam  = v.findViewById(R.id.chip_nonspam);
        chipSpam     = v.findViewById(R.id.chip_spam);
        etSearch     = v.findViewById(R.id.et_search_calls);

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CallHistoryAdapter(logs, this);
        rv.setAdapter(adapter);

        // Selection bar buttons
        v.findViewById(R.id.btn_cancel_selection_calls).setOnClickListener(x -> {
            adapter.clearSelection();
            if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        });
        v.findViewById(R.id.btn_select_all_calls).setOnClickListener(x -> {
            adapter.selectAll();
            updateSelectionCount();
        });
        v.findViewById(R.id.btn_delete_selected_calls).setOnClickListener(x ->
            confirmDeleteSelected());

        // View contacts button
        View tvViewContacts = v.findViewById(R.id.tv_view_contacts);
        if (tvViewContacts != null) {
            tvViewContacts.setOnClickListener(x -> {
                if (getContext() != null)
                    startActivity(new Intent().setClassName(
                        getContext(), "com.callx.app.activities.AllContactsActivity"));
            });
        }

        // Filter chips
        setupChip(chipAll,      "all");
        setupChip(chipMissed,   "missed");
        setupChip(chipContacts, "contacts");
        setupChip(chipNonspam,  "nonspam");
        setupChip(chipSpam,     "spam");

        // Search
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int i, int b, int c) {
                    searchQuery = s.toString().trim().toLowerCase();
                    applyFilter();
                }
                @Override public void afterTextChanged(Editable e) {}
            });
        }

        loadOnlineContacts();
        loadCallLogs();
        return v;
    }

    private void setupChip(TextView chip, String filter) {
        if (chip == null) return;
        chip.setOnClickListener(x -> {
            activeFilter = filter;
            updateChipUI();
            applyFilter();
        });
    }

    private void updateChipUI() {
        if (getContext() == null) return;
        setChipState(chipAll,      "all");
        setChipState(chipMissed,   "missed");
        setChipState(chipContacts, "contacts");
        setChipState(chipNonspam,  "nonspam");
        setChipState(chipSpam,     "spam");
    }

    private void setChipState(TextView chip, String filter) {
        if (chip == null) return;
        boolean selected = filter.equals(activeFilter);
        chip.setBackgroundResource(selected ? R.drawable.chip_selected : R.drawable.chip_unselected);
        chip.setTextColor(getResources().getColor(
            selected ? android.R.color.white : R.color.text_primary, null));
    }

    private void applyFilter() {
        logs.clear();
        for (CallLog l : allLogs) {
            // Search filter
            if (!searchQuery.isEmpty()) {
                String name = l.partnerName != null ? l.partnerName.toLowerCase() : "";
                if (!name.contains(searchQuery)) continue;
            }
            // Tab filter
            String dir = l.direction != null ? l.direction.toLowerCase() : "";
            switch (activeFilter) {
                case "missed":
                    if (!dir.contains("missed")) continue;
                    break;
                case "contacts":
                    if (l.partnerName == null || l.partnerName.isEmpty()) continue;
                    break;
                case "spam":
                    if (!dir.contains("spam")) continue;
                    break;
                case "nonspam":
                    if (dir.contains("spam") || dir.contains("missed")) continue;
                    break;
                default: // "all" — no filter
                    break;
            }
            logs.add(l);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyState != null)
            emptyState.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Online contacts ────────────────────────────────────────────────────
    private void loadOnlineContacts() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || getContext() == null) return;

        FirebaseUtils.getContactsRef(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<User> contacts = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    User u = c.getValue(User.class);
                    if (u != null) {
                        if (u.uid == null) u.uid = c.getKey();
                        contacts.add(u);
                    }
                }
                checkOnlineStatus(contacts);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void checkOnlineStatus(List<User> contacts) {
        if (contacts.isEmpty()) { showNoOnline(); return; }
        String myUid = FirebaseUtils.getCurrentUid();
        final int[] done = {0};
        final List<User> onlineUsers = new ArrayList<>();
        for (User u : contacts) {
            if (u.uid == null) { done[0]++; continue; }
            if (myUid != null && myUid.equals(u.uid)) { done[0]++; continue; }
            FirebaseUtils.getUserRef(u.uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    Boolean online = snap.child("online").getValue(Boolean.class);
                    if (Boolean.TRUE.equals(online)) {
                        String photo  = snap.child("photoUrl").getValue(String.class);
                        String thumbu = snap.child("thumbUrl").getValue(String.class);
                        String name   = snap.child("name").getValue(String.class);
                        if (photo  != null) u.photoUrl = photo;
                        if (thumbu != null) u.thumbUrl = thumbu;
                        if (name   != null) u.name     = name;
                        onlineUsers.add(u);
                    }
                    done[0]++;
                    if (done[0] >= contacts.size()) renderOnlineUsers(onlineUsers);
                }
                @Override public void onCancelled(DatabaseError e) {
                    done[0]++;
                    if (done[0] >= contacts.size()) renderOnlineUsers(onlineUsers);
                }
            });
        }
    }

    private void renderOnlineUsers(List<User> users) {
        if (getContext() == null || llOnlineUsers == null) return;
        llOnlineUsers.removeAllViews();
        if (users.isEmpty()) { showNoOnline(); return; }
        if (llOnlinePanel != null) llOnlinePanel.setVisibility(View.VISIBLE);
        LayoutInflater inf = LayoutInflater.from(getContext());
        for (User u : users) {
            View item = inf.inflate(R.layout.item_online_user, llOnlineUsers, false);
            CircleImageView iv = item.findViewById(R.id.iv_online_avatar);
            TextView tv = item.findViewById(R.id.tv_online_name);
            tv.setText(u.name != null ? u.name : "User");
            String onlineAvatar = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            if (onlineAvatar != null && !onlineAvatar.isEmpty()) {
                Glide.with(getContext()).load(onlineAvatar)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(iv);
            }
            final User finalU = u;
            item.setOnClickListener(x -> openChat(finalU));
            llOnlineUsers.addView(item);
        }
    }

    private void showNoOnline() {
        if (llOnlinePanel != null) llOnlinePanel.setVisibility(View.GONE);
    }

    private void openChat(User u) {
        if (u.uid == null || getContext() == null) return;
        Intent i = new Intent().setClassName(getContext(), "com.callx.app.activities.ChatActivity");
        i.putExtra("partnerUid",  u.uid);
        i.putExtra("partnerName", u.name != null ? u.name : "");
        startActivity(i);
    }

    // ── Call Logs — v16 Offline-First ─────────────────────────────────────
    private void loadCallLogs() {
        if (getContext() != null) {
            AppDatabase db = AppDatabase.getInstance(getContext());
            Executors.newSingleThreadExecutor().execute(() -> {
                List<CallLogEntity> cached = db.callLogDao().getAllCallLogsSync();
                if (cached != null && !cached.isEmpty()) {
                    List<CallLog> roomLogs = new ArrayList<>();
                    for (CallLogEntity e : cached) {
                        CallLog l = new CallLog();
                        l.id          = e.id;
                        l.partnerUid  = e.partnerUid;
                        l.partnerName = e.partnerName;
                        l.direction   = e.direction;
                        l.mediaType   = e.mediaType;
                        l.timestamp   = e.timestamp;
                        l.duration    = e.duration;
                        roomLogs.add(l);
                    }
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (allLogs.isEmpty()) {
                                allLogs.addAll(roomLogs);
                                applyFilter();
                            }
                        });
                    }
                }
            });
        }

        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        FirebaseUtils.getCallsRef(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                allLogs.clear();
                List<CallLogEntity> toSave = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    CallLog l = c.getValue(CallLog.class);
                    if (l != null) {
                        if (l.id == null) l.id = c.getKey();
                        allLogs.add(l);
                        CallLogEntity entity = new CallLogEntity();
                        entity.id          = l.id;
                        entity.partnerUid  = l.partnerUid;
                        entity.partnerName = l.partnerName;
                        entity.direction   = l.direction;
                        entity.mediaType   = l.mediaType;
                        entity.timestamp   = l.timestamp;
                        entity.duration    = l.duration;
                        toSave.add(entity);
                    }
                }
                Collections.sort(allLogs, (a, b) -> {
                    long ta = a.timestamp == null ? 0 : a.timestamp;
                    long tb = b.timestamp == null ? 0 : b.timestamp;
                    return Long.compare(tb, ta);
                });
                applyFilter();

                if (getContext() != null && !toSave.isEmpty()) {
                    AppDatabase db = AppDatabase.getInstance(getContext());
                    Executors.newSingleThreadExecutor().execute(() ->
                        db.callLogDao().insertCallLogs(toSave));
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    // ── Selection ──────────────────────────────────────────────────────────
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
            .setTitle("Delete " + count + " call log" + (count > 1 ? "s" : "") + "?")
            .setMessage("Selected call history will be permanently deleted.")
            .setPositiveButton("Delete", (d, w) -> deleteSelected())
            .setNegativeButton("Cancel", null).show();
    }

    private void deleteSelected() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        List<CallLog> selected = adapter.getSelectedItems();
        for (CallLog l : selected)
            if (l.id != null) FirebaseUtils.getCallsRef(uid).child(l.id).removeValue();
        allLogs.removeAll(selected);
        adapter.clearSelection();
        applyFilter();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }
}
