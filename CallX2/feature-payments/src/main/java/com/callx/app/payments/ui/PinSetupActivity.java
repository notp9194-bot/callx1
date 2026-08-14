package com.callx.app.payments.ui;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;

public class PinSetupActivity extends PaymentBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading("UPI PIN Setup");
        caption("Set or change the payment PIN used by the confirmation screen. The demo stores only a configured flag, never the PIN.");
        EditText first = field("New PIN (4–6 digits)", true);
        first.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText second = field("Confirm new PIN", true);
        second.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        Button save = primaryButton("Save PIN");
        save.setOnClickListener(v -> {
            String one = first.getText().toString();
            String two = second.getText().toString();
            if (one.length() < 4 || one.length() > 6 || !one.equals(two)) {
                toast("PIN must match and contain 4–6 digits");
                return;
            }
            paymentRepository.setPinConfigured(true, (value, error) -> {
                toast(error == null ? "PIN setup saved for demo mode" : error);
                finish();
            });
        });
    }
}