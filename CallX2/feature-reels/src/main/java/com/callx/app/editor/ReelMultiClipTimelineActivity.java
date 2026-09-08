package com.callx.app.editor;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.utils.VideoPickerHelper;
import com.callx.app.views.VideoTrimFilmstripView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * "Clips" tool — CapCut-style multi-clip timeline. Lets the user add several
 * video clips, drag-reorder them, trim each one individually, preview the
 * whole sequence back-to-back, then merges everything into a single mp4
 * (via Media3 Transformer, same engine ReelVideoExportEngine already uses)
 * so the result can flow through the rest of the single-clip editor/upload
 * pipeline completely unchanged.
 *
 * Launched from ReelEditorActivity's "Clips" tool, seeded with whatever
 * clip is already loaded there as clip #1.
 */
@UnstableApi
public class ReelMultiClipTimelineActivity extends AppCompatActivity {

    public static final String EXTRA_SEED_VIDEO_URI     = "seed_video_uri";
    public static final String EXTRA_SEED_IS_FILE_PATH  = "seed_is_file_path";
    public static final String EXTRA_SEED_DURATION_MS   = "seed_duration_ms";
    public static final String RESULT_MERGED_PATH        = "result_merged_path";
    public static final String RESULT_MERGED_DURATION_MS = "result_merged_duration_ms";

