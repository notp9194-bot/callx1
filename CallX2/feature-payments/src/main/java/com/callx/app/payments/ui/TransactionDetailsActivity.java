package com.callx.app.payments.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

import com.callx.app.db.entity.PaymentTransactionEntity;

import java.util.concurrent.Executors;

public class TransactionDetailsActivity extends PaymentBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading("Transaction Details");
        caption("Reference, status and payment metadata are shown here.");
        LinearLayout details = card();
        String id = getIntent().getStringExtra("transactionId");
        Executors.newSingleThreadExecutor().execute(() -> {
            PaymentTransactionEntity transaction = paymentRepository.getTransaction(id);
            runOnUiThread(() -> {
                if (transaction == null) {
                    addCardText(details, "Payment not found", "It may not have synced to this device yet.");
                    return;
                }
                addCardText(details, "Amount", rupees(transaction.amountPaise));
                addCardText(details, "Counterparty", transaction.counterpartyName);
                addCardText(details, "Type", transaction.type);
                addCardText(details, "Status", transaction.status);
                addCardText(details, "UTR / Reference ID", transaction.referenceId);
                addCardText(details, "Date", new java.text.SimpleDateFormat(
                        "dd MMM yyyy, hh:mm a", java.util.Locale.getDefault())
                        .format(new java.util.Date(transaction.createdAt)));
                if (transaction.note != null && !transaction.note.isEmpty())
                    addCardText(details, "Note", transaction.note);
            });
        });
        Button share = outlineButton("Share Receipt");
        share.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, "CallX payment reference: " + id);
            startActivity(Intent.createChooser(intent, "Share receipt"));
        });
    }
}