package com.callx.app.chat.linkeddevice;

import com.callx.app.chat.R;
import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * Thin wrapper so the QR scanner for device pairing uses CallX2's own
 * full-screen layout (title banner) instead of zxing's default chrome.
 */
public class DevicePairingScannerActivity extends CaptureActivity {

    @Override
    protected int getLayoutId() {
        return R.layout.activity_device_pairing_scanner;
    }
}
