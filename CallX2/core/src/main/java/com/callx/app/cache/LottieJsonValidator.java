package com.callx.app.cache;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Guards against the class of bug that crashed the whole app on long-press:
 * a hand-authored placeholder Lottie JSON (emoji-assets/heart.json etc.) had
 * animated keyframes with "t"/"s" but no "e" (end value) or "i"/"o" (bezier
 * easing) — fields the native rlottie C++ parser assumes are present for
 * every non-final keyframe of an animated property. Missing them isn't a
 * Java exception; it's a native SIGSEGV, which no try/catch anywhere in
 * Java code can ever catch — it kills the whole process.
 *
 * The only real defense is to never hand a file like that to the native
 * layer in the first place. This does a cheap, pure-Java structural check
 * before every {@code RLottieViewWrapper.loadFromFile()} call in the
 * reaction picker. It's deliberately conservative: if the file doesn't look
 * completely well-formed, we say no and the caller falls back to the
 * unicode glyph instead of ever touching native code.
 */
public final class LottieJsonValidator {

    private static final String TAG = "LottieJsonValidator";

    private LottieJsonValidator() {}

    /** @return true only if this file is safe to hand to native RLottie. */
    public static boolean isSafeToLoad(File f) {
        if (f == null || !f.exists() || f.length() == 0) return false;
        try {
            JSONObject root = new JSONObject(readFile(f));
            if (!root.has("v") || !root.has("layers")) return false;
            JSONArray layers = root.optJSONArray("layers");
            if (layers == null || layers.length() == 0) return false;
            return checkNode(root);
        } catch (Exception e) {
            Log.w(TAG, "lottie validation failed for " + f.getName(), e);
            return false;
        }
    }

    /**
     * Recursively finds every "animated property" object (shape:
     * {"a":1,"k":[ {t, s, ...}, ... ]}) anywhere in the tree — layer
     * transforms, shape transforms, path/fill/stroke properties can all be
     * animated the same way — and confirms every non-final keyframe carries
     * "e", "i", and "o". Any miss anywhere → whole file rejected.
     */
    private static boolean checkNode(Object node) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.optInt("a", -1) == 1 && obj.opt("k") instanceof JSONArray) {
                JSONArray kf = obj.getJSONArray("k");
                // Only "animated keyframe list" shape (objects with "t"/"s"),
                // not a plain animated scalar/array — those don't apply here.
                if (kf.length() > 0 && kf.opt(0) instanceof JSONObject
                        && ((JSONObject) kf.opt(0)).has("t")) {
                    for (int i = 0; i < kf.length() - 1; i++) {
                        JSONObject k = kf.getJSONObject(i);
                        if (!k.has("e") || !k.has("i") || !k.has("o") || !k.has("s")) {
                            return false;
                        }
                    }
                }
            }
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                if (!checkNode(obj.get(keys.next()))) return false;
            }
            return true;
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                if (!checkNode(arr.get(i))) return false;
            }
            return true;
        }
        return true; // scalars are always fine
    }

    private static String readFile(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) >= 0) off += n;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }
}
