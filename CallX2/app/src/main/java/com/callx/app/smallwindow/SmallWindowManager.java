package com.callx.app.smallwindow;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import com.callx.app.R;

/**
 * SmallWindowManager — Chat / Call ko ek chhoti floating window mein dikhao.
 *
 * Features:
 *  - Draggable floating window (full screen ke upar)
 *  - Minimize → corner bubble
 *  - Close button
 *  - Android 8+ TYPE_APPLICATION_OVERLAY use karta hai
 *
 * Usage:
 *   SmallWindowManager.getInstance().show(context, "Ali Hassan", "Online");
 *   SmallWindowManager.getInstance().dismiss(context);
 *
 * Requires: android.permission.SYSTEM_ALERT_WINDOW
 */
public class SmallWindowManager {

    private static SmallWindowManager instance;

    public static SmallWindowManager getInstance() {
        if (instance == null) instance = new SmallWindowManager();
        return instance;
    }

    private View    smallWindowView;
    private View    bubbleView;
    private boolean isMinimized = false;

    // ── Drag state ────────────────────────────────────────────────────────
    private int  initialX, initialY;
    private float initialTouchX, initialTouchY;

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Small window dikhao.
     *
     * @param context Application context
     * @param name    Contact / chat ka naam
     * @param status  Status text (e.g. "Online", "In a call")
     */
    public void show(Context context, String name, String status) {
        if (smallWindowView != null) dismiss(context); // purana remove karo

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        LayoutInflater inflater = LayoutInflater.from(context);
        smallWindowView = inflater.inflate(R.layout.layout_small_window, null);

        // ── Window params ─────────────────────────────────────────────────
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            dpToPx(context, 260),
            dpToPx(context, 180),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 120;

        // ── Bind views ────────────────────────────────────────────────────
        TextView tvName   = smallWindowView.findViewById(R.id.tv_sw_name);
        TextView tvStatus = smallWindowView.findViewById(R.id.tv_sw_status);
        ImageButton btnMin   = smallWindowView.findViewById(R.id.btn_sw_minimize);
        ImageButton btnClose = smallWindowView.findViewById(R.id.btn_sw_close);

        if (tvName   != null) tvName.setText(name   != null ? name   : "");
        if (tvStatus != null) tvStatus.setText(status != null ? status : "");

        // ── Drag logic ────────────────────────────────────────────────────
        smallWindowView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX      = params.x;
                    initialY      = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    params.x = initialX + (int)(event.getRawX() - initialTouchX);
                    params.y = initialY + (int)(event.getRawY() - initialTouchY);
                    wm.updateViewLayout(smallWindowView, params);
                    return true;
            }
            return false;
        });

        // ── Minimize button ───────────────────────────────────────────────
        if (btnMin != null) {
            btnMin.setOnClickListener(v -> minimize(context, wm, params));
        }

        // ── Close button ──────────────────────────────────────────────────
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss(context));
        }

        wm.addView(smallWindowView, params);
        isMinimized = false;
    }

    // ── Minimize → corner bubble ──────────────────────────────────────────

    private void minimize(Context context, WindowManager wm, WindowManager.LayoutParams swParams) {
        if (smallWindowView == null || isMinimized) return;
        isMinimized = true;

        // Remove the main small window
        try { wm.removeView(smallWindowView); } catch (Exception ignored) {}

        // Create bubble
        LayoutInflater inflater = LayoutInflater.from(context);
        bubbleView = inflater.inflate(R.layout.layout_small_window_bubble, null);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams bubbleParams = new WindowManager.LayoutParams(
            dpToPx(context, 56),
            dpToPx(context, 56),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

        bubbleParams.gravity = Gravity.TOP | Gravity.END;
        bubbleParams.x = 24;
        bubbleParams.y = 80;

        // Drag on bubble
        bubbleView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX      = bubbleParams.x;
                    initialY      = bubbleParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    bubbleParams.x = initialX - (int)(event.getRawX() - initialTouchX);
                    bubbleParams.y = initialY + (int)(event.getRawY() - initialTouchY);
                    wm.updateViewLayout(bubbleView, bubbleParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(event.getRawX() - initialTouchX);
                    float dy = Math.abs(event.getRawY() - initialTouchY);
                    if (dx < 10 && dy < 10) {
                        // Tap — restore
                        try { wm.removeView(bubbleView); } catch (Exception ignored) {}
                        bubbleView = null;
                        isMinimized = false;
                        show(context, null, null); // re-show (caller should cache name/status)
                    }
                    return true;
            }
            return false;
        });

        wm.addView(bubbleView, bubbleParams);
    }

    // ── Dismiss ───────────────────────────────────────────────────────────

    public void dismiss(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        if (smallWindowView != null) {
            try { wm.removeView(smallWindowView); } catch (Exception ignored) {}
            smallWindowView = null;
        }
        if (bubbleView != null) {
            try { wm.removeView(bubbleView); } catch (Exception ignored) {}
            bubbleView = null;
        }
        isMinimized = false;
    }

    public boolean isShowing() {
        return smallWindowView != null || bubbleView != null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private int dpToPx(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
