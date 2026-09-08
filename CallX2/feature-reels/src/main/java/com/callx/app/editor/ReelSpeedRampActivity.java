package com.callx.app.editor;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;
import com.callx.app.views.SpeedRampCurveView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Speed Ramp" editor tool — lets the user draw a variable-speed curve
 * across the clip (slow-mo dips, speed-ups, fast→slow→fast, etc.) instead
 * of the old flat single-speed picker (ReelSpeedControlActivity, which is
 * still used for the camera's pre-record speed and is untouched by this).
 *
 * Result is returned as a JSON array of {posMs, speed} keyframes via
 * RESULT_RAMP_JSON, the same "carry a JSON extra through to upload" pattern
 * already used elsewhere in the editor (e.g. EXTRA_PRESET_STICKERS_JSON).
 */
@UnstableApi
public class ReelSpeedRampActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI      = "video_uri";
    public static final String EXTRA_DURATION_MS     = "duration_ms";
    public static final String EXTRA_EXISTING_RAMP_JSON = "existing_ramp_json";
    public static final String RESULT_RAMP_JSON      = "result_ramp_json";
    public static final String RESULT_HAS_RAMP       = "result_has_ramp";

    private ExoPlayer player;
    private PlayerView playerView;
    private SpeedRampCurveView curveView;
    private TextView tvSpeedReadout;
    private ImageButton btnPlayPause;
    private long durationMs;

    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private float lastAppliedSpeed = 1.0f;
    private final Runnable previewSyncTick = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) {
                long pos = player.getCurrentPosition();
                curveView.setPlayheadMs(pos);
                float target = curveView.speedAt(pos);
                // Only push a new PlaybackParameters when it actually moved — ExoPlayer
                // re-inits its audio speed pipeline on every set, so throttling this
                // avoids audible micro-stutter on a smooth ramp.
                if (Math.abs(target - lastAppliedSpeed) > 0.02f) {
                    lastAppliedSpeed = target;
                    player.setPlaybackParameters(new PlaybackParameters(target));
                }
                updateReadout(target);
            }
            previewHandler.postDelayed(this, 80);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_speed_ramp);

        durationMs = getIntent().getLongExtra(EXTRA_DURATION_MS, 5000);
        String videoUriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);

        bindViews();
        setupPresets();

        if (videoUriStr != null) setupPlayer(Uri.parse(videoUriStr));

        curveView.setDurationMs(durationMs);
        String existing = getIntent().getStringExtra(EXTRA_EXISTING_RAMP_JSON);
        if (existing != null && !existing.isEmpty()) {
            curveView.loadKeyframes(parseRampJson(existing));
        }
        curveView.setOnRampChangeListener(kfs -> updateReadout(curveView.speedAt(
                player != null ? player.getCurrentPosition() : 0)));
        updateReadout(1.0f);
    }

    private void bindViews() {
        playerView = findViewById(R.id.speedramp_player_view);
        curveView = findViewById(R.id.speedramp_curve_view);
        tvSpeedReadout = findViewById(R.id.tv_speedramp_readout);
        btnPlayPause = findViewById(R.id.btn_speedramp_play_pause);

        findViewById(R.id.btn_speedramp_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_speedramp_reset).setOnClickListener(v -> {
            curveView.applyPreset("flat");
            Toast.makeText(this, "Ramp reset to 1x", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_speedramp_apply).setOnClickListener(v -> returnResult());

        btnPlayPause.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) {
                player.pause();
                btnPlayPause.setImageResource(R.drawable.ic_play);
            } else {
                if (player.getCurrentPosition() >= player.getDuration()) player.seekTo(0);
                player.play();
                btnPlayPause.setImageResource(R.drawable.ic_pause);
            }
        });
    }

    private void setupPresets() {
        bindPreset(R.id.chip_preset_flat, "flat");
        bindPreset(R.id.chip_preset_slowmo_dip, "slowmo_dip");
        bindPreset(R.id.chip_preset_build_up, "build_up");
        bindPreset(R.id.chip_preset_fast_slow_fast, "fast_slow_fast");
        bindPreset(R.id.chip_preset_timelapse_end, "timelapse_end");
    }

    private void bindPreset(int viewId, String key) {
        View v = findViewById(viewId);
        if (v == null) return;
        v.setOnClickListener(x -> {
            curveView.applyPreset(key);
            if (player != null) player.seekTo(0);
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    private void setupPlayer(Uri uri) {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.setRepeatMode(ExoPlayer.REPEAT_MODE_OFF);
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    btnPlayPause.setImageResource(R.drawable.ic_play);
                    curveView.setPlayheadMs(durationMs);
                }
            }
        });
        player.prepare();
        previewHandler.post(previewSyncTick);
    }

    private void updateReadout(float speed) {
        tvSpeedReadout.setText(String.format(java.util.Locale.US, "%.1fx", speed));
    }

    private void returnResult() {
        List<SpeedRampCurveView.Keyframe> kfs = curveView.getKeyframes();
        boolean hasRamp = !curveView.isFlat();
        Intent r = new Intent();
        r.putExtra(RESULT_HAS_RAMP, hasRamp);
        if (hasRamp) r.putExtra(RESULT_RAMP_JSON, rampToJson(kfs));
        setResult(RESULT_OK, r);
        finish();
    }

    private static String rampToJson(List<SpeedRampCurveView.Keyframe> kfs) {
        JSONArray arr = new JSONArray();
        try {
            for (SpeedRampCurveView.Keyframe k : kfs) {
                JSONObject o = new JSONObject();
                o.put("posMs", k.posMs);
                o.put("speed", k.speed);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    /** Also used by ReelEditorActivity / the export engine to read a stored ramp. */
    public static List<SpeedRampCurveView.Keyframe> parseRampJson(String json) {
        List<SpeedRampCurveView.Keyframe> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new SpeedRampCurveView.Keyframe(o.getLong("posMs"), (float) o.getDouble("speed")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Interpolated speed at posMs for an arbitrary parsed keyframe list — used by
     *  ReelEditorActivity's live preview and can be reused by the export engine. */
    public static float speedAt(List<SpeedRampCurveView.Keyframe> kfs, long posMs) {
        if (kfs == null || kfs.isEmpty()) return 1.0f;
        if (posMs <= kfs.get(0).posMs) return kfs.get(0).speed;
        for (int i = 0; i < kfs.size() - 1; i++) {
            SpeedRampCurveView.Keyframe a = kfs.get(i), b = kfs.get(i + 1);
            if (posMs >= a.posMs && posMs <= b.posMs) {
                if (b.posMs == a.posMs) return a.speed;
                float t = (posMs - a.posMs) / (float) (b.posMs - a.posMs);
                return a.speed + (b.speed - a.speed) * t;
            }
        }
        return kfs.get(kfs.size() - 1).speed;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        previewHandler.removeCallbacks(previewSyncTick);
        if (player != null) { player.release(); player = null; }
    }
}
