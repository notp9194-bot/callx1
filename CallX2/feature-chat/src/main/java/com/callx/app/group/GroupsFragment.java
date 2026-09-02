package com.callx.app.group;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.chatlist.RecyclerViewPoolViewModel;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.models.Group;
import com.callx.app.utils.AppBgExecutor;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups tab with the same local-first/list ergonomics as Chats:
 * searchable list, stable latest-message ordering, unread badges, selection
 * actions, and incremental Firebase membership/group updates.
 *
 * The membership node uses ChildEventListener. Each group gets one narrow live
 * listener at groups/{id}; a changed group replaces only its own row instead
 * of refetching and rebuilding the complete group list.
 */
public class GroupsFragment extends Fragment {

    private final List<Group> groups = new ArrayList<>();
    private final Set<String> activeGroupIds = new HashSet<>();
    private final Map<String, DatabaseReference> groupRefs = new HashMap<>();
    private final Map<String, ValueEventListener> groupListeners = new HashMap<>();

    private GroupAdapter adapter;
    private View emptyState;
    private TextView searchEmpty;
    private TextView syncError;
    private EditText search;
    private TextView archivedToggle;
    private View selectionBar;
    private TextView selectedCount;

    private ChildEventListener groupsChildListener;
    private DatabaseReference userGroupsRef;
    private SharedPreferences listPrefs;
    private boolean showArchived;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingFilter;

