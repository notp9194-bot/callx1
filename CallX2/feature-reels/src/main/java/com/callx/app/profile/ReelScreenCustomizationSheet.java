package com.callx.app.profile;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.callx.app.reels.R;
import com.callx.app.utils.RainbowStripColorPickerBottomSheet;

/**
 * ReelScreenCustomizationSheet — Ultra-advanced screen customization bottom sheet.
 * Contains 12 full sections + bonus features accessible from the 3-dot menu.
 *
 * Sections:
 *  1. 🎨 Theme & Background
 *  2. 👤 Profile Picture
 *  3. ✔ Username & Badge
 *  4. 📝 Bio (About)
 *  5. 📊 Stats Box
 *  6. 🔗 Links / Social Buttons
 *  7. 🎵 Audio / Music Player
 *  8. 🟣 Highlight Stories
 *  9. ⚙ Top Action Bar
 * 10. 📑 Tabs / Section Icons
 * 11. 🎬 Reel Grid / Content Area
 * 12. ⚡ Extra UI Settings
 * 🚀  BONUS: Export / Import / Reset / Presets
 */
public class ReelScreenCustomizationSheet extends BottomSheetDialogFragment {

    public interface OnCustomizationChangedListener {
        void onCustomizationChanged();
    }

    private ReelScreenCustomizationPrefs prefs;
    private OnCustomizationChangedListener listener;
    private int currentSection = 0;
    private FrameLayout contentArea;
    private final String[] SECTION_EMOJIS = {
        "🎨", "👤", "✔", "📝", "📊", "🔗", "🎵", "🟣", "⚙", "📑", "🎬", "⚡", "🚀"
    };
    private final String[] SECTION_LABELS = {
        "Theme", "Avatar", "Username", "Bio", "Stats", "Links",
        "Player", "Stories", "TopBar", "Tabs", "Grid", "Extra", "Bonus"
    };
    private Button[] sectionBtns;

    public static ReelScreenCustomizationSheet newInstance() {
        return new ReelScreenCustomizationSheet();
    }

    public void setOnCustomizationChangedListener(OnCustomizationChangedListener l) {
        this.listener = l;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_MaterialComponents_BottomSheetDialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        prefs = new ReelScreenCustomizationPrefs(ctx);

        // Root scroll container
        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(0, 0, 0, dp(32));
        sv.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Drag handle
        FrameLayout handleFrame = new FrameLayout(ctx);
        handleFrame.setPadding(0, dp(10), 0, dp(6));
        View handle = new View(ctx);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#CCCCCC"));
        handleBg.setCornerRadius(dp(3));
        handle.setBackground(handleBg);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER_HORIZONTAL);
        handleFrame.addView(handle, hlp);
        root.addView(handleFrame);

