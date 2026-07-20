package com.callx.app.stickers;

import android.content.Context;
import android.graphics.*;
import android.os.CountDownTimer;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;

/**
 * StatusStickerOverlayView — renders a live sticker card on top of a status/story preview.
 *
 * Supports four sticker types:
 *  🎵 music     — album art + song + artist, pulsing equaliser bars
 *  ⏳ countdown — live ticking countdown to a target date, coloured card
 *  🧠 quiz      — multiple-choice question card with option rows
 *  💬 question  — open question box with "Send a reply" hint
 *
 * The view is draggable by default. The host activity should call
 * {@link #attachDragToParent(ViewGroup)} after adding this view to a FrameLayout.
 *
 * Usage:
 *   StatusStickerOverlayView v = StatusStickerOverlayView.fromJson(ctx, stickerJson);
 *   frameLayout.addView(v);
 *   v.attachDragToParent(frameLayout);
 */
public class StatusStickerOverlayView extends LinearLayout {

    private CountDownTimer countdownTimer;

    private StatusStickerOverlayView(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        setClickable(true);
        setFocusable(true);
    }

    // ── Factory ────────────────────────────────────────────────────────────

    /**
     * Create the appropriate sticker card from a JSON config produced by
     * {@link StatusStickerPickerSheet}.
     */
    public static StatusStickerOverlayView fromJson(Context ctx, String json) {
        String type = jsonStr(json, "type", "");
        switch (type) {
            case "music":     return buildMusic(ctx, json);
            case "countdown": return buildCountdown(ctx, json);
            case "quiz":      return buildQuiz(ctx, json);
            case "question":  return buildQuestion(ctx, json);
            default:          return buildQuestion(ctx, json);
        }
    }

    // ─── Music sticker ─────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildMusic(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 12, dp * 12, dp * 12, dp * 12);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE000000);
        bg.setStroke(1, 0x44FFFFFF);
        v.setBackground(bg);

        // Row: album art + text
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Album art placeholder
        ImageView ivArt = new ImageView(ctx);
        ivArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String artUrl = jsonStr(json, "albumArt", "");
        if (!artUrl.isEmpty()) {
            try {
                // Load with Glide if available — graceful no-op if not
                Class<?> glide = Class.forName("com.bumptech.glide.Glide");
                java.lang.reflect.Method with = glide.getMethod("with", Context.class);
                Object rm = with.invoke(null, ctx);
                Object rr = rm.getClass().getMethod("load", String.class).invoke(rm, artUrl);
                rr.getClass().getMethod("into", ImageView.class).invoke(rr, ivArt);
            } catch (Exception ignored) {
                ivArt.setBackgroundColor(0xFF222222);
            }
        } else {
            android.graphics.drawable.GradientDrawable artBg = new android.graphics.drawable.GradientDrawable();
            artBg.setColor(0xFF222222);
            artBg.setCornerRadius(dp * 8);
            ivArt.setBackground(artBg);
        }
        // Music note overlay
        TextView tvNote = new TextView(ctx);
        tvNote.setText("🎵");
        tvNote.setTextSize(22);
        tvNote.setGravity(android.view.Gravity.CENTER);

        FrameLayout artFrame = new FrameLayout(ctx);
        FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(dp * 48, dp * 48);
        artLp.rightMargin = dp * 12;
        ivArt.setLayoutParams(new FrameLayout.LayoutParams(dp * 48, dp * 48));
        artFrame.addView(ivArt);
        artFrame.addView(tvNote);
        row.addView(artFrame, artLp);

        // Song + artist text
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        String song   = jsonStr(json, "song",   "Unknown Song");
        String artist = jsonStr(json, "artist", "");

        TextView tvSong = new TextView(ctx);
        tvSong.setText(song);
        tvSong.setTextColor(Color.WHITE);
        tvSong.setTextSize(14);
        tvSong.setTypeface(null, Typeface.BOLD);
        tvSong.setMaxLines(1);
        tvSong.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(tvSong);

        if (!artist.isEmpty()) {
            TextView tvArtist = new TextView(ctx);
            tvArtist.setText(artist);
            tvArtist.setTextColor(0xFFCCCCCC);
            tvArtist.setTextSize(12);
            tvArtist.setMaxLines(1);
            textCol.addView(tvArtist);
        }

