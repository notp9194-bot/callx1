package com.callx.app.admin.model;

/**
 * Mirrors one node under verification_requests/{uid} (see
 * FirebaseUtils#getVerificationRequestRef in :core). Needs a no-arg
 * constructor for Firebase's automatic DataSnapshot.getValue(Class) mapping.
 */
public class VerificationRequest {
    public String uid;
    public String name;
    public String photoUrl;
    public String reason;
    public String status;      // "pending" | "approved" | "rejected"
    public Long   submittedAt; // ServerValue.TIMESTAMP, millis epoch

    public VerificationRequest() { }
}
