package com.callx.app.admin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.Locale;

/** Small, dependency-free view factory shared by the admin screens. */
public final class AdminUi {
    public static final int INK = Color.rgb(27, 31, 42);
    public static final int MUTED = Color.rgb(101, 109, 125);
    public static final int BRAND = Color.rgb(91, 63, 211);
    public static final int DANGER = Color.rgb(194, 48, 62);
    public static final int SURFACE = Color.rgb(247, 247, 251);

    private AdminUi() {}

    public static int dp(Context c, int value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static LinearLayout screen(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
        return root;
    }

    public static ScrollView scroll(Context c) {
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 24));
        return scroll;
    }

    public static LinearLayout column(Context c) {
        LinearLayout out = new LinearLayout(c);
        out.setOrientation(LinearLayout.VERTICAL);
        return out;
    }

    public static Toolbar toolbar(Context c, String title) {
        Toolbar bar = new Toolbar(c);
        bar.setTitle(title);
        bar.setTitleTextColor(Color.WHITE);
        bar.setBackgroundColor(BRAND);
        bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        bar.setNavigationOnClickListener(v -> {
            if (c instanceof android.app.Activity) ((android.app.Activity) c).finish();
        });
        return bar;
    }

    public static TextView title(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(INK);
        v.setTextSize(20);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setPadding(0, dp(c, 12), 0, dp(c, 8));
        return v;
    }

    public static TextView label(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(MUTED);
        v.setTextSize(13);
        v.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return v;
    }

    public static TextView body(Context c, String text) {
        TextView v = label(c, text);
        v.setTextColor(INK);
        v.setTextSize(14);
        return v;
    }

    public static MaterialCardView card(Context c) {
        MaterialCardView card = new MaterialCardView(c);
        card.setCardBackgroundColor(Color.WHITE);
        card.setRadius(dp(c, 14));
        card.setStrokeColor(Color.rgb(229, 230, 237));
        card.setStrokeWidth(dp(c, 1));
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 6), 0, dp(c, 6));
        card.setLayoutParams(lp);
        return card;
    }

    public static Button button(Context c, String text, View.OnClickListener click) {
        MaterialButton b = new MaterialButton(c);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        return b;
    }

    public static Button dangerButton(Context c, String text, View.OnClickListener click) {
        Button b = button(c, text, click);
        b.setTextColor(DANGER);
        return b;
    }

    public static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setSingleLine(false);
        e.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        return e;
    }

    public static void gap(Context c, LinearLayout parent, int height) {
        Space gap = new Space(c);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(c, height)));
    }

    public static void toast(Context c, String message) {
        Toast.makeText(c, message, Toast.LENGTH_LONG).show();
    }

    public static void confirm(Context c, String title, String message, String action,
                               Runnable onYes) {
        new AlertDialog.Builder(c).setTitle(title).setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action, (d, which) -> onYes.run()).show();
    }

    public static String money(Object paise) {
        return String.format(Locale.US, "₹%.2f", AdminApi.number(paise) / 100.0);
    }
}