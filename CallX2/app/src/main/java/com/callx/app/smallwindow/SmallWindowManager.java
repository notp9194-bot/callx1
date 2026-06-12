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
 *   SmallWindowManager.getInstance().show(context, "uid_123", "Ali Hassan", "Online");
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

    // ── Cached userId/name/status for bubble restore ──────────────────────
    private String cachedUserId;
    private String cachedName;
    private String cachedStatus;

    // ── Drag state ────────────────────────────────────────────────────────
    private int   initialX, initialY;
    private float initialTouchX, initialTouchY;

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Small window dikhao.
     *
     * @param context  Application context
     * @param userId   Firebase UID — future deep-link / re-open chat ke liye
     * @param name     Contact / chat ka naam
     * @param status   Status text (e.g. "Online", "In a call")
     */
    public void show(Context context, String userId, String name, String status) {
        if (smallWindowView != null) dismiss(context); // purana remove karo

        // Cache for bubble restore
        if (userId != null) cachedUserId = userId;
        if (name   != null) cachedName   = name;
        if (status != null) cachedStatus = status;

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 120;

        // ── Bind views ────────────────────────────────────────────────────
        TextView    tvName   = smallWindowView.findViewById(R.id.tv_sw_name);
        TextView    tvStatus = smallWindowView.findViewById(R.id.tv_sw_status);
        ImageButton btnMin   = smallWindowView.findViewById(R.id.btn_sw_minimize);
        ImageButton btnClose = smallWindowView.findViewById(R.id.btn_sw_close);

        if (tvName   != null) tvName.setText(cachedName   != null ? cachedName   : "");
        if (tvStatus != null) tvStatus.setText(cachedStatus != null ? cachedStatus : "");

        // ── Drag logic on root view ───────────────────────────────────────
        smallWindowView.setOnTouchListener(new View.OnTouchListener() {
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX      = params.x;
                        initialY      = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging    = false;
                        return false;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            try { wm.updateViewLayout(smallWindowView, params); } catch (Exception ignored) {}
                            return true;
                        }
                        return false;

                    case MotionEvent.ACTION_UP:
                        return isDragging;
                }
                return false;
            }
        });

        // ── Minimize button ───────────────────────────────────────────────
        if (btnMin != null) {
            btnMin.setOnClickListener(v -> minimize(context, wm, params));
        }

        // ── Close button ──────────────────────────────────────────────────
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                dismiss(context);
                try {
                    context.stopService(new android.content.Intent(context, SmallWindowService.class));
                } catch (Exception ignored) {}
            });
        }

        wm.addView(smallWindowView, params);
        isMinimized = false;
    }

    // ── Minimize → corner bubble ──────────────────────────────────────────

    private void minimize(Context context, WindowManager wm, WindowManager.LayoutParams swParams) {
        if (smallWindowView == null || isMinimized) return;
        isMinimized = true;

        try { wm.removeView(smallWindowView); } catch (Exception ignored) {}
        smallWindowView = null;

        LayoutInflater inflater = LayoutInflater.from(context);
        bubbleView = inflater.inflate(R.layout.layout_small_window_bubble, null);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams bubbleParams = new WindowManager.LayoutParams(
            dpToPx(context, 56),
            dpToPx(context, 56),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);

        bubbleParams.gravity = Gravity.TOP | Gravity.END;
        bubbleParams.x = 24;
        bubbleParams.y = 80;

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX      = bubbleParams.x;
                        initialY      = bubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging    = false;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            bubbleParams.x = initialX - (int) dx;
                            bubbleParams.y = initialY + (int) dy;
                            try { wm.updateViewLayout(bubbleView, bubbleParams); } catch (Exception ignored) {}
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            try { wm.removeView(bubbleView); } catch (Exception ignored) {}
                            bubbleView  = null;
                            isMinimized = false;
                            // Restore with cached userId
                            show(context, cachedUserId, cachedName, cachedStatus);
                        }
                        return true;
                }
                return false;
            }
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
        isMinimized  = false;
        cachedUserId = null;
        cachedName   = null;
        cachedStatus = null;
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
