package com.callx.app.stickers;

import android.content.Context;
import android.graphics.*;
import android.os.CountDownTimer;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.*;
import androidx.annotation.NonNull;

/**
 * StatusStickerOverlayView — renders a live sticker card on top of a status/story preview.
 *
 * Supports six sticker types:
 *  🎵 music     — album art + song + artist, pulsing equaliser bars
 *  ⏳ countdown — live ticking countdown to a target date, coloured card
 *  🧠 quiz      — multiple-choice question card with option rows
 *  💬 question  — open question box with "Send a reply" hint
 *  🗳️ poll      — 2-option vote card, reveals live % split once voted
 *  🎚️ slider    — draggable emoji rating slider (0-100%), reveals live average once released
 *  👤 mention    — @username tag card, tap opens that user's profile
 *  #️⃣ hashtag   — #topic tag card, tap opens the hashtag's feed
 *  🔗 link       — tappable link pill, tap opens it in the browser
 *  ➕ addyours   — prompt card, tap opens the composer to join the chain
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
    private String stickerType;
    private String stickerJson;

    // ── Size control (pinch + Small/Medium/Large buttons in the composer) ──
    public static final float SCALE_SMALL  = 0.7f;
    public static final float SCALE_MEDIUM = 1.0f;
    public static final float SCALE_LARGE  = 1.4f;
    private static final float SCALE_MIN = 0.5f, SCALE_MAX = 2.0f;
    private float stickerScale = SCALE_MEDIUM;
    private OnStickerTappedListener stickerTapListener;

    /** Fired on a plain tap (not a drag/pinch) — composer uses this to show the size buttons. */
    public interface OnStickerTappedListener { void onTapped(StatusStickerOverlayView sticker); }
    public void setOnStickerTappedListener(OnStickerTappedListener l) { this.stickerTapListener = l; }
    public float getStickerScale() { return stickerScale; }

    // ── Viewer "tap to zoom, react to return" gate ───────────────────────────
    // Used only by the status VIEWER (never the composer): a viewer's first
    // tap enlarges the sticker to the middle of the screen — front and centre,
    // above everything else — so they can read/answer it properly, and the
    // story pauses for as long as it stays enlarged. Whatever counts as the
    // viewer's "reaction" for that sticker type (a quiz pick, a poll vote, a
    // slider release, a countdown toggle, or handing off to an external
    // sheet/screen) then shrinks it back to exactly the spot the poster
    // dropped it at and lets the story resume.
    private boolean zoomGateArmed;
    private boolean zoomedIn;
    private float zoomBaseX, zoomBaseY, zoomBaseScaleX = 1f, zoomBaseScaleY = 1f;
    private GestureDetector zoomGateTapDetector;

    /** True once {@link #zoomToFront} has enlarged this sticker and before {@link #restoreFromZoom} runs. */
    public boolean isZoomedIn() { return zoomedIn; }

    /**
     * Arms the tap-to-zoom gate: until the sticker is zoomed in, this view
     * swallows every touch itself (so a tap never lands directly on an inner
     * option/button underneath) and fires {@code onFirstTap} for a clean tap.
     * Once zoomed in, the gate steps aside so the real controls inside the
     * card (quiz options, poll choices, the slider, the countdown row, …)
     * receive taps normally — that follow-up tap is the viewer's "reaction".
     */
    public void armViewerZoomGate(Runnable onFirstTap) {
        zoomGateArmed = true;
        zoomGateTapDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (onFirstTap != null) onFirstTap.run();
                return true;
            }
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (zoomGateArmed && !zoomedIn) return true; // steal every touch until zoomed in
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (zoomGateArmed && !zoomedIn) {
            if (zoomGateTapDetector != null) zoomGateTapDetector.onTouchEvent(event);
            return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Enlarges this sticker and brings it in front of everything else in
     * {@code overlayParent}, centred on screen so the viewer can read it
     * properly. Remembers the exact spot/scale it started at so
     * {@link #restoreFromZoom} can put it back precisely. No-op if already
     * zoomed in.
     */
    public void zoomToFront(final ViewGroup overlayParent, final Runnable onZoomed) {
        if (zoomedIn) { if (onZoomed != null) onZoomed.run(); return; }
        zoomedIn = true;
        zoomBaseX = getX();
        zoomBaseY = getY();
        zoomBaseScaleX = getScaleX();
        zoomBaseScaleY = getScaleY();
        overlayParent.bringChildToFront(this);
        overlayParent.invalidate();
        setElevation(24f * getContext().getResources().getDisplayMetrics().density);
        final float targetScale = Math.min(SCALE_MAX, Math.max(stickerScale, SCALE_MEDIUM) * 1.35f);
        overlayParent.post(() -> {
            float targetX = (overlayParent.getWidth()  - getWidth())  / 2f;
            float targetY = (overlayParent.getHeight() - getHeight()) / 2f;
            animate().x(targetX).y(targetY).scaleX(targetScale).scaleY(targetScale)
                    .setDuration(260).setInterpolator(new OvershootInterpolator(0.9f))
                    .withEndAction(() -> { if (onZoomed != null) onZoomed.run(); })
                    .start();
        });
    }

    /**
     * Shrinks a zoomed-in sticker back to the exact spot/scale the poster
     * placed it at. No-op if it isn't currently zoomed in.
     */
    public void restoreFromZoom(final Runnable onRestored) {
        if (!zoomedIn) { if (onRestored != null) onRestored.run(); return; }
        animate().x(zoomBaseX).y(zoomBaseY).scaleX(zoomBaseScaleX).scaleY(zoomBaseScaleY)
                .setDuration(220).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    zoomedIn = false;
                    setElevation(0f);
                    if (onRestored != null) onRestored.run();
                })
                .start();
    }


    // ── Music sticker metadata (links back to the Reels trending-audio track) ──
    private String musicSoundId, musicSong, musicArtist, musicCoverUrl, musicSoundUrl;

    // ── Quiz interactivity state (mirrors the question sticker's full flow) ──
    private String quizQuestion;
    private java.util.List<String> quizOptions;
    private java.util.List<Boolean> quizCorrects;
    private final java.util.List<TextView> quizOptionViews = new java.util.ArrayList<>();
    private boolean quizAnswered = false;
    private OnQuizOptionSelectedListener quizListener;

    /** Fired when a viewer taps an unanswered quiz option. Index is 0-based into getQuizOptions(). */
    public interface OnQuizOptionSelectedListener {
        void onOptionSelected(int index);
    }

    // ── Countdown "Remind me" subscription state ─────────────────────────────
    private String countdownLabel;
    private boolean countdownSubscribed = false;
    private boolean countdownExpired = false;
    private LinearLayout countdownSubscribeRow;
    private TextView countdownBellIcon;
    private TextView countdownSubscribeLabel;
    private OnCountdownSubscribeToggleListener countdownListener;

    /** Fired when a viewer taps the ⏳ Countdown card's "Remind me" row. newState = subscribed or not. */
    public interface OnCountdownSubscribeToggleListener {
        void onToggle(boolean nowSubscribed);
    }

    // ── Poll interactivity state ─────────────────────────────────────────────
    private String pollQuestion, pollOptionA, pollOptionB;
    private boolean pollAnswered = false;
    private TextView pollOptViewA, pollOptViewB;
    private View pollFillA, pollFillB;
    private View pollContainerA, pollContainerB;
    private TextView pollPctA, pollPctB;
    private OnPollOptionSelectedListener pollListener;

    /** Fired when a viewer taps an unanswered 🗳️ Poll option. option is "A" or "B". */
    public interface OnPollOptionSelectedListener {
        void onOptionSelected(String option);
    }

    // ── Slider interactivity state ───────────────────────────────────────────
    private String sliderQuestion, sliderEmoji;
    private boolean sliderAnswered = false;
    private SeekBar sliderBar;
    private TextView sliderValueLabel, sliderThumbEmoji, sliderAvgLabel;
    private OnSliderValueSubmittedListener sliderListener;

    /** Fired once a viewer releases the 🎚️ Slider thumb. value is 0-100. */
    public interface OnSliderValueSubmittedListener {
        void onSubmitted(int value);
    }

    // ── Mention sticker data (links to a tagged user's profile) ─────────────
    private String mentionUsername;

    // ── Hashtag sticker data (links to the topic's feed) ────────────────────
    private String hashtagTag;

    // ── Link sticker data (opens an external URL in the browser) ────────────
    private String linkUrl, linkLabel;

    // ── Add Yours sticker data (prompt + chain-origin attribution) ──────────
    private String addYoursPrompt, addYoursOriginUid, addYoursOriginName;

    private StatusStickerOverlayView(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        setClickable(true);
        setFocusable(true);
    }

    /** The sticker's type — "music"/"countdown"/"quiz"/"question". */
    public String getStickerType() { return stickerType; }

    /** For a "question" sticker, the prompt the poster typed (e.g. "Ask me anything!"). Null for other types. */
    public String getQuestionPrompt() {
        return "question".equals(stickerType) ? jsonStr(stickerJson, "prompt", "Ask me anything!") : null;
    }

    // ── Music sticker linking (tap → open the Reels Sound Detail sheet) ─────

    /** The Reels sound/track id this sticker points to, or null/empty if it isn't linked to one. */
    public String getMusicSoundId() { return "music".equals(stickerType) ? musicSoundId : null; }

    /** Song title shown on the card, or null for non-music stickers. */
    public String getMusicSong() { return "music".equals(stickerType) ? musicSong : null; }

    /** Artist name shown on the card, or null for non-music stickers. */
    public String getMusicArtist() { return "music".equals(stickerType) ? musicArtist : null; }

    /** Album art / cover URL, or null for non-music stickers. */
    public String getMusicCoverUrl() { return "music".equals(stickerType) ? musicCoverUrl : null; }

    /** Playable audio URL for the linked track, or null for non-music stickers. */
    public String getMusicSoundUrl() { return "music".equals(stickerType) ? musicSoundUrl : null; }

    /** True once this music sticker is linked to a real Reels track the viewer can open. */
    public boolean isMusicLinkedToReelSound() {
        return "music".equals(stickerType) && musicSoundId != null && !musicSoundId.isEmpty();
    }

    // ── Quiz interactive flow ────────────────────────────────────────────────

    /** The quiz's question text, or null for non-quiz stickers. */
    public String getQuizQuestion() { return "quiz".equals(stickerType) ? quizQuestion : null; }

    /** The quiz's option labels in order, or null for non-quiz stickers. */
    public java.util.List<String> getQuizOptions() { return "quiz".equals(stickerType) ? quizOptions : null; }

    /** Index of the correct option, or -1 if unknown/not a quiz. */
    public int getQuizCorrectIndex() {
        if (quizCorrects == null) return -1;
        for (int i = 0; i < quizCorrects.size(); i++) {
            if (Boolean.TRUE.equals(quizCorrects.get(i))) return i;
        }
        return -1;
    }

    /** True once this viewer has answered (tapped an option, or had a prior answer restored). */
    public boolean isQuizAnswered() { return quizAnswered; }

    /** Wires the tap-to-answer callback for an unanswered quiz sticker. No-op once answered. */
    public void setOnQuizOptionSelectedListener(OnQuizOptionSelectedListener listener) {
        this.quizListener = listener;
    }

    /**
     * Locks the quiz card into its answered state: disables further taps and
     * highlights the correct option green, a wrong selection red, and dims the rest —
     * mirrors what viewers already expect from the ✓/✗ pattern IG-style quiz stickers use.
     * Safe to call to restore a previously-recorded answer (e.g. on reopening the status).
     */
    public void revealQuizAnswer(int selectedIndex) {
        if (quizOptionViews.isEmpty() || quizOptions == null) return;
        quizAnswered = true;
        int correctIdx = getQuizCorrectIndex();
        int dp = dp(getContext());
        for (int i = 0; i < quizOptionViews.size(); i++) {
            TextView opt = quizOptionViews.get(i);
            opt.setOnClickListener(null);
            opt.setClickable(false);
            boolean isCorrect = (i == correctIdx);
            boolean isWrongSelected = (i == selectedIndex && i != correctIdx);
            String label = i < quizOptions.size() ? quizOptions.get(i) : "";
            opt.setText((isCorrect ? "\u2713 " : isWrongSelected ? "\u2717 " : "") + label);

            android.graphics.drawable.GradientDrawable optBg = new android.graphics.drawable.GradientDrawable();
            optBg.setCornerRadius(dp * 10);
            if (isCorrect) {
                optBg.setColor(0xFF2ECC71);
            } else if (isWrongSelected) {
                optBg.setColor(0xFFE74C3C);
            } else {
                optBg.setColor(0x22FFFFFF);
                optBg.setStroke(1, 0x33FFFFFF);
            }
            opt.setBackground(optBg);
            opt.setTextColor(Color.WHITE);
            opt.setAlpha((isCorrect || isWrongSelected) ? 1f : 0.5f);
        }
    }

    // ── Countdown interactive flow ───────────────────────────────────────────

    /** The countdown's label text (e.g. "My Birthday 🎂"), or null for non-countdown stickers. */
    public String getCountdownLabel() { return "countdown".equals(stickerType) ? countdownLabel : null; }

    /** True once this viewer has subscribed for a reminder (or had a prior subscription restored). */
    public boolean isCountdownSubscribed() { return countdownSubscribed; }

    /** True once the countdown has reached zero. */
    public boolean isCountdownExpired() { return countdownExpired; }

    /** Wires the tap-to-subscribe/unsubscribe callback. No-op once the countdown has expired. */
    public void setOnCountdownSubscribeToggleListener(OnCountdownSubscribeToggleListener listener) {
        this.countdownListener = listener;
    }

    /**
     * Sets the subscribed/unsubscribed visual state without firing the toggle listener —
     * used to restore a viewer's prior subscription when the status is reopened.
     */
    public void setCountdownSubscribed(boolean subscribed) {
        this.countdownSubscribed = subscribed;
        updateCountdownSubscribeUi();
    }

    private void updateCountdownSubscribeUi() {
        if (countdownSubscribeRow == null) return;
        if (countdownExpired) {
            countdownBellIcon.setText("\u23F0"); // ⏰
            countdownSubscribeLabel.setText("Countdown ended");
            countdownSubscribeRow.setClickable(false);
            countdownSubscribeRow.setAlpha(0.6f);
            return;
        }
        countdownBellIcon.setText(countdownSubscribed ? "\uD83D\uDD14" : "\uD83D\uDD15"); // 🔔 / 🔕
        countdownSubscribeLabel.setText(countdownSubscribed ? "Reminder set \u2713" : "Remind me");
    }

    // ── Poll interactive flow ────────────────────────────────────────────────

    /** The poll's question text, or null for non-poll stickers. */
    public String getPollQuestion() { return "poll".equals(stickerType) ? pollQuestion : null; }

    /** Option A's label, or null for non-poll stickers. */
    public String getPollOptionA() { return "poll".equals(stickerType) ? pollOptionA : null; }

    /** Option B's label, or null for non-poll stickers. */
    public String getPollOptionB() { return "poll".equals(stickerType) ? pollOptionB : null; }

    /** True once this viewer has voted (tapped an option, or had a prior vote restored). */
    public boolean isPollAnswered() { return pollAnswered; }

    /** Wires the tap-to-vote callback for an unanswered poll. No-op once voted. */
    public void setOnPollOptionSelectedListener(OnPollOptionSelectedListener listener) {
        this.pollListener = listener;
    }

    /**
     * Locks the poll into its voted state: disables further taps, highlights this
     * viewer's pick, and fills each option's percentage bar from the live vote counts.
     * Safe to call to restore a previously-recorded vote (e.g. on reopening the status).
     */
    public void revealPollResult(String selectedOption, int countA, int countB) {
        if (pollOptViewA == null || pollOptViewB == null) return;
        pollAnswered = true;
        int total = countA + countB;
        int pctA = total > 0 ? Math.round(countA * 100f / total) : 50;
        int pctB = total > 0 ? 100 - pctA : 50;

        if (pollContainerA != null) { pollContainerA.setOnClickListener(null); pollContainerA.setClickable(false); }
        if (pollContainerB != null) { pollContainerB.setOnClickListener(null); pollContainerB.setClickable(false); }

        pollPctA.setText(pctA + "%");
        pollPctB.setText(pctB + "%");
        pollPctA.setVisibility(VISIBLE);
        pollPctB.setVisibility(VISIBLE);

        ViewGroup.LayoutParams fillLpA = pollFillA.getLayoutParams();
        if (fillLpA instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) fillLpA).weight = Math.max(pctA, 1);
        ViewGroup.LayoutParams fillLpB = pollFillB.getLayoutParams();
        if (fillLpB instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) fillLpB).weight = Math.max(pctB, 1);
        pollFillA.requestLayout();
        pollFillB.requestLayout();

        boolean pickedA = "A".equals(selectedOption);
        pollOptViewA.setAlpha(pickedA ? 1f : 0.6f);
        pollOptViewB.setAlpha(!pickedA ? 1f : 0.6f);
        TextView pickedView = pickedA ? pollOptViewA : pollOptViewB;
        pickedView.setText("\u2713 " + pickedView.getText());
    }

    // ── Slider interactive flow ──────────────────────────────────────────────

    /** The slider's question/prompt text, or null for non-slider stickers. */
    public String getSliderQuestion() { return "slider".equals(stickerType) ? sliderQuestion : null; }

    /** The slider's emoji thumb, or null for non-slider stickers. */
    public String getSliderEmoji() { return "slider".equals(stickerType) ? sliderEmoji : null; }

    /** The tagged username (no leading @), or null for non-mention stickers. */
    public String getMentionUsername() { return "mention".equals(stickerType) ? mentionUsername : null; }

    /** The tagged topic (no leading #), or null for non-hashtag stickers. */
    public String getHashtagTag() { return "hashtag".equals(stickerType) ? hashtagTag : null; }

    // ── Link sticker linking (tap → open the URL in the browser) ────────────

    /** The full URL this sticker points to (always includes a scheme), or null for non-link stickers. */
    public String getLinkUrl() { return "link".equals(stickerType) ? linkUrl : null; }

    /** The custom label text the poster typed, or "" if they left it blank. Null for non-link stickers. */
    public String getLinkLabel() { return "link".equals(stickerType) ? linkLabel : null; }

    // ── Add Yours chain flow ─────────────────────────────────────────────────

    /** The prompt text (e.g. "My study era 📚"), or null for non-addyours stickers. */
    public String getAddYoursPrompt() { return "addyours".equals(stickerType) ? addYoursPrompt : null; }

    /** The uid of whoever started this chain, or "" if this status IS the origin. Null for other types. */
    public String getAddYoursOriginUid() { return "addyours".equals(stickerType) ? addYoursOriginUid : null; }

    /** The display name of whoever started this chain, or "" if this status IS the origin. Null for other types. */
    public String getAddYoursOriginName() { return "addyours".equals(stickerType) ? addYoursOriginName : null; }

    /** True once this viewer has released the slider (or had a prior value restored). */
    public boolean isSliderAnswered() { return sliderAnswered; }

    /** Wires the release-to-submit callback for an unanswered slider. No-op once submitted. */
    public void setOnSliderValueSubmittedListener(OnSliderValueSubmittedListener listener) {
        this.sliderListener = listener;
    }

    /**
     * Locks the slider at myValue and shows the live average (0-100) as a small emoji
     * marker on the track. Safe to call to restore a previously-recorded response.
     */
    public void revealSliderAverage(int myValue, int avgValue) {
        if (sliderBar == null) return;
        sliderAnswered = true;
        sliderBar.setProgress(myValue);
        sliderBar.setEnabled(false);
        sliderValueLabel.setText(myValue + "%");
        if (sliderAvgLabel != null) {
            sliderAvgLabel.setVisibility(VISIBLE);
            sliderAvgLabel.setText("Avg " + avgValue + "%");
        }
    }

    // ── Factory ────────────────────────────────────────────────────────────

    /**
     * Create the appropriate sticker card from a JSON config produced by
     * {@link StatusStickerPickerSheet}.
     */
    public static StatusStickerOverlayView fromJson(Context ctx, String json) {
        String type = jsonStr(json, "type", "");
        StatusStickerOverlayView v;
        switch (type) {
            case "music":     v = buildMusic(ctx, json); break;
            case "countdown": v = buildCountdown(ctx, json); break;
            case "quiz":      v = buildQuiz(ctx, json); break;
            case "question":  v = buildQuestion(ctx, json); break;
            case "poll":      v = buildPoll(ctx, json); break;
            case "slider":    v = buildSlider(ctx, json); break;
            case "mention":   v = buildMention(ctx, json); break;
            case "hashtag":   v = buildHashtag(ctx, json); break;
            case "link":      v = buildLink(ctx, json); break;
            case "addyours":  v = buildAddYours(ctx, json); break;
            default:          v = buildQuestion(ctx, json); type = "question"; break;
        }
        v.stickerType = type;
        v.stickerJson = json;

        // BUG FIX: this used to read the saved scale with jsonStr(), a helper
        // that only matches QUOTED string values ("key":"value"). toJsonWithScale()
        // writes scale as a bare JSON number (o.put("scale", floatValue) →
        // "scale":1.2, no quotes), so jsonStr() could never find it and silently
        // fell back to the "1.0" default every time — every sticker reopened (or
        // reloaded in the viewer) reset to medium size regardless of what the
        // user pinched it to before posting. Use the numeric-aware jsonNum().
        float savedScale = SCALE_MEDIUM;
        try { savedScale = Float.parseFloat(jsonNum(json, "scale", "1.0")); } catch (Exception ignored) {}
        v.applyScale(savedScale);

        // Saved finger-adjusted position (posXRatio/posYRatio, 0..1 relative to
        // the parent frame) baked in by toJsonWithScale() at post-time. -1 means
        // this sticker JSON has no saved position (e.g. a legacy status posted
        // before this fix) — callers should fall back to their own default
        // placement in that case, via hasSavedPosition().
        try {
            v.savedPosXRatio = Float.parseFloat(jsonNum(json, "posXRatio", "-1"));
            v.savedPosYRatio = Float.parseFloat(jsonNum(json, "posYRatio", "-1"));
        } catch (Exception ignored) {
            v.savedPosXRatio = -1f;
            v.savedPosYRatio = -1f;
        }
        return v;
    }

    // ── Saved position (where the user's finger left it in the composer) ────
    private float savedPosXRatio = -1f, savedPosYRatio = -1f;
    /** True if this sticker was posted with a finger-adjusted position saved. */
    public boolean hasSavedPosition() { return savedPosXRatio >= 0f && savedPosYRatio >= 0f; }
    /** X position as a 0..1 ratio of the parent frame's width. Only valid if {@link #hasSavedPosition()}. */
    public float getSavedPosXRatio() { return savedPosXRatio; }
    /** Y position as a 0..1 ratio of the parent frame's height. Only valid if {@link #hasSavedPosition()}. */
    public float getSavedPosYRatio() { return savedPosYRatio; }

    /** Clamps and applies a new size, both visually and for persistence via {@link #toJsonWithScale()}. */
    public void applyScale(float scale) {
        stickerScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale));
        setScaleX(stickerScale);
        setScaleY(stickerScale);
    }

    /** Animated variant used by the Small/Medium/Large preset buttons. */
    public void animateToScale(float scale) {
        stickerScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale));
        animate().scaleX(stickerScale).scaleY(stickerScale).setDuration(150).start();
    }

    /**
     * The original sticker JSON with the current size AND the current
     * finger-dragged position baked in — call this at post-time (after the
     * user has finished dragging/pinching it on the compose preview) so the
     * exact same size/position shows up again when the status is viewed.
     * Position is saved as a 0..1 ratio of the parent frame's width/height
     * (not raw pixels) so it still lines up correctly even if the viewer's
     * overlay frame ends up a different size than the composer's.
     */
    public String toJsonWithScale() {
        try {
            org.json.JSONObject o = new org.json.JSONObject(stickerJson != null ? stickerJson : "{}");
            o.put("scale", stickerScale);
            if (getParent() instanceof View) {
                View parent = (View) getParent();
                if (parent.getWidth() > 0 && parent.getHeight() > 0) {
                    o.put("posXRatio", getX() / parent.getWidth());
                    o.put("posYRatio", getY() / parent.getHeight());
                }
            }
            return o.toString();
        } catch (Exception e) {
            return stickerJson != null ? stickerJson : "{}";
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
        String soundId  = jsonStr(json, "soundId", "");
        String soundUrl = jsonStr(json, "soundUrl", "");
        v.musicSong = song;
        v.musicArtist = artist;
        v.musicCoverUrl = artUrl;
        v.musicSoundId = soundId;
        v.musicSoundUrl = soundUrl;

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

        // Chevron hint — only shown when this sticker is linked to a real Reels
        // track, so viewers know tapping it opens that track's sound detail sheet.
        if (!soundId.isEmpty()) {
            TextView tvChevron = new TextView(ctx);
            tvChevron.setText("\u203A"); // ›
            tvChevron.setTextColor(0xFFCCCCCC);
            tvChevron.setTextSize(20);
            tvChevron.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chevLp.leftMargin = dp * 4;
            row.addView(tvChevron, chevLp);
        }

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
        v.countdownLabel = label;

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

        // "🔔 Remind me" row — tap to subscribe/unsubscribe; StatusViewerActivity wires
        // the actual persistence + owner-notify via setOnCountdownSubscribeToggleListener.
        // Rendered but inert (no-op toggle) when no listener is attached, e.g. in the
        // NewStatusActivity composer preview.
        LinearLayout subscribeRow = new LinearLayout(ctx);
        subscribeRow.setOrientation(LinearLayout.HORIZONTAL);
        subscribeRow.setGravity(android.view.Gravity.CENTER);
        subscribeRow.setPadding(dp * 12, dp * 8, dp * 12, dp * 8);
        subscribeRow.setClickable(true);
        subscribeRow.setFocusable(true);

        android.graphics.drawable.GradientDrawable subBg = new android.graphics.drawable.GradientDrawable();
        subBg.setCornerRadius(dp * 20);
        subBg.setColor(0x33FFFFFF);
        subBg.setStroke(1, 0x55FFFFFF);
        subscribeRow.setBackground(subBg);

        TextView tvBell = new TextView(ctx);
        tvBell.setText("\uD83D\uDD15"); // 🔕
        tvBell.setTextSize(15);
        LinearLayout.LayoutParams bellLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bellLp.rightMargin = dp * 6;
        subscribeRow.addView(tvBell, bellLp);

        TextView tvSubLabel = new TextView(ctx);
        tvSubLabel.setText("Remind me");
        tvSubLabel.setTextColor(Color.WHITE);
        tvSubLabel.setTextSize(13);
        tvSubLabel.setTypeface(null, Typeface.BOLD);
        subscribeRow.addView(tvSubLabel);

        LinearLayout.LayoutParams subRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subRowLp.topMargin = dp * 12;
        subRowLp.gravity = android.view.Gravity.CENTER;
        v.addView(subscribeRow, subRowLp);

        v.countdownSubscribeRow   = subscribeRow;
        v.countdownBellIcon       = tvBell;
        v.countdownSubscribeLabel = tvSubLabel;

        subscribeRow.setOnClickListener(clickedView -> {
            if (v.countdownExpired) return;
            boolean newState = !v.countdownSubscribed;
            v.countdownSubscribed = newState;
            v.updateCountdownSubscribeUi();
            if (v.countdownListener != null) v.countdownListener.onToggle(newState);
        });

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
            countdownExpired = true;
            updateCountdownSubscribeUi();
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
                StatusStickerOverlayView.this.countdownExpired = true;
                StatusStickerOverlayView.this.updateCountdownSubscribeUi();
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

        v.quizQuestion = question;
        v.quizOptions  = opts;
        v.quizCorrects = corrects;

        // NOTE: unlike the old build, the correct answer is never revealed up front —
        // that was spoiling the quiz for every viewer before they'd even tapped. Options
        // render neutral + tappable here; revealQuizAnswer() does the ✓/✗ styling once
        // this viewer has actually answered (see setOnQuizOptionSelectedListener).
        for (int i = 0; i < opts.size() && i < 4; i++) {
            final int idx = i;
            TextView opt = new TextView(ctx);
            opt.setText(opts.get(i));
            opt.setTextColor(Color.WHITE);
            opt.setTextSize(13);
            opt.setGravity(android.view.Gravity.CENTER);
            opt.setPadding(dp * 12, dp * 8, dp * 12, dp * 8);
            opt.setClickable(true);
            opt.setFocusable(true);

            android.graphics.drawable.GradientDrawable optBg = new android.graphics.drawable.GradientDrawable();
            optBg.setCornerRadius(dp * 10);
            optBg.setColor(0x33FFFFFF);
            optBg.setStroke(1, 0x55FFFFFF);
            opt.setBackground(optBg);

            opt.setOnClickListener(clickedView -> {
                if (v.quizAnswered) return;
                if (v.quizListener != null) v.quizListener.onOptionSelected(idx);
            });

            LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            optLp.bottomMargin = dp * 6;
            v.addView(opt, optLp);
            v.quizOptionViews.add(opt);
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

    // ─── Poll sticker ──────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildPoll(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 14, dp * 14, dp * 14);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE0A1F2A);
        bg.setStroke(1, 0xFF17C3E0);
        v.setBackground(bg);

        // Header
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp * 10;

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("\uD83D\uDDF3\uFE0F"); // 🗳️
        tvEmoji.setTextSize(18);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eLp.rightMargin = dp * 6;
        headerRow.addView(tvEmoji, eLp);

        TextView tvType = new TextView(ctx);
        tvType.setText("POLL");
        tvType.setTextColor(0xFF17C3E0);
        tvType.setTextSize(12);
        tvType.setTypeface(null, Typeface.BOLD);
        tvType.setLetterSpacing(0.12f);
        headerRow.addView(tvType);

        v.addView(headerRow, hrLp);

        // Question
        String question = jsonStr(json, "question", "Poll question");
        String optA = jsonStr(json, "optionA", "Yes");
        String optB = jsonStr(json, "optionB", "No");
        v.pollQuestion = question;
        v.pollOptionA  = optA;
        v.pollOptionB  = optB;

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

        Object[] rA = buildPollOptionRow(ctx, dp, optA);
        Object[] rB = buildPollOptionRow(ctx, dp, optB);
        View containerA = (View) rA[0], containerB = (View) rB[0];
        v.pollOptViewA = (TextView) rA[1]; v.pollFillA = (View) rA[2]; v.pollPctA = (TextView) rA[3];
        v.pollOptViewB = (TextView) rB[1]; v.pollFillB = (View) rB[2]; v.pollPctB = (TextView) rB[3];

        LinearLayout.LayoutParams optLpA = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp * 42);
        optLpA.bottomMargin = dp * 8;
        v.addView(containerA, optLpA);
        v.addView(containerB, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp * 42));

        // Full pill is the tap target (not just the label), and gets disabled on vote.
        containerA.setOnClickListener(cv -> {
            if (v.pollAnswered) return;
            if (v.pollListener != null) v.pollListener.onOptionSelected("A");
        });
        containerB.setOnClickListener(cv -> {
            if (v.pollAnswered) return;
            if (v.pollListener != null) v.pollListener.onOptionSelected("B");
        });
        v.pollContainerA = containerA;
        v.pollContainerB = containerB;

        return v;
    }

    /** Builds one poll option pill: [container, labelView, fillView, pctView]. */
    private static Object[] buildPollOptionRow(Context ctx, int dp, String label) {
        FrameLayout container = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable pillBg = new android.graphics.drawable.GradientDrawable();
        pillBg.setCornerRadius(dp * 20);
        pillBg.setColor(0x22FFFFFF);
        pillBg.setStroke(1, 0x55FFFFFF);
        container.setBackground(pillBg);
        container.setClipToOutline(true);
        container.setClickable(true);
        container.setFocusable(true);

        // Percentage fill bar — hidden (0-width) until the viewer votes.
        LinearLayout fillRow = new LinearLayout(ctx);
        fillRow.setOrientation(LinearLayout.HORIZONTAL);
        View fill = new View(ctx);
        fill.setBackgroundColor(0x5517C3E0);
        View spacer = new View(ctx);
        fillRow.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f));
        fillRow.addView(spacer, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        container.addView(fillRow, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Label + percentage content row, drawn on top of the fill.
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(android.view.Gravity.CENTER_VERTICAL);
        content.setPadding(dp * 14, 0, dp * 14, 0);

        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.WHITE);
        tvLabel.setTextSize(14);
        tvLabel.setTypeface(null, Typeface.BOLD);
        content.addView(tvLabel, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvPct = new TextView(ctx);
        tvPct.setTextColor(Color.WHITE);
        tvPct.setTextSize(13);
        tvPct.setTypeface(null, Typeface.BOLD);
        tvPct.setVisibility(GONE);
        content.addView(tvPct);

        container.addView(content, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        return new Object[]{container, tvLabel, fill, tvPct};
    }

    // ─── Slider sticker ────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildSlider(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 14, dp * 14, dp * 14);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE23122E);
        bg.setStroke(1, 0xFFE91E8C);
        v.setBackground(bg);

        String emoji = jsonStr(json, "emoji", "\u2764\uFE0F"); // ❤️
        String question = jsonStr(json, "question", "Rate it!");
        v.sliderEmoji = emoji;
        v.sliderQuestion = question;

        // Header
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp * 10;

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("\uD83C\uDF9A\uFE0F"); // 🎚️
        tvEmoji.setTextSize(18);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eLp.rightMargin = dp * 6;
        headerRow.addView(tvEmoji, eLp);

        TextView tvType = new TextView(ctx);
        tvType.setText("SLIDER");
        tvType.setTextColor(0xFFE91E8C);
        tvType.setTextSize(12);
        tvType.setTypeface(null, Typeface.BOLD);
        tvType.setLetterSpacing(0.12f);
        headerRow.addView(tvType);

        v.addView(headerRow, hrLp);

        // Question
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

        // Live value readout, above the track
        LinearLayout valueRow = new LinearLayout(ctx);
        valueRow.setOrientation(LinearLayout.HORIZONTAL);
        valueRow.setGravity(android.view.Gravity.CENTER);

        TextView tvThumbEmoji = new TextView(ctx);
        tvThumbEmoji.setText(emoji);
        tvThumbEmoji.setTextSize(20);
        LinearLayout.LayoutParams teLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        teLp.rightMargin = dp * 6;
        valueRow.addView(tvThumbEmoji, teLp);
        v.sliderThumbEmoji = tvThumbEmoji;

        TextView tvValue = new TextView(ctx);
        tvValue.setText("50%");
        tvValue.setTextColor(Color.WHITE);
        tvValue.setTextSize(15);
        tvValue.setTypeface(null, Typeface.BOLD);
        valueRow.addView(tvValue);
        v.sliderValueLabel = tvValue;

        TextView tvAvg = new TextView(ctx);
        tvAvg.setTextColor(0xFFE91E8C);
        tvAvg.setTextSize(12);
        tvAvg.setTypeface(null, Typeface.BOLD);
        tvAvg.setVisibility(GONE);
        LinearLayout.LayoutParams avgLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        avgLp.leftMargin = dp * 10;
        valueRow.addView(tvAvg, avgLp);
        v.sliderAvgLabel = tvAvg;

        LinearLayout.LayoutParams vrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vrLp.bottomMargin = dp * 4;
        v.addView(valueRow, vrLp);

        // Track
        SeekBar seekBar = new SeekBar(ctx);
        seekBar.setMax(100);
        seekBar.setProgress(50);
        try {
            seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFE91E8C));
            seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE91E8C));
        } catch (Exception ignored) {}
        v.sliderBar = seekBar;

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvValue.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (v.sliderAnswered) return;
                if (v.sliderListener != null) v.sliderListener.onSubmitted(sb.getProgress());
            }
        });

        v.addView(seekBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return v;
    }

    // ─── Mention sticker ───────────────────────────────────────────────────

    private static StatusStickerOverlayView buildMention(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 10, dp * 16, dp * 10);
        v.setOrientation(HORIZONTAL);
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 24);
        bg.setColor(0xEE14283A);
        bg.setStroke(1, 0xFF3D9BE9);
        v.setBackground(bg);

        String username = jsonStr(json, "username", "");
        v.mentionUsername = username;

        TextView tvAt = new TextView(ctx);
        tvAt.setText("👤");
        tvAt.setTextSize(16);
        LinearLayout.LayoutParams atLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        atLp.rightMargin = dp * 8;
        v.addView(tvAt, atLp);

        TextView tvUsername = new TextView(ctx);
        tvUsername.setText("@" + username);
        tvUsername.setTextColor(0xFF3D9BE9);
        tvUsername.setTextSize(15);
        tvUsername.setTypeface(null, Typeface.BOLD);
        tvUsername.setMaxLines(1);
        tvUsername.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.addView(tvUsername);

        return v;
    }

    // ─── Hashtag sticker ───────────────────────────────────────────────────

    private static StatusStickerOverlayView buildHashtag(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 10, dp * 16, dp * 10);
        v.setOrientation(HORIZONTAL);
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 24);
        bg.setColor(0xEE2A2410);
        bg.setStroke(1, 0xFFE9C93D);
        v.setBackground(bg);

        String tag = jsonStr(json, "tag", "");
        v.hashtagTag = tag;

        TextView tvHash = new TextView(ctx);
        tvHash.setText("#");
        tvHash.setTextColor(0xFFE9C93D);
        tvHash.setTextSize(18);
        tvHash.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.rightMargin = dp * 6;
        v.addView(tvHash, hLp);

        TextView tvTag = new TextView(ctx);
        tvTag.setText(tag);
        tvTag.setTextColor(0xFFE9C93D);
        tvTag.setTextSize(15);
        tvTag.setTypeface(null, Typeface.BOLD);
        tvTag.setMaxLines(1);
        tvTag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.addView(tvTag);

        return v;
    }

    // ─── Link sticker ──────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildLink(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 16, dp * 10, dp * 18, dp * 10);
        v.setOrientation(HORIZONTAL);
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Rendered as a solid white pill (unlike the other dark cards) — mirrors
        // the high-contrast "link" pill viewers already expect from IG-style stories.
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 24);
        bg.setColor(0xFFFFFFFF);
        v.setBackground(bg);

        String url   = jsonStr(json, "url", "");
        String label = jsonStr(json, "label", "");
        v.linkUrl   = url;
        v.linkLabel = label;

        TextView tvIcon = new TextView(ctx);
        tvIcon.setText("\uD83D\uDD17"); // 🔗
        tvIcon.setTextSize(16);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iLp.rightMargin = dp * 8;
        v.addView(tvIcon, iLp);

        String display = !label.isEmpty() ? label : displayHost(url);
        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(display);
        tvLabel.setTextColor(0xFF111111);
        tvLabel.setTextSize(15);
        tvLabel.setTypeface(null, Typeface.BOLD);
        tvLabel.setMaxLines(1);
        tvLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.addView(tvLabel);

        return v;
    }

    /** Best-effort host name for display (e.g. "example.com"), falling back to the raw URL. */
    private static String displayHost(String url) {
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost();
            return host != null ? host.replaceFirst("^www\\.", "") : url;
        } catch (Exception e) {
            return url;
        }
    }

    // ─── Add Yours sticker ─────────────────────────────────────────────────

    private static StatusStickerOverlayView buildAddYours(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 16, dp * 14, dp * 16, dp * 14);
        v.setGravity(android.view.Gravity.CENTER);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE1A1A1A);
        bg.setStroke(dp * 2, 0xFFFFFFFF);
        v.setBackground(bg);

        String prompt     = jsonStr(json, "prompt", "Add Yours");
        String originUid  = jsonStr(json, "originUid", "");
        String originName = jsonStr(json, "originName", "");
        v.addYoursPrompt     = prompt;
        v.addYoursOriginUid  = originUid;
        v.addYoursOriginName = originName;

        TextView tvPlus = new TextView(ctx);
        tvPlus.setText("\u2795"); // ➕
        tvPlus.setTextSize(22);
        tvPlus.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.bottomMargin = dp * 6;
        v.addView(tvPlus, pLp);

        TextView tvHeader = new TextView(ctx);
        tvHeader.setText("ADD YOURS");
        tvHeader.setTextColor(Color.WHITE);
        tvHeader.setTextSize(12);
        tvHeader.setTypeface(null, Typeface.BOLD);
        tvHeader.setLetterSpacing(0.12f);
        tvHeader.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp * 6;
        v.addView(tvHeader, hLp);

        TextView tvPrompt = new TextView(ctx);
        tvPrompt.setText(prompt);
        tvPrompt.setTextColor(Color.WHITE);
        tvPrompt.setTextSize(16);
        tvPrompt.setTypeface(null, Typeface.BOLD);
        tvPrompt.setGravity(android.view.Gravity.CENTER);
        tvPrompt.setMaxLines(2);
        LinearLayout.LayoutParams promptLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (!originName.isEmpty()) promptLp.bottomMargin = dp * 6;
        v.addView(tvPrompt, promptLp);

        // Only present once a viewer has actually joined the chain — the origin
        // post itself has no originName yet, since it hasn't been added-to.
        if (!originName.isEmpty()) {
            TextView tvOrigin = new TextView(ctx);
            tvOrigin.setText("\u21B3 Started by " + originName);
            tvOrigin.setTextColor(0xFFBBBBBB);
            tvOrigin.setTextSize(11);
            tvOrigin.setGravity(android.view.Gravity.CENTER);
            v.addView(tvOrigin);
        }

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
        final long[]  downTime   = new long[1];
        final boolean[] moved    = new boolean[1];
        final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(getContext(),
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    applyScale(stickerScale * detector.getScaleFactor());
                    moved[0] = true;
                    return true;
                }
            });

        setOnTouchListener((view, event) -> {
            scaleDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // BUG FIX (whole preview screen scrolling while dragging a
                    // sticker): the compose/preview screen's media area sits
                    // inside a NestedScrollView. Returning true from this
                    // listener only tells Android THIS view handled the
                    // touch — it does nothing to stop the ScrollView ancestor
                    // from also intercepting the same finger-move as a
                    // vertical scroll gesture, which is exactly what was
                    // happening: dragging a sticker up/down/left/right
                    // dragged the whole screen with it. Explicitly disallow
                    // any ancestor from intercepting for as long as this
                    // finger (or these fingers) are down on the sticker.
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    startPos[0]   = view.getX();
                    startPos[1]   = view.getY();
                    downTime[0]   = System.currentTimeMillis();
                    moved[0]      = false;
                    animate().scaleX(stickerScale * 1.05f).scaleY(stickerScale * 1.05f).setDuration(80).start();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // Second finger just landed — rebase the drag anchor so the sticker
                    // doesn't jump when we go from 1-finger drag to 2-finger pinch.
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    startPos[0]   = view.getX();
                    startPos[1]   = view.getY();
                    moved[0]      = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Keep re-asserting this every move: some ScrollView/
                    // RecyclerView ancestors re-arm their own interception on
                    // each new touch sequence, so a single ACTION_DOWN call
                    // isn't always enough for the whole drag.
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                    if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                        float dx = event.getRawX() - startTouch[0];
                        float dy = event.getRawY() - startTouch[1];
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) moved[0] = true;
                        view.setX(startPos[0] + dx);
                        view.setY(startPos[1] + dy);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    // One finger lifted but one remains — rebase again for the remaining drag.
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    startPos[0]   = view.getX();
                    startPos[1]   = view.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    // Gesture is fully done — let the parent scroll normally again.
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                    animate().scaleX(stickerScale).scaleY(stickerScale).setDuration(80).start();
                    boolean isTap = !moved[0] && (System.currentTimeMillis() - downTime[0]) < 300;
                    if (isTap && stickerTapListener != null) stickerTapListener.onTapped(this);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    // Interrupted (e.g. a system gesture stole it) — always
                    // restore normal scrolling rather than leaving it stuck
                    // disallowed for the rest of the screen's lifetime.
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                    animate().scaleX(stickerScale).scaleY(stickerScale).setDuration(80).start();
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

    /**
     * Like {@link #jsonStr}, but for bare numeric JSON values (e.g. "scale":1.2,
     * "posXRatio":0.35) which are written WITHOUT surrounding quotes — jsonStr's
     * quote-delimited search never matches those and would always fall through
     * to the default.
     */
    private static String jsonNum(String json, String key, String def) {
        try {
            String search = "\"" + key + "\":";
            int start = json.indexOf(search);
            if (start < 0) return def;
            start += search.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end))
                    || json.charAt(end) == '-' || json.charAt(end) == '.'
                    || json.charAt(end) == 'e' || json.charAt(end) == 'E'
                    || json.charAt(end) == '+')) {
                end++;
            }
            if (end == start) return def;
            return json.substring(start, end);
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
