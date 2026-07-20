package com.callx.app.community;

import android.app.AlertDialog;
import android.content.Intent;
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
import com.callx.app.community.canvas.CommunityAvatarPreloader;
import com.callx.app.community.canvas.CommunityScrollOptimizer;
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
 * v34: Community feed fragment — updated with share and bookmark support.
 *
 * New callbacks:
 *  onShare(post)    → Android system share sheet (text + media URL if any)
 *  onBookmark(post) → saved to CommunityBookmarksActivity via SharedPreferences/Firebase
 */
public class CommunityFeedFragment extends Fragment implements CommunityPostAdapter.Listener {

    private static final String ARG_COMMUNITY_ID    = "communityId";
    private static final String ARG_IS_ANNOUNCEMENT = "isAnnouncement";

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

    private CommunityReactionPickerView reactionPicker;

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
        repo = CommunityRepository.getInstance(requireContext());
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_community_feed, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rvFeed     = view.findViewById(R.id.rv_feed);
        emptyState = view.findViewById(R.id.layout_empty_feed);

        adapter = new CommunityPostAdapter(currentUid, this);
        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvFeed.setLayoutManager(llm);
        rvFeed.setAdapter(adapter);
        CommunityScrollOptimizer.apply(rvFeed, llm);
        // Glide preloader — prefetches post author avatars 6 items ahead
        CommunityAvatarPreloader.attachAvatar(this, rvFeed,
                new CommunityAvatarPreloader.UrlProvider() {
                    @Override public String urlAt(int pos) {
                        java.util.List<com.callx.app.db.entity.CommunityPostEntity> list =
                                adapter.getCurrentList();
                        return (pos >= 0 && pos < list.size()) ? list.get(pos).authorPhotoUrl : null;
                    }
                    @Override public int count() { return adapter.getItemCount(); }
                }, 40);
        rvFeed.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0) loadOlderIfNeeded();
            }
        });

        reactionPicker = view.findViewById(R.id.reaction_picker);
        if (reactionPicker == null) {
            reactionPicker = new CommunityReactionPickerView(requireContext());
        }

        if (communityId != null) {
            repo.observeFeedWindowed(communityId, isAnnouncement, WINDOW_SIZE)
                    .observe(getViewLifecycleOwner(), this::onWindowUpdated);
            repo.observeMembers(communityId).observe(getViewLifecycleOwner(), this::onMembersUpdated);
        }
    }

    private void onWindowUpdated(List<CommunityPostEntity> window) {
        latestWindow = window != null ? window : Collections.emptyList();
        mergeAndSubmit();
    }

    private void onMembersUpdated(List<CommunityMemberEntity> members) {
        if (!isAdded() || members == null || currentUid == null) return;
        for (CommunityMemberEntity m : members) {
            if (currentUid.equals(m.uid)) {
                myRole = m.role != null ? m.role : CommunityRole.MEMBER;
                myName = m.name != null ? m.name : "";
                break;
            }
        }
    }

    private void mergeAndSubmit() {
        if (!isAdded()) return;
        Set<String> inWindow = new HashSet<>();
        for (CommunityPostEntity p : latestWindow) inWindow.add(p.id);
        List<CommunityPostEntity> dedupedOlder = new ArrayList<>();
        for (CommunityPostEntity p : olderExtra) if (!inWindow.contains(p.id)) dedupedOlder.add(p);

        List<CommunityPostEntity> merged = new ArrayList<>(latestWindow);
        merged.addAll(dedupedOlder);
        adapter.submitList(merged);
        emptyState.setVisibility(merged.isEmpty() ? View.VISIBLE : View.GONE);
        rvFeed.setVisibility(merged.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void loadOlderIfNeeded() {
        if (isLoadingMore || !hasMoreOlder) return;
        LinearLayoutManager lm = (LinearLayoutManager) rvFeed.getLayoutManager();
        if (lm == null) return;
        int total = adapter.getItemCount();
        int lastVisible = lm.findLastVisibleItemPosition();
        if (total - lastVisible > LOAD_MORE_THRESHOLD) return;

        long oldestTs = Long.MAX_VALUE;
        if (!olderExtra.isEmpty()) oldestTs = olderExtra.get(olderExtra.size()-1).createdAt;
        else if (!latestWindow.isEmpty()) oldestTs = latestWindow.get(latestWindow.size()-1).createdAt;
        if (oldestTs == Long.MAX_VALUE) return;

        isLoadingMore = true;
        repo.fetchOlderPosts(communityId, isAnnouncement, oldestTs, LOAD_MORE_PAGE_SIZE,
                (posts, error) -> {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        isLoadingMore = false;
                        if (posts != null && !posts.isEmpty()) {
                            olderExtra.addAll(posts);
                            if (posts.size() < LOAD_MORE_PAGE_SIZE) hasMoreOlder = false;
                            mergeAndSubmit();
                        } else {
                            hasMoreOlder = false;
                        }
                    });
                });
    }

    // ─── CommunityPostAdapter.Listener ───────────────────────────────────────

    @Override
    public void onLike(CommunityPostEntity post) {
        if (currentUid == null || communityId == null) return;
        repo.likePost(communityId, post.id, currentUid, myName, null);
    }

    @Override
    public void onComment(CommunityPostEntity post) {
        if (!isAdded()) return;
        Intent i = new Intent(requireContext(), CommunityPostCommentsActivity.class);
        i.putExtra(CommunityPostCommentsActivity.EXTRA_COMMUNITY_ID, communityId);
        i.putExtra(CommunityPostCommentsActivity.EXTRA_POST_ID, post.id);
        i.putExtra(CommunityPostCommentsActivity.EXTRA_POST_AUTHOR, post.authorName);
        startActivity(i);
    }

    @Override
    public void onLongPressLike(CommunityPostEntity post, android.view.View anchorView) {
        if (!isAdded() || reactionPicker == null) return;
        reactionPicker.show(anchorView, reactionType -> {
            if (currentUid != null) repo.reactToPost(communityId, post.id, currentUid, reactionType, null);
        });
    }

    @Override
    public void onReaction(CommunityPostEntity post, String reactionType) {
        if (currentUid != null && communityId != null)
            repo.reactToPost(communityId, post.id, currentUid, reactionType, null);
    }

    // ─── v34: Share ──────────────────────────────────────────────────────────

    @Override
    public void onShare(CommunityPostEntity post) {
        if (!isAdded()) return;

        // Build share text
        StringBuilder sb = new StringBuilder();
        if (post.authorName != null && !post.authorName.isEmpty())
            sb.append(post.authorName).append(":\n");
        if (post.text != null && !post.text.isEmpty())
            sb.append(post.text).append("\n");
        if (post.mediaUrl != null && !post.mediaUrl.isEmpty())
            sb.append(post.mediaUrl).append("\n");
        sb.append("\nShared via CallX2");

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(shareIntent, "Share Post"));

        // Increment shareCount on Firebase
        if (communityId != null && currentUid != null)
            repo.incrementPostShareCount(communityId, post.id);
    }

    // ─── v34: Bookmark ────────────────────────────────────────────────────────

    public void onBookmark(CommunityPostEntity post) {
        if (!isAdded() || communityId == null) return;
        boolean wasBookmarked = CommunityBookmarksActivity.isBookmarked(
                requireContext(), communityId, post.id);
        if (wasBookmarked) {
            CommunityBookmarksActivity.removeBookmark(requireContext(), communityId, post.id);
            Toast.makeText(requireContext(), "Bookmark removed", Toast.LENGTH_SHORT).show();
        } else {
            CommunityBookmarksActivity.bookmarkPost(requireContext(), communityId, post.id);
            Toast.makeText(requireContext(), "Post saved to bookmarks ✓", Toast.LENGTH_SHORT).show();
        }
        // Increment bookmarkCount on Firebase
        if (!wasBookmarked)
            repo.incrementPostBookmarkCount(communityId, post.id);
    }

    // ─── Other Listener callbacks ─────────────────────────────────────────────

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

    /** Hook for subclasses to supply a different LiveData source. */
    protected androidx.lifecycle.LiveData<java.util.List<com.callx.app.db.entity.CommunityPostEntity>> observeFeedSource() {
        return repo.observeFeedWindowed(communityId, isAnnouncement, WINDOW_SIZE);
    }

    /** Returns true when this fragment is the announcements tab. */
    protected boolean isAnnouncementsTab() { return false; }

}
