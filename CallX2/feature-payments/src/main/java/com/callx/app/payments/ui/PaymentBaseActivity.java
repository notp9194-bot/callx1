package com.callx.app.payments.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.payments.repository.PaymentRepository;
import com.callx.app.payments.repository.PaymentRepositoryProvider;

/**
 * Shared visual shell for the payment flow. Keeping this in the feature module
 * means gateway integrations do not leak into the screen layer.
 */
public abstract class PaymentBaseActivity extends AppCompatActivity {
    protected PaymentRepository paymentRepository;
    protected LinearLayout page;
    protected LinearLayout content;

    protected static final int BG = Color.rgb(7, 27, 22);
    protected static final int SURFACE = Color.rgb(15, 43, 34);
    protected static final int SURFACE_ALT = Color.rgb(24, 58, 46);
    protected static final int ACCENT = Color.rgb(49, 202, 126);
    protected static final int TEXT = Color.rgb(244, 255, 249);
    protected static final int MUTED = Color.rgb(174, 207, 192);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        paymentRepository = PaymentRepositoryProvider.get(this);
        buildShell(getIntent().getStringExtra("title"));
    }

    private void buildShell(String title) {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(8), dp(16), dp(8));
        toolbar.setBackgroundColor(SURFACE);

        TextView back = label("‹", 34, TEXT);
        back.setGravity(Gravity.CENTER);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        back.setOnClickListener(v -> finish());

        TextView titleView = label(title == null ? "Payments" : title, 20, TEXT);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    protected TextView label(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    protected TextView heading(String value) {
        TextView view = label(value, 22, TEXT);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(8), 0, dp(10));
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    protected TextView caption(String value) {
        TextView view = label(value, 14, MUTED);
        view.setPadding(0, 0, 0, dp(14));
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    protected EditText field(String hint, boolean numeric) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(Color.rgb(126, 163, 146));
        input.setTextColor(TEXT);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(round(SURFACE_ALT, 14));
        if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
        params.setMargins(0, 0, 0, dp(12));
        content.addView(input, params);
        return input;
    }

    protected Button primaryButton(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(15);
        button.setTextColor(BG);
        button.setAllCaps(false);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackground(round(ACCENT, 14));
        button.setMinHeight(dp(52));
        button.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
        params.setMargins(0, dp(4), 0, dp(12));
        content.addView(button, params);
        return button;
    }

    protected Button outlineButton(String title) {
        Button button = primaryButton(title);
        button.setTextColor(TEXT);
        button.setBackground(stroke(ACCENT, 14));
        return button;
    }

    protected LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(SURFACE, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(14));
        content.addView(card, params);
        return card;
    }

    protected void addCardText(LinearLayout card, String title, String subtitle) {
        TextView primary = label(title, 16, TEXT);
        primary.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(primary, new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView secondary = label(subtitle, 13, MUTED);
            secondary.setPadding(0, dp(5), 0, 0);
            card.addView(secondary, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    protected long amountPaise(String raw) {
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(raw.trim());
            return value.movePointRight(2).longValueExact();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    protected String rupees(long paise) {
        return "₹" + new java.text.DecimalFormat("#,##0.00").format(paise / 100.0);
    }

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    protected GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    protected GradientDrawable stroke(int color, int radiusDp) {
        GradientDrawable drawable = round(Color.TRANSPARENT, radiusDp);
        drawable.setStroke(dp(1), color);
        return drawable;
    }

    protected void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    protected void open(Class<?> destination) {
        startActivity(new Intent(this, destination));
    }

    protected void openWithExtras(Class<?> destination, Intent extras) {
        extras.setClass(this, destination);
        startActivity(extras);
    }
}