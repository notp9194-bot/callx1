package com.callx.app.editor;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.view.animation.LinearInterpolator;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;

import java.io.File;
import java.util.Locale;

/**
 * ReelTransitionsActivity — Clip-to-clip transition effect picker.
 *
 * Features:
 *  ✅ 14 transition presets: Cut, Fade, Zoom In, Zoom Out, Spin,
 *     Slide Left, Slide Right, Slide Up, Slide Down, Glitch, Blur,
 *     Flash, Wipe, Split
 *  ✅ Visual thumbnail preview for each transition (animated view demo)
 *  ✅ Apply to ALL clips or specific clip gaps
 *  ✅ Duration slider (0.1s → 0.8s)
 *  ✅ LIVE preview — reuses the reel's own video (ExoPlayer/PlayerView, same
 *     rounded-corner style as the editor's other tool screens) or photo (Glide
 *     into an ImageView) and actually PLAYS the selected transition's motion
 *     on top of it on a loop, so the user sees exactly how it will look on
 *     their own clip instead of a generic icon/description card.
 *  ✅ Returns selected transition name + duration to editor
 */
public class ReelTransitionsActivity extends AppCompatActivity {

    // ── Extras passed IN from ReelEditorActivity ───────────────────────────
    public static final String EXTRA_MEDIA_URI          = "transitions_media_uri";
    public static final String EXTRA_IS_FILE_PATH        = "transitions_is_file";
    public static final String EXTRA_IS_IMAGE            = "transitions_is_image";
    public static final String EXTRA_TRIM_START_MS       = "transitions_trim_start_ms";
    public static final String EXTRA_TRIM_END_MS         = "transitions_trim_end_ms";
    public static final String EXTRA_SELECTED_NAME       = "transitions_selected_name";
    public static final String EXTRA_SELECTED_DURATION   = "transitions_selected_duration";
    public static final String EXTRA_SELECTED_APPLY_ALL  = "transitions_selected_apply_all";

    public static final String RESULT_TRANSITION_NAME     = "result_transition_name";
    public static final String RESULT_TRANSITION_DURATION = "result_transition_duration_ms";
    public static final String RESULT_APPLY_ALL           = "result_transition_apply_all";

    private static final Object[][] TRANSITIONS = {
        {"Cut",        "✂️",  "Instant hard cut — no transition",           0},
        {"Fade",       "🌫️", "Smooth fade through black",                  300},
        {"Zoom In",    "🔍",  "Camera zooms into the next clip",             250},
        {"Zoom Out",   "🔎",  "Camera zooms out from the next clip",         250},
        {"Spin",       "🌀",  "360° clockwise spin between clips",           400},
        {"Slide Left", "⬅️", "Previous clip slides out left",               300},
        {"Slide Right","➡️", "Previous clip slides out right",              300},
        {"Slide Up",   "⬆️", "Previous clip slides up off screen",          300},
        {"Slide Down", "⬇️", "Previous clip slides down off screen",        300},
        {"Glitch",     "⚡",  "RGB glitch distortion flash",                 200},
        {"Blur",       "💨",  "Motion blur fade transition",                 350},
        {"Flash",      "🌟",  "White flash between clips",                   150},
        {"Wipe",       "🪟",  "Horizontal wipe reveal",                      350},
        {"Split",      "🪓",  "Screen splits in half to reveal next clip",   400},
    };

    /** Pause between one demo playback and the next, so the clip is visible normally in between. */
    private static final long PREVIEW_HOLD_MS = 1300;

    private RecyclerView rvTransitions;
    private SeekBar      sbDuration;
    private TextView     tvDurationMs, tvSelectedName, tvSelectedDesc;
    private Switch       swApplyAll;
    private View         btnApply, btnBack;

    // ── Live preview views ──────────────────────────────────────────────
    private FrameLayout  flPreviewContent;
    private PlayerView   previewPlayerView;
    private ImageView    ivPreviewPhoto;
    private View         vWipeOverlay;
    private View         vSplitTop, vSplitBottom;
    private View         vFlashOverlay;
    private TextView     tvNoPreview;

