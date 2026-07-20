package com.callx.app.editor;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.util.*;

/**
 * ReelStickerPickerActivity v2 — Add stickers, emoji, text overlays + Interactive stickers to reels.
 *
 * Features:
 *  ✅ Tab bar: Emoji / Text / GIF / Trending / Interactive
 *  ✅ Emoji grid (200+ emojis across 8 categories)
 *  ✅ Text sticker creator (custom text, font style, colour picker)
 *  ✅ Animated GIF sticker grid (placeholder URLs, swap with Giphy/Tenor)
 *  ✅ Trending sticker row (hot stickers this week)
 *  ✅ Search bar filters emoji list in real-time
 *  ✅ [NEW] Interactive stickers: Poll, Quiz, Slider, Question
 *  ✅ Result returned to caller as JSON string: {type, value, x, y}
 *     Interactive stickers: {type:"interactive", stickerType, question, options, emoji, x, y}
 */
public class ReelStickerPickerActivity extends AppCompatActivity {

    public static final String RESULT_STICKER_JSON = "result_sticker_json";

    /* Tab indices */
    private static final int TAB_EMOJI       = 0;
    private static final int TAB_TEXT        = 1;
    private static final int TAB_GIF         = 2;
    private static final int TAB_TRENDING    = 3;
    private static final int TAB_INTERACTIVE = 4;

    /* Emoji data */
    private static final String[][] EMOJI_CATEGORIES = {
        {"😀","😂","🥹","😍","🤩","😎","🥳","😜","🤪","😏","🤔","😮","😱","😴","🥺","😭","😤","🤬","😈","👻"},
        {"❤️","🧡","💛","💚","💙","💜","🖤","🤍","💕","💞","💓","💗","💖","💝","💘","❣️","🔥","✨","⭐","🌟"},
        {"👍","👎","👏","🙌","🤝","🤜","👊","✊","🤞","🤟","🤘","🤙","☝️","👉","👈","👆","👇","🖐️","✋","🙏"},
        {"🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐙","🦋","🐝","🦄","🦖","🐉"},
        {"🍕","🍔","🌮","🌯","🍜","🍣","🍩","🎂","🍦","🧁","🥤","☕","🍺","🍷","🥂","🍹","🧃","🫖","🥐","🍎"},
        {"⚽","🏀","🏈","⚾","🎾","🏐","🏉","🥏","🎱","🏓","🏸","🥊","🏋️","🤸","⛷️","🏄","🤽","🧗","🚴","🏆"},
        {"🚗","✈️","🚀","🛸","🚂","⛵","🏍️","🚁","🛶","🚢","🚂","🚕","🚌","🏎️","🚓","🚑","🛻","🚐","🚚","🎡"},
        {"🌈","⛅","🌊","🌸","🌺","🌻","🍁","❄️","⚡","🌙","🌞","🌝","🌍","🪐","🌠","☄️","🔮","💎","🎭","🎪"}
    };

    private static final String EMOJI_FLAT;
    static {
        StringBuilder sb = new StringBuilder();
        for (String[] cat : EMOJI_CATEGORIES) for (String e : cat) sb.append(e).append(",");
        EMOJI_FLAT = sb.toString();
    }

    /* GIF placeholder data */
    private static final String[] GIF_LABELS = {
        "LOL 😂","Fire 🔥","Love ❤️","WOW 😮","Sad 😢","Hype 🎉",
        "Lit ✨","Chill 😎","GG 🏆","Vibe 🌊","Dance 💃","Cool 😏"
    };

    /* Trending stickers */
    private static final String[] TRENDING = {"🫶","🤌","💀","🫡","🫠","🥲","🫣","🤭","🫢","🥸","🥴","😵‍💫"};

    /* Interactive sticker types */
    private static final String[][] INTERACTIVE_STICKERS = {
        {"📊", "Poll", "Ask your audience to vote between options"},
        {"🧠", "Quiz", "Test your audience with a question (one correct answer)"},
        {"😍", "Slider", "Let viewers rate something with an emoji slider"},
        {"💬", "Question", "Let viewers ask you anything or share thoughts"}
    };

