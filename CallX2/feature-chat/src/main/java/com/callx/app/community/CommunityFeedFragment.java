package com.callx.app.community;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v31: Community feed fragment.
 * New: reaction picker on long-press of like, delete/report in post options.
 */
public class CommunityFeedFragment extends Fragment implements CommunityPostAdapter.Listener {

    private static final String ARG_COMMUNITY_ID    = "communityId";
    private static final String ARG_IS_ANNOUNCEMENT = "isAnnouncement";

    // PERF: see CommunityDao.observeFeedWindowed's javadoc — the live feed
    // query is capped to this many most-recent posts so any single member's
    // like/vote/comment doesn't re-query and re-diff a community's entire
    // post history. WINDOW_SIZE is what's kept "live" (auto-updating);
    // LOAD_MORE_PAGE_SIZE is how many additional (non-live, static) older
    // posts get paged in locally each time the user scrolls near the
    // bottom — see loadOlderIfNeeded()/onScrolled below.
    protected static final int WINDOW_SIZE = 40;
    private static final int LOAD_MORE_PAGE_SIZE = 30;
    private static final int LOAD_MORE_THRESHOLD = 6;

    protected String communityId;
    private boolean isAnnouncement;
    protected String currentUid;
    private String myRole = CommunityRole.MEMBER;
    private String myName = "";

    private RecyclerView rvFeed;
    private View emptyState;
    private CommunityPostAdapter adapter;
    protected CommunityRepository repo;

    // Reaction picker (shared, only one open at a time)
    private CommunityReactionPickerView reactionPicker;

    // PERF: load-more state. `latestWindow` is always exactly what the live
    // (auto-updating) query last emitted; `olderExtra` is additional older
    // posts paged in locally on scroll, which are NOT live — they only
    // refresh the next time they scroll back into the live window naturally
    // (same trade-off MessagePagingAdapter's older pages make). Merged and
    // re-submitted to the adapter on every change to either.
    private List<CommunityPostEntity> latestWindow = Collections.emptyList();
    private final List<CommunityPostEntity> olderExtra = new ArrayList<>();
    private boolean isLoadingMore = false;
    private boolean hasMoreOlder = true;

    public static CommunityFeedFragment newInstance(String communityId) {
        CommunityFeedFragment f = new CommunityFeedFragment();
        Bundle args = new Bundle();
        args.putString(ARG_COMMUNITY_ID, communityId);
        args.putBoolean(ARG_IS_ANNOUNCEMENT, false);
        f.setArguments(args);
        return f;
    }

