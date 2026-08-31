package com.callx.app.comments;
import com.callx.app.utils.AlertDialogStyler;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.inputmethod.InputContentInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.models.ReelComment;
import com.callx.app.models.ReelReply;
import com.callx.app.reels.R;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.workers.ReelCommentNotifWorker;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ReelCommentFragment — "single source of truth" for the reel comment UI
 * (sort/search, edit/pin/report, reactions, replies — everything the old
 * ReelCommentActivity had), matching the SoundDetailFragment pattern:
 *
 *   - ReelCommentActivity  → thin host, fullscreen (isSheet = false)
 *   - ReelCommentSheetFragment → thin host, bottom sheet (isSheet = true)
 *
 * Whatever renders here is IDENTICAL in both places — no duplicate logic.
 * The old, separately-implemented ReelCommentsBottomSheet (its own adapter,
 * its own pagination, missing sort/edit/pin/report) has been deleted; this
 * fragment is now the only comment UI in the app.
 */
public class ReelCommentFragment extends Fragment {

    private static final String ARG_REEL_ID    = "reel_id";
    private static final String ARG_REEL_UID   = "reel_uid";
    private static final String ARG_HIGHLIGHT  = "highlight_comment_id";
    private static final String ARG_IS_SHEET   = "is_sheet";
    /** Instagram-style: caption/owner-name row shown above the comment list,
     *  ONLY when this sheet was opened by tapping the reel's caption/name —
     *  not when opened via the comment icon/count. Empty caption → header
     *  stays hidden even if these args are present. */
    private static final String ARG_CAPTION      = "caption_text";
    private static final String ARG_OWNER_NAME   = "caption_owner_name";
    private static final String ARG_OWNER_AVATAR = "caption_owner_avatar_url";

    private static final int MAX_COMMENT_LENGTH = 300;
    /** Min gap between two posted comments/replies from this device — blunt
     *  client-side anti-spam guard. Server-side rules validate length/uid,
     *  but nothing previously stopped a user mashing "send" in a loop. */
    private static final long COMMENT_COOLDOWN_MS = 2000;

    public interface OnCloseListener { void onClose(); }
    private OnCloseListener closeListener;
    public void setOnCloseListener(OnCloseListener l) { this.closeListener = l; }
    private void close() { if (closeListener != null) closeListener.onClose(); }

    // ── Factory ──────────────────────────────────────────────────────────────

    public static ReelCommentFragment newInstance(String reelId, String reelUid,
                                                    String highlightCommentId, boolean isSheet) {
        return newInstance(reelId, reelUid, highlightCommentId, isSheet, null, null, null);
    }

    /** Overload used when the sheet should open with the reel's
     *  caption/owner row visible above the comment list (caption-tap entry
     *  point). Pass null/empty caption for the normal comment-icon entry
     *  point — the header simply won't render. */
    public static ReelCommentFragment newInstance(String reelId, String reelUid,
                                                    String highlightCommentId, boolean isSheet,
                                                    String caption, String ownerName, String ownerAvatarUrl) {
        ReelCommentFragment f = new ReelCommentFragment();
        Bundle b = new Bundle();
        b.putString(ARG_REEL_ID,  reelId  != null ? reelId  : "");
        b.putString(ARG_REEL_UID, reelUid != null ? reelUid : "");
        b.putString(ARG_HIGHLIGHT, highlightCommentId != null ? highlightCommentId : "");
        b.putBoolean(ARG_IS_SHEET, isSheet);
        b.putString(ARG_CAPTION,      caption        != null ? caption        : "");
        b.putString(ARG_OWNER_NAME,   ownerName      != null ? ownerName      : "");
        b.putString(ARG_OWNER_AVATAR, ownerAvatarUrl != null ? ownerAvatarUrl : "");
        f.setArguments(b);
        return f;
    }

    // ── Views ────────────────────────────────────────────────────────────────
    private RecyclerView   rvComments;
    // Caption header (caption-tap entry point only — see bindCaptionHeader())
    private String captionText;
    private String captionOwnerName;
    private String captionOwnerAvatar;
    private GifAwareCommentEditText etComment;
    private ImageButton    btnSend;
    private ImageButton    btnAttachPhoto;
    private FrameLayout    layoutImagePreview;
    private ImageView      ivImagePreview;
    private ImageButton    btnRemoveImage;
    private ProgressBar    progressImage;
    private TextView       tvEmpty;
    private CommentSkeletonView skeletonComments;
    // True once the first comments page (or the empty state) has actually
    // resolved — used to dismiss skeletonComments exactly once, since
    // showEmpty() itself keeps re-running on every later refresh.
    private boolean firstCommentsPageResolved = false;
    private TextView       tvCommentCount;
    private LinearLayout   barReplyingTo;
    private TextView       tvReplyingTo;
    private ImageButton    btnCancelReply;
    private TextView       tvCharCount;
    private TextView       chipNewest, chipTop;
    private ImageButton    btnSearchToggle;
    private LinearLayout   layoutSearch;
    private EditText       etSearch;
    private ImageButton    btnCloseSearch;
    private RecyclerView   rvMentionSuggestions;
    private MentionSuggestionAdapter mentionAdapter;
    private TextView       pillNewComments;
    private LinearLayout   layoutSwipeHint;
    private LinearLayout   containerQuickEmojis;
    private TextView       tvLoadingOlder;

    // ── State ────────────────────────────────────────────────────────────────
    private String reelId  = "";
    private String reelUid = "";
    private boolean isSheet = false;
    private String myUid   = "";
    private String myName  = "User";
    private String myPhoto = "";

    private boolean sortByTop    = false;
    private boolean searchActive = false;
    private String  searchQuery  = "";
    private String  highlightCommentId = "";

    // ── Comment photo attachment (Instagram-style) ──────────────────────────
    /** Local uri the user just picked, shown in the preview while it uploads. */
    private Uri    pickedImageUri = null;
    /** Cloudinary secure_url once the upload finishes — attached to the
     *  comment/reply on send, then cleared. Null while nothing is attached
     *  or the upload hasn't completed yet. */
    private String uploadedImageUrl = null;
    private boolean uploadingImage = false;

