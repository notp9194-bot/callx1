package com.callx.app.profile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.json.JSONException;

/**
 * ReelScreenCustomizationPrefs — ultra-comprehensive SharedPreferences wrapper
 * for all 12 customization sections of UserReelsActivity.
 *
 * Sections:
 *  1. Theme & Background
 *  2. Profile Picture
 *  3. Username & Badge
 *  4. Bio (About)
 *  5. Stats Box
 *  6. Links / Social Buttons
 *  7. Audio / Music Player
 *  8. Highlight Stories
 *  9. Top Action Bar
 * 10. Tabs / Section Icons
 * 11. Reel Grid / Content Area
 * 12. Extra UI Settings
 */
public class ReelScreenCustomizationPrefs {

    private static final String PREFS_NAME = "reel_screen_customization";

    // ── 1. Theme & Background ─────────────────────────────────────────────
    public static final String KEY_THEME_MODE          = "theme_mode";          // 0=Light 1=Dark 2=AMOLED
    public static final String KEY_BG_STYLE            = "bg_style";            // 0=Solid 1=Gradient 2=Blur 3=Image
    public static final String KEY_BG_COLOR            = "bg_color";            // hex
    public static final String KEY_GRADIENT_COLOR1     = "gradient_color1";
    public static final String KEY_GRADIENT_COLOR2     = "gradient_color2";
    public static final String KEY_GRADIENT_ANGLE      = "gradient_angle";      // 0-360
    public static final String KEY_BG_IMAGE_URI        = "bg_image_uri";
    public static final String KEY_BG_BLUR_RADIUS      = "bg_blur_radius";      // 0-25
    public static final String KEY_OVERLAY_COLOR       = "overlay_color";
    public static final String KEY_OVERLAY_OPACITY     = "overlay_opacity";     // 0-100
    public static final String KEY_GLOW_ENABLE         = "glow_enable";
    public static final String KEY_GLOW_COLOR          = "glow_color";
    public static final String KEY_GLOW_INTENSITY      = "glow_intensity";      // 0-100
    public static final String KEY_GLOW_RADIUS         = "glow_radius";         // dp

    // ── 2. Profile Picture ────────────────────────────────────────────────
    public static final String KEY_AVATAR_BORDER_ENABLE   = "avatar_border_enable";
    public static final String KEY_AVATAR_BORDER_STYLE    = "avatar_border_style";  // 0=Solid 1=Gradient
    public static final String KEY_AVATAR_BORDER_COLOR1   = "avatar_border_color1";
    public static final String KEY_AVATAR_BORDER_COLOR2   = "avatar_border_color2";
    public static final String KEY_AVATAR_BORDER_WIDTH    = "avatar_border_width";  // dp
    public static final String KEY_AVATAR_GLOW_ENABLE     = "avatar_glow_enable";
    public static final String KEY_AVATAR_GLOW_COLOR      = "avatar_glow_color";
    public static final String KEY_AVATAR_GLOW_INTENSITY  = "avatar_glow_intensity";
    public static final String KEY_AVATAR_STATUS_DOT      = "avatar_status_dot";
    public static final String KEY_AVATAR_DOT_COLOR       = "avatar_dot_color";
    public static final String KEY_AVATAR_DOT_SIZE        = "avatar_dot_size";     // dp

    // ── 3. Username & Badge ───────────────────────────────────────────────
    public static final String KEY_USERNAME_COLOR         = "username_color";
    public static final String KEY_USERNAME_FONT          = "username_font";       // 0=Default 1=Poppins 2=Inter 3=Roboto 4=Bold
    public static final String KEY_USERNAME_SIZE          = "username_size";       // sp
    public static final String KEY_BADGE_SHOW             = "badge_show";
    public static final String KEY_BADGE_COLOR            = "badge_color";
    public static final String KEY_BADGE_SIZE             = "badge_size";          // dp
    public static final String KEY_BADGE_POSITION         = "badge_position";      // 0=Left 1=Right

    // ── 4. Bio (About) ────────────────────────────────────────────────────
    public static final String KEY_BIO_TEXT_COLOR         = "bio_text_color";
    public static final String KEY_BIO_FONT               = "bio_font";
    public static final String KEY_BIO_SIZE               = "bio_size";           // sp
    public static final String KEY_BIO_LINE_SPACING       = "bio_line_spacing";   // 0-50 (extra sp)
    public static final String KEY_BIO_MAX_LINES          = "bio_max_lines";      // 1-10
    public static final String KEY_BIO_EMOJI_SIZE         = "bio_emoji_size";     // sp

