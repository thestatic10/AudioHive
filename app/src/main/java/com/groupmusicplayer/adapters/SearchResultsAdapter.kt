package com.groupmusicplayer.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.groupmusicplayer.databinding.ItemTrackSearchBinding
import com.groupmusicplayer.models.SpotifyTrack

class SearchResultsAdapter(
    private val onAddClick: (SpotifyTrack) -> Unit
) : RecyclerView.Adapter<SearchResultsAdapter.SearchViewHolder>() {

    private var tracks: List<SpotifyTrack> = emptyList()

    fun updateTracks(newTracks: List<SpotifyTrack>) {
        val diffCallback = SearchDiffCallback(tracks, newTracks)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        tracks = newTracks
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val binding = ItemTrackSearchBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SearchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        holder.bind(tracks[position])
    }

    override fun getItemCount(): Int = tracks.size

    inner class SearchViewHolder(
        private val binding: ItemTrackSearchBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(track: SpotifyTrack) {
            binding.tvTrackTitle.text = track.name
            binding.tvTrackArtist.text = track.artists.joinToString(", ") { it.name }
            binding.tvTrackAlbum.text = track.album.name
            binding.tvTrackDuration.text = formatDuration(track.duration_ms)
            
            // Load album art
            val albumArtUrl = track.album.images.firstOrNull()?.url
            // TODO: Implement with Glide
            // Glide.with(binding.root.context)
            //     .load(albumArtUrl)
            //     .placeholder(R.drawable.ic_music_note)
            //     .into(binding.ivAlbumArt)
            
            binding.btnAdd.setOnClickListener {
                onAddClick(track)
            }
            
            // Show preview indicator if available
            binding.ivPreview.visibility = if (track.preview_url != null) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            
            // Handle preview play (optional feature)
            binding.ivPreview.setOnClickListener {
                // TODO: Implement preview playback
                track.preview_url?.let { previewUrl ->
                    // Play 30-second preview
                }
            }
        }
        
        private fun formatDuration(durationMs: Long): String {
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / 1000) / 60
            return String.format("%d:%02d", minutes, seconds)
        }
    }

    private class SearchDiffCallback(
        private val oldList: List<SpotifyTrack>,
        private val newList: List<SpotifyTrack>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
} 