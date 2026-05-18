package com.callx.app.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.callx.app.chat.R
import com.callx.app.models.Message
import com.callx.app.utils.MediaCache
import java.text.SimpleDateFormat
import java.util.*

/**
 * MessageAdapter — Classic RecyclerView adapter for chat messages (Kotlin).
 * Used for starred messages, search results, and other non-paged lists.
 */
class MessageAdapter(
    private val messages: MutableList<Message>,
    private val currentUid: String,
    private val isGroup: Boolean,
    private val actionListener: ActionListener
) : RecyclerView.Adapter<MessageAdapter.VH>() {

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
        private const val TYPE_SENT     = 1
        private const val TYPE_RECEIVED = 2
    }

    private val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private var player: MediaPlayer? = null
    private var playingPos = -1

    override fun getItemViewType(pos: Int): Int {
        val m = messages[pos]
        return if (m.senderId == currentUid) TYPE_SENT else TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_SENT) R.layout.item_message_sent else R.layout.item_message_received
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = messages[pos]
        val sent = m.senderId == currentUid

        if (m.deleted == true) {
            h.tvMessage?.text = "🚫 This message was deleted"
            h.tvMessage?.alpha = 0.6f
            h.tvTime?.text = if (m.timestamp != null) timeFmt.format(Date(m.timestamp!!)) else ""
            return
        }

        h.tvMessage?.text = m.text ?: ""
        h.tvSenderName?.apply {
            text = m.senderName ?: ""
            visibility = if (isGroup && !sent && !m.senderName.isNullOrEmpty()) View.VISIBLE else View.GONE
        }

        when (m.type) {
            "image" -> {
                h.ivImage?.apply { visibility = View.VISIBLE; Glide.with(context).load(m.mediaUrl).into(this) }
            }
            "audio" -> {
                h.llAudio?.visibility = View.VISIBLE
                h.btnPlayAudio?.setOnClickListener { togglePlay(h, m, pos) }
            }
            "file" -> {
                h.llFile?.visibility = View.VISIBLE
                h.tvFileName?.text = m.fileName ?: "File"
            }
        }

        if (!m.replyToId.isNullOrEmpty()) {
            h.llReplyPreview?.visibility = View.VISIBLE
            h.tvReplySender?.text = m.replyToSenderName ?: ""
            h.tvReplyText?.text = m.replyToText ?: "[message]"
        } else h.llReplyPreview?.visibility = View.GONE

        h.tvTime?.text = if (m.timestamp != null) timeFmt.format(Date(m.timestamp!!)) else ""
        h.tvStarredIcon?.visibility = if (m.starred == true) View.VISIBLE else View.GONE

        if (sent && h.tvStatus != null) {
            h.tvStatus.visibility = View.VISIBLE
            when (m.status ?: "sent") {
                "read"      -> { h.tvStatus.text = "✓✓ "; h.tvStatus.setTextColor(0xFFFF00FF.toInt()) }
                "delivered" -> { h.tvStatus.text = "✓✓"; h.tvStatus.setTextColor(0xFFFF00FF.toInt()) }
                else        -> { h.tvStatus.text = "✓"; h.tvStatus.setTextColor(0xCCFFFFFF.toInt()) }
            }
        }

        h.itemView.setOnLongClickListener {
            showActions(h.itemView.context, m, sent)
            true
        }
    }

    private fun showActions(ctx: Context, m: Message, sent: Boolean) {
        val options = mutableListOf("Reply", "Star/Unstar", "Forward", "Pin/Unpin")
        if (sent && m.type == "text" && m.deleted != true) options.add("Edit")
        if (m.type == "text" && !m.text.isNullOrEmpty()) options.add("Copy")
        if (sent) options.add("Info")
        if (m.deleted != true) options.add("Delete")

        android.app.AlertDialog.Builder(ctx)
            .setTitle("Message")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Reply"      -> actionListener.onReply(m)
                    "Star/Unstar"-> actionListener.onStar(m)
                    "Forward"    -> actionListener.onForward(m)
                    "Pin/Unpin"  -> actionListener.onPin(m)
                    "Edit"       -> actionListener.onEdit(m)
                    "Copy"       -> actionListener.onCopy(m)
                    "Info"       -> actionListener.onInfo(m)
                    "Delete"     -> actionListener.onDelete(m)
                }
            }.show()
    }

    private fun togglePlay(h: VH, m: Message, pos: Int) {
        if (m.mediaUrl == null) return
        if (player != null && playingPos == pos && player!!.isPlaying) {
            player!!.pause(); h.btnPlayAudio?.setImageResource(R.drawable.ic_play); return
        }
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null; playingPos = pos
        h.btnPlayAudio?.setImageResource(R.drawable.ic_pause)
        MediaCache.get(h.itemView.context, m.mediaUrl!!, object : MediaCache.Callback {
            override fun onReady(file: java.io.File) { playFile(h, file.absolutePath, pos) }
            override fun onError(reason: String) { playFile(h, m.mediaUrl!!, pos) }
        })
    }

    private fun playFile(h: VH, path: String, pos: Int) {
        try {
            player = MediaPlayer()
            player!!.setDataSource(path)
            player!!.prepareAsync()
            player!!.setOnPreparedListener { it.start() }
            player!!.setOnCompletionListener {
                h.btnPlayAudio?.setImageResource(R.drawable.ic_play)
                try { it.release() } catch (_: Exception) {}
                player = null; playingPos = -1
            }
        } catch (e: Exception) { player = null }
    }

    fun releasePlayer() {
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null; playingPos = -1
    }

    override fun getItemCount() = messages.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvMessage:    TextView?     = v.findViewById(R.id.tv_message)
        val tvTime:       TextView?     = v.findViewById(R.id.tv_time)
        val tvSenderName: TextView?     = v.findViewById(R.id.tv_sender_name)
        val ivImage:      ImageView?    = v.findViewById(R.id.iv_image)
        val llAudio:      LinearLayout? = v.findViewById(R.id.ll_audio)
        val btnPlayAudio: ImageButton?  = v.findViewById(R.id.btn_play_pause)
        val llFile:       LinearLayout? = v.findViewById(R.id.ll_file)
        val tvFileName:   TextView?     = v.findViewById(R.id.tv_file_name)
        val tvStatus:     TextView?     = v.findViewById(R.id.tv_status)
        val llReplyPreview: LinearLayout? = v.findViewById(R.id.ll_reply_preview)
        val tvReplySender:  TextView?   = v.findViewById(R.id.tv_reply_sender)
        val tvReplyText:    TextView?   = v.findViewById(R.id.tv_reply_text)
        val tvStarredIcon:  TextView?   = v.findViewById(R.id.tv_starred_icon)
    }
}
