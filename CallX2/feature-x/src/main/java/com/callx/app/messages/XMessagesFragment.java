package com.callx.app.messages;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.callx.app.search.XSearchActivity;

/**
 * XMessagesFragment — v32 upgrade.
 *
 * New in v32:
 *   ✅ Group DMs section (above 1:1 DMs), loaded from x/dm_groups/{uid}
 *   ✅ "New Group" FAB/button opens XCreateGroupDMActivity
 *   ✅ Group rows navigate to XGroupDMConversationActivity
 *   ✅ Unread badges for group DMs
 *
 * Layout expects:
 *   - btn_x_new_dm           → new 1:1 DM (XSearchActivity dm_mode)
 *   - btn_x_new_group_dm     → new group DM (XCreateGroupDMActivity)
 *   - tv_section_groups      → "Groups" label (VISIBLE only when groups exist)
 *   - rv_x_group_dms         → group DM list RecyclerView
 *   - tv_section_direct      → "Direct Messages" label
 *   - rv_x_messages          → 1:1 DM list RecyclerView
 *   - swipe_x_messages       → SwipeRefreshLayout wrapping both lists
 */
public class XMessagesFragment extends Fragment {

    // 1:1 DM list
    private RecyclerView      rvMessages;
    private XMessagePreviewAdapter adapter;
    private ValueEventListener convListener;

    // Group DM list
    private RecyclerView       rvGroupDms;
    private XGroupPreviewAdapter groupAdapter;
    private ValueEventListener groupListener;

    // Section labels
    private View tvSectionGroups;
    private View tvSectionDirect;

    private SwipeRefreshLayout swipeRefresh;
    private String myUid;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_x_messages, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        // ── 1:1 DMs ─────────────────────────────────────────────────────
        rvMessages    = view.findViewById(R.id.rv_x_messages);
        swipeRefresh  = view.findViewById(R.id.swipe_x_messages);

        adapter = new XMessagePreviewAdapter(requireContext());
        if (rvMessages != null) {
            rvMessages.setLayoutManager(new LinearLayoutManager(requireContext()));
            rvMessages.setItemAnimator(null);
            rvMessages.setAdapter(adapter);
        }

        // ── Group DMs ────────────────────────────────────────────────────
        rvGroupDms     = view.findViewById(R.id.rv_x_group_dms);
        tvSectionGroups= view.findViewById(R.id.tv_section_groups);
        tvSectionDirect= view.findViewById(R.id.tv_section_direct);

        groupAdapter = new XGroupPreviewAdapter(requireContext());
        if (rvGroupDms != null) {
            rvGroupDms.setLayoutManager(new LinearLayoutManager(requireContext()));
            rvGroupDms.setItemAnimator(null);
            rvGroupDms.setAdapter(groupAdapter);
        }

        // ── Buttons ───────────────────────────────────────────────────────
        View btnNewDm = view.findViewById(R.id.btn_x_new_dm);
        if (btnNewDm != null)
            btnNewDm.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), XSearchActivity.class)
                            .putExtra("dm_mode", true)));

        View btnNewGroupDm = view.findViewById(R.id.btn_x_new_group_dm);
        if (btnNewGroupDm != null)
            btnNewGroupDm.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), XCreateGroupDMActivity.class)));

        if (swipeRefresh != null)
            swipeRefresh.setOnRefreshListener(this::loadAll);

        loadAll();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Load both lists
    // ─────────────────────────────────────────────────────────────────────

    private void loadAll() {
        if (myUid.isEmpty()) return;
        if (swipeRefresh != null) swipeRefresh.setRefreshing(true);
        loadDirectDMs();
        loadGroupDMs();
    }

    // ── 1:1 DMs ──────────────────────────────────────────────────────────

    private void loadDirectDMs() {
        if (myUid.isEmpty()) return;
        if (convListener != null)
            XFirebaseUtils.xDmConversationsRef(myUid).removeEventListener(convListener);

        convListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                List<XMessagePreviewAdapter.ConversationPreview> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XMessagePreviewAdapter.ConversationPreview p =
                            new XMessagePreviewAdapter.ConversationPreview();
                    p.conversationId = ds.getKey();
                    p.otherUid       = ds.child("otherUid").getValue(String.class);
                    p.otherName      = ds.child("otherName").getValue(String.class);
                    p.otherHandle    = ds.child("otherHandle").getValue(String.class);
                    p.otherPhotoUrl  = ds.child("otherPhoto").getValue(String.class);
                    p.otherThumbUrl  = ds.child("otherThumb").getValue(String.class);
                    p.lastMessage    = ds.child("lastMessage").getValue(String.class);
                    Long ts          = ds.child("lastMessageTs").getValue(Long.class);
                    p.lastTs         = ts != null ? ts : 0;
                    Boolean unread   = ds.child("unread").getValue(Boolean.class);
                    p.unread         = Boolean.TRUE.equals(unread);
                    list.add(p);
                }
                Collections.sort(list, (a, b) -> Long.compare(b.lastTs, a.lastTs));
                adapter.setItems(list);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (isAdded() && swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        };
        XFirebaseUtils.xDmConversationsRef(myUid)
                .orderByChild("lastMessageTs")
                .limitToLast(50)
                .addValueEventListener(convListener);
    }

    // ── Group DMs ─────────────────────────────────────────────────────────

    private void loadGroupDMs() {
        if (myUid.isEmpty()) return;
        if (groupListener != null)
            XFirebaseUtils.xDmGroupsRef().removeEventListener(groupListener);

        groupListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                List<XGroupPreviewAdapter.GroupPreview> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    // Only show groups where I am a member
                    if (!ds.child("members").hasChild(myUid)) continue;

                    XGroupPreviewAdapter.GroupPreview g = new XGroupPreviewAdapter.GroupPreview();
                    g.groupId      = ds.getKey();
                    g.name         = ds.child("name").getValue(String.class);
                    g.iconUrl      = ds.child("iconUrl").getValue(String.class);
                    g.lastMessage  = ds.child("lastMessage").getValue(String.class);
                    g.lastSenderName = ds.child("lastSenderName").getValue(String.class);
                    Long ts        = ds.child("lastMessageTs").getValue(Long.class);
                    g.lastTs       = ts != null ? ts : 0;
                    Boolean unread = ds.child("unread").child(myUid).getValue(Boolean.class);
                    g.unread       = Boolean.TRUE.equals(unread);
                    int memberCount = (int) ds.child("members").getChildrenCount();
                    g.memberCount  = memberCount;
                    list.add(g);
                }
                Collections.sort(list, (a, b) -> Long.compare(b.lastTs, a.lastTs));
                groupAdapter.setItems(list);

                // Show/hide the "Groups" section label
                boolean hasGroups = !list.isEmpty();
                if (tvSectionGroups != null)
                    tvSectionGroups.setVisibility(hasGroups ? View.VISIBLE : View.GONE);
                if (rvGroupDms != null)
                    rvGroupDms.setVisibility(hasGroups ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        // Query all groups — filter by membership client-side
        // (For large-scale apps this would use x/user_groups/{uid} index)
        XFirebaseUtils.xDmGroupsRef()
                .orderByChild("lastMessageTs")
                .limitToLast(30)
                .addValueEventListener(groupListener);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle cleanup
    // ─────────────────────────────────────────────────────────────────────

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (convListener != null)
            XFirebaseUtils.xDmConversationsRef(myUid).removeEventListener(convListener);
        if (groupListener != null)
            XFirebaseUtils.xDmGroupsRef().removeEventListener(groupListener);
    }
}
