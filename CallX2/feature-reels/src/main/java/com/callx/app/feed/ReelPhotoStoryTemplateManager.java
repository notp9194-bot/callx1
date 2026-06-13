package com.callx.app.feed;

import android.graphics.Color;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ReelPhotoStoryTemplateManager ── Story Template Pack System v6
 * ═══════════════════════════════════════════════════════════════════
 *
 * Manages pre-defined "Story Packs" that apply a complete visual theme
 * across all photos in a photo_slideshow reel with a single method call.
 *
 * Each template defines:
 *   • A rotating sequence of colour filters (cycles across photos)
 *   • A per-slide visual effect
 *   • Page transition type
 *   • Ken Burns intensity + direction sequence
 *   • Default caption style JSON
 *   • Default photo duration in ms
 *   • Whether breathing pulse is enabled
 *   • Display name + emoji + accent colour for the UI picker
 *
 * ── Available Packs ──────────────────────────────────────────────────────
 *   TRAVEL     🌍  warm / golden_hour alternating, cube transitions, cinematic KB
 *   BIRTHDAY   🎉  vivid / neon_pop, carousel, grain effect, bouncy
 *   FASHION    👗  noir / chrome alternating, fade, vignette, minimal caption
 *   AESTHETIC  ✨  dream / matte alternating, parallax, center_out KB, italic
 *   HYPE       ⚡  neon_pop / vivid, glitch transition, neon_glow effect
 *   VINTAGE    🎞  vintage / fade_film, stack transition, dust effect
 *   MINIMAL    🤍  normal / cool, reveal, none effect, short duration
 *   PARTY      🥳  vivid / rose alternating, flip3d, chrome_leak effect
 *   CINEMATIC  🎬  chrome / matte, morph, vignette, long duration, widescreen feel
 *   NATURE     🌿  warm / dream, origami, bokeh effect, gentle KB
 *   DARK       🌑  noir / matrix, swirl, scanlines, bold caption
 *   ROMANCE    💕  rose / dream, curtain, grain, italic caption
 */
public class ReelPhotoStoryTemplateManager {

    // ── Template definition ───────────────────────────────────────────────────

    public static class StoryTemplate {
        public final String   id;
        public final String   displayName;
        public final String   emoji;
        public final int      accentColor;
        public final String[] filterSequence;  // cycles across photos
        public final String   effect;
        public final String   transition;
        public final String   kenBurnsIntensity;
        public final String[] kbDirectionSequence;
        public final String   defaultCaptionStyle;  // JSON
        public final int      defaultDurationMs;
        public final boolean  pulseAnimation;

        public StoryTemplate(String id, String name, String emoji, int accent,
                             String[] filters, String effect, String transition,
                             String kbIntensity, String[] kbDirs, String captionStyle,
                             int durationMs, boolean pulse) {
            this.id                  = id;
            this.displayName         = name;
            this.emoji               = emoji;
            this.accentColor         = accent;
            this.filterSequence      = filters;
            this.effect              = effect;
            this.transition          = transition;
            this.kenBurnsIntensity   = kbIntensity;
            this.kbDirectionSequence = kbDirs;
            this.defaultCaptionStyle = captionStyle;
            this.defaultDurationMs   = durationMs;
            this.pulseAnimation      = pulse;
        }
    }

    // ── Template registry ─────────────────────────────────────────────────────

