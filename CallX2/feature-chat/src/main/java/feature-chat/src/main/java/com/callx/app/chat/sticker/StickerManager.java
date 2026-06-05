package com.callx.app.chat.sticker;

  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.FirebaseDatabase;
  import java.util.Arrays;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.UUID;

  /**
   * StickerManager — Sticker packs and GIF sending for chat.
   *
   * Sticker message type = "sticker"
   * GIF message type = "gif"
   * Fields: mediaUrl (CDN URL of sticker/GIF)
   *
   * Sticker packs stored in Firebase: stickerPacks/{packId}/stickers
   * GIFs sourced from Tenor API (free key: LIVDSRZULELA)
   */
  public class StickerManager {

      public static final String TENOR_BASE =
              "https://tenor.googleapis.com/v2/search?key=LIVDSRZULELA&limit=20&q=";

      /** Built-in emoji sticker URLs (replace with CDN URLs in production) */
      public static final List<String> DEFAULT_STICKERS = Arrays.asList(
          "https://fonts.gstatic.com/s/e/notoemoji/latest/1f600/512.webp",
          "https://fonts.gstatic.com/s/e/notoemoji/latest/1f602/512.webp",
          "https://fonts.gstatic.com/s/e/notoemoji/latest/1f60d/512.webp",
          "https://fonts.gstatic.com/s/e/notoemoji/latest/1f614/512.webp",
          "https://fonts.gstatic.com/s/e/notoemoji/latest/1f621/512.webp",
          "https://fonts.gstatic.com/s/e/notoemoji/latest/1f44f/512.webp",
          "https://fonts.gstatic.com/s/e/notoemoji/latest/1f525/512.webp",
          "https://fonts.gstatic.com/s/e/notoemoji/latest/2764/512.webp"
      );

      public static void sendSticker(String chatId, String stickerUrl) {
          sendMedia(chatId, "sticker", stickerUrl);
      }

      public static void sendGif(String chatId, String gifUrl) {
          sendMedia(chatId, "gif", gifUrl);
      }

      private static void sendMedia(String chatId, String type, String url) {
          String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
          String msgId = UUID.randomUUID().toString().replace("-", "");
          Map<String, Object> msg = new HashMap<>();
          msg.put("id", msgId);
          msg.put("senderId", myUid);
          msg.put("type", type);
          msg.put("mediaUrl", url);
          msg.put("timestamp", System.currentTimeMillis());
          msg.put("status", "sent");
          FirebaseDatabase.getInstance()
              .getReference("chats").child(chatId).child("messages").child(msgId)
              .setValue(msg);
      }

      /** Tenor GIF search URL — fetch in OkHttp/Retrofit */
      public static String getTenorSearchUrl(String query) {
          return TENOR_BASE + query.replace(" ", "+");
      }
  }