package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ReelRemixPickerSheet — Bottom sheet to select remix layout mode
 *
 * Layout options:
 *  1. Side by Side  — original left, your camera right (like Duet)
 *  2. React Cam     — original full screen, your face cam top-right corner
 *  3. Green Screen  — you in front of original video as background
 *  4. Overlay       — your video plays over original at 50% opacity
 *
 * Usage:
 *   ReelRemixPickerSheet sheet = ReelRemixPickerSheet.newInstance(reelModel);
 *   sheet.show(getSupportFragmentManager(), "remix_picker");
 */
public class ReelRemixPickerSheet extends BottomSheetDialogFragment {

    private static final String ARG_REEL_ID    = "reelId";
    private static final String ARG_OWNER_UID  = "ownerUid";
    private static final String ARG_OWNER_NAME = "ownerName";
    private static final String ARG_VIDEO_URL  = "videoUrl";
    private static final String ARG_THUMB_URL  = "thumbUrl";

    public static ReelRemixPickerSheet newInstance(ReelModel reel) {
        ReelRemixPickerSheet sheet = new ReelRemixPickerSheet();
        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID,    reel.reelId);
        args.putString(ARG_OWNER_UID,  reel.uid);
        args.putString(ARG_OWNER_NAME, reel.ownerName);
        args.putString(ARG_VIDEO_URL,  reel.videoUrl);
        args.putString(ARG_THUMB_URL,  reel.thumbUrl != null ? reel.thumbUrl : reel.thumbnailUrl);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_remix_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args      = requireArguments();
        String reelId    = args.getString(ARG_REEL_ID);
        String ownerUid  = args.getString(ARG_OWNER_UID);
        String ownerName = args.getString(ARG_OWNER_NAME);
        String videoUrl  = args.getString(ARG_VIDEO_URL);
        String thumbUrl  = args.getString(ARG_THUMB_URL);

        TextView tvTitle = view.findViewById(R.id.tv_remix_sheet_title);
        tvTitle.setText("Remix @" + ownerName + "'s reel");

        view.findViewById(R.id.layout_remix_side_by_side).setOnClickListener(v ->
            openRemix(reelId, ownerUid, ownerName, videoUrl, thumbUrl,
                ReelRemixActivity.LAYOUT_SIDE_BY_SIDE));

        view.findViewById(R.id.layout_remix_react_cam).setOnClickListener(v ->
            openRemix(reelId, ownerUid, ownerName, videoUrl, thumbUrl,
                ReelRemixActivity.LAYOUT_REACT_CAM));

        view.findViewById(R.id.layout_remix_green_screen).setOnClickListener(v ->
            openRemix(reelId, ownerUid, ownerName, videoUrl, thumbUrl,
                ReelRemixActivity.LAYOUT_GREEN_SCREEN));

        view.findViewById(R.id.layout_remix_overlay).setOnClickListener(v ->
            openRemix(reelId, ownerUid, ownerName, videoUrl, thumbUrl,
                ReelRemixActivity.LAYOUT_OVERLAY));

        view.findViewById(R.id.btn_remix_cancel).setOnClickListener(v -> dismiss());
    }

    private void openRemix(String reelId, String ownerUid, String ownerName,
                           String videoUrl, String thumbUrl, String layout) {
        dismiss();
        Intent intent = new Intent(requireContext(), ReelRemixActivity.class);
        intent.putExtra(ReelRemixActivity.EXTRA_REEL_ID,    reelId);
        intent.putExtra(ReelRemixActivity.EXTRA_OWNER_UID,  ownerUid);
        intent.putExtra(ReelRemixActivity.EXTRA_OWNER_NAME, ownerName);
        intent.putExtra(ReelRemixActivity.EXTRA_VIDEO_URL,  videoUrl);
        intent.putExtra(ReelRemixActivity.EXTRA_THUMB_URL,  thumbUrl);
        intent.putExtra(ReelRemixActivity.EXTRA_LAYOUT,     layout);
        startActivity(intent);
    }
}
