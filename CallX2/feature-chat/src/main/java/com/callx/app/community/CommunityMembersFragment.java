package com.callx.app.community;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.community.CommunityRole;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.util.List;

/**
 * CommunityMembersFragment — lists all members of a community with role
 * badges (OWNER / ADMIN chip, plain for MEMBER).
 *
 * Admin-flag tradeoff (same as CommunityGroupsFragment): the long-press
 * options popup is gated by checking the current user's role from the Room
 * LiveData — plain MEMBERs don't see the popup options. Firebase rules
 * are the real server-side enforcement.
 */
public class CommunityMembersFragment extends Fragment {

    private static final String ARG_COMMUNITY_ID = "communityId";

    private String communityId;
    private String currentUid;

    private RecyclerView rvMembers;
    private View emptyState;
    private CommunityMemberAdapter adapter;
    private CommunityRepository repo;

    // Current user's role — updated when member list loads
    private String myRole = CommunityRole.MEMBER;

    // ── Factory ────────────────────────────────────────────────────────────
    public static CommunityMembersFragment newInstance(String communityId) {
        CommunityMembersFragment f = new CommunityMembersFragment();
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
        return inflater.inflate(R.layout.fragment_community_members, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvMembers  = view.findViewById(R.id.rv_community_members);
        emptyState = view.findViewById(R.id.empty_community_members);

        // RecyclerView setup — matching app performance conventions
        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvMembers.setLayoutManager(llm);
        rvMembers.setHasFixedSize(true);
        rvMembers.setItemViewCacheSize(15);
        rvMembers.setItemAnimator(null);
        rvMembers.setOverScrollMode(View.OVER_SCROLL_NEVER);

        adapter = new CommunityMemberAdapter(currentUid);
        adapter.setOnMemberLongPressListener(this::onMemberLongPress);
        rvMembers.setAdapter(adapter);

        if (communityId != null) {
            repo.observeMembers(communityId).observe(getViewLifecycleOwner(), this::onMembersUpdated);
        }
    }

    private void onMembersUpdated(List<CommunityMemberEntity> members) {
        if (!isAdded()) return;
        adapter.submitList(members);
        boolean empty = members == null || members.isEmpty();
        rvMembers.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);

        // Update cached role for current user
        if (members != null && currentUid != null) {
            for (CommunityMemberEntity m : members) {
                if (currentUid.equals(m.uid)) {
                    myRole = m.role != null ? m.role : CommunityRole.MEMBER;
                    break;
                }
            }
        }
    }

    /**
     * Long-press on a member row: show admin actions popup.
     * Only shown if current user is ADMIN or OWNER, and target is not OWNER.
     */
    private void onMemberLongPress(CommunityMemberEntity target) {
        if (!isAdded() || communityId == null) return;

        // Gate: current user must be admin/owner
        if (!CommunityRole.isAdminOrOwner(myRole)) return;

        // Gate: cannot action against the OWNER
        if (CommunityRole.OWNER.equals(target.role)) return;

        // Don't show popup for yourself (no self-remove in this UI)
        if (currentUid != null && currentUid.equals(target.uid)) return;

        // Build PopupMenu anchored to the tapped view
        // (We use an AlertDialog instead since PopupMenu needs an anchor View
        //  and we don't have one from the long-press directly)
        String targetName = target.name != null ? target.name : "this member";
        boolean isTargetAdmin = CommunityRole.ADMIN.equals(target.role);

        String[] options = isTargetAdmin
                ? new String[]{"Remove as Admin", "Remove from Community"}
                : new String[]{"Make Admin 👑", "Remove from Community"};

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(targetName)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        // Toggle admin
                        String newRole = isTargetAdmin ? CommunityRole.MEMBER : CommunityRole.ADMIN;
                        repo.updateMemberRole(communityId, target.uid, newRole,
                                (success, error) -> {
                                    if (!isAdded()) return;
                                    if (!success) {
                                        requireActivity().runOnUiThread(() ->
                                                Toast.makeText(requireContext(),
                                                        "Failed: " + error, Toast.LENGTH_SHORT).show());
                                    }
                                });
                    } else {
                        // Remove from community
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Remove Member")
                                .setMessage("Remove " + targetName + " from this community?")
                                .setPositiveButton("Remove", (d2, w2) ->
                                        repo.removeMember(communityId, target.uid,
                                                (success, error) -> {
                                                    if (!isAdded()) return;
                                                    if (!success) {
                                                        requireActivity().runOnUiThread(() ->
                                                                Toast.makeText(requireContext(),
                                                                        "Failed: " + error,
                                                                        Toast.LENGTH_SHORT).show());
                                                    }
                                                }))
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .show();
    }
}
