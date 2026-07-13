package com.callx.app.conversation.controllers;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

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
        void onMediaTapped(RecentMediaLoader.Item item);
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
        if (bottomRow == null || topContent == null) return; // older/replaced layout — skip silently

        if (closeBtn != null) closeBtn.setOnClickListener(x -> sheet.dismiss());

        RecentMediaStripAdapter.Listener stripListener = new RecentMediaStripAdapter.Listener() {
            @Override public void onCameraTapped() { callbacks.onCameraTapped(); }
            @Override public void onMediaTapped(RecentMediaLoader.Item item) { callbacks.onMediaTapped(item); }
        };
        RecentMediaStripAdapter stripAdapter = new RecentMediaStripAdapter(stripListener);
        bottomRow.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        bottomRow.setAdapter(stripAdapter);

        RecentMediaGridAdapter gridAdapter = null;
        if (grid != null) {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int cellPx = dm.widthPixels / 4;
            gridAdapter = new RecentMediaGridAdapter(callbacks::onMediaTapped) {
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

        boolean hasPerm = hasMediaReadPermission(activity);
        RecentMediaGridAdapter finalGridAdapter = gridAdapter;
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
        BottomSheetBehavior<android.widget.FrameLayout> behavior = sheet.getBehavior();
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
        // its own drag physics, so this rides the same gesture 1:1.
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

    private static float dpToPx(AppCompatActivity activity, float dp) {
        return dp * activity.getResources().getDisplayMetrics().density;
    }

    private static boolean hasMediaReadPermission(AppCompatActivity activity) {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED;
    }
}
