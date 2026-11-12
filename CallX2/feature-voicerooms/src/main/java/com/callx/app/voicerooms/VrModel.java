package com.callx.app.voicerooms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VrModel — plain UI models for Voice Rooms.
 *
 * v794: ab ye classes `voiceRooms/{roomId}/...` aur `voiceRoomList/{roomId}` ke
 * Firebase snapshots se fill hoti hain (VrRepo).
 */
public final class VrModel {
    private VrModel() {}

    public static final String ROLE_HOST = "host";
    public static final String ROLE_SPEAKER = "speaker";
    public static final String ROLE_LISTENER = "listener";

    /** Room ka koi bhi participant (host / speaker / listener). */
    public static class Member {
        public final String uid;
        public final String name;
        public final String role;
        public final boolean verified;
        public boolean online;
        public boolean muted;
        /** Listener ne speak karne ke liye haath uthaya hai (host ko request dikhti hai). */
        public boolean handRaised;
        /** null => initials avatar. Backend jodte waqt yahan Glide URL aayega. */
        public String photoUrl;

        /** Host ne zabardasti mute kiya (server flag). */
        public boolean hostMuted;
        /** Speaker ne khud mute kiya. */
        public boolean selfMuted;

        public Member(String name, String role, boolean online, boolean verified) {
            this(name.toLowerCase(Locale.ROOT), name, role, online, verified);
        }

        public Member(String uid, String name, String role, boolean online, boolean verified) {
            this.uid = uid;
            this.name = name;
            this.role = role;
            this.online = online;
            this.verified = verified;
        }
    }

    public static class Room {
        public String id;
        public String title;
        public String topic;
        /** Topic row (banner ke neeche wali line). */
        public String description;
        public String category;
        public String hostName;
        public String hostUid;
        public String hostPhoto;
        public boolean hostVerified;
        public boolean allowRequests = true;
        public boolean live = true;
        public long createdAt;
        public int listeners;
        public int audienceCount;
        public final List<Member> speakers = new ArrayList<>();
    }

    public static class Msg {
        public String id;          // Firebase push key
        public String uid;         // sender
        public String photoUrl;
        public String type;        // null/"text" | "gift"
        public String giftEmoji;
        public long ts;
        public final String name;
        public String time;
        public final String text;
        public int hearts;
        public boolean liked;

        public Msg(String name, String time, String text, int hearts) {
            this.name = name;
            this.time = time;
            this.text = text;
            this.hearts = hearts;
        }
    }

    /** Live poll (host banata hai, sab vote karte hain). */
    public static class Poll {
        public String id;
        public String question;
        public final List<String> options = new ArrayList<>();
        public int[] counts = new int[0];
        public int myVote = -1;
        public long endsAt;
        public boolean expired() { return System.currentTimeMillis() >= endsAt; }
        public int total() { int t = 0; for (int c : counts) t += c; return t; }
    }

    public static class Gift {
        public String id = "";
        public final String emoji;
        public final String name;
        public final int coins;

        public Gift(String id, String emoji, String name, int coins) {
            this(emoji, name, coins);
            this.id = id;
        }

        public Gift(String emoji, String name, int coins) {
            this.emoji = emoji;
            this.name = name;
            this.coins = coins;
        }
    }

    public static class Track {
        public final String title;
        public final String artist;
        public final String duration;

        public Track(String title, String artist, String duration) {
            this.title = title;
            this.artist = artist;
            this.duration = duration;
        }
    }
}