    private final List<TimelineClip> clips = new ArrayList<>();
    private TimelineClipAdapter adapter;
    private RecyclerView recyclerView;
    private ExoPlayer previewPlayer;
    private PlayerView playerView;
    private ImageButton btnPlayPause;
    private TextView tvTotalDuration;
    private VideoPickerHelper videoPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_multiclip_timeline);

        String seedUri = getIntent().getStringExtra(EXTRA_SEED_VIDEO_URI);
        boolean seedIsFilePath = getIntent().getBooleanExtra(EXTRA_SEED_IS_FILE_PATH, false);
        long seedDuration = getIntent().getLongExtra(EXTRA_SEED_DURATION_MS, 0);
        if (seedUri != null && !seedUri.isEmpty()) {
            clips.add(new TimelineClip(seedUri, seedIsFilePath, seedDuration > 0 ? seedDuration : 5000));
        }

        videoPicker = new VideoPickerHelper(this,
                uri -> addClipFromUri(uri, false),
                uris -> { for (Uri u : uris) addClipFromUri(u, false); });

        bindViews();
        adapter = new TimelineClipAdapter(clips, adapterListener);
        recyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerView.setAdapter(adapter);
        attachDragReorder();

        rebuildPreviewPlaylist();
        updateTotalDuration();
    }

    private void bindViews() {
        playerView      = findViewById(R.id.multiclip_player_view);
        recyclerView    = findViewById(R.id.multiclip_recycler);
        btnPlayPause    = findViewById(R.id.btn_multiclip_play_pause);
        tvTotalDuration = findViewById(R.id.tv_multiclip_total_duration);

        findViewById(R.id.btn_multiclip_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_multiclip_done).setOnClickListener(v -> onDoneTapped());
        btnPlayPause.setOnClickListener(v -> togglePlayback());
    }

    private final TimelineClipAdapter.Listener adapterListener = new TimelineClipAdapter.Listener() {
        @Override public void onClipTapped(int position) {
            if (position >= 0 && position < clips.size()) openTrimSheet(position);
        }
        @Override public void onClipDeleted(int position) {
            if (position < 0 || position >= clips.size() || clips.size() <= 1) return;
            clips.remove(position);
            adapter.notifyDataSetChanged();
            rebuildPreviewPlaylist();
            updateTotalDuration();
        }
        @Override public void onAddClipTapped() {
            videoPicker.openGalleryMulti();
        }
        @Override public void onOrderChanged() {
            rebuildPreviewPlaylist();
        }
    };

    private void addClipFromUri(Uri uri, boolean isFilePath) {
        VideoPickerHelper.VideoInfo info = videoPicker.getVideoInfo(uri);
        long duration = info.durationMs > 0 ? info.durationMs : 5000;
        clips.add(new TimelineClip(uri.toString(), isFilePath, duration));
        adapter.notifyDataSetChanged();
        rebuildPreviewPlaylist();
        updateTotalDuration();
        recyclerView.scrollToPosition(clips.size()); // land on the trailing "+" card
    }

    private void attachDragReorder() {
        ItemTouchHelper.SimpleCallback cb = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                             @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getBindingAdapterPosition(), to = target.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
                if (from >= clips.size() || to >= clips.size()) return false; // never swap with the "+" card
                adapter.onItemMove(from, to);
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) { /* no-op: delete uses its own button */ }
            @Override public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                adapter.onDragFinished();
                updateTotalDuration();
            }
            @Override public boolean isLongPressDragEnabled() { return true; }
        };
        new ItemTouchHelper(cb).attachToRecyclerView(recyclerView);
    }

    // ── Per-clip trim ───────────────────────────────────────────────────

    private void openTrimSheet(int position) {
        TimelineClip clip = clips.get(position);
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_clip_trim, null);
        dialog.setContentView(content);

        VideoTrimFilmstripView trimView = content.findViewById(R.id.clip_trim_filmstrip);
        TextView tvClipDuration = content.findViewById(R.id.tv_clip_trim_duration);
        trimView.setDuration(clip.durationMs);
        trimView.setTrimRange(clip.trimStartMs, clip.trimEndMs);
        trimView.loadThumbnails(this, clip.uriOrPath, clip.isFilePath, clip.durationMs);
        tvClipDuration.setText(formatDuration(clip.trimmedDurationMs()));

        trimView.setOnTrimChangeListener(new VideoTrimFilmstripView.OnTrimChangeListener() {
            @Override public void onTrimChanged(long startMs, long endMs, boolean fromUser) {
                clip.trimStartMs = startMs;
                clip.trimEndMs   = endMs;
                tvClipDuration.setText(formatDuration(clip.trimmedDurationMs()));
            }
            @Override public void onTrimTouchEnd(long startMs, long endMs) {
                clip.trimStartMs = startMs;
                clip.trimEndMs   = endMs;
                adapter.notifyItemChanged(position);
                rebuildPreviewPlaylist();
                updateTotalDuration();
            }
        });

        content.findViewById(R.id.btn_clip_trim_done).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // ── Concatenated preview ────────────────────────────────────────────

    @OptIn(markerClass = UnstableApi.class)
    private void rebuildPreviewPlaylist() {
        if (previewPlayer == null) {
            previewPlayer = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(previewPlayer);
            playerView.setUseController(false);
        }
        long pos = previewPlayer.getCurrentPosition();
        boolean wasPlaying = previewPlayer.isPlaying();
        previewPlayer.stop();
        previewPlayer.clearMediaItems();
        List<MediaItem> items = new ArrayList<>();
        for (TimelineClip c : clips) {
            items.add(new MediaItem.Builder()
                    .setUri(c.toUri())
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(c.trimStartMs)
                            .setEndPositionMs(c.trimEndMs)
                            .build())
                    .build());
        }
        if (items.isEmpty()) return;
        previewPlayer.setMediaItems(items);
        previewPlayer.prepare();
        if (wasPlaying) previewPlayer.play(); else previewPlayer.seekTo(0, 0);
    }

    private void togglePlayback() {
        if (previewPlayer == null) return;
        if (previewPlayer.isPlaying()) {
            previewPlayer.pause();
            btnPlayPause.setImageResource(R.drawable.ic_play);
        } else {
            if (previewPlayer.getCurrentMediaItemIndex() == previewPlayer.getMediaItemCount() - 1
                    && previewPlayer.getCurrentPosition() >= previewPlayer.getDuration()) {
                previewPlayer.seekTo(0, 0);
            }
            previewPlayer.play();
            btnPlayPause.setImageResource(R.drawable.ic_pause);
        }
    }

    private void updateTotalDuration() {
        long total = 0;
        for (TimelineClip c : clips) total += c.trimmedDurationMs();
        tvTotalDuration.setText(clips.size() + (clips.size() == 1 ? " clip · " : " clips · ") + formatDuration(total));
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    // ── Merge & return ──────────────────────────────────────────────────

    private void onDoneTapped() {
        if (clips.isEmpty()) { finish(); return; }

        // Single untrimmed clip → nothing to merge, hand it straight back.
        if (clips.size() == 1) {
            TimelineClip only = clips.get(0);
            boolean untouched = only.trimStartMs == 0 && only.trimEndMs == only.durationMs;
            if (untouched) {
                Intent r = new Intent();
                r.putExtra(RESULT_MERGED_PATH, only.uriOrPath);
                r.putExtra("result_is_file_path", only.isFilePath);
                r.putExtra(RESULT_MERGED_DURATION_MS, only.durationMs);
                setResult(RESULT_OK, r);
                finish();
                return;
            }
        }

        mergeClips();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void mergeClips() {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Merging clips…");
        progress.setCancelable(false);
        progress.setMax(100);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.show();

        if (previewPlayer != null) previewPlayer.stop();

        try {
            File outDir = new File(getCacheDir(), "reel_multiclip");
            if (!outDir.exists()) outDir.mkdirs();
            File output = new File(outDir, "merged_" + System.currentTimeMillis() + ".mp4");

            List<EditedMediaItem> editedItems = new ArrayList<>();
            long totalMs = 0;
            for (TimelineClip c : clips) {
                MediaItem item = new MediaItem.Builder()
                        .setUri(c.toUri())
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(c.trimStartMs)
                                .setEndPositionMs(c.trimEndMs)
                                .build())
                        .build();
                editedItems.add(new EditedMediaItem.Builder(item).build());
                totalMs += c.trimmedDurationMs();
            }
            long finalTotalMs = totalMs;

            EditedMediaItemSequence sequence = new EditedMediaItemSequence(ImmutableList.copyOf(editedItems));
            Composition composition = new Composition.Builder(ImmutableList.of(sequence)).build();

            Handler mainHandler = new Handler(Looper.getMainLooper());
            Transformer transformer = new Transformer.Builder(this)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(@NonNull Composition c, @NonNull ExportResult r) {
                            mainHandler.post(() -> {
                                progress.dismiss();
                                Intent result = new Intent();
                                result.putExtra(RESULT_MERGED_PATH, output.getAbsolutePath());
                                result.putExtra("result_is_file_path", true);
                                result.putExtra(RESULT_MERGED_DURATION_MS, finalTotalMs);
                                setResult(RESULT_OK, result);
                                finish();
                            });
                        }
                        @Override public void onError(@NonNull Composition c, @NonNull ExportResult r,
                                                        @NonNull ExportException e) {
                            mainHandler.post(() -> {
                                progress.dismiss();
                                Toast.makeText(ReelMultiClipTimelineActivity.this,
                                        "Couldn't merge clips: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    })
                    .build();

            transformer.start(composition, output.getAbsolutePath());

            ProgressHolder holder = new ProgressHolder();
            Runnable poller = new Runnable() {
                @Override public void run() {
                    int state = transformer.getProgress(holder);
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        progress.setProgress(holder.progress);
                        mainHandler.postDelayed(this, 250);
                    } else if (state == Transformer.PROGRESS_STATE_NOT_STARTED) {
                        mainHandler.postDelayed(this, 250);
                    }
                }
            };
            mainHandler.postDelayed(poller, 250);

        } catch (Exception e) {
            progress.dismiss();
            Toast.makeText(this, "Couldn't merge clips: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) adapter.shutdown();
        if (previewPlayer != null) { previewPlayer.release(); previewPlayer = null; }
    }
}
