package com.callx.app.adapters

import android.view.*
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.callx.app.chat.R

/**
 * MediaThumbAdapter — 3-column media grid for GroupInfoActivity.
 */
class MediaThumbAdapter(
    private val urls: List<String>,
    private val listener: OnMediaClickListener
) : RecyclerView.Adapter<MediaThumbAdapter.VH>() {

    interface OnMediaClickListener { fun onClick(url: String) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val size = parent.measuredWidth / 3
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_thumb, parent, false)
        v.layoutParams = RecyclerView.LayoutParams(size, size)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val url = urls[pos]
        Glide.with(h.ivThumb.context).load(url).centerCrop()
            .placeholder(R.drawable.ic_gallery).into(h.ivThumb)
        h.itemView.setOnClickListener { listener.onClick(url) }
    }

    override fun getItemCount() = minOf(urls.size, 9)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivThumb: ImageView = v.findViewById(R.id.iv_media_thumb)
    }
}
