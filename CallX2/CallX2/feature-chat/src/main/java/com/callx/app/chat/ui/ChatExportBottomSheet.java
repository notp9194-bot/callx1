package com.callx.app.chat.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.chat.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ChatExportBottomSheet — "Export chat" picker shown from Chat 3-dot menu.
 * Mirrors WhatsApp's "Without media" / "Include media" choice; the actual
 * file building + share-sheet work happens in ChatExportController so this
 * class stays a thin picker UI, consistent with the other bottom sheets.
 */
public class ChatExportBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChatExportBottomSheet";

    /** Callback invoked with true = include media, false = text only. */
    public interface Listener {
        void onExportChoice(boolean includeMedia);
    }

    private Listener listener;

    public static ChatExportBottomSheet newInstance(Listener listener) {
        ChatExportBottomSheet sheet = new ChatExportBottomSheet();
        sheet.listener = listener;
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                              @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_export_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        v.findViewById(R.id.action_export_with_media).setOnClickListener(x -> {
            dismiss();
            if (listener != null) listener.onExportChoice(true);
        });

        v.findViewById(R.id.action_export_without_media).setOnClickListener(x -> {
            dismiss();
            if (listener != null) listener.onExportChoice(false);
        });

        v.findViewById(R.id.action_export_cancel).setOnClickListener(x -> dismiss());
    }

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        // Allow re-attaching listener after rotation if host activity implements it.
        if (listener == null && context instanceof Activity
                && context instanceof Listener) {
            listener = (Listener) context;
        }
    }
}
