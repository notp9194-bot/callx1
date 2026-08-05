package com.callx.app.comments;
import com.callx.app.utils.AlertDialogStyler;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.models.ReelComment;
import com.callx.app.models.ReelReply;
import com.callx.app.reels.R;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.workers.ReelCommentNotifWorker;
import de.hdodenhof.circleimageview.CircleImageView;
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

    private static final int MAX_COMMENT_LENGTH = 300;

    public interface OnCloseListener { void onClose(); }
    private OnCloseListener closeListener;
    public void setOnCloseListener(OnCloseListener l) { this.closeListener = l; }
    private void close() { if (closeListener != null) closeListener.onClose(); }

    // ── Factory ──────────────────────────────────────────────────────────────

    public static ReelCommentFragment newInstance(String reelId, String reelUid,
                                                    String highlightCommentId, boolean isSheet) {
        ReelCommentFragment f = new ReelCommentFragment();
        Bundle b = new Bundle();
        b.putString(ARG_REEL_ID,  reelId  != null ? reelId  : "");
        b.putString(ARG_REEL_UID, reelUid != null ? reelUid : "");
        b.putString(ARG_HIGHLIGHT, highlightCommentId != null ? highlightCommentId : "");
        b.putBoolean(ARG_IS_SHEET, isSheet);
        f.setArguments(b);
        return f;
    }

    // ── Views ────────────────────────────────────────────────────────────────
    private RecyclerView   rvComments;
    private EditText       etComment;
    private ImageButton    btnSend;
    private TextView       tvEmpty;
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
    private View           layoutMentionSuggestions;
    private LinearLayout   containerMentionSuggestions;
    private TextView       pillNewComments;
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

    private ReelComment replyingToComment = null;
    /** Non-null when the user tapped "Reply" on a REPLY (not a top-level
     *  comment) — Instagram flattens this into the same parent's reply
     *  thread but tags the reply's author. */
    private ReelReply   replyingToReplyMention = null;

    // ── @mention autocomplete state ─────────────────────────────────────────
    /** lowercase display-name → uid, built from everyone visible in this
     *  thread so far (commenters + repliers) — the tag source. */
    private final Map<String, String> mentionNameToUid = new HashMap<>();
    /** uid → display name for every user tagged during THIS compose session
     *  (cleared on send/cancel) — attached to the comment/reply on submit. */
    private final Map<String, String> pendingMentions = new HashMap<>();
    private boolean suppressTextWatcher = false;

    // ── "New comments" pill state ───────────────────────────────────────────
    private int  pendingNewComments = 0;
    /** True once the initial comment burst has settled — the pill only
     *  reacts to genuinely NEW comments arriving after that point, not the
     *  batch of existing ones Firebase delivers via onChildAdded on open. */
    private boolean initialLoadSettled = false;

    // ── Data ─────────────────────────────────────────────────────────────────
    private final List<ReelComment> allComments = new ArrayList<>();

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
            autoScrollIfAtBottom();
        }
        if (pendingNewComments > 0 && pillNewComments != null) {
            pillNewComments.setText(pendingNewComments == 1
                ? "↓ New comment" : "↓ " + pendingNewComments + " new comments");
            pillNewComments.setVisibility(View.VISIBLE);
        }
    };

    private void requestRefresh() {
        if (refreshQueued) return;
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
    private static final int PAGE_SIZE = 40;
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
                    registerMentionCandidate(c.uid, c.ownerName);
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

        readCurrentUser();
        bindViews(v);
        setupAdapter();
        setupSortChips();
        setupSearch();
        setupCharCounter();
        setupMentionAutocomplete();
        setupNewCommentsPill();
        loadMyPhoto();
        restoreDraft();

        if (!reelId.isEmpty()) loadComments();
        else showEmpty(true);

        if (rvComments != null) {
            rvComments.postDelayed(() -> {
                initialLoadSettled = true;
                // If the live window's initial burst came back under a full
                // page, that IS every comment on this reel — nothing older
                // to page in, so skip wiring up load-more entirely.
                hasMoreOlder = allComments.size() >= PAGE_SIZE;
            }, 1200);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        saveDraft();
    }

    @Override
    public void onDestroyView() {
        saveDraft();
        refreshHandler.removeCallbacksAndMessages(null);
        try {
            if (commentsListener != null && commentsQuery != null)
                commentsQuery.removeEventListener(commentsListener);
        } catch (Exception ignored) {}
        super.onDestroyView();
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

    private void bindViews(View root) {
        rvComments      = root.findViewById(R.id.rv_comments);
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
        layoutMentionSuggestions    = root.findViewById(R.id.layout_mention_suggestions);
        containerMentionSuggestions = root.findViewById(R.id.container_mention_suggestions);
        pillNewComments = root.findViewById(R.id.pill_new_comments);
        tvLoadingOlder  = root.findViewById(R.id.tv_loading_older);

        // Same button closes either mode — finish() for the fullscreen host,
        // dismiss() for the sheet host — see setOnCloseListener callers.
        ImageButton btnBack = root.findViewById(R.id.btn_back);
        if (btnBack        != null) btnBack.setOnClickListener(v -> close());
        if (btnSend        != null) btnSend.setOnClickListener(v -> onSendClicked());
        if (btnCancelReply != null) btnCancelReply.setOnClickListener(v -> cancelReply());
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
            if (rvAnim instanceof RecyclerView.SimpleItemAnimator) {
                ((RecyclerView.SimpleItemAnimator) rvAnim).setSupportsChangeAnimations(false);
            }

            // PERF: page older comments in as the user nears the top of the
            // currently-loaded window, instead of ever downloading the full
            // thread — see maybeLoadOlderComments().
            rvComments.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    if (dy >= 0) return; // only care about scrolling UP
                    LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                    if (lm != null && lm.findFirstVisibleItemPosition() <= 4) {
                        maybeLoadOlderComments();
                    }
                }
            });

            attachSwipeToReply(rvComments);
        }
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
                    triggered = progress >= 1f;

                    View item = vh.itemView;
                    if (progress > 0.05f) {
                        int iconSize = dpToPx(22);
                        int cx = item.getLeft() + dpToPx(28);
                        int cy = item.getTop() + item.getHeight() / 2;
                        android.graphics.drawable.Drawable icon =
                            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_reply);
                        if (icon != null) {
                            icon.mutate().setAlpha((int) (255 * Math.min(1f, progress * 1.6f)));
                            int scale = triggered ? iconSize + dpToPx(4) : iconSize;
                            icon.setBounds(cx - scale / 2, cy - scale / 2, cx + scale / 2, cy + scale / 2);
                            icon.draw(c);
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
                if (c != null) startReply(c);
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

    private void registerMentionCandidate(@Nullable String uid, @Nullable String name) {
        if (uid == null || uid.isEmpty() || name == null || name.isEmpty()) return;
        if (uid.equals(myUid)) return; // can't tag yourself
        mentionNameToUid.put(name.toLowerCase(java.util.Locale.ROOT), uid);
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
        if (containerMentionSuggestions == null || layoutMentionSuggestions == null) return;

        List<Map.Entry<String, String>> matches = new ArrayList<>();
        for (Map.Entry<String, String> e : mentionNameToUid.entrySet()) {
            if (e.getKey().startsWith(query)) matches.add(e);
            if (matches.size() >= 8) break;
        }

        if (matches.isEmpty()) { hideMentionSuggestions(); return; }

        containerMentionSuggestions.removeAllViews();
        int dp6 = dpToPx(6), dp10 = dpToPx(10);
        for (Map.Entry<String, String> e : matches) {
            String uid  = e.getValue();
            // Recover original-case display name from the candidate we stored it under.
            String name = capitalizeFromCandidate(e.getKey());

            TextView chip = new TextView(requireContext());
            chip.setText("@" + name);
            chip.setTextSize(13f);
            chip.setTextColor(getResources().getColor(R.color.brand_primary));
            chip.setBackgroundResource(R.drawable.bg_sort_chip);
            chip.setPadding(dp10, dp6, dp10, dp6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dpToPx(8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> insertMention(uid, name, atIndex, cursor));
            containerMentionSuggestions.addView(chip);
        }
        layoutMentionSuggestions.setVisibility(View.VISIBLE);
    }

    private String capitalizeFromCandidate(String lowerName) {
        // We only stored the lowercase key; look up the matching original
        // name from whatever's currently rendered so the chip shows proper
        // casing (fall back to the lowercase form if not found).
        for (ReelComment c : allComments) {
            if (c.ownerName != null && c.ownerName.toLowerCase(java.util.Locale.ROOT).equals(lowerName))
                return c.ownerName;
        }
        return lowerName;
    }

    private void hideMentionSuggestions() {
        if (layoutMentionSuggestions != null) layoutMentionSuggestions.setVisibility(View.GONE);
        if (containerMentionSuggestions != null) containerMentionSuggestions.removeAllViews();
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

    // ── "New comments" pill ──────────────────────────────────────────────────

    private void setupNewCommentsPill() {
        if (pillNewComments != null) {
            pillNewComments.setOnClickListener(v -> {
                pendingNewComments = 0;
                pillNewComments.setVisibility(View.GONE);
                if (rvComments != null && adapter != null)
                    rvComments.scrollToPosition(adapter.getItemCount() - 1);
            });
        }
    }

    private boolean isNearBottom() {
        if (rvComments == null) return true;
        LinearLayoutManager lm = (LinearLayoutManager) rvComments.getLayoutManager();
        if (lm == null || adapter == null) return true;
        int last = lm.findLastVisibleItemPosition();
        return last >= adapter.getItemCount() - 3;
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

        adapter.setComments(filtered);

        if (sortByTop) adapter.sortByTop();
        else           adapter.sortByNewest();

        updateCountHeader();
        showEmpty(filtered.isEmpty());
        checkAndHighlightComment();
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
                registerMentionCandidate(c.uid, c.ownerName);
                boolean wasNearBottom = isNearBottom();
                allComments.add(c);
                if (!initialLoadSettled || wasNearBottom) {
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
                registerMentionCandidate(updated.uid, updated.ownerName);
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

    private void autoScrollIfAtBottom() {
        if (rvComments == null || adapter == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) rvComments.getLayoutManager();
        if (lm == null) return;
        int last = lm.findLastVisibleItemPosition();
        int count = adapter.getItemCount();
        if (last >= count - 3) {
            rvComments.scrollToPosition(count - 1);
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private void onSendClicked() {
        if (replyingToComment != null) postReply();
        else postComment();
    }

    private void postComment() {
        String text = getInputText();
        if (text == null) return;

        try {
            DatabaseReference ref = commentsRef != null
                ? commentsRef
                : FirebaseUtils.getReelCommentsRef(reelId);
            String key = ref.push().getKey();
            if (key == null) return;

            Map<String, Object> data = new HashMap<>();
            data.put("commentId",  key);
            data.put("uid",        myUid);
            data.put("ownerName",  myName);
            data.put("ownerPhoto", myPhoto);
            data.put("text",       text);
            data.put("timestamp",  System.currentTimeMillis());
            data.put("likesCount", 0);
            data.put("replyCount", 0);
            data.put("isPinned",   false);
            data.put("isEdited",   false);

            Map<String, String> mentions = resolveMentionsInText(text);
            if (!mentions.isEmpty()) data.put("mentions", mentions);

            ref.child(key).setValue(data);

            incrementCommentsCount(+1);
            clearInput();
            clearDraft();

            ReelCommentNotifWorker.enqueue(
                requireContext(), reelId, reelUid, myUid, myName, key, text);

            for (Map.Entry<String, String> e : mentions.entrySet()) {
                if (e.getKey().equals(myUid) || e.getKey().equals(reelUid)) continue;
                ReelCommentNotifWorker.enqueueMention(
                    requireContext(), reelId, e.getKey(), myUid, myName, key, text);
            }
            pendingMentions.clear();

        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to post comment", Toast.LENGTH_SHORT).show();
        }
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
                            if (r.replyId == null) r.replyId = s.getKey();
                            registerMentionCandidate(r.uid, r.ownerName);
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

            CircleImageView ivAvatar = v.findViewById(R.id.iv_avatar);
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

    private void bindReplyAvatar(CircleImageView iv, @Nullable String uid, @Nullable String photoUrl) {
        iv.setImageResource(R.drawable.ic_person);
        String url = photoUrl;
        if ((url == null || url.isEmpty()) && uid != null && !uid.isEmpty()) {
            FirebaseDatabase.getInstance()
                .getReference("reels/users").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        if (!isAdded()) return;
                        String thumb = s.child("thumbUrl").getValue(String.class);
                        String photo = s.child("photoUrl").getValue(String.class);
                        String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        if (p != null && !p.isEmpty()) loadReplyAvatarInto(iv, p);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
            return;
        }
        if (url != null && !url.isEmpty()) loadReplyAvatarInto(iv, url);
    }

    private void loadReplyAvatarInto(CircleImageView iv, String url) {
        try {
            Glide.with(requireContext()).load(url)
                .apply(new RequestOptions().circleCrop())
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
    }

    private void updateCountHeader() {
        if (tvCommentCount == null) return;
        int n = allComments.size();
        tvCommentCount.setText(n > 0 ? "Comments (" + n + ")" : "Comments");
    }

    @Nullable
    private String getInputText() {
        if (etComment == null) return null;
        String t = etComment.getText().toString().trim();
        if (TextUtils.isEmpty(t)) {
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

    private void showKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        } catch (Exception ignored) {}
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
