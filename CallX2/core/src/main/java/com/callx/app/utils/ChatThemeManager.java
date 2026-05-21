package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;

/**
 * ChatThemeManager — Bubble colour themes for Chat screen.
 *
 * Usage:
 *   ChatThemeManager.get(ctx).applyBubble(llBubble, sent, msgType, hasReply);
 *   ChatThemeManager.get(ctx).setTheme(ChatThemeManager.THEME_OCEAN);
 *   adapter.notifyDataSetChanged(); // after theme change
 */
public class ChatThemeManager {

    // ── Theme IDs ────────────────────────────────────────────────────────
    public static final int THEME_HYBRID   = 0;  // Default: Magenta-Orange / Gold-Green
    public static final int THEME_OCEAN    = 1;  // Blue-Cyan / Sky-Indigo
    public static final int THEME_FOREST   = 2;  // Green-Teal / Lime-Emerald
    public static final int THEME_SUNSET   = 3;  // Orange-Red / Peach-Coral
    public static final int THEME_LAVENDER = 4;  // Purple-Pink / Violet-Rose
    public static final int THEME_MIDNIGHT = 5;  // Dark Navy / Dark Slate
    public static final int THEME_CLASSIC  = 6;  // WhatsApp-style: Green / White
    public static final int THEME_MONO     = 7;  // Charcoal / Light Grey (flat)

    public static final String[] THEME_NAMES = {
        "\uD83C\uDF08 Hybrid (Default)",
        "\uD83C\uDF0A Ocean",
        "\uD83C\uDF3F Forest",
        "\uD83C\uDF05 Sunset",
        "\uD83D\uDC9C Lavender",
        "\uD83C\uDF19 Midnight",
        "\uD83D\uDC9A Classic",
        "\u2B1B Monochrome"
    };

    // ── Corner radii in dp ───────────────────────────────────────────────
    private static final float R_LARGE = 20f;
    private static final float R_SMALL = 6f;

    // ── SharedPreferences ────────────────────────────────────────────────
    private static final String PREF_NAME = "chat_theme_prefs";
    private static final String KEY_THEME = "bubble_theme";

    // ── Singleton ────────────────────────────────────────────────────────
    private static ChatThemeManager instance;
    private final SharedPreferences prefs;
    private int currentTheme;

    private ChatThemeManager(Context ctx) {
        prefs        = ctx.getApplicationContext()
                          .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        currentTheme = prefs.getInt(KEY_THEME, THEME_HYBRID);
    }

    public static ChatThemeManager get(Context ctx) {
        if (instance == null) instance = new ChatThemeManager(ctx);
        return instance;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public int getCurrentTheme() { return currentTheme; }

    public void setTheme(int themeId) {
        currentTheme = themeId;
        prefs.edit().putInt(KEY_THEME, themeId).apply();
    }

    /**
     * Apply the correct bubble background to a given bubble container view.
     *
     * @param bubbleView  the LinearLayout / View with id ll_bubble
     * @param sent        true = sent message, false = received
     * @param msgType     "text", "image", "video", "audio", "file" etc.
     * @param hasReply    true when this message has a quote-reply preview
     */
    public void applyBubble(android.view.View bubbleView,
                            boolean sent, String msgType, boolean hasReply) {
        if (bubbleView == null) return;

        float density = bubbleView.getContext().getResources().getDisplayMetrics().density;

        // Sent = sharp bottom-right corner, Received = sharp top-left corner
        float[] corners = sent
                ? new float[]{
                    R_LARGE * density, R_LARGE * density,  // topLeft
                    R_LARGE * density, R_LARGE * density,  // topRight
                    R_SMALL * density, R_SMALL * density,  // bottomRight (sharp)
                    R_LARGE * density, R_LARGE * density   // bottomLeft
                  }
                : new float[]{
                    R_SMALL * density, R_SMALL * density,  // topLeft (sharp)
                    R_LARGE * density, R_LARGE * density,  // topRight
                    R_LARGE * density, R_LARGE * density,  // bottomRight
                    R_LARGE * density, R_LARGE * density   // bottomLeft
                  };

        int[] colors = getColors(currentTheme, sent);
        GradientDrawable gd;

        if (currentTheme == THEME_MONO || currentTheme == THEME_CLASSIC) {
            gd = new GradientDrawable();
            gd.setColor(colors[0]);
        } else {
            gd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    colors);
        }
        gd.setCornerRadii(corners);
        bubbleView.setBackground(gd);
    }

