package com.callx.app.payments.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.callx.app.db.entity.PaymentTransactionEntity;

import java.util.ArrayList;
import java.util.List;

public class TransactionHistoryActivity extends PaymentBaseActivity {
    private LinearLayout list;
    private List<PaymentTransactionEntity> all = new ArrayList<>();
    private String filter = "ALL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading("Transaction History");
        caption("Your local-first payment ledger. Filters are ready for gateway statuses.");

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(0, 0, 0, dp(12));
        content.addView(filters, new LinearLayout.LayoutParams(-1, dp(50)));
        addFilter(filters, "All", "ALL");
        addFilter(filters, "Sent", "SEND");
        addFilter(filters, "Requests", "REQUEST");

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        paymentRepository.observeTransactions().observe(this, values -> {
            all = values == null ? new ArrayList<>() : values;
            render();
        });
    }

    private void addFilter(LinearLayout parent, String title, String value) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setBackground(stroke(ACCENT, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
        params.setMargins(0, 0, dp(6), 0);
        parent.addView(button, params);
        button.setOnClickListener(v -> {
            filter = value;
            render();
        });
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        int shown = 0;
        for (PaymentTransactionEntity transaction : all) {
            if (!"ALL".equals(filter) && !filter.equals(transaction.type)) continue;
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(15), dp(13), dp(15), dp(13));
            row.setBackground(round(SURFACE, 16));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, 0, 0, dp(10));
            list.addView(row, params);
            TextView top = label(("REQUEST".equals(transaction.type) ? "Request from " : "Payment to ")
                    + (transaction.counterpartyName == null ? "Contact" : transaction.counterpartyName),
                    16, TEXT);
            top.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(top);
            TextView bottom = label(rupees(transaction.amountPaise) + "  •  "
                    + transaction.status + "\n" + formatDate(transaction.createdAt),
                    13, transaction.status.equals("SUCCESS") ? Color.rgb(130, 225, 170) : MUTED);
            bottom.setPadding(0, dp(5), 0, 0);
            row.addView(bottom);
            row.setOnClickListener(v -> {
                android.content.Intent intent = new android.content.Intent(this, TransactionDetailsActivity.class);
                intent.putExtra("transactionId", transaction.id);
                startActivity(intent);
            });
        }
        if (shown == 0) {
            TextView empty = label("No payments yet. Try the demo Send Money flow.", 15, MUTED);
            empty.setPadding(0, dp(18), 0, dp(18));
            list.addView(empty);
        }
    }

    private String formatDate(long millis) {
        return new java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a",
                java.util.Locale.getDefault()).format(new java.util.Date(millis));
    }
}