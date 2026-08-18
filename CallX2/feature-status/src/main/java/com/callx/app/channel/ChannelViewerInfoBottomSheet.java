package com.callx.app.channel;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewModelProvider;

/**
 * ChannelViewerInfoBottomSheet — bottom sheet showing channel info + action buttons (v5).
 *
 * v5 additions:
 *   ✓ NEW: QR code button — generates and shows the channel QR code inline
 *   ✓ NEW: Share channel button — opens Android share sheet with invite link
 *   ✓ Existing actions: follow/unfollow, mute, notification settings, report
 */
public class ChannelViewerInfoBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChannelViewerInfo";

    private static final String ARG_CHANNEL_ID      = "channelId";
    private static final String ARG_CHANNEL_NAME    = "channelName";
    private static final String ARG_CHANNEL_DESC    = "channelDesc";
    private static final String ARG_CHANNEL_INVITE  = "inviteLink";
    private static final String ARG_FOLLOWER_COUNT  = "followerCount";
    private static final String ARG_IS_FOLLOWING    = "isFollowing";
    private static final String ARG_IS_MUTED        = "isMuted";
    private static final String ARG_IS_ADMIN        = "isAdmin";
    private static final int    QR_SIZE             = 400;

    private ChannelViewModel viewModel;
    private String channelId, channelName, channelDesc, inviteLink;
    private long   followerCount;
    private boolean isFollowing, isMuted, isAdmin;

    public static ChannelViewerInfoBottomSheet newInstance(String channelId, String channelName,
            String channelDesc, String inviteLink, long followerCount,
            boolean isFollowing, boolean isMuted, boolean isAdmin) {
        ChannelViewerInfoBottomSheet sheet = new ChannelViewerInfoBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CHANNEL_ID,     channelId);
        args.putString(ARG_CHANNEL_NAME,   channelName);
        args.putString(ARG_CHANNEL_DESC,   channelDesc);
        args.putString(ARG_CHANNEL_INVITE, inviteLink);
        args.putLong(  ARG_FOLLOWER_COUNT, followerCount);
        args.putBoolean(ARG_IS_FOLLOWING,  isFollowing);
        args.putBoolean(ARG_IS_MUTED,      isMuted);
        args.putBoolean(ARG_IS_ADMIN,      isAdmin);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_channel_viewer_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        if (args == null) { dismiss(); return; }

        channelId     = args.getString(ARG_CHANNEL_ID);
        channelName   = args.getString(ARG_CHANNEL_NAME);
        channelDesc   = args.getString(ARG_CHANNEL_DESC);
        inviteLink    = args.getString(ARG_CHANNEL_INVITE);
        followerCount = args.getLong(ARG_FOLLOWER_COUNT);
        isFollowing   = args.getBoolean(ARG_IS_FOLLOWING);
        isMuted       = args.getBoolean(ARG_IS_MUTED);
        isAdmin       = args.getBoolean(ARG_IS_ADMIN);

        viewModel = new ViewModelProvider((ViewModelStoreOwner) requireActivity())
            .get(ChannelViewModel.class);

        // Info labels
        TextView tvName = view.findViewById(R.id.tv_info_channel_name);
        TextView tvDesc = view.findViewById(R.id.tv_info_channel_desc);
        TextView tvFollowers = view.findViewById(R.id.tv_info_follower_count);
        if (tvName      != null) tvName.setText(channelName != null ? channelName : "");
        if (tvDesc      != null) tvDesc.setText(channelDesc != null ? channelDesc : "No description.");
        if (tvFollowers != null) tvFollowers.setText(formatCompact(followerCount) + " followers");

        // Follow / Unfollow button
        MaterialButton btnFollow = view.findViewById(R.id.btn_info_follow);
        if (btnFollow != null) {
            btnFollow.setText(isFollowing ? "Unfollow" : "Follow");
            btnFollow.setOnClickListener(v -> {
                viewModel.getChannel(channelId).observe(getViewLifecycleOwner(), ch -> {
                    if (ch == null) return;
                    if (isFollowing) { viewModel.unfollowChannel(ch); btnFollow.setText("Follow"); }
                    else             { viewModel.followChannel(ch);   btnFollow.setText("Unfollow"); }
                    isFollowing = !isFollowing;
                });
            });
        }

        // Mute button
        MaterialButton btnMute = view.findViewById(R.id.btn_info_mute);
        if (btnMute != null) {
            btnMute.setText(isMuted ? "Unmute" : "Mute");
            btnMute.setOnClickListener(v -> {
                viewModel.getChannel(channelId).observe(getViewLifecycleOwner(), ch -> {
                    if (ch == null) return;
                    if (isMuted) { viewModel.unmuteChannel(ch);            btnMute.setText("Mute"); }
                    else         { viewModel.muteChannel(ch, 0); btnMute.setText("Unmute"); }
                    isMuted = !isMuted;
                });
            });
        }

        // Notifications button
        MaterialButton btnNotif = view.findViewById(R.id.btn_info_notifications);
        if (btnNotif != null) {
            btnNotif.setOnClickListener(v -> {
                Intent i = new Intent(requireContext(), ChannelNotificationSettingsActivity.class);
                i.putExtra(ChannelNotificationSettingsActivity.EXTRA_CHANNEL_ID,   channelId);
                i.putExtra(ChannelNotificationSettingsActivity.EXTRA_CHANNEL_NAME, channelName);
                startActivity(i);
                dismiss();
            });
        }

        // ── NEW: QR Code button ─────────────────────────────────────────
        MaterialButton btnQr = view.findViewById(R.id.btn_info_qr_code);
        ImageView ivQr       = view.findViewById(R.id.iv_info_qr_code);
        if (btnQr != null) {
            btnQr.setOnClickListener(v -> {
                String linkForQr = inviteLink != null && !inviteLink.isEmpty()
                    ? inviteLink : "https://callx.app/channel/" + channelId;
                if (ivQr != null) {
                    if (ivQr.getVisibility() == View.VISIBLE) {
                        ivQr.setVisibility(View.GONE);
                        btnQr.setText("Show QR code");
                    } else {
                        try {
                            BarcodeEncoder encoder = new BarcodeEncoder();
                            Bitmap bmp = encoder.encodeBitmap(linkForQr, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE);
                            ivQr.setImageBitmap(bmp);
                            ivQr.setVisibility(View.VISIBLE);
                            btnQr.setText("Hide QR code");
                        } catch (WriterException e) {
                            Toast.makeText(requireContext(), "Could not generate QR.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        // ── NEW: Share channel button ───────────────────────────────────
        MaterialButton btnShare = view.findViewById(R.id.btn_info_share_channel);
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> {
                ChannelShareHelper.shareChannelViaAndroid(requireContext(), channelId, channelName, inviteLink);
                dismiss();
            });
        }

        // Report button
        MaterialButton btnReport = view.findViewById(R.id.btn_info_report);
        if (btnReport != null) {
            btnReport.setOnClickListener(v -> {
                viewModel.reportChannel(channelId);
                dismiss();
            });
        }
    }

    private String formatCompact(long n) {
        if (n >= 1_000_000L) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
