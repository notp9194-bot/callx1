package com.callx.app.conversation.controllers;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ChatStickerPickerActivity — Full production sticker / emoji / text / GIF picker.
 *
 * Launched from {@link MediaEditActivity} when the user taps the Sticker (😊)
 * or Aa (text) button. Returns the selected overlay content via Intent extra so
 * the editor can place it as an {@link MediaEditActivity.OverlayItem}.
 *
 * Tabs (fully wired):
 *  ✅ Emoji   — searchable 6-col grid with categorised emoji data
 *  ✅ Text    — full-screen text entry with font family + color selector
 *  ✅ GIF     — labelled GIF grid (Tenor labels, ready for API swap)
 *  ✅ Trending — curated trending emoji/sticker set
 *
 * Result extras:
 *   RESULT_TYPE  = "emoji" | "text"
 *   RESULT_VALUE = the emoji string / text string
 *   RESULT_COLOR = (int) chosen text color — only for type == "text"
 *   RESULT_FONT  = font family name string — only for type == "text"
 *   RESULT_SIZE  = (float) text size in sp — only for type == "text"
 *   RESULT_BOLD  = (boolean) bold — only for type == "text"
 *   RESULT_ITALIC= (boolean) italic — only for type == "text"
 *   RESULT_ALIGN = "left"|"center"|"right" — only for type == "text"
 *   RESULT_HAS_BG= (boolean) text has background pill
 */
public class ChatStickerPickerActivity extends AppCompatActivity {

    // ── Intent contract ─────────────────────────────────────────────────
    public static final String RESULT_TYPE   = "sticker_type";   // "emoji" | "text"
    public static final String RESULT_VALUE  = "sticker_value";
    public static final String RESULT_COLOR  = "sticker_color";
    public static final String RESULT_FONT   = "sticker_font";
    public static final String RESULT_SIZE   = "sticker_size";
    public static final String RESULT_BOLD   = "sticker_bold";
    public static final String RESULT_ITALIC = "sticker_italic";
    public static final String RESULT_ALIGN  = "sticker_align";
    public static final String RESULT_HAS_BG = "sticker_has_bg";

    /** When launching for text overlays instead of emoji, caller sets this extra = true. */
    public static final String EXTRA_TEXT_MODE = "text_mode";

    // ── Tabs ─────────────────────────────────────────────────────────────
    private static final int TAB_EMOJI    = 0;
    private static final int TAB_TEXT     = 1;
    private static final int TAB_GIF      = 2;
    private static final int TAB_TRENDING = 3;

    private int currentTab = TAB_EMOJI;

    // ── Views ─────────────────────────────────────────────────────────────
    private TextView   tabEmoji, tabText, tabGif, tabTrending;
    private EditText   etSearch;
    private RecyclerView rvGrid;
    private View       layoutTextEditor;

    // Text editor views
    private EditText   etTextInput;
    private RecyclerView rvFontColors, rvFontFamilies;
    private TextView   btnBold, btnItalic, btnAlignLeft, btnAlignCenter, btnAlignRight;
    private TextView   btnTextBg, btnTextDone;

    private int      selectedColor  = android.graphics.Color.WHITE;
    private String   selectedFont   = "default";
    private float    selectedSize   = 32f;
    private boolean  isBold         = false;
    private boolean  isItalic       = false;
    private String   textAlign      = "center";
    private boolean  hasTextBg      = false;

