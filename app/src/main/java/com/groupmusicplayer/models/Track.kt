package com.groupmusicplayer.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Track(
    val spotifyId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0,
    val albumArt: String = "",
    val addedBy: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val previewUrl: String? = null
) : Parcelable {
    val spotifyUri: String
        get() = if (spotifyId.isNotEmpty()) "spotify:track:$spotifyId" else ""
}

@Parcelize
data class SpotifySearchResult(
    val tracks: SpotifyTracks
) : Parcelable

@Parcelize
data class SpotifyTracks(
    val items: List<SpotifyTrack>
) : Parcelable

@Parcelize
data class SpotifyTrack(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val album: SpotifyAlbum,
    val duration_ms: Long,
    val preview_url: String?
) : Parcelable {
    fun toTrack(addedBy: String): Track {
        return Track(
            spotifyId = id,
            title = name,
            artist = artists.joinToString(", ") { it.name },
            album = album.name,
            duration = duration_ms,
            albumArt = album.images.firstOrNull()?.url ?: "",
            addedBy = addedBy,
            previewUrl = preview_url
        )
    }
}

@Parcelize
data class SpotifyArtist(
    val name: String
) : Parcelable

@Parcelize
data class SpotifyAlbum(
    val name: String,
    val images: List<SpotifyImage>
) : Parcelable

@Parcelize
data class SpotifyImage(
    val url: String,
    val height: Int?,
    val width: Int?
) : Parcelable 