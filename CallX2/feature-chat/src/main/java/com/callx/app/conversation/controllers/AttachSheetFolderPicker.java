package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.callx.app.chat.R;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Drives the "Recents ▾" dropdown in the attach sheet's expanded header —
 * tapping the chevron next to the "Recents" title queries every on-device
 * media folder (Camera, Videos, Screenshots, Downloads, WhatsApp, ...) via
 * {@link RecentMediaLoader#loadFolders} and shows them in a rounded popup
 * card anchored under the header, exactly like the reference screenshot's
 * folder picker. Picking a row swaps the grid over to that folder.
 */
final class AttachSheetFolderPicker {

    interface OnFolderSelected {
        void onFolderSelected(RecentMediaLoader.Folder folder);
    }

    private AttachSheetFolderPicker() {}

    /**
     * @param anchor      the "Recents ▾" row — the popup drops down from directly below it.
     * @param currentFilterKey the RecentMediaLoader filter currently applied to the grid, so
     *                    the matching row can be checkmarked.
     */
    static void showUnderAnchor(Activity activity, View anchor, View sheetRoot,
                                 ExecutorService executor, String currentFilterKey,
                                 OnFolderSelected callback) {
        executor.execute(() -> {
            List<RecentMediaLoader.Folder> folders = RecentMediaLoader.loadFolders(activity);
            activity.runOnUiThread(() -> {
                if (folders.isEmpty()) return; // no media on device / no permission — nothing to pick from
                show(activity, anchor, sheetRoot, folders, currentFilterKey, callback);
            });
        });
    }

    private static void show(Activity activity, View anchor, View sheetRoot,
                              List<RecentMediaLoader.Folder> folders, String currentFilterKey,
                              OnFolderSelected callback) {
        View content = LayoutInflater.from(activity)
                .inflate(R.layout.popup_attach_folder_list, (ViewGroup) sheetRoot, false);
        MaxHeightRecyclerView list = content.findViewById(R.id.folder_list);
        list.setLayoutManager(new LinearLayoutManager(activity));

        // Cap the popup's height to ~55% of the sheet so a long folder list
        // scrolls internally instead of running off (or past) the bottom of
        // the attach sheet.
        int maxHeightPx = Math.round(sheetRoot.getHeight() * 0.55f);
        if (maxHeightPx > 0) {
            list.setMaxHeightPx(maxHeightPx);
        }

        PopupWindow popup = new PopupWindow(content,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(0)); // let the MaterialCardView draw its own rounded bg
        popup.setElevation(dpToPx(activity, 12f));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(android.R.style.Animation_Dialog);

        AttachFolderAdapter adapter = new AttachFolderAdapter(folders, currentFilterKey, folder -> {
            callback.onFolderSelected(folder);
            popup.dismiss();
        });
        list.setAdapter(adapter);

        // Width: match the sheet (minus a small side inset) so it reads as
        // part of the sheet rather than a floating unrelated menu — same
        // proportions as the reference screenshot's dropdown card.
        int sideInsetPx = Math.round(dpToPx(activity, 12f));
        popup.setWidth(sheetRoot.getWidth() - sideInsetPx * 2);

        int[] anchorLoc = new int[2];
        anchor.getLocationInWindow(anchorLoc);
        int[] sheetLoc = new int[2];
        sheetRoot.getLocationInWindow(sheetLoc);

        int xOffset = sheetLoc[0] + sideInsetPx - anchorLoc[0];
        int yOffset = (int) dpToPx(activity, 6f);
        popup.showAsDropDown(anchor, xOffset, yOffset, Gravity.NO_GRAVITY);
    }

    private static float dpToPx(Activity activity, float dp) {
        return dp * activity.getResources().getDisplayMetrics().density;
    }
}
