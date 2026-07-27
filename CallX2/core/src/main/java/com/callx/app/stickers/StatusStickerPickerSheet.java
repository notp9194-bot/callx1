package com.callx.app.stickers;
import com.callx.app.utils.AlertDialogStyler;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * StatusStickerPickerSheet — Instagram-style sticker picker for Status/Stories.
 *
 * Available sticker types:
 *  ✅ 🎵 Music Sticker    — show now-playing song card on status
 *  ✅ ⏳ Countdown Timer  — count down to an upcoming event
 *  ✅ 🧠 Quiz Sticker     — multiple choice quiz, one correct answer
 *  ✅ 💬 Question Box     — open-ended question for viewers to answer
 *  ✅ 🗳️ Poll Sticker     — 2-option vote (Yes/No style), live % split
 *  ✅ 🎚️ Slider Sticker   — emoji rating slider (0-100%), live average
 *  ✅ 👤 Mention Sticker  — @username tag, tap opens that user's profile
 *  ✅ #️⃣ Hashtag Sticker  — #topic tag, tap opens the hashtag's X feed
 *  ✅ 🔗 Link Sticker     — tappable external link, opens in the browser
 *  ✅ ➕ Add Yours        — a prompt that opens the composer so others can
 *                          post their own story continuing the same chain
 *
 * Usage:
 *   StatusStickerPickerSheet.show(activity, listener);
 *
 * The listener receives a StickerResult containing type + config JSON.
 */
public class StatusStickerPickerSheet extends BottomSheetDialogFragment {

    public interface OnStickerSelected {
        void onSelected(StickerResult result);
    }

    public static class StickerResult {
        public final String type;   // "music" | "countdown" | "quiz" | "question" | "poll" | "slider" | "mention" | "hashtag" | "link" | "addyours"
        public final String json;   // config JSON for this sticker

        public StickerResult(String type, String json) {
            this.type = type;
            this.json = json;
        }
    }

    private OnStickerSelected listener;

    // Request code for ReelTrendingAudioActivity (feature-reels). Launched via
    // reflection since feature-status does not have a compile-time dependency
    // on feature-reels (only feature-reels depends on feature-status).
    private static final int REQ_MUSIC_TRENDING_AUDIO = 7031;

    public static StatusStickerPickerSheet show(
            androidx.fragment.app.FragmentActivity host,
            OnStickerSelected listener) {
        StatusStickerPickerSheet sheet = new StatusStickerPickerSheet();
        sheet.listener = listener;
        sheet.show(host.getSupportFragmentManager(), "status_sticker_picker");
        return sheet;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setContentView(buildView(dialog.getContext()));
        dialog.getBehavior().setPeekHeight(600);
        return dialog;
    }

    private View buildView(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(0xFF141414);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp * 16, dp * 20, dp * 16, dp * 32);

        // Header
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("Add Sticker");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp * 20;
        root.addView(tvTitle, titleLp);

        // 2-wide grid of sticker type cards
        String[][] stickers = {
            {"🎵", "Music",     "Show what you're listening to"},
            {"⏳", "Countdown", "Create a countdown to an event"},
            {"🧠", "Quiz",      "Test your audience (one correct answer)"},
            {"💬", "Question",  "Ask viewers anything"},
            {"🗳️", "Poll",      "Let viewers vote between 2 options"},
            {"🎚️", "Slider",    "Emoji rating slider viewers can drag"},
            {"👤", "Mention",   "Tag a user — tap opens their profile"},
            {"#️⃣", "Hashtag",   "Tag a topic — tap opens the hashtag feed"},
            {"🔗", "Link",      "Add a tappable link — opens in browser"},
            {"➕", "Add Yours", "Start a prompt others can join"}
        };

