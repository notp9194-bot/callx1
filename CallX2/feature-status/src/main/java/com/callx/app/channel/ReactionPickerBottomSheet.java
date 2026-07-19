package com.callx.app.channel;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.status.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ReactionPickerBottomSheet — WhatsApp-style emoji reaction picker for channel posts.
 *
 * Shows the 6 primary reactions + "more emojis" option.
 * If the user has already reacted with the same emoji, tapping it removes the reaction.
 * Callback: OnEmojiSelected(emoji, postId) — emoji is null to remove reaction.
 */
public class ReactionPickerBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReactionPicker";

    private static final String ARG_POST_ID     = "postId";
    private static final String ARG_MY_REACTION = "myReaction";

    // Standard WhatsApp reactions
    private static final String[] REACTIONS = {
        "\uD83D\uDC4D",  // 👍
        "❤️",
        "\uD83D\uDE02",  // 😂
        "\uD83D\uDE2E",  // 😮
        "\uD83D\uDE22",  // 😢
        "\uD83D\uDE4F"   // 🙏
    };

    public interface OnEmojiSelected {
        void onEmojiSelected(@Nullable String emoji, String postId);
    }

    private OnEmojiSelected callback;

    public static ReactionPickerBottomSheet newInstance(String postId, @Nullable String myReaction) {
        ReactionPickerBottomSheet sheet = new ReactionPickerBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_POST_ID, postId);
        args.putString(ARG_MY_REACTION, myReaction);
        sheet.setArguments(args);
        return sheet;
    }

    public void setOnEmojiSelected(OnEmojiSelected cb) { this.callback = cb; }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reaction_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String postId     = getArguments() != null ? getArguments().getString(ARG_POST_ID) : "";
        String myReaction = getArguments() != null ? getArguments().getString(ARG_MY_REACTION) : null;

        LinearLayout container = view.findViewById(R.id.layout_reactions_row);
        if (container == null) return;

        for (String emoji : REACTIONS) {
            TextView tv = new TextView(requireContext());
            tv.setText(emoji);
            tv.setTextSize(30f);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(16, 12, 16, 12);

            boolean isSelected = emoji.equals(myReaction);
            if (isSelected) {
                tv.setBackgroundResource(R.drawable.bg_reaction_selected);
                tv.setScaleX(1.2f);
                tv.setScaleY(1.2f);
            }

            tv.setOnClickListener(v -> {
                if (callback != null) {
                    // Tapping own reaction → remove it
                    callback.onEmojiSelected(isSelected ? null : emoji, postId);
                }
                dismiss();
            });

            // Animate on hover
            tv.setOnHoverListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                    v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).start();
                } else if (event.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                }
                return false;
            });

            container.addView(tv);
        }

        // "More emojis" option
        TextView tvMore = view.findViewById(R.id.tv_more_emojis);
        if (tvMore != null) {
            tvMore.setOnClickListener(v -> {
                // Show an extended emoji set via AlertDialog
                showMoreEmojis(postId, myReaction);
            });
        }

        // Remove reaction option (only if already reacted)
        TextView tvRemove = view.findViewById(R.id.tv_remove_reaction);
        if (tvRemove != null) {
            tvRemove.setVisibility(myReaction != null ? View.VISIBLE : View.GONE);
            tvRemove.setOnClickListener(v -> {
                if (callback != null) callback.onEmojiSelected(null, postId);
                dismiss();
            });
        }
    }

    private void showMoreEmojis(String postId, String myReaction) {
        String[] extraEmojis = {
            "🔥","👏","🎉","💯","😍","🤔","😅","🤣","😎","💪",
            "🙌","👀","🤝","💡","✅","❌","💔","🎊","🚀","⭐"
        };
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle("React with emoji")
            .setItems(extraEmojis, (d, which) -> {
                if (callback != null) callback.onEmojiSelected(extraEmojis[which], postId);
                dismiss();
            })
            .show();
    }
}
