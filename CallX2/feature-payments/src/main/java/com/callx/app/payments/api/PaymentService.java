package com.callx.app.payments.api;

import com.callx.app.payments.model.PaymentDraft;

/**
 * Gateway boundary for UPI, bank rails or a payment gateway.
 *
 * The UI and repository never know which provider is behind this interface.
 * Replace MockPaymentService with an authenticated implementation when the API
 * contract is ready.
 */
public interface PaymentService {
    void execute(PaymentDraft draft, Callback callback);

    interface Callback {
        void onComplete(GatewayResponse response);
    }

    final class GatewayResponse {
        public final boolean success;
        public final String status;
        public final String referenceId;
        public final String message;

        public GatewayResponse(boolean success, String status, String referenceId, String message) {
            this.success = success;
            this.status = status;
            this.referenceId = referenceId;
            this.message = message;
        }
    }
}