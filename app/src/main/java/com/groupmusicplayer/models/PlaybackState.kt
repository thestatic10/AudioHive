package com.groupmusicplayer.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlaybackState(
    val isPlaying: Boolean = false,
    val position: Long = 0,
    val volume: Int = 80,
    val lastUpdated: Long = System.currentTimeMillis(),
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF
) : Parcelable

enum class RepeatMode {
    OFF, TRACK, CONTEXT
}

@Parcelize
data class CurrentTrack(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) : Parcelable 