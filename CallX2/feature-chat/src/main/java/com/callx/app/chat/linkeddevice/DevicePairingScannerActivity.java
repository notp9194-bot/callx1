package com.callx.app.chat.linkeddevice;

import com.callx.app.chat.R;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/**
 * Thin wrapper so the QR scanner for device pairing uses CallX2's own
 * full-screen layout (title banner) instead of zxing's default chrome.
 *
 * CaptureActivity (zxing-android-embedded 4.3.0) does not expose a
 * getLayoutId() hook — that method doesn't exist on the supertype, which is
 * why compilation failed with "method does not override or implement a
 * method from a supertype". The correct extension point is
 * initializeContent(): set the custom content view yourself and return the
 * DecoratedBarcodeView instance from it (must match the id used in the
 * layout: zxing_barcode_scanner).
 */
public class DevicePairingScannerActivity extends CaptureActivity {

    @Override
    protected DecoratedBarcodeView initializeContent() {
        setContentView(R.layout.activity_device_pairing_scanner);
        return findViewById(R.id.zxing_barcode_scanner);
    }
}