    /**
     * Returns the appropriate text colour for a bubble.
     */
    public int getTextColor(boolean sent) {
        switch (currentTheme) {
            case THEME_CLASSIC:
                return sent ? 0xFF0D0D0D : 0xFF111111;
            case THEME_MONO:
                return sent ? 0xFFFFFFFF : 0xFF1A1A1A;
            default:
                return sent ? 0xFFFFFFFF : 0xFF1A1A1A;
        }
    }

    /**
     * Returns the primary/accent colour for this theme.
     * Used for: toolbar gradient start, send/mic button, FAB, reply-bar accent stripe.
     */
    public int getPrimaryColor() {
        switch (currentTheme) {
            case THEME_OCEAN:     return 0xFF0EA5E9;
            case THEME_FOREST:   return 0xFF16A34A;
            case THEME_SUNSET:   return 0xFFF97316;
            case THEME_LAVENDER: return 0xFF7C3AED;
            case THEME_MIDNIGHT: return 0xFF1E293B;
            case THEME_CLASSIC:  return 0xFF25D366;
            case THEME_MONO:     return 0xFF374151;
            case THEME_HYBRID:
            default:             return 0xFFFF0080;
        }
    }

    /**
     * Returns the secondary/gradient-end colour for this theme.
     * Used as the toolbar gradient end colour and send/mic button gradient end.
     */
    public int getSecondaryColor() {
        switch (currentTheme) {
            case THEME_OCEAN:     return 0xFF6366F1;
            case THEME_FOREST:   return 0xFF0D9488;
            case THEME_SUNSET:   return 0xFFEF4444;
            case THEME_LAVENDER: return 0xFFDB2777;
            case THEME_MIDNIGHT: return 0xFF0F172A;
            case THEME_CLASSIC:  return 0xFF128C7E;
            case THEME_MONO:     return 0xFF1F2937;
            case THEME_HYBRID:
            default:             return 0xFFFF6B00;
        }
    }

    /**
     * Returns the chat list background colour for this theme.
     */
    public int getChatBgColor() {
        switch (currentTheme) {
            case THEME_OCEAN:     return 0xFFE0F2FE;
            case THEME_FOREST:   return 0xFFDCFCE7;
            case THEME_SUNSET:   return 0xFFFFF7ED;
            case THEME_LAVENDER: return 0xFFF5F3FF;
            case THEME_MIDNIGHT: return 0xFF0F172A;
            case THEME_CLASSIC:  return 0xFFECE5DD;
            case THEME_MONO:     return 0xFFF3F4F6;
            case THEME_HYBRID:
            default:             return 0xFFECEEF5;
        }
    }

    /**
     * Returns the input bar background colour for this theme.
     */
    public int getInputBarColor() {
        switch (currentTheme) {
            case THEME_MIDNIGHT: return 0xFF1E293B;
            case THEME_MONO:     return 0xFFE5E7EB;
            default:             return 0xFFFFFFFF;
        }
    }

    /**
     * Applies the full-screen theme to the chat screen UI elements:
     * toolbar gradient, input bar, send/mic buttons, FAB, chat background.
     *
     * Call this once in onCreate() and again after theme change.
     *
     * @param toolbar       the LinearLayout acting as the toolbar header
     * @param chatRoot      the root layout (for background)
     * @param inputBarRoot  the input row LinearLayout
     * @param btnSend       the send ImageButton
     * @param btnMic        the mic ImageButton
     * @param fab           the FloatingActionButton (can be null)
     * @param replyAccent   the accent stripe View inside reply bar (can be null)
     */
    public void applyScreenTheme(
            android.view.View toolbar,
            android.view.View chatRoot,
            android.view.View inputBarRoot,
            android.view.View btnSend,
            android.view.View btnMic,
            android.view.View fab,
            android.view.View replyAccent) {

        int primary   = getPrimaryColor();
        int secondary = getSecondaryColor();

        // ── Toolbar gradient ──────────────────────────────────────────────
        android.graphics.drawable.GradientDrawable toolbarGd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.L_R,
                        new int[]{primary, secondary});
        toolbar.setBackground(toolbarGd);

