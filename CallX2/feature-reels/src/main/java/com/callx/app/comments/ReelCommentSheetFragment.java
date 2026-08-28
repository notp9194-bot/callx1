package com.callx.app.comments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ReelCommentSheetFragment — thin host only (matches SoundDetailSheetFragment's
 * pattern). Replaces the old, separately-implemented ReelCommentsBottomSheet
 * (own adapter, own pagination, missing sort/edit/pin/report — now deleted).
 *
 * Sara comment logic ReelCommentFragment mein hai. Yeh class sirf:
 *   1. ReelCommentFragment ko child fragment ke roop mein add karti hai
 *      (isSheet = true)
 *   2. The Instagram-style "docked video above the sheet" BottomSheetBehavior
 *      chrome — ported as-is from the old ReelCommentsBottomSheet so
 *      ReelPlayerFragment's existing video dim/dock integration (the Host
 *      interface below) keeps working unchanged.
 *   3. Close callback ke roop mein dismiss() deti hai
 *
 * Koi comment-logic duplicate nahi — jo ReelCommentActivity (fullscreen) mein
 * dikhta hai wahi yahan sheet mein bhi dikhta hai, ek hi ReelCommentFragment se.
 */
public class ReelCommentSheetFragment extends BottomSheetDialogFragment {

    /**
     * The reel player stays alive behind this sheet. The host only receives
     * the sheet's visual progress; it must not pause or recreate its player.
     */
    public interface Host {
        void onCommentsSheetProgress(float progress);
        void onCommentsSheetDismissed();
        /** Sheet finished settling into a stable state after a drag/fling. */
        void onCommentsSheetSettled(float settledProgress);
        /** User tapped the docked video preview above the sheet. */
        void onCommentsSheetVideoTap();
    }

    public static final String TAG = "ReelCommentSheetFragment";

    private static final String ARG_REEL_ID    = "reel_id";
    private static final String ARG_REEL_UID   = "reel_uid";
    private static final String ARG_CAPTION       = "caption_text";
    private static final String ARG_OWNER_NAME    = "caption_owner_name";
    private static final String ARG_OWNER_AVATAR  = "caption_owner_avatar_url";

    // ── Factory (same shape as the old ReelCommentsBottomSheet.newInstance) ──
    public static ReelCommentSheetFragment newInstance(String reelId, String reelUid, int commentsCount) {
        return newInstance(reelId, reelUid, commentsCount, null, null, null);
    }

