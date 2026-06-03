package com.callx.app.chat.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.callx.app.chat.R;

/**
 * EmojiPickerSheet — Full emoji picker bottom sheet for message reactions.
 *
 * Usage:
 *   EmojiPickerSheet.show(activity, message, (msg, emoji) -> reactToMessage(msg, emoji));
 */
public class EmojiPickerSheet extends BottomSheetDialogFragment {

    public interface OnEmojiSelected {
        void onEmojiSelected(String emoji);
    }

    private static final String[] ALL_EMOJIS = {
        "❤️","👍","😂","😮","😢","🙏","🔥","👏","🎉","😍",
        "😎","🤔","😅","🥰","😭","😡","👎","🤣","😊","😘",
        "💯","🙌","✨","🤝","💪","👀","🤩","😏","😒","🤗",
        "💔","🥺","😤","🤯","🥳","😴","🤭","🙄","😬","🫶",
        "💀","👻","🤡","🌹","⚡","🌊","🎯","💥","🎸","🍕",
        "☕","🏆","🎭","🌈","⭐","💎","🔑","🎁","🛡️","⚔️"
    };

    private OnEmojiSelected listener;

    public static EmojiPickerSheet newInstance(OnEmojiSelected listener) {
        EmojiPickerSheet sheet = new EmojiPickerSheet();
        sheet.listener = listener;
        return sheet;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context ctx = requireContext();

        // Build grid programmatically to avoid extra layout file dependency
        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 20));
        root.setBackgroundColor(0xFFFFFFFF);

        // Title
        TextView title = new TextView(ctx);
        title.setText("Reaction choose karo");
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(ctx, 4), dp(ctx, 4), 0, dp(ctx, 12));
        root.addView(title);

        // Grid
        GridView grid = new GridView(ctx);
        grid.setNumColumns(8);
        grid.setHorizontalSpacing(dp(ctx, 2));
        grid.setVerticalSpacing(dp(ctx, 2));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return ALL_EMOJIS.length; }
            @Override public Object getItem(int pos) { return ALL_EMOJIS[pos]; }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convertView, ViewGroup parent) {
                TextView tv = (convertView instanceof TextView)
                        ? (TextView) convertView : new TextView(ctx);
                tv.setText(ALL_EMOJIS[pos]);
                tv.setTextSize(28);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
                tv.setBackgroundResource(android.R.drawable.list_selector_background);
                tv.setOnClickListener(v -> {
                    if (listener != null) listener.onEmojiSelected(ALL_EMOJIS[pos]);
                    dismiss();
                });
                return tv;
            }
        });
        int gridH = dp(ctx, 56) * ((ALL_EMOJIS.length + 7) / 8);
        root.addView(grid, new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, gridH));

        return root;
    }

    private static int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }
}