        // Mini equaliser bars (3 rects animated via alpha)
        LinearLayout bars = new LinearLayout(ctx);
        bars.setOrientation(LinearLayout.HORIZONTAL);
        bars.setGravity(android.view.Gravity.BOTTOM);
        int[] heights = {dp * 8, dp * 14, dp * 10, dp * 16, dp * 8};
        for (int i = 0; i < 5; i++) {
            View bar = new View(ctx);
            android.graphics.drawable.GradientDrawable bd = new android.graphics.drawable.GradientDrawable();
            bd.setColor(0xFFFF3B5C);
            bd.setCornerRadius(dp * 2);
            bar.setBackground(bd);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp * 3, heights[i]);
            barLp.rightMargin = dp * 2;
            bars.addView(bar, barLp);
            animateBar(bar, 200 + i * 100L);
        }
        LinearLayout.LayoutParams barsLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barsLp.topMargin = dp * 4;
        textCol.addView(bars, barsLp);

        row.addView(textCol, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        v.addView(row);
        return v;
    }

    private static void animateBar(View bar, long duration) {
        bar.animate().alpha(0.3f).setDuration(duration)
            .withEndAction(() -> bar.animate().alpha(1f).setDuration(duration)
                .withEndAction(() -> animateBar(bar, duration)).start()).start();
    }

    // ─── Countdown sticker ─────────────────────────────────────────────────

    private static StatusStickerOverlayView buildCountdown(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 16, dp * 14, dp * 16, dp * 14);
        v.setGravity(android.view.Gravity.CENTER);

        String hexColor = jsonStr(json, "color", "#7C3AED");
        int baseColor;
        try { baseColor = Color.parseColor(hexColor); } catch (Exception e) { baseColor = 0xFF7C3AED; }

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[]{baseColor, darken(baseColor, 0.65f)});
        bg.setCornerRadius(dp * 20);
        v.setBackground(bg);

        String label = jsonStr(json, "label", "Countdown");
        String targetDate = jsonStr(json, "targetDate", "");

        // Header
        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("⏳");
        tvEmoji.setTextSize(28);
        tvEmoji.setGravity(android.view.Gravity.CENTER);
        v.addView(tvEmoji);

        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.WHITE);
        tvLabel.setTextSize(15);
        tvLabel.setTypeface(null, Typeface.BOLD);
        tvLabel.setGravity(android.view.Gravity.CENTER);
        tvLabel.setMaxLines(1);
        tvLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lbLp.bottomMargin = dp * 10;
        v.addView(tvLabel, lbLp);

        // Time blocks row
        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(android.view.Gravity.CENTER);

        String[][] blocks = {{"00","DAYS"},{"00","HRS"},{"00","MIN"},{"00","SEC"}};
        final TextView[] timeViews = new TextView[4];
        for (int i = 0; i < blocks.length; i++) {
            LinearLayout block = new LinearLayout(ctx);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setGravity(android.view.Gravity.CENTER);

            android.graphics.drawable.GradientDrawable blockBg = new android.graphics.drawable.GradientDrawable();
            blockBg.setColor(0x33FFFFFF);
            blockBg.setCornerRadius(dp * 10);
            block.setBackground(blockBg);
            block.setPadding(dp * 10, dp * 8, dp * 10, dp * 8);

            TextView tvVal = new TextView(ctx);
            tvVal.setText(blocks[i][0]);
            tvVal.setTextColor(Color.WHITE);
            tvVal.setTextSize(22);
            tvVal.setTypeface(null, Typeface.BOLD);
            tvVal.setGravity(android.view.Gravity.CENTER);
            block.addView(tvVal);
            timeViews[i] = tvVal;

            TextView tvUnit = new TextView(ctx);
            tvUnit.setText(blocks[i][1]);
            tvUnit.setTextColor(0xCCFFFFFF);
            tvUnit.setTextSize(10);
            tvUnit.setGravity(android.view.Gravity.CENTER);
            block.addView(tvUnit);

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.leftMargin  = dp * 4;
            bLp.rightMargin = dp * 4;
            timeRow.addView(block, bLp);

            // Colon separator (except after last)
            if (i < blocks.length - 1) {
                TextView colon = new TextView(ctx);
                colon.setText(":");
                colon.setTextColor(Color.WHITE);
                colon.setTextSize(20);
                colon.setTypeface(null, Typeface.BOLD);
                timeRow.addView(colon);
            }
        }

        v.addView(timeRow);

        // Start live countdown if target date is valid
        v.startCountdown(targetDate, timeViews);

        return v;
    }

    private void startCountdown(String targetDateStr, TextView[] timeViews) {
        long targetMs = 0;
        try {
            if (targetDateStr != null && !targetDateStr.isEmpty()) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US);
                java.util.Date date = sdf.parse(targetDateStr);
                if (date != null) targetMs = date.getTime();
            }
        } catch (Exception ignored) {}

        if (targetMs <= 0) {
            // Default: 7 days from now
            targetMs = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000);
        }

        long remainMs = targetMs - System.currentTimeMillis();
        if (remainMs <= 0) {
            timeViews[0].setText("00");
            timeViews[1].setText("00");
            timeViews[2].setText("00");
            timeViews[3].setText("00");
            return;
        }

        final long finalTargetMs = targetMs;
        countdownTimer = new CountDownTimer(remainMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long total = finalTargetMs - System.currentTimeMillis();
                if (total < 0) total = 0;
                long days  = total / (24 * 60 * 60 * 1000);
                long hrs   = (total % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
                long mins  = (total % (60 * 60 * 1000)) / (60 * 1000);
                long secs  = (total % (60 * 1000)) / 1000;
                timeViews[0].setText(String.format(java.util.Locale.US, "%02d", days));
                timeViews[1].setText(String.format(java.util.Locale.US, "%02d", hrs));
                timeViews[2].setText(String.format(java.util.Locale.US, "%02d", mins));
                timeViews[3].setText(String.format(java.util.Locale.US, "%02d", secs));
            }
            @Override public void onFinish() {
                for (TextView tv : timeViews) tv.setText("00");
            }
        }.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
    }

    // ─── Quiz sticker ──────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildQuiz(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 14, dp * 14, dp * 14);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE1A0A3B);
        bg.setStroke(1, 0xFF7C3AED);
        v.setBackground(bg);

        // Header
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp * 10;

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("🧠");
        tvEmoji.setTextSize(18);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eLp.rightMargin = dp * 6;
        headerRow.addView(tvEmoji, eLp);

        TextView tvType = new TextView(ctx);
        tvType.setText("QUIZ");
        tvType.setTextColor(0xFFAA55FF);
        tvType.setTextSize(12);
        tvType.setTypeface(null, Typeface.BOLD);
        tvType.setLetterSpacing(0.12f);
        headerRow.addView(tvType);

        v.addView(headerRow, hrLp);

        // Question
        String question = jsonStr(json, "question", "Quiz question");
        TextView tvQuestion = new TextView(ctx);
        tvQuestion.setText(question);
        tvQuestion.setTextColor(Color.WHITE);
        tvQuestion.setTextSize(15);
        tvQuestion.setTypeface(null, Typeface.BOLD);
        tvQuestion.setMaxLines(3);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qLp.bottomMargin = dp * 10;
        v.addView(tvQuestion, qLp);

        // Parse options from JSON array [{text:"...",correct:bool}]
        java.util.List<String> opts = new java.util.ArrayList<>();
        java.util.List<Boolean> corrects = new java.util.ArrayList<>();
        try {
            int arrStart = json.indexOf("\"options\":[") + 10;
            int arrEnd   = json.indexOf("]", arrStart);
            if (arrStart > 9 && arrEnd > arrStart) {
                String arrContent = json.substring(arrStart + 1, arrEnd);
                // Parse each {text:"...",correct:bool} object
                int depth = 0, start = 0;
                for (int i = 0; i < arrContent.length(); i++) {
                    char c = arrContent.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            String obj = arrContent.substring(start, i + 1);
                            opts.add(jsonStr(obj, "text", "Option"));
                            corrects.add(obj.contains("\"correct\":true"));
                            start = i + 1;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (opts.isEmpty()) { opts.add("Option A"); opts.add("Option B"); corrects.add(true); corrects.add(false); }

        for (int i = 0; i < opts.size() && i < 4; i++) {
            boolean isCorrect = i < corrects.size() && corrects.get(i);
            TextView opt = new TextView(ctx);
            opt.setText((isCorrect ? "✓ " : "") + opts.get(i));
            opt.setTextColor(isCorrect ? Color.WHITE : 0xFFCCCCCC);
            opt.setTextSize(13);
            opt.setGravity(android.view.Gravity.CENTER);
            opt.setPadding(dp * 12, dp * 8, dp * 12, dp * 8);

            android.graphics.drawable.GradientDrawable optBg = new android.graphics.drawable.GradientDrawable();
            optBg.setCornerRadius(dp * 10);
            optBg.setColor(isCorrect ? 0xFF7C3AED : 0x33FFFFFF);
            if (!isCorrect) optBg.setStroke(1, 0x55FFFFFF);
            opt.setBackground(optBg);

            LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            optLp.bottomMargin = dp * 6;
            v.addView(opt, optLp);
        }

        return v;
    }

    // ─── Question Box sticker ──────────────────────────────────────────────

    private static StatusStickerOverlayView buildQuestion(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 14, dp * 14, dp * 14);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE0A2B1E);
        bg.setStroke(1, 0xFF00C897);
        v.setBackground(bg);

        // Header
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp * 8;

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("💬");
        tvEmoji.setTextSize(18);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eLp.rightMargin = dp * 6;
        headerRow.addView(tvEmoji, eLp);

        TextView tvType = new TextView(ctx);
        tvType.setText("ASK ME");
        tvType.setTextColor(0xFF00C897);
        tvType.setTextSize(12);
        tvType.setTypeface(null, Typeface.BOLD);
        tvType.setLetterSpacing(0.12f);
        headerRow.addView(tvType);

        v.addView(headerRow, hrLp);

        // Prompt
        String prompt = jsonStr(json, "prompt", "Ask me anything!");
        TextView tvPrompt = new TextView(ctx);
        tvPrompt.setText(prompt);
        tvPrompt.setTextColor(Color.WHITE);
        tvPrompt.setTextSize(15);
        tvPrompt.setTypeface(null, Typeface.BOLD);
        tvPrompt.setMaxLines(3);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.bottomMargin = dp * 10;
        v.addView(tvPrompt, pLp);

        // Reply input box
        LinearLayout replyBox = new LinearLayout(ctx);
        replyBox.setOrientation(LinearLayout.HORIZONTAL);
        replyBox.setGravity(android.view.Gravity.CENTER_VERTICAL);
        replyBox.setPadding(dp * 12, dp * 10, dp * 12, dp * 10);

        android.graphics.drawable.GradientDrawable rBg = new android.graphics.drawable.GradientDrawable();
        rBg.setCornerRadius(dp * 24);
        rBg.setColor(0x33FFFFFF);
        rBg.setStroke(1, 0x55FFFFFF);
        replyBox.setBackground(rBg);

        TextView tvReply = new TextView(ctx);
        tvReply.setText("Send a reply…");
        tvReply.setTextColor(0x88FFFFFF);
        tvReply.setTextSize(13);
        replyBox.addView(tvReply, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvSend = new TextView(ctx);
        tvSend.setText("→");
        tvSend.setTextColor(0xFF00C897);
        tvSend.setTextSize(18);
        replyBox.addView(tvSend);

        v.addView(replyBox, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return v;
    }

    // ─── Drag support ──────────────────────────────────────────────────────

    /**
     * Make this sticker draggable within the given FrameLayout parent.
     * Long-press removes the sticker with a scale-out animation.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public void attachDragToParent(final ViewGroup parent) {
        final float[] startTouch = new float[2];
        final float[] startPos   = new float[2];

        setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    startPos[0]   = view.getX();
                    startPos[1]   = view.getY();
                    animate().scaleX(1.05f).scaleY(1.05f).setDuration(80).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    view.setX(startPos[0] + (event.getRawX() - startTouch[0]));
                    view.setY(startPos[1] + (event.getRawY() - startTouch[1]));
                    return true;
                case MotionEvent.ACTION_UP:
                    animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    return true;
            }
            return false;
        });

        setOnLongClickListener(view -> {
            view.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200)
                .withEndAction(() -> parent.removeView(view)).start();
            return true;
        });
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static String jsonStr(String json, String key, String def) {
        try {
            String search = "\"" + key + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) return def;
            start += search.length();
            int end = json.indexOf("\"", start);
            if (end < 0) return def;
            return json.substring(start, end).replace("\\\"","\"");
        } catch (Exception e) { return def; }
    }

    private static int dp(Context ctx) {
        return (int) ctx.getResources().getDisplayMetrics().density;
    }

    /** Darken a colour by the given factor (0 = black, 1 = original). */
    private static int darken(int color, float factor) {
        return Color.argb(Color.alpha(color),
            (int)(Color.red(color)   * factor),
            (int)(Color.green(color) * factor),
            (int)(Color.blue(color)  * factor));
    }
}
