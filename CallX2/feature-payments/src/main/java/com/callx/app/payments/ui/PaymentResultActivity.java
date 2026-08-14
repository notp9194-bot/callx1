package com.callx.app.payments.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

public class PaymentResultActivity extends PaymentBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean success = "SUCCESS".equals(getIntent().getStringExtra("status"));
        heading(success ? "Payment Successful" : "Payment Failed");
        caption(success
                ? "This is a mock confirmation. No real money was transferred."
                : "The payment could not be completed.");

        LinearLayout receipt = card();
        addCardText(receipt,
                rupees(getIntent().getLongExtra("amountPaise", 0L)),
                (success ? "To " : "Attempted for ")
                        + getIntent().getStringExtra("counterpartyName"));
        addCardText(receipt, "Reference ID",
                getIntent().getStringExtra("referenceId") == null
                        ? "Not available" : getIntent().getStringExtra("referenceId"));
        String serviceMessage = getIntent().getStringExtra("message");
        if (serviceMessage != null) addCardText(receipt, "Demo gateway", serviceMessage);

        Button share = outlineButton("Share Receipt");
        share.setOnClickListener(v -> shareReceipt());
        Button done = primaryButton("Done");
        done.setOnClickListener(v -> {
            Intent home = new Intent(this, PaymentsHomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(home);
            finish();
        });
    }

    private void shareReceipt() {
        String text = "CallX payment receipt\nAmount: "
                + rupees(getIntent().getLongExtra("amountPaise", 0L))
                + "\nReference: " + getIntent().getStringExtra("referenceId");
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, "Share receipt"));
    }
}