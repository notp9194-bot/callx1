package com.callx.app.utils;

import android.net.Uri;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

/**
 * Keeps FirebaseAuth's own photo URL in step with the avatar the user actually
 * chose in the app (users/{uid}/photoUrl).
 *
 * Why: many screens (communities, reel comments, status, account switcher, …) read
 * FirebaseAuth.getCurrentUser().getPhotoUrl() as a quick synchronous "my photo".
 * For Google-login users that value is their Google profile photo, which the app no
 * longer auto-uses as an avatar. Instead of patching every one of those call sites,
 * we make that Auth value match the app's avatar:
 *   - user has an app avatar → Auth photo = that URL
 *   - user has none (default avatar) → Auth photo cleared (null)
 * Cheap: one tiny DB read, and updateProfile() only runs when the two differ.
 */
public final class AuthPhotoSync {

    private AuthPhotoSync() {}

    /** Reads users/{uid}/photoUrl and aligns the Auth photo with it. Async, safe to call often. */
    public static void sync() {
        try {
            final FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            if (u == null) return;
            FirebaseUtils.getUserRef(u.getUid()).child("photoUrl").get()
                .addOnSuccessListener(snap -> {
                    String db = snap.getValue(String.class);
                    set(db);
                });
        } catch (Exception ignored) { /* best-effort */ }
    }

    /** Sets the Auth photo to {@code url} (null/empty = clear). No-op if already equal. */
    public static void set(String url) {
        try {
            final FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            if (u == null) return;
            String want = url == null ? "" : url;
            String cur  = u.getPhotoUrl() == null ? "" : u.getPhotoUrl().toString();
            if (want.equals(cur)) return;
            u.updateProfile(new UserProfileChangeRequest.Builder()
                .setPhotoUri(want.isEmpty() ? null : Uri.parse(want))
                .build());
        } catch (Exception ignored) { /* best-effort */ }
    }
}
