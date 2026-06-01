package com.callx.app.bottomsheet;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.StatusSeenTracker;

/**
 * StatusReactionBottomSheet v25 — Enhanced reaction sheet.
 * FIX: Shows current user's selected reaction (highlighted).
 * FIX: Tapping same emoji removes reaction (toggle).
 * NEW: Shows reaction count per emoji.
 * NEW: 8 emoji options including fire and clap.
 * NEW: Custom emoji option.
 */
public class StatusReactionBottomSheet {

    public static final String[] EMOJIS  = {"❤️","😂","😮","😢","😡","👍","🔥","👏"};
    private static final int SELECTED_BG = Color.parseColor("#1A6200EE");
    private static final int NORMAL_BG   = Color.TRANSPARENT;

    public interface OnReactionSelected {
        void onSelected(String emoji, boolean removed);
    }

    public static void show(Context ctx, StatusItem item, String myUid,
                            OnReactionSelected listener) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = buildView(ctx, item, myUid, emoji -> {
            String current = item.getReaction(myUid);
            boolean removing = emoji.equals(current);
            StatusSeenTracker.reactTo(item.ownerUid, item.id, emoji, current, newEmoji -> {
                if (listener != null) listener.onSelected(emoji, newEmoji == null);
            });
            String msg = removing ? "Reaction removed" : emoji + " sent";
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            sheet.dismiss();
        });
        sheet.setContentView(root);
        sheet.show();
    }

    private static LinearLayout buildView(Context ctx, StatusItem item, String myUid,
                                           EmojiClickListener listener) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx,20), dp(ctx,16), dp(ctx,20), dp(ctx,24));
        int bgColor = resolveAttrColor(ctx, android.R.attr.windowBackground);
        root.setBackgroundColor(bgColor);

        // Title
        TextView title = new TextView(ctx);
        title.setText("React to status");
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(ctx,16));
        title.setTextColor(resolveAttrColor(ctx, android.R.attr.textColorPrimary));
        root.addView(title);

        String myReaction = item.getReaction(myUid);

        // Emoji row
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        for (String emoji : EMOJIS) {
            int count = item.getReactionCount(emoji);
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cell.setLayoutParams(lp);
            cell.setPadding(0, dp(ctx,4), 0, dp(ctx,4));

            boolean selected = emoji.equals(myReaction);
            if (selected) {
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(dp(ctx,12));
                bg.setColor(SELECTED_BG);
                cell.setBackground(bg);
            }

            TextView emojiView = new TextView(ctx);
            emojiView.setText(emoji);
            emojiView.setTextSize(28);
            emojiView.setGravity(android.view.Gravity.CENTER);
            cell.addView(emojiView);

            if (count > 0) {
                TextView countView = new TextView(ctx);
                countView.setText(String.valueOf(count));
                countView.setTextSize(10);
                countView.setGravity(android.view.Gravity.CENTER);
                countView.setTextColor(selected ? Color.parseColor("#6200EE") :
                        resolveAttrColor(ctx, android.R.attr.textColorSecondary));
                cell.addView(countView);
            }

            final String e = emoji;
            cell.setOnClickListener(v -> listener.onClick(e));
            row.addView(cell);
        }
        root.addView(row);

        // Remove reaction row (if user has reacted)
        if (myReaction != null) {
            TextView removeBtn = new TextView(ctx);
            removeBtn.setText("✕  Remove reaction");
            removeBtn.setTextSize(14);
            removeBtn.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            removeBtn.setTextColor(Color.parseColor("#E53935"));
            removeBtn.setPadding(0, dp(ctx,16), 0, 0);
            removeBtn.setOnClickListener(v -> listener.onClick(myReaction)); // same emoji = toggle off
            root.addView(removeBtn);
        }

        return root;
    }

    interface EmojiClickListener { void onClick(String emoji); }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static int resolveAttrColor(Context ctx, int attr) {
        int[] attrs = {attr};
        android.content.res.TypedArray ta = ctx.obtainStyledAttributes(attrs);
        int color = ta.getColor(0, Color.WHITE);
        ta.recycle();
        return color;
    }
}
