package com.callx.app.payments.ui;

import android.os.Bundle;
import android.widget.Button;

public class PaymentSettingsActivity extends PaymentBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading("Payment Settings");
        caption("Manage the payment surface without coupling it to a provider.");
        Button accounts = outlineButton("Linked Accounts");
        accounts.setOnClickListener(v -> open(BankAccountActivity.class));
        Button pin = outlineButton("Security • Setup / Change PIN");
        pin.setOnClickListener(v -> open(PinSetupActivity.class));
        Button help = outlineButton("Payment Help");
        help.setOnClickListener(v -> toast("Payment support will be connected here."));
        Button gateway = primaryButton("Gateway status: Demo");
        gateway.setOnClickListener(v -> toast("Replace MockPaymentService when UPI or bank API is ready."));
    }
}