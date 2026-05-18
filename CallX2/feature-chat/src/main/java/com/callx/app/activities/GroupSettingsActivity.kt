package com.callx.app.activities

import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import com.callx.app.chat.R
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.HashMap

/**
 * GroupSettingsActivity — Group notification and permission settings (Kotlin).
 *
 * Settings stored in:
 *  - Firebase:  groups/{groupId}/settings/{uid}/...  (per-user)
 *  - Firebase:  groups/{groupId}/groupSettings/...   (group-level, admin-controlled)
 *  - SharedPrefs: "group_settings_{groupId}"         (offline cache)
 */
class GroupSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID   = "groupId"
        const val EXTRA_GROUP_NAME = "groupName"
        private const val PREF_PREFIX = "group_settings_"
    }

    private lateinit var groupId: String
    private lateinit var groupName: String
    private lateinit var currentUid: String
    private var isAdmin = false
    private lateinit var prefs: SharedPreferences

    // Notification views
    private lateinit var tvMuteStatus: TextView
    private lateinit var tvNotifTone: TextView
    private lateinit var swNotifPreview: SwitchCompat
    private lateinit var swMentionAlert: SwitchCompat

    // Media views
    private lateinit var swAutoDlImages: SwitchCompat
    private lateinit var swAutoDlVideos: SwitchCompat
    private lateinit var swSaveGallery: SwitchCompat

    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val title = if (uri != null) {
            prefs.edit().putString("notif_tone_uri", uri.toString()).apply()
            RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Custom"
        } else {
            prefs.edit().remove("notif_tone_uri").apply()
            "Default"
        }
        tvNotifTone.text = title
        pushPerUserSetting("notifToneUri", uri?.toString() ?: "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_settings)

        groupId    = intent.getStringExtra(EXTRA_GROUP_ID) ?: run { finish(); return }
        groupName  = intent.getStringExtra(EXTRA_GROUP_NAME) ?: "Group"
        currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: run { finish(); return }
        prefs      = getSharedPreferences("$PREF_PREFIX$groupId", MODE_PRIVATE)

        setupToolbar()
        bindViews()
        loadSettings()
        checkAdminRole()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "Group Settings" }
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun bindViews() {
        tvMuteStatus   = findViewById(R.id.tv_mute_status)
        tvNotifTone    = findViewById(R.id.tv_notif_tone)
        swNotifPreview = findViewById(R.id.sw_notif_preview)
        swMentionAlert = findViewById(R.id.sw_mention_alert)
        swAutoDlImages = findViewById(R.id.sw_auto_dl_images)
        swAutoDlVideos = findViewById(R.id.sw_auto_dl_videos)
        swSaveGallery  = findViewById(R.id.sw_save_gallery)

        // Mute click
        tvMuteStatus.setOnClickListener { showMuteDialog() }

        // Notification tone
        tvNotifTone.setOnClickListener {
            val cur = prefs.getString("notif_tone_uri", null)?.let { Uri.parse(it) }
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            }
            ringtonePicker.launch(intent)
        }

        swNotifPreview.setOnCheckedChangeListener { _, c -> savePref("notif_preview", c); pushPerUserSetting("notifPreview", c.toString()) }
        swMentionAlert.setOnCheckedChangeListener { _, c -> savePref("mention_alert", c); pushPerUserSetting("mentionAlert", c.toString()) }
        swAutoDlImages.setOnCheckedChangeListener { _, c -> savePref("auto_dl_images", c); pushPerUserSetting("autoDlImages", c.toString()) }
        swAutoDlVideos.setOnCheckedChangeListener { _, c -> savePref("auto_dl_videos", c); pushPerUserSetting("autoDlVideos", c.toString()) }
        swSaveGallery.setOnCheckedChangeListener  { _, c -> savePref("save_gallery", c);  pushPerUserSetting("saveGallery", c.toString()) }
    }

    private fun loadSettings() {
        tvMuteStatus.text = when (prefs.getLong("muted_until", 0L)) {
            0L   -> "Off"
            -1L  -> "Always"
            else -> "Muted"
        }
        swNotifPreview.isChecked = prefs.getBoolean("notif_preview", true)
        swMentionAlert.isChecked = prefs.getBoolean("mention_alert", true)
        swAutoDlImages.isChecked = prefs.getBoolean("auto_dl_images", true)
        swAutoDlVideos.isChecked = prefs.getBoolean("auto_dl_videos", false)
        swSaveGallery.isChecked  = prefs.getBoolean("save_gallery", false)
        val toneUri = prefs.getString("notif_tone_uri", null)
        tvNotifTone.text = if (toneUri != null) {
            RingtoneManager.getRingtone(this, Uri.parse(toneUri))?.getTitle(this) ?: "Custom"
        } else "Default"
    }

    private fun showMuteDialog() {
        val opts = arrayOf("Off", "1 hour", "8 hours", "1 week", "Always")
        AlertDialog.Builder(this).setTitle("Mute notifications").setItems(opts) { _, which ->
            val until = when (which) {
                0 -> 0L
                1 -> System.currentTimeMillis() + 3_600_000L
                2 -> System.currentTimeMillis() + 28_800_000L
                3 -> System.currentTimeMillis() + 604_800_000L
                else -> -1L
            }
            prefs.edit().putLong("muted_until", until).apply()
            tvMuteStatus.text = opts[which]
            pushPerUserSetting("mutedUntil", until.toString())
        }.show()
    }

    private fun checkAdminRole() {
        FirebaseUtils.getGroupMembersRef(groupId).child(currentUid).child("role")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    isAdmin = s.getValue(String::class.java)?.let { it == "admin" || it == "creator" } ?: false
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun savePref(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    private fun savePref(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    private fun pushPerUserSetting(key: String, value: String) {
        FirebaseUtils.getGroupsRef().child(groupId).child("settings").child(currentUid).child(key).setValue(value)
    }
}
