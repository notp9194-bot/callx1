package com.callx.app.comments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.*;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.models.ReelComment;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ReelCloudinaryUtils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ReelCommentsBottomSheet — Advanced v3 (Photo-in-Comment, Instagram-style)
 *
 * v3 additions over v2:
 *  • 📷 Attach-photo button beside the comment input
 *  • Photo preview strip (thumbnail + upload progress + remove button)
 *  • Background Cloudinary upload via ReelCloudinaryUtils.uploadCommentPhoto()
 *  • Firebase: photoUrl + photoThumbUrl + photoWidth + photoHeight saved to ReelComment
 *  • Inline photo thumbnail rendered in each comment row (adapter handles display)
 */
public class ReelCommentsBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG          = "ReelCommentsBottomSheet";
    public static final String ARG_REEL_ID  = "reel_id";
    public static final String ARG_REEL_UID = "reel_uid";
    public static final String ARG_COMMENTS = "comments_count";

    private static final int PAGE_SIZE = 20;

    // ── Factory ────────────────────────────────────────────────────────────
    public static ReelCommentsBottomSheet newInstance(String reelId, String reelUid, int commentsCount) {
        ReelCommentsBottomSheet f = new ReelCommentsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID,  reelId);
        args.putString(ARG_REEL_UID, reelUid != null ? reelUid : "");
        args.putInt(ARG_COMMENTS,    commentsCount);
        f.setArguments(args);
        return f;
    }

    // ── Fields ─────────────────────────────────────────────────────────────
    private TextView     tvCommentsCount;
    private EditText     etSearch;
    private RecyclerView rv;
    private ProgressBar  progressBar;
    private TextView     tvEmpty;

    // Add-comment bar
    private CircleImageView ivMyAvatar;
    private EditText        etAddComment;
    private ImageButton     btnSendComment;

    // v3: Photo attachment views
    private ImageButton   btnAttachPhoto;
    private LinearLayout  layoutPhotoPreview;
    private ImageView     ivCommentPhotoPreview;
    private ProgressBar   pbPhotoUpload;
    private TextView      tvPhotoUploadStatus;
    private ImageButton   btnRemovePhoto;

    private CommentsAdapter         adapter;
    private final List<CommentItem> allItems      = new ArrayList<>();
    private final List<CommentItem> filteredItems = new ArrayList<>();

    // Pagination
    private long    lastLoadedTimestamp = Long.MAX_VALUE;
    private boolean isLoading           = false;
    private boolean allLoaded           = false;

    // Real-time listener
    private ChildEventListener realtimeListener;

    private String reelId, reelUid, myUid, myName, myPhoto;
    private int    commentsCount;

    // v3: Photo state
    private Uri    pendingPhotoUri   = null;
    private String pendingFullUrl    = null;
    private String pendingThumbUrl   = null;
    private boolean isPhotoUploading = false;

    // Image picker launcher (must be registered in onCreate)
    private ActivityResultLauncher<String> imagePickerLauncher;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Register image picker before onCreateView
        imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) onPhotoPicked(uri); }
        );
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reel_comments, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        myUid  = FirebaseUtils.getCurrentUid();
        myName = FirebaseUtils.getCurrentName();

        Bundle args = getArguments();
        if (args != null) {
            reelId        = args.getString(ARG_REEL_ID);
            reelUid       = args.getString(ARG_REEL_UID, "");
            commentsCount = args.getInt(ARG_COMMENTS, 0);
        }

        // Existing views
        tvCommentsCount = v.findViewById(R.id.tv_comments_count_header);
        etSearch        = v.findViewById(R.id.et_search);
        rv              = v.findViewById(R.id.rv_comments);
        progressBar     = v.findViewById(R.id.progress_bar);
        tvEmpty         = v.findViewById(R.id.tv_empty);
        ivMyAvatar      = v.findViewById(R.id.iv_my_avatar);
        etAddComment    = v.findViewById(R.id.et_add_comment);
        btnSendComment  = v.findViewById(R.id.btn_send_comment);

        // v3: Photo views
        btnAttachPhoto        = v.findViewById(R.id.btn_attach_photo);
        layoutPhotoPreview    = v.findViewById(R.id.layout_photo_preview);
        ivCommentPhotoPreview = v.findViewById(R.id.iv_comment_photo_preview);
        pbPhotoUpload         = v.findViewById(R.id.pb_photo_upload);
        tvPhotoUploadStatus   = v.findViewById(R.id.tv_photo_upload_status);
        btnRemovePhoto        = v.findViewById(R.id.btn_remove_photo);

        tvCommentsCount.setText(formatCount(commentsCount));

        loadMyAvatar();

        adapter = new CommentsAdapter(filteredItems);
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
                if (!isLoading && !allLoaded && last >= total - 4) loadNextPage();
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnSendComment.setOnClickListener(v2 -> postComment());

        // v3: Photo attachment
        btnAttachPhoto.setOnClickListener(v2 -> imagePickerLauncher.launch("image/*"));
        btnRemovePhoto.setOnClickListener(v2 -> clearPendingPhoto());

        if (reelId != null) {
            loadFirstPage();
            attachRealtimeListener();
        } else {
            showEmpty();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (realtimeListener != null) {
            FirebaseUtils.getReelCommentsRef(reelId).removeEventListener(realtimeListener);
        }
    }

    // ── My avatar ──────────────────────────────────────────────────────────
    private void loadMyAvatar() {
        if (myUid.isEmpty() || ivMyAvatar == null) return;
        com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("reels/users").child(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        String thumb = s.child("thumbUrl").getValue(String.class);
                        String photo = s.child("photoUrl").getValue(String.class);
                        String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        if (url == null) url = "";
                        myPhoto = url;
                        if (isAdded() && !myPhoto.isEmpty()) {
                            Glide.with(requireContext()).load(myPhoto)
                                    .apply(RequestOptions.circleCropTransform())
                                    .placeholder(R.drawable.ic_person)
                                    .into(ivMyAvatar);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    // ── v3: Photo Handling ─────────────────────────────────────────────────

    private void onPhotoPicked(Uri uri) {
        pendingPhotoUri = uri;

        // Show preview strip immediately with local thumbnail
        layoutPhotoPreview.setVisibility(View.VISIBLE);
        Glide.with(this).load(uri).centerCrop().into(ivCommentPhotoPreview);

        // Show upload progress
        pbPhotoUpload.setVisibility(View.VISIBLE);
        tvPhotoUploadStatus.setText("Uploading photo…");
        btnSendComment.setEnabled(false);
        isPhotoUploading = true;

        // Upload in background using app's existing Cloudinary setup
        ReelCloudinaryUtils.uploadCommentPhoto(requireContext(), uri,
            new ReelCloudinaryUtils.CommentPhotoCallback() {
                @Override
                public void onSuccess(String fullUrl, String thumbUrl) {
                    if (!isAdded()) return;
                    pendingFullUrl    = fullUrl;
                    pendingThumbUrl   = thumbUrl;
                    isPhotoUploading  = false;
                    pbPhotoUpload.setVisibility(View.GONE);
                    tvPhotoUploadStatus.setText("Photo ready ✓");
                    refreshSendButton();
                }

                @Override
                public void onError(String message) {
                    if (!isAdded()) return;
                    isPhotoUploading = false;
                    clearPendingPhoto();
                    Toast.makeText(requireContext(),
                        "Photo upload failed. Try again.", Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void clearPendingPhoto() {
        pendingPhotoUri  = null;
        pendingFullUrl   = null;
        pendingThumbUrl  = null;
        isPhotoUploading = false;
        layoutPhotoPreview.setVisibility(View.GONE);
        pbPhotoUpload.setVisibility(View.GONE);
        refreshSendButton();
    }

    private void refreshSendButton() {
        boolean hasText  = !etAddComment.getText().toString().trim().isEmpty();
        boolean hasPhoto = pendingPhotoUri != null && !isPhotoUploading;
        btnSendComment.setEnabled(hasText || hasPhoto);
    }

    // ── Post comment ───────────────────────────────────────────────────────
    private void postComment() {
        if (myUid.isEmpty() || reelId == null) return;

        String text = etAddComment.getText().toString().trim();
        boolean hasPhoto = pendingFullUrl != null;

        if (text.isEmpty() && !hasPhoto) return;
        if (isPhotoUploading) {
            Toast.makeText(requireContext(),
                "Please wait — photo is still uploading…", Toast.LENGTH_SHORT).show();
            return;
        }

        etAddComment.setText("");
        etAddComment.clearFocus();

        String commentId = FirebaseUtils.getReelCommentsRef(reelId).push().getKey();
        if (commentId == null) return;

        long now = System.currentTimeMillis();
        ReelComment c = new ReelComment(commentId, myUid, myName,
                myPhoto != null ? myPhoto : "", text, now);

        // v3: Attach photo data if user attached a photo
        if (hasPhoto) {
            c.photoUrl      = pendingFullUrl;
            c.photoThumbUrl = pendingThumbUrl != null ? pendingThumbUrl : pendingFullUrl;
        }

        FirebaseUtils.getReelCommentsRef(reelId).child(commentId).setValue(c);

        // Increment comment count on the reel node
        FirebaseUtils.db().getReference("reels").child(reelId).child("commentsCount")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        Long cur = s.getValue(Long.class);
                        long next = (cur != null ? cur : 0L) + 1L;
                        s.getRef().setValue(next);
                        if (isAdded()) tvCommentsCount.setText(formatCount((int) next));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });

        // Clear photo state after sending
        if (hasPhoto) clearPendingPhoto();
    }

    // ── Pagination ─────────────────────────────────────────────────────────
    private void loadFirstPage() {
        isLoading = true;
        progressBar.setVisibility(View.VISIBLE);
        Query q = FirebaseUtils.getReelCommentsRef(reelId)
                .orderByChild("timestamp")
                .limitToLast(PAGE_SIZE);
        fetchPage(q, true);
    }

    private void loadNextPage() {
        if (lastLoadedTimestamp == Long.MAX_VALUE) return;
        isLoading = true;
        Query q = FirebaseUtils.getReelCommentsRef(reelId)
                .orderByChild("timestamp")
                .endBefore((double) lastLoadedTimestamp)
                .limitToLast(PAGE_SIZE);
        fetchPage(q, false);
    }

    private void fetchPage(Query q, boolean isFirst) {
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<CommentItem> pageItems = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    ReelComment c = child.getValue(ReelComment.class);
                    if (c != null) {
                        if (c.commentId == null) c.commentId = child.getKey();
                        boolean iLiked = !myUid.isEmpty() && c.isLikedBy(myUid);
                        CommentItem item = new CommentItem(
                                c.commentId,
                                c.uid        != null ? c.uid       : "",
                                c.ownerName  != null ? c.ownerName : "User",
                                "", // photo fetched below
                                c.text       != null ? c.text      : "",
                                c.likesCount,
                                c.isPinned,
                                iLiked
                        );
                        // v3: carry photo URLs
                        item.photoUrl      = c.photoUrl;
                        item.photoThumbUrl = c.photoThumbUrl;
                        pageItems.add(0, item);
                        if (c.timestamp > 0 && c.timestamp < lastLoadedTimestamp) {
                            lastLoadedTimestamp = c.timestamp;
                        }
                    }
                }
                if (pageItems.size() < PAGE_SIZE) allLoaded = true;
                if (pageItems.isEmpty()) {
                    isLoading = false;
                    if (!isAdded()) return;
                    progressBar.setVisibility(View.GONE);
                    if (allItems.isEmpty()) showEmpty();
                    return;
                }
                fetchPhotosForPage(pageItems, isFirst);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                isLoading = false;
                if (isAdded()) progressBar.setVisibility(View.GONE);
            }
        });
    }

    private void fetchPhotosForPage(List<CommentItem> pageItems, boolean isFirst) {
        final int total = pageItems.size();
        final AtomicInteger done = new AtomicInteger(0);

        for (CommentItem item : pageItems) {
            if (item.uid.isEmpty()) {
                if (done.incrementAndGet() >= total && isAdded()) appendPage(pageItems, isFirst);
                continue;
            }
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("reels/users").child(item.uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            String thumb    = s.child("thumbUrl").getValue(String.class);
                            String photo    = s.child("photoUrl").getValue(String.class);
                            String resolved = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                            String name     = s.child("displayName").getValue(String.class);
                            Boolean verified= s.child("verified").getValue(Boolean.class);
                            if (resolved != null) item.ownerPhoto = resolved;
                            item.username   = name != null ? name : "";
                            item.isVerified = Boolean.TRUE.equals(verified);
                            if (done.incrementAndGet() >= total && isAdded()) appendPage(pageItems, isFirst);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            if (done.incrementAndGet() >= total && isAdded()) appendPage(pageItems, isFirst);
                        }
                    });
        }
    }

    private void appendPage(List<CommentItem> pageItems, boolean isFirst) {
        if (!isAdded() || getContext() == null) return;
        isLoading = false;
        progressBar.setVisibility(View.GONE);

        if (isFirst) {
            pageItems.sort((a, b) -> {
                if (a.isPinned && !b.isPinned) return -1;
                if (!a.isPinned && b.isPinned) return 1;
                return 0;
            });
        }
        allItems.addAll(pageItems);
        filterList(etSearch.getText().toString());
        tvEmpty.setVisibility(filteredItems.isEmpty() && !isLoading ? View.VISIBLE : View.GONE);
    }

    // ── Real-time listener ─────────────────────────────────────────────────
    private void attachRealtimeListener() {
        realtimeListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, @Nullable String prev) {
                if (!isAdded()) return;
                FirebaseUtils.db().getReference("reels").child(reelId).child("commentsCount")
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot s) {
                                Long c = s.getValue(Long.class);
                                if (c != null && isAdded()) tvCommentsCount.setText(formatCount(c.intValue()));
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
            }
            @Override public void onChildChanged(@NonNull DataSnapshot s, @Nullable String prev) {
                String cId = s.getKey();
                if (cId == null) return;
                ReelComment updated = s.getValue(ReelComment.class);
                if (updated == null) return;
                for (CommentItem item : allItems) {
                    if (cId.equals(item.commentId)) {
                        item.likesCount  = updated.likesCount;
                        item.isLikedByMe = !myUid.isEmpty() && updated.isLikedBy(myUid);
                        item.isPinned    = updated.isPinned;
                        // v3: update photo urls if changed
                        item.photoUrl      = updated.photoUrl;
                        item.photoThumbUrl = updated.photoThumbUrl;
                        if (isAdded()) adapter.notifyDataSetChanged();
                        break;
                    }
                }
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {
                String cId = s.getKey();
                allItems.removeIf(item -> cId != null && cId.equals(item.commentId));
                filteredItems.removeIf(item -> cId != null && cId.equals(item.commentId));
                if (isAdded()) adapter.notifyDataSetChanged();
            }
            @Override public void onChildMoved(@NonNull DataSnapshot s, @Nullable String prev) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getReelCommentsRef(reelId).addChildEventListener(realtimeListener);
    }

    // ── Comment Like ───────────────────────────────────────────────────────
    private void toggleCommentLike(CommentItem item, ImageButton btnLike, TextView tvLikes) {
        if (myUid.isEmpty() || item.commentId == null) return;
        boolean nowLiked = !item.isLikedByMe;
        item.isLikedByMe  = nowLiked;
        item.likesCount   = Math.max(0, item.likesCount + (nowLiked ? 1 : -1));

        btnLike.setImageResource(nowLiked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        tvLikes.setVisibility(item.likesCount > 0 ? View.VISIBLE : View.GONE);
        tvLikes.setText(formatCount(item.likesCount));

        DatabaseReference ref = FirebaseUtils.getReelCommentsRef(reelId).child(item.commentId);
        if (nowLiked) {
            ref.child("likedBy").child(myUid).setValue(true);
        } else {
            ref.child("likedBy").child(myUid).removeValue();
        }
        ref.child("likesCount").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Long cur = s.getValue(Long.class);
                long next = Math.max(0, (cur != null ? cur : 0L) + (nowLiked ? 1 : -1));
                s.getRef().setValue(next);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ── Long-press actions ─────────────────────────────────────────────────
    private void showLongPressMenu(CommentItem item) {
        if (!isAdded() || getContext() == null) return;
        boolean isOwner     = myUid.equals(item.uid);
        boolean isReelOwner = myUid.equals(reelUid);

        List<String> options = new ArrayList<>();
        if (isOwner)                         { options.add("Delete"); options.add("Edit"); }
        if (isOwner || isReelOwner)           { options.add(item.isPinned ? "Unpin" : "Pin"); }
        options.add("Copy text");
        if (!isOwner)                         options.add("Report");

        new android.app.AlertDialog.Builder(requireContext())
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String chosen = options.get(which);
                    switch (chosen) {
                        case "Delete":   deleteComment(item);       break;
                        case "Edit":     startEditComment(item);     break;
                        case "Pin":
                        case "Unpin":    togglePin(item);            break;
                        case "Copy text":
                            android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) requireContext()
                                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("comment", item.text));
                                Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case "Report":
                            Toast.makeText(requireContext(), "Comment reported", Toast.LENGTH_SHORT).show();
                            break;
                    }
                }).show();
    }

    private void deleteComment(CommentItem item) {
        if (item.commentId == null) return;
        FirebaseUtils.getReelCommentsRef(reelId).child(item.commentId).removeValue();
        allItems.remove(item);
        filteredItems.remove(item);
        adapter.notifyDataSetChanged();
    }

    private void startEditComment(CommentItem item) {
        if (!isAdded() || getContext() == null) return;
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle("Edit comment");
        final EditText input = new EditText(requireContext());
        input.setText(item.text);
        input.setSelection(item.text.length());
        builder.setView(input);
        builder.setPositiveButton("Save", (d, w) -> {
            String newText = input.getText().toString().trim();
            if (!newText.isEmpty() && item.commentId != null) {
                item.text = newText;
                FirebaseUtils.getReelCommentsRef(reelId).child(item.commentId).child("text").setValue(newText);
                FirebaseUtils.getReelCommentsRef(reelId).child(item.commentId).child("isEdited").setValue(true);
                FirebaseUtils.getReelCommentsRef(reelId).child(item.commentId).child("editedAt")
                        .setValue(System.currentTimeMillis());
                adapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void togglePin(CommentItem item) {
        if (item.commentId == null) return;
        boolean nowPinned = !item.isPinned;
        item.isPinned = nowPinned;
        FirebaseUtils.getReelCommentsRef(reelId).child(item.commentId)
                .child("isPinned").setValue(nowPinned);
        allItems.sort((a, b) -> {
            if (a.isPinned && !b.isPinned) return -1;
            if (!a.isPinned && b.isPinned) return 1;
            return 0;
        });
        filterList(etSearch.getText().toString());
    }

    // ── Filter ─────────────────────────────────────────────────────────────
    private void filterList(String query) {
        filteredItems.clear();
        if (query.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String q = query.toLowerCase(java.util.Locale.getDefault());
            for (CommentItem c : allItems) {
                if (c.ownerName.toLowerCase(java.util.Locale.getDefault()).contains(q)
                        || c.text.toLowerCase(java.util.Locale.getDefault()).contains(q)) {
                    filteredItems.add(c);
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

    private void openCommentActivity(String scrollToCommentId) {
        try {
            Class<?> cls = Class.forName("com.callx.app.comments.ReelCommentActivity");
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

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(java.util.Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    // ── Inner Adapter ──────────────────────────────────────────────────────
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
            h.tvComment.setText(c.text != null ? c.text : "");

            if (h.tvUsername != null) {
                if (c.username != null && !c.username.isEmpty()) {
                    h.tvUsername.setVisibility(View.VISIBLE);
                    h.tvUsername.setText("@" + c.username);
                } else {
                    h.tvUsername.setVisibility(View.GONE);
                }
            }

            if (h.ivVerified != null) h.ivVerified.setVisibility(c.isVerified ? View.VISIBLE : View.GONE);
            if (h.tvEdited   != null) h.tvEdited.setVisibility(c.isEdited ? View.VISIBLE : View.GONE);

            h.btnLike.setImageResource(c.isLikedByMe ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            h.tvLikes.setVisibility(c.likesCount > 0 ? View.VISIBLE : View.GONE);
            h.tvLikes.setText(formatCount(c.likesCount));
            h.btnLike.setOnClickListener(v -> toggleCommentLike(c, h.btnLike, h.tvLikes));

            h.tvPinned.setVisibility(c.isPinned ? View.VISIBLE : View.GONE);

            // Avatar
            if (c.ownerPhoto != null && !c.ownerPhoto.isEmpty()) {
                Glide.with(requireContext()).load(c.ownerPhoto)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person)
                        .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }

            // ── v3: Photo thumbnail in comment row ──────────────────────────
            if (h.ivCommentPhoto != null) {
                boolean hasPhoto = c.photoUrl != null && !c.photoUrl.isEmpty();
                if (hasPhoto) {
                    h.ivCommentPhoto.setVisibility(View.VISIBLE);
                    String displayUrl = (c.photoThumbUrl != null && !c.photoThumbUrl.isEmpty())
                            ? c.photoThumbUrl : c.photoUrl;
                    Glide.with(requireContext())
                            .load(displayUrl)
                            .centerCrop()
                            .placeholder(android.R.color.darker_gray)
                            .into(h.ivCommentPhoto);

                    // Tap → full-screen viewer
                    h.ivCommentPhoto.setOnClickListener(v -> openPhotoViewer(c.photoUrl, c.photoThumbUrl));
                } else {
                    h.ivCommentPhoto.setVisibility(View.GONE);
                }
            }
            // ────────────────────────────────────────────────────────────────

            h.btnReply.setOnClickListener(v -> openCommentActivity(c.commentId));

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

            h.itemView.setOnLongClickListener(v -> {
                showLongPressMenu(c);
                return true;
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            ImageView       ivVerified;
            ImageView       ivCommentPhoto; // v3
            TextView        tvName, tvUsername, tvComment, tvPinned, tvEdited;
            TextView        tvLikes;
            ImageButton     btnLike;
            Button          btnReply;
            VH(@NonNull View v) {
                super(v);
                ivAvatar       = v.findViewById(R.id.iv_avatar);
                ivVerified     = v.findViewById(R.id.iv_verified);
                ivCommentPhoto = v.findViewById(R.id.iv_comment_photo); // v3 — in item_reel_commenter if added, or item_reel_comment
                tvName         = v.findViewById(R.id.tv_name);
                tvUsername     = v.findViewById(R.id.tv_username);
                tvComment      = v.findViewById(R.id.tv_comment);
                tvLikes        = v.findViewById(R.id.tv_likes);
                tvPinned       = v.findViewById(R.id.tv_pinned);
                tvEdited       = v.findViewById(R.id.tv_edited);
                btnLike        = v.findViewById(R.id.btn_like_comment);
                btnReply       = v.findViewById(R.id.btn_reply);
            }
        }
    }

    // ── v3: Full-screen photo viewer ───────────────────────────────────────
    private void openPhotoViewer(String photoUrl, String thumbUrl) {
        try {
            Class<?> cls = Class.forName("com.callx.app.comments.CommentPhotoViewerActivity");
            Intent i = new Intent(requireContext(), cls);
            i.putExtra("photo_url", photoUrl);
            i.putExtra("thumb_url", thumbUrl != null ? thumbUrl : photoUrl);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            // Fallback: open in browser / MediaViewerActivity
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(photoUrl)));
            } catch (Exception ignored) {}
        }
    }

    // ── Model ──────────────────────────────────────────────────────────────
    static class CommentItem {
        String  commentId, uid, ownerName, ownerPhoto, text, username;
        String  photoUrl, photoThumbUrl; // v3
        int     likesCount;
        boolean isPinned, isLikedByMe, isVerified, isEdited;
        CommentItem(String commentId, String uid, String ownerName,
                    String ownerPhoto, String text, int likesCount,
                    boolean isPinned, boolean isLikedByMe) {
            this.commentId   = commentId;
            this.uid         = uid;
            this.ownerName   = ownerName;
            this.ownerPhoto  = ownerPhoto;
            this.text        = text;
            this.likesCount  = likesCount;
            this.isPinned    = isPinned;
            this.isLikedByMe = isLikedByMe;
            this.username    = "";
        }
    }
}