        // Title row
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(16), dp(4), dp(16), dp(12));
        TextView title = new TextView(ctx);
        title.setText("🎨 Screen Customization");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1A1A2E"));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleLp);
        root.addView(titleRow);

        // Section tab bar
        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setBackgroundColor(Color.parseColor("#F8F8F8"));
        LinearLayout tabBar = new LinearLayout(ctx);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        sectionBtns = new Button[SECTION_LABELS.length];
        for (int i = 0; i < SECTION_LABELS.length; i++) {
            final int idx = i;
            Button btn = new Button(ctx);
            btn.setText(SECTION_EMOJIS[i] + "\n" + SECTION_LABELS[i]);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            btn.setPadding(dp(8), dp(4), dp(8), dp(4));
            btn.setMinWidth(dp(54));
            btn.setMinHeight(dp(44));
            btn.setGravity(Gravity.CENTER);
            ((android.widget.TextView) btn).setLineSpacingMultiplier(1.1f);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setCornerRadius(dp(10));
            if (i == 0) {
                btnBg.setColor(Color.parseColor("#5B5BF6"));
                btn.setTextColor(Color.WHITE);
            } else {
                btnBg.setColor(Color.parseColor("#EFEFEF"));
                btn.setTextColor(Color.parseColor("#444444"));
            }
            btn.setBackground(btnBg);
            btn.setAllCaps(false);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.setMarginEnd(dp(6));
            btn.setOnClickListener(v -> {
                currentSection = idx;
                refreshSectionTabs();
                showSection(idx);
            });
            sectionBtns[i] = btn;
            tabBar.addView(btn, blp);
        }
        tabScroll.addView(tabBar);
        root.addView(tabScroll);

        // Divider
        View div = new View(ctx);
        div.setBackgroundColor(Color.parseColor("#E8E8E8"));
        root.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Content area
        contentArea = new FrameLayout(ctx);
        contentArea.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.addView(contentArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Bottom action buttons
        root.addView(buildBottomActions(ctx));

        // Show first section
        showSection(0);
        return sv;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null) {
            View bsView = getDialog().findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bsView != null) {
                BottomSheetBehavior<?> behavior = BottomSheetBehavior.from(bsView);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(false);
                behavior.setPeekHeight(dp(520));
                int screenH = requireActivity().getWindow().getDecorView().getHeight();
                bsView.getLayoutParams().height = screenH > 0 ? (int)(screenH * 0.92f) : dp(720);
                bsView.requestLayout();
            }
        }
    }

    // ── Section renderer ──────────────────────────────────────────────────

    private void showSection(int idx) {
        if (contentArea == null || getContext() == null) return;
        contentArea.removeAllViews();
        LinearLayout section;
        switch (idx) {
            case 0:  section = buildSection1_Theme();      break;
            case 1:  section = buildSection2_Avatar();     break;
            case 2:  section = buildSection3_Username();   break;
            case 3:  section = buildSection4_Bio();        break;
            case 4:  section = buildSection5_Stats();      break;
            case 5:  section = buildSection6_Links();      break;
            case 6:  section = buildSection7_Player();     break;
            case 7:  section = buildSection8_Highlights(); break;
            case 8:  section = buildSection9_TopBar();     break;
            case 9:  section = buildSection10_Tabs();      break;
            case 10: section = buildSection11_Grid();      break;
            case 11: section = buildSection12_Extra();     break;
            default: section = buildSectionBonus();        break;
        }
        contentArea.addView(section, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void refreshSectionTabs() {
        if (sectionBtns == null || getContext() == null) return;
        for (int i = 0; i < sectionBtns.length; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            if (i == currentSection) {
                bg.setColor(Color.parseColor("#5B5BF6"));
                sectionBtns[i].setTextColor(Color.WHITE);
            } else {
                bg.setColor(Color.parseColor("#EFEFEF"));
                sectionBtns[i].setTextColor(Color.parseColor("#444444"));
            }
            sectionBtns[i].setBackground(bg);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 1 — 🎨 Theme & Background
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection1_Theme() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "🎨 Theme & Background");

        addOptionRow(ll, "Theme Mode", new String[]{"Light", "Dark", "AMOLED"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_THEME_MODE, ReelScreenCustomizationPrefs.DEF_THEME_MODE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_THEME_MODE, v); notifyChanged(); });

        addOptionRow(ll, "Background Style", new String[]{"Solid", "Gradient", "Blur", "Image"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_BG_STYLE, ReelScreenCustomizationPrefs.DEF_BG_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_BG_STYLE, v); notifyChanged(); });

        addColorRow(ll, "Background Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_BG_COLOR, ReelScreenCustomizationPrefs.DEF_BG_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_BG_COLOR, hex); notifyChanged(); });

        addSectionSubHeader(ll, "Gradient");
        addColorRow(ll, "Gradient Color 1",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_GRADIENT_COLOR1, ReelScreenCustomizationPrefs.DEF_GRADIENT_COLOR1),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_GRADIENT_COLOR1, hex); notifyChanged(); });
        addColorRow(ll, "Gradient Color 2",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_GRADIENT_COLOR2, ReelScreenCustomizationPrefs.DEF_GRADIENT_COLOR2),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_GRADIENT_COLOR2, hex); notifyChanged(); });
        addSliderRow(ll, "Gradient Angle (°)", 0, 360,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_GRADIENT_ANGLE, ReelScreenCustomizationPrefs.DEF_GRADIENT_ANGLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_GRADIENT_ANGLE, v); notifyChanged(); });

        addSectionSubHeader(ll, "Blur / Image Overlay");
        addSliderRow(ll, "Blur Radius", 0, 25,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_BG_BLUR_RADIUS, ReelScreenCustomizationPrefs.DEF_BG_BLUR_RADIUS),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_BG_BLUR_RADIUS, v); notifyChanged(); });
        addColorRow(ll, "Overlay Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_OVERLAY_COLOR, ReelScreenCustomizationPrefs.DEF_OVERLAY_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_OVERLAY_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Overlay Opacity (%)", 0, 100,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_OVERLAY_OPACITY, ReelScreenCustomizationPrefs.DEF_OVERLAY_OPACITY),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_OVERLAY_OPACITY, v); notifyChanged(); });

        addSectionSubHeader(ll, "Glow Effect");
        addToggleRow(ll, "Glow Effect",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_GLOW_ENABLE, ReelScreenCustomizationPrefs.DEF_GLOW_ENABLE),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_GLOW_ENABLE, v); notifyChanged(); });
        addColorRow(ll, "Glow Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_GLOW_COLOR, ReelScreenCustomizationPrefs.DEF_GLOW_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_GLOW_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Glow Intensity (%)", 0, 100,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_GLOW_INTENSITY, ReelScreenCustomizationPrefs.DEF_GLOW_INTENSITY),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_GLOW_INTENSITY, v); notifyChanged(); });
        addSliderRow(ll, "Glow Radius (dp)", 0, 60,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_GLOW_RADIUS, ReelScreenCustomizationPrefs.DEF_GLOW_RADIUS),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_GLOW_RADIUS, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 2 — 👤 Profile Picture
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection2_Avatar() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "👤 Profile Picture");

        addToggleRow(ll, "Border Enable",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_ENABLE, ReelScreenCustomizationPrefs.DEF_AVATAR_BORDER_ENABLE),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_ENABLE, v); notifyChanged(); });
        addOptionRow(ll, "Border Style", new String[]{"Solid", "Gradient"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_STYLE, ReelScreenCustomizationPrefs.DEF_AVATAR_BORDER_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_STYLE, v); notifyChanged(); });
        addColorRow(ll, "Border Color 1",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_COLOR1, ReelScreenCustomizationPrefs.DEF_AVATAR_BORDER_COLOR1),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_COLOR1, hex); notifyChanged(); });
        addColorRow(ll, "Border Color 2 (gradient)",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_COLOR2, ReelScreenCustomizationPrefs.DEF_AVATAR_BORDER_COLOR2),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_COLOR2, hex); notifyChanged(); });
        addSliderRow(ll, "Border Width (dp)", 1, 10,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_WIDTH, ReelScreenCustomizationPrefs.DEF_AVATAR_BORDER_WIDTH),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_AVATAR_BORDER_WIDTH, v); notifyChanged(); });

        addSectionSubHeader(ll, "Outer Glow");
        addToggleRow(ll, "Outer Glow Enable",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_AVATAR_GLOW_ENABLE, ReelScreenCustomizationPrefs.DEF_AVATAR_GLOW_ENABLE),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_AVATAR_GLOW_ENABLE, v); notifyChanged(); });
        addColorRow(ll, "Glow Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_AVATAR_GLOW_COLOR, ReelScreenCustomizationPrefs.DEF_AVATAR_GLOW_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_AVATAR_GLOW_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Glow Intensity (%)", 0, 100,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_AVATAR_GLOW_INTENSITY, ReelScreenCustomizationPrefs.DEF_AVATAR_GLOW_INTENSITY),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_AVATAR_GLOW_INTENSITY, v); notifyChanged(); });

        addSectionSubHeader(ll, "Active Status Dot");
        addToggleRow(ll, "Status Dot Enable",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_AVATAR_STATUS_DOT, ReelScreenCustomizationPrefs.DEF_AVATAR_STATUS_DOT),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_AVATAR_STATUS_DOT, v); notifyChanged(); });
        addColorRow(ll, "Dot Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_AVATAR_DOT_COLOR, ReelScreenCustomizationPrefs.DEF_AVATAR_DOT_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_AVATAR_DOT_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Dot Size (dp)", 6, 20,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_AVATAR_DOT_SIZE, ReelScreenCustomizationPrefs.DEF_AVATAR_DOT_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_AVATAR_DOT_SIZE, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 3 — ✔ Username & Badge
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection3_Username() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "✔ Username & Badge");

        addColorRow(ll, "Username Text Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_USERNAME_COLOR, ReelScreenCustomizationPrefs.DEF_USERNAME_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_USERNAME_COLOR, hex); notifyChanged(); });
        addOptionRow(ll, "Username Font",
                new String[]{"System Default", "Poppins", "Inter", "Roboto", "Bold"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_USERNAME_FONT, ReelScreenCustomizationPrefs.DEF_USERNAME_FONT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_USERNAME_FONT, v); notifyChanged(); });
        addSliderRow(ll, "Username Size (sp)", 10, 28,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_USERNAME_SIZE, ReelScreenCustomizationPrefs.DEF_USERNAME_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_USERNAME_SIZE, v); notifyChanged(); });

        addSectionSubHeader(ll, "Verified Badge");
        addToggleRow(ll, "Show Badge",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_BADGE_SHOW, ReelScreenCustomizationPrefs.DEF_BADGE_SHOW),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_BADGE_SHOW, v); notifyChanged(); });
        addColorRow(ll, "Badge Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_BADGE_COLOR, ReelScreenCustomizationPrefs.DEF_BADGE_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_BADGE_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Badge Size (dp)", 12, 28,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_BADGE_SIZE, ReelScreenCustomizationPrefs.DEF_BADGE_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_BADGE_SIZE, v); notifyChanged(); });
        addOptionRow(ll, "Badge Position", new String[]{"Left", "Right"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_BADGE_POSITION, ReelScreenCustomizationPrefs.DEF_BADGE_POSITION),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_BADGE_POSITION, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 4 — 📝 Bio (About)
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection4_Bio() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "📝 Bio (About)");

        addColorRow(ll, "Bio Text Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_BIO_TEXT_COLOR, ReelScreenCustomizationPrefs.DEF_BIO_TEXT_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_BIO_TEXT_COLOR, hex); notifyChanged(); });
        addOptionRow(ll, "Bio Font", new String[]{"System Default", "Poppins", "Inter", "Roboto", "Bold"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_BIO_FONT, ReelScreenCustomizationPrefs.DEF_BIO_FONT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_BIO_FONT, v); notifyChanged(); });
        addSliderRow(ll, "Bio Size (sp)", 9, 20,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_BIO_SIZE, ReelScreenCustomizationPrefs.DEF_BIO_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_BIO_SIZE, v); notifyChanged(); });
        addSliderRow(ll, "Line Spacing (extra sp)", 0, 20,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_BIO_LINE_SPACING, ReelScreenCustomizationPrefs.DEF_BIO_LINE_SPACING),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_BIO_LINE_SPACING, v); notifyChanged(); });
        addSliderRow(ll, "Max Lines", 1, 10,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_BIO_MAX_LINES, ReelScreenCustomizationPrefs.DEF_BIO_MAX_LINES),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_BIO_MAX_LINES, v); notifyChanged(); });
        addSliderRow(ll, "Emoji Size (sp)", 8, 28,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_BIO_EMOJI_SIZE, ReelScreenCustomizationPrefs.DEF_BIO_EMOJI_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_BIO_EMOJI_SIZE, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 5 — 📊 Stats Box
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection5_Stats() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "📊 Stats Box (Posts / Followers / Following)");

        addOptionRow(ll, "Box Style", new String[]{"Default", "Glass", "Card", "Outline"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_BOX_STYLE, ReelScreenCustomizationPrefs.DEF_STATS_BOX_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_BOX_STYLE, v); notifyChanged(); });
        addOptionRow(ll, "Background Style", new String[]{"Solid", "Gradient", "Glassmorphism"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_BG_STYLE, ReelScreenCustomizationPrefs.DEF_STATS_BG_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_BG_STYLE, v); notifyChanged(); });
        addColorRow(ll, "Background Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_STATS_BG_COLOR, ReelScreenCustomizationPrefs.DEF_STATS_BG_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_STATS_BG_COLOR, hex); notifyChanged(); });
        addColorRow(ll, "Background Color 2 (gradient)",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_STATS_BG_COLOR2, ""),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_STATS_BG_COLOR2, hex); notifyChanged(); });
        addSliderRow(ll, "Opacity (%)", 0, 100,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_OPACITY, ReelScreenCustomizationPrefs.DEF_STATS_OPACITY),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_OPACITY, v); notifyChanged(); });
        addSliderRow(ll, "Corner Radius (dp)", 0, 32,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_CORNER_RADIUS, ReelScreenCustomizationPrefs.DEF_STATS_CORNER_RADIUS),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_CORNER_RADIUS, v); notifyChanged(); });

        addSectionSubHeader(ll, "Border");
        addToggleRow(ll, "Border Enable",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_STATS_BORDER_ENABLE, ReelScreenCustomizationPrefs.DEF_STATS_BORDER_ENABLE),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_STATS_BORDER_ENABLE, v); notifyChanged(); });
        addColorRow(ll, "Border Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_STATS_BORDER_COLOR, ReelScreenCustomizationPrefs.DEF_STATS_BORDER_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_STATS_BORDER_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Border Width (dp)", 1, 6,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_BORDER_WIDTH, ReelScreenCustomizationPrefs.DEF_STATS_BORDER_WIDTH),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_BORDER_WIDTH, v); notifyChanged(); });

        addSectionSubHeader(ll, "Divider");
        addColorRow(ll, "Divider Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_STATS_DIVIDER_COLOR, ReelScreenCustomizationPrefs.DEF_STATS_DIVIDER_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_STATS_DIVIDER_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Divider Width (dp)", 0, 4,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_DIVIDER_WIDTH, ReelScreenCustomizationPrefs.DEF_STATS_DIVIDER_WIDTH),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_DIVIDER_WIDTH, v); notifyChanged(); });

        addSectionSubHeader(ll, "Number Text");
        addColorRow(ll, "Number Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_STATS_NUM_COLOR, ReelScreenCustomizationPrefs.DEF_STATS_NUM_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_STATS_NUM_COLOR, hex); notifyChanged(); });
        addOptionRow(ll, "Number Font", new String[]{"System Default", "Poppins", "Inter", "Roboto", "Bold"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_NUM_FONT, ReelScreenCustomizationPrefs.DEF_STATS_NUM_FONT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_NUM_FONT, v); notifyChanged(); });
        addSliderRow(ll, "Number Size (sp)", 12, 26,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_NUM_SIZE, ReelScreenCustomizationPrefs.DEF_STATS_NUM_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_NUM_SIZE, v); notifyChanged(); });

        addSectionSubHeader(ll, "Label Text");
        addColorRow(ll, "Label Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_STATS_LABEL_COLOR, ReelScreenCustomizationPrefs.DEF_STATS_LABEL_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_STATS_LABEL_COLOR, hex); notifyChanged(); });
        addOptionRow(ll, "Label Font", new String[]{"System Default", "Poppins", "Inter", "Roboto", "Bold"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_LABEL_FONT, ReelScreenCustomizationPrefs.DEF_STATS_LABEL_FONT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_LABEL_FONT, v); notifyChanged(); });
        addSliderRow(ll, "Label Size (sp)", 9, 18,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_LABEL_SIZE, ReelScreenCustomizationPrefs.DEF_STATS_LABEL_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_LABEL_SIZE, v); notifyChanged(); });
        addSliderRow(ll, "Padding (dp)", 4, 24,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_STATS_PADDING, ReelScreenCustomizationPrefs.DEF_STATS_PADDING),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_STATS_PADDING, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 6 — 🔗 Links / Social Buttons
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection6_Links() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "🔗 Links / Social Buttons");

        addOptionRow(ll, "Button Style", new String[]{"Pill", "Outline", "Filled", "Glass"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_LINK_BTN_STYLE, ReelScreenCustomizationPrefs.DEF_LINK_BTN_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_LINK_BTN_STYLE, v); notifyChanged(); });
        addOptionRow(ll, "Button Shape", new String[]{"Rounded", "Square"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_LINK_BTN_SHAPE, ReelScreenCustomizationPrefs.DEF_LINK_BTN_SHAPE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_LINK_BTN_SHAPE, v); notifyChanged(); });
        addColorRow(ll, "Background Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_LINK_BG_COLOR, ReelScreenCustomizationPrefs.DEF_LINK_BG_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_LINK_BG_COLOR, hex); notifyChanged(); });
        addColorRow(ll, "Background Color 2 (gradient)",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_LINK_BG_COLOR2, ""),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_LINK_BG_COLOR2, hex); notifyChanged(); });
        addColorRow(ll, "Border Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_LINK_BORDER_COLOR, ReelScreenCustomizationPrefs.DEF_LINK_BORDER_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_LINK_BORDER_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Border Width (dp)", 0, 4,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_LINK_BORDER_WIDTH, ReelScreenCustomizationPrefs.DEF_LINK_BORDER_WIDTH),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_LINK_BORDER_WIDTH, v); notifyChanged(); });

        addSectionSubHeader(ll, "Icon");
        addOptionRow(ll, "Icon Style", new String[]{"Filled", "Outline"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_LINK_ICON_STYLE, ReelScreenCustomizationPrefs.DEF_LINK_ICON_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_LINK_ICON_STYLE, v); notifyChanged(); });
        addColorRow(ll, "Icon Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_LINK_ICON_COLOR, ReelScreenCustomizationPrefs.DEF_LINK_ICON_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_LINK_ICON_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Icon Size (dp)", 12, 28,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_LINK_ICON_SIZE, ReelScreenCustomizationPrefs.DEF_LINK_ICON_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_LINK_ICON_SIZE, v); notifyChanged(); });

        addSectionSubHeader(ll, "Text");
        addColorRow(ll, "Text Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_LINK_TEXT_COLOR, ReelScreenCustomizationPrefs.DEF_LINK_TEXT_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_LINK_TEXT_COLOR, hex); notifyChanged(); });
        addOptionRow(ll, "Text Font", new String[]{"System Default", "Poppins", "Inter", "Roboto", "Bold"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_LINK_TEXT_FONT, ReelScreenCustomizationPrefs.DEF_LINK_TEXT_FONT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_LINK_TEXT_FONT, v); notifyChanged(); });
        addSliderRow(ll, "Text Size (sp)", 9, 18,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_LINK_TEXT_SIZE, ReelScreenCustomizationPrefs.DEF_LINK_TEXT_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_LINK_TEXT_SIZE, v); notifyChanged(); });
        addSliderRow(ll, "Button Padding (dp)", 4, 20,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_LINK_PADDING, ReelScreenCustomizationPrefs.DEF_LINK_PADDING),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_LINK_PADDING, v); notifyChanged(); });
        addSliderRow(ll, "Button Spacing (dp)", 4, 20,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_LINK_SPACING, ReelScreenCustomizationPrefs.DEF_LINK_SPACING),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_LINK_SPACING, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 7 — 🎵 Audio / Music Player
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection7_Player() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "🎵 Audio / Music Player");

        addToggleRow(ll, "Show Player",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_PLAYER_SHOW, ReelScreenCustomizationPrefs.DEF_PLAYER_SHOW),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_PLAYER_SHOW, v); notifyChanged(); });
        addOptionRow(ll, "Player Style", new String[]{"Default", "Modern", "Minimal"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_PLAYER_STYLE, ReelScreenCustomizationPrefs.DEF_PLAYER_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_PLAYER_STYLE, v); notifyChanged(); });
        addOptionRow(ll, "Background Style", new String[]{"Color", "Gradient", "Glass"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_PLAYER_BG_STYLE, ReelScreenCustomizationPrefs.DEF_PLAYER_BG_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_PLAYER_BG_STYLE, v); notifyChanged(); });
        addColorRow(ll, "Background Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_PLAYER_BG_COLOR, ReelScreenCustomizationPrefs.DEF_PLAYER_BG_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_PLAYER_BG_COLOR, hex); notifyChanged(); });
        addColorRow(ll, "Background Color 2 (gradient)",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_PLAYER_BG_COLOR2, ""),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_PLAYER_BG_COLOR2, hex); notifyChanged(); });
        addSliderRow(ll, "Corner Radius (dp)", 0, 32,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_PLAYER_CORNER_RADIUS, ReelScreenCustomizationPrefs.DEF_PLAYER_CORNER_RADIUS),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_PLAYER_CORNER_RADIUS, v); notifyChanged(); });
        addColorRow(ll, "Icon Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_PLAYER_ICON_COLOR, ReelScreenCustomizationPrefs.DEF_PLAYER_ICON_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_PLAYER_ICON_COLOR, hex); notifyChanged(); });
        addColorRow(ll, "Waveform Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_PLAYER_WAVEFORM_COLOR, ReelScreenCustomizationPrefs.DEF_PLAYER_WAVEFORM_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_PLAYER_WAVEFORM_COLOR, hex); notifyChanged(); });
        addColorRow(ll, "Song Name Text Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_PLAYER_TEXT_COLOR, ReelScreenCustomizationPrefs.DEF_PLAYER_TEXT_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_PLAYER_TEXT_COLOR, hex); notifyChanged(); });
        addOptionRow(ll, "Font", new String[]{"System Default", "Poppins", "Inter", "Roboto", "Bold"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_PLAYER_FONT, ReelScreenCustomizationPrefs.DEF_PLAYER_FONT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_PLAYER_FONT, v); notifyChanged(); });
        addSliderRow(ll, "Font Size (sp)", 9, 18,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_PLAYER_FONT_SIZE, ReelScreenCustomizationPrefs.DEF_PLAYER_FONT_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_PLAYER_FONT_SIZE, v); notifyChanged(); });
        addColorRow(ll, "Play Button Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_PLAYER_PLAY_BTN_COLOR, ReelScreenCustomizationPrefs.DEF_PLAYER_PLAY_BTN_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_PLAYER_PLAY_BTN_COLOR, hex); notifyChanged(); });
        addColorRow(ll, "Progress Bar Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_PLAYER_PROGRESS_COLOR, ReelScreenCustomizationPrefs.DEF_PLAYER_PROGRESS_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_PLAYER_PROGRESS_COLOR, hex); notifyChanged(); });
        addColorRow(ll, "Progress Background",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_PLAYER_PROGRESS_BG, ReelScreenCustomizationPrefs.DEF_PLAYER_PROGRESS_BG),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_PLAYER_PROGRESS_BG, hex); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 8 — 🟣 Highlight Stories
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection8_Highlights() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "🟣 Highlight Stories");

        addOptionRow(ll, "Border Style", new String[]{"Solid", "Gradient"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_HL_BORDER_STYLE, ReelScreenCustomizationPrefs.DEF_HL_BORDER_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_HL_BORDER_STYLE, v); notifyChanged(); });
        addColorRow(ll, "Border Color 1",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_HL_BORDER_COLOR1, ReelScreenCustomizationPrefs.DEF_HL_BORDER_COLOR1),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_HL_BORDER_COLOR1, hex); notifyChanged(); });
        addColorRow(ll, "Border Color 2 (gradient)",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_HL_BORDER_COLOR2, ReelScreenCustomizationPrefs.DEF_HL_BORDER_COLOR2),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_HL_BORDER_COLOR2, hex); notifyChanged(); });
        addSliderRow(ll, "Border Width (dp)", 1, 8,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_HL_BORDER_WIDTH, ReelScreenCustomizationPrefs.DEF_HL_BORDER_WIDTH),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_HL_BORDER_WIDTH, v); notifyChanged(); });

        addSectionSubHeader(ll, "Glow");
        addToggleRow(ll, "Glow Enable",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_HL_GLOW_ENABLE, ReelScreenCustomizationPrefs.DEF_HL_GLOW_ENABLE),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_HL_GLOW_ENABLE, v); notifyChanged(); });
        addColorRow(ll, "Glow Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_HL_GLOW_COLOR, ReelScreenCustomizationPrefs.DEF_HL_GLOW_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_HL_GLOW_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Glow Size (dp)", 2, 20,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_HL_GLOW_SIZE, ReelScreenCustomizationPrefs.DEF_HL_GLOW_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_HL_GLOW_SIZE, v); notifyChanged(); });

        addSectionSubHeader(ll, "Title");
        addColorRow(ll, "Title Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_HL_TITLE_COLOR, ReelScreenCustomizationPrefs.DEF_HL_TITLE_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_HL_TITLE_COLOR, hex); notifyChanged(); });
        addOptionRow(ll, "Title Font", new String[]{"System Default", "Poppins", "Inter", "Roboto", "Bold"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_HL_TITLE_FONT, ReelScreenCustomizationPrefs.DEF_HL_TITLE_FONT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_HL_TITLE_FONT, v); notifyChanged(); });
        addSliderRow(ll, "Title Size (sp)", 8, 16,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_HL_TITLE_SIZE, ReelScreenCustomizationPrefs.DEF_HL_TITLE_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_HL_TITLE_SIZE, v); notifyChanged(); });
        addOptionRow(ll, "Title Position", new String[]{"Below", "Inside"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_HL_TITLE_POSITION, ReelScreenCustomizationPrefs.DEF_HL_TITLE_POSITION),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_HL_TITLE_POSITION, v); notifyChanged(); });
        addSliderRow(ll, "Spacing Between Items (dp)", 4, 24,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_HL_SPACING, ReelScreenCustomizationPrefs.DEF_HL_SPACING),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_HL_SPACING, v); notifyChanged(); });
        addColorRow(ll, "Add Button Icon Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_HL_ADD_BTN_ICON_COLOR, ReelScreenCustomizationPrefs.DEF_HL_ADD_BTN_ICON_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_HL_ADD_BTN_ICON_COLOR, hex); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 9 — ⚙ Top Action Bar (Icons)
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection9_TopBar() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "⚙ Top Action Bar (Icons)");

        addColorRow(ll, "Icon Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_TOPBAR_ICON_COLOR, ReelScreenCustomizationPrefs.DEF_TOPBAR_ICON_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_TOPBAR_ICON_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Icon Size (dp)", 16, 36,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_ICON_SIZE, ReelScreenCustomizationPrefs.DEF_TOPBAR_ICON_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_ICON_SIZE, v); notifyChanged(); });
        addOptionRow(ll, "Icon Style", new String[]{"Outline", "Filled"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_ICON_STYLE, ReelScreenCustomizationPrefs.DEF_TOPBAR_ICON_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_ICON_STYLE, v); notifyChanged(); });

        addSectionSubHeader(ll, "Background Circle");
        addToggleRow(ll, "Circle Background Enable",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_TOPBAR_CIRCLE_ENABLE, ReelScreenCustomizationPrefs.DEF_TOPBAR_CIRCLE_ENABLE),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_TOPBAR_CIRCLE_ENABLE, v); notifyChanged(); });
        addColorRow(ll, "Circle Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_TOPBAR_CIRCLE_COLOR, ReelScreenCustomizationPrefs.DEF_TOPBAR_CIRCLE_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_TOPBAR_CIRCLE_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Circle Size (dp)", 28, 52,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_CIRCLE_SIZE, ReelScreenCustomizationPrefs.DEF_TOPBAR_CIRCLE_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_CIRCLE_SIZE, v); notifyChanged(); });
        addSliderRow(ll, "Icon Spacing (dp)", 0, 16,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_ICON_SPACING, ReelScreenCustomizationPrefs.DEF_TOPBAR_ICON_SPACING),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_ICON_SPACING, v); notifyChanged(); });
        addOptionRow(ll, "3-dot Menu Style", new String[]{"Vertical Dots", "Horizontal Lines"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_MENU_STYLE, ReelScreenCustomizationPrefs.DEF_TOPBAR_MENU_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TOPBAR_MENU_STYLE, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 10 — 📑 Tabs / Section Icons
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection10_Tabs() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "📑 Tabs / Section Icons");

        addOptionRow(ll, "Tab Icon Style", new String[]{"Default", "Filled", "Outline"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TAB_ICON_STYLE, ReelScreenCustomizationPrefs.DEF_TAB_ICON_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TAB_ICON_STYLE, v); notifyChanged(); });
        addColorRow(ll, "Active Tab Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_TAB_ACTIVE_COLOR, ReelScreenCustomizationPrefs.DEF_TAB_ACTIVE_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_TAB_ACTIVE_COLOR, hex); notifyChanged(); });
        addColorRow(ll, "Inactive Tab Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_TAB_INACTIVE_COLOR, ReelScreenCustomizationPrefs.DEF_TAB_INACTIVE_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_TAB_INACTIVE_COLOR, hex); notifyChanged(); });

        addSectionSubHeader(ll, "Indicator");
        addOptionRow(ll, "Indicator Style", new String[]{"Line", "Dot", "None"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TAB_INDICATOR_STYLE, ReelScreenCustomizationPrefs.DEF_TAB_INDICATOR_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TAB_INDICATOR_STYLE, v); notifyChanged(); });
        addColorRow(ll, "Indicator Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_TAB_INDICATOR_COLOR, ReelScreenCustomizationPrefs.DEF_TAB_INDICATOR_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_TAB_INDICATOR_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Indicator Height (dp)", 1, 8,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TAB_INDICATOR_HEIGHT, ReelScreenCustomizationPrefs.DEF_TAB_INDICATOR_HEIGHT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TAB_INDICATOR_HEIGHT, v); notifyChanged(); });
        addSliderRow(ll, "Indicator Width (dp, 0=full)", 0, 80,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TAB_INDICATOR_WIDTH, ReelScreenCustomizationPrefs.DEF_TAB_INDICATOR_WIDTH),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TAB_INDICATOR_WIDTH, v); notifyChanged(); });

        addSectionSubHeader(ll, "Tab Bar");
        addColorRow(ll, "Tab Bar Background",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_TAB_BAR_BG_COLOR, ReelScreenCustomizationPrefs.DEF_TAB_BAR_BG_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_TAB_BAR_BG_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Tab Bar Height (dp)", 36, 64,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TAB_BAR_HEIGHT, ReelScreenCustomizationPrefs.DEF_TAB_BAR_HEIGHT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TAB_BAR_HEIGHT, v); notifyChanged(); });
        addSliderRow(ll, "Icon Size (dp)", 16, 32,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TAB_ICON_SIZE, ReelScreenCustomizationPrefs.DEF_TAB_ICON_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TAB_ICON_SIZE, v); notifyChanged(); });
        addSliderRow(ll, "Icon Spacing (dp)", 0, 20,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TAB_ICON_SPACING, ReelScreenCustomizationPrefs.DEF_TAB_ICON_SPACING),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TAB_ICON_SPACING, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 11 — 🎬 Reel Grid / Content Area
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection11_Grid() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "🎬 Reel Grid / Content Area");

        addColorRow(ll, "Grid Background Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_GRID_BG_COLOR, ReelScreenCustomizationPrefs.DEF_GRID_BG_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_GRID_BG_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Thumbnail Corner Radius (dp)", 0, 20,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_GRID_THUMB_RADIUS, ReelScreenCustomizationPrefs.DEF_GRID_THUMB_RADIUS),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_GRID_THUMB_RADIUS, v); notifyChanged(); });
        addSliderRow(ll, "Grid Spacing Vertical (dp)", 0, 8,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_GRID_SPACING_V, ReelScreenCustomizationPrefs.DEF_GRID_SPACING_V),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_GRID_SPACING_V, v); notifyChanged(); });
        addSliderRow(ll, "Grid Spacing Horizontal (dp)", 0, 8,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_GRID_SPACING_H, ReelScreenCustomizationPrefs.DEF_GRID_SPACING_H),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_GRID_SPACING_H, v); notifyChanged(); });
        addColorRow(ll, "Play Icon Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_GRID_PLAY_ICON_COLOR, ReelScreenCustomizationPrefs.DEF_GRID_PLAY_ICON_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_GRID_PLAY_ICON_COLOR, hex); notifyChanged(); });
        addOptionRow(ll, "Grid Layout", new String[]{"2 Columns", "3 Columns"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_GRID_COLUMNS, ReelScreenCustomizationPrefs.DEF_GRID_COLUMNS) - 2,
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_GRID_COLUMNS, v + 2); notifyChanged(); });
        addColorRow(ll, "Text Overlay Color (Views, etc.)",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_GRID_OVERLAY_COLOR, ReelScreenCustomizationPrefs.DEF_GRID_OVERLAY_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_GRID_OVERLAY_COLOR, hex); notifyChanged(); });
        addOptionRow(ll, "Overlay Font", new String[]{"System Default", "Poppins", "Inter", "Roboto", "Bold"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_GRID_OVERLAY_FONT, ReelScreenCustomizationPrefs.DEF_GRID_OVERLAY_FONT),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_GRID_OVERLAY_FONT, v); notifyChanged(); });
        addSliderRow(ll, "Overlay Font Size (sp)", 8, 18,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_GRID_OVERLAY_SIZE, ReelScreenCustomizationPrefs.DEF_GRID_OVERLAY_SIZE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_GRID_OVERLAY_SIZE, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION 12 — ⚡ Extra UI Settings
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSection12_Extra() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "⚡ Extra UI Settings");

        addSliderRow(ll, "Screen Padding (dp)", 0, 24,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_SCREEN_PADDING, ReelScreenCustomizationPrefs.DEF_SCREEN_PADDING),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_SCREEN_PADDING, v); notifyChanged(); });
        addToggleRow(ll, "Smooth Scrolling",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_SCROLL_SMOOTH, ReelScreenCustomizationPrefs.DEF_SCROLL_SMOOTH),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_SCROLL_SMOOTH, v); notifyChanged(); });

        addSectionSubHeader(ll, "Shadow");
        addToggleRow(ll, "Shadow Enable",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_SHADOW_ENABLE, ReelScreenCustomizationPrefs.DEF_SHADOW_ENABLE),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_SHADOW_ENABLE, v); notifyChanged(); });
        addColorRow(ll, "Shadow Color",
                prefs.getString(ReelScreenCustomizationPrefs.KEY_SHADOW_COLOR, ReelScreenCustomizationPrefs.DEF_SHADOW_COLOR),
                hex -> { prefs.putString(ReelScreenCustomizationPrefs.KEY_SHADOW_COLOR, hex); notifyChanged(); });
        addSliderRow(ll, "Shadow Intensity (%)", 0, 100,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_SHADOW_INTENSITY, ReelScreenCustomizationPrefs.DEF_SHADOW_INTENSITY),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_SHADOW_INTENSITY, v); notifyChanged(); });
        addSliderRow(ll, "Shadow Radius (dp)", 2, 24,
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_SHADOW_RADIUS, ReelScreenCustomizationPrefs.DEF_SHADOW_RADIUS),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_SHADOW_RADIUS, v); notifyChanged(); });

        addSectionSubHeader(ll, "Animations");
        addToggleRow(ll, "Animations Enable",
                prefs.getBoolean(ReelScreenCustomizationPrefs.KEY_ANIMATIONS_ENABLE, ReelScreenCustomizationPrefs.DEF_ANIMATIONS_ENABLE),
                v -> { prefs.putBoolean(ReelScreenCustomizationPrefs.KEY_ANIMATIONS_ENABLE, v); notifyChanged(); });
        addOptionRow(ll, "Transition Style", new String[]{"Fade", "Slide", "Zoom"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_TRANSITION_STYLE, ReelScreenCustomizationPrefs.DEF_TRANSITION_STYLE),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_TRANSITION_STYLE, v); notifyChanged(); });

        addSectionSubHeader(ll, "Global Font");
        addOptionRow(ll, "Font Family", new String[]{"System Default", "Poppins", "Inter", "Roboto"},
                prefs.getInt(ReelScreenCustomizationPrefs.KEY_FONT_FAMILY, ReelScreenCustomizationPrefs.DEF_FONT_FAMILY),
                v -> { prefs.putInt(ReelScreenCustomizationPrefs.KEY_FONT_FAMILY, v); notifyChanged(); });
        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SECTION BONUS — 🚀 Export / Import / Reset / Presets
    // ══════════════════════════════════════════════════════════════════════
    private LinearLayout buildSectionBonus() {
        Context ctx = requireContext();
        LinearLayout ll = newSectionLL(ctx);
        addSectionHeader(ll, "🚀 Bonus / Pro Features");

        // Export
        addActionButton(ll, "📤 Export Settings (JSON)", "#5B5BF6", () -> {
            if (getContext() == null) return;
            String json = prefs.exportAsJson();
            // Copy to clipboard
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Customization", json));
                Toast.makeText(requireContext(), "Settings copied to clipboard!", Toast.LENGTH_SHORT).show();
            }
            // Show in dialog
            showJsonDialog("Export Settings", json, false);
        });

        // Import
        addActionButton(ll, "📥 Import Settings (JSON)", "#22D3A6", () -> {
            if (getContext() == null) return;
            showJsonDialog("Import Settings — Paste JSON below", "", true);
        });

        // Reset to Default
        addActionButton(ll, "🔄 Reset to Default", "#FF6B6B", () -> {
            if (getContext() == null) return;
            new AlertDialog.Builder(requireContext())
                    .setTitle("Reset All Settings?")
                    .setMessage("All customization will be reset to defaults. This cannot be undone.")
                    .setPositiveButton("Reset", (d, w) -> {
                        prefs.resetToDefault();
                        notifyChanged();
                        Toast.makeText(requireContext(), "Reset to defaults!", Toast.LENGTH_SHORT).show();
                        dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        addDivider(ll);
        addSectionSubHeader(ll, "Presets");

        // Save Preset
        addActionButton(ll, "💾 Save Current as Preset", "#5B5BF6", () -> {
            if (getContext() == null) return;
            showPresetNameDialog();
        });

        // Load Preset
        addActionButton(ll, "📂 Load Preset", "#22D3A6", () -> {
            if (getContext() == null) return;
            showLoadPresetDialog();
        });

        // Delete Preset
        addActionButton(ll, "🗑️ Delete Preset", "#888888", () -> {
            if (getContext() == null) return;
            showDeletePresetDialog();
        });

        addDivider(ll);
        // Info
        TextView info = new TextView(ctx);
        info.setText("💡 All settings are saved instantly and applied on next screen open. "
                + "Use Export/Import to share themes with others.\n\n"
                + "Pro tip: Create multiple presets for different moods (Dark Night, Vibrant Day, Minimal, etc.)");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setTextColor(Color.parseColor("#666666"));
        info.setPadding(0, dp(8), 0, dp(4));
        ll.addView(info);

        return ll;
    }

    // ── Dialog helpers ─────────────────────────────────────────────────────

    private void showJsonDialog(String title, String content, boolean isImport) {
        if (getContext() == null) return;
        Context ctx = requireContext();
        EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(6);
        et.setMaxLines(12);
        et.setText(content);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        et.setTypeface(Typeface.MONOSPACE);
        ScrollView sv = new ScrollView(ctx);
        sv.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(et);
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(sv);
        if (isImport) {
            builder.setPositiveButton("Import", (d, w) -> {
                String json = et.getText().toString().trim();
                boolean ok = prefs.importFromJson(json);
                Toast.makeText(requireContext(),
                        ok ? "Settings imported successfully!" : "Invalid JSON format",
                        Toast.LENGTH_SHORT).show();
                if (ok) { notifyChanged(); dismiss(); }
            }).setNegativeButton("Cancel", null);
        } else {
            builder.setPositiveButton("Close", null);
        }
        builder.show();
    }

    private void showPresetNameDialog() {
        if (getContext() == null) return;
        Context ctx = requireContext();
        EditText et = new EditText(ctx);
        et.setHint("Preset name (e.g. Dark Night)");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        FrameLayout fl = new FrameLayout(ctx);
        fl.setPadding(dp(20), dp(12), dp(20), 0);
        fl.addView(et);
        new AlertDialog.Builder(ctx)
                .setTitle("💾 Save Preset")
                .setView(fl)
                .setPositiveButton("Save", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) { Toast.makeText(requireContext(), "Enter a preset name", Toast.LENGTH_SHORT).show(); return; }
                    prefs.savePreset(name);
                    Toast.makeText(requireContext(), "Preset \"" + name + "\" saved!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLoadPresetDialog() {
        if (getContext() == null) return;
        java.util.List<String> names = prefs.getPresetNames();
        if (names.isEmpty()) {
            Toast.makeText(requireContext(), "No presets saved yet. Save one first!", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] arr = names.toArray(new String[0]);
        new AlertDialog.Builder(requireContext())
                .setTitle("📂 Load Preset")
                .setItems(arr, (d, i) -> {
                    boolean ok = prefs.loadPreset(arr[i]);
                    Toast.makeText(requireContext(),
                            ok ? "Preset \"" + arr[i] + "\" loaded!" : "Failed to load preset",
                            Toast.LENGTH_SHORT).show();
                    if (ok) { notifyChanged(); dismiss(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDeletePresetDialog() {
        if (getContext() == null) return;
        java.util.List<String> names = prefs.getPresetNames();
        if (names.isEmpty()) {
            Toast.makeText(requireContext(), "No presets to delete.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] arr = names.toArray(new String[0]);
        new AlertDialog.Builder(requireContext())
                .setTitle("🗑️ Delete Preset")
                .setItems(arr, (d, i) -> {
                    prefs.deletePreset(arr[i]);
                    Toast.makeText(requireContext(), "Preset \"" + arr[i] + "\" deleted.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Bottom action buttons ─────────────────────────────────────────────

    private LinearLayout buildBottomActions(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(4));
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button resetBtn = makeStyledButton(ctx, "Reset", "#FF6B6B");
        resetBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(ctx)
                    .setTitle("Reset All?")
                    .setMessage("Reset ALL customization to defaults?")
                    .setPositiveButton("Reset", (d, w) -> {
                        prefs.resetToDefault();
                        notifyChanged();
                        Toast.makeText(ctx, "Reset to defaults!", Toast.LENGTH_SHORT).show();
                        dismiss();
                    })
                    .setNegativeButton("Cancel", null).show();
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        resetLp.setMarginEnd(dp(8));
        row.addView(resetBtn, resetLp);

        Button applyBtn = makeStyledButton(ctx, "✅ Apply", "#5B5BF6");
        applyBtn.setOnClickListener(v -> {
            notifyChanged();
            Toast.makeText(ctx, "Customization applied!", Toast.LENGTH_SHORT).show();
            dismiss();
        });
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        row.addView(applyBtn, applyLp);

        return row;
    }

    // ── UI building helpers ────────────────────────────────────────────────

    interface IntConsumer  { void accept(int v); }
    interface BoolConsumer { void accept(boolean v); }
    interface StrConsumer  { void accept(String v); }

    private LinearLayout newSectionLL(Context ctx) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    private void addSectionHeader(LinearLayout ll, String text) {
        Context ctx = ll.getContext();
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.parseColor("#1A1A2E"));
        tv.setPadding(0, 0, 0, dp(10));
        ll.addView(tv);
    }

    private void addSectionSubHeader(LinearLayout ll, String text) {
        Context ctx = ll.getContext();
        // Divider + sub-title
        View div = new View(ctx);
        div.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(10);
        dlp.bottomMargin = dp(8);
        ll.addView(div, dlp);
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.parseColor("#555577"));
        tv.setPadding(0, 0, 0, dp(6));
        ll.addView(tv);
    }

    private void addDivider(LinearLayout ll) {
        View div = new View(ll.getContext());
        div.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(12);
        dlp.bottomMargin = dp(12);
        ll.addView(div, dlp);
    }

    /** Slider row: label, current value badge, SeekBar */
    private void addSliderRow(LinearLayout ll, String label, int min, int max, int current, IntConsumer onChange) {
        Context ctx = ll.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(10);

        LinearLayout labelRow = new LinearLayout(ctx);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lv = new TextView(ctx);
        lv.setText(label);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lv.setTextColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelRow.addView(lv, lvLp);

        TextView valBadge = new TextView(ctx);
        valBadge.setText(String.valueOf(current));
        valBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        valBadge.setTextColor(Color.parseColor("#5B5BF6"));
        valBadge.setTypeface(null, Typeface.BOLD);
        valBadge.setMinWidth(dp(32));
        valBadge.setGravity(Gravity.CENTER);
        labelRow.addView(valBadge);
        row.addView(labelRow);

        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgress(current - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int val = p + min;
                valBadge.setText(String.valueOf(val));
                onChange.accept(val);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ll.addView(row, rlp);
    }

    /** Toggle row: label on left, Switch on right */
    private void addToggleRow(LinearLayout ll, String label, boolean current, BoolConsumer onChange) {
        Context ctx = ll.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        rlp.bottomMargin = dp(4);

        TextView lv = new TextView(ctx);
        lv.setText(label);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lv.setTextColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(lv, lvLp);

        Switch sw = new Switch(ctx);
        sw.setChecked(current);
        sw.setOnCheckedChangeListener((btn, checked) -> onChange.accept(checked));
        row.addView(sw);
        ll.addView(row, rlp);
    }

    /** Option row: label + radio/chip group */
    private void addOptionRow(LinearLayout ll, String label, String[] options, int currentIdx, IntConsumer onChange) {
        Context ctx = ll.getContext();
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = dp(10);

        TextView lv = new TextView(ctx);
        lv.setText(label);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lv.setTextColor(Color.parseColor("#333333"));
        lv.setPadding(0, 0, 0, dp(4));
        wrapper.addView(lv);

        // Scrollable chips row
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, 0, 0, 0);
        final int[] sel = {currentIdx};
        final TextView[] chipViews = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView chip = new TextView(ctx);
            chip.setText(options[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            chip.setGravity(Gravity.CENTER);
            chip.setClickable(true);
            chip.setFocusable(true);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setCornerRadius(dp(16));
            if (i == currentIdx) {
                chipBg.setColor(Color.parseColor("#5B5BF6"));
                chip.setTextColor(Color.WHITE);
            } else {
                chipBg.setColor(Color.parseColor("#F0F0F0"));
                chip.setTextColor(Color.parseColor("#444444"));
            }
            chip.setBackground(chipBg);
            chipViews[i] = chip;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginEnd(dp(6));
            chip.setOnClickListener(v -> {
                // Deselect old
                GradientDrawable oldBg = new GradientDrawable();
                oldBg.setCornerRadius(dp(16));
                oldBg.setColor(Color.parseColor("#F0F0F0"));
                chipViews[sel[0]].setBackground(oldBg);
                chipViews[sel[0]].setTextColor(Color.parseColor("#444444"));
                // Select new
                sel[0] = idx;
                GradientDrawable newBg = new GradientDrawable();
                newBg.setCornerRadius(dp(16));
                newBg.setColor(Color.parseColor("#5B5BF6"));
                chipViews[idx].setBackground(newBg);
                chipViews[idx].setTextColor(Color.WHITE);
                onChange.accept(idx);
            });
            chips.addView(chip, clp);
        }
        hsv.addView(chips);
        wrapper.addView(hsv);
        ll.addView(wrapper, wlp);
    }

    /** Color row: label + color preview circle + tap to pick */
    private void addColorRow(LinearLayout ll, String label, String currentHex, StrConsumer onChange) {
        Context ctx = ll.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        rlp.bottomMargin = dp(4);

        TextView lv = new TextView(ctx);
        lv.setText(label);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lv.setTextColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(lv, lvLp);

        // Color preview swatch
        View swatch = new View(ctx);
        GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setShape(GradientDrawable.OVAL);
        boolean hasColor = currentHex != null && !currentHex.isEmpty();
        swatchBg.setColor(hasColor ? safeColor(currentHex) : Color.parseColor("#DDDDDD"));
        swatchBg.setStroke(dp(1), Color.parseColor("#CCCCCC"));
        swatch.setBackground(swatchBg);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        swLp.setMarginEnd(dp(8));
        row.addView(swatch, swLp);

        // Pick button
        TextView pickBtn = new TextView(ctx);
        pickBtn.setText(hasColor ? currentHex : "Default");
        pickBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pickBtn.setTextColor(Color.parseColor("#5B5BF6"));
        pickBtn.setTypeface(null, Typeface.BOLD);
        pickBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable pickBg = new GradientDrawable();
        pickBg.setCornerRadius(dp(8));
        pickBg.setColor(Color.parseColor("#F0F0FF"));
        pickBtn.setBackground(pickBg);
        pickBtn.setOnClickListener(v -> {
            RainbowStripColorPickerBottomSheet.show(
                    ctx, label, currentHex, true,
                    picked -> {
                        String hex = (picked != null) ? picked : "";
                        // Update swatch
                        GradientDrawable nb = new GradientDrawable();
                        nb.setShape(GradientDrawable.OVAL);
                        nb.setColor(!hex.isEmpty() ? safeColor(hex) : Color.parseColor("#DDDDDD"));
                        nb.setStroke(dp(1), Color.parseColor("#CCCCCC"));
                        swatch.setBackground(nb);
                        pickBtn.setText(!hex.isEmpty() ? hex : "Default");
                        onChange.accept(hex);
                    });
        });
        row.addView(pickBtn);
        ll.addView(row, rlp);
    }

    /** Action button */
    private void addActionButton(LinearLayout ll, String text, String colorHex, Runnable action) {
        Context ctx = ll.getContext();
        Button btn = makeStyledButton(ctx, text, colorHex);
        btn.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(8);
        ll.addView(btn, blp);
    }

    private Button makeStyledButton(Context ctx, String text, String colorHex) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(safeColor(colorHex));
        btn.setBackground(bg);
        btn.setPadding(dp(16), dp(12), dp(16), dp(12));
        return btn;
    }

    private void notifyChanged() {
        if (listener != null) listener.onCustomizationChanged();
    }

    private int dp(int val) {
        if (getContext() == null) return val;
        return (int)(val * getContext().getResources().getDisplayMetrics().density);
    }

    private static int safeColor(String hex) {
        if (hex == null || hex.isEmpty()) return Color.parseColor("#5B5BF6");
        try {
            if (!hex.startsWith("#")) hex = "#" + hex;
            return Color.parseColor(hex);
        } catch (Exception e) {
            return Color.parseColor("#5B5BF6");
        }
    }
}
