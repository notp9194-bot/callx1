package com.callx.app.feed;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.FrameMetrics;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Lightweight, session-scoped diagnostics for the Home feed.
 *
 * It deliberately records only while the Home RecyclerView is moving (plus a
 * small settle window), so opening Home or watching a reel does not pollute the
 * report. Frame durations come from Android FrameMetrics; they are not
 * estimated from scroll velocity.
 */
public final class HomeFeedScrollDiagnostics {

    private static final HomeFeedScrollDiagnostics INSTANCE =
            new HomeFeedScrollDiagnostics();

    private static final int MAX_EVENTS = 40;
    private static final long ISSUE_COALESCE_MS = 500L;
    private static final long SCROLL_GRACE_MS = 280L;
    private static final long FRAME_DURATION_UNKNOWN = -1L;

    public static HomeFeedScrollDiagnostics get() {
        return INSTANCE;
    }

    private HomeFeedScrollDiagnostics() {}

    /**
     * HomeFragment implements this to provide the exact card/player state at
     * the moment an over-budget frame is delivered.
     */
    public interface SnapshotProvider {
        Snapshot captureScrollSnapshot();
    }

    public static final class Snapshot {
        public final int scrollState;
        public final int activeCardIndex;
        public final int firstVisibleAdapterPosition;
        public final int lastVisibleAdapterPosition;
        public final int feedItemCount;
        public final int playerState;
        public final boolean playerAttached;
        public final boolean firstFramePtsGatePending;
        public final boolean firstFrameRevealed;
        public final boolean hardwareLayerOn;
        public final float thumbnailAlpha;
        public final long playerPositionMs;
        public final String reelId;

        public Snapshot(int scrollState, int activeCardIndex,
                        int firstVisibleAdapterPosition, int lastVisibleAdapterPosition,
                        int feedItemCount, int playerState, boolean playerAttached,
                        boolean firstFramePtsGatePending, boolean firstFrameRevealed,
                        boolean hardwareLayerOn, float thumbnailAlpha,
                        long playerPositionMs, String reelId) {
            this.scrollState = scrollState;
            this.activeCardIndex = activeCardIndex;
            this.firstVisibleAdapterPosition = firstVisibleAdapterPosition;
            this.lastVisibleAdapterPosition = lastVisibleAdapterPosition;
            this.feedItemCount = feedItemCount;
            this.playerState = playerState;
            this.playerAttached = playerAttached;
            this.firstFramePtsGatePending = firstFramePtsGatePending;
            this.firstFrameRevealed = firstFrameRevealed;
            this.hardwareLayerOn = hardwareLayerOn;
            this.thumbnailAlpha = thumbnailAlpha;
            this.playerPositionMs = playerPositionMs;
            this.reelId = reelId;
        }
    }

    public static final class Event {
        public final long wallClockMs;
        public final long frameDurationMs;
        public final long budgetMs;
        public final int scrollState;
        public final int dx;
        public final int dy;
        public final int scrollOffsetPx;
        public final int firstVisibleAdapterPosition;
        public final int lastVisibleAdapterPosition;
        public final int activeCardIndex;
        public final int feedItemCount;
        public final int playerState;
        public final boolean playerAttached;
        public final boolean firstFramePtsGatePending;
        public final boolean firstFrameRevealed;
        public final boolean hardwareLayerOn;
        public final float thumbnailAlpha;
        public final long playerPositionMs;
        public final String reelId;
        public final String cause;
        public final String evidence;
        private int repeats = 1;
        private long worstFrameDurationMs;

