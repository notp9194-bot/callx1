package com.callx.app.comments;
import com.callx.app.utils.AlertDialogStyler;

import android.animation.AnimatorListenerAdapter;
import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
import com.callx.app.cache.ReelCommentAvatarBinder;
import com.callx.app.models.ReelComment;
import com.callx.app.models.ReelReply;
import com.callx.app.reels.R;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ImageCompressor;
import com.callx.app.utils.NetworkUtils;
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
    /** Char counter appears from here (last 50 chars) and turns red from COUNTER_WARN_AT. */
    private static final int COUNTER_SHOW_AT = MAX_COMMENT_LENGTH - 50;
    private static final int COUNTER_WARN_AT = MAX_COMMENT_LENGTH - 30;
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
    private ImageView      ivMyAvatar;
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
    // Error / offline UI (see "Error & offline states" section)
    private View           layoutErrorState;
    private TextView       tvErrorIcon, tvErrorTitle, tvErrorMessage, btnErrorRetry;
    private TextView       tvStatusBanner;

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

    // ── Reply local-first (optimistic) state ─────────────────────────────
    // Replies aren't backed by a live RecyclerView list like top-level
    // comments (allComments/adapter) — buildReplyRow()'s container is a
    // plain LinearLayout rebuilt from scratch by loadRepliesInto() every
    // time "View replies" is toggled. So an in-flight/failed reply's
    // sendState needs to be remembered OUTSIDE that container to survive
    // a collapse→expand cycle — this map is that memory, keyed by parent
    // commentId. Entries are removed once Firebase confirms the write.
    private final Map<String, java.util.List<ReelReply>> pendingRepliesByParent = new HashMap<>();
    /** The container/toggle most recently built by loadRepliesInto() —
     *  used so a freshly-posted reply can be appended and shown instantly
     *  (same "show it now, reconcile later" idea as postComment()) without
     *  waiting for the next full toggle rebuild. Only one thread is ever
     *  being actively replied to at a time in this UI, so "most recent"
     *  is always the right one. */
    private LinearLayout activeRepliesContainer;
    private TextView     activeRepliesToggle;
    private String       activeRepliesParentId;

    // ── Reply paging ─────────────────────────────────────────────────────
    // Replies are rendered in pages (first REPLIES_INITIAL, then
    // REPLIES_STEP per "View N more replies" tap) instead of inflating the
    // whole thread at once. The full list is fetched once per expand/refresh
    // and cached here (parentId → ordered rows) so paging is instant; both
    // maps are dropped when the thread is collapsed.
    private static final int    REPLIES_INITIAL = 3;
    private static final int    REPLIES_STEP    = 8;
    private static final String MORE_ROW_TAG    = "reply_more_row";
    private final Map<String, java.util.List<ReelReply>> repliesCache = new HashMap<>();
    private final Map<String, Integer> repliesShown = new HashMap<>();
    // PERF: replies are now paged on the SERVER (orderByKey + limitToFirst), not just in the UI.
    // repliesCache holds only what has been fetched so far (+ local pending rows).
    /** parentId → key of the last server reply fetched (cursor for startAfter). */
    private final Map<String, String>  repliesCursor  = new HashMap<>();
    /** parentId → true while the server still has replies beyond the cursor. */
    private final Map<String, Boolean> repliesHasMore = new HashMap<>();
    /** parentIds with a "view more" fetch in flight (blocks double taps). */
    private final Set<String> repliesLoadingMore = new HashSet<>();
    private final Map<String, Integer> repliesMoreToken = new HashMap<>();
    /** Replies this user posted this session (already confirmed). A paged refresh may not
     *  include them (they are the newest keys), so they are merged back at the end. */
    private final Map<String, java.util.List<ReelReply>> sessionPostedReplies = new HashMap<>();

    // ── Likes live OUTSIDE the comment nodes now ─────────────────────────
    // userCommentLikes/{me}/{reelId}/{commentOrReplyId} = true. One small read per sheet
    // open replaces downloading every liker of every comment.
    private final Set<String> myLikedIds = new HashSet<>();
    /** ids toggled locally this session — the initial load must not override them. */
    private final Set<String> myLikesTouched = new HashSet<>();
    private boolean myLikesReady = false;

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
        // this.-qualified: these fields are declared further down (no forward-reference error)
        refreshHandler.removeCallbacks(this.applyRunnable);
        this.applyQueued = false;
        applyFilterAndSortNow();
        scheduleCacheSave();
        if (pendingAutoScroll) {
            pendingAutoScroll = false;
            autoScrollIfAtTop();
        }
        if (pendingNewComments > 0 && pillNewComments != null) {
            pillNewComments.setText(pillNewComments.getResources().getQuantityString(
                R.plurals.reel_c_new_comments, pendingNewComments, pendingNewComments));
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
    /** True after a "load older" page failed/timed out - blocks the scroll and
     *  viewport-fill triggers from hammering a dead connection; cleared by the
     *  user tapping the retry chip or by the network coming back. */
    private boolean olderLoadFailed = false;
    private int     olderRequestId  = 0;
    private Runnable olderTimeoutRunnable = () -> {};

    /** Triggered by the scroll listener once the user nears the top of the
     *  loaded list — fetches the next older page as a one-off read (NOT a
     *  live listener, so it doesn't grow the realtime bandwidth footprint). */
    private void maybeLoadOlderComments() {
        if (!initialLoadSettled || loadingOlder || olderLoadFailed || !hasMoreOlder
                || oldestLoadedKey == null || reelId.isEmpty()) return;
        loadingOlder = true;
        showLoadingOlder(true);

        // A one-off read never calls back while offline (and a permission /
        // network error only arrives as onCancelled) - without this the
        // "Loading earlier comments…" chip would spin forever. Each request
        // gets an id so a late result from a request we already gave up on
        // can't be applied on top of a retry.
        final int req = ++olderRequestId;
        refreshHandler.removeCallbacks(olderTimeoutRunnable);
        olderTimeoutRunnable = () -> {
            if (req == olderRequestId && loadingOlder) onOlderLoadFailed();
        };
        refreshHandler.postDelayed(olderTimeoutRunnable,
            isOnlineNow() ? LOAD_TIMEOUT_ONLINE_MS : LOAD_TIMEOUT_OFFLINE_MS);

        Query olderPage = FirebaseUtils.getReelCommentsRef(reelId)
            .orderByKey().endBefore(oldestLoadedKey).limitToLast(PAGE_SIZE);

        olderPage.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded() || req != olderRequestId) return;
                refreshHandler.removeCallbacks(olderTimeoutRunnable);
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
                if (!isAdded() || req != olderRequestId) return;
                onOlderLoadFailed();
            }
        });
    }

    private void showLoadingOlder(boolean show) {
        if (tvLoadingOlder == null) return;
        if (show) {
            tvLoadingOlder.setText(R.string.reel_c_loading_earlier);
            tvLoadingOlder.setOnClickListener(null);
            tvLoadingOlder.setClickable(false);
            tvLoadingOlder.setMinHeight(0);
        }
        tvLoadingOlder.setVisibility(show ? View.VISIBLE : View.GONE);
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
        loadMyLikes();
        bindViews(v);
        bindCaptionHeader(v);
        setupAdapter();
        setupSortChips();
        setupSearch();
        setupCharCounter();
        setupInputLimitAndSendState();
        setupMentionAutocomplete();
        setupNewCommentsPill();
        setupQuickEmojiRow();
        loadMyPhoto();
        restoreDraft();

        if (!reelId.isEmpty()) {
            showCommentsShimmer();
            loadComments();
            armFirstPageSignal();
            listenCommentsCount();
            // Disk-cache warm-start: paints the last cached window
            // immediately so the sheet doesn't sit on the shimmer while
            // Firebase's first read is in flight — see
            // ReelCommentCacheManager's class doc. Pure paint layer, does
            // NOT touch allComments/loadedCommentIds, so it never
            // interferes with the real ChildEventListener burst above.
            paintFromDiskCacheIfEmpty();
        }
        else showEmpty(true);
        listenBlockedUsers();
        registerNetworkWatcher();

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
        // Leaving while an Undo window is open = the user is done: commit now
        // (otherwise the Snackbar dies with the view and the delete is lost).
        for (String k : new ArrayList<>(pendingDeletes.keySet())) commitPendingDelete(k);
        saveDraft();
        if (cacheSaveQueued) {                       // flush the throttled write before the handler is wiped
            refreshHandler.removeCallbacks(cacheSaveRunnable);
            cacheSaveQueued = false;
            saveCommentsToDiskCache();
        }
        applyQueued = false;
        replyRowPool.clear();
        if (skeletonComments != null) skeletonComments.stop();
        if (ivMyAvatar != null && getContext() != null) {
            try { ReelCommentAvatarBinder.cancel(getContext(), ivMyAvatar); } catch (Exception ignored) {}
        }
        refreshHandler.removeCallbacksAndMessages(null);
        unregisterNetworkWatcher();
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
        // FIX (TransactionTooLargeException on backgrounding a reel with many
        // comments loaded): rvComments never opted out of the default view
        // hierarchy state save, so its whole subtree got frozen into the
        // fragment's savedInstanceState bundle — the same class of bug already
        // fixed for chat's rvMessages via setSaveEnabled(false). Comment scroll
        // position isn't something we need restored across process death, so
        // disable it here too.
        if (rvComments != null) rvComments.setSaveEnabled(false);
        skeletonComments = root.findViewById(R.id.skeleton_comments);
        etComment       = root.findViewById(R.id.et_comment);
        btnSend         = root.findViewById(R.id.btn_send);
        ivMyAvatar      = root.findViewById(R.id.iv_my_avatar);
        bindMyAvatar();   // Auth photo now; loadMyPhoto() upgrades it to the reels photo
        tvEmpty         = root.findViewById(R.id.tv_empty);
        tvCommentCount  = root.findViewById(R.id.tv_comment_count);
        if (tvCommentCount != null) androidx.core.view.ViewCompat.setAccessibilityHeading(tvCommentCount, true);
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
        layoutErrorState = root.findViewById(R.id.layout_error_state);
        tvErrorIcon      = root.findViewById(R.id.tv_error_icon);
        tvErrorTitle     = root.findViewById(R.id.tv_error_title);
        tvErrorMessage   = root.findViewById(R.id.tv_error_message);
        btnErrorRetry    = root.findViewById(R.id.btn_error_retry);
        tvStatusBanner   = root.findViewById(R.id.tv_status_banner);
        ReelCommentsAdapter.asButton(btnErrorRetry);
        if (btnErrorRetry != null) btnErrorRetry.setOnClickListener(v -> retryInitialLoad());
        if (tvStatusBanner != null) tvStatusBanner.setOnClickListener(v -> {
            if (bannerMode == BANNER_REFRESH_FAILED) retryInitialLoad();
        });
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
                Toast.makeText(requireContext(), getString(R.string.reel_c_err_open_gallery), Toast.LENGTH_SHORT).show();
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
                    tvToggle.setText(ReelCommentsAdapter.repliesToggleLabel(tvToggle.getContext(), comment.replyCount));
                    repliesCache.remove(comment.commentId);
                    repliesShown.remove(comment.commentId);
                    repliesCursor.remove(comment.commentId);
                    repliesHasMore.remove(comment.commentId);
                    repliesLoadingMore.remove(comment.commentId);
                    repliesMoreToken.remove(comment.commentId);
                    collapseReplies(container);
                } else {
                    tvToggle.setText(R.string.reel_c_loading);
                    repliesShown.remove(comment.commentId);   // fresh expand = first page only
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

            @Override
            public void onTranslateComment(ReelComment comment, int position) {
                // TODO: wire to an actual translation call (ML Kit Translate
                // or a backend endpoint) once one is added to the project —
                // this stub just confirms the menu entry reached the host.
                // Instagram shows the translated text inline, replacing
                // tv_comment_text with a "See original" toggle; do the same
                // here once a real translate() call is available.
                Toast.makeText(requireContext(), getString(R.string.reel_c_translate_soon), Toast.LENGTH_SHORT).show();
            }
        });

        if (rvComments != null) {
            rvComments.setLayoutManager(new LinearLayoutManager(requireContext()));
            rvComments.setAdapter(adapter);
            // Reused from FastFlingRecyclerView's v4 chat fix (see core's
            // RecyclerViewFrictionTuner javadoc): comments are a plain text
            // list like chat, so the same lower-friction long-glide feel
            // applies cleanly here — no pagination/preloader retuning
            // needed like reels grid or Home feed would require.
            com.callx.app.utils.RecyclerViewFrictionTuner.applyReducedFriction(rvComments);

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

            // Distances: reply arms at 56dp of finger travel (was 72dp); the row
            // itself never visually goes past 72dp (rubber-band after 56dp).
            private final int triggerPx   = dpToPx(56);
            private final int maxVisualPx = dpToPx(72);
            // Quick flick: arms with a short drag if the finger was fast.
            private final int   flingMinDx  = dpToPx(28);
            private final float flingMinVel = dpToPx(600);   // px/s
            private final int lockPx   = dpToPx(8);
            private final int iconCx   = dpToPx(28);
            private final int chipBase = dpToPx(13);
            private final int chipGrow = dpToPx(5);
            private final int iconSmall = dpToPx(17);
            private final int iconBig   = dpToPx(20);

            // PERF: built once, reused every frame (was: new Paint +
            // getColor + getDrawable().mutate() on EVERY onChildDraw call).
            private android.graphics.Paint chipPaint;
            private android.graphics.drawable.Drawable icon;

            // Gesture state
            private boolean dragging, armed, fired, parentLocked;
            private ReelComment dragComment;
            private RecyclerView.ViewHolder dragVh;   // ignore other rows still recovering
            private float lastRawDx, velocity;
            private long lastT;

            @Override
            public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh,
                                   @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            // Deliberately unreachable (>1): the row must never actually be
            // "swiped away" by ItemTouchHelper - we only use the drag
            // distance as a gesture signal and always let the row spring back.
            @Override
            public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder vh) { return 2f; }

            // Also disable the velocity path: a fast fling used to bypass the
            // threshold above and really dismiss the row (onSwiped is a no-op,
            // so it would have stayed off-screen). Flicks are handled by us.
            @Override
            public float getSwipeEscapeVelocity(float defaultValue) { return Float.MAX_VALUE; }

            // Faster spring-back (default is ~250ms+, scaled by distance).
            @Override
            public long getAnimationDuration(@NonNull RecyclerView r, int animationType,
                                              float animateDx, float animateDy) {
                return 120L;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) { /* unused */ }

            /** Row translation for a raw finger distance: 1:1 up to the
             *  trigger, then heavy resistance, hard-capped at maxVisualPx. */
            private float visualDx(float dX) {
                if (dX <= 0f) return 0f;
                if (dX <= triggerPx) return dX;
                return Math.min(maxVisualPx, triggerPx + (dX - triggerPx) * 0.25f);
            }

            private void ensureDrawObjects(Context ctx) {
                if (chipPaint == null) {
                    chipPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    chipPaint.setColor(androidx.core.content.ContextCompat.getColor(
                        ctx, R.color.brand_primary));
                }
                if (icon == null) {
                    android.graphics.drawable.Drawable d =
                        androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_reply);
                    if (d != null) {
                        icon = d.mutate();
                        icon.setTint(Color.WHITE);
                    }
                }
            }

            /** Finger lifted: fire the reply NOW (not after the spring-back). */
            private void onRelease(@NonNull RecyclerView r, @NonNull View item) {
                dragging = false;
                if (parentLocked && r.getParent() != null) {
                    r.getParent().requestDisallowInterceptTouchEvent(false);
                }
                parentLocked = false;
                boolean fling = !armed && lastRawDx >= flingMinDx && velocity >= flingMinVel;
                if ((armed || fling) && !fired && dragComment != null) {
                    fired = true;
                    if (fling) item.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CLOCK_TICK);
                    final ReelComment fc = dragComment;
                    // post(): we may be inside a draw pass; startReply changes
                    // visibility/focus. Next frame is ~16ms, not ~300ms.
                    r.post(() -> { if (isAdded()) startReply(fc); });
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView r,
                                     @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                                     int actionState, boolean isCurrentlyActive) {
                if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
                    super.onChildDraw(c, r, vh, dX, dY, actionState, isCurrentlyActive);
                    return;
                }
                final View item = vh.itemView;

                if (isCurrentlyActive) {
                    if (!dragging) {
                        dragging = true; armed = false; fired = false; dragVh = vh;
                        velocity = 0f; lastRawDx = 0f; lastT = 0L;
                        int pos = vh.getAdapterPosition();
                        dragComment = (pos != RecyclerView.NO_POSITION && adapter != null)
                            ? adapter.getComment(pos) : null;
                    }
                    long now = android.os.SystemClock.uptimeMillis();
                    if (lastT != 0L && now > lastT) {
                        float v = (dX - lastRawDx) * 1000f / (now - lastT);
                        velocity = 0.5f * velocity + 0.5f * v;
                    }
                    lastRawDx = dX; lastT = now;

                    // Once it is clearly a horizontal swipe, stop parents
                    // (bottom sheet / pager) from stealing the gesture.
                    if (!parentLocked && dX > lockPx && r.getParent() != null) {
                        r.getParent().requestDisallowInterceptTouchEvent(true);
                        parentLocked = true;
                    }

                    boolean nowArmed = dX >= triggerPx;
                    // Haptic tick exactly once, as the drag crosses the threshold.
                    if (nowArmed && !armed) {
                        item.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    armed = nowArmed;
                } else if (dragging && vh == dragVh) {
                    // First non-active frame after the finger lifted.
                    onRelease(r, item);
                }

                float rawClamped = Math.max(0f, dX);
                float progress = Math.min(1f, rawClamped / triggerPx);
                boolean triggered = isCurrentlyActive ? armed : fired;

                if (progress > 0.05f) {
                    ensureDrawObjects(r.getContext());
                    int cx = item.getLeft() + iconCx;
                    int cy = item.getTop() + item.getHeight() / 2;
                    float chipProgress = Math.min(1f, progress * 1.3f);
                    chipPaint.setAlpha(triggered ? 255 : (int) (170 * chipProgress));
                    c.drawCircle(cx, cy, chipBase + chipGrow * chipProgress, chipPaint);
                    if (icon != null) {
                        int sz = triggered ? iconBig : iconSmall;
                        icon.setAlpha((int) (255 * Math.min(1f, progress * 1.6f)));
                        icon.setBounds(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2);
                        icon.draw(c);
                    }
                }
                super.onChildDraw(c, r, vh, visualDx(dX), dY, actionState, isCurrentlyActive);
            }

            @Override
            public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(r, vh);
                // Fallback: no non-active frame was drawn before the view cleared.
                if (dragging && vh == dragVh) onRelease(r, vh.itemView);
                if (vh != dragVh && dragVh != null) return;   // another row cleared; keep current drag state
                dragVh = null;
                armed = false; fired = false; dragComment = null;
                velocity = 0f; lastRawDx = 0f; lastT = 0L;
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
                applyFilterAndSortNow();
            }
        });

        chipTop.setOnClickListener(v -> {
            if (!sortByTop) {
                sortByTop = true;
                updateSortChipUI();
                applyFilterAndSortNow();
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
                applyFilterAndSortNow();
            }
        });

        if (btnCloseSearch != null) {
            btnCloseSearch.setOnClickListener(v -> {
                searchActive = false;
                searchQuery  = "";
                if (layoutSearch != null) layoutSearch.setVisibility(View.GONE);
                if (etSearch    != null) etSearch.setText("");
                applyFilterAndSortNow();
            });
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    searchQuery = s.toString().trim().toLowerCase();
                    applyFilterAndSortNow();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    // ── Send button state + hard length limit ─────────────────────────────────

    private Boolean sendEnabledState = null;
    private long lastLimitToastAt = 0L;

    /** Hard-caps the field at MAX_COMMENT_LENGTH (typing AND paste) and keeps
     *  the send button in sync with whether there is anything to send. */
    private void setupInputLimitAndSendState() {
        if (etComment == null) return;
        sendEnabledState = null;   // view may have been recreated: force a fresh apply

        // Keep any filters already on the field, add the length cap.
        android.text.InputFilter[] old = etComment.getFilters();
        android.text.InputFilter[] all = new android.text.InputFilter[old.length + 1];
        System.arraycopy(old, 0, all, 0, old.length);
        all[old.length] = new android.text.InputFilter.LengthFilter(MAX_COMMENT_LENGTH) {
            @Override
            public CharSequence filter(CharSequence source, int start, int end,
                                       android.text.Spanned dest, int dstart, int dend) {
                CharSequence out = super.filter(source, start, end, dest, dstart, dend);
                if (out != null && isAdded()) {            // something was cut off
                    long now = System.currentTimeMillis();
                    if (now - lastLimitToastAt > 1500L) {
                        lastLimitToastAt = now;
                        Toast.makeText(requireContext(),
                            getString(R.string.reel_c_max_chars, MAX_COMMENT_LENGTH),
                            Toast.LENGTH_SHORT).show();
                    }
                }
                return out;
            }
        };
        etComment.setFilters(all);

        etComment.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSendState(true); }
        });
        updateSendState(false);

        // Attach AFTER the first (silent) state apply so opening the sheet never
        // animates. From here on the row animates its own changes: send
        // appearing/disappearing, the field resizing around it, and the photo
        // button hiding while replying - instead of snapping.
        View row = fragmentRoot != null ? fragmentRoot.findViewById(R.id.row_comment_input) : null;
        if (row instanceof ViewGroup) {
            android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
            lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
            lt.setDuration(150L);
            ((ViewGroup) row).setLayoutTransition(lt);
        }
    }

    /** Send only EXISTS while there is something to send (non-blank text or a
     *  picked photo) - hidden otherwise, so an empty bar is just avatar + field.
     *  The row's LayoutTransition fades it in/out and resizes the field around
     *  it; on top of that it gets a small overshoot pop when it appears. */
    private void updateSendState(boolean animate) {
        if (btnSend == null) return;
        boolean has = pickedImageUri != null
            || (etComment != null && etComment.getText() != null
                && etComment.getText().toString().trim().length() > 0);
        if (sendEnabledState != null && sendEnabledState == has) return;
        sendEnabledState = has;

        btnSend.setEnabled(has);
        btnSend.animate().cancel();
        btnSend.setAlpha(1f);
        if (!has) {
            btnSend.setScaleX(1f);
            btnSend.setScaleY(1f);
            btnSend.setVisibility(View.GONE);
            return;
        }
        btnSend.setVisibility(View.VISIBLE);
        if (!animate) {
            btnSend.setScaleX(1f);
            btnSend.setScaleY(1f);
            return;
        }
        btnSend.setScaleX(0.7f);
        btnSend.setScaleY(0.7f);
        btnSend.animate().scaleX(1f).scaleY(1f).setDuration(180L)
            .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
    }

    // ── "Replying to" bar: animated show / hide ───────────────────────────────

    private ValueAnimator replyBarAnim;

    /** Slides the bar open/closed (height + fade, ~150ms) instead of popping. */
    private void setReplyBarVisible(boolean show) {
        final View bar = barReplyingTo;
        if (bar == null) return;
        final int full = dpToPx(44);
        final boolean visible = bar.getVisibility() == View.VISIBLE;
        final ViewGroup.LayoutParams lp = bar.getLayoutParams();

        if (replyBarAnim != null) replyBarAnim.cancel();
        if (show && visible && lp.height == full) return;     // already fully open
        if (!show && !visible) return;                        // already closed

        final int from = visible ? bar.getHeight() : 0;
        final int to   = show ? full : 0;
        if (show) {
            lp.height = from;
            bar.setAlpha(from / (float) full);
            bar.setVisibility(View.VISIBLE);
            bar.setLayoutParams(lp);
        }

        final boolean[] cancelled = {false};
        ValueAnimator va = ValueAnimator.ofInt(from, to);
        va.setDuration(150L);
        va.addUpdateListener(a -> {
            int h = (int) a.getAnimatedValue();
            lp.height = h;
            bar.setAlpha(h / (float) full);
            bar.setLayoutParams(lp);
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator animation) { cancelled[0] = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled[0]) return;            // a newer animation owns the bar now
                if (!show) bar.setVisibility(View.GONE);
                lp.height = full;                    // restore for next show
                bar.setAlpha(1f);
                bar.setLayoutParams(lp);
            }
        });
        replyBarAnim = va;
        va.start();
    }

    // ── Character counter ─────────────────────────────────────────────────────

    private void setupCharCounter() {
        if (etComment == null || tvCharCount == null) return;

        etComment.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                int len = s.length();
                // Only show the counter when the limit is actually near; for
                // normal short comments it is just clutter above the field.
                if (len < COUNTER_SHOW_AT) {
                    if (tvCharCount.getVisibility() != View.GONE) {
                        tvCharCount.setVisibility(View.GONE);
                    }
                    return;
                }
                if (tvCharCount.getVisibility() != View.VISIBLE) {
                    tvCharCount.setVisibility(View.VISIBLE);
                }
                tvCharCount.setText(len + "/" + MAX_COMMENT_LENGTH);
                android.content.Context ctx = tvCharCount.getContext();
                tvCharCount.setTextColor(androidx.core.content.ContextCompat.getColor(ctx,
                    len >= COUNTER_WARN_AT ? R.color.comment_counter_warn : R.color.text_muted));
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

    // PERF: direct callers (send-state flips, blocklist listener, older-page, first-page
    // resolve, retry) used to each run a full filter+copy+sort+diff synchronously, even
    // several times in the same frame. applyFilterAndSort() now only QUEUES one run on the
    // next looper turn (any number of calls in the same turn = one run). User-driven paths
    // that must feel instant (sort chips, search typing, posting, Undo) call
    // applyFilterAndSortNow() directly.
    private boolean applyQueued = false;
    private final Runnable applyRunnable = () -> {
        applyQueued = false;
        if (isAdded() && adapter != null) applyFilterAndSortNow();
    };

    private void applyFilterAndSort() {
        if (applyQueued) return;
        applyQueued = true;
        refreshHandler.post(applyRunnable);
    }

    private void applyFilterAndSortNow() {
        if (adapter == null) return;
        List<ReelComment> filtered = new ArrayList<>(allComments.size());

        for (ReelComment c : allComments) {
            if (c.uid != null && blockedUids.contains(c.uid)) continue; // blocked user's comment, hide it
            if (pendingDeletes.containsKey("c:" + c.commentId)) continue; // deleted, waiting out the Undo window
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
        boolean emptyNow = filtered.isEmpty();
        if (!emptyNow) markFirstPageReady();
        // An empty list only means "no comments" once the first page has
        // really resolved. Before that it is still loading (skeleton) or it
        // failed (error state) - e.g. the blocklist listener answering first
        // must not flash "Be the first to comment" over an offline screen.
        if (emptyNow && !firstPageReady && !reelId.isEmpty()) {
            // keep skeleton / error state as-is
        } else {
            showEmpty(emptyNow);
        }
        checkAndHighlightComment();
        maybeAutoFillViewport();
    }

    /** Disk-cache warm-start (see ReelCommentCacheManager's class doc):
     *  reads the last cached window in the background and, ONLY if the
     *  real Firebase burst hasn't landed anything yet by the time the
     *  read comes back, paints it straight into the adapter so the sheet
     *  shows something instantly instead of sitting on the shimmer.
     *  Deliberately does not touch allComments/loadedCommentIds — the
     *  real ChildEventListener burst still drives the actual data exactly
     *  as before and will transparently overwrite this via the normal
     *  applyFilterAndSort() path once it settles. */
    private void paintFromDiskCacheIfEmpty() {
        if (reelId.isEmpty()) return;
        ReelCommentCacheManager.loadPageAsync(requireContext(), reelId, cached -> {
            if (!isAdded() || cached.isEmpty()) return;
            if (!allComments.isEmpty()) return; // real data already arrived first
            List<ReelComment> sorted = new ArrayList<>(cached);
            Collections.sort(sorted, sortByTop
                ? ReelCommentsAdapter.TOP_FIRST : ReelCommentsAdapter.NEWEST_FIRST);
            adapter.setComments(sorted);
            showEmpty(false);
        });
    }

    /** Fire-and-forget disk cache write of the current live window — keeps
     *  paintFromDiskCacheIfEmpty() warm for the NEXT open. Only runs off
     *  the real (network-driven) refresh path in refreshRunnable, and
     *  skipped mid-search so a filtered view never clobbers the real
     *  cached page — see ReelCommentCacheManager's class doc for which
     *  rows actually get persisted. */
    private void saveCommentsToDiskCache() {
        if (reelId.isEmpty() || !searchQuery.isEmpty() || !isAdded()) return;
        // PERF: skip the DB clear+insert entirely when nothing the cache stores has changed
        // (most refreshes are a no-op echo of our own like/send-state flip).
        int sig = commentsSignature();
        if (cacheSigValid && sig == lastCacheSig) return;
        lastCacheSig = sig;
        cacheSigValid = true;
        ReelCommentCacheManager.savePage(requireContext(), reelId, allComments);
    }

    // PERF: trailing throttle — a burst of refreshes (likes, typing echo, paging) costs at most
    // one disk write per CACHE_SAVE_THROTTLE_MS instead of one per refresh.
    private static final long CACHE_SAVE_THROTTLE_MS = 500L;
    private boolean cacheSaveQueued = false;
    private boolean cacheSigValid   = false;
    private int     lastCacheSig    = 0;
    private final Runnable cacheSaveRunnable = () -> {
        cacheSaveQueued = false;
        saveCommentsToDiskCache();
    };

    private void scheduleCacheSave() {
        if (cacheSaveQueued) return;          // one is already pending - it will see the latest data
        cacheSaveQueued = true;
        refreshHandler.postDelayed(cacheSaveRunnable, CACHE_SAVE_THROTTLE_MS);
    }

    /** Order-sensitive hash of exactly the fields ReelCommentCacheManager persists (no allocation). */
    private int commentsSignature() {
        int h = 1;
        for (ReelComment c : allComments) {
            if (c.sendState != null) continue;
            h = 31 * h + (c.commentId == null ? 0 : c.commentId.hashCode());
            h = 31 * h + c.likesCount;
            h = 31 * h + c.replyCount;
            h = 31 * h + (c.isPinned ? 1 : 0) + (c.isEdited ? 2 : 0)
                       + (c.likedByMe ? 4 : 0) + (c.creatorLiked ? 8 : 0);
            h = 31 * h + (c.text == null ? 0 : c.text.hashCode());
            h = 31 * h + (c.ownerName == null ? 0 : c.ownerName.hashCode());
            h = 31 * h + (c.ownerPhoto == null ? 0 : c.ownerPhoto.hashCode());
            h = 31 * h + (c.imageUrl == null ? 0 : c.imageUrl.hashCode());
            h = 31 * h + (c.reactions == null ? 0 : c.reactions.hashCode());
        }
        return h;
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
    /** Paints the input bar's own avatar (placeholder until a photo is known). */
    private void bindMyAvatar() {
        if (ivMyAvatar == null || getContext() == null) return;
        ReelCommentAvatarBinder.bind(requireContext(), ivMyAvatar, myPhoto, 0L, R.drawable.ic_person);
    }

    private void loadMyPhoto() {
        if (myUid.isEmpty()) return;
        FirebaseDatabase.getInstance()
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    String thumb = s.child("thumbUrl").getValue(String.class);
                    String photo = s.child("photoUrl").getValue(String.class);
                    String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (p != null && !p.isEmpty()) { myPhoto = p; if (isAdded()) bindMyAvatar(); }
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
            @Override public void onCancelled(@NonNull DatabaseError e) {
                // Used to call showEmpty(true) = "Be the first to comment" on a
                // load FAILURE, which is just wrong. Show an error instead.
                if (isAdded()) onInitialLoadFailed();
            }
        };

        commentsQuery.addChildEventListener(commentsListener);
    }

    @Nullable
    private ReelComment safeParseComment(DataSnapshot s) {
        try {
            ReelComment c = s.getValue(ReelComment.class);
            if (c == null) return null;
            if (c.commentId == null) c.commentId = s.getKey() != null ? s.getKey() : "";
            normalizeCommentLikes(c);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Likes: my-like set + legacy folding ──────────────────────────────

    private void loadMyLikes() {
        if (myUid.isEmpty() || reelId.isEmpty()) { myLikesReady = true; return; }
        CommentLikeWriter.myLikesRef(myUid, reelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded()) return;
                    for (DataSnapshot ch : snap.getChildren()) {
                        String id = ch.getKey();
                        if (id == null || myLikesTouched.contains(id)) continue;
                        if (Boolean.TRUE.equals(ch.getValue(Boolean.class))) myLikedIds.add(id);
                    }
                    myLikesReady = true;
                    reapplyMyLikes();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    // Rules not deployed / offline: fall back to legacy likedBy + per-tap check.
                    myLikesReady = true;
                }
            });
    }

    /** Marks already-parsed comments liked once the my-likes set arrives (partial rebind only). */
    private void reapplyMyLikes() {
        if (myLikedIds.isEmpty()) return;
        for (ReelComment c : allComments) {
            if (c.commentId != null && !c.likedByMe && myLikedIds.contains(c.commentId)) {
                c.likedByMe = true;
                if (adapter != null) adapter.notifyLikeChanged(c.commentId);
            }
        }
        for (java.util.List<ReelReply> l : repliesCache.values()) {
            for (ReelReply r : l) {
                if (r.replyId != null && !r.likedByMe && myLikedIds.contains(r.replyId)) r.likedByMe = true;
            }
        }
    }

    /** Folds the legacy likedBy map (old data) + my-likes set into the two flags the UI uses,
     *  then drops the map so it is never held in memory / compared / cached. */
    private void normalizeCommentLikes(ReelComment c) {
        Map<String, Boolean> lb = c.likedBy;
        boolean legacyMe = lb != null && !myUid.isEmpty() && Boolean.TRUE.equals(lb.get(myUid));
        if (lb != null && !reelUid.isEmpty() && Boolean.TRUE.equals(lb.get(reelUid))) c.creatorLiked = true;
        c.likedByMe = legacyMe || (c.commentId != null && myLikedIds.contains(c.commentId));
        c.likedBy = null;
    }

    private void normalizeReplyLikes(ReelReply r) {
        Map<String, Boolean> lb = r.likedBy;
        boolean legacyMe = lb != null && !myUid.isEmpty() && Boolean.TRUE.equals(lb.get(myUid));
        if (lb != null && !reelUid.isEmpty() && Boolean.TRUE.equals(lb.get(reelUid))) r.creatorLiked = true;
        r.likedByMe = legacyMe || (r.replyId != null && myLikedIds.contains(r.replyId));
        r.likedBy = null;
    }

    /** A blind ServerValue.increment must never double-count: if the my-likes set has not
     *  loaded yet and the item looks un-liked, confirm the marker once before toggling. */
    private void resolveMyLike(String itemId, boolean looksLiked,
                               java.util.function.Consumer<Boolean> done) {
        if (myLikesReady || looksLiked) { done.accept(looksLiked); return; }
        CommentLikeWriter.myLikesRef(myUid, reelId).child(itemId).get()
            .addOnCompleteListener(t -> {
                boolean liked = t.isSuccessful()
                    && Boolean.TRUE.equals(t.getResult().getValue(Boolean.class));
                done.accept(liked);
            });
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
        updateSendState(true);
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
                        Toast.makeText(requireContext(), getString(R.string.reel_c_gif_upload_failed), Toast.LENGTH_SHORT).show();
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
        updateSendState(true);
        uploadedImageUrl = null;
        uploadingImage = true;

        if (layoutImagePreview != null) layoutImagePreview.setVisibility(View.VISIBLE);
        if (progressImage != null) progressImage.setVisibility(View.VISIBLE);
        if (ivImagePreview != null) {
            Glide.with(this).load(uri).into(ivImagePreview);
        }

        // PERF: reuses the same ImageCompressor pipeline the reel photo/chat
        // upload flows already use (WhatsApp-style EXIF-fix + resize + WebP
        // re-encode, Standard tier) instead of uploading the raw picked file
        // straight to Cloudinary — a comment photo doesn't need HD, so
        // hd=false, same default the single-image chat send path uses.
        ImageCompressor.compress(requireContext(), uri, false, new ImageCompressor.Callback() {
            @Override public void onSuccess(ImageCompressor.Result result) {
                if (!isAdded()) return;
                // User may have removed the preview while this was still
                // compressing — don't resurrect it.
                if (pickedImageUri == null || !pickedImageUri.equals(uri)) return;
                uploadCompressedCommentImage(uri, Uri.fromFile(result.fullFile));
            }

            @Override public void onError(Exception e) {
                if (!isAdded()) return;
                uploadingImage = false;
                Toast.makeText(requireContext(), getString(R.string.reel_c_photo_upload_failed), Toast.LENGTH_SHORT).show();
                clearPickedImage();
            }
        });
    }

    /** Uploads the ImageCompressor-produced file to Cloudinary. originalUri is
     *  only used to guard against a stale/removed preview, same check the
     *  pre-compression code used against the raw picked uri. */
    private void uploadCompressedCommentImage(Uri originalUri, Uri compressedUri) {
        try {
            // alreadyCompressed=true: compressedUri already went through
            // ImageCompressor (WebP) above — skip CloudinaryUploader's own
            // internal re-compression, which otherwise re-encodes to JPEG
            // while still tagging the upload as image/webp and breaks
            // every send. See CloudinaryUploader#upload's alreadyCompressed
            // overload doc for the full root cause.
            CloudinaryUploader.upload(requireContext(), compressedUri, "callx/reel_comments", "image",
                null, true,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result result) {
                        if (!isAdded()) return;
                        uploadingImage = false;
                        if (pickedImageUri == null || !pickedImageUri.equals(originalUri)) return;
                        uploadedImageUrl = result.secureUrl;
                        if (progressImage != null) progressImage.setVisibility(View.GONE);
                    }

                    @Override public void onError(String message) {
                        if (!isAdded()) return;
                        uploadingImage = false;
                        Toast.makeText(requireContext(), getString(R.string.reel_c_photo_upload_failed), Toast.LENGTH_SHORT).show();
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
        updateSendState(true);
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private void onSendClicked() {
        if (uploadingImage) {
            Toast.makeText(requireContext(), getString(R.string.reel_c_photo_uploading), Toast.LENGTH_SHORT).show();
            return;
        }
        // ANTI-SPAM FIX: nothing previously stopped rapid-fire tapping of
        // send — each tap fired a full postComment()/postReply() Firebase
        // write with no gap. A short client-side cooldown is a cheap first
        // line of defense (server-side rules are the real backstop).
        long now = System.currentTimeMillis();
        if (now - lastCommentPostAt < COMMENT_COOLDOWN_MS) {
            CommentHaptics.reject(getView());
            Toast.makeText(requireContext(), getString(R.string.reel_c_too_fast),
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
        applyFilterAndSortNow();

        clearInput();
        clearDraft();
        clearPickedImage();
        pendingMentions.clear();

        // Accepted + visible instantly (local-first) -> confirm NOW, not after
        // the server ack, so the buzz lines up with the bubble appearing.
        // A failed write buzzes again with reject (see sendCommentToFirebase).
        CommentHaptics.confirm(getView());
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
                    CommentHaptics.reject(getView());
                    Toast.makeText(requireContext(),
                        getString(R.string.reel_c_comment_not_sent), Toast.LENGTH_SHORT).show();
                });
        } catch (Exception e) {
            local.sendState = ReelComment.SEND_STATE_FAILED;
            applyFilterAndSort();
            CommentHaptics.reject(getView());
        }
    }

    /** Tap-to-retry on a failed comment row: re-sends with the SAME push
     *  key (no duplicate comment created) and flips it back to "sending". */
    private void retryComment(ReelComment comment) {
        if (comment == null || comment.commentId == null) return;
        if (!ReelComment.SEND_STATE_FAILED.equals(comment.sendState)) return;
        comment.sendState = ReelComment.SEND_STATE_SENDING;
        CommentHaptics.tick(getView());
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
        ReelReply   mention = replyingToReplyMention;

        DatabaseReference repliesRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reelCommentReplies")
            .child(reelId)
            .child(parent.commentId);

        String key = repliesRef.push().getKey();
        if (key == null) return;

        // ── Local-first: same "show it now, reconcile later" pattern
        // postComment() already uses for top-level comments — see
        // ReelReply.sendState doc. Previously this fired a plain
        // fire-and-forget setValue() with no local row at all, so a
        // posted reply only ever appeared once the NEXT "View replies"
        // toggle re-fetched it from Firebase, and a failed/offline write
        // silently vanished with just a one-off Toast.
        ReelReply local = new ReelReply(key, parent.commentId, myUid, myName, myPhoto, text,
            System.currentTimeMillis());
        if (mention != null) {
            local.mentionUid  = mention.uid;
            local.mentionName = mention.ownerName;
        }
        local.sendState = ReelReply.SEND_STATE_SENDING;
        pendingRepliesByParent.computeIfAbsent(parent.commentId, k -> new java.util.ArrayList<>()).add(local);

        // Render instantly if this parent's replies section is the one
        // currently open — same instant-bubble feel as the main comment
        // list, without needing a live RecyclerView for replies.
        if (parent.commentId.equals(activeRepliesParentId)
                && activeRepliesContainer != null && activeRepliesToggle != null) {
            // PERF: do NOT fetch the rest of the thread just to append one reply. If more
            // replies exist on the server the new row goes right above "View N more".
            View row = buildReplyRow(local, parent, activeRepliesContainer, activeRepliesToggle);
            if (row != null) {
                int at = hasMoreRow(activeRepliesContainer)
                    ? activeRepliesContainer.getChildCount() - 1
                    : activeRepliesContainer.getChildCount();
                activeRepliesContainer.addView(row, at);
                java.util.List<ReelReply> cached = repliesCache.get(parent.commentId);
                if (cached != null) {
                    cached.add(local);
                    repliesShown.put(parent.commentId, cached.size());
                }
                updateReplyConnectors(activeRepliesContainer);
                activeRepliesContainer.setVisibility(View.VISIBLE);
                activeRepliesToggle.setText(R.string.reel_c_hide_replies);
            }
        }

        clearInput();
        clearDraft();
        cancelReply();
        pendingMentions.clear();

        CommentHaptics.confirm(getView());
        sendReplyToFirebase(repliesRef, key, local, parent, mention, text);
    }

    /** Fires the actual Firebase write for a local-first ReelReply and
     *  flips its sendState based on the real result — mirrors
     *  sendCommentToFirebase()'s role for top-level comments. Used by both
     *  postReply() and retryReply() (same push key, no duplicate reply). */
    private void sendReplyToFirebase(DatabaseReference repliesRef, String key, ReelReply local,
                                     ReelComment parent, @Nullable ReelReply mention, String text) {
        Map<String, Object> data = new HashMap<>();
        data.put("replyId",         key);
        data.put("parentCommentId", parent.commentId);
        data.put("uid",             local.uid);
        data.put("ownerName",       local.ownerName);
        data.put("ownerPhoto",      local.ownerPhoto);
        data.put("text",            local.text);
        data.put("timestamp",       local.timestamp);
        data.put("likesCount",      0);
        if (mention != null) {
            data.put("mentionUid",  mention.uid);
            data.put("mentionName", mention.ownerName);
        }

        try {
            repliesRef.child(key).setValue(data)
                .addOnSuccessListener(a -> {
                    if (!isAdded()) return;
                    local.sendState = null; // confirmed sent
                    java.util.List<ReelReply> pending = pendingRepliesByParent.get(parent.commentId);
                    if (pending != null) pending.remove(local);
                    sessionPostedReplies.computeIfAbsent(parent.commentId, k -> new java.util.ArrayList<>()).add(local);

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

                    // Re-render this row's send-state chrome (dim/failed
                    // text removed) if its parent's replies are still open.
                    if (parent.commentId.equals(activeRepliesParentId)
                            && activeRepliesContainer != null && activeRepliesToggle != null) {
                        loadRepliesInto(parent, activeRepliesContainer, activeRepliesToggle);
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    local.sendState = ReelReply.SEND_STATE_FAILED;
                    if (parent.commentId.equals(activeRepliesParentId)
                            && activeRepliesContainer != null && activeRepliesToggle != null) {
                        loadRepliesInto(parent, activeRepliesContainer, activeRepliesToggle);
                    }
                    CommentHaptics.reject(getView());
                    Toast.makeText(requireContext(),
                        getString(R.string.reel_c_reply_not_sent), Toast.LENGTH_SHORT).show();
                });
        } catch (Exception e) {
            local.sendState = ReelReply.SEND_STATE_FAILED;
            CommentHaptics.reject(getView());
            if (parent.commentId.equals(activeRepliesParentId)
                    && activeRepliesContainer != null && activeRepliesToggle != null) {
                loadRepliesInto(parent, activeRepliesContainer, activeRepliesToggle);
            }
        }
    }

    /** Tap-to-retry on a failed reply row — re-sends with the same push
     *  key (no duplicate reply) via sendReplyToFirebase(). */
    private void retryReply(ReelReply r, ReelComment parent, LinearLayout container, TextView tvToggle) {
        if (r == null || r.replyId == null || !ReelReply.SEND_STATE_FAILED.equals(r.sendState)) return;
        r.sendState = ReelReply.SEND_STATE_SENDING;
        CommentHaptics.tick(getView());
        loadRepliesInto(parent, container, tvToggle);

        DatabaseReference repliesRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reelCommentReplies")
            .child(reelId)
            .child(parent.commentId);
        // sendReplyToFirebase only reads mention.uid/ownerName — rebuild a
        // minimal stand-in from what's already stored on the failed reply
        // itself (mentionUid/mentionName), no need to keep the original
        // reply-to-reply mention object around just for a retry.
        ReelReply mention = null;
        if (r.mentionUid != null) {
            mention = new ReelReply();
            mention.uid       = r.mentionUid;
            mention.ownerName = r.mentionName;
        }
        sendReplyToFirebase(repliesRef, r.replyId, r, parent, mention, r.text);
    }

    // ── Like ──────────────────────────────────────────────────────────────────


    private void toggleLike(ReelComment comment, int position) {
        if (myUid.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.reel_c_login_to_like), Toast.LENGTH_SHORT).show();
            return;
        }
        resolveMyLike(comment.commentId, comment.likedByMe, liked -> {
            if (!isAdded()) return;
            if (liked != comment.likedByMe) {      // server already had my like: just sync the heart
                comment.likedByMe = liked;
                if (liked) myLikedIds.add(comment.commentId); else myLikedIds.remove(comment.commentId);
                if (adapter != null) adapter.notifyLikeChanged(comment.commentId);
                return;
            }
            applyCommentLike(comment);
        });
    }

    private void applyCommentLike(ReelComment comment) {
        final boolean currentlyLiked = comment.likedByMe;
        final boolean prevCreator = comment.creatorLiked;
        final boolean iAmReelOwner = myUid.equals(reelUid);
        final int prevCount = comment.likesCount;
        final String id = comment.commentId;
        // Tap-time haptic (covers the heart button AND double-tap-to-like,
        // both route through here). Rolled back below with a reject buzz.
        CommentHaptics.like(getView(), !currentlyLiked);

        // Optimistic local flip — heart/count update instantly; the live listener's
        // onChildChanged reconciles the real count moments later.
        myLikesTouched.add(id);
        comment.likedByMe = !currentlyLiked;
        if (comment.likedByMe) myLikedIds.add(id); else myLikedIds.remove(id);
        if (iAmReelOwner) comment.creatorLiked = comment.likedByMe;
        comment.likesCount = Math.max(0, comment.likesCount + (currentlyLiked ? -1 : 1));
        if (adapter != null) adapter.notifyLikeChanged(id);

        // ONE atomic multi-path write (count + my-like marker + creator flag); the old
        // code did a likedBy set AND a likesCount transaction = 2 round trips.
        CommentLikeWriter.write(CommentLikeWriter.commentPath(reelId, id), reelId, id, myUid,
            !currentlyLiked, iAmReelOwner, () -> {
                comment.likedByMe = currentlyLiked;
                if (currentlyLiked) myLikedIds.add(id); else myLikedIds.remove(id);
                comment.creatorLiked = prevCreator;
                comment.likesCount = prevCount;
                if (adapter != null && isAdded()) adapter.notifyLikeChanged(id);
                if (isAdded()) CommentHaptics.reject(getView());
            });

        if (comment.uid != null && !comment.uid.equals(myUid)) {
            if (!currentlyLiked) {
                ReelCommentNotifWorker.enqueueLike(
                    requireContext(), reelId, comment.uid, myUid, myName, id);
            } else {
                ReelCommentNotifWorker.cancelLike(requireContext(), reelId, myUid, id);
            }
        }
    }

    // ── Emoji reactions ───────────────────────────────────────────────────────

    private void postReaction(ReelComment comment, @Nullable String emoji, int position) {
        if (myUid.isEmpty()) return;

        DatabaseReference reactRef = FirebaseUtils.getReelCommentsRef(reelId)
            .child(comment.commentId)
            .child("reactions")
            .child(myUid);

        // Picking confirms, removing an existing reaction is a lighter tick.
        if (emoji == null) CommentHaptics.tick(getView());
        else CommentHaptics.confirm(getView());

        DatabaseReference.CompletionListener done = (err, ref) -> {
            if (err == null || !isAdded()) return;
            CommentHaptics.reject(getView());
            Toast.makeText(requireContext(), getString(R.string.reel_c_reaction_failed),
                Toast.LENGTH_SHORT).show();
        };
        if (emoji == null) {
            reactRef.removeValue(done);
        } else {
            reactRef.setValue(emoji, done);
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
            .setTitle(R.string.reel_c_edit_comment)
            .setView(et)
            .setPositiveButton(R.string.reel_c_save, (d, w) -> {
                String newText = et.getText().toString().trim();
                if (TextUtils.isEmpty(newText)) return;
                if (newText.equals(comment.text)) return;
                if (newText.length() > MAX_COMMENT_LENGTH) {
                    Toast.makeText(requireContext(), getString(R.string.reel_c_too_long_comment, MAX_COMMENT_LENGTH),
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
            .setNegativeButton(R.string.reel_c_cancel, null)
            .create());
    }

    // ── Pin comment ───────────────────────────────────────────────────────────

    private void togglePin(ReelComment comment) {
        if (!myUid.equals(reelUid)) {
            Toast.makeText(requireContext(), getString(R.string.reel_c_only_owner_pin),
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
            newPinnedState ? R.string.reel_c_pinned : R.string.reel_c_unpinned,
            Toast.LENGTH_SHORT).show();
    }

    // ── Report comment ────────────────────────────────────────────────────────

    private void showReportDialog(ReelComment comment) {
        String[] reasons = {
            "Spam", "Hate speech", "Harassment", "Misinformation",
            "Nudity or sexual content", "Violence", "Other"
        };   // stored in Firebase as-is - NOT localized (labels below are)
        final String[] reasonLabels = getResources().getStringArray(R.array.reel_c_report_reasons);

        AlertDialogStyler.showRounded(new AlertDialog.Builder(requireContext())
            .setTitle(R.string.reel_c_report_comment)
            .setItems(reasonLabels, (d, which) -> {
                submitReport(comment, reasons[which]);
            })
            .setNegativeButton(R.string.reel_c_cancel, null)
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
                Toast.makeText(requireContext(), getString(R.string.reel_c_comment_reported),
                    Toast.LENGTH_SHORT).show())
            .addOnFailureListener(e ->
                Toast.makeText(requireContext(), getString(R.string.reel_c_report_failed),
                    Toast.LENGTH_SHORT).show());
    }

    // ── Reply UI ──────────────────────────────────────────────────────────────

    private void startReply(ReelComment comment) {
        replyingToComment = comment;
        replyingToReplyMention = null;
        clearPickedImage();
        if (btnAttachPhoto != null) btnAttachPhoto.setVisibility(View.GONE);
        String name = comment.ownerName != null ? comment.ownerName : "user";
        if (tvReplyingTo   != null) tvReplyingTo.setText(getString(R.string.reel_c_replying_to, name));
        setReplyBarVisible(true);
        if (etComment      != null) {
            etComment.setText("");
            etComment.setHint(getString(R.string.reel_c_reply_hint, name));
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
        if (tvReplyingTo   != null) tvReplyingTo.setText(getString(R.string.reel_c_replying_to, name));
        setReplyBarVisible(true);
        if (etComment      != null) {
            etComment.setHint(getString(R.string.reel_c_reply_hint, name));
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
        setReplyBarVisible(false);
        if (etComment     != null) etComment.setHint(R.string.reel_c_write_comment);
        if (btnAttachPhoto != null) btnAttachPhoto.setVisibility(View.VISIBLE);
    }

    // ── Reply row pool (PERF) ───────────────────────────────────────────
    // Reply rows used to be inflated from XML on every expand / refresh / "view more" page and
    // thrown away on collapse. Rows detached from a thread now go back into this small pool and
    // are re-bound by buildReplyRow(); reset in releaseReplyRow() so nothing leaks between rows.
    private static final int REPLY_ROW_POOL_MAX = 16;
    private final java.util.ArrayDeque<View> replyRowPool = new java.util.ArrayDeque<>();

    private static final class ReplyRowDefaults {
        boolean clickable, longClickable;
        int timeColor;
    }
    private final java.util.WeakHashMap<View, ReplyRowDefaults> replyRowDefaults = new java.util.WeakHashMap<>();

    private View obtainReplyRow(LinearLayout container) {
        View pooled = replyRowPool.pollFirst();
        if (pooled != null) return pooled;
        View v = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_reel_reply, container, false);
        ReplyRowDefaults d = new ReplyRowDefaults();
        d.clickable     = v.isClickable();
        d.longClickable = v.isLongClickable();
        TextView tt = v.findViewById(R.id.tv_time);
        d.timeColor = tt != null ? tt.getCurrentTextColor() : 0;
        replyRowDefaults.put(v, d);
        return v;
    }

    /** Empties a replies container, sending its reply rows (not the "view more" row) to the pool. */
    private void recycleReplyRows(LinearLayout container) {
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View c = container.getChildAt(i);
            if (c.getTag(R.id.reel_reply_row_id) != null) releaseReplyRow(c);
        }
        container.removeAllViews();
    }

    /** Resets every piece of per-bind state buildReplyRow() can set, then pools the row. */
    private void releaseReplyRow(View v) {
        ReplyRowDefaults d = replyRowDefaults.get(v);
        if (d == null || replyRowPool.size() >= REPLY_ROW_POOL_MAX) return;
        v.animate().cancel();
        v.setAlpha(1f);
        v.setOnClickListener(null);
        v.setOnLongClickListener(null);
        v.setClickable(d.clickable);
        v.setLongClickable(d.longClickable);
        v.setTag(R.id.reel_reply_row_id, null);
        android.widget.ImageView iv = v.findViewById(R.id.iv_avatar);
        if (iv != null) {
            try { Glide.with(iv.getContext()).clear(iv); } catch (Exception ignored) {}
            iv.setTag(R.id.tag_avatar_uid, null);
        }
        View like = v.findViewById(R.id.btn_like_reply);
        if (like != null) {
            like.animate().cancel();
            like.setScaleX(1f); like.setScaleY(1f);
            like.setOnClickListener(null);
            like.setTag(null);                      // applyHeartState keys its pop animation off this
        }
        View cnt = v.findViewById(R.id.tv_likes_count);
        if (cnt != null) { cnt.animate().cancel(); cnt.setScaleX(1f); cnt.setScaleY(1f); }
        View rep = v.findViewById(R.id.btn_reply);
        if (rep != null) rep.setOnClickListener(null);
        TextView tt = v.findViewById(R.id.tv_time);
        if (tt != null) tt.setTextColor(d.timeColor);
        replyRowPool.addLast(v);
    }

    private DatabaseReference repliesRefFor(ReelComment parent) {
        return FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reelCommentReplies").child(reelId).child(parent.commentId);
    }

    /** Parses one reply for display; null = skip (empty / blocked author). */
    @Nullable
    private ReelReply parseReplyForDisplay(DataSnapshot s) {
        try {
            ReelReply r = s.getValue(ReelReply.class);
            if (r == null || TextUtils.isEmpty(r.text)) return null;
            if (r.uid != null && blockedUids.contains(r.uid)) return null; // blocked user's reply, hide it
            if (r.replyId == null) r.replyId = s.getKey();
            normalizeReplyLikes(r);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private int moreRemaining(ReelComment parent, int loaded) {
        return Math.max(1, parent.replyCount - loaded);
    }

    /** (Re)loads the thread's first page from the SERVER: only as many rows as are/should be
     *  visible (REPLIES_INITIAL, or what the user had already opened) + 1 to learn whether more
     *  exist. A 500-reply thread now costs ~4 rows, not 500. */
    private void loadRepliesInto(ReelComment parent,
                                 LinearLayout container, TextView tvToggle) {
        // Remember this as the "active" replies UI so a reply posted while
        // it's open can be appended instantly — see field doc above.
        activeRepliesContainer = container;
        activeRepliesToggle    = tvToggle;
        activeRepliesParentId  = parent.commentId;

        // Offline, this one-off read never answers and the toggle would sit on
        // "Loading…" forever - arm a timeout. A late success still wins (it
        // simply renders over the retry label).
        final int loadToken = ++repliesLoadSeq;
        repliesLoadToken.put(parent.commentId, loadToken);
        refreshHandler.postDelayed(() -> {
            Integer t = repliesLoadToken.get(parent.commentId);
            if (t != null && t == loadToken) onRepliesLoadFailed(parent, container, tvToggle);
        }, isOnlineNow() ? LOAD_TIMEOUT_ONLINE_MS : LOAD_TIMEOUT_OFFLINE_MS);

        Integer openBefore = repliesShown.get(parent.commentId);
        final int fetch = Math.max(REPLIES_INITIAL, openBefore != null ? openBefore : 0);

        repliesRefFor(parent).orderByKey().limitToFirst(fetch + 1)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (!isAdded()) return;
                    repliesLoadToken.remove(parent.commentId);
                    final boolean wasVisible = container.getVisibility() == View.VISIBLE;
                    recycleReplyRows(container);
                    final java.util.List<ReelReply> list = new java.util.ArrayList<>();
                    java.util.Set<String> confirmedIds = new java.util.HashSet<>();
                    int seen = 0;
                    String lastKey = null;
                    boolean more = false;
                    for (DataSnapshot s : snapshot.getChildren()) {
                        if (seen >= fetch) { more = true; break; }   // the +1 probe row
                        seen++;
                        lastKey = s.getKey();
                        ReelReply r = parseReplyForDisplay(s);
                        if (r == null) continue;
                        confirmedIds.add(r.replyId);
                        if (pendingDeletes.containsKey("r:" + r.replyId)) continue; // waiting out Undo
                        registerMentionCandidate(r.uid, r.ownerName, r.ownerPhoto);
                        list.add(r);
                    }
                    // Merge in still-pending (sending/failed) local replies for
                    // this parent that Firebase hasn't confirmed yet — without
                    // this, collapsing then re-expanding "View replies" would
                    // silently drop an in-flight or failed reply on the floor
                    // since this listener rebuilds the container from scratch.
                    java.util.List<ReelReply> pending = pendingRepliesByParent.get(parent.commentId);
                    if (pending != null) {
                        for (ReelReply r : new java.util.ArrayList<>(pending)) {
                            if (confirmedIds.contains(r.replyId)) continue;
                            if (pendingDeletes.containsKey("r:" + r.replyId)) continue;
                            confirmedIds.add(r.replyId);
                            list.add(r);
                        }
                    }
                    // Replies I posted this session are the NEWEST keys, so a paged read of
                    // a long thread does not contain them — keep them visible.
                    java.util.List<ReelReply> mine = sessionPostedReplies.get(parent.commentId);
                    if (mine != null) {
                        for (ReelReply r : mine) {
                            if (confirmedIds.contains(r.replyId)) continue;
                            if (pendingDeletes.containsKey("r:" + r.replyId)) continue;
                            confirmedIds.add(r.replyId);
                            list.add(r);
                        }
                    }
                    final int count = list.size();
                    repliesCache.put(parent.commentId, list);
                    repliesShown.put(parent.commentId, count);
                    if (lastKey != null) repliesCursor.put(parent.commentId, lastKey);
                    else repliesCursor.remove(parent.commentId);
                    repliesHasMore.put(parent.commentId, more);
                    for (int i = 0; i < count; i++) {
                        View row = buildReplyRow(list.get(i), parent, container, tvToggle);
                        if (row != null) container.addView(row);
                    }
                    if (more) {
                        container.addView(buildMoreRow(parent, container, tvToggle,
                            moreRemaining(parent, count)));
                    }
                    updateReplyConnectors(container);
                    container.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                    tvToggle.setText(count > 0 ? R.string.reel_c_hide_replies : R.string.reel_c_no_replies);
                    // Short slide+fade only on a fresh expand — not on a
                    // refresh of an already-open thread (avoids flicker).
                    if (count > 0 && !wasVisible) expandReplies(container);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isAdded()) return;
                    onRepliesLoadFailed(parent, container, tvToggle);
                }
            });
    }

    // ── Reply paging helpers ──────────────────────────────────────────────

    private static boolean hasMoreRow(LinearLayout container) {
        int n = container.getChildCount();
        return n > 0 && MORE_ROW_TAG.equals(container.getChildAt(n - 1).getTag());
    }

    /** "View N more replies" row (connector as the last row of the thread). */
    private View buildMoreRow(ReelComment parent, LinearLayout container,
                              TextView tvToggle, int remaining) {
        View row = LayoutInflater.from(container.getContext())
            .inflate(R.layout.item_reel_reply_more, container, false);
        row.setTag(MORE_ROW_TAG);
        TextView tv = row.findViewById(R.id.tv_reply_more);
        ReelCommentsAdapter.asButton(tv);
        tv.setText(tv.getResources().getQuantityString(R.plurals.reel_c_view_more_replies, remaining, remaining));
        tv.setOnClickListener(v -> showMoreReplies(parent, container, tvToggle, REPLIES_STEP));
        return row;
    }

    /** Fetches the NEXT page from the server (startAfter cursor, REPLIES_STEP rows) and appends
     *  it — no rebuild of the rows already on screen. */
    private void showMoreReplies(ReelComment parent, LinearLayout container,
                                 TextView tvToggle, int step) {
        final String id = parent.commentId;
        final java.util.List<ReelReply> cur0 = repliesCache.get(id);
        final String cursor = repliesCursor.get(id);
        if (cur0 == null || cursor == null || !Boolean.TRUE.equals(repliesHasMore.get(id))) return;
        if (!repliesLoadingMore.add(id)) return;               // a fetch is already running

        final TextView moreTv = hasMoreRow(container)
            ? (TextView) container.getChildAt(container.getChildCount() - 1)
                .findViewById(R.id.tv_reply_more) : null;
        final int rem = moreRemaining(parent, cur0.size());
        if (moreTv != null) { moreTv.setEnabled(false); moreTv.setText(R.string.reel_c_loading); }

        final int token = ++repliesLoadSeq;
        repliesMoreToken.put(id, token);
        final Runnable restore = () -> {
            repliesLoadingMore.remove(id);
            repliesMoreToken.remove(id);
            if (moreTv != null) {
                moreTv.setEnabled(true);
                moreTv.setText(moreTv.getResources().getQuantityString(
                    R.plurals.reel_c_view_more_replies, rem, rem));
            }
        };
        refreshHandler.postDelayed(() -> {
            Integer t = repliesMoreToken.get(id);
            if (t != null && t == token) restore.run();         // offline: never answered
        }, isOnlineNow() ? LOAD_TIMEOUT_ONLINE_MS : LOAD_TIMEOUT_OFFLINE_MS);

        repliesRefFor(parent).orderByKey().startAfter(cursor).limitToFirst(step + 1)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                    Integer t = repliesMoreToken.get(id);
                    if (t == null || t != token) return;         // timed out / collapsed meanwhile
                    repliesMoreToken.remove(id);
                    repliesLoadingMore.remove(id);
                    java.util.List<ReelReply> cur = repliesCache.get(id);
                    if (!isAdded() || cur == null) return;       // thread collapsed
                    if (hasMoreRow(container)) container.removeViewAt(container.getChildCount() - 1);

                    java.util.Set<String> have = new java.util.HashSet<>();
                    for (ReelReply r : cur) have.add(r.replyId);
                    int seen = 0;
                    String lastKey = cursor;
                    boolean more = false;
                    for (DataSnapshot sn : snapshot.getChildren()) {
                        if (seen >= step) { more = true; break; }
                        seen++;
                        lastKey = sn.getKey();
                        ReelReply r = parseReplyForDisplay(sn);
                        if (r == null || have.contains(r.replyId)) continue;   // blocked / already shown
                        if (pendingDeletes.containsKey("r:" + r.replyId)) continue;
                        registerMentionCandidate(r.uid, r.ownerName, r.ownerPhoto);
                        cur.add(r);
                        have.add(r.replyId);
                        View row = buildReplyRow(r, parent, container, tvToggle);
                        if (row != null) container.addView(row);
                    }
                    repliesCursor.put(id, lastKey);
                    repliesHasMore.put(id, more);
                    repliesShown.put(id, cur.size());
                    if (more) {
                        container.addView(buildMoreRow(parent, container, tvToggle,
                            moreRemaining(parent, cur.size())));
                    }
                    updateReplyConnectors(container);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    Integer t = repliesMoreToken.get(id);
                    if (t != null && t == token && isAdded()) restore.run();
                }
            });
    }

    // ── Reply thread visuals (trunk + ↳ connector, expand/collapse) ──────────

    /** Marks the last reply so its trunk stops at the curve; all others
     *  keep the line running down to the next reply. */
    private static void updateReplyConnectors(LinearLayout container) {
        int n = container.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = container.getChildAt(i).findViewById(R.id.reply_connector);
            if (c instanceof ReplyConnectorView) {
                ((ReplyConnectorView) c).setLast(i == n - 1);
            }
        }
    }

    private static final long REPLY_ANIM_MS = 160L;

    /** The parent-row trunk that belongs to this replies container (or null). */
    @Nullable
    private static View replyTrunk(LinearLayout container) {
        return container instanceof ReplyThreadContainer
            ? ((ReplyThreadContainer) container).getTrunk() : null;
    }

    /** Short slide-down + fade-in. Only alpha/translationY (GPU-cheap). */
    private void expandReplies(LinearLayout container) {
        float dy = 8f * container.getResources().getDisplayMetrics().density;
        container.animate().cancel();
        container.setAlpha(0f);
        container.setTranslationY(-dy);
        container.animate().alpha(1f).translationY(0f)
            .setDuration(REPLY_ANIM_MS).start();
        // Parent trunk fades in with the replies (alpha only; it is vertical,
        // so a slide would not be visible anyway).
        View trunk = replyTrunk(container);
        if (trunk != null) {
            trunk.animate().cancel();
            trunk.setAlpha(0f);
            trunk.animate().alpha(1f).setDuration(REPLY_ANIM_MS).start();
        }
    }

    /** Short fade + slide-up, then really hide and free the row views. */
    private void collapseReplies(LinearLayout container) {
        float dy = 6f * container.getResources().getDisplayMetrics().density;
        container.animate().cancel();
        final View trunk = replyTrunk(container);
        if (trunk != null) {
            trunk.animate().cancel();
            trunk.animate().alpha(0f).setDuration(REPLY_ANIM_MS).start();
        }
        container.animate().alpha(0f).translationY(-dy)
            .setDuration(REPLY_ANIM_MS)
            .withEndAction(() -> {
                // Guard: the row may have been recycled/rebound mid-animation
                // (adapter resets alpha to 1) — never wipe someone else's replies.
                if (container.getAlpha() > 0.05f) return;
                container.setVisibility(View.GONE);   // also hides the trunk
                recycleReplyRows(container);
                container.setAlpha(1f);
                container.setTranslationY(0f);
                if (trunk != null) trunk.setAlpha(1f);
            }).start();
    }

    /** Builds a fully interactive reply row — avatar, like (with count),
     *  reply (tags the author, flattened into the same parent thread),
     *  edited label, and a long-press menu for edit/delete/report. This
     *  matches ReelCommentsAdapter's top-level comment behavior 1:1. */
    @Nullable
    private View buildReplyRow(ReelReply r, ReelComment parent,
                               LinearLayout container, TextView tvToggle) {
        try {
            View v = obtainReplyRow(container);
            v.setTag(R.id.reel_reply_row_id, r.replyId);

            android.widget.ImageView ivAvatar = v.findViewById(R.id.iv_avatar);
            TextView tvName     = v.findViewById(R.id.tv_name);
            TextView tvText     = v.findViewById(R.id.tv_text);
            TextView tvTime     = v.findViewById(R.id.tv_time);
            TextView tvEdited   = v.findViewById(R.id.tv_edited);
            TextView tvAuthorBadge  = v.findViewById(R.id.tv_author_badge);
            TextView tvCreatorLiked = v.findViewById(R.id.tv_creator_liked);
            TextView btnReplyTo = v.findViewById(R.id.btn_reply);
            ReelCommentsAdapter.asButton(btnReplyTo);
            ImageButton btnLike = v.findViewById(R.id.btn_like_reply);
            TextView tvLikes    = v.findViewById(R.id.tv_likes_count);

            if (tvName != null) tvName.setText(r.ownerName != null ? r.ownerName : getString(R.string.reel_c_user_fallback));
            android.widget.ImageView ivVerified = v.findViewById(R.id.iv_verified);
            com.callx.app.utils.VerifiedBadgeUtils.bindForUid(ivVerified, r.uid);
            if (tvTime != null) tvTime.setText(formatTime(r.timestamp));
            if (tvEdited != null) tvEdited.setVisibility(r.isEdited ? View.VISIBLE : View.GONE);

            if (tvAuthorBadge != null) {
                tvAuthorBadge.setVisibility(
                    !reelUid.isEmpty() && reelUid.equals(r.uid) ? View.VISIBLE : View.GONE);
            }
            if (tvCreatorLiked != null) {
                boolean likedByCreator = r.creatorLiked;
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

            ReelCommentsAdapter.applyHeartState(btnLike, tvLikes,
                r.likedByMe, r.likesCount, false, "reply");
            if (btnLike != null) {
                btnLike.setOnClickListener(v2 ->
                    toggleReplyLike(r, parent, container, tvToggle, btnLike, tvLikes));
            }

            if (btnReplyTo != null) {
                btnReplyTo.setOnClickListener(v2 -> startReplyToReply(parent, r));
            }

            v.setOnLongClickListener(v2 -> {
                showReplyContextMenu(r, parent, container, tvToggle);
                return true;
            });

            // ── Local-first send state (Instagram-level optimistic write,
            //    extended to replies — see ReelReply.sendState doc) ──────
            if (ReelReply.SEND_STATE_SENDING.equals(r.sendState)) {
                v.setAlpha(0.55f);
                v.setLongClickable(false);
            } else if (ReelReply.SEND_STATE_FAILED.equals(r.sendState)) {
                v.setAlpha(1f);
                if (tvTime != null) {
                    tvTime.setText(R.string.reel_c_failed_retry);
                    tvTime.setTextColor(androidx.core.content.ContextCompat.getColor(
                        requireContext(), R.color.reel_error_red));
                }
                v.setOnClickListener(v2 -> retryReply(r, parent, container, tvToggle));
            } else {
                v.setAlpha(1f);
            }

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
                                 LinearLayout container, TextView tvToggle,
                                 @Nullable ImageButton btnLike, @Nullable TextView tvLikes) {
        if (myUid.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.reel_c_login_to_like), Toast.LENGTH_SHORT).show();
            return;
        }
        resolveMyLike(reply.replyId, reply.likedByMe, liked -> {
            if (!isAdded()) return;
            if (liked != reply.likedByMe) {
                reply.likedByMe = liked;
                if (liked) myLikedIds.add(reply.replyId); else myLikedIds.remove(reply.replyId);
                ReelCommentsAdapter.applyHeartState(btnLike, tvLikes, liked, reply.likesCount, false, "reply");
                return;
            }
            applyReplyLike(reply, parent, btnLike, tvLikes);
        });
    }

    private void applyReplyLike(ReelReply reply, ReelComment parent,
                                @Nullable ImageButton btnLike, @Nullable TextView tvLikes) {
        final boolean currentlyLiked = reply.likedByMe;
        final boolean prevCreator = reply.creatorLiked;
        final boolean iAmReelOwner = myUid.equals(reelUid);
        final int prevCount = reply.likesCount;
        final String id = reply.replyId;
        CommentHaptics.like(btnLike != null ? btnLike : getView(), !currentlyLiked);

        // Optimistic flip — heart + count update instantly (same pop animation as comments).
        // The rows hold the same ReelReply objects as repliesCache, so this IS the cached
        // state: no refetch, no rebuild (v470).
        myLikesTouched.add(id);
        reply.likedByMe = !currentlyLiked;
        if (reply.likedByMe) myLikedIds.add(id); else myLikedIds.remove(id);
        if (iAmReelOwner) reply.creatorLiked = reply.likedByMe;
        reply.likesCount = Math.max(0, prevCount + (currentlyLiked ? -1 : 1));
        ReelCommentsAdapter.applyHeartState(btnLike, tvLikes,
            reply.likedByMe, reply.likesCount, true, "reply");

        // ONE atomic multi-path write instead of likedBy.setValue + likesCount transaction.
        CommentLikeWriter.write(CommentLikeWriter.replyPath(reelId, parent.commentId, id),
            reelId, id, myUid, !currentlyLiked, iAmReelOwner, () -> {
                reply.likedByMe = currentlyLiked;
                if (currentlyLiked) myLikedIds.add(id); else myLikedIds.remove(id);
                reply.creatorLiked = prevCreator;
                reply.likesCount = prevCount;
                if (isAdded()) {
                    ReelCommentsAdapter.applyHeartState(btnLike, tvLikes,
                        currentlyLiked, prevCount, true, "reply");
                    CommentHaptics.reject(btnLike != null ? btnLike : getView());
                }
            });

        if (reply.uid != null && !reply.uid.equals(myUid)) {
            if (!currentlyLiked) {
                ReelCommentNotifWorker.enqueueLike(
                    requireContext(), reelId, reply.uid, myUid, myName, id);
            } else {
                ReelCommentNotifWorker.cancelLike(requireContext(), reelId, myUid, id);
            }
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
            opts.add(getString(R.string.reel_c_edit_reply));
            actions.add(() -> showEditReplyDialog(reply, parent, container, tvToggle));
        }
        if (!isOwn) {
            opts.add(getString(R.string.reel_c_report_reply));
            actions.add(() -> showReportReplyDialog(reply));
        }
        if (isOwn || isReelOwner) {
            opts.add(getString(R.string.reel_c_delete_reply));
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
            .setTitle(R.string.reel_c_edit_reply)
            .setView(et)
            .setPositiveButton(R.string.reel_c_save, (d, w) -> {
                String newText = et.getText().toString().trim();
                if (TextUtils.isEmpty(newText) || newText.equals(reply.text)) return;
                if (newText.length() > MAX_COMMENT_LENGTH) {
                    Toast.makeText(requireContext(), getString(R.string.reel_c_too_long_reply, MAX_COMMENT_LENGTH),
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
            .setNegativeButton(R.string.reel_c_cancel, null)
            .create());
    }

    private void showDeleteReplyDialog(ReelReply reply, ReelComment parent,
                                       LinearLayout container, TextView tvToggle) {
        AlertDialogStyler.showReusableConfirm(requireContext(), "delete_reel_reply",
            AlertDialogStyler.DialogSize.DEFAULT,
            getString(R.string.reel_c_delete_reply_title),
            getString(R.string.reel_c_delete_reply_msg),
            getString(R.string.reel_c_delete), () -> deleteReplyWithUndo(reply, parent, container, tvToggle),
            null, null,
            getString(R.string.reel_c_cancel));
    }

    private void deleteReply(ReelReply reply, ReelComment parent,
                             LinearLayout container, TextView tvToggle) {
        java.util.List<ReelReply> mine = sessionPostedReplies.get(parent.commentId);
        if (mine != null) mine.removeIf(x -> reply.replyId != null && reply.replyId.equals(x.replyId));
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
                        // The delete may be committed seconds later (Undo window):
                        // only refresh if this is still the open thread, so a
                        // recycled/other row is never rebuilt with these replies.
                        if (isAdded() && container == activeRepliesContainer
                                && parent.commentId.equals(activeRepliesParentId)) {
                            loadRepliesInto(parent, container, tvToggle);
                        }
                    }
                });
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.reel_c_delete_reply_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void showReportReplyDialog(ReelReply reply) {
        String[] reasons = {
            "Spam", "Hate speech", "Harassment", "Misinformation",
            "Nudity or sexual content", "Violence", "Other"
        };   // stored in Firebase as-is - NOT localized (labels below are)
        final String[] reasonLabels = getResources().getStringArray(R.array.reel_c_report_reasons);
        AlertDialogStyler.showRounded(new AlertDialog.Builder(requireContext())
            .setTitle(R.string.reel_c_report_reply)
            .setItems(reasonLabels, (d, which) -> submitReplyReport(reply, reasons[which]))
            .setNegativeButton(R.string.reel_c_cancel, null)
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
                Toast.makeText(requireContext(), getString(R.string.reel_c_reply_reported),
                    Toast.LENGTH_SHORT).show())
            .addOnFailureListener(e ->
                Toast.makeText(requireContext(), getString(R.string.reel_c_report_failed),
                    Toast.LENGTH_SHORT).show());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void showDeleteDialog(ReelComment comment, int position) {
        AlertDialogStyler.showReusableConfirm(requireContext(), "delete_reel_comment",
            AlertDialogStyler.DialogSize.DEFAULT,
            getString(R.string.reel_c_delete_comment_title),
            getString(R.string.reel_c_delete_comment_msg),
            getString(R.string.reel_c_delete), () -> deleteCommentWithUndo(comment),
            null, null,
            getString(R.string.reel_c_cancel));
    }

    private void deleteComment(ReelComment comment) {
        try {
            FirebaseUtils.getReelCommentsRef(reelId).child(comment.commentId).removeValue();
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("reelCommentReplies")
                .child(reelId).child(comment.commentId).removeValue();
            incrementCommentsCount(-1);
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.reel_c_delete_failed), Toast.LENGTH_SHORT).show();
        }
    }

    // ── Delete with Undo ──────────────────────────────────────────────────────
    // Deleting hides the comment/reply locally and shows "Deleted · Undo" for
    // ~4s. Only when the window closes without Undo is the real Firebase
    // delete sent (commitPendingDelete). Keys: "c:<commentId>" / "r:<replyId>".
    private static final int UNDO_WINDOW_MS = 4000;
    private final Map<String, Runnable> pendingDeletes = new HashMap<>();

    private int pendingCommentDeleteCount() {
        int n = 0;
        for (String k : pendingDeletes.keySet()) if (k.startsWith("c:")) n++;
        return n;
    }

    private void commitPendingDelete(String key) {
        Runnable r = pendingDeletes.remove(key);
        if (r != null) r.run();
    }

    private void showUndoSnackbar(int msgRes, String key, Runnable onUndo, Runnable onCommit) {
        pendingDeletes.put(key, onCommit);
        View anchorRoot = fragmentRoot != null ? fragmentRoot : getView();
        try {
            if (anchorRoot == null) throw new IllegalStateException("no view");
            com.google.android.material.snackbar.Snackbar sb =
                com.google.android.material.snackbar.Snackbar.make(anchorRoot, msgRes, UNDO_WINDOW_MS);
            sb.setAction(R.string.reel_c_undo, v -> {
                if (pendingDeletes.remove(key) != null) onUndo.run();
            });
            sb.addCallback(new com.google.android.material.snackbar.Snackbar.Callback() {
                @Override public void onDismissed(com.google.android.material.snackbar.Snackbar bar, int event) {
                    // Everything except the Undo tap (timeout, swipe-away,
                    // replaced by a newer snackbar) means: really delete.
                    if (event != DISMISS_EVENT_ACTION) commitPendingDelete(key);
                }
            });
            if (etComment != null && etComment.isAttachedToWindow()) sb.setAnchorView(etComment);
            sb.show();
        } catch (Exception e) {
            commitPendingDelete(key);   // no snackbar possible -> behave like before
        }
    }

    private void deleteCommentWithUndo(ReelComment comment) {
        showUndoSnackbar(R.string.reel_c_comment_deleted, "c:" + comment.commentId,
            this::applyFilterAndSortNow,              // Undo: bring it back
            () -> deleteComment(comment));            // timeout: real delete
        applyFilterAndSortNow();                      // hide now
    }

    private void deleteReplyWithUndo(ReelReply reply, ReelComment parent,
                                     LinearLayout container, TextView tvToggle) {
        if (reply.replyId == null) { deleteReply(reply, parent, container, tvToggle); return; }
        showUndoSnackbar(R.string.reel_c_reply_deleted, "r:" + reply.replyId,
            () -> { if (isAdded()) loadRepliesInto(parent, container, tvToggle); },
            () -> {
                // also forget a still-local (sending/failed) copy of this reply
                java.util.List<ReelReply> pl = pendingRepliesByParent.get(parent.commentId);
                if (pl != null) pl.removeIf(x -> reply.replyId.equals(x.replyId));
                deleteReply(reply, parent, container, tvToggle);
            });
        hideReplyLocally(reply, parent, container, tvToggle);
    }

    /** Removes one reply row (and its cache entry) without a rebuild/network. */
    private void hideReplyLocally(ReelReply reply, ReelComment parent,
                                  LinearLayout container, TextView tvToggle) {
        View row = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View c = container.getChildAt(i);
            if (reply.replyId.equals(c.getTag(R.id.reel_reply_row_id))) { row = c; break; }
        }
        if (row == null) return;
        container.removeView(row);
        releaseReplyRow(row);

        boolean empty;
        java.util.List<ReelReply> list = repliesCache.get(parent.commentId);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (reply.replyId.equals(list.get(i).replyId)) {
                    list.remove(i);
                    Integer s = repliesShown.get(parent.commentId);
                    if (s != null && i < s) repliesShown.put(parent.commentId, s - 1);
                    break;
                }
            }
            // the "View N more" row (if any) stays as is — it is driven by the server cursor
            empty = list.isEmpty() && !Boolean.TRUE.equals(repliesHasMore.get(parent.commentId));
        } else {
            empty = true;
            for (int i = 0; i < container.getChildCount(); i++) {
                if (container.getChildAt(i).getTag(R.id.reel_reply_row_id) != null) { empty = false; break; }
            }
        }
        if (empty) {
            container.setVisibility(View.GONE);       // also hides the parent trunk
            tvToggle.setText(R.string.reel_c_no_replies);
        }
        updateReplyConnectors(container);
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


    // ── Error & offline states ────────────────────────────────────────────────
    // Three levels, from loudest to quietest:
    //   1. Full-screen error (layout_error_state) - the FIRST page failed or
    //      timed out and there is nothing to show. Retry re-attaches listeners.
    //   2. Status banner (tv_status_banner) - the list IS showing (live or
    //      disk-cached) but we're offline / a refresh failed. Non-blocking.
    //   3. Inline retry chips - "load older" page failed (tv_loading_older) and
    //      "load replies" failed (the replies toggle itself becomes the retry).
    // Realtime Database never calls back while offline, so "failed" has to be
    // detected with a timeout + a connectivity watcher, not only onCancelled.

    private static final long LOAD_TIMEOUT_ONLINE_MS  = 10_000L;
    private static final long LOAD_TIMEOUT_OFFLINE_MS = 3_000L;
    private static final long OFFLINE_DEBOUNCE_MS     = 1_500L;
    private static final int  BANNER_NONE = 0, BANNER_OFFLINE = 1, BANNER_REFRESH_FAILED = 2;

    /** True once the first comments page has really resolved (data arrived, or
     *  the server confirmed the thread is empty). Gates the "empty" state. */
    private boolean firstPageReady = false;
    private boolean errorStateShown = false;
    private int     bannerMode = BANNER_NONE;

    private final Map<String, Integer> repliesLoadToken = new HashMap<>();
    private int repliesLoadSeq = 0;

    private ConnectivityManager.NetworkCallback netCallback;
    private ConnectivityManager netManager;

    private final Runnable initialLoadTimeoutRunnable = () -> {
        if (isAdded() && !firstPageReady) onInitialLoadFailed();
    };
    private final Runnable offlineCheckRunnable = () -> {
        if (!isAdded() || isOnlineNow()) return;
        applyOfflineUi();
    };

    private boolean isOnlineNow() {
        Context c = getContext();
        return c == null || NetworkUtils.isOnline(c);
    }

    /** Fires once the first page has resolved - including the EMPTY case, which
     *  a ChildEventListener alone can never signal (no children = no callback). */
    private void armFirstPageSignal() {
        firstPageReady = false;
        refreshHandler.removeCallbacks(initialLoadTimeoutRunnable);
        refreshHandler.postDelayed(initialLoadTimeoutRunnable,
            isOnlineNow() ? LOAD_TIMEOUT_ONLINE_MS : LOAD_TIMEOUT_OFFLINE_MS);
        if (commentsQuery == null) return;
        commentsQuery.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                markFirstPageReady();
                applyFilterAndSort();   // resolves empty-vs-list now that we know
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (isAdded() && !firstPageReady) onInitialLoadFailed();
            }
        });
    }

    private void markFirstPageReady() {
        if (firstPageReady) return;
        firstPageReady = true;
        refreshHandler.removeCallbacks(initialLoadTimeoutRunnable);
        if (bannerMode == BANNER_REFRESH_FAILED) hideStatusBanner();
    }

    private void onInitialLoadFailed() {
        refreshHandler.removeCallbacks(initialLoadTimeoutRunnable);
        if (!isAdded()) return;
        boolean offline = !isOnlineNow();
        if (adapter != null && adapter.getItemCount() > 0) {
            // We have rows on screen (live or disk cache) - don't wipe them.
            showStatusBanner(offline ? BANNER_OFFLINE : BANNER_REFRESH_FAILED);
        } else {
            showErrorState(offline);
        }
    }

    private void showErrorState(boolean offline) {
        if (layoutErrorState == null) return;
        hideCommentsShimmer();   // idempotent; stops + hides the skeleton
        errorStateShown = true;
        if (rvComments != null) rvComments.setVisibility(View.GONE);
        if (tvEmpty    != null) tvEmpty.setVisibility(View.GONE);
        bindErrorTexts(offline);
        layoutErrorState.setVisibility(View.VISIBLE);
    }

    private void bindErrorTexts(boolean offline) {
        if (tvErrorIcon    != null) tvErrorIcon.setText(offline ? "📡" : "⚠️");
        if (tvErrorTitle   != null) tvErrorTitle.setText(offline
            ? R.string.reel_c_err_offline_title : R.string.reel_c_err_load_title);
        if (tvErrorMessage != null) tvErrorMessage.setText(offline
            ? R.string.reel_c_err_offline_msg : R.string.reel_c_err_load_msg);
    }

    /** Retry for the first page: re-attaches the live listener (Firebase drops
     *  it after onCancelled) and re-arms the resolve signal + timeout. */
    private void retryInitialLoad() { reloadInitial(true); }

    private void reloadInitial(boolean userTapped) {
        if (!isAdded() || reelId.isEmpty()) return;
        if (userTapped) CommentHaptics.tick(getView());
        hideStatusBanner();
        errorStateShown = false;
        if (layoutErrorState != null) layoutErrorState.setVisibility(View.GONE);
        try {
            if (commentsListener != null && commentsQuery != null)
                commentsQuery.removeEventListener(commentsListener);
        } catch (Exception ignored) {}
        if (adapter == null || adapter.getItemCount() == 0) showCommentsShimmer();
        else if (rvComments != null) rvComments.setVisibility(View.VISIBLE);
        loadComments();          // dup-guarded by loadedCommentIds
        armFirstPageSignal();
    }

    private void showStatusBanner(int mode) {
        if (tvStatusBanner == null) return;
        bannerMode = mode;
        if (mode == BANNER_NONE) { hideStatusBanner(); return; }
        boolean tappable = mode == BANNER_REFRESH_FAILED;
        tvStatusBanner.setText(tappable
            ? R.string.reel_c_refresh_failed_banner : R.string.reel_c_offline_banner);
        tvStatusBanner.setClickable(tappable);
        androidx.core.view.ViewCompat.setAccessibilityDelegate(tvStatusBanner, null);
        if (tappable) ReelCommentsAdapter.asButton(tvStatusBanner);
        tvStatusBanner.setVisibility(View.VISIBLE);
    }

    private void hideStatusBanner() {
        bannerMode = BANNER_NONE;
        if (tvStatusBanner != null) tvStatusBanner.setVisibility(View.GONE);
    }

    private void onOlderLoadFailed() {
        loadingOlder = false;
        olderLoadFailed = true;
        olderRequestId++;                       // ignore any late result of this request
        refreshHandler.removeCallbacks(olderTimeoutRunnable);
        if (tvLoadingOlder != null) {
            tvLoadingOlder.setText(R.string.reel_c_older_failed);
            tvLoadingOlder.setMinHeight(
                (int) (48 * tvLoadingOlder.getResources().getDisplayMetrics().density));
            tvLoadingOlder.setGravity(android.view.Gravity.CENTER);
            ReelCommentsAdapter.asButton(tvLoadingOlder);
            tvLoadingOlder.setOnClickListener(v -> {
                CommentHaptics.tick(v);
                olderLoadFailed = false;
                maybeLoadOlderComments();
            });
            tvLoadingOlder.setVisibility(View.VISIBLE);
        }
        CommentHaptics.reject(getView());
    }

    private void onRepliesLoadFailed(ReelComment parent, LinearLayout container, TextView tvToggle) {
        repliesLoadToken.remove(parent.commentId);
        if (!isAdded()) return;
        // Thread already open (this was only a background refresh after a like /
        // edit): keep what's on screen, stay quiet (no buzz either - the user
        // didn't ask for anything). Otherwise the toggle itself becomes the
        // retry - tapping it runs the normal expand path again.
        if (container.getVisibility() == View.VISIBLE) return;
        CommentHaptics.reject(getView());
        tvToggle.setText(R.string.reel_c_replies_failed_retry);
    }

    // ── Connectivity watcher ──────────────────────────────────────────────────

    @android.annotation.SuppressLint("MissingPermission")
    private void registerNetworkWatcher() {
        Context c = getContext();
        if (c == null) return;
        try {
            netManager = (ConnectivityManager) c.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (netManager == null) return;
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(@NonNull Network network) {
                    refreshHandler.post(() -> onNetworkBack());
                }
                @Override public void onLost(@NonNull Network network) {
                    // Wi-Fi <-> cellular handoffs fire onLost briefly - debounce.
                    refreshHandler.removeCallbacks(offlineCheckRunnable);
                    refreshHandler.postDelayed(offlineCheckRunnable, OFFLINE_DEBOUNCE_MS);
                }
            };
            netManager.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback);
            if (!NetworkUtils.isOnline(c)) {
                refreshHandler.postDelayed(offlineCheckRunnable, OFFLINE_DEBOUNCE_MS);
            }
        } catch (Exception e) {
            netCallback = null;   // watcher is best-effort; timeouts still cover us
        }
    }

    private void unregisterNetworkWatcher() {
        try {
            if (netManager != null && netCallback != null) netManager.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {}
        netCallback = null;
        netManager = null;
    }

    private void applyOfflineUi() {
        if (errorStateShown) {
            bindErrorTexts(true);
        } else if (firstPageReady && adapter != null && adapter.getItemCount() > 0
                && bannerMode != BANNER_REFRESH_FAILED) {
            showStatusBanner(BANNER_OFFLINE);
        }
    }

    private void onNetworkBack() {
        if (!isAdded()) return;
        refreshHandler.removeCallbacks(offlineCheckRunnable);
        if (bannerMode == BANNER_OFFLINE) hideStatusBanner();
        if (errorStateShown || bannerMode == BANNER_REFRESH_FAILED) {
            reloadInitial(false);               // auto-recover, no tap needed
        } else if (!firstPageReady) {
            // Still on the skeleton: restart the clock instead of erroring early.
            refreshHandler.removeCallbacks(initialLoadTimeoutRunnable);
            refreshHandler.postDelayed(initialLoadTimeoutRunnable, LOAD_TIMEOUT_ONLINE_MS);
        }
        if (olderLoadFailed) {
            olderLoadFailed = false;
            showLoadingOlder(false);
            maybeLoadOlderComments();
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showEmpty(boolean empty) {
        errorStateShown = false;
        if (layoutErrorState != null) layoutErrorState.setVisibility(View.GONE);
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
        n = Math.max(0, n - pendingCommentDeleteCount());
        tvCommentCount.setText(n > 0 ? getString(R.string.reel_c_comments_count, n) : getString(R.string.reel_c_comments));
    }

    @Nullable
    private String getInputText() {
        if (etComment == null) return null;
        String t = etComment.getText().toString().trim();
        boolean hasImage = replyingToComment == null
            && uploadedImageUrl != null && !uploadedImageUrl.isEmpty();
        if (TextUtils.isEmpty(t) && !hasImage) {
            Toast.makeText(requireContext(), getString(R.string.reel_c_write_something), Toast.LENGTH_SHORT).show();
            return null;
        }
        if (t.length() > MAX_COMMENT_LENGTH) {
            Toast.makeText(requireContext(), getString(R.string.reel_c_too_long_comment, MAX_COMMENT_LENGTH), Toast.LENGTH_SHORT).show();
            return null;
        }
        if (myUid.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.reel_c_login_first), Toast.LENGTH_SHORT).show();
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
