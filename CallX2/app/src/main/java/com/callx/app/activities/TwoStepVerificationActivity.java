package com.callx.app.activities;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.R;
import com.callx.app.utils.SecurityManager;

/**
 * Two-Step Verification — fully implemented with PBKDF2 PIN hashing.
 * Modes: setup (if not enabled), change, disable, verify.
 */
public class TwoStepVerificationActivity extends AppCompatActivity {

    public static final String EXTRA_MODE_VERIFY = "verify"; // unlock mode

    private SecurityManager secMgr;
    private boolean verifyMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secMgr = new SecurityManager(this);
        verifyMode = getIntent().getBooleanExtra(EXTRA_MODE_VERIFY, false);
        buildUI();
    }

    private void buildUI() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(getColor(R.color.surface_bg));

        Toolbar tb = new Toolbar(this);
        tb.setTitle(verifyMode ? "Verify Identity" : "Two-Step Verification");
        tb.setTitleTextColor(0xFFFFFFFF);
        tb.setBackgroundColor(getColor(R.color.brand_primary));
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.getNavigationIcon().setTint(0xFFFFFFFF);
        tb.setNavigationOnClickListener(v -> finish());

        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        // Icon
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_shield);
        icon.setColorFilter(getColor(R.color.brand_primary));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconLp.setMargins(0, dp(24), 0, dp(16));
        icon.setLayoutParams(iconLp);

        // Status label
        TextView statusTv = new TextView(this);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setTextSize(14);
        statusTv.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, 0, 0, dp(32));
        statusTv.setLayoutParams(statusLp);

        if (verifyMode) {
            statusTv.setText("Enter your two-step verification PIN to continue.");
            buildVerifyFlow(root, full);
        } else if (secMgr.isTwoStepEnabled()) {
            statusTv.setText("Two-step verification is ENABLED. Your account is protected with an extra PIN layer.");
            buildManageFlow(root, full);
        } else {
            statusTv.setText("Add an extra layer of security. You will be asked for this PIN when registering your number.");
            buildSetupFlow(root, full);
        }

        setContentView(tb);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(tb);
        outer.addView(sv);
        setContentView(outer);
        root.addView(icon, 0);
        root.addView(statusTv, 1);
        sv.addView(root);
    }

    private void buildSetupFlow(LinearLayout root, LinearLayout.LayoutParams full) {
        TextView lbl = label("Set Verification PIN");
        EditText pinEt = pinField("Create a 6-digit PIN");
        EditText confirmEt = pinField("Confirm PIN");
        TextView hintLbl = label("Recovery Hint (optional)");
        EditText hintEt = new EditText(this);
        hintEt.setHint("e.g. 'My favourite city'");
        hintEt.setInputType(InputType.TYPE_CLASS_TEXT);
        hintEt.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            getColor(R.color.brand_primary)));

        com.google.android.material.button.MaterialButton btn = primaryBtn("Enable Two-Step Verification");
        btn.setOnClickListener(v -> {
            String pin  = pinEt.getText().toString().trim();
            String conf = confirmEt.getText().toString().trim();
            String hint = hintEt.getText().toString().trim();
            if (pin.length() != 6) { toast("PIN must be exactly 6 digits"); return; }
            if (!pin.equals(conf))  { toast("PINs do not match"); return; }
            secMgr.setTwoStep(pin, hint);
            toast("Two-step verification enabled!");
            setResult(RESULT_OK);
            finish();
        });

        root.addView(lbl, full);
        root.addView(pinEt, full);
        root.addView(confirmEt, full);
        root.addView(hintLbl, full);
        root.addView(hintEt, full);
        root.addView(spacer(dp(24)));
        root.addView(btn, full);
    }

    private void buildManageFlow(LinearLayout root, LinearLayout.LayoutParams full) {
        // Status card
        TextView activeTv = new TextView(this);
        activeTv.setText("Two-Step Verification: ACTIVE");
        activeTv.setTextColor(0xFF22D3A6);
        activeTv.setTextSize(15);
        activeTv.setTypeface(null, android.graphics.Typeface.BOLD);
        activeTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(full);
        cardLp.setMargins(0, 0, 0, dp(32));
        activeTv.setLayoutParams(cardLp);

        com.google.android.material.button.MaterialButton changeBtn = primaryBtn("Change PIN");
        changeBtn.setOnClickListener(v -> showChangePinDialog());

        com.google.android.material.button.MaterialButton disableBtn = dangerBtn("Disable Two-Step Verification");
        disableBtn.setOnClickListener(v -> showDisableDialog());

        String hint = secMgr.getTwoStepHint();
        if (!hint.isEmpty()) {
            TextView hintTv = new TextView(this);
            hintTv.setText("Recovery hint: " + hint);
            hintTv.setTextColor(getColor(R.color.text_muted));
            hintTv.setTextSize(13);
            hintTv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(full);
            hLp.setMargins(0, dp(8), 0, 0);
            hintTv.setLayoutParams(hLp);
            root.addView(activeTv, full);
            root.addView(changeBtn, full);
            root.addView(hintTv);
            root.addView(spacer(dp(12)));
            root.addView(disableBtn, full);
        } else {
            root.addView(activeTv, full);
            root.addView(changeBtn, full);
            root.addView(spacer(dp(12)));
            root.addView(disableBtn, full);
        }
    }

    private void buildVerifyFlow(LinearLayout root, LinearLayout.LayoutParams full) {
        EditText pinEt = pinField("Enter your 6-digit PIN");
        String hint = secMgr.getTwoStepHint();
        if (!hint.isEmpty()) {
            TextView hintTv = new TextView(this);
            hintTv.setText("Hint: " + hint);
            hintTv.setTextColor(getColor(R.color.text_muted));
            hintTv.setTextSize(13);
            root.addView(hintTv, full);
        }
        com.google.android.material.button.MaterialButton verifyBtn = primaryBtn("Verify");
        verifyBtn.setOnClickListener(v -> {
            String pin = pinEt.getText().toString().trim();
            if (secMgr.checkTwoStep(pin)) {
                setResult(RESULT_OK);
                finish();
            } else {
                toast("Incorrect PIN");
            }
        });
        root.addView(pinEt, full);
        root.addView(spacer(dp(16)));
        root.addView(verifyBtn, full);
    }

    private void showChangePinDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(8));
        EditText oldPin  = pinField("Current PIN");
        EditText newPin  = pinField("New 6-digit PIN");
        EditText confPin = pinField("Confirm new PIN");
        EditText hint    = new EditText(this);
        hint.setHint("New hint (optional)");
        layout.addView(oldPin);
        layout.addView(newPin);
        layout.addView(confPin);
        layout.addView(hint);
        new AlertDialog.Builder(this)
            .setTitle("Change Two-Step PIN")
            .setView(layout)
            .setPositiveButton("Change", (d, w) -> {
                if (!secMgr.checkTwoStep(oldPin.getText().toString())) {
                    toast("Incorrect current PIN"); return;
                }
                String np = newPin.getText().toString();
                if (np.length() != 6) { toast("New PIN must be 6 digits"); return; }
                if (!np.equals(confPin.getText().toString())) { toast("PINs don't match"); return; }
                secMgr.setTwoStep(np, hint.getText().toString().trim());
                toast("PIN updated successfully");
                recreate();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showDisableDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(8));
        EditText pinEt = pinField("Enter current PIN to confirm");
        layout.addView(pinEt);
        new AlertDialog.Builder(this)
            .setTitle("Disable Two-Step Verification?")
            .setMessage("Your account will be less secure. Enter your PIN to confirm.")
            .setView(layout)
            .setPositiveButton("Disable", (d, w) -> {
                if (secMgr.checkTwoStep(pinEt.getText().toString())) {
                    secMgr.disableTwoStep();
                    toast("Two-step verification disabled");
                    setResult(RESULT_OK);
                    finish();
                } else {
                    toast("Incorrect PIN");
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private EditText pinField(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            getColor(R.color.brand_primary)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        et.setLayoutParams(lp);
        return et;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(16), 0, dp(4));
        tv.setLayoutParams(lp);
        return tv;
    }

    private com.google.android.material.button.MaterialButton primaryBtn(String text) {
        com.google.android.material.button.MaterialButton btn =
            new com.google.android.material.button.MaterialButton(this);
        btn.setText(text);
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            getColor(R.color.brand_primary)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        btn.setLayoutParams(lp);
        return btn;
    }

    private com.google.android.material.button.MaterialButton dangerBtn(String text) {
        com.google.android.material.button.MaterialButton btn =
            new com.google.android.material.button.MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(text);
        btn.setTextColor(getColor(R.color.action_danger));
        btn.setStrokeColor(android.content.res.ColorStateList.valueOf(
            getColor(R.color.action_danger)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        btn.setLayoutParams(lp);
        return btn;
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
