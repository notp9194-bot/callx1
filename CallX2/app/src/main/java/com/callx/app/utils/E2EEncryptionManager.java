package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.SecureRandom;

/**
 * Feature 15: End-to-End Encryption
 *
 * Protocol:
 *  1. Each user generates an RSA-2048 key pair on first launch.
 *     Public key is uploaded to Firebase: users/{uid}/publicKey = base64(pub)
 *  2. When Alice starts a chat with Bob she:
 *     a. Fetches Bob's public key from Firebase.
 *     b. Generates a random AES-256 session key for this chatId.
 *     c. Encrypts the session key with Bob's RSA public key → stores in
 *        Firebase: e2eKeys/{chatId}/{bobUid} = base64(encryptedSessionKey)
 *     d. Also encrypts it with her own public key for self-decryption:
 *        e2eKeys/{chatId}/{aliceUid} = base64(encryptedSessionKey)
 *  3. Each message:
 *     a. Encrypt plaintext with AES-256-GCM using session key → ciphertext + IV.
 *     b. Store: message.text = base64(ciphertext), message.iv = base64(iv),
 *        message.e2eEncrypted = true
 *  4. Recipient decrypts session key with own RSA private key, then decrypts msg.
 *
 *  Session keys are cached in memory for the app session to avoid repeated
 *  Firebase reads.
 */
public class E2EEncryptionManager {

    private static final String TAG      = "E2EEncrypt";
    private static final String PREFS    = "e2e_prefs";
    private static final String KEY_PRIV = "rsa_private_key";
    private static final String KEY_PUB  = "rsa_public_key";
    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final String RSA_ALGO = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final int    GCM_TAG  = 128;
    private static final int    IV_LEN   = 12; // bytes

    private static E2EEncryptionManager instance;
    private final SharedPreferences prefs;
    private PrivateKey privateKey;
    private PublicKey  publicKey;

    // chatId → AES SecretKey (cached for session)
    private final Map<String, SecretKey> sessionKeyCache = new HashMap<>();

    private E2EEncryptionManager(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadOrGenerateKeyPair();
    }

    public static synchronized E2EEncryptionManager getInstance(Context ctx) {
        if (instance == null) instance = new E2EEncryptionManager(ctx);
        return instance;
    }

    // ── Key management ─────────────────────────────────────────────────────

    private void loadOrGenerateKeyPair() {
        String privB64 = prefs.getString(KEY_PRIV, null);
        String pubB64  = prefs.getString(KEY_PUB, null);
        if (privB64 != null && pubB64 != null) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                privateKey = kf.generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)));
                publicKey  = kf.generatePublic(
                        new X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)));
                return;
            } catch (Exception e) {
                Log.e(TAG, "Failed to load key pair, regenerating", e);
            }
        }
        generateAndStoreKeyPair();
    }

    private void generateAndStoreKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            privateKey = kp.getPrivate();
            publicKey  = kp.getPublic();
            prefs.edit()
                 .putString(KEY_PRIV, Base64.encodeToString(
                         privateKey.getEncoded(), Base64.NO_WRAP))
                 .putString(KEY_PUB,  Base64.encodeToString(
                         publicKey.getEncoded(), Base64.NO_WRAP))
                 .apply();
        } catch (Exception e) {
            Log.e(TAG, "Key pair generation failed", e);
        }
    }

    /** Base64 encoded public key to upload to Firebase. */
    public String getPublicKeyBase64() {
        return publicKey != null
                ? Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP)
                : null;
    }

    // ── Session key helpers ────────────────────────────────────────────────

    /** Generate a new random AES-256 session key for a chat. */
    public SecretKey generateSessionKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return kg.generateKey();
    }

    /**
     * Encrypt a SecretKey with the given RSA PublicKey (for key exchange).
     * Returns base64-encoded ciphertext.
     */
    public String encryptSessionKey(SecretKey sessionKey, PublicKey recipientPub)
            throws Exception {
        Cipher c = Cipher.getInstance(RSA_ALGO);
        c.init(Cipher.ENCRYPT_MODE, recipientPub);
        byte[] enc = c.doFinal(sessionKey.getEncoded());
        return Base64.encodeToString(enc, Base64.NO_WRAP);
    }

    /**
     * Decrypt an RSA-encrypted session key with our private key.
     * Returns the AES SecretKey.
     */
    public SecretKey decryptSessionKey(String encB64) throws Exception {
        Cipher c = Cipher.getInstance(RSA_ALGO);
        c.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] dec = c.doFinal(Base64.decode(encB64, Base64.NO_WRAP));
        return new SecretKeySpec(dec, "AES");
    }

    /**
     * Parse a base64 RSA public key string (as stored in Firebase) into a PublicKey.
     */
    public PublicKey parsePublicKey(String b64) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(new X509EncodedKeySpec(Base64.decode(b64, Base64.NO_WRAP)));
    }

    // ── Session key cache ──────────────────────────────────────────────────

    public void cacheSessionKey(String chatId, SecretKey key) {
        sessionKeyCache.put(chatId, key);
    }

    public SecretKey getCachedSessionKey(String chatId) {
        return sessionKeyCache.get(chatId);
    }

    // ── Encrypt / Decrypt messages ─────────────────────────────────────────

    /**
     * Encrypt plaintext using AES-256-GCM.
     * @return String[2] → { base64(ciphertext), base64(iv) }
     */
    public String[] encryptMessage(String plaintext, SecretKey key) throws Exception {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance(AES_ALGO);
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
        byte[] cipher = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return new String[]{
                Base64.encodeToString(cipher, Base64.NO_WRAP),
                Base64.encodeToString(iv, Base64.NO_WRAP)
        };
    }

    /**
     * Decrypt an AES-256-GCM encrypted message.
     * @param cipherB64 base64 ciphertext (from message.text)
     * @param ivB64     base64 IV (from message.iv)
     */
    public String decryptMessage(String cipherB64, String ivB64, SecretKey key)
            throws Exception {
        byte[] cipher = Base64.decode(cipherB64, Base64.NO_WRAP);
        byte[] iv     = Base64.decode(ivB64, Base64.NO_WRAP);
        Cipher c = Cipher.getInstance(AES_ALGO);
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
        byte[] plain = c.doFinal(cipher);
        return new String(plain, StandardCharsets.UTF_8);
    }

    // ── Convenience: encrypt/decrypt with cached key ───────────────────────

    /**
     * Encrypt a message for a given chatId using its cached session key.
     * Returns null if no session key is cached (key exchange not done yet).
     */
    public String[] encryptForChat(String chatId, String plaintext) {
        SecretKey k = sessionKeyCache.get(chatId);
        if (k == null) return null;
        try { return encryptMessage(plaintext, k); }
        catch (Exception e) { Log.e(TAG, "Encrypt failed", e); return null; }
    }

    /**
     * Decrypt a message for a given chatId using its cached session key.
     * Returns "[Encrypted]" if key not available yet.
     */
    public String decryptForChat(String chatId, String cipherB64, String ivB64) {
        SecretKey k = sessionKeyCache.get(chatId);
        if (k == null) return "[Encrypted — tap to unlock]";
        try { return decryptMessage(cipherB64, ivB64, k); }
        catch (Exception e) {
            Log.e(TAG, "Decrypt failed", e);
            return "[Decryption failed]";
        }
    }
}
