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
 * Premium Instagram/TikTok-style bottom sheet for the 3-dot menu in Reel Player.
 */
public class ReelMoreBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelMoreBottomSheet";

    // Argument keys
    private static final String ARG_IS_OWNER    = "is_owner";
    private static final String ARG_IS_SAVED    = "is_saved";
    private static final String ARG_SPEED_LABEL = "speed_label";

    // Callback interface — caller handles all actions
    public interface OnItemClickListener {
        void onMoreItemClick(String action);
    }

    // ─── Action constants ────────────────────────────────────────────────────
    public static final String ACTION_SAVE                 = "save";
    public static final String ACTION_BOOKMARK_COLLECTIONS = "bookmark_collections";
    public static final String ACTION_SPEED               = "speed";
    public static final String ACTION_DOWNLOAD            = "download";
    public static final String ACTION_DUET                = "duet";
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
    public static final String ACTION_DELETE              = "delete";

    // ─── Item model ──────────────────────────────────────────────────────────
    private static class MenuItem {
        String  action;
        String  label;
        int     iconRes;   // drawable resource id
        int     textColor; // 0 = use default white
        boolean isDividerAfter;

        MenuItem(String action, String label, int iconRes) {
            this(action, label, iconRes, 0, false);
        }
        MenuItem(String action, String label, int iconRes, int textColor, boolean dividerAfter) {
            this.action         = action;
            this.label          = label;
            this.iconRes        = iconRes;
            this.textColor      = textColor;
            this.isDividerAfter = dividerAfter;
        }
    }

    private OnItemClickListener listener;
    private boolean isOwner;
    private boolean isSaved;
    private String  speedLabel;

    // ─── Factory ─────────────────────────────────────────────────────────────
    public static ReelMoreBottomSheet newInstance(boolean isOwner, boolean isSaved, String speedLabel) {
        ReelMoreBottomSheet sheet = new ReelMoreBottomSheet();
        Bundle args = new Bundle();
        args.putBoolean(ARG_IS_OWNER,    isOwner);
        args.putBoolean(ARG_IS_SAVED,    isSaved);
        args.putString (ARG_SPEED_LABEL, speedLabel);
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
            isOwner    = getArguments().getBoolean(ARG_IS_OWNER,    false);
            isSaved    = getArguments().getBoolean(ARG_IS_SAVED,    false);
            speedLabel = getArguments().getString (ARG_SPEED_LABEL, "Speed: 1x");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reel_more, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Expand fully on open
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

            // Row
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
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dpToPx(26), dpToPx(26));
            iconParams.setMarginEnd(dpToPx(18));
            icon.setLayoutParams(iconParams);
            icon.setImageResource(item.iconRes);

            int iconColor = item.textColor != 0 ? item.textColor : Color.WHITE;
            icon.setColorFilter(iconColor);

            // Label
            TextView label = new TextView(ctx);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
            label.setText(item.label);
            label.setTextSize(15f);
            label.setTextColor(item.textColor != 0 ? item.textColor : Color.WHITE);
            label.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));

            row.addView(icon);
            row.addView(label);
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

            // Click
            final String action = item.action;
            row.setOnClickListener(v -> {
                dismiss();
                if (listener != null) listener.onMoreItemClick(action);
            });
        }
    }

    // ─── Viewer menu items ────────────────────────────────────────────────────
    private List<MenuItem> buildViewerItems() {
        String saveLabel = isSaved ? "Unsave" : "Save";
        int    saveIcon  = isSaved
            ? R.drawable.ic_close
            : R.drawable.ic_bookmark;

        List<MenuItem> list = new ArrayList<>();
        list.add(new MenuItem(ACTION_SAVE,                 saveLabel,             saveIcon));
        list.add(new MenuItem(ACTION_BOOKMARK_COLLECTIONS, "Bookmark Collections",R.drawable.ic_bookmark,       0, true));
        list.add(new MenuItem(ACTION_SPEED,                speedLabel,            R.drawable.ic_speed));
        list.add(new MenuItem(ACTION_DOWNLOAD,             "Download",            R.drawable.ic_download_reel,          0, true));
        list.add(new MenuItem(ACTION_DUET,                 "Duet",                R.drawable.ic_video_call));
        list.add(new MenuItem(ACTION_STITCH,               "Stitch",              R.drawable.ic_swap));
        list.add(new MenuItem(ACTION_VIDEO_REPLY,          "Video Reply",         R.drawable.ic_reply));
        list.add(new MenuItem(ACTION_SHARE_TO_STORY,       "Share to Story",      R.drawable.ic_share_reel,       0, true));
        list.add(new MenuItem(ACTION_COLLAB_REQUEST,       "Collab Request",      R.drawable.ic_group,            0, true));
        list.add(new MenuItem(ACTION_NOT_INTERESTED,       "Not Interested",      R.drawable.ic_eye_off));
        list.add(new MenuItem(ACTION_COPY_LINK,            "Copy Link",           R.drawable.ic_link,              0, true));
        list.add(new MenuItem(ACTION_REPORT,               "Report",              R.drawable.ic_flag,
            Color.parseColor("#FF4444"), false));
        return list;
    }

    // ─── Owner menu items ─────────────────────────────────────────────────────
    private List<MenuItem> buildOwnerItems() {
        String saveLabel = isSaved ? "Unsave" : "Save";
        int    saveIcon  = isSaved
            ? R.drawable.ic_close
            : R.drawable.ic_bookmark;

        List<MenuItem> list = new ArrayList<>();
        list.add(new MenuItem(ACTION_SAVE,                 saveLabel,             saveIcon));
        list.add(new MenuItem(ACTION_BOOKMARK_COLLECTIONS, "Bookmark Collections",R.drawable.ic_bookmark,  0, true));
        list.add(new MenuItem(ACTION_SPEED,                speedLabel,            R.drawable.ic_speed));
        list.add(new MenuItem(ACTION_DOWNLOAD,             "Download",            R.drawable.ic_download_reel,     0, true));
        list.add(new MenuItem(ACTION_EDIT,                 "Edit Reel",           R.drawable.ic_edit));
        list.add(new MenuItem(ACTION_ANALYTICS,            "Analytics",           R.drawable.ic_reel_explore));
        list.add(new MenuItem(ACTION_PINNED_COMMENTS,      "Pinned Comments",     R.drawable.ic_pin,          0, true));
        list.add(new MenuItem(ACTION_DUET,                 "Duet",                R.drawable.ic_video_call));
        list.add(new MenuItem(ACTION_STITCH,               "Stitch",              R.drawable.ic_swap));
        list.add(new MenuItem(ACTION_SHARE_TO_STORY,       "Share to Story",      R.drawable.ic_share_reel,  0, true));
        list.add(new MenuItem(ACTION_QR_CODE,              "QR Code",             R.drawable.ic_qr_code));
        list.add(new MenuItem(ACTION_COLLAB_REQUEST,       "Collab Request",      R.drawable.ic_group,       0, true));
        list.add(new MenuItem(ACTION_COPY_LINK,            "Copy Link",           R.drawable.ic_link));
        list.add(new MenuItem(ACTION_DELETE,               "Delete",              R.drawable.ic_delete,
            Color.parseColor("#FF4444"), false));
        return list;
    }

    // ─── Utility ─────────────────────────────────────────────────────────────
    private int dpToPx(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
