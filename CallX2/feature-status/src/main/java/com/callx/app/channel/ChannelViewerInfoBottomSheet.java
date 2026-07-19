package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.status.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ChannelViewerInfoBottomSheet — shows channel info when tapping the header
 * in ChannelViewerActivity. WhatsApp-style info sheet.
 *
 * Shows:
 *   - Channel icon (large), name, verified badge
 *   - Description
 *   - Follower count
 *   - Category
 *   - Created date
 *   - Owner info
 *   - Privacy (public/private)
 *   - Action buttons: Share, Mute/Unmute (callback to activity)
 */
public class ChannelViewerInfoBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChannelViewerInfo";
    private static final String ARG_CHANNEL = "channelJson";

    // Pass as extras on the Bundle for simplicity
    private static final String ARG_ID          = "id";
    private static final String ARG_NAME        = "name";
    private static final String ARG_DESC        = "desc";
    private static final String ARG_ICON        = "icon";
    private static final String ARG_FOLLOWERS   = "followers";
    private static final String ARG_VERIFIED    = "verified";
    private static final String ARG_CATEGORY    = "category";
    private static final String ARG_CREATED_AT  = "createdAt";
    private static final String ARG_IS_PRIVATE  = "isPrivate";
    private static final String ARG_TOTAL_POSTS = "totalPosts";

    public static ChannelViewerInfoBottomSheet newInstance(ChannelEntity ch) {
        ChannelViewerInfoBottomSheet sheet = new ChannelViewerInfoBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_ID,        ch.id);
        args.putString(ARG_NAME,      ch.name != null ? ch.name : "");
        args.putString(ARG_DESC,      ch.description != null ? ch.description : "");
        args.putString(ARG_ICON,      ch.iconUrl != null ? ch.iconUrl : "");
        args.putLong  (ARG_FOLLOWERS, ch.followers);
        args.putBoolean(ARG_VERIFIED, ch.verified);
        args.putString(ARG_CATEGORY,  ch.category != null ? ch.category : "General");
        args.putLong  (ARG_CREATED_AT,ch.createdAt);
        args.putBoolean(ARG_IS_PRIVATE, ch.isPrivate);
        args.putLong  (ARG_TOTAL_POSTS, ch.totalPosts);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_channel_viewer_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getArguments() == null) return;

        Bundle a = getArguments();
        String channelId  = a.getString(ARG_ID, "");
        String name       = a.getString(ARG_NAME, "");
        String desc       = a.getString(ARG_DESC, "");
        String iconUrl    = a.getString(ARG_ICON, "");
        long followers    = a.getLong(ARG_FOLLOWERS, 0);
        boolean verified  = a.getBoolean(ARG_VERIFIED, false);
        String category   = a.getString(ARG_CATEGORY, "General");
        long createdAt    = a.getLong(ARG_CREATED_AT, 0);
        boolean isPrivate = a.getBoolean(ARG_IS_PRIVATE, false);
        long totalPosts   = a.getLong(ARG_TOTAL_POSTS, 0);

        CircleImageView ivIcon = view.findViewById(R.id.iv_channel_info_icon);
        if (ivIcon != null && !iconUrl.isEmpty()) {
            Glide.with(this).load(iconUrl).circleCrop().override(160, 160).into(ivIcon);
        }

        setText(view, R.id.tv_channel_info_name, name);

        TextView tvVerified = view.findViewById(R.id.tv_channel_info_verified);
        if (tvVerified != null) tvVerified.setVisibility(verified ? View.VISIBLE : View.GONE);

        setText(view, R.id.tv_channel_info_desc,
            desc.isEmpty() ? "No description provided." : desc);

        setText(view, R.id.tv_channel_info_followers, formatFollowers(followers) + " followers");
        setText(view, R.id.tv_channel_info_category, category);
        setText(view, R.id.tv_channel_info_privacy, isPrivate ? "Private channel" : "Public channel");
        setText(view, R.id.tv_channel_info_posts, totalPosts + " posts");

        if (createdAt > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMMM yyyy",
                java.util.Locale.getDefault());
            setText(view, R.id.tv_channel_info_created, "Created " + sdf.format(new java.util.Date(createdAt)));
        }

        // Share button
        Button btnShare = view.findViewById(R.id.btn_channel_info_share);
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> {
                String link = "https://callx.app/channel/" + channelId;
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, "Follow " + name + " on CallX: " + link);
                startActivity(Intent.createChooser(share, "Share channel"));
            });
        }

        // Close button
        Button btnClose = view.findViewById(R.id.btn_channel_info_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> dismiss());
    }

    private void setText(View root, int id, String text) {
        TextView tv = root.findViewById(id);
        if (tv != null) tv.setText(text);
    }

    private String formatFollowers(long n) {
        if (n >= 1_000_000L) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
