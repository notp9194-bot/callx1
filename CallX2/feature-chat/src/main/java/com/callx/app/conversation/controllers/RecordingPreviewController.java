package com.callx.app.conversation.controllers;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.ui.RecordingWaveformView;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

/**
 * RecordingPreviewController — LIVE waveform amplitude presence.
 *
 * Upgrades the plain boolean "X is recording…" strip (ChatPresenceController
 * #publishOurRecordingState / #watchPartnerRecording, chatRecording/{..})
 * into a real-time waveform: the partner sees the ACTUAL shape of your
 * voice as you speak, not just a static pill. Sibling of
 * ChatPlaybackPresenceController, same delegate pattern.
 *
 * Firebase path: chatRecordingWave/{chatId}/{uid} = int 0..31 (quantized
 * amplitude), see FirebaseUtils#getChatRecordingWaveRef.
 *
 * ── Perf design ─────────────────────────────────────────────────────────
 *
 * 1. NO extra polling. Reuses ChatMediaController's existing 100ms self-bar
 *    amplitude tick via AmplitudeListener — zero additional MediaRecorder
 *    calls, zero additional Handler loops on the sending side.
 *
 * 2. Network writes THROTTLED to every 2nd sample (~200ms, 5/sec instead
 *    of 10) and QUANTIZED to a single 0-31 int — a ~1-byte scalar
 *    overwrite, not an array/object. Redundant-value writes are skipped
 *    entirely (silence while recording pauses briefly costs nothing).
 *
 * 3. The incoming listener is NOT persistent — attaches only while the
 *    partner is actually holding the mic (gated on the existing
 *    chatRecording/{..} boolean, reused rather than duplicated). An idle
 *    chat screen carries zero listeners on chatRecordingWave.
 *
 * 4. View reference is CACHED on attach instead of calling findViewById()
 *    on every ~5/sec tick — avoids a view-tree walk on the main thread
 *    per sample.
 *
 * 5. SCROLL-AWARE draw gating — this is the direct answer to "smooth
 *    scrolling": while rvMessages is actively flinging
 *    (SCROLL_STATE_SETTLING), incoming samples update the waveform's ring
 *    buffer via pushLevelQuiet() (no invalidate(), no draw pass) instead
 *    of competing with the message list for that frame's draw budget. The
 *    moment the fling ends (SCROLL_STATE_IDLE/DRAGGING) a single flush()
 *    catches the view up in one draw. (Earlier revisions also toggled an
 *    explicit hardware layer on the waveform view — removed in the latest
 *    pass, see point 12: it bought nothing for a view that invalidates
 *    on nearly every sample.)
 *
 * 6. Reuses {@link RecordingWaveformView} as-is (fixed-capacity ring
 *    buffer, no per-frame allocation, single onDraw pass). Lives entirely
 *    inside the static ll_voice_recording_strip overlay, OUTSIDE
 *    rvMessages's hierarchy — never triggers onBindViewHolder or
 *    participates in item recycling.
 *
 * 7. Client-side smoothing via ONE reusable Runnable field (no new lambda
 *    allocation per network sample) — turns ~200ms network cadence into a
 *    ~100ms-feeling bar cadence at half the network cost.
 *
 * 8. DatabaseReference objects are RESOLVED ONCE (in init()/attach) and
 *    reused — FirebaseUtils....child(uid) was being rebuilt (path parse +
 *    object alloc) on every single publish/attach call; now it's built
 *    once per chat session / per recording burst.
 *
 * 9. SCREEN-VISIBILITY gating — onScreenPaused()/onScreenResumed() (wired
 *    from ChatActivity#onPause/#onResume, same pattern as
 *    ChatPresenceController) fully detaches BOTH the wave listener and the
 *    boolean gate listener the instant the chat screen leaves the
 *    foreground, even if the partner keeps recording, and reattaches on
 *    resume with a one-shot get() to catch up. Zero callback dispatch,
 *    zero view work, zero draw calls while nothing is on screen to see
 *    them.
 *
 * 10. REDUNDANT-TARGET guard on the incoming side — if the wire value is
 *     identical to what's already showing (e.g. a brief silence held
 *     across samples), pushIncomingLevelSmoothed() bails before the
 *     midpoint math, the buffer write, or the Handler reschedule. No
 *     wasted work animating a flat line to itself.
 *
 * 11. {@link RecordingWaveformView} itself now backs its ring buffer with
 *     a primitive float[] instead of ArrayDeque<Float> — zero autoboxing
 *     per sample — and redraws via postInvalidateOnAnimation() so its
 *     repaint lands on the next Choreographer frame instead of forcing an
 *     out-of-cadence traversal mid-frame. See that class for details.
 *
 * 12. Explicit hardware layer REMOVED from the waveform view. A layer's
 *     entire benefit is reusing a cached GPU texture across MULTIPLE
 *     frames where the content didn't change; this view invalidates on
 *     ~every incoming sample (~5/sec), so a layer was being torn down and
 *     rebuilt almost every time it fired — a pure texture-upload tax with
 *     no caching win. With hardware acceleration on (the app default) the
 *     view already renders via its own RenderNode as a sibling of
 *     rvMessages, so dropping the explicit layer costs nothing and saves
 *     the redundant GPU copy. Same call already made for
 *     TypingStripCanvasView.
 *
 * 13. The boolean chatRecording gate listener is now detached on
 *     onScreenPaused() too, not just the wave listener. Previously it
 *     stayed subscribed the whole time the chat screen was backgrounded —
 *     every partner recording-state flip still dispatched an
 *     onDataChange() callback that was immediately no-opped by the
 *     screenForeground check. Detaching it means truly zero Firebase
 *     callback traffic while the screen isn't visible, with
 *     onScreenResumed() resubscribing and letting the existing one-shot
 *     get() catch up on any state missed in between.
 */
