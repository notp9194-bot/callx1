package com.callx.app.utils;

/**
 * ActiveChatTracker — core module singleton.
 *
 * Tracks which chatId is currently visible on screen so that background
 * services (FCM, WorkManager) can skip duplicate delivery marking when
 * ChatActivity is already handling it.
 *
 * Lives in :core so both :feature-chat and :app (CallxMessagingService)
 * can access it without a circular dependency.
 *
 * Thread-safe: volatile field, written only on the main thread.
 */
public final class ActiveChatTracker {

    private static volatile String sActiveChatId = null;

    private ActiveChatTracker() {}

    /**
     * Call from ChatActivity.onResume() — marks this chat as currently visible.
     * FCM background delivery handler will skip this chatId.
     */
    public static void set(String chatId) {
        sActiveChatId = chatId;
    }

    /**
     * Call from ChatActivity.onPause() — clears the active chat.
     * FCM background delivery handler will resume marking deliveries.
     */
    public static void clear() {
        sActiveChatId = null;
    }

    /**
     * Returns the chatId currently open, or null if no chat is visible.
     * Safe to call from any thread.
     */
    public static String get() {
        return sActiveChatId;
    }

    /** Convenience: returns true if the given chatId is currently visible on screen. */
    public static boolean isActive(String chatId) {
        if (chatId == null) return false;
        return chatId.equals(sActiveChatId);
    }
}
