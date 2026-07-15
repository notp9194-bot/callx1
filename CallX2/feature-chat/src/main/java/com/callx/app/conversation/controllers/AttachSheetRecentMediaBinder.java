package com.callx.app.conversation.controllers;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * WhatsApp-style attach-sheet media: a 4-column "Recents" grid — camera
 * tile at position 0 (see RecentMediaGridAdapter) followed by recent
 * gallery thumbnails — that sits right after the Poll/Payment/Event/AI
 * images icon grid. The grid's first ~1.5 rows are already visible at the
 * collapsed peek height (see the peekHeight computation below); dragging
 * the sheet up reveals the rest.
 *
 * Tapping a thumbnail toggles a numbered multi-select (MediaSelectionState),
 * same as WhatsApp/Telegram. Tapping a thumbnail while the sheet is still
 * collapsed/peeking additionally drives the sheet straight to
 * STATE_EXPANDED so the just-picked item lands in full view still marked
 * selected — riding the exact same eased crossfade the drag gesture
 * already uses (BottomSheetBehavior animates programmatic setState() calls
 * the same way it animates a released drag), so the transition is smooth
 * rather than an instant cut. A floating caption/send bar (selection_bar)
 * appears once anything is selected and fires Callbacks#onMediaSend with
 * the full ordered selection.
 *
 * Both ChatMediaController (1-1 chat) and GroupChatActivity (group chat)
 * inflate the same bottom_sheet_attach.xml, so this is a single shared
 * binder instead of two copies of the same wiring drifting apart.
 */
public final class AttachSheetRecentMediaBinder {

    private AttachSheetRecentMediaBinder() {}

    private static final int RECENT_MEDIA_LIMIT = 60;
    private static final int GRID_PAGE_SIZE = 60;
    // Fraction of the sheet's collapsed→expanded drag distance over which the
    // icon grid fades out and the "Recents" header fades in. Kept short
    // (first 35% of the drag) so the crossfade finishes well before the user
    // reaches full expansion, matching the reference screenshots.
    private static final float FADE_FRACTION = 0.35f;
    // How much of the Recents grid to reveal below the collapsed icon grid —
    // one full row (1.0) plus half of the next row peeking out, matching the
    // reference screenshot exactly.
    private static final float PEEK_GRID_ROWS = 1.5f;

    public interface Callbacks {
        void onCameraTapped();
        /**
         * "More apps" row in the Recents ▾ dropdown was tapped — hand off to
         * whatever the surface's own document/file chooser is (ChatMediaController
         * and GroupChatActivity both already wire opt_document to this same
         * system content-chooser flow; reuse it here instead of duplicating it).
         */
        void onMoreAppsRequested();
        /**
         * "See more" row in the Recents ▾ dropdown was tapped — hand off to the
         * surface's system Photos picker, i.e. the exact same flow opt_gallery
         * already triggers.
         */
        void onSeeMoreRequested();
        /**
         * Fired when the user taps Send on the selection bar with 1+ items picked.
         * @param isHD whether the WhatsApp-style HD toggle (top-right of the
         *             expanded "Recents" header) was ON at send time — true
         *             means images should be compressed at the higher HD cap
         *             instead of the default/Standard cap.
         * @param isViewOnce whether the view-once toggle (selection bar,
         *             next to the caption field) was ON at send time — true
         *             means the resulting message should be tagged view-once
         *             (single flag for the whole batch, same as WhatsApp).
         */
        void onMediaSend(List<RecentMediaLoader.Item> items, String caption, boolean isHD, boolean isViewOnce);
        /**
         * "Edit" (pencil) was tapped in the selection bar with 1+ items
         * picked — hand the ordered selection off to MediaEditActivity
         * instead of sending straight away. Same isHD/caption plumbing as
         * onMediaSend so the editor can seed its HD toggle/caption field
         * and hand both back once the user hits Send there.
         */
        void onMediaEdit(List<RecentMediaLoader.Item> items, String caption, boolean isHD);
    }

    public static void bind(AppCompatActivity activity, BottomSheetDialog sheet, View sheetRoot,
                             ExecutorService mediaQueryExecutor, Callbacks callbacks) {
        bind(activity, sheet, sheetRoot, mediaQueryExecutor, true, callbacks);
    }

