package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;

/**
 * BotCommand — represents a single slash-command entry.
 *
 * Two kinds:
 *  BUILT_IN  — system commands handled entirely client-side by BotManager
 *              (never written to Firebase; synthesised at runtime)
 *  CUSTOM    — admin-defined commands stored at
 *              groups/{groupId}/botCommands/{commandName}
 *              The "response" field holds the static text the bot will
 *              echo back in the group when a member fires that command.
 *
 * Firebase path (custom only):
 *   groups/{groupId}/botCommands/{commandName}/
 *     command, description, response, createdBy, createdAt, enabled
 */
@IgnoreExtraProperties
public class BotCommand {

    public enum Kind { BUILT_IN, CUSTOM }

    /** The slash keyword without the "/" prefix, e.g. "help", "roll" */
    public String command;

    /** Short description shown in the suggestion list */
    public String description;

    /**
     * Static reply text for CUSTOM commands.
     * BotManager uses this to push the bot's reply message into the group.
     * Null for BUILT_IN commands (they have dynamic responses).
     */
    public String response;

    /** "builtin" | "custom" */
    public String kind;

    /** UID of the admin who created this command (custom only) */
    public String createdBy;

    public long createdAt;

    /** Admin can temporarily disable a custom command */
    public boolean enabled = true;

    public BotCommand() {}

    public BotCommand(String command, String description, String kind) {
        this.command     = command;
        this.description = description;
        this.kind        = kind;
        this.enabled     = true;
    }

    // ── Built-in command catalogue ─────────────────────────────────────────
    // These are synthesised at runtime in BotManager — never written to Firebase.

    public static BotCommand[] BUILT_INS = {
        new BotCommand("help",     "Show all available commands",          "builtin"),
        new BotCommand("poll",     "Create a quick yes/no poll",           "builtin"),
        new BotCommand("flip",     "Flip a coin — heads or tails",         "builtin"),
        new BotCommand("roll",     "Roll a dice — /roll [sides] e.g. /roll 20", "builtin"),
        new BotCommand("stats",    "Show group stats (members, messages)", "builtin"),
        new BotCommand("pin",      "Pin the last message (admin only)",    "builtin"),
        new BotCommand("mute",     "Mute a member — /mute @user (admin)", "builtin"),
        new BotCommand("kick",     "Remove a member — /kick @user (admin)", "builtin"),
        new BotCommand("announce", "Send a bold announcement to the group (admin)", "builtin"),
        new BotCommand("remind",   "Set a reminder — /remind 10m do laundry", "builtin"),
    };
}
