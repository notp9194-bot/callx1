package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;

/**
 * v32: Search results fragment used inside CommunitySearchActivity.
 * mode: "posts" | "members" | "groups"
 *
 * v32 UPGRADE: "groups" mode fully implemented — previously showed
 * "Search groups coming soon". Now fetches community-linked groups from
 * CommunityRepository (Room + Firebase), filters by name/description,
 * and navigates via the same resolveGroupAccess() used by CommunityGroupsFragment.
 */
public class CommunitySearchResultsFragment extends Fragment {

    private static final String ARG_COMMUNITY_ID = "communityId";
    private static final String ARG_MODE         = "mode";

    private String communityId;
    private String mode;

    private RecyclerView rvResults;
    private View layoutEmpty;
    private CommunityRepository repo;

    private List<CommunityPostEntity>   allPosts   = new ArrayList<>();
    private List<CommunityMemberEntity> allMembers = new ArrayList<>();
    private List<GroupEntity>           allGroups  = new ArrayList<>();
    private String currentQuery = "";

    // Adapters
    private CommunityPostSearchAdapter   postSearchAdapter;
    private CommunityMemberSearchAdapter memberSearchAdapter;
    private CommunityGroupAdapter        groupAdapter;

    public static CommunitySearchResultsFragment newInstance(String communityId, String mode) {
        CommunitySearchResultsFragment f = new CommunitySearchResultsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_COMMUNITY_ID, communityId);
        args.putString(ARG_MODE, mode);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        communityId = getArguments() != null ? getArguments().getString(ARG_COMMUNITY_ID) : null;
        mode        = getArguments() != null ? getArguments().getString(ARG_MODE, "posts") : "posts";
        repo = CommunityRepository.getInstance(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_community_search_results, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rvResults   = view.findViewById(R.id.rv_search_results);
        layoutEmpty = view.findViewById(R.id.layout_empty_search);

        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvResults.setLayoutManager(llm);
        rvResults.setHasFixedSize(false);
        rvResults.setItemAnimator(null);

        switch (mode) {
            case "members":
                memberSearchAdapter = new CommunityMemberSearchAdapter();
                rvResults.setAdapter(memberSearchAdapter);
                if (communityId != null) {
                    repo.observeMembers(communityId).observe(getViewLifecycleOwner(), members -> {
                        allMembers = members != null ? members : new ArrayList<>();
                        applyQuery(currentQuery);
                    });
                }
                break;

            case "groups":
                // v32: Full group search — replaces old "coming soon" stub.
                // Uses CommunityGroupAdapter with the same click → resolveGroupAccess()
                // flow as CommunityGroupsFragment for consistent behaviour.
                groupAdapter = new CommunityGroupAdapter();
                groupAdapter.setOnGroupClickListener(this::openGroup);
                rvResults.setAdapter(groupAdapter);
                if (communityId != null) {
                    // observeCommunityGroups returns all groups linked to this community
                    repo.observeCommunityGroups(communityId).observe(getViewLifecycleOwner(), groups -> {
                        allGroups = groups != null ? groups : new ArrayList<>();
                        applyQuery(currentQuery);
                    });
                }
                break;

            default: // posts
                postSearchAdapter = new CommunityPostSearchAdapter();
                rvResults.setAdapter(postSearchAdapter);
                if (communityId != null) {
                    repo.observeFeed(communityId).observe(getViewLifecycleOwner(), posts -> {
                        allPosts = posts != null ? posts : new ArrayList<>();
                        applyQuery(currentQuery);
                    });
                }
                break;
        }
    }

    /** Called by CommunitySearchActivity when the search query changes. */
    public void onQueryChanged(String query) {
        currentQuery = query;
        applyQuery(query);
    }

    private void applyQuery(String query) {
        if (!isAdded()) return;
        String q = query != null ? query.toLowerCase().trim() : "";

        switch (mode) {
            case "members": {
                List<CommunityMemberEntity> filtered = new ArrayList<>();
                for (CommunityMemberEntity m : allMembers) {
                    if (q.isEmpty() || (m.name != null && m.name.toLowerCase().contains(q))) {
                        filtered.add(m);
                    }
                }
                if (memberSearchAdapter != null) memberSearchAdapter.submitList(filtered);
                boolean empty = filtered.isEmpty() && !q.isEmpty();
                rvResults.setVisibility(empty ? View.GONE : View.VISIBLE);
                if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                break;
            }

            case "groups": {
                List<GroupEntity> filtered = new ArrayList<>();
                for (GroupEntity g : allGroups) {
                    if (q.isEmpty()
                            || (g.name != null && g.name.toLowerCase().contains(q))
                            || (g.description != null && g.description.toLowerCase().contains(q))) {
                        filtered.add(g);
                    }
                }
                if (groupAdapter != null) groupAdapter.submitList(filtered);
                boolean empty = filtered.isEmpty();
                rvResults.setVisibility(empty ? View.GONE : View.VISIBLE);
                if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                break;
            }

            default: { // posts
                if (q.isEmpty()) {
                    if (postSearchAdapter != null) postSearchAdapter.submitList(new ArrayList<>());
                    if (layoutEmpty != null) layoutEmpty.setVisibility(View.VISIBLE);
                    rvResults.setVisibility(View.GONE);
                    return;
                }
                List<CommunityPostEntity> filtered = new ArrayList<>();
                for (CommunityPostEntity p : allPosts) {
                    if ((p.text != null && p.text.toLowerCase().contains(q))
                            || (p.authorName != null && p.authorName.toLowerCase().contains(q))) {
                        filtered.add(p);
                    }
                }
                if (postSearchAdapter != null) postSearchAdapter.submitList(filtered);
                boolean empty = filtered.isEmpty();
                rvResults.setVisibility(empty ? View.GONE : View.VISIBLE);
                if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Group navigation — mirrors CommunityGroupsFragment.onGroupClick()
    // ─────────────────────────────────────────────────────────────────────

    private void openGroup(GroupEntity group) {
        if (!isAdded() || communityId == null) return;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String myUid   = user != null ? user.getUid() : null;
        String myName  = user != null && user.getDisplayName() != null ? user.getDisplayName() : "";
        String myPhoto = user != null && user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;

        if (myUid == null) {
            Toast.makeText(requireContext(), "Please log in first", Toast.LENGTH_SHORT).show();
            return;
        }

        repo.resolveGroupAccess(communityId, group.id, myUid, myName, myPhoto,
                new CommunityRepository.GroupAccessListener() {
                    @Override public void onGranted() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            Intent i = new Intent(requireContext(),
                                    com.callx.app.group.GroupChatActivity.class);
                            i.putExtra("groupId",   group.id);
                            i.putExtra("groupName", group.name);
                            startActivity(i);
                        });
                    }
                    @Override public void onNotCommunityMember() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        "Join the community first to access its groups",
                                        Toast.LENGTH_SHORT).show());
                    }
                    @Override public void onRequestPending() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        "Your join request is pending admin approval",
                                        Toast.LENGTH_SHORT).show());
                    }
                    @Override public void onRequestSent() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        "Join request sent — wait for admin approval",
                                        Toast.LENGTH_SHORT).show());
                    }
                    @Override public void onError(String msg) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        "Error: " + msg, Toast.LENGTH_SHORT).show());
                    }
                });
    }
}
