package com.callx.app.payments.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import com.callx.app.payments.model.PaymentDraft;

public class SendMoneyActivity extends PaymentBaseActivity {
    private EditText contact;
    private EditText amount;
    private EditText note;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading("Send Money");
        caption("Choose a contact, enter an amount and review before confirming.");
        contact = field("Contact name or UPI ID", false);
        String prefilledName = getIntent().getStringExtra("counterpartyName");
        if (prefilledName != null) contact.setText(prefilledName);
        amount = field("Amount in INR", true);
        note = field("Note (optional)", false);
        Button next = primaryButton("Review Payment");
        next.setOnClickListener(v -> continueToConfirmation());
    }

    private void continueToConfirmation() {
        String recipient = contact.getText().toString().trim();
        long paise = amountPaise(amount.getText().toString());
        if (recipient.isEmpty()) {
            toast("Add a contact or UPI ID");
            return;
        }
        if (paise <= 0) {
            toast("Enter a valid amount");
            return;
        }
        Intent intent = new Intent(this, PaymentConfirmationActivity.class);
        intent.putExtra("type", "SEND");
        intent.putExtra("counterpartyName", recipient);
        intent.putExtra("counterpartyUid", getIntent().getStringExtra("counterpartyUid"));
        intent.putExtra("counterpartyUpi", getIntent().getStringExtra("counterpartyUpi"));
        intent.putExtra("amountPaise", paise);
        intent.putExtra("note", note.getText().toString().trim());
        intent.putExtra("chatId", getIntent().getStringExtra("chatId"));
        startActivity(intent);
    }
}