public class RecordingPreviewController {

    /** Publish every Nth self-sample (2 = ~200ms at the existing 100ms tick). */
    private static final int PUBLISH_EVERY_N_SAMPLES = 2;

    /** Quantization steps for the wire value — plenty for a waveform bar. */
    private static final int QUANT_STEPS = 31;

    private final ChatActivityDelegate delegate;

    // ── Outgoing ──────────────────────────────────────────────────────────
    private int sampleCounter = 0;
    private int lastWrittenQuantized = -1;
    /** Resolved once instead of rebuilt on every publish call. */
    private DatabaseReference ourWaveRef;

    // ── Incoming ──────────────────────────────────────────────────────────
    private ValueEventListener recordingGateListener; // reuses chatRecording/{..} boolean
    private ValueEventListener waveListener;           // only alive while partner is recording
    private boolean waveListenerAttached = false;
    private float lastIncomingLevel = 0f;
    /** Resolved once per chat session instead of rebuilt per attach. */
    private DatabaseReference partnerRecordingGateRef;
    private DatabaseReference partnerWaveRef;
    /** False while the chat screen itself is backgrounded — suspends ALL
     *  incoming listener/view work even if the partner keeps recording. */
    private boolean screenForeground = true;
    /** True if we detached the wave listener specifically because the
     *  screen went to background (not because recording stopped) — lets
     *  onScreenResumed() know it should re-check and reattach. */
    private boolean detachedForBackground = false;

    /** Cached so incoming ticks (~5/sec) never pay for a findViewById walk. */
    private RecordingWaveformView cachedView;

    /** True while rvMessages is mid-fling — gates draw vs. buffer-only updates. */
    private boolean listIsFlinging = false;
    private RecyclerView.OnScrollListener scrollGateListener;

    private final android.os.Handler smoothHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Reused every tick instead of allocating a new lambda per sample. */
    private final SmoothStep smoothStep = new SmoothStep();

    public RecordingPreviewController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init / teardown ──────────────────────────────────────────────────

    public void init() {
        String chatId = delegate.getChatId();
        String uid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (chatId != null && uid != null) {
            ourWaveRef = FirebaseUtils.getChatRecordingWaveRef(chatId).child(uid);
        }
        if (chatId != null && partnerUid != null && !partnerUid.isEmpty()) {
            partnerRecordingGateRef = FirebaseUtils.getChatRecordingRef(chatId).child(partnerUid);
            partnerWaveRef = FirebaseUtils.getChatRecordingWaveRef(chatId).child(partnerUid);
        }
        attachRecordingGateListener();
        attachScrollGate();
    }

