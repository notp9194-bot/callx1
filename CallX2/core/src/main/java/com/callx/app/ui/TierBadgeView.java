package com.callx.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.TextView;

/** Small reusable tier pill for avatars, profile names, and admin previews. */
public class TierBadgeView extends TextView {
    public TierBadgeView(Context context) { super(context); init(); }
    public TierBadgeView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public TierBadgeView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }

    private void init() {
        setVisibility(GONE);
        setTextSize(10);
        setTextColor(Color.WHITE);
        setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        int h = dp(22);
        setMinHeight(h);
        setPadding(dp(8), 0, dp(8), 0);
    }

    public void setTier(String tier) {
        String key = tier == null ? "" : tier.toLowerCase();
        if (!key.equals("star") && !key.equals("gold") && !key.equals("platinum")) {
            setVisibility(GONE);
            return;
        }
        String label = key.equals("platinum") ? "♛ PLATINUM" : "★ " + key.toUpperCase();
        setText(label);
        int color = "platinum".equals(key) ? Color.rgb(123, 95, 201)
            : "gold".equals(key) ? Color.rgb(196, 137, 15) : Color.rgb(45, 132, 224);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        setBackground(bg);
        setVisibility(VISIBLE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}