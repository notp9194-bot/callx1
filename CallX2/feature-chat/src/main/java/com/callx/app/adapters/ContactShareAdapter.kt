package com.callx.app.adapters

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.callx.app.chat.R
import com.callx.app.models.User
import de.hdodenhof.circleimageview.CircleImageView

class ContactShareAdapter(
    private val contacts: List<User>,
    private val listener: OnContactShareListener
) : RecyclerView.Adapter<ContactShareAdapter.ContactVH>() {

    interface OnContactShareListener {
        fun onShareToContact(contact: User)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact_share, parent, false)
        return ContactVH(v)
    }

    override fun onBindViewHolder(h: ContactVH, pos: Int) {
        val c = contacts[pos]
        h.tvName.text = c.name ?: "User"
        val avatarUrl = c.thumbUrl?.takeIf { it.isNotEmpty() } ?: c.photoUrl
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(h.itemView.context).load(avatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person).into(h.ivAvatar)
        } else h.ivAvatar.setImageResource(R.drawable.ic_person)
        h.itemView.setOnClickListener { listener.onShareToContact(c) }
    }

    override fun getItemCount() = contacts.size

    class ContactVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:   TextView        = v.findViewById(R.id.tv_name)
        val ivAvatar: CircleImageView = v.findViewById(R.id.iv_avatar)
    }
}