    public void release() {
        detachRecordingGateListener();
        detachScrollGate();
        detachWaveListener();
        smoothHandler.removeCallbacksAndMessages(null);
        // Flush our own node immediately — no debounce — so we never leave
        // a stale amplitude sitting on the partner's screen.
        publishOurAmplitudeInternal(-1, true);
    }

    // ── Screen visibility gate: suspend ALL incoming work — including the
    //    always-on boolean gate listener, not just the wave listener — the
    //    instant this chat screen leaves the foreground, independent of
    //    whether the partner is still recording. Called from
    //    ChatActivity#onPause/#onResume, same pattern as
    //    ChatPresenceController#onScreenPaused. ─────────────────────────────

    public void onScreenPaused() {
        screenForeground = false;
        if (waveListenerAttached) {
            detachWaveListener();
            detachedForBackground = true;
        }
        // The gate listener itself was previously left attached while
        // backgrounded — it kept dispatching onDataChange callbacks (just
        // no-opped via the screenForeground check) for as long as the
        // chat screen sat in the background. Detaching it too means truly
        // zero Firebase callback dispatch while nothing is on screen.
        detachRecordingGateListener();
    }

    public void onScreenResumed() {
        screenForeground = true;
        attachRecordingGateListener();
        if (!detachedForBackground) return;
        detachedForBackground = false;
        // One cheap single-value read to see if the partner is STILL
        // recording (likely not, but if they are, e.g. a long voice note
        // spanning our brief backgrounding, reattach immediately instead
        // of waiting for their next boolean flip).
        if (partnerRecordingGateRef != null) {
            partnerRecordingGateRef.get().addOnSuccessListener(s -> {
                if (screenForeground && Boolean.TRUE.equals(s.getValue(Boolean.class))) {
                    attachWaveListener();
                }
            });
        }
    }

    // ── Scroll gate: don't let the waveform's draw calls compete with a
    //    fling of the message list ────────────────────────────────────────

