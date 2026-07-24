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
 * ReelSoundQuickActionSheet v2
 *
 * Small card shown when the right-rail photo/disc button is tapped in the Reel player.
 *
 * Contains THREE separate, distinct action rows:
 *   1. Remix    — record ALONGSIDE the original (side-by-side, react cam, etc.)
 *   2. Sequence — record your continuation AFTER the original
 *   3. Sound    — open SoundDetailActivity to explore / use this audio
 *
 * Callbacks are separate per row — no combined "Remix and sequence" anymore.
 * The host fragment (ReelPlayerFragment) implements {@link OnActionListener}.
 */
public class ReelSoundQuickActionSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelSoundQuickActionSheet";

    private static final String ARG_TITLE     = "title";
    private static final String ARG_COVER_URL = "cover_url";

    // ── Listener ──────────────────────────────────────────────────────────────

    public interface OnActionListener {
        /** User tapped the Remix row → show layout picker → ReelRemixActivity */
        void onRemix();
        /** User tapped the Sequence row → ReelSequenceActivity */
        void onSequence();
        /** User tapped the Sound row → SoundDetailActivity */
        void onSoundInfoSelected();
    }

    private OnActionListener listener;
    private String soundTitle;
    private String coverUrl;

    // ── Factory ───────────────────────────────────────────────────────────────

    public static ReelSoundQuickActionSheet newInstance(String title, String coverUrl) {
        ReelSoundQuickActionSheet sheet = new ReelSoundQuickActionSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE,     title    != null ? title    : "Original Audio");
        args.putString(ARG_COVER_URL, coverUrl != null ? coverUrl : "");
        sheet.setArguments(args);
        return sheet;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
            soundTitle = getArguments().getString(ARG_TITLE,     "Original Audio");
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

        // Expand immediately, no peek
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            d.getBehavior().setSkipCollapsed(true);
        }

        // Sound cover + title
        TextView  tvTitle = view.findViewById(R.id.tv_quick_sound_title);
        ImageView ivCover = view.findViewById(R.id.iv_quick_sound_cover);
        if (tvTitle != null) tvTitle.setText(soundTitle);
        if (ivCover != null && coverUrl != null && !coverUrl.isEmpty()) {
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .into(ivCover);
        }

        // ── Row 1: Remix ──────────────────────────────────────────────────────
        View rowRemix = view.findViewById(R.id.row_remix);
        if (rowRemix != null) {
            rowRemix.setOnClickListener(v -> {
                dismiss();
                if (listener != null) listener.onRemix();
            });
        }

        // ── Row 2: Sequence ───────────────────────────────────────────────────
        View rowSequence = view.findViewById(R.id.row_sequence);
        if (rowSequence != null) {
            rowSequence.setOnClickListener(v -> {
                dismiss();
                if (listener != null) listener.onSequence();
            });
        }

        // ── Row 3: Sound info ─────────────────────────────────────────────────
        View rowSound = view.findViewById(R.id.row_sound_info);
        if (rowSound != null) {
            rowSound.setOnClickListener(v -> {
                dismiss();
                if (listener != null) listener.onSoundInfoSelected();
            });
        }
    }
}
