package com.callx.app.chat.linkeddevice;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.linkeddevice.LinkedDevice;
import com.callx.app.linkeddevice.LinkedDeviceManager;
import com.callx.app.linkeddevice.PairingSession;
import com.google.firebase.database.ValueEventListener;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.List;

/**
 * Entry point for "Use CallX2 on the web": lists currently linked companion
 * sessions and lets the user scan a new pairing QR shown at web.callx2.app
 * (see /callx2-web/callx2-web.html), or revoke access from any device.
 */
public class LinkedDevicesActivity extends AppCompatActivity {

    /** QR payload convention: "callx2-pair:<8-char code>" — keeps the raw pairing code out of arbitrary QR readers. */
    private static final String PAIR_PREFIX = "callx2-pair:";

    private LinkedDeviceAdapter adapter;
    private ValueEventListener deviceListener;
    private View emptyState;
    private View logoutAllBtn;

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() == null) return; // user cancelled
                handleScannedPayload(result.getContents());
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_linked_devices);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_linked_devices);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LinkedDeviceAdapter(this::showDeviceMenu);
        rv.setAdapter(adapter);

        emptyState = findViewById(R.id.layout_empty_state);
        logoutAllBtn = findViewById(R.id.btn_logout_all);

        findViewById(R.id.btn_link_device).setOnClickListener(v -> launchScanner());
        logoutAllBtn.setOnClickListener(v -> confirmLogoutAll());
    }

    @Override
    protected void onStart() {
        super.onStart();
        deviceListener = LinkedDeviceManager.get().listenDevices(this::onDevicesChanged);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LinkedDeviceManager.get().stopListening(deviceListener);
    }

    private void onDevicesChanged(List<LinkedDevice> devices) {
        adapter.submitList(devices);
        emptyState.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        logoutAllBtn.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void launchScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan the QR code shown on web.callx2.app");
        options.setBeepEnabled(false);
        options.setOrientationLocked(true);
        options.setCaptureActivity(DevicePairingScannerActivity.class);
        scanLauncher.launch(options);
    }

    private void handleScannedPayload(String payload) {
        if (payload == null || !payload.startsWith(PAIR_PREFIX)) {
            Toast.makeText(this, "That doesn't look like a CallX2 Web pairing code", Toast.LENGTH_SHORT).show();
            return;
        }
        String pairingCode = payload.substring(PAIR_PREFIX.length()).trim();
        if (pairingCode.isEmpty()) {
            Toast.makeText(this, "Invalid pairing code", Toast.LENGTH_SHORT).show();
            return;
        }

        LinkedDeviceManager.get().lookupPairingSession(pairingCode, new LinkedDeviceManager.PairingSessionCallback() {
            @Override
            public void onFound(PairingSession session) {
                if (!PairingSession.STATUS_PENDING.equals(session.status)) {
                    Toast.makeText(LinkedDevicesActivity.this,
                            "This code has already been used", Toast.LENGTH_SHORT).show();
                    return;
                }
                PairingApprovalBottomSheet sheet = PairingApprovalBottomSheet.newInstance(pairingCode, session);
                sheet.show(getSupportFragmentManager(), PairingApprovalBottomSheet.TAG);
            }

            @Override
            public void onNotFound() {
                Toast.makeText(LinkedDevicesActivity.this,
                        "Code not found — the QR on web.callx2.app may have refreshed. Try scanning again.",
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onExpired() {
                Toast.makeText(LinkedDevicesActivity.this,
                        "This QR code expired. Refresh web.callx2.app and scan again.", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(LinkedDevicesActivity.this, "Error: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showDeviceMenu(LinkedDevice device, View anchor) {
        new AlertDialog.Builder(this)
                .setTitle(device.deviceName)
                .setItems(new CharSequence[]{"Log out from this device"}, (dialog, which) -> {
                    LinkedDeviceManager.get().revokeDevice(device.deviceId, new LinkedDeviceManager.PairingCallback() {
                        @Override
                        public void onSuccess() {
                            Toast.makeText(LinkedDevicesActivity.this, "Logged out", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(LinkedDevicesActivity.this, "Failed: " + message, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .show();
    }

    private void confirmLogoutAll() {
        new AlertDialog.Builder(this)
                .setTitle("Log out from all devices?")
                .setMessage("You'll need to scan a QR code again to use CallX2 on the web.")
                .setPositiveButton("Log Out All", (d, w) ->
                        LinkedDeviceManager.get().revokeAllDevices(new LinkedDeviceManager.PairingCallback() {
                            @Override
                            public void onSuccess() {
                                Toast.makeText(LinkedDevicesActivity.this,
                                        "Logged out from all devices", Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(LinkedDevicesActivity.this,
                                        "Failed: " + message, Toast.LENGTH_SHORT).show();
                            }
                        }))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