    // ── 5. Stats Box ──────────────────────────────────────────────────────
    public static final String KEY_STATS_BOX_STYLE        = "stats_box_style";    // 0=Default 1=Glass 2=Card 3=Outline
    public static final String KEY_STATS_BG_STYLE         = "stats_bg_style";     // 0=Solid 1=Gradient 2=Glass
    public static final String KEY_STATS_BG_COLOR         = "stats_bg_color";
    public static final String KEY_STATS_BG_COLOR2        = "stats_bg_color2";
    public static final String KEY_STATS_OPACITY          = "stats_opacity";      // 0-100
    public static final String KEY_STATS_CORNER_RADIUS    = "stats_corner_radius";// dp
    public static final String KEY_STATS_BORDER_ENABLE    = "stats_border_enable";
    public static final String KEY_STATS_BORDER_COLOR     = "stats_border_color";
    public static final String KEY_STATS_BORDER_WIDTH     = "stats_border_width"; // dp
    public static final String KEY_STATS_DIVIDER_COLOR    = "stats_divider_color";
    public static final String KEY_STATS_DIVIDER_WIDTH    = "stats_divider_width";// dp
    public static final String KEY_STATS_NUM_COLOR        = "stats_num_color";
    public static final String KEY_STATS_NUM_FONT         = "stats_num_font";
    public static final String KEY_STATS_NUM_SIZE         = "stats_num_size";     // sp
    public static final String KEY_STATS_LABEL_COLOR      = "stats_label_color";
    public static final String KEY_STATS_LABEL_FONT       = "stats_label_font";
    public static final String KEY_STATS_LABEL_SIZE       = "stats_label_size";   // sp
    public static final String KEY_STATS_PADDING          = "stats_padding";      // dp

    // ── 6. Links / Social Buttons ─────────────────────────────────────────
    public static final String KEY_LINK_BTN_STYLE         = "link_btn_style";     // 0=Pill 1=Outline 2=Filled 3=Glass
    public static final String KEY_LINK_BTN_SHAPE         = "link_btn_shape";     // 0=Rounded 1=Square
    public static final String KEY_LINK_BG_COLOR          = "link_bg_color";
    public static final String KEY_LINK_BG_COLOR2         = "link_bg_color2";
    public static final String KEY_LINK_BORDER_COLOR      = "link_border_color";
    public static final String KEY_LINK_BORDER_WIDTH      = "link_border_width";  // dp
    public static final String KEY_LINK_ICON_STYLE        = "link_icon_style";    // 0=Filled 1=Outline
    public static final String KEY_LINK_ICON_COLOR        = "link_icon_color";
    public static final String KEY_LINK_ICON_SIZE         = "link_icon_size";     // dp
    public static final String KEY_LINK_TEXT_COLOR        = "link_text_color";
    public static final String KEY_LINK_TEXT_FONT         = "link_text_font";
    public static final String KEY_LINK_TEXT_SIZE         = "link_text_size";     // sp
    public static final String KEY_LINK_PADDING           = "link_padding";       // dp
    public static final String KEY_LINK_SPACING           = "link_spacing";       // dp

    // ── 7. Audio / Music Player ───────────────────────────────────────────
    public static final String KEY_PLAYER_STYLE           = "player_style";       // 0=Default 1=Modern 2=Minimal
    public static final String KEY_PLAYER_BG_COLOR        = "player_bg_color";
    public static final String KEY_PLAYER_BG_COLOR2       = "player_bg_color2";
    public static final String KEY_PLAYER_BG_STYLE        = "player_bg_style";    // 0=Color 1=Gradient 2=Glass
    public static final String KEY_PLAYER_CORNER_RADIUS   = "player_corner_radius";// dp
    public static final String KEY_PLAYER_ICON_COLOR      = "player_icon_color";
    public static final String KEY_PLAYER_WAVEFORM_COLOR  = "player_waveform_color";
    public static final String KEY_PLAYER_TEXT_COLOR      = "player_text_color";
    public static final String KEY_PLAYER_FONT            = "player_font";
    public static final String KEY_PLAYER_FONT_SIZE       = "player_font_size";   // sp
    public static final String KEY_PLAYER_PLAY_BTN_COLOR  = "player_play_btn_color";
    public static final String KEY_PLAYER_PROGRESS_COLOR  = "player_progress_color";
    public static final String KEY_PLAYER_PROGRESS_BG     = "player_progress_bg";
    public static final String KEY_PLAYER_SHOW            = "player_show";