    public static CommunityFeedFragment newAnnouncementsInstance(String communityId) {
        CommunityFeedFragment f = new CommunityFeedFragment();
        Bundle args = new Bundle();
        args.putString(ARG_COMMUNITY_ID, communityId);
        args.putBoolean(ARG_IS_ANNOUNCEMENT, true);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        communityId    = getArguments() != null ? getArguments().getString(ARG_COMMUNITY_ID) : null;
        isAnnouncement = getArguments() != null && getArguments().getBoolean(ARG_IS_ANNOUNCEMENT, false);
        currentUid     = FirebaseAuth.getInstance().getCurrentUser() != null
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
        return inflater.inflate(R.layout.fragment_community_feed, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvFeed     = view.findViewById(R.id.rv_community_feed);
        emptyState = view.findViewById(R.id.empty_feed);

        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        llm.setInitialPrefetchItemCount(4);
        rvFeed.setLayoutManager(llm);
        rvFeed.setHasFixedSize(false);
        rvFeed.setItemViewCacheSize(10);
        rvFeed.setItemAnimator(null);
        rvFeed.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // PERF: CommunityPostCanvasView's constructor allocates ~45 Paint
        // objects (one View replaces what used to be a whole inflated
        // CardView/LinearLayout tree). The RecyclerView default recycled
        // pool only keeps 5 scrap views per type, so a fast fling through a
        // long feed was repeatedly re-running that Paint setup instead of
        // reusing an already-built view. Every row here is the same view
        // type, so it's safe to size the pool generously.
        rvFeed.setRecycledViewPool(new RecyclerView.RecycledViewPool());
        rvFeed.getRecycledViewPool().setMaxRecycledViews(0, 24);

        adapter = new CommunityPostAdapter(currentUid, this);
        rvFeed.setAdapter(adapter);

        // PERF: page in more locally-cached older posts as the user
        // approaches the bottom of what's currently loaded, instead of the
        // feed ever holding/rendering a community's entire post history at
        // once. Pure local Room read (see loadOlderIfNeeded()) — no network.
        rvFeed.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0) return; // only trigger when scrolling downward
                LinearLayoutManager mgr = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (mgr == null) return;
                int lastVisible = mgr.findLastVisibleItemPosition();
                int total = adapter.getItemCount();
                if (lastVisible >= 0 && total - lastVisible <= LOAD_MORE_THRESHOLD) {
                    loadOlderIfNeeded();
                }
            }
        });

        reactionPicker = new CommunityReactionPickerView(requireContext());
        reactionPicker.setOnReactionSelectedListener((reactionType) -> {
            // handled via onReaction callback which is set per-post
        });

        if (communityId != null) {
            observeFeedSource().observe(getViewLifecycleOwner(), this::onFeedUpdated);
            repo.observeMembers(communityId).observe(getViewLifecycleOwner(), this::onMembersUpdated);
            repo.syncRecentPosts(communityId, isAnnouncementsTab());
        }
    }

    /**
     * Which LiveData source feeds this tab. Overridden by
     * {@link CommunityAnnouncementsFragment} to scope to announcements only.
     * PERF: windowed (see WINDOW_SIZE) rather than an unbounded feed query —
     * pair with loadOlderIfNeeded() for the rest of a large community's history.
     */
    protected androidx.lifecycle.LiveData<List<CommunityPostEntity>> observeFeedSource() {
        return isAnnouncement ? repo.observeAnnouncementsWindowed(communityId, WINDOW_SIZE)
                               : repo.observeFeedWindowed(communityId, WINDOW_SIZE);
    }

    /** Whether this tab instance is the announcements-only variant. */
    protected boolean isAnnouncementsTab() {
        return isAnnouncement;
    }

    private void onFeedUpdated(List<CommunityPostEntity> windowPosts) {
        if (!isAdded()) return;
        latestWindow = windowPosts != null ? windowPosts : Collections.emptyList();
        // A fresh live emission always reflects the true current state of
        // the most recent WINDOW_SIZE posts, so drop any paged-in "older"
        // post that has since re-entered that live window to avoid ever
        // showing a stale (non-live-updating) copy alongside the live one.
        if (!olderExtra.isEmpty()) {
            Set<String> liveIds = new HashSet<>();
            for (CommunityPostEntity p : latestWindow) liveIds.add(p.id);
            // NOTE: List.removeIf() needs API 24+; this app's minSdk is 23,
            // so remove via Iterator instead of Collection.removeIf().
            java.util.Iterator<CommunityPostEntity> it = olderExtra.iterator();
            while (it.hasNext()) {
                if (liveIds.contains(it.next().id)) it.remove();
            }
        }
        mergeAndSubmit();
    }

    private void mergeAndSubmit() {
        List<CommunityPostEntity> merged;
        if (olderExtra.isEmpty()) {
            merged = latestWindow;
        } else {
            merged = new ArrayList<>(latestWindow.size() + olderExtra.size());
            merged.addAll(latestWindow);
            merged.addAll(olderExtra);
        }
        adapter.submitList(merged);
        boolean empty = merged.isEmpty();
        rvFeed.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** PERF: pages in the next LOAD_MORE_PAGE_SIZE older posts from Room
     *  (already-synced local cache — see CommunityRepository.loadOlderPostsLocal)
     *  once the user scrolls near the bottom of what's currently shown. */
    private void loadOlderIfNeeded() {
        if (isLoadingMore || !hasMoreOlder || communityId == null || !isAdded()) return;
        List<CommunityPostEntity> current = olderExtra.isEmpty() ? latestWindow
                : concatForCursor();
        if (current.isEmpty()) return;
        long oldestCreatedAt = current.get(current.size() - 1).createdAt;

        isLoadingMore = true;
        repo.loadOlderPostsLocal(communityId, isAnnouncementsTab(), oldestCreatedAt, LOAD_MORE_PAGE_SIZE,
                older -> {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        isLoadingMore = false;
                        if (older == null || older.isEmpty()) {
                            hasMoreOlder = false;
                            return;
                        }
                        olderExtra.addAll(older);
                        if (older.size() < LOAD_MORE_PAGE_SIZE) hasMoreOlder = false;
                        mergeAndSubmit();
                    });
                });
    }

    private List<CommunityPostEntity> concatForCursor() {
        List<CommunityPostEntity> all = new ArrayList<>(latestWindow.size() + olderExtra.size());
        all.addAll(latestWindow);
        all.addAll(olderExtra);
        return all;
    }

    private void onMembersUpdated(List<CommunityMemberEntity> members) {
        if (!isAdded() || members == null) return;
        for (CommunityMemberEntity m : members) {
            if (currentUid != null && currentUid.equals(m.uid)) {
                myRole = m.role != null ? m.role : CommunityRole.MEMBER;
                myName = m.name != null ? m.name : myName;
                break;
            }
        }
        adapter.setAdminOrOwner(CommunityRole.isAdminOrOwner(myRole));
    }

    // ─── Listener callbacks ─────────────────────────────────────────────────────

    @Override
    public void onLike(CommunityPostEntity post) {
        // Quick tap = toggle LIKE reaction
        if (currentUid == null || communityId == null) return;
        String reactionType = (post.myReactionType == null || post.myReactionType.isEmpty())
                ? CommunityReaction.LIKE : null;
        repo.addReaction(communityId, post.id, currentUid,
                reactionType != null ? reactionType : "", (success, error) -> {
                    if (!success && isAdded())
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "Failed", Toast.LENGTH_SHORT).show());
                });
    }

    @Override
    public void onLongPressLike(CommunityPostEntity post, View anchorView) {
        if (currentUid == null || communityId == null || !isAdded()) return;
        if (reactionPicker.isShowing()) reactionPicker.dismiss();
        reactionPicker.setOnReactionSelectedListener(reactionType -> {
            repo.addReaction(communityId, post.id, currentUid, reactionType, null);
        });
        reactionPicker.showAtView(anchorView);
    }

    @Override
    public void onReaction(CommunityPostEntity post, String reactionType) {
        if (currentUid == null || communityId == null) return;
        repo.addReaction(communityId, post.id, currentUid, reactionType, null);
    }

    @Override
    public void onComment(CommunityPostEntity post) {
        if (!isAdded()) return;
        android.content.Intent i = new android.content.Intent(requireContext(),
                CommunityPostCommentsActivity.class);
        i.putExtra(CommunityPostCommentsActivity.EXTRA_COMMUNITY_ID, communityId);
        i.putExtra(CommunityPostCommentsActivity.EXTRA_POST_ID, post.id);
        startActivity(i);
    }

    @Override
    public void onDelete(CommunityPostEntity post) {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Post?")
                .setMessage("This post will be permanently removed.")
                .setPositiveButton("Delete", (d, w) -> {
                    repo.deletePost(communityId, post.id, currentUid, myName, null,
                            (success, error) -> {
                                if (isAdded())
                                    requireActivity().runOnUiThread(() ->
                                            Toast.makeText(requireContext(),
                                                    success ? "Post deleted" : "Failed: " + error,
                                                    Toast.LENGTH_SHORT).show());
                            });
                })
                .setNegativeButton("Cancel", null).show();
    }

    @Override
    public void onReport(CommunityPostEntity post) {
        if (!isAdded()) return;
        android.widget.EditText et = new android.widget.EditText(requireContext());
        et.setHint("Reason (optional)");
        new AlertDialog.Builder(requireContext())
                .setTitle("Report Post")
                .setView(et)
                .setPositiveButton("Report", (d, w) -> {
                    String reason = et.getText().toString().trim();
                    repo.reportPost(communityId, post.id, currentUid,
                            reason.isEmpty() ? "inappropriate" : reason, null);
                    Toast.makeText(requireContext(), "Report submitted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    @Override
    public void onPollVote(CommunityPostEntity post, int optionIndex) {
        if (currentUid == null || communityId == null) return;
        repo.votePoll(communityId, post.id, currentUid, optionIndex, null);
    }

    @Override
    public void onMediaClicked(CommunityPostEntity post) {
        if (!isAdded() || post.mediaUrl == null) return;
        android.content.Intent i = new android.content.Intent(requireContext(),
                CommunityFullscreenMediaActivity.class);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_MEDIA_URL, post.mediaUrl);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_MEDIA_TYPE, post.mediaType);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_AUTHOR_NAME, post.authorName);
        startActivity(i);
    }
}
