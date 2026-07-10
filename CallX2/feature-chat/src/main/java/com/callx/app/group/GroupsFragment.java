package com.callx.app.group;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.group.NewGroupActivity;
import com.callx.app.group.GroupAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.models.Group;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * GroupsFragment v16 — Offline-First
 *
 * Flow:
 *   1. loadFromRoom() — Room se turant cached groups dikhao
 *   2. load()         — Firebase se sync karo + Room mein save karo
 *
 * CRASH FIXES:
 *   - groupsListener stored + removed in onDestroyView (was never removed → NPE after detach)
 *   - outer onDataChange: isAdded() check added before every UI/adapter call
 *   - loadFromRoom: isAdded() check added before runOnUiThread UI calls
 */
public class GroupsFragment extends Fragment {

    private final List<Group> groups = new ArrayList<>();
    private GroupAdapter adapter;
    private View emptyState;

    // CRASH FIX: Store listener so we can remove it in onDestroyView
    private ValueEventListener groupsListener;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_groups, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_groups);
        emptyState = v.findViewById(R.id.empty_groups);
        // v83: Telegram-level RecyclerView tuning ─────────────────────────
        LinearLayoutManager llm = new LinearLayoutManager(getContext());
        llm.setInitialPrefetchItemCount(8);
        rv.setLayoutManager(llm);

        // v83: constructor no longer takes a list — submitList() is the write path
        adapter = new GroupAdapter();
        rv.setAdapter(adapter);

        // Fixed-size rows — avoids full list remeasure on item change
        rv.setHasFixedSize(true);
        // Keep 20 off-screen VHs alive to reduce VH churn during scroll
        rv.setItemViewCacheSize(20);
        // Pre-size recycled view pool
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(0, 20);
        rv.setRecycledViewPool(pool);
        // Pause Glide during fast flings
        rv.addOnScrollListener(new com.callx.app.chatlist.GlideScrollListener(requireContext()));
        // v85+: null ItemAnimator
        rv.setItemAnimator(null);
        // v86: disable edge glow + nested scroll overhead
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setNestedScrollingEnabled(false);
        rv.setClipToPadding(false);
        rv.setClipChildren(false);

        FloatingActionButton fab = v.findViewById(R.id.fab_new_group);
        if (fab != null)
            fab.setOnClickListener(x ->
                startActivity(new Intent(getContext(), NewGroupActivity.class)));

        loadFromRoom();
        load();
        return v;
    }

    @Override
    public void onDestroyView() {
        // CRASH FIX: Remove Firebase listener to stop callbacks after view is destroyed
        if (groupsListener != null) {
            String uid = FirebaseUtils.getCurrentUid();
            if (uid != null && !uid.isEmpty()) {
                FirebaseUtils.getUserGroupsRef(uid).removeEventListener(groupsListener);
            }
            groupsListener = null;
        }
        adapter = null;
        emptyState = null;
        super.onDestroyView();
    }

    // ── v16: Room se offline-first load ───────────────────────────
    private void loadFromRoom() {
        if (getContext() == null) return;
        AppDatabase db = AppDatabase.getInstance(getContext());

        Executors.newSingleThreadExecutor().execute(() -> {
            List<GroupEntity> cached = db.groupDao().getAllGroupsSync();
            if (cached == null || cached.isEmpty()) return;

            List<Group> roomGroups = new ArrayList<>();
            for (GroupEntity e : cached) {
                Group g = new Group();
                g.id = e.id;
                if (g.id == null || g.id.isEmpty()) continue;
                g.name          = e.name;
                g.description   = e.description;
                g.iconUrl       = e.iconUrl;
                g.createdBy     = e.createdBy;
                g.lastMessage   = e.lastMessage;
                g.lastSenderName = e.lastSenderName;
                g.lastMessageAt = e.lastMessageAt;
                g.lastMessageType      = e.lastMessageType;
                g.lastMessageStatus    = e.lastMessageStatus;
                g.lastMessageSenderUid = e.lastMessageSenderUid;
                g.lastMessageId        = e.lastMessageId;
                roomGroups.add(g);
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // CRASH FIX: isAdded() check before touching adapter/views
                    if (!isAdded() || adapter == null) return;
                    if (groups.isEmpty()) {
                        diffUpdateGroups(roomGroups);
                        if (emptyState != null)
                            emptyState.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    // ── Firebase sync + Room save ──────────────────────────────────
    private void load() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty()) return; // CRASH FIX: guard null uid

        groupsListener = new ValueEventListener() {  // CRASH FIX: store listener ref
            @Override public void onDataChange(DataSnapshot snap) {
                // CRASH FIX: isAdded() check on every UI operation in outer callback
                if (!isAdded() || adapter == null) return;

                if (!snap.hasChildren()) {
                    diffUpdateGroups(new ArrayList<>());
                    if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
                    return;
                }
                if (emptyState != null) emptyState.setVisibility(View.GONE);
                final int[] pending = {(int) snap.getChildrenCount()};
                final List<GroupEntity> toSave = new ArrayList<>();
                // PERF: fetch into a local list, NOT the live `groups` field —
                // diffUpdateGroups() needs the pre-fetch `groups` contents as the
                // "old" side of the diff. Mutating `groups` directly while fetching
                // (old behaviour) made that comparison impossible and forced a
                // brute notifyDataSetChanged() once every group landed.
                final List<Group> fetched = new ArrayList<>();

                for (DataSnapshot g : snap.getChildren()) {
                    String gid = g.getKey();
                    FirebaseUtils.getGroupsRef().child(gid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot ds) {
                                Group gr = ds.getValue(Group.class);
                                if (gr != null) {
                                    if (gr.id == null) gr.id = ds.getKey();
                                    fetched.add(gr);

                                    GroupEntity entity = new GroupEntity();
                                    entity.id            = gr.id;
                                    entity.name          = gr.name;
                                    entity.description   = gr.description;
                                    entity.iconUrl       = gr.iconUrl;
                                    entity.createdBy     = gr.createdBy;
                                    entity.lastMessage   = gr.lastMessage;
                                    entity.lastSenderName = gr.lastSenderName;
                                    entity.lastMessageAt = gr.lastMessageAt;
                                    entity.lastMessageType      = gr.lastMessageType;
                                    entity.lastMessageStatus    = gr.lastMessageStatus;
                                    entity.lastMessageSenderUid = gr.lastMessageSenderUid;
                                    entity.lastMessageId        = gr.lastMessageId;
                                    toSave.add(entity);
                                }
                                if (--pending[0] == 0) {
                                    if (isAdded() && adapter != null) diffUpdateGroups(fetched);
                                    if (isAdded() && getContext() != null && !toSave.isEmpty()) {
                                        AppDatabase db = AppDatabase.getInstance(getContext());
                                        Executors.newSingleThreadExecutor().execute(() ->
                                            db.groupDao().insertGroups(toSave));
                                    }
                                }
                            }
                            @Override public void onCancelled(DatabaseError e) {
                                if (--pending[0] == 0) {
                                    if (getActivity() != null) {
                                        getActivity().runOnUiThread(() -> {
                                            if (isAdded() && adapter != null)
                                                diffUpdateGroups(fetched);
                                        });
                                    }
                                }
                            }
                        });
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getUserGroupsRef(uid).addValueEventListener(groupsListener);
    }

    /**
     * v83: DiffUtil logic moved into GroupAdapter.DIFF_CALLBACK + AsyncListDiffer.
     * The diff now runs on a background thread (AsyncListDiffer default executor)
     * so the main thread never blocks on calculateDiff(). Local `groups` list is
     * kept as a working copy for Room-save and empty-state checks; the adapter's
     * differ owns the authoritative display list.
     */
    private void diffUpdateGroups(List<Group> newList) {
        groups.clear();
        groups.addAll(newList);
        if (adapter != null) adapter.submitList(new ArrayList<>(groups));
    }
}
