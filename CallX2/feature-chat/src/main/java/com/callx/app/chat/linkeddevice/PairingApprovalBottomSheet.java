package com.callx.app.chat.linkeddevice;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.chat.R;
import com.callx.app.linkeddevice.LinkedDeviceManager;
import com.callx.app.linkeddevice.PairingSession;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Shown right after the phone's camera decodes a pairing QR code from
 * web.callx2.app. Nothing is linked until the user explicitly taps
 * "Link Device" here — scanning alone never grants access.
 */
public class PairingApprovalBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "PairingApprovalBottomSheet";

    public interface Listener {
        void onLinked();
        void onCancelled();
    }

    private String pairingCode;
    private PairingSession session;
    private Listener listener;

    public static PairingApprovalBottomSheet newInstance(String pairingCode, PairingSession session) {
        PairingApprovalBottomSheet f = new PairingApprovalBottomSheet();
        f.pairingCode = pairingCode;
        f.session = session;
        return f;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_pairing_approval, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        TextView tvLabel = v.findViewById(R.id.tv_device_label);
        String label = session.deviceInfo != null && session.deviceInfo.label != null
                ? session.deviceInfo.label : "Unknown device";
        tvLabel.setText(label);

        v.findViewById(R.id.btn_deny).setOnClickListener(x -> {
            LinkedDeviceManager.get().denyPairing(pairingCode, new LinkedDeviceManager.PairingCallback() {
                @Override public void onSuccess() { }
                @Override public void onError(String message) { }
            });
            dismiss();
            if (listener != null) listener.onCancelled();
        });

        v.findViewById(R.id.btn_approve).setOnClickListener(x -> {
            v.findViewById(R.id.btn_approve).setEnabled(false);
            LinkedDeviceManager.get().approvePairing(pairingCode, session, new LinkedDeviceManager.PairingCallback() {
                @Override
                public void onSuccess() {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Device linked", Toast.LENGTH_SHORT).show();
                    dismiss();
                    if (listener != null) listener.onLinked();
                }

                @Override
                public void onError(String message) {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Couldn't link device: " + message, Toast.LENGTH_SHORT).show();
                    v.findViewById(R.id.btn_approve).setEnabled(true);
                }
            });
        });
    }

    @Override
    public void onCancel(@NonNull android.content.DialogInterface dialog) {
        super.onCancel(dialog);
        if (listener != null) listener.onCancelled();
    }
}
