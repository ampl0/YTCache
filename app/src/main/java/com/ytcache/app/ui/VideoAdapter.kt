package com.ytcache.app.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ytcache.app.R
import com.ytcache.app.data.VideoEntry
import com.ytcache.app.databinding.ItemVideoBinding
import com.ytcache.app.util.Format

class VideoAdapter(
    private val thumbs: ThumbLoader,
    private val onClick: (VideoEntry) -> Unit,
    private val onLongPress: (VideoEntry) -> Unit,
    private val onMenu: (View, VideoEntry) -> Unit,
    private val isSelected: (VideoEntry) -> Boolean,
    private val selectionMode: () -> Boolean,
) : ListAdapter<VideoEntry, VideoAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * `?attr/selectableItemBackground` is a theme attribute, not a resource:
     * resolving it means asking the theme for the drawable it points at.
     * Handing the attribute id to getDrawable throws Resources$NotFoundException
     * (that crashed the library as soon as the first card was bound).
     */
    private var rippleId = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        if (rippleId == 0) {
            val value = TypedValue()
            if (parent.context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground,
                    value,
                    true,
                )
            ) {
                rippleId = value.resourceId
            }
        }
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val binding = holder.binding
        val context = binding.root.context

        binding.titleText.text = item.title

        val duration = Format.duration(item.lengthSeconds)
        binding.durationBadge.text = duration
        binding.durationBadge.visibility = if (duration.isBlank()) View.GONE else View.VISIBLE

        val pieces = listOf(
            item.author.ifBlank { "YouTube" },
            Format.relative(item.capturedAt),
            Format.size(item.sizeBytes),
        ).filter { it.isNotBlank() }
        binding.subtitleText.text = pieces.joinToString(" · ")

        binding.statusBadge.visibility = if (item.partial) View.VISIBLE else View.GONE

        val progress = if (item.lengthSeconds > 0 && item.resumeMs > 0L) {
            ((item.resumeMs / 10L) / item.lengthSeconds.toLong()).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        binding.thumbProgress.visibility = if (progress > 0) View.VISIBLE else View.GONE
        binding.thumbProgress.progress = progress

        thumbs.load(binding.thumb, item.thumbPath)

        val selected = isSelected(item)
        binding.selectionCheck.visibility = if (selected) View.VISIBLE else View.GONE
        binding.root.strokeWidth = if (selected) {
            (2 * context.resources.displayMetrics.density).toInt()
        } else {
            (1 * context.resources.displayMetrics.density).toInt()
        }
        binding.root.strokeColor = ContextCompat.getColor(
            context,
            if (selected) R.color.brand_red else R.color.hairline,
        )
        binding.overflowButton.visibility = if (selectionMode()) View.GONE else View.VISIBLE
        binding.thumb.alpha = if (selectionMode() && !selected) 0.72f else 1f

        binding.root.setOnClickListener { onClick(item) }
        binding.root.setOnLongClickListener {
            onLongPress(item)
            true
        }
        binding.overflowButton.setOnClickListener { onMenu(it, item) }
        if (selectionMode()) {
            binding.root.foreground = null
        } else if (rippleId != 0) {
            binding.root.foreground = ContextCompat.getDrawable(context, rippleId)
        }
        binding.root.isLongClickable = true
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoEntry>() {
            override fun areItemsTheSame(oldItem: VideoEntry, newItem: VideoEntry): Boolean =
                oldItem.videoId == newItem.videoId

            override fun areContentsTheSame(oldItem: VideoEntry, newItem: VideoEntry): Boolean =
                oldItem.title == newItem.title &&
                    oldItem.author == newItem.author &&
                    oldItem.capturedAt == newItem.capturedAt &&
                    oldItem.sizeBytes == newItem.sizeBytes &&
                    oldItem.streamCount == newItem.streamCount &&
                    oldItem.hasThumb == newItem.hasThumb &&
                    oldItem.lengthSeconds == newItem.lengthSeconds &&
                    oldItem.resumeMs == newItem.resumeMs &&
                    oldItem.partial == newItem.partial &&
                    oldItem.playable == newItem.playable
        }
    }
}
