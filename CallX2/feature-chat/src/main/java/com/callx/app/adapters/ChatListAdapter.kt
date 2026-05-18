package com.callx.app.adapters

import android.content.Context
import android.content.Intent
import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.callx.app.activities.ChatActivity
import com.callx.app.cache.StatusCacheManager
import com.callx.app.chat.R
import com.callx.app.models.User
import de.hdodenhof.circleimageview.CircleImageView
import java.text.SimpleDateFormat
import java.util.*

class ChatListAdapter(
    private val contacts: MutableList<User>,
    private val selectionListener: SelectionListener?
) : RecyclerView.Adapter<ChatListAdapter.VH>() {

    interface SelectionListener {
        fun onSelectionStarted()
        fun onSelectionChanged()
        fun onSelectionCleared()
    }

    private val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private var specialRequestSenders: Set<String> = emptySet()
    private var isSelecting = false
    private val selectedUids = mutableSetOf<String>()

    fun setSpecialRequestSenders(set: Set<String>?) {
        specialRequestSenders = set ?: emptySet()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val u = contacts[pos]
        val ctx = h.itemView.context
        h.tvName.text = u.name ?: "User"

        val avatarUrl = u.thumbUrl?.takeIf { it.isNotEmpty() } ?: u.photoUrl
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(ctx).load(avatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(h.ivAvatar)
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person)
        }

        val scm = StatusCacheManager.getInstance(ctx)
        val uid = u.uid
        if (h.ivStoryRing != null && uid != null) {
            when {
                scm.hasUnseen(uid)  -> h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_unseen)
                scm.hasStatus(uid)  -> h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_seen)
                else                -> h.ivStoryRing.background = null
            }
        }

        h.tvLastMsg.text = u.lastMessage ?: ""
        val ts = u.lastMessageAt
        h.tvTime.text = if (ts != null && ts > 0) fmt.format(Date(ts)) else ""

        val unread = u.unread ?: 0L
        if (h.tvBadge != null) {
            h.tvBadge.visibility = if (unread > 0) View.VISIBLE else View.GONE
            if (unread > 0) h.tvBadge.text = if (unread > 99) "99+" else unread.toString()
        }

        val isSelected = selectedUids.contains(uid)
        h.itemView.alpha = if (isSelecting && !isSelected) 0.6f else 1.0f
        h.ivCheck?.visibility = if (isSelecting && isSelected) View.VISIBLE else View.GONE

        h.itemView.setOnClickListener {
            if (isSelecting && uid != null) {
                if (selectedUids.contains(uid)) selectedUids.remove(uid)
                else selectedUids.add(uid)
                notifyItemChanged(pos)
                selectionListener?.onSelectionChanged()
            } else {
                val intent = Intent(ctx, ChatActivity::class.java).apply {
                    putExtra("uid", uid)
                    putExtra("name", u.name)
                    putExtra("photo", u.photoUrl)
                }
                ctx.startActivity(intent)
            }
        }
        h.itemView.setOnLongClickListener {
            if (!isSelecting && uid != null) {
                isSelecting = true
                selectedUids.add(uid)
                notifyDataSetChanged()
                selectionListener?.onSelectionStarted()
            }
            true
        }
    }

    override fun getItemCount() = contacts.size

    fun getSelectedUids(): Set<String> = selectedUids.toSet()
    fun clearSelection() { isSelecting = false; selectedUids.clear(); notifyDataSetChanged(); selectionListener?.onSelectionCleared() }
    fun isInSelectionMode() = isSelecting

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:    TextView        = v.findViewById(R.id.tv_name)
        val tvLastMsg: TextView        = v.findViewById(R.id.tv_last_message)
        val tvTime:    TextView        = v.findViewById(R.id.tv_time)
        val ivAvatar:  CircleImageView = v.findViewById(R.id.iv_avatar)
        val tvBadge:   TextView?       = v.findViewById(R.id.tv_badge)
        val ivStoryRing: CircleImageView? = v.findViewById(R.id.iv_story_ring)
        val ivCheck:   android.widget.ImageView? = v.findViewById(R.id.iv_check)
    }
}
