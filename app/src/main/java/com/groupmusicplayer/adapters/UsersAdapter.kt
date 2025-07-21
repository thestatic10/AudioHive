package com.groupmusicplayer.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.groupmusicplayer.databinding.ItemUserBinding
import com.groupmusicplayer.models.User

class UsersAdapter : RecyclerView.Adapter<UsersAdapter.UserViewHolder>() {

    private var users: List<User> = emptyList()

    fun updateUsers(newUsers: List<User>) {
        val diffCallback = UserDiffCallback(users, newUsers)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        users = newUsers
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val binding = ItemUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(users[position])
    }

    override fun getItemCount(): Int = users.size

    inner class UserViewHolder(
        private val binding: ItemUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(user: User) {
            binding.tvUserName.text = user.name
            
            // Show host badge
            if (user.isHost) {
                binding.chipHost.visibility = android.view.View.VISIBLE
            } else {
                binding.chipHost.visibility = android.view.View.GONE
            }
            
            // Show online status
            binding.ivOnlineStatus.setImageResource(
                if (user.isOnline) {
                    com.groupmusicplayer.R.drawable.ic_online
                } else {
                    com.groupmusicplayer.R.drawable.ic_offline
                }
            )
            
            // Set online status color
            val statusColor = if (user.isOnline) {
                com.groupmusicplayer.R.color.success_color
            } else {
                com.groupmusicplayer.R.color.secondary_text
            }
            binding.ivOnlineStatus.setColorFilter(
                binding.root.context.getColor(statusColor)
            )
            
            // Generate user avatar (simple circle with initials)
            val initials = user.name.split(" ").take(2).map { it.firstOrNull()?.uppercaseChar() ?: "" }.joinToString("")
            binding.tvUserInitials.text = initials
            
            // Set background color based on name hash for consistent colors
            val colors = listOf(
                com.groupmusicplayer.R.color.primary_color,
                com.groupmusicplayer.R.color.secondary_color,
                com.groupmusicplayer.R.color.accent_color,
                com.groupmusicplayer.R.color.spotify_green
            )
            val colorIndex = user.name.hashCode().let { if (it < 0) -it else it } % colors.size
            binding.viewUserAvatar.setBackgroundResource(colors[colorIndex])
            
            // Show join time
            val joinTime = formatJoinTime(user.joinedAt)
            binding.tvJoinTime.text = "Joined $joinTime"
        }
        
        private fun formatJoinTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            return when {
                diff < 60_000 -> "just now"
                diff < 3600_000 -> "${diff / 60_000}m ago"
                diff < 86400_000 -> "${diff / 3600_000}h ago"
                else -> "${diff / 86400_000}d ago"
            }
        }
    }

    private class UserDiffCallback(
        private val oldList: List<User>,
        private val newList: List<User>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].deviceId == newList[newItemPosition].deviceId
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
} 