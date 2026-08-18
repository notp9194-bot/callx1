package com.callx.app.utils;

import android.content.Intent;
import android.graphics.Rect;
import android.view.View;

/**
 * TELEGRAM-STYLE OPEN/CLOSE ANIMATION — SOURCE RECT HANDOFF
 * ──────────────────────────────────────────────────────────────────────
 * Chat bubble ke andar jis thumbnail (ImageView / video cell / canvas-drawn
 * media slot) par tap hua tha, uska on-screen rect yahan se
 * MediaViewerActivity ke Intent extras mein attach hota hai.
 *
 * MediaViewerActivity is rect ko do jagah use karta hai:
 *  • OPEN  — content view ko is chhote rect se shuru karke full-screen tak
 *            scale-up karta hai (bubble se "nikal ke" khulta hai).
 *  • CLOSE — swipe (up ya down, dono) ya back/close-button se band karte
 *            waqt, content view ko wapas isi rect ki taraf shrink karta hai
 *            taaki band hote hi wahi "chipak" jaye jaha se photo open hui
 *            thi — exact wahi Telegram media-viewer wala feel.
 *
 * View null ya abhi layout hi na hua ho (width/height 0) to koi extra
 * attach nahi hota — us case mein viewer purana plain fade/translate
 * close hi karega (safe no-op fallback, kabhi crash nahi karta).
 */
public final class MediaViewerSourceRect {
    public static final String EXTRA_LEFT   = "srcRectLeft";
    public static final String EXTRA_TOP    = "srcRectTop";
    public static final String EXTRA_WIDTH  = "srcRectWidth";
    public static final String EXTRA_HEIGHT = "srcRectHeight";

    private MediaViewerSourceRect() {}

    /** A View's current on-screen bounds as a Rect, or null if not laid out yet. */
    public static Rect ofView(View sourceView) {
        if (sourceView == null || sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) return null;
        int[] loc = new int[2];
        sourceView.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1], loc[0] + sourceView.getWidth(), loc[1] + sourceView.getHeight());
    }

    /** Attach a real View's current on-screen bounds (e.g. an ImageView/cell thumbnail). */
    public static void attach(Intent intent, View sourceView) {
        if (intent == null || sourceView == null) return;
        if (sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) return;
        int[] loc = new int[2];
        sourceView.getLocationOnScreen(loc);
        attach(intent, new Rect(loc[0], loc[1], loc[0] + sourceView.getWidth(), loc[1] + sourceView.getHeight()));
    }

    /** Attach an explicit on-screen Rect (e.g. a canvas-drawn media slot's bounds). */
    public static void attach(Intent intent, Rect screenRect) {
        if (intent == null || screenRect == null) return;
        if (screenRect.width() <= 0 || screenRect.height() <= 0) return;
        intent.putExtra(EXTRA_LEFT, screenRect.left);
        intent.putExtra(EXTRA_TOP, screenRect.top);
        intent.putExtra(EXTRA_WIDTH, screenRect.width());
        intent.putExtra(EXTRA_HEIGHT, screenRect.height());
    }

    /** Reads the rect back out in MediaViewerActivity; null if none was attached. */
    public static Rect read(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_LEFT)) return null;
        int left   = intent.getIntExtra(EXTRA_LEFT, 0);
        int top    = intent.getIntExtra(EXTRA_TOP, 0);
        int width  = intent.getIntExtra(EXTRA_WIDTH, 0);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 0);
        if (width <= 0 || height <= 0) return null;
        return new Rect(left, top, left + width, top + height);
    }
}
