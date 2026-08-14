package com.callx.app.payments.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class ScanQrActivity extends PaymentBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading("Scan QR");
        caption("Scan a UPI QR or select a QR image from your gallery.");

        LinearLayout scannerCard = card();
        scannerCard.setGravity(Gravity.CENTER);
        TextView frame = label("QR\nSCAN", 28, ACCENT);
        frame.setGravity(Gravity.CENTER);
        frame.setBackground(stroke(ACCENT, 20));
        scannerCard.addView(frame, new LinearLayout.LayoutParams(-1, dp(220)));
        TextView hint = label("Camera access is used only while scanning.", 13, MUTED);
        hint.setGravity(Gravity.CENTER);
        scannerCard.addView(hint, new LinearLayout.LayoutParams(-1, dp(42)));

        Button scan = primaryButton("Open QR Scanner");
        scan.setOnClickListener(v -> openScanner());
        Button gallery = outlineButton("Choose QR from Gallery");
        gallery.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            startActivityForResult(Intent.createChooser(pick, "Choose QR image"), 7001);
        });
    }

    private void openScanner() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE_TYPES)
                .setPrompt("Scan payment QR")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IntentIntegrator.REQUEST_CODE) {
            IntentResult result = IntentIntegrator.parseActivityResult(resultCode, data);
            if (result != null && result.getContents() != null) {
                openPaymentForQr(result.getContents());
            }
        } else if (requestCode == 7001 && data != null && data.getData() != null) {
            toast("QR image selected. QR parsing will be connected to the gateway API.");
            openPaymentForQr(data.getData().toString());
        }
    }

    private void openPaymentForQr(String rawValue) {
        Intent intent = new Intent(this, SendMoneyActivity.class);
        intent.putExtra("counterpartyName", rawValue.startsWith("upi://")
                ? "Scanned UPI contact" : "Scanned QR contact");
        intent.putExtra("counterpartyUpi", rawValue);
        startActivity(intent);
    }
}