    private static final StoryTemplate[] TEMPLATES = {

        new StoryTemplate(
            "travel", "Travel", "🌍", 0xFFFF9800,
            new String[]{"warm","golden_hour","sunset","warm"},
            "none", "cube", "cinematic",
            new String[]{"tl_br","center_out","tr_bl","bottom_up"},
            "{\"color\":\"#FFFFFF\",\"bg\":\"#CC000000\",\"size\":14,\"bold\":true,\"font\":\"sans\"}",
            4000, false
        ),

        new StoryTemplate(
            "birthday", "Birthday", "🎉", 0xFFFF416C,
            new String[]{"vivid","neon_pop","vivid","rose"},
            "grain", "carousel", "dramatic",
            new String[]{"center_out","random","tl_br","tr_bl"},
            "{\"color\":\"#FFFF00\",\"bg\":\"#BB000000\",\"size\":15,\"bold\":true,\"font\":\"sans\"}",
            3000, true
        ),

        new StoryTemplate(
            "fashion", "Fashion", "👗", 0xFFCCCCCC,
            new String[]{"noir","chrome","bw","noir"},
            "vignette", "fade", "subtle",
            new String[]{"bottom_up","center_out","tl_br","bottom_up"},
            "{\"color\":\"#FFFFFF\",\"bg\":\"#AA000000\",\"size\":13,\"italic\":true,\"font\":\"serif\"}",
            4500, false
        ),

        new StoryTemplate(
            "aesthetic", "Aesthetic", "✨", 0xFFA855F7,
            new String[]{"dream","matte","fade_film","dream"},
            "grain", "parallax", "normal",
            new String[]{"center_out","center_out","tl_br","tr_bl"},
            "{\"color\":\"#FFFFFF\",\"bg\":\"#99000000\",\"size\":12,\"italic\":true,\"font\":\"serif\"}",
            4000, true
        ),

        new StoryTemplate(
            "hype", "Hype", "⚡", 0xFF00E5FF,
            new String[]{"neon_pop","vivid","neon_pop","chrome"},
            "neon_glow", "glitch", "dramatic",
            new String[]{"tl_br","br_tl","center_out","random"},
            "{\"color\":\"#00FFFF\",\"bg\":\"#BB000000\",\"size\":16,\"bold\":true,\"font\":\"sans\"}",
            2500, true
        ),

        new StoryTemplate(
            "vintage", "Vintage", "🎞", 0xFFAA7744,
            new String[]{"vintage","fade_film","matte","vintage"},
            "dust", "stack", "subtle",
            new String[]{"tl_br","tr_bl","tl_br","bottom_up"},
            "{\"color\":\"#F5E6C8\",\"bg\":\"#88000000\",\"size\":13,\"italic\":true,\"font\":\"serif\"}",
            5000, false
        ),

        new StoryTemplate(
            "minimal", "Minimal", "🤍", 0xFFFFFFFF,
            new String[]{"normal","cool","normal","normal"},
            "none", "reveal", "subtle",
            new String[]{"center_out","bottom_up","center_out","center_out"},
            "{\"color\":\"#FFFFFF\",\"bg\":\"#00000000\",\"size\":12,\"font\":\"sans\"}",
            3000, false
        ),

        new StoryTemplate(
            "party", "Party", "🥳", 0xFFFF00AA,
            new String[]{"vivid","rose","neon_pop","vivid"},
            "chrome_leak", "flip3d", "dramatic",
            new String[]{"tl_br","tr_bl","center_out","br_tl"},
            "{\"color\":\"#FFFFFF\",\"bg\":\"#BB000000\",\"size\":15,\"bold\":true,\"font\":\"sans\"}",
            2800, true
        ),

        new StoryTemplate(
            "cinematic", "Cinematic", "🎬", 0xFF888888,
            new String[]{"chrome","matte","chrome","noir"},
            "vignette", "morph", "cinematic",
            new String[]{"tl_br","tr_bl","bottom_up","center_out"},
            "{\"color\":\"#EEEEEE\",\"bg\":\"#AA000000\",\"size\":14,\"italic\":true,\"font\":\"serif\"}",
            5500, false
        ),

        new StoryTemplate(
            "nature", "Nature", "🌿", 0xFF4CAF50,
            new String[]{"warm","dream","warm","golden_hour"},
            "bokeh", "origami", "normal",
            new String[]{"center_out","tl_br","bottom_up","center_out"},
            "{\"color\":\"#FFFFFF\",\"bg\":\"#88000000\",\"size\":13,\"font\":\"sans\"}",
            4200, true
        ),

        new StoryTemplate(
            "dark", "Dark", "🌑", 0xFF333333,
            new String[]{"noir","matrix","bw","noir"},
            "scanlines", "swirl", "dramatic",
            new String[]{"tl_br","br_tl","tl_br","tr_bl"},
            "{\"color\":\"#00FF44\",\"bg\":\"#CC000000\",\"size\":14,\"bold\":true,\"font\":\"mono\"}",
            3500, false
        ),

        new StoryTemplate(
            "romance", "Romance", "💕", 0xFFFF6688,
            new String[]{"rose","dream","rose","fade_film"},
            "grain", "curtain", "normal",
            new String[]{"center_out","tl_br","center_out","tr_bl"},
            "{\"color\":\"#FFE0E8\",\"bg\":\"#99000000\",\"size\":13,\"italic\":true,\"font\":\"serif\"}",
            4000, true
        ),
    };

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns all available templates in display order. */
    public static StoryTemplate[] getAllTemplates() {
        return TEMPLATES.clone();
    }