    /**
     * @param supportsViewOnce whether this surface has a view-once pipeline
     *        wired up on the sending side (currently 1-1 chat only — group
     *        chat has no ChatViewOnceController equivalent yet). When false,
     *        the toggle button is hidden entirely instead of sitting there
     *        as a dead/misleading control.
     */
    public static void bind(AppCompatActivity activity, BottomSheetDialog sheet, View sheetRoot,
                             ExecutorService mediaQueryExecutor, boolean supportsViewOnce, Callbacks callbacks) {
        RecyclerView grid       = sheetRoot.findViewById(R.id.recents_grid);
        View recentsEmpty       = sheetRoot.findViewById(R.id.recents_empty);
        View topContent         = sheetRoot.findViewById(R.id.top_content);
        View iconGridSection    = sheetRoot.findViewById(R.id.icon_grid_section);
        View expandedHeader     = sheetRoot.findViewById(R.id.expanded_header);
        View closeBtn           = sheetRoot.findViewById(R.id.btn_close_sheet);
        View selectionBar       = sheetRoot.findViewById(R.id.selection_bar);
        EditText captionInput   = sheetRoot.findViewById(R.id.selection_caption_input);
        View sendBtn            = sheetRoot.findViewById(R.id.btn_selection_send);
        TextView sendCount      = sheetRoot.findViewById(R.id.selection_send_count);
        View editBtn            = sheetRoot.findViewById(R.id.btn_selection_edit);
        if (grid == null || topContent == null) return; // older/replaced layout — skip silently

        View hdToggle           = sheetRoot.findViewById(R.id.btn_hd_toggle);
        View viewOnceToggle     = sheetRoot.findViewById(R.id.btn_selection_view_once);
        View recentsDropdownRow = sheetRoot.findViewById(R.id.recents_dropdown_row);
        TextView recentsTitle   = sheetRoot.findViewById(R.id.recents_title);
        if (closeBtn != null) closeBtn.setOnClickListener(x -> sheet.dismiss());

        // View-once toggle — OFF by default, one flag for the whole selection
        // (same UX as WhatsApp: you can't mix normal + view-once in one send).
        // Mirrors ChatActivity#setViewOnceMode's tint swap exactly so the
        // control reads identically whether it's the input-bar button or
        // this one — active = purple, idle = grey.
        final boolean[] viewOnceEnabled = {false};
        if (viewOnceToggle instanceof ImageView) {
            if (!supportsViewOnce) {
                viewOnceToggle.setVisibility(View.GONE);
            } else {
                updateViewOnceToggleVisual((ImageView) viewOnceToggle, false);
                viewOnceToggle.setOnClickListener(x -> {
                    viewOnceEnabled[0] = !viewOnceEnabled[0];
                    updateViewOnceToggleVisual((ImageView) viewOnceToggle, viewOnceEnabled[0]);
                });
            }
        }

        BottomSheetBehavior<android.widget.FrameLayout> behavior = sheet.getBehavior();

        // Root-cause fix for the "sheet bottom floats above the nav bar on
        // first expand" bug: every maxHeight ceiling below this used to be
        // computed off the HOST ACTIVITY's window/decorView. But
        // BottomSheetDialog opens its OWN separate Window — on an
        // edge-to-edge activity that dialog window doesn't automatically
        // inherit the activity's edge-to-edge state or inset dispatch, so
        // measuring against the activity window could disagree with what
        // CoordinatorLayout inside the DIALOG's window actually gets,
        // especially on the very first frame before the dialog's own
        // WindowInsets have been dispatched. Force this dialog's window to
        // edge-to-edge explicitly so it draws full-bleed behind the nav bar
        // exactly like the rest of the chat screen, and do all ceiling
        // math (below) off THIS window's decorView/insets instead of the
        // activity's.
        if (sheet.getWindow() != null) {
            WindowCompat.setDecorFitsSystemWindows(sheet.getWindow(), false);
        }

        MediaSelectionState selection = new MediaSelectionState();

        // WhatsApp-style HD toggle — OFF by default (Standard/compressed
        // send), tapping flips it ON for this sheet session so the next
        // Send uses ImageCompressor's higher HD cap instead. Purely a
        // per-open-sheet UI flag; not persisted, same as WhatsApp resets
        // it back to Standard the next time you open the attach sheet.
        final boolean[] hdEnabled = {false};
        if (hdToggle instanceof TextView) {
            updateHdToggleVisual((TextView) hdToggle, false);
            hdToggle.setOnClickListener(x -> {
                hdEnabled[0] = !hdEnabled[0];
                updateHdToggleVisual((TextView) hdToggle, hdEnabled[0]);
            });
        }

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int cellPx = dm.widthPixels / 4;

        // --- Overshoot fix -------------------------------------------------
        // Root cause: this sheet's content (top_content + the 560dp Recents
        // grid) is taller than the screen on most phones, and nothing was
        // telling BottomSheetBehavior the ceiling for STATE_EXPANDED. With
        // no maxHeight set, Behavior derives the expanded offset purely from
        // measured content height, which can push the sheet's top past y=0
        // — i.e. dragging up sends it climbing above the visible screen
        // (under the status bar). It only *looked* like it "snapped back
        // correct" when scrolling the grid because a nested-scroll pass
        // forces CoordinatorLayout to re-settle the sheet against its real,
        // parent-clamped bounds. Giving it that ceiling up front makes drag
        // and scroll agree from the start.
        //
        // v154 computed this ceiling from raw DisplayMetrics (dm.heightPixels)
        // and it could *still* overshoot. Reason: DisplayMetrics reports the
        // physical screen size, not the actual height the CoordinatorLayout
        // inside this dialog gets — those differ whenever the dialog window
        // isn't perfectly edge-to-edge (theme insets, multi-window/split
        // screen, a resized window while the caption EditText has IME
        // focus, etc). If our ceiling ends up LARGER than that real parent
        // height, BottomSheetBehavior's `expandedOffset = parentHeight -
        // maxHeight` goes NEGATIVE — the sheet's top gets pushed above y=0,
        // i.e. exactly this bug.
        //
        // Fix: derive the ceiling from real WindowInsetsCompat on the decor
        // view (correctly accounts for the status bar / display cutouts)
        // instead of the legacy "status_bar_height" dimen lookup, and
        // re-apply it a second time once the dialog window has actually
        // settled (see the topContent layout listener below) so a
        // too-early first measurement can't leave a stale value behind.
        int maxSheetHeightPx = computeMaxSheetHeightPx(activity, sheet);
        if (maxSheetHeightPx > 0) behavior.setMaxHeight(maxSheetHeightPx);

        // Authoritative recompute: WindowInsetsCompat delivered to the
        // DIALOG's own root view is the actual moment this window knows
        // its real status-bar/nav-bar bounds — more reliable than the
        // "topContent measured > 0" heuristic below, which only proves the
        // sheet's CONTENT has a size, not that the window's insets have
        // settled. Re-applying maxHeight/peekHeight right here means the
        // very first STATE_EXPANDED (including the tap-while-collapsed
        // case a few lines down) already uses the correct ceiling instead
        // of a stale/estimated one.
        ViewCompat.setOnApplyWindowInsetsListener(sheetRoot, (view, windowInsets) -> {
            int refreshedMaxHeightPx = computeMaxSheetHeightPx(activity, sheet);
            if (refreshedMaxHeightPx > 0) behavior.setMaxHeight(refreshedMaxHeightPx);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(sheetRoot);
        // --------------------------------------------------------------------
        RecentMediaGridAdapter.Listener gridListener = item -> {
            selection.toggle(item);
            // Tapped while the sheet is still collapsed/peeking — smoothly
            // drive it up to the full Recents view, same as if the user had
            // dragged it, so the pick just made opens out into the expanded
            // grid instead of just sitting half-cut at the peek line. The
            // item stays selected across the move.
            if (behavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        };
        RecentMediaGridAdapter.CameraListener cameraListener = callbacks::onCameraTapped;
        RecentMediaGridAdapter gridAdapter = new RecentMediaGridAdapter(
                activity, gridListener, cameraListener, selection, cellPx);
        grid.setLayoutManager(new GridLayoutManager(activity, 4));
        grid.setAdapter(gridAdapter);
        grid.setHasFixedSize(true);
        grid.setItemViewCacheSize(16);
        if (grid.getItemAnimator() instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            ((androidx.recyclerview.widget.SimpleItemAnimator) grid.getItemAnimator())
                    .setSupportsChangeAnimations(false);
        }
        grid.getRecycledViewPool().setMaxRecycledViews(0, 32);

        // Warms the next ~12 thumbnails below the fold via Glide's
        // RecyclerViewPreloader so they're already decoded/cached by
        // the time the grid scrolls to them — same idea as the
        // Glide preloading already used for the chat list.
        //
        // NOTE: Glide's recyclerview-integration artifact does NOT ship
        // a ready-made "FixedPreloadSizeProvider" class — only
        // ViewPreloadSizeProvider (which measures an actual target
        // view). Since we always want a fixed cell size here, we
        // implement PreloadSizeProvider ourselves — same fix already
        // applied in core/ChatMediaPreloader.java.
        final int[] preloadCellSize = new int[]{cellPx, cellPx};
        com.bumptech.glide.ListPreloader.PreloadSizeProvider<android.net.Uri> preloadSizeProvider =
                (item, adapterPosition, perItemPosition) -> preloadCellSize;
        com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader<android.net.Uri> preloader =
                new com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader<>(
                        com.bumptech.glide.Glide.with(activity),
                        gridAdapter,
                        preloadSizeProvider,
                        12);
        grid.addOnScrollListener(preloader);
        RecentMediaGridAdapter finalGridAdapter = gridAdapter;

        // Grid keeps itself in sync via MediaSelectionState.ToggleListener
        // (targeted notifyItemChanged, registered inside the adapter's
        // constructor) — this listener only has to drive the floating
        // caption/send bar, which is a cheap alpha/visibility flip, not a
        // RecyclerView rebind.
        selection.addListener(() -> updateSelectionBar(activity, selection, selectionBar, sendCount));

        if (sendBtn != null) {
            sendBtn.setOnClickListener(x -> {
                if (selection.isEmpty()) return;
                List<RecentMediaLoader.Item> items = selection.items();
                String caption = captionInput != null ? captionInput.getText().toString().trim() : "";
                sheet.dismiss();
                callbacks.onMediaSend(items, caption, hdEnabled[0], viewOnceEnabled[0]);
                selection.clear();
            });
        }

        if (editBtn instanceof ImageView) {
            ((ImageView) editBtn).setColorFilter(android.graphics.Color.parseColor("#FF8A8A8A"));
            editBtn.setOnClickListener(x -> {
                if (selection.isEmpty()) return;
                List<RecentMediaLoader.Item> items = selection.items();
                String caption = captionInput != null ? captionInput.getText().toString().trim() : "";
                sheet.dismiss();
                callbacks.onMediaEdit(items, caption, hdEnabled[0]);
                selection.clear();
            });
        }

        boolean hasPerm = hasMediaReadPermission(activity);
        // Guards against firing two overlapping "load next page" queries off
        // one fast fling, and against paging forever once MediaStore is dry.
        final boolean[] loadingMore = {false};
        final boolean[] noMorePages = {false};
        // Which folder the grid is currently showing — null/FILTER_ALL means
        // "Recents" (everything). Swapped by the "Recents ▾" dropdown (see
        // AttachSheetFolderPicker wiring below); reset() re-runs the first
        // page against whatever this currently holds.
        final String[] currentFilter = {RecentMediaLoader.FILTER_ALL};

        Runnable[] loadFirstPageHolder = new Runnable[1];
        if (hasPerm) {
            loadFirstPageHolder[0] = () -> {
                loadingMore[0] = false;
                noMorePages[0] = false;
                String filterAtRequestTime = currentFilter[0];
                mediaQueryExecutor.execute(() -> {
                    List<RecentMediaLoader.Item> items =
                            RecentMediaLoader.loadRecentPage(activity, 0, RECENT_MEDIA_LIMIT, filterAtRequestTime);
                    activity.runOnUiThread(() -> {
                        if (!java.util.Objects.equals(filterAtRequestTime, currentFilter[0])) return; // stale
                        finalGridAdapter.submit(items);
                        grid.scrollToPosition(0);
                        if (recentsEmpty != null) recentsEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                        if (items.size() < RECENT_MEDIA_LIMIT) noMorePages[0] = true;
                    });
                });
            };
            loadFirstPageHolder[0].run();

            // Infinite scroll: once the user has dragged the sheet up and is
            // scrolling the grid itself, load the next page a few rows before
            // hitting the bottom — same "keep paging MediaStore" pattern
            // WhatsApp/Telegram use instead of loading everything up front.
            grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@androidx.annotation.NonNull RecyclerView rv, int dx, int dy) {
                    if (dy <= 0 || loadingMore[0] || noMorePages[0]) return;
                    GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                    if (lm == null) return;
                    int lastVisible = lm.findLastVisibleItemPosition();
                    int total = finalGridAdapter.getLoadedCount();
                    if (lastVisible >= total - 8) { // ~2 rows from the end
                        loadingMore[0] = true;
                        int offset = total;
                        String filterAtRequestTime = currentFilter[0];
                        mediaQueryExecutor.execute(() -> {
                            List<RecentMediaLoader.Item> more =
                                    RecentMediaLoader.loadRecentPage(activity, offset, GRID_PAGE_SIZE, filterAtRequestTime);
                            activity.runOnUiThread(() -> {
                                if (!java.util.Objects.equals(filterAtRequestTime, currentFilter[0])) return; // stale
                                loadingMore[0] = false;
                                if (more.isEmpty()) {
                                    noMorePages[0] = true;
                                } else {
                                    finalGridAdapter.append(more);
                                    if (more.size() < GRID_PAGE_SIZE) noMorePages[0] = true;
                                }
                            });
                        });
                    }
                }
            });
        }

        // "Recents ▾" dropdown — opens AttachSheetFolderPicker's popup listing
        // every on-device folder; picking one swaps the grid's filter, clears
        // the current page, and re-runs the first-page load above against the
        // new folder (see loadFirstPageHolder). Any selection made in the old
        // folder is intentionally left as-is — WhatsApp/Telegram both keep a
        // cross-folder selection alive the same way.
        if (recentsDropdownRow != null && hasPerm) {
            recentsDropdownRow.setOnClickListener(x -> AttachSheetFolderPicker.showUnderAnchor(
                    activity, recentsDropdownRow, sheetRoot, mediaQueryExecutor, currentFilter[0],
                    folder -> {
                        if (RecentMediaLoader.ACTION_MORE_APPS.equals(folder.filterKey)) {
                            sheet.dismiss();
                            callbacks.onMoreAppsRequested();
                            return;
                        }
                        if (RecentMediaLoader.ACTION_SEE_MORE.equals(folder.filterKey)) {
                            sheet.dismiss();
                            callbacks.onSeeMoreRequested();
                            return;
                        }
                        currentFilter[0] = folder.filterKey;
                        if (recentsTitle != null) recentsTitle.setText(folder.name);
                        if (loadFirstPageHolder[0] != null) loadFirstPageHolder[0].run();
                    }));
        }

        // Peek height = top_content (drag handle + icon grid) PLUS
        // PEEK_GRID_ROWS worth of the Recents grid sitting right below it —
        // so the collapsed sheet always shows the icon grid, the camera
        // tile, and one full row of recent thumbnails with the next row
        // peeking out half-cut, matching the reference screenshot. Dragging
        // up past that reveals the rest of the grid (and pages in more).
        final int peekGridPx = Math.round(cellPx * PEEK_GRID_ROWS);
        topContent.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (topContent.getHeight() > 0) {
                    topContent.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    // Fast-fling overshoot fix: calling behavior.setMaxHeight()
                    // (which internally calls requestLayout()) from inside this
                    // callback fires WHILE CoordinatorLayout is still mid layout
                    // traversal, so the corrected re-measure can get deferred a
                    // frame later instead of landing immediately. A quick upward
                    // fling released in that gap still gets settled against the
                    // earlier, less-precise ceiling — that's what sent the sheet
                    // climbing past y=0 specifically on fast drags while a slow
                    // drag (which naturally waits out that gap) landed correctly.
                    // Posting this instead lets the current traversal finish
                    // first, so the refined ceiling is fully committed as its
                    // own clean frame before the sheet can respond to a gesture.
                    topContent.post(() -> {
                        int refreshedMaxHeightPx = computeMaxSheetHeightPx(activity, sheet);
                        if (refreshedMaxHeightPx > 0) behavior.setMaxHeight(refreshedMaxHeightPx);

                        behavior.setSkipCollapsed(false);
                        behavior.setPeekHeight(topContent.getHeight() + peekGridPx);
                        behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                    });
                }
            }
        });

        // Crossfade: icon grid fades OUT / collapses, "Recents" header
        // fades IN, over the first FADE_FRACTION of the collapsed→expanded
        // drag — instead of the icon grid just sitting there and the grid
        // growing in underneath it. slideOffset is 0 at peek (COLLAPSED) and
        // 1 at STATE_EXPANDED, same reference BottomSheetBehavior uses for
        // its own drag physics, so this rides the same gesture 1:1 — including
        // when the transition is driven programmatically (behavior.setState()
        // from a grid tap above), since Material's BottomSheetBehavior
        // animates setState() the same way it animates a released drag.
        //
        // Plain linear alpha read as flat/mechanical, so this also eases the
        // progress through a decelerate curve and adds a small translateY +
        // scale on both layers (icon grid drifts up/shrinks slightly as it
        // leaves, header settles down/in as it arrives) — same idea as
        // Telegram's attach-sheet header swap, not just an opacity dissolve.
        final android.view.animation.Interpolator fadeEasing = new android.view.animation.DecelerateInterpolator(1.6f);
        final float driftPx = dpToPx(activity, 14f);

        if (iconGridSection != null || expandedHeader != null) {
            behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override public void onStateChanged(@androidx.annotation.NonNull View bs, int newState) {}

                @Override public void onSlide(@androidx.annotation.NonNull View bs, float slideOffset) {
                    float t = Math.max(0f, Math.min(1f, slideOffset)) / FADE_FRACTION;
                    t = Math.max(0f, Math.min(1f, t));
                    float eased = fadeEasing.getInterpolation(t);
                    float fadeOut = 1f - eased;
                    float fadeIn  = eased;

                    if (iconGridSection != null) {
                        iconGridSection.setAlpha(fadeOut);
                        iconGridSection.setTranslationY(-driftPx * eased);
                        float scale = 1f - 0.04f * eased;
                        iconGridSection.setScaleX(scale);
                        iconGridSection.setScaleY(scale);
                        iconGridSection.setVisibility(fadeOut <= 0.02f ? View.GONE : View.VISIBLE);
                    }
                    if (expandedHeader != null) {
                        expandedHeader.setAlpha(fadeIn);
                        expandedHeader.setTranslationY(driftPx * (1f - eased));
                        expandedHeader.setVisibility(fadeIn <= 0.02f ? View.GONE : View.VISIBLE);
                    }
                }
            });
        }
    }

    /** Fades/slides the floating caption+send bar in when 1+ items are picked, out when none are. */
    private static void updateSelectionBar(AppCompatActivity activity, MediaSelectionState selection,
                                            View selectionBar, TextView sendCount) {
        if (selectionBar == null) return;
        if (sendCount != null) sendCount.setText(String.valueOf(Math.max(1, selection.size())));

        boolean shouldShow = !selection.isEmpty();
        boolean currentlyShown = selectionBar.getVisibility() == View.VISIBLE && selectionBar.getAlpha() > 0.5f;
        if (shouldShow == currentlyShown) return;

        float driftPx = dpToPx(activity, 24f);
        if (shouldShow) {
            selectionBar.setVisibility(View.VISIBLE);
            selectionBar.setAlpha(0f);
            selectionBar.setTranslationY(driftPx);
            selectionBar.animate().alpha(1f).translationY(0f).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .withEndAction(null).start();
        } else {
            selectionBar.animate().alpha(0f).translationY(driftPx).setDuration(150)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> selectionBar.setVisibility(View.GONE)).start();
        }
    }

    private static float dpToPx(AppCompatActivity activity, float dp) {
        return dp * activity.getResources().getDisplayMetrics().density;
    }

    /**
     * Real ceiling for BottomSheetBehavior#setMaxHeight — see the
     * "Overshoot fix" comment in bind() for why this can't just be
     * `DisplayMetrics.heightPixels - status_bar_height dimen`.
     *
     * IMPORTANT: measures off the BottomSheetDialog's OWN window, not the
     * host activity's. BottomSheetDialog opens a separate Window from the
     * activity, and on an edge-to-edge screen that dialog window has its
     * own independent WindowInsets dispatch/timing — reading the
     * activity's decorView/insets here was the actual root cause of the
     * sheet landing short of the screen edge (floating above the nav bar)
     * specifically on the very first expand, before anything had forced
     * the two windows' measurements back into agreement. Falls back to the
     * activity's window only if the dialog's own window isn't available
     * yet (called from bind(), before sheet.show()).
     */
    private static int computeMaxSheetHeightPx(AppCompatActivity activity, BottomSheetDialog sheet) {
        android.view.Window dialogWindow = sheet.getWindow();
        View decorView = dialogWindow != null ? dialogWindow.getDecorView()
                                               : activity.getWindow().getDecorView();
        int windowHeightPx = decorView.getHeight();
        int statusBarInsetPx = 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decorView);
        if (insets == null) {
            // Dialog window insets not dispatched yet — the activity's are
            // the closest available approximation for this early call.
            insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
        }
        if (insets != null) {
            statusBarInsetPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
        }
        if (statusBarInsetPx <= 0) {
            // Fallback for the pre-layout call: legacy dimen lookup.
            int statusBarResId = activity.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (statusBarResId > 0) {
                statusBarInsetPx = activity.getResources().getDimensionPixelSize(statusBarResId);
            }
        }
        if (windowHeightPx <= 0) {
            // Decor view not laid out yet — best available estimate.
            windowHeightPx = activity.getResources().getDisplayMetrics().heightPixels;
        }
        int topGapPx = Math.round(dpToPx(activity, 24f)); // sliver of chat stays visible, like the reference

        // Never trust the decorView-based measurement in isolation — right
        // after the dialog window attaches it can briefly read taller than
        // the real screen (that gap is exactly what let a fast fling,
        // released before the corrected re-measure lands, settle with the
        // sheet's top above y=0). Clamping against the plain DisplayMetrics
        // screen height too means the very first value handed to
        // setMaxHeight() is always the more conservative of the two, so it
        // can never be too tall — the later refresh can only tighten it
        // further, never loosen it past a safe bound.
        //
        // DisplayMetrics#heightPixels excludes the navigation bar, while the
        // dialog's decorView is edge-to-edge (now explicitly forced so via
        // WindowCompat.setDecorFitsSystemWindows in bind()) and genuinely
        // extends behind it. Clamping straight against heightPixels
        // therefore shrank the ceiling by the navigation bar's height, so
        // on the very first STATE_EXPANDED layout the bottom selection bar
        // landed short of the screen edge — floating above the nav bar with
        // a gap instead of sitting right above it. Add the nav bar inset
        // back in so the clamp matches the decorView's true edge-to-edge
        // height.
        int navBarInsetPx = 0;
        if (insets != null) {
            navBarInsetPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
        }
        int rawScreenHeightPx = activity.getResources().getDisplayMetrics().heightPixels + navBarInsetPx;
        windowHeightPx = Math.min(windowHeightPx, rawScreenHeightPx);

        return windowHeightPx - statusBarInsetPx - topGapPx;
    }

    /** Swaps the HD chip between its filled-green (ON) and outline (OFF) look. */
    private static void updateHdToggleVisual(TextView hdToggle, boolean enabled) {
        hdToggle.setBackgroundResource(enabled ? R.drawable.bg_hd_toggle_active : R.drawable.bg_hd_toggle_inactive);
        hdToggle.setTextColor(enabled ? 0xFFFFFFFF : ContextCompat.getColor(hdToggle.getContext(), R.color.text_secondary));
    }

    /**
     * Swaps the view-once icon between idle-grey and active-purple — same
     * two colors ChatActivity#setViewOnceMode uses for the input-bar
     * btn_view_once toggle, so the feature reads identically from either
     * entry point.
     */
    private static void updateViewOnceToggleVisual(ImageView viewOnceToggle, boolean enabled) {
        viewOnceToggle.setColorFilter(enabled
                ? android.graphics.Color.parseColor("#FF6200EE")   // active tint
                : android.graphics.Color.parseColor("#FF8A8A8A")); // idle/grey tint
    }

    private static boolean hasMediaReadPermission(AppCompatActivity activity) {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED;
    }
}
