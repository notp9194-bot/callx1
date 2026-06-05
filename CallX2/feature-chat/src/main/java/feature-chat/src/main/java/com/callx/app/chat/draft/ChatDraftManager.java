package com.callx.app.chat.draft;

  import android.content.Context;
  import android.content.SharedPreferences;

  /**
   * ChatDraftManager — Auto-saves unsent message drafts per chat.
   *
   * Storage: SharedPreferences (lightweight, instant read/write)
   * Key: draft_{chatId}
   *
   * Usage:
   *   ChatDraftManager.save(ctx, chatId, editText.getText().toString());
   *   String draft = ChatDraftManager.get(ctx, chatId);
   *   ChatDraftManager.clear(ctx, chatId);
   */
  public class ChatDraftManager {

      private static final String PREF_NAME = "chat_drafts";

      private ChatDraftManager() {}

      public static void save(Context ctx, String chatId, String text) {
          if (text == null || text.trim().isEmpty()) {
              clear(ctx, chatId);
              return;
          }
          prefs(ctx).edit().putString(key(chatId), text).apply();
      }

      public static String get(Context ctx, String chatId) {
          return prefs(ctx).getString(key(chatId), "");
      }

      public static void clear(Context ctx, String chatId) {
          prefs(ctx).edit().remove(key(chatId)).apply();
      }

      public static boolean hasDraft(Context ctx, String chatId) {
          String draft = get(ctx, chatId);
          return draft != null && !draft.trim().isEmpty();
      }

      private static SharedPreferences prefs(Context ctx) {
          return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
      }

      private static String key(String chatId) {
          return "draft_" + chatId;
      }
  }