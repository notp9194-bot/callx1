package com.callx.app.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.GridLayout;

/**
 * Solid-color wallpaper picker — the lowest-cost wallpaper option there is.
 *
 * A photo wallpaper (even downsampled to 720x720, see ChatThemeController /
 * GroupChatActivity#applyWallpaper) still costs a Bitmap decode, a Glide
 * disk+memory cache entry (something for the LRU cache to hold and
 * eventually evict), and a real GPU texture upload behind every message
 * bubble the whole time you scroll. A flat ColorDrawable behind the list
 * pays none of that — no decode, no cache entry, effectively a solid fill.
 *
 * Shared between ChatThemeController (1:1) and GroupChatActivity so both
 * pickers show the exact same palette and behave identically.
 */
public final class ChatWallpaperColorPicker {

    // Curated dark, bubble-legible flat tones. No gradients, no images —
    // picking one of these is what actually keeps scrolling smooth.
    private static final int[] PRESET_COLORS = {
            0xFF0B141A, // WhatsApp dark
            0xFF111B21,
            0xFF1A1A2E,
            0xFF14213D,
            0xFF1B4332,
            0xFF3A0CA3,
            0xFF3D0000,
            0xFF212121,
    };

    public interface OnColorApplied { void onApplied(); }

    private ChatWallpaperColorPicker() {}

    /** Shows the color grid, then a this-chat/global scope choice, and saves+applies on pick. */
    public static void show(Context ctx, String conversationId, OnColorApplied onApplied) {
        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(4);
        int pad = dp(ctx, 16);
        grid.setPadding(pad, pad, pad, pad);

        AlertDialog[] holder = new AlertDialog[1];

        for (int color : PRESET_COLORS) {
            View swatch = new View(ctx);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(ctx, 56);
            lp.height = dp(ctx, 56);
            lp.setMargins(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
            swatch.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color);
            bg.setStroke(dp(ctx, 1), 0x33FFFFFF);
            swatch.setBackground(bg);
            swatch.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                showScopeDialog(ctx, conversationId, color, onApplied);
            });
            grid.addView(swatch);
        }

        holder[0] = new AlertDialog.Builder(ctx)
                .setTitle("🎨 Solid Color Wallpaper")
                .setView(grid)
                .setNegativeButton("Cancel", null)
                .create();

        AlertDialogStyler.showRounded(holder[0]);
    }

    private static void showScopeDialog(Context ctx, String conversationId, int color, OnColorApplied onApplied) {
        String[] options = {"🙋 This chat only", "🌐 All chats (Global)"};
        ChatWallpaperManager wm = ChatWallpaperManager.get(ctx);
        AlertDialogStyler.showRounded(
            new AlertDialog.Builder(ctx)
                .setTitle("🎨 Apply Color")
                .setItems(options, (d, which) -> {
                    if (which == 0) wm.setWallpaperColor(conversationId, color);
                    else            wm.setGlobalWallpaperColor(color);
                    if (onApplied != null) onApplied.onApplied();
                })
                .setNegativeButton("Cancel", null)
            .create());
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
