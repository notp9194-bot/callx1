package com.callx.app.adapters

import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.callx.app.chat.R
import com.callx.app.utils.Constants
import java.text.SimpleDateFormat
import java.util.*

/**
 * GroupMemberAdapter — Member list for GroupInfoActivity.
 */
class GroupMemberAdapter(
    private val items: MutableList<MemberItem>,
    private val currentUid: String,
    private val listener: OnMemberActionListener
) : RecyclerView.Adapter<GroupMemberAdapter.VH>() {

    interface OnMemberActionListener { fun onAction(uid: String, action: String) }

    data class MemberItem(
        val uid:      String,
        val name:     String,
        val role:     String,
        val photoUrl: String?,
        val online:   Boolean,
        val lastSeen: Long?
    )

    var isAdmin = false
    private val dateFmt = SimpleDateFormat("MMM dd", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group_member, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.tvName.text  = item.name
        h.tvRole.text  = when (item.role) { "admin" -> "Admin 👑"; "creator" -> "Creator"; else -> "" }
        h.tvRole.visibility = if (item.role == "member") View.GONE else View.VISIBLE
        h.onlineDot.visibility = if (item.online) View.VISIBLE else View.GONE

        if (!item.photoUrl.isNullOrEmpty()) {
            Glide.with(h.itemView.context).load(item.photoUrl).circleCrop()
                .placeholder(R.drawable.ic_person).into(h.ivAvatar)
        } else h.ivAvatar.setImageResource(R.drawable.ic_person)

        val lastSeenText = when {
            item.online  -> "Online"
            item.lastSeen != null -> "Last seen ${dateFmt.format(Date(item.lastSeen))}"
            else -> ""
        }
        h.tvLastSeen.text = lastSeenText

        h.btnMore.visibility = if (isAdmin && item.uid != currentUid) View.VISIBLE else View.GONE
        h.btnMore.setOnClickListener { showOptions(h.itemView, item) }
    }

    private fun showOptions(anchor: View, item: MemberItem) {
        val isAdm = item.role == "admin"
        val opts = arrayOf("View Profile", "Message", if (isAdm) "Revoke Admin" else "Make Admin", "Remove from Group")
        AlertDialog.Builder(anchor.context)
            .setTitle(item.name)
            .setItems(opts) { _, which ->
                val action = when (which) { 0 -> "view"; 1 -> "message"; 2 -> if (isAdm) "revoke_admin" else "make_admin"; else -> "remove" }
                listener.onAction(item.uid, action)
            }.show()
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:    TextView         = v.findViewById(R.id.tv_member_name)
        val tvRole:    TextView         = v.findViewById(R.id.tv_member_role)
        val tvLastSeen: TextView        = v.findViewById(R.id.tv_last_seen)
        val ivAvatar:  de.hdodenhof.circleimageview.CircleImageView = v.findViewById(R.id.iv_avatar)
        val onlineDot: View             = v.findViewById(R.id.view_online_dot)
        val btnMore:   ImageButton      = v.findViewById(R.id.btn_more)
    }
}
