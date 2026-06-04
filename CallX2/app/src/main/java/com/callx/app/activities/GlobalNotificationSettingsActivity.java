import com.callx.app.settings.ReelNotificationSettingsActivity;
package com.callx.app.activities;

  import android.app.NotificationManager;
  import android.app.TimePickerDialog;
  import android.content.Intent;
  import android.content.SharedPreferences;
  import android.os.Build;
  import android.os.Bundle;
  import android.provider.Settings;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.*;
  import androidx.appcompat.app.AppCompatActivity;
  import com.callx.app.R;
  import com.callx.app.utils.FirebaseUtils;
  import com.callx.app.utils.QuietHoursManager;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.*;

  /**
   * GlobalNotificationSettingsActivity — Unified notification settings for ALL categories.
   *
   * Sections:
   *  1. MESSAGES     — 1:1 chat notifications, inline reply, mute, snooze
   *  2. GROUPS       — Group message notifications, @mention priority, mute
   *  3. CALLS        — Incoming call, missed call, call-back button
   *  4. STATUS       — Status post, reaction, expiry reminder
   *  5. REELS        → opens ReelNotificationSettingsActivity (full 38-type control)
   *  6. QUIET HOURS  — DND schedule with category overrides
   *  7. ADVANCED     — Digest, contact-join, delivery-state badges, in-app banners
   *
   * All settings saved to Firebase: users/{uid}/globalNotifSettings
   * Also saved to SharedPreferences for fast local reads.
   */
  public class GlobalNotificationSettingsActivity extends AppCompatActivity {

      private static final String PREFS = "callx_global_notif";

      private LinearLayout container;
      private ScrollView   sv;
      private ProgressBar  progress;
      private ImageButton  btnBack;
      private Button       btnSave;

      private String myUid;
      private DatabaseReference settingsRef;

      private final Map<String, Switch>  switches   = new LinkedHashMap<>();
      private QuietHoursManager          qhm;

      // Keys for Firebase / SharedPreferences
      private static final String[][] SETTINGS = {
          // key, label, section-header-start?, default
          // --- MESSAGES ---
          {"sec_messages",      "💬  MESSAGES",              "header", ""},
          {"msg_enabled",       "Message notifications",     "true",   ""},
          {"msg_preview",       "Show message preview",      "true",   ""},
          {"msg_inline_reply",  "Inline reply from notif",   "true",   ""},
          {"msg_snooze",        "Snooze support (1h/8h/24h)","true",   ""},
          {"msg_img_preview",   "Image preview in notif",    "true",   ""},
          {"msg_read_receipt",  "Read-receipt notification", "false",  ""},
          // --- GROUPS ---
          {"sec_groups",        "👥  GROUPS",                "header", ""},
          {"grp_enabled",       "Group message notifications","true",  ""},
          {"grp_mention",       "@Mention priority alert",   "true",   ""},
          {"grp_per_mute",      "Per-group mute from notif", "true",   ""},
          {"grp_join_notif",    "Member join notification",  "true",   ""},
          // --- CALLS ---
          {"sec_calls",         "📞  CALLS",                 "header", ""},
          {"call_enabled",      "Incoming call notifications","true",  ""},
          {"call_missed",       "Missed call notification",  "true",   ""},
          {"call_callback_btn", "Call-back button in notif", "true",   ""},
          {"call_group",        "Missed calls grouped",      "true",   ""},
          // --- STATUS ---
          {"sec_status",        "📍  STATUS",                "header", ""},
          {"status_enabled",    "Contact status notification","true",  ""},
          {"status_reaction",   "Status reaction notification","true", ""},
          {"status_viewed",     "Status viewed notification","false",  ""},
          {"status_expiry",     "Status expiry reminder",    "true",   ""},
          // --- ADVANCED ---
          {"sec_advanced",      "⚙️  ADVANCED",              "header", ""},
          {"adv_digest",        "Unread digest (every 2h)",  "true",   ""},
          {"adv_contact_join",  "Contact joined CallX notif","true",   ""},
          {"adv_inapp_banner",  "In-app banner (foreground)","true",   ""},
          {"adv_delivery_badge","Delivery-state badges",     "true",   ""},
          {"adv_firebase_sync", "Sync notification history", "true",   ""},
      };

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);

          try { myUid = FirebaseUtils.getCurrentUid(); }
          catch (Exception e) { finish(); return; }

          qhm = new QuietHoursManager(this);
          settingsRef = FirebaseUtils.getUserRef(myUid).child("globalNotifSettings");

          buildLayout();
          loadSettings();
      }

      private void buildLayout() {
          LinearLayout root = new LinearLayout(this);
          root.setOrientation(LinearLayout.VERTICAL);
          root.setBackgroundColor(0xFF111111);

          // Toolbar
          LinearLayout tb = new LinearLayout(this);
          tb.setOrientation(LinearLayout.HORIZONTAL);
          tb.setGravity(android.view.Gravity.CENTER_VERTICAL);
          tb.setBackgroundColor(0xFF1A1A1A);
          tb.setPadding(dp(4), 0, dp(12), 0);
          LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
          root.addView(tb, tbLp);

          btnBack = new ImageButton(this);
          btnBack.setImageResource(R.drawable.ic_arrow_back);
          btnBack.setBackground(null);
          btnBack.getDrawable().setTint(0xFFFFFFFF);
          btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
          btnBack.setOnClickListener(v -> finish());
          tb.addView(btnBack);

          TextView tvTitle = new TextView(this);
          tvTitle.setText("Notification Settings");
          tvTitle.setTextColor(0xFFFFFFFF);
          tvTitle.setTextSize(18);
          tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
          tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
              ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
          tvTitle.setPadding(dp(8), 0, 0, 0);
          tb.addView(tvTitle);

          btnSave = new Button(this);
          btnSave.setText("Save");
          btnSave.setTextColor(0xFFFFFFFF);
          btnSave.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF3B5C));
          btnSave.setOnClickListener(v -> saveAll());
          LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
          tb.addView(btnSave, saveLp);

          progress = new ProgressBar(this);
          progress.setVisibility(View.GONE);
          progress.setLayoutParams(new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
          root.addView(progress);

          sv = new ScrollView(this);
          container = new LinearLayout(this);
          container.setOrientation(LinearLayout.VERTICAL);
          container.setPadding(0, 0, 0, dp(32));
          sv.addView(container);
          root.addView(sv, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

          buildRows();

          // Reels shortcut row
          addShortcutRow("🎬  Reel Notifications", "Control 38+ reel notification types",
              v -> startActivity(new Intent(this, ReelNotificationSettingsActivity.class)));

          // Quiet hours row (special)
          addQuietHoursSection();

          setContentView(root);
      }

      private void buildRows() {
          for (String[] setting : SETTINGS) {
              String key     = setting[0];
              String label   = setting[1];
              String defVal  = setting[2];

              if ("header".equals(defVal)) {
                  addHeader(label);
                  continue;
              }

              addToggleRow(key, label, "true".equals(defVal));
          }
      }

      private void addHeader(String text) {
          TextView tv = new TextView(this);
          tv.setText(text);
          tv.setTextColor(0xFF888888);
          tv.setTextSize(11);
          tv.setPadding(dp(16), dp(16), dp(16), dp(4));
          container.addView(tv);
      }

      private void addToggleRow(String key, String label, boolean def) {
          LinearLayout row = new LinearLayout(this);
          row.setOrientation(LinearLayout.HORIZONTAL);
          row.setGravity(android.view.Gravity.CENTER_VERTICAL);
          row.setPadding(dp(16), dp(13), dp(16), dp(13));

          TextView tv = new TextView(this);
          tv.setText(label);
          tv.setTextColor(0xFFFFFFFF);
          tv.setTextSize(14);
          tv.setLayoutParams(new LinearLayout.LayoutParams(0,
              ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

          Switch sw = new Switch(this);
          sw.setChecked(def);
          switches.put(key, sw);

          row.addView(tv);
          row.addView(sw);

          View divider = new View(this);
          divider.setBackgroundColor(0xFF222222);
          divider.setLayoutParams(new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, 1));

          container.addView(row);
          container.addView(divider);
      }

      private void addShortcutRow(String title, String subtitle, View.OnClickListener click) {
          addHeader(title);
          LinearLayout row = new LinearLayout(this);
          row.setOrientation(LinearLayout.VERTICAL);
          row.setPadding(dp(16), dp(12), dp(16), dp(12));
          row.setBackground(getDrawable(android.R.drawable.list_selector_background));
          row.setOnClickListener(click);

          TextView tv = new TextView(this);
          tv.setText(subtitle);
          tv.setTextColor(0xFFAAAAAA);
          tv.setTextSize(13);
          row.addView(tv);

          TextView arrow = new TextView(this);
          arrow.setText("Open →");
          arrow.setTextColor(0xFFFF3B5C);
          arrow.setTextSize(12);
          row.addView(arrow);

          container.addView(row);
          View div = new View(this);
          div.setBackgroundColor(0xFF222222);
          div.setLayoutParams(new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, 1));
          container.addView(div);
      }

      private void addQuietHoursSection() {
          addHeader("🌙  QUIET HOURS (DO NOT DISTURB)");

          // Master toggle row
          LinearLayout masterRow = new LinearLayout(this);
          masterRow.setOrientation(LinearLayout.HORIZONTAL);
          masterRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
          masterRow.setPadding(dp(16), dp(13), dp(16), dp(13));

          LinearLayout textCol = new LinearLayout(this);
          textCol.setOrientation(LinearLayout.VERTICAL);
          textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
              ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

          TextView tvLabel = new TextView(this);
          tvLabel.setText("Enable Quiet Hours");
          tvLabel.setTextColor(0xFFFFFFFF);
          tvLabel.setTextSize(14);

          TextView tvSummary = new TextView(this);
          tvSummary.setTextColor(0xFF888888);
          tvSummary.setTextSize(12);
          tvSummary.setText(qhm.isEnabled() ? qhm.getSummary() : "Tap to configure");
          textCol.addView(tvLabel);
          textCol.addView(tvSummary);

          Switch swQH = new Switch(this);
          swQH.setChecked(qhm.isEnabled());
          switches.put("quiet_hours_master", swQH);

          masterRow.addView(textCol);
          masterRow.addView(swQH);
          container.addView(masterRow);
          container.addView(divider());

          // Start time
          addTimePickerRow("Start Time", qhm.getStartHour(), qhm.getStartMinute(),
              (h, m) -> { qhm.setStartTime(h, m); });

          // End time
          addTimePickerRow("End Time", qhm.getEndHour(), qhm.getEndMinute(),
              (h, m) -> { qhm.setEndTime(h, m); });

          // Category overrides during quiet hours
          addHeader("   Allow during Quiet Hours:");
          addToggleRow("quiet_allow_calls",    "📞 Calls (always recommended)", true);
          addToggleRow("quiet_allow_messages", "💬 Messages", false);
          addToggleRow("quiet_allow_groups",   "👥 Group Messages", false);
          addToggleRow("quiet_allow_reels",    "🎬 Reel Notifications", false);

          // DND access button (Android 6+)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
              if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
                  Button btnDndAccess = new Button(this);
                  btnDndAccess.setText("Grant Do Not Disturb Access");
                  btnDndAccess.setBackgroundTintList(
                      android.content.res.ColorStateList.valueOf(0xFF007AFF));
                  btnDndAccess.setTextColor(0xFFFFFFFF);
                  btnDndAccess.setOnClickListener(v ->
                      startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
                  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                  lp.setMargins(dp(16), dp(8), dp(16), dp(4));
                  container.addView(btnDndAccess, lp);
              }
          }
      }

      interface TimePickListener { void onSet(int hour, int minute); }

      private void addTimePickerRow(String label, int curH, int curM, TimePickListener listener) {
          LinearLayout row = new LinearLayout(this);
          row.setOrientation(LinearLayout.HORIZONTAL);
          row.setGravity(android.view.Gravity.CENTER_VERTICAL);
          row.setPadding(dp(16), dp(12), dp(16), dp(12));

          TextView tv = new TextView(this);
          tv.setText(label);
          tv.setTextColor(0xFFFFFFFF);
          tv.setTextSize(14);
          tv.setLayoutParams(new LinearLayout.LayoutParams(0,
              ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

          TextView tvTime = new TextView(this);
          tvTime.setTextColor(0xFFFF3B5C);
          tvTime.setTextSize(14);
          tvTime.setText(fmt(curH, curM));
          tvTime.setPadding(dp(8), dp(4), dp(8), dp(4));

          row.addView(tv);
          row.addView(tvTime);
          row.setOnClickListener(v -> {
              new TimePickerDialog(this, (tp, h, m) -> {
                  tvTime.setText(fmt(h, m));
                  listener.onSet(h, m);
              }, curH, curM, false).show();
          });

          container.addView(row);
          container.addView(divider());
      }

      private String fmt(int h, int m) {
          String ap = h >= 12 ? "PM" : "AM";
          int h12 = h % 12; if (h12 == 0) h12 = 12;
          return String.format("%d:%02d %s", h12, m, ap);
      }

      private View divider() {
          View d = new View(this);
          d.setBackgroundColor(0xFF222222);
          d.setLayoutParams(new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, 1));
          return d;
      }

      private void loadSettings() {
          progress.setVisibility(View.VISIBLE);
          settingsRef.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
              @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                  if (isFinishing() || isDestroyed()) return;
                  progress.setVisibility(View.GONE);
                  for (Map.Entry<String, Switch> e : switches.entrySet()) {
                      Boolean val = snap.child(e.getKey()).getValue(Boolean.class);
                      if (val != null) e.getValue().setChecked(val);
                  }
                  // Apply quiet hours switches
                  Switch qhSwitch = switches.get("quiet_hours_master");
                  if (qhSwitch != null) qhSwitch.setChecked(qhm.isEnabled());
                  Switch qCalls = switches.get("quiet_allow_calls");
                  if (qCalls != null) qCalls.setChecked(qhm.isCallsAllowed());
                  Switch qMsg = switches.get("quiet_allow_messages");
                  if (qMsg != null) qMsg.setChecked(qhm.isMessagesAllowed());
                  Switch qGrp = switches.get("quiet_allow_groups");
                  if (qGrp != null) qGrp.setChecked(qhm.isGroupsAllowed());
                  Switch qReel = switches.get("quiet_allow_reels");
                  if (qReel != null) qReel.setChecked(qhm.isReelsAllowed());
              }
              @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                  if (!isFinishing()) progress.setVisibility(View.GONE);
              }
          });
      }

      private void saveAll() {
          progress.setVisibility(View.VISIBLE);
          Map<String, Object> m = new HashMap<>();
          for (Map.Entry<String, Switch> e : switches.entrySet())
              m.put(e.getKey(), e.getValue().isChecked());
          m.put("updatedAt", System.currentTimeMillis());

          // Persist quiet hours locally
          Switch qhMaster = switches.get("quiet_hours_master");
          if (qhMaster != null) qhm.setEnabled(qhMaster.isChecked());
          Switch qCalls = switches.get("quiet_allow_calls");
          if (qCalls != null) qhm.setCallsAllowed(qCalls.isChecked());
          Switch qMsg = switches.get("quiet_allow_messages");
          if (qMsg != null) qhm.setMessagesAllowed(qMsg.isChecked());
          Switch qGrp = switches.get("quiet_allow_groups");
          if (qGrp != null) qhm.setGroupsAllowed(qGrp.isChecked());
          Switch qReel = switches.get("quiet_allow_reels");
          if (qReel != null) qhm.setReelsAllowed(qReel.isChecked());

          settingsRef.updateChildren(m).addOnCompleteListener(task -> {
              if (!isFinishing()) {
                  progress.setVisibility(View.GONE);
                  Toast.makeText(this,
                      task.isSuccessful() ? "Settings saved!" : "Save failed",
                      Toast.LENGTH_SHORT).show();
                  if (task.isSuccessful()) finish();
              }
          });
      }

      private int dp(int v) {
          return (int)(v * getResources().getDisplayMetrics().density);
      }
  }
  