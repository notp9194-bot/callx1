package com.callx.app.ui;

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
import com.callx.app.activities.UserReelsActivity;
import com.callx.app.models.ReelComment;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.*;

import java.util.*;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ReelCommentsBottomSheet
 *
 * Instagram-style bottom sheet shown when user taps the comments COUNT on a reel.
 * (Note: tapping the comment ICON still opens ReelCommentActivity for full interaction.)
 *
 * Avatar fix: photoUrl is fetched fresh from the users node (same pattern as
 * ReelLikesBottomSheet) because reelComments node may store a stale or missing ownerPhoto.
 */
public class ReelCommentsBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG          = "ReelCommentsBottomSheet";
    public static final String ARG_REEL_ID  = "reel_id";
    public static final String ARG_REEL_UID = "reel_uid";
    public static final String ARG_COMMENTS = "comments_count";

    // ── Factory ────────────────────────────────────────────────────────────
    public static ReelCommentsBottomSheet newInstance(String reelId, String reelUid, int commentsCount) {
        ReelCommentsBottomSheet f = new ReelCommentsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID,    reelId);
        args.putString(ARG_REEL_UID,   reelUid != null ? reelUid : "");
        args.putInt(ARG_COMMENTS,      commentsCount);
        f.setArguments(args);
        return f;
    }

    // ── Fields ─────────────────────────────────────────────────────────────
    private TextView     tvCommentsCount;
    private EditText     etSearch;
    private RecyclerView rv;
    private ProgressBar  progressBar;
    private TextView     tvEmpty;

    private CommentsAdapter         adapter;
    private final List<CommentItem> allItems      = new ArrayList<>();
    private final List<CommentItem> filteredItems = new ArrayList<>();

    private String reelId;
    private String reelUid;
    private int    commentsCount;

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reel_comments, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Bundle args = getArguments();
        if (args != null) {
            reelId        = args.getString(ARG_REEL_ID);
            reelUid       = args.getString(ARG_REEL_UID, "");
            commentsCount = args.getInt(ARG_COMMENTS, 0);
        }

        tvCommentsCount = v.findViewById(R.id.tv_comments_count_header);
        etSearch        = v.findViewById(R.id.et_search);
        rv              = v.findViewById(R.id.rv_comments);
        progressBar     = v.findViewById(R.id.progress_bar);
        tvEmpty         = v.findViewById(R.id.tv_empty);

        // Bottom reply box → opens full comment screen
        v.findViewById(R.id.layout_reply_box).setOnClickListener(vv -> openCommentActivity(null));

        tvCommentsCount.setText(formatCount(commentsCount));

        adapter = new CommentsAdapter(filteredItems);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        if (reelId != null) loadComments();
        else showEmpty();
    }

    // ── Data loading ───────────────────────────────────────────────────────

    private void loadComments() {
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        FirebaseUtils.getReelCommentsRef(reelId)
                .orderByChild("timestamp")
                .limitToLast(100)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        allItems.clear();
                        for (DataSnapshot child : snap.getChildren()) {
                            ReelComment c = child.getValue(ReelComment.class);
                            if (c != null) {
                                if (c.commentId == null) c.commentId = child.getKey();
                                // photo left empty — filled fresh by fetchPhotos() below
                                allItems.add(0, new CommentItem(
                                        c.commentId,
                                        c.uid        != null ? c.uid        : "",
                                        c.ownerName  != null ? c.ownerName  : "User",
                                        "",
                                        c.text       != null ? c.text       : "",
                                        c.likesCount,
                                        c.isPinned
                                ));
                            }
                        }
                        // Pinned comments always first
                        allItems.sort((a, b) -> {
                            if (a.isPinned && !b.isPinned) return -1;
                            if (!a.isPinned && b.isPinned) return 1;
                            return 0;
                        });

                        if (allItems.isEmpty()) {
                            if (isAdded()) { progressBar.setVisibility(View.GONE); showEmpty(); }
                            return;
                        }
                        // Fetch fresh photoUrl from users node — same as ReelLikesBottomSheet
                        fetchPhotos();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (isAdded()) { progressBar.setVisibility(View.GONE); showEmpty(); }
                    }
                });
    }

    /**
     * For each comment item, fetch photoUrl from the users node.
     * This mirrors fetchUsers() in ReelLikesBottomSheet and guarantees fresh avatars
     * regardless of what was stored in reelComments node.
     */
    private void fetchPhotos() {
        final int total   = allItems.size();
        final int[] done  = {0};

        for (CommentItem item : allItems) {
            if (item.uid.isEmpty()) {
                done[0]++;
                if (done[0] >= total && isAdded()) finishLoad();
                continue;
            }
            FirebaseUtils.getUserRef(item.uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            String photo = s.child("thumbUrl").getValue(String.class);
                            if (photo != null && !photo.isEmpty()) item.ownerPhoto = photo;
                            done[0]++;
                            if (done[0] >= total && isAdded()) finishLoad();
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            done[0]++;
                            if (done[0] >= total && isAdded()) finishLoad();
                        }
                    });
        }
    }

    private void finishLoad() {
        if (!isAdded() || getContext() == null) return;
        progressBar.setVisibility(View.GONE);
        filteredItems.clear();
        filteredItems.addAll(allItems);
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void filterList(String query) {
        filteredItems.clear();
        if (query.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String q = query.toLowerCase(Locale.getDefault());
            for (CommentItem c : allItems) {
                if (c.ownerName.toLowerCase(Locale.getDefault()).contains(q)
                        || c.text.toLowerCase(Locale.getDefault()).contains(q)) {
                    filteredItems.add(c);
                }
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showEmpty() {
        tvEmpty.setVisibility(View.VISIBLE);
        rv.setVisibility(View.GONE);
    }

    /** Open ReelCommentActivity via reflection to avoid cross-module import. */
    private void openCommentActivity(String scrollToCommentId) {
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.ReelCommentActivity");
            Intent i = new Intent(requireContext(), cls);
            i.putExtra("reel_id",  reelId);
            i.putExtra("reel_uid", reelUid);
            if (scrollToCommentId != null) i.putExtra("scroll_to_comment", scrollToCommentId);
            startActivity(i);
            dismiss();
        } catch (ClassNotFoundException e) {
            dismiss();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    // ── Adapter ────────────────────────────────────────────────────────────
    class CommentsAdapter extends RecyclerView.Adapter<CommentsAdapter.VH> {
        final List<CommentItem> data;
        CommentsAdapter(List<CommentItem> d) { data = d; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_reel_commenter, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            CommentItem c = data.get(pos);
            h.tvName.setText(c.ownerName);
            h.tvComment.setText(c.text);

            // Likes count on comment
            if (c.likesCount > 0) {
                h.tvLikes.setVisibility(View.VISIBLE);
                h.tvLikes.setText(formatCount(c.likesCount));
            } else {
                h.tvLikes.setVisibility(View.GONE);
            }

            // Pinned badge
            h.tvPinned.setVisibility(c.isPinned ? View.VISIBLE : View.GONE);

            // Avatar — fetched fresh from users node so always up-to-date
            if (!c.ownerPhoto.isEmpty()) {
                Glide.with(requireContext())
                        .load(c.ownerPhoto)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person)
                        .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            // Tap row → open commenter's profile
            h.itemView.setOnClickListener(v -> {
                if (c.uid.isEmpty()) return;
                try {
                    Intent i = new Intent(requireContext(), UserReelsActivity.class);
                    i.putExtra(UserReelsActivity.EXTRA_UID,   c.uid);
                    i.putExtra(UserReelsActivity.EXTRA_NAME,  c.ownerName);
                    i.putExtra(UserReelsActivity.EXTRA_PHOTO, c.ownerPhoto);
                    startActivity(i);
                    dismiss();
                } catch (Exception ignored) {}
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView        tvName, tvComment, tvLikes, tvPinned;
            VH(@NonNull View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_avatar);
                tvName    = v.findViewById(R.id.tv_name);
                tvComment = v.findViewById(R.id.tv_comment);
                tvLikes   = v.findViewById(R.id.tv_likes);
                tvPinned  = v.findViewById(R.id.tv_pinned);
            }
        }
    }

    // ── Model ──────────────────────────────────────────────────────────────
    static class CommentItem {
        String  commentId, uid, ownerName, ownerPhoto, text;
        int     likesCount;
        boolean isPinned;
        CommentItem(String commentId, String uid, String ownerName,
                    String ownerPhoto, String text, int likesCount, boolean isPinned) {
            this.commentId  = commentId;
            this.uid        = uid;
            this.ownerName  = ownerName;
            this.ownerPhoto = ownerPhoto;
            this.text       = text;
            this.likesCount = likesCount;
            this.isPinned   = isPinned;
        }
    }
}