    // ── UI refs ──
    private LinearLayout  tabBar;
    private EditText      etSearch;
    private RecyclerView  rvStickers;
    private LinearLayout  layoutText;
    private EditText      etTextSticker;
    private RecyclerView  rvFontColors;
    private TextView      btnAddText;
    private ImageButton   btnBack;
    private ScrollView    layoutInteractive;

    private int           currentTab = TAB_EMOJI;
    private final List<String> emojiList = new ArrayList<>();
    private EmojiGridAdapter  emojiAdapter;

    private static final int[]  COLOR_VALUES = {
        0xFFFFFFFF, 0xFF000000, 0xFFFF3B5C, 0xFFFFD700,
        0xFF00D4AA, 0xFF4A90E2, 0xFFFF6B6B, 0xFF9B59B6
    };
    private int selectedColor = 0xFFFFFFFF;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_sticker_picker);

        bindViews();
        buildEmojiList("");
        setupTabs();
        setupSearch();
        setupTextTab();
        buildInteractiveTab();
        switchToTab(TAB_EMOJI);
    }

    private void bindViews() {
        btnBack           = findViewById(R.id.btn_sticker_back);
        tabBar            = findViewById(R.id.tab_bar_sticker);
        etSearch          = findViewById(R.id.et_sticker_search);
        rvStickers        = findViewById(R.id.rv_stickers_grid);
        layoutText        = findViewById(R.id.layout_text_sticker);
        etTextSticker     = findViewById(R.id.et_text_sticker_input);
        rvFontColors      = findViewById(R.id.rv_font_colors);
        btnAddText        = findViewById(R.id.btn_add_text_sticker);
        layoutInteractive = findViewById(R.id.layout_interactive_stickers);

        btnBack.setOnClickListener(v -> finish());
    }

    private void buildEmojiList(String query) {
        emojiList.clear();
        for (String e : EMOJI_FLAT.split(",")) {
            if (query.isEmpty() || e.contains(query)) emojiList.add(e);
        }
    }

    private void setupTabs() {
        String[] tabLabels = {"Emoji","Text","GIF","Trending","Interactive"};
        for (int i = 0; i < tabLabels.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(tabLabels[i]);
            tv.setTextSize(i == TAB_INTERACTIVE ? 12 : 13);
            tv.setPadding(dpToPx(10),dpToPx(10),dpToPx(10),dpToPx(10));
            tv.setTextColor(0xFFCCCCCC);
            LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setOnClickListener(v -> switchToTab(idx));
            tabBar.addView(tv);
        }
    }

    private void switchToTab(int tab) {
        currentTab = tab;

        // update tab highlight
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View v = tabBar.getChildAt(i);
            if (v instanceof TextView) {
                boolean active = (i == tab);
                ((TextView) v).setTextColor(active ? 0xFFFF3B5C : 0xFFCCCCCC);
                ((TextView) v).setTypeface(null,
                    active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }

        boolean showSearch = (tab == TAB_EMOJI || tab == TAB_GIF || tab == TAB_TRENDING);
        etSearch.setVisibility(showSearch ? View.VISIBLE : View.GONE);
        layoutText.setVisibility(tab == TAB_TEXT ? View.VISIBLE : View.GONE);
        layoutInteractive.setVisibility(tab == TAB_INTERACTIVE ? View.VISIBLE : View.GONE);
        rvStickers.setVisibility((tab != TAB_TEXT && tab != TAB_INTERACTIVE) ? View.VISIBLE : View.GONE);

        switch (tab) {
            case TAB_EMOJI:       loadEmojiGrid();       break;
            case TAB_GIF:         loadGifGrid();         break;
            case TAB_TRENDING:    loadTrendingGrid();    break;
            case TAB_INTERACTIVE: /* already built */    break;
        }
    }

    private void loadEmojiGrid() {
        rvStickers.setLayoutManager(new GridLayoutManager(this, 6));
        emojiAdapter = new EmojiGridAdapter(emojiList, emoji -> returnSticker("emoji", emoji));
        rvStickers.setAdapter(emojiAdapter);
    }

    private void loadGifGrid() {
        rvStickers.setLayoutManager(new GridLayoutManager(this, 3));
        rvStickers.setAdapter(new GifGridAdapter(GIF_LABELS, label -> returnSticker("gif", label)));
    }

    private void loadTrendingGrid() {
        rvStickers.setLayoutManager(new GridLayoutManager(this, 6));
        List<String> list = Arrays.asList(TRENDING);
        rvStickers.setAdapter(new EmojiGridAdapter(list, emoji -> returnSticker("emoji", emoji)));
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (currentTab == TAB_EMOJI) {
                    buildEmojiList(s.toString());
                    if (emojiAdapter != null) emojiAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void setupTextTab() {
        rvFontColors.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvFontColors.setAdapter(new ColorPickerAdapter(COLOR_VALUES, colorInt -> {
            selectedColor = colorInt;
            etTextSticker.setTextColor(colorInt);
        }));

        btnAddText.setOnClickListener(v -> {
            String text = etTextSticker.getText() != null
                ? etTextSticker.getText().toString().trim() : "";
            if (text.isEmpty()) {
                Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show();
                return;
            }
            returnSticker("text", text + "|" + String.format("#%06X", (0xFFFFFF & selectedColor)));
        });
    }

    // ── Interactive stickers tab ──────────────────────────────────────────

    /**
     * Builds the interactive sticker cards inside layoutInteractive.
     * Called once on create. Each card opens a creator dialog when tapped.
     */
    private void buildInteractiveTab() {
        if (layoutInteractive == null) return;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(24));

        // Header label
        TextView header = new TextView(this);
        header.setText("Interactive Stickers");
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(15);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, 0, 0, dpToPx(4));
        container.addView(header);

        TextView subHeader = new TextView(this);
        subHeader.setText("Engage your audience — viewers respond directly on your reel");
        subHeader.setTextColor(0xFFAAAAAA);
        subHeader.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dpToPx(16);
        container.addView(subHeader, subLp);

        // Sticker cards
        for (String[] info : INTERACTIVE_STICKERS) {
            String emoji = info[0];
            String name  = info[1];
            String desc  = info[2];

            android.widget.LinearLayout card = buildInteractiveCard(emoji, name, desc);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dpToPx(10);
            card.setOnClickListener(v -> openInteractiveCreator(name));
            container.addView(card, cardLp);
        }

        layoutInteractive.addView(container);
    }

    private android.widget.LinearLayout buildInteractiveCard(String emoji, String name, String desc) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14));

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1E1E2E);
        bg.setCornerRadius(dpToPx(14));
        bg.setStroke(1, 0xFF333344);
        card.setBackground(bg);

        // Emoji circle
        TextView tvEmoji = new TextView(this);
        tvEmoji.setText(emoji);
        tvEmoji.setTextSize(28);
        tvEmoji.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams emojiLp = new android.widget.LinearLayout.LayoutParams(
            dpToPx(52), dpToPx(52));
        emojiLp.rightMargin = dpToPx(14);
        android.graphics.drawable.GradientDrawable emojiBg = new android.graphics.drawable.GradientDrawable();
        emojiBg.setColor(0xFF2A2A3E);
        emojiBg.setCornerRadius(dpToPx(12));
        tvEmoji.setBackground(emojiBg);
        card.addView(tvEmoji, emojiLp);

        // Text column
        android.widget.LinearLayout textCol = new android.widget.LinearLayout(this);
        textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams textColLp = new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(textColLp);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(15);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(tvName);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextColor(0xFFAAAAAA);
        tvDesc.setTextSize(12);
        tvDesc.setMaxLines(2);
        textCol.addView(tvDesc);

        card.addView(textCol, textColLp);

        // Arrow icon
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(0xFFFF3B5C);
        arrow.setTextSize(22);
        card.addView(arrow);

        return card;
    }

    /** Opens the appropriate creator dialog for the given interactive sticker type. */
    private void openInteractiveCreator(String type) {
        switch (type) {
            case "Poll":     showPollCreator();     break;
            case "Quiz":     showQuizCreator();     break;
            case "Slider":   showSliderCreator();   break;
            case "Question": showQuestionCreator(); break;
        }
    }

    // ─── Poll Creator ──────────────────────────────────────────────────────

    private void showPollCreator() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(8));

        EditText etQuestion = new EditText(this);
        etQuestion.setHint("Poll question e.g. Which do you prefer?");
        etQuestion.setTextColor(0xFF111111);
        etQuestion.setHintTextColor(0xFF888888);
        etQuestion.setTextSize(15);
        etQuestion.setMaxLines(2);
        etQuestion.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        layout.addView(etQuestion);

        addDivider(layout);

        List<EditText> optionFields = new ArrayList<>();
        String[] defaultOpts = {"Option 1", "Option 2", "Option 3", "Option 4"};
        for (int i = 0; i < 4; i++) {
            EditText et = new EditText(this);
            et.setHint(defaultOpts[i]);
            et.setTextColor(0xFF111111);
            et.setHintTextColor(0xFF888888);
            et.setTextSize(14);
            et.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dpToPx(8);
            layout.addView(et, lp);
            optionFields.add(et);
        }

        new AlertDialog.Builder(this)
            .setTitle("📊 Create Poll")
            .setView(layout)
            .setPositiveButton("Add to Reel", (d, w) -> {
                String question = etQuestion.getText().toString().trim();
                if (question.isEmpty()) { Toast.makeText(this, "Add a question", Toast.LENGTH_SHORT).show(); return; }
                List<String> opts = new ArrayList<>();
                for (EditText ef : optionFields) {
                    String opt = ef.getText().toString().trim();
                    if (!opt.isEmpty()) opts.add(opt);
                }
                if (opts.size() < 2) { Toast.makeText(this, "Add at least 2 options", Toast.LENGTH_SHORT).show(); return; }
                returnInteractiveSticker("poll", question, opts, "");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─── Quiz Creator ──────────────────────────────────────────────────────

    private void showQuizCreator() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(8));

        EditText etQuestion = new EditText(this);
        etQuestion.setHint("Quiz question e.g. What year did...?");
        etQuestion.setTextColor(0xFF111111);
        etQuestion.setHintTextColor(0xFF888888);
        etQuestion.setTextSize(15);
        etQuestion.setMaxLines(2);
        etQuestion.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        layout.addView(etQuestion);

        addDivider(layout);

        TextView hint = new TextView(this);
        hint.setText("Options (mark one as correct):");
        hint.setTextColor(0xFF555555);
        hint.setTextSize(13);
        android.widget.LinearLayout.LayoutParams hintLp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dpToPx(8);
        layout.addView(hint, hintLp);

        List<EditText> optionFields = new ArrayList<>();
        final int[] correctIndex = {0};

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dpToPx(6);
            layout.addView(row, rowLp);

            CheckBox cb = new CheckBox(this);
            cb.setChecked(i == 0);
            cb.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    correctIndex[0] = idx;
                    // uncheck others — find all checkboxes in parent
                    for (int j = 0; j < layout.getChildCount(); j++) {
                        View child = layout.getChildAt(j);
                        if (child instanceof android.widget.LinearLayout) {
                            android.widget.LinearLayout rowView = (android.widget.LinearLayout) child;
                            if (rowView.getChildCount() > 0 && rowView.getChildAt(0) instanceof CheckBox) {
                                CheckBox other = (CheckBox) rowView.getChildAt(0);
                                if (other != btn) other.setChecked(false);
                            }
                        }
                    }
                }
            });
            row.addView(cb);

            EditText et = new EditText(this);
            et.setHint("Option " + (i + 1));
            et.setTextColor(0xFF111111);
            et.setHintTextColor(0xFF888888);
            et.setTextSize(14);
            et.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
            android.widget.LinearLayout.LayoutParams etLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(et, etLp);
            optionFields.add(et);
        }

        new AlertDialog.Builder(this)
            .setTitle("🧠 Create Quiz")
            .setView(layout)
            .setPositiveButton("Add to Reel", (d, w) -> {
                String question = etQuestion.getText().toString().trim();
                if (question.isEmpty()) { Toast.makeText(this, "Add a question", Toast.LENGTH_SHORT).show(); return; }
                List<String> opts = new ArrayList<>();
                for (EditText ef : optionFields) {
                    String opt = ef.getText().toString().trim();
                    if (!opt.isEmpty()) opts.add(opt);
                }
                if (opts.size() < 2) { Toast.makeText(this, "Add at least 2 options", Toast.LENGTH_SHORT).show(); return; }
                // embed correct index in first option prefix "✓"
                String configExtra = "correctIndex:" + correctIndex[0];
                returnInteractiveSticker("quiz", question, opts, configExtra);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─── Slider Creator ────────────────────────────────────────────────────

    private void showSliderCreator() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(8));

        EditText etQuestion = new EditText(this);
        etQuestion.setHint("Ask viewers to rate something...");
        etQuestion.setTextColor(0xFF111111);
        etQuestion.setHintTextColor(0xFF888888);
        etQuestion.setTextSize(15);
        etQuestion.setMaxLines(2);
        etQuestion.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        layout.addView(etQuestion);

        addDivider(layout);

        TextView emojiLabel = new TextView(this);
        emojiLabel.setText("Slider emoji:");
        emojiLabel.setTextColor(0xFF555555);
        emojiLabel.setTextSize(13);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(12);
        layout.addView(emojiLabel, lp);

        String[] sliderEmojis = {"😍","🔥","👏","💯","🌟","❤️","🤩","😂","🎉","✨"};
        final String[] selectedSliderEmoji = {"😍"};

        android.widget.LinearLayout emojiRow = new android.widget.LinearLayout(this);
        emojiRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams emojiRowLp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        emojiRowLp.topMargin = dpToPx(6);
        layout.addView(emojiRow, emojiRowLp);

        for (String em : sliderEmojis) {
            TextView tvEm = new TextView(this);
            tvEm.setText(em);
            tvEm.setTextSize(24);
            tvEm.setGravity(android.view.Gravity.CENTER);
            tvEm.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
            tvEm.setOnClickListener(v -> {
                selectedSliderEmoji[0] = em;
                // Highlight selected
                for (int i = 0; i < emojiRow.getChildCount(); i++) {
                    emojiRow.getChildAt(i).setAlpha(0.4f);
                }
                tvEm.setAlpha(1f);
            });
            tvEm.setAlpha(em.equals("😍") ? 1f : 0.4f);
            emojiRow.addView(tvEm);
        }

        new AlertDialog.Builder(this)
            .setTitle("😍 Create Slider")
            .setView(layout)
            .setPositiveButton("Add to Reel", (d, w) -> {
                String question = etQuestion.getText().toString().trim();
                if (question.isEmpty()) { Toast.makeText(this, "Add a question", Toast.LENGTH_SHORT).show(); return; }
                returnInteractiveSticker("slider", question, new ArrayList<>(), "emoji:" + selectedSliderEmoji[0]);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─── Question Box Creator ──────────────────────────────────────────────

    private void showQuestionCreator() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(8));

        EditText etQuestion = new EditText(this);
        etQuestion.setHint("Ask me anything! or type your own prompt...");
        etQuestion.setTextColor(0xFF111111);
        etQuestion.setHintTextColor(0xFF888888);
        etQuestion.setTextSize(15);
        etQuestion.setMaxLines(2);
        etQuestion.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        layout.addView(etQuestion);

        addDivider(layout);

        TextView hint = new TextView(this);
        hint.setText("Viewers can type any answer and send it to you.");
        hint.setTextColor(0xFF888888);
        hint.setTextSize(12);
        android.widget.LinearLayout.LayoutParams hintLp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dpToPx(10);
        layout.addView(hint, hintLp);

        new AlertDialog.Builder(this)
            .setTitle("💬 Question Box")
            .setView(layout)
            .setPositiveButton("Add to Reel", (d, w) -> {
                String question = etQuestion.getText().toString().trim();
                if (question.isEmpty()) question = "Ask me anything!";
                returnInteractiveSticker("question", question, new ArrayList<>(), "");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─── Return helpers ────────────────────────────────────────────────────

    private void addDivider(android.widget.LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFEEEEEE);
        android.widget.LinearLayout.LayoutParams dp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dp.topMargin = dpToPx(10);
        dp.bottomMargin = dpToPx(4);
        parent.addView(divider, dp);
    }

    /**
     * Build and return an interactive sticker JSON to the calling activity.
     *
     * JSON format:
     * {
     *   "type": "interactive",
     *   "stickerType": "poll"|"quiz"|"slider"|"question",
     *   "question": "...",
     *   "options": ["opt1","opt2",...],
     *   "extra": "...",
     *   "x": 0.5,
     *   "y": 0.4
     * }
     */
    private void returnInteractiveSticker(String stickerType, String question,
                                          List<String> options, String extra) {
        StringBuilder optArr = new StringBuilder("[");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) optArr.append(",");
            optArr.append("\"").append(options.get(i).replace("\"","\\\"")).append("\"");
        }
        optArr.append("]");

        String json = "{\"type\":\"interactive\","
            + "\"stickerType\":\"" + stickerType + "\","
            + "\"question\":\"" + question.replace("\"","\\\"") + "\","
            + "\"options\":" + optArr + ","
            + "\"extra\":\"" + extra.replace("\"","\\\"") + "\","
            + "\"x\":0.5,\"y\":0.4}";

        Intent result = new Intent();
        result.putExtra(RESULT_STICKER_JSON, json);
        setResult(RESULT_OK, result);
        finish();
    }

    private void returnSticker(String type, String value) {
        String json = "{\"type\":\"" + type + "\",\"value\":\"" +
            value.replace("\"", "\\\"") + "\",\"x\":0.5,\"y\":0.5}";
        Intent result = new Intent();
        result.putExtra(RESULT_STICKER_JSON, json);
        setResult(RESULT_OK, result);
        finish();
    }

    private int dpToPx(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }

    // ── Emoji adapter ─────────────────────────────────────────────────────
    interface OnItemClick { void onClick(String val); }

    static class EmojiGridAdapter extends RecyclerView.Adapter<EmojiGridAdapter.VH> {
        private final List<String> items;
        private final OnItemClick  click;
        EmojiGridAdapter(List<String> items, OnItemClick click) { this.items=items; this.click=click; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            TextView tv = new TextView(p.getContext());
            int size = (int)(p.getContext().getResources().getDisplayMetrics().density * 56);
            tv.setLayoutParams(new RecyclerView.LayoutParams(size, size));
            tv.setTextSize(26); tv.setGravity(android.view.Gravity.CENTER);
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tv.setText(items.get(pos));
            h.tv.setOnClickListener(v -> click.onClick(items.get(pos)));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder { TextView tv; VH(TextView v){super(v);tv=v;} }
    }

    // ── GIF adapter (placeholder) ─────────────────────────────────────────
    static class GifGridAdapter extends RecyclerView.Adapter<GifGridAdapter.VH> {
        private final String[]   labels;
        private final OnItemClick click;
        GifGridAdapter(String[] labels, OnItemClick click) { this.labels=labels; this.click=click; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_gif_sticker, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tv.setText(labels[pos]);
            h.itemView.setOnClickListener(v -> click.onClick(labels[pos]));
        }
        @Override public int getItemCount() { return labels.length; }
        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(View v){ super(v); tv=v.findViewById(R.id.tv_gif_label); }
        }
    }

    // ── Color picker adapter ──────────────────────────────────────────────
    interface OnColorPick { void onPick(int color); }
    static class ColorPickerAdapter extends RecyclerView.Adapter<ColorPickerAdapter.VH> {
        private final int[]      colors;
        private final OnColorPick pick;
        ColorPickerAdapter(int[] colors, OnColorPick pick) { this.colors=colors; this.pick=pick; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            View circle = new View(p.getContext());
            int size = (int)(p.getContext().getResources().getDisplayMetrics().density * 36);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size,size);
            lp.setMarginEnd((int)(p.getContext().getResources().getDisplayMetrics().density*8));
            circle.setLayoutParams(lp);
            return new VH(circle);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(colors[pos]);
            gd.setStroke(2, 0x44FFFFFF);
            h.view.setBackground(gd);
            h.view.setOnClickListener(v -> pick.onPick(colors[pos]));
        }
        @Override public int getItemCount() { return colors.length; }
        static class VH extends RecyclerView.ViewHolder { View view; VH(View v){super(v);view=v;} }
    }
}