    // ── 8. Highlight Stories ──────────────────────────────────────────────
    public static final String KEY_HL_BORDER_STYLE        = "hl_border_style";    // 0=Solid 1=Gradient
    public static final String KEY_HL_BORDER_COLOR1       = "hl_border_color1";
    public static final String KEY_HL_BORDER_COLOR2       = "hl_border_color2";
    public static final String KEY_HL_BORDER_WIDTH        = "hl_border_width";    // dp
    public static final String KEY_HL_GLOW_ENABLE         = "hl_glow_enable";
    public static final String KEY_HL_GLOW_COLOR          = "hl_glow_color";
    public static final String KEY_HL_GLOW_SIZE           = "hl_glow_size";       // dp
    public static final String KEY_HL_TITLE_COLOR         = "hl_title_color";
    public static final String KEY_HL_TITLE_FONT          = "hl_title_font";
    public static final String KEY_HL_TITLE_SIZE          = "hl_title_size";      // sp
    public static final String KEY_HL_TITLE_POSITION      = "hl_title_position";  // 0=Below 1=Inside
    public static final String KEY_HL_SPACING             = "hl_spacing";         // dp
    public static final String KEY_HL_ADD_BTN_ICON_COLOR  = "hl_add_btn_icon_color";

    // ── 9. Top Action Bar ─────────────────────────────────────────────────
    public static final String KEY_TOPBAR_ICON_COLOR      = "topbar_icon_color";
    public static final String KEY_TOPBAR_ICON_SIZE       = "topbar_icon_size";   // dp
    public static final String KEY_TOPBAR_ICON_STYLE      = "topbar_icon_style";  // 0=Outline 1=Filled
    public static final String KEY_TOPBAR_CIRCLE_ENABLE   = "topbar_circle_enable";
    public static final String KEY_TOPBAR_CIRCLE_COLOR    = "topbar_circle_color";
    public static final String KEY_TOPBAR_CIRCLE_SIZE     = "topbar_circle_size"; // dp
    public static final String KEY_TOPBAR_ICON_SPACING    = "topbar_icon_spacing";// dp
    public static final String KEY_TOPBAR_MENU_STYLE      = "topbar_menu_style";  // 0=Dots 1=Lines

    // ── 10. Tabs / Section Icons ──────────────────────────────────────────
    public static final String KEY_TAB_ICON_STYLE         = "tab_icon_style";     // 0=Default 1=Filled 2=Outline
    public static final String KEY_TAB_ACTIVE_COLOR       = "tab_active_color";
    public static final String KEY_TAB_INACTIVE_COLOR     = "tab_inactive_color";
    public static final String KEY_TAB_INDICATOR_STYLE    = "tab_indicator_style";// 0=Line 1=Dot 2=None
    public static final String KEY_TAB_INDICATOR_COLOR    = "tab_indicator_color";
    public static final String KEY_TAB_INDICATOR_HEIGHT   = "tab_indicator_height";// dp
    public static final String KEY_TAB_INDICATOR_WIDTH    = "tab_indicator_width"; // dp (0=full)
    public static final String KEY_TAB_BAR_BG_COLOR       = "tab_bar_bg_color";
    public static final String KEY_TAB_BAR_HEIGHT         = "tab_bar_height";     // dp
    public static final String KEY_TAB_ICON_SIZE          = "tab_icon_size";      // dp
    public static final String KEY_TAB_ICON_SPACING       = "tab_icon_spacing";   // dp

    // ── 11. Reel Grid / Content Area ──────────────────────────────────────
    public static final String KEY_GRID_BG_COLOR          = "grid_bg_color";
    public static final String KEY_GRID_THUMB_RADIUS      = "grid_thumb_radius";  // dp
    public static final String KEY_GRID_SPACING_V         = "grid_spacing_v";     // dp
    public static final String KEY_GRID_SPACING_H         = "grid_spacing_h";     // dp
    public static final String KEY_GRID_PLAY_ICON_COLOR   = "grid_play_icon_color";
    public static final String KEY_GRID_COLUMNS           = "grid_columns";       // 2 or 3
    public static final String KEY_GRID_OVERLAY_COLOR     = "grid_overlay_color";
    public static final String KEY_GRID_OVERLAY_FONT      = "grid_overlay_font";
    public static final String KEY_GRID_OVERLAY_SIZE      = "grid_overlay_size";  // sp

