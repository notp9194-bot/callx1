package com.callx.app.stickers;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * v578 — single place for the "Add Yours" chain logic (status viewer, chain screen, composer, reels).
 *
 * Data (written ONLY by Cloud Functions, see functions/index.js onStatusCreatedAddYours):
 *   addYoursChains/{chainId}             = { prompt, originUid, originName, count, template?, ... }
 *   addYoursChainEntries/{chainId}/{uid} = { statusId, ts, expiresAt, listed, isOrigin, + name/photo/thumb only if listed }
 */
public final class AddYoursHelper {

    public static final String CHAINS  = "addYoursChains";
    public static final String ENTRIES = "addYoursChainEntries";
    /** Lives in feature-status; core can't reference it directly, so it's started by class name. */
    public static final String CHAIN_ACTIVITY = "com.callx.app.viewer.AddYoursChainActivity";
    public static final String EXTRA_CHAIN_ID = "chainId";
    public static final String EXTRA_PROMPT   = "prompt";
    /** v579: origin/template hints so the chain screen can still join a legacy chain whose chain doc doesn't exist yet. */
    public static final String EXTRA_ORIGIN_UID  = "originUid";
    public static final String EXTRA_ORIGIN_NAME = "originName";
    public static final String EXTRA_TEMPLATE    = "templateJson";

    private AddYoursHelper() {}

    /** Localised "Adding to X's prompt" text shared by the viewer, the chain screen and reels. */
    public static String adding(@NonNull Context ctx, @Nullable String originName) {
        return (originName != null && !originName.isEmpty())
                ? ctx.getString(com.callx.app.core.R.string.add_yours_toast_adding_named, originName)
                : ctx.getString(com.callx.app.core.R.string.add_yours_toast_adding_their);
    }

    // ── ids ──────────────────────────────────────────────────────────────

    /** New chain id (Firebase push key — generated locally, works offline). */
    public static String newChainId() {
        String k = null;
        try { k = FirebaseUtils.db().getReference(CHAINS).push().getKey(); } catch (Exception ignored) {}
        if (k != null && !k.isEmpty()) return k;
        return "c" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(new java.util.Random().nextInt(1 << 30), 36);
    }

    /**
     * Chain id for a tapped sticker. New stickers carry one; stickers created before v578 don't, so a
     * deterministic id is derived (every viewer of the same legacy chain computes the same value).
     */
    public static String resolveChainId(@NonNull StatusStickerOverlayView s, String ownerUid, String statusId) {
        String id = s.getAddYoursChainId();
        if (id != null && !id.isEmpty()) return id;
        String originUid = s.getAddYoursOriginUid();
        String base = (originUid == null || originUid.isEmpty())
                ? ownerUid + "_" + statusId
                : originUid + "_p" + Integer.toHexString(String.valueOf(s.getAddYoursPrompt()).hashCode());
        String clean = ("L_" + base).replaceAll("[^A-Za-z0-9_-]", "_");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    // ── composer hand-off ────────────────────────────────────────────────

    /** Sticker JSON handed to NewStatusActivity so the joiner's story continues the same chain (+ same template). */
    public static String buildJoinJson(String prompt, String chainId, String originUid, String originName,
                                       @Nullable String templateJson) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "addyours");
        o.put("prompt", prompt != null ? prompt : "");
        o.put("chainId", chainId);
        o.put("originUid", originUid != null ? originUid : "");
        o.put("originName", originName != null ? originName : "");
        if (templateJson != null && !templateJson.isEmpty()) {
            JSONObject t = new JSONObject(templateJson);
            o.put("template", t);
            // template also remembers where/how big the original sticker sat → same spot for the joiner
            if (t.has("scale"))       o.put("scale", t.optDouble("scale", 1.0));
            if (t.has("rotationDeg")) o.put("rotationDeg", t.optDouble("rotationDeg", 0.0));
            if (t.has("posXRatio") && t.has("posYRatio")) {
                o.put("posXRatio", t.optDouble("posXRatio", -1));
                o.put("posYRatio", t.optDouble("posYRatio", -1));
            }
        }
        return o.toString();
    }

    // ── reads ────────────────────────────────────────────────────────────

    public interface JoinedCallback { void onResult(boolean joined); }

    /** True if {uid} already has a still-live entry in this chain (one join per user per chain). */
    public static void checkJoined(String chainId, String uid, @NonNull final JoinedCallback cb) {
        if (chainId == null || chainId.isEmpty() || uid == null || uid.isEmpty()) { cb.onResult(false); return; }
        FirebaseUtils.db().getReference(ENTRIES).child(chainId).child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        boolean joined = false;
                        if (s.exists()) {
                            Long exp = s.child("expiresAt").getValue(Long.class);
                            joined = exp == null || exp > System.currentTimeMillis();
                        }
                        cb.onResult(joined);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(false); }
                });
    }

    /** Live "N added" label on the sticker. Returns a detach Runnable — call it when the status changes. */
    public static Runnable attachCount(final String chainId, @NonNull final StatusStickerOverlayView sticker) {
        if (chainId == null || chainId.isEmpty()) return () -> {};
        final DatabaseReference ref = FirebaseUtils.db().getReference(CHAINS).child(chainId).child("count");
        final ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Long c = s.getValue(Long.class);
                sticker.setAddYoursCount(c != null ? c : 0L);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        };
        ref.addValueEventListener(l);
        return () -> ref.removeEventListener(l);
    }

    // ── navigation ───────────────────────────────────────────────────────

    /** Opens the chain screen (prompt, count, grid of everyone who added theirs). */
    public static void openChain(@NonNull Context ctx, String chainId, @Nullable String prompt) {
        openChain(ctx, chainId, prompt, null, null, null);
    }

    /**
     * Same, plus the sticker's origin/template as a fallback: pre-v578 chains have no chain doc until the first
     * person joins, so the chain screen needs these to offer "Add Yours" instead of loading forever.
     */
    public static void openChain(@NonNull Context ctx, String chainId, @Nullable String prompt,
                                 @Nullable String originUid, @Nullable String originName, @Nullable String templateJson) {
        if (chainId == null || chainId.isEmpty()) return;
        try {
            Intent i = new Intent();
            i.setClassName(ctx.getPackageName(), CHAIN_ACTIVITY);
            i.putExtra(EXTRA_CHAIN_ID, chainId);
            if (prompt != null) i.putExtra(EXTRA_PROMPT, prompt);
            if (originUid != null && !originUid.isEmpty()) i.putExtra(EXTRA_ORIGIN_UID, originUid);
            if (originName != null && !originName.isEmpty()) i.putExtra(EXTRA_ORIGIN_NAME, originName);
            if (templateJson != null && !templateJson.isEmpty()) i.putExtra(EXTRA_TEMPLATE, templateJson);
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) { /* chain screen unavailable — sticker stays inert */ }
    }

    /**
     * Reels tap on an Add Yours sticker (was copy-pasted in ReelPlayerFragment + ReelPhotoSlideshowAdapter).
     * Reels don't take part in chains (chains live in the status tree), so this keeps the old behaviour:
     * toast naming whose prompt it is, then {@code openCamera}.
     */
    public static void onReelTap(@NonNull Context ctx, @NonNull StatusStickerOverlayView sticker, @NonNull Runnable openCamera) {
        String prompt = sticker.getAddYoursPrompt();
        if (prompt == null || prompt.isEmpty()) return;
        String origin = sticker.getAddYoursOriginName();
        Toast.makeText(ctx, adding(ctx, origin), Toast.LENGTH_SHORT).show();
        try { openCamera.run(); } catch (Exception ignored) {}
    }
}