        for (int r = 0; r < stickers.length; r += 2) {
            LinearLayout row = buildRow(ctx, dp);
            row.addView(buildStickerCard(ctx, dp, stickers[r]));
            if (r + 1 < stickers.length) row.addView(buildStickerCard(ctx, dp, stickers[r + 1]));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r + 2 < stickers.length) rowLp.bottomMargin = dp * 12;
            root.addView(row, rowLp);
        }

        sv.addView(root);
        return sv;
    }

    private LinearLayout buildRow(Context ctx, int dp) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private View buildStickerCard(Context ctx, int dp, String[] info) {
        String emoji = info[0];
        String name  = info[1];
        String desc  = info[2];

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER);
        card.setPadding(dp * 12, dp * 16, dp * 12, dp * 16);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 16);

        int cardColor;
        switch (name) {
            case "Music":     cardColor = 0xFF1A2540; break;
            case "Countdown": cardColor = 0xFF251A40; break;
            case "Quiz":      cardColor = 0xFF1A2520; break;
            case "Question":  cardColor = 0xFF251A1A; break;
            case "Poll":      cardColor = 0xFF102A2A; break;
            case "Slider":    cardColor = 0xFF2A1A2E; break;
            case "Mention":   cardColor = 0xFF14283A; break;
            case "Hashtag":   cardColor = 0xFF2A2410; break;
            case "Link":      cardColor = 0xFF232323; break;
            case "Add Yours": cardColor = 0xFF241A2E; break;
            default:          cardColor = 0xFF1E1E1E;
        }
        bg.setColor(cardColor);
        bg.setStroke(1, 0xFF333333);
        card.setBackground(bg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cardLp.leftMargin  = dp * 4;
        cardLp.rightMargin = dp * 4;
        card.setLayoutParams(cardLp);

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText(emoji);
        tvEmoji.setTextSize(36);
        tvEmoji.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eLp.bottomMargin = dp * 8;
        card.addView(tvEmoji, eLp);

        TextView tvName = new TextView(ctx);
        tvName.setText(name);
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(14);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvName.setGravity(android.view.Gravity.CENTER);
        card.addView(tvName);

        TextView tvDesc = new TextView(ctx);
        tvDesc.setText(desc);
        tvDesc.setTextColor(0xFF888888);
        tvDesc.setTextSize(11);
        tvDesc.setGravity(android.view.Gravity.CENTER);
        tvDesc.setMaxLines(2);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = dp * 4;
        card.addView(tvDesc, dLp);

        card.setOnClickListener(v -> {
            if ("Music".equals(name)) {
                // Music picks a real track from ReelTrendingAudioActivity and comes back
                // via onActivityResult — dismissing now would detach this fragment before
                // the result arrives, so we only dismiss once the pick is confirmed.
                openMusicCreator(ctx);
            } else {
                dismiss();
                openCreator(ctx, name);
            }
        });

        // Ripple effect
        card.setClickable(true);
        card.setFocusable(true);

        return card;
    }

    private void openCreator(Context ctx, String type) {
        switch (type) {
            case "Music":     openMusicCreator(ctx);     break;
            case "Countdown": openCountdownCreator(ctx); break;
            case "Quiz":      openQuizCreator(ctx);      break;
            case "Question":  openQuestionCreator(ctx);  break;
            case "Poll":      openPollCreator(ctx);      break;
            case "Slider":    openSliderCreator(ctx);    break;
            case "Mention":   openMentionCreator(ctx);   break;
            case "Hashtag":   openHashtagCreator(ctx);   break;
            case "Link":      openLinkCreator(ctx);      break;
            case "Add Yours": openAddYoursCreator(ctx);  break;
        }
    }

    // ─── Music Sticker Creator ─────────────────────────────────────────────
    // Instead of typing a song name, the user picks a real track from the same
    // Trending Audio screen used by Reels. The picked track's soundId/soundUrl
    // travel with the sticker so a viewer tapping it later opens that exact
    // track's Sound Detail sheet (same one Reels uses).

    private void openMusicCreator(Context ctx) {
        try {
            Class<?> trendingCls = Class.forName("com.callx.app.music.ReelTrendingAudioActivity");
            Intent intent = new Intent(ctx, trendingCls);
            startActivityForResult(intent, REQ_MUSIC_TRENDING_AUDIO);
        } catch (Exception e) {
            Toast.makeText(ctx, "Trending audio isn't available right now", Toast.LENGTH_SHORT).show();
            dismiss();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MUSIC_TRENDING_AUDIO) return;

        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            // Literal extra keys mirror ReelTrendingAudioActivity.RESULT_* constants —
            // kept as literals (not a compile-time import) since feature-status has
            // no build dependency on feature-reels.
            String soundId = data.getStringExtra("audio_id");
            String title   = data.getStringExtra("audio_title");
            String artist  = data.getStringExtra("audio_artist");
            // Preview-only stream (audio_preview_url) — never the full-quality
            // audio_url, since that's meant for actual reel composition, not
            // for a viewer just tapping to listen to the sticker.
            String soundUrl= data.getStringExtra("audio_preview_url");
            String coverUrl= data.getStringExtra("audio_cover_url");

            if (title == null || title.trim().isEmpty()) {
                dismiss();
                return;
            }
            String json = "{\"type\":\"music\""
                + ",\"song\":\"" + esc(title.trim()) + "\""
                + ",\"artist\":\"" + esc(artist != null ? artist.trim() : "") + "\""
                + ",\"albumArt\":\"" + esc(coverUrl != null ? coverUrl : "") + "\""
                + ",\"soundId\":\"" + esc(soundId != null ? soundId : "") + "\""
                + ",\"soundUrl\":\"" + esc(soundUrl != null ? soundUrl : "") + "\""
                + "}";
            if (listener != null) listener.onSelected(new StickerResult("music", json));
        }
        dismiss();
    }

    // ─── Countdown Sticker Creator ─────────────────────────────────────────

    private void openCountdownCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etLabel = new EditText(ctx);
        etLabel.setHint("Event name e.g. My Birthday 🎂");
        etLabel.setTextSize(15);
        layout.addView(etLabel);

        TextView tvDate = new TextView(ctx);
        tvDate.setText("Target date:");
        tvDate.setTextColor(android.graphics.Color.GRAY);
        tvDate.setTextSize(13);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = dp * 14;
        layout.addView(tvDate, lbLp);

        // Simple date input
        EditText etDate = new EditText(ctx);
        etDate.setHint("YYYY-MM-DD e.g. 2025-12-31");
        etDate.setTextSize(14);
        etDate.setInputType(android.text.InputType.TYPE_CLASS_DATETIME);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = dp * 6;
        layout.addView(etDate, dateLp);

        // Color picker
        TextView tvColor = new TextView(ctx);
        tvColor.setText("Card color:");
        tvColor.setTextColor(android.graphics.Color.GRAY);
        tvColor.setTextSize(13);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clLp.topMargin = dp * 14;
        layout.addView(tvColor, clLp);

        String[][] colorOpts = {
            {"💜 Purple", "#7C3AED"},
            {"🔵 Blue",   "#1D4ED8"},
            {"🟠 Orange", "#EA580C"},
            {"🔴 Red",    "#DC2626"},
            {"🟢 Green",  "#16A34A"}
        };
        final String[] selectedColor = {"#7C3AED"};

        LinearLayout colorRow = new LinearLayout(ctx);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = dp * 6;
        layout.addView(colorRow, crLp);

        for (String[] opt : colorOpts) {
            final String hexColor = opt[1];
            TextView chip = new TextView(ctx);
            chip.setText(opt[0]);
            chip.setTextSize(11);
            chip.setPadding(dp * 8, dp * 6, dp * 8, dp * 6);
            chip.setTextColor(android.graphics.Color.WHITE);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(dp * 14);
            gd.setColor(android.graphics.Color.parseColor(hexColor));
            chip.setBackground(gd);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = dp * 6;
            chip.setLayoutParams(chipLp);
            chip.setOnClickListener(v -> {
                selectedColor[0] = hexColor;
                for (int i = 0; i < colorRow.getChildCount(); i++)
                    colorRow.getChildAt(i).setAlpha(0.5f);
                chip.setAlpha(1f);
            });
            chip.setAlpha(hexColor.equals("#7C3AED") ? 1f : 0.5f);
            colorRow.addView(chip);
        }

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("⏳ Countdown Sticker")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String label = etLabel.getText().toString().trim();
                String date  = etDate.getText().toString().trim();
                if (label.isEmpty()) {
                    Toast.makeText(ctx, "Add an event name", Toast.LENGTH_SHORT).show(); return;
                }
                String json = "{\"type\":\"countdown\",\"label\":\"" + esc(label)
                    + "\",\"targetDate\":\"" + esc(date)
                    + "\",\"color\":\"" + selectedColor[0] + "\"}";
                if (listener != null) listener.onSelected(new StickerResult("countdown", json));
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ─── Quiz Sticker Creator ──────────────────────────────────────────────

    private void openQuizCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etQuestion = new EditText(ctx);
        etQuestion.setHint("Quiz question e.g. Capital of France?");
        etQuestion.setTextSize(15);
        etQuestion.setMaxLines(2);
        layout.addView(etQuestion);

        TextView hint = new TextView(ctx);
        hint.setText("Options (tap ✓ to mark correct):");
        hint.setTextColor(android.graphics.Color.GRAY);
        hint.setTextSize(13);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp * 12;
        layout.addView(hint, hLp);

        java.util.List<EditText> optFields = new java.util.ArrayList<>();
        final int[] correctIdx = {0};

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp * 8;
            layout.addView(row, rowLp);

            CheckBox cb = new CheckBox(ctx);
            cb.setChecked(i == 0);
            cb.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) correctIdx[0] = idx;
            });
            row.addView(cb);

            EditText et = new EditText(ctx);
            et.setHint("Option " + (i + 1));
            et.setTextSize(14);
            row.addView(et, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            optFields.add(et);
        }

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("🧠 Quiz Sticker")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String question = etQuestion.getText().toString().trim();
                if (question.isEmpty()) {
                    Toast.makeText(ctx, "Add a question", Toast.LENGTH_SHORT).show(); return;
                }
                java.util.List<String> opts = new java.util.ArrayList<>();
                for (EditText ef : optFields) {
                    String o = ef.getText().toString().trim();
                    if (!o.isEmpty()) opts.add(o);
                }
                if (opts.size() < 2) {
                    Toast.makeText(ctx, "Add at least 2 options", Toast.LENGTH_SHORT).show(); return;
                }
                StringBuilder optsJson = new StringBuilder("[");
                for (int i = 0; i < opts.size(); i++) {
                    if (i > 0) optsJson.append(",");
                    optsJson.append("{\"text\":\"").append(esc(opts.get(i)))
                        .append("\",\"correct\":").append(i == correctIdx[0]).append("}");
                }
                optsJson.append("]");
                String json = "{\"type\":\"quiz\",\"question\":\"" + esc(question)
                    + "\",\"options\":" + optsJson
                    + ",\"correctIndex\":" + correctIdx[0] + "}";
                if (listener != null) listener.onSelected(new StickerResult("quiz", json));
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ─── Question Box Creator ──────────────────────────────────────────────

    private void openQuestionCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etQuestion = new EditText(ctx);
        etQuestion.setHint("Ask me anything! or type your own prompt...");
        etQuestion.setTextSize(15);
        etQuestion.setMaxLines(2);
        layout.addView(etQuestion);

        TextView sub = new TextView(ctx);
        sub.setText("Viewers can type any answer and send it to you privately.");
        sub.setTextColor(android.graphics.Color.GRAY);
        sub.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp * 10;
        layout.addView(sub, subLp);

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("💬 Question Box")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String question = etQuestion.getText().toString().trim();
                if (question.isEmpty()) question = "Ask me anything!";
                String json = "{\"type\":\"question\",\"prompt\":\"" + esc(question) + "\"}";
                if (listener != null) listener.onSelected(new StickerResult("question", json));
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ─── Poll Sticker Creator ───────────────────────────────────────────────

    private void openPollCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etQuestion = new EditText(ctx);
        etQuestion.setHint("Poll question e.g. Should I get a haircut?");
        etQuestion.setTextSize(15);
        etQuestion.setMaxLines(2);
        layout.addView(etQuestion);

        TextView hint = new TextView(ctx);
        hint.setText("Options:");
        hint.setTextColor(android.graphics.Color.GRAY);
        hint.setTextSize(13);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp * 12;
        layout.addView(hint, hLp);

        LinearLayout optRow = new LinearLayout(ctx);
        optRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams optRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        optRowLp.topMargin = dp * 6;
        layout.addView(optRow, optRowLp);

        EditText etOptA = new EditText(ctx);
        etOptA.setHint("Option A");
        etOptA.setText("Yes");
        etOptA.setTextSize(14);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        aLp.rightMargin = dp * 6;
        optRow.addView(etOptA, aLp);

        EditText etOptB = new EditText(ctx);
        etOptB.setHint("Option B");
        etOptB.setText("No");
        etOptB.setTextSize(14);
        optRow.addView(etOptB, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("🗳️ Poll Sticker")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String question = etQuestion.getText().toString().trim();
                if (question.isEmpty()) {
                    Toast.makeText(ctx, "Add a poll question", Toast.LENGTH_SHORT).show(); return;
                }
                String optA = etOptA.getText().toString().trim();
                String optB = etOptB.getText().toString().trim();
                if (optA.isEmpty()) optA = "Yes";
                if (optB.isEmpty()) optB = "No";
                String json = "{\"type\":\"poll\",\"question\":\"" + esc(question)
                    + "\",\"optionA\":\"" + esc(optA)
                    + "\",\"optionB\":\"" + esc(optB) + "\"}";
                if (listener != null) listener.onSelected(new StickerResult("poll", json));
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ─── Slider Sticker Creator ─────────────────────────────────────────────

    private void openSliderCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etQuestion = new EditText(ctx);
        etQuestion.setHint("Rate it! e.g. How excited are you?");
        etQuestion.setTextSize(15);
        etQuestion.setMaxLines(2);
        layout.addView(etQuestion);

        TextView tvEmojiLabel = new TextView(ctx);
        tvEmojiLabel.setText("Slider emoji:");
        tvEmojiLabel.setTextColor(android.graphics.Color.GRAY);
        tvEmojiLabel.setTextSize(13);
        LinearLayout.LayoutParams elLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        elLp.topMargin = dp * 14;
        layout.addView(tvEmojiLabel, elLp);

        String[] emojiOpts = {"❤️", "😂", "😮", "😢", "👍", "🔥"};
        final String[] selectedEmoji = {"❤️"};

        LinearLayout emojiRow = new LinearLayout(ctx);
        emojiRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams erLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        erLp.topMargin = dp * 6;
        layout.addView(emojiRow, erLp);

        for (String emoji : emojiOpts) {
            TextView chip = new TextView(ctx);
            chip.setText(emoji);
            chip.setTextSize(20);
            chip.setPadding(dp * 8, dp * 6, dp * 8, dp * 6);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = dp * 4;
            chip.setLayoutParams(chipLp);
            chip.setAlpha(emoji.equals(selectedEmoji[0]) ? 1f : 0.4f);
            chip.setOnClickListener(v -> {
                selectedEmoji[0] = emoji;
                for (int i = 0; i < emojiRow.getChildCount(); i++)
                    emojiRow.getChildAt(i).setAlpha(0.4f);
                chip.setAlpha(1f);
            });
            emojiRow.addView(chip);
        }

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("🎚️ Slider Sticker")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String question = etQuestion.getText().toString().trim();
                if (question.isEmpty()) question = "Rate it!";
                String json = "{\"type\":\"slider\",\"question\":\"" + esc(question)
                    + "\",\"emoji\":\"" + selectedEmoji[0] + "\"}";
                if (listener != null) listener.onSelected(new StickerResult("slider", json));
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ─── Mention Sticker Creator ────────────────────────────────────────────

    private void openMentionCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etUsername = new EditText(ctx);
        etUsername.setHint("username (without @)");
        etUsername.setTextSize(15);
        etUsername.setMaxLines(1);
        layout.addView(etUsername);

        TextView sub = new TextView(ctx);
        sub.setText("Tapping this sticker opens their profile.");
        sub.setTextColor(android.graphics.Color.GRAY);
        sub.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp * 10;
        layout.addView(sub, subLp);

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("👤 Mention Sticker")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String username = etUsername.getText().toString().trim();
                if (username.startsWith("@")) username = username.substring(1);
                if (username.isEmpty()) {
                    Toast.makeText(ctx, "Enter a username to mention", Toast.LENGTH_SHORT).show(); return;
                }
                String json = "{\"type\":\"mention\",\"username\":\"" + esc(username) + "\"}";
                if (listener != null) listener.onSelected(new StickerResult("mention", json));
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ─── Hashtag Sticker Creator ────────────────────────────────────────────

    private void openHashtagCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etTag = new EditText(ctx);
        etTag.setHint("topic (without #) e.g. sunset");
        etTag.setTextSize(15);
        etTag.setMaxLines(1);
        layout.addView(etTag);

        TextView sub = new TextView(ctx);
        sub.setText("Tapping this sticker opens the topic's feed.");
        sub.setTextColor(android.graphics.Color.GRAY);
        sub.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp * 10;
        layout.addView(sub, subLp);

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("#️⃣ Hashtag Sticker")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String tag = etTag.getText().toString().trim();
                if (tag.startsWith("#")) tag = tag.substring(1);
                tag = tag.replaceAll("\\s+", "");
                if (tag.isEmpty()) {
                    Toast.makeText(ctx, "Enter a topic to tag", Toast.LENGTH_SHORT).show(); return;
                }
                String json = "{\"type\":\"hashtag\",\"tag\":\"" + esc(tag) + "\"}";
                if (listener != null) listener.onSelected(new StickerResult("hashtag", json));
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ─── Link Sticker Creator ───────────────────────────────────────────────

    private void openLinkCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etUrl = new EditText(ctx);
        etUrl.setHint("https://example.com");
        etUrl.setTextSize(15);
        etUrl.setMaxLines(1);
        etUrl.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(etUrl);

        EditText etLabel = new EditText(ctx);
        etLabel.setHint("Link text (optional) e.g. Shop now");
        etLabel.setTextSize(14);
        etLabel.setMaxLines(1);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lLp.topMargin = dp * 10;
        layout.addView(etLabel, lLp);

        TextView sub = new TextView(ctx);
        sub.setText("Tapping this sticker opens the link in a browser.");
        sub.setTextColor(android.graphics.Color.GRAY);
        sub.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp * 10;
        layout.addView(sub, subLp);

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("🔗 Link Sticker")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String url = etUrl.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(ctx, "Enter a link", Toast.LENGTH_SHORT).show(); return;
                }
                // No scheme typed — default to https so ACTION_VIEW resolves correctly later.
                if (!url.matches("(?i)^[a-z][a-z0-9+.\\-]*://.*")) url = "https://" + url;
                String label = etLabel.getText().toString().trim();
                String json = "{\"type\":\"link\",\"url\":\"" + esc(url)
                    + "\",\"label\":\"" + esc(label) + "\"}";
                if (listener != null) listener.onSelected(new StickerResult("link", json));
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ─── Add Yours Sticker Creator ─────────────────────────────────────────
    // The poster only sets the prompt here — originUid/originName stay empty,
    // marking this status as the start of the chain. When a viewer taps the
    // rendered card, StatusViewerActivity opens the composer pre-loaded with
    // this same prompt and fills in originUid/originName, so the chain keeps
    // pointing back to whoever started it (not just the immediately-previous
    // poster).

    private void openAddYoursCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etPrompt = new EditText(ctx);
        etPrompt.setHint("e.g. My study era 📚");
        etPrompt.setTextSize(15);
        etPrompt.setMaxLines(2);
        layout.addView(etPrompt);

        TextView sub = new TextView(ctx);
        sub.setText("Friends who tap this can post their own story with the same prompt.");
        sub.setTextColor(android.graphics.Color.GRAY);
        sub.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp * 10;
        layout.addView(sub, subLp);

        // Quick-fill suggestion chips — tapping one just fills the field, doesn't submit.
        String[] suggestions = {"My study era 📚", "Rate my fit 👗", "Song of the day 🎵", "POV: today 📍"};
        LinearLayout chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams chipRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipRowLp.topMargin = dp * 12;
        layout.addView(chipRow, chipRowLp);

        for (String sug : suggestions) {
            TextView chip = new TextView(ctx);
            chip.setText(sug);
            chip.setTextColor(android.graphics.Color.WHITE);
            chip.setTextSize(11);
            chip.setPadding(dp * 8, dp * 6, dp * 8, dp * 6);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(dp * 14);
            gd.setColor(0xFF333333);
            chip.setBackground(gd);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = dp * 6;
            chip.setOnClickListener(v -> etPrompt.setText(sug));
            chipRow.addView(chip, chipLp);
        }

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(ctx)
            .setTitle("➕ Add Yours Sticker")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String prompt = etPrompt.getText().toString().trim();
                if (prompt.isEmpty()) {
                    Toast.makeText(ctx, "Add a prompt", Toast.LENGTH_SHORT).show(); return;
                }
                String json = "{\"type\":\"addyours\",\"prompt\":\"" + esc(prompt)
                    + "\",\"originUid\":\"\",\"originName\":\"\"}";
                if (listener != null) listener.onSelected(new StickerResult("addyours", json));
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
