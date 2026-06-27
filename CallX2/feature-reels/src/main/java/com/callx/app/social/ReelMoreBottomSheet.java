package com.callx.app.social;

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
 *
 * Duet / Stitch permission levels:
 *   "everyone"  → show option normally, anyone can tap
 *   "followers" + isFollowing=true  → show option normally
 *   "followers" + isFollowing=false → show option grayed-out + lock label, non-tappable
 *   "off"       → hide option entirely
 */
public class ReelMoreBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelMoreBottomSheet";

    // Argument keys
    private static final String ARG_IS_OWNER     = "is_owner";
    private static final String ARG_IS_SAVED     = "is_saved";
    private static final String ARG_SPEED_LABEL  = "speed_label";
    private static final String ARG_DUET_LEVEL   = "duet_level";   // "everyone"|"followers"|"off"
    private static final String ARG_STITCH_LEVEL = "stitch_level"; // "everyone"|"followers"|"off"
    private static final String ARG_IS_FOLLOWING = "is_following";
    private static final String ARG_SERIES_ID   = "series_id";

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
    /** Collab Repost — opens CollabRepostActivity for joint repost with a collaborator */
    public static final String ACTION_COLLAB_REPOST       = "collab_repost";
    public static final String ACTION_NOT_INTERESTED      = "not_interested";
    public static final String ACTION_COPY_LINK           = "copy_link";
    public static final String ACTION_REPORT              = "report";
    public static final String ACTION_BLOCK               = "block_user";
    // Owner-only
    public static final String ACTION_EDIT                = "edit";
    public static final String ACTION_ANALYTICS           = "analytics";
    public static final String ACTION_PINNED_COMMENTS     = "pinned_comments";
    public static final String ACTION_QR_CODE             = "qr_code";
    public static final String ACTION_DELETE              = "delete";
      // ── Advanced Duet Actions (v10) ──
      public static final String ACTION_DUET_INVITE    = "duet_invite";
      public static final String ACTION_DUET_BATTLE    = "duet_battle";
      public static final String ACTION_DUET_TREE      = "duet_tree";
      public static final String ACTION_DUET_CHALLENGE = "duet_challenge";
      public static final String ACTION_MULTI_DUET     = "multi_duet";
      public static final String ACTION_DUET_APPROVAL  = "duet_approval";
      public static final String ACTION_VIEW_SERIES    = "view_series";
      // ── Remix (v12) ──
      public static final String ACTION_REMIX           = "remix";
      public static final String ACTION_VIEW_REMIXES    = "view_remixes";
      // ── Watch History ──
      public static final String ACTION_WATCH_HISTORY   = "watch_history";
      // ── Quality Settings ──
      public static final String ACTION_QUALITY         = "quality";

    // ─── Item model ──────────────────────────────────────────────────────────
    private static class MenuItem {
        String  action;
        String  label;
        int     iconRes;
        int     textColor;      // 0 = default white
        boolean isDividerAfter;
        boolean disabled;       // grayed-out, non-tappable (followers-only lock)

        MenuItem(String action, String label, int iconRes) {
            this(action, label, iconRes, 0, false, false);
        }
        MenuItem(String action, String label, int iconRes,
                 int textColor, boolean dividerAfter, boolean disabled) {
            this.action         = action;
            this.label          = label;
            this.iconRes        = iconRes;
            this.textColor      = textColor;
            this.isDividerAfter = dividerAfter;
            this.disabled       = disabled;
        }
    }

    // ─── Item colors ─────────────────────────────────────────────────────────
    private static final int CLR_PINK     = 0xFFFF416C;
    private static final int CLR_CYAN     = 0xFF00C6FF;
    private static final int CLR_YELLOW   = 0xFFFFD700;
    private static final int CLR_GREEN    = 0xFF00F260;
    private static final int CLR_PURPLE   = 0xFFA855F7;
    private static final int CLR_ORANGE   = 0xFFFF9500;
    private static final int CLR_TEAL     = 0xFF00E5FF;
    private static final int CLR_RED      = 0xFFFF4444;
    private static final int CLR_GOLD     = 0xFFFFE082;
    private static final int CLR_COLLAB   = 0xFF7C3AED; // violet for collab repost
    private static final int CLR_DISABLED = 0x55FFFFFF; // semi-transparent white for locked items

    private OnItemClickListener listener;
    private boolean isOwner;
    private boolean isSaved;
    private String  speedLabel;
    private String  duetLevel;
    private String  stitchLevel;
    private boolean isFollowing;
    private String  seriesId;

    // ─── Factory ─────────────────────────────────────────────────────────────

    /**
     * @param duetLevel   "everyone" | "followers" | "off"
     * @param stitchLevel "everyone" | "followers" | "off"
     * @param isFollowing whether the current viewer follows the reel owner
     */
    public static ReelMoreBottomSheet newInstance(boolean isOwner, boolean isSaved,
                                                  String speedLabel,
                                                  String duetLevel, String stitchLevel,
                                                  boolean isFollowing) {
        return newInstance(isOwner, isSaved, speedLabel, duetLevel, stitchLevel, isFollowing, null);
    }

    public static ReelMoreBottomSheet newInstance(boolean isOwner, boolean isSaved,
                                                  String speedLabel,
                                                  String duetLevel, String stitchLevel,
                                                  boolean isFollowing, String seriesId) {
        ReelMoreBottomSheet sheet = new ReelMoreBottomSheet();
        Bundle args = new Bundle();
        args.putBoolean(ARG_IS_OWNER,     isOwner);
        args.putBoolean(ARG_IS_SAVED,     isSaved);
        args.putString (ARG_SPEED_LABEL,  speedLabel);
        args.putString (ARG_DUET_LEVEL,   duetLevel   != null ? duetLevel   : "everyone");
        args.putString (ARG_STITCH_LEVEL, stitchLevel != null ? stitchLevel : "everyone");
        args.putBoolean(ARG_IS_FOLLOWING, isFollowing);
        args.putString (ARG_SERIES_ID,    seriesId    != null ? seriesId    : "");
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
            isOwner     = getArguments().getBoolean(ARG_IS_OWNER,     false);
            isSaved     = getArguments().getBoolean(ARG_IS_SAVED,     false);
            speedLabel  = getArguments().getString (ARG_SPEED_LABEL,  "Speed: 1x");
            duetLevel   = getArguments().getString (ARG_DUET_LEVEL,   "everyone");
            stitchLevel = getArguments().getString (ARG_STITCH_LEVEL, "everyone");
            isFollowing = getArguments().getBoolean(ARG_IS_FOLLOWING, false);
            seriesId    = getArguments().getString (ARG_SERIES_ID,   "");
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

            if (item.disabled) {
                // Locked row — no ripple, not clickable
                row.setAlpha(0.45f);
            } else {
                row.setBackground(getResources().getDrawable(R.drawable.bg_more_item_ripple, null));
                row.setClickable(true);
                row.setFocusable(true);
            }

            // Icon
            ImageView icon = new ImageView(ctx);
            LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dpToPx(26), dpToPx(26));
            iconParams.setMarginEnd(dpToPx(18));
            icon.setLayoutParams(iconParams);
            icon.setImageResource(item.iconRes);
            int iconColor = item.disabled ? CLR_DISABLED
                          : (item.textColor != 0 ? item.textColor : Color.WHITE);
            icon.setColorFilter(iconColor);

            // Label
            TextView label = new TextView(ctx);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
            label.setText(item.label);
            label.setTextSize(15f);
            label.setTextColor(item.disabled ? CLR_DISABLED
                             : (item.textColor != 0 ? item.textColor : Color.WHITE));
            label.setTypeface(android.graphics.Typeface.create(
                "sans-serif", android.graphics.Typeface.NORMAL));

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

            // Click — only if not disabled
            if (!item.disabled) {
                final String action = item.action;
                row.setOnClickListener(v -> {
                    dismiss();
                    if (listener != null) listener.onMoreItemClick(action);
                });
            }
        }
    }

    // ─── Viewer menu items ────────────────────────────────────────────────────

    private List<MenuItem> buildViewerItems() {
        List<MenuItem> list = new ArrayList<>();
        list.add(new MenuItem(ACTION_BOOKMARK_COLLECTIONS, "Bookmark Collections", R.drawable.ic_bookmark,      CLR_CYAN,   true,  false));
        list.add(new MenuItem(ACTION_SPEED,                speedLabel,             R.drawable.ic_speed,         CLR_YELLOW, false, false));
        list.add(new MenuItem(ACTION_DOWNLOAD,             "Download",             R.drawable.ic_download_reel, CLR_GREEN,  true,  false));

        // ── Duet ──
        addDuetStitchItem(list, ACTION_DUET, "Duet", R.drawable.ic_video_call,
                          duetLevel, false);

        // ── Duet Invite (v10) ──
        if (!"off".equals(duetLevel)) {
            list.add(new MenuItem(ACTION_DUET_INVITE, "Invite to Duet", R.drawable.ic_video_call, CLR_TEAL, false, false));
        }

        // ── Multi-Person Duet (v10) ──
        if (!"off".equals(duetLevel)) {
            list.add(new MenuItem(ACTION_MULTI_DUET, "Multi Duet 👥", R.drawable.ic_group, CLR_PURPLE, false, false));
        }

        // ── Stitch ──
        addDuetStitchItem(list, ACTION_STITCH, "Stitch", R.drawable.ic_swap,
                          stitchLevel, false);

        // ── Duet Challenge (v10) ──
        list.add(new MenuItem(ACTION_DUET_CHALLENGE, "Create Challenge 🏆", R.drawable.ic_reels, CLR_GOLD, false, false));

        // ── Duet Series (v11) ──
        if (seriesId != null && !seriesId.isEmpty()) {
            list.add(new MenuItem(ACTION_VIEW_SERIES, "View Series 🎬", R.drawable.ic_duet_series, CLR_CYAN, false, false));
        }

        // ── Remix (v12) ──
        list.add(new MenuItem(ACTION_REMIX,         "🎬 Remix",          R.drawable.ic_reels,       CLR_PURPLE,  false, false));
        list.add(new MenuItem(ACTION_VIEW_REMIXES,  "View Remixes",      R.drawable.ic_reels,       CLR_TEAL,    true,  false));

        list.add(new MenuItem(ACTION_VIDEO_REPLY,   "Video Reply",        R.drawable.ic_reply,       CLR_PURPLE,  false, false));
        list.add(new MenuItem(ACTION_SHARE_TO_STORY,"Share to Story",   R.drawable.ic_share_reel,  CLR_GREEN,   true,  false));
        list.add(new MenuItem(ACTION_COLLAB_REPOST, "🤝 Collab Repost", R.drawable.ic_group,       CLR_COLLAB,  false, false));
        list.add(new MenuItem(ACTION_COLLAB_REQUEST,"Collab Request",   R.drawable.ic_group,       CLR_TEAL,    true,  false));
        list.add(new MenuItem(ACTION_NOT_INTERESTED,"Not Interested",   R.drawable.ic_eye_off,     CLR_GOLD,    false, false));
        list.add(new MenuItem(ACTION_QUALITY,       "Video Quality",    R.drawable.ic_speed,       CLR_CYAN,    false, false));
        list.add(new MenuItem(ACTION_WATCH_HISTORY, "Watch History",    R.drawable.ic_history,     CLR_ORANGE,  true,  false));
        list.add(new MenuItem(ACTION_COPY_LINK,     "Copy Link",        R.drawable.ic_link,        CLR_CYAN,   true,  false));
        list.add(new MenuItem(ACTION_REPORT,        "Report",           R.drawable.ic_flag,        CLR_RED,    false, false));
        list.add(new MenuItem(ACTION_BLOCK,         "Block User",       R.drawable.ic_phone_off,   CLR_RED,    false, false));
        return list;
    }

    // ─── Owner menu items ─────────────────────────────────────────────────────

    private List<MenuItem> buildOwnerItems() {
        String saveLabel = isSaved ? "Unsave" : "Save";
        int    saveIcon  = isSaved ? R.drawable.ic_close : R.drawable.ic_bookmark;

        List<MenuItem> list = new ArrayList<>();
        list.add(new MenuItem(ACTION_SAVE,                 saveLabel,              saveIcon,                    CLR_PINK,   false, false));
        list.add(new MenuItem(ACTION_BOOKMARK_COLLECTIONS, "Bookmark Collections", R.drawable.ic_bookmark,      CLR_CYAN,   true,  false));
        list.add(new MenuItem(ACTION_SPEED,                speedLabel,             R.drawable.ic_speed,         CLR_YELLOW, false, false));
        list.add(new MenuItem(ACTION_DOWNLOAD,             "Download",             R.drawable.ic_download_reel, CLR_GREEN,  true,  false));
        list.add(new MenuItem(ACTION_EDIT,                 "Edit Reel",            R.drawable.ic_edit,          CLR_ORANGE, false, false));
        list.add(new MenuItem(ACTION_ANALYTICS,            "Analytics",            R.drawable.ic_reel_explore,  CLR_TEAL,   false, false));
        list.add(new MenuItem(ACTION_PINNED_COMMENTS,      "Pinned Comments",      R.drawable.ic_pin,           CLR_TEAL,   true,  false));

        // Owner can always duet/stitch their own reel (duetLevel = current setting)
        addDuetStitchItem(list, ACTION_DUET,   "Duet",   R.drawable.ic_video_call, duetLevel,   false);
        addDuetStitchItem(list, ACTION_STITCH, "Stitch", R.drawable.ic_swap,       stitchLevel, false);

        // ── v10: Multi Duet + Challenge + Approval Queue (owner only) ──
        list.add(new MenuItem(ACTION_MULTI_DUET,     "Multi Duet 👥",        R.drawable.ic_group,       CLR_PURPLE, false, false));
        list.add(new MenuItem(ACTION_DUET_CHALLENGE, "Create Challenge 🏆",  R.drawable.ic_reels,       CLR_GOLD,   false, false));
        list.add(new MenuItem(ACTION_DUET_APPROVAL,  "Duet Approval Queue",  R.drawable.ic_video_call,  CLR_TEAL,   true,  false));

        // ── Duet Series (v11) ──
        if (seriesId != null && !seriesId.isEmpty()) {
            list.add(new MenuItem(ACTION_VIEW_SERIES, "View Series 🎬", R.drawable.ic_duet_series, CLR_CYAN, false, false));
        }

        // ── Remix (v12 owner) ── View who remixed + manage allow_remix setting
        list.add(new MenuItem(ACTION_VIEW_REMIXES,   "View Remixes",      R.drawable.ic_reels,      CLR_TEAL,    false, false));

        list.add(new MenuItem(ACTION_SHARE_TO_STORY, "Share to Story",   R.drawable.ic_share_reel,  CLR_GREEN,   true,  false));
        list.add(new MenuItem(ACTION_COLLAB_REPOST,  "🤝 Collab Repost", R.drawable.ic_group,       CLR_COLLAB,  false, false));
        list.add(new MenuItem(ACTION_QR_CODE,        "QR Code",          R.drawable.ic_qr_code,     CLR_ORANGE,  false, false));
        list.add(new MenuItem(ACTION_COLLAB_REQUEST, "Collab Request",   R.drawable.ic_group,       CLR_TEAL,    false, false));
        list.add(new MenuItem(ACTION_QUALITY,        "Video Quality",    R.drawable.ic_speed,       CLR_CYAN,    true,  false));
        list.add(new MenuItem(ACTION_COPY_LINK,      "Copy Link",        R.drawable.ic_link,        CLR_CYAN,   false, false));
        list.add(new MenuItem(ACTION_DELETE,         "Delete",           R.drawable.ic_delete,      CLR_RED,    false, false));
        return list;
    }

    /**
     * Adds Duet or Stitch to the menu according to the permission level:
     *   "off"       → skip entirely
     *   "followers" + !isFollowing → add grayed-out "Duet (followers only)" — non-tappable
     *   "followers" + isFollowing  → add normally
     *   "everyone"  → add normally
     */
    private void addDuetStitchItem(List<MenuItem> list, String action,
                                   String baseLabel, int iconRes,
                                   String level, boolean dividerAfter) {
        if ("off".equals(level)) return; // hide entirely

        boolean followersOnly = "followers".equals(level);
        boolean canUse        = !followersOnly || isFollowing;

        String  label    = followersOnly && !isFollowing
                               ? baseLabel + " (followers only)"
                               : baseLabel;
        int     color    = canUse ? CLR_PURPLE : CLR_DISABLED;
        boolean disabled = !canUse;

        list.add(new MenuItem(action, label, iconRes, color, dividerAfter, disabled));
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private int dpToPx(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
