package com.callx.app.chat.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.callx.app.chat.R;
import com.callx.app.utils.E2EEncryptionManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * EncryptionDetailsBottomSheet — WhatsApp-style "safety number" screen,
 * opened from ChatSecurityBottomSheet's new Encryption row.
 *
 * Shows a single combined safety number (both participants' identity keys
 * hashed together, sorted so ordering doesn't matter — see
 * E2EEncryptionManager#getSafetyNumber) plus each side's individual key
 * fingerprint, and lets the person mark the conversation "Verified" once
 * they've compared the number with their contact out-of-band (in person,
 * or over a different trusted channel). This is the actual MITM check: a
 * per-device fingerprint alone can't prove the server didn't also hand
 * THIS device a spoofed partner key — only an independent side-channel
 * comparison can.
 *
 * Also exposes "Reset Security Code" (evictSharedKey) for the case the
 * person wants to force a brand-new X3DH handshake — e.g. after
 * confirming a genuine device change with their contact.
 */
public class EncryptionDetailsBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "EncryptionDetailsBottomSheet";

    private static final String ARG_PARTNER_UID  = "partnerUid";
    private static final String ARG_PARTNER_NAME = "partnerName";

    private String partnerUid;
    private String partnerName;
    private E2EEncryptionManager e2e;

    private TextView tvVerifyLabel;
    private View rowVerifiedBadge;

    public static EncryptionDetailsBottomSheet newInstance(String partnerUid, String partnerName) {
        EncryptionDetailsBottomSheet f = new EncryptionDetailsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_PARTNER_UID, partnerUid);
        args.putString(ARG_PARTNER_NAME, partnerName);
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                              @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_encryption_details, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Bundle args = getArguments();
        partnerUid  = args != null ? args.getString(ARG_PARTNER_UID) : null;
        partnerName = args != null ? args.getString(ARG_PARTNER_NAME) : null;
        e2e = E2EEncryptionManager.getInstance(requireContext());

        TextView tvPartnerName        = v.findViewById(R.id.tv_partner_name);
        TextView tvSafetyNumber       = v.findViewById(R.id.tv_safety_number);
        TextView tvOurFingerprint     = v.findViewById(R.id.tv_our_fingerprint);
        TextView tvPartnerFingerprint = v.findViewById(R.id.tv_partner_fingerprint);
        View btnVerify                = v.findViewById(R.id.btn_verify);
        View btnResetSession          = v.findViewById(R.id.btn_reset_session);
        tvVerifyLabel                 = v.findViewById(R.id.tv_verify_label);
        rowVerifiedBadge              = v.findViewById(R.id.row_verified_badge);

        String who = (partnerName != null && !partnerName.isEmpty()) ? partnerName : "this contact";
        tvPartnerName.setText("with " + who);

        if (partnerUid == null || partnerUid.isEmpty()) {
            tvSafetyNumber.setText("Encryption not set up yet");
            btnVerify.setVisibility(View.GONE);
            btnResetSession.setVisibility(View.GONE);
            return;
        }

        refreshDisplay(tvSafetyNumber, tvOurFingerprint, tvPartnerFingerprint);

        btnVerify.setOnClickListener(x -> {
            boolean nowVerified = !e2e.isVerified(partnerUid);
            e2e.setVerified(partnerUid, nowVerified);
            refreshVerifiedState();
            Toast.makeText(requireContext(),
                    nowVerified ? "Marked as verified" : "Verification removed",
                    Toast.LENGTH_SHORT).show();
        });

        btnResetSession.setOnClickListener(x -> confirmResetSession());
    }

    private void refreshDisplay(TextView tvSafetyNumber, TextView tvOurFingerprint, TextView tvPartnerFingerprint) {
        String safetyNumber = e2e.getSafetyNumber(partnerUid);
        tvSafetyNumber.setText(safetyNumber != null ? safetyNumber : "Not established yet — send or receive a message first");

        tvOurFingerprint.setText("Your key:   " + e2e.getOurPublicKeyFingerprint());
        tvPartnerFingerprint.setText("Their key:  " + e2e.getPartnerPublicKeyFingerprint(partnerUid));

        refreshVerifiedState();
    }

    private void refreshVerifiedState() {
        boolean verified = e2e.isVerified(partnerUid);
        rowVerifiedBadge.setVisibility(verified ? View.VISIBLE : View.GONE);
        if (tvVerifyLabel != null) {
            tvVerifyLabel.setText(verified ? "✕  Remove Verification" : "✓  Mark as Verified");
        }
    }

    private void confirmResetSession() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Reset Security Code?")
                .setMessage("This starts a brand-new encrypted session with " +
                        (partnerName != null && !partnerName.isEmpty() ? partnerName : "this contact") +
                        ". Only do this if you've confirmed a device or code change with them directly — " +
                        "otherwise you could be resetting past a genuine warning.")
                .setPositiveButton("Reset", (d, w) -> {
                    e2e.evictSharedKey(partnerUid);
                    e2e.setVerified(partnerUid, false);
                    Toast.makeText(requireContext(), "Security code reset", Toast.LENGTH_SHORT).show();
                    dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