        // ── Chat background ───────────────────────────────────────────────
        if (chatRoot != null) chatRoot.setBackgroundColor(getChatBgColor());

        // ── Input bar background ──────────────────────────────────────────
        if (inputBarRoot != null) inputBarRoot.setBackgroundColor(getInputBarColor());

        // ── Send button ───────────────────────────────────────────────────
        if (btnSend != null) {
            android.graphics.drawable.GradientDrawable sendGd =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            new int[]{primary, secondary});
            sendGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            btnSend.setBackground(sendGd);
        }

        // ── Mic button ────────────────────────────────────────────────────
        if (btnMic != null) {
            android.graphics.drawable.GradientDrawable micGd =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            new int[]{primary, secondary});
            micGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            btnMic.setBackground(micGd);
        }

        // ── FAB — tint via ColorStateList (View.setBackgroundTintList, API 21+) ──
        if (fab != null) {
            fab.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(primary));
        }

        // ── Reply bar accent stripe ───────────────────────────────────────
        if (replyAccent != null) {
            replyAccent.setBackgroundColor(primary);
        }
    }

    /**
     * Returns tick / delivery-status colour for sent messages.
     * @param isRead true for "seen"/"read", false for "delivered"/"sent"
     */
    public int getTickColor(boolean isRead) {
        switch (currentTheme) {
            case THEME_CLASSIC:  return isRead ? 0xFF34B7F1 : 0xFF8FAF9F;
            case THEME_MIDNIGHT:
            case THEME_MONO:     return isRead ? 0xFF60A5FA : 0xFF9CA3AF;
            case THEME_OCEAN:    return isRead ? 0xFF7DD3FC : 0xCCFFFFFF;
            case THEME_FOREST:   return isRead ? 0xFF86EFAC : 0xCCFFFFFF;
            default:             return isRead ? 0xFFFF00FF : 0xCCFFFFFF; // Magenta / faded white
        }
    }

    // ── Internal colour table ─────────────────────────────────────────────

    private static int[] getColors(int theme, boolean sent) {
        switch (theme) {
            case THEME_OCEAN:
                return sent
                    ? new int[]{0xFF0EA5E9, 0xFF6366F1}   // sky-blue → indigo
                    : new int[]{0xFF38BDF8, 0xFF22D3EE};  // light-blue → cyan

            case THEME_FOREST:
                return sent
                    ? new int[]{0xFF16A34A, 0xFF0D9488}   // green → teal
                    : new int[]{0xFF86EFAC, 0xFF6EE7B7};  // light-green → emerald

            case THEME_SUNSET:
                return sent
                    ? new int[]{0xFFF97316, 0xFFEF4444}   // orange → red
                    : new int[]{0xFFFBBF24, 0xFFF87171};  // amber → coral

            case THEME_LAVENDER:
                return sent
                    ? new int[]{0xFF7C3AED, 0xFFDB2777}   // purple → pink
                    : new int[]{0xFFA78BFA, 0xFFF0ABFC};  // violet → fuchsia

            case THEME_MIDNIGHT:
                return sent
                    ? new int[]{0xFF1E293B, 0xFF0F172A}   // slate-700 → slate-900
                    : new int[]{0xFF334155, 0xFF1E293B};  // slate-600 → slate-700

            case THEME_CLASSIC:
                return sent
                    ? new int[]{0xFF25D366, 0xFF25D366}   // WhatsApp green (flat)
                    : new int[]{0xFFFFFFFF, 0xFFFFFFFF};  // white (flat)

            case THEME_MONO:
                return sent
                    ? new int[]{0xFF374151, 0xFF374151}   // charcoal (flat)
                    : new int[]{0xFFE5E7EB, 0xFFE5E7EB};  // light grey (flat)

            case THEME_HYBRID:
            default:
                return sent
                    ? new int[]{0xFFFF0080, 0xFFFF6B00}   // magenta → orange
                    : new int[]{0xFFFFD700, 0xFF00E676};  // gold → green
        }
    }
}