    private static final String PREFS = "group_list_settings";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_ARCHIVED = "archived";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle state) {
        View v = inflater.inflate(R.layout.fragment_groups, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_groups);
        emptyState = v.findViewById(R.id.empty_groups);
        searchEmpty = v.findViewById(R.id.tv_group_search_empty);
        syncError = v.findViewById(R.id.tv_groups_error);
        search = v.findViewById(R.id.et_group_search);
        archivedToggle = v.findViewById(R.id.btn_show_archived_groups);
        selectionBar = v.findViewById(R.id.ll_group_selection_bar);
        selectedCount = v.findViewById(R.id.tv_group_selected_count);
        listPrefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        com.callx.app.chatlist.ChatListLayoutManager llm =
                new com.callx.app.chatlist.ChatListLayoutManager(requireContext());
        llm.setInitialPrefetchItemCount(8);
        rv.setLayoutManager(llm);

        adapter = new GroupAdapter(new GroupAdapter.SelectionListener() {
            @Override public void onSelectionStarted() {
                if (selectionBar != null) selectionBar.setVisibility(View.VISIBLE);
                updateSelectionCount();
            }
            @Override public void onSelectionChanged() { updateSelectionCount(); }
            @Override public void onSelectionCleared() {
                if (selectionBar != null) selectionBar.setVisibility(View.GONE);
            }
        });
        rv.setAdapter(adapter);
        rv.setHasFixedSize(true);
        rv.setItemViewCacheSize(20);
        RecyclerViewPoolViewModel poolVm = new ViewModelProvider(requireActivity())
                .get(RecyclerViewPoolViewModel.class);
        rv.setRecycledViewPool(poolVm.getGroupsPool());
        rv.addOnScrollListener(new com.callx.app.chatlist.GlideScrollListener(requireContext()));
        rv.setItemAnimator(null);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setNestedScrollingEnabled(false);
        rv.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_DEFAULT);
        rv.setRecyclerListener(holder -> {
            if (holder instanceof GroupAdapter.VH) {
                GroupAdapter.VH vh = (GroupAdapter.VH) holder;
                try { Glide.with(vh.avatar.getContext()).clear(vh.avatar); }
                catch (Exception ignored) { }
            }
        });
        rv.setClipToPadding(false);
        rv.setClipChildren(false);

        FloatingActionButton fab = v.findViewById(R.id.fab_new_group);
        if (fab != null) fab.setOnClickListener(x ->
                startActivity(new Intent(getContext(), NewGroupActivity.class)));

        setupSearch(v);
        setupSelectionActions(v);
        if (archivedToggle != null) {
            archivedToggle.setOnClickListener(x -> {
                showArchived = !showArchived;
                archivedToggle.setText(showArchived ? "All groups" : "Archived");
                applyGroupFilter();
            });
        }

        loadFromRoom();
        loadIncrementally();
        return v;
    }

    private void setupSearch(View root) {
        if (search == null) return;
        View clear = root.findViewById(R.id.btn_group_search_clear);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                if (clear != null) clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                if (pendingFilter != null) mainHandler.removeCallbacks(pendingFilter);
                pendingFilter = GroupsFragment.this::applyGroupFilter;
                mainHandler.postDelayed(pendingFilter, 160);
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        if (clear != null) clear.setOnClickListener(x -> search.setText(""));
    }

    private void setupSelectionActions(View root) {
        View cancel = root.findViewById(R.id.btn_cancel_group_selection);
        View all = root.findViewById(R.id.btn_select_all_groups);
        View pin = root.findViewById(R.id.btn_pin_selected_groups);
        View mute = root.findViewById(R.id.btn_mute_selected_groups);
        View archive = root.findViewById(R.id.btn_archive_selected_groups);
        View delete = root.findViewById(R.id.btn_delete_selected_groups);
        if (cancel != null) cancel.setOnClickListener(x -> adapter.clearSelection());
        if (all != null) all.setOnClickListener(x -> adapter.selectAll());
        if (pin != null) pin.setOnClickListener(x -> togglePinnedSelected());
        if (mute != null) mute.setOnClickListener(x -> toggleMutedSelected());
        if (archive != null) archive.setOnClickListener(x -> toggleArchivedSelected());
        if (delete != null) delete.setOnClickListener(x -> confirmDeleteSelected());
    }

    @Override public void onDestroyView() {
        if (pendingFilter != null) mainHandler.removeCallbacks(pendingFilter);
        if (userGroupsRef != null && groupsChildListener != null)
            userGroupsRef.removeEventListener(groupsChildListener);
        userGroupsRef = null;
        groupsChildListener = null;
        for (String id : new ArrayList<>(groupRefs.keySet())) detachGroupListener(id);
        groupRefs.clear();
        groupListeners.clear();
        adapter = null;
        emptyState = null;
        searchEmpty = null;
        syncError = null;
        search = null;
        selectionBar = null;
        super.onDestroyView();
    }

    private void loadFromRoom() {
        if (getContext() == null) return;
        Context appContext = getContext().getApplicationContext();
        AppDatabase db = AppDatabase.getInstance(appContext);
        AppBgExecutor.execute(() -> {
            List<GroupEntity> cached = db.groupDao().getAllGroupsSync();
            if (cached == null || cached.isEmpty()) return;
            List<Group> restored = new ArrayList<>();
            for (GroupEntity e : cached) {
                if (e.id == null || e.id.isEmpty()) continue;
                Group g = new Group();
                g.id = e.id;
                g.name = e.name;
                g.description = e.description;
                g.iconUrl = e.iconUrl;
                g.createdBy = e.createdBy;
                g.lastMessage = e.lastMessage;
                g.lastSenderName = e.lastSenderName;
                g.lastMessageAt = e.lastMessageAt;
                g.lastMessageType = e.lastMessageType;
                g.lastMessageStatus = e.lastMessageStatus;
                g.lastMessageSenderUid = e.lastMessageSenderUid;
                g.lastMessageId = e.lastMessageId;
                if (e.unread != null) g.unread.put(FirebaseUtils.getCurrentUid(), e.unread);
                g.localMuted = Boolean.TRUE.equals(e.muted);
                applyLocalState(g);
                restored.add(g);
            }
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded() || adapter == null || !groups.isEmpty()) return;
                diffUpdateGroups(restored);
            });
        });
    }

    private void loadIncrementally() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty()) return;
        userGroupsRef = FirebaseUtils.getUserGroupsRef(uid);
        groupsChildListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, String previousChildKey) {
                String id = snap.getKey();
                if (id == null || id.isEmpty()) return;
                activeGroupIds.add(id);
                attachGroupListener(id);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snap, String previousChildKey) {
                String id = snap.getKey();
                if (id != null) {
                    activeGroupIds.add(id);
                    attachGroupListener(id);
                }
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot snap) {
                String id = snap.getKey();
                if (id == null) return;
                activeGroupIds.remove(id);
                detachGroupListener(id);
                removeGroup(id);
            }
            @Override public void onChildMoved(@NonNull DataSnapshot snap, String previousChildKey) { }
            @Override public void onCancelled(@NonNull DatabaseError error) { showSyncError(error); }
        };
        userGroupsRef.addChildEventListener(groupsChildListener);

        // One small membership snapshot cleans stale Room rows. Group details
        // still arrive through the per-group incremental listeners above.
        userGroupsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                activeGroupIds.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    if (child.getKey() != null) activeGroupIds.add(child.getKey());
                }
                groups.removeIf(g -> g.id == null || !activeGroupIds.contains(g.id));
                applyGroupFilter();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { showSyncError(error); }
        });
    }

    private void attachGroupListener(String groupId) {
        if (groupRefs.containsKey(groupId)) return;
        DatabaseReference ref = FirebaseUtils.getGroupsRef().child(groupId);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!activeGroupIds.contains(groupId)) return;
                Group g = Group.fromSnapshot(snap);
                if (g == null) return;
                if (g.id == null || g.id.isEmpty()) g.id = groupId;
                applyLocalState(g);
                upsertGroup(g);
                saveGroup(g);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                showSyncError(error);
            }
        };
        groupRefs.put(groupId, ref);
        groupListeners.put(groupId, listener);
        ref.addValueEventListener(listener);
    }

    private void detachGroupListener(String id) {
        DatabaseReference ref = groupRefs.remove(id);
        ValueEventListener listener = groupListeners.remove(id);
        if (ref != null && listener != null) ref.removeEventListener(listener);
    }

    private void upsertGroup(Group incoming) {
        for (int i = 0; i < groups.size(); i++) {
            if (incoming.id.equals(groups.get(i).id)) {
                groups.set(i, incoming);
                applyGroupFilter();
                return;
            }
        }
        groups.add(incoming);
        applyGroupFilter();
    }

    private void removeGroup(String id) {
        groups.removeIf(g -> id.equals(g.id));
        if (getContext() != null) {
            AppDatabase db = AppDatabase.getInstance(getContext().getApplicationContext());
            AppBgExecutor.execute(() -> db.groupDao().deleteGroup(id));
        }
        applyGroupFilter();
    }

    private void diffUpdateGroups(List<Group> newList) {
        groups.clear();
        if (newList != null) {
            for (Group g : newList) {
                applyLocalState(g);
                groups.add(g);
            }
        }
        applyGroupFilter();
    }

    private void applyGroupFilter() {
        if (!isAdded() || adapter == null) return;
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase();
        List<Group> visible = new ArrayList<>();
        for (Group g : groups) {
            if (!showArchived && g.localArchived) continue;
            if (showArchived && !g.localArchived) continue;
            if (!query.isEmpty()) {
                String haystack = ((g.name == null ? "" : g.name) + " "
                        + (g.lastMessage == null ? "" : g.lastMessage) + " "
                        + (g.lastSenderName == null ? "" : g.lastSenderName)).toLowerCase();
                if (!haystack.contains(query)) continue;
            }
            visible.add(g);
        }
        sortGroups(visible);
        adapter.submitList(visible);
        boolean hasRows = !visible.isEmpty();
        if (emptyState != null) emptyState.setVisibility(hasRows ? View.GONE : View.VISIBLE);
        if (searchEmpty != null)
            searchEmpty.setVisibility(!hasRows && !query.isEmpty() ? View.VISIBLE : View.GONE);
        updateEmptyCopy(query, visible);
    }

    private void updateEmptyCopy(String query, List<Group> visible) {
        if (emptyState == null || !visible.isEmpty() || !query.isEmpty()) return;
        TextView title = emptyState.findViewById(R.id.tv_empty_groups_title);
        TextView sub = emptyState.findViewById(R.id.tv_empty_groups_subtitle);
        if (title != null) title.setText(showArchived ? "No archived groups" : "Koi group nahi hai");
        if (sub != null) sub.setText(showArchived
                ? "Archived group yahan dikhte hain"
                : "Naya group banane ke liye + tap karo");
    }

    private void sortGroups(List<Group> list) {
        Collections.sort(list, (a, b) -> {
            if (a.localPinned != b.localPinned) return a.localPinned ? -1 : 1;
            int time = Long.compare(effectiveTime(b), effectiveTime(a));
            if (time != 0) return time;
            String an = a.name == null ? "" : a.name;
            String bn = b.name == null ? "" : b.name;
            return an.compareToIgnoreCase(bn);
        });
    }

    private static long effectiveTime(Group g) {
        if (g.lastMessageAt != null && g.lastMessageAt > 0) return g.lastMessageAt;
        return g.createdAt == null ? 0 : g.createdAt;
    }

    private void applyLocalState(Group g) {
        if (g == null || g.id == null) return;
        g.localPinned = listPrefs != null && listPrefs.getStringSet(KEY_PINNED, Collections.emptySet())
                .contains(g.id);
        g.localArchived = listPrefs != null && listPrefs.getStringSet(KEY_ARCHIVED, Collections.emptySet())
                .contains(g.id);
        String uid = FirebaseUtils.getCurrentUid();
        g.localMuted = uid != null && g.mutedBy != null && Boolean.TRUE.equals(g.mutedBy.get(uid));
    }

    private void saveGroup(Group g) {
        if (getContext() == null || g == null || g.id == null) return;
        GroupEntity e = new GroupEntity();
        e.id = g.id;
        e.name = g.name;
        e.description = g.description;
        e.iconUrl = g.iconUrl;
        e.createdBy = g.createdBy;
        e.lastMessage = g.lastMessage;
        e.lastSenderName = g.lastSenderName;
        e.lastMessageAt = g.lastMessageAt;
        e.lastMessageType = g.lastMessageType;
        e.lastMessageStatus = g.lastMessageStatus;
        e.lastMessageSenderUid = g.lastMessageSenderUid;
        e.lastMessageId = g.lastMessageId;
        String uid = FirebaseUtils.getCurrentUid();
        e.unread = g.unread == null || uid == null ? 0L : g.unread.get(uid);
        e.muted = g.localMuted;
        AppDatabase db = AppDatabase.getInstance(getContext().getApplicationContext());
        AppBgExecutor.execute(() -> db.groupDao().insertGroup(e));
    }

    private void updateSelectionCount() {
        if (selectedCount != null && adapter != null)
            selectedCount.setText(adapter.getSelectedCount() + " selected");
    }

    private List<Group> selectedGroups() {
        return adapter == null ? Collections.emptyList() : adapter.getSelectedItems();
    }

    private void togglePinnedSelected() {
        List<Group> selected = selectedGroups();
        if (selected.isEmpty()) return;
        Set<String> pinned = mutableSet(KEY_PINNED);
        boolean pin = false;
        for (Group g : selected) if (!pinned.contains(g.id)) { pin = true; break; }
        for (Group g : selected) {
            if (pin) pinned.add(g.id); else pinned.remove(g.id);
        }
        persistSet(KEY_PINNED, pinned);
        refreshLocalStates();
        adapter.clearSelection();
        applyGroupFilter();
    }

    private void toggleMutedSelected() {
        List<Group> selected = selectedGroups();
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        boolean mute = false;
        for (Group g : selected) if (!g.localMuted) { mute = true; break; }
        for (Group g : selected) {
            g.localMuted = mute;
            if (g.mutedBy == null) g.mutedBy = new HashMap<>();
            if (mute) g.mutedBy.put(uid, true); else g.mutedBy.remove(uid);
            if (mute) FirebaseUtils.getGroupsRef().child(g.id).child("mutedBy").child(uid)
                    .setValue(true, (error, ref) -> { if (error != null) showSyncError(error); });
            else FirebaseUtils.getGroupsRef().child(g.id).child("mutedBy").child(uid)
                    .removeValue((error, ref) -> { if (error != null) showSyncError(error); });
            saveGroup(g);
        }
        refreshLocalStates();
        adapter.clearSelection();
        applyGroupFilter();
    }

    private void toggleArchivedSelected() {
        List<Group> selected = selectedGroups();
        if (selected.isEmpty()) return;
        Set<String> archived = mutableSet(KEY_ARCHIVED);
        boolean archive = false;
        for (Group g : selected) if (!g.localArchived) { archive = true; break; }
        for (Group g : selected) {
            if (archive) archived.add(g.id); else archived.remove(g.id);
        }
        persistSet(KEY_ARCHIVED, archived);
        refreshLocalStates();
        adapter.clearSelection();
        applyGroupFilter();
    }

    /** State changes must use fresh model instances: AsyncListDiffer compares
     * object snapshots, so mutating an object already held by its old list can
     * make a pin/unread/mute change invisible to DiffUtil. */
    private void refreshLocalStates() {
        for (int i = 0; i < groups.size(); i++) {
            Group old = groups.get(i);
            Group copy = copyGroup(old);
            applyLocalState(copy);
            groups.set(i, copy);
        }
    }

    private static Group copyGroup(Group source) {
        Group copy = new Group();
        copy.id = source.id;
        copy.name = source.name;
        copy.description = source.description;
        copy.iconUrl = source.iconUrl;
        copy.createdBy = source.createdBy;
        copy.adminUid = source.adminUid;
        copy.createdAt = source.createdAt;
        copy.lastMessage = source.lastMessage;
        copy.lastSenderName = source.lastSenderName;
        copy.lastMessageAt = source.lastMessageAt;
        copy.lastMessageType = source.lastMessageType;
        copy.lastMessageStatus = source.lastMessageStatus;
        copy.lastMessageSenderUid = source.lastMessageSenderUid;
        copy.lastMessageId = source.lastMessageId;
        copy.topicsEnabled = source.topicsEnabled;
        if (source.members != null) copy.members.putAll(source.members);
        if (source.admins != null) copy.admins.putAll(source.admins);
        if (source.mutedBy != null) copy.mutedBy.putAll(source.mutedBy);
        if (source.unread != null) copy.unread.putAll(source.unread);
        copy.localPinned = source.localPinned;
        copy.localArchived = source.localArchived;
        copy.localMuted = source.localMuted;
        return copy;
    }

    private void confirmDeleteSelected() {
        List<Group> selected = selectedGroups();
        if (selected.isEmpty() || getContext() == null) return;
        com.callx.app.utils.AlertDialogStyler.showReusableConfirm(requireContext(),
                "remove_selected_groups",
                com.callx.app.utils.AlertDialogStyler.DialogSize.COMPACT,
                "Remove " + selected.size() + " group" + (selected.size() > 1 ? "s" : "") + "?",
                "You will leave these groups and remove them from this list.",
                "Remove", () -> removeSelected(selected), null, null, "Cancel");
    }

    private void removeSelected(List<Group> selected) {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        Set<String> pinned = mutableSet(KEY_PINNED);
        Set<String> archived = mutableSet(KEY_ARCHIVED);
        for (Group g : selected) {
            FirebaseUtils.getUserGroupsRef(uid).child(g.id)
                    .removeValue((error, ref) -> { if (error != null) showSyncError(error); });
            removeGroup(g.id);
            pinned.remove(g.id);
            archived.remove(g.id);
        }
        persistSet(KEY_PINNED, pinned);
        persistSet(KEY_ARCHIVED, archived);
        adapter.clearSelection();
    }

    private Set<String> mutableSet(String key) {
        Set<String> current = listPrefs == null
                ? new HashSet<>() : listPrefs.getStringSet(key, Collections.emptySet());
        return new HashSet<>(current);
    }

    private void persistSet(String key, Set<String> value) {
        if (listPrefs != null) listPrefs.edit().putStringSet(key, new HashSet<>(value)).apply();
    }

    private void showSyncError(DatabaseError error) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (syncError == null || !isAdded()) return;
            syncError.setText("Groups sync failed: " + error.getMessage());
            syncError.setVisibility(View.VISIBLE);
        });
    }
}