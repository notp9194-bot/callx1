package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the accounts that have been used on this device.
 *
 * Only non-sensitive account metadata is stored here. Firebase passwords,
 * refresh tokens and ID tokens are intentionally never persisted by CallX.
 * A switch therefore goes through the normal Firebase login/OTP flow.
 */
public final class AccountSessionStore {

    private static final String PREFS_NAME = "callx_account_center_v1";
    private static final String KEY_ACCOUNTS = "saved_accounts";
    private static final int MAX_SAVED_ACCOUNTS = 8;

    private AccountSessionStore() {}

    public static final class Account {
        public final String uid;
        public final String name;
        public final String email;
        public final String phone;
        public final String photoUrl;
        public final String provider;
        public final String callxId;

        public Account(
                String uid,
                String name,
                String email,
                String phone,
                String photoUrl,
                String provider,
                String callxId) {
            this.uid = safe(uid);
            this.name = safe(name);
            this.email = safe(email);
            this.phone = safe(phone);
            this.photoUrl = safe(photoUrl);
            this.provider = safe(provider);
            this.callxId = safe(callxId);
        }

        public String displayName() {
            if (!name.isEmpty()) return name;
            if (!email.isEmpty()) return email;
            if (!phone.isEmpty()) return phone;
            return "CallX account";
        }

        public String identifier() {
            if (!email.isEmpty()) return email;
            if (!phone.isEmpty()) return phone;
            return uid;
        }
    }

    public static void rememberFirebaseUser(Context context, FirebaseUser user) {
        if (user == null) return;

        String provider = "email";
        for (com.google.firebase.auth.UserInfo info : user.getProviderData()) {
            String providerId = info.getProviderId();
            if ("google.com".equals(providerId)) {
                provider = "Google";
                break;
            }
            if ("phone".equals(providerId)) provider = "Phone";
        }

        remember(
                context,
                user.getUid(),
                user.getDisplayName(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getPhotoUrl() == null ? "" : user.getPhotoUrl().toString(),
                provider,
                "");
    }

    public static synchronized void remember(
            Context context,
            String uid,
            String name,
            String email,
            String phone,
            String photoUrl,
            String provider,
            String callxId) {
        if (safe(uid).isEmpty()) return;

        JSONArray next = new JSONArray();
        next.put(toJson(new Account(uid, name, email, phone, photoUrl, provider, callxId)));

        for (Account account : getAccounts(context)) {
            if (!account.uid.equals(uid) && next.length() < MAX_SAVED_ACCOUNTS) {
                next.put(toJson(account));
            }
        }

        prefs(context).edit().putString(KEY_ACCOUNTS, next.toString()).apply();
    }

    public static synchronized List<Account> getAccounts(Context context) {
        ArrayList<Account> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_ACCOUNTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String uid = item.optString("uid", "");
                if (uid.isEmpty()) continue;
                result.add(new Account(
                        uid,
                        item.optString("name", ""),
                        item.optString("email", ""),
                        item.optString("phone", ""),
                        item.optString("photoUrl", ""),
                        item.optString("provider", ""),
                        item.optString("callxId", "")));
            }
        } catch (JSONException ignored) {
            // A corrupt local list should not block Firebase login.
        }
        return result;
    }

    public static synchronized void remove(Context context, String uid) {
        JSONArray next = new JSONArray();
        for (Account account : getAccounts(context)) {
            if (!account.uid.equals(uid)) next.put(toJson(account));
        }
        prefs(context).edit().putString(KEY_ACCOUNTS, next.toString()).apply();
    }

    private static JSONObject toJson(Account account) {
        JSONObject item = new JSONObject();
        try {
            item.put("uid", account.uid);
            item.put("name", account.name);
            item.put("email", account.email);
            item.put("phone", account.phone);
            item.put("photoUrl", account.photoUrl);
            item.put("provider", account.provider);
            item.put("callxId", account.callxId);
        } catch (JSONException ignored) {
            // All values are local strings; JSONObject.put should not fail.
        }
        return item;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}