        private Event(long wallClockMs, long frameDurationMs, long budgetMs,
                      int scrollState, int dx, int dy, int scrollOffsetPx,
                      Snapshot snapshot, String cause, String evidence) {
            this.wallClockMs = wallClockMs;
            this.frameDurationMs = frameDurationMs;
            this.budgetMs = budgetMs;
            this.scrollState = scrollState;
            this.dx = dx;
            this.dy = dy;
            this.scrollOffsetPx = scrollOffsetPx;
            this.firstVisibleAdapterPosition = snapshot != null
                    ? snapshot.firstVisibleAdapterPosition : RecyclerView.NO_POSITION;
            this.lastVisibleAdapterPosition = snapshot != null
                    ? snapshot.lastVisibleAdapterPosition : RecyclerView.NO_POSITION;
            this.activeCardIndex = snapshot != null ? snapshot.activeCardIndex : -1;
            this.feedItemCount = snapshot != null ? snapshot.feedItemCount : -1;
            this.playerState = snapshot != null ? snapshot.playerState : -1;
            this.playerAttached = snapshot != null && snapshot.playerAttached;
            this.firstFramePtsGatePending = snapshot != null
                    && snapshot.firstFramePtsGatePending;
            this.firstFrameRevealed = snapshot != null && snapshot.firstFrameRevealed;
            this.hardwareLayerOn = snapshot != null && snapshot.hardwareLayerOn;
            this.thumbnailAlpha = snapshot != null ? snapshot.thumbnailAlpha : -1f;
            this.playerPositionMs = snapshot != null ? snapshot.playerPositionMs : -1L;
            this.reelId = snapshot != null && snapshot.reelId != null
                    ? snapshot.reelId : "";
            this.cause = cause;
            this.evidence = evidence;
            this.worstFrameDurationMs = frameDurationMs;
        }

        private void merge(long frameDuration, String newCause, String newEvidence) {
            repeats++;
            if (frameDuration > worstFrameDurationMs) worstFrameDurationMs = frameDuration;
        }

        public int getRepeats() {
            return repeats;
        }

        public long getWorstFrameDurationMs() {
            return worstFrameDurationMs;
        }
    }

    private final ArrayDeque<Event> events = new ArrayDeque<>(MAX_EVENTS);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SnapshotProvider provider;
    private Window.OnFrameMetricsAvailableListener frameListener;
    private Activity attachedActivity;
    private boolean attached;

    private int scrollState = RecyclerView.SCROLL_STATE_IDLE;
    private int lastDx;
    private int lastDy;
    private long lastScrollAtMs;
    private int lastScrollOffsetPx;
    private long lastEventAtMs;

    public void attach(@NonNull Activity activity, @NonNull SnapshotProvider snapshotProvider) {
        detach();
        provider = snapshotProvider;
        attachedActivity = activity;
        attached = true;
        if (Build.VERSION.SDK_INT >= 24) attachFrameMetrics(activity);
    }

    public void detach() {
        if (attachedActivity != null && frameListener != null && Build.VERSION.SDK_INT >= 24) {
            try {
                attachedActivity.getWindow()
                        .removeOnFrameMetricsAvailableListener(frameListener);
            } catch (Exception ignored) {}
        }
        attachedActivity = null;
        provider = null;
        frameListener = null;
        attached = false;
        scrollState = RecyclerView.SCROLL_STATE_IDLE;
    }

    public void onScrolled(int dx, int dy, int scrollOffsetPx) {
        if (!attached) return;
        lastDx = dx;
        lastDy = dy;
        lastScrollOffsetPx = scrollOffsetPx;
        lastScrollAtMs = System.currentTimeMillis();
    }

    public void onScrollStateChanged(int newState) {
        if (!attached) return;
        scrollState = newState;
        if (newState != RecyclerView.SCROLL_STATE_IDLE) {
            lastScrollAtMs = System.currentTimeMillis();
        }
    }

    @RequiresApi(24)
    private void attachFrameMetrics(@NonNull Activity activity) {
        float refreshRateHz = 60f;
        try {
            float rate = activity.getWindowManager().getDefaultDisplay().getRefreshRate();
            if (rate > 0f) refreshRateHz = rate;
        } catch (Exception ignored) {
            // A 60Hz fallback is only used when Android cannot report the
            // display rate; the actual frame duration is still measured.
        }
        final long budgetMs = Math.max(1L, Math.round(1000f / refreshRateHz));
        frameListener = (window, metrics, dropCount) -> {
            if (!attached || isOutsideScrollWindow()) return;

            long totalDurationNs = safeMetric(metrics, FrameMetrics.TOTAL_DURATION);
            if (totalDurationNs <= 0L) return;
            long frameDurationMs = Math.max(1L, totalDurationNs / 1_000_000L);
            if (frameDurationMs <= budgetMs) return;

            Snapshot snapshot = provider != null ? provider.captureScrollSnapshot() : null;
            String cause = classifyCause(metrics, frameDurationMs, budgetMs, snapshot);
            String evidence = buildEvidence(metrics, frameDurationMs, budgetMs, snapshot);
            record(frameDurationMs, budgetMs, snapshot, cause, evidence);
        };
        try {
            activity.getWindow().addOnFrameMetricsAvailableListener(frameListener, mainHandler);
        } catch (Exception ignored) {
            frameListener = null;
        }
    }

