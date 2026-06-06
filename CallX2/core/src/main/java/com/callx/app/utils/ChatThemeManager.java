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
    public static final int THEME_HYBRID    = 0;  // Default: Magenta-Orange / Gold-Green
    public static final int THEME_OCEAN     = 1;  // Blue-Cyan / Sky-Indigo
    public static final int THEME_FOREST    = 2;  // Green-Teal / Lime-Emerald
    public static final int THEME_SUNSET    = 3;  // Orange-Red / Peach-Coral
    public static final int THEME_LAVENDER  = 4;  // Purple-Pink / Violet-Rose
    public static final int THEME_MIDNIGHT  = 5;  // Dark Navy / Dark Slate
    public static final int THEME_CLASSIC   = 6;  // WhatsApp-style: Green / White
    public static final int THEME_MONO      = 7;  // Charcoal / Light Grey (flat)
    // ── 5 New Themes ─────────────────────────────────────────────────────
    public static final int THEME_CHERRY    = 8;  // Cherry Blossom: Deep Rose / Blush Pink
    public static final int THEME_AURORA    = 9;  // Aurora Borealis: Teal-Purple shimmer
    public static final int THEME_COFFEE    = 10; // Coffee: Warm Brown / Caramel
    public static final int THEME_NEON      = 11; // Neon Glow: Electric Green / Hot Pink
    public static final int THEME_ROYAL     = 12; // Royal: Deep Gold / Burgundy
    // ── 5 New Themes ─────────────────────────────────────────────────────
    public static final int THEME_GALAXY    = 13; // Galaxy: Deep Space Blue / Nebula Purple
    public static final int THEME_CANDY     = 14; // Candy: Bubblegum Pink / Mint
    public static final int THEME_FIRE      = 15; // Fire: Flame Red / Lava Orange
    public static final int THEME_ICE       = 16; // Ice: Arctic Blue / Glacier White
    public static final int THEME_JUNGLE    = 17; // Jungle: Deep Green / Lime Yellow

    public static final String[] THEME_NAMES = {
        "\uD83C\uDF08 Hybrid (Default)",
        "\uD83C\uDF0A Ocean",
        "\uD83C\uDF3F Forest",
        "\uD83C\uDF05 Sunset",
        "\uD83D\uDC9C Lavender",
        "\uD83C\uDF19 Midnight",
        "\uD83D\uDC9A Classic",
        "\u2B1B Monochrome",
        // 5 new
        "\uD83C\uDF38 Cherry Blossom",
        "\uD83C\uDF0C Aurora Borealis",
        "\u2615 Coffee",
        "\u26A1 Neon Glow",
        "\uD83D\uDC51 Royal",
        // 5 new
        "\uD83C\uDF0C Galaxy",
        "\uD83C\uDF6C Candy",
        "\uD83D\uDD25 Fire",
        "\u2744\uFE0F Ice",
        "\uD83C\uDF3F Jungle"
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
     */
    public void applyBubble(android.view.View bubbleView,
                            boolean sent, String msgType, boolean hasReply) {
        if (bubbleView == null) return;

        float density = bubbleView.getContext().getResources().getDisplayMetrics().density;

        float[] corners = BubbleShapeManager.get(bubbleView.getContext())
                              .getCornerRadii(sent, density);

        int[] colors = getColors(currentTheme, sent);
        GradientDrawable gd;

        if (currentTheme == THEME_MONO || currentTheme == THEME_CLASSIC
                || currentTheme == THEME_COFFEE || currentTheme == THEME_ICE) {
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
            case THEME_COFFEE:
                return sent ? 0xFFFFF8F0 : 0xFF2C1A0E;
            case THEME_AURORA:
                return sent ? 0xFFFFFFFF : 0xFF0A0A0A;
            default:
                return sent ? 0xFFFFFFFF : 0xFF1A1A1A;
        }
    }

    /**
     * Returns the primary/accent colour for this theme.
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
            case THEME_CHERRY:   return 0xFFE11D48;
            case THEME_AURORA:   return 0xFF14B8A6;
            case THEME_COFFEE:   return 0xFF92400E;
            case THEME_NEON:     return 0xFF00FF88;
            case THEME_ROYAL:    return 0xFFB8860B;
            case THEME_GALAXY:  return 0xFF7B2FBE;
            case THEME_CANDY:   return 0xFFFF6EB4;
            case THEME_FIRE:    return 0xFFFF3A00;
            case THEME_ICE:     return 0xFF48CAE4;
            case THEME_JUNGLE:  return 0xFF2D6A4F;
            case THEME_HYBRID:
            default:             return 0xFFFF0080;
        }
    }

    /**
     * Returns the secondary/gradient-end colour for this theme.
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
            case THEME_CHERRY:   return 0xFFFF6B9D;
            case THEME_AURORA:   return 0xFF8B5CF6;
            case THEME_COFFEE:   return 0xFFD97706;
            case THEME_NEON:     return 0xFFFF0088;
            case THEME_ROYAL:    return 0xFF7B1C3C;
            case THEME_GALAXY:  return 0xFF4361EE;
            case THEME_CANDY:   return 0xFF98F5E1;
            case THEME_FIRE:    return 0xFFFFA500;
            case THEME_ICE:     return 0xFFCAF0F8;
            case THEME_JUNGLE:  return 0xFF95D5B2;
            case THEME_HYBRID:
            default:             return 0xFFFF6B00;
        }
    }

    private boolean isDarkMode(android.content.Context ctx) {
        int flags = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public int getChatBgColor(android.content.Context ctx) {
        boolean dark = isDarkMode(ctx);
        if (dark) {
            switch (currentTheme) {
                case THEME_OCEAN:     return 0xFF0C1A2E;
                case THEME_FOREST:   return 0xFF0A1A0F;
                case THEME_SUNSET:   return 0xFF1A0D00;
                case THEME_LAVENDER: return 0xFF130A1F;
                case THEME_MIDNIGHT: return 0xFF050A12;
                case THEME_CLASSIC:  return 0xFF0A0A0A;
                case THEME_MONO:     return 0xFF111111;
                case THEME_CHERRY:   return 0xFF1A0810;
                case THEME_AURORA:   return 0xFF050F14;
                case THEME_COFFEE:   return 0xFF1A0D05;
                case THEME_NEON:     return 0xFF050F08;
                case THEME_ROYAL:    return 0xFF0F0A02;
                case THEME_GALAXY:  return 0xFF07050F;
                case THEME_CANDY:   return 0xFF1A0A14;
                case THEME_FIRE:    return 0xFF150500;
                case THEME_ICE:     return 0xFF051A22;
                case THEME_JUNGLE:  return 0xFF050F08;
                case THEME_HYBRID:
                default:             return 0xFF0A0A0A;
            }
        } else {
            switch (currentTheme) {
                case THEME_OCEAN:     return 0xFFE0F2FE;
                case THEME_FOREST:   return 0xFFDCFCE7;
                case THEME_SUNSET:   return 0xFFFFF7ED;
                case THEME_LAVENDER: return 0xFFF5F3FF;
                case THEME_MIDNIGHT: return 0xFF0F172A;
                case THEME_CLASSIC:  return 0xFFECE5DD;
                case THEME_MONO:     return 0xFFF3F4F6;
                case THEME_CHERRY:   return 0xFFFFF0F3;
                case THEME_AURORA:   return 0xFFE6FFFA;
                case THEME_COFFEE:   return 0xFFFDF6EC;
                case THEME_NEON:     return 0xFF0A1A0F;
                case THEME_ROYAL:    return 0xFFFDF8EC;
                case THEME_GALAXY:  return 0xFFEDE7F6;
                case THEME_CANDY:   return 0xFFFFF0F7;
                case THEME_FIRE:    return 0xFFFFF3ED;
                case THEME_ICE:     return 0xFFE8F8FF;
                case THEME_JUNGLE:  return 0xFFE8F5E9;
                case THEME_HYBRID:
                default:             return 0xFFECEEF5;
            }
        }
    }

    public int getInputBarColor(android.content.Context ctx) {
        boolean dark = isDarkMode(ctx);
        if (dark) {
            switch (currentTheme) {
                case THEME_MIDNIGHT: return 0xFF050A12;
                case THEME_MONO:     return 0xFF1A1A1A;
                case THEME_NEON:     return 0xFF050F08;
                default:             return 0xFF111111;
            }
        } else {
            switch (currentTheme) {
                case THEME_MIDNIGHT: return 0xFF1E293B;
                case THEME_MONO:     return 0xFFE5E7EB;
                case THEME_COFFEE:   return 0xFFFDF6EC;
                case THEME_NEON:     return 0xFF0A1A0F;
                default:             return 0xFFFFFFFF;
            }
        }
    }

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
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{primary, secondary});
        toolbar.setBackground(toolbarGd);

        android.content.Context ctx = toolbar.getContext();
        if (chatRoot != null) chatRoot.setBackgroundColor(getChatBgColor(ctx));
        if (inputBarRoot != null) inputBarRoot.setBackgroundColor(getInputBarColor(ctx));

        if (btnSend != null) {
            android.graphics.drawable.GradientDrawable sendGd =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            new int[]{primary, secondary});
            sendGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            btnSend.setBackground(sendGd);
        }

        if (btnMic != null) {
            android.graphics.drawable.GradientDrawable micGd =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            new int[]{primary, secondary});
            micGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            btnMic.setBackground(micGd);
        }

        if (fab != null) {
            fab.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(primary));
        }

        if (replyAccent != null) {
            replyAccent.setBackgroundColor(primary);
        }
    }

    public int getTickColor(boolean isRead) {
        switch (currentTheme) {
            case THEME_CLASSIC:  return isRead ? 0xFF34B7F1 : 0xFF8FAF9F;
            case THEME_MIDNIGHT:
            case THEME_MONO:     return isRead ? 0xFF60A5FA : 0xFF9CA3AF;
            case THEME_OCEAN:    return isRead ? 0xFF7DD3FC : 0xCCFFFFFF;
            case THEME_FOREST:   return isRead ? 0xFF86EFAC : 0xCCFFFFFF;
            case THEME_CHERRY:   return isRead ? 0xFFFF9EC4 : 0xCCFFFFFF;
            case THEME_AURORA:   return isRead ? 0xFF5EEAD4 : 0xCCFFFFFF;
            case THEME_COFFEE:   return isRead ? 0xFFD97706 : 0xCCFFFFFF;
            case THEME_NEON:     return isRead ? 0xFF00FF88 : 0xCCFFFFFF;
            case THEME_ROYAL:    return isRead ? 0xFFFFD700 : 0xCCFFFFFF;
            case THEME_GALAXY:  return isRead ? 0xFFBB86FC : 0xCCFFFFFF;
            case THEME_CANDY:   return isRead ? 0xFFFF9FD8 : 0xCCFFFFFF;
            case THEME_FIRE:    return isRead ? 0xFFFFAB40 : 0xCCFFFFFF;
            case THEME_ICE:     return isRead ? 0xFF90E0EF : 0xCCFFFFFF;
            case THEME_JUNGLE:  return isRead ? 0xFF95D5B2 : 0xCCFFFFFF;
            default:             return isRead ? 0xFFFF00FF : 0xCCFFFFFF;
        }
    }

    // ── Internal colour table ─────────────────────────────────────────────

    private static int[] getColors(int theme, boolean sent) {
        switch (theme) {
            case THEME_OCEAN:
                return sent
                    ? new int[]{0xFF0EA5E9, 0xFF6366F1}
                    : new int[]{0xFF38BDF8, 0xFF22D3EE};

            case THEME_FOREST:
                return sent
                    ? new int[]{0xFF16A34A, 0xFF0D9488}
                    : new int[]{0xFF86EFAC, 0xFF6EE7B7};

            case THEME_SUNSET:
                return sent
                    ? new int[]{0xFFF97316, 0xFFEF4444}
                    : new int[]{0xFFFBBF24, 0xFFF87171};

            case THEME_LAVENDER:
                return sent
                    ? new int[]{0xFF7C3AED, 0xFFDB2777}
                    : new int[]{0xFFA78BFA, 0xFFF0ABFC};

            case THEME_MIDNIGHT:
                return sent
                    ? new int[]{0xFF1E293B, 0xFF0F172A}
                    : new int[]{0xFF334155, 0xFF1E293B};

            case THEME_CLASSIC:
                return sent
                    ? new int[]{0xFF25D366, 0xFF25D366}
                    : new int[]{0xFFFFFFFF, 0xFFFFFFFF};

            case THEME_MONO:
                return sent
                    ? new int[]{0xFF374151, 0xFF374151}
                    : new int[]{0xFFE5E7EB, 0xFFE5E7EB};

            // ── 5 New Themes ──────────────────────────────────────────────

            case THEME_CHERRY:
                // Cherry Blossom: Deep Rose → Hot Pink / Blush → Petal
                return sent
                    ? new int[]{0xFFE11D48, 0xFFFF6B9D}    // crimson → hot-pink
                    : new int[]{0xFFFFB3C6, 0xFFFFD6E0};   // blush → petal

            case THEME_AURORA:
                // Aurora Borealis: Teal → Purple / Aqua shimmer
                return sent
                    ? new int[]{0xFF0D9488, 0xFF7C3AED}    // teal → deep-purple
                    : new int[]{0xFF5EEAD4, 0xFFA78BFA};   // aqua → soft-violet

            case THEME_COFFEE:
                // Coffee: Warm Espresso (flat) / Latte cream (flat)
                return sent
                    ? new int[]{0xFF6F3F1F, 0xFF6F3F1F}    // espresso (flat)
                    : new int[]{0xFFD4A97A, 0xFFD4A97A};   // latte (flat)

            case THEME_NEON:
                // Neon Glow: Electric Green → Hot Pink / Dark Teal glow
                return sent
                    ? new int[]{0xFF00C853, 0xFFFF0088}    // electric-green → neon-pink
                    : new int[]{0xFF00BFA5, 0xFF1DE9B6};   // teal → mint glow

            case THEME_ROYAL:
                // Royal: Deep Gold → Burgundy / Champagne shimmer
                return sent
                    ? new int[]{0xFFB8860B, 0xFF7B1C3C}    // dark-gold → burgundy
                    : new int[]{0xFFFFE066, 0xFFFFF0B3};   // champagne → ivory

            case THEME_GALAXY:
                // Galaxy: Deep Space Blue → Nebula Purple / Soft Lavender glow
                return sent
                    ? new int[]{0xFF7B2FBE, 0xFF4361EE}    // deep-purple → electric-blue
                    : new int[]{0xFFBB86FC, 0xFF9C4DCC};   // lavender → mid-purple

            case THEME_CANDY:
                // Candy: Bubblegum Pink → Mint / Pastel shades
                return sent
                    ? new int[]{0xFFFF6EB4, 0xFFFF9FD8}    // hot-pink → bubblegum
                    : new int[]{0xFF98F5E1, 0xFFB5EAD7};   // mint → pastel-green

            case THEME_FIRE:
                // Fire: Flame Red → Lava Orange / Ember glow
                return sent
                    ? new int[]{0xFFFF3A00, 0xFFFFA500}    // flame-red → orange
                    : new int[]{0xFFFFCB69, 0xFFFFAB40};   // light-amber → ember

            case THEME_ICE:
                // Ice: Arctic Blue flat / Glacier White flat
                return sent
                    ? new int[]{0xFF48CAE4, 0xFF48CAE4}    // arctic-blue (flat)
                    : new int[]{0xFFCAF0F8, 0xFFCAF0F8};   // glacier-white (flat)

            case THEME_JUNGLE:
                // Jungle: Deep Green → Lime / Leaf glow
                return sent
                    ? new int[]{0xFF2D6A4F, 0xFF40916C}    // deep-green → mid-green
                    : new int[]{0xFF95D5B2, 0xFFD8F3DC};   // light-leaf → pale-mint

            case THEME_HYBRID:
            default:
                return sent
                    ? new int[]{0xFFFF0080, 0xFFFF6B00}
                    : new int[]{0xFFFFD700, 0xFF00E676};
        }
    }
}
