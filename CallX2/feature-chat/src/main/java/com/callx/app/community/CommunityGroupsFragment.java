package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.community.CommunityRole;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;

import java.util.List;

/**
 * CommunityGroupsFragment — shows the group chats linked to a community.
 *
 * Admin-flag tradeoff: We show the FAB to everyone and rely on
 * CommunityRepository / Firebase security rules as the real enforcement gate.
 * A proper per-row admin check is done via observeMembers so the FAB is
 * hidden for plain members once the member list loads — see onMembersLoaded().
 *
 * NOTE: This Fragment is instantiated by CommunityActivity's ViewPager2
 * adapter via newInstance(communityId). Arguments survive config changes.
 */
public class CommunityGroupsFragment extends Fragment {

    private static final String ARG_COMMUNITY_ID = "communityId";

    private String communityId;
    private String currentUid;

    private RecyclerView rvGroups;
    private View emptyState;
    private FloatingActionButton fabAdd;

    private CommunityGroupAdapter adapter;
    private CommunityRepository repo;

    // ── Factory ────────────────────────────────────────────────────────────
    public static CommunityGroupsFragment newInstance(String communityId) {
        CommunityGroupsFragment f = new CommunityGroupsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_COMMUNITY_ID, communityId);
        f.setArguments(args);
        return f;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        communityId = getArguments() != null ? getArguments().getString(ARG_COMMUNITY_ID) : null;
        currentUid  = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_community_groups, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvGroups   = view.findViewById(R.id.rv_community_groups);
        emptyState = view.findViewById(R.id.empty_community_groups);
        fabAdd     = view.findViewById(R.id.fab_add_group);

        // RecyclerView setup — matching GroupsFragment performance conventions
        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvGroups.setLayoutManager(llm);
        rvGroups.setHasFixedSize(true);
        rvGroups.setItemViewCacheSize(15);
        rvGroups.setItemAnimator(null);
        rvGroups.setOverScrollMode(View.OVER_SCROLL_NEVER);

        adapter = new CommunityGroupAdapter();
        adapter.setOnGroupLongPressListener(this::onGroupLongPress);
        adapter.setOnGroupClickListener(this::onGroupClick);
        rvGroups.setAdapter(adapter);

        // FAB — opens CommunityAddGroupActivity
        fabAdd.setOnClickListener(v -> {
            if (communityId == null) return;
            Intent i = new Intent(requireContext(), CommunityAddGroupActivity.class);
            i.putExtra(CommunityAddGroupActivity.EXTRA_COMMUNITY_ID, communityId);
            startActivity(i);
        });

        // Observe groups via Room LiveData
        if (communityId != null) {
            repo.observeCommunityGroups(communityId).observe(getViewLifecycleOwner(), this::onGroupsUpdated);

            // Observe members to determine if current user is admin/owner
            repo.observeMembers(communityId).observe(getViewLifecycleOwner(), this::onMembersLoaded);

            // Kick off a Firebase sync so the Room cache is fresh
            repo.syncRecentPosts(communityId, false);
        }
    }

    private void onGroupsUpdated(List<GroupEntity> groups) {
        if (!isAdded()) return;
        adapter.submitList(groups);
        boolean empty = groups == null || groups.isEmpty();
        rvGroups.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /**
     * Show/hide FAB based on the current user's community role.
     * This is the real admin gate — plain MEMBERs don't see the add button.
     */
    private void onMembersLoaded(List<CommunityMemberEntity> members) {
        if (!isAdded() || members == null || currentUid == null) return;
        boolean isAdminOrOwner = false;
        for (CommunityMemberEntity m : members) {
            if (currentUid.equals(m.uid)) {
                isAdminOrOwner = CommunityRole.isAdminOrOwner(m.role);
                break;
            }
        }
        fabAdd.setVisibility(isAdminOrOwner ? View.VISIBLE : View.GONE);
    }

    /**
     * v32: Community Access System — tap gate before opening a linked group.
     * WhatsApp-style: OPEN groups auto-join and open immediately.
     * Instagram-style: ADMIN_ONLY groups get an ask-to-join request that a
     * community admin/owner approves from CommunityJoinRequestsActivity.
     */
    private void onGroupClick(GroupEntity group) {
        if (!isAdded() || communityId == null || currentUid == null) return;

        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String myName = user != null && user.getDisplayName() != null ? user.getDisplayName() : "";
        String myPhoto = user != null && user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;

        repo.resolveGroupAccess(communityId, group.id, currentUid, myName, myPhoto,
                new CommunityRepository.GroupAccessListener() {
                    @Override public void onGranted() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            Intent i = new Intent(requireContext(), com.callx.app.group.GroupChatActivity.class);
                            i.putExtra("groupId", group.id);
                            i.putExtra("groupName", group.name);
                            startActivity(i);
                        });
                    }
                    @Override public void onNotCommunityMember() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        "Join the community first to access its groups", Toast.LENGTH_SHORT).show());
                    }
                    @Override public void onRequestPending() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        "Your request to join this group is pending admin approval", Toast.LENGTH_SHORT).show());
                    }
                    @Override public void onRequestSent() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        "Ask-to-join sent — an admin needs to approve you", Toast.LENGTH_SHORT).show());
                    }
                    @Override public void onError(String message) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        "Couldn't check access: " + message, Toast.LENGTH_SHORT).show());
                    }
                });
    }

    /**
     * Long-press on a group row: confirm dialog → removeGroupFromCommunity.
     * Only admin/owners will see the FAB, but the long-press is always wired
     * and lets Firebase rules enforce permission server-side for safety.
     */
    private void onGroupLongPress(GroupEntity group) {
        if (!isAdded() || communityId == null) return;

        // Check current user role before showing the dialog
        // We check using the cached members — if no role found, skip action.
        repo.observeMembers(communityId).observe(getViewLifecycleOwner(), members -> {
            if (members == null || currentUid == null) return;
            boolean canManage = false;
            for (CommunityMemberEntity m : members) {
                if (currentUid.equals(m.uid)) {
                    canManage = CommunityRole.isAdminOrOwner(m.role);
                    break;
                }
            }
            if (!canManage) return;

            new AlertDialog.Builder(requireContext())
                    .setTitle("Remove Group")
                    .setMessage("Remove \"" + group.name + "\" from this community?")
                    .setPositiveButton("Remove", (d, w) ->
                            repo.removeGroupFromCommunity(communityId, group.id,
                                    (success, error) -> {
                                        if (!isAdded()) return;
                                        if (!success) {
                                            requireActivity().runOnUiThread(() ->
                                                    Toast.makeText(requireContext(),
                                                            "Failed: " + error, Toast.LENGTH_SHORT).show());
                                        }
                                    }))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }
}