    // ── 12. Extra UI Settings ─────────────────────────────────────────────
    public static final String KEY_SCREEN_PADDING         = "screen_padding";     // dp
    public static final String KEY_SCROLL_SMOOTH          = "scroll_smooth";
    public static final String KEY_SHADOW_ENABLE          = "shadow_enable";
    public static final String KEY_SHADOW_COLOR           = "shadow_color";
    public static final String KEY_SHADOW_INTENSITY       = "shadow_intensity";   // 0-100
    public static final String KEY_SHADOW_RADIUS          = "shadow_radius";      // dp
    public static final String KEY_ANIMATIONS_ENABLE      = "animations_enable";
    public static final String KEY_TRANSITION_STYLE       = "transition_style";   // 0=Fade 1=Slide 2=Zoom
    public static final String KEY_FONT_FAMILY            = "font_family";        // 0=System 1=Poppins 2=Inter 3=Roboto

    // ── Default values ────────────────────────────────────────────────────
    public static final int    DEF_THEME_MODE          = 0;
    public static final int    DEF_BG_STYLE            = 0;
    public static final String DEF_BG_COLOR            = "#FFFFFF";
    public static final String DEF_GRADIENT_COLOR1     = "#5B5BF6";
    public static final String DEF_GRADIENT_COLOR2     = "#22D3A6";
    public static final int    DEF_GRADIENT_ANGLE      = 135;
    public static final int    DEF_BG_BLUR_RADIUS      = 8;
    public static final String DEF_OVERLAY_COLOR       = "#000000";
    public static final int    DEF_OVERLAY_OPACITY     = 30;
    public static final boolean DEF_GLOW_ENABLE        = false;
    public static final String DEF_GLOW_COLOR          = "#5B5BF6";
    public static final int    DEF_GLOW_INTENSITY      = 60;
    public static final int    DEF_GLOW_RADIUS         = 20;

    public static final boolean DEF_AVATAR_BORDER_ENABLE  = true;
    public static final int    DEF_AVATAR_BORDER_STYLE    = 1;
    public static final String DEF_AVATAR_BORDER_COLOR1   = "#5B5BF6";
    public static final String DEF_AVATAR_BORDER_COLOR2   = "#22D3A6";
    public static final int    DEF_AVATAR_BORDER_WIDTH    = 3;
    public static final boolean DEF_AVATAR_GLOW_ENABLE    = false;
    public static final String DEF_AVATAR_GLOW_COLOR      = "#5B5BF6";
    public static final int    DEF_AVATAR_GLOW_INTENSITY  = 60;
    public static final boolean DEF_AVATAR_STATUS_DOT     = true;
    public static final String DEF_AVATAR_DOT_COLOR       = "#22D3A6";
    public static final int    DEF_AVATAR_DOT_SIZE        = 12;

    public static final String DEF_USERNAME_COLOR      = "";
    public static final int    DEF_USERNAME_FONT       = 0;
    public static final int    DEF_USERNAME_SIZE       = 16;
    public static final boolean DEF_BADGE_SHOW         = true;
    public static final String DEF_BADGE_COLOR         = "#5B5BF6";
    public static final int    DEF_BADGE_SIZE          = 18;
    public static final int    DEF_BADGE_POSITION      = 1;

    public static final String DEF_BIO_TEXT_COLOR      = "";
    public static final int    DEF_BIO_FONT            = 0;
    public static final int    DEF_BIO_SIZE            = 13;
    public static final int    DEF_BIO_LINE_SPACING    = 4;
    public static final int    DEF_BIO_MAX_LINES       = 3;
    public static final int    DEF_BIO_EMOJI_SIZE      = 14;

    public static final int    DEF_STATS_BOX_STYLE     = 0;
    public static final int    DEF_STATS_BG_STYLE      = 0;
    public static final String DEF_STATS_BG_COLOR      = "";
    public static final int    DEF_STATS_OPACITY       = 100;
    public static final int    DEF_STATS_CORNER_RADIUS = 12;
    public static final boolean DEF_STATS_BORDER_ENABLE= false;
    public static final String DEF_STATS_BORDER_COLOR  = "#5B5BF6";
    public static final int    DEF_STATS_BORDER_WIDTH  = 1;
    public static final String DEF_STATS_DIVIDER_COLOR = "#E2E8F0";
    public static final int    DEF_STATS_DIVIDER_WIDTH = 1;
    public static final String DEF_STATS_NUM_COLOR     = "";
    public static final int    DEF_STATS_NUM_FONT      = 0;
    public static final int    DEF_STATS_NUM_SIZE      = 17;
    public static final String DEF_STATS_LABEL_COLOR   = "";
    public static final int    DEF_STATS_LABEL_FONT    = 0;
    public static final int    DEF_STATS_LABEL_SIZE    = 11;
    public static final int    DEF_STATS_PADDING       = 8;

