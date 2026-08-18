package com.callx.app.bots;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.callx.app.models.BotCommand;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * BotManager — slash-command engine for group chats.
 *
 * Built-in commands (client-side): /help /flip /roll /stats /announce /pin
 *                                   /mute /kick /remind
 * Custom commands: loaded from Firebase groups/{groupId}/botCommands/{cmd}
 *
 * Usage in GroupChatActivity:
 *   BotManager mgr = BotManager.get(ctx, groupId);
 *   if (mgr.parseAndExecute(text, this::pushBotMessage, isAdmin)) return; // consumed
 */
public class BotManager {

    public interface SendCallback { void pushBotMessage(String text); }

    private static BotManager instance;
    private final Context ctx;
    private final String groupId;
    private final List<BotCommand> custom = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final Random RNG = new Random();
    private int cachedMembers = 0, cachedMessages = 0;

    private BotManager(Context ctx, String gid) {
        this.ctx = ctx.getApplicationContext();
        this.groupId = gid;
        loadCustom();
        preloadStats();
    }

    public static BotManager get(Context ctx, String groupId) {
        if (instance == null || !groupId.equals(instance.groupId))
            instance = new BotManager(ctx, groupId);
        return instance;
    }

    /** All built-ins + custom for suggestion list. */
    public List<BotCommand> getSuggestions(String typed) {
        String q = typed.toLowerCase().trim();
        List<BotCommand> r = new ArrayList<>();
        for (BotCommand b : BotCommand.BUILT_INS) if (b.command.startsWith(q)) r.add(b);
        for (BotCommand c : custom) if (c.enabled && c.command.startsWith(q)) r.add(c);
        return r;
    }

    public List<BotCommand> getAllCommands() {
        List<BotCommand> all = new ArrayList<>(Arrays.asList(BotCommand.BUILT_INS));
        all.addAll(custom);
        return all;
    }

    /**
     * Parse and execute. Returns true if the input was a slash command.
     * Call BEFORE pushing the text as a regular message.
     */
    public boolean parseAndExecute(String input, SendCallback cb, boolean isAdmin) {
        if (input == null || !input.startsWith("/")) return false;
        String[] parts = input.trim().split("\\s+", 2);
        String cmd  = parts[0].substring(1).toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";
        switch (cmd) {
            case "help":     doHelp(cb);                         return true;
            case "flip":     doFlip(cb);                         return true;
            case "roll":     doRoll(args, cb);                   return true;
            case "stats":    doStats(cb);                        return true;
            case "announce": doAnnounce(args, cb, isAdmin);      return true;
            case "pin":      doPin(cb, isAdmin);                 return true;
            case "mute":     doAdmin("mute", args, cb, isAdmin); return true;
            case "kick":     doAdmin("kick", args, cb, isAdmin); return true;
            case "remind":   doRemind(args, cb);                 return true;
            default:         return doCustom(cmd, cb);
        }
    }

    private void doHelp(SendCallback cb) {
        StringBuilder sb = new StringBuilder("🤖 *Bot Commands*\n\n");
        for (BotCommand b : BotCommand.BUILT_INS)
            sb.append("/").append(b.command).append(" — ").append(b.description).append("\n");
        if (!custom.isEmpty()) {
            sb.append("\n📋 *Custom*\n");
            for (BotCommand c : custom)
                if (c.enabled) sb.append("/").append(c.command).append(" — ").append(c.description).append("\n");
        }
        post(sb.toString().trim(), cb);
    }

    private void doFlip(SendCallback cb) { post(RNG.nextBoolean() ? "🪙 Heads!" : "🪙 Tails!", cb); }

    private void doRoll(String args, SendCallback cb) {
        int sides = 6;
        try { if (!args.isEmpty()) sides = Math.max(2, Integer.parseInt(args.trim())); }
        catch (NumberFormatException ignored) {}
        post("🎲 Rolled d" + sides + ": *" + (RNG.nextInt(sides) + 1) + "*", cb);
    }

    private void doStats(SendCallback cb) {
        post("📊 *Group Stats*\n👥 Members: " + cachedMembers + "\n💬 Messages: " + cachedMessages, cb);
    }

    private void doAnnounce(String text, SendCallback cb, boolean isAdmin) {
        if (!isAdmin) { main.post(() -> Toast.makeText(ctx, "Admins only", Toast.LENGTH_SHORT).show()); return; }
        if (text.isEmpty()) { post("Usage: /announce <message>", cb); return; }
        post("📢 *Announcement*\n" + text, cb);
    }

    private void doPin(SendCallback cb, boolean isAdmin) {
        if (!isAdmin) { main.post(() -> Toast.makeText(ctx, "Admins only", Toast.LENGTH_SHORT).show()); return; }
        post("📌 Long-press a message → Pin to pin it.", cb);
    }

    private void doAdmin(String action, String args, SendCallback cb, boolean isAdmin) {
        if (!isAdmin) { main.post(() -> Toast.makeText(ctx, "Admins only", Toast.LENGTH_SHORT).show()); return; }
        if (args.isEmpty()) { post("Usage: /" + action + " @username", cb); return; }
        post("⚙️ Use Group Info to " + action + " members.", cb);
    }

    private void doRemind(String args, SendCallback cb) {
        if (args.isEmpty()) { post("Usage: /remind 10m <message>", cb); return; }
        String[] p = args.split("\\s+", 2);
        long ms = parseTime(p[0]);
        String msg = p.length > 1 ? p[1] : "Reminder!";
        if (ms <= 0) { post("⏰ Format: /remind 10s/5m/2h <message>", cb); return; }
        post("⏰ Reminder set for " + p[0] + ": \"" + msg + "\"", cb);
        main.postDelayed(() -> { if (cb != null) cb.pushBotMessage("⏰ *Reminder:* " + msg); }, ms);
    }

    private boolean doCustom(String cmd, SendCallback cb) {
        for (BotCommand c : custom)
            if (c.enabled && c.command.equals(cmd) && c.response != null) { post(c.response, cb); return true; }
        return false;
    }

    private void post(String text, SendCallback cb) { main.post(() -> { if (cb != null) cb.pushBotMessage(text); }); }

    private long parseTime(String s) {
        s = s.trim().toLowerCase();
        try {
            if (s.endsWith("s")) return Long.parseLong(s.replace("s","")) * 1000L;
            if (s.endsWith("m")) return Long.parseLong(s.replace("m","")) * 60_000L;
            if (s.endsWith("h")) return Long.parseLong(s.replace("h","")) * 3_600_000L;
        } catch (Exception ignored) {}
        return -1;
    }

    private void loadCustom() {
        FirebaseUtils.getGroupsRef().child(groupId).child("botCommands")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        custom.clear();
                        for (DataSnapshot c : s.getChildren()) {
                            BotCommand bc = c.getValue(BotCommand.class);
                            if (bc != null) { if (bc.command == null) bc.command = c.getKey(); custom.add(bc); }
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void preloadStats() {
        FirebaseUtils.getGroupsRef().child(groupId).child("members")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) { cachedMembers = (int) s.getChildrenCount(); }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        FirebaseUtils.getGroupMessagesRef(groupId)
                .limitToLast(1000)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) { cachedMessages = (int) s.getChildrenCount(); }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }
}
