package com.callx.app.db.entity;

  /**
   * MESSAGEENTITY ADDITIONS — v2
   *
   * Add these fields to existing MessageEntity.java.
   * Room will auto-migrate or you can run a schema migration.
   *
   * Also add to MessageDao:
   *   @Query("SELECT * FROM messages WHERE chatId = :chatId AND expiresAt > 0 AND expiresAt <= :now")
   *   List<MessageEntity> getExpiredMessages(long now);
   *
   *   @Query("DELETE FROM messages WHERE id = :id")
   *   void deleteById(String id);
   *
   *   @Query("SELECT * FROM messages WHERE chatId = :chatId AND text LIKE :query ORDER BY timestamp DESC")
   *   List<MessageEntity> searchMessages(String chatId, String query);
   */
  public class MessageEntity_v2_additions {
      /*
       // Disappearing Messages
       public long expiresAt = 0L;   // 0 = never

       // Location
       public double locationLat = 0;
       public double locationLng = 0;
       public String locationName;

       // Poll (stored as JSON string — use Gson converter)
       public String pollJson;

       // Scheduled
       public boolean scheduled = false;
      */
  }