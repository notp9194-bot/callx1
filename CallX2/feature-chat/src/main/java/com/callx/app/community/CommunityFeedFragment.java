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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.util.List;

/**
 * v31: Community feed fragment.
 * New: reaction picker on long-press of like, delete/report in post options.
 */
public class CommunityFeedFragment extends Fragment implements CommunityPostAdapter.Listener {

    private static final String ARG_COMMUNITY_ID    = "communityId";
    private static final String ARG_IS_ANNOUNCEMENT = "isAnnouncement";

    private String communityId;
    private boolean isAnnouncement;
    private String currentUid;
    private String myRole = CommunityRole.MEMBER;
    private String myName = "";

    private RecyclerView rvFeed;
    private View emptyState;
    private CommunityPostAdapter adapter;
    private CommunityRepository repo;

    // Reaction picker (shared, only one open at a time)
    private CommunityReactionPickerView reactionPicker;

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
        rvFeed.setLayoutManager(llm);
        rvFeed.setHasFixedSize(false);
        rvFeed.setItemViewCacheSize(10);
        rvFeed.setItemAnimator(null);
        rvFeed.setOverScrollMode(View.OVER_SCROLL_NEVER);

        adapter = new CommunityPostAdapter(currentUid, this);
        rvFeed.setAdapter(adapter);

        reactionPicker = new CommunityReactionPickerView(requireContext());
        reactionPicker.setOnReactionSelectedListener((reactionType) -> {
            // handled via onReaction callback which is set per-post
        });

        if (communityId != null) {
            if (isAnnouncement) {
                repo.observeAnnouncements(communityId).observe(getViewLifecycleOwner(), this::onFeedUpdated);
            } else {
                repo.observeFeed(communityId).observe(getViewLifecycleOwner(), this::onFeedUpdated);
            }
            repo.observeMembers(communityId).observe(getViewLifecycleOwner(), this::onMembersUpdated);
            repo.syncRecentPosts(communityId, isAnnouncement);
        }
    }

    private void onFeedUpdated(List<CommunityPostEntity> posts) {
        if (!isAdded()) return;
        adapter.submitList(posts);
        boolean empty = posts == null || posts.isEmpty();
        rvFeed.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
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
                CommunityPostDetailActivity.class);
        i.putExtra(CommunityPostDetailActivity.EXTRA_COMMUNITY_ID, communityId);
        i.putExtra(CommunityPostDetailActivity.EXTRA_POST_ID, post.id);
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
