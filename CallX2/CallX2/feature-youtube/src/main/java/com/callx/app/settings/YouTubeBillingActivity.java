package com.callx.app.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * YouTubeBillingActivity — Billing & Payments screen.
 */
public class YouTubeBillingActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_billing);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_billing);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Billing & Payments");
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String email = user != null && user.getEmail() != null ? user.getEmail() : "Not signed in";

        TextView tvAccount = findViewById(R.id.tv_yt_billing_account);
        if (tvAccount != null) tvAccount.setText(email);

        TextView tvPlan = findViewById(R.id.tv_yt_billing_plan);
        if (tvPlan != null) tvPlan.setText("Free — No active subscriptions");

        View btnAddPayment = findViewById(R.id.btn_yt_add_payment);
        if (btnAddPayment != null) {
            btnAddPayment.setOnClickListener(v ->
                Toast.makeText(this,
                    "Payment methods manage karne ke liye Google Pay ya YouTube website use karein.",
                    Toast.LENGTH_LONG).show());
        }

        View btnViewInvoices = findViewById(R.id.btn_yt_view_invoices);
        if (btnViewInvoices != null) {
            btnViewInvoices.setOnClickListener(v ->
                Toast.makeText(this,
                    "Invoices: payments.google.com pe dekho",
                    Toast.LENGTH_SHORT).show());
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