    private void attachScrollGate() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null || binding.rvMessages == null) return;
        scrollGateListener = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                boolean flingingNow = newState == RecyclerView.SCROLL_STATE_SETTLING;
                if (listIsFlinging && !flingingNow) {
                    // Fling just ended — catch the waveform up with ONE draw.
                    if (cachedView != null) cachedView.flush();
                }
                listIsFlinging = flingingNow;
            }
        };
        binding.rvMessages.addOnScrollListener(scrollGateListener);
    }

    private void detachScrollGate() {
        ActivityChatBinding binding = delegate.getBinding();
        if (scrollGateListener != null && binding != null && binding.rvMessages != null) {
            binding.rvMessages.removeOnScrollListener(scrollGateListener);
        }
        scrollGateListener = null;
    }

    // ── Outgoing: called every 100ms from ChatMediaController's existing
    //    recording tick via setAmplitudeListener() ────────────────────────

    /** level0to1 in [0,1]. Cheap to call every tick — internally throttles. */
    public void onOurAmplitudeSample(float level0to1) {
        sampleCounter++;
        if (sampleCounter % PUBLISH_EVERY_N_SAMPLES != 0) return; // throttle: skip this tick
        int quantized = Math.round(Math.max(0f, Math.min(1f, level0to1)) * QUANT_STEPS);
        publishOurAmplitudeInternal(quantized, false);
    }

    /** Called when OUR recording stops (release/cancel/send) to clear the node. */
    public void onOurRecordingStopped() {
        sampleCounter = 0;
        publishOurAmplitudeInternal(-1, true);
    }

    private void publishOurAmplitudeInternal(int quantized, boolean clear) {
        DatabaseReference ref = ourWaveRef;
        if (ref == null) return;
        if (clear) {
            lastWrittenQuantized = -1;
            ref.removeValue();
            ref.onDisconnect().cancel();
            return;
        }
        if (quantized == lastWrittenQuantized) return; // redundant-write guard
        lastWrittenQuantized = quantized;
        ref.setValue(quantized);
        ref.onDisconnect().removeValue(); // safety net: process killed mid-recording
    }

    // ── Incoming: gate a lightweight wave listener on the EXISTING
    //    chatRecording boolean, so it's never idle-attached ────────────────

    private void attachRecordingGateListener() {
        if (recordingGateListener != null || partnerRecordingGateRef == null) return;

        recordingGateListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                boolean recording = Boolean.TRUE.equals(s.getValue(Boolean.class));
                // Ignore boolean flips entirely while backgrounded — resume
                // handling (onScreenResumed) does its own fresh check.
                if (!screenForeground) return;
                if (recording) attachWaveListener(); else detachWaveListener();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        partnerRecordingGateRef.addValueEventListener(recordingGateListener);
    }

    private void detachRecordingGateListener() {
        if (recordingGateListener != null && partnerRecordingGateRef != null) {
            partnerRecordingGateRef.removeEventListener(recordingGateListener);
        }
        recordingGateListener = null;
    }

    private void attachWaveListener() {
        if (waveListenerAttached || partnerWaveRef == null) return;

        cachedView = resolvePreviewView();
        if (cachedView != null) {
            cachedView.reset();
            // NO explicit hardware layer here — deliberately. A layer only
            // pays off for a view whose content stays put between
            // invalidations (the GPU can reuse the cached texture); this
            // waveform invalidates on ~every incoming sample, so an
            // explicit layer would just force a fresh GPU-texture copy on
            // nearly every frame — pure overhead, no caching benefit. With
            // hardware acceleration on (the app default) the view already
            // gets its own RenderNode as a sibling of rvMessages, so
            // invalidating it doesn't force rvMessages to re-record either
            // way. Same reasoning already applied to TypingStripCanvasView.
        }
        lastIncomingLevel = 0f;

        waveListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Integer q = s.getValue(Integer.class);
                float target = (q == null) ? 0f : Math.max(0f, Math.min(1f, q / (float) QUANT_STEPS));
                pushIncomingLevelSmoothed(target);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        partnerWaveRef.addValueEventListener(waveListener);
        waveListenerAttached = true;
    }

    private void detachWaveListener() {
        if (!waveListenerAttached) return;
        if (waveListener != null && partnerWaveRef != null) {
            partnerWaveRef.removeEventListener(waveListener);
        }
        waveListener = null;
        waveListenerAttached = false;
        smoothHandler.removeCallbacksAndMessages(null);
        if (cachedView != null) {
            cachedView.reset();
        }
        cachedView = null;
    }

    /** Pushes the sample immediately, then one linearly-interpolated
     *  half-step 100ms later — turns ~200ms network cadence into a
     *  ~100ms-feeling bar cadence without doubling network writes.
     *  Both pushes go through pushOrBuffer() so a mid-fling window never
     *  forces an extra draw. */
    private void pushIncomingLevelSmoothed(float target) {
        if (cachedView == null) return;
        // Redundant-target guard: if the wire value hasn't actually moved
        // (e.g. a brief silence held across a couple of samples), skip the
        // midpoint math, the buffer write, and the Handler reschedule
        // entirely instead of animating a flat line to itself.
        if (target == lastIncomingLevel) return;

        smoothHandler.removeCallbacks(smoothStep);

        // Bridge bar now (old -> halfway), real target bar 100ms later —
        // turns one 200ms network update into two evenly-spaced bars.
        float midpoint = (lastIncomingLevel + target) / 2f;
        pushOrBuffer(midpoint);
        lastIncomingLevel = target;

        smoothStep.target = target;
        smoothHandler.postDelayed(smoothStep, 100L);
    }

    /** Routes a sample through pushLevel (draws) or pushLevelQuiet
     *  (buffers only) depending on whether rvMessages is currently
     *  flinging — see the scroll-gate perf note in the class doc. */
    private void pushOrBuffer(float level) {
        if (cachedView == null) return;
        if (listIsFlinging) {
            cachedView.pushLevelQuiet(level);
        } else {
            cachedView.pushLevel(level);
        }
    }

    /** Mutable, reused Runnable — avoids allocating a new lambda on every
     *  ~200ms network sample for the whole lifetime of a recording. */
    private final class SmoothStep implements Runnable {
        float target;
        @Override public void run() {
            pushOrBuffer(target);
        }
    }

    private RecordingWaveformView resolvePreviewView() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null) return null;
        return binding.getRoot().findViewById(
                com.callx.app.chat.R.id.waveform_recording_preview);
    }
}
