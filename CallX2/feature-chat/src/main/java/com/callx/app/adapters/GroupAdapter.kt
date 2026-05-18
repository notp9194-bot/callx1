package com.callx.app.adapters

import android.content.Context
import android.content.Intent
import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.callx.app.activities.GroupChatActivity
import com.callx.app.chat.R
import com.callx.app.models.Group
import de.hdodenhof.circleimageview.CircleImageView

class GroupAdapter(private val groups: List<Group>) :
    RecyclerView.Adapter<GroupAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val g = groups[pos]
        val ctx = h.itemView.context
        h.tvName.text = g.name ?: "Group"
        h.tvLast.text = g.lastMessage ?: ""
        h.tvMembers.text = "${g.members?.size ?: 0} members"

        if (!g.iconUrl.isNullOrEmpty()) {
            Glide.with(ctx).load(g.iconUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_group)
                .into(h.ivAvatar)
            h.ivAvatar.setPadding(0, 0, 0, 0)
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_group)
            val pad = (ctx.resources.displayMetrics.density * 12).toInt()
            h.ivAvatar.setPadding(pad, pad, pad, pad)
        }

        h.itemView.setOnClickListener {
            ctx.startActivity(Intent(ctx, GroupChatActivity::class.java).apply {
                putExtra("groupId",   g.id)
                putExtra("groupName", g.name)
                putExtra("groupIcon", g.iconUrl)
            })
        }
    }

    override fun getItemCount() = groups.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:    TextView        = v.findViewById(R.id.tv_group_name)
        val tvLast:    TextView        = v.findViewById(R.id.tv_last_message)
        val tvMembers: TextView        = v.findViewById(R.id.tv_member_count)
        val ivAvatar:  CircleImageView = v.findViewById(R.id.iv_avatar)
    }
}