    public static final int    DEF_LINK_BTN_STYLE      = 0;
    public static final int    DEF_LINK_BTN_SHAPE      = 0;
    public static final String DEF_LINK_BG_COLOR       = "";
    public static final String DEF_LINK_BORDER_COLOR   = "#5B5BF6";
    public static final int    DEF_LINK_BORDER_WIDTH   = 1;
    public static final int    DEF_LINK_ICON_STYLE     = 0;
    public static final String DEF_LINK_ICON_COLOR     = "";
    public static final int    DEF_LINK_ICON_SIZE      = 18;
    public static final String DEF_LINK_TEXT_COLOR     = "";
    public static final int    DEF_LINK_TEXT_FONT      = 0;
    public static final int    DEF_LINK_TEXT_SIZE      = 13;
    public static final int    DEF_LINK_PADDING        = 8;
    public static final int    DEF_LINK_SPACING        = 8;

    public static final int    DEF_PLAYER_STYLE        = 0;
    public static final String DEF_PLAYER_BG_COLOR     = "";
    public static final int    DEF_PLAYER_BG_STYLE     = 0;
    public static final int    DEF_PLAYER_CORNER_RADIUS= 12;
    public static final String DEF_PLAYER_ICON_COLOR   = "";
    public static final String DEF_PLAYER_WAVEFORM_COLOR = "#5B5BF6";
    public static final String DEF_PLAYER_TEXT_COLOR   = "";
    public static final int    DEF_PLAYER_FONT         = 0;
    public static final int    DEF_PLAYER_FONT_SIZE    = 13;
    public static final String DEF_PLAYER_PLAY_BTN_COLOR = "#5B5BF6";
    public static final String DEF_PLAYER_PROGRESS_COLOR = "#5B5BF6";
    public static final String DEF_PLAYER_PROGRESS_BG  = "#E2E8F0";
    public static final boolean DEF_PLAYER_SHOW        = true;

    public static final int    DEF_HL_BORDER_STYLE     = 1;
    public static final String DEF_HL_BORDER_COLOR1    = "#5B5BF6";
    public static final String DEF_HL_BORDER_COLOR2    = "#22D3A6";
    public static final int    DEF_HL_BORDER_WIDTH     = 2;
    public static final boolean DEF_HL_GLOW_ENABLE     = false;
    public static final String DEF_HL_GLOW_COLOR       = "#5B5BF6";
    public static final int    DEF_HL_GLOW_SIZE        = 8;
    public static final String DEF_HL_TITLE_COLOR      = "";
    public static final int    DEF_HL_TITLE_FONT       = 0;
    public static final int    DEF_HL_TITLE_SIZE       = 11;
    public static final int    DEF_HL_TITLE_POSITION   = 0;
    public static final int    DEF_HL_SPACING          = 12;
    public static final String DEF_HL_ADD_BTN_ICON_COLOR = "";

    public static final String DEF_TOPBAR_ICON_COLOR   = "";
    public static final int    DEF_TOPBAR_ICON_SIZE    = 24;
    public static final int    DEF_TOPBAR_ICON_STYLE   = 0;
    public static final boolean DEF_TOPBAR_CIRCLE_ENABLE = false;
    public static final String DEF_TOPBAR_CIRCLE_COLOR = "#22222250";
    public static final int    DEF_TOPBAR_CIRCLE_SIZE  = 36;
    public static final int    DEF_TOPBAR_ICON_SPACING = 4;
    public static final int    DEF_TOPBAR_MENU_STYLE   = 0;

