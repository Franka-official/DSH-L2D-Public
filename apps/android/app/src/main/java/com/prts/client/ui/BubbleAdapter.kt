package com.prts.client.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.prts.client.R
import com.prts.client.databinding.ItemBubbleBinding

data class Bubble(val who: String, val text: String,
    val translation: String? = null, val translationLanguage: String? = null)

class BubbleAdapter : RecyclerView.Adapter<BubbleAdapter.VH>() {
    private val items = ArrayList<Bubble>()
    private val replies = LinkedHashMap<String, Int>()

    fun upsertReply(key: String, value: Bubble): Int {
        val existing = replies[key]
        if (existing != null) {
            items[existing] = value
            notifyItemChanged(existing, "streamed-text")
            return existing
        }
        val position = items.size
        replies[key] = position
        add(value)
        return position
    }

    fun add(b: Bubble) {
        items.add(b)
        notifyItemInserted(items.size - 1)
    }

    class VH(val b: ItemBubbleBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBubbleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        val me = it.who == "我"
        holder.b.bubbleName.text = if (me) "你" else it.who
        holder.b.bubbleText.text = it.text
        holder.b.bubbleText.setBackgroundResource(if (me) R.drawable.ui_bubble_out else R.drawable.ui_bubble_in)
        val align = if (me) Gravity.END else Gravity.START
        holder.b.root.gravity = align
        (holder.b.bubbleText.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = align
        holder.b.bubbleName.gravity = align
        val translation = it.translation?.trim()?.takeIf { value -> !me && value.isNotEmpty() }
        holder.b.bubbleTranslation.text = translation.orEmpty()
        holder.b.bubbleTranslation.visibility = if (translation == null) View.GONE else View.VISIBLE
        holder.b.bubbleTranslation.contentDescription = translation?.let { value ->
            val language = when (it.translationLanguage) { "ja" -> "日文"; "en" -> "英文"; else -> "" }
            "${language}翻译：$value"
        }
    }

    override fun getItemCount() = items.size
}
