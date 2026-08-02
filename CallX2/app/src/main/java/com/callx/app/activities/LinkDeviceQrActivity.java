package com.callx.app.activities;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.callx.app.R;
import com.callx.app.linkeddevices.LinkedDeviceRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ValueEventListener;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * Shown on the PRIMARY device (Settings → Linked Devices → "Link a Device").
 * Generates a short-lived pairing session, renders it as a QR code, and
 * listens in real time for a companion device to scan it — showing an
 * approve/deny dialog once one does.
 */
public class LinkDeviceQrActivity extends AppCompatActivity {

    private final LinkedDeviceRepository repository = new LinkedDeviceRepository();
    private ImageView ivQr;
    private ProgressBar progressQr;
    private TextView tvStatus;
    private String pairingId;
    private ValueEventListener pairingListener;
    private boolean dialogShowing = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_link_device_qr);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        ivQr = findViewById(R.id.iv_qr_code);
        progressQr = findViewById(R.id.progress_qr);
        tvStatus = findViewById(R.id.tv_status);
        View btnRefresh = findViewById(R.id.btn_refresh_qr);
        btnRefresh.setOnClickListener(v -> {
            btnRefresh.setVisibility(View.GONE);
            startNewPairingSession();
        });

        startNewPairingSession();
    }

    private void startNewPairingSession() {
        String primaryUid = currentUid();
        if (primaryUid == null) {
            Toast.makeText(this, "You need to be signed in to link a device", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        progressQr.setVisibility(View.VISIBLE);
        ivQr.setImageBitmap(null);
        stopListening();

        pairingId = repository.createPairingSession(primaryUid);
        renderQr("callx-link://" + primaryUid + "/" + pairingId);
        pairingListener = repository.watchPairingSession(pairingId, new LinkedDeviceRepository.PairingRequestListener() {
            @Override public void onCompanionRequested(String companionUid, String deviceName, String platform) {
                runOnUiThread(() -> showApprovalDialog(primaryUid, companionUid, deviceName, platform));
            }
            @Override public void onExpired() {
                runOnUiThread(() -> {
                    tvStatus.setText("This code expired for security. Generate a new one to continue.");
                    findViewById(R.id.btn_refresh_qr).setVisibility(View.VISIBLE);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(LinkDeviceQrActivity.this,
                    "Couldn't load pairing status: " + message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void renderQr(String content) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 800, 800);
            Bitmap bitmap = Bitmap.createBitmap(800, 800, Bitmap.Config.RGB_565);
            for (int x = 0; x < 800; x++) {
                for (int y = 0; y < 800; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            ivQr.setImageBitmap(bitmap);
        } catch (WriterException e) {
            Toast.makeText(this, "Couldn't generate QR code", Toast.LENGTH_SHORT).show();
        } finally {
            progressQr.setVisibility(View.GONE);
        }
    }

    private void showApprovalDialog(String primaryUid, String companionUid, String deviceName, String platform) {
        if (dialogShowing || isFinishing()) return;
        dialogShowing = true;
        String label = (deviceName == null || deviceName.isEmpty()) ? "A new device" : deviceName;
        String sub = platform == null ? "" : platform;
        new AlertDialog.Builder(this)
            .setTitle("Link this device?")
            .setMessage(label + (sub.isEmpty() ? "" : " (" + sub + ")") +
                " wants to link to your CallX account. It will be able to see your chats and send messages as you.")
            .setPositiveButton("Approve", (d, w) -> {
                String primaryName = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getDisplayName() : "";
                repository.approvePairing(pairingId, primaryUid, primaryName, companionUid,
                    deviceName, platform, appVersionName());
                Toast.makeText(this, "Device linked", Toast.LENGTH_SHORT).show();
                finish();
            })
            .setNegativeButton("Deny", (d, w) -> {
                repository.rejectPairing(pairingId);
                dialogShowing = false;
            })
            .setCancelable(false)
            .show();
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String currentUid() {
        return FirebaseAuth.getInstance().getCurrentUser() == null
            ? null : FirebaseAuth.getInstance().getCurrentUser().getUid();
    }

    private void stopListening() {
        if (pairingListener != null && pairingId != null) {
            repository.stopWatching(pairingId, pairingListener);
        }
        pairingListener = null;
    }

    @Override
    protected void onDestroy() {
        stopListening();
        super.onDestroy();
    }
}
