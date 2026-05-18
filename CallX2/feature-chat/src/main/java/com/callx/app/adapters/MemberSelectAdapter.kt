package com.callx.app.adapters

import android.view.*
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.callx.app.chat.R
import com.callx.app.models.User
import de.hdodenhof.circleimageview.CircleImageView

class MemberSelectAdapter(
    private val users: List<User>,
    private val selected: MutableSet<String>
) : RecyclerView.Adapter<MemberSelectAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_member_select, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val u = users[pos]
        h.tvName.text = u.name ?: "User"
        val avatarUrl = u.thumbUrl?.takeIf { it.isNotEmpty() } ?: u.photoUrl
        if (!avatarUrl.isNullOrEmpty()) Glide.with(h.itemView.context).load(avatarUrl).circleCrop().into(h.ivAvatar)
        else h.ivAvatar.setImageResource(R.drawable.ic_person)

        h.cb.setOnCheckedChangeListener(null)
        h.cb.isChecked = selected.contains(u.uid)
        h.cb.setOnCheckedChangeListener { _, checked ->
            if (checked) u.uid?.let { selected.add(it) } else u.uid?.let { selected.remove(it) }
        }
        h.itemView.setOnClickListener { h.cb.isChecked = !h.cb.isChecked }
    }

    override fun getItemCount() = users.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:   TextView        = v.findViewById(R.id.tv_name)
        val ivAvatar: CircleImageView = v.findViewById(R.id.iv_avatar)
        val cb:       CheckBox        = v.findViewById(R.id.cb_select)
    }
}
