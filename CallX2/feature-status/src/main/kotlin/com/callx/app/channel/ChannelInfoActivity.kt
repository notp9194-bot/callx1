package com.callx.app.channel

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.callx.app.db.entity.ChannelEntity
import com.callx.app.status.R
import com.callx.app.utils.AlertDialogStyler
import com.callx.app.viewmodel.ChannelViewModel
import com.google.android.material.switchmaterial.SwitchMaterial
import de.hdodenhof.circleimageview.CircleImageView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ChannelInfoActivity — Status → Channel viewer → ⋮ overflow menu → "Channel info".
 *
 * CallX2's status-module first Kotlin screen (feature-status ab Java + Kotlin
 * dono side-by-side compile karta hai, [com.callx.app.activities.AboutActivity]
 * jaisa hi code-built UI pattern — koi XML layout nahi, taaki resource wiring
 * ki zaroorat na pade).
 *
 * Poore-screen channel-info page, WhatsApp/Telegram "channel details" screen
 * jaisa: avatar, naam + verified badge, follower count, quick-action row
 * (Following / Forward / Share / Search), description + created-on date, aur
 * settings groups — Mute notifications (toggle), Public channel / Profile
 * privacy info, Clear media files, Unfollow channel, Report channel.
 *
 * Sab actions [ChannelViewModel] ke through hi actual Firebase/Room repo
 * operations chalate hain — [ChannelViewerActivity] jo overflow-menu items
 * already use karta hai unhi ka reuse.
 */
class ChannelInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID        = "channelId"
        const val EXTRA_CHANNEL_NAME      = "channelName"
        const val EXTRA_CHANNEL_ICON      = "channelIcon"
        const val EXTRA_CHANNEL_VERIFIED  = "channelVerified"
        const val EXTRA_CHANNEL_FOLLOWERS = "channelFollowers"
        const val EXTRA_OWNER_UID         = "ownerUid"

        /** Result extra: set when the user taps the "Search" quick action so the
         *  caller (ChannelViewerActivity) can expand its toolbar search field. */
        const val RESULT_EXTRA_ACTION = "channel_info_action"
        const val ACTION_OPEN_SEARCH  = "open_search"
    }

    private lateinit var viewModel: ChannelViewModel
    private var channelId: String? = null
    private var channelName: String? = null
    private var channelEntity: ChannelEntity? = null
    private var isFollowing = false
    private var isMuted = false
    private var isAdminOrOwner = false

    private lateinit var ivAvatar: CircleImageView
    private lateinit var tvName: TextView
    private lateinit var ivVerified: TextView
    private lateinit var tvFollowers: TextView
    private lateinit var tvDescription: TextView
    private lateinit var tvCreatedOn: TextView
    private lateinit var tvFollowActionLabel: TextView
    private lateinit var ivFollowActionIcon: TextView
    private lateinit var switchMute: SwitchMaterial
    private lateinit var tvPublicPrivateTitle: TextView
    private lateinit var tvPublicPrivateDesc: TextView
    private lateinit var rowUnfollow: View
    private lateinit var rowReport: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
        if (channelId == null) { finish(); return }

        viewModel = ViewModelProvider(this).get(ChannelViewModel::class.java)

        setContentView(buildUi())

        // Seed with whatever the caller already knew (instant paint), then
        // refine everything from the live Room/Firebase-synced entity below.
        channelName?.let { tvName.text = it }
        intent.getStringExtra(EXTRA_CHANNEL_ICON)?.takeIf { it.isNotEmpty() }?.let {
            Glide.with(this).load(it).circleCrop().into(ivAvatar)
        }
        ivVerified.visibility = if (intent.getBooleanExtra(EXTRA_CHANNEL_VERIFIED, false)) View.VISIBLE else View.GONE
        tvFollowers.text = formatHeader(intent.getLongExtra(EXTRA_CHANNEL_FOLLOWERS, 0L))

        viewModel.getChannel(channelId!!).observe(this) { ch ->
            if (ch == null) return@observe
            channelEntity = ch
            isFollowing = ch.isFollowed
            isMuted = ch.isMuted
            isAdminOrOwner = viewModel.isAdminOrOwner(ch)
            bindChannel(ch)
        }
    }

    // ── Data binding ─────────────────────────────────────────────────────

    private fun bindChannel(ch: ChannelEntity) {
        tvName.text = ch.name ?: channelName ?: ""
        ivVerified.visibility = if (ch.verified || ch.isVerified) View.VISIBLE else View.GONE
        tvFollowers.text = formatHeader(ch.followers)

        if (!ch.iconUrl.isNullOrEmpty()) {
            Glide.with(this).load(ch.iconUrl).circleCrop().into(ivAvatar)
        }

        val desc = StringBuilder()
        if (!ch.description.isNullOrEmpty()) desc.append(ch.description)
        tvDescription.text = desc.toString()
        tvDescription.visibility = if (desc.isEmpty()) View.GONE else View.VISIBLE

        tvCreatedOn.text = if (ch.createdAt > 0)
            "Created on " + SimpleDateFormat("M/d/yy", Locale.getDefault()).format(java.util.Date(ch.createdAt))
        else ""
        tvCreatedOn.visibility = if (ch.createdAt > 0) View.VISIBLE else View.GONE

        updateFollowAction()

        switchMute.setOnCheckedChangeListener(null)
        switchMute.isChecked = isMuted
        switchMute.setOnCheckedChangeListener { _, checked -> onMuteToggled(checked) }

        if (ch.isPrivate) {
            tvPublicPrivateTitle.text = "Private channel"
            tvPublicPrivateDesc.text = "Only people with an invite link can find and join this channel."
        } else {
            tvPublicPrivateTitle.text = "Public channel"
            tvPublicPrivateDesc.text = "Anyone can find this channel and see what's been shared."
        }

        rowUnfollow.visibility = if (isFollowing && !isAdminOrOwner) View.VISIBLE else View.GONE
        rowReport.visibility = if (!isAdminOrOwner) View.VISIBLE else View.GONE
    }

    private fun updateFollowAction() {
        if (isFollowing) {
            tvFollowActionLabel.text = "Following"
            ivFollowActionIcon.text = "✓"
        } else {
            tvFollowActionLabel.text = "Follow"
            ivFollowActionIcon.text = "+"
        }
    }

    private fun formatHeader(followers: Long): String {
        val formatted = NumberFormat.getInstance(Locale.US).format(followers)
        return "Channel • $formatted followers"
    }

    // ── Actions ──────────────────────────────────────────────────────────

    private fun onFollowActionTapped() {
        val ch = channelEntity ?: return
        if (isFollowing) {
            AlertDialogStyler.showReusableConfirm(
                this, "channel_info_unfollow", AlertDialogStyler.DialogSize.DEFAULT,
                "Unfollow " + (ch.name ?: channelName) + "?",
                "You will stop receiving updates from this channel.",
                "Unfollow", { viewModel.unfollowChannel(ch) },
                null, null,
                "Cancel"
            )
        } else {
            viewModel.followChannel(ch)
        }
    }

    private fun onMuteToggled(muted: Boolean) {
        val ch = channelEntity ?: return
        if (muted) {
            viewModel.muteChannel(ch, Long.MAX_VALUE)
            Toast.makeText(this, "Notifications muted", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.unmuteChannel(ch)
            Toast.makeText(this, "Notifications unmuted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onForwardTapped() = shareChannelLink("Forward " + (channelEntity?.name ?: channelName))

    private fun onShareTapped() = shareChannelLink("Share " + (channelEntity?.name ?: channelName))

    private fun shareChannelLink(chooserTitle: String?) {
        val ch = channelEntity
        val link = if (ch?.inviteLink != null && ch.inviteLink!!.isNotEmpty())
            ch.inviteLink else "https://callx.app/channel/$channelId"
        val name = ch?.name ?: channelName ?: "this channel"
        val share = Intent(Intent.ACTION_SEND)
        share.type = "text/plain"
        share.putExtra(Intent.EXTRA_TEXT, "Follow $name on CallX: $link")
        startActivity(Intent.createChooser(share, chooserTitle))
    }

    private fun onSearchTapped() {
        val result = Intent()
        result.putExtra(RESULT_EXTRA_ACTION, ACTION_OPEN_SEARCH)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun onClearMediaFilesTapped() {
        AlertDialogStyler.showReusableConfirm(
            this, "channel_info_clear_media", AlertDialogStyler.DialogSize.DEFAULT,
            "Clear media files?",
            "Downloaded photos and videos cached from this channel will be removed from your device. Media already sent still stays on the server.",
            "Clear", {
                Thread {
                    try { Glide.get(applicationContext).clearDiskCache() } catch (_: Exception) {}
                    runOnUiThread {
                        try { Glide.get(applicationContext).clearMemory() } catch (_: Exception) {}
                        Toast.makeText(this, "Media files cleared", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            },
            null, null,
            "Cancel"
        )
    }

    private fun onUnfollowRowTapped() {
        val ch = channelEntity ?: return
        AlertDialogStyler.showReusableConfirm(
            this, "channel_info_unfollow_row", AlertDialogStyler.DialogSize.DEFAULT,
            "Unfollow " + (ch.name ?: channelName) + "?",
            "You will stop receiving updates from this channel.",
            "Unfollow", {
                viewModel.unfollowChannel(ch)
                finish()
            },
            null, null,
            "Cancel"
        )
    }

    private fun onReportRowTapped() {
        val id = channelId ?: return
        val name = channelEntity?.name ?: channelName
        AlertDialogStyler.showReusableConfirm(
            this, "channel_info_report", AlertDialogStyler.DialogSize.DEFAULT,
            "Report channel",
            "Report $name for inappropriate content?",
            "Report", {
                viewModel.reportChannel(id)
                Toast.makeText(this, "Report submitted", Toast.LENGTH_SHORT).show()
            },
            null, null,
            "Cancel"
        )
    }

    private fun onProfilePrivacyTapped() {
        AlertDialogStyler.showReusableConfirm(
            this, "channel_info_privacy", AlertDialogStyler.DialogSize.DEFAULT,
            "Profile privacy",
            "Your profile photo, name, and phone number are never shown to people in this channel — only your channel activity (reactions, comments) may be visible depending on channel settings.",
            "OK", { },
            null, null,
            null
        )
    }

    // ── UI construction (pure code-built, matches the Telegram/WhatsApp-style
    //    channel-info screen — no XML layout) ────────────────────────────────

    private val bgColor = Color.parseColor("#121212")
    private val gapColor = Color.BLACK
    private val cardColor = Color.parseColor("#1A1A1A")
    private val titleColor = Color.WHITE
    private val subtitleColor = Color.parseColor("#9AA0A6")
    private val dangerColor = Color.parseColor("#F04A4A")
    private val circleBtnColor = Color.parseColor("#2C2C2E")

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(bgColor)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(bgColor)
        }

        // ── Back arrow ───────────────────────────────────────────────────
        val backBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(Color.WHITE)
            background = null
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { finish() }
        }
        outer.addView(backBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            leftMargin = dp(8); topMargin = dp(8)
        })

        // ── Avatar + name + followers ──────────────────────────────────────
        val headerBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(4), dp(24), dp(20))
        }

        ivAvatar = CircleImageView(this).apply {
            setBackgroundColor(Color.parseColor("#2C2C2E"))
        }
        headerBlock.addView(ivAvatar, LinearLayout.LayoutParams(dp(120), dp(120)))

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        tvName = TextView(this).apply {
            setTextColor(titleColor)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        nameRow.addView(tvName)

        ivVerified = TextView(this).apply {
            text = "✓"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4FA9F5"))
            }
        }
        val badgeSize = dp(20)
        nameRow.addView(ivVerified, LinearLayout.LayoutParams(badgeSize, badgeSize).apply { leftMargin = dp(8) })
        headerBlock.addView(nameRow)

        tvFollowers = TextView(this).apply {
            setTextColor(subtitleColor)
            textSize = 15f
            setPadding(0, dp(6), 0, 0)
        }
        headerBlock.addView(tvFollowers)

        outer.addView(headerBlock)

        // ── Quick action row: Following / Forward / Share / Search ─────────
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }

        val followAction = actionButtonEmoji("+", "Following") { onFollowActionTapped() }
        tvFollowActionLabel = followAction.second
        ivFollowActionIcon = followAction.first
        actionRow.addView(followAction.third, actionBtnLp())

        actionRow.addView(actionButtonEmoji("↪", "Forward") { onForwardTapped() }.third, actionBtnLp())
        actionRow.addView(actionButtonDrawable(R.drawable.ic_share_reel, "Share") { onShareTapped() }, actionBtnLp())
        actionRow.addView(actionButtonDrawable(R.drawable.ic_search, "Search") { onSearchTapped() }, actionBtnLp())

        outer.addView(actionRow)

        // ── Description + created-on ────────────────────────────────────
        val descBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardColor)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        tvDescription = TextView(this).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 15f
            setLineSpacing(dp(2).toFloat(), 1f)
            autoLinkMask = Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS
            setLinkTextColor(Color.parseColor("#4FA9F5"))
        }
        descBlock.addView(tvDescription)
        tvCreatedOn = TextView(this).apply {
            setTextColor(subtitleColor)
            textSize = 13f
            setPadding(0, dp(14), 0, 0)
        }
        descBlock.addView(tvCreatedOn)
        outer.addView(descBlock)

        outer.addView(sectionGap())

        // ── Mute notifications ───────────────────────────────────────────
        val muteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cardColor)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        muteRow.addView(rowEmojiIcon("🔔"))
        val muteLabel = TextView(this).apply {
            text = "Mute notifications"
            setTextColor(titleColor)
            textSize = 16f
        }
        muteRow.addView(muteLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(28) })
        switchMute = SwitchMaterial(this)
        muteRow.addView(switchMute)
        outer.addView(muteRow)

        outer.addView(sectionGap())

        // ── Public channel / Profile privacy info block ─────────────────
        val infoBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardColor)
        }
        infoBlock.addView(infoRow("🌐") { title, desc ->
            tvPublicPrivateTitle = title; tvPublicPrivateDesc = desc
        })
        infoBlock.addView(spacerDivider())
        infoBlock.addView(infoRowStatic("🔢", "Profile privacy",
            "This channel has added privacy for your profile and phone number. Tap to learn more.") { onProfilePrivacyTapped() })
        outer.addView(infoBlock)

        outer.addView(sectionGap())

        // ── Clear media / Unfollow / Report ─────────────────────────────
        val actionsBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardColor)
        }
        actionsBlock.addView(settingsActionRow("🖼", "Clear media files", titleColor) { onClearMediaFilesTapped() })

        rowUnfollow = settingsActionRow("🚪", "Unfollow channel", dangerColor) { onUnfollowRowTapped() }
        actionsBlock.addView(rowUnfollow)

        rowReport = settingsActionRow("👎", "Report channel", dangerColor) { onReportRowTapped() }
        actionsBlock.addView(rowReport)

        outer.addView(actionsBlock)
        outer.addView(sectionGap())

        scroll.addView(outer)
        root.addView(scroll)
        return root
    }

    // ── Small UI-builder helpers ─────────────────────────────────────────

    private fun actionBtnLp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** Round icon button built from an emoji glyph (used when no drawable fits). */
    private fun actionButtonEmoji(emoji: String, label: String, onClick: () -> Unit): Triple<TextView, TextView, LinearLayout> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val circle = TextView(this).apply {
            text = emoji
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(circleBtnColor)
            }
            setOnClickListener { onClick() }
        }
        col.addView(circle, LinearLayout.LayoutParams(dp(52), dp(52)))
        val labelView = TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(subtitleColor)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(labelView)
        return Triple(circle, labelView, col)
    }

    /** Round icon button built from a project drawable resource. */
    private fun actionButtonDrawable(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val circle = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(15)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(circleBtnColor)
            }
            setOnClickListener { onClick() }
        }
        col.addView(circle, LinearLayout.LayoutParams(dp(52), dp(52)))
        val labelView = TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(subtitleColor)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(labelView)
        return col
    }

    private fun rowEmojiIcon(emoji: String): TextView = TextView(this).apply {
        text = emoji
        textSize = 20f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
    }

    /** Builds the "Public channel" row and hands back its title/desc TextViews via callback. */
    private fun infoRow(emoji: String, bind: (TextView, TextView) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        row.addView(TextView(this).apply {
            text = emoji
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            setTextColor(titleColor)
            textSize = 16f
        }
        val desc = TextView(this).apply {
            setTextColor(subtitleColor)
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }
        col.addView(title)
        col.addView(desc)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(28) })
        bind(title, desc)
        return row
    }

    private fun infoRowStatic(emoji: String, title: String, desc: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = emoji
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(titleColor)
            textSize = 16f
        })
        col.addView(TextView(this).apply {
            text = desc
            setTextColor(subtitleColor)
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(28) })
        return row
    }

    private fun settingsActionRow(emoji: String, title: String, textColor: Int, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = emoji
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        row.addView(TextView(this).apply {
            text = title
            setTextColor(textColor)
            textSize = 16f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(28) })
        return row
    }

    private fun spacerDivider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#2A2A2A"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            leftMargin = dp(20); rightMargin = dp(20)
        }
    }

    private fun sectionGap(): View = View(this).apply {
        setBackgroundColor(gapColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
