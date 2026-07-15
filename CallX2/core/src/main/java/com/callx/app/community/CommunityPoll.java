package com.callx.app.community;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v30: Poll attached to a CommunityPostEntity. Stored as a single JSON blob
 * in CommunityPostEntity.pollJson (Room) and mirrored as structured children
 * under communities/{communityId}/posts/{postId}/poll (Firebase) — see
 * CommunityRepository#votePoll. Small + always read/written whole, so JSON
 * is simpler than normalizing into extra Room tables.
 *
 * Uses org.json (bundled with the Android SDK, no extra Gradle dependency)
 * instead of Gson to keep this class usable from :core without pulling in
 * a serialization library just for one small POJO.
 */
public class CommunityPoll {

    public static class Option {
        public String text;
        public int votes;

        public Option() {}
        public Option(String text, int votes) {
            this.text = text;
            this.votes = votes;
        }
    }

    public String question;
    public List<Option> options = new ArrayList<>();
    /** uid -> option index voted for. One vote per member (not multi-choice). */
    public Map<String, Integer> voters = new HashMap<>();

    public CommunityPoll() {}

    public CommunityPoll(String question, List<String> optionTexts) {
        this.question = question;
        for (String t : optionTexts) options.add(new Option(t, 0));
    }

    public int totalVotes() {
        int total = 0;
        for (Option o : options) total += o.votes;
        return total;
    }

    /** Percentage (0-100) for the option at index, rounded. Safe on totalVotes()==0. */
    public int percentFor(int index) {
        int total = totalVotes();
        if (total <= 0 || index < 0 || index >= options.size()) return 0;
        return Math.round(options.get(index).votes * 100f / total);
    }

    public Integer votedOptionOf(String uid) {
        return voters.get(uid);
    }

    /** Applies uid's vote — moves it from any previous option first (single-choice poll). */
    public void applyVote(String uid, int optionIndex) {
        Integer prev = voters.get(uid);
        if (prev != null && prev >= 0 && prev < options.size()) {
            options.get(prev).votes = Math.max(0, options.get(prev).votes - 1);
        }
        if (optionIndex >= 0 && optionIndex < options.size()) {
            options.get(optionIndex).votes++;
            voters.put(uid, optionIndex);
        }
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("question", question != null ? question : "");
            JSONArray opts = new JSONArray();
            for (Option o : options) {
                JSONObject jo = new JSONObject();
                jo.put("text", o.text != null ? o.text : "");
                jo.put("votes", o.votes);
                opts.put(jo);
            }
            root.put("options", opts);
            JSONObject votersObj = new JSONObject();
            for (Map.Entry<String, Integer> e : voters.entrySet()) {
                votersObj.put(e.getKey(), e.getValue());
            }
            root.put("voters", votersObj);
            return root.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    public static CommunityPoll fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            CommunityPoll poll = new CommunityPoll();
            poll.question = root.optString("question", "");
            JSONArray opts = root.optJSONArray("options");
            if (opts != null) {
                for (int i = 0; i < opts.length(); i++) {
                    JSONObject jo = opts.getJSONObject(i);
                    poll.options.add(new Option(jo.optString("text", ""), jo.optInt("votes", 0)));
                }
            }
            JSONObject votersObj = root.optJSONObject("voters");
            if (votersObj != null) {
                java.util.Iterator<String> keys = votersObj.keys();
                while (keys.hasNext()) {
                    String uid = keys.next();
                    poll.voters.put(uid, votersObj.optInt(uid, -1));
                }
            }
            return poll;
        } catch (JSONException e) {
            return null;
        }
    }
}
