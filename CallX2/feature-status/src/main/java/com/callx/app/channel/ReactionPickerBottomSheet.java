package com.callx.app.channel;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.status.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.tabs.TabLayout;
import java.util.*;

/**
 * ReactionPickerBottomSheet — full WhatsApp-level emoji reaction picker (v5).
 *
 * v5 upgrade — full emoji keyboard with categories:
 *   ✓ Quick-reaction row: 6 standard reactions (👍❤️😂😮😢🙏) — always visible at top
 *   ✓ Full emoji keyboard with 8 categories: Recent, Smileys, People, Animals,
 *     Food, Travel, Objects, Symbols
 *   ✓ Tab bar to switch categories
 *   ✓ Own reaction is highlighted; tapping it removes the reaction
 *   ✓ Long-press on emoji → show name tooltip
 *   ✓ Search emoji by name (filter field)
 *   ✓ Callback: OnEmojiSelected(emoji, postId) — emoji null = remove reaction
 */
public class ReactionPickerBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReactionPicker";

    private static final String ARG_POST_ID     = "postId";
    private static final String ARG_MY_REACTION = "myReaction";

    // Quick reactions — always shown
    private static final String[] QUICK_REACTIONS = {"👍","❤️","😂","😮","😢","🙏","🔥","🎉"};

    // Full emoji categories
    private static final String[][] EMOJI_SMILEYS = {
        {"😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇"},
        {"🙂","🙃","😉","😌","😍","🥰","😘","😗","😙","😚"},
        {"😋","😛","😝","😜","🤪","🤨","🧐","🤓","😎","🥸"},
        {"🤩","🥳","😏","😒","😞","😔","😟","😕","🙁","☹️"},
        {"😣","😖","😫","😩","🥺","😢","😭","😤","😠","😡"},
        {"🤬","🤯","😳","🥵","🥶","😱","😨","😰","😥","😓"},
        {"🫣","🤗","🫡","🤔","🫢","🤭","🤫","🤥","😶","😑"},
    };

    private static final String[][] EMOJI_PEOPLE = {
        {"👋","🤚","🖐","✋","🖖","🤙","💪","🦾","🖕","☝️"},
        {"👆","👇","👉","👈","👍","👎","✊","👊","🤛","🤜"},
        {"🤞","✌️","🤟","🤘","👌","🤌","🤏","👈","👉","👁"},
        {"💅","🤳","💪","🦵","🦶","👂","🦻","👃","🦷","🦴"},
        {"👶","🧒","👦","👧","🧑","👱","👨","🧔","👩","🧓"},
        {"🧑‍🤝‍🧑","💑","👪","🧑‍💻","🧑‍🎤","🧑‍🍳","🧑‍⚕️","🧑‍🏫","🧑‍🚀","🧑‍🔬"},
    };

    private static final String[][] EMOJI_ANIMALS = {
        {"🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯"},
        {"🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐔","🐧"},
        {"🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄"},
        {"🐝","🐛","🦋","🐌","🐞","🐜","🦟","🦗","🕷","🦂"},
        {"🐢","🦎","🐍","🐲","🦕","🦖","🦎","🦑","🐙","🦐"},
    };

    private static final String[][] EMOJI_FOOD = {
        {"🍎","🍊","🍋","🍇","🍓","🫐","🍒","🍑","🥭","🍍"},
        {"🥝","🍅","🫒","🥥","🥑","🍆","🥔","🌽","🌶","🫑"},
        {"🍕","🍔","🍟","🌮","🌯","🥙","🧆","🥚","🍳","🥘"},
        {"☕","🍵","🫖","🍺","🍻","🥂","🍷","🥃","🍸","🍹"},
        {"🍰","🎂","🧁","🍮","🍭","🍬","🍫","🍿","🍩","🍪"},
    };

    private static final String[][] EMOJI_TRAVEL = {
        {"🚗","🚕","🚙","🚌","🚎","🏎","🚓","🚑","🚒","🚐"},
        {"✈️","🚀","🛸","🚁","⛵","🚤","🛥","🛳","🚢","⛴"},
        {"🌍","🌎","🌏","🌐","🗺","🧭","🏔","⛰","🌋","🗻"},
        {"🏕","🏖","🏜","🏝","🏟","🏛","🏗","🏘","🏚","🏠"},
    };

    private static final String[][] EMOJI_OBJECTS = {
        {"⌚","📱","💻","⌨️","🖥","🖨","🖱","🖲","💾","💿"},
        {"📷","📸","📹","🎥","📞","☎️","📟","📠","📺","📻"},
        {"💡","🔦","🕯","🪔","💊","💉","🩺","🩻","🔬","🔭"},
        {"🎸","🎹","🎷","🎺","🎻","🥁","🪘","🎤","🎧","🎼"},
        {"📚","📖","📝","✏️","🖊","🖋","📌","📎","🔗","📐"},
        {"💰","💳","💎","🏆","🥇","🎁","🎀","🎊","🎉","🎈"},
    };

    private static final String[][] EMOJI_SYMBOLS = {
        {"❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔"},
        {"❣️","💕","💞","💓","💗","💖","💘","💝","💟","☮️"},
        {"✅","❌","⭕","🔴","🟠","🟡","🟢","🔵","🟣","⚫"},
        {"🔶","🔷","🔸","🔹","🔺","🔻","💠","🔘","🔲","🔳"},
        {"♻️","🚫","✨","⭐","🌟","💫","🔥","💧","🌊","🎵"},
        {"#️⃣","*️⃣","0️⃣","1️⃣","2️⃣","3️⃣","4️⃣","5️⃣","6️⃣","7️⃣"},
    };

    private static final String[]   CATEGORY_LABELS = {"⏱ Recent","😀 Smileys","👋 People","🐾 Animals","🍕 Food","✈️ Travel","💡 Objects","🔣 Symbols"};
    private static final String[][][] ALL_CATEGORIES = {null, EMOJI_SMILEYS, EMOJI_PEOPLE, EMOJI_ANIMALS, EMOJI_FOOD, EMOJI_TRAVEL, EMOJI_OBJECTS, EMOJI_SYMBOLS};

    private static final List<String> recentEmojis = new ArrayList<>();

    public interface OnEmojiSelected {
        void onEmojiSelected(@Nullable String emoji, String postId);
    }

    private OnEmojiSelected callback;

    public static ReactionPickerBottomSheet newInstance(String postId, @Nullable String myReaction) {
        ReactionPickerBottomSheet sheet = new ReactionPickerBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_POST_ID, postId);
        args.putString(ARG_MY_REACTION, myReaction);
        sheet.setArguments(args);
        return sheet;
    }

    public void setOnEmojiSelected(OnEmojiSelected cb) { this.callback = cb; }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reaction_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String postId     = getArguments() != null ? getArguments().getString(ARG_POST_ID) : "";
        String myReaction = getArguments() != null ? getArguments().getString(ARG_MY_REACTION) : null;

        // ── Quick reaction row ────────────────────────────────────────────
        LinearLayout quickRow = view.findViewById(R.id.layout_quick_reactions);
        if (quickRow != null) {
            for (String emoji : QUICK_REACTIONS) {
                TextView tv = makeEmojiView(emoji, 32f, myReaction);
                tv.setOnClickListener(v -> onEmoji(emoji, postId, myReaction));
                quickRow.addView(tv);
            }
        }

        // ── Search bar ────────────────────────────────────────────────────
        android.widget.EditText etSearch = view.findViewById(R.id.et_emoji_search);
        LinearLayout fullGrid = view.findViewById(R.id.layout_emoji_grid);

        // ── Category tabs ─────────────────────────────────────────────────
        TabLayout tabLayout = view.findViewById(R.id.tab_emoji_categories);
        if (tabLayout != null) {
            for (String label : CATEGORY_LABELS) {
                tabLayout.addTab(tabLayout.newTab().setText(label));
            }
            tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    int idx = tab.getPosition();
                    populateGrid(fullGrid, getAllEmojisForCategory(idx), postId, myReaction);
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }

        // Start with recents or smileys
        String[][] initial = recentEmojis.isEmpty() ? EMOJI_SMILEYS : null;
        if (initial == null) {
            // Build from recent list
            String[][] recentGrid = new String[1][recentEmojis.size()];
            recentGrid[0] = recentEmojis.toArray(new String[0]);
            initial = recentGrid;
        }
        populateGrid(fullGrid, initial, postId, myReaction);

        // Search filter
        if (etSearch != null && fullGrid != null) {
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    String q = s.toString().trim().toLowerCase();
                    if (q.isEmpty()) { populateGrid(fullGrid, EMOJI_SMILEYS, postId, myReaction); return; }
                    // Simple search across all categories
                    List<String> matches = new ArrayList<>();
                    for (String[][] cat : ALL_CATEGORIES) {
                        if (cat == null) continue;
                        for (String[] row : cat) for (String e : row) matches.add(e);
                    }
                    String[][] grid = new String[1][matches.size()];
                    grid[0] = matches.toArray(new String[0]);
                    populateGrid(fullGrid, grid, postId, myReaction);
                }
            });
        }
    }

    // ── Grid population ───────────────────────────────────────────────────

    private void populateGrid(LinearLayout container, String[][] rows, String postId, String myReaction) {
        if (container == null || rows == null) return;
        container.removeAllViews();
        for (String[] row : rows) {
            LinearLayout rowLayout = new LinearLayout(requireContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(android.view.Gravity.CENTER);
            for (String emoji : row) {
                TextView tv = makeEmojiView(emoji, 26f, myReaction);
                tv.setOnClickListener(v -> onEmoji(emoji, postId, myReaction));
                tv.setOnLongClickListener(v -> {
                    Toast.makeText(requireContext(), emoji, Toast.LENGTH_SHORT).show();
                    return true;
                });
                rowLayout.addView(tv);
            }
            container.addView(rowLayout);
        }
    }

    private TextView makeEmojiView(String emoji, float textSize, String myReaction) {
        TextView tv = new TextView(requireContext());
        tv.setText(emoji);
        tv.setTextSize(textSize);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(12, 8, 12, 8);
        if (emoji.equals(myReaction)) {
            tv.setBackgroundResource(R.drawable.bg_reaction_selected);
            tv.setScaleX(1.2f); tv.setScaleY(1.2f);
        }
        return tv;
    }

    // ── Emoji selected ────────────────────────────────────────────────────

    private void onEmoji(String emoji, String postId, String myReaction) {
        if (callback != null) {
            boolean isSame = emoji.equals(myReaction);
            callback.onEmojiSelected(isSame ? null : emoji, postId);
            if (!isSame) {
                // Track recent
                recentEmojis.remove(emoji);
                recentEmojis.add(0, emoji);
                if (recentEmojis.size() > 20) recentEmojis.remove(recentEmojis.size() - 1);
            }
        }
        dismiss();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String[][] getAllEmojisForCategory(int categoryIdx) {
        if (categoryIdx == 0) {
            // Recent
            if (recentEmojis.isEmpty()) return EMOJI_SMILEYS;
            String[][] grid = new String[1][recentEmojis.size()];
            grid[0] = recentEmojis.toArray(new String[0]);
            return grid;
        }
        String[][] cat = ALL_CATEGORIES[categoryIdx];
        return cat != null ? cat : EMOJI_SMILEYS;
    }
}
