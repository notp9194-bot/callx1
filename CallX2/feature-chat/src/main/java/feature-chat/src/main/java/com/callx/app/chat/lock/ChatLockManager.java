package com.callx.app.chat.lock;

  import android.content.Context;
  import android.content.SharedPreferences;
  import java.util.HashSet;
  import java.util.Set;

  /**
   * ChatLockManager — Lock individual chats with biometric/PIN.
   *
   * Locked chats show a blurred preview in chat list.
   * Opening requires biometric or app PIN verification.
   *
   * Storage: SharedPreferences (set of locked chatIds)
   */
  public class ChatLockManager {

      private static final String PREF = "chat_lock_prefs";
      private static final String KEY_LOCKED = "locked_chat_ids";

      public static void lockChat(Context ctx, String chatId) {
          Set<String> locked = getLockedChats(ctx);
          locked.add(chatId);
          save(ctx, locked);
      }

      public static void unlockChat(Context ctx, String chatId) {
          Set<String> locked = getLockedChats(ctx);
          locked.remove(chatId);
          save(ctx, locked);
      }

      public static boolean isLocked(Context ctx, String chatId) {
          return getLockedChats(ctx).contains(chatId);
      }

      public static Set<String> getLockedChats(Context ctx) {
          SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
          return new HashSet<>(prefs.getStringSet(KEY_LOCKED, new HashSet<>()));
      }

      private static void save(Context ctx, Set<String> locked) {
          ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
             .edit().putStringSet(KEY_LOCKED, locked).apply();
      }

      /** Call from ChatListAdapter — show lock icon + blur preview for locked chats */
      public static boolean shouldHidePreview(Context ctx, String chatId) {
          return isLocked(ctx, chatId);
      }
  }