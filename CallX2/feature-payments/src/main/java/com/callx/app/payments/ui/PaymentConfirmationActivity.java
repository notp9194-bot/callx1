package com.callx.app.payments.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.callx.app.db.entity.PaymentTransactionEntity;
import com.callx.app.payments.model.PaymentDraft;

public class PaymentConfirmationActivity extends PaymentBaseActivity {
    private EditText pin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean request = "REQUEST".equals(getIntent().getStringExtra("type"));
        long amountPaise = getIntent().getLongExtra("amountPaise", 0L);
        String name = getIntent().getStringExtra("counterpartyName");
        heading(request ? "Confirm Request" : "Confirm Payment");

        LinearLayout summary = card();
        addCardText(summary, request ? "Requesting from " + name : "Sending to " + name,
                rupees(amountPaise) + (getIntent().getStringExtra("note") == null
                        ? "" : "\n" + getIntent().getStringExtra("note")));
        caption("For demo mode, enter any 4–6 digit PIN. The PIN is not stored.");
        pin = field("UPI PIN / demo PIN", true);
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        Button setup = outlineButton("Set up or change PIN");
        setup.setOnClickListener(v -> open(PinSetupActivity.class));
        Button confirm = primaryButton(request ? "Create Request" : "Confirm Payment");
        confirm.setOnClickListener(v -> confirmPayment());
    }

    private void confirmPayment() {
        String pinValue = pin.getText().toString().trim();
        if (pinValue.length() < 4 || pinValue.length() > 6) {
            toast("Enter a 4–6 digit PIN");
            return;
        }
        PaymentDraft draft = new PaymentDraft(
                getIntent().getStringExtra("type"),
                getIntent().getStringExtra("counterpartyUid"),
                getIntent().getStringExtra("counterpartyName"),
                getIntent().getStringExtra("counterpartyUpi"),
                getIntent().getLongExtra("amountPaise", 0L),
                getIntent().getStringExtra("note"),
                getIntent().getStringExtra("chatId")
        );
        paymentRepository.createTransaction(draft, (transaction, error) -> {
            if (transaction == null) {
                toast(error == null ? "Payment failed" : error);
                return;
            }
            Intent result = new Intent(this, PaymentResultActivity.class);
            result.putExtra("transactionId", transaction.id);
            result.putExtra("status", transaction.status);
            result.putExtra("referenceId", transaction.referenceId);
            result.putExtra("amountPaise", transaction.amountPaise);
            result.putExtra("counterpartyName", transaction.counterpartyName);
            result.putExtra("message", error);
            startActivity(result);
            finish();
        });
    }
}