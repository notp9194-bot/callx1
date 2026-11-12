package com.callx.app.voicerooms;

import com.callx.app.voicerooms.VrModel.Gift;
import com.callx.app.voicerooms.VrModel.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * VrCatalog — static catalog only (categories, gift list, emoji grid).
 * v794: VrDemoData hata diya; rooms/members/chat ab Firebase se aate hain.
 * Gift ids/prices server (functions_index.js VR_GIFT_CATALOG) se match hone chahiye —
 * price ka final faisla hamesha server karta hai, ye sirf display ke liye hai.
 */
final class VrCatalog {
    private VrCatalog() {}

    static final String[] CATEGORIES = {"All", "Music", "Talk", "Chill", "Gaming", "Learn"};

    static List<Gift> gifts() {
        List<Gift> l = new ArrayList<>();
        l.add(new Gift("rose", "\uD83C\uDF39", "Rose", 10));
        l.add(new Gift("heart", "\uD83D\uDC9C", "Heart", 20));
        l.add(new Gift("fire", "\uD83D\uDD25", "Fire", 30));
        l.add(new Gift("gift", "\uD83C\uDF81", "Gift", 50));
        l.add(new Gift("mic", "\uD83C\uDFA4", "Mic", 100));
        l.add(new Gift("crown", "\uD83D\uDC51", "Crown", 500));
        l.add(new Gift("rocket", "\uD83D\uDE80", "Rocket", 700));
        l.add(new Gift("diamond", "\uD83D\uDC8E", "Diamond", 1000));
        return l;
    }

    /** Music sheet abhi bhi UI-only (room music streaming is step ke scope me nahi). */
    static List<Track> tracks() {
        List<Track> l = new ArrayList<>();
        l.add(new Track("Midnight Drive", "Neon Waves", "3:42"));
        l.add(new Track("Lo-fi Chai", "Study Beats", "2:58"));
        l.add(new Track("Monsoon Mood", "Indie Roots", "4:05"));
        l.add(new Track("Sunset Loop", "Chill Atlas", "3:21"));
        return l;
    }

    /** Emoji picker sheet ka grid. */
    static List<String> emojis() {
        String[] e = {
            "\uD83D\uDE00","\uD83D\uDE02","\uD83E\uDD23","\uD83D\uDE0A","\uD83D\uDE0D","\uD83E\uDD70","\uD83D\uDE18","\uD83D\uDE0E",
            "\uD83E\uDD29","\uD83E\uDD73","\uD83D\uDE07","\uD83D\uDE09","\uD83D\uDE0F","\uD83D\uDE0C","\uD83E\uDD14","\uD83D\uDE2E",
            "\uD83D\uDE22","\uD83D\uDE2D","\uD83D\uDE21","\uD83E\uDD7A","\uD83D\uDE34","\uD83E\uDD2F","\uD83D\uDE05","\uD83D\uDE4C",
            "\uD83D\uDC4F","\uD83D\uDC4D","\uD83D\uDE4F","\uD83D\uDCAA","\u270C\uFE0F","\uD83E\uDD1D","\uD83D\uDC4B","\uD83E\uDEF6",
            "\uD83D\uDC9C","\u2764\uFE0F","\uD83D\uDC99","\uD83D\uDC9B","\uD83D\uDC9A","\uD83D\uDDA4","\uD83D\uDC96","\uD83D\uDC95",
            "\uD83D\uDD25","\u2728","\uD83C\uDF89","\uD83C\uDF86","\uD83C\uDFB6","\uD83C\uDFA4","\uD83C\uDFA7","\uD83C\uDFB8",
            "\uD83C\uDF39","\uD83C\uDF1F","\uD83D\uDCAF","\uD83D\uDC51","\uD83D\uDC8E","\uD83D\uDE80","\u2615","\uD83C\uDF55"
        };
        List<String> l = new ArrayList<>();
        for (String x : e) l.add(x);
        return l;
    }
}
