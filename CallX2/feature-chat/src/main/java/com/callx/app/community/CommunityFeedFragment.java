package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CommunityFeedFragment — the main scrollable feed tab (regular posts,
 * isAnnouncement=false). CommunityAnnouncementsFragment is a near-identical
 * sibling that observes observeAnnouncements() instead — both share
 * CommunityPostAdapter so there's exactly one post-rendering codepath.
 *
 * Reuses chat's RecyclerView performance conventions: fixed size, no item
 * animator, capped view cache — same as MessagePagingAdapter/GroupsFragment.
 */
public class CommunityFeedFragment extends Fragment implements CommunityPostAdapter.Listener {

    private static final String ARG_COMMUNITY_ID = "communityId";

    protected String communityId;
    protected String currentUid;
    protected CommunityRepository repo;

    private RecyclerView rvFeed;
    private View emptyState;
    private SwipeRefreshLayout swipeRefresh;
    protected CommunityPostAdapter adapter;

    /** postId -> liked-by-me — adapter has no local like-state, cached here per screen session. */
    private final Map<String, Boolean> likedCache = new HashMap<>();
    /** postId -> my vote index for that post's poll (if any). */
    private final Map<String, Integer> myVotes = new HashMap<>();

    public static CommunityFeedFragment newInstance(String communityId) {
        CommunityFeedFragment f = new CommunityFeedFragment();
        Bundle args = new Bundle();
        args.putString(ARG_COMMUNITY_ID, communityId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        communityId = getArguments() != null ? getArguments().getString(ARG_COMMUNITY_ID) : null;
        currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_community_feed, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        rvFeed       = view.findViewById(R.id.rv_feed);
        emptyState   = view.findViewById(R.id.layout_empty);

        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvFeed.setLayoutManager(llm);
        rvFeed.setHasFixedSize(true);
        rvFeed.setItemViewCacheSize(15);
        rvFeed.setItemAnimator(null);
        rvFeed.setOverScrollMode(View.OVER_SCROLL_NEVER);

        adapter = new CommunityPostAdapter(this);
        rvFeed.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(() -> {
            if (communityId != null) repo.syncRecentPosts(communityId, isAnnouncementsTab());
            swipeRefresh.postDelayed(() -> swipeRefresh.setRefreshing(false), 800);
        });

        if (communityId != null) {
            observeFeedSource().observe(getViewLifecycleOwner(), this::onPostsUpdated);
            repo.syncRecentPosts(communityId, isAnnouncementsTab());
        }
    }

    /** Subclass hook — CommunityAnnouncementsFragment overrides both of these. */
    protected androidx.lifecycle.LiveData<List<CommunityPostEntity>> observeFeedSource() {
        return repo.observeFeed(communityId);
    }

    protected boolean isAnnouncementsTab() {
        return false;
    }

    private void onPostsUpdated(List<CommunityPostEntity> posts) {
        if (!isAdded()) return;
        adapter.submitList(posts);
        boolean empty = posts == null || posts.isEmpty();
        rvFeed.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    // ── CommunityPostAdapter.Listener ───────────────────────────────────────

    @Override
    public void onLikeClicked(CommunityPostEntity post) {
        if (!isAdded() || currentUid == null) return;
        boolean wasLiked = Boolean.TRUE.equals(likedCache.get(post.id));
        likedCache.put(post.id, !wasLiked);
        adapter.notifyDataSetChanged(); // small list per screen; fine for an immediate optimistic like flip
        repo.toggleLike(communityId, post.id, currentUid, (success, error) -> {
            if (!success && isAdded()) {
                likedCache.put(post.id, wasLiked); // revert on failure
                requireActivity().runOnUiThread(() -> adapter.notifyDataSetChanged());
            }
        });
    }

    @Override
    public void onCommentClicked(CommunityPostEntity post) {
        if (!isAdded()) return;
        Intent i = new Intent(requireContext(), CommunityPostCommentsActivity.class);
        i.putExtra(CommunityPostCommentsActivity.EXTRA_COMMUNITY_ID, communityId);
        i.putExtra(CommunityPostCommentsActivity.EXTRA_POST_ID, post.id);
        startActivity(i);
    }

    @Override
    public void onVote(CommunityPostEntity post, int optionIndex) {
        if (!isAdded() || currentUid == null) return;
        myVotes.put(post.id, optionIndex);
        repo.votePoll(communityId, post.id, currentUid, optionIndex, (success, error) -> {
            if (!success) myVotes.remove(post.id);
        });
    }

    @Override
    public Integer myVoteFor(CommunityPostEntity post) {
        Integer cached = myVotes.get(post.id);
        if (cached != null) return cached;
        CommunityPoll poll = CommunityPoll.fromJson(post.pollJson);
        return poll != null && currentUid != null ? poll.votedOptionOf(currentUid) : null;
    }

    @Override
    public boolean isLikedByMe(CommunityPostEntity post) {
        return Boolean.TRUE.equals(likedCache.get(post.id));
    }
}
