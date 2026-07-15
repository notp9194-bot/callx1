package com.callx.app.community;

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

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.repository.CommunityRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * v31: Search results fragment used inside CommunitySearchActivity.
 * mode: "posts" | "members" | "groups"
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
    private String currentQuery = "";

    // Adapter for posts
    private CommunityPostSearchAdapter postSearchAdapter;
    // Adapter for members
    private CommunityMemberSearchAdapter memberSearchAdapter;

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
                // Show group names — use a simple text adapter stub
                TextView tv = new TextView(requireContext());
                tv.setPadding(32, 32, 32, 32);
                tv.setText("Search groups coming soon");
                // For now use empty state
                layoutEmpty.setVisibility(View.VISIBLE);
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
            case "members":
                List<CommunityMemberEntity> filteredMembers = new ArrayList<>();
                for (CommunityMemberEntity m : allMembers) {
                    if (m.name != null && m.name.toLowerCase().contains(q)) {
                        filteredMembers.add(m);
                    }
                }
                if (memberSearchAdapter != null) memberSearchAdapter.submitList(filteredMembers);
                boolean emptyM = filteredMembers.isEmpty();
                rvResults.setVisibility(emptyM && !q.isEmpty() ? View.GONE : View.VISIBLE);
                layoutEmpty.setVisibility(emptyM && !q.isEmpty() ? View.VISIBLE : View.GONE);
                break;
            default: // posts
                if (q.isEmpty()) {
                    if (postSearchAdapter != null) postSearchAdapter.submitList(new ArrayList<>());
                    layoutEmpty.setVisibility(View.VISIBLE);
                    rvResults.setVisibility(View.GONE);
                    return;
                }
                List<CommunityPostEntity> filteredPosts = new ArrayList<>();
                for (CommunityPostEntity p : allPosts) {
                    if ((p.text != null && p.text.toLowerCase().contains(q))
                            || (p.authorName != null && p.authorName.toLowerCase().contains(q))) {
                        filteredPosts.add(p);
                    }
                }
                if (postSearchAdapter != null) postSearchAdapter.submitList(filteredPosts);
                boolean emptyP = filteredPosts.isEmpty();
                rvResults.setVisibility(emptyP ? View.GONE : View.VISIBLE);
                layoutEmpty.setVisibility(emptyP ? View.VISIBLE : View.GONE);
                break;
        }
    }
}
