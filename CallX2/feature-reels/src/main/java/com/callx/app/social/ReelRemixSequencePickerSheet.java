package com.callx.app.social;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ReelRemixSequencePickerSheet — Chooser shown after tapping "Remix and sequence"
 * on the right-rail of the Reel player.
 *
 * Presents two distinct creation modes:
 *  • Remix    — record ALONGSIDE the original (side-by-side, react cam, etc.)
 *               → dismisses and shows {@link ReelRemixPickerSheet} for layout selection
 *  • Sequence — record AFTER the original (your clip plays after theirs)
 *               → dismisses and starts {@link ReelSequenceActivity}
 *
 * The host (ReelPlayerFragment) implements {@link OnModeSelectedListener}.
 */
public class ReelRemixSequencePickerSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelRemixSequencePickerSheet";

    // ── Args ─────────────────────────────────────────────────────────────────
    private static final String ARG_REEL_ID    = "reelId";
    private static final String ARG_OWNER_UID  = "ownerUid";
    private static final String ARG_OWNER_NAME = "ownerName";
    private static final String ARG_VIDEO_URL  = "videoUrl";
    private static final String ARG_THUMB_URL  = "thumbUrl";
    private static final String ARG_DURATION   = "duration";

    // ── Callback ──────────────────────────────────────────────────────────────
    public interface OnModeSelectedListener {
        /** User chose to Remix (simultaneous recording). */
        void onRemixSelected(ReelModel reel);
        /** User chose to Sequence (sequential recording after original). */
        void onSequenceSelected(ReelModel reel);
    }

    private OnModeSelectedListener listener;

    // ── Factory ───────────────────────────────────────────────────────────────

    public static ReelRemixSequencePickerSheet newInstance(ReelModel reel) {
        ReelRemixSequencePickerSheet sheet = new ReelRemixSequencePickerSheet();
        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID,    reel.reelId  != null ? reel.reelId    : "");
        args.putString(ARG_OWNER_UID,  reel.uid     != null ? reel.uid       : "");
        args.putString(ARG_OWNER_NAME, reel.ownerName != null ? reel.ownerName : "");
        args.putString(ARG_VIDEO_URL,  reel.videoUrl != null ? reel.videoUrl  : "");
        args.putString(ARG_THUMB_URL,  reel.effectiveThumbUrl());
        args.putInt(ARG_DURATION, reel.duration);
        sheet.setArguments(args);
        return sheet;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof OnModeSelectedListener) {
            listener = (OnModeSelectedListener) getParentFragment();
        } else if (context instanceof OnModeSelectedListener) {
            listener = (OnModeSelectedListener) context;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ReelMoreBottomSheetTheme);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_remix_sequence_picker, container, false);
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

        Bundle args      = requireArguments();
        String reelId    = args.getString(ARG_REEL_ID,    "");
        String ownerUid  = args.getString(ARG_OWNER_UID,  "");
        String ownerName = args.getString(ARG_OWNER_NAME, "");
        String videoUrl  = args.getString(ARG_VIDEO_URL,  "");
        String thumbUrl  = args.getString(ARG_THUMB_URL,  "");
        int    duration  = args.getInt(ARG_DURATION,      0);

        // ── Owner label ───────────────────────────────────────────────────────
        android.widget.TextView tvOwner = view.findViewById(R.id.tv_rsp_owner);
        if (tvOwner != null && !ownerName.isEmpty()) {
            tvOwner.setText("@" + ownerName + "'s reel");
        }

        // Reconstruct a lightweight ReelModel for the listener callbacks
        ReelModel stub = buildStub(reelId, ownerUid, ownerName, videoUrl, thumbUrl, duration);

        // ── Remix row ──────────────────────────────────────────────────────────
        view.findViewById(R.id.layout_rsp_remix).setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onRemixSelected(stub);
        });

        // ── Sequence row ───────────────────────────────────────────────────────
        view.findViewById(R.id.layout_rsp_sequence).setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onSequenceSelected(stub);
        });

        // ── Cancel ────────────────────────────────────────────────────────────
        View btnCancel = view.findViewById(R.id.btn_rsp_cancel);
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dismiss());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ReelModel buildStub(String reelId, String ownerUid, String ownerName,
                                       String videoUrl, String thumbUrl, int duration) {
        ReelModel m = new ReelModel();
        m.reelId    = reelId;
        m.uid       = ownerUid;
        m.ownerName = ownerName;
        m.videoUrl  = videoUrl;
        m.thumbUrl  = thumbUrl;
        m.duration  = duration;
        return m;
    }
}
