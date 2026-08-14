package com.callx.app.payments.api;

import android.os.Handler;
import android.os.Looper;

import com.callx.app.payments.model.PaymentDraft;

import java.util.Locale;

/**
 * Safe demo gateway. It never moves money; it returns a deterministic success
 * response so every payment screen can be exercised before backend wiring.
 */
public final class MockPaymentService implements PaymentService {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void execute(PaymentDraft draft, Callback callback) {
        final String reference = String.format(Locale.US, "CALLXMOCK%010d",
                Math.abs(System.currentTimeMillis() % 10_000_000_000L));
        mainHandler.postDelayed(() -> callback.onComplete(new GatewayResponse(
                true,
                "SUCCESS",
                reference,
                "Demo payment saved locally. Connect a gateway to process real money."
        )), 350L);
    }
}