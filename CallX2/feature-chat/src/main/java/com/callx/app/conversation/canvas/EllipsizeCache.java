package com.callx.app.conversation.canvas;

import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Tiny memoizer for {@link TextUtils#ellipsize} results.
 *
 * Several renderers (contact name/phone, file bubble name/meta, media-group
 * per-cell captions and audio/file labels) were previously calling
 * measureText()/TextUtils.ellipsize() fresh inside draw() — i.e. on every
 * single frame, including frames triggered by something unrelated (an
 * expiry-countdown tick, a waveform tick elsewhere in the same RecyclerView,
 * a plain re-invalidate). Ellipsizing is comparatively expensive (it has to
 * binary-search/measure substrings), so this cache only redoes that work
 * when the inputs that could actually change the result changed:
 *   - the source text itself
 *   - the available width to ellipsize into
 *   - the paint's text size (a proxy for "the paint that measures it changed
 *     in a way that would change the result" — style/typeface changes on a
 *     shared Paint mid-frame are covered by callers re-fetching afterward,
 *     same as before this cache existed)
 *
 * One instance per on-screen text slot (e.g. one for contact name, one for
 * contact phone, one per media-group cell) — callers own the instance and
 * decide its lifetime/reset points, this class only memoizes.
 */
final class EllipsizeCache {

    private String sourceText;
    private float maxWidth = -1f;
    private float textSize = -1f;
    private TextUtils.TruncateAt truncateAt;
    private String result = "";

    /**
     * Returns the ellipsized string for the given inputs, recomputing only
     * if they differ from the last call.
     */
    String get(CharSequence source, TextPaint paint, float maxWidthPx, TextUtils.TruncateAt at) {
        String src = source != null ? source.toString() : "";
        float width = Math.max(1f, maxWidthPx);
        float size = paint.getTextSize();
        if (sourceText != null && sourceText.equals(src) && maxWidth == width
                && textSize == size && truncateAt == at) {
            return result;
        }
        sourceText = src;
        maxWidth = width;
        textSize = size;
        truncateAt = at;
        result = TextUtils.ellipsize(src, paint, width, at).toString();
        return result;
    }
}
