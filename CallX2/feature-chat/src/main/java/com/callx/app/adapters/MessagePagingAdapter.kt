package com.callx.app.adapters

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.view.*
import android.widget.*
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.callx.app.chat.R
import com.callx.app.models.Message
import com.callx.app.utils.FileUtils
import com.callx.app.utils.MediaCache
import java.text.SimpleDateFormat
import java.util.*

/**
 * MessagePagingAdapter — Paging 3 PagingDataAdapter for chat messages (Kotlin).
 *
 * Supports sent/received layout types, text, image, audio, file, and video.
 */
class MessagePagingAdapter(
    private val currentUid: String,
    private val isGroup: Boolean
) : PagingDataAdapter<Message, MessagePagingAdapter.VH>(DIFF) {

    interface ActionListener {
        fun onReply(m: Message)
        fun onDelete(m: Message)
        fun onEdit(m: Message)
        fun onReact(m: Message, emoji: String)
        fun onForward(m: Message)
        fun onStar(m: Message)
        fun onPin(m: Message)
        fun onCopy(m: Message)
        fun onInfo(m: Message)
    }

    companion object {
        private const val TYPE_SENT        = 1
        private const val TYPE_RECEIVED    = 2
        private const val TYPE_STATUS_SEEN = 3
        private const val TYPE_REEL_SEEN   = 4

        private val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) =
                a.messageId != null && a.messageId == b.messageId
            override fun areContentsTheSame(a: Message, b: Message) =
                a.messageId == b.messageId && a.text == b.text &&
                a.type == b.type && a.status == b.status &&
                a.timestamp == b.timestamp && a.edited == b.edited && a.deleted == b.deleted
        }
    }

    private val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
    var actionListener: ActionListener? = null
    private var player: MediaPlayer? = null
    private var playingPos = -1

    override fun getItemViewType(position: Int): Int {
        val m = getItem(position) ?: return TYPE_RECEIVED
        return when (m.type) {
            "status_seen" -> TYPE_STATUS_SEEN
            "reel_seen"   -> TYPE_REEL_SEEN
            else          -> if (m.senderId == currentUid) TYPE_SENT else TYPE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            TYPE_SENT        -> R.layout.item_message_sent
            TYPE_STATUS_SEEN -> R.layout.item_status_seen_bubble
            TYPE_REEL_SEEN   -> R.layout.item_reel_seen_bubble
            else             -> R.layout.item_message_received
        }
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = getItem(position) ?: return
        val sent = m.senderId == currentUid

        // Handle deleted messages
        if (m.deleted == true) {
            h.tvMessage?.text = "🚫 This message was deleted"
            h.tvMessage?.alpha = 0.6f
            bindFooter(h, m, sent)
            return
        }

        when (m.type) {
            "text", null -> bindText(h, m, sent)
            "image"      -> bindImage(h, m)
            "video"      -> bindVideo(h, m)
            "audio"      -> bindAudio(h, m, position)
            "file"       -> bindFile(h, m)
            else         -> bindText(h, m, sent)
        }

        // Sender name (group received messages)
        if (isGroup && !sent) {
            h.tvSenderName?.apply {
                text = m.senderName ?: ""
                visibility = if (!m.senderName.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
        }

        // Reply preview
        if (!m.replyToId.isNullOrEmpty()) {
            h.llReplyPreview?.visibility = View.VISIBLE
            h.tvReplySender?.text = m.replyToSenderName ?: ""
            h.tvReplyText?.text = m.replyToText ?: "[message]"
        } else {
            h.llReplyPreview?.visibility = View.GONE
        }

        // Edited label
        h.tvEdited?.visibility = if (m.edited == true) View.VISIBLE else View.GONE

        // Reactions
        val reactStr = m.reactions?.values?.groupingBy { it }?.eachCount()
            ?.entries?.joinToString(" ") { "${it.key} ${it.value}" } ?: ""
        if (reactStr.isNotEmpty()) {
            h.llReactions?.visibility = View.VISIBLE
            h.tvReactions?.text = reactStr
        } else {
            h.llReactions?.visibility = View.GONE
        }

        bindFooter(h, m, sent)
        setupLongPress(h, m, sent, h.itemView.context)
    }

    private fun bindText(h: VH, m: Message, sent: Boolean) {
        h.tvMessage?.apply { text = m.text ?: ""; visibility = View.VISIBLE }
        h.ivImage?.visibility = View.GONE
        h.flVideo?.visibility = View.GONE
        h.llAudio?.visibility = View.GONE
        h.llFile?.visibility  = View.GONE
    }

    private fun bindImage(h: VH, m: Message) {
        h.tvMessage?.visibility = View.GONE
        h.ivImage?.apply {
            visibility = View.VISIBLE
            Glide.with(context).load(m.mediaUrl).placeholder(R.drawable.ic_gallery).into(this)
            setOnClickListener { openMedia(context, m.mediaUrl, "image") }
        }
    }

    private fun bindVideo(h: VH, m: Message) {
        h.tvMessage?.visibility = View.GONE
        h.flVideo?.visibility = View.VISIBLE
        h.ivVideoThumb?.let { iv ->
            val thumb = m.thumbnailUrl ?: m.mediaUrl
            Glide.with(iv.context).load(thumb).placeholder(R.drawable.ic_video).into(iv)
            h.flVideo?.setOnClickListener { openMedia(iv.context, m.mediaUrl, "video") }
        }
        if (m.duration != null) {
            h.tvVideoDuration?.apply { text = formatDuration(m.duration!!); visibility = View.VISIBLE }
        }
    }

    private fun bindAudio(h: VH, m: Message, pos: Int) {
        h.tvMessage?.visibility = View.GONE
        h.llAudio?.visibility = View.VISIBLE
        h.tvAudioDur?.text = if (m.duration != null) formatDuration(m.duration!!) else ""
        h.btnPlayAudio?.setOnClickListener { togglePlay(h, m, pos) }
    }

    private fun bindFile(h: VH, m: Message) {
        h.tvMessage?.visibility = View.GONE
        h.llFile?.visibility = View.VISIBLE
        h.tvFileName?.text = m.fileName ?: "File"
        h.tvFileMeta?.text = if (m.fileSize != null) "${m.fileSize!! / 1024} KB" else ""
        h.ivDownload?.setOnClickListener {
            m.mediaUrl?.let { url ->
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                h.itemView.context.startActivity(intent)
            }
        }
    }

    private fun bindFooter(h: VH, m: Message, sent: Boolean) {
        h.tvTime?.text = if (m.timestamp != null) timeFmt.format(Date(m.timestamp!!)) else ""
        h.tvStarredIcon?.visibility = if (m.starred == true) View.VISIBLE else View.GONE

        if (h.tvStatus != null) {
            if (sent) {
                h.tvStatus.visibility = View.VISIBLE
                when (m.status ?: "sent") {
                    "read"      -> { h.tvStatus.text = "✓✓ "; h.tvStatus.setTextColor(0xFFFF00FF.toInt()) }
                    "delivered" -> { h.tvStatus.text = "✓✓"; h.tvStatus.setTextColor(0xFFFF00FF.toInt()) }
                    else        -> { h.tvStatus.text = "✓"; h.tvStatus.setTextColor(0xCCFFFFFF.toInt()) }
                }
            } else {
                h.tvStatus.visibility = View.GONE
            }
        }
    }

    private fun setupLongPress(h: VH, m: Message, sent: Boolean, ctx: Context) {
        h.itemView.setOnLongClickListener {
            showActionSheet(ctx, m, sent)
            true
        }
    }

    private fun showActionSheet(ctx: Context, m: Message, sent: Boolean) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val sv = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_message_actions, null)

        val emojis  = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")
        val emojiIds = listOf(R.id.emoji_heart, R.id.emoji_thumb, R.id.emoji_laugh, R.id.emoji_wow, R.id.emoji_sad, R.id.emoji_pray)
        emojis.zip(emojiIds).forEach { (emoji, id) ->
            sv.findViewById<TextView>(id)?.setOnClickListener { sheet.dismiss(); actionListener?.onReact(m, emoji) }
        }

        sv.findViewById<View>(R.id.action_reply).setOnClickListener  { sheet.dismiss(); actionListener?.onReply(m) }
        sv.findViewById<View>(R.id.action_star).setOnClickListener   { sheet.dismiss(); actionListener?.onStar(m) }
        sv.findViewById<View>(R.id.action_pin).setOnClickListener    { sheet.dismiss(); actionListener?.onPin(m) }
        sv.findViewById<View>(R.id.action_forward).setOnClickListener { sheet.dismiss(); actionListener?.onForward(m) }

        if (sent && m.type == "text" && m.deleted != true) {
            sv.findViewById<View>(R.id.action_edit)?.let { it.visibility = View.VISIBLE; it.setOnClickListener { sheet.dismiss(); actionListener?.onEdit(m) } }
        }
        if (m.type == "text" && !m.text.isNullOrEmpty() && m.deleted != true) {
            sv.findViewById<View>(R.id.action_copy)?.let { it.visibility = View.VISIBLE; it.setOnClickListener { sheet.dismiss(); actionListener?.onCopy(m) } }
        }
        if (sent) {
            sv.findViewById<View>(R.id.action_info)?.let { it.visibility = View.VISIBLE; it.setOnClickListener { sheet.dismiss(); actionListener?.onInfo(m) } }
        }
        if (m.deleted != true) {
            sv.findViewById<View>(R.id.action_delete)?.setOnClickListener { sheet.dismiss(); actionListener?.onDelete(m) }
        }

        sheet.setContentView(sv); sheet.show()
    }

    private fun openMedia(ctx: Context, url: String?, type: String) {
        if (url.isNullOrEmpty()) return
        ctx.startActivity(Intent().apply {
            setClassName(ctx.packageName, "com.callx.app.activities.MediaViewerActivity")
            putExtra("url", url); putExtra("type", type)
        })
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun togglePlay(h: VH, m: Message, pos: Int) {
        if (m.mediaUrl == null) return
        if (player != null && playingPos == pos && player!!.isPlaying) {
            player!!.pause()
            h.btnPlayAudio?.setImageResource(R.drawable.ic_play)
            return
        }
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null; playingPos = pos
        h.btnPlayAudio?.setImageResource(R.drawable.ic_pause)

        val cached = MediaCache.getCached(h.itemView.context, m.mediaUrl!!)
        if (cached != null) playFromFile(h, cached.absolutePath, pos)
        else MediaCache.get(h.itemView.context, m.mediaUrl!!, object : MediaCache.Callback {
            override fun onReady(file: java.io.File) { playFromFile(h, file.absolutePath, pos) }
            override fun onError(reason: String)     { playFromFile(h, m.mediaUrl!!, pos) }
        })
    }

    private fun playFromFile(h: VH, path: String, pos: Int) {
        try {
            player = MediaPlayer()
            if (!path.startsWith("http")) {
                val f = java.io.File(path)
                if (f.exists()) java.io.FileInputStream(f).use { player!!.setDataSource(it.fd) }
                else player!!.setDataSource(path)
            } else {
                player!!.setDataSource(path)
            }
            player!!.prepareAsync()
            player!!.setOnPreparedListener { it.start() }
            player!!.setOnCompletionListener {
                h.btnPlayAudio?.setImageResource(R.drawable.ic_play)
                try { it.release() } catch (_: Exception) {}
                player = null; playingPos = -1
            }
        } catch (e: Exception) {
            player?.let { try { it.release() } catch (_: Exception) {} }
            player = null
        }
    }

    fun releasePlayer() {
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null; playingPos = -1
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvMessage:     TextView?        = v.findViewById(R.id.tv_message)
        val tvTime:        TextView?        = v.findViewById(R.id.tv_time)
        val tvSenderName:  TextView?        = v.findViewById(R.id.tv_sender_name)
        val ivImage:       ImageView?       = v.findViewById(R.id.iv_image)
        val flVideo:       FrameLayout?     = v.findViewById(R.id.fl_video)
        val ivVideoThumb:  ImageView?       = v.findViewById(R.id.iv_video_thumb)
        val tvVideoDuration: TextView?      = v.findViewById(R.id.tv_duration)
        val llAudio:       LinearLayout?    = v.findViewById(R.id.ll_audio)
        val btnPlayAudio:  ImageButton?     = v.findViewById(R.id.btn_play_pause)
        val seekAudio:     SeekBar?         = v.findViewById(R.id.seek_audio)
        val tvAudioDur:    TextView?        = v.findViewById(R.id.tv_audio_dur)
        val llFile:        LinearLayout?    = v.findViewById(R.id.ll_file)
        val tvFileName:    TextView?        = v.findViewById(R.id.tv_file_name)
        val tvFileMeta:    TextView?        = v.findViewById(R.id.tv_file_meta)
        val ivDownload:    ImageButton?     = v.findViewById(R.id.btn_download)
        val tvStatus:      TextView?        = v.findViewById(R.id.tv_status)
        val llReplyPreview: LinearLayout?   = v.findViewById(R.id.ll_reply_preview)
        val tvReplySender:  TextView?       = v.findViewById(R.id.tv_reply_sender)
        val tvReplyText:    TextView?       = v.findViewById(R.id.tv_reply_text)
        val llReactions:   LinearLayout?    = v.findViewById(R.id.ll_reactions)
        val tvReactions:   TextView?        = v.findViewById(R.id.tv_reactions)
        val tvEdited:      TextView?        = v.findViewById(R.id.tv_edited)
        val tvPinnedLabel: TextView?        = v.findViewById(R.id.tv_pinned_label)
        val tvForwarded:   TextView?        = v.findViewById(R.id.tv_forwarded)
        val tvStarredIcon: TextView?        = v.findViewById(R.id.tv_starred_icon)
    }
}