    private boolean isOutsideScrollWindow() {
        long now = System.currentTimeMillis();
        return scrollState == RecyclerView.SCROLL_STATE_IDLE
                && (lastScrollAtMs == 0L || now - lastScrollAtMs > SCROLL_GRACE_MS);
    }

    private void record(long frameDurationMs, long budgetMs, Snapshot snapshot,
                        String cause, String evidence) {
        long now = System.currentTimeMillis();
        Event previous = events.peekLast();
        if (previous != null && now - lastEventAtMs <= ISSUE_COALESCE_MS
                && previous.cause.equals(cause)) {
            previous.merge(frameDurationMs, cause, evidence);
        } else {
            if (events.size() >= MAX_EVENTS) events.removeFirst();
            events.addLast(new Event(now, frameDurationMs, budgetMs, scrollState,
                    lastDx, lastDy, lastScrollOffsetPx, snapshot, cause, evidence));
        }
        lastEventAtMs = now;
    }

    private String classifyCause(FrameMetrics metrics, long frameDurationMs,
                                 long budgetMs, Snapshot snapshot) {
        // This is intentionally phrased as a signal, not an unprovable claim:
        // FrameMetrics can identify the expensive render phase, while the
        // snapshot identifies the Home-feed operation active at that instant.
        if (snapshot != null && snapshot.playerAttached
                && (snapshot.firstFramePtsGatePending
                || (snapshot.thumbnailAlpha > 0.01f && snapshot.thumbnailAlpha < 0.99f))) {
            return "Thumbnail → video handoff window";
        }
        if (snapshot != null && snapshot.playerState == 2 /* STATE_BUFFERING */) {
            return "Video buffering during scroll";
        }
        long drawMs = metricMs(metrics, FrameMetrics.DRAW_DURATION);
        long layoutMs = metricMs(metrics, FrameMetrics.LAYOUT_MEASURE_DURATION);
        long inputMs = metricMs(metrics, FrameMetrics.INPUT_HANDLING_DURATION);
        long animationMs = metricMs(metrics, FrameMetrics.ANIMATION_DURATION);
        if (drawMs > budgetMs / 2L) return "View draw/render work";
        if (layoutMs > budgetMs / 2L) return "Layout/measure work";
        if (inputMs > budgetMs / 2L) return "Input handling on main thread";
        if (animationMs > budgetMs / 2L) return "Animation work";
        if (frameDurationMs >= budgetMs * 2L) return "Severe main-thread/render stall";
        return "Frame over budget during Home feed scroll";
    }

    private String buildEvidence(FrameMetrics metrics, long frameDurationMs,
                                 long budgetMs, Snapshot snapshot) {
        StringBuilder out = new StringBuilder(160);
        out.append("frame ").append(frameDurationMs).append("ms vs ")
                .append(budgetMs).append("ms budget");
        long drawMs = metricMs(metrics, FrameMetrics.DRAW_DURATION);
        long layoutMs = metricMs(metrics, FrameMetrics.LAYOUT_MEASURE_DURATION);
        long inputMs = metricMs(metrics, FrameMetrics.INPUT_HANDLING_DURATION);
        if (drawMs > 0L) out.append(", draw ").append(drawMs).append("ms");
        if (layoutMs > 0L) out.append(", layout ").append(layoutMs).append("ms");
        if (inputMs > 0L) out.append(", input ").append(inputMs).append("ms");
        if (snapshot != null) {
            out.append(", card ").append(snapshot.activeCardIndex)
                    .append(", visible adapters ")
                    .append(snapshot.firstVisibleAdapterPosition).append("–")
                    .append(snapshot.lastVisibleAdapterPosition);
            if (snapshot.reelId != null && !snapshot.reelId.isEmpty()) {
                out.append(", reel ").append(shortId(snapshot.reelId));
            }
            if (snapshot.playerAttached) {
                out.append(", player ").append(playerStateName(snapshot.playerState));
            }
            if (snapshot.thumbnailAlpha >= 0f) {
                out.append(", thumb alpha ")
                        .append(String.format(Locale.US, "%.2f", snapshot.thumbnailAlpha));
            }
            if (snapshot.firstFramePtsGatePending) out.append(", PTS gate pending");
            // firstFrameRevealed is intentionally sticky for the lifetime of
            // an active card. Only call it an active handoff when the
            // thumbnail is actually being faded, otherwise an already-finished
            // reveal would be misreported on every later scroll frame.
            if (snapshot.firstFrameRevealed
                    && snapshot.thumbnailAlpha > 0.01f
                    && snapshot.thumbnailAlpha < 0.99f) {
                out.append(", first-frame reveal active");
            }
        }
        return out.toString();
    }

