package com.callx.app.comments;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.*;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import android.widget.FrameLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ReelLikesBottomSheet  — Advanced v2
 *
 * Improvements over v1:
 *  1. N+1 fix  — name/photo read from the reelLikeMeta/{reelId} denormalized
 *                snapshot (written at like time by FirebaseUtils.writeReelLike()),
 *                fetched ONCE for the whole reel instead of once per liker. Falls
 *                back to a single reels/users/{uid} read, per uid, only for likes
 *                that predate this fix and have no reelLikeMeta entry.
 *  2. Pagination — PAGE_SIZE items at a time, loads more on scroll-to-end.
 *  3. Follow button — shows "Follow" when current user doesn't follow liker;
 *                     turns "Following ✓" on tap, writes reelFollows node.
 *  4. Real-time listener — addValueEventListener so new likes appear live.
 *  5. Username sub-label + verified badge.
 */
public class ReelLikesBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG         = "ReelLikesBottomSheet";
    public static final String ARG_REEL_ID = "reel_id";
    public static final String ARG_LIKES   = "likes_count";
    public static final String ARG_PLAYS   = "plays_count";

    private static final int PAGE_SIZE = 20;

    // ── Factory ────────────────────────────────────────────────────────────
    public static ReelLikesBottomSheet newInstance(String reelId, int likesCount, int playsCount) {
        ReelLikesBottomSheet f = new ReelLikesBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID, reelId);
        args.putInt(ARG_LIKES,  likesCount);
        args.putInt(ARG_PLAYS,  playsCount);
        f.setArguments(args);
        return f;
    }

    // ── Fields ─────────────────────────────────────────────────────────────
    private TextView     tvLikesCount, tvPlaysCount;
    private EditText     etSearch;
    private RecyclerView rv;
    private ProgressBar  progressBar;
    private TextView     tvEmpty;

    private LikersAdapter        adapter;
    private final List<UserItem> allItems      = new ArrayList<>();
    private final List<UserItem> filteredItems = new ArrayList<>();

    // Pagination state
    private String lastLoadedKey = null;
    private boolean isLoading    = false;
    private boolean allLoaded    = false;

    // Follow state: uid → true/false
    private final Map<String, Boolean> followingMap = new HashMap<>();

    // Denormalized liker snapshot: uid → reelLikeMeta/{reelId}/{uid} DataSnapshot.
    // Loaded once for the whole reel; pages are then built from this map with
    // zero extra Firebase reads (see fetchUsersForPage()).
    private final Map<String, DataSnapshot> likeMetaCache = new HashMap<>();

    // Real-time listener handle
    private ValueEventListener realtimeListener;
    private DatabaseReference  realtimeRef;

    private String reelId;
    private int    likesCount, playsCount;
    private String myUid;

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override
    public void onStart() {
        super.onStart();
        BottomSheetDialog d = (BottomSheetDialog) getDialog();
        if (d == null) return;
        FrameLayout bs = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs == null) return;
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bs);
        behavior.setHideable(true);
        behavior.setSkipCollapsed(true);
        behavior.setFitToContents(true);
        behavior.setDraggable(true);
        behavior.setHalfExpandedRatio(0.5f);
        behavior.setExpandedOffset(0);
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reel_likes, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        myUid = FirebaseUtils.getCurrentUid();

        Bundle args = getArguments();
        if (args != null) {
            reelId     = args.getString(ARG_REEL_ID);
            likesCount = args.getInt(ARG_LIKES, 0);
            playsCount = args.getInt(ARG_PLAYS, 0);
        }

        tvLikesCount = v.findViewById(R.id.tv_likes_count_header);
        tvPlaysCount = v.findViewById(R.id.tv_plays_count_header);
        etSearch     = v.findViewById(R.id.et_search);
        rv           = v.findViewById(R.id.rv_likers);
        progressBar  = v.findViewById(R.id.progress_bar);
        tvEmpty      = v.findViewById(R.id.tv_empty);

        tvLikesCount.setText(formatCount(likesCount));
        tvPlaysCount.setText(formatCount(playsCount));

        adapter = new LikersAdapter(filteredItems);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        // Pagination scroll listener
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) r.getLayoutManager();
                if (lm == null) return;
                int last    = lm.findLastVisibleItemPosition();
                int total   = adapter.getItemCount();
                if (!isLoading && !allLoaded && last >= total - 4) {
                    loadNextPage();
                }
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Load my following map and the denormalized liker snapshot in parallel,
        // then start loading likers once both are ready.
        if (reelId != null) {
            final AtomicInteger prep = new AtomicInteger(2);
            Runnable onPrepDone = () -> {
                if (prep.decrementAndGet() == 0) loadFirstPage();
            };
            loadMyFollowing(onPrepDone);
            loadLikeMetaCache(onPrepDone);
        } else {
            showEmpty();
        }
    }

    /** Single read of the whole reelLikeMeta/{reelId} node — see class doc. */
    private void loadLikeMetaCache(Runnable onDone) {
        FirebaseUtils.getReelLikeMetaRef(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        for (DataSnapshot child : snap.getChildren()) {
                            if (child.getKey() != null) likeMetaCache.put(child.getKey(), child);
                        }
                        onDone.run();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        onDone.run();
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Detach real-time listener to avoid memory leaks
        if (realtimeListener != null && realtimeRef != null) {
            realtimeRef.removeEventListener(realtimeListener);
        }
    }

    // ── Following map ──────────────────────────────────────────────────────
    /** Load who myUid already follows, then signal completion. */
    private void loadMyFollowing(Runnable onDone) {
        if (myUid.isEmpty()) {
            onDone.run();
            return;
        }
        FirebaseUtils.getReelFollowsRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        for (DataSnapshot child : snap.getChildren()) {
                            Boolean val = child.getValue(Boolean.class);
                            if (Boolean.TRUE.equals(val) && child.getKey() != null) {
                                followingMap.put(child.getKey(), true);
                            }
                        }
                        attachRealtimeListener();
                        onDone.run();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        onDone.run();
                    }
                });
    }

    // ── Pagination ─────────────────────────────────────────────────────────
    private void loadFirstPage() {
        isLoading = true;
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        Query q = FirebaseUtils.getReelLikesRef(reelId)
                .orderByKey()
                .limitToFirst(PAGE_SIZE);
        fetchPage(q);
    }

    private void loadNextPage() {
        if (lastLoadedKey == null) return;
        isLoading = true;

        // Show a footer loading indicator
        Query q = FirebaseUtils.getReelLikesRef(reelId)
                .orderByKey()
                .startAfter(lastLoadedKey)
                .limitToFirst(PAGE_SIZE);
        fetchPage(q);
    }

    private void fetchPage(Query q) {
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<String> uids = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    // COMPAT FIX: like values used to be a bare `true`; newer
                    // writes store a like-timestamp (long) instead, so the
                    // liker-avatar row can order by recency. A like entry is
                    // valid either way — treat "node exists with any value"
                    // as liked rather than requiring the legacy Boolean type,
                    // or newly-liked reels would silently vanish from this list.
                    if (child.exists() && child.getValue() != null && child.getKey() != null) {
                        uids.add(child.getKey());
                        lastLoadedKey = child.getKey();
                    }
                }
                if (uids.size() < PAGE_SIZE) allLoaded = true;
                if (uids.isEmpty()) {
                    isLoading = false;
                    if (!isAdded()) return;
                    progressBar.setVisibility(View.GONE);
                    if (allItems.isEmpty()) showEmpty();
                    return;
                }
                fetchUsersForPage(uids);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                isLoading = false;
                if (isAdded()) progressBar.setVisibility(View.GONE);
            }
        });
    }

    /**
     * N+1 FIX: builds the page from likeMetaCache (one read for the whole reel,
     * done once in loadLikeMetaCache()) — no Firebase call per liker anymore.
     * Only uids missing from the cache (likes written before this fix existed)
     * fall back to a single reels/users/{uid} read each, same as before.
     */
    private void fetchUsersForPage(List<String> uids) {
        final List<UserItem> pageItems = Collections.synchronizedList(new ArrayList<>());
        final List<String> misses = new ArrayList<>();

        for (String uid : uids) {
            DataSnapshot meta = likeMetaCache.get(uid);
            if (meta != null) {
                pageItems.add(buildUserItem(uid,
                        meta.child("name").getValue(String.class),
                        meta.child("username").getValue(String.class),
                        meta.child("photo").getValue(String.class),
                        meta.child("verified").getValue(Boolean.class)));
            } else {
                misses.add(uid);
            }
        }

        if (misses.isEmpty()) {
            if (isAdded()) appendPage(pageItems);
            return;
        }

        // Legacy fallback: pre-fix likes with no reelLikeMeta entry.
        final AtomicInteger done = new AtomicInteger(0);
        for (String uid : misses) {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("reels/users").child(uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            pageItems.add(buildUserItem(uid,
                                    s.child("displayName").getValue(String.class),
                                    s.child("handle").getValue(String.class),
                                    resolvePhoto(s.child("thumbUrl").getValue(String.class),
                                                 s.child("photoUrl").getValue(String.class)),
                                    s.child("verified").getValue(Boolean.class)));
                            if (done.incrementAndGet() >= misses.size() && isAdded()) {
                                appendPage(pageItems);
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            if (done.incrementAndGet() >= misses.size() && isAdded()) {
                                appendPage(pageItems);
                            }
                        }
                    });
        }
    }

    private String resolvePhoto(String thumb, String photo) {
        return (thumb != null && !thumb.isEmpty()) ? thumb : photo;
    }

    private UserItem buildUserItem(String uid, String name, String username,
                                    String photo, Boolean verified) {
        boolean isFollowing = Boolean.TRUE.equals(followingMap.get(uid));
        return new UserItem(
                uid,
                name     != null ? name     : "User",
                username != null ? username : "",
                photo    != null ? photo    : "",
                Boolean.TRUE.equals(verified),
                isFollowing
        );
    }

    private void appendPage(List<UserItem> pageItems) {
        if (!isAdded() || getContext() == null) return;
        isLoading = false;
        progressBar.setVisibility(View.GONE);

        // Sort page by name before appending
        pageItems.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        allItems.addAll(pageItems);

        // Re-apply search filter
        String query = etSearch.getText().toString();
        filterList(query);
        tvEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Real-time listener (new likes appear live) ──────────────────────────
    // FIX: this used to attach to getReelLikesRef(reelId) — the full
    // reelLikes/{reelId} node, one child per liker uid — just to read
    // snap.getChildrenCount() for the header number. That means EVERY like
    // or unlike on the reel re-downloads the entire likers list to every
    // open sheet, even though only a count is displayed. On a viral reel
    // (lakhs of likes) this is a large, constantly-refetched payload for a
    // single integer. reels/{reelId}/likesCount is a dedicated counter node
    // already kept correct by atomic transactions in
    // ReelSocialController.toggleLike() (see countRef.runTransaction there),
    // so listen to that single value instead — O(1) per update, not O(n).
    private void attachRealtimeListener() {
        realtimeRef = FirebaseUtils.getReelLikesCountRef(reelId);
        realtimeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Integer count = snap.getValue(Integer.class);
                if (isAdded()) tvLikesCount.setText(formatCount(count != null ? count : 0));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        realtimeRef.addValueEventListener(realtimeListener);
    }

    // ── Follow action ──────────────────────────────────────────────────────
    private void toggleFollow(UserItem user, Button btnFollow) {
        if (myUid.isEmpty()) return;
        boolean nowFollowing = !Boolean.TRUE.equals(followingMap.get(user.uid));
        followingMap.put(user.uid, nowFollowing);
        user.isFollowing = nowFollowing;

        DatabaseReference followRef = FirebaseUtils.getReelFollowsRef(myUid).child(user.uid);
        if (nowFollowing) {
            followRef.setValue(true);
            btnFollow.setText("Following ✓");
        } else {
            followRef.removeValue();
            btnFollow.setText("Follow");
        }
    }

    // ── Filter ─────────────────────────────────────────────────────────────
    private void filterList(String query) {
        filteredItems.clear();
        if (query.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String q = query.toLowerCase(Locale.getDefault());
            for (UserItem u : allItems) {
                if (u.name.toLowerCase(Locale.getDefault()).contains(q)
                        || u.username.toLowerCase(Locale.getDefault()).contains(q)) {
                    filteredItems.add(u);
                }
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filteredItems.isEmpty() && !isLoading ? View.VISIBLE : View.GONE);
    }

    private void showEmpty() {
        progressBar.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
        rv.setVisibility(View.GONE);
    }

    private void launchByClass(String className, String[] keys, String[] values) {
        try {
            Class<?> cls = Class.forName(className);
            Intent i = new Intent(requireContext(), cls);
            for (int x = 0; x < keys.length; x++) i.putExtra(keys[x], values[x]);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(requireContext(), "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    // ── Adapter ────────────────────────────────────────────────────────────
    class LikersAdapter extends RecyclerView.Adapter<LikersAdapter.VH> {
        final List<UserItem> data;
        LikersAdapter(List<UserItem> d) { data = d; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_reel_liker, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            UserItem u = data.get(pos);
            h.tvName.setText(u.name);

            // Username sub-label
            if (!u.username.isEmpty()) {
                h.tvUsername.setVisibility(View.VISIBLE);
                h.tvUsername.setText("@" + u.username);
            } else {
                h.tvUsername.setVisibility(View.GONE);
            }

            // Verified badge
            h.ivVerified.setVisibility(u.isVerified ? View.VISIBLE : View.GONE);

            // Avatar
            if (!u.photo.isEmpty()) {
                Glide.with(requireContext()).load(u.photo)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person)
                        .override(96, 96)
                        .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            // Follow button — hide for own profile
            if (u.uid.equals(myUid)) {
                h.btnFollow.setVisibility(View.GONE);
            } else {
                h.btnFollow.setVisibility(View.VISIBLE);
                h.btnFollow.setText(u.isFollowing ? "Following ✓" : "Follow");
                h.btnFollow.setOnClickListener(v -> toggleFollow(u, h.btnFollow));
            }

            // Message button
            h.btnMessage.setOnClickListener(v ->
                launchByClass("com.callx.app.conversation.ChatActivity",
                        new String[]{"partnerUid", "partnerName", "partnerPhoto"},
                        new String[]{u.uid, u.name, u.photo}));

            // Row tap → profile
            h.itemView.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(requireContext(), UserReelsActivity.class);
                    i.putExtra(UserReelsActivity.EXTRA_UID,   u.uid);
                    i.putExtra(UserReelsActivity.EXTRA_NAME,  u.name);
                    i.putExtra(UserReelsActivity.EXTRA_PHOTO, u.photo);
                    startActivity(i);
                    dismiss();
                } catch (Exception ignored) {}
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            ImageView       ivVerified;
            TextView        tvName, tvUsername;
            Button          btnFollow, btnMessage;
            VH(@NonNull View v) {
                super(v);
                ivAvatar   = v.findViewById(R.id.iv_avatar);
                ivVerified = v.findViewById(R.id.iv_verified);
                tvName     = v.findViewById(R.id.tv_name);
                tvUsername = v.findViewById(R.id.tv_username);
                btnFollow  = v.findViewById(R.id.btn_follow);
                btnMessage = v.findViewById(R.id.btn_message);
            }
        }
    }

    // ── Model ──────────────────────────────────────────────────────────────
    static class UserItem {
        String uid, name, username, photo;
        boolean isVerified, isFollowing;
        UserItem(String uid, String name, String username,
                 String photo, boolean isVerified, boolean isFollowing) {
            this.uid        = uid;
            this.name       = name;
            this.username   = username;
            this.photo      = photo;
            this.isVerified = isVerified;
            this.isFollowing = isFollowing;
        }
    }
}
