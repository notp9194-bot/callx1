package com.callx.app.community;

import android.app.AlertDialog;
import android.content.Intent;
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
import com.callx.app.community.canvas.CommunityAvatarPreloader;
import com.callx.app.community.canvas.CommunityScrollOptimizer;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.util.List;

/**
 * v31: Members tab — lists members with role badges + member badges.
 * Admin long-press menu now includes: Make Admin, Remove Admin, Mute/Unmute, Ban, Assign Badge.
 */
public class CommunityMembersFragment extends Fragment {

    private static final String ARG_COMMUNITY_ID = "communityId";

    private String communityId;
    private String currentUid;

    private RecyclerView rvMembers;
    private View emptyState;
    private CommunityMemberAdapter adapter;
    private CommunityRepository repo;

    private String myRole = CommunityRole.MEMBER;
    private String myName = "";

    public static CommunityMembersFragment newInstance(String communityId) {
        CommunityMembersFragment f = new CommunityMembersFragment();
        Bundle args = new Bundle();
        args.putString(ARG_COMMUNITY_ID, communityId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        communityId = getArguments() != null ? getArguments().getString(ARG_COMMUNITY_ID) : null;
        currentUid  = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        myName = FirebaseAuth.getInstance().getCurrentUser() != null
                && FirebaseAuth.getInstance().getCurrentUser().getDisplayName() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getDisplayName() : "";
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

        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvMembers.setLayoutManager(llm);
        CommunityScrollOptimizer.apply(rvMembers, llm);
        CommunityScrollOptimizer.applySharedPool(rvMembers);
        rvMembers.setHasFixedSize(true);
        rvMembers.setItemAnimator(null);
        rvMembers.setOverScrollMode(View.OVER_SCROLL_NEVER);

        adapter = new CommunityMemberAdapter(currentUid);
        rvMembers.setAdapter(adapter);
        CommunityAvatarPreloader.attachAvatar(this, rvMembers,
                new CommunityAvatarPreloader.UrlProvider() {
                    @Override public String urlAt(int pos) {
                        java.util.List<com.callx.app.db.entity.CommunityMemberEntity> list =
                                adapter.getCurrentList();
                        return (pos >= 0 && pos < list.size()) ? list.get(pos).photoUrl : null;
                    }
                    @Override public int count() { return adapter.getItemCount(); }
                }, 44);
        adapter.setOnMemberLongPressListener(this::onMemberLongPress);
        rvMembers.setAdapter(adapter);

        if (communityId != null) {
            repo.observeMembers(communityId).observe(getViewLifecycleOwner(), this::onMembersUpdated);
        }
    }

    private void onMembersUpdated(List<CommunityMemberEntity> members) {
        if (!isAdded()) return;
        if (members != null && currentUid != null) {
            for (CommunityMemberEntity m : members) {
                if (currentUid.equals(m.uid)) {
                    myRole = m.role != null ? m.role : CommunityRole.MEMBER;
                    myName = m.name != null ? m.name : myName;
                    break;
                }
            }
        }
        adapter.setMyRole(myRole);
        adapter.submitList(members);
        boolean empty = members == null || members.isEmpty();
        rvMembers.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void onMemberLongPress(CommunityMemberEntity m) {
        if (!CommunityRole.isAdminOrOwner(myRole)) return;
        if (currentUid != null && currentUid.equals(m.uid)) return; // can't act on yourself

        PopupMenu popup = new PopupMenu(requireContext(), requireView());

        // Role management
        if (CommunityRole.OWNER.equals(myRole)) {
            if (CommunityRole.ADMIN.equals(m.role)) {
                popup.getMenu().add(0, 1, 0, "Remove Admin");
            } else if (CommunityRole.MEMBER.equals(m.role)) {
                popup.getMenu().add(0, 2, 0, "Make Admin");
            }
        }

        // Mute / Unmute
        popup.getMenu().add(0, m.isMuted ? 3 : 4, 0, m.isMuted ? "Unmute Member" : "Mute Member");

        // Ban
        popup.getMenu().add(0, 5, 0, "Ban Member");

        // Badge assignment
        popup.getMenu().add(0, 6, 0, "Assign Badge");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: // Remove admin
                    repo.setMemberRole(communityId, m.uid, CommunityRole.MEMBER, currentUid, myName, simpleToast());
                    return true;
                case 2: // Make admin
                    repo.setMemberRole(communityId, m.uid, CommunityRole.ADMIN, currentUid, myName, simpleToast());
                    return true;
                case 3: // Unmute
                    repo.muteMember(communityId, m.uid, false, currentUid, myName, null, simpleToast());
                    return true;
                case 4: // Mute
                    showMuteReasonDialog(m);
                    return true;
                case 5: // Ban
                    showBanConfirmDialog(m);
                    return true;
                case 6: // Badge
                    showBadgePickerDialog(m);
                    return true;
                default: return false;
            }
        });
        popup.show();
    }

    private void showMuteReasonDialog(CommunityMemberEntity m) {
        android.widget.EditText et = new android.widget.EditText(requireContext());
        et.setHint("Reason (optional)");
        new AlertDialog.Builder(requireContext())
                .setTitle("Mute " + m.name + "?")
                .setMessage("Muted members can read but cannot post.")
                .setView(et)
                .setPositiveButton("Mute", (d, w) -> {
                    String reason = et.getText().toString().trim();
                    repo.muteMember(communityId, m.uid, true, currentUid, myName,
                            reason.isEmpty() ? null : reason, simpleToast());
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showBanConfirmDialog(CommunityMemberEntity m) {
        android.widget.EditText et = new android.widget.EditText(requireContext());
        et.setHint("Reason (optional)");
        new AlertDialog.Builder(requireContext())
                .setTitle("Ban " + m.name + "?")
                .setMessage("This removes them from the community.")
                .setView(et)
                .setPositiveButton("Ban", (d, w) -> {
                    String reason = et.getText().toString().trim();
                    repo.removeMember(communityId, m.uid, currentUid, myName,
                            reason.isEmpty() ? null : reason, simpleToast());
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showBadgePickerDialog(CommunityMemberEntity m) {
        String[] badges = {
                CommunityBadge.NONE + " (Remove Badge)",
                CommunityBadge.EARLY_MEMBER + " 🌱 Early Member",
                CommunityBadge.ACTIVE + " ⚡ Active",
                CommunityBadge.TOP_CONTRIBUTOR + " 🏆 Top Contributor",
                CommunityBadge.VERIFIED + " ✅ Verified",
                CommunityBadge.MODERATOR + " 🛡️ Moderator"
        };
        String[] badgeKeys = {
                CommunityBadge.NONE, CommunityBadge.EARLY_MEMBER, CommunityBadge.ACTIVE,
                CommunityBadge.TOP_CONTRIBUTOR, CommunityBadge.VERIFIED, CommunityBadge.MODERATOR
        };
        new AlertDialog.Builder(requireContext())
                .setTitle("Assign Badge to " + m.name)
                .setItems(badges, (d, which) -> {
                    repo.setBadge(communityId, m.uid, badgeKeys[which], currentUid, myName,
                            (success, error) -> {
                                if (isAdded()) {
                                    requireActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(),
                                            success ? "Badge assigned" : "Failed: " + error,
                                            Toast.LENGTH_SHORT).show());
                                }
                            });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private CommunityRepository.SimpleCallback simpleToast() {
        return (success, error) -> {
            if (isAdded()) {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(),
                                success ? "Done" : "Failed: " + error, Toast.LENGTH_SHORT).show());
            }
        };
    }
}
