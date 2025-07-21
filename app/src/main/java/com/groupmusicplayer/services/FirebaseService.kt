package com.groupmusicplayer.services

import com.google.firebase.database.*
import com.groupmusicplayer.models.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class FirebaseService {
    private val database = FirebaseDatabase.getInstance()
    private val sessionsRef = database.getReference("sessions")
    
    companion object {
        @Volatile
        private var INSTANCE: FirebaseService? = null
        
        fun getInstance(): FirebaseService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseService().also { INSTANCE = it }
            }
        }
    }
    
    // Generate unique room code
    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }
    
    // Create a new session
    suspend fun createSession(sessionName: String, hostDeviceId: String): Result<Session> {
        return try {
            val sessionId = sessionsRef.push().key ?: throw Exception("Failed to generate session ID")
            val roomCode = generateRoomCode()
            
            val session = Session(
                sessionId = sessionId,
                roomCode = roomCode,
                hostDeviceId = hostDeviceId,
                sessionName = sessionName
            )
            
            val sessionData = mapOf(
                "info" to mapOf(
                    "hostDeviceId" to hostDeviceId,
                    "roomCode" to roomCode,
                    "createdAt" to ServerValue.TIMESTAMP,
                    "sessionName" to sessionName
                ),
                "users" to mapOf(
                    hostDeviceId to mapOf(
                        "deviceId" to hostDeviceId,
                        "name" to "Host",
                        "isHost" to true,
                        "joinedAt" to ServerValue.TIMESTAMP,
                        "isOnline" to true,
                        "lastSeen" to ServerValue.TIMESTAMP
                    )
                ),
                "queue" to emptyMap<String, Any>(),
                "currentTrack" to emptyMap<String, Any>(),
                "playbackState" to mapOf(
                    "isPlaying" to false,
                    "position" to 0,
                    "volume" to 80,
                    "lastUpdated" to ServerValue.TIMESTAMP,
                    "shuffleEnabled" to false,
                    "repeatMode" to "OFF"
                ),
                "history" to emptyMap<String, Any>()
            )
            
            sessionsRef.child(sessionId).setValue(sessionData).await()
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Join existing session
    suspend fun joinSession(roomCode: String, userName: String, deviceId: String): Result<Session> {
        return try {
            android.util.Log.d("FirebaseService", "joinSession called with roomCode: $roomCode, userName: $userName, deviceId: $deviceId")
            
            val query = sessionsRef.orderByChild("info/roomCode").equalTo(roomCode)
            android.util.Log.d("FirebaseService", "Executing Firebase query for room code: $roomCode")
            
            val snapshot = query.get().await()
            android.util.Log.d("FirebaseService", "Query result - exists: ${snapshot.exists()}, children count: ${snapshot.childrenCount}")
            
            if (!snapshot.exists()) {
                android.util.Log.e("FirebaseService", "Session not found for room code: $roomCode")
                throw Exception("Session not found")
            }
            
            val sessionEntry = snapshot.children.first()
            val sessionId = sessionEntry.key ?: throw Exception("Invalid session")
            android.util.Log.d("FirebaseService", "Found session with ID: $sessionId")
            
            val sessionInfo = sessionEntry.child("info").getValue(SessionInfo::class.java)
                ?: throw Exception("Invalid session data")
            android.util.Log.d("FirebaseService", "Session info retrieved: ${sessionInfo.sessionName}")
            
            // Add user to session
            val userData = mapOf(
                "deviceId" to deviceId,
                "name" to userName,
                "isHost" to false,
                "joinedAt" to ServerValue.TIMESTAMP,
                "isOnline" to true,
                "lastSeen" to ServerValue.TIMESTAMP
            )
            
            android.util.Log.d("FirebaseService", "Adding user to session...")
            sessionsRef.child(sessionId).child("users").child(deviceId).setValue(userData).await()
            android.util.Log.d("FirebaseService", "User added successfully")
            
            val session = Session(
                sessionId = sessionId,
                roomCode = sessionInfo.roomCode,
                hostDeviceId = sessionInfo.hostDeviceId,
                sessionName = sessionInfo.sessionName,
                createdAt = sessionInfo.createdAt
            )
            
            android.util.Log.d("FirebaseService", "Session object created successfully")
            Result.success(session)
        } catch (e: Exception) {
            android.util.Log.e("FirebaseService", "Error in joinSession: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // Get session by room code (alternative method that doesn't require Firebase index)
    suspend fun getSessionByRoomCode(roomCode: String): Result<Session> {
        return try {
            android.util.Log.d("FirebaseService", "Searching for session with room code: '$roomCode'")
            
            // Get all sessions and filter locally (works without index)
            val snapshot = sessionsRef.get().await()
            
            if (!snapshot.exists()) {
                android.util.Log.d("FirebaseService", "No sessions exist in database")
                throw Exception("No sessions found in database")
            }
            
            android.util.Log.d("FirebaseService", "Found ${snapshot.childrenCount} sessions, searching...")
            
            for (sessionEntry in snapshot.children) {
                val sessionId = sessionEntry.key ?: continue
                val sessionInfo = sessionEntry.child("info").getValue(SessionInfo::class.java)
                
                android.util.Log.d("FirebaseService", "Checking session $sessionId with room code: '${sessionInfo?.roomCode}'")
                
                if (sessionInfo?.roomCode == roomCode) {
                    android.util.Log.d("FirebaseService", "Found matching session: $sessionId")
                    
                    val session = Session(
                        sessionId = sessionId,
                        roomCode = sessionInfo.roomCode,
                        hostDeviceId = sessionInfo.hostDeviceId,
                        sessionName = sessionInfo.sessionName,
                        createdAt = sessionInfo.createdAt
                    )
                    
                    return Result.success(session)
                }
            }
            
            android.util.Log.e("FirebaseService", "No session found with room code: '$roomCode'")
            throw Exception("Session with room code '$roomCode' not found")
            
        } catch (e: Exception) {
            android.util.Log.e("FirebaseService", "Error in getSessionByRoomCode: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // Add track to queue
    suspend fun addTrackToQueue(sessionId: String, track: Track): Result<Unit> {
        return try {
            val queueRef = sessionsRef.child(sessionId).child("queue")
            val newTrackRef = queueRef.push()
            newTrackRef.setValue(track).await()
            
            // Add to history
            addToHistory(sessionId, QueueAction(ActionType.ADDED, track, track.addedBy))
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Remove track from queue
    suspend fun removeTrackFromQueue(sessionId: String, trackKey: String, userName: String): Result<Unit> {
        return try {
            val trackRef = sessionsRef.child(sessionId).child("queue").child(trackKey)
            val track = trackRef.get().await().getValue(Track::class.java)
            
            trackRef.removeValue().await()
            
            // Add to history
            track?.let {
                addToHistory(sessionId, QueueAction(ActionType.REMOVED, it, userName))
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Update playback state
    suspend fun updatePlaybackState(sessionId: String, playbackState: PlaybackState): Result<Unit> {
        return try {
            val playbackData = mapOf(
                "isPlaying" to playbackState.isPlaying,
                "position" to playbackState.position,
                "volume" to playbackState.volume,
                "lastUpdated" to ServerValue.TIMESTAMP,
                "shuffleEnabled" to playbackState.shuffleEnabled,
                "repeatMode" to playbackState.repeatMode.name
            )
            
            sessionsRef.child(sessionId).child("playbackState").setValue(playbackData).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Update current track
    suspend fun updateCurrentTrack(sessionId: String, currentTrack: CurrentTrack?): Result<Unit> {
        return try {
            val currentTrackData = if (currentTrack?.track != null) {
                mapOf(
                    "track" to currentTrack.track,
                    "isPlaying" to currentTrack.isPlaying,
                    "position" to currentTrack.position,
                    "lastUpdated" to ServerValue.TIMESTAMP
                )
            } else {
                null // This will clear the current track
            }
            
            sessionsRef.child(sessionId).child("currentTrack").setValue(currentTrackData).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Listen to session changes
    fun listenToSession(sessionId: String): Flow<Session?> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val sessionInfo = snapshot.child("info").getValue(SessionInfo::class.java)
                    if (sessionInfo != null) {
                        val session = Session(
                            sessionId = sessionId,
                            roomCode = sessionInfo.roomCode,
                            hostDeviceId = sessionInfo.hostDeviceId,
                            sessionName = sessionInfo.sessionName,
                            createdAt = sessionInfo.createdAt
                        )
                        trySend(session)
                    } else {
                        trySend(null)
                    }
                } catch (e: Exception) {
                    trySend(null)
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        sessionsRef.child(sessionId).addValueEventListener(listener)
        awaitClose { sessionsRef.child(sessionId).removeEventListener(listener) }
    }
    
    // Listener methods
    
    // Listen to session info changes
    fun listenToSessionInfo(sessionId: String): Flow<Session> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sessionInfo = snapshot.child("info").getValue(SessionInfo::class.java)
                if (sessionInfo != null) {
                    val session = Session(
                        sessionId = sessionId,
                        roomCode = sessionInfo.roomCode,
                        hostDeviceId = sessionInfo.hostDeviceId,
                        sessionName = sessionInfo.sessionName,
                        createdAt = sessionInfo.createdAt
                    )
                    trySend(session)
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        sessionsRef.child(sessionId).addValueEventListener(listener)
        awaitClose { sessionsRef.child(sessionId).removeEventListener(listener) }
    }
    
    // Listen to queue changes
    fun listenToQueue(sessionId: String): Flow<List<Pair<String, Track>>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val queue = mutableListOf<Pair<String, Track>>()
                for (child in snapshot.children) {
                    val track = child.getValue(Track::class.java)
                    if (track != null && child.key != null) {
                        queue.add(Pair(child.key!!, track))
                    }
                }
                trySend(queue)
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        sessionsRef.child(sessionId).child("queue").addValueEventListener(listener)
        awaitClose { sessionsRef.child(sessionId).child("queue").removeEventListener(listener) }
    }
    
    // Listen to playback state changes
    fun listenToPlaybackState(sessionId: String): Flow<PlaybackState?> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val isPlaying = snapshot.child("isPlaying").getValue(Boolean::class.java) ?: false
                    val position = snapshot.child("position").getValue(Long::class.java) ?: 0L
                    val volume = snapshot.child("volume").getValue(Int::class.java) ?: 80
                    val lastUpdated = snapshot.child("lastUpdated").getValue(Long::class.java) ?: 0L
                    val shuffleEnabled = snapshot.child("shuffleEnabled").getValue(Boolean::class.java) ?: false
                    val repeatModeStr = snapshot.child("repeatMode").getValue(String::class.java) ?: "OFF"
                    val repeatMode = try { RepeatMode.valueOf(repeatModeStr) } catch (e: Exception) { RepeatMode.OFF }
                    
                    val playbackState = PlaybackState(
                        isPlaying = isPlaying,
                        position = position,
                        volume = volume,
                        lastUpdated = lastUpdated,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode
                    )
                    trySend(playbackState)
                } catch (e: Exception) {
                    trySend(null)
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        sessionsRef.child(sessionId).child("playbackState").addValueEventListener(listener)
        awaitClose { sessionsRef.child(sessionId).child("playbackState").removeEventListener(listener) }
    }
    
    // Listen to current track changes
    fun listenToCurrentTrack(sessionId: String): Flow<CurrentTrack?> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    if (snapshot.exists()) {
                        val track = snapshot.child("track").getValue(Track::class.java)
                        val isPlaying = snapshot.child("isPlaying").getValue(Boolean::class.java) ?: false
                        val position = snapshot.child("position").getValue(Long::class.java) ?: 0L
                        val lastUpdated = snapshot.child("lastUpdated").getValue(Long::class.java) ?: 0L
                        
                        val currentTrack = CurrentTrack(
                            track = track,
                            isPlaying = isPlaying,
                            position = position,
                            lastUpdated = lastUpdated
                        )
                        trySend(currentTrack)
                    } else {
                        trySend(CurrentTrack())
                    }
                } catch (e: Exception) {
                    trySend(null)
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        sessionsRef.child(sessionId).child("currentTrack").addValueEventListener(listener)
        awaitClose { sessionsRef.child(sessionId).child("currentTrack").removeEventListener(listener) }
    }
    
    // Listen to users in session
    fun listenToUsers(sessionId: String): Flow<List<User>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val users = mutableListOf<User>()
                for (child in snapshot.children) {
                    val user = child.getValue(User::class.java)
                    if (user != null) {
                        users.add(user)
                    }
                }
                trySend(users)
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        sessionsRef.child(sessionId).child("users").addValueEventListener(listener)
        awaitClose { sessionsRef.child(sessionId).child("users").removeEventListener(listener) }
    }
    
    // Update user online status
    suspend fun updateUserStatus(sessionId: String, deviceId: String, isOnline: Boolean): Result<Unit> {
        return try {
            val updates = mapOf(
                "isOnline" to isOnline,
                "lastSeen" to ServerValue.TIMESTAMP
            )
            sessionsRef.child(sessionId).child("users").child(deviceId).updateChildren(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Add action to history
    private suspend fun addToHistory(sessionId: String, action: QueueAction) {
        try {
            val historyRef = sessionsRef.child(sessionId).child("history").push()
            historyRef.setValue(action).await()
        } catch (e: Exception) {
            // Log error but don't fail the main operation
        }
    }

    // Send control request (for non-host users to request actions)
    suspend fun sendControlRequest(sessionId: String, request: Map<String, Any>): Result<Unit> {
        return try {
            sessionsRef.child(sessionId).child("controlRequests").push().setValue(request).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Listen to control requests (for host to process non-host actions)
    fun listenToControlRequests(sessionId: String): Flow<Map<String, Any>> = callbackFlow {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val request = snapshot.getValue<Map<String, Any>>()
                if (request != null) {
                    // Remove the request after processing to avoid re-processing
                    snapshot.ref.removeValue()
                    trySend(request)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        sessionsRef.child(sessionId).child("controlRequests").addChildEventListener(listener)
        
        awaitClose {
            sessionsRef.child(sessionId).child("controlRequests").removeEventListener(listener)
        }
    }
} 