    // ── Emoji data ─────────────────────────────────────────────────────────
    private static final List<String> EMOJI_SMILEYS = Arrays.asList(
        "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃","😉","😊","😇",
        "🥰","😍","🤩","😘","😗","😚","😙","😋","😛","😜","🤪","😝","🤑",
        "🤗","🤭","🤫","🤔","🤐","🤨","😐","😑","😶","😏","😒","🙄","😬",
        "🤥","😌","😔","😪","🤤","😴","😷","🤒","🤕","🤢","🤧","🥵","🥶",
        "🥴","😵","🤯","🤠","🥳","😎","🤓","🧐","😕","😟","🙁","☹️","😮",
        "😯","😲","😳","🥺","😦","😧","😨","😰","😥","😢","😭","😱","😖"
    );
    private static final List<String> EMOJI_HEARTS = Arrays.asList(
        "❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞",
        "💓","💗","💖","💘","💝","💟","☮️","✝️","☪️","🕉️","✡️","🔯","🕎",
        "☯️","☦️","🛐","⛎","♈","♉","♊","♋","♌","♍","♎","♏","♐","♑"
    );
    private static final List<String> EMOJI_HANDS = Arrays.asList(
        "👋","🤚","🖐️","✋","🖖","👌","🤌","🤏","✌️","🤞","🤟","🤘","🤙",
        "👈","👉","👆","🖕","👇","☝️","👍","👎","✊","👊","🤛","🤜","👏",
        "🙌","👐","🤲","🤝","🙏","✍️","💅","🤳","💪","🦾","🦿","🦵","🦶"
    );
    private static final List<String> EMOJI_ANIMALS = Arrays.asList(
        "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷",
        "🐸","🐵","🙈","🙉","🙊","🐔","🐧","🐦","🐤","🦆","🦅","🦉","🦇",
        "🐺","🐗","🐴","🦄","🐝","🐛","🦋","🐌","🐞","🐜","🦟","🦗","🦂"
    );
    private static final List<String> EMOJI_FOOD = Arrays.asList(
        "🍎","🍊","🍋","🍇","🍓","🫐","🍈","🍑","🥭","🍍","🥥","🥝","🍅",
        "🍆","🥑","🥦","🥬","🥒","🌶️","🫑","🧄","🧅","🥔","🍠","🧆","🥚",
        "🍳","🧇","🥞","🧈","🍞","🥐","🥖","🥨","🧀","🥗","🥙","🌮","🌯"
    );
    private static final List<String> EMOJI_TRAVEL = Arrays.asList(
        "🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐","🛻","🚚","🚛",
        "✈️","🚀","🛸","🚁","⛵","🚂","🚃","🚄","🚅","🚆","🚇","🚈","🚉",
        "🏠","🏡","🏢","🏣","🏤","🏥","🏦","🏨","🏩","🏪","🏫","🏬","🏭"
    );
    private static final List<String> EMOJI_OBJECTS = Arrays.asList(
        "⌚","📱","💻","⌨️","🖥️","🖨️","🖱️","🖲️","💽","💾","💿","📀","📷",
        "📸","📹","🎥","📽️","🎞️","📞","☎️","📟","📠","📺","📻","🧭","⏱️",
        "🎮","🕹️","🎲","♟️","🎯","🎱","🏆","🥇","🥈","🥉","🎖️","🏅","🎗️"
    );
    private static final List<String> TRENDING_STICKERS = Arrays.asList(
        "🔥","💯","✨","🎉","🙏","😭","😍","🤣","💀","🥺",
        "👀","😤","🤩","💪","😂","🫡","🫶","🤌","💅","🦋",
        "🫠","🥹","🫣","🤯","😮‍💨","😈","👻","💸","🌊","🎭"
    );

    private List<String> currentEmojiList = new ArrayList<>();
    private EmojiAdapter emojiAdapter;

    // ── Font colors ────────────────────────────────────────────────────────
    private static final int[] TEXT_COLORS = {
        android.graphics.Color.WHITE,
        android.graphics.Color.BLACK,
        0xFFFF5252,   // Red
        0xFFFF9800,   // Orange
        0xFFFFEB3B,   // Yellow
        0xFF4CAF50,   // Green
        0xFF2196F3,   // Blue
        0xFF9C27B0,   // Purple
        0xFFFF4081,   // Pink
        0xFF00BCD4,   // Cyan
        0xFF795548,   // Brown
        0xFF607D8B,   // Blue-grey
    };

