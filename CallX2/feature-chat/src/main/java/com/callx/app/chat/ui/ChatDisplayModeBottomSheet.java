package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.ChatDisplayModePrefs;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Chat Display Mode chooser — same idea as Reels' ReelDisplayModeBottomSheet,
 * built for the chat module (which can't depend on :feature-reels).
 *
 * Two flows use this same sheet:
 *  1) First-visit flow — shown automatically the very first time the user
 *     ever opens a chat (ChatActivity/GroupChatActivity call
 *     newInstance(..., firstTime=true)). Not cancelable — user must pick one.
 *  2) 3-dot menu flow — reopened anytime afterwards from the chat's "Display
 *     Mode" menu item so the user can change their mind. Cancelable, and the
 *     currently active mode is highlighted with a check mark.
 */
public class ChatDisplayModeBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChatDisplayModeBottomSheet";

    private static final String ARG_CURRENT_MODE = "current_mode";
    private static final String ARG_FIRST_TIME   = "first_time";

    public interface OnModeSelectedListener {
        void onChatModeSelected(String mode);
    }

    private OnModeSelectedListener listener;
    private String currentMode;
    private boolean firstTime;

    public static ChatDisplayModeBottomSheet newInstance(String currentMode, boolean firstTime) {
        ChatDisplayModeBottomSheet sheet = new ChatDisplayModeBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CURRENT_MODE, currentMode != null ? currentMode : ChatDisplayModePrefs.MODE_IMMERSIVE);
        args.putBoolean(ARG_FIRST_TIME, firstTime);
        sheet.setArguments(args);
        return sheet;
    }

    public void setOnModeSelectedListener(OnModeSelectedListener l) {
        this.listener = l;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof OnModeSelectedListener) {
            listener = (OnModeSelectedListener) getParentFragment();
        } else if (context instanceof OnModeSelectedListener) {
            listener = (OnModeSelectedListener) context;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL,
                com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        if (getArguments() != null) {
            currentMode = getArguments().getString(ARG_CURRENT_MODE, ChatDisplayModePrefs.MODE_IMMERSIVE);
            firstTime   = getArguments().getBoolean(ARG_FIRST_TIME, false);
        }
        // First-visit chooser: force a real choice, no accidental dismiss.
        setCancelable(!firstTime);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                              @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(buildSheetBackground());
        root.setPadding(0, 0, 0, dp(20));

        // Drag handle (hidden on first-visit flow — nudges user to tap, not swipe away)
        FrameLayout handleBar = new FrameLayout(ctx);
        handleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        View handle = new View(ctx);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(40), dp(4));
        hp.gravity = Gravity.CENTER;
        handle.setLayoutParams(hp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x55FFFFFF);
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        handle.setVisibility(firstTime ? View.INVISIBLE : View.VISIBLE);
        handleBar.addView(handle);
        root.addView(handleBar);

        // Header
        TextView header = new TextView(ctx);
        LinearLayout.LayoutParams hp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hp2.setMargins(dp(20), 0, dp(20), dp(4));
        header.setLayoutParams(hp2);
        header.setText("🖥️  Chat Display Mode");
        header.setTextSize(17f);
        header.setTextColor(0xFFFFFFFF);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(header);

        TextView sub = new TextView(ctx);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(dp(20), 0, dp(20), dp(16));
        sub.setLayoutParams(subLp);
        sub.setText("Choose how chat looks when opened");
        sub.setTextSize(12.5f);
        sub.setTextColor(0x99FFFFFF);
        root.addView(sub);

        LinearLayout optionsContainer = new LinearLayout(ctx);
        optionsContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ocLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ocLp.setMargins(dp(12), 0, dp(12), 0);
        optionsContainer.setLayoutParams(ocLp);

        addOption(ctx, optionsContainer,
                ChatDisplayModePrefs.MODE_IMMERSIVE, "\uD83D\uDCF1",
                "Immersive (Full Screen)",
                "Status bar and navigation bar stay hidden — same as now");
        addOption(ctx, optionsContainer,
                ChatDisplayModePrefs.MODE_NORMAL, "\uD83D\uDDA5\uFE0F",
                "Normal",
                "Status bar and navigation bar stay visible in chat");

        root.addView(optionsContainer);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            BottomSheetBehavior<FrameLayout> behavior = d.getBehavior();
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
            d.setCancelable(!firstTime);
            d.setCanceledOnTouchOutside(!firstTime);
        }
    }

    private void addOption(Context ctx, LinearLayout container, String mode, String emoji,
                            String title, String subtitle) {
        boolean selected = mode.equals(currentMode);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(buildOptionBackground(selected));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(12);
        row.setLayoutParams(rowParams);

        // Icon badge
        FrameLayout badge = new FrameLayout(ctx);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        badgeParams.setMarginEnd(dp(14));
        badge.setLayoutParams(badgeParams);
        GradientDrawable badgeBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0EA5E9, 0xFF6366F1});
        badgeBg.setCornerRadius(dp(14));
        badge.setBackground(badgeBg);

        TextView icon = new TextView(ctx);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        iconParams.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconParams);
        icon.setText(emoji);
        icon.setTextSize(20f);
        badge.addView(icon);

        // Text column
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(textColParams);

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextSize(15f);
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        TextView subtitleView = new TextView(ctx);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(12.5f);
        subtitleView.setTextColor(0x99FFFFFF);
        subtitleView.setPadding(0, dp(2), 0, 0);

        textCol.addView(titleView);
        textCol.addView(subtitleView);

        row.addView(badge);
        row.addView(textCol);

        // Selected check mark
        if (selected) {
            TextView check = new TextView(ctx);
            LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            checkParams.setMarginStart(dp(10));
            check.setLayoutParams(checkParams);
            check.setText("✓");
            check.setTextSize(20f);
            check.setTextColor(0xFFA855F7);
            check.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            row.addView(check);
        }

        row.setOnClickListener(v -> {
            if (listener != null) listener.onChatModeSelected(mode);
            dismissAllowingStateLoss();
        });

        container.addView(row);
    }

    private GradientDrawable buildOptionBackground(boolean selected) {
        GradientDrawable bg;
        if (selected) {
            bg = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x33A855F7, 0x1AA855F7});
            bg.setStroke(dp(1), 0x66A855F7);
        } else {
            bg = new GradientDrawable();
            bg.setColor(0x14FFFFFF);
        }
        bg.setCornerRadius(dp(14));
        return bg;
    }

    private GradientDrawable buildSheetBackground() {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1A2E, 0xFF0F0F1A});
        gd.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        return gd;
    }

    private int dp(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
