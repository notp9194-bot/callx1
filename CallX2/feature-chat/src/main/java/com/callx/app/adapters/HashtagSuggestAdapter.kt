package com.callx.app.adapters

import android.content.Context
import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.callx.app.chat.R

/**
 * HashtagSuggestAdapter — Horizontal scrollable hashtag chips.
 */
class HashtagSuggestAdapter(
    private val context: Context,
    private val hashtags: List<String>,
    private val listener: OnHashtagClickListener
) : RecyclerView.Adapter<HashtagSuggestAdapter.VH>() {

    interface OnHashtagClickListener { fun onHashtagClick(hashtag: String) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(context).inflate(R.layout.item_hashtag_chip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val tag = hashtags[position]
        h.tvHashtag.text = "#$tag"
        h.itemView.setOnClickListener { listener.onHashtagClick(tag) }
    }

    override fun getItemCount() = hashtags.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvHashtag: TextView = v.findViewById(R.id.tv_hashtag)
    }
}
