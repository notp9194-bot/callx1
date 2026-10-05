package com.callx.app.community;
import com.callx.app.utils.FirebaseUtils;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
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
import java.util.List;

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
    private android.widget.ImageView ivEmptyIcon;
    private TextView tvEmptyTitle, tvEmptySubtitle;
    private com.google.android.material.button.MaterialButton btnEmptyCreate;

    /** Host activity (CommunityActivity) — post karne ka haq aur composer kholne ka kaam uska hai. */
    public interface ComposeHost {
        boolean canCompose();
        void openComposer(boolean announcement);
    }
    private CommunityPostAdapter adapter;
    protected CommunityRepository repo;

    private CommunityReactionPickerView reactionPicker;

    private List<CommunityPostEntity> latestWindow = Collections.emptyList();
    // FIX: pehle load-more wale purane posts static copy (olderExtra) the — unpe like/edit/delete
    // live update nahi hota tha. Ab window ki limit badhti hai aur LiveData dobara observe hota
    // hai, to saare posts Room se live aate hain.
    protected int windowLimit = WINDOW_SIZE;
    private LiveData<List<CommunityPostEntity>> feedLive;
    private boolean isLoadingMore = false;
    private boolean hasMoreOlder = true;

    // ─── Loading skeleton + initial sync ────────────────────────────────────────
    // FIX: Room khali ho (fresh install / pehli baar) to "No posts yet" flash hota tha jabki
    // Firebase se data aa raha hota tha. Ab skeleton tab tak dikhta hai jab tak cached data na aaye
    // ya pehli sync complete na ho (6s timeout). Feed/announcements tab ka initial sync bhi yahin
    // se hota hai — pehle sirf Groups tab non-announcement sync karta tha.
    private static final long INITIAL_SYNC_TTL_MS = 60_000L;
    private static final long SKELETON_TIMEOUT_MS = 6_000L;
    private static final java.util.Map<String, Long> LAST_INITIAL_SYNC = new java.util.HashMap<>();
    private View skeletonView;
    private boolean initialLoading = false;
    private final android.os.Handler skeletonHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable skeletonTimeout = () -> finishInitialLoading();

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
                ? FirebaseUtils.getCurrentUid() : null;
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
        rvFeed     = view.findViewById(R.id.rv_community_feed);
        emptyState = view.findViewById(R.id.empty_feed);
        skeletonView = view.findViewById(R.id.skeleton_feed);
        ivEmptyIcon     = view.findViewById(R.id.iv_empty_icon);
        tvEmptyTitle    = view.findViewById(R.id.tv_empty_title);
        tvEmptySubtitle = view.findViewById(R.id.tv_empty_subtitle);
        btnEmptyCreate  = view.findViewById(R.id.btn_empty_create);
        if (btnEmptyCreate != null) {
            btnEmptyCreate.setOnClickListener(v -> {
                if (getActivity() instanceof ComposeHost) {
                    ((ComposeHost) getActivity()).openComposer(isAnnouncement);
                }
            });
        }
        setupPullToRefresh(view);

        adapter = new CommunityPostAdapter(currentUid, this);
        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvFeed.setLayoutManager(llm);
        com.callx.app.utils.RecyclerViewFrictionTuner.applyReducedFriction(rvFeed);
        rvFeed.setAdapter(adapter);
        CommunityScrollOptimizer.apply(rvFeed, llm);
        // Glide preloader — prefetches post author avatars 6 items ahead
        CommunityAvatarPreloader.attachAvatar(this, rvFeed,
                new CommunityAvatarPreloader.UrlProvider() {
                    @Override public String urlAt(int pos) {
                        java.util.List<com.callx.app.db.entity.CommunityPostEntity> list =
                                adapter.getCurrentList();
                        return (pos >= 0 && pos < list.size()) ? list.get(pos).authorPhoto : null;
                    }
                    @Override public int count() { return adapter.getItemCount(); }
                }, 40);
        rvFeed.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0) loadOlderIfNeeded();
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleViewCount();
            }
        });

        reactionPicker = new CommunityReactionPickerView(requireContext());

        if (communityId != null) {
            startInitialLoad();
            observeWindow();
            repo.observeMembers(communityId).observe(getViewLifecycleOwner(), this::onMembersUpdated);
        }
    }

    private void observeWindow() {
        if (feedLive != null) feedLive.removeObservers(getViewLifecycleOwner());
        feedLive = repo.observeFeedWindowed(communityId, isAnnouncement, windowLimit);
        feedLive.observe(getViewLifecycleOwner(), this::onWindowUpdated);
    }

    private void onWindowUpdated(List<CommunityPostEntity> window) {
        latestWindow = window != null ? window : Collections.emptyList();
        // Window poori bhari hai => shayad aur purane posts hain; kam aayi => Room me bas itne hi.
        hasMoreOlder = latestWindow.size() >= windowLimit;
        isLoadingMore = false;
        // Cached data aa gaya => skeleton ki zarurat nahi (sync background me chalta rahega).
        if (!latestWindow.isEmpty()) initialLoading = false;
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
        updateEmptyState();
    }

    private void mergeAndSubmit() {
        if (!isAdded()) return;
        adapter.submitList(new ArrayList<>(latestWindow));
        final boolean empty = latestWindow.isEmpty();
        final boolean showSkeleton = empty && initialLoading;
        if (skeletonView != null) skeletonView.setVisibility(showSkeleton ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(empty && !showSkeleton ? View.VISIBLE : View.GONE);
        if (empty && !showSkeleton) updateEmptyState();
        rvFeed.setVisibility(empty ? View.GONE : View.VISIBLE);
        scheduleViewCount();
    }

    // ─── Empty state ─────────────────────────────────────────────────────────
    // Feed aur Announcements ke liye alag icon/text. "Create" button sirf unko jo post kar sakte
    // hain: feed => FAB dikh raha ho; announcements => admin/owner bhi ho.
    private boolean canPostHere() {
        if (!(getActivity() instanceof ComposeHost)) return false;
        if (!((ComposeHost) getActivity()).canCompose()) return false;
        return !isAnnouncement || CommunityRole.isAdminOrOwner(myRole);
    }

    private void updateEmptyState() {
        if (!isAdded() || emptyState == null || tvEmptyTitle == null) return;
        final boolean canPost = canPostHere();
        if (isAnnouncement) {
            ivEmptyIcon.setImageResource(R.drawable.ic_empty_announcement);
            tvEmptyTitle.setText("No announcements yet");
            tvEmptySubtitle.setText(canPost
                    ? "Share important updates with everyone in this community"
                    : "Updates from community admins will show up here");
            btnEmptyCreate.setText("Create announcement");
        } else {
            ivEmptyIcon.setImageResource(R.drawable.ic_empty_feed);
            tvEmptyTitle.setText("No posts yet");
            tvEmptySubtitle.setText(canPost
                    ? "Start the conversation \u2014 share an update, photo or poll"
                    : "Posts from this community will show up here");
            btnEmptyCreate.setText("Create post");
        }
        btnEmptyCreate.setVisibility(canPost ? View.VISIBLE : View.GONE);
    }

    /** Pehli baar (ya TTL ke baad) Firebase se recent posts khinch ke Room me daalo. */
    private void startInitialLoad() {
        final String key = communityId + ":" + isAnnouncement;
        final long now = System.currentTimeMillis();
        Long last;
        synchronized (LAST_INITIAL_SYNC) { last = LAST_INITIAL_SYNC.get(key); }
        if (last != null && now - last < INITIAL_SYNC_TTL_MS) {
            initialLoading = false; // haal hi me sync hua — Room khali hai to empty state sahi hai
            return;
        }
        initialLoading = true;
        skeletonHandler.removeCallbacks(skeletonTimeout);
        skeletonHandler.postDelayed(skeletonTimeout, SKELETON_TIMEOUT_MS);
        repo.syncRecentPosts(communityId, isAnnouncement, (success, error) -> {
            if (success) {
                synchronized (LAST_INITIAL_SYNC) { LAST_INITIAL_SYNC.put(key, System.currentTimeMillis()); }
            }
            if (isAdded()) finishInitialLoading();
        });
    }

    private void finishInitialLoading() {
        skeletonHandler.removeCallbacks(skeletonTimeout);
        if (!initialLoading) return;
        initialLoading = false;
        if (isAdded() && adapter != null) mergeAndSubmit();
    }

    private void loadOlderIfNeeded() {
        if (isLoadingMore || !hasMoreOlder || communityId == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) rvFeed.getLayoutManager();
        if (lm == null) return;
        int total = adapter.getItemCount();
        int lastVisible = lm.findLastVisibleItemPosition();
        if (total - lastVisible > LOAD_MORE_THRESHOLD) return;

        // Window badhao + re-observe; naya emission isLoadingMore reset karta hai.
        isLoadingMore = true;
        windowLimit += LOAD_MORE_PAGE_SIZE;
        observeWindow();
    }

    // ─── Pull-to-refresh ─────────────────────────────────────────────────────
    // FIX: layout me SwipeRefreshLayout tha par listener kabhi lagaya nahi gaya — kheechne par
    // spinner aata aur kuch sync nahi hota tha. Ab Firebase se recent posts Room me aate hain
    // (LiveData khud list update karta hai), aur spinner completion par ya 8s timeout par band.
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTimeout = () -> stopRefreshing();

    private void setupPullToRefresh(View root) {
        swipeRefresh = root.findViewById(R.id.swipe_refresh);
        if (swipeRefresh == null) return;
        // Direct child FrameLayout hai, RecyclerView nahi — warna list beech me scrolled hone par
        // bhi pull-down refresh trigger ho sakta hai.
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> rvFeed.canScrollVertically(-1));
        swipeRefresh.setOnRefreshListener(() -> {
            if (communityId == null) { stopRefreshing(); return; }
            refreshHandler.removeCallbacks(refreshTimeout);
            refreshHandler.postDelayed(refreshTimeout, 8000L);
            repo.syncRecentPosts(communityId, isAnnouncement, (success, error) -> {
                if (!isAdded()) return;
                stopRefreshing();
                if (!success) {
                    Toast.makeText(requireContext(), "Refresh nahi ho paya", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void stopRefreshing() {
        refreshHandler.removeCallbacks(refreshTimeout);
        if (swipeRefresh != null && swipeRefresh.isRefreshing()) swipeRefresh.setRefreshing(false);
    }

    // ─── Unique view count ───────────────────────────────────────────────────
    // FIX: incrementViewCount() bana tha par kahin call nahi hota tha. Ab jo post screen par
    // settle hone ke baad (scroll idle + ~0.7s) kam se kam aadha dikhe, wo har member ke liye
    // ek hi baar count hota hai (device par SharedPreferences me yaad rakhte hain). Apni post
    // count nahi hoti.
    private static final String VIEWED_PREFS = "community_viewed_posts";
    private static final String VIEWED_KEY   = "keys";
    private static final int    VIEWED_CAP   = 3000;
    private final android.os.Handler viewHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable viewCountRunnable = this::countVisiblePostViews;
    private final java.util.Set<String> viewCountedThisSession = new java.util.HashSet<>();

    private void scheduleViewCount() {
        viewHandler.removeCallbacks(viewCountRunnable);
        viewHandler.postDelayed(viewCountRunnable, 700L);
    }

    private void countVisiblePostViews() {
        if (!isAdded() || !isResumed() || rvFeed == null || adapter == null
                || communityId == null || currentUid == null) return;
        if (rvFeed.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
        LinearLayoutManager lm = (LinearLayoutManager) rvFeed.getLayoutManager();
        if (lm == null) return;
        int first = lm.findFirstVisibleItemPosition();
        int last  = lm.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return;

        List<CommunityPostEntity> list = adapter.getCurrentList();
        android.content.SharedPreferences prefs =
                requireContext().getSharedPreferences(VIEWED_PREFS, android.content.Context.MODE_PRIVATE);
        java.util.Set<String> stored = null;
        boolean dirty = false;
        for (int pos = first; pos <= last && pos < list.size(); pos++) {
            View v = lm.findViewByPosition(pos);
            if (v == null || v.getHeight() <= 0) continue;
            int visible = Math.min(v.getBottom(), rvFeed.getHeight()) - Math.max(v.getTop(), 0);
            // Lambe post (screen se bade) ke liye bhi chalna chahiye: aadha card YA aadhi screen.
            float need = Math.min(v.getHeight() * 0.5f, rvFeed.getHeight() * 0.5f);
            if (visible < need) continue;

            CommunityPostEntity post = list.get(pos);
            if (post == null || post.id == null || currentUid.equals(post.authorUid)) continue;
            String key = currentUid + "::" + communityId + "::" + post.id;
            if (!viewCountedThisSession.add(key)) continue;
            if (stored == null) {
                // getStringSet ka returned set modify karna mana hai — copy karo.
                stored = new java.util.HashSet<>(prefs.getStringSet(VIEWED_KEY, java.util.Collections.<String>emptySet()));
            }
            if (stored.contains(key)) continue;
            if (stored.size() >= VIEWED_CAP) stored.clear(); // bounded; rare edge: purane post dobara ek baar ginenge
            stored.add(key);
            dirty = true;
            repo.incrementPostViewCount(communityId, post.id);
        }
        if (dirty) prefs.edit().putStringSet(VIEWED_KEY, stored).apply();
    }

    // ─── Link / Hashtag ──────────────────────────────────────────────────────

    @Override
    public void onLinkClick(String url) {
        if (!isAdded() || url == null || url.trim().isEmpty()) return;
        String u = url.trim();
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) u = "https://" + u;
        android.net.Uri uri = android.net.Uri.parse(u);
        String scheme = uri.getScheme();
        // Sirf web links — koi intent:/file:/javascript: scheme post text se nahi khulega.
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return;
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Link khul nahi paya", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onHashtagClick(String hashtag) {
        if (!isAdded() || communityId == null || hashtag == null) return;
        android.content.Intent i = new android.content.Intent(requireContext(), CommunitySearchActivity.class);
        i.putExtra(CommunitySearchActivity.EXTRA_COMMUNITY_ID, communityId);
        i.putExtra(CommunitySearchActivity.EXTRA_QUERY, hashtag);
        startActivity(i);
    }

    // ─── Edit / Pin ──────────────────────────────────────────────────────────

    @Override
    public void onEdit(CommunityPostEntity post) {
        if (!isAdded() || communityId == null) return;
        final android.widget.EditText et = new android.widget.EditText(requireContext());
        et.setText(post.text != null ? post.text : "");
        et.setSelection(et.getText().length());
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(2);
        final boolean hasOtherContent = (post.mediaUrl != null && !post.mediaUrl.isEmpty())
                || (post.pollJson != null && !post.pollJson.isEmpty());
        new AlertDialog.Builder(requireContext())
                .setTitle("Edit post")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = et.getText().toString().trim();
                    if (newText.isEmpty() && !hasOtherContent) {
                        Toast.makeText(requireContext(), "Post khali nahi ho sakta", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (newText.equals(post.text != null ? post.text.trim() : "")) return; // koi badlav nahi
                    repo.editPostText(communityId, post.id, newText, (success, error) -> {
                        if (isAdded()) requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(),
                                        success ? "Post updated" : "Failed: " + error,
                                        Toast.LENGTH_SHORT).show());
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onPin(CommunityPostEntity post, boolean pin) {
        if (!isAdded() || communityId == null || currentUid == null) return;
        repo.setPostPinned(communityId, post.id, pin, currentUid, myName, (success, error) -> {
            if (isAdded()) requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireContext(),
                            success ? (pin ? "Post pinned" : "Post unpinned") : "Failed: " + error,
                            Toast.LENGTH_SHORT).show());
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Bookmarks screen me unsave kiya ho to feed ka icon sync ho jaye.
        if (adapter != null) adapter.refreshBookmarks();
        scheduleViewCount();
        updateEmptyState();
    }

    @Override
    public void onDestroyView() {
        viewHandler.removeCallbacks(viewCountRunnable);
        refreshHandler.removeCallbacks(refreshTimeout);
        skeletonHandler.removeCallbacks(skeletonTimeout);
        skeletonView = null;
        ivEmptyIcon = null; tvEmptyTitle = null; tvEmptySubtitle = null; btnEmptyCreate = null;
        swipeRefresh = null;
        super.onDestroyView();
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
        i.putExtra(CommunityPostCommentsActivity.EXTRA_POST_AUTHOR, post.authorUid);
        startActivity(i);
    }

    @Override
    public void onLongPressLike(CommunityPostEntity post, android.view.View anchorView) {
        if (!isAdded() || reactionPicker == null) return;
        reactionPicker.setOnReactionSelectedListener(reactionType -> {
            if (currentUid != null) repo.reactToPost(communityId, post.id, currentUid, reactionType, null);
        });
        reactionPicker.showAtView(anchorView);
    }

    @Override
    public void onReaction(CommunityPostEntity post, String reactionType) {
        if (currentUid != null && communityId != null)
            repo.reactToPost(communityId, post.id, currentUid, reactionType, null);
    }

    @Override
    public void onReactionsDetail(CommunityPostEntity post) {
        if (!isAdded() || communityId == null) return;
        // No reactions yet — nothing to show
        if (CommunityReaction.totalCount(post.reactionCountsJson) <= 0) return;
        Intent i = new Intent(requireContext(), CommunityReactionsDetailActivity.class);
        i.putExtra(CommunityReactionsDetailActivity.EXTRA_COMMUNITY_ID, communityId);
        i.putExtra(CommunityReactionsDetailActivity.EXTRA_POST_ID, post.id);
        startActivity(i);
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
        // FIX: icon turant flip ho — sirf is row ka glyph repaint hota hai.
        if (adapter != null) adapter.setBookmarked(post.id, !wasBookmarked);
        // Increment bookmarkCount on Firebase
        if (!wasBookmarked)
            repo.incrementPostBookmarkCount(communityId, post.id);
    }

    // ─── Other Listener callbacks ─────────────────────────────────────────────

    @Override
    public void onDelete(CommunityPostEntity post) {
        if (!isAdded()) return;
        com.callx.app.utils.AlertDialogStyler.showReusableConfirm(requireContext(),
                "community_delete_post", com.callx.app.utils.AlertDialogStyler.DialogSize.DEFAULT,
                "Delete Post?", "This post will be permanently removed.",
                "Delete", () -> {
                    repo.deletePost(communityId, post.id, currentUid, myName, null,
                            (success, error) -> {
                                if (isAdded())
                                    requireActivity().runOnUiThread(() ->
                                            Toast.makeText(requireContext(),
                                                    success ? "Post deleted" : "Failed: " + error,
                                                    Toast.LENGTH_SHORT).show());
                            });
                },
                null, null,
                "Cancel");
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

    @Override
    public void onMediaCellClicked(CommunityPostEntity post, int index) {
        if (!isAdded()) return;
        String url = post.mediaUrl, type = post.mediaType;
        try {
            if (post.mediaUrlsJson != null) {
                org.json.JSONArray urls = new org.json.JSONArray(post.mediaUrlsJson);
                if (index >= 0 && index < urls.length()) url = urls.optString(index, url);
                if (post.mediaTypesJson != null) {
                    org.json.JSONArray types = new org.json.JSONArray(post.mediaTypesJson);
                    if (index >= 0 && index < types.length()) type = types.optString(index, type);
                }
            }
        } catch (Exception ignored) {}
        if (url == null || url.isEmpty()) return;
        android.content.Intent i = new android.content.Intent(requireContext(),
                CommunityFullscreenMediaActivity.class);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_MEDIA_URL, url);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_MEDIA_TYPE, type);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_AUTHOR_NAME, post.authorName);
        startActivity(i);
    }

    /** Hook for subclasses to supply a different LiveData source. */
    protected androidx.lifecycle.LiveData<java.util.List<com.callx.app.db.entity.CommunityPostEntity>> observeFeedSource() {
        return repo.observeFeedWindowed(communityId, isAnnouncement, windowLimit);
    }

    /** Returns true when this fragment is the announcements tab. */
    protected boolean isAnnouncementsTab() { return false; }

}
