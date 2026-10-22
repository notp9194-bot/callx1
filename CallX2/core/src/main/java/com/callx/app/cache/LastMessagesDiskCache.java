package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.callx.app.models.Message;
import com.callx.app.utils.AppBgExecutor;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Persistent copy of each chat's latest messages, so the first frame of a chat can render before Room is
 * ready after a process restart.
 *
 * v2 (replaces the SharedPreferences + Gson store):
 *  - ONE small file per chat in the app cache dir — no giant prefs XML that is fully loaded on first access
 *    and fully rewritten on every save; the OS may reclaim cache files, which is fine for a warm cache and
 *    keeps them out of backups.
 *  - AES-256-GCM encrypted with a key derived from the same Keystore-backed secret as the SQLCipher DB
 *    ({@link EncryptedDbKeyStore}), so decrypted message text is no longer readable on disk in plain JSON
 *    while the real database is encrypted. If encryption is unavailable nothing is written (fail closed).
 *  - Each snapshot carries a {@code savedAt} stamp (see {@link LastMessagesCache#getAgeMs}); deleted /
 *    expired rows are never restored.
 *  - Legacy v1 prefs entries are migrated lazily on first read and then removed.
 */
public final class LastMessagesDiskCache {

    private static final String TAG = "LastMessagesDiskCache";
    private static final String LEGACY_PREFS_NAME = "last_messages_disk_cache_v1";
    private static final String LEGACY_INDEX_KEY = "__chat_keys__";
    private static final String DIR_NAME = "last_messages_v2";
    private static final String EXT = ".bin";
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_PER_CHAT = 20;
    private static final int MAX_DISK_CHATS = 50;
    private static final Gson GSON = new Gson();
    private static final Type MESSAGE_LIST_TYPE = new TypeToken<List<Message>>() {}.getType();
    private static final Object WRITE_LOCK = new Object();
    private static final Object KEY_LOCK = new Object();
    private static final SecureRandom RNG = new SecureRandom();
    /** Per-file write counter: a slower older write never overwrites a newer snapshot. */
    private static final ConcurrentHashMap<String, AtomicLong> WRITE_VERSIONS = new ConcurrentHashMap<>();
    private static volatile SecretKeySpec sKey;

    private static final class Envelope {
        int v;
        long savedAt;
        List<Message> messages;
    }

    private LastMessagesDiskCache() {}

    // ── paths / names ────────────────────────────────────────────────────────

    private static File dir(Context context) {
        File d = new File(context.getApplicationContext().getCacheDir(), DIR_NAME);
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    private static String sha1Hex(String value) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String accountPrefix(String accountUid) {
        return sha1Hex(accountUid).substring(0, 12) + "_";
    }

    private static File file(Context context, String accountUid, String chatId) {
        return new File(dir(context), accountPrefix(accountUid) + sha1Hex(chatId).substring(0, 16) + EXT);
    }

    // ── crypto ───────────────────────────────────────────────────────────────

    private static SecretKeySpec key(Context context) throws Exception {
        SecretKeySpec k = sKey;
        if (k != null) return k;
        synchronized (KEY_LOCK) {
            if (sKey == null) {
                byte[] raw = EncryptedDbKeyStore.getInstance(context).getDbKeyBytes();
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    md.update("callx.last_messages.v2".getBytes(StandardCharsets.UTF_8));
                    sKey = new SecretKeySpec(md.digest(raw), "AES");
                } finally {
                    Arrays.fill(raw, (byte) 0);
                }
            }
            return sKey;
        }
    }

    /** iv(12) || ciphertext+tag, or null when encryption is unavailable. */
    private static byte[] encrypt(Context context, byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(context), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "encrypt unavailable: " + e.getMessage());
            return null;
        }
    }

    private static byte[] decrypt(Context context, byte[] blob) {
        try {
            if (blob == null || blob.length <= 12 + 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(context), new GCMParameterSpec(128, blob, 0, 12));
            return c.doFinal(blob, 12, blob.length - 12);
        } catch (Exception e) {
            return null;   // wrong key / corrupt file -> treated as a cache miss
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    // ── legacy v1 (SharedPreferences) ────────────────────────────────────────

    private static SharedPreferences legacyPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String legacyKey(String accountUid, String chatId) {
        return "m_" + legacySafe(accountUid) + "_" + legacySafe(chatId);
    }

    private static String legacySafe(String value) {
        if (value == null || value.isEmpty()) return "_";
        return value.replace('\\', '_').replace('"', '_');
    }

    // ── read ─────────────────────────────────────────────────────────────────

    private static Envelope readEnvelope(Context context, File f) {
        if (!f.exists()) return null;
        try {
            byte[] plain = decrypt(context, readAll(f));
            if (plain == null) { //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            Envelope env = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), Envelope.class);
            if (env == null || env.v != FORMAT_VERSION || env.messages == null) return null;
            return env;
        } catch (Exception e) {
            Log.w(TAG, "read failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads one chat synchronously. The payload is intentionally tiny (maximum 20 messages), making this
     * suitable for the first frame of a chat Activity. Returns true only when a usable snapshot was restored.
     */
    public static boolean loadIntoMemory(Context context, String accountUid, String chatId) {
        if (context == null || accountUid == null || accountUid.isEmpty()
                || chatId == null || chatId.isEmpty()
                || LastMessagesCache.getInstance().has(chatId)) {
            return LastMessagesCache.getInstance().has(chatId);
        }
        try {
            long savedAt = 0L;
            List<Message> messages = null;
            Envelope env = readEnvelope(context, file(context, accountUid, chatId));
            if (env != null) {
                messages = env.messages;
                savedAt = env.savedAt;
            } else {
                // one-time migration from the v1 SharedPreferences store (age unknown -> savedAt = 0)
                SharedPreferences p = legacyPrefs(context);
                String lk = legacyKey(accountUid, chatId);
                String json = p.getString(lk, null);
                if (json != null && !json.isEmpty()) {
                    try {
                        messages = GSON.fromJson(json, MESSAGE_LIST_TYPE);
                    } catch (Exception ignored) {
                        // corrupt legacy entry -> just drop it
                    }
                    p.edit().remove(lk).apply();
                    if (messages != null && !messages.isEmpty()) saveAsync(context, accountUid, chatId, messages);
                }
            }
            if (messages == null || messages.isEmpty()) return false;
            LastMessagesCache.getInstance().seed(chatId, messages, savedAt);
            return LastMessagesCache.getInstance().has(chatId);
        } catch (Exception e) {
            Log.w(TAG, "load failed: " + e.getMessage());
            return false;
        }
    }

    /** Cheap existence check used only by background prefetch code. */
    public static boolean has(Context context, String accountUid, String chatId) {
        if (context == null || accountUid == null || chatId == null) return false;
        try {
            if (file(context, accountUid, chatId).exists()) return true;
            String json = legacyPrefs(context).getString(legacyKey(accountUid, chatId), null);
            return json != null && !json.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ── write ────────────────────────────────────────────────────────────────

    /**
     * Persists a defensive copy off the UI thread. Versioning prevents a slower older write from
     * overwriting a newer Firebase/Room snapshot. Deleted / expired rows are not stored.
     */
    public static void saveAsync(Context context, String accountUid, String chatId, List<Message> messages) {
        if (context == null || accountUid == null || accountUid.isEmpty()
                || chatId == null || chatId.isEmpty() || messages == null
                || messages.isEmpty()) return;

        long now = System.currentTimeMillis();
        List<Message> copy = new ArrayList<>(messages.size());
        for (Message m : messages) {
            if (!LastMessagesCache.isHiddenFromWarmFrame(m, now)) copy.add(m);
        }
        if (copy.isEmpty()) return;
        if (copy.size() > MAX_PER_CHAT) {
            copy = new ArrayList<>(copy.subList(copy.size() - MAX_PER_CHAT, copy.size()));
        }
        final Context appContext = context.getApplicationContext();
        final File target = file(appContext, accountUid, chatId);
        AtomicLong version = WRITE_VERSIONS.computeIfAbsent(target.getName(), ignored -> new AtomicLong());
        final long writeVersion = version.incrementAndGet();
        final List<Message> finalCopy = copy;

        AppBgExecutor.execute(() -> {
            if (version.get() != writeVersion) return;
            try {
                Envelope env = new Envelope();
                env.v = FORMAT_VERSION;
                env.savedAt = System.currentTimeMillis();
                env.messages = finalCopy;
                byte[] enc = encrypt(appContext, GSON.toJson(env).getBytes(StandardCharsets.UTF_8));
                if (enc == null) return;                       // fail closed: never write plaintext
                synchronized (WRITE_LOCK) {
                    if (version.get() != writeVersion) return;
                    File tmp = new File(target.getPath() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(enc);
                        out.getFD().sync();
                    }
                    if (!tmp.renameTo(target)) {               // some filesystems refuse rename-over
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                        if (!tmp.renameTo(target)) {
                            //noinspection ResultOfMethodCallIgnored
                            tmp.delete();
                            return;
                        }
                    }
                    trimLru(appContext);
                }
            } catch (Exception e) {
                Log.w(TAG, "save failed: " + e.getMessage());
            }
        });
    }

    /** Keep at most MAX_DISK_CHATS snapshots (least recently written go first) and sweep stray temp files. */
    private static void trimLru(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) return;
        List<File> snaps = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".tmp")) { //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else if (f.getName().endsWith(EXT)) {
                snaps.add(f);
            }
        }
        if (snaps.size() <= MAX_DISK_CHATS) return;
        Collections.sort(snaps, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < snaps.size() - MAX_DISK_CHATS; i++) {
            //noinspection ResultOfMethodCallIgnored
            snaps.get(i).delete();
        }
    }

    /** Clears all snapshots (v2 files and any leftover v1 prefs entries) belonging to one Firebase account. */
    public static void clearForAccountAsync(Context context, String accountUid) {
        if (context == null || accountUid == null || accountUid.isEmpty()) return;
        final Context appContext = context.getApplicationContext();
        final String prefix = accountPrefix(accountUid);
        // invalidate in-flight saves so they can't recreate a file after the clear
        for (Map.Entry<String, AtomicLong> e : WRITE_VERSIONS.entrySet()) {
            if (e.getKey().startsWith(prefix)) e.getValue().incrementAndGet();
        }
        AppBgExecutor.execute(() -> {
            synchronized (WRITE_LOCK) {
                try {
                    File[] files = dir(appContext).listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().startsWith(prefix)) { //noinspection ResultOfMethodCallIgnored
                                f.delete();
                            }
                        }
                    }
                    SharedPreferences p = legacyPrefs(appContext);
                    Set<String> old = p.getStringSet(LEGACY_INDEX_KEY, Collections.emptySet());
                    SharedPreferences.Editor editor = p.edit();
                    Set<String> keep = new LinkedHashSet<>();
                    String legacyPrefix = "m_" + legacySafe(accountUid) + "_";
                    for (String k : old) {
                        if (k.startsWith(legacyPrefix)) editor.remove(k);
                        else keep.add(k);
                    }
                    editor.putStringSet(LEGACY_INDEX_KEY, keep).apply();
                } catch (Exception e) {
                    Log.w(TAG, "clear failed: " + e.getMessage());
                }
            }
        });
    }
}
