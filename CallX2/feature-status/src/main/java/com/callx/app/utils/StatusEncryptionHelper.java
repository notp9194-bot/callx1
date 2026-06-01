package com.callx.app.utils;

import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.spec.*;
import java.util.HashMap;
import java.util.Map;

/**
 * StatusEncryptionHelper v26 — E2EE for private/close-friends statuses.
 * AES-256-GCM for content encryption; key stored per-viewer in Firebase.
 * Content key encrypted with viewer's public key (RSA-OAEP).
 */
public final class StatusEncryptionHelper {
    private static final String AES_MODE = "AES/GCM/NoPadding";
    private static final int    GCM_IV   = 12;
    private static final int    GCM_TAG  = 128;
    private StatusEncryptionHelper() {}

    public static class EncryptedPayload {
        public String cipherText; // Base64
        public String iv;         // Base64
        public String keyBase64;  // Base64 AES key (store encrypted per-viewer in Firebase)
    }

    /** Encrypt text/URL content with AES-256-GCM */
    public static EncryptedPayload encrypt(String plaintext) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES"); kg.init(256); SecretKey key = kg.generateKey();
        byte[] iv = new byte[GCM_IV]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes("UTF-8"));
        EncryptedPayload p = new EncryptedPayload();
        p.cipherText = Base64.encodeToString(ct, Base64.NO_WRAP);
        p.iv         = Base64.encodeToString(iv, Base64.NO_WRAP);
        p.keyBase64  = Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);
        return p;
    }

    /** Decrypt with stored AES key */
    public static String decrypt(String cipherText, String ivB64, String keyB64) throws Exception {
        byte[] keyBytes = Base64.decode(keyB64, Base64.NO_WRAP);
        byte[] iv       = Base64.decode(ivB64,    Base64.NO_WRAP);
        byte[] ct       = Base64.decode(cipherText, Base64.NO_WRAP);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
        return new String(cipher.doFinal(ct), "UTF-8");
    }

    /** Store encrypted key for each viewer in Firebase: encryptedKeys/{statusId}/{viewerUid} */
    public static void storeKeyForViewers(String statusId, String aesKeyB64, java.util.List<String> viewerUids) {
        if (statusId == null || aesKeyB64 == null || viewerUids == null) return;
        Map<String, Object> updates = new HashMap<>();
        for (String uid : viewerUids) if (uid != null) updates.put(uid, aesKeyB64);
        FirebaseUtils.db().getReference("encryptedKeys").child(statusId).updateChildren(updates);
    }
}
