package com.groupmusicplayer.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Session(
    val sessionId: String = "",
    val roomCode: String = "",
    val hostDeviceId: String = "",
    val sessionName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
) : Parcelable

@Parcelize
data class SessionInfo(
    val hostDeviceId: String = "",
    val roomCode: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val sessionName: String = ""
) : Parcelable 