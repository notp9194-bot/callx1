// ── Add these methods to your existing FirebaseUtils.java ─────────────────

// Firebase node: statuses/{ownerUid}
public static DatabaseReference getUserStatusRef(String ownerUid) {
    return db().getReference("statuses").child(ownerUid);
}

// Firebase node: statusSeen/{ownerUid}/{statusId}
public static DatabaseReference getStatusSeenByRef(String ownerUid, String statusId) {
    return db().getReference("statusSeen").child(ownerUid).child(statusId);
}

// Firebase node: statusReactions/{ownerUid}/{statusId}
public static DatabaseReference getStatusReactionRef(String ownerUid, String statusId) {
    return db().getReference("statusReactions").child(ownerUid).child(statusId);
}

// Firebase node: statusHighlights/{ownerUid}
public static DatabaseReference getStatusHighlightsRef(String ownerUid) {
    return db().getReference("statusHighlights").child(ownerUid);
}
