package com.groupmusicplayer.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class User(
    val deviceId: String = "",
    val name: String = "",
    val isHost: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis(),
    val isOnline: Boolean = true,
    val lastSeen: Long = System.currentTimeMillis()
) : Parcelable

@Parcelize
data class QueueAction(
    val action: ActionType,
    val track: Track? = null,
    val user: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val fromPosition: Int? = null,
    val toPosition: Int? = null
) : Parcelable

enum class ActionType {
    ADDED, REMOVED, REORDERED, SKIPPED, PLAYED, PAUSED
} 