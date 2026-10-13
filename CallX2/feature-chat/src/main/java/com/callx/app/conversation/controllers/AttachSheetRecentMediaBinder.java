package com.callx.app.conversation.controllers;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;

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
    // Sheet sizing now lives in Layouter (grid height = real available height,
    // not a fixed 560dp); this is only the pre-layout fallback.
    // Fraction of the sheet's collapsed→expanded drag distance over which the
    // icon grid fades out and the "Recents" header fades in. Kept short
    // (first 35% of the drag) so the crossfade finishes well before the user
    // reaches full expansion, matching the reference screenshots.
    private static final float FADE_FRACTION = 0.35f;
    // How much of the Recents grid to reveal below the collapsed icon grid —
    // one full row (1.0) plus half of the next row peeking out, matching the
    // reference screenshot exactly.
    private static final float PEEK_GRID_ROWS = 1.5f;
    private static final int GRID_TOP_MARGIN_DP = 14; // recents_grid's layout_marginTop
    private static final int SHEET_BOTTOM_PADDING_DP = 12; // sheet_scroll_content's paddingBottom

    /**
     * Owns every size-related decision for the sheet so they can't drift apart:
     *
     *  - FILL mode (enough media to fill the screen): the grid is sized to the
     *    REAL available height (screen ceiling − fixed top area), not a
     *    hardcoded 560dp. While dragging up, the icon grid / "Recents" header
     *    only crossfade (alpha, never GONE — see top_swap in the layout), and
     *    the grid slides up by the height the icon grid frees, so the sheet's
     *    layout height never changes mid-drag (the old GONE toggle made the
     *    whole content jump). At STATE_EXPANDED the grid's bottom lands exactly
     *    on the sheet's bottom.
     *  - NON-FILL mode (few items): no crossfade, no slide — the sheet is just
     *    as tall as its content, like before.
     *  - Bottom padding on the grid = nav-bar/keyboard inset (+ the floating
     *    selection bar's height while something is selected) so the last row
     *    is never hidden behind either.
     *  - The selection bar's bottom margin follows the nav-bar / keyboard
     *    inset, so the caption field is never covered by the IME.
     */
    private static final class Layouter {
        final AppCompatActivity activity;
        final BottomSheetDialog sheet;
        final BottomSheetBehavior<android.widget.FrameLayout> behavior;
        final View topContent, iconGrid, header, selectionBar;
        final View[] belowGrid; // recents_empty / allow button — shifted together with the grid
        final RecyclerView grid;
        final int cellPx;
        RecentMediaGridAdapter adapter;
        MediaSelectionState selection;
        int navInset, imeInset;
        boolean fill;
        float lastSlide;
        private final android.view.animation.Interpolator fadeEasing =
                new android.view.animation.DecelerateInterpolator(1.6f);

        Layouter(AppCompatActivity activity, BottomSheetDialog sheet,
                 BottomSheetBehavior<android.widget.FrameLayout> behavior, View topContent,
                 View iconGrid, View header, View selectionBar, RecyclerView grid,
                 int cellPx, View... belowGrid) {
            this.activity = activity; this.sheet = sheet; this.behavior = behavior;
            this.topContent = topContent; this.iconGrid = iconGrid; this.header = header;
            this.selectionBar = selectionBar; this.grid = grid; this.cellPx = cellPx;
            this.belowGrid = belowGrid;
        }

        private int dp(float v) { return Math.round(dpToPx(activity, v)); }

        int bottomInset() { return Math.max(navInset, imeInset); }

        /** Height the icon grid frees up when it crossfades to the (shorter) header. */
        int delta() {
            if (iconGrid == null || header == null) return 0;
            return Math.max(0, iconGrid.getHeight() - header.getHeight());
        }

        int gridPadBottom() {
            int pad = bottomInset();
            if (selection != null && !selection.isEmpty() && selectionBar != null) {
                int barH = selectionBar.getHeight() > 0 ? selectionBar.getHeight() : dp(66);
                pad += barH + dp(20); // bar + its top/bottom margins
            }
            return pad;
        }

        void setInsets(int nav, int ime) {
            if (nav == navInset && ime == imeInset) return;
            navInset = nav; imeInset = ime;
            update();
        }

        void update() {
            // Selection bar rides above the nav bar / keyboard.
            if (selectionBar != null && selectionBar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) selectionBar.getLayoutParams();
                int want = dp(10) + bottomInset();
                if (mlp.bottomMargin != want) { mlp.bottomMargin = want; selectionBar.setLayoutParams(mlp); }
            }
            int padB = gridPadBottom();
            if (grid.getPaddingBottom() != padB) {
                grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(), grid.getPaddingRight(), padB);
            }

            int count = adapter != null ? adapter.getItemCount() : 1;
            int rows = Math.max(1, (count + 3) / 4);
            int needed = rows * cellPx + padB;
            int ceiling = computeMaxSheetHeightPx(activity, sheet);
            int top = topContent.getHeight();
            if (top <= 0) { // not laid out yet — first real update comes from the layout listener
                setGridHeight(Math.min(needed, dp(560)));
                if (ceiling > 0) behavior.setMaxHeight(ceiling);
                return;
            }

            int d = delta();
            int spacing = dp(GRID_TOP_MARGIN_DP + SHEET_BOTTOM_PADDING_DP);
            int maxG = ceiling > 0 ? Math.max(cellPx, ceiling + d - top - spacing) : dp(560);
            fill = d > 0 && ceiling > 0 && needed >= maxG;
            int g = Math.min(maxG, needed);
            setGridHeight(g);

            // FILL: expanded sheet == ceiling (grid slid up by d). NON-FILL: sheet == its content.
            int expandedH = fill ? ceiling : top + spacing + g;
            if (ceiling > 0) expandedH = Math.min(ceiling, expandedH);
            behavior.setMaxHeight(expandedH);
            behavior.setPeekHeight(Math.min(top + Math.round(cellPx * PEEK_GRID_ROWS), expandedH));
            onSlide(lastSlide); // re-apply visuals if fill/non-fill just flipped
        }

        private void setGridHeight(int px) {
            ViewGroup.LayoutParams lp = grid.getLayoutParams();
            if (lp.height != px) { lp.height = px; grid.setLayoutParams(lp); }
        }

        /** Drag progress 0 (peek) … 1 (expanded). Alpha/translation only — never touches layout. */
        void onSlide(float slideOffset) {
            lastSlide = slideOffset;
            float t = fill ? Math.max(0f, Math.min(1f, slideOffset)) / FADE_FRACTION : 0f;
            t = Math.max(0f, Math.min(1f, t));
            float eased = fadeEasing.getInterpolation(t);
            float drift = dpToPx(activity, 14f);

            if (iconGrid != null) {
                iconGrid.setAlpha(1f - eased);
                iconGrid.setTranslationY(-drift * eased);
                float scale = 1f - 0.04f * eased;
                iconGrid.setScaleX(scale);
                iconGrid.setScaleY(scale);
                // INVISIBLE (not GONE): keeps top_swap's height constant; still blocks taps.
                iconGrid.setVisibility(1f - eased <= 0.02f ? View.INVISIBLE : View.VISIBLE);
            }
            if (header != null) {
                header.setAlpha(eased);
                header.setTranslationY(drift * (1f - eased));
                header.setVisibility(eased <= 0.02f ? View.INVISIBLE : View.VISIBLE);
            }
            // Grid slides up into the space the icon grid freed, only once the icons are mostly gone (no overlap).
            float shiftT = fill ? Math.max(0f, Math.min(1f, (eased - 0.4f) / 0.6f)) : 0f;
            float shift = delta() * shiftT;
            grid.setTranslationY(-shift);
            for (View v : belowGrid) if (v != null) v.setTranslationY(-shift);
        }
    }

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

    /**
     * Per-screen knobs, so a surface configures the shared sheet instead of
     * reaching into its views after inflate (Status used to hide 7 chip ids by
     * hand and still showed a dead Audio chip + HD toggle).
     * Defaults = the full chat sheet.
     */
    public static final class Config {
        int[] hiddenViewIds = new int[0];
        int maxSelection = MediaSelectionState.MAX_SELECTION;
        boolean supportsViewOnce = true;
        boolean showHdToggle = true;
        int mediaTypes = RecentMediaLoader.MEDIA_ALL;

        /** Views (icon-grid chips, toggles, …) to hide for this surface. */
        public Config hide(int... viewIds) { this.hiddenViewIds = viewIds; return this; }
        /** Max items one send/post may carry (toast shown past it). */
        public Config maxSelection(int max) { this.maxSelection = max; return this; }
        /** false = hide the view-once toggle (surface has no view-once pipeline). */
        public Config viewOnce(boolean enabled) { this.supportsViewOnce = enabled; return this; }
        /** false = hide the HD toggle (surface ignores isHD). */
        public Config hdToggle(boolean visible) { this.showHdToggle = visible; return this; }
        /** RecentMediaLoader.MEDIA_ALL / MEDIA_IMAGES / MEDIA_VIDEOS. */
        public Config mediaTypes(int types) { this.mediaTypes = types; return this; }
    }

    public static void bind(AppCompatActivity activity, BottomSheetDialog sheet, View sheetRoot,
                             ExecutorService mediaQueryExecutor, Callbacks callbacks) {
        bind(activity, sheet, sheetRoot, mediaQueryExecutor, new Config(), callbacks);
    }

    /**
     * Back-compat overload.
     * @param supportsViewOnce whether this surface has a view-once pipeline
     *        wired up on the sending side (currently 1-1 chat only — group
     *        chat has no ChatViewOnceController equivalent yet). When false,
     *        the toggle button is hidden entirely instead of sitting there
     *        as a dead/misleading control.
     */
    public static void bind(AppCompatActivity activity, BottomSheetDialog sheet, View sheetRoot,
                             ExecutorService mediaQueryExecutor, boolean supportsViewOnce, Callbacks callbacks) {
        bind(activity, sheet, sheetRoot, mediaQueryExecutor, new Config().viewOnce(supportsViewOnce), callbacks);
    }

    public static void bind(AppCompatActivity activity, BottomSheetDialog sheet, View sheetRoot,
                             ExecutorService mediaQueryExecutor, Config config, Callbacks callbacks) {
        final Config cfg = config != null ? config : new Config();
        for (int id : cfg.hiddenViewIds) {
            View hidden = sheetRoot.findViewById(id);
            if (hidden != null) hidden.setVisibility(View.GONE);
        }
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
            if (!cfg.supportsViewOnce) {
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

        MediaSelectionState selection = new MediaSelectionState(cfg.maxSelection);

        // WhatsApp-style HD toggle — OFF by default (Standard/compressed
        // send), tapping flips it ON for this sheet session so the next
        // Send uses ImageCompressor's higher HD cap instead. Purely a
        // per-open-sheet UI flag; not persisted, same as WhatsApp resets
        // it back to Standard the next time you open the attach sheet.
        final boolean[] hdEnabled = {false};
        if (hdToggle != null && !cfg.showHdToggle) {
            hdToggle.setVisibility(View.GONE);
        } else if (hdToggle instanceof TextView) {
            updateHdToggleVisual((TextView) hdToggle, false);
            hdToggle.setOnClickListener(x -> {
                hdEnabled[0] = !hdEnabled[0];
                updateHdToggleVisual((TextView) hdToggle, hdEnabled[0]);
            });
        }

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int cellPx = dm.widthPixels / 4;

        // ★ ROOT-CAUSE FIX: Material's internal container one level up
        // (design_bottom_sheet — the actual view BottomSheetBehavior
        // measures/drags, one level above our own inflated sheetRoot) was
        // sizing itself LARGER than sheetRoot's real measured content —
        // confirmed by testing: a big blank gap appears between the last
        // grid row and the screen edge, with the chat visible below THAT.
        // Forcing this container's own layout height to WRAP_CONTENT here
        // makes BottomSheetBehavior's onMeasureChild measure it with
        // AT_MOST(maxHeight) instead of stretching it to fill maxHeight
        // regardless of content — so its final height can never exceed
        // what sheetRoot actually needs. Also re-painting the same rounded
        // card background on it (belt-and-suspenders): even if some future
        // timing edge case still over-allocates by a frame, the leftover
        // reads as card, never as the chat screen behind it.
        sheetRoot.post(() -> {
            Object outerContainerObj = sheetRoot.getParent();
            if (outerContainerObj instanceof View) {
                View outerContainer = (View) outerContainerObj;
                outerContainer.setBackgroundResource(R.drawable.bg_bottom_sheet_round);
                ViewGroup.LayoutParams outerLp = outerContainer.getLayoutParams();
                if (outerLp != null && outerLp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    outerLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    outerContainer.setLayoutParams(outerLp);
                }
            }
        });

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
        final Layouter[] layouterHolder = new Layouter[1];
        // Keyboard: lets the dialog window receive IME insets (edge-to-edge dialog → no automatic resize).
        if (sheet.getWindow() != null) {
            sheet.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        ViewCompat.setOnApplyWindowInsetsListener(sheetRoot, (view, windowInsets) -> {
            Layouter l = layouterHolder[0];
            if (l != null) {
                l.setInsets(windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                        windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                l.update();
            } else {
                int refreshedMaxHeightPx = computeMaxSheetHeightPx(activity, sheet);
                if (refreshedMaxHeightPx > 0) behavior.setMaxHeight(refreshedMaxHeightPx);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(sheetRoot);
        // --------------------------------------------------------------------
        RecentMediaGridAdapter.Listener gridListener = item -> {
            // Cap the batch (chat + status both post one message/status per item).
            if (!selection.isSelected(item.uri) && selection.isFull()) {
                Toast.makeText(activity, activity.getString(R.string.attach_selection_limit,
                        selection.maxSelection()), Toast.LENGTH_SHORT).show();
                return;
            }
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
        // ★ FIX: clamp immediately off the adapter's initial state too —
        // covers the no-media-permission case (grid never gets a submit()
        // call at all, so without this it would sit at the old hardcoded
        // 560dp showing just a camera tile in a sea of empty space) and
        // avoids a brief 560dp→shrink flash before the async first page
        // load above returns.
        final Layouter layouter = new Layouter(activity, sheet, behavior, topContent, iconGridSection,
                expandedHeader, selectionBar, grid, cellPx, recentsEmpty,
                sheetRoot.findViewById(R.id.btn_recents_allow));
        layouter.adapter = gridAdapter;
        layouter.selection = selection;
        layouterHolder[0] = layouter;
        layouter.update();
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
        selection.addListener(() -> {
            updateSelectionBar(activity, selection, selectionBar, sendCount);
            layouter.update(); // grid bottom padding follows the bar
        });

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

        // Permission can be granted LATER from the empty state's "Allow access"
        // button, so access is a mutable flag and everything below (first-page
        // load, infinite scroll, folder dropdown) checks it at run time instead
        // of being wired only if the permission happened to exist at bind().
        final boolean[] hasAccess = {hasMediaAccess(activity)};
        View allowBtn = sheetRoot.findViewById(R.id.btn_recents_allow);
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
        // De-dupe: track URI strings already submitted to the grid adapter so a
        // MediaStore update mid-session can't introduce duplicate items on re-load.
        final java.util.Set<String> submittedUriStrings = new java.util.LinkedHashSet<>();

        {
            loadFirstPageHolder[0] = () -> {
                if (!hasAccess[0]) {
                    showEmptyState(recentsEmpty, allowBtn, false, true);
                    return;
                }
                loadingMore[0] = false;
                noMorePages[0] = false;
                String filterAtRequestTime = currentFilter[0];
                mediaQueryExecutor.execute(() -> {
                    List<RecentMediaLoader.Item> items =
                            RecentMediaLoader.loadPageAfter(activity, null, RECENT_MEDIA_LIMIT, filterAtRequestTime, cfg.mediaTypes);
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (!java.util.Objects.equals(filterAtRequestTime, currentFilter[0])) return; // stale
                        // New filter = new page = reset de-dupe tracking
                        submittedUriStrings.clear();
                        List<RecentMediaLoader.Item> deduped = new java.util.ArrayList<>();
                        for (RecentMediaLoader.Item item : items) {
                            if (submittedUriStrings.add(item.uri.toString())) deduped.add(item);
                        }
                        finalGridAdapter.submit(deduped);
                        // ★ FIX: clamp grid height to what this page's item
                        // count actually needs — getItemCount() already
                        // accounts for the camera-tile offset internally.
                        layouter.update();
                        grid.scrollToPosition(0);
                        showEmptyState(recentsEmpty, allowBtn, deduped.isEmpty(), false);
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
                    if (dy <= 0 || !hasAccess[0] || loadingMore[0] || noMorePages[0]) return;
                    GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                    if (lm == null) return;
                    int lastVisible = lm.findLastVisibleItemPosition();
                    int total = finalGridAdapter.getLoadedCount();
                    if (lastVisible >= total - 8) { // ~2 rows from the end
                        loadingMore[0] = true;
                        // Keyset cursor: continue strictly after the last loaded item
                        // instead of re-reading an ever-growing offset prefix.
                        final RecentMediaLoader.Item lastLoaded = finalGridAdapter.getLastItem();
                        String filterAtRequestTime = currentFilter[0];
                        mediaQueryExecutor.execute(() -> {
                            List<RecentMediaLoader.Item> more =
                                    RecentMediaLoader.loadPageAfter(activity, lastLoaded, GRID_PAGE_SIZE, filterAtRequestTime, cfg.mediaTypes);
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            activity.runOnUiThread(() -> {
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                if (!java.util.Objects.equals(filterAtRequestTime, currentFilter[0])) return; // stale
                                loadingMore[0] = false;
                                if (more.isEmpty()) {
                                    noMorePages[0] = true;
                                } else {
                                    // De-dupe before appending
                                    List<RecentMediaLoader.Item> deduped = new java.util.ArrayList<>();
                                    for (RecentMediaLoader.Item item : more) {
                                        if (submittedUriStrings.add(item.uri.toString())) deduped.add(item);
                                    }
                                    if (!deduped.isEmpty()) finalGridAdapter.append(deduped);
                                    // ★ FIX: re-clamp as more pages load in —
                                    // grows the grid's height to match the
                                    // NEW total.
                                    // getItemCount() (not getLoadedCount()) —
                                    // includes the +1 camera-tile offset, same
                                    // basis the other two call sites use.
                                    layouter.update();
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
        if (allowBtn != null) {
            allowBtn.setOnClickListener(x -> requestMediaAccess(activity, () -> {
                hasAccess[0] = hasMediaAccess(activity);
                if (hasAccess[0]) {
                    if (loadFirstPageHolder[0] != null) loadFirstPageHolder[0].run();
                } else {
                    Toast.makeText(activity, R.string.attach_permission_denied, Toast.LENGTH_LONG).show();
                    // "Don't ask again" (or denied twice): the system dialog won't
                    // show any more — send them to the app's Settings page instead.
                    if (!shouldShowRationale(activity)) openAppSettings(activity);
                }
            }));
        }
        if (recentsDropdownRow != null) {
            recentsDropdownRow.setOnClickListener(x -> { if (!hasAccess[0]) return; AttachSheetFolderPicker.showUnderAnchor(
                    activity, recentsDropdownRow, sheetRoot, mediaQueryExecutor, currentFilter[0], cfg.mediaTypes,
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
                    }); });
        }

        // Peek height = top_content (drag handle + icon grid) PLUS
        // PEEK_GRID_ROWS worth of the Recents grid sitting right below it —
        // so the collapsed sheet always shows the icon grid, the camera
        // tile, and one full row of recent thumbnails with the next row
        // peeking out half-cut, matching the reference screenshot. Dragging
        // up past that reveals the rest of the grid (and pages in more).
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
                        layouter.update(); // maxHeight + peek from the real measured sizes
                        behavior.setSkipCollapsed(false);
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
        if (iconGridSection != null || expandedHeader != null) {
            behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override public void onStateChanged(@androidx.annotation.NonNull View bs, int newState) {}

                @Override public void onSlide(@androidx.annotation.NonNull View bs, float slideOffset) {
                    layouter.onSlide(slideOffset);
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

    /** Permissions that unlock the Recents grid on this Android version. */
    private static String[] mediaPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"};
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /**
     * True if ANY media access is granted — images, videos, or Android 14's
     * "selected photos" (READ_MEDIA_VISUAL_USER_SELECTED, where MediaStore
     * returns just the items the user picked). Images and videos are queried
     * from separate tables, so one granted type is enough to show something;
     * the denied table just throws SecurityException which the loader catches.
     */
    private static boolean hasMediaAccess(AppCompatActivity activity) {
        for (String perm : mediaPermissions()) {
            if (ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldShowRationale(AppCompatActivity activity) {
        for (String perm : mediaPermissions()) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)) return true;
        }
        return false;
    }

    private static void openAppSettings(AppCompatActivity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.getPackageName(), null)));
        } catch (Exception ignored) { /* no settings activity — toast already explains */ }
    }

    /**
     * Asks for the media permissions from inside an already-open sheet.
     * registerForActivityResult() must normally run before the Activity is
     * STARTED, which is long past by the time a sheet opens, so this goes
     * through the registry directly (and unregisters itself after one result).
     */
    private static void requestMediaAccess(AppCompatActivity activity, Runnable onResult) {
        final String key = "attach_sheet_media_perm_" + System.nanoTime();
        final ActivityResultLauncher<String[]>[] holder = new ActivityResultLauncher[1];
        holder[0] = activity.getActivityResultRegistry().register(key,
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    if (holder[0] != null) holder[0].unregister();
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    onResult.run();
                });
        holder[0].launch(mediaPermissions());
    }

    /**
     * Empty-state text + "Allow access" button.
     * @param noMedia  access is fine but this folder/device has nothing to show
     * @param noAccess permission missing — show the permission message + button
     */
    private static void showEmptyState(View emptyView, View allowBtn, boolean noMedia, boolean noAccess) {
        if (emptyView instanceof TextView) {
            ((TextView) emptyView).setText(noAccess ? R.string.attach_empty_no_permission
                                                     : R.string.attach_empty_no_media);
        }
        if (emptyView != null) emptyView.setVisibility(noMedia || noAccess ? View.VISIBLE : View.GONE);
        if (allowBtn != null) allowBtn.setVisibility(noAccess ? View.VISIBLE : View.GONE);
    }
}