    /** Find a template by id (null if not found). */
    @Nullable
    public static StoryTemplate getById(@NonNull String id) {
        for (StoryTemplate t : TEMPLATES) { if (t.id.equals(id)) return t; }
        return null;
    }

    /**
     * Applies a template to the given ReelModel in-place.
     *
     * This will set:
     *   • photoFilterList     — repeating filter sequence across all photos
     *   • photoEffectList     — same effect on every photo
     *   • transitionType      — global transition (all slides)
     *   • photoTransitionList — null (use global)
     *   • kenBurnsIntensity   — from template
     *   • photoKenBurnsDirectionList — cycling direction sequence
     *   • photoCaptionStyleList — same style for every photo that has a caption
     *   • photoDurationMs     — global default
     *   • photoPulseAnimation — from template
     *   • slideshowTemplateName — stores template id for reference
     *
     * Caption texts are NOT overwritten — only the style is updated.
     *
     * @param reel     The ReelModel to modify (must be photo_slideshow type).
     * @param template The template to apply.
     * @return true on success, false if reel is null or not a photo slideshow.
     */
    public static boolean applyTemplate(@NonNull ReelModel reel, @NonNull StoryTemplate template) {
        if (reel.photoUrls == null || reel.photoUrls.isEmpty()) return false;

        int n = reel.photoUrls.size();

        // Filters — cycle through the sequence
        reel.photoFilterList = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            reel.photoFilterList.add(template.filterSequence[i % template.filterSequence.length]);
        }
        reel.photoFilter = template.filterSequence[0]; // global fallback

        // Effect — same on all slides
        reel.photoEffectList = new ArrayList<>(Collections.nCopies(n, template.effect));

        // Transition
        reel.transitionType = template.transition;
        reel.photoTransitionList = null; // use global

        // Ken Burns
        reel.kenBurnsIntensity = template.kenBurnsIntensity;
        reel.photoKenBurnsDirectionList = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            reel.photoKenBurnsDirectionList.add(
                template.kbDirectionSequence[i % template.kbDirectionSequence.length]);
        }

        // Caption style — apply to every slide (texts untouched)
        reel.photoCaptionStyleList = new ArrayList<>(Collections.nCopies(n, template.defaultCaptionStyle));

        // Duration
        reel.photoDurationMs = template.defaultDurationMs;

        // Pulse
        reel.photoPulseAnimation = template.pulseAnimation;

        // Record the applied template
        reel.slideshowTemplateName = template.id;

        // Recompute total duration
        reel.recomputeTotalDuration();

        return true;
    }

    /**
     * Convenience: apply template by id string.
     * Returns false if id is not found or reel is invalid.
     */
    public static boolean applyTemplateById(@NonNull ReelModel reel, @NonNull String templateId) {
        StoryTemplate t = getById(templateId);
        if (t == null) return false;
        return applyTemplate(reel, t);
    }

    /**
     * Resets a ReelModel back to plain defaults (no template, normal filter, fade transition).
     */
    public static void clearTemplate(@NonNull ReelModel reel) {
        reel.slideshowTemplateName = null;
        reel.photoFilter           = "normal";
        reel.photoFilterList       = null;
        reel.photoEffectList       = null;
        reel.transitionType        = "fade";
        reel.photoTransitionList   = null;
        reel.kenBurnsIntensity     = "normal";
        reel.photoKenBurnsDirectionList = null;
        reel.photoCaptionStyleList = null;
        reel.photoPulseAnimation   = false;
        reel.recomputeTotalDuration();
    }
}
