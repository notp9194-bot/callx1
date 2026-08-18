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
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.callx.app.R;
import com.callx.app.linkeddevice.LinkedDevice;
import com.callx.app.linkeddevice.LinkedDeviceManager;
import com.callx.app.linkeddevice.PairingSession;
import com.callx.app.linkeddevices.DeviceInfoUtil;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ValueEventListener;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * Shown on the device that wants to BECOME a companion/linked session
 * (Settings → Linked Devices → "Link this device").
 *
 * This matches WhatsApp's actual multi-device UX: the NEW device shows a QR
 * code, and the PRIMARY phone scans + approves it (via LinkedDevicesActivity
 * / DevicePairingScannerActivity — the same screen already used for linking
 * CallX2 Web). Once approved, a Cloud Function mints a real Firebase Auth
 * custom token for the primary's uid, and this device signs in with it —
 * becoming a fully native, independently-authenticated session of that same
 * account. No client-side uid substitution or "effective uid" plumbing is
 * needed anywhere else in the app: FirebaseAuth.getCurrentUser().getUid()
 * is simply correct from this point on, exactly like a second WhatsApp
 * device.
 */
public class LinkDeviceQrActivity extends AppCompatActivity {

    private final LinkedDeviceManager manager = LinkedDeviceManager.get();
    private ImageView ivQr;
    private ProgressBar progressQr;
    private TextView tvStatus;
    private String pairingCode;
    private ValueEventListener sessionListener;
    private boolean handledOnce = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_link_device_qr);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        ivQr = findViewById(R.id.iv_qr_code);
        progressQr = findViewById(R.id.progress_qr);
        tvStatus = findViewById(R.id.tv_status);
        tvStatus.setText("Scan this code with your primary phone: Linked Devices → link a device");
        View btnRefresh = findViewById(R.id.btn_refresh_qr);
        btnRefresh.setOnClickListener(v -> {
            btnRefresh.setVisibility(View.GONE);
            startNewPairingSession();
        });

        startNewPairingSession();
    }

    private void startNewPairingSession() {
        handledOnce = false;
        progressQr.setVisibility(View.VISIBLE);
        ivQr.setImageBitmap(null);
        stopListening();

        pairingCode = manager.createPairingSession(DeviceInfoUtil.getDeviceName(), DeviceInfoUtil.getOsVersion(),
            new LinkedDeviceManager.PairingCallback() {
                @Override public void onSuccess() {
                    runOnUiThread(() -> renderQr("callx2-pair:" + pairingCode));
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> Toast.makeText(LinkDeviceQrActivity.this,
                        "Couldn't start pairing: " + message, Toast.LENGTH_SHORT).show());
                }
            });

        sessionListener = manager.watchOwnPairingSession(pairingCode, new LinkedDeviceManager.PairingSessionCallback() {
            @Override public void onFound(PairingSession session) {
                if (handledOnce) return;
                handledOnce = true;
                runOnUiThread(() -> signInWithToken(session.customToken));
            }
            @Override public void onNotFound() { /* not yet written — ignore */ }
            @Override public void onExpired() {
                runOnUiThread(() -> {
                    tvStatus.setText("This code expired for security. Generate a new one to continue.");
                    findViewById(R.id.btn_refresh_qr).setVisibility(View.VISIBLE);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if ("Denied".equals(message)) {
                        tvStatus.setText("Linking was denied on your primary device.");
                        findViewById(R.id.btn_refresh_qr).setVisibility(View.VISIBLE);
                    } else {
                        Toast.makeText(LinkDeviceQrActivity.this,
                            "Couldn't load pairing status: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void signInWithToken(String customToken) {
        tvStatus.setText("Linked! Signing in…");
        progressQr.setVisibility(View.VISIBLE);
        FirebaseAuth.getInstance().signInWithCustomToken(customToken)
            .addOnSuccessListener(result -> {
                Toast.makeText(this, "Device linked", Toast.LENGTH_SHORT).show();
                startActivity(new android.content.Intent(this, MainActivity.class)
                    .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
            })
            .addOnFailureListener(e -> {
                progressQr.setVisibility(View.GONE);
                Toast.makeText(this, "Sign-in failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                findViewById(R.id.btn_refresh_qr).setVisibility(View.VISIBLE);
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

    private void stopListening() {
        if (sessionListener != null && pairingCode != null) {
            manager.stopWatchingOwnSession(pairingCode, sessionListener);
        }
        sessionListener = null;
    }

    @Override
    protected void onDestroy() {
        stopListening();
        super.onDestroy();
    }
}
