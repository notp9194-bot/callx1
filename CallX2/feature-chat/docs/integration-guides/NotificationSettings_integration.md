// ═══════════════════════════════════════════════════════════════════
// Per-Chat Notification Settings — Complete Integration Guide
// ═══════════════════════════════════════════════════════════════════

// ── PART 1: ChatActivity.java — open settings screen ──────────────────

// In overflow menu (menu_chat.xml):
/*
<item
    android:id="@+id/action_notif_settings"
    android:title="🔔 Notification Settings"
    android:showAsAction="never"/>
*/

// In onOptionsItemSelected():
case R.id.action_notif_settings:
    Intent i = new Intent(this, NotificationSettingsActivity.class);
    i.putExtra(NotificationSettingsActivity.EXTRA_CHAT_ID,      chatId);
    i.putExtra(NotificationSettingsActivity.EXTRA_PARTNER_NAME, partnerName);
    startActivity(i);
    return true;

// ── PART 2: Mute badge in chat list ──────────────────────────────────
// In ChatsAdapter.onBindViewHolder() — show 🔇 if muted:
boolean muted = ChatNotificationManager.isChatMuted(context, chat.chatId);
holder.ivMuteIcon.setVisibility(muted ? View.VISIBLE : View.GONE);

// ── PART 3: FCM Message Handler — respect mute ────────────────────────
// In your FirebaseMessagingService.onMessageReceived():
@Override
public void onMessageReceived(RemoteMessage rm) {
    String chatId = rm.getData().get("chatId");

    // Check mute BEFORE showing notification
    if (chatId != null && ChatNotificationManager.isChatMuted(this, chatId)) {
        return;  // Drop silently
    }

    // Get per-chat manager
    ChatNotificationManager notifMgr = new ChatNotificationManager(this, chatId);
    String partnerName = rm.getData().get("senderName");
    String messageText = rm.getData().get("body");

    // Create channel (Android 8+)
    String channelId = notifMgr.getOrCreateChannel(partnerName);

    // Build notification
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(partnerName)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH);

    // Apply per-chat settings (ringtone, vibration, LED, preview)
    notifMgr.applyToNotification(builder, partnerName, messageText);

    // Show notification
    NotificationManagerCompat.from(this).notify(chatId.hashCode(), builder.build());
}

// ── PART 4: Mute badge drawable (in chat list item) ──────────────────
// item_chat_list.xml — add after last_message TextView:
/*
<ImageView
    android:id="@+id/iv_mute_icon"
    android:layout_width="14dp"
    android:layout_height="14dp"
    android:src="@drawable/ic_notifications_off"
    android:tint="#AAAAAA"
    android:visibility="gone"
    android:layout_gravity="center_vertical"
    android:layout_marginStart="4dp"/>
*/

// ── PART 5: AndroidManifest.xml ──────────────────────────────────────
/*
<activity
    android:name="com.callx.app.activities.NotificationSettingsActivity"
    android:theme="@style/AppTheme"
    android:exported="false"
    android:parentActivityName="com.callx.app.activities.ChatActivity"/>
*/

// ── PART 6: ChatListActivity — show mute indicator ───────────────────
// In chat list adapter onBindViewHolder():
ChatNotificationManager mgr = new ChatNotificationManager(context, item.chatId);
if (mgr.isMuted()) {
    holder.tvLastMessage.setTextColor(0xFFAAAAAA); // dim text when muted
    holder.ivMuteIcon.setVisibility(View.VISIBLE);
} else {
    holder.tvLastMessage.setTextColor(0xFF333333);
    holder.ivMuteIcon.setVisibility(View.GONE);
}

// ── PART 7: Required permissions (AndroidManifest.xml) ───────────────
/*
<!-- For posting notifications (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

<!-- For vibration -->
<uses-permission android:name="android.permission.VIBRATE"/>
*/

// ── PART 8: styles.xml — add row styles used in activity_notification_settings.xml ──
/*
<style name="SectionHeader">
    <item name="android:layout_width">match_parent</item>
    <item name="android:layout_height">32dp</item>
    <item name="android:gravity">center_vertical</item>
    <item name="android:paddingStart">16dp</item>
    <item name="android:textColor">@color/brand_primary</item>
    <item name="android:textSize">11sp</item>
    <item name="android:textStyle">bold</item>
    <item name="android:background">@color/surface_variant</item>
    <item name="android:layout_marginTop">8dp</item>
</style>

<style name="SettingsRow">
    <item name="android:layout_width">match_parent</item>
    <item name="android:layout_height">64dp</item>
    <item name="android:orientation">horizontal</item>
    <item name="android:gravity">center_vertical</item>
    <item name="android:paddingStart">16dp</item>
    <item name="android:paddingEnd">16dp</item>
    <item name="android:background">@color/surface</item>
    <item name="android:clickable">true</item>
    <item name="android:focusable">true</item>
    <item name="android:foreground">?attr/selectableItemBackground</item>
</style>

<style name="SettingsTitle">
    <item name="android:layout_width">wrap_content</item>
    <item name="android:layout_height">wrap_content</item>
    <item name="android:textColor">@color/text_primary</item>
    <item name="android:textSize">15sp</item>
</style>

<style name="SettingsSubtitle">
    <item name="android:layout_width">wrap_content</item>
    <item name="android:layout_height">wrap_content</item>
    <item name="android:textColor">@color/text_secondary</item>
    <item name="android:textSize">13sp</item>
    <item name="android:layout_marginTop">2dp</item>
</style>

<style name="SettingsIcon">
    <item name="android:layout_width">24dp</item>
    <item name="android:layout_height">24dp</item>
    <item name="android:layout_marginEnd">16dp</item>
</style>

<style name="SettingsTextBlock">
    <item name="android:layout_width">0dp</item>
    <item name="android:layout_height">wrap_content</item>
    <item name="android:layout_weight">1</item>
    <item name="android:orientation">vertical</item>
</style>

<style name="ChevronIcon">
    <item name="android:layout_width">16dp</item>
    <item name="android:layout_height">16dp</item>
    <item name="android:src">@drawable/ic_chevron_right</item>
    <item name="android:tint">@color/text_secondary</item>
</style>

<style name="Divider">
    <item name="android:layout_width">match_parent</item>
    <item name="android:layout_height">0.5dp</item>
    <item name="android:background">@color/divider</item>
    <item name="android:layout_marginStart">56dp</item>
</style>
*/
