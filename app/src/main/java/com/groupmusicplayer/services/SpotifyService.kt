package com.groupmusicplayer.services

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.annotations.SerializedName
import com.groupmusicplayer.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

class SpotifyService(private val context: Context) {
    
    companion object {
        private const val CLIENT_ID = "" // Your real Spotify Client ID
        private const val CLIENT_SECRET = "" // Your real Spotify Client Secret
        private const val REDIRECT_URI = "com.groupmusicplayer://callback"
        private const val BASE_URL = "https://api.spotify.com/v1/"
        private const val AUTH_URL = "https://accounts.spotify.com/api/"
        private const val PREFS_NAME = "spotify_prefs"
        private const val ACCESS_TOKEN_KEY = "access_token"
        private const val REFRESH_TOKEN_KEY = "refresh_token"
        private const val TOKEN_EXPIRES_KEY = "token_expires"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private val authInterceptor = Interceptor { chain ->
        val accessToken = getAccessToken()
        val request = if (accessToken != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    private val authOkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val authRetrofit = Retrofit.Builder()
        .baseUrl(AUTH_URL)
        .client(authOkHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    private val spotifyApi = retrofit.create(SpotifyApi::class.java)
    private val spotifyAuthApi = authRetrofit.create(SpotifyAuthApi::class.java)
    
    // Check if user is authenticated
    fun isAuthenticated(): Boolean {
        val accessToken = getAccessToken()
        val expiresAt = prefs.getLong(TOKEN_EXPIRES_KEY, 0)
        return accessToken != null && System.currentTimeMillis() < expiresAt
    }
    
    // Get authorization URL for user login
    fun getAuthorizationUrl(): String {
        val scopes = listOf(
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "playlist-read-private",
            "playlist-read-collaborative",
            "streaming"
        ).joinToString(" ")
        
        return "https://accounts.spotify.com/authorize?" +
                "client_id=$CLIENT_ID&" +
                "response_type=code&" +
                "redirect_uri=${java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")}&" +
                "scope=${java.net.URLEncoder.encode(scopes, "UTF-8")}"
    }
    
    // Exchange authorization code for access token
    suspend fun exchangeCodeForToken(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val credentials = Credentials.basic(CLIENT_ID, CLIENT_SECRET)
            android.util.Log.d("SpotifyService", "CLIENT_ID being used: $CLIENT_ID")
            android.util.Log.d("SpotifyService", "CLIENT_SECRET being used: ${CLIENT_SECRET.take(8)}...")
            android.util.Log.d("SpotifyService", "Authorization header: $credentials")
            android.util.Log.d("SpotifyService", "Authorization code: ${code.take(20)}...")
            android.util.Log.d("SpotifyService", "Redirect URI: $REDIRECT_URI")
            
            val response = spotifyAuthApi.getAccessToken(
                authorization = credentials,
                grantType = "authorization_code",
                code = code,
                redirectUri = REDIRECT_URI
            )
            
            if (response.isSuccessful) {
                val tokenResponse = response.body()!!
                saveTokens(tokenResponse)
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Token exchange failed with Authorization header: ${response.code()} - $errorBody")
                
                // If Authorization header approach failed with invalid_client, try form body approach
                if (response.code() == 400 && errorBody.contains("invalid_client")) {
                    android.util.Log.d("SpotifyService", "Retrying with client credentials in form body...")
                    
                    val response2 = spotifyAuthApi.getAccessTokenWithClientCredentials(
                        grantType = "authorization_code",
                        code = code,
                        redirectUri = REDIRECT_URI,
                        clientId = CLIENT_ID,
                        clientSecret = CLIENT_SECRET
                    )
                    
                    if (response2.isSuccessful) {
                        val tokenResponse = response2.body()!!
                        saveTokens(tokenResponse)
                        android.util.Log.d("SpotifyService", "Successfully exchanged token using form body credentials")
                        Result.success(Unit)
                    } else {
                        val errorBody2 = response2.errorBody()?.string() ?: "No error details"
                        android.util.Log.e("SpotifyService", "Token exchange failed with form body: ${response2.code()} - $errorBody2")
                        Result.failure(Exception("Both credential approaches failed: Header(${response.code()}) Form(${response2.code()})"))
                    }
                } else {
                    Result.failure(Exception("Failed to get access token: ${response.code()} - $errorBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Refresh access token
    suspend fun refreshAccessToken(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val refreshToken = prefs.getString(REFRESH_TOKEN_KEY, null)
                ?: return@withContext Result.failure(Exception("No refresh token available"))
            
            val credentials = Credentials.basic(CLIENT_ID, CLIENT_SECRET)
            val response = spotifyAuthApi.refreshToken(
                authorization = credentials,
                grantType = "refresh_token",
                refreshToken = refreshToken
            )
            
            if (response.isSuccessful) {
                val tokenResponse = response.body()!!
                saveTokens(tokenResponse)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to refresh token: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Search for tracks
    suspend fun searchTracks(query: String, limit: Int = 20): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Searching for tracks: '$query' (limit: $limit)")
            val response = spotifyApi.searchTracks(
                query = query,
                type = "track",
                limit = limit
            )
            
            if (response.isSuccessful) {
                val searchResult = response.body()!!
                android.util.Log.d("SpotifyService", "Search successful: Found ${searchResult.tracks.items.size} tracks")
                searchResult.tracks.items.take(3).forEachIndexed { index, track ->
                    android.util.Log.d("SpotifyService", "  Track $index: ${track.name} by ${track.artists.firstOrNull()?.name}")
                }
                Result.success(searchResult.tracks.items)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Search failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Search failed: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Search exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // Get user's playlists
    suspend fun getUserPlaylists(limit: Int = 50): Result<List<SpotifyPlaylist>> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            val response = spotifyApi.getUserPlaylists(limit = limit)
            
            if (response.isSuccessful) {
                val playlistResponse = response.body()!!
                Result.success(playlistResponse.items)
            } else {
                Result.failure(Exception("Failed to get playlists: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Get tracks from a playlist
    suspend fun getPlaylistTracks(playlistId: String, limit: Int = 100): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            val response = spotifyApi.getPlaylistTracks(
                playlistId = playlistId,
                limit = limit
            )
            
            if (response.isSuccessful) {
                val tracksResponse = response.body()!!
                val tracks = tracksResponse.items.mapNotNull { it.track }
                Result.success(tracks)
            } else {
                Result.failure(Exception("Failed to get playlist tracks: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Get current playback state
    suspend fun getCurrentPlayback(): Result<SpotifyPlaybackState?> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Getting current playback state...")
            val response = spotifyApi.getCurrentPlayback()
            
            if (response.isSuccessful) {
                val playbackState = response.body()
                android.util.Log.d("SpotifyService", "Playback state retrieved: ${playbackState?.let { "Playing: ${it.isPlaying}, Track: ${it.item?.name}" } ?: "null"}")
                Result.success(playbackState)
            } else if (response.code() == 204) {
                android.util.Log.w("SpotifyService", "No active playback found (204)")
                Result.success(null)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Failed to get playback state: ${response.code()} - $errorBody")
                Result.failure(Exception("Failed to get playback state: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Get playback exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // Play/Resume playback
    suspend fun play(deviceId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Attempting to play music...")
            android.util.Log.d("SpotifyService", "Device ID: ${deviceId ?: "null (default)"}")
            
            val response = spotifyApi.play(deviceId = deviceId)
            
            if (response.isSuccessful) {
                android.util.Log.d("SpotifyService", "Play command successful")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Play failed: ${response.code()} - $errorBody")
                
                if (response.code() == 404) {
                    Result.failure(Exception("No active device found. Please open Spotify on a device and start playing something first."))
                } else if (response.code() == 403 && errorBody.contains("Restriction violated")) {
                    Result.failure(Exception("Spotify Premium required. This feature requires a Spotify Premium subscription to control playback via the app.\n\nAlternatively, start playing music manually on Spotify first, then use the app controls."))
                } else {
                    Result.failure(Exception("Failed to play: ${response.code()} - $errorBody"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Play exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // Pause playback
    suspend fun pause(deviceId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Attempting to pause music...")
            android.util.Log.d("SpotifyService", "Device ID: ${deviceId ?: "null (default)"}")
            
            val response = spotifyApi.pause(deviceId = deviceId)
            
            if (response.isSuccessful) {
                android.util.Log.d("SpotifyService", "Pause command successful")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Pause failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Failed to pause: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Pause exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // Skip to next track
    suspend fun skipNext(deviceId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Attempting to skip to next track...")
            android.util.Log.d("SpotifyService", "Device ID: ${deviceId ?: "null (default)"}")
            
            val response = spotifyApi.skipNext(deviceId = deviceId)
            
            if (response.isSuccessful) {
                android.util.Log.d("SpotifyService", "Skip next successful")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Skip next failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Failed to skip next: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Skip next exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // Skip to previous track
    suspend fun skipPrevious(deviceId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Attempting to skip to previous track...")
            android.util.Log.d("SpotifyService", "Device ID: ${deviceId ?: "null (default)"}")
            
            val response = spotifyApi.skipPrevious(deviceId = deviceId)
            
            if (response.isSuccessful) {
                android.util.Log.d("SpotifyService", "Skip previous successful")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Skip previous failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Failed to skip previous: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Skip previous exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Get current playback state from Spotify
    suspend fun getCurrentPlaybackState(): Result<SpotifyPlaybackState?> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Getting current playback state...")
            val response = spotifyApi.getCurrentPlayback()
            
            if (response.isSuccessful) {
                val playbackState = response.body()
                if (playbackState != null) {
                    android.util.Log.d("SpotifyService", "Playback state: isPlaying=${playbackState.isPlaying}")
                    android.util.Log.d("SpotifyService", "Current track: ${playbackState.item?.name ?: "None"}")
                    android.util.Log.d("SpotifyService", "Device: ${playbackState.device?.name ?: "None"}")
                } else {
                    android.util.Log.d("SpotifyService", "No active playback found")
                }
                Result.success(playbackState)
            } else if (response.code() == 204) {
                // 204 means no content - no active playback
                android.util.Log.d("SpotifyService", "No active playback (204)")
                Result.success(null)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Failed to get playback state: ${response.code()} - $errorBody")
                Result.failure(Exception("Failed to get playback state: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Get playback state error: ${e.message}")
            Result.failure(e)
        }
    }

    // Start playback with specific track URIs (for playing app's queue)
    suspend fun playWithUris(trackUris: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Starting playback with ${trackUris.size} track URIs...")
            trackUris.take(3).forEachIndexed { index, uri ->
                android.util.Log.d("SpotifyService", "  URI $index: $uri")
            }
            
            val playbackRequest = PlaybackRequest(uris = trackUris)
            val response = spotifyApi.playWithBody(playbackRequest)
            
            if (response.isSuccessful) {
                android.util.Log.d("SpotifyService", "Playback with URIs started successfully")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Play with URIs failed: ${response.code()} - $errorBody")
                
                if (response.code() == 404) {
                    Result.failure(Exception("No active device found. Please open Spotify on a device and start playing something first."))
                } else if (response.code() == 403 && errorBody.contains("Restriction violated")) {
                    Result.failure(Exception("Spotify Premium required. This feature requires a Spotify Premium subscription to control playback via the app.\n\nAlternatively, start playing music manually on Spotify first, then use the app controls."))
                } else {
                    Result.failure(Exception("Failed to play with URIs: ${response.code()} - $errorBody"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Play with URIs error: ${e.message}")
            Result.failure(e)
        }
    }
    
    // Add track to queue
    suspend fun addToQueue(uri: String, deviceId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Adding track to Spotify queue...")
            android.util.Log.d("SpotifyService", "Track URI: $uri")
            android.util.Log.d("SpotifyService", "Device ID: ${deviceId ?: "null (default)"}")
            
            val response = spotifyApi.addToQueue(uri = uri, deviceId = deviceId)
            
            if (response.isSuccessful) {
                android.util.Log.d("SpotifyService", "Successfully added track to queue")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Failed to add to queue: ${response.code()} - $errorBody")
                
                if (response.code() == 404) {
                    Result.failure(Exception("No active device found. Please open Spotify on a device and start playing something first."))
                } else {
                    Result.failure(Exception("Failed to add to queue: ${response.code()} - $errorBody"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Add to queue exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // Set volume
    suspend fun setVolume(volumePercent: Int, deviceId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            val response = spotifyApi.setVolume(volumePercent = volumePercent, deviceId = deviceId)
            
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set volume: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Seek to position
    suspend fun seek(positionMs: Long, deviceId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            val response = spotifyApi.seek(positionMs = positionMs, deviceId = deviceId)
            
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to seek: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Get available devices
    suspend fun getDevices(): Result<List<SpotifyDevice>> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()
            android.util.Log.d("SpotifyService", "Getting available Spotify devices...")
            val response = spotifyApi.getDevices()
            
            if (response.isSuccessful) {
                val devicesResponse = response.body()!!
                android.util.Log.d("SpotifyService", "Found ${devicesResponse.devices.size} devices:")
                devicesResponse.devices.forEachIndexed { index, device ->
                    android.util.Log.d("SpotifyService", "  Device $index: ${device.name} (${device.type}) - Active: ${device.isActive}, ID: ${device.id}")
                }
                Result.success(devicesResponse.devices)
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error details"
                android.util.Log.e("SpotifyService", "Failed to get devices: ${response.code()} - $errorBody")
                Result.failure(Exception("Failed to get devices: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyService", "Get devices exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // Helper functions
    private fun getAccessToken(): String? = prefs.getString(ACCESS_TOKEN_KEY, null)
    
    private fun saveTokens(tokenResponse: TokenResponse) {
        val expiresAt = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000)
        prefs.edit()
            .putString(ACCESS_TOKEN_KEY, tokenResponse.accessToken)
            .putString(REFRESH_TOKEN_KEY, tokenResponse.refreshToken ?: prefs.getString(REFRESH_TOKEN_KEY, null))
            .putLong(TOKEN_EXPIRES_KEY, expiresAt)
            .apply()
    }
    
    private suspend fun ensureValidToken() {
        if (!isAuthenticated()) {
            val refreshResult = refreshAccessToken()
            if (refreshResult.isFailure) {
                throw Exception("Authentication required")
            }
        }
    }
    
    // Clear stored tokens (logout)
    fun logout() {
        prefs.edit()
            .remove(ACCESS_TOKEN_KEY)
            .remove(REFRESH_TOKEN_KEY)
            .remove(TOKEN_EXPIRES_KEY)
            .apply()
    }
}

// Retrofit API interfaces
interface SpotifyApi {
    @GET("search")
    suspend fun searchTracks(
        @Query("q") query: String,
        @Query("type") type: String,
        @Query("limit") limit: Int
    ): retrofit2.Response<SpotifySearchResult>
    
    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Query("limit") limit: Int
    ): retrofit2.Response<SpotifyPlaylistsResponse>
    
    @GET("playlists/{playlist_id}/tracks")
    suspend fun getPlaylistTracks(
        @Path("playlist_id") playlistId: String,
        @Query("limit") limit: Int
    ): retrofit2.Response<SpotifyPlaylistTracksResponse>
    
    @GET("me/player")
    suspend fun getCurrentPlayback(): retrofit2.Response<SpotifyPlaybackState>
    
    @PUT("me/player/play")
    suspend fun play(
        @Query("device_id") deviceId: String? = null
    ): retrofit2.Response<Unit>

    @PUT("me/player/play")
    suspend fun playWithBody(
        @Body playbackRequest: PlaybackRequest,
        @Query("device_id") deviceId: String? = null
    ): retrofit2.Response<Unit>
    
    @PUT("me/player/pause")
    suspend fun pause(
        @Query("device_id") deviceId: String? = null
    ): retrofit2.Response<Unit>
    
    @POST("me/player/next")
    suspend fun skipNext(
        @Query("device_id") deviceId: String? = null
    ): retrofit2.Response<Unit>
    
    @POST("me/player/previous")
    suspend fun skipPrevious(
        @Query("device_id") deviceId: String? = null
    ): retrofit2.Response<Unit>
    
    @POST("me/player/queue")
    suspend fun addToQueue(
        @Query("uri") uri: String,
        @Query("device_id") deviceId: String? = null
    ): retrofit2.Response<Unit>
    
    @PUT("me/player/volume")
    suspend fun setVolume(
        @Query("volume_percent") volumePercent: Int,
        @Query("device_id") deviceId: String? = null
    ): retrofit2.Response<Unit>
    
    @PUT("me/player/seek")
    suspend fun seek(
        @Query("position_ms") positionMs: Long,
        @Query("device_id") deviceId: String? = null
    ): retrofit2.Response<Unit>
    
    @GET("me/player/devices")
    suspend fun getDevices(): retrofit2.Response<SpotifyDevicesResponse>
}

interface SpotifyAuthApi {
    @FormUrlEncoded
    @POST("token")
    suspend fun getAccessToken(
        @Header("Authorization") authorization: String,
        @Field("grant_type") grantType: String,
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String
    ): retrofit2.Response<TokenResponse>
    
    @FormUrlEncoded
    @POST("token")
    suspend fun getAccessTokenWithClientCredentials(
        @Field("grant_type") grantType: String,
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String
    ): retrofit2.Response<TokenResponse>
    
    @FormUrlEncoded
    @POST("token")
    suspend fun refreshToken(
        @Header("Authorization") authorization: String,
        @Field("grant_type") grantType: String,
        @Field("refresh_token") refreshToken: String
    ): retrofit2.Response<TokenResponse>
}

// Data classes for Spotify API responses
data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("scope") val scope: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("refresh_token") val refreshToken: String?
)

data class SpotifyPlaylistsResponse(
    val items: List<SpotifyPlaylist>
)

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val images: List<SpotifyImage>,
    @SerializedName("tracks") val tracksInfo: SpotifyTracksInfo
)

data class SpotifyTracksInfo(
    val total: Int
)

data class SpotifyPlaylistTracksResponse(
    val items: List<SpotifyPlaylistTrackItem>
)

data class SpotifyPlaylistTrackItem(
    val track: SpotifyTrack?
)

data class SpotifyPlaybackState(
    @SerializedName("is_playing") val isPlaying: Boolean,
    @SerializedName("progress_ms") val progressMs: Long?,
    val item: SpotifyTrack?,
    val device: SpotifyDevice?
)

data class SpotifyDevice(
    val id: String?,
    val name: String,
    val type: String,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("volume_percent") val volumePercent: Int?
)

data class SpotifyDevicesResponse(
    val devices: List<SpotifyDevice>
)

data class PlaybackRequest(
    val uris: List<String>? = null,
    @SerializedName("context_uri") val contextUri: String? = null,
    val offset: PlaybackOffset? = null
)

data class PlaybackOffset(
    val position: Int? = null,
    val uri: String? = null
)
