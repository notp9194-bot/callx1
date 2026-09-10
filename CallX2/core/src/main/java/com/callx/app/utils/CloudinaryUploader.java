package com.callx.app.utils;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.MimeTypeMap;
import okhttp3.*;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
public class CloudinaryUploader {
    private static final String TAG = "Cloudinary";

    /**
     * PERF FIX (WhatsApp-style lazy media, missing-thumbnail case): derives
     * a lightweight preview URL from any Cloudinary secure_url WITHOUT any
     * extra upload or network round trip — Cloudinary supports on-the-fly
     * transformations by inserting a segment right after "/upload/" in the
     * URL path (e.g. "w_200,h_200,c_fill,q_auto"). Cloudinary generates and
     * CDN-caches that resized variant the first time it's requested.
     *
     * Root cause this fixes: messages where thumbnailUrl never got set
     * (thumb upload step failed, or an older client sent the message
     * without a dual-upload) were falling back to loading the RAW full-
     * resolution mediaUrl for the chat bubble — a full-size download for
     * every such bubble, on chat open / scroll, not just on tap. Deriving
     * a small transformed URL here instead means the bubble NEVER needs to
     * pull full-res bytes just to render a thumbnail, even when the real
     * pre-generated thumbnailUrl is missing.
     *
     * No-op (returns the original URL unchanged) for any non-Cloudinary
     * URL, or a URL that doesn't contain "/upload/" — callers always get
     * back a usable URL either way.
     */
    public static String deriveThumbUrl(String secureUrl, int size) {
        return deriveThumbUrl(secureUrl, size, "auto");
    }

    /**
     * Same as deriveThumbUrl(url, size) but with an explicit Cloudinary
     * delivery format instead of "f_auto". f_auto is a per-request guess
     * (content negotiation off the request's Accept header) — it's usually
     * WebP/AVIF on modern devices but isn't guaranteed, so callers that want
     * a predictable, always-WebP payload size (e.g. a grid pre-computing an
     * expected byte budget) should pass "webp" explicitly here instead.
     */
    public static String deriveThumbUrl(String secureUrl, int size, String format) {
        if (secureUrl == null || secureUrl.isEmpty()) return secureUrl;
        String marker = "/upload/";
        int idx = secureUrl.indexOf(marker);
        if (idx < 0) return secureUrl; // not a Cloudinary delivery URL we recognize — use as-is
        String f = (format == null || format.isEmpty()) ? "auto" : format;
        String transform = "w_" + size + ",h_" + size + ",c_fill,q_auto,f_" + f + "/";
        return secureUrl.substring(0, idx + marker.length())
                + transform
                + secureUrl.substring(idx + marker.length());
    }

    /**
     * PERF FIX (reels player — codec forcing): derives a video delivery URL
     * that pins Cloudinary's video codec transformation (vc_<codec>) so the
     * player pulls an AV1/HEVC-encoded stream instead of whatever default
     * (often H.264) Cloudinary would otherwise pick. AV1/HEVC give the same
     * visual quality at roughly 30-50% less bandwidth than H.264, so reel
     * open → first-frame time drops and less mobile data is used per view.
     *
     * No-op (returns the original URL unchanged) for any non-Cloudinary URL,
     * or one that doesn't contain "/upload/" — callers always get back a
     * playable URL either way, and a device that can't hardware-decode the
     * requested codec should pass "auto" instead (see CodecSupport).
     */
    public static String deriveVideoCodecUrl(String secureUrl, String codec) {
        if (secureUrl == null || secureUrl.isEmpty()) return secureUrl;
        if (codec == null || codec.isEmpty()) return secureUrl;
        String marker = "/upload/";
        int idx = secureUrl.indexOf(marker);
        if (idx < 0) return secureUrl;
        String transform = "vc_" + codec + "/";
        return secureUrl.substring(0, idx + marker.length())
                + transform
                + secureUrl.substring(idx + marker.length());
    }

    public interface UploadCallback {
        void onSuccess(Result result);
        void onError(String message);
        /** Called periodically with upload progress (0–100). Default no-op. */
        default void onProgress(int percent) {}
    }

