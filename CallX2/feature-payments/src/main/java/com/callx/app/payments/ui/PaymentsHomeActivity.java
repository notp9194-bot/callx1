package com.callx.app.payments.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class PaymentsHomeActivity extends PaymentBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading("Payments");
        caption("Send, request and track money inside your chats. Demo mode is enabled until a gateway is connected.");

        LinearLayout balance = card();
        TextView overline = label("AVAILABLE BALANCE", 12, MUTED);
        balance.addView(overline);
        TextView amount = label("₹0.00", 30, TEXT);
        amount.setTypeface(null, android.graphics.Typeface.BOLD);
        amount.setPadding(0, dp(5), 0, dp(2));
        balance.addView(amount);
        TextView demo = label("Demo balance • no real money is moved", 12, Color.rgb(130, 225, 170));
        balance.addView(demo);

        primaryButton("Send Money").setOnClickListener(v -> open(SendMoneyActivity.class));
        outlineButton("Request Money").setOnClickListener(v -> open(RequestMoneyActivity.class));
        outlineButton("Scan QR").setOnClickListener(v -> open(ScanQrActivity.class));
        outlineButton("Transaction History").setOnClickListener(v -> open(TransactionHistoryActivity.class));
        outlineButton("Bank Account / UPI").setOnClickListener(v -> open(BankAccountActivity.class));
        outlineButton("Payment Settings").setOnClickListener(v -> open(PaymentSettingsActivity.class));
    }
}