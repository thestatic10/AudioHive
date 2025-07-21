package com.groupmusicplayer.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.groupmusicplayer.databinding.ItemTrackQueueBinding
import com.groupmusicplayer.models.Track

class QueueAdapter(
    private val onRemoveClick: (String, Track) -> Unit,
    private val onReorderClick: (Int, Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private var queue: List<Pair<String, Track>> = emptyList()

    fun updateQueue(newQueue: List<Pair<String, Track>>) {
        val diffCallback = QueueDiffCallback(queue, newQueue)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        queue = newQueue
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemTrackQueueBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return QueueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(queue[position], position)
    }

    override fun getItemCount(): Int = queue.size

    inner class QueueViewHolder(
        private val binding: ItemTrackQueueBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(queueItem: Pair<String, Track>, position: Int) {
            val (trackKey, track) = queueItem
            
            binding.tvTrackTitle.text = track.title
            binding.tvTrackArtist.text = track.artist
            binding.tvTrackDuration.text = formatDuration(track.duration)
            binding.tvAddedBy.text = "Added by ${track.addedBy}"
            binding.tvPosition.text = "${position + 1}"
            
            // Load album art
            // TODO: Implement with Glide
            // Glide.with(binding.root.context)
            //     .load(track.albumArt)
            //     .placeholder(R.drawable.ic_music_note)
            //     .into(binding.ivAlbumArt)
            
            binding.btnRemove.setOnClickListener {
                onRemoveClick(trackKey, track)
            }
            
            // TODO: Implement drag handle for reordering
            binding.ivDragHandle.setOnTouchListener { _, _ ->
                // Implement drag and drop
                false
            }
        }
        
        private fun formatDuration(durationMs: Long): String {
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / 1000) / 60
            return String.format("%d:%02d", minutes, seconds)
        }
    }

    private class QueueDiffCallback(
        private val oldList: List<Pair<String, Track>>,
        private val newList: List<Pair<String, Track>>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].first == newList[newItemPosition].first
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
} 