    // ── Font families ──────────────────────────────────────────────────────
    private static final String[] FONT_FAMILIES = {
        "default", "sans-serif", "serif", "monospace",
        "sans-serif-condensed", "sans-serif-medium", "sans-serif-light",
        "cursive", "sans-serif-black"
    };
    private static final String[] FONT_LABELS = {
        "Default", "Sans", "Serif", "Mono",
        "Condensed", "Medium", "Light",
        "Cursive", "Black"
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_sticker_picker);

        bindViews();
        setupTabs();
        setupSearch();
        setupTextEditor();
        buildEmojiList("");

        boolean textMode = getIntent().getBooleanExtra(EXTRA_TEXT_MODE, false);
        if (textMode) {
            switchTab(TAB_TEXT);
        } else {
            switchTab(TAB_EMOJI);
        }
    }

    private void bindViews() {
        tabEmoji    = findViewById(R.id.chat_sticker_tab_emoji);
        tabText     = findViewById(R.id.chat_sticker_tab_text);
        tabGif      = findViewById(R.id.chat_sticker_tab_gif);
        tabTrending = findViewById(R.id.chat_sticker_tab_trending);
        etSearch    = findViewById(R.id.chat_sticker_search);
        rvGrid      = findViewById(R.id.chat_sticker_rv);
        layoutTextEditor = findViewById(R.id.chat_sticker_text_editor);

        etTextInput   = findViewById(R.id.chat_sticker_text_input);
        rvFontColors  = findViewById(R.id.chat_sticker_rv_colors);
        rvFontFamilies = findViewById(R.id.chat_sticker_rv_fonts);
        btnBold       = findViewById(R.id.chat_sticker_btn_bold);
        btnItalic     = findViewById(R.id.chat_sticker_btn_italic);
        btnAlignLeft  = findViewById(R.id.chat_sticker_btn_align_left);
        btnAlignCenter= findViewById(R.id.chat_sticker_btn_align_center);
        btnAlignRight = findViewById(R.id.chat_sticker_btn_align_right);
        btnTextBg     = findViewById(R.id.chat_sticker_btn_bg);
        btnTextDone   = findViewById(R.id.chat_sticker_btn_text_done);
    }

    // ── Tabs ──────────────────────────────────────────────────────────────

    private void setupTabs() {
        if (tabEmoji    != null) tabEmoji.setOnClickListener(v -> switchTab(TAB_EMOJI));
        if (tabText     != null) tabText.setOnClickListener(v -> switchTab(TAB_TEXT));
        if (tabGif      != null) tabGif.setOnClickListener(v -> switchTab(TAB_GIF));
        if (tabTrending != null) tabTrending.setOnClickListener(v -> switchTab(TAB_TRENDING));
    }

    private void switchTab(int tab) {
        currentTab = tab;
        updateTabStyles();

        boolean isText = (tab == TAB_TEXT);
        if (layoutTextEditor != null) layoutTextEditor.setVisibility(isText ? View.VISIBLE : View.GONE);
        if (rvGrid           != null) rvGrid.setVisibility(isText ? View.GONE : View.VISIBLE);
        if (etSearch         != null) etSearch.setVisibility(
                (tab == TAB_EMOJI || tab == TAB_GIF || tab == TAB_TRENDING) ? View.VISIBLE : View.GONE);

        switch (tab) {
            case TAB_EMOJI:    loadEmojiGrid();    break;
            case TAB_GIF:      loadGifGrid();      break;
            case TAB_TRENDING: loadTrendingGrid(); break;
            case TAB_TEXT:     /* handled via text editor UI */ break;
        }
    }

    private void updateTabStyles() {
        int activeColor   = android.graphics.Color.WHITE;
        int inactiveColor = 0xFFAAAAAA;
        setTabActive(tabEmoji,    currentTab == TAB_EMOJI,    activeColor, inactiveColor);
        setTabActive(tabText,     currentTab == TAB_TEXT,     activeColor, inactiveColor);
        setTabActive(tabGif,      currentTab == TAB_GIF,      activeColor, inactiveColor);
        setTabActive(tabTrending, currentTab == TAB_TRENDING, activeColor, inactiveColor);
    }

    private void setTabActive(TextView tv, boolean active, int on, int off) {
        if (tv == null) return;
        tv.setTextColor(active ? on : off);
        tv.setAlpha(active ? 1f : 0.55f);
    }

    // ── Grid loaders ──────────────────────────────────────────────────────

    private void loadEmojiGrid() {
        buildEmojiList(etSearch != null ? etSearch.getText().toString() : "");
        if (rvGrid != null) {
            rvGrid.setLayoutManager(new GridLayoutManager(this, 7));
            emojiAdapter = new EmojiAdapter(currentEmojiList, this::returnEmoji);
            rvGrid.setAdapter(emojiAdapter);
        }
    }

    private void loadGifGrid() {
        // GIF labels for Tenor integration — swap with real Tenor API call as needed
        List<String> gifLabels = Arrays.asList(
            "Celebration 🎉","Love ❤️","Laughing 😂","Facepalm 🤦","High Five 🙌",
            "Dancing 💃","Fire 🔥","Awkward 😬","Clapping 👏","Thinking 🤔",
            "Cool 😎","Shocked 😱","Crying 😭","Excited 🥳","Sleeping 😴"
        );
        if (rvGrid != null) {
            rvGrid.setLayoutManager(new GridLayoutManager(this, 2));
            rvGrid.setAdapter(new GifLabelAdapter(gifLabels, label -> returnEmoji("🎞️ " + label)));
        }
    }

    private void loadTrendingGrid() {
        if (rvGrid != null) {
            rvGrid.setLayoutManager(new GridLayoutManager(this, 7));
            emojiAdapter = new EmojiAdapter(TRENDING_STICKERS, this::returnEmoji);
            rvGrid.setAdapter(emojiAdapter);
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    private void setupSearch() {
        if (etSearch == null) return;
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (currentTab == TAB_EMOJI) {
                    buildEmojiList(s.toString());
                    if (emojiAdapter != null) emojiAdapter.update(currentEmojiList);
                }
            }
        });
    }

    private void buildEmojiList(String query) {
        List<String> all = new ArrayList<>();
        all.addAll(EMOJI_SMILEYS);
        all.addAll(EMOJI_HEARTS);
        all.addAll(EMOJI_HANDS);
        all.addAll(EMOJI_ANIMALS);
        all.addAll(EMOJI_FOOD);
        all.addAll(EMOJI_TRAVEL);
        all.addAll(EMOJI_OBJECTS);
        currentEmojiList.clear();
        if (query == null || query.isEmpty()) {
            currentEmojiList.addAll(all);
        } else {
            for (String e : all) {
                if (e.contains(query)) currentEmojiList.add(e);
            }
        }
    }

    // ── Text editor ───────────────────────────────────────────────────────

    private void setupTextEditor() {
        // Color picker
        if (rvFontColors != null) {
            rvFontColors.setLayoutManager(new LinearLayoutManager(this,
                    LinearLayoutManager.HORIZONTAL, false));
            rvFontColors.setAdapter(new ColorPickerAdapter(TEXT_COLORS, color -> {
                selectedColor = color;
                if (etTextInput != null) etTextInput.setTextColor(color);
            }));
        }

        // Font family picker
        if (rvFontFamilies != null) {
            rvFontFamilies.setLayoutManager(new LinearLayoutManager(this,
                    LinearLayoutManager.HORIZONTAL, false));
            rvFontFamilies.setAdapter(new FontFamilyAdapter(
                    Arrays.asList(FONT_LABELS), Arrays.asList(FONT_FAMILIES), font -> {
                selectedFont = font;
                applyFontToInput();
            }));
        }

        // Bold / Italic
        if (btnBold != null) btnBold.setOnClickListener(v -> {
            isBold = !isBold;
            btnBold.setAlpha(isBold ? 1f : 0.45f);
            applyFontToInput();
        });
        if (btnItalic != null) btnItalic.setOnClickListener(v -> {
            isItalic = !isItalic;
            btnItalic.setAlpha(isItalic ? 1f : 0.45f);
            applyFontToInput();
        });

        // Alignment
        if (btnAlignLeft != null) btnAlignLeft.setOnClickListener(v -> setAlign("left"));
        if (btnAlignCenter != null) btnAlignCenter.setOnClickListener(v -> setAlign("center"));
        if (btnAlignRight != null) btnAlignRight.setOnClickListener(v -> setAlign("right"));

        // Background pill
        if (btnTextBg != null) btnTextBg.setOnClickListener(v -> {
            hasTextBg = !hasTextBg;
            btnTextBg.setAlpha(hasTextBg ? 1f : 0.45f);
        });

        // Done
        if (btnTextDone != null) btnTextDone.setOnClickListener(v -> {
            String text = etTextInput != null ? etTextInput.getText().toString().trim() : "";
            if (text.isEmpty()) {
                if (etTextInput != null) etTextInput.setError("Enter some text");
                return;
            }
            returnText(text);
        });
    }

    private void applyFontToInput() {
        if (etTextInput == null) return;
        android.graphics.Typeface tf;
        try {
            tf = android.graphics.Typeface.create(selectedFont,
                    (isBold && isItalic) ? android.graphics.Typeface.BOLD_ITALIC
                  : isBold  ? android.graphics.Typeface.BOLD
                  : isItalic? android.graphics.Typeface.ITALIC
                  : android.graphics.Typeface.NORMAL);
        } catch (Exception e) {
            tf = android.graphics.Typeface.DEFAULT;
        }
        etTextInput.setTypeface(tf);
    }

    private void setAlign(String align) {
        textAlign = align;
        if (etTextInput == null) return;
        if ("left".equals(align))        etTextInput.setGravity(android.view.Gravity.START);
        else if ("right".equals(align))  etTextInput.setGravity(android.view.Gravity.END);
        else                             etTextInput.setGravity(android.view.Gravity.CENTER);

        float l = "left".equals(align)   ? 1f : 0.45f;
        float c = "center".equals(align) ? 1f : 0.45f;
        float r = "right".equals(align)  ? 1f : 0.45f;
        if (btnAlignLeft   != null) btnAlignLeft.setAlpha(l);
        if (btnAlignCenter != null) btnAlignCenter.setAlpha(c);
        if (btnAlignRight  != null) btnAlignRight.setAlpha(r);
    }

    // ── Return result ─────────────────────────────────────────────────────

    private void returnEmoji(String emoji) {
        Intent result = new Intent();
        result.putExtra(RESULT_TYPE,  "emoji");
        result.putExtra(RESULT_VALUE, emoji);
        result.putExtra(RESULT_COLOR, android.graphics.Color.WHITE);
        setResult(RESULT_OK, result);
        finish();
    }

    private void returnText(String text) {
        Intent result = new Intent();
        result.putExtra(RESULT_TYPE,   "text");
        result.putExtra(RESULT_VALUE,  text);
        result.putExtra(RESULT_COLOR,  selectedColor);
        result.putExtra(RESULT_FONT,   selectedFont);
        result.putExtra(RESULT_SIZE,   selectedSize);
        result.putExtra(RESULT_BOLD,   isBold);
        result.putExtra(RESULT_ITALIC, isItalic);
        result.putExtra(RESULT_ALIGN,  textAlign);
        result.putExtra(RESULT_HAS_BG, hasTextBg);
        setResult(RESULT_OK, result);
        finish();
    }

    // ── Adapters (inner) ──────────────────────────────────────────────────

    /** Simple emoji grid adapter */
    private static class EmojiAdapter extends RecyclerView.Adapter<EmojiAdapter.VH> {
        interface Listener { void onPick(String emoji); }
        private final List<String> items;
        private final Listener listener;
        EmojiAdapter(List<String> items, Listener l) {
            this.items = new ArrayList<>(items);
            this.listener = l;
        }
        void update(List<String> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setTextSize(26f);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 64));
            tv.setPadding(4, 4, 4, 4);
            return new VH(tv);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String emoji = items.get(position);
            ((TextView) h.itemView).setText(emoji);
            h.itemView.setOnClickListener(v -> listener.onPick(emoji));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder { VH(View v) { super(v); } }
    }

    /** GIF label grid adapter */
    private static class GifLabelAdapter extends RecyclerView.Adapter<GifLabelAdapter.VH> {
        interface Listener { void onPick(String label); }
        private final List<String> labels;
        private final Listener listener;
        GifLabelAdapter(List<String> labels, Listener l) { this.labels = labels; this.listener = l; }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setTextSize(14f);
            tv.setTextColor(android.graphics.Color.WHITE);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setBackgroundColor(0xFF2A2A2A);
            int pad = (int)(parent.getContext().getResources().getDisplayMetrics().density * 12);
            tv.setPadding(pad, pad * 2, pad, pad * 2);
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(8, 8, 8, 8);
            tv.setLayoutParams(lp);
            return new VH(tv);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String label = labels.get(position);
            ((TextView) h.itemView).setText(label);
            h.itemView.setOnClickListener(v -> listener.onPick(label));
        }
        @Override public int getItemCount() { return labels.size(); }
        static class VH extends RecyclerView.ViewHolder { VH(View v) { super(v); } }
    }

    /** Horizontal color dot picker */
    private static class ColorPickerAdapter extends RecyclerView.Adapter<ColorPickerAdapter.VH> {
        interface Listener { void onPick(int color); }
        private final int[] colors;
        private final Listener listener;
        private int selectedIndex = 0;
        ColorPickerAdapter(int[] colors, Listener l) { this.colors = colors; this.listener = l; }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = new View(parent.getContext());
            float d = parent.getContext().getResources().getDisplayMetrics().density;
            int sz = (int)(36 * d);
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(sz, sz);
            lp.setMarginEnd((int)(8 * d));
            v.setLayoutParams(lp);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            h.itemView.setBackgroundColor(colors[position]);
            h.itemView.setAlpha(position == selectedIndex ? 1f : 0.55f);
            h.itemView.setOnClickListener(v -> {
                int old = selectedIndex;
                selectedIndex = position;
                notifyItemChanged(old);
                notifyItemChanged(selectedIndex);
                listener.onPick(colors[position]);
            });
            // Ring for selected
            if (position == selectedIndex) {
                h.itemView.setBackgroundColor(colors[position]);
            }
        }
        @Override public int getItemCount() { return colors.length; }
        static class VH extends RecyclerView.ViewHolder { VH(View v) { super(v); } }
    }

    /** Horizontal font family chip picker */
    private static class FontFamilyAdapter extends RecyclerView.Adapter<FontFamilyAdapter.VH> {
        interface Listener { void onPick(String font); }
        private final List<String> labels;
        private final List<String> fonts;
        private final Listener listener;
        private int selectedIndex = 0;
        FontFamilyAdapter(List<String> labels, List<String> fonts, Listener l) {
            this.labels = labels; this.fonts = fonts; this.listener = l;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            float d = parent.getContext().getResources().getDisplayMetrics().density;
            int hPad = (int)(14 * d);
            int vPad = (int)(7 * d);
            tv.setPadding(hPad, vPad, hPad, vPad);
            tv.setTextSize(13f);
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int)(8 * d));
            tv.setLayoutParams(lp);
            return new VH(tv);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            TextView tv = (TextView) h.itemView;
            tv.setText(labels.get(position));
            boolean sel = (position == selectedIndex);
            tv.setTextColor(sel ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
            tv.setBackgroundColor(sel ? android.graphics.Color.WHITE : 0xFF333333);
            try {
                tv.setTypeface(android.graphics.Typeface.create(fonts.get(position),
                        android.graphics.Typeface.NORMAL));
            } catch (Exception ignored) {}
            tv.setOnClickListener(v -> {
                int old = selectedIndex;
                selectedIndex = position;
                notifyItemChanged(old);
                notifyItemChanged(selectedIndex);
                listener.onPick(fonts.get(position));
            });
        }
        @Override public int getItemCount() { return labels.size(); }
        static class VH extends RecyclerView.ViewHolder { VH(View v) { super(v); } }
    }
}
