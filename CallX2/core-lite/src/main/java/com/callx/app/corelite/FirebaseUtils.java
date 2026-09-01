package com.callx.app.corelite;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

/**
 * Firebase helpers shared by the standalone admin app and the main app's
 * verification flow. Keep this class intentionally small: this module must
 * not acquire the main app's media, Room, messaging, or networking stack.
 */
public final class FirebaseUtils {
    /** Same RTDB endpoint used by the main app's Constants.DB_URL facade. */
    public static final String DB_URL =
        "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";

    public static final String VERIFICATION_REQUESTS_PATH = "verification_requests";
    public static final String ADMINS_PATH = "admins";
    public static final String FIELD_IS_VERIFIED = "isVerified";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    private FirebaseUtils() { }

    public static FirebaseDatabase db() {
        return FirebaseDatabase.getInstance(DB_URL);
    }

    public static String getCurrentUid() {
        com.google.firebase.auth.FirebaseUser user =
            FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : "";
    }

    public static DatabaseReference getVerificationRequestsRef() {
        return db().getReference(VERIFICATION_REQUESTS_PATH);
    }

    public static DatabaseReference getVerificationRequestRef(String uid) {
        return getVerificationRequestsRef().child(uid);
    }

    public static DatabaseReference getAdminsRef() {
        return db().getReference(ADMINS_PATH);
    }

    public static DatabaseReference getIsVerifiedRef(String uid) {
        return db().getReference("users").child(uid).child(FIELD_IS_VERIFIED);
    }
}