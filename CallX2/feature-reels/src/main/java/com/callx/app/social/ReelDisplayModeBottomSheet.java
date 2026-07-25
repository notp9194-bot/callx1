package com.callx.app.social;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.reels.R;
import com.callx.app.utils.ReelDisplayModePrefs;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Reels Display Mode chooser.
 *
 * Two flows use this same sheet:
 *  1) First-visit flow — shown automatically the very first time the user
 *     opens the Reels tab (MainActivity calls newInstance(..., firstTime=true)).
 *     Not cancelable — the user must pick one so Reels always has a defined mode.
 *  2) 3-dot menu flow — reopened anytime afterwards from the Reels "Display Mode"
 *     menu item so the user can change their mind. Cancelable, and the
 *     currently active mode is highlighted with a check mark.
 */
public class ReelDisplayModeBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelDisplayModeBottomSheet";

    private static final String ARG_CURRENT_MODE = "current_mode";
    private static final String ARG_FIRST_TIME   = "first_time";

    public interface OnModeSelectedListener {
        void onModeSelected(String mode);
    }

    private OnModeSelectedListener listener;
    private String currentMode;
    private boolean firstTime;

    public static ReelDisplayModeBottomSheet newInstance(String currentMode, boolean firstTime) {
        ReelDisplayModeBottomSheet sheet = new ReelDisplayModeBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CURRENT_MODE, currentMode != null ? currentMode : ReelDisplayModePrefs.MODE_IMMERSIVE);
        args.putBoolean(ARG_FIRST_TIME, firstTime);
        sheet.setArguments(args);
        return sheet;
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
        setStyle(STYLE_NORMAL, R.style.ReelMoreBottomSheetTheme);
        if (getArguments() != null) {
            currentMode = getArguments().getString(ARG_CURRENT_MODE, ReelDisplayModePrefs.MODE_IMMERSIVE);
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
        return inflater.inflate(R.layout.bottom_sheet_reel_display_mode, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            d.getBehavior().setSkipCollapsed(true);
            d.setCancelable(!firstTime);
            d.setCanceledOnTouchOutside(!firstTime);
        }

        // No drag handle on the first-visit flow — nudges the user to tap an option
        // rather than swipe the sheet away.
        View dragHandle = view.findViewById(R.id.fl_display_mode_drag_handle);
        if (dragHandle != null) dragHandle.setVisibility(firstTime ? View.INVISIBLE : View.VISIBLE);

        LinearLayout container = view.findViewById(R.id.ll_display_mode_options);
        addOption(container,
            ReelDisplayModePrefs.MODE_IMMERSIVE,
            R.drawable.ic_display_mode_immersive,
            "Immersive (Full Screen)",
            "Status bar and bottom navigation stay hidden — same as now");
        addOption(container,
            ReelDisplayModePrefs.MODE_NORMAL,
            R.drawable.ic_display_mode_normal,
            "Normal",
            "Status bar and bottom navigation stay visible in Reels");
    }

    private void addOption(LinearLayout container, String mode, int iconRes,
                            String title, String subtitle) {
        Context ctx = requireContext();
        boolean selected = mode.equals(currentMode);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dpToPx(72));
        row.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        row.setBackground(getResources().getDrawable(
            selected ? R.drawable.bg_display_mode_option_selected : R.drawable.bg_display_mode_option, null));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dpToPx(12);
        row.setLayoutParams(rowParams);

        // Icon badge
        FrameLayout badge = new FrameLayout(ctx);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44));
        badgeParams.setMarginEnd(dpToPx(14));
        badge.setLayoutParams(badgeParams);
        badge.setBackground(getResources().getDrawable(R.drawable.bg_display_mode_icon_badge, null));

        ImageView icon = new ImageView(ctx);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dpToPx(22), dpToPx(22));
        iconParams.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconParams);
        icon.setImageResource(iconRes);
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
        titleView.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));

        TextView subtitleView = new TextView(ctx);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(12.5f);
        subtitleView.setTextColor(0x99FFFFFF);
        subtitleView.setPadding(0, dpToPx(2), 0, 0);

        textCol.addView(titleView);
        textCol.addView(subtitleView);

        row.addView(badge);
        row.addView(textCol);

        // Selected check mark
        if (selected) {
            ImageView check = new ImageView(ctx);
            LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dpToPx(22), dpToPx(22));
            checkParams.setMarginStart(dpToPx(10));
            check.setLayoutParams(checkParams);
            check.setImageResource(R.drawable.ic_display_mode_check);
            check.setColorFilter(0xFFA855F7);
            row.addView(check);
        }

        row.setOnClickListener(v -> {
            if (listener != null) listener.onModeSelected(mode);
            dismissAllowingStateLoss();
        });

        container.addView(row);
    }

    private int dpToPx(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
