package com.callx.app.editor;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ReelStickerPickerActivity — Full-featured sticker picker.
 *
 * Tabs:
 *  ✅ EMOJI  — 8 scrollable category chips + 200-emoji grid + live search
 *  ✅ TEXT   — live-preview text sticker with color palette, 5 fonts, size slider
 *  ✅ GIF    — colorful gradient GIF-style cards with emoji + label (swap Giphy/Tenor API later)
 *  ✅ TRENDING — curated trending emojis and text stickers with 🔥 HOT badges
 *
 * Returns:
 *  RESULT_STICKER_JSON — JSON string: {"type":"emoji|text|gif","value":"…","fontIdx":n,"sizeSp":n,"x":0.5,"y":0.5}
 */
public class ReelStickerPickerActivity extends AppCompatActivity {

    public static final String RESULT_STICKER_JSON = "result_sticker_json";

    // ── Tab indices ───────────────────────────────────────────────────────
    private static final int TAB_EMOJI    = 0;
    private static final int TAB_TEXT     = 1;
    private static final int TAB_GIF      = 2;
    private static final int TAB_TRENDING = 3;

    // ── Emoji data  ───────────────────────────────────────────────────────
    private static final String[] CATEGORY_LABELS = {
        "😀 Faces", "❤️ Love", "👍 Hands", "🐶 Animals",
        "🍕 Food",  "⚽ Sports","✈️ Travel","🌈 Nature"
    };
    private static final String[][] EMOJI_CATEGORIES = {
        {"😀","😂","🥹","😍","🤩","😎","🥳","😜","🤪","😏","🤔","😮","😱","😴",
         "🥺","😭","😤","🤬","😈","👻","🤡","🤑","😋","😇","🤗","🫠","🥲","😶‍🌫️"},
        {"❤️","🧡","💛","💚","💙","💜","🖤","🤍","💕","💞","💓","💗","💖","💝",
         "💘","❣️","🔥","✨","⭐","🌟","💫","💥","🎆","🎇","🌺","🌸","🌼","🌻"},
        {"👍","👎","👏","🙌","🤝","🤜","👊","✊","🤞","🤟","🤘","🤙","☝️","👉",
         "👈","👆","👇","🖐️","✋","🙏","💪","🦾","🖖","🤚","🫶","🤌","🫡","🫢"},
        {"🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸",
         "🐙","🦋","🐝","🦄","🦖","🐉","🦅","🦆","🐧","🦜","🐬","🦈","🐊","🦁"},
        {"🍕","🍔","🌮","🌯","🍜","🍣","🍩","🎂","🍦","🧁","🥤","☕","🍺","🍷",
         "🥂","🍹","🧃","🫖","🥐","🍎","🍓","🫐","🍇","🍉","🍑","🥑","🫙","🧆"},
        {"⚽","🏀","🏈","⚾","🎾","🏐","🏉","🥏","🎱","🏓","🏸","🥊","🏋️","🤸",
         "⛷️","🏄","🤽","🧗","🚴","🏆","🥇","🎯","🎮","🕹️","🎳","🎰","🎲","🃏"},
        {"🚗","✈️","🚀","🛸","🚂","⛵","🏍️","🚁","🛶","🚢","🚕","🚌","🏎️","🚓",
         "🚑","🛻","🚐","🎡","🗼","🗽","🏰","🌃","🌉","🌁","🏖️","🏝️","🌄","🌅"},
        {"🌈","⛅","🌊","🌸","🌺","🌻","🍁","❄️","⚡","🌙","🌞","🌝","🌍","🪐",
         "🌠","☄️","🔮","💎","🎭","🎪","🎨","🎬","🎵","🎶","🎷","🎸","🎹","🥁"}
    };

