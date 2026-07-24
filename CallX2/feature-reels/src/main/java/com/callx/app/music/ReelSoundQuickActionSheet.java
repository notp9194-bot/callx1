package com.callx.app.music;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;

/**
 * ReelSoundQuickActionSheet
 *
 * Small floating card shown when the sound/audio tile in the Reel player's
 * right action rail is tapped — matches Instagram's compact "Remix and
 * sequence" popup exactly: no dark scrim, no drag handle, no full-width
 * bottom sheet — just a small rounded card floating above the caption row.
 *
 * Tapping the audio row opens SoundDetailActivity; tapping "Remix and
 * sequence" opens the remix flow. The sheet itself performs no navigation —
 * it reports the tap back to the host via {@link OnActionListener} and
 * dismisses itself.
 */
public class ReelSoundQuickActionSheet extends DialogFragment {

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
        setStyle(STYLE_NO_FRAME, R.style.ReelSoundQuickCardTheme);
        if (getArguments() != null) {
            soundTitle = getArguments().getString(ARG_TITLE, "Original Audio");
            coverUrl   = getArguments().getString(ARG_COVER_URL, "");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        if (window != null) {
            // Full-screen transparent window — the card itself is a small
            // bottom|end-anchored view inside the layout, so it floats over
            // the reel instead of stretching edge-to-edge like a bottom sheet.
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                              WindowManager.LayoutParams.MATCH_PARENT);
            window.setDimAmount(0f); // no dark scrim behind the card
            window.setGravity(android.view.Gravity.NO_GRAVITY);
            // Draw behind the status bar / nav bar instead of letting this
            // new window paint its own opaque system-bar background — the
            // reel player behind it is already immersive/edge-to-edge, so
            // this dialog must extend the same way or the bars flash solid.
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Mirror the host activity's current immersive system-UI flags onto
        // this dialog's own decor view. A dialog window otherwise starts
        // with default (non-immersive) decor, which is what repaints the
        // status bar / nav bar backgrounds even though the activity behind
        // it is fullscreen.
        Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null || getActivity() == null) return;
        View hostDecor = getActivity().getWindow().getDecorView();
        View dialogDecor = dialog.getWindow().getDecorView();
        dialogDecor.setSystemUiVisibility(hostDecor.getSystemUiVisibility());
        hostDecor.setOnSystemUiVisibilityChangeListener(vis -> {
            if (isAdded() && getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().getDecorView().setSystemUiVisibility(vis);
            }
        });
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialogInterface) {
        super.onDismiss(dialogInterface);
        if (getActivity() != null) {
            getActivity().getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(null);
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

        TextView  tvTitle = view.findViewById(R.id.tv_quick_sound_title);
        ImageView ivCover = view.findViewById(R.id.iv_quick_sound_cover);
        View rowRemix     = view.findViewById(R.id.row_remix_sequence);
        View rowSound     = view.findViewById(R.id.row_sound_info);

        // Tapping anywhere outside the card (the transparent host) dismisses it.
        view.setOnClickListener(v -> dismiss());

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
