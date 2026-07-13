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
        if (bottomRow == null || topContent == null) return; // older/replaced layout — skip silently

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
        if (hasPerm) {
            mediaQueryExecutor.execute(() -> {
                List<RecentMediaLoader.Item> items = RecentMediaLoader.loadRecent(activity, RECENT_MEDIA_LIMIT);
                activity.runOnUiThread(() -> {
                    stripAdapter.submit(items);
                    if (finalGridAdapter != null) finalGridAdapter.submit(items);
                    if (recentsEmpty != null) recentsEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    if (recentsLabel != null) recentsLabel.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                    if (grid != null) grid.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                });
            });
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
    }

    private static boolean hasMediaReadPermission(AppCompatActivity activity) {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED;
    }
}