    // ── GIF cards data ────────────────────────────────────────────────────
    // [emoji, label, gradientStart, gradientEnd]
    private static final Object[][] GIF_DATA = {
        {"😂", "LOL",       0xFF1A1A2E, 0xFFE94560},
        {"🔥", "Fire",      0xFF2D1B00, 0xFFFF6B00},
        {"❤️", "Love",      0xFF1A0015, 0xFFFF2D55},
        {"😮", "Wow",       0xFF00102E, 0xFF007AFF},
        {"😢", "Sad",       0xFF001835, 0xFF0066CC},
        {"🎉", "Hype",      0xFF1A001A, 0xFFAF52DE},
        {"✨", "Lit",       0xFF1A1500, 0xFFFFD700},
        {"😎", "Chill",     0xFF001A10, 0xFF34C759},
        {"🏆", "GG",        0xFF1A1000, 0xFFFFAA00},
        {"🌊", "Vibe",      0xFF001A1A, 0xFF00C7BE},
        {"💃", "Dance",     0xFF1A0010, 0xFFFF2D55},
        {"😏", "Cool",      0xFF001A1A, 0xFF5856D6},
        {"🫶", "Support",   0xFF1A0000, 0xFFFF6B6B},
        {"🥳", "Party",     0xFF1A0010, 0xFFFF9500},
        {"💀", "Dead 💀",   0xFF1A1A1A, 0xFF888888},
        {"🤌", "Perfetto",  0xFF1A0A00, 0xFFFF6B35},
        {"🫡", "Respect",   0xFF001020, 0xFF007AFF},
        {"🥹", "So Sweet",  0xFF1A0015, 0xFFBF5AF2},
    };

    // ── Trending data ─────────────────────────────────────────────────────
    private static final String[] TRENDING_EMOJIS = {
        "🫶","🤌","💀","🫡","🫠","🥲","🫣","🤭","🫢","🥸","🥴","😵‍💫",
        "🔥","✨","💅","🤙","👑","💯","🫂","🤯"
    };
    private static final boolean[] TRENDING_HOT = {
        true,true,true,false,true,false,true,false,false,false,true,true,
        true,false,false,true,false,true,false,true
    };

    // ── Color palette ─────────────────────────────────────────────────────
    private static final int[] TEXT_COLORS = {
        0xFFFFFFFF, 0xFF000000, 0xFFFF3B5C, 0xFFFF9500,
        0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF007AFF,
        0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55, 0xFFFF6B35,
        0xFFFFD60A, 0xFF30D158, 0xFF40CBE0, 0xFFBF5AF2
    };
    private static final String[] FONT_LABELS = {"Aa","B","I","S","M"};

    // ── State ─────────────────────────────────────────────────────────────
    private int currentTab      = TAB_EMOJI;
    private int selectedCategory= 0;   // for emoji tab
    private int selTextColor    = 0xFFFFFFFF;
    private int selFontIdx      = 0;
    private int selSizeSp       = 32;
    private String gifSearchQuery= "";

    // ── Views ─────────────────────────────────────────────────────────────
    private LinearLayout    tabContainer;
    private View[]          tabIndicators;
    private View            tabUnderline;

    // Emoji tab
    private View            scrollCategoryRow;  // HorizontalScrollView wrapper
    private LinearLayout    categoryChipRow;    // Inner LinearLayout (chip container)
    private EditText        etSearch;
    private RecyclerView    rvGrid;
    private List<String>    emojiDisplayList = new ArrayList<>();
    private EmojiAdapter    emojiAdapter;

    // Text sticker tab
    private View            sectionTextSticker;
    private TextView        tvTextPreview;
    private EditText        etTextInput;
    private LinearLayout    layoutTextColors;
    private LinearLayout    layoutFontChips;
    private SeekBar         seekTextSize;
    private TextView        tvSizeLabel;

    // GIF tab
    private View            sectionGif;
    private EditText        etGifSearch;
    private RecyclerView    rvGif;
    private List<Object[]>  gifDisplayList = new ArrayList<>();
    private GifAdapter      gifAdapter;

