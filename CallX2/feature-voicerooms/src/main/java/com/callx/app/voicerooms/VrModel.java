package com.callx.app.voicerooms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VrModel — plain UI models for Voice Rooms.
 *
 * v792: sirf UI ke liye. Jab Firebase/backend jodenge to inhi classes ko
 * `voiceRooms/{roomId}/...` ke snapshots se fill karenge (fields already
 * us hisaab se rakhe hain: uid, role, muted, photoUrl).
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
        /** null => initials avatar. Backend jodte waqt yahan Glide URL aayega. */
        public String photoUrl;

        public Member(String name, String role, boolean online, boolean verified) {
            this.uid = name.toLowerCase(Locale.ROOT);
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
        public boolean hostVerified;
        public int listeners;
        public int audienceCount;
        public final List<Member> speakers = new ArrayList<>();
    }

    public static class Msg {
        public final String name;
        public final String time;
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

    public static class Gift {
        public final String emoji;
        public final String name;
        public final int coins;

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
