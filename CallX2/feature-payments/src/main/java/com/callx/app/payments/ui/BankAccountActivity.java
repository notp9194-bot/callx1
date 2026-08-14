package com.callx.app.payments.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.callx.app.db.entity.PaymentAccountEntity;

public class BankAccountActivity extends PaymentBaseActivity {
    private LinearLayout accounts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading("Bank Account / UPI");
        caption("Link a default account for future payouts. Demo mode stores only masked details.");
        EditText bank = field("Bank name", false);
        EditText last4 = field("Account last 4 digits", true);
        EditText upi = field("UPI ID (optional)", false);
        Button link = primaryButton("Link Demo Account");
        link.setOnClickListener(v -> {
            if (bank.getText().toString().trim().isEmpty()
                    && upi.getText().toString().trim().isEmpty()) {
                toast("Add a bank name or UPI ID");
                return;
            }
            paymentRepository.linkAccount(
                    bank.getText().toString().trim(),
                    last4.getText().toString().trim(),
                    upi.getText().toString().trim(),
                    (account, error) -> toast(error == null ? "Account linked" : error)
            );
            bank.setText("");
            last4.setText("");
            upi.setText("");
        });

        TextView label = label("Linked accounts", 18, TEXT);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(label, new LinearLayout.LayoutParams(-1, dp(44)));
        accounts = new LinearLayout(this);
        accounts.setOrientation(LinearLayout.VERTICAL);
        content.addView(accounts, new LinearLayout.LayoutParams(-1, -2));
        paymentRepository.observeAccounts().observe(this, this::renderAccounts);
    }

    private void renderAccounts(java.util.List<PaymentAccountEntity> values) {
        accounts.removeAllViews();
        if (values == null || values.isEmpty()) {
            accounts.addView(label("No linked account yet.", 14, MUTED));
            return;
        }
        for (PaymentAccountEntity account : values) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setBackground(round(SURFACE, 18));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, 0, 0, dp(12));
            accounts.addView(row, rowParams);
            addCardText(row,
                    account.upiId == null || account.upiId.isEmpty() ? account.bankName : account.upiId,
                    (account.maskedAccount == null ? "" : account.maskedAccount + "  •  ")
                            + (account.isDefault ? "Default" : account.status));
        }
    }
}