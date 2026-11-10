package com.callx.app.voicerooms;

import com.callx.app.voicerooms.VrModel.Gift;
import com.callx.app.voicerooms.VrModel.Member;
import com.callx.app.voicerooms.VrModel.Msg;
import com.callx.app.voicerooms.VrModel.Room;
import com.callx.app.voicerooms.VrModel.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * VrDemoData — UI-only placeholder data (screenshot jaisa).
 * Backend jodte waqt is class ko hata ke Firebase listeners se adapters fill
 * karenge; adapters/screens ko change nahi karna padega.
 */
final class VrDemoData {
    private VrDemoData() {}

    static final String[] CATEGORIES = {"All", "Music", "Talk", "Chill", "Gaming", "Learn"};

    /** Screenshot wala room. */
    static Room defaultRoom() {
        Room r = new Room();
        r.id = "demo_chill_vibes";
        r.title = "Chill Vibes Only \uD83D\uDC9C";
        r.topic = "Music \u2022 Fun \u2022 Friends";
        r.description = "Let's talk about your favourite songs!";
        r.category = "Music";
        r.hostName = "Riya";
        r.hostVerified = true;
        r.listeners = 2400;
        r.audienceCount = 1200;
        r.speakers.addAll(speakers());
        return r;
    }

    static List<Member> speakers() {
        List<Member> l = new ArrayList<>();
        l.add(new Member("Riya", VrModel.ROLE_HOST, true, true));
        l.add(new Member("Aman", VrModel.ROLE_SPEAKER, true, false));
        l.add(new Member("Pooja", VrModel.ROLE_SPEAKER, true, false));
        l.add(new Member("Rohit", VrModel.ROLE_SPEAKER, true, false));
        return l;
    }

    static List<Member> audience() {
        String[] names = {
            "Sneha", "Vikash", "Monu", "Divya", "Rinku", "Neha", "Sahil", "Kajal",
            "Ishaan", "Tanvi", "Karan", "Meera", "Arjun", "Simran", "Dev", "Anika",
            "Yash", "Ritu", "Varun", "Naina"
        };
        List<Member> l = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            // pehle 8 online (screenshot jaisa green dot), baaki mixed
            boolean online = i < 8 || i % 3 != 0;
            l.add(new Member(names[i], VrModel.ROLE_LISTENER, online, false));
        }
        return l;
    }

    static List<Msg> chat() {
        List<Msg> l = new ArrayList<>();
        l.add(new Msg("Aman", "9:20 PM", "This room is awesome! \uD83D\uDD25", 24));
        l.add(new Msg("Pooja", "9:21 PM", "Good vibes only \uD83D\uDC9C", 18));
        l.add(new Msg("Sneha", "9:22 PM", "Please play something chill \uD83C\uDFB6", 7));
        l.add(new Msg("Vikash", "9:23 PM", "Hello everyone \uD83D\uDC4B", 3));
        return l;
    }

    static List<Room> rooms() {
        List<Room> l = new ArrayList<>();
        l.add(defaultRoom());
        l.add(room("r2", "Late Night Lo-fi \uD83C\uDF19", "Study \u2022 Focus \u2022 Calm", "Chill",
                "Ananya", true, 860));
        l.add(room("r3", "Bollywood Antakshari", "Songs \u2022 Games \u2022 Fun", "Music",
                "Rahul", false, 1500));
        l.add(room("r4", "Startup Stories", "Founders \u2022 Ideas \u2022 Growth", "Talk",
                "Kabir", true, 420));
        l.add(room("r5", "Valorant Squad Hangout", "Ranked \u2022 Tips \u2022 Banter", "Gaming",
                "Zoya", false, 310));
        l.add(room("r6", "English Speaking Practice", "Learn \u2022 Speak \u2022 Improve", "Learn",
                "Nisha", true, 975));
        return l;
    }

    private static Room room(String id, String title, String topic, String cat,
                             String host, boolean verified, int listeners) {
        Room r = new Room();
        r.id = id;
        r.title = title;
        r.topic = topic;
        r.description = "Join the conversation \u2014 raise your hand to speak!";
        r.category = cat;
        r.hostName = host;
        r.hostVerified = verified;
        r.listeners = listeners;
        r.audienceCount = Math.max(0, listeners - 4);
        r.speakers.add(new Member(host, VrModel.ROLE_HOST, true, verified));
        r.speakers.add(new Member("Aman", VrModel.ROLE_SPEAKER, true, false));
        r.speakers.add(new Member("Pooja", VrModel.ROLE_SPEAKER, true, false));
        return r;
    }

    static List<Gift> gifts() {
        List<Gift> l = new ArrayList<>();
        l.add(new Gift("\uD83C\uDF39", "Rose", 10));
        l.add(new Gift("\uD83D\uDC9C", "Heart", 20));
        l.add(new Gift("\uD83D\uDD25", "Fire", 30));
        l.add(new Gift("\uD83C\uDF81", "Gift", 50));
        l.add(new Gift("\uD83C\uDFA4", "Mic", 100));
        l.add(new Gift("\uD83D\uDC51", "Crown", 500));
        l.add(new Gift("\uD83D\uDE80", "Rocket", 700));
        l.add(new Gift("\uD83D\uDC8E", "Diamond", 1000));
        return l;
    }

    static List<Track> tracks() {
        List<Track> l = new ArrayList<>();
        l.add(new Track("Midnight Drive", "Neon Waves", "3:42"));
        l.add(new Track("Lo-fi Chai", "Study Beats", "2:58"));
        l.add(new Track("Monsoon Mood", "Indie Roots", "4:05"));
        l.add(new Track("Sunset Loop", "Chill Atlas", "3:21"));
        return l;
    }
}