    /** Used by the caption-tap entry point (see ReelUiController's tv_caption
     *  click and ReelShareController.openCommentsSheetWithCaption()) so the
     *  sheet opens with the reel's caption/owner row visible above the
     *  comment list, Instagram-style. */
    public static ReelCommentSheetFragment newInstance(String reelId, String reelUid, int commentsCount,
                                                          String caption, String ownerName, String ownerAvatarUrl) {
        ReelCommentSheetFragment f = new ReelCommentSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_REEL_ID,  reelId  != null ? reelId  : "");
        b.putString(ARG_REEL_UID, reelUid != null ? reelUid : "");
        b.putString(ARG_CAPTION,      caption        != null ? caption        : "");
        b.putString(ARG_OWNER_NAME,   ownerName      != null ? ownerName      : "");
        b.putString(ARG_OWNER_AVATAR, ownerAvatarUrl != null ? ownerAvatarUrl : "");
        f.setArguments(b);
        return f;
    }

    private BottomSheetBehavior<FrameLayout> sheetBehavior;
    private float lastKnownProgress = 0f;

    // ── View — sirf ek container FrameLayout chahiye, jisme ReelCommentFragment add hoga ──

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        FrameLayout frame = new FrameLayout(requireContext());
        frame.setId(android.R.id.content);
        return frame;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (savedInstanceState == null) {
            Bundle b = getArguments() != null ? getArguments() : new Bundle();
            String reelId  = b.getString(ARG_REEL_ID,  "");
            String reelUid = b.getString(ARG_REEL_UID, "");
            String caption      = b.getString(ARG_CAPTION,      "");
            String ownerName    = b.getString(ARG_OWNER_NAME,   "");
            String ownerAvatar  = b.getString(ARG_OWNER_AVATAR, "");

            ReelCommentFragment fragment = ReelCommentFragment.newInstance(
                reelId, reelUid, null /* highlight */, true /* isSheet = true */,
                caption, ownerName, ownerAvatar);
            fragment.setOnCloseListener(this::dismiss);

            getChildFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
        }
    }

    // ── Instagram-style video-dock BottomSheetBehavior chrome — ported as-is
    //    from the old ReelCommentsBottomSheet so ReelPlayerFragment's Host
    //    integration keeps working unchanged. ────────────────────────────────

    @Override
    public void onStart() {
        super.onStart();
        if (!(getDialog() instanceof BottomSheetDialog)) return;

        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        FrameLayout sheet = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;

        // BUG FIX: without this, the dialog window stops above the system
        // navigation bar (decorFitsSystemWindows defaults to true) — which
        // also means WindowInsets never report a real navigation-bar inset
        // at all (the OS already reserved that space, so the value is 0).
        // Edge-to-edge is required both for the height math below to match
        // the window's real size, and for the insets capture right after
        // this to have anything real to report.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialog.getWindow(), false);

        // BUG FIX: with the window now edge-to-edge, something has to push
        // the input bar back up above the navigation bar / keyboard — a
        // plain OnApplyWindowInsetsListener attached further down (on the
        // hosted ReelCommentFragment's own root) never actually saw those
        // insets: CoordinatorLayout and Material's own design_bottom_sheet
        // handling can each consume them for their own layout first. Instead,
        // capture them right here at the decor view — the very first stop
        // for WindowInsets in the whole window, before anything downstream
        // gets a chance to consume them — and hand the value straight to the
        // child fragment directly instead of hoping dispatch reaches it.
        View decorView = dialog.getWindow().getDecorView();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(decorView, (v, windowInsets) -> {
            int imeBottom = windowInsets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
            int navBottom = windowInsets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            ReelCommentFragment child = findCommentFragment();
            if (child != null) child.applyBottomInset(Math.max(imeBottom, navBottom));
            return windowInsets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(decorView);

        // Safety net: the listener above only fires on the next actual
        // insets dispatch, which can be missed if the child fragment's view
        // wasn't attached yet on the very first pass. Re-apply once more
        // from a direct snapshot after this frame settles.
        decorView.post(() -> {
            if (!isAdded()) return;
            androidx.core.view.WindowInsetsCompat snapshot =
                androidx.core.view.ViewCompat.getRootWindowInsets(decorView);
            if (snapshot == null) return;
            int imeBottom = snapshot.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
            int navBottom = snapshot.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            ReelCommentFragment child = findCommentFragment();
            if (child != null) child.applyBottomInset(Math.max(imeBottom, navBottom));
        });

        final int expandedOffsetPx = (int) (getResources().getDisplayMetrics().heightPixels * 0.44f);

        // BUG FIX (take 2): with a short first-loaded comment batch (e.g.
        // only the initial PAGE_SIZE=12), the sheet's own content (our
        // fragment's root LinearLayout) could end up sized to just
        // "12 rows + input bar" instead of the intended full sheet height —
        // the input row then sat right under the last loaded comment
        // instead of docked to the screen's bottom. setFitToContents(false)
        // below only controls the sheet's target STATE geometry; the
        // design_bottom_sheet frame itself is still inflated with a
        // WRAP_CONTENT height by Material's own dialog layout, so it still
        // shrinks to fit whatever content we give it.
        //
        // The fix is NOT to force it to raw MATCH_PARENT — the sheet's top
        // is positioned at expandedOffsetPx (≈44% down the screen), so a
        // full-screen-tall frame starting there would push its bottom (and
        // our input bar with it) well past the visible screen edge,
        // off-screen entirely — which is exactly what a naive
        // "just match_parent it" fix causes. The frame needs to be tall
        // enough to reach the screen's bottom edge from wherever it starts,
        // no taller: (screen height − expandedOffset).
        int targetSheetHeightPx = getResources().getDisplayMetrics().heightPixels - expandedOffsetPx;
        ViewGroup.LayoutParams sheetParams = sheet.getLayoutParams();
        if (sheetParams != null && sheetParams.height != targetSheetHeightPx) {
            sheetParams.height = targetSheetHeightPx;
            sheet.setLayoutParams(sheetParams);
        }

        // Instagram never darkens the docked video above the sheet — only the
        // sheet's own opaque background separates it visually. The default
        // BottomSheetDialog scrim dims the *whole* window (video included),
        // which is what was making the video look dark/washed out. Kill it.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0f);
        }

        // BUG FIX: the add-comment EditText sits at the bottom of the sheet.
        // BottomSheetDialog's underlying window has no explicit soft-input
        // mode, so on most devices it defaults to SOFT_INPUT_ADJUST_PAN (or
        // nothing at all) instead of resizing — the keyboard then floats on
        // top of the window and covers the input row instead of pushing it
        // up. That's why the comment box "sometimes" wasn't visible/tappable
        // once the keyboard was open: it was still there, just underneath
        // the IME. ADJUST_RESIZE makes the sheet's content shrink to make
        // room for the keyboard, exactly like Instagram's comment sheet.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        sheetBehavior = BottomSheetBehavior.from(sheet);
        sheetBehavior.setFitToContents(false);
        sheetBehavior.setExpandedOffset(expandedOffsetPx);
        sheetBehavior.setHalfExpandedRatio(0.45f);
        sheetBehavior.setDraggable(true);
        sheetBehavior.setSkipCollapsed(true);

        final int halfExpandedTopPx = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);

        sheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    dispatchHostDismissed();
                    return;
                }
                if (newState == BottomSheetBehavior.STATE_HALF_EXPANDED
                        || newState == BottomSheetBehavior.STATE_EXPANDED) {
                    lastKnownProgress = dockProgressFromTop(bottomSheet.getTop(), expandedOffsetPx, halfExpandedTopPx);
                    dispatchHostSettled(lastKnownProgress);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                lastKnownProgress = dockProgressFromTop(bottomSheet.getTop(), expandedOffsetPx, halfExpandedTopPx);
                dispatchHostProgress(lastKnownProgress);
            }
        });

        dispatchHostProgress(0f);
        sheetBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);

        sheet.post(() -> {
            if (!isAdded() || sheetBehavior == null) return;
            lastKnownProgress = dockProgressFromTop(sheet.getTop(), expandedOffsetPx, halfExpandedTopPx);
            dispatchHostSettled(lastKnownProgress);
        });

        // Tapping the dimmed area above the sheet normally just dismisses it.
        // If the tap actually lands on the docked video preview, forward it as
        // a play/pause toggle instead and keep the sheet open — matches IG.
        View touchOutside = dialog.findViewById(com.google.android.material.R.id.touch_outside);
        if (touchOutside != null) {
            final int videoZoneBottomPx = (int) (getResources().getDisplayMetrics().heightPixels * 0.44f);
            touchOutside.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (event.getY() < videoZoneBottomPx) {
                        Host host = getCommentsHost();
                        if (host != null) host.onCommentsSheetVideoTap();
                    } else if (getDialog() != null) {
                        getDialog().cancel();
                    }
                }
                return true;
            });
        }

        setupListDragToDismiss();
    }

    /**
     * Coordinates the comment list's own scrolling with the sheet's drag.
     * RecyclerView's built-in nested-scrolling normally hands off unconsumed
     * scroll to the BottomSheetBehavior automatically, but only once the list
     * is already at its very top; this adds an explicit, reliable fallback so
     * "pull down from the top of the list" always collapses/dismisses the
     * sheet instead of the gesture getting swallowed by the list.
     *
     * The list itself now lives inside the child ReelCommentFragment (not
     * this class), so it's fetched via getCommentsRecyclerView() — everything
     * else is ported as-is from the old ReelCommentsBottomSheet.
     */
    private void setupListDragToDismiss() {
        ReelCommentFragment child = findCommentFragment();
        RecyclerView rv = (child != null) ? child.getCommentsRecyclerView() : null;
        if (rv == null || sheetBehavior == null) return;

        final int touchSlop = ViewConfiguration.get(requireContext()).getScaledTouchSlop();
        final int dismissThresholdPx = dpToPx(90);

        rv.setNestedScrollingEnabled(true);
        rv.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float startY;
            private boolean draggingSheet;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent e) {
                if (sheetBehavior == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = e.getRawY();
                        draggingSheet = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - startY;
                        boolean atTop = !recyclerView.canScrollVertically(-1);
                        if (!draggingSheet && atTop && dy > touchSlop
                                && sheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                            draggingSheet = true;
                        }
                        break;
                    default:
                        break;
                }
                return draggingSheet;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent e) {
                if (!draggingSheet) return;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float dy = e.getRawY() - startY;
                        if (dy > dismissThresholdPx) {
                            sheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                        } else {
                            sheetBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                        }
                        draggingSheet = false;
                        break;
                    default:
                        break;
                }
            }
        });
    }

    @Nullable
    private ReelCommentFragment findCommentFragment() {
        androidx.fragment.app.Fragment f = getChildFragmentManager().findFragmentById(android.R.id.content);
        return (f instanceof ReelCommentFragment) ? (ReelCommentFragment) f : null;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    /**
     * Expands the sheet to STATE_EXPANDED. Called by the hosted
     * ReelCommentFragment when the comment/reply input gets focus, so the
     * keyboard never has to fight the HALF_EXPANDED sheet for space (see the
     * comment-box "sometimes not visible" bug fix in ReelCommentFragment).
     */
    public void expandFully() {
        if (sheetBehavior != null) {
            sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    /**
     * 0 at the half-expanded top, 1 at the fully expanded top — used to drive
     * the video dock transform. Deliberately independent of
     * BottomSheetBehavior's own slideOffset, which stays anchored near 0 for
     * half-expanded once the collapsed stage is skipped.
     */
    private float dockProgressFromTop(int sheetTopPx, int expandedTopPx, int halfExpandedTopPx) {
        if (halfExpandedTopPx <= expandedTopPx) return 0f;
        float raw = (halfExpandedTopPx - sheetTopPx) / (float) (halfExpandedTopPx - expandedTopPx);
        return Math.max(0f, Math.min(1f, raw));
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        dispatchHostDismissed();
        super.onDismiss(dialog);
    }

    private Host getCommentsHost() {
        if (getParentFragment() instanceof Host) {
            return (Host) getParentFragment();
        }
        return null;
    }

    private void dispatchHostProgress(float progress) {
        Host host = getCommentsHost();
        if (host != null) host.onCommentsSheetProgress(progress);
    }

    private void dispatchHostDismissed() {
        Host host = getCommentsHost();
        if (host != null) host.onCommentsSheetDismissed();
    }

    private void dispatchHostSettled(float progress) {
        Host host = getCommentsHost();
        if (host != null) host.onCommentsSheetSettled(Math.max(0f, Math.min(1f, progress)));
    }
}
