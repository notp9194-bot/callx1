package com.callx.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.reels.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * ReelMoreBottomSheet
 * Production Instagram/TikTok-style bottom sheet for the 3-dot menu in Reel Player.
 *
 * Duet system additions:
 *  ✅ ACTION_VIEW_DUETS — "View Duets" option (shows duet count badge)
 *  ✅ duetCount badge on the "Duet" action item
 *  ✅ "Remix Settings" for owners to control duet/stitch permission
 */
public class ReelMoreBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelMoreBottomSheet";

    // Argument keys
    private static final String ARG_IS_OWNER    = "is_owner";
    private static final String ARG_IS_SAVED    = "is_saved";
    private static final String ARG_SPEED_LABEL = "speed_label";
    private static final String ARG_ALLOW_DUET   = "allow_duet";
    private static final String ARG_ALLOW_STITCH = "allow_stitch";
    private static final String ARG_DUET_COUNT   = "duet_count";

    // Callback
    public interface OnItemClickListener {
        void onMoreItemClick(String action);
    }

    // ─── Action constants ────────────────────────────────────────────────────
    public static final String ACTION_SAVE                 = "save";
    public static final String ACTION_BOOKMARK_COLLECTIONS = "bookmark_collections";
    public static final String ACTION_SPEED               = "speed";
    public static final String ACTION_DOWNLOAD            = "download";
    public static final String ACTION_DUET                = "duet";
    public static final String ACTION_VIEW_DUETS          = "view_duets";    // ✅ NEW
    public static final String ACTION_STITCH              = "stitch";
    public static final String ACTION_VIDEO_REPLY         = "video_reply";
    public static final String ACTION_SHARE_TO_STORY      = "share_to_story";
    public static final String ACTION_COLLAB_REQUEST      = "collab_request";
    public static final String ACTION_NOT_INTERESTED      = "not_interested";
    public static final String ACTION_COPY_LINK           = "copy_link";
    public static final String ACTION_REPORT              = "report";
    // Owner-only
    public static final String ACTION_EDIT                = "edit";
    public static final String ACTION_ANALYTICS           = "analytics";
    public static final String ACTION_PINNED_COMMENTS     = "pinned_comments";
    public static final String ACTION_QR_CODE             = "qr_code";
    public static final String ACTION_REMIX_SETTINGS      = "remix_settings"; // ✅ NEW
    public static final String ACTION_DELETE              = "delete";

    // ─── Item model ──────────────────────────────────────────────────────────
    private static class MenuItem {
        String  action;
        String  label;
        int     iconRes;
        int     textColor;
        boolean isDividerAfter;
        String  badge; // optional count badge (e.g. "12")

        MenuItem(String action, String label, int iconRes) {
            this(action, label, iconRes, 0, false, null);
        }
        MenuItem(String action, String label, int iconRes, int textColor, boolean dividerAfter) {
            this(action, label, iconRes, textColor, dividerAfter, null);
        }
        MenuItem(String action, String label, int iconRes, int textColor,
                 boolean dividerAfter, String badge) {
            this.action         = action;
            this.label          = label;
            this.iconRes        = iconRes;
            this.textColor      = textColor;
            this.isDividerAfter = dividerAfter;
            this.badge          = badge;
        }
    }

    // Colors
    private static final int CLR_PINK   = 0xFFFF416C;
    private static final int CLR_CYAN   = 0xFF00C6FF;
    private static final int CLR_YELLOW = 0xFFFFD700;
    private static final int CLR_GREEN  = 0xFF00F260;
    private static final int CLR_PURPLE = 0xFFA855F7;
    private static final int CLR_ORANGE = 0xFFFF9500;
    private static final int CLR_TEAL   = 0xFF00E5FF;
    private static final int CLR_RED    = 0xFFFF4444;
    private static final int CLR_GOLD   = 0xFFFFE082;

    private OnItemClickListener listener;
    private boolean isOwner;
    private boolean isSaved;
    private String  speedLabel;
    private boolean allowDuet   = true;
    private boolean allowStitch = true;
    private int     duetCount   = 0;

    // ─── Factory ─────────────────────────────────────────────────────────────
    public static ReelMoreBottomSheet newInstance(boolean isOwner, boolean isSaved,
                                                  String speedLabel,
                                                  boolean allowDuet, boolean allowStitch) {
        return newInstance(isOwner, isSaved, speedLabel, allowDuet, allowStitch, 0);
    }

    public static ReelMoreBottomSheet newInstance(boolean isOwner, boolean isSaved,
                                                  String speedLabel,
                                                  boolean allowDuet, boolean allowStitch,
                                                  int duetCount) {
        ReelMoreBottomSheet sheet = new ReelMoreBottomSheet();
        Bundle args = new Bundle();
        args.putBoolean(ARG_IS_OWNER,    isOwner);
        args.putBoolean(ARG_IS_SAVED,    isSaved);
        args.putString (ARG_SPEED_LABEL, speedLabel);
        args.putBoolean(ARG_ALLOW_DUET,   allowDuet);
        args.putBoolean(ARG_ALLOW_STITCH, allowStitch);
        args.putInt    (ARG_DUET_COUNT,   duetCount);
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof OnItemClickListener) {
            listener = (OnItemClickListener) getParentFragment();
        } else if (context instanceof OnItemClickListener) {
            listener = (OnItemClickListener) context;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ReelMoreBottomSheetTheme);
        if (getArguments() != null) {
            isOwner     = getArguments().getBoolean(ARG_IS_OWNER,    false);
            isSaved     = getArguments().getBoolean(ARG_IS_SAVED,    false);
            speedLabel  = getArguments().getString (ARG_SPEED_LABEL, "Speed: 1x");
            allowDuet   = getArguments().getBoolean(ARG_ALLOW_DUET,   true);
            allowStitch = getArguments().getBoolean(ARG_ALLOW_STITCH, true);
            duetCount   = getArguments().getInt    (ARG_DUET_COUNT,   0);
        }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reel_more, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            d.getBehavior().setSkipCollapsed(true);
        }
        LinearLayout container = view.findViewById(R.id.ll_more_items);
        buildMenuItems(container);
    }

    // ─── Build menu ──────────────────────────────────────────────────────────
    private void buildMenuItems(LinearLayout container) {
        List<MenuItem> items = isOwner ? buildOwnerItems() : buildViewerItems();
        Context ctx = requireContext();

        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dpToPx(56));
            row.setPadding(dpToPx(20), dpToPx(4), dpToPx(20), dpToPx(4));
            row.setBackground(getResources().getDrawable(R.drawable.bg_more_item_ripple, null));
            row.setClickable(true);
            row.setFocusable(true);

            // Icon
            ImageView icon = new ImageView(ctx);
            LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dpToPx(26), dpToPx(26));
            iconParams.setMarginEnd(dpToPx(18));
            icon.setLayoutParams(iconParams);
            icon.setImageResource(item.iconRes);
            int iconColor = item.textColor != 0 ? item.textColor : Color.WHITE;
            icon.setColorFilter(iconColor);

            // Label
            TextView label = new TextView(ctx);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            label.setText(item.label);
            label.setTextSize(15f);
            label.setTextColor(item.textColor != 0 ? item.textColor : Color.WHITE);

            row.addView(icon);
            row.addView(label);

            // Badge (duet count, etc.)
            if (item.badge != null && !item.badge.isEmpty()) {
                TextView badge = new TextView(ctx);
                LinearLayout.LayoutParams bp =
                    new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                badge.setLayoutParams(bp);
                badge.setText(item.badge);
                badge.setTextColor(0xFFA855F7);
                badge.setTextSize(12f);
                badge.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
                badge.setBackgroundColor(0x22A855F7);
                row.addView(badge);
            }

            container.addView(row);

            // Divider
            if (item.isDividerAfter && i < items.size() - 1) {
                View divider = new View(ctx);
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                dp.setMargins(dpToPx(20), dpToPx(4), dpToPx(20), dpToPx(4));
                divider.setLayoutParams(dp);
                divider.setBackgroundColor(Color.parseColor("#22FFFFFF"));
                container.addView(divider);
            }

            final String action = item.action;
            row.setOnClickListener(v -> {
                dismiss();
                if (listener != null) listener.onMoreItemClick(action);
            });
        }
    }

    // ─── Viewer items ─────────────────────────────────────────────────────────
    private List<MenuItem> buildViewerItems() {
        String saveLabel = isSaved ? "Unsave" : "Save";
        int    saveIcon  = isSaved ? R.drawable.ic_close : R.drawable.ic_bookmark;
        String duetBadge = duetCount > 0 ? formatCount(duetCount) : null;

        List<MenuItem> list = new ArrayList<>();
        list.add(new MenuItem(ACTION_SAVE,                 saveLabel,              saveIcon,                CLR_PINK,   false));
        list.add(new MenuItem(ACTION_BOOKMARK_COLLECTIONS, "Bookmark Collections", R.drawable.ic_bookmark,  CLR_CYAN,   true));
        list.add(new MenuItem(ACTION_SPEED,                speedLabel,             R.drawable.ic_speed,     CLR_YELLOW, false));
        list.add(new MenuItem(ACTION_DOWNLOAD,             "Download",             R.drawable.ic_download_reel, CLR_GREEN, true));
        if (allowDuet) {
            list.add(new MenuItem(ACTION_DUET,     "Duet",        R.drawable.ic_video_call, CLR_PURPLE, false, null));
            if (duetCount > 0)
                list.add(new MenuItem(ACTION_VIEW_DUETS, "View Duets", R.drawable.ic_reels,
                                      CLR_PURPLE, false, duetBadge));
        }
        if (allowStitch)
            list.add(new MenuItem(ACTION_STITCH,       "Stitch",      R.drawable.ic_swap,       CLR_PURPLE, false));
        list.add(new MenuItem(ACTION_VIDEO_REPLY,      "Video Reply",  R.drawable.ic_reply,      CLR_PURPLE, false));
        list.add(new MenuItem(ACTION_SHARE_TO_STORY,   "Share to Story",R.drawable.ic_share_reel, CLR_GREEN, true));
        list.add(new MenuItem(ACTION_COLLAB_REQUEST,   "Collab Request",R.drawable.ic_group,     CLR_TEAL,   true));
        list.add(new MenuItem(ACTION_NOT_INTERESTED,   "Not Interested",R.drawable.ic_eye_off,   CLR_GOLD,   false));
        list.add(new MenuItem(ACTION_COPY_LINK,        "Copy Link",    R.drawable.ic_link,       CLR_CYAN,   true));
        list.add(new MenuItem(ACTION_REPORT,           "Report",       R.drawable.ic_flag,       CLR_RED,    false));
        return list;
    }

    // ─── Owner items ──────────────────────────────────────────────────────────
    private List<MenuItem> buildOwnerItems() {
        String saveLabel = isSaved ? "Unsave" : "Save";
        int    saveIcon  = isSaved ? R.drawable.ic_close : R.drawable.ic_bookmark;
        String duetBadge = duetCount > 0 ? formatCount(duetCount) : null;

        List<MenuItem> list = new ArrayList<>();
        list.add(new MenuItem(ACTION_SAVE,                 saveLabel,              saveIcon,               CLR_PINK,   false));
        list.add(new MenuItem(ACTION_BOOKMARK_COLLECTIONS, "Bookmark Collections", R.drawable.ic_bookmark,  CLR_CYAN,   true));
        list.add(new MenuItem(ACTION_SPEED,                speedLabel,             R.drawable.ic_speed,     CLR_YELLOW, false));
        list.add(new MenuItem(ACTION_DOWNLOAD,             "Download",             R.drawable.ic_download_reel, CLR_GREEN, true));
        list.add(new MenuItem(ACTION_EDIT,                 "Edit Reel",            R.drawable.ic_edit,      CLR_ORANGE, false));
        list.add(new MenuItem(ACTION_ANALYTICS,            "Analytics",            R.drawable.ic_reel_explore, CLR_TEAL, false));
        list.add(new MenuItem(ACTION_PINNED_COMMENTS,      "Pinned Comments",      R.drawable.ic_pin,       CLR_TEAL,   false));
        list.add(new MenuItem(ACTION_REMIX_SETTINGS,       "Remix Settings",       R.drawable.ic_settings,  CLR_TEAL,   true)); // ✅ NEW
        if (duetCount > 0)
            list.add(new MenuItem(ACTION_VIEW_DUETS,       "View Duets",           R.drawable.ic_reels,     CLR_PURPLE, false, duetBadge)); // ✅ NEW
        if (allowDuet)
            list.add(new MenuItem(ACTION_DUET,             "Duet",                 R.drawable.ic_video_call, CLR_PURPLE, false));
        if (allowStitch)
            list.add(new MenuItem(ACTION_STITCH,           "Stitch",               R.drawable.ic_swap,      CLR_PURPLE, false));
        list.add(new MenuItem(ACTION_SHARE_TO_STORY,       "Share to Story",       R.drawable.ic_share_reel, CLR_GREEN, true));
        list.add(new MenuItem(ACTION_QR_CODE,              "QR Code",              R.drawable.ic_qr_code,   CLR_ORANGE, false));
        list.add(new MenuItem(ACTION_COLLAB_REQUEST,       "Collab Request",       R.drawable.ic_group,     CLR_TEAL,   true));
        list.add(new MenuItem(ACTION_COPY_LINK,            "Copy Link",            R.drawable.ic_link,      CLR_CYAN,   false));
        list.add(new MenuItem(ACTION_DELETE,               "Delete",               R.drawable.ic_delete,    CLR_RED,    false));
        return list;
    }

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * requireContext().getResources().getDisplayMetrics().density);
    }
}
