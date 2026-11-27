package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;

import java.io.File;
import java.util.Locale;

/**
 * PERF: direct ProgressiveMediaSource for LOCAL videos (file:// path, bare path,
 * content://) — skips DefaultMediaSourceFactory's scheme/mime resolution, uses
 * FileDataSource for files, and for ISO-BMFF extensions (mp4/m4v/mov/3gp) limits
 * extractor probing to the two MP4 extractors instead of the full default list.
 * Anything unrecognised keeps the full DefaultExtractorsFactory, so behaviour is
 * identical to before for odd containers. Remote (http/https) is NOT handled here —
 * callers keep MediaItem.fromUri() for streaming.
 */
public final class LocalVideoSource {

    private LocalVideoSource() {}

    private static final ExtractorsFactory MP4_ONLY =
            () -> new Extractor[]{ new FragmentedMp4Extractor(), new Mp4Extractor() };

    private static final ExtractorsFactory ANY = new DefaultExtractorsFactory();

    /** @return a ready MediaSource for a local uri, or null if not local (caller uses MediaItem.fromUri). */
    @Nullable
    public static MediaSource create(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return null;
        try {
            String scheme = uri.getScheme();
            DataSource.Factory dsf;
            Uri playUri = uri;
            if (scheme == null) {
                String p = uri.toString();
                if (p.isEmpty()) return null;
                playUri = Uri.fromFile(new File(p));
                dsf = new FileDataSource.Factory();
            } else if ("file".equals(scheme)) {
                dsf = new FileDataSource.Factory();
            } else if ("content".equals(scheme)) {
                dsf = new DefaultDataSource.Factory(ctx.getApplicationContext());
            } else {
                return null;
            }
            ExtractorsFactory ef = isIsoBmff(playUri) ? MP4_ONLY : ANY;
            return new ProgressiveMediaSource.Factory(dsf, ef)
                    .createMediaSource(MediaItem.fromUri(playUri));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Sets the source on {@code player} (local → ProgressiveMediaSource, else plain MediaItem). Does NOT call prepare(). */
    public static void apply(Context ctx, ExoPlayer player, Uri uri) {
        MediaSource ms = create(ctx, uri);
        if (ms != null) player.setMediaSource(ms);
        else player.setMediaItem(MediaItem.fromUri(uri));
    }

    private static boolean isIsoBmff(Uri uri) {
        String path = uri.getPath();
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".mp4") || p.endsWith(".m4v") || p.endsWith(".mov")
                || p.endsWith(".3gp") || p.endsWith(".3gpp");
    }
}
