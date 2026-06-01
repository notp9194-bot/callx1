package com.callx.app.bottomsheet;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusHighlightManager;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.*;

/**
 * StatusAddToHighlightBottomSheet v25 — Proper highlight album picker.
 * FIX: Was a plain AlertDialog with only a text input — no existing albums shown.
 * NEW: Shows existing highlight albums with item counts, tap to add to existing.
 * NEW: "New album" input field to create a new album.
 * NEW: Loads albums from Firebase statusHighlights/{ownerUid}.
 */
public class StatusAddToHighlightBottomSheet {

    public interface OnAddedListener {
        void onAdded(String albumName);
    }

    public static void show(Context ctx, String ownerUid, StatusItem item,
                            OnAddedListener listener) {
        if (item == null || ownerUid == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 32));

        // Title
        TextView title = new TextView(ctx);
        title.setText("Add to Highlights");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(ctx, 4));
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("Choose an existing album or create new");
        sub.setTextSize(13);
        sub.setTextColor(Color.GRAY);
        sub.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(sub);

        // Existing albums section
        TextView albumsLabel = new TextView(ctx);
        albumsLabel.setText("Existing albums");
        albumsLabel.setTextSize(14);
        albumsLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        albumsLabel.setPadding(0, 0, 0, dp(ctx, 8));
        root.addView(albumsLabel);

        LinearLayout albumList = new LinearLayout(ctx);
        albumList.setOrientation(LinearLayout.VERTICAL);

        ProgressBar progress = new ProgressBar(ctx);
        albumList.addView(progress);
        root.addView(albumList);

        // Divider
        View divider = new View(ctx);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#22000000"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.topMargin = dp(ctx, 16);
        divLp.bottomMargin = dp(ctx, 16);
        divider.setLayoutParams(divLp);
        root.addView(divider);

        // New album input
        TextView newLabel = new TextView(ctx);
        newLabel.setText("Create new album");
        newLabel.setTextSize(14);
        newLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        newLabel.setPadding(0, 0, 0, dp(ctx, 8));
        root.addView(newLabel);

        LinearLayout newRow = new LinearLayout(ctx);
        newRow.setOrientation(LinearLayout.HORIZONTAL);
        newRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        EditText etName = new EditText(ctx);
        etName.setHint("Album name (e.g. Vacation 2024)");
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etName.setLayoutParams(etLp);
        newRow.addView(etName);

        Button addBtn = new Button(ctx);
        addBtn.setText("Add");
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        addLp.setMarginStart(dp(ctx, 8));
        addBtn.setLayoutParams(addLp);
        addBtn.setOnClickListener(v -> {
            String album = etName.getText() != null ? etName.getText().toString().trim() : "";
            if (album.isEmpty()) {
                etName.setError("Enter album name");
                return;
            }
            String albumId = album.toLowerCase(Locale.getDefault()).replace(" ", "_");
            StatusHighlightManager.addToHighlight(ownerUid, item, albumId, album);
            if (listener != null) listener.onAdded(album);
            sheet.dismiss();
        });
        newRow.addView(addBtn);
        root.addView(newRow);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(root);
        sheet.setContentView(scroll);
        sheet.show();

        // Load existing albums from Firebase
        StatusHighlightManager.getHighlightsRef(ownerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snap) {
                    albumList.removeAllViews();
                    if (!snap.exists() || snap.getChildrenCount() == 0) {
                        TextView empty = new TextView(ctx);
                        empty.setText("No existing albums");
                        empty.setTextSize(13);
                        empty.setTextColor(Color.GRAY);
                        albumList.addView(empty);
                        return;
                    }
                    for (DataSnapshot albumSnap : snap.getChildren()) {
                        String albumId   = albumSnap.getKey();
                        if (albumId == null) continue;
                        long count       = albumSnap.getChildrenCount();
                        // Try to get album display name from first child
                        String albumName = albumId;
                        DataSnapshot firstItem = albumSnap.getChildren().iterator().hasNext()
                                ? albumSnap.getChildren().iterator().next() : null;
                        if (firstItem != null) {
                            String n = firstItem.child("highlightAlbumName").getValue(String.class);
                            if (n != null && !n.isEmpty()) albumName = n;
                        }
                        final String fAlbumId = albumId;
                        final String fAlbumName = albumName;

                        LinearLayout row = new LinearLayout(ctx);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));

                        android.graphics.drawable.GradientDrawable rowBg =
                            new android.graphics.drawable.GradientDrawable();
                        rowBg.setCornerRadius(dp(ctx, 10));
                        rowBg.setColor(Color.parseColor("#F5F5F5"));
                        row.setBackground(rowBg);
                        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        rowLp.bottomMargin = dp(ctx, 8);
                        row.setLayoutParams(rowLp);

                        // Album icon
                        TextView icon = new TextView(ctx);
                        icon.setText("⭐");
                        icon.setTextSize(22);
                        icon.setPadding(0, 0, dp(ctx, 12), 0);
                        row.addView(icon);

                        // Album name + count
                        LinearLayout info = new LinearLayout(ctx);
                        info.setOrientation(LinearLayout.VERTICAL);
                        info.setLayoutParams(new LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                        TextView tvName = new TextView(ctx);
                        tvName.setText(fAlbumName);
                        tvName.setTextSize(15);
                        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
                        TextView tvCount = new TextView(ctx);
                        tvCount.setText(count + " item" + (count != 1 ? "s" : ""));
                        tvCount.setTextSize(12);
                        tvCount.setTextColor(Color.GRAY);
                        info.addView(tvName);
                        info.addView(tvCount);
                        row.addView(info);

                        // Add button
                        TextView addToExisting = new TextView(ctx);
                        addToExisting.setText("+ Add");
                        addToExisting.setTextSize(13);
                        addToExisting.setTextColor(Color.parseColor("#6200EE"));
                        addToExisting.setTypeface(null, android.graphics.Typeface.BOLD);
                        row.addView(addToExisting);

                        row.setOnClickListener(v -> {
                            StatusHighlightManager.addToHighlight(
                                    ownerUid, item, fAlbumId, fAlbumName);
                            if (listener != null) listener.onAdded(fAlbumName);
                            sheet.dismiss();
                        });

                        albumList.addView(row);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {
                    albumList.removeAllViews();
                    TextView err = new TextView(ctx);
                    err.setText("Could not load albums");
                    err.setTextColor(Color.RED);
                    albumList.addView(err);
                }
            });
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
