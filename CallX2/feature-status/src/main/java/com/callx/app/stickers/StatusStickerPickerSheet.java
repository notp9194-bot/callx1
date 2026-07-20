package com.callx.app.stickers;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.status.R;

/**
 * StatusStickerPickerSheet — Instagram-style sticker picker for Status/Stories.
 *
 * Available sticker types:
 *  ✅ 🎵 Music Sticker   — show now-playing song card on status
 *  ✅ ⏳ Countdown Timer — count down to an upcoming event
 *  ✅ 🧠 Quiz Sticker    — multiple choice quiz, one correct answer
 *  ✅ 💬 Question Box    — open-ended question for viewers to answer
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
        public final String type;   // "music" | "countdown" | "quiz" | "question"
        public final String json;   // config JSON for this sticker

        public StickerResult(String type, String json) {
            this.type = type;
            this.json = json;
        }
    }

    private OnStickerSelected listener;

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

        // 2x2 grid of sticker type cards
        String[][] stickers = {
            {"🎵", "Music",     "Show what you're listening to"},
            {"⏳", "Countdown", "Create a countdown to an event"},
            {"🧠", "Quiz",      "Test your audience (one correct answer)"},
            {"💬", "Question",  "Ask viewers anything"}
        };

        // Row 1
        LinearLayout row1 = buildRow(ctx, dp);
        row1.addView(buildStickerCard(ctx, dp, stickers[0]));
        row1.addView(buildStickerCard(ctx, dp, stickers[1]));
        LinearLayout.LayoutParams rowLp1 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp1.bottomMargin = dp * 12;
        root.addView(row1, rowLp1);

        // Row 2
        LinearLayout row2 = buildRow(ctx, dp);
        row2.addView(buildStickerCard(ctx, dp, stickers[2]));
        row2.addView(buildStickerCard(ctx, dp, stickers[3]));
        root.addView(row2, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
            dismiss();
            openCreator(ctx, name);
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
        }
    }

    // ─── Music Sticker Creator ─────────────────────────────────────────────

    private void openMusicCreator(Context ctx) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        EditText etSong = new EditText(ctx);
        etSong.setHint("Song name e.g. Blinding Lights");
        etSong.setTextSize(15);
        layout.addView(etSong);

        EditText etArtist = new EditText(ctx);
        etArtist.setHint("Artist name e.g. The Weeknd");
        etArtist.setTextSize(14);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = dp * 10;
        layout.addView(etArtist, etLp);

        EditText etAlbumArt = new EditText(ctx);
        etAlbumArt.setHint("Album art URL (optional)");
        etAlbumArt.setTextSize(13);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        artLp.topMargin = dp * 10;
        layout.addView(etAlbumArt, artLp);

        new android.app.AlertDialog.Builder(ctx)
            .setTitle("🎵 Music Sticker")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String song   = etSong.getText().toString().trim();
                String artist = etArtist.getText().toString().trim();
                String art    = etAlbumArt.getText().toString().trim();
                if (song.isEmpty()) {
                    Toast.makeText(ctx, "Add song name", Toast.LENGTH_SHORT).show(); return;
                }
                String json = "{\"type\":\"music\",\"song\":\"" + esc(song)
                    + "\",\"artist\":\"" + esc(artist)
                    + "\",\"albumArt\":\"" + esc(art) + "\"}";
                if (listener != null) listener.onSelected(new StickerResult("music", json));
            })
            .setNegativeButton("Cancel", null)
            .show();
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

        new android.app.AlertDialog.Builder(ctx)
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
            .show();
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

        new android.app.AlertDialog.Builder(ctx)
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
            .show();
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

        new android.app.AlertDialog.Builder(ctx)
            .setTitle("💬 Question Box")
            .setView(layout)
            .setPositiveButton("Add to Status", (d, w) -> {
                String question = etQuestion.getText().toString().trim();
                if (question.isEmpty()) question = "Ask me anything!";
                String json = "{\"type\":\"question\",\"prompt\":\"" + esc(question) + "\"}";
                if (listener != null) listener.onSelected(new StickerResult("question", json));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