    /** Avatar dual-upload callback — thumb pehle, full baad */
    public interface AvatarUploadCallback {
        /** thumbUrl → Firebase mein seedha save karo, UI update karo */
        void onThumbReady(String thumbUrl);
        /** photoUrl → Firebase mein save karo */
        void onFullReady(String photoUrl);
        void onError(String message);
    }
    public static class Result {
        public String secureUrl;
        public int    width;
        public int    height;
        public String publicId;
        public String resourceType;
        public String format;
        public Long bytes;
        public Long durationMs;
        public String thumbnailUrl;
    }
    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build();

    /** resource_type: image | video | raw | auto. Use 'raw' for PDF/docs. */
    public static void upload(Context ctx, Uri uri, String folder,
                              String resourceType, UploadCallback cb) {
        upload(ctx, uri, folder, resourceType, null, cb);
    }

    /**
     * Same as {@link #upload(Context, Uri, String, String, UploadCallback)}
     * but with an explicit {@code fileNameHint} (e.g. "voice.m4a",
     * "full_123.webp") used to determine the upload's filename/extension
     * DIRECTLY, bypassing MIME sniffing on {@code uri}.
     *
     * FIX (WhatsApp-level, "resources with extension bin are not allowed"):
     * Media E2E callers (voice notes, picked audio, encrypted image/video
     * thumbnails) don't upload the original file — they encrypt it to a
     * throwaway temp file first (named "<original>.enc") and upload THAT
     * as resource_type=raw. Neither ContentResolver.getType() nor the
     * ".enc"-suffixed path extension resolve to any known MIME type, so
     * every single one of those uploads fell through to the
     * filename="upload.bin" fallback below — and Cloudinary's account-level
     * raw-upload security settings reject the "bin" extension outright with
     * a 400. This was silent before the sibling FIX (see the 400-reason
     * surfacing below) started showing the real Cloudinary error message —
     * that's the exact error the person is now seeing on every voice-note
     * recording AND every attachment-sheet audio pick, since both funnel
     * through the same E2E-encrypted "audio" upload path in
     * ChatMediaController#doUpload.
     *
     * Passing the ORIGINAL (pre-encryption) filename here as a hint fixes
     * it: the extension is only used for Cloudinary's allowed-format check
     * and delivery URL, never to interpret the actual bytes — safe for
     * resource_type=raw ciphertext, which Cloudinary stores/serves as an
     * opaque blob regardless of what the extension says.
     *
     * Pass null for the normal (non-E2E) path — behaves exactly as before.
     */
    public static void upload(Context ctx, Uri uri, String folder,
                              String resourceType, String fileNameHint, UploadCallback cb) {
        upload(ctx, uri, folder, resourceType, fileNameHint, false, cb);
    }

