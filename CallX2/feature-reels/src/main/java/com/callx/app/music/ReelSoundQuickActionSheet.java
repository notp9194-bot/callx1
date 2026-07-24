package com.callx.app.music;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ReelSoundQuickActionSheet
 *
 * Small intermediate card shown when the sound-disc / right-rail action
 * button is tapped in the Reel player (Instagram-style "Remix and
 * sequence" + audio-info card). Tapping the audio row opens
 * SoundDetailActivity; tapping "Remix and sequence" opens the remix flow.
 *
 * This sheet itself performs no navigation — it just reports the tap
 * back to the host via {@link OnActionListener} and dismisses itself,
 * matching the pattern used by ReelMoreBottomSheet.
 */
public class ReelSoundQuickActionSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelSoundQuickActionSheet";

    private static final String ARG_TITLE     = "title";
    private static final String ARG_COVER_URL = "cover_url";

    public interface OnActionListener {
        void onRemixAndSequence();
        void onSoundInfoSelected();
    }

    private OnActionListener listener;
    private String soundTitle;
    private String coverUrl;

    public static ReelSoundQuickActionSheet newInstance(String title, String coverUrl) {
        ReelSoundQuickActionSheet sheet = new ReelSoundQuickActionSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title != null ? title : "Original Audio");
        args.putString(ARG_COVER_URL, coverUrl != null ? coverUrl : "");
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof OnActionListener) {
            listener = (OnActionListener) getParentFragment();
        } else if (context instanceof OnActionListener) {
            listener = (OnActionListener) context;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ReelMoreBottomSheetTheme);
        if (getArguments() != null) {
            soundTitle = getArguments().getString(ARG_TITLE, "Original Audio");
            coverUrl   = getArguments().getString(ARG_COVER_URL, "");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reel_sound_quick, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            d.getBehavior().setSkipCollapsed(true);
        }

        TextView  tvTitle = view.findViewById(R.id.tv_quick_sound_title);
        ImageView ivCover = view.findViewById(R.id.iv_quick_sound_cover);
        View rowRemix     = view.findViewById(R.id.row_remix_sequence);
        View rowSound     = view.findViewById(R.id.row_sound_info);

        if (tvTitle != null) tvTitle.setText(soundTitle);
        if (ivCover != null && coverUrl != null && !coverUrl.isEmpty()) {
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .into(ivCover);
        }

        if (rowRemix != null) rowRemix.setOnClickListener(v -> {
            if (listener != null) listener.onRemixAndSequence();
            dismiss();
        });
        if (rowSound != null) rowSound.setOnClickListener(v -> {
            if (listener != null) listener.onSoundInfoSelected();
            dismiss();
        });
    }
}
