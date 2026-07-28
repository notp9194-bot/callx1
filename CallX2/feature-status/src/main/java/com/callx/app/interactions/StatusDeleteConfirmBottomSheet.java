package com.callx.app.interactions;
import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
/**
 * StatusDeleteConfirmBottomSheet v39 — Delete confirmation sheet.
 *
 * v25: Was a plain AlertDialog — became a visually clear BottomSheet.
 * v39: When the status/story is inside one or more Highlight albums, this now
 *      shows TWO distinct choices instead of one, Instagram-style:
 *        1) "Delete Status Only"                — removes the live status/story,
 *           the Highlight album copy is left untouched (stays permanent).
 *        2) "Delete & Remove from Highlights"    — removes the live status AND
 *           removes it from every Highlight album it belongs to.
 *      If the status isn't in any Highlight, the sheet falls back to the
 *      original single "Delete Status" button.
 */
public class StatusDeleteConfirmBottomSheet {
    public interface OnConfirmListener {
        /** @param alsoRemoveFromHighlights true if the user chose to also strip
         *                                  this status out of its Highlight album(s). */
        void onConfirmed(boolean alsoRemoveFromHighlights);
    }
    public static void show(Context ctx, String statusType, String previewUrl,
                            boolean isInHighlights, OnConfirmListener listener) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 24), dp(ctx, 20), dp(ctx, 32));
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        // Warning icon
        TextView warnIcon = new TextView(ctx);
        warnIcon.setText("🗑️");
        warnIcon.setTextSize(40);
        warnIcon.setGravity(android.view.Gravity.CENTER);
        warnIcon.setPadding(0, 0, 0, dp(ctx, 12));
        root.addView(warnIcon);
        // Title
        TextView title = new TextView(ctx);
        title.setText("Delete this status?");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(ctx, 8));
        root.addView(title);
        // Subtitle
        TextView sub = new TextView(ctx);
        sub.setText(isInHighlights
                ? "This status is saved in your Highlights.\nChoose what you'd like to delete."
                : "This status will be permanently removed and\nno one will be able to see it anymore.");
        sub.setTextSize(14);
        sub.setTextColor(Color.GRAY);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(ctx, 28));
        root.addView(sub);

        if (isInHighlights) {
            // Option 1: Delete status only — Highlight copy stays permanent.
            Button deleteOnlyBtn = new Button(ctx);
            deleteOnlyBtn.setText("Delete Status Only");
            deleteOnlyBtn.setTextColor(Color.WHITE);
            deleteOnlyBtn.setTextSize(15);
            android.graphics.drawable.GradientDrawable onlyBg =
                new android.graphics.drawable.GradientDrawable();
            onlyBg.setColor(Color.parseColor("#E53935"));
            onlyBg.setCornerRadius(dp(ctx, 12));
            deleteOnlyBtn.setBackground(onlyBg);
            LinearLayout.LayoutParams onlyLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 52));
            onlyLp.bottomMargin = dp(ctx, 10);
            deleteOnlyBtn.setLayoutParams(onlyLp);
            deleteOnlyBtn.setOnClickListener(v -> {
                sheet.dismiss();
                if (listener != null) listener.onConfirmed(false);
            });
            root.addView(deleteOnlyBtn);

            // Option 2: Delete + remove from Highlights too.
            Button deleteAllBtn = new Button(ctx);
            deleteAllBtn.setText("Delete & Remove from Highlights");
            deleteAllBtn.setTextColor(Color.WHITE);
            deleteAllBtn.setTextSize(14);
            android.graphics.drawable.GradientDrawable allBg =
                new android.graphics.drawable.GradientDrawable();
            allBg.setColor(Color.parseColor("#B71C1C"));
            allBg.setCornerRadius(dp(ctx, 12));
            deleteAllBtn.setBackground(allBg);
            LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 52));
            allLp.bottomMargin = dp(ctx, 10);
            deleteAllBtn.setLayoutParams(allLp);
            deleteAllBtn.setOnClickListener(v -> {
                sheet.dismiss();
                if (listener != null) listener.onConfirmed(true);
            });
            root.addView(deleteAllBtn);
        } else {
            // Single option — status was never added to any Highlight.
            Button deleteBtn = new Button(ctx);
            deleteBtn.setText("Delete Status");
            deleteBtn.setTextColor(Color.WHITE);
            deleteBtn.setTextSize(15);
            android.graphics.drawable.GradientDrawable delBg =
                new android.graphics.drawable.GradientDrawable();
            delBg.setColor(Color.parseColor("#E53935"));
            delBg.setCornerRadius(dp(ctx, 12));
            deleteBtn.setBackground(delBg);
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 52));
            delLp.bottomMargin = dp(ctx, 10);
            deleteBtn.setLayoutParams(delLp);
            deleteBtn.setOnClickListener(v -> {
                sheet.dismiss();
                if (listener != null) listener.onConfirmed(false);
            });
            root.addView(deleteBtn);
        }

        // Cancel button (outlined)
        Button cancelBtn = new Button(ctx);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextSize(15);
        cancelBtn.setTextColor(Color.parseColor("#6200EE"));
        android.graphics.drawable.GradientDrawable cancelBg =
            new android.graphics.drawable.GradientDrawable();
        cancelBg.setColor(Color.TRANSPARENT);
        cancelBg.setStroke(dp(ctx, 1), Color.parseColor("#6200EE"));
        cancelBg.setCornerRadius(dp(ctx, 12));
        cancelBtn.setBackground(cancelBg);
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 52)));
        cancelBtn.setOnClickListener(v -> sheet.dismiss());
        root.addView(cancelBtn);
        sheet.setContentView(root);
        sheet.show();
    }
    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
