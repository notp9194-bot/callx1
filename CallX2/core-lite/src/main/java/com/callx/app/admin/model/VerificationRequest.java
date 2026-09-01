package com.callx.app.admin.model;

/**
 * Mirrors one node under verification_requests/{uid}. The no-arg constructor
 * is required by Firebase Realtime Database's automatic object mapping.
 */
public class VerificationRequest {
    public String uid;
    public String name;
    public String photoUrl;
    public String reason;
    public String status;
    public Long submittedAt;

    public VerificationRequest() { }
}