    private final ActivityResultLauncher<String> imagePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) onImagePicked(uri);
        });

    private ReelComment replyingToComment = null;
    /** Non-null when the user tapped "Reply" on a REPLY (not a top-level
     *  comment) — Instagram flattens this into the same parent's reply
     *  thread but tags the reply's author. */
    private ReelReply   replyingToReplyMention = null;

    // ── @mention autocomplete state ─────────────────────────────────────────
    /** lowercase display-name → full candidate (uid + name + avatar url),
     *  built from everyone visible in this thread so far (commenters +
     *  repliers) — the tag source. Replaces the old bare
     *  Map<String,String> name→uid, which had no avatar and forced a
     *  linear re-scan of allComments to recover display-name casing on
     *  every suggestion render (see MentionCandidate's class doc). */
    private final Map<String, MentionCandidate> mentionCandidates = new HashMap<>();
    /** uid → display name for every user tagged during THIS compose session
     *  (cleared on send/cancel) — attached to the comment/reply on submit. */
    private final Map<String, String> pendingMentions = new HashMap<>();
    private boolean suppressTextWatcher = false;
    // Snapshot of where the in-progress "@token" starts/ends in the input,
    // captured each time the suggestion list is (re)shown — read by the
    // adapter's click callback (see setupAdapter... rvMentionSuggestions
    // wiring in bindViews) since the tap arrives asynchronously relative to
    // whatever the cursor position is at click time.
    private int pendingMentionAt = -1;
    private int pendingMentionCursor = -1;

    // ── "New comments" pill state ───────────────────────────────────────────
    private int  pendingNewComments = 0;
    /** True once the initial comment burst has settled — the pill only
     *  reacts to genuinely NEW comments arriving after that point, not the
     *  batch of existing ones Firebase delivers via onChildAdded on open. */
    private boolean initialLoadSettled = false;

    // ── Data ─────────────────────────────────────────────────────────────────
    private final List<ReelComment> allComments = new ArrayList<>();

    // ── Blocked users (SECURITY FIX) ─────────────────────────────────────────
    // Previously the comment UI had no concept of blocked users at all — if
    // you blocked someone elsewhere in the app, their comments/replies still
    // showed up here. This mirrors BlockedUsersActivity's path
    // (blocks/{myUid}/{blockedUid} = true) and hides their rows client-side.
    // NOTE: this is a UI-level filter, not a security boundary — a blocked
    // user's comment still exists in the DB, matching how blocking works
    // elsewhere in this app (chat, feed, etc.).
    private final Set<String> blockedUids = new HashSet<>();
    private DatabaseReference blocksListenerRef;
    private ValueEventListener blocksListener;

    private long lastCommentPostAt = 0L;

    // ── Burst-update debouncing (PERF) ──────────────────────────────────────
    // Firebase's ChildEventListener fires onChildAdded once per existing
    // comment on initial load — a reel with 200 comments meant 200 separate
    // applyFilterAndSort() calls, each rebuilding the filtered list AND
    // re-sorting, back to back, before the first frame even settled. This
    // coalesces any burst of add/change/remove events arriving within one
    // short window into a single refresh.
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean refreshQueued = false;
    private boolean pendingAutoScroll = false;
    private static final long REFRESH_DEBOUNCE_MS = 60;
    private final Runnable refreshRunnable = () -> {
        refreshQueued = false;
        applyFilterAndSort();
        if (pendingAutoScroll) {
            pendingAutoScroll = false;
            autoScrollIfAtTop();
        }
        if (pendingNewComments > 0 && pillNewComments != null) {
            pillNewComments.setText(pendingNewComments == 1
                ? "↑ New comment" : "↑ " + pendingNewComments + " new comments");
            pillNewComments.setVisibility(View.VISIBLE);
        }
    };

    private void requestRefresh() {
        // FIX: previously this only scheduled a refresh on the FIRST event
        // of a burst and ignored every event after — if 12 onChildAdded
        // events (see PAGE_SIZE below) didn't all land within one
        // REFRESH_DEBOUNCE_MS window (slow network), the fragment rendered
        // a PARTIAL list early, then re-rendered again once the rest
        // trickled in — visible as the list "settling"/re-sorting itself
        // right after opening. Now every event PUSHES the timer back, so
        // the single render only happens once the burst has genuinely gone
        // quiet — one clean paint, no mid-air reorder.
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshQueued = true;
        refreshHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS);
    }

    // ── Pagination (PERF) ────────────────────────────────────────────────────
    // Instagram never downloads an entire comment thread up front — a viral
    // reel can have tens of thousands of comments, and a plain
    // ChildEventListener on "reelComments/{reelId}" fires onChildAdded once
    // per EXISTING comment (huge parse + bandwidth cost, and REFRESH_DEBOUNCE_MS
    // above only coalesces the *UI* refresh, not the network/parse work).
    // Instead we live-listen to only the most recent PAGE_SIZE comments and
    // page older ones in on demand as the user scrolls up — exactly like
    // Instagram's comment sheet. Ordered by KEY (not a "timestamp" child):
    // comment IDs are Firebase push() keys, which are already chronologically
    // sortable, so this needs no extra ".indexOn" rule in the Firebase console.
    // Instagram-style: only the latest 12 comments on open; the next batch
    // of 12 older ones loads only once the user scrolls up near the top
    // (see maybeLoadOlderComments()) — not the whole thread up front.
    private static final int PAGE_SIZE = 12;
    private final Set<String> loadedCommentIds = new HashSet<>();
    private String  oldestLoadedKey = null;
    private boolean hasMoreOlder    = true;
    private boolean loadingOlder    = false;
    private Query    commentsQuery;

    /** Triggered by the scroll listener once the user nears the top of the
     *  loaded list — fetches the next older page as a one-off read (NOT a
     *  live listener, so it doesn't grow the realtime bandwidth footprint). */
    private void maybeLoadOlderComments() {
        if (!initialLoadSettled || loadingOlder || !hasMoreOlder
                || oldestLoadedKey == null || reelId.isEmpty()) return;
        loadingOlder = true;
        showLoadingOlder(true);

        Query olderPage = FirebaseUtils.getReelCommentsRef(reelId)
            .orderByKey().endBefore(oldestLoadedKey).limitToLast(PAGE_SIZE);

        olderPage.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                List<ReelComment> older = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    ReelComment c = safeParseComment(child);
                    if (c == null || TextUtils.isEmpty(c.text)) continue;
                    if (!loadedCommentIds.add(c.commentId)) continue; // dup guard
                    registerMentionCandidate(c.uid, c.ownerName, c.ownerPhoto);
                    older.add(c);
                }
                if (!older.isEmpty()) {
                    // Query is ascending by key, so the first child returned
                    // is the oldest of this page — that becomes our new floor.
                    oldestLoadedKey = older.get(0).commentId;
                    allComments.addAll(0, older);
                }
                hasMoreOlder = older.size() >= PAGE_SIZE;
                loadingOlder = false;
                showLoadingOlder(false);
                if (!older.isEmpty()) applyFilterAndSort();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                loadingOlder = false;
                showLoadingOlder(false);
            }
        });
    }

    private void showLoadingOlder(boolean show) {
        if (tvLoadingOlder != null) tvLoadingOlder.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ── Firebase ─────────────────────────────────────────────────────────────
    private DatabaseReference  commentsRef;
    private ChildEventListener commentsListener;

    // Manual keyboard-aware padding for the SHEET host only (see
    // setupKeyboardAwarePadding()) — BottomSheetDialog windows don't always
    // resize reliably with windowSoftInputMode=ADJUST_RESIZE (a long-known
    // Android dialog quirk), so the input bar could end up hidden behind
    // the keyboard instead of docked above it.
    private View fragmentRoot;


    // Live total comment count (reels/{reelId}/commentsCount) — the header
    // must show the TRUE total, not just how many rows are loaded/paged in
    // locally (allComments only ever holds the live PAGE_SIZE window plus
    // whatever older pages were paged in on demand).
    private DatabaseReference  commentsCountRef;
    private ValueEventListener commentsCountListener;
    private int totalCommentsCount = -1; // -1 = not yet known

    // ── Adapter ──────────────────────────────────────────────────────────────
    private ReelCommentsAdapter adapter;

    /** Exposes the comments list so a sheet host (ReelCommentSheetFragment)
     *  can coordinate "pull down from top of list" with its own
     *  BottomSheetBehavior — this fragment itself has no sheet concept. */
    @Nullable
    public RecyclerView getCommentsRecyclerView() {
        return rvComments;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_reel_comment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Bundle b = getArguments() != null ? getArguments() : new Bundle();
        reelId  = b.getString(ARG_REEL_ID,  "");
        reelUid = b.getString(ARG_REEL_UID, "");
        highlightCommentId = b.getString(ARG_HIGHLIGHT, "");
        isSheet = b.getBoolean(ARG_IS_SHEET, false);
        captionText       = b.getString(ARG_CAPTION,      "");
        captionOwnerName  = b.getString(ARG_OWNER_NAME,   "");
        captionOwnerAvatar = b.getString(ARG_OWNER_AVATAR, "");

        readCurrentUser();
        bindViews(v);
        bindCaptionHeader(v);
        setupAdapter();
        setupSortChips();
        setupSearch();
        setupCharCounter();
        setupMentionAutocomplete();
        setupNewCommentsPill();
        setupQuickEmojiRow();
        loadMyPhoto();
        restoreDraft();

        if (!reelId.isEmpty()) { showCommentsShimmer(); loadComments(); listenCommentsCount(); }
        else showEmpty(true);
        listenBlockedUsers();

        if (rvComments != null) {
            rvComments.postDelayed(() -> {
                initialLoadSettled = true;
                // If the live window's initial burst came back under a full
                // page, that IS every comment on this reel — nothing older
                // to page in, so skip wiring up load-more entirely.
                hasMoreOlder = allComments.size() >= PAGE_SIZE;
                // Pagination was gated on initialLoadSettled until just now
                // (see maybeLoadOlderComments()'s guard) — give the
                // viewport-fill check a chance to run now that it's unlocked,
                // instead of only reacting to the next live data change.
                maybeAutoFillViewport();
                maybeShowSwipeReplyHint();
            }, 1200);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        saveDraft();
        // Stop burning frames on a shader-matrix animation while this
        // fragment isn't visible (backgrounded app, or another sheet on
        // top) — resumed in onResume() if the skeleton is still needed.
        if (skeletonComments != null) skeletonComments.stop();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (skeletonComments != null && skeletonComments.getVisibility() == View.VISIBLE) {
            skeletonComments.start();
        }
    }

    @Override
    public void onDestroyView() {
        saveDraft();
        if (skeletonComments != null) skeletonComments.stop();
        refreshHandler.removeCallbacksAndMessages(null);
        try {
            if (commentsListener != null && commentsQuery != null)
                commentsQuery.removeEventListener(commentsListener);
            if (commentsCountListener != null && commentsCountRef != null)
                commentsCountRef.removeEventListener(commentsCountListener);
            if (blocksListener != null && blocksListenerRef != null)
                blocksListenerRef.removeEventListener(blocksListener);
        } catch (Exception ignored) {}
        super.onDestroyView();
    }

    /** Live-listens to my blocklist (blocks/{myUid}) so blocking/unblocking
     *  someone elsewhere in the app updates this thread immediately without
     *  needing to reopen the comment sheet. */
    private void listenBlockedUsers() {
        if (myUid.isEmpty()) return;
        blocksListenerRef = FirebaseUtils.getBlocksRef(myUid);
        blocksListener = blocksListenerRef.addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                blockedUids.clear();
                for (DataSnapshot ds : snapshot.getChildren()) {
                    if (Boolean.TRUE.equals(ds.getValue(Boolean.class)) && ds.getKey() != null) {
                        blockedUids.add(ds.getKey());
                    }
                }
                applyFilterAndSort();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void readCurrentUser() {
        try {
            FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            if (u != null) {
                myUid  = u.getUid()         != null ? u.getUid()         : "";
                myName = u.getDisplayName() != null && !u.getDisplayName().isEmpty()
                         ? u.getDisplayName() : "User";
                myPhoto = u.getPhotoUrl()   != null ? u.getPhotoUrl().toString() : "";
            }
        } catch (Exception ignored) {}
    }

    /** Cached target decode size (px) for the caption-header avatar (32dp) —
     *  computed once per process, mirrors the reshare-thumb optimization
     *  pattern used in StatusViewerActivity. */
    private static volatile int sCaptionAvatarPx = 0;

    /** Populates and reveals the caption/owner row above the sort chips —
     *  only when this instance was opened via the caption-tap entry point
     *  (non-empty captionText/captionOwnerName), matching Instagram: opening
     *  the sheet from the comment icon/count never shows this row.
     *
     *  Ultra-optimized: the header lives behind a ViewStub in the layout, so
     *  on the far more common comment-icon/count entry (no caption/owner
     *  content) this method does a single null-content check and returns —
     *  zero inflation, zero view lookups, zero Glide setup. The ViewStub is
     *  only inflated (once — inflate() nulls the stub reference itself)
     *  when there's actually something to show. */
    private void bindCaptionHeader(View root) {
        boolean hasContent = (captionText != null && !captionText.isEmpty())
                           || (captionOwnerName != null && !captionOwnerName.isEmpty());
        if (!hasContent) return; // stub never inflated — nothing to hide, nothing allocated

        android.view.ViewStub stub = root.findViewById(R.id.stub_reel_caption_header);
        View headerRoot = (stub != null) ? stub.inflate() : root.findViewById(R.id.layout_reel_caption_header);
        if (headerRoot == null) return;

        TextView tvOwner = headerRoot.findViewById(R.id.tv_caption_header_owner);
        TextView tvText  = headerRoot.findViewById(R.id.tv_caption_header_text);
        ImageView ivAvatar = headerRoot.findViewById(R.id.iv_caption_header_avatar);

        if (tvOwner != null) tvOwner.setText(captionOwnerName != null ? captionOwnerName : "");
        if (tvText != null) {
            if (captionText != null && !captionText.isEmpty()) {
                tvText.setText(captionText);
                tvText.setVisibility(View.VISIBLE);
            } else {
                tvText.setVisibility(View.GONE);
            }
        }
        if (ivAvatar != null && captionOwnerAvatar != null && !captionOwnerAvatar.isEmpty()) {
            // Same size-aware decode strategy as StatusViewerActivity's
            // reshare thumb: decode straight to the 32dp target instead of
            // full-res, RGB_565 (opaque avatar, no alpha needed), downsample
            // during decode (not after), and cache the transformed result on
            // disk so repeat opens of the same reel's sheet skip re-decoding.
            int px = sCaptionAvatarPx;
            if (px <= 0) {
                px = Math.round(32 * getResources().getDisplayMetrics().density);
                sCaptionAvatarPx = px;
            }
            RequestOptions opts = new RequestOptions()
                .override(px, px)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                .downsample(com.bumptech.glide.load.resource.bitmap.DownsampleStrategy.CENTER_OUTSIDE)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE)
                .centerCrop()
                .dontAnimate();
            Glide.with(this)
                .load(captionOwnerAvatar)
                .apply(opts)
                .priority(com.bumptech.glide.Priority.IMMEDIATE)
                .into(ivAvatar);
        }
    }

    private void bindViews(View root) {
        fragmentRoot    = root;
        rvComments      = root.findViewById(R.id.rv_comments);
        skeletonComments = root.findViewById(R.id.skeleton_comments);
        etComment       = root.findViewById(R.id.et_comment);
        btnSend         = root.findViewById(R.id.btn_send);
        tvEmpty         = root.findViewById(R.id.tv_empty);
        tvCommentCount  = root.findViewById(R.id.tv_comment_count);
        barReplyingTo   = root.findViewById(R.id.bar_replying_to);
        tvReplyingTo    = root.findViewById(R.id.tv_replying_to);
        btnCancelReply  = root.findViewById(R.id.btn_cancel_reply);
        tvCharCount     = root.findViewById(R.id.tv_char_count);
        chipNewest      = root.findViewById(R.id.chip_newest);
        chipTop         = root.findViewById(R.id.chip_top);
        btnSearchToggle = root.findViewById(R.id.btn_search_toggle);
        layoutSearch    = root.findViewById(R.id.layout_search);
        etSearch        = root.findViewById(R.id.et_search);
        btnCloseSearch  = root.findViewById(R.id.btn_close_search);
        rvMentionSuggestions = root.findViewById(R.id.rv_mention_suggestions);
        if (rvMentionSuggestions != null) {
            mentionAdapter = new MentionSuggestionAdapter();
            mentionAdapter.setListener(candidate ->
                insertMention(candidate.uid, candidate.name, pendingMentionAt, pendingMentionCursor));
            rvMentionSuggestions.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            rvMentionSuggestions.setAdapter(mentionAdapter);
        }
        pillNewComments = root.findViewById(R.id.pill_new_comments);
        layoutSwipeHint = root.findViewById(R.id.layout_swipe_hint);
        containerQuickEmojis = root.findViewById(R.id.container_quick_emojis);
        tvLoadingOlder  = root.findViewById(R.id.tv_loading_older);
        btnAttachPhoto     = root.findViewById(R.id.btn_attach_photo);
        layoutImagePreview = root.findViewById(R.id.layout_comment_image_preview);
        ivImagePreview     = root.findViewById(R.id.iv_comment_image_preview);
        btnRemoveImage     = root.findViewById(R.id.btn_remove_comment_image);
        progressImage      = root.findViewById(R.id.progress_comment_image);

        // Same button closes either mode — finish() for the fullscreen host,
        // dismiss() for the sheet host — see setOnCloseListener callers.
        ImageButton btnBack = root.findViewById(R.id.btn_back);
        if (btnBack        != null) btnBack.setOnClickListener(v -> close());
        if (btnSend        != null) btnSend.setOnClickListener(v -> onSendClicked());
        if (btnCancelReply != null) btnCancelReply.setOnClickListener(v -> cancelReply());
        if (btnAttachPhoto != null) btnAttachPhoto.setOnClickListener(v -> {
            try {
                imagePickerLauncher.launch("image/*");
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Couldn't open gallery", Toast.LENGTH_SHORT).show();
            }
        });
        if (btnRemoveImage != null) btnRemoveImage.setOnClickListener(v -> clearPickedImage());

        // Keyboard GIF (Gboard's built-in GIF search tab) — commitContent
        // hands us a content:// uri, which we route through the exact same
        // pick→preview→upload pipeline as an attached gallery photo, so it
        // rides on the existing imageUrl comment field with no new API.
        if (etComment != null) {
            etComment.setGifReceivedListener(this::onKeyboardGifReceived);
        }

        // BUG FIX: when this fragment is hosted inside ReelCommentSheetFragment
        // (isSheet=true) the sheet can be sitting in its HALF_EXPANDED state,
        // which only reserves ~45% of the screen. Tapping the input while
        // half-expanded left the EditText fighting the keyboard for space —
        // on some devices it got squeezed to zero height and looked like it
        // "wasn't there" / couldn't be typed into. Expand the sheet fully the
        // moment the input gets focus, exactly like Instagram's comment sheet.
        if (etComment != null) {
            etComment.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    Fragment parent = getParentFragment();
                    if (parent instanceof ReelCommentSheetFragment) {
                        ((ReelCommentSheetFragment) parent).expandFully();
                    }
                }
            });
        }
    }

    private void setupAdapter() {
        adapter = new ReelCommentsAdapter(myUid);
        adapter.setReelOwnerUid(reelUid);
        adapter.setListener(new ReelCommentsAdapter.OnCommentActionListener() {

            @Override
            public void onLikeComment(ReelComment comment, int position) {
                toggleLike(comment, position);
            }

            @Override
            public void onReplyComment(ReelComment comment) {
                startReply(comment);
            }

            @Override
            public void onLongPress(ReelComment comment, int position) {
                boolean canDelete = myUid.equals(comment.uid) || myUid.equals(reelUid);
                if (canDelete) showDeleteDialog(comment, position);
            }

            @Override
            public void onAvatarClick(ReelComment comment) {
                // Extend: navigate to profile
            }

            @Override
            public void onViewReplies(ReelComment comment,
                                      LinearLayout container, TextView tvToggle) {
                if (container.getVisibility() == View.VISIBLE) {
                    container.setVisibility(View.GONE);
                    container.removeAllViews();
                    tvToggle.setText("View " + comment.replyCount
                        + (comment.replyCount == 1 ? " reply" : " replies"));
                } else {
                    tvToggle.setText("Loading…");
                    loadRepliesInto(comment, container, tvToggle);
                }
            }

            @Override
            public void onEditComment(ReelComment comment, int position) {
                showEditDialog(comment, position);
            }

            @Override
            public void onPinComment(ReelComment comment) {
                togglePin(comment);
            }

            @Override
            public void onReportComment(ReelComment comment) {
                showReportDialog(comment);
            }

            @Override
            public void onReactComment(ReelComment comment, @Nullable String emoji, int position) {
                postReaction(comment, emoji, position);
            }

            @Override
            public void onRetryComment(ReelComment comment) {
                retryComment(comment);
            }
        });

        if (rvComments != null) {
            rvComments.setLayoutManager(new LinearLayoutManager(requireContext()));
            rvComments.setAdapter(adapter);

            // ── Smooth-scrolling tuning ─────────────────────────────────
            // Comment rows aren't uniform height (replies/reactions expand
            // them), so setHasFixedSize() isn't safe here — these are the
            // levers that are: a bigger off-screen view cache means fewer
            // fresh inflate+bind cycles during a fast fling, and a shared,
            // pre-warmed RecycledViewPool means recycled rows are ready to
            // rebind immediately instead of being inflated from scratch.
            rvComments.setItemViewCacheSize(12);
            RecycledViewPool pool = new RecycledViewPool();
            pool.setMaxRecycledViews(0, 20);
            rvComments.setRecycledViewPool(pool);

            // PERF/UX: the default DefaultItemAnimator plays a brief fade
            // "change" animation on every notifyItemChanged — including
            // payload-only like/reaction updates — which reads as a flicker
            // during a burst of live activity. Instagram's comment rows
            // never blink; they just update in place. Insert/remove
            // animations (new comment arriving, a delete) are kept.
            RecyclerView.ItemAnimator rvAnim = rvComments.getItemAnimator();
            if (rvAnim instanceof SimpleItemAnimator) {
                ((SimpleItemAnimator) rvAnim).setSupportsChangeAnimations(false);
            }

            // PERF/UX: newest comments render at the TOP now (see
            // applyFilterAndSort()), so older ones live further DOWN the
            // list — page the next older batch in as the user nears the
            // BOTTOM of the currently-loaded window, not the top. See
            // maybeLoadOlderComments().
            rvComments.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    if (dy <= 0) return; // only care about scrolling DOWN
                    LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                    if (lm != null && adapter != null
                            && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 5) {
                        maybeLoadOlderComments();
                    }
                }
            });

            // v2 (velocity-based avatar prefetch): measures scroll speed the
            // same way FollowersListActivity/ChatsFragment do (px moved / ms
            // elapsed since the last scroll callback) and hands it to
            // ReelCommentsAdapter#prefetchAvatarsFrom for the rows just past
            // the last visible one — fast fling skips prefetch entirely,
            // slow scroll warms several rows ahead. Separate listener from
            // the pagination one above so a change to either never risks
            // breaking the other.
            rvComments.addOnScrollListener(new RecyclerView.OnScrollListener() {
                private long lastTimeMs = 0L;

                @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    long dt = lastTimeMs == 0L ? 0L : (now - lastTimeMs);
                    float velocity = (dt > 0) ? Math.abs(dy) / (float) dt : 0f;
                    lastTimeMs = now;

                    LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                    if (lm == null || adapter == null) return;
                    int lastVisible = dy >= 0
                        ? lm.findLastVisibleItemPosition()
                        : lm.findFirstVisibleItemPosition();
                    if (lastVisible < 0) return;

                    adapter.prefetchAvatarsFrom(requireContext(), lastVisible + 1, velocity);
                }
            });

            attachSwipeToReply(rvComments);
        }
    }

    // ── One-time "swipe to reply" onboarding hint ────────────────────────────
    // Shown exactly once, ever, the first time this device opens ANY reel's
    // comment section — not per-reel — so returning users never see it
    // again. A small floating pill plus a two-pulse "peek" of the top
    // comment row sliding right and springing back, so the gesture itself
    // is demonstrated, not just described in text.

    private static final String PREF_SEEN_SWIPE_HINT = "seen_swipe_reply_hint";

    private void maybeShowSwipeReplyHint() {
        if (layoutSwipeHint == null || rvComments == null || adapter == null) return;
        if (adapter.getItemCount() == 0 || !isAdded()) return;

        android.content.SharedPreferences prefs = requireContext()
            .getSharedPreferences("reel_comment_drafts", Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_SEEN_SWIPE_HINT, false)) return;
        prefs.edit().putBoolean(PREF_SEEN_SWIPE_HINT, true).apply();

        layoutSwipeHint.setAlpha(0f);
        layoutSwipeHint.setVisibility(View.VISIBLE);
        layoutSwipeHint.animate().alpha(1f).setDuration(250).start();

        // Small delay so the RecyclerView has definitely laid out row 0
        // before we go looking for its ViewHolder to animate.
        rvComments.postDelayed(() -> {
            if (!isAdded() || rvComments == null) return;
            RecyclerView.ViewHolder vh = rvComments.findViewHolderForAdapterPosition(0);
            if (vh == null) return;
            View item = vh.itemView;
            int peekPx = dpToPx(46);

            item.animate()
                .translationX(peekPx)
                .setStartDelay(150)
                .setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> item.animate()
                    .translationX(0)
                    .setDuration(340)
                    .setInterpolator(new android.view.animation.OvershootInterpolator())
                    .withEndAction(() -> {
                        // Second, smaller pulse — reads as "this is a
                        // repeatable gesture", not a one-off glitch.
                        item.animate()
                            .translationX(peekPx / 2)
                            .setStartDelay(260)
                            .setDuration(200)
                            .withEndAction(() -> item.animate()
                                .translationX(0)
                                .setDuration(260)
                                .start())
                            .start();
                    })
                    .start())
                .start();
        }, 350);

        layoutSwipeHint.postDelayed(() -> {
            if (layoutSwipeHint == null) return;
            layoutSwipeHint.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> {
                    if (layoutSwipeHint != null) layoutSwipeHint.setVisibility(View.GONE);
                }).start();
        }, 3400);
    }

    // ── Swipe-to-reply (advanced gesture, Telegram/IG-style) ────────────────

    private void attachSwipeToReply(RecyclerView rv) {
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.RIGHT) {

            private final int maxSwipePx = dpToPx(72);
            /** True once this drag has crossed the full swipe distance —
             *  read in clearView() when the finger lifts. */
            private boolean triggered = false;

            @Override
            public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh,
                                   @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            // Deliberately unreachable (>1): the row must never actually be
            // "swiped away" by ItemTouchHelper's own dismiss animation —
            // we only use the drag distance as a gesture signal and always
            // let the row spring back via clearView()'s default recovery.
            @Override
            public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder vh) { return 2f; }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) { /* unused */ }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView r,
                                     @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                                     int actionState, boolean isCurrentlyActive) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    float clamped = Math.max(0f, Math.min(dX, maxSwipePx));
                    float progress = clamped / maxSwipePx;
                    boolean nowTriggered = progress >= 1f;

                    // Haptic "tick" fires exactly once, right as the drag
                    // crosses the commit threshold — not every frame — so
                    // releasing feels like confirming a deliberate action,
                    // the same cue Telegram/iOS Mail give on their swipe
                    // actions, instead of the gesture just silently working.
                    if (nowTriggered && !triggered) {
                        vh.itemView.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    triggered = nowTriggered;

                    View item = vh.itemView;
                    if (progress > 0.05f) {
                        int cx = item.getLeft() + dpToPx(28);
                        int cy = item.getTop() + item.getHeight() / 2;

                        // Circular brand-tinted chip behind the icon — grows
                        // and solidifies to full opacity as the gesture
                        // arms, then the icon itself pops slightly larger,
                        // so there's a clear "this is about to fire" signal
                        // before the user even lifts their finger.
                        float chipProgress = Math.min(1f, progress * 1.3f);
                        int chipRadius = (int) (dpToPx(13) + dpToPx(5) * chipProgress);
                        android.graphics.Paint chipPaint =
                            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                        chipPaint.setColor(androidx.core.content.ContextCompat.getColor(
                            requireContext(), R.color.brand_primary));
                        chipPaint.setAlpha(triggered ? 255 : (int) (170 * chipProgress));
                        c.drawCircle(cx, cy, chipRadius, chipPaint);

                        android.graphics.drawable.Drawable icon =
                            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_reply);
                        if (icon != null) {
                            android.graphics.drawable.Drawable tinted = icon.mutate();
                            tinted.setTint(Color.WHITE);
                            tinted.setAlpha((int) (255 * Math.min(1f, progress * 1.6f)));
                            int iconSize = triggered ? dpToPx(20) : dpToPx(17);
                            tinted.setBounds(cx - iconSize / 2, cy - iconSize / 2,
                                              cx + iconSize / 2, cy + iconSize / 2);
                            tinted.draw(c);
                        }
                    }
                    super.onChildDraw(c, r, vh, clamped, dY, actionState, isCurrentlyActive);
                } else {
                    super.onChildDraw(c, r, vh, dX, dY, actionState, isCurrentlyActive);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(r, vh);
                if (!triggered) return;
                triggered = false;
                int pos = vh.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || adapter == null) return;
                ReelComment c = adapter.getComment(pos);
                // Posted rather than called inline: clearView() fires while
                // the row's spring-back animation/touch handling is still
                // wrapping up, and starting the reply (focus + keyboard)
                // synchronously here was landing too early on some devices
                // — the keyboard just never opened. Posting lets this frame
                // finish first.
                if (c != null) { ReelComment fc = c; r.post(() -> startReply(fc)); }
            }
        };
        new ItemTouchHelper(callback).attachToRecyclerView(rv);
    }

    // ── Sort chips ────────────────────────────────────────────────────────────

    private void setupSortChips() {
        if (chipNewest == null || chipTop == null) return;

        chipNewest.setOnClickListener(v -> {
            if (sortByTop) {
                sortByTop = false;
                updateSortChipUI();
                applyFilterAndSort();
            }
        });

        chipTop.setOnClickListener(v -> {
            if (!sortByTop) {
                sortByTop = true;
                updateSortChipUI();
                applyFilterAndSort();
            }
        });
    }

    private void updateSortChipUI() {
        if (chipNewest == null || chipTop == null) return;
        chipNewest.setBackgroundResource(sortByTop
            ? R.drawable.bg_sort_chip : R.drawable.bg_sort_chip_selected);
        chipNewest.setTextColor(getResources().getColor(
            sortByTop ? android.R.color.darker_gray : R.color.brand_primary));

        chipTop.setBackgroundResource(sortByTop
            ? R.drawable.bg_sort_chip_selected : R.drawable.bg_sort_chip);
        chipTop.setTextColor(getResources().getColor(
            sortByTop ? R.color.brand_primary : android.R.color.darker_gray));
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void setupSearch() {
        if (btnSearchToggle == null) return;

        btnSearchToggle.setOnClickListener(v -> {
            searchActive = !searchActive;
            if (layoutSearch != null)
                layoutSearch.setVisibility(searchActive ? View.VISIBLE : View.GONE);
            if (searchActive && etSearch != null) {
                etSearch.requestFocus();
                showKeyboard(etSearch);
            } else {
                searchQuery = "";
                if (etSearch != null) etSearch.setText("");
                applyFilterAndSort();
            }
        });

        if (btnCloseSearch != null) {
            btnCloseSearch.setOnClickListener(v -> {
                searchActive = false;
                searchQuery  = "";
                if (layoutSearch != null) layoutSearch.setVisibility(View.GONE);
                if (etSearch    != null) etSearch.setText("");
                applyFilterAndSort();
            });
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    searchQuery = s.toString().trim().toLowerCase();
                    applyFilterAndSort();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    // ── Character counter ─────────────────────────────────────────────────────

    private void setupCharCounter() {
        if (etComment == null || tvCharCount == null) return;

        etComment.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                int len = s.length();
                tvCharCount.setVisibility(len > 0 ? View.VISIBLE : View.GONE);
                tvCharCount.setText(len + "/" + MAX_COMMENT_LENGTH);
                int warnColor = len >= MAX_COMMENT_LENGTH - 30
                    ? getResources().getColor(android.R.color.holo_red_light)
                    : getResources().getColor(R.color.text_muted);
                tvCharCount.setTextColor(warnColor);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    // ── @mention candidate registry ─────────────────────────────────────────

    private void registerMentionCandidate(@Nullable String uid, @Nullable String name, @Nullable String photoUrl) {
        if (uid == null || uid.isEmpty() || name == null || name.isEmpty()) return;
        if (uid.equals(myUid)) return; // can't tag yourself
        mentionCandidates.put(name.toLowerCase(java.util.Locale.ROOT),
            new MentionCandidate(uid, name, photoUrl));
    }

    // ── @mention autocomplete UI ─────────────────────────────────────────────

    private void setupMentionAutocomplete() {
        if (etComment == null) return;
        etComment.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppressTextWatcher) return;
                handleMentionQuery(s.toString(), etComment.getSelectionStart());
            }
        });
    }

    /** Looks backward from the cursor for an unfinished "@token" and shows
     *  matching suggestions, or hides the strip if the cursor isn't inside
     *  one right now. */
    private void handleMentionQuery(String text, int cursor) {
        if (cursor < 0 || cursor > text.length()) { hideMentionSuggestions(); return; }

        int at = -1;
        for (int i = cursor - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == '@') { at = i; break; }
            if (Character.isWhitespace(ch)) break;
        }
        if (at < 0) { hideMentionSuggestions(); return; }

        String query = text.substring(at + 1, cursor).toLowerCase(java.util.Locale.ROOT);
        showMentionSuggestions(query, at, cursor);
    }

    private void showMentionSuggestions(String query, int atIndex, int cursor) {
        if (rvMentionSuggestions == null || mentionAdapter == null) return;

        List<MentionCandidate> matches = new ArrayList<>();
        for (MentionCandidate c : mentionCandidates.values()) {
            if (c.name.toLowerCase(java.util.Locale.ROOT).startsWith(query)) matches.add(c);
            if (matches.size() >= 8) break;
        }

        if (matches.isEmpty()) { hideMentionSuggestions(); return; }

        // Captured here so the adapter's click callback (fired later, async
        // relative to typing) knows exactly which "@token" span to replace —
        // see insertMention() and the listener wired in bindViews().
        pendingMentionAt     = atIndex;
        pendingMentionCursor = cursor;

        mentionAdapter.submitList(matches);
        rvMentionSuggestions.setVisibility(View.VISIBLE);
    }

    private void hideMentionSuggestions() {
        if (rvMentionSuggestions != null) rvMentionSuggestions.setVisibility(View.GONE);
        if (mentionAdapter != null) mentionAdapter.submitList(null);
    }

    private void insertMention(String uid, String name, int atIndex, int cursor) {
        if (etComment == null) return;
        pendingMentions.put(uid, name);

        String current = etComment.getText().toString();
        String before = current.substring(0, atIndex);
        String after  = cursor <= current.length() ? current.substring(cursor) : "";
        String replacement = "@" + name + " ";

        suppressTextWatcher = true;
        etComment.setText(before + replacement + after);
        etComment.setSelection(before.length() + replacement.length());
        suppressTextWatcher = false;

        hideMentionSuggestions();
    }

    /** Scans the final text for every pending-mention name still present and
     *  returns only those (a chip picked then deleted shouldn't notify). */
    private Map<String, String> resolveMentionsInText(String finalText) {
        Map<String, String> resolved = new HashMap<>();
        if (finalText == null || pendingMentions.isEmpty()) return resolved;
        String lower = finalText.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, String> e : pendingMentions.entrySet()) {
            String token = "@" + e.getValue().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains(token)) resolved.put(e.getKey(), e.getValue());
        }
        return resolved;
    }

    // ── Quick emoji reply row ─────────────────────────────────────────────────
    // Instagram-style strip of common emojis sitting right above the text
    // field. Tapping one INSERTS it at the cursor (doesn't auto-send) — a
    // shortcut for typing, distinct from postReaction()'s long-press
    // "react to a comment" feature elsewhere in this file.

    private static final String[] QUICK_EMOJIS =
        { "❤️", "🙌", "🔥", "👏", "😂", "😍", "😮", "😢", "🙏", "💯" };

    private void setupQuickEmojiRow() {
        if (containerQuickEmojis == null) return;
        containerQuickEmojis.removeAllViews();

        int chipPad   = dpToPx(8);
        int chipMargin = dpToPx(2);
        android.util.TypedValue outValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, outValue, true);

        for (String emoji : QUICK_EMOJIS) {
            TextView chip = new TextView(requireContext());
            chip.setText(emoji);
            chip.setTextSize(22f);
            chip.setPadding(chipPad, chipPad, chipPad, chipPad);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setBackgroundResource(outValue.resourceId);
            chip.setClickable(true);
            chip.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(chipMargin, 0, chipMargin, 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> insertQuickEmoji(emoji, chip));
            containerQuickEmojis.addView(chip);
        }
    }

    /** Inserts an emoji at the current cursor position (replacing any
     *  selection), keeps the field focused, and makes sure the keyboard
     *  stays open — tapping a quick emoji should feel exactly like typing
     *  it, not like a separate action that closes the field. */
    private void insertQuickEmoji(String emoji, View sourceChip) {
        if (etComment == null) return;

        // Small tactile "pop" on the chip itself + a light haptic tick —
        // same confirmation language as the swipe-to-reply gesture, so
        // quick taps here feel equally responsive.
        sourceChip.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        sourceChip.animate().scaleX(1.3f).scaleY(1.3f).setDuration(80)
            .withEndAction(() -> sourceChip.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
            .start();

        Editable text = etComment.getText();
        int start = Math.max(0, etComment.getSelectionStart());
        int end   = Math.max(start, etComment.getSelectionEnd());
        if (text == null) {
            etComment.setText(emoji);
            etComment.setSelection(emoji.length());
        } else {
            start = Math.min(start, text.length());
            end   = Math.min(end,   text.length());
            text.replace(start, end, emoji);
            etComment.setSelection(start + emoji.length());
        }
        etComment.requestFocus();
        showKeyboard(etComment);
    }

    // ── "New comments" pill ──────────────────────────────────────────────────
    // Newest comments render at the TOP of the list now (see
    // applyFilterAndSort()), so "new comment arrived" means position 0, not
    // the last position — the pill and auto-scroll below jump to the TOP.

    private void setupNewCommentsPill() {
        if (pillNewComments != null) {
            pillNewComments.setOnClickListener(v -> {
                pendingNewComments = 0;
                pillNewComments.setVisibility(View.GONE);
                if (rvComments != null) rvComments.scrollToPosition(0);
            });
        }
    }

    /** True when the user is already looking at (or very near) the top of
     *  the list, i.e. the newest comment — the "stay pinned to latest"
     *  case, same idea WhatsApp/Instagram use for "stay pinned to bottom"
     *  in a bottom-anchored chat, just flipped for our top-anchored feed. */
    private boolean isNearTop() {
        if (rvComments == null) return true;
        LinearLayoutManager lm = (LinearLayoutManager) rvComments.getLayoutManager();
        if (lm == null) return true;
        return lm.findFirstVisibleItemPosition() <= 2;
    }

    // ── Draft persistence ────────────────────────────────────────────────────

    private String draftPrefKey() {
        return "reel_comment_draft_" + reelId;
    }

    private void saveDraft() {
        if (etComment == null || reelId.isEmpty()) return;
        try {
            String text = etComment.getText().toString();
            android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("reel_comment_drafts", Context.MODE_PRIVATE);
            if (TextUtils.isEmpty(text)) prefs.edit().remove(draftPrefKey()).apply();
            else prefs.edit().putString(draftPrefKey(), text).apply();
        } catch (Exception ignored) {}
    }

    private void restoreDraft() {
        if (etComment == null || reelId.isEmpty()) return;
        try {
            android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("reel_comment_drafts", Context.MODE_PRIVATE);
            String draft = prefs.getString(draftPrefKey(), "");
            if (!TextUtils.isEmpty(draft)) {
                suppressTextWatcher = true;
                etComment.setText(draft);
                etComment.setSelection(draft.length());
                suppressTextWatcher = false;
            }
        } catch (Exception ignored) {}
    }

    private void clearDraft() {
        if (reelId.isEmpty()) return;
        try {
            requireContext().getSharedPreferences("reel_comment_drafts", Context.MODE_PRIVATE)
                .edit().remove(draftPrefKey()).apply();
        } catch (Exception ignored) {}
    }

    // ── Highlight logic ─────────────────────────────────────────────────────────

    private void checkAndHighlightComment() {
        if (highlightCommentId.isEmpty() || allComments.isEmpty()) return;
        for (int i = 0; i < allComments.size(); i++) {
            if (highlightCommentId.equals(allComments.get(i).commentId)) {
                final int pos = i;
                rvComments.post(() -> {
                    rvComments.scrollToPosition(pos);
                    // A second post lets layout finish placing the row
                    // before we look it up for the flash animation.
                    rvComments.post(() -> flashHighlightedRow(pos));
                    highlightCommentId = ""; // Only once
                });
                break;
            }
        }
    }

    /** Brief background pulse on the deep-linked comment so it's obvious
     *  which row the user was sent to, fading back to transparent. */
    private void flashHighlightedRow(int pos) {
        if (rvComments == null) return;
        RecyclerView.LayoutManager lm = rvComments.getLayoutManager();
        if (lm == null) return;
        View row = lm.findViewByPosition(pos);
        if (row == null) return;

        int highlightColor = 0x335B5BF6; // translucent brand tint
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), highlightColor, Color.TRANSPARENT);
        anim.setDuration(900);
        anim.addUpdateListener(a -> row.setBackgroundColor((int) a.getAnimatedValue()));
        anim.start();
    }

    private void applyFilterAndSort() {
        List<ReelComment> filtered = new ArrayList<>();

        for (ReelComment c : allComments) {
            if (c.uid != null && blockedUids.contains(c.uid)) continue; // blocked user's comment, hide it
            if (searchQuery.isEmpty()) {
                filtered.add(c);
            } else {
                boolean nameMatch = c.ownerName != null
                    && c.ownerName.toLowerCase().contains(searchQuery);
                boolean textMatch = c.text != null
                    && c.text.toLowerCase().contains(searchQuery);
                if (nameMatch || textMatch) filtered.add(c);
            }
        }

        // BUG FIX: sort BEFORE submitting, in one pass. The old code called
        // adapter.setComments(filtered) — insertion order (oldest-first,
        // since Firebase's initial burst arrives ascending by key) — and
        // THEN adapter.sortByTop()/sortByNewest() as a SEPARATE call. Each
        // is a separate AsyncListDiffer.submitList(), and the second one
        // reads items() before the first diff has necessarily finished, so
        // the RecyclerView could paint the unsorted (oldest-on-top) list
        // for a frame and then "snap" to the sorted (newest-on-top) one —
        // exactly the open-time reorder flicker this was reported as.
        // Sorting first and submitting once means there is only ever ONE
        // list to diff against, so the first paint is already correct.
        Collections.sort(filtered, sortByTop
            ? ReelCommentsAdapter.TOP_FIRST : ReelCommentsAdapter.NEWEST_FIRST);

        adapter.setComments(filtered);

        updateCountHeader();
        showEmpty(filtered.isEmpty());
        checkAndHighlightComment();
        maybeAutoFillViewport();
    }

    /** BUG FIX: pagination was purely scroll-delta-triggered (see the
     *  OnScrollListener in setupAdapter()), which silently never fires when
     *  the currently-loaded batch is short enough to fit entirely on
     *  screen — nothing to scroll means no onScrolled(dy>0) event, ever,
     *  even though hasMoreOlder is still true and there ARE more comments
     *  to page in. That's why "scroll to load more" could still do nothing
     *  no matter how much you tried to scroll. This runs after every list
     *  update and proactively keeps paging older comments in — same as the
     *  scroll trigger, just also covering the "nothing to scroll yet"
     *  case — until either the viewport is actually full (so a real scroll
     *  gesture can take over) or the thread genuinely has no more older
     *  comments left. */
    private void maybeAutoFillViewport() {
        if (rvComments == null) return;
        rvComments.post(() -> {
            if (rvComments == null || !isAdded()) return;
            if (!rvComments.canScrollVertically(1) && hasMoreOlder && !loadingOlder) {
                maybeLoadOlderComments();
            }
        });
    }

    /** Reels profile photo load karo (reels/users/{uid}) — chat profile nahi. */
    private void loadMyPhoto() {
        if (myUid.isEmpty()) return;
        FirebaseDatabase.getInstance()
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    String thumb = s.child("thumbUrl").getValue(String.class);
                    String photo = s.child("photoUrl").getValue(String.class);
                    String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (p != null && !p.isEmpty()) myPhoto = p;
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Load comments (paginated ChildEventListener — see PAGE_SIZE note) ────

    // ── Live total comments count (header) ──────────────────────────────────
    // Separate from allComments/pagination on purpose — the header must
    // reflect the TRUE total (reels/{reelId}/commentsCount, kept in sync by
    // incrementCommentsCount()'s transaction for every viewer), not just
    // how many rows this device happens to have loaded so far.

    private void listenCommentsCount() {
        if (reelId.isEmpty()) return;
        commentsCountRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reels").child(reelId).child("commentsCount");
        commentsCountListener = commentsCountRef.addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Integer v = s.getValue(Integer.class);
                totalCommentsCount = Math.max(0, v != null ? v : 0);
                updateCountHeader();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void loadComments() {
        commentsRef   = FirebaseUtils.getReelCommentsRef(reelId);
        // PERF: live-listen to only the most recent PAGE_SIZE comments —
        // older ones are paged in on demand by maybeLoadOlderComments().
        commentsQuery = commentsRef.orderByKey().limitToLast(PAGE_SIZE);

        commentsListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot s, @Nullable String prev) {
                ReelComment c = safeParseComment(s);
                if (c == null || TextUtils.isEmpty(c.text)) return;
                if (!loadedCommentIds.add(c.commentId)) return; // dup / window re-add guard
                if (oldestLoadedKey == null || c.commentId.compareTo(oldestLoadedKey) < 0) {
                    oldestLoadedKey = c.commentId;
                }
                registerMentionCandidate(c.uid, c.ownerName, c.ownerPhoto);
                boolean wasNearTop = isNearTop();
                allComments.add(c);
                if (!initialLoadSettled || wasNearTop) {
                    pendingAutoScroll = true;
                } else if (!c.uid.equals(myUid)) {
                    pendingNewComments++;
                }
                // PERF: don't rebuild the whole filtered+sorted list on every
                // single event — a burst of N adds (e.g. initial page load)
                // now costs one refresh instead of N.
                requestRefresh();
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot s, @Nullable String prev) {
                ReelComment updated = safeParseComment(s);
                if (updated == null) return;
                registerMentionCandidate(updated.uid, updated.ownerName, updated.ownerPhoto);
                for (int i = 0; i < allComments.size(); i++) {
                    if (allComments.get(i).commentId != null
                        && allComments.get(i).commentId.equals(updated.commentId)) {
                        allComments.set(i, updated);
                        break;
                    }
                }
                requestRefresh();
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot s) {
                // NOTE: this also fires when a comment simply ages out of the
                // live PAGE_SIZE window (a newer one pushed it out) — not just
                // on a real delete, since Firebase can't tell us which. With
                // PAGE_SIZE=40 that only happens once 40 newer comments land
                // during a single viewing session, an acceptable trade-off
                // for not ever downloading the full thread.
                String removedId = s.getKey();
                if (removedId == null) return;
                loadedCommentIds.remove(removedId);
                for (int i = 0; i < allComments.size(); i++) {
                    if (removedId.equals(allComments.get(i).commentId)) {
                        allComments.remove(i);
                        break;
                    }
                }
                requestRefresh();
            }

            @Override public void onChildMoved(@NonNull DataSnapshot s, @Nullable String prev) {}
            @Override public void onCancelled(@NonNull DatabaseError e) { showEmpty(true); }
        };

        commentsQuery.addChildEventListener(commentsListener);
    }

    @Nullable
    private ReelComment safeParseComment(DataSnapshot s) {
        try {
            ReelComment c = s.getValue(ReelComment.class);
            if (c == null) return null;
            if (c.commentId == null) c.commentId = s.getKey() != null ? s.getKey() : "";
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private void autoScrollIfAtTop() {
        if (rvComments == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) rvComments.getLayoutManager();
        if (lm == null) return;
        if (lm.findFirstVisibleItemPosition() <= 2) {
            rvComments.scrollToPosition(0);
        }
    }

    // ── Keyboard GIF (Gboard's built-in GIF search) ─────────────────────────
    // No separate GIF picker/API — this just accepts whatever content:// uri
    // the user's own keyboard (e.g. Google Keyboard's GIF tab) delivers via
    // the standard commitContent InputConnection extension, then uploads it
    // through the same pipeline as an attached gallery photo below.

    private void onKeyboardGifReceived(InputContentInfoCompat contentInfo) {
        if (contentInfo == null) return;
        try {
            contentInfo.requestPermission();
        } catch (Exception ignored) {}

        Uri uri = contentInfo.getContentUri();
        pickedImageUri = uri;
        uploadedImageUrl = null;
        uploadingImage = true;

        if (layoutImagePreview != null) layoutImagePreview.setVisibility(View.VISIBLE);
        if (progressImage != null) progressImage.setVisibility(View.VISIBLE);
        if (ivImagePreview != null) {
            // Glide animates GIFs automatically here (no .asBitmap() call),
            // so the preview and the eventual comment bubble both play it.
            Glide.with(this).load(uri).into(ivImagePreview);
        }

        try {
            CloudinaryUploader.upload(requireContext(), uri, "callx/reel_comments_gif", "image",
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result result) {
                        contentInfo.releasePermission();
                        if (!isAdded()) return;
                        uploadingImage = false;
                        if (pickedImageUri == null || !pickedImageUri.equals(uri)) return;
                        uploadedImageUrl = result.secureUrl;
                        if (progressImage != null) progressImage.setVisibility(View.GONE);
                    }

                    @Override public void onError(String message) {
                        contentInfo.releasePermission();
                        if (!isAdded()) return;
                        uploadingImage = false;
                        Toast.makeText(requireContext(), "GIF upload failed", Toast.LENGTH_SHORT).show();
                        clearPickedImage();
                    }
                });
        } catch (Exception e) {
            contentInfo.releasePermission();
            uploadingImage = false;
            clearPickedImage();
        }
    }

    // ── Comment photo attachment (Instagram-style) ──────────────────────────

    private void onImagePicked(Uri uri) {
        pickedImageUri = uri;
        uploadedImageUrl = null;
        uploadingImage = true;

        if (layoutImagePreview != null) layoutImagePreview.setVisibility(View.VISIBLE);
        if (progressImage != null) progressImage.setVisibility(View.VISIBLE);
        if (ivImagePreview != null) {
            Glide.with(this).load(uri).into(ivImagePreview);
        }

        try {
            CloudinaryUploader.upload(requireContext(), uri, "callx/reel_comments", "image",
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result result) {
                        if (!isAdded()) return;
                        uploadingImage = false;
                        // User may have removed the preview while this was
                        // still uploading — don't resurrect it.
                        if (pickedImageUri == null || !pickedImageUri.equals(uri)) return;
                        uploadedImageUrl = result.secureUrl;
                        if (progressImage != null) progressImage.setVisibility(View.GONE);
                    }

                    @Override public void onError(String message) {
                        if (!isAdded()) return;
                        uploadingImage = false;
                        Toast.makeText(requireContext(), "Photo upload failed", Toast.LENGTH_SHORT).show();
                        clearPickedImage();
                    }
                });
        } catch (Exception e) {
            uploadingImage = false;
            clearPickedImage();
        }
    }

    private void clearPickedImage() {
        pickedImageUri = null;
        uploadedImageUrl = null;
        uploadingImage = false;
        if (layoutImagePreview != null) layoutImagePreview.setVisibility(View.GONE);
        if (progressImage != null) progressImage.setVisibility(View.GONE);
        if (ivImagePreview != null) ivImagePreview.setImageDrawable(null);
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private void onSendClicked() {
        if (uploadingImage) {
            Toast.makeText(requireContext(), "Photo uploading… please wait", Toast.LENGTH_SHORT).show();
            return;
        }
        // ANTI-SPAM FIX: nothing previously stopped rapid-fire tapping of
        // send — each tap fired a full postComment()/postReply() Firebase
        // write with no gap. A short client-side cooldown is a cheap first
        // line of defense (server-side rules are the real backstop).
        long now = System.currentTimeMillis();
        if (now - lastCommentPostAt < COMMENT_COOLDOWN_MS) {
            Toast.makeText(requireContext(), "You're commenting too fast — slow down a bit",
                Toast.LENGTH_SHORT).show();
            return;
        }
        lastCommentPostAt = now;
        if (replyingToComment != null) postReply();
        else postComment();
    }

    private void postComment() {
        String text = getInputText();
        if (text == null) return;

        DatabaseReference ref = commentsRef != null
            ? commentsRef
            : FirebaseUtils.getReelCommentsRef(reelId);
        String key = ref.push().getKey();
        if (key == null) return;

        // ── Local-first: build the comment object and show it immediately.
        // Chat's postComment previously called ref.child(key).setValue(data)
        // fire-and-forget with no success/failure listener at all — if the
        // write failed (offline, blocked, permission denied) the comment
        // silently never appeared and the user only saw a one-off Toast
        // with nothing left in the list to act on. Now the bubble shows
        // instantly (like WhatsApp's local-first media send flow) and
        // reconciles once Firebase actually confirms the write.
        long timestamp = System.currentTimeMillis();
        ReelComment local = new ReelComment(key, myUid, myName, myPhoto, text, timestamp);
        if (uploadedImageUrl != null && !uploadedImageUrl.isEmpty()) {
            local.imageUrl = uploadedImageUrl;
        }
        Map<String, String> mentions = resolveMentionsInText(text);
        if (!mentions.isEmpty()) local.mentions = mentions;
        local.sendState = ReelComment.SEND_STATE_SENDING;

        loadedCommentIds.add(key); // dup guard: our own onChildAdded echo will skip this id
        allComments.add(local);
        pendingAutoScroll = true;
        applyFilterAndSort();

        clearInput();
        clearDraft();
        clearPickedImage();
        pendingMentions.clear();

        sendCommentToFirebase(ref, key, local);
    }

    /** Builds the Firebase write payload for a local ReelComment and fires
     *  it off, flipping the row's sendState based on the real result
     *  instead of assuming success the moment setValue() is called. */
    private void sendCommentToFirebase(DatabaseReference ref, String key, ReelComment local) {
        Map<String, Object> data = new HashMap<>();
        data.put("commentId",  key);
        data.put("uid",        local.uid);
        data.put("ownerName",  local.ownerName);
        data.put("ownerPhoto", local.ownerPhoto);
        data.put("text",       local.text);
        data.put("timestamp",  local.timestamp);
        data.put("likesCount", 0);
        data.put("replyCount", 0);
        data.put("isPinned",   false);
        data.put("isEdited",   false);
        if (local.imageUrl != null && !local.imageUrl.isEmpty()) {
            data.put("imageUrl", local.imageUrl);
        }
        if (local.mentions != null && !local.mentions.isEmpty()) {
            data.put("mentions", local.mentions);
        }

        try {
            ref.child(key).setValue(data)
                .addOnSuccessListener(a -> {
                    if (!isAdded()) return;
                    local.sendState = null; // back to normal — confirmed sent
                    incrementCommentsCount(+1);
                    applyFilterAndSort();

                    ReelCommentNotifWorker.enqueue(
                        requireContext(), reelId, reelUid, myUid, myName, key, local.text);
                    if (local.mentions != null) {
                        for (Map.Entry<String, String> e : local.mentions.entrySet()) {
                            if (e.getKey().equals(myUid) || e.getKey().equals(reelUid)) continue;
                            ReelCommentNotifWorker.enqueueMention(
                                requireContext(), reelId, e.getKey(), myUid, myName, key, local.text);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    local.sendState = ReelComment.SEND_STATE_FAILED;
                    applyFilterAndSort();
                    Toast.makeText(requireContext(),
                        "Comment not sent — check your connection", Toast.LENGTH_SHORT).show();
                });
        } catch (Exception e) {
            local.sendState = ReelComment.SEND_STATE_FAILED;
            applyFilterAndSort();
        }
    }

    /** Tap-to-retry on a failed comment row: re-sends with the SAME push
     *  key (no duplicate comment created) and flips it back to "sending". */
    private void retryComment(ReelComment comment) {
        if (comment == null || comment.commentId == null) return;
        if (!ReelComment.SEND_STATE_FAILED.equals(comment.sendState)) return;
        comment.sendState = ReelComment.SEND_STATE_SENDING;
        applyFilterAndSort();

        DatabaseReference ref = commentsRef != null
            ? commentsRef
            : FirebaseUtils.getReelCommentsRef(reelId);
        sendCommentToFirebase(ref, comment.commentId, comment);
    }

    private void postReply() {
        String text = getInputText();
        if (text == null) return;
        ReelComment parent = replyingToComment;

        try {
            DatabaseReference repliesRef = FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("reelCommentReplies")
                .child(reelId)
                .child(parent.commentId);

            String key = repliesRef.push().getKey();
            if (key == null) return;

            ReelReply mention = replyingToReplyMention;

            Map<String, Object> data = new HashMap<>();
            data.put("replyId",         key);
            data.put("parentCommentId", parent.commentId);
            data.put("uid",             myUid);
            data.put("ownerName",       myName);
            data.put("ownerPhoto",      myPhoto);
            data.put("text",            text);
            data.put("timestamp",       System.currentTimeMillis());
            data.put("likesCount",      0);
            if (mention != null) {
                data.put("mentionUid",  mention.uid);
                data.put("mentionName", mention.ownerName);
            }
            repliesRef.child(key).setValue(data);

            FirebaseUtils.getReelCommentsRef(reelId)
                .child(parent.commentId).child("replyCount")
                .runTransaction(new Transaction.Handler() {
                    @NonNull @Override
                    public Transaction.Result doTransaction(@NonNull MutableData d) {
                        Integer v = d.getValue(Integer.class);
                        d.setValue(v != null ? v + 1 : 1);
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(@Nullable DatabaseError e,
                                                     boolean b, @Nullable DataSnapshot s) {}
                });

            clearInput();
            clearDraft();
            cancelReply();

            if (!parent.uid.equals(myUid)) {
                ReelCommentNotifWorker.enqueueReply(
                    requireContext(), reelId, parent.uid, myUid, myName, key, text);
            }
            if (mention != null && mention.uid != null
                    && !mention.uid.equals(myUid) && !mention.uid.equals(parent.uid)) {
                ReelCommentNotifWorker.enqueueReply(
                    requireContext(), reelId, mention.uid, myUid, myName, key, text);
            }

            Map<String, String> extraMentions = resolveMentionsInText(text);
            for (Map.Entry<String, String> e : extraMentions.entrySet()) {
                String uid = e.getKey();
                if (uid.equals(myUid) || uid.equals(parent.uid)) continue;
                if (mention != null && uid.equals(mention.uid)) continue;
                ReelCommentNotifWorker.enqueueMention(
                    requireContext(), reelId, uid, myUid, myName, key, text);
            }
            pendingMentions.clear();

        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to post reply", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Like ──────────────────────────────────────────────────────────────────

    private void toggleLike(ReelComment comment, int position) {
        if (myUid.isEmpty()) {
            Toast.makeText(requireContext(), "Please login to like", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean currentlyLiked = comment.isLikedBy(myUid);
        DatabaseReference commentRef = FirebaseUtils.getReelCommentsRef(reelId)
            .child(comment.commentId);

        // Optimistic local flip — the heart/count update instantly instead
        // of waiting on the Firebase round trip; onChildChanged reconciles
        // the real value moments later (a same-value re-render is a no-op
        // once AsyncListDiffer sees the content is identical).
        if (comment.likedBy == null) comment.likedBy = new HashMap<>();
        if (currentlyLiked) comment.likedBy.remove(myUid);
        else comment.likedBy.put(myUid, true);
        comment.likesCount = Math.max(0, comment.likesCount + (currentlyLiked ? -1 : 1));
        if (adapter != null) adapter.notifyLikeChanged(comment.commentId);

        commentRef.child("likedBy").child(myUid)
            .setValue(currentlyLiked ? null : true);

        commentRef.child("likesCount").runTransaction(new Transaction.Handler() {
            @NonNull @Override
            public Transaction.Result doTransaction(@NonNull MutableData d) {
                Integer v = d.getValue(Integer.class);
                int cur = v != null ? v : 0;
                d.setValue(Math.max(0, currentlyLiked ? cur - 1 : cur + 1));
                return Transaction.success(d);
            }
            @Override public void onComplete(@Nullable DatabaseError e,
                                             boolean b, @Nullable DataSnapshot s) {}
        });

        if (!currentlyLiked && !comment.uid.equals(myUid)) {
            ReelCommentNotifWorker.enqueueLike(
                requireContext(), reelId, comment.uid, myUid, myName, comment.commentId);
        }
    }

    // ── Emoji reactions ───────────────────────────────────────────────────────

    private void postReaction(ReelComment comment, @Nullable String emoji, int position) {
        if (myUid.isEmpty()) return;

        DatabaseReference reactRef = FirebaseUtils.getReelCommentsRef(reelId)
            .child(comment.commentId)
            .child("reactions")
            .child(myUid);

        if (emoji == null) {
            reactRef.removeValue();
        } else {
            reactRef.setValue(emoji);
        }
    }

    // ── Edit comment ──────────────────────────────────────────────────────────

    private void showEditDialog(ReelComment comment, int position) {
        if (!myUid.equals(comment.uid)) return;

        EditText et = new EditText(requireContext());
        et.setText(comment.text);
        et.setMaxLines(5);
        et.setSelection(et.getText().length());
        int pad = dpToPx(16);
        et.setPadding(pad, pad, pad, pad);

        AlertDialogStyler.showRounded(new AlertDialog.Builder(requireContext())
            .setTitle("Edit comment")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String newText = et.getText().toString().trim();
                if (TextUtils.isEmpty(newText)) return;
                if (newText.equals(comment.text)) return;
                if (newText.length() > MAX_COMMENT_LENGTH) {
                    Toast.makeText(requireContext(), "Comment too long (max 300 chars)",
                        Toast.LENGTH_SHORT).show();
                    return;
                }

                DatabaseReference ref = FirebaseUtils.getReelCommentsRef(reelId)
                    .child(comment.commentId);
                Map<String, Object> updates = new HashMap<>();
                updates.put("text",     newText);
                updates.put("isEdited", true);
                updates.put("editedAt", System.currentTimeMillis());
                ref.updateChildren(updates);
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ── Pin comment ───────────────────────────────────────────────────────────

    private void togglePin(ReelComment comment) {
        if (!myUid.equals(reelUid)) {
            Toast.makeText(requireContext(), "Only the reel owner can pin comments",
                Toast.LENGTH_SHORT).show();
            return;
        }

        boolean newPinnedState = !comment.isPinned;

        if (newPinnedState) {
            for (ReelComment c : allComments) {
                if (c.isPinned && !c.commentId.equals(comment.commentId)) {
                    FirebaseUtils.getReelCommentsRef(reelId)
                        .child(c.commentId).child("isPinned").setValue(false);
                }
            }
        }

        FirebaseUtils.getReelCommentsRef(reelId)
            .child(comment.commentId).child("isPinned").setValue(newPinnedState);

        Toast.makeText(requireContext(),
            newPinnedState ? "Comment pinned" : "Comment unpinned",
            Toast.LENGTH_SHORT).show();
    }

    // ── Report comment ────────────────────────────────────────────────────────

    private void showReportDialog(ReelComment comment) {
        String[] reasons = {
            "Spam", "Hate speech", "Harassment", "Misinformation",
            "Nudity or sexual content", "Violence", "Other"
        };

        AlertDialogStyler.showRounded(new AlertDialog.Builder(requireContext())
            .setTitle("Report comment")
            .setItems(reasons, (d, which) -> {
                submitReport(comment, reasons[which]);
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    private void submitReport(ReelComment comment, String reason) {
        if (myUid.isEmpty()) return;
        Map<String, Object> report = new HashMap<>();
        report.put("reporterUid", myUid);
        report.put("reason",      reason);
        report.put("timestamp",   System.currentTimeMillis());
        report.put("commentText", comment.text);

        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reelCommentReports")
            .child(reelId)
            .child(comment.commentId)
            .child(myUid)
            .setValue(report)
            .addOnSuccessListener(a ->
                Toast.makeText(requireContext(), "Comment reported. Thank you.",
                    Toast.LENGTH_SHORT).show())
            .addOnFailureListener(e ->
                Toast.makeText(requireContext(), "Failed to report. Try again.",
                    Toast.LENGTH_SHORT).show());
    }

    // ── Reply UI ──────────────────────────────────────────────────────────────

    private void startReply(ReelComment comment) {
        replyingToComment = comment;
        replyingToReplyMention = null;
        clearPickedImage();
        if (btnAttachPhoto != null) btnAttachPhoto.setVisibility(View.GONE);
        String name = comment.ownerName != null ? comment.ownerName : "user";
        if (tvReplyingTo   != null) tvReplyingTo.setText("Replying to @" + name);
        if (barReplyingTo  != null) barReplyingTo.setVisibility(View.VISIBLE);
        if (etComment      != null) {
            etComment.setText("");
            etComment.setHint("Reply to @" + name + "…");
            etComment.requestFocus();
        }
        showKeyboard(etComment);
    }

    /** Reply-to-a-reply — Instagram flattens this into the SAME top-level
     *  parent's thread (no infinite nesting) but pre-fills "@name " and
     *  tags the reply's author so notifications/UI can reference them. */
    private void startReplyToReply(ReelComment parent, ReelReply reply) {
        replyingToComment = parent;
        replyingToReplyMention = reply;
        clearPickedImage();
        if (btnAttachPhoto != null) btnAttachPhoto.setVisibility(View.GONE);
        String name = reply.ownerName != null ? reply.ownerName : "user";
        if (tvReplyingTo   != null) tvReplyingTo.setText("Replying to @" + name);
        if (barReplyingTo  != null) barReplyingTo.setVisibility(View.VISIBLE);
        if (etComment      != null) {
            etComment.setHint("Reply to @" + name + "…");
            String prefill = "@" + name + " ";
            etComment.setText(prefill);
            etComment.setSelection(prefill.length());
            etComment.requestFocus();
        }
        showKeyboard(etComment);
    }

    private void cancelReply() {
        replyingToComment = null;
        replyingToReplyMention = null;
        if (barReplyingTo != null) barReplyingTo.setVisibility(View.GONE);
        if (etComment     != null) etComment.setHint("Write a comment…");
        if (btnAttachPhoto != null) btnAttachPhoto.setVisibility(View.VISIBLE);
    }

    private void loadRepliesInto(ReelComment parent,
                                 LinearLayout container, TextView tvToggle) {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reelCommentReplies")
            .child(reelId)
            .child(parent.commentId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (!isAdded()) return;
                    container.removeAllViews();
                    int count = 0;
                    for (DataSnapshot s : snapshot.getChildren()) {
                        try {
                            ReelReply r = s.getValue(ReelReply.class);
                            if (r == null || TextUtils.isEmpty(r.text)) continue;
                            if (r.uid != null && blockedUids.contains(r.uid)) continue; // blocked user's reply, hide it
                            if (r.replyId == null) r.replyId = s.getKey();
                            registerMentionCandidate(r.uid, r.ownerName, r.ownerPhoto);
                            View row = buildReplyRow(r, parent, container, tvToggle);
                            if (row != null) { container.addView(row); count++; }
                        } catch (Exception ignored) {}
                    }
                    container.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                    tvToggle.setText(count > 0 ? "Hide replies" : "No replies yet");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    tvToggle.setText("View " + parent.replyCount
                        + (parent.replyCount == 1 ? " reply" : " replies"));
                }
            });
    }

    /** Builds a fully interactive reply row — avatar, like (with count),
     *  reply (tags the author, flattened into the same parent thread),
     *  edited label, and a long-press menu for edit/delete/report. This
     *  matches ReelCommentsAdapter's top-level comment behavior 1:1. */
    @Nullable
    private View buildReplyRow(ReelReply r, ReelComment parent,
                               LinearLayout container, TextView tvToggle) {
        try {
            View v = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_reel_reply, container, false);

            android.widget.ImageView ivAvatar = v.findViewById(R.id.iv_avatar);
            TextView tvName     = v.findViewById(R.id.tv_name);
            TextView tvText     = v.findViewById(R.id.tv_text);
            TextView tvTime     = v.findViewById(R.id.tv_time);
            TextView tvEdited   = v.findViewById(R.id.tv_edited);
            TextView tvAuthorBadge  = v.findViewById(R.id.tv_author_badge);
            TextView tvCreatorLiked = v.findViewById(R.id.tv_creator_liked);
            TextView btnReplyTo = v.findViewById(R.id.btn_reply);
            ImageButton btnLike = v.findViewById(R.id.btn_like_reply);
            TextView tvLikes    = v.findViewById(R.id.tv_likes_count);

            if (tvName != null) tvName.setText(r.ownerName != null ? r.ownerName : "User");
            android.widget.ImageView ivVerified = v.findViewById(R.id.iv_verified);
            com.callx.app.utils.VerifiedBadgeUtils.bindForUid(ivVerified, r.uid);
            if (tvTime != null) tvTime.setText(formatTime(r.timestamp));
            if (tvEdited != null) tvEdited.setVisibility(r.isEdited ? View.VISIBLE : View.GONE);

            if (tvAuthorBadge != null) {
                tvAuthorBadge.setVisibility(
                    !reelUid.isEmpty() && reelUid.equals(r.uid) ? View.VISIBLE : View.GONE);
            }
            if (tvCreatorLiked != null) {
                boolean likedByCreator = !reelUid.isEmpty() && r.isLikedBy(reelUid);
                tvCreatorLiked.setVisibility(likedByCreator ? View.VISIBLE : View.GONE);
            }

            if (tvText != null) {
                String body = r.text != null ? r.text : "";
                if (r.mentionName != null && !r.mentionName.isEmpty()
                        && !body.trim().startsWith("@" + r.mentionName)) {
                    body = "@" + r.mentionName + " " + body;
                }
                MentionSpanUtils.bindSingle(tvText, body, r.mentionUid, r.mentionName);
            }

            if (ivAvatar != null) bindReplyAvatar(ivAvatar, r.uid, r.ownerPhoto);

            boolean liked = r.isLikedBy(myUid);
            if (tvLikes != null)
                tvLikes.setText(r.likesCount > 0 ? String.valueOf(r.likesCount) : "");
            if (btnLike != null) {
                btnLike.setImageResource(liked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
                btnLike.setColorFilter(liked
                    ? getResources().getColor(android.R.color.holo_red_light)
                    : getResources().getColor(android.R.color.darker_gray));
                btnLike.setOnClickListener(v2 -> toggleReplyLike(r, parent, container, tvToggle));
            }

            if (btnReplyTo != null) {
                btnReplyTo.setOnClickListener(v2 -> startReplyToReply(parent, r));
            }

            v.setOnLongClickListener(v2 -> {
                showReplyContextMenu(r, parent, container, tvToggle);
                return true;
            });

            return v;
        } catch (Exception e) {
            return null;
        }
    }

    // Reply avatar cache (uid → photoUrl) — mirrors ReelCommentsAdapter's
    // avatarCache. Without this every "View replies" toggle re-hit
    // Firebase for a uid we may have already resolved seconds ago (e.g.
    // collapse/expand, or the same commenter replying in multiple threads
    // on this reel).
    private static final LruCache<String, String> replyAvatarCache = new LruCache<>(200);

    // 26dp reply avatar, bucketed to the shared TINY tier (32dp) so this
    // reuses the same cached decode as other small avatars app-wide.
    private static final com.callx.app.utils.AvatarSizeTier REPLY_AVATAR_TIER =
        com.callx.app.utils.AvatarSizeTier.TINY;

    // PERF: same size-capped decode fix as ReelCommentsAdapter.avatarRequestOptions
    // — reply avatars were being loaded with a bare Glide.load().into(iv), no
    // .override(), so Glide had to wait on the ImageView's ViewTreeObserver to
    // resolve a target size at layout time and could decode a full-resolution
    // bitmap if the view hadn't been measured yet. Pinning .override(sizePx,
    // sizePx) to the fixed 26dp reply avatar size (built once, reused for every
    // reply row) guarantees we never decode more pixels than a reply avatar can
    // ever show, and skips the ViewTarget size-resolution step entirely.
    private static volatile RequestOptions replyAvatarRequestOptions;

    private static RequestOptions replyAvatarRequestOptions(Context ctx) {
        RequestOptions opts = replyAvatarRequestOptions;
        if (opts == null) {
            int sizePx = com.callx.app.utils.AvatarUrlBuilder.tierPx(ctx, REPLY_AVATAR_TIER);
            opts = new RequestOptions().override(sizePx, sizePx);
            replyAvatarRequestOptions = opts;
        }
        return opts;
    }

    private void bindReplyAvatar(android.widget.ImageView iv, @Nullable String uid, @Nullable String photoUrl) {
        // CORRECTNESS: unlike top-level comment rows (managed by
        // ReelCommentsAdapter's RecyclerView, which tags each row with the
        // uid its avatar is currently FOR before an async fallback lookup),
        // reply rows here are plain inflated Views inside a LinearLayout
        // that lives inside a RecyclerView-recycled comment row. If the
        // outer comment row scrolls off and gets recycled/rebound to a
        // DIFFERENT comment while a reply avatar's async Firebase lookup is
        // still in flight, the old callback could land on an ImageView that
        // (visually or literally) now belongs to different content. Tagging
        // it the same way as the top-level avatar binder closes that gap.
        iv.setTag(R.id.tag_avatar_uid, uid);
        iv.setImageResource(R.drawable.ic_person);

        if (photoUrl != null && !photoUrl.isEmpty()) {
            if (uid != null) replyAvatarCache.put(uid, photoUrl);
            loadReplyAvatarInto(iv, photoUrl);
            return;
        }

        String cached = uid != null ? replyAvatarCache.get(uid) : null;
        if (cached != null && !cached.isEmpty()) {
            loadReplyAvatarInto(iv, cached);
            return;
        }

        if (uid == null || uid.isEmpty()) return;
        FirebaseDatabase.getInstance()
            .getReference("reels/users").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (!isAdded()) return;
                    String thumb = s.child("thumbUrl").getValue(String.class);
                    String photo = s.child("photoUrl").getValue(String.class);
                    String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (p == null || p.isEmpty()) return;
                    replyAvatarCache.put(uid, p);
                    // Stale-callback guard: only apply if this exact
                    // ImageView is still showing an avatar for this uid.
                    if (uid.equals(iv.getTag(R.id.tag_avatar_uid))) {
                        loadReplyAvatarInto(iv, p);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadReplyAvatarInto(android.widget.ImageView iv, String url) {
        if (!isAdded()) return;
        try {
            // Circular avatar (see item_reel_reply.xml's
            // bg_comment_avatar_circle + clipToOutline) — no .circleCrop()
            // transform needed, the outline clip handles the shape for
            // free. Routed through AvatarUrlBuilder so a 26dp reply avatar
            // decodes at ~52px instead of whatever full resolution the
            // source photo happens to be — same size-capping already used
            // for top-level comment avatars.
            String resizedUrl = com.callx.app.utils.AvatarUrlBuilder
                .build(requireContext(), url, REPLY_AVATAR_TIER);
            Glide.with(requireContext()).load(resizedUrl)
                .apply(replyAvatarRequestOptions(requireContext()))
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(iv);
        } catch (Exception ignored) {}
    }

    // ── Reply like ────────────────────────────────────────────────────────────

    private void toggleReplyLike(ReelReply reply, ReelComment parent,
                                 LinearLayout container, TextView tvToggle) {
        if (myUid.isEmpty()) {
            Toast.makeText(requireContext(), "Please login to like", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean currentlyLiked = reply.isLikedBy(myUid);
        DatabaseReference replyRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reelCommentReplies")
            .child(reelId).child(parent.commentId).child(reply.replyId);

        replyRef.child("likedBy").child(myUid).setValue(currentlyLiked ? null : true);

        replyRef.child("likesCount").runTransaction(new Transaction.Handler() {
            @NonNull @Override
            public Transaction.Result doTransaction(@NonNull MutableData d) {
                Integer v = d.getValue(Integer.class);
                int cur = v != null ? v : 0;
                d.setValue(Math.max(0, currentlyLiked ? cur - 1 : cur + 1));
                return Transaction.success(d);
            }
            @Override public void onComplete(@Nullable DatabaseError e,
                                             boolean b, @Nullable DataSnapshot s) {
                if (isAdded()) loadRepliesInto(parent, container, tvToggle);
            }
        });

        if (!currentlyLiked && !reply.uid.equals(myUid)) {
            ReelCommentNotifWorker.enqueueLike(
                requireContext(), reelId, reply.uid, myUid, myName, reply.replyId);
        }
    }

    // ── Reply context menu (edit / delete / report) ─────────────────────────────

    private void showReplyContextMenu(ReelReply reply, ReelComment parent,
                                      LinearLayout container, TextView tvToggle) {
        boolean isOwn = myUid.equals(reply.uid);
        boolean isReelOwner = myUid.equals(reelUid);

        List<String> opts = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (isOwn) {
            opts.add("Edit reply");
            actions.add(() -> showEditReplyDialog(reply, parent, container, tvToggle));
        }
        if (!isOwn) {
            opts.add("Report reply");
            actions.add(() -> showReportReplyDialog(reply));
        }
        if (isOwn || isReelOwner) {
            opts.add("Delete reply");
            actions.add(() -> showDeleteReplyDialog(reply, parent, container, tvToggle));
        }
        if (opts.isEmpty()) return;

        String[] optsArray = opts.toArray(new String[0]);
        AlertDialogStyler.showRounded(new AlertDialog.Builder(requireContext())
            .setItems(optsArray, (d, which) -> actions.get(which).run())
            .create());
    }

    private void showEditReplyDialog(ReelReply reply, ReelComment parent,
                                     LinearLayout container, TextView tvToggle) {
        if (!myUid.equals(reply.uid)) return;

        EditText et = new EditText(requireContext());
        et.setText(reply.text);
        et.setMaxLines(5);
        et.setSelection(et.getText().length());
        int pad = dpToPx(16);
        et.setPadding(pad, pad, pad, pad);

        AlertDialogStyler.showRounded(new AlertDialog.Builder(requireContext())
            .setTitle("Edit reply")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String newText = et.getText().toString().trim();
                if (TextUtils.isEmpty(newText) || newText.equals(reply.text)) return;
                if (newText.length() > MAX_COMMENT_LENGTH) {
                    Toast.makeText(requireContext(), "Reply too long (max 300 chars)",
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                DatabaseReference ref = FirebaseDatabase.getInstance(Constants.DB_URL)
                    .getReference("reelCommentReplies")
                    .child(reelId).child(parent.commentId).child(reply.replyId);
                Map<String, Object> updates = new HashMap<>();
                updates.put("text",     newText);
                updates.put("isEdited", true);
                updates.put("editedAt", System.currentTimeMillis());
                ref.updateChildren(updates)
                    .addOnCompleteListener(t -> { if (isAdded()) loadRepliesInto(parent, container, tvToggle); });
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    private void showDeleteReplyDialog(ReelReply reply, ReelComment parent,
                                       LinearLayout container, TextView tvToggle) {
        AlertDialogStyler.showReusableConfirm(requireContext(), "delete_reel_reply",
            AlertDialogStyler.DialogSize.DEFAULT,
            "Delete reply?",
            "This reply will be permanently removed.",
            "Delete", () -> deleteReply(reply, parent, container, tvToggle),
            null, null,
            "Cancel");
    }

    private void deleteReply(ReelReply reply, ReelComment parent,
                             LinearLayout container, TextView tvToggle) {
        try {
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("reelCommentReplies")
                .child(reelId).child(parent.commentId).child(reply.replyId)
                .removeValue();

            FirebaseUtils.getReelCommentsRef(reelId)
                .child(parent.commentId).child("replyCount")
                .runTransaction(new Transaction.Handler() {
                    @NonNull @Override
                    public Transaction.Result doTransaction(@NonNull MutableData d) {
                        Integer v = d.getValue(Integer.class);
                        int cur = v != null ? v : 0;
                        d.setValue(Math.max(0, cur - 1));
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(@Nullable DatabaseError e,
                                                     boolean b, @Nullable DataSnapshot s) {
                        if (isAdded()) loadRepliesInto(parent, container, tvToggle);
                    }
                });
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to delete reply", Toast.LENGTH_SHORT).show();
        }
    }

    private void showReportReplyDialog(ReelReply reply) {
        String[] reasons = {
            "Spam", "Hate speech", "Harassment", "Misinformation",
            "Nudity or sexual content", "Violence", "Other"
        };
        AlertDialogStyler.showRounded(new AlertDialog.Builder(requireContext())
            .setTitle("Report reply")
            .setItems(reasons, (d, which) -> submitReplyReport(reply, reasons[which]))
            .setNegativeButton("Cancel", null)
            .create());
    }

    private void submitReplyReport(ReelReply reply, String reason) {
        if (myUid.isEmpty()) return;
        Map<String, Object> report = new HashMap<>();
        report.put("reporterUid", myUid);
        report.put("reason",      reason);
        report.put("timestamp",   System.currentTimeMillis());
        report.put("replyText",   reply.text);

        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reelReplyReports")
            .child(reelId)
            .child(reply.replyId)
            .child(myUid)
            .setValue(report)
            .addOnSuccessListener(a ->
                Toast.makeText(requireContext(), "Reply reported. Thank you.",
                    Toast.LENGTH_SHORT).show())
            .addOnFailureListener(e ->
                Toast.makeText(requireContext(), "Failed to report. Try again.",
                    Toast.LENGTH_SHORT).show());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void showDeleteDialog(ReelComment comment, int position) {
        AlertDialogStyler.showReusableConfirm(requireContext(), "delete_reel_comment",
            AlertDialogStyler.DialogSize.DEFAULT,
            "Delete comment?",
            "This comment will be permanently removed.",
            "Delete", () -> deleteComment(comment),
            null, null,
            "Cancel");
    }

    private void deleteComment(ReelComment comment) {
        try {
            FirebaseUtils.getReelCommentsRef(reelId).child(comment.commentId).removeValue();
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("reelCommentReplies")
                .child(reelId).child(comment.commentId).removeValue();
            incrementCommentsCount(-1);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to delete", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Comments count transaction ────────────────────────────────────────────

    private void incrementCommentsCount(int delta) {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reels").child(reelId).child("commentsCount")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Integer v = d.getValue(Integer.class);
                    int cur = v != null ? v : 0;
                    d.setValue(Math.max(0, cur + delta));
                    return Transaction.success(d);
                }
                @Override public void onComplete(@Nullable DatabaseError e,
                                                 boolean b, @Nullable DataSnapshot s) {}
            });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showEmpty(boolean empty) {
        if (rvComments != null) rvComments.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tvEmpty    != null) tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        hideCommentsShimmer();
    }

    /** Starts the skeleton — called once, right before the first Firebase
     *  listener attaches (loadComments()/showEmpty(true) for a blank
     *  reelId). Hides the real list/empty-state views underneath so they
     *  don't show stale/blank content behind the skeleton. */
    private void showCommentsShimmer() {
        firstCommentsPageResolved = false;
        if (skeletonComments != null) {
            skeletonComments.setVisibility(View.VISIBLE);
            skeletonComments.start();
        }
        if (rvComments != null) rvComments.setVisibility(View.GONE);
        if (tvEmpty    != null) tvEmpty.setVisibility(View.GONE);
    }

    /** Stops + hides the skeleton the first time the comments list actually
     *  resolves (empty or not) — a no-op on every call after that, so later
     *  refreshes (new comment added, filter changed, etc.) don't re-touch it. */
    private void hideCommentsShimmer() {
        if (firstCommentsPageResolved) return;
        firstCommentsPageResolved = true;
        if (skeletonComments != null) {
            skeletonComments.stop();
            skeletonComments.setVisibility(View.GONE);
        }
    }

    private void updateCountHeader() {
        if (tvCommentCount == null) return;
        // Prefer the live true total; fall back to the loaded-batch size
        // only for the brief window before the count listener's first
        // value arrives, so the header isn't blank on first paint.
        int n = totalCommentsCount >= 0 ? totalCommentsCount : allComments.size();
        tvCommentCount.setText(n > 0 ? "Comments (" + n + ")" : "Comments");
    }

    @Nullable
    private String getInputText() {
        if (etComment == null) return null;
        String t = etComment.getText().toString().trim();
        boolean hasImage = replyingToComment == null
            && uploadedImageUrl != null && !uploadedImageUrl.isEmpty();
        if (TextUtils.isEmpty(t) && !hasImage) {
            Toast.makeText(requireContext(), "Please write something", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (t.length() > MAX_COMMENT_LENGTH) {
            Toast.makeText(requireContext(), "Comment too long (max 300 chars)", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (myUid.isEmpty()) {
            Toast.makeText(requireContext(), "Please login first", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (reelId.isEmpty()) return null;
        return t;
    }

    private void clearInput() {
        if (etComment != null) {
            etComment.setText("");
            etComment.clearFocus();
        }
        if (tvCharCount != null) tvCharCount.setVisibility(View.GONE);
        hideMentionSuggestions();
        try {
            InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && etComment != null)
                imm.hideSoftInputFromWindow(etComment.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    // ── Bottom inset (SHEET host only) ───────────────────────────────────────
    // Pushed in directly by ReelCommentSheetFragment, which captures the
    // real IME + navigation-bar insets at the dialog's decor view (the very
    // top of the window's view hierarchy) and calls this instead of relying
    // on WindowInsets dispatch reaching all the way down to this fragment's
    // own root. That dispatch used to silently return nothing here — several
    // views up the chain (CoordinatorLayout, Material's own
    // design_bottom_sheet handling) can consume insets for their own layout
    // before a plain OnApplyWindowInsetsListener this far down ever sees
    // them — which is why the input bar previously sat behind the 3-button/
    // gesture navigation bar at rest instead of padded above it.
    public void applyBottomInset(int bottomInsetPx) {
        if (fragmentRoot == null) return;
        int bottom = Math.max(0, bottomInsetPx);
        fragmentRoot.setPadding(fragmentRoot.getPaddingLeft(), fragmentRoot.getPaddingTop(),
                                 fragmentRoot.getPaddingRight(), bottom);
    }

    private void showKeyboard(View v) {
        if (v == null) return;
        v.requestFocus();
        // BUG FIX: showSoftInput() called synchronously right after a
        // gesture-driven requestFocus() (swipe-to-reply's clearView(), not
        // a direct user tap on the EditText) could silently no-op — the
        // view/window hadn't necessarily settled focus yet since it fires
        // mid-animation-callback. Posting it lets the current touch/animation
        // frame finish first, and SHOW_FORCED (vs SHOW_IMPLICIT) reliably
        // opens the keyboard even when the request didn't originate from a
        // direct tap on the field itself.
        v.post(() -> {
            if (!isAdded()) return;
            try {
                InputMethodManager imm = (InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_FORCED);
            } catch (Exception ignored) {}
        });
    }

    private String formatTime(long ts) {
        return (String) DateUtils.getRelativeTimeSpanString(
            ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    private int dpToPx(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }
}
