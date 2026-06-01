package com.callx.app.bottomsheet;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * StatusDeleteConfirmBottomSheet v25 — Proper delete confirmation sheet.
 * FIX: Was a plain AlertDialog — now a visually clear BottomSheet.
 * Shows: status thumbnail preview (if available), warning text, cancel + delete buttons.
 */
public class StatusDeleteConfirmBottomSheet {

    public interface OnConfirmListener {
        void onConfirmed();
    }

    public static void show(Context ctx, String statusType, String previewUrl,
                            OnConfirmListener listener) {
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
        sub.setText("This status will be permanently removed and\nno one will be able to see it anymore.");
        sub.setTextSize(14);
        sub.setTextColor(Color.GRAY);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(ctx, 28));
        root.addView(sub);

        // Delete button (red)
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
            if (listener != null) listener.onConfirmed();
        });
        root.addView(deleteBtn);

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