    /**
     * Same as {@link #upload(Context, Uri, String, String, String, UploadCallback)}
     * but with {@code alreadyCompressed} to skip readBytes()'s automatic
     * image re-compression.
     *
     * FIX (reel-comment image send failing 100% since ImageCompressor was
     * added to that flow): readBytes() below unconditionally re-compresses
     * ANY image/* uri via MediaCompressor.compressImage(), which ALWAYS
     * outputs JPEG bytes (its useWebP param is hardcoded false there) —
     * but the filename/Content-Type sent to Cloudinary is derived
     * separately, from the URI's own extension/mime, computed BEFORE
     * knowing readBytes() would re-encode it. For a plain gallery JPEG
     * this coincidentally matched (jpeg in → jpeg out). But
     * ReelCommentFragment now hands this a Uri.fromFile() pointing at an
     * ImageCompressor-produced ".webp" file: the mime/extension resolve to
     * "image/webp", while the bytes actually uploaded are re-encoded JPEG
     * — Cloudinary receives a file tagged webp whose content parses as
     * JPEG and rejects it. Passing alreadyCompressed=true here skips the
     * redundant/mismatching re-compression entirely: the already-optimized
     * bytes go up as-is, tagged with their real (matching) mime.
     */
    public static void upload(Context ctx, Uri uri, String folder,
                              String resourceType, String fileNameHint,
                              boolean alreadyCompressed, UploadCallback cb) {
        // PERF: shared bounded pool instead of a raw `new Thread()` per
        // upload — see MediaUploadExecutor's class doc (unbounded thread
        // creation under a big multi-select send is wasted overhead, not
        // extra speed, since uplink bandwidth is the real bottleneck).
        MediaUploadExecutor.execute(() -> {
            try {
                byte[] bytes = readBytes(ctx, uri, alreadyCompressed);
                if (bytes == null || bytes.length == 0) {
                    post(cb, null, "Empty file");
                    return;
                }

                // ── Content-hash dedup (WhatsApp-style instant re-send/forward) ──
                // Hash the EXACT bytes about to be uploaded (post-compression —
                // what Cloudinary would actually receive). Check the FREE,
                // local-only tiers (in-memory → Room) first — a hit here means
                // this exact file was already uploaded from this device, so
                // skip everything else and return immediately. See
                // MediaDedupManager's class doc for why the server-wide tier
                // is deliberately NOT checked here — it rides along on the
                // /cloudinary/sign call below instead of costing its own
                // round trip. Not used for E2E paths (ciphertext is unique
                // per key, so it never dedups).
                final String contentHash = MediaHashUtil.sha256(bytes);
                if (contentHash != null) {
                    MediaDedupManager.Result cached = MediaDedupManager.lookupLocal(ctx, contentHash);
                    if (cached != null) {
                        Log.d(TAG, "Dedup hit (" + cached.source + "), upload skipped for hash "
                                + contentHash.substring(0, 8) + "…");
                        post(cb, cached.toUploadResult(), null);
                        return;
                    }
                }
                // Hint takes priority — it names the REAL original file
                // (voice.m4a, full_123.webp, ...), not the temp .enc blob
                // we're actually reading bytes from.
                String hintExt = null;
                if (fileNameHint != null && !fileNameHint.isEmpty()) {
                    int dot = fileNameHint.lastIndexOf('.');
                    if (dot >= 0 && dot < fileNameHint.length() - 1) {
                        hintExt = fileNameHint.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                    }
                }
                String mime = (hintExt != null) ? null : ctx.getContentResolver().getType(uri);
                // FIX: Android's built-in MimeTypeMap has no entry for the
                // "m4a" extension (AudioRecorderHelper always records to
                // .m4a) — ContentResolver.getType() on a FileProvider Uri
                // for a .m4a file falls straight through to null here, so
                // every voice-note/audio upload was silently sent as
                // filename="upload.bin" + Content-Type
                // "application/octet-stream" instead of "upload.m4a" /
                // "audio/mp4". Same class of gap can hit picked audio
                // files with other extensions MimeTypeMap doesn't know
                // (.opus, .3gp) — cover those explicitly too rather than
                // relying on the resolver alone.
                if (mime == null && hintExt == null) {
                    String guessedExt = null;
                    String path = uri.getPath();
                    if (path != null) {
                        int dot = path.lastIndexOf('.');
                        if (dot >= 0) guessedExt = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                    }
                    if ("m4a".equals(guessedExt)) mime = "audio/mp4";
                    else if ("opus".equals(guessedExt)) mime = "audio/opus";
                    else if ("3gp".equals(guessedExt)) mime = "audio/3gpp";
                }
                String ext = hintExt;
                if (ext == null) {
                    if (mime == null) mime = "application/octet-stream";
                    ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                    if (ext == null || ext.isEmpty()) {
                        // Same MimeTypeMap gap in reverse — "audio/mp4" doesn't
                        // reliably map back to "m4a" on every API level either.
                        ext = "audio/mp4".equals(mime) ? "m4a"
                            : "audio/opus".equals(mime) ? "opus"
                            : "audio/3gpp".equals(mime) ? "3gp"
                            : "bin";
                    }
                } else if (mime == null) {
                    // We trust the hint's extension for naming even when we
                    // can't resolve a matching MIME (e.g. "enc" would never
                    // map — but we already stripped that off by using the
                    // ORIGINAL extension instead). Best-effort MIME lookup;
                    // falls back to a generic binary content-type, which is
                    // fine since resource_type=raw doesn't validate content
                    // against it.
                    String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    if (guessed != null) mime = guessed;
                    else if ("m4a".equals(ext)) mime = "audio/mp4";
                    else if ("opus".equals(ext)) mime = "audio/opus";
                    else if ("3gp".equals(ext)) mime = "audio/3gpp";
                    else mime = "application/octet-stream";
                }
                String filename = "upload." + ext;
                final String rType = (resourceType == null || resourceType.isEmpty())
                    ? "auto" : resourceType;

                // Step 1 — sign (PIGGYBACKS the server-wide dedup check — see
                // MediaDedupManager's class doc). Sending the content hash
                // here means the server can answer "someone already has this
                // exact file, here's the URL" in the SAME response instead of
                // needing a separate /media/dedup-lookup round trip first —
                // one network call now covers both a possible dedup hit AND
                // (on a miss) the signature this upload needed anyway.
                JSONObject payload = new JSONObject()
                    .put("folder", folder == null ? "callx" : folder)
                    .put("resource_type", rType);
                if (contentHash != null) payload.put("hash", contentHash);
                Request signReq = new Request.Builder()
                    .url(Constants.SERVER_URL + "/cloudinary/sign")
                    .post(RequestBody.create(payload.toString(),
                        MediaType.parse("application/json")))
                    .build();
                Response signRes = client.newCall(signReq).execute();
                String signBody = signRes.body() != null ? signRes.body().string() : "";
                signRes.close();
                if (!signRes.isSuccessful()) {
                    Log.e(TAG, "Sign failed (" + signRes.code() + "): " + signBody);
                    post(cb, null, "Server error " + signRes.code() +
                        ". Image bhejne mein dikkat. Server pe Cloudinary configure nahi hai shayad.");
                    return;
                }
                JSONObject signJson = new JSONObject(signBody);

                // Server-wide dedup hit — someone else already uploaded this
                // exact file. Skip the multipart upload entirely; just warm
                // our local tiers so the NEXT identical send from this
                // device is instant even without asking the server again.
                if (signJson.optBoolean("dedup", false)) {
                    MediaDedupManager.Result serverHit =
                        MediaDedupManager.fromSignResponse(signJson.optJSONObject("result"));
                    if (serverHit != null) {
                        Log.d(TAG, "Dedup hit (server), upload skipped for hash "
                                + (contentHash != null ? contentHash.substring(0, 8) : "?") + "…");
                        if (contentHash != null) {
                            MediaDedupManager.register(ctx, contentHash, serverHit.toUploadResult(), folder);
                        }
                        post(cb, serverHit.toUploadResult(), null);
                        return;
                    }
                    // Malformed dedup payload — fall through and upload normally rather than fail the send.
                }

                String signature = signJson.getString("signature");
                String timestamp = signJson.getString("timestamp");
                String apiKey    = signJson.getString("api_key");
                String cloudName = signJson.optString("cloud_name",
                    Constants.CLOUDINARY_CLOUD_NAME);
                String f         = signJson.optString("folder", "callx");
                String rt        = signJson.optString("resource_type", rType);

                // Step 2 — direct upload
                MultipartBody.Builder mp = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename,
                        RequestBody.create(bytes, MediaType.parse(mime)))
                    .addFormDataPart("api_key", apiKey)
                    .addFormDataPart("timestamp", timestamp)
                    .addFormDataPart("signature", signature)
                    .addFormDataPart("folder", f);
                String upUrl = "https://api.cloudinary.com/v1_1/" +
                    cloudName + "/" + rt + "/upload";
                Request upReq = new Request.Builder()
                    .url(upUrl).post(mp.build()).build();
                Response upRes = client.newCall(upReq).execute();
                String body = upRes.body() != null ? upRes.body().string() : "";
                upRes.close();
                if (!upRes.isSuccessful()) {
                    // FIX: Cloudinary/server always sends WHY it rejected the
                    // upload in the JSON body — {"error":{"message":"..."}}
                    // — but that message was being logged and then thrown
                    // away, leaving only a bare "Upload failed (400)" for
                    // both the user and the logs. That's why a 400 on audio
                    // sends looked like a mystery: the actual reason
                    // ("Invalid resource_type raw for this file", "raw
                    // delivery disabled for this account", a bad
                    // signature, etc.) never surfaced anywhere. Now it does.
                    String reason = null;
                    try {
                        JSONObject errJson = new JSONObject(body);
                        JSONObject errObj = errJson.optJSONObject("error");
                        if (errObj != null) reason = errObj.optString("message", null);
                    } catch (Exception ignored) { /* body wasn't JSON — fall through to raw body below */ }
                    Log.e(TAG, "Upload failed (" + upRes.code() + "): " + body);
                    post(cb, null, "Upload failed (" + upRes.code() + ")"
                        + (reason != null && !reason.isEmpty() ? ": " + reason : ""));
                    return;
                }
                JSONObject upJson = new JSONObject(body);
                Result r = new Result();
                r.secureUrl    = upJson.optString("secure_url",
                    upJson.optString("url"));
                r.publicId     = upJson.optString("public_id");
                r.resourceType = upJson.optString("resource_type", rt);
                r.format       = upJson.optString("format");
                if (upJson.has("bytes")) r.bytes = upJson.getLong("bytes");
                if (upJson.has("duration"))
                    r.durationMs = (long)(upJson.getDouble("duration") * 1000);
                // Cloudinary returns eager[0].secure_url as the video thumbnail when
                // an eager transformation is configured on the server side.
                if (upJson.has("eager")) {
                    org.json.JSONArray eager = upJson.optJSONArray("eager");
                    if (eager != null && eager.length() > 0) {
                        r.thumbnailUrl = eager.getJSONObject(0).optString("secure_url", null);
                    }
                }
                // Fallback: use the poster frame URL Cloudinary sometimes provides
                if (r.thumbnailUrl == null || r.thumbnailUrl.isEmpty()) {
                    String rawUrl = r.secureUrl;
                    if (rawUrl != null && "video".equals(r.resourceType)) {
                        r.thumbnailUrl = rawUrl.replaceFirst("\\.[^.]+$", ".jpg");
                    }
                }
                if (r.secureUrl == null || r.secureUrl.isEmpty()) {
                    post(cb, null, "No URL in response");
                    return;
                }
                // Register this hash → URL so the NEXT identical file (this
                // device or any other) hits the dedup cache instead of
                // re-uploading. Fully non-blocking — see register()'s doc —
                // so this never delays the success callback below.
                if (contentHash != null) {
                    MediaDedupManager.register(ctx, contentHash, r, f);
                }
                post(cb, r, null);
            } catch (Exception e) {
                Log.e(TAG, "Upload error", e);
                post(cb, null, e.getMessage() == null ? "Upload error" : e.getMessage());
            }
        });
    }
    private static byte[] readBytes(Context ctx, Uri uri) throws IOException {
        return readBytes(ctx, uri, false);
    }

    /** @param alreadyCompressed true = skip the MediaCompressor re-encode
     *  below entirely (uri already points at an ImageCompressor/similar
     *  pre-compressed file) — see the alreadyCompressed upload() overload's
     *  doc for the mismatched-mime bug this avoids. */
    private static byte[] readBytes(Context ctx, Uri uri, boolean alreadyCompressed) throws IOException {
        // Images: compress before upload (resize + JPEG 80%)
        // GIF ko compress MAT karo — MediaCompressor JPEG banata hai, animation destroy ho jaati hai
        String mime = ctx.getContentResolver().getType(uri);
        if (!alreadyCompressed && mime != null && mime.startsWith("image/") && !"image/gif".equals(mime)) {
            byte[] compressed = MediaCompressor.compressImage(ctx, uri);
            if (compressed != null && compressed.length > 0) {
                Log.d(TAG, "Image compressed for upload: " + compressed.length / 1024 + " KB");
                return compressed;
            }
        }
        // Other types: read as-is
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
    private static void post(UploadCallback cb, Result r, String err) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (r != null) cb.onSuccess(r); else cb.onError(err);
        });
    }
    /**
     * Avatar dual-upload:
     *   Step 1 → thumbnail (100×100 WebP 60%) upload → onThumbReady callback
     *   Step 2 → full photo (800×800 JPEG 85%) upload → onFullReady callback
     *
     * Usage:
     *   CloudinaryUploader.uploadAvatar(ctx, uri, new AvatarUploadCallback() { ... });
     */
    public static void uploadAvatar(Context ctx, Uri uri, AvatarUploadCallback cb) {
        new Thread(() -> {
            try {
                // ── Step 1: Thumbnail ─────────────────────────────────────
                byte[] thumbBytes = MediaCompressor.compressImageWithQuality(
                    ctx, uri, 100, 60, true);   // 100px, WebP 60%
                if (thumbBytes == null || thumbBytes.length == 0) {
                    post(cb, null, null, "Thumbnail compress failed");
                    return;
                }
                String thumbUrl = uploadBytes(ctx, thumbBytes, "image/webp",
                    "thumb.webp", "callx/avatars/thumbs", "image");
                if (thumbUrl == null) {
                    post(cb, null, null, "Thumbnail upload failed");
                    return;
                }
                post(cb, thumbUrl, null, null);   // onThumbReady fired

                // ── Step 2: Full photo ────────────────────────────────────
                byte[] fullBytes = MediaCompressor.compressImageWithQuality(
                    ctx, uri, 800, 85, false);   // 800px, JPEG 85%
                if (fullBytes == null || fullBytes.length == 0) {
                    post(cb, null, null, "Full photo compress failed");
                    return;
                }
                String photoUrl = uploadBytes(ctx, fullBytes, "image/jpeg",
                    "photo.jpg", "callx/avatars", "image");
                if (photoUrl == null) {
                    post(cb, null, null, "Full photo upload failed");
                    return;
                }
                postFull(cb, photoUrl);           // onFullReady fired

            } catch (Exception e) {
                Log.e(TAG, "uploadAvatar error", e);
                post(cb, null, null, e.getMessage() != null ? e.getMessage() : "Upload error");
            }
        }).start();
    }

    /** Internal: byte[] → Cloudinary → secureUrl. Returns null on failure. */
    private static String uploadBytes(Context ctx, byte[] bytes, String mime,
                                      String filename, String folder,
                                      String resourceType) {
        try {
            // Sign
            JSONObject payload = new JSONObject()
                .put("folder", folder)
                .put("resource_type", resourceType);
            Request signReq = new Request.Builder()
                .url(Constants.SERVER_URL + "/cloudinary/sign")
                .post(RequestBody.create(payload.toString(),
                    MediaType.parse("application/json")))
                .build();
            Response signRes = client.newCall(signReq).execute();
            String signBody = signRes.body() != null ? signRes.body().string() : "";
            signRes.close();
            if (!signRes.isSuccessful()) return null;

            JSONObject signJson = new JSONObject(signBody);
            String signature = signJson.getString("signature");
            String timestamp = signJson.getString("timestamp");
            String apiKey    = signJson.getString("api_key");
            String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
            String f         = signJson.optString("folder", folder);
            String rt        = signJson.optString("resource_type", resourceType);

            // Upload
            MultipartBody.Builder mp = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename,
                    RequestBody.create(bytes, MediaType.parse(mime)))
                .addFormDataPart("api_key", apiKey)
                .addFormDataPart("timestamp", timestamp)
                .addFormDataPart("signature", signature)
                .addFormDataPart("folder", f);
            String upUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/" + rt + "/upload";
            Request upReq = new Request.Builder().url(upUrl).post(mp.build()).build();
            Response upRes = client.newCall(upReq).execute();
            String upBody = upRes.body() != null ? upRes.body().string() : "";
            upRes.close();
            if (!upRes.isSuccessful()) return null;

            JSONObject upJson = new JSONObject(upBody);
            String url = upJson.optString("secure_url", upJson.optString("url"));
            return (url == null || url.isEmpty()) ? null : url;

        } catch (Exception e) {
            Log.e(TAG, "uploadBytes error: " + e.getMessage());
            return null;
        }
    }

    /**
     * FIX (CDN edge-side invalidation — AvatarUrlBuilder version-param
     * upgrade #4): the client-side {@code &v=<avatarVersion>} query param
     * (see AvatarUrlBuilder's class doc) only ever fixes a STALE CACHE-KEY
     * collision on THIS device — it changes what Glide asks for, it can
     * never reach into Cloudinary and purge what's already sitting at the
     * CDN edge under the OLD avatar's URL. Every avatar upload gets a
     * brand-new Cloudinary public_id (see uploadAvatar above), so the new
     * URL never collides with the old one at the CDN either — but the OLD
     * public_id's transformed variants (every AvatarSizeTier × density-
     * bucket × AVIF/WebP combination anyone ever requested — see
     * AvatarUrlBuilder) stay live at the edge indefinitely with no TTL,
     * still fully servable to anyone still holding that URL: a stale Room
     * row on a device that hasn't synced the new avatarVersion yet, an open
     * share link, a scraped profile page, etc.
     *
     * FIX (batched invalidation): a single avatar change touches at LEAST
     * two public_ids (thumb + full — see uploadAvatar's dual-upload), and
     * Cloudinary's own Admin API — e.g. api.delete_resources(publicIds[],
     * {invalidate: true}) — natively accepts an ARRAY of up to 100
     * public_ids per call and invalidates every transformed size/format
     * variant of each in that one request. Previously this fired one
     * /cloudinary/invalidate POST per URL (2 round trips for thumb+full,
     * more if a caller ever passed additional old sizes) — now every
     * public_id from this call is collected first and sent as ONE POST with
     * a "public_ids" array, so the backend makes exactly one Admin API call
     * no matter how many old avatar variants need purging. Duplicate
     * public_ids (defensive — thumb/full should never actually collide) are
     * deduped via the LinkedHashSet below before the request goes out.
     *
     * Fire-and-forget POST to the same backend that already signs uploads
     * (see uploadBytes above — /cloudinary/sign). The Admin API secret can
     * only live on the server, which is why this is a request TO the
     * backend rather than a direct Cloudinary call from the app (same
     * reason /cloudinary/sign exists instead of signing uploads on-device).
     *
     * Best-effort only, called AFTER the new avatar has already fully
     * uploaded and saved — a failure here never blocks or fails the avatar
     * change itself, it just means the old variants linger at the edge
     * until their eventual TTL/LRU eviction instead of purging immediately.
     */
    public static void invalidateAvatarEdgeCache(Context ctx, String... oldSecureUrls) {
        if (oldSecureUrls == null || oldSecureUrls.length == 0) return;
        new Thread(() -> {
            // LinkedHashSet: dedupes AND keeps a stable order for logging —
            // not that order matters to Cloudinary, which invalidates the
            // whole batch atomically regardless of array order.
            java.util.LinkedHashSet<String> publicIds = new java.util.LinkedHashSet<>();
            for (String oldUrl : oldSecureUrls) {
                String publicId = publicIdFromUrl(oldUrl);
                if (publicId != null) publicIds.add(publicId);
            }
            if (publicIds.isEmpty()) return;

            try {
                org.json.JSONArray idsArray = new org.json.JSONArray(publicIds);
                JSONObject payload = new JSONObject()
                    .put("public_ids", idsArray)   // array → ONE Admin API call server-side, not one per id
                    .put("resource_type", "image")
                    .put("invalidate", true);
                Request req = new Request.Builder()
                    .url(Constants.SERVER_URL + "/cloudinary/invalidate")
                    .post(RequestBody.create(payload.toString(),
                        MediaType.parse("application/json")))
                    .build();
                Response res = client.newCall(req).execute();
                if (!res.isSuccessful()) {
                    Log.w(TAG, "Batch edge invalidate failed (" + res.code() + ") for "
                        + publicIds.size() + " id(s): " + publicIds);
                }
                res.close();
            } catch (Exception e) {
                // Best-effort — old variants just age out at the edge naturally instead.
                Log.w(TAG, "Batch edge invalidate error for " + publicIds.size()
                    + " id(s): " + e.getMessage());
            }
        }).start();
    }

    /**
     * Extracts a Cloudinary public_id (folder/name, no extension, no
     * "v<timestamp>/" version segment) from a delivery URL — the identifier
     * the Admin API needs, as opposed to the delivery URL AvatarUrlBuilder
     * builds transforms on top of. Returns null for anything that doesn't
     * look like a Cloudinary "/upload/" URL.
     */
    private static String publicIdFromUrl(String secureUrl) {
        if (secureUrl == null || secureUrl.isEmpty()) return null;
        String marker = "/upload/";
        int idx = secureUrl.indexOf(marker);
        if (idx < 0) return null;
        String rest = secureUrl.substring(idx + marker.length());
        if (rest.matches("^v\\d+/.*")) {
            rest = rest.substring(rest.indexOf('/') + 1); // strip Cloudinary's auto version segment
        }
        int dot = rest.lastIndexOf('.');
        return dot > 0 ? rest.substring(0, dot) : rest;
    }

    private static void post(AvatarUploadCallback cb, String thumbUrl,
                             String photoUrl, String err) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (err != null)       cb.onError(err);
            else if (thumbUrl != null) cb.onThumbReady(thumbUrl);
        });
    }
    private static void postFull(AvatarUploadCallback cb, String photoUrl) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onFullReady(photoUrl));
    }

    public CloudinaryUploader() {}

    /** Instance shim used by legacy callers that do new CloudinaryUploader().uploadFile(…). */
    public void uploadFile(android.content.Context ctx, android.net.Uri uri, String folder,
                           UploadCallback cb) {
        upload(ctx, uri, folder, "image", cb);
    }
}
