package com.callx.app.conversation.controllers;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * WhatsApp-style attach-sheet media: a bottom row of [camera tile, recent
 * gallery thumbnails...] (bottom_media_row) that sits right after the
 * Poll/Payment/Event/AI images icon grid, plus a 4-column "Recents" grid
 * that's clipped below the collapsed peek line and only revealed when the
 * sheet is dragged up.
 *
 * Tapping a thumbnail (strip OR grid) no longer sends immediately — it
 * toggles a numbered multi-select (MediaSelectionState) shared by both
 * views, same as WhatsApp/Telegram. Tapping from the STRIP (i.e. while the
 * sheet is still collapsed/peeking) additionally drives the sheet straight
 * to STATE_EXPANDED so the just-picked item lands in the "Recents" view
 * still marked selected — riding the exact same eased crossfade the drag
 * gesture already uses (BottomSheetBehavior animates programmatic
 * setState() calls the same way it animates a released drag), so the
 * transition is smooth rather than an instant cut. A floating caption/send
 * bar (selection_bar) appears once anything is selected and fires
 * Callbacks#onMediaSend with the full ordered selection.
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
    // icon grid/strip fade out and the "Recents" header fades in. Kept short
    // (first 35% of the drag) so the crossfade finishes well before the user
    // reaches full expansion, matching the reference screenshots.
    private static final float FADE_FRACTION = 0.35f;

    public interface Callbacks {
        void onCameraTapped();
        /**
         * Fired when the user taps Send on the selection bar with 1+ items picked.
         * @param isHD whether the WhatsApp-style HD toggle (top-right of the
         *             expanded "Recents" header) was ON at send time — true
         *             means images should be compressed at the higher HD cap
         *             instead of the default/Standard cap.
         */
        void onMediaSend(List<RecentMediaLoader.Item> items, String caption, boolean isHD);
    }

    public static void bind(AppCompatActivity activity, BottomSheetDialog sheet, View sheetRoot,
                             ExecutorService mediaQueryExecutor, Callbacks callbacks) {
        RecyclerView bottomRow  = sheetRoot.findViewById(R.id.bottom_media_row);
        RecyclerView grid       = sheetRoot.findViewById(R.id.recents_grid);
        View recentsLabel       = sheetRoot.findViewById(R.id.recents_label);
        View recentsEmpty       = sheetRoot.findViewById(R.id.recents_empty);
        View topContent         = sheetRoot.findViewById(R.id.top_content);
        View iconGridSection    = sheetRoot.findViewById(R.id.icon_grid_section);
        View expandedHeader     = sheetRoot.findViewById(R.id.expanded_header);
        View closeBtn           = sheetRoot.findViewById(R.id.btn_close_sheet);
        View selectionBar       = sheetRoot.findViewById(R.id.selection_bar);
        EditText captionInput   = sheetRoot.findViewById(R.id.selection_caption_input);
        View sendBtn            = sheetRoot.findViewById(R.id.btn_selection_send);
        TextView sendCount      = sheetRoot.findViewById(R.id.selection_send_count);
        if (bottomRow == null || topContent == null) return; // older/replaced layout — skip silently

        View hdToggle           = sheetRoot.findViewById(R.id.btn_hd_toggle);
        if (closeBtn != null) closeBtn.setOnClickListener(x -> sheet.dismiss());

        BottomSheetBehavior<android.widget.FrameLayout> behavior = sheet.getBehavior();

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

        RecentMediaStripAdapter.Listener stripListener = new RecentMediaStripAdapter.Listener() {
            @Override public void onCameraTapped() { callbacks.onCameraTapped(); }
            @Override public void onMediaToggled(RecentMediaLoader.Item item) {
                selection.toggle(item);
                // Tapped from the compact strip (only visible at/near peek) —
                // smoothly drive the sheet up to the full Recents view, same
                // as if the user had dragged it, so the pick they just made
                // opens out into the expanded grid instead of just sitting
                // in the small strip. The item stays selected across the move.
                if (behavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                }
            }
        };
        RecentMediaStripAdapter stripAdapter = new RecentMediaStripAdapter(stripListener, selection);
        bottomRow.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        bottomRow.setAdapter(stripAdapter);

        RecentMediaGridAdapter gridAdapter = null;
        if (grid != null) {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int cellPx = dm.widthPixels / 4;
            RecentMediaGridAdapter.Listener gridListener = item -> selection.toggle(item);
            gridAdapter = new RecentMediaGridAdapter(gridListener, selection) {
                @Override public VH onCreateViewHolder(ViewGroup parent, int viewType) {
                    VH h = super.onCreateViewHolder(parent, viewType);
                    ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
                    lp.width = cellPx;
                    lp.height = cellPx;
                    h.itemView.setLayoutParams(lp);
                    return h;
                }
            };
            grid.setLayoutManager(new GridLayoutManager(activity, 4));
            grid.setAdapter(gridAdapter);
        }
        RecentMediaGridAdapter finalGridAdapter = gridAdapter;

        // Keeps the strip + grid + floating selection bar all in sync no
        // matter which view the toggle came from — cheap (≤60 cells) so a
        // full notifyDataSetChanged() here isn't worth the bookkeeping a
        // targeted notifyItemChanged(position) would need across two
        // independently-paginated adapters.
        selection.addListener(() -> {
            stripAdapter.notifyDataSetChanged();
            if (finalGridAdapter != null) finalGridAdapter.notifyDataSetChanged();
            updateSelectionBar(activity, selection, selectionBar, sendCount);
        });

        if (sendBtn != null) {
            sendBtn.setOnClickListener(x -> {
                if (selection.isEmpty()) return;
                List<RecentMediaLoader.Item> items = selection.items();
                String caption = captionInput != null ? captionInput.getText().toString().trim() : "";
                sheet.dismiss();
                callbacks.onMediaSend(items, caption, hdEnabled[0]);
                selection.clear();
            });
        }

        boolean hasPerm = hasMediaReadPermission(activity);
        // Guards against firing two overlapping "load next page" queries off
        // one fast fling, and against paging forever once MediaStore is dry.
        final boolean[] loadingMore = {false};
        final boolean[] noMorePages = {false};
        if (hasPerm) {
            mediaQueryExecutor.execute(() -> {
                List<RecentMediaLoader.Item> items = RecentMediaLoader.loadRecentPage(activity, 0, RECENT_MEDIA_LIMIT);
                activity.runOnUiThread(() -> {
                    stripAdapter.submit(items);
                    if (finalGridAdapter != null) finalGridAdapter.submit(items);
                    if (recentsEmpty != null) recentsEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    if (recentsLabel != null) recentsLabel.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                    if (grid != null) grid.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                    if (items.size() < RECENT_MEDIA_LIMIT) noMorePages[0] = true;
                });
            });

            // Infinite scroll: once the user has dragged the sheet up and is
            // scrolling the grid itself, load the next page a few rows before
            // hitting the bottom — same "keep paging MediaStore" pattern
            // WhatsApp/Telegram use instead of loading everything up front.
            if (grid != null) {
                grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
                    @Override public void onScrolled(@androidx.annotation.NonNull RecyclerView rv, int dx, int dy) {
                        if (dy <= 0 || loadingMore[0] || noMorePages[0] || finalGridAdapter == null) return;
                        GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                        if (lm == null) return;
                        int lastVisible = lm.findLastVisibleItemPosition();
                        int total = finalGridAdapter.getLoadedCount();
                        if (lastVisible >= total - 8) { // ~2 rows from the end
                            loadingMore[0] = true;
                            int offset = total;
                            mediaQueryExecutor.execute(() -> {
                                List<RecentMediaLoader.Item> more =
                                        RecentMediaLoader.loadRecentPage(activity, offset, GRID_PAGE_SIZE);
                                activity.runOnUiThread(() -> {
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
        } else {
            if (recentsLabel != null) recentsLabel.setVisibility(View.GONE);
            if (grid != null) grid.setVisibility(View.GONE);
        }

        // Peek height = collapsed content only (icon rows + bottom camera/media
        // row) so the sheet opens compact; dragging up past that reveals the
        // Recents grid, which lives right after top_content in the layout.
        behavior.setSkipCollapsed(false);
        behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        topContent.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (topContent.getHeight() > 0) {
                    behavior.setPeekHeight(topContent.getHeight());
                    topContent.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            }
        });

        // Crossfade: icon grid + strip fade OUT / collapse, "Recents" header
        // fades IN, over the first FADE_FRACTION of the collapsed→expanded
        // drag — instead of the icon grid just sitting there and the grid
        // growing in underneath it. slideOffset is 0 at peek (COLLAPSED) and
        // 1 at STATE_EXPANDED, same reference BottomSheetBehavior uses for
        // its own drag physics, so this rides the same gesture 1:1 — including
        // when the transition is driven programmatically (behavior.setState()
        // from a strip tap above), since Material's BottomSheetBehavior
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
                    if (bottomRow != null) {
                        bottomRow.setAlpha(fadeOut);
                        bottomRow.setTranslationY(-driftPx * eased);
                        bottomRow.setVisibility(fadeOut <= 0.02f ? View.GONE : View.VISIBLE);
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

    /** Swaps the HD chip between its filled-green (ON) and outline (OFF) look. */
    private static void updateHdToggleVisual(TextView hdToggle, boolean enabled) {
        hdToggle.setBackgroundResource(enabled ? R.drawable.bg_hd_toggle_active : R.drawable.bg_hd_toggle_inactive);
        hdToggle.setTextColor(enabled ? 0xFFFFFFFF : ContextCompat.getColor(hdToggle.getContext(), R.color.text_secondary));
    }

    private static boolean hasMediaReadPermission(AppCompatActivity activity) {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED;
    }
}
