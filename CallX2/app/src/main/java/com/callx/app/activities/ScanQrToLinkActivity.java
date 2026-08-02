package com.callx.app.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.callx.app.R;
import com.callx.app.linkeddevices.DeviceInfoUtil;
import com.callx.app.linkeddevices.DeviceSessionManager;
import com.callx.app.linkeddevices.LinkedDeviceRepository;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ValueEventListener;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Launched from the "Link a Device" flow on a device that wants to become a
 * companion (i.e. this install's own account will be set aside and it will
 * instead mirror another CallX account in real time).
 */
public class ScanQrToLinkActivity extends AppCompatActivity {

    private static final Pattern QR_PATTERN = Pattern.compile("^callx-link://([^/]+)/(.+)$");

    private final LinkedDeviceRepository repository = new LinkedDeviceRepository();
    private final BarcodeScanner scanner = BarcodeScanning.getClient();
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private TextView tvStatus;
    private View progressScan;
    private boolean handledOneCode = false;
    private String pairingId;
    private ValueEventListener approvalListener;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_qr_to_link);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        tvStatus = findViewById(R.id.tv_scan_status);
        progressScan = findViewById(R.id.progress_scan);

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 9021);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9021 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission is needed to scan the QR code", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                PreviewView previewView = findViewById(R.id.preview_view);

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(this, selector, preview, analysis);
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't start camera", Toast.LENGTH_SHORT).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (handledOneCode || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        scanner.process(image)
            .addOnSuccessListener(this::onBarcodesDetected)
            .addOnFailureListener(e -> { })
            .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onBarcodesDetected(List<Barcode> barcodes) {
        if (handledOneCode) return;
        for (Barcode barcode : barcodes) {
            String raw = barcode.getRawValue();
            if (raw == null) continue;
            Matcher matcher = QR_PATTERN.matcher(raw);
            if (matcher.matches()) {
                handledOneCode = true;
                String primaryUid = matcher.group(1);
                pairingId = matcher.group(2);
                runOnUiThread(() -> beginPairing(primaryUid, pairingId));
                return;
            }
        }
    }

    private void beginPairing(String primaryUid, String pairingId) {
        tvStatus.setText("Signing in…");
        progressScan.setVisibility(View.VISIBLE);
        stopCamera();

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            submitRequest(primaryUid, pairingId);
        } else {
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener(result -> submitRequest(primaryUid, pairingId))
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Couldn't connect: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
        }
    }

    private void submitRequest(String primaryUid, String pairingId) {
        String companionUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String deviceName = DeviceInfoUtil.getDeviceName();
        String platform = DeviceInfoUtil.getPlatform();
        repository.submitCompanionRequest(pairingId, companionUid, deviceName, platform, appVersionName());

        tvStatus.setText("Waiting for approval on your other device…");
        approvalListener = repository.watchApproval(pairingId, new LinkedDeviceRepository.ApprovalListener() {
            @Override public void onApproved(String primaryUid, String primaryName) {
                runOnUiThread(() -> {
                    DeviceSessionManager.getInstance().enterCompanionMode(primaryUid, primaryName);
                    DeviceSessionManager.getInstance().watchForRevocation(null);
                    Toast.makeText(ScanQrToLinkActivity.this, "Linked! Loading chats…", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(ScanQrToLinkActivity.this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                });
            }
            @Override public void onRejected() {
                runOnUiThread(() -> {
                    Toast.makeText(ScanQrToLinkActivity.this, "The other device denied this request", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
            @Override public void onExpired() {
                runOnUiThread(() -> {
                    Toast.makeText(ScanQrToLinkActivity.this, "This pairing session expired", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ScanQrToLinkActivity.this, "Error: " + message, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void stopCamera() {
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    @Override
    protected void onDestroy() {
        stopCamera();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        scanner.close();
        if (approvalListener != null && pairingId != null) {
            // keep listening across this screen's lifecycle only — detach on destroy
        }
        super.onDestroy();
    }
}
