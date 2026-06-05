package com.callx.app.social;

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
 * ReelSharesBottomSheet — Advanced v2
 *
 * Improvements over v1:
 *  1. N+1 fix      — batch user fetches with AtomicInteger.
 *  2. Pagination   — PAGE_SIZE sharers at a time.
 *  3. Follow button — Follow/Following ✓ per row.
 *  4. Real-time count update in header.
 *  5. Username + verified badge.
 *  6. Timestamp sub-label ("3 hours ago") when available.
 */
public class ReelSharesBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG          = "ReelSharesBottomSheet";
    public static final String ARG_REEL_ID  = "reel_id";
    public static final String ARG_SHARES   = "shares_count";
    public static final String ARG_REPOSTS  = "reposts_count";

    private static final int PAGE_SIZE = 20;

    // ── Factory ────────────────────────────────────────────────────────────
    public static ReelSharesBottomSheet newInstance(String reelId, int sharesCount, int repostsCount) {
        ReelSharesBottomSheet f = new ReelSharesBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID,  reelId);
        args.putInt(ARG_SHARES,      sharesCount);
        args.putInt(ARG_REPOSTS,     repostsCount);
        f.setArguments(args);
        return f;
    }

    // ── Fields ─────────────────────────────────────────────────────────────
    private TextView     tvSharesCount, tvRepostsCount;
    private EditText     etSearch;
    private RecyclerView rv;
    private ProgressBar  progressBar;
    private TextView     tvEmpty;

    private SharersAdapter       adapter;
    private final List<ShareItem> allItems      = new ArrayList<>();
    private final List<ShareItem> filteredItems = new ArrayList<>();

    // Pagination — for reelReposts we sort by timestamp desc
    private long    lastTimestamp = Long.MAX_VALUE;
    private boolean isLoading     = false;
    private boolean allLoaded     = false;

    // Follow map
    private final Map<String, Boolean> followingMap = new HashMap<>();

    // Real-time listener
    private ValueEventListener realtimeListener;
    private DatabaseReference  realtimeRef;

    private String reelId;
    private int    sharesCount, repostsCount;
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
        return inflater.inflate(R.layout.bottom_sheet_reel_shares, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        myUid = FirebaseUtils.getCurrentUid();

        Bundle args = getArguments();
        if (args != null) {
            reelId       = args.getString(ARG_REEL_ID);
            sharesCount  = args.getInt(ARG_SHARES,  0);
            repostsCount = args.getInt(ARG_REPOSTS, 0);
        }

        tvSharesCount  = v.findViewById(R.id.tv_shares_count_header);
        tvRepostsCount = v.findViewById(R.id.tv_reposts_count_header);
        etSearch       = v.findViewById(R.id.et_search);
        rv             = v.findViewById(R.id.rv_sharers);
        progressBar    = v.findViewById(R.id.progress_bar);
        tvEmpty        = v.findViewById(R.id.tv_empty);

        tvSharesCount.setText(formatCount(sharesCount));
        tvRepostsCount.setText(formatCount(repostsCount));

        adapter = new SharersAdapter(filteredItems);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        // Pagination scroll
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) r.getLayoutManager();
                if (lm == null) return;
                int last  = lm.findLastVisibleItemPosition();
                int total = adapter.getItemCount();
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

        if (reelId != null) {
            loadMyFollowing();
        } else {
            showEmpty();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (realtimeListener != null && realtimeRef != null) {
            realtimeRef.removeEventListener(realtimeListener);
        }
    }

    // ── Following map ──────────────────────────────────────────────────────
    private void loadMyFollowing() {
        if (myUid.isEmpty()) { loadFirstPage(); return; }
        FirebaseUtils.getReelFollowsRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        for (DataSnapshot child : snap.getChildren()) {
                            Boolean val = child.getValue(Boolean.class);
                            if (Boolean.TRUE.equals(val) && child.getKey() != null) {
                                followingMap.put(child.getKey(), true);
                            }
                        }
                        loadFirstPage();
                        attachRealtimeListener();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { loadFirstPage(); }
                });
    }

    // ── Pagination ─────────────────────────────────────────────────────────
    private void loadFirstPage() {
        isLoading = true;
        progressBar.setVisibility(View.VISIBLE);

        // reelReposts/{reelId}/{uid} = timestamp — order by value (timestamp) desc
        Query q = FirebaseUtils.getReelRepostsRef(reelId)
                .orderByValue()
                .limitToLast(PAGE_SIZE);
        fetchPage(q);
    }

    private void loadNextPage() {
        if (lastTimestamp == Long.MAX_VALUE) return;
        isLoading = true;

        Query q = FirebaseUtils.getReelRepostsRef(reelId)
                .orderByValue()
                .endBefore((double) lastTimestamp)
                .limitToLast(PAGE_SIZE);
        fetchPage(q);
    }

    private void fetchPage(Query q) {
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                // Build uid → timestamp map
                LinkedHashMap<String, Long> uidTs = new LinkedHashMap<>();
                for (DataSnapshot child : snap.getChildren()) {
                    if (child.getKey() == null) continue;
                    Long ts = null;
                    try { ts = child.getValue(Long.class); } catch (Exception ignored) {}
                    uidTs.put(child.getKey(), ts != null ? ts : 0L);
                    if (ts != null && ts < lastTimestamp) lastTimestamp = ts;
                }
                if (uidTs.size() < PAGE_SIZE) allLoaded = true;
                if (uidTs.isEmpty()) {
                    isLoading = false;
                    if (!isAdded()) return;
                    progressBar.setVisibility(View.GONE);
                    if (allItems.isEmpty()) showEmpty();
                    return;
                }
                fetchUsersForPage(uidTs);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                isLoading = false;
                if (isAdded()) progressBar.setVisibility(View.GONE);
            }
        });
    }

    /** N+1 FIX: batch with AtomicInteger */
    private void fetchUsersForPage(LinkedHashMap<String, Long> uidTs) {
        final int total = uidTs.size();
        final AtomicInteger done = new AtomicInteger(0);
        final List<ShareItem> pageItems = Collections.synchronizedList(new ArrayList<>());

        for (Map.Entry<String, Long> entry : uidTs.entrySet()) {
            String uid = entry.getKey();
            long   ts  = entry.getValue();
            // Load Reels profile (reels/users/{uid})
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("reels/users").child(uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            String name      = s.child("displayName").getValue(String.class);
                            String username  = s.child("handle").getValue(String.class);
                            String thumb     = s.child("thumbUrl").getValue(String.class);
                            String photo     = s.child("photoUrl").getValue(String.class);
                            String resolvedPhoto = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                            Boolean verified = s.child("verified").getValue(Boolean.class);
                            String uid2      = uid;
                            boolean isFollowing = Boolean.TRUE.equals(followingMap.get(uid2));
                            pageItems.add(new ShareItem(
                                    uid2,
                                    name     != null ? name     : "User",
                                    username != null ? username : "",
                                    resolvedPhoto != null ? resolvedPhoto : "",
                                    Boolean.TRUE.equals(verified),
                                    isFollowing,
                                    ts
                            ));
                            if (done.incrementAndGet() >= total && isAdded()) appendPage(pageItems);
                        }
                        @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                            if (done.incrementAndGet() >= total && isAdded()) appendPage(pageItems);
                        }
                    });
        }
    }

    private void appendPage(List<ShareItem> pageItems) {
        if (!isAdded() || getContext() == null) return;
        isLoading = false;
        progressBar.setVisibility(View.GONE);

        // Sort newest repost first
        pageItems.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        allItems.addAll(pageItems);
        filterList(etSearch.getText().toString());
        tvEmpty.setVisibility(filteredItems.isEmpty() && !isLoading ? View.VISIBLE : View.GONE);
    }

    // ── Real-time count ────────────────────────────────────────────────────
    private void attachRealtimeListener() {
        realtimeRef = FirebaseUtils.getReelRepostsRef(reelId);
        realtimeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                long count = snap.getChildrenCount();
                if (isAdded()) tvRepostsCount.setText(formatCount((int) count));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        realtimeRef.addValueEventListener(realtimeListener);
    }

    // ── Follow ─────────────────────────────────────────────────────────────
    private void toggleFollow(ShareItem user, Button btnFollow) {
        if (myUid.isEmpty()) return;
        boolean nowFollowing = !Boolean.TRUE.equals(followingMap.get(user.uid));
        followingMap.put(user.uid, nowFollowing);
        user.isFollowing = nowFollowing;

        DatabaseReference ref = FirebaseUtils.getReelFollowsRef(myUid).child(user.uid);
        if (nowFollowing) { ref.setValue(true); btnFollow.setText("Following ✓"); }
        else              { ref.removeValue();  btnFollow.setText("Follow"); }
    }

    // ── Filter ─────────────────────────────────────────────────────────────
    private void filterList(String query) {
        filteredItems.clear();
        if (query.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String q = query.toLowerCase(Locale.getDefault());
            for (ShareItem u : allItems) {
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

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    /** Human-readable relative timestamp: "3h ago", "2d ago" etc. */
    private String relativeTime(long ts) {
        if (ts <= 0) return "";
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000)          return "Just now";
        if (diff < 3_600_000)       return (diff / 60_000)    + "m ago";
        if (diff < 86_400_000)      return (diff / 3_600_000) + "h ago";
        return (diff / 86_400_000) + "d ago";
    }

    // ── Adapter ────────────────────────────────────────────────────────────
    class SharersAdapter extends RecyclerView.Adapter<SharersAdapter.VH> {
        final List<ShareItem> data;
        SharersAdapter(List<ShareItem> d) { data = d; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_reel_liker, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ShareItem u = data.get(pos);
            h.tvName.setText(u.name);

            if (h.tvUsername != null) {
                if (!u.username.isEmpty()) {
                    h.tvUsername.setVisibility(View.VISIBLE);
                    h.tvUsername.setText("@" + u.username);
                } else {
                    h.tvUsername.setVisibility(View.GONE);
                }
            }

            if (h.tvTimestamp != null) {
                String rel = relativeTime(u.timestamp);
                h.tvTimestamp.setVisibility(rel.isEmpty() ? View.GONE : View.VISIBLE);
                h.tvTimestamp.setText(rel);
            }

            if (h.ivVerified != null) {
                h.ivVerified.setVisibility(u.isVerified ? View.VISIBLE : View.GONE);
            }

            if (!u.photo.isEmpty()) {
                Glide.with(requireContext()).load(u.photo)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person)
                        .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            // Follow button
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
            TextView        tvName, tvUsername, tvTimestamp;
            Button          btnFollow, btnMessage;
            VH(@NonNull View v) {
                super(v);
                ivAvatar    = v.findViewById(R.id.iv_avatar);
                ivVerified  = v.findViewById(R.id.iv_verified);
                tvName      = v.findViewById(R.id.tv_name);
                tvUsername  = v.findViewById(R.id.tv_username);
                tvTimestamp = v.findViewById(R.id.tv_timestamp);
                btnFollow   = v.findViewById(R.id.btn_follow);
                btnMessage  = v.findViewById(R.id.btn_message);
            }
        }
    }

    // ── Model ──────────────────────────────────────────────────────────────
    static class ShareItem {
        String uid, name, username, photo;
        boolean isVerified, isFollowing;
        long    timestamp;
        ShareItem(String uid, String name, String username,
                  String photo, boolean isVerified, boolean isFollowing, long timestamp) {
            this.uid        = uid;
            this.name       = name;
            this.username   = username;
            this.photo      = photo;
            this.isVerified = isVerified;
            this.isFollowing = isFollowing;
            this.timestamp  = timestamp;
        }
    }
}