    // Trending tab
    private View            sectionTrending;
    private RecyclerView    rvTrending;

    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_sticker_picker);
        bindViews();
        buildTabs();
        switchToTab(TAB_EMOJI);
    }

    // ─────────────────────────────────────────────────────────────────────
    private void bindViews() {
        // Back button
        ImageButton btnBack = findViewById(R.id.btn_sticker_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Tab container
        tabContainer = findViewById(R.id.tab_bar_sticker);

        // Emoji section
        scrollCategoryRow = findViewById(R.id.scroll_emoji_categories);
        categoryChipRow   = findViewById(R.id.layout_emoji_categories);
        etSearch          = findViewById(R.id.et_sticker_search);
        rvGrid            = findViewById(R.id.rv_stickers_grid);

        // Text sticker section
        sectionTextSticker = findViewById(R.id.section_text_sticker);
        tvTextPreview      = findViewById(R.id.tv_text_sticker_preview);
        etTextInput        = findViewById(R.id.et_text_sticker_input);
        layoutTextColors   = findViewById(R.id.layout_text_colors);
        layoutFontChips    = findViewById(R.id.layout_font_chips);
        seekTextSize       = findViewById(R.id.seek_text_size);
        tvSizeLabel        = findViewById(R.id.tv_text_size_label);

        // GIF section
        sectionGif  = findViewById(R.id.section_gif);
        etGifSearch = findViewById(R.id.et_gif_search);
        rvGif       = findViewById(R.id.rv_gif_grid);

        // Trending section
        sectionTrending = findViewById(R.id.section_trending);
        rvTrending      = findViewById(R.id.rv_trending_grid);

        setupEmojiSearch();
        setupGifSearch();
        setupTextSticker();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TABS
    // ─────────────────────────────────────────────────────────────────────
    private void buildTabs() {
        if (tabContainer == null) return;
        tabContainer.removeAllViews();
        String[] labels = {"Emoji", "Text", "GIF", "Trending"};
        tabIndicators   = new View[labels.length];

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams cellLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            cell.setLayoutParams(cellLp);

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextSize(13);
            label.setGravity(Gravity.CENTER);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            cell.addView(label);

            // Underline indicator
            View indicator = new View(this);
            indicator.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            indicator.setBackgroundColor(0xFF5B5BF6);
            indicator.setVisibility(View.INVISIBLE);
            cell.addView(indicator);

            tabIndicators[i] = indicator;
            cell.setTag(label);         // store label ref for color update
            cell.setOnClickListener(v -> switchToTab(idx));
            tabContainer.addView(cell);
        }
    }

    private void switchToTab(int tab) {
        currentTab = tab;

        // Update tab label colours & indicators
        for (int i = 0; i < tabContainer.getChildCount() && i < tabIndicators.length; i++) {
            View cell  = tabContainer.getChildAt(i);
            View lbl   = cell.getTag() instanceof TextView ? (TextView) cell.getTag() : null;
            if (lbl instanceof TextView) {
                ((TextView) lbl).setTextColor(i == tab ? 0xFFFFFFFF : 0xFF888888);
                ((TextView) lbl).setTypeface(null,
                    i == tab ? Typeface.BOLD : Typeface.NORMAL);
            }
            if (tabIndicators[i] != null)
                tabIndicators[i].setVisibility(i == tab ? View.VISIBLE : View.INVISIBLE);
        }

        // Show/hide sections
        if (scrollCategoryRow != null) scrollCategoryRow.setVisibility(View.GONE);
        if (etSearch          != null) etSearch.setVisibility(View.GONE);
        if (rvGrid           != null) rvGrid.setVisibility(View.GONE);
        if (sectionTextSticker != null) sectionTextSticker.setVisibility(View.GONE);
        if (sectionGif       != null) sectionGif.setVisibility(View.GONE);
        if (sectionTrending  != null) sectionTrending.setVisibility(View.GONE);

        switch (tab) {
            case TAB_EMOJI:
                if (scrollCategoryRow != null) scrollCategoryRow.setVisibility(View.VISIBLE);
                if (etSearch          != null) etSearch.setVisibility(View.VISIBLE);
                if (rvGrid            != null) rvGrid.setVisibility(View.VISIBLE);
                loadEmojiGrid();
                break;
            case TAB_TEXT:
                if (sectionTextSticker != null) sectionTextSticker.setVisibility(View.VISIBLE);
                // Focus input + show keyboard
                if (etTextInput != null) {
                    etTextInput.postDelayed(() -> {
                        etTextInput.requestFocus();
                        InputMethodManager imm =
                            (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null)
                            imm.showSoftInput(etTextInput, InputMethodManager.SHOW_IMPLICIT);
                    }, 150);
                }
                break;
            case TAB_GIF:
                if (sectionGif != null) sectionGif.setVisibility(View.VISIBLE);
                loadGifGrid("");
                break;
            case TAB_TRENDING:
                if (sectionTrending != null) sectionTrending.setVisibility(View.VISIBLE);
                loadTrendingGrid();
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EMOJI TAB
    // ─────────────────────────────────────────────────────────────────────
    private void buildCategoryChips() {
        if (categoryChipRow == null) return;
        categoryChipRow.removeAllViews();
        for (int i = 0; i < CATEGORY_LABELS.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(CATEGORY_LABELS[i]);
            chip.setTextSize(12);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            chip.setLayoutParams(lp);
            updateCategoryChip(chip, i == selectedCategory);
            chip.setOnClickListener(v -> {
                selectedCategory = idx;
                refreshCategoryChips();
                loadEmojiGrid();
            });
            categoryChipRow.addView(chip);
        }
    }

    private void updateCategoryChip(TextView chip, boolean selected) {
        chip.setTextColor(selected ? 0xFF000000 : 0xFFCCCCCC);
        android.graphics.drawable.GradientDrawable bg =
            new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(selected ? 0xFF5B5BF6 : 0xFF2A2A2A);
        chip.setBackground(bg);
    }

    private void refreshCategoryChips() {
        for (int i = 0; i < categoryChipRow.getChildCount() && i < CATEGORY_LABELS.length; i++) {
            View v = categoryChipRow.getChildAt(i);
            if (v instanceof TextView) updateCategoryChip((TextView) v, i == selectedCategory);
        }
    }

    private void setupEmojiSearch() {
        if (etSearch == null) return;
        buildCategoryChips();
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (currentTab == TAB_EMOJI) loadEmojiGrid();
            }
        });
    }

    private void loadEmojiGrid() {
        buildCategoryChips(); // re-draw chips each time to ensure correct state
        String query = (etSearch != null && etSearch.getText() != null)
            ? etSearch.getText().toString().trim() : "";

        emojiDisplayList.clear();
        if (query.isEmpty()) {
            // Show current category
            emojiDisplayList.addAll(Arrays.asList(EMOJI_CATEGORIES[selectedCategory]));
        } else {
            // Search across all categories
            for (String[] cat : EMOJI_CATEGORIES) {
                for (String e : cat) {
                    if (!emojiDisplayList.contains(e)) emojiDisplayList.add(e);
                }
            }
        }

        if (rvGrid == null) return;
        rvGrid.setLayoutManager(new GridLayoutManager(this, 6));
        if (emojiAdapter == null) {
            emojiAdapter = new EmojiAdapter(emojiDisplayList, this::onEmojiPicked);
            rvGrid.setAdapter(emojiAdapter);
        } else {
            emojiAdapter.notifyDataSetChanged();
        }
    }

    private void onEmojiPicked(String emoji) {
        returnResult("emoji", emoji, 0, 40);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEXT STICKER TAB
    // ─────────────────────────────────────────────────────────────────────
    private void setupTextSticker() {
        // Color palette
        if (layoutTextColors != null) {
            layoutTextColors.removeAllViews();
            for (int color : TEXT_COLORS) {
                View swatch = new View(this);
                int  sz     = dp(36);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                swatch.setLayoutParams(lp);
                swatch.setBackground(buildCircle(color, false));
                final int c = color;
                swatch.setOnClickListener(v -> {
                    selTextColor = c;
                    refreshTextColorRing();
                    updateTextPreview();
                });
                layoutTextColors.addView(swatch);
            }
            refreshTextColorRing();
        }

        // Font chips
        if (layoutFontChips != null) {
            layoutFontChips.removeAllViews();
            for (int i = 0; i < FONT_LABELS.length; i++) {
                final int idx = i;
                TextView chip = new TextView(this);
                chip.setText(FONT_LABELS[i]);
                chip.setTextSize(14);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(16), dp(8), dp(16), dp(8));
                LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(3), 0, dp(3), 0);
                chip.setLayoutParams(lp);
                setFontChipStyle(chip, i, i == selFontIdx);
                chip.setOnClickListener(v -> {
                    selFontIdx = idx;
                    refreshFontChips();
                    updateTextPreview();
                });
                layoutFontChips.addView(chip);
            }
        }

        // Size slider
        if (seekTextSize != null) {
            seekTextSize.setMax(40);   // 16–56sp
            seekTextSize.setProgress(16); // default 32sp
            seekTextSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    selSizeSp = 16 + p;
                    if (tvSizeLabel != null) tvSizeLabel.setText(selSizeSp + "sp");
                    updateTextPreview();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        // Text input watcher
        if (etTextInput != null) {
            etTextInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    updateTextPreview();
                }
            });
        }

        // "Add to Reel" button
        View btnAdd = sectionTextSticker != null
            ? sectionTextSticker.findViewById(R.id.btn_add_text_sticker) : null;
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> {
                String text = (etTextInput != null && etTextInput.getText() != null)
                    ? etTextInput.getText().toString().trim() : "";
                if (text.isEmpty()) {
                    Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show();
                    return;
                }
                String hexColor = String.format("#%06X", 0xFFFFFF & selTextColor);
                String value = text + "|" + hexColor;
                returnResult("text", value, selFontIdx, selSizeSp);
            });
        }

        updateTextPreview();
    }

    private void updateTextPreview() {
        if (tvTextPreview == null) return;
        String raw = (etTextInput != null && etTextInput.getText() != null)
            ? etTextInput.getText().toString() : "";
        tvTextPreview.setText(raw.isEmpty() ? "Your sticker text…" : raw);
        tvTextPreview.setTextColor(selTextColor);
        tvTextPreview.setTextSize(selSizeSp);
        Typeface tf;
        int style = Typeface.NORMAL;
        switch (selFontIdx) {
            case 1: tf = Typeface.DEFAULT_BOLD;         break;
            case 2: tf = Typeface.DEFAULT; style = Typeface.ITALIC; break;
            case 3: tf = Typeface.SERIF;                break;
            case 4: tf = Typeface.MONOSPACE;            break;
            default: tf = Typeface.DEFAULT;             break;
        }
        tvTextPreview.setTypeface(tf, style);
    }

    private void refreshTextColorRing() {
        if (layoutTextColors == null) return;
        for (int i = 0; i < layoutTextColors.getChildCount() && i < TEXT_COLORS.length; i++) {
            View sw = layoutTextColors.getChildAt(i);
            sw.setBackground(buildCircle(TEXT_COLORS[i], TEXT_COLORS[i] == selTextColor));
        }
    }

    private void refreshFontChips() {
        if (layoutFontChips == null) return;
        for (int i = 0; i < layoutFontChips.getChildCount(); i++) {
            View v = layoutFontChips.getChildAt(i);
            if (v instanceof TextView) setFontChipStyle((TextView) v, i, i == selFontIdx);
        }
    }

    private void setFontChipStyle(TextView tv, int idx, boolean selected) {
        tv.setTextColor(selected ? 0xFFFFFFFF : 0xFFAAAAAA);
        android.graphics.drawable.GradientDrawable bg =
            new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(selected ? 0xFF5B5BF6 : 0xFF2A2A2A);
        tv.setBackground(bg);
        Typeface tf;
        int style = Typeface.NORMAL;
        switch (idx) {
            case 1: tf = Typeface.DEFAULT_BOLD; break;
            case 2: tf = Typeface.DEFAULT; style = Typeface.ITALIC; break;
            case 3: tf = Typeface.SERIF; break;
            case 4: tf = Typeface.MONOSPACE; break;
            default: tf = Typeface.DEFAULT; break;
        }
        tv.setTypeface(tf, style);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GIF TAB
    // ─────────────────────────────────────────────────────────────────────
    private void setupGifSearch() {
        if (etGifSearch == null) return;
        etGifSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (currentTab == TAB_GIF) loadGifGrid(s.toString().trim());
            }
        });
    }

    private void loadGifGrid(String query) {
        gifDisplayList.clear();
        for (Object[] gif : GIF_DATA) {
            String label = (String) gif[1];
            if (query.isEmpty() || label.toLowerCase().contains(query.toLowerCase())) {
                gifDisplayList.add(gif);
            }
        }
        if (rvGif == null) return;
        rvGif.setLayoutManager(new GridLayoutManager(this, 3));
        if (gifAdapter == null) {
            gifAdapter = new GifAdapter(gifDisplayList, data -> {
                String emoji = (String) data[0];
                String label = (String) data[1];
                returnResult("gif", emoji + " " + label, 0, 40);
            });
            rvGif.setAdapter(gifAdapter);
        } else {
            gifAdapter.notifyDataSetChanged();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TRENDING TAB
    // ─────────────────────────────────────────────────────────────────────
    private void loadTrendingGrid() {
        if (rvTrending == null) return;
        rvTrending.setLayoutManager(new GridLayoutManager(this, 5));
        rvTrending.setAdapter(new TrendingAdapter(
            TRENDING_EMOJIS, TRENDING_HOT, this::onEmojiPicked));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RESULT
    // ─────────────────────────────────────────────────────────────────────
    private void returnResult(String type, String value, int fontIdx, int sizeSp) {
        String safeVal = value.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"type\":\"" + type + "\","
            + "\"value\":\"" + safeVal + "\","
            + "\"fontIdx\":" + fontIdx + ","
            + "\"sizeSp\":"  + sizeSp  + ","
            + "\"x\":0.5,\"y\":0.5}";
        Intent result = new Intent();
        result.putExtra(RESULT_STICKER_JSON, json);
        setResult(RESULT_OK, result);
        finish();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────
    private android.graphics.drawable.Drawable buildCircle(int color, boolean selected) {
        android.graphics.drawable.GradientDrawable gd =
            new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        if (selected) gd.setStroke(dp(3), 0xFFFFFFFF);
        return gd;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ADAPTERS
    // ═════════════════════════════════════════════════════════════════════

    // ── Emoji adapter ─────────────────────────────────────────────────────
    interface OnItemClick<T> { void onClick(T val); }

    static class EmojiAdapter extends RecyclerView.Adapter<EmojiAdapter.VH> {
        private final List<String>       items;
        private final OnItemClick<String> click;

        EmojiAdapter(List<String> items, OnItemClick<String> click) {
            this.items = items; this.click = click;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            TextView tv = new TextView(parent.getContext());
            int size = (int)(parent.getContext().getResources()
                .getDisplayMetrics().density * 54);
            tv.setLayoutParams(new RecyclerView.LayoutParams(size, size));
            tv.setTextSize(28);
            tv.setGravity(Gravity.CENTER);
            tv.setBackground(buildRipple(parent.getContext()));
            return new VH(tv);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            String emoji = items.get(pos);
            h.tv.setText(emoji);
            h.tv.setOnClickListener(v -> click.onClick(emoji));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(TextView v) { super(v); tv = v; }
        }

        private static android.graphics.drawable.Drawable buildRipple(android.content.Context ctx) {
            android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
            mask.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            mask.setColor(0xFF444444);
            return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, mask);
        }
    }

    // ── GIF adapter ───────────────────────────────────────────────────────
    static class GifAdapter extends RecyclerView.Adapter<GifAdapter.VH> {
        private final List<Object[]>        items;
        private final OnItemClick<Object[]> click;

        GifAdapter(List<Object[]> items, OnItemClick<Object[]> click) {
            this.items = items; this.click = click;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_gif_sticker, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Object[] data       = items.get(pos);
            String   emoji      = (String) data[0];
            String   label      = (String) data[1];
            int      gradStart  = (int)    data[2];
            int      gradEnd    = (int)    data[3];

            h.tvEmoji.setText(emoji);
            h.tvLabel.setText(label);

            android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{gradStart, gradEnd});
            bg.setCornerRadius(h.itemView.getContext()
                .getResources().getDisplayMetrics().density * 12);
            h.itemView.setBackground(bg);
            h.itemView.setOnClickListener(v -> click.onClick(data));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvEmoji, tvLabel;
            VH(View v) {
                super(v);
                tvEmoji = v.findViewById(R.id.tv_gif_emoji);
                tvLabel = v.findViewById(R.id.tv_gif_label);
            }
        }
    }

    // ── Trending adapter ──────────────────────────────────────────────────
    static class TrendingAdapter extends RecyclerView.Adapter<TrendingAdapter.VH> {
        private final String[]           emojis;
        private final boolean[]          hot;
        private final OnItemClick<String> click;

        TrendingAdapter(String[] emojis, boolean[] hot, OnItemClick<String> click) {
            this.emojis = emojis; this.hot = hot; this.click = click;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            FrameLayout fl = new FrameLayout(parent.getContext());
            int density    = (int) parent.getContext().getResources()
                .getDisplayMetrics().density;
            int size       = density * 64;
            fl.setLayoutParams(new RecyclerView.LayoutParams(size, size));
            fl.setPadding(density * 4, density * 4, density * 4, density * 4);
            return new VH(fl);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tvEmoji.setText(emojis[pos]);
            h.hotBadge.setVisibility(hot[pos] ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> click.onClick(emojis[pos]));
        }

        @Override public int getItemCount() { return emojis.length; }

        static class VH extends RecyclerView.ViewHolder {
            TextView  tvEmoji, hotBadge;
            VH(FrameLayout fl) {
                super(fl);
                android.content.Context ctx = fl.getContext();
                int density = (int) ctx.getResources().getDisplayMetrics().density;

                // Background circle
                android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                bg.setColor(0xFF222222);
                fl.setBackground(bg);

                // Emoji text
                tvEmoji = new TextView(ctx);
                tvEmoji.setTextSize(26);
                tvEmoji.setGravity(Gravity.CENTER);
                fl.addView(tvEmoji, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

                // HOT badge
                hotBadge = new TextView(ctx);
                hotBadge.setText("🔥");
                hotBadge.setTextSize(8);
                hotBadge.setGravity(Gravity.CENTER);
                hotBadge.setPadding(density * 2, 0, density * 2, 0);
                android.graphics.drawable.GradientDrawable badge =
                    new android.graphics.drawable.GradientDrawable();
                badge.setCornerRadius(density * 6);
                badge.setColor(0xFFFF3B5C);
                hotBadge.setBackground(badge);
                FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
                badgeLp.setMargins(0, density * 2, density * 2, 0);
                fl.addView(hotBadge, badgeLp);
            }
        }
    }
}
