package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.status.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * CreateChannelInfoSheet — WhatsApp-style info bottom sheet shown before
 * opening CreateChannelActivity. Matches screenshot 4.
 *
 * Shows:
 *  • "Create a channel to reach unlimited followers"
 *  • Three key points (discover, privacy, responsibility)
 *  • "Continue" button → opens CreateChannelActivity
 */
public class CreateChannelInfoSheet extends BottomSheetDialogFragment {

    public static final String TAG = "CreateChannelInfoSheet";

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_channel_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Button btnContinue = view.findViewById(R.id.btn_channel_info_continue);
        if (btnContinue != null) {
            btnContinue.setOnClickListener(v -> {
                dismiss();
                if (getActivity() != null)
                    getActivity().startActivity(
                        new Intent(requireActivity(), CreateChannelActivity.class));
            });
        }
    }
}