    private static long safeMetric(FrameMetrics metrics, int metric) {
        try {
            return metrics.getMetric(metric);
        } catch (Exception ignored) {
            return FRAME_DURATION_UNKNOWN;
        }
    }

    private static long metricMs(FrameMetrics metrics, int metric) {
        long ns = safeMetric(metrics, metric);
        return ns <= 0L ? 0L : Math.max(1L, ns / 1_000_000L);
    }

    private static String shortId(String id) {
        return id.length() <= 12 ? id : id.substring(0, 12) + "…";
    }

    private static String playerStateName(int state) {
        switch (state) {
            case 1: return "IDLE";
            case 2: return "BUFFERING";
            case 3: return "READY";
            case 4: return "ENDED";
            default: return "UNKNOWN";
        }
    }

    public boolean isFrameTrackingSupported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    public synchronized boolean hasEvents() {
        return !events.isEmpty();
    }

    public synchronized int getEventCount() {
        return events.size();
    }

    public synchronized List<Event> getEventsNewestFirst() {
        List<Event> result = new ArrayList<>(events.size());
        for (Event event : events) result.add(0, event);
        return result;
    }

    public synchronized void clear() {
        events.clear();
        lastEventAtMs = 0L;
    }

    /**
     * Text is composed here so UserReelsActivity only has to render a single
     * dialog. The newest event appears first and repeated frames are grouped.
     */
    public synchronized String buildReportText() {
        StringBuilder out = new StringBuilder(1200);
        out.append("Only real Android FrameMetrics captured during Home feed scrolling are shown.\n");
        if (!isFrameTrackingSupported()) {
            out.append("\nFrame-level tracking needs Android 7.0+ (API 24).");
            return out.toString();
        }
        if (events.isEmpty()) {
            out.append("\nNo over-budget frame recorded yet.\n\n")
                    .append("Open the Home tab, scroll through several reels, then open ")
                    .append("this menu again. A clean run stays empty.");
            return out.toString();
        }

        out.append("\nDetected bursts: ").append(events.size()).append("\n");
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
        time.setTimeZone(TimeZone.getDefault());
        int shown = 0;
        for (Event event : getEventsNewestFirst()) {
            if (shown++ >= 20) break;
            out.append("\n").append(time.format(new Date(event.wallClockMs)))
                    .append("  •  ").append(event.cause);
            if (event.getRepeats() > 1) {
                out.append("  (").append(event.getRepeats()).append(" frames, worst ")
                        .append(event.getWorstFrameDurationMs()).append("ms)");
            } else {
                out.append("  (").append(event.frameDurationMs).append("ms)");
            }
            out.append("\n  Location: adapter ")
                    .append(event.firstVisibleAdapterPosition).append("–")
                    .append(event.lastVisibleAdapterPosition)
                    .append(", active card ").append(event.activeCardIndex)
                    .append(", scroll offset ").append(event.scrollOffsetPx).append("px");
            if (!event.reelId.isEmpty()) {
                out.append("\n  Reel: ").append(event.reelId);
            }
            out.append("\n  Signal: ").append(event.evidence);
            if (event.cause.equals("Thumbnail → video handoff window")) {
                out.append("\n  Meaning: thumbnail and first-frame handoff overlapped this slow frame;")
                        .append(" inspect player attach/seek/reveal timing for this reel.");
            } else if (event.cause.equals("Video buffering during scroll")) {
                out.append("\n  Meaning: decoder/network buffering was active while the list moved.");
            } else {
                out.append("\n  Meaning: the named render phase exceeded the display budget;");
                out.append(" it is the strongest measured cause for this burst.");
            }
        }
        return out.toString();
    }
}