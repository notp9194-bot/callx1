package com.callx.app.live;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DatabaseReference;

public class LiveManager {

    public static DatabaseReference getLivesRef() {
        return FirebaseUtils.db().getReference("lives");
    }

    public static DatabaseReference getLiveRef(String liveId) {
        return FirebaseUtils.db().getReference("lives").child(liveId);
    }

    public static DatabaseReference getLiveMessagesRef(String liveId) {
        return FirebaseUtils.db().getReference("lives").child(liveId).child("messages");
    }

    public static DatabaseReference getLiveViewersRef(String liveId) {
        return FirebaseUtils.db().getReference("lives").child(liveId).child("viewers");
    }

    public static DatabaseReference getUserActiveLiveRef(String uid) {
        return FirebaseUtils.db().getReference("userActiveLive").child(uid);
    }

    public static String generateLiveId() {
        return getLivesRef().push().getKey();
    }
}
