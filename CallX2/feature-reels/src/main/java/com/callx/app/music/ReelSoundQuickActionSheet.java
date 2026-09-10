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

import com.callx.app.followers.FollowAvatarBinder;
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
    // Reused so onDestroyView can cancel the in-flight FollowAvatarBinder
    // request the same way FollowConnectionsActivity cancels row avatars
    // on recycle — this sheet is short-lived but the dialog can be
    // dismissed mid-decode, no reason to let that Glide request run on.
    private ImageView ivCover;

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
        // Day/night-aware theme — unlike ReelMoreBottomSheet (forced dark,
        // sits over the always-dark video player), this sheet was asked to
        // follow the device/app light-dark setting instead of being pinned
        // dark. See themes.xml for ReelSoundQuickActionSheetTheme.
        setStyle(STYLE_NORMAL, R.style.ReelSoundQuickActionSheetTheme);
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
        // FIX (avatar pipeline parity): this cover was a flat, untiered
        // Glide.load(coverUrl) — no L2/L3 reuse, no density-aware tier
        // decode, nothing cancelling the request if the sheet is dismissed
        // mid-decode. Reused FollowAvatarBinder — the SAME shared pipeline
        // FollowConnectionsActivity's row avatars use (L2 memory + L3 disk
        // cache via ReelsAvatarL2Cache, SMALL tier sizing, dedupe-by-URL-tag)
        // — instead of standing up a separate cache for one more avatar-
        // shaped image. No avatarVersion exists for a sound cover, so pass
        // 0L, same as MutualFollowersActivity/ReelCloseFriendsActivity do
        // for their own version-less binds.
        TextView tvTitle = view.findViewById(R.id.tv_quick_sound_title);
        ivCover = view.findViewById(R.id.iv_quick_sound_cover);
        if (tvTitle != null) tvTitle.setText(soundTitle);
        if (ivCover != null) {
            FollowAvatarBinder.bind(requireContext(), ivCover, coverUrl, 0L, R.drawable.ic_music_note);
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

    @Override
    public void onDestroyView() {
        if (ivCover != null) {
            FollowAvatarBinder.cancel(requireContext(), ivCover);
            ivCover = null;
        }
        super.onDestroyView();
    }
}
