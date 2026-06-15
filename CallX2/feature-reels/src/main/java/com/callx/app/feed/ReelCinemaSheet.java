package com.callx.app.feed;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.reels.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ReelCinemaSheet
 * ───────────────
 * Opened via long-press on the reel player.
 * Has one button: "Cinema Mode — Hide UI" / "Restore UI".
 *
 * State is stored per-reel in the calling fragment via a reelId-keyed Set,
 * so scrolling away and back to the same reel remembers the hidden state.
 */
public class ReelCinemaSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelCinemaSheet";

    private static final String ARG_IS_HIDDEN = "is_hidden";

    public interface Listener {
        /** Called when user taps the cinema toggle button. */
        void onCinemaToggle();
    }

    private Listener listener;
    private boolean  isCurrentlyHidden;

    public static ReelCinemaSheet newInstance(boolean isCurrentlyHidden) {
        ReelCinemaSheet sheet = new ReelCinemaSheet();
        Bundle args = new Bundle();
        args.putBoolean(ARG_IS_HIDDEN, isCurrentlyHidden);
        sheet.setArguments(args);
        return sheet;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            isCurrentlyHidden = getArguments().getBoolean(ARG_IS_HIDDEN, false);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reel_cinema, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        LinearLayout btnCinema = view.findViewById(R.id.btn_cinema_mode);
        TextView tvLabel       = view.findViewById(R.id.tv_cinema_label);
        TextView tvHint        = view.findViewById(R.id.tv_cinema_hint);

        // Update label based on current state
        if (isCurrentlyHidden) {
            tvLabel.setText("Restore UI");
            tvHint.setText("Long press to hide again");
        } else {
            tvLabel.setText("Cinema Mode — Hide UI");
            tvHint.setText("Long press to restore");
        }

        btnCinema.setOnClickListener(v -> {
            if (listener != null) listener.onCinemaToggle();
            dismissAllowingStateLoss();
        });
    }
}