    public static final int    DEF_TAB_ICON_STYLE      = 0;
    public static final String DEF_TAB_ACTIVE_COLOR    = "#5B5BF6";
    public static final String DEF_TAB_INACTIVE_COLOR  = "";
    public static final int    DEF_TAB_INDICATOR_STYLE = 0;
    public static final String DEF_TAB_INDICATOR_COLOR = "#5B5BF6";
    public static final int    DEF_TAB_INDICATOR_HEIGHT= 3;
    public static final int    DEF_TAB_INDICATOR_WIDTH = 0;
    public static final String DEF_TAB_BAR_BG_COLOR    = "";
    public static final int    DEF_TAB_BAR_HEIGHT      = 48;
    public static final int    DEF_TAB_ICON_SIZE       = 22;
    public static final int    DEF_TAB_ICON_SPACING    = 8;

    public static final String DEF_GRID_BG_COLOR       = "";
    public static final int    DEF_GRID_THUMB_RADIUS   = 0;
    public static final int    DEF_GRID_SPACING_V      = 1;
    public static final int    DEF_GRID_SPACING_H      = 1;
    public static final String DEF_GRID_PLAY_ICON_COLOR= "#FFFFFF";
    public static final int    DEF_GRID_COLUMNS        = 3;
    public static final String DEF_GRID_OVERLAY_COLOR  = "#FFFFFF";
    public static final int    DEF_GRID_OVERLAY_FONT   = 0;
    public static final int    DEF_GRID_OVERLAY_SIZE   = 12;

    public static final int    DEF_SCREEN_PADDING      = 0;
    public static final boolean DEF_SCROLL_SMOOTH      = true;
    public static final boolean DEF_SHADOW_ENABLE      = false;
    public static final String DEF_SHADOW_COLOR        = "#000000";
    public static final int    DEF_SHADOW_INTENSITY    = 40;
    public static final int    DEF_SHADOW_RADIUS       = 8;
    public static final boolean DEF_ANIMATIONS_ENABLE  = true;
    public static final int    DEF_TRANSITION_STYLE    = 0;
    public static final int    DEF_FONT_FAMILY         = 0;

    // ── Preset keys ───────────────────────────────────────────────────────
    public static final String KEY_PRESET_PREFIX = "preset_";

    // ── Instance ──────────────────────────────────────────────────────────
    private final SharedPreferences prefs;

    public ReelScreenCustomizationPrefs(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences.Editor edit() { return prefs.edit(); }

    // ── Typed getters ─────────────────────────────────────────────────────
    public int    getInt(String key, int def)       { return prefs.getInt(key, def); }
    public String getString(String key, String def) { return prefs.getString(key, def); }
    public boolean getBoolean(String key, boolean def) { return prefs.getBoolean(key, def); }

    // ── Typed setters ─────────────────────────────────────────────────────
    public void putInt(String key, int value)       { prefs.edit().putInt(key, value).apply(); }
    public void putString(String key, String value) { prefs.edit().putString(key, value).apply(); }
    public void putBoolean(String key, boolean value){ prefs.edit().putBoolean(key, value).apply(); }

    // ── Bulk helpers ──────────────────────────────────────────────────────

    /** Resets ALL keys to their defaults by clearing the SharedPreferences. */
    public void resetToDefault() {
        prefs.edit().clear().apply();
    }

    /** Exports all customization settings as a JSON string. */
    public String exportAsJson() {
        try {
            JSONObject json = new JSONObject();
            for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Integer)  json.put(entry.getKey(), (int)(Integer) v);
                else if (v instanceof Boolean) json.put(entry.getKey(), (boolean)(Boolean) v);
                else if (v instanceof String)  json.put(entry.getKey(), v.toString());
            }
            return json.toString(2);
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Imports settings from a JSON string exported by {@link #exportAsJson()}. */
    public boolean importFromJson(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);
            SharedPreferences.Editor editor = prefs.edit();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.get(key);
                if (value instanceof Integer)  editor.putInt(key, (Integer) value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof String)  editor.putString(key, (String) value);
            }
            editor.apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Saves current settings as a named preset. */
    public void savePreset(String name) {
        putString(KEY_PRESET_PREFIX + name, exportAsJson());
    }

    /** Loads a named preset. Returns false if not found. */
    public boolean loadPreset(String name) {
        String json = getString(KEY_PRESET_PREFIX + name, null);
        if (json == null || json.isEmpty()) return false;
        return importFromJson(json);
    }

    /** Returns all saved preset names. */
    public java.util.List<String> getPresetNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(KEY_PRESET_PREFIX)) {
                names.add(key.substring(KEY_PRESET_PREFIX.length()));
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    /** Deletes a named preset. */
    public void deletePreset(String name) {
        prefs.edit().remove(KEY_PRESET_PREFIX + name).apply();
    }
}