    private ExoPlayer previewPlayer;
    private String    mediaUriStr;
    private boolean   isFilePath  = true;
    private boolean   isImage     = false;
    private boolean   hasMedia    = false;
    private long      trimStartMs = 0;
    private long      trimEndMs   = 0;

    private int  selectedIdx   = 0;
    private int  durationMs    = 300;
    private boolean applyAll   = true;

    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private Runnable      loopCycleRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_transitions);

        mediaUriStr = getIntent().getStringExtra(EXTRA_MEDIA_URI);
        isFilePath  = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);
        isImage     = getIntent().getBooleanExtra(EXTRA_IS_IMAGE, false);
        trimStartMs = getIntent().getLongExtra(EXTRA_TRIM_START_MS, 0);
        trimEndMs   = getIntent().getLongExtra(EXTRA_TRIM_END_MS, 0);
        if (!isImage && mediaUriStr != null) isImage = looksLikeImage(mediaUriStr);
        hasMedia = mediaUriStr != null && !mediaUriStr.isEmpty();

        applyAll = getIntent().getBooleanExtra(EXTRA_SELECTED_APPLY_ALL, true);
        String   preName = getIntent().getStringExtra(EXTRA_SELECTED_NAME);
        int      preDur  = getIntent().getIntExtra(EXTRA_SELECTED_DURATION, -1);

        bindViews();
        buildList();
        setupPreviewMedia();

        int startIdx = 0;
        if (preName != null && !preName.isEmpty()) {
            for (int i = 0; i < TRANSITIONS.length; i++) {
                if (TRANSITIONS[i][0].toString().equalsIgnoreCase(preName)) { startIdx = i; break; }
            }
        }
        if (preDur > 0) durationMs = preDur;
        updateSelection(startIdx);
    }

    private boolean looksLikeImage(String uriOrPath) {
        String s = uriOrPath.toLowerCase(Locale.ROOT);
        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png")
            || s.endsWith(".webp") || s.endsWith(".heic") || s.endsWith(".heif")
            || s.contains("image/");
    }

    private void bindViews() {
        btnBack         = findViewById(R.id.btn_transitions_back);
        rvTransitions   = findViewById(R.id.rv_transitions_list);
        sbDuration      = findViewById(R.id.sb_transition_duration);
        tvDurationMs    = findViewById(R.id.tv_transition_duration_val);
        tvSelectedName  = findViewById(R.id.tv_transition_selected_name);
        tvSelectedDesc  = findViewById(R.id.tv_transition_selected_desc);
        swApplyAll      = findViewById(R.id.sw_transition_apply_all);
        btnApply        = findViewById(R.id.btn_transitions_apply);

        flPreviewContent  = findViewById(R.id.fl_transition_preview_content);
        previewPlayerView = findViewById(R.id.transition_preview_player);
        ivPreviewPhoto    = findViewById(R.id.iv_transition_preview_photo);
        vWipeOverlay      = findViewById(R.id.v_transition_wipe_overlay);
        vSplitTop         = findViewById(R.id.v_transition_split_top);
        vSplitBottom      = findViewById(R.id.v_transition_split_bottom);
        vFlashOverlay     = findViewById(R.id.v_transition_flash_overlay);
        tvNoPreview       = findViewById(R.id.tv_transition_no_preview);

        btnBack.setOnClickListener(v -> finish());

        swApplyAll.setChecked(applyAll);
        swApplyAll.setOnCheckedChangeListener((b, checked) -> applyAll = checked);

        sbDuration.setMax(70);  // 0→700ms, steps of 10
        sbDuration.setProgress(20); // default 300ms

        sbDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                durationMs = 100 + p * 10;
                tvDurationMs.setText(durationMs + " ms");
                // Live-reflect the new duration on the next demo loop automatically —
                // playTransitionDemo() always reads the current durationMs.
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        btnApply.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(RESULT_TRANSITION_NAME,     TRANSITIONS[selectedIdx][0].toString());
            result.putExtra(RESULT_TRANSITION_DURATION, durationMs);
            result.putExtra(RESULT_APPLY_ALL,           applyAll);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    // ── Live preview media setup (video via ExoPlayer, photo via Glide) ────

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void setupPreviewMedia() {
        if (!hasMedia) {
            if (tvNoPreview != null) tvNoPreview.setVisibility(View.VISIBLE);
            return;
        }
        Uri uri = isFilePath ? Uri.fromFile(new File(mediaUriStr)) : Uri.parse(mediaUriStr);

        if (isImage) {
            if (ivPreviewPhoto != null) {
                ivPreviewPhoto.setVisibility(View.VISIBLE);
                Glide.with(this)
                    .load(uri)
                    .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .fitCenter())
                    .into(ivPreviewPhoto);
            }
        } else {
            if (previewPlayerView != null) {
                previewPlayerView.setVisibility(View.VISIBLE);
                previewPlayer = new ExoPlayer.Builder(this).build();
                previewPlayerView.setPlayer(previewPlayer);
                previewPlayer.setMediaItem(MediaItem.fromUri(uri));
                // ✅ Same manual trim-loop technique ReelEditorActivity's playheadUpdater
                // uses — loops preview playback within [trimStartMs, trimEndMs] only,
                // so this screen shows exactly the range the user already trimmed.
                previewPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
                previewPlayer.prepare();
                previewPlayer.setPlayWhenReady(true);
                if (trimStartMs > 0) previewPlayer.seekTo(trimStartMs);
                previewHandler.post(previewLoopWatcher);
            }
        }
    }

    private final Runnable previewLoopWatcher = new Runnable() {
        @Override public void run() {
            if (previewPlayer != null) {
                long pos = previewPlayer.getCurrentPosition();
                if (trimEndMs > trimStartMs && pos >= trimEndMs) {
                    previewPlayer.seekTo(trimStartMs);
                }
            }
            previewHandler.postDelayed(this, 200);
        }
    };

    private void buildList() {
        rvTransitions.setLayoutManager(new LinearLayoutManager(this));
        rvTransitions.setAdapter(new TransitionAdapter());
    }

    private void updateSelection(int idx) {
        selectedIdx = idx;
        tvSelectedName.setText(TRANSITIONS[idx][0].toString());
        tvSelectedDesc.setText(TRANSITIONS[idx][2].toString());
        int dur = (int) TRANSITIONS[idx][3];
        if (dur > 0) {
            durationMs = dur;
            sbDuration.setProgress((dur - 100) / 10);
            tvDurationMs.setText(dur + " ms");
        }
        if (rvTransitions.getAdapter() != null) rvTransitions.getAdapter().notifyDataSetChanged();

        // ✅ Play the newly-picked transition on the live preview right away.
        restartPreviewLoop();
    }

    // ── Live transition-preview animation engine ────────────────────────
    // Since a reel is edited/exported as a single clip here, there is no real
    // second clip to cut TO — so each transition is demoed as a there-and-back
    // loop on the reel's own video/photo: play normally for a beat, then run
    // the transition's actual motion (the same motion that will be baked in
    // between clip gaps at export), then resume. This is what makes the
    // preview "live" instead of a static icon.

    private void restartPreviewLoop() {
        if (loopCycleRunnable != null) previewHandler.removeCallbacks(loopCycleRunnable);
        if (flPreviewContent != null) flPreviewContent.animate().cancel();
        resetPreviewTransforms();
        if (!hasMedia) return;
        loopCycleRunnable = this::playTransitionDemo;
        previewHandler.postDelayed(loopCycleRunnable, 250);
    }

    private void resetPreviewTransforms() {
        if (flPreviewContent != null) {
            flPreviewContent.setAlpha(1f);
            flPreviewContent.setScaleX(1f);
            flPreviewContent.setScaleY(1f);
            flPreviewContent.setRotation(0f);
            flPreviewContent.setTranslationX(0f);
            flPreviewContent.setTranslationY(0f);
        }
        if (vFlashOverlay != null) vFlashOverlay.setAlpha(0f);
        if (vWipeOverlay != null)   setViewWidth(vWipeOverlay, 0);
        if (vSplitTop != null)      setViewHeight(vSplitTop, 0);
        if (vSplitBottom != null)   setViewHeight(vSplitBottom, 0);
    }

    private void playTransitionDemo() {
        if (isFinishing() || isDestroyed() || flPreviewContent == null) return;
        String name = TRANSITIONS[selectedIdx][0].toString();
        int    dur  = Math.max(120, durationMs);

        Runnable afterDone = () -> {
            resetPreviewTransforms();
            loopCycleRunnable = this::playTransitionDemo;
            previewHandler.postDelayed(loopCycleRunnable, PREVIEW_HOLD_MS);
        };

        switch (name) {
            case "Cut":         animateCut(afterDone);              break;
            case "Fade":        animateFade(dur, afterDone);        break;
            case "Zoom In":     animateZoom(dur, true,  afterDone); break;
            case "Zoom Out":    animateZoom(dur, false, afterDone); break;
            case "Spin":        animateSpin(dur, afterDone);        break;
            case "Slide Left":  animateSlide(dur, -1, 0, afterDone);break;
            case "Slide Right": animateSlide(dur,  1, 0, afterDone);break;
            case "Slide Up":    animateSlide(dur, 0, -1, afterDone);break;
            case "Slide Down":  animateSlide(dur, 0,  1, afterDone);break;
            case "Glitch":      animateGlitch(dur, afterDone);      break;
            case "Blur":        animateBlur(dur, afterDone);        break;
            case "Flash":       animateFlash(dur, afterDone);       break;
            case "Wipe":        animateWipe(dur, afterDone);        break;
            case "Split":       animateSplit(dur, afterDone);       break;
            default:            afterDone.run();
        }
    }

    private void animateCut(Runnable afterDone) {
        // A hard cut has no motion — just hold briefly so the "cut point" reads.
        previewHandler.postDelayed(afterDone, 120);
    }

    private void animateFade(int dur, Runnable afterDone) {
        flPreviewContent.animate().alpha(0f).setDuration(dur / 2L).withEndAction(() ->
            flPreviewContent.animate().alpha(1f).setDuration(dur / 2L)
                .withEndAction(afterDone).start()
        ).start();
    }

    private void animateZoom(int dur, boolean zoomIn, Runnable afterDone) {
        float outScale = zoomIn ? 1.4f : 0.6f;
        float inFromScale = zoomIn ? 0.7f : 1.5f;
        flPreviewContent.animate().scaleX(outScale).scaleY(outScale).alpha(0f)
            .setDuration(dur / 2L).withEndAction(() -> {
                flPreviewContent.setScaleX(inFromScale);
                flPreviewContent.setScaleY(inFromScale);
                flPreviewContent.setAlpha(0f);
                flPreviewContent.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(dur / 2L).withEndAction(afterDone).start();
            }).start();
    }

    private void animateSpin(int dur, Runnable afterDone) {
        flPreviewContent.animate().rotation(180f).alpha(0f)
            .setDuration(dur / 2L).withEndAction(() -> {
                flPreviewContent.setRotation(-180f);
                flPreviewContent.setAlpha(0f);
                flPreviewContent.animate().rotation(0f).alpha(1f)
                    .setDuration(dur / 2L).withEndAction(afterDone).start();
            }).start();
    }

    private void animateSlide(int dur, int dirX, int dirY, Runnable afterDone) {
        float w = flPreviewContent.getWidth()  > 0 ? flPreviewContent.getWidth()  : 300;
        float h = flPreviewContent.getHeight() > 0 ? flPreviewContent.getHeight() : 500;
        flPreviewContent.animate()
            .translationX(dirX * w).translationY(dirY * h)
            .setDuration(dur / 2L).withEndAction(() -> {
                flPreviewContent.setTranslationX(-dirX * w);
                flPreviewContent.setTranslationY(-dirY * h);
                flPreviewContent.animate().translationX(0f).translationY(0f)
                    .setDuration(dur / 2L).withEndAction(afterDone).start();
            }).start();
    }

    private void animateGlitch(int dur, Runnable afterDone) {
        final float amp = 18f;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(dur);
        va.setInterpolator(new LinearInterpolator());
        va.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float jitter = (float) Math.sin(t * 50) * amp * (1f - t);
            flPreviewContent.setTranslationX(jitter);
            if (vFlashOverlay != null) {
                vFlashOverlay.setAlpha(Math.abs((float) Math.sin(t * 40)) * 0.35f * (1f - t * 0.3f));
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                flPreviewContent.setTranslationX(0f);
                if (vFlashOverlay != null) vFlashOverlay.setAlpha(0f);
                afterDone.run();
            }
        });
        va.start();
    }

    private void animateBlur(int dur, Runnable afterDone) {
        // No true blur below API 31 without RenderEffect — approximated with a
        // quick scale + alpha punch, which reads the same as a motion-blur cut.
        flPreviewContent.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.35f)
            .setDuration(dur / 2L).withEndAction(() ->
                flPreviewContent.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(dur / 2L).withEndAction(afterDone).start()
            ).start();
    }

    private void animateFlash(int dur, Runnable afterDone) {
        if (vFlashOverlay == null) { afterDone.run(); return; }
        vFlashOverlay.animate().alpha(1f).setDuration((long) (dur * 0.3))
            .withEndAction(() ->
                vFlashOverlay.animate().alpha(0f).setDuration((long) (dur * 0.7))
                    .withEndAction(afterDone).start()
            ).start();
    }

    private void animateWipe(int dur, Runnable afterDone) {
        if (vWipeOverlay == null) { afterDone.run(); return; }
        int fullWidth = flPreviewContent.getWidth() > 0 ? flPreviewContent.getWidth() : 300;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(0, fullWidth);
        va.setDuration(dur);
        va.addUpdateListener(a -> setViewWidth(vWipeOverlay, (int) a.getAnimatedValue()));
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { afterDone.run(); }
        });
        va.start();
    }

    private void animateSplit(int dur, Runnable afterDone) {
        if (vSplitTop == null || vSplitBottom == null) { afterDone.run(); return; }
        int halfHeight = flPreviewContent.getHeight() > 0 ? flPreviewContent.getHeight() / 2 : 250;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(0, halfHeight);
        va.setDuration(dur);
        va.addUpdateListener(a -> {
            int h = (int) a.getAnimatedValue();
            setViewHeight(vSplitTop, h);
            setViewHeight(vSplitBottom, h);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { afterDone.run(); }
        });
        va.start();
    }

    private void setViewWidth(View v, int w) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = w;
        v.setLayoutParams(lp);
    }

    private void setViewHeight(View v, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.height = h;
        v.setLayoutParams(lp);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    protected void onPause() {
        super.onPause();
        if (previewPlayer != null) previewPlayer.setPlayWhenReady(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewPlayer != null) previewPlayer.setPlayWhenReady(true);
    }

    @Override
    protected void onDestroy() {
        if (loopCycleRunnable != null) previewHandler.removeCallbacks(loopCycleRunnable);
        previewHandler.removeCallbacks(previewLoopWatcher);
        if (previewPlayer != null) { previewPlayer.stop(); previewPlayer.release(); previewPlayer = null; }
        super.onDestroy();
    }

    private class TransitionAdapter extends RecyclerView.Adapter<TransitionAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_transition_chip, p, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tvEmoji.setText(TRANSITIONS[pos][1].toString());
            h.tvName.setText(TRANSITIONS[pos][0].toString());
            h.tvDesc.setText(TRANSITIONS[pos][2].toString());
            boolean sel = (pos == selectedIdx);
            h.vSelected.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.itemView.setAlpha(sel ? 1.0f : 0.7f);
            h.itemView.setOnClickListener(v -> updateSelection(pos));
        }

        @Override public int getItemCount() { return TRANSITIONS.length; }

        class VH extends RecyclerView.ViewHolder {
            TextView tvEmoji, tvName, tvDesc;
            View     vSelected;
            VH(View v) {
                super(v);
                tvEmoji   = v.findViewById(R.id.tv_transition_emoji);
                tvName    = v.findViewById(R.id.tv_transition_name);
                tvDesc    = v.findViewById(R.id.tv_transition_desc);
                vSelected = v.findViewById(R.id.v_transition_selected);
            }
        }
    }
}
