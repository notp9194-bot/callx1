package com.callx.app.utils;

import android.os.Handler;
import android.os.Looper;
import java.util.Random;

/**
 * CollabAIHelper — AI-powered caption suggestions for reposts.
 *
 * Currently uses template-based suggestions (no API key needed).
 * To upgrade to Gemini/OpenAI: replace suggestRepostCaption() body
 * with a POST to your server's /ai/caption endpoint.
 */
public class CollabAIHelper {

    public interface CaptionCallback { void onCaption(String caption); }

    private static final String[][] TEMPLATES = {
        {"This is 🔥🔥🔥", "Can't stop watching this!", "Saving this forever 👏"},
        {"THIS 👆", "We all needed to see this", "Drop everything and watch"},
        {"Literally me 💀", "No words needed 🫡", "The accuracy 😭"},
        {"Sharing because everyone needs this", "Obsessed with this 🤩", "Tag someone who needs to see this"},
        {"The talent on this app 🤯", "Okay but this is incredible", "How are people this talented??"},
    };

    /**
     * Suggests a repost caption based on reel context.
     * @param reelId     ID of the reel being reposted (for future server-side AI call)
     * @param ownerName  Creator name — included in some suggestions
     * @param cb         Callback with the suggested caption
     */
    public static void suggestRepostCaption(String reelId, String ownerName, CaptionCallback cb) {
        // Simulate async AI generation delay (replace with real API call)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String caption = generateCaption(ownerName);
            cb.onCaption(caption);
        }, 800);
    }

    /**
     * Suggests a collab caption for joint posts.
     */
    public static void suggestCollabCaption(String ownerName, String inviteeName, CaptionCallback cb) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String[] collabTemplates = {
                "Collab with " + inviteeName + " 🔥",
                "Made this with " + inviteeName + " 🤝",
                ownerName + " × " + inviteeName + " 💥",
                "When " + inviteeName + " and I get creative 🎬",
                "Collab drop with " + inviteeName + "! 🎉"
            };
            cb.onCaption(collabTemplates[new Random().nextInt(collabTemplates.length)]);
        }, 800);
    }

    private static String generateCaption(String ownerName) {
        Random r = new Random();
        String[][] t = TEMPLATES;
        String[] row = t[r.nextInt(t.length)];
        String base  = row[r.nextInt(row.length)];

        // 30% chance to add creator credit
        if (ownerName != null && !ownerName.isEmpty() && r.nextInt(10) < 3) {
            base = "via @" + ownerName.replace(" ", "") + " — " + base;
        }
        return base;
    }

    // ── Server-side AI integration stub ──────────────────────────────────────
    // To use real AI, POST to your backend:
    //
    // POST /ai/repost-caption
    // { "reelId": "...", "ownerName": "..." }
    // Returns: { "caption": "..." }
    //
    // Replace the body of suggestRepostCaption() with an OkHttp/Retrofit call.
}
