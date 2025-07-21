package com.groupmusicplayer.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.groupmusicplayer.R
import com.groupmusicplayer.adapters.QueueAdapter
import com.groupmusicplayer.adapters.SearchResultsAdapter
import com.groupmusicplayer.adapters.UsersAdapter
import com.groupmusicplayer.databinding.ActivityMusicPlayerBinding
import com.groupmusicplayer.models.*
import com.groupmusicplayer.services.FirebaseService
import com.groupmusicplayer.services.SpotifyService
import com.groupmusicplayer.utils.UIAnimationHelper
import com.groupmusicplayer.utils.UIOptimizationHelper
import kotlinx.coroutines.launch

class MusicPlayerActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMusicPlayerBinding
    private lateinit var firebaseService: FirebaseService
    private lateinit var spotifyService: SpotifyService
    private lateinit var animationHelper: UIAnimationHelper
    private lateinit var optimizationHelper: UIOptimizationHelper
    
    private lateinit var queueAdapter: QueueAdapter
    private lateinit var searchResultsAdapter: SearchResultsAdapter
    private lateinit var usersAdapter: UsersAdapter
    
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    
    private var sessionId: String? = null
    private var roomCode: String? = null
    private var isHost: Boolean = false
    private var currentSession: Session? = null
    private var currentTrack: CurrentTrack? = null
    private var playbackState: PlaybackState? = null
    private var queue: List<Pair<String, Track>> = emptyList()
    private var users: List<User> = emptyList()
    private var playbackStateUpdateJob: kotlinx.coroutines.Job? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        firebaseService = FirebaseService.getInstance()
        spotifyService = SpotifyService(this)
        animationHelper = UIAnimationHelper(this)
        optimizationHelper = UIOptimizationHelper.getInstance()
        
        extractIntentData()
        setupRecyclerViews()
        setupUI()
        setupBottomSheet()
        setupListeners()
        
        // Handle Spotify callback if present
        handleSpotifyCallback()
        
        if (roomCode != null) {
            if (intent.getBooleanExtra("rejoin", false)) {
                rejoinSession()
            } else {
                findSessionByRoomCode()
            }
        }
    }
    
    private fun extractIntentData() {
        roomCode = intent.getStringExtra("room_code")
        isHost = intent.getBooleanExtra("is_host", false)
        sessionId = intent.getStringExtra("session_id")
    }
    
    private fun setupUI() {
        binding.toolbar.title = "Loading..."
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // Show host indicator
        if (isHost) {
            binding.chipHost.visibility = View.VISIBLE
        }
        
        // Setup room code display
        roomCode?.let {
            binding.tvRoomCode.text = "Room: $it"
        }
        
        updateUI()
    }
    
    private fun setupRecyclerViews() {
        // Queue RecyclerView
        queueAdapter = QueueAdapter(
            onRemoveClick = { trackKey, track ->
                removeTrackFromQueue(trackKey, track)
            },
            onReorderClick = { fromPosition, toPosition ->
                // TODO: Implement reordering
            }
        )
        binding.rvQueue.layoutManager = LinearLayoutManager(this)
        binding.rvQueue.adapter = queueAdapter
        
        // Search Results RecyclerView
        searchResultsAdapter = SearchResultsAdapter { track ->
            addTrackToQueue(track)
        }
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = searchResultsAdapter
        
        // Users RecyclerView
        usersAdapter = UsersAdapter()
        binding.rvUsers.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvUsers.adapter = usersAdapter
    }
    
    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheetBehavior.peekHeight = 0
        
        binding.btnSearch.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }
    }
    
    private fun setupListeners() {
        // Playback controls
        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }
        
        binding.btnSkipNext.setOnClickListener {
            skipNext()
        }
        
        binding.btnSkipPrevious.setOnClickListener {
            skipPrevious()
        }
        
        // Search
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
        }
        
        binding.btnSearchAction.setOnClickListener {
            performSearch()
        }
        
        // Copy room code
        binding.tvRoomCode.setOnClickListener {
            copyRoomCodeToClipboard()
        }
    }
    

    
    private fun startListeningForUpdates(id: String) {
        sessionId = id
        
        // Listen to session info changes
        lifecycleScope.launch {
            firebaseService.listenToSessionInfo(id).collect { session ->
                currentSession = session
                updateSessionInfo()
            }
        }
        
        // Listen to queue changes
        lifecycleScope.launch {
            firebaseService.listenToQueue(id).collect { queueList ->
                queue = queueList
                updateQueue()
            }
        }
        
        // Listen to current track changes
        lifecycleScope.launch {
            firebaseService.listenToCurrentTrack(id).collect { track ->
                currentTrack = track
                updateCurrentTrack()
            }
        }
        
        // Listen to playback state changes
        lifecycleScope.launch {
            firebaseService.listenToPlaybackState(id).collect { state ->
                playbackState = state
                updatePlaybackControls()
            }
        }

        // Start playback state monitoring if host and authenticated
        if (isHost && isSpotifyAuthenticated()) {
            startPlaybackStateMonitoring()
        }
        
        // Listen to users changes
        lifecycleScope.launch {
            firebaseService.listenToUsers(id).collect { usersList ->
                users = usersList
                updateUsers()
            }
        }

        // Listen to control requests (only host processes these)
        if (isHost) {
            lifecycleScope.launch {
                firebaseService.listenToControlRequests(id).collect { request ->
                    processControlRequest(request)
                }
            }
        }
    }
    
    private fun findSessionByRoomCode() {
        roomCode?.let { code ->
            android.util.Log.d("MusicPlayerActivity", "findSessionByRoomCode called with: '$code'")
            android.util.Log.d("MusicPlayerActivity", "Room code length: ${code.length}")
            android.util.Log.d("MusicPlayerActivity", "isHost: $isHost")
            
            lifecycleScope.launch {
                try {
                    // Add a small delay to ensure session data is fully written to Firebase
                    kotlinx.coroutines.delay(1000) 
                    android.util.Log.d("MusicPlayerActivity", "Calling getSessionByRoomCode after delay...")
                    val result = firebaseService.getSessionByRoomCode(code)
                    android.util.Log.d("MusicPlayerActivity", "getSessionByRoomCode result: isSuccess = ${result.isSuccess}")
                    
                    if (result.isSuccess) {
                        val session = result.getOrThrow()
                        android.util.Log.d("MusicPlayerActivity", "Session found: ${session.sessionName} (${session.sessionId})")
                        
                        currentSession = session
                        startListeningForUpdates(session.sessionId)
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Unknown error"
                        android.util.Log.e("MusicPlayerActivity", "Session not found: $error")
                        android.util.Log.e("MusicPlayerActivity", "Searched for room code: '$code'")
                        showError("Session Not Found", "Session with room code '$code' was not found.\n\nError: $error", true)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MusicPlayerActivity", "Error finding session: ${e.message}", e)
                    showError("Error", "Failed to find session: ${e.message}", true)
                }
            }
        } ?: run {
            android.util.Log.e("MusicPlayerActivity", "Room code is null!")
            showError("Error", "No room code provided", true)
        }
    }
    
    private fun rejoinSession() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedSessionId = prefs.getString("last_session_id", null)
        val savedRoomCode = prefs.getString("last_room_code", null)
        val deviceId = generateDeviceId()
        val userName = getCurrentUserName()
        
        if (savedSessionId != null && savedRoomCode != null) {
            lifecycleScope.launch {
                try {
                    // First check if session still exists
                    val sessionResult = firebaseService.getSessionByRoomCode(savedRoomCode)
                    if (sessionResult.isSuccess) {
                        val session = sessionResult.getOrThrow()
                        
                        // Rejoin the session
                        val joinResult = firebaseService.joinSession(savedRoomCode, userName, deviceId)
                        if (joinResult.isSuccess) {
                            currentSession = session
                            sessionId = session.sessionId
                            roomCode = session.roomCode
                            startListeningForUpdates(session.sessionId)
                        } else {
                            showError("Failed to Rejoin", "Could not rejoin the session.", true)
                        }
                    } else {
                        showError("Session Expired", "The session is no longer available.", true)
                    }
                } catch (e: Exception) {
                    showError("Error", "Failed to rejoin session: ${e.message}", true)
                }
            }
        } else {
            showError("No Session", "No previous session found.", true)
        }
    }
    
    private fun updateSessionInfo() {
        currentSession?.let { session ->
            binding.toolbar.title = session.sessionName
            binding.tvRoomCode.text = "Room: ${session.roomCode}"
        }
    }
    
    private fun updateQueue() {
        // Safety check to prevent crash if adapter isn't initialized yet
        if (::queueAdapter.isInitialized) {
            queueAdapter.updateQueue(queue)
        }
        
        // Update queue count
        binding.tvQueueCount.text = "${queue.size} songs"
        
        // Show empty state if needed
        if (queue.isEmpty()) {
            binding.tvQueueEmpty.visibility = View.VISIBLE
            binding.rvQueue.visibility = View.GONE
        } else {
            binding.tvQueueEmpty.visibility = View.GONE
            binding.rvQueue.visibility = View.VISIBLE
        }
    }
    
    private fun updateCurrentTrack() {
        currentTrack?.let { current ->
            if (current.track != null) {
                android.util.Log.d("MusicPlayerActivity", "📱 Updating current track display: ${current.track.title} - ${current.track.artist}")
                
                val newTrackText = "${current.track.title} - ${current.track.artist}"
                
                // Animate text change if it's different
                if (binding.tvCurrentTrack.text.toString() != newTrackText) {
                    animationHelper.animateTextChange(binding.tvCurrentTrack, newTrackText)
                    
                    // Bounce effect to draw attention to new track
                    animationHelper.bounceView(binding.layoutCurrentTrack)
                }
                
                // Smooth transition between layouts
                if (binding.layoutCurrentTrack.visibility != View.VISIBLE) {
                    animationHelper.crossFadeViews(binding.layoutNoTrack, binding.layoutCurrentTrack)
                }
                
                // Load album art (TODO: implement image loading)
                // Glide.with(this).load(current.track.albumArt).into(binding.ivAlbumArt)
            } else {
                android.util.Log.d("MusicPlayerActivity", "📱 No track in current track object")
                if (binding.layoutNoTrack.visibility != View.VISIBLE) {
                    animationHelper.crossFadeViews(binding.layoutCurrentTrack, binding.layoutNoTrack)
                }
            }
        } ?: run {
            android.util.Log.d("MusicPlayerActivity", "📱 No current track object")
            if (binding.layoutNoTrack.visibility != View.VISIBLE) {
                animationHelper.crossFadeViews(binding.layoutCurrentTrack, binding.layoutNoTrack)
            }
        }
    }
    
    private fun updatePlaybackControls() {
        playbackState?.let { state ->
            val isPlaying = state.isPlaying
            android.util.Log.d("MusicPlayerActivity", "🎮 Updating playback controls: isPlaying=$isPlaying (isHost=$isHost)")
            
            // Use batched UI update for better performance
            optimizationHelper.batchUIUpdate {
                // Only update icon if it's different from current to avoid conflicts with optimistic updates
                val currentIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                val currentDrawable = binding.btnPlayPause.drawable
                
                // Update with subtle animation only if actually changing
                val lastIconTag = binding.btnPlayPause.getTag(R.id.icon_tag) as? Int
                if (lastIconTag != currentIcon) {
                    binding.btnPlayPause.setTag(R.id.icon_tag, currentIcon)
                    animationHelper.animatePlayPauseButton(binding.btnPlayPause, currentIcon)
                }
                
                // Enable controls for all users (both host and non-host)
                binding.btnPlayPause.isEnabled = true
                binding.btnSkipNext.isEnabled = true
                binding.btnSkipPrevious.isEnabled = true
                
                // Add haptic feedback to buttons if not already added  
                if (binding.btnPlayPause.getTag(R.id.haptic_feedback_added) == null) {
                    animationHelper.addButtonFeedback(binding.btnPlayPause)
                    animationHelper.addButtonFeedback(binding.btnSkipNext)
                    animationHelper.addButtonFeedback(binding.btnSkipPrevious)
                    binding.btnPlayPause.setTag(R.id.haptic_feedback_added, true)
                }
            }
            
            // Update volume slider if needed
            // binding.seekBarVolume.progress = state.volume
        } ?: run {
            android.util.Log.d("MusicPlayerActivity", "🎮 No playback state - disabling controls")
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            binding.btnPlayPause.isEnabled = true // Still allow triggering
            binding.btnSkipNext.isEnabled = isHost // Only host can skip if no state
            binding.btnSkipPrevious.isEnabled = isHost // Only host can skip if no state
        }
    }
    
    private fun updateUsers() {
        usersAdapter.updateUsers(users)
        binding.tvUsersCount.text = "${users.size} connected"
    }
    
    private fun updateUI() {
        updateSessionInfo()
        updateQueue()
        updateCurrentTrack()
        updatePlaybackControls()
        updateUsers()
    }
    
    private fun togglePlayPause() {
        android.util.Log.d("MusicPlayerActivity", "🎵 Play/Pause button clicked!")
        android.util.Log.d("MusicPlayerActivity", "isHost: $isHost")
        android.util.Log.d("MusicPlayerActivity", "Current playback state: ${playbackState?.isPlaying}")
        
        // Optimistic UI update with animation
        val currentlyPlaying = playbackState?.isPlaying ?: false
        val newIcon = if (currentlyPlaying) R.drawable.ic_play else R.drawable.ic_pause
        
        animationHelper.animatePlayPauseButton(binding.btnPlayPause, newIcon) {
            android.util.Log.d("MusicPlayerActivity", "✨ Play/pause button animation completed")
        }
        
        if (!isHost) {
            // Non-host users: send play/pause control request via Firebase for host to process
            android.util.Log.d("MusicPlayerActivity", "Non-host user requesting play/pause via Firebase")
            sessionId?.let { id ->
                lifecycleScope.launch {
                    val action = if (playbackState?.isPlaying == true) "pause" else "play"
                    val controlRequest = mapOf(
                        "action" to action,
                        "requestedBy" to getCurrentUserId(),
                        "timestamp" to System.currentTimeMillis()
                    )
                    firebaseService.sendControlRequest(id, controlRequest)
                }
            }
            return
        }
        
        // Check if host is authenticated with Spotify
        android.util.Log.d("MusicPlayerActivity", "Host user - checking Spotify authentication...")
        if (!spotifyService.isAuthenticated()) {
            android.util.Log.w("MusicPlayerActivity", "Host not authenticated with Spotify - prompting login")
            promptSpotifyLogin()
            return
        }
        
        android.util.Log.d("MusicPlayerActivity", "Host authenticated - proceeding with Spotify API call")
        
        // Host handles Spotify API calls
        lifecycleScope.launch {
            try {
                // First, check available devices
                android.util.Log.d("MusicPlayerActivity", "Checking available Spotify devices before playback...")
                val devicesResult = spotifyService.getDevices()
                if (devicesResult.isSuccess) {
                    val devices = devicesResult.getOrThrow()
                    android.util.Log.d("MusicPlayerActivity", "Available devices: ${devices.size}")
                    
                    if (devices.isEmpty()) {
                        android.util.Log.w("MusicPlayerActivity", "No Spotify devices found!")
                        showError("No Devices Found", "No Spotify devices are available. Please:\n\n1. Open Spotify on your phone, computer, or other device\n2. Start playing any song\n3. Then try again")
                        return@launch
                    }
                    
                    val activeDevice = devices.find { it.isActive }
                    android.util.Log.d("MusicPlayerActivity", "Active device: ${activeDevice?.name ?: "None"}")
                    
                    if (activeDevice == null) {
                        android.util.Log.w("MusicPlayerActivity", "No active device found!")
                        showError("No Active Device", "No Spotify device is currently active. Please:\n\n1. Open Spotify on your device\n2. Start playing any song\n3. Then try using the controls here")
                        return@launch
                    }
                } else {
                    android.util.Log.e("MusicPlayerActivity", "Failed to get devices: ${devicesResult.exceptionOrNull()?.message}")
                }
                
                // Now try to play/pause
                val action = if (playbackState?.isPlaying == true) "pause" else "play"
                android.util.Log.d("MusicPlayerActivity", "Attempting to $action music...")
                
                val result = if (playbackState?.isPlaying == true) {
                    spotifyService.pause()
                } else {
                    // If we have tracks in queue, start playback with our queue
                    if (queue.isNotEmpty()) {
                        android.util.Log.d("MusicPlayerActivity", "Starting playback with app's queue (${queue.size} tracks)...")
                        val trackUris = queue.map { it.second.spotifyUri }.filter { it.isNotEmpty() }
                        android.util.Log.d("MusicPlayerActivity", "Track URIs to play: ${trackUris.size}")
                        
                        if (trackUris.isNotEmpty()) {
                            spotifyService.playWithUris(trackUris)
                        } else {
                            android.util.Log.w("MusicPlayerActivity", "No valid track URIs found in queue")
                            spotifyService.play()
                        }
                    } else {
                        android.util.Log.d("MusicPlayerActivity", "No tracks in queue, attempting basic play...")
                        spotifyService.play()
                    }
                }
                
                if (result.isFailure) {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    android.util.Log.e("MusicPlayerActivity", "Playback $action failed: $error")
                    
                    if (error.contains("authentication", ignoreCase = true)) {
                        promptSpotifyLogin()
                    } else {
                        showError("Playback Error", error)
                    }
                } else {
                    android.util.Log.d("MusicPlayerActivity", "Playback $action successful!")
                }
            } catch (e: Exception) {
                val error = e.message ?: "Unknown error"
                android.util.Log.e("MusicPlayerActivity", "Playback exception: $error", e)
                
                if (error.contains("authentication", ignoreCase = true)) {
                    promptSpotifyLogin()
                } else {
                    showError("Playback Error", error)
                }
            }
        }
    }
    
    private fun skipNext() {
        android.util.Log.d("MusicPlayerActivity", "⏭️ Skip Next clicked (isHost: $isHost)")
        
        if (!isHost) {
            // Non-host users: trigger skip request via Firebase for host to process
            android.util.Log.d("MusicPlayerActivity", "Non-host user requesting skip next via Firebase")
            sessionId?.let { id ->
                lifecycleScope.launch {
                    // Create a skip request that the host will see and process
                    val skipRequest = mapOf(
                        "action" to "skip_next",
                        "requestedBy" to getCurrentUserId(),
                        "timestamp" to System.currentTimeMillis()
                    )
                    firebaseService.sendControlRequest(id, skipRequest)
                }
            }
            return
        }
        
        // Host user: execute skip directly via Spotify
        if (!spotifyService.isAuthenticated()) {
            promptSpotifyLogin()
            return
        }
        
        lifecycleScope.launch {
            try {
                val result = spotifyService.skipNext()
                if (result.isFailure) {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    if (error.contains("authentication", ignoreCase = true)) {
                        promptSpotifyLogin()
                    } else {
                        showError("Skip Error", error)
                    }
                }
            } catch (e: Exception) {
                val error = e.message ?: "Unknown error"
                if (error.contains("authentication", ignoreCase = true)) {
                    promptSpotifyLogin()
                } else {
                    showError("Skip Error", error)
                }
            }
        }
    }
    
    private fun skipPrevious() {
        android.util.Log.d("MusicPlayerActivity", "⏮️ Skip Previous clicked (isHost: $isHost)")
        
        if (!isHost) {
            // Non-host users: trigger skip request via Firebase for host to process
            android.util.Log.d("MusicPlayerActivity", "Non-host user requesting skip previous via Firebase")
            sessionId?.let { id ->
                lifecycleScope.launch {
                    // Create a skip request that the host will see and process
                    val skipRequest = mapOf(
                        "action" to "skip_previous",
                        "requestedBy" to getCurrentUserId(),
                        "timestamp" to System.currentTimeMillis()
                    )
                    firebaseService.sendControlRequest(id, skipRequest)
                }
            }
            return
        }
        
        // Host user: execute skip directly via Spotify
        if (!spotifyService.isAuthenticated()) {
            promptSpotifyLogin()
            return
        }
        
        lifecycleScope.launch {
            try {
                val result = spotifyService.skipPrevious()
                if (result.isFailure) {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    if (error.contains("authentication", ignoreCase = true)) {
                        promptSpotifyLogin()
                    } else {
                        showError("Skip Error", error)
                    }
                }
            } catch (e: Exception) {
                val error = e.message ?: "Unknown error"
                if (error.contains("authentication", ignoreCase = true)) {
                    promptSpotifyLogin()
                } else {
                    showError("Skip Error", error)
                }
            }
        }
    }
    
    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) {
            searchResultsAdapter.updateTracks(emptyList())
            return
        }
        
        // Show immediate loading state
        binding.progressSearch.visibility = View.VISIBLE
        animationHelper.showLoadingAnimation(binding.progressSearch)
        
        // Use smart search with debouncing and caching
        optimizationHelper.smartSearch(
            query = query,
            debounceMs = 300,
            searchAction = { searchQuery ->
                spotifyService.searchTracks(searchQuery, 20).getOrThrow()
            },
            onResult = { tracks ->
                // Hide loading state
                animationHelper.hideLoadingAnimation(binding.progressSearch)
                binding.progressSearch.visibility = View.GONE
                
                // Update results with fade animation
                searchResultsAdapter.updateTracks(tracks as List<SpotifyTrack>)
                
                // Add slide-in animation for results
                if (tracks.isNotEmpty()) {
                    animationHelper.slideInFromBottom(binding.rvSearchResults)
                }
            }
        )
    }
    
    private fun addTrackToQueue(spotifyTrack: SpotifyTrack) {
        sessionId?.let { id ->
            lifecycleScope.launch {
                try {
                    val userName = getCurrentUserName()
                    val track = spotifyTrack.toTrack(userName)
                    val result = firebaseService.addTrackToQueue(id, track)
                    
                    if (result.isFailure) {
                        showError("Add Track Error", result.exceptionOrNull()?.message ?: "Failed to add track")
                    }
                } catch (e: Exception) {
                    showError("Add Track Error", e.message ?: "Failed to add track")
                }
            }
        }
    }
    
    private fun removeTrackFromQueue(trackKey: String, track: Track) {
        sessionId?.let { id ->
            lifecycleScope.launch {
                try {
                    val userName = getCurrentUserName()
                    val result = firebaseService.removeTrackFromQueue(id, trackKey, userName)
                    
                    if (result.isFailure) {
                        showError("Remove Track Error", result.exceptionOrNull()?.message ?: "Failed to remove track")
                    }
                } catch (e: Exception) {
                    showError("Remove Track Error", e.message ?: "Failed to remove track")
                }
            }
        }
    }
    
    private fun copyRoomCodeToClipboard() {
        roomCode?.let { code ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Room Code", code)
            clipboard.setPrimaryClip(clip)
            
            android.widget.Toast.makeText(
                this,
                getString(R.string.room_code_copied),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    

    
    private fun generateDeviceId(): String {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        
        if (deviceId == null) {
            deviceId = "device_${System.currentTimeMillis()}_${(1000..9999).random()}"
            prefs.edit().putString("device_id", deviceId).apply()
        }
        
        return deviceId
    }

    private fun getCurrentUserName(): String {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("last_user_name", null) ?: "Unknown User"
    }
    
    private fun showError(title: String, message: String, finishActivity: Boolean = false) {
        if (!isFinishing && !isDestroyed) {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                    if (finishActivity) {
                        finish()
                    }
                }
                .setOnDismissListener {
                    if (finishActivity) {
                        finish()
                    }
                }
                .show()
        }
    }
    
    private fun promptSpotifyLogin() {
        if (!isFinishing && !isDestroyed) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Spotify Login Required")
                .setMessage("To play music, you need to connect your Spotify Premium account. This will open your browser to login to Spotify.")
                .setPositiveButton("Login to Spotify") { dialog, _ ->
                    dialog.dismiss()
                    openSpotifyLogin()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun openSpotifyLogin() {
        try {
            val authUrl = spotifyService.getAuthorizationUrl()
            android.util.Log.d("MusicPlayerActivity", "Opening Spotify auth URL: $authUrl")
            
            // Try Custom Tabs first (recommended approach)
            try {
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setUrlBarHidingEnabled(false)
                    .build()
                
                customTabsIntent.launchUrl(this, Uri.parse(authUrl))
                android.util.Log.d("MusicPlayerActivity", "Successfully opened Custom Tabs")
                return
            } catch (e: Exception) {
                android.util.Log.w("MusicPlayerActivity", "Custom Tabs failed, trying regular browser: ${e.message}")
            }
            
            // Fallback to regular browser intent
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // Check if there's an app that can handle this intent
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                android.util.Log.d("MusicPlayerActivity", "Successfully started browser intent")
            } else {
                android.util.Log.e("MusicPlayerActivity", "No app found to handle browser intent")
                showError("Login Error", "No browser app found to open Spotify login. Please ensure you have a web browser installed.")
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicPlayerActivity", "Error opening Spotify login", e)
            showError("Login Error", "Failed to open Spotify login: ${e.message}")
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyCallback()
    }
    
    private fun handleSpotifyCallback() {
        val uri = intent?.data
        android.util.Log.d("MusicPlayerActivity", "handleSpotifyCallback called with URI: $uri")
        
        if (uri != null && uri.scheme == "com.groupmusicplayer" && uri.host == "callback") {
            android.util.Log.d("MusicPlayerActivity", "Processing Spotify callback")
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")
            
            if (error != null) {
                android.util.Log.e("MusicPlayerActivity", "Spotify auth error: $error")
                showError("Spotify Login Failed", "Error: $error")
                // Clear the intent data to prevent reprocessing
                intent.data = null
                return
            }
            
            if (code != null) {
                android.util.Log.d("MusicPlayerActivity", "Got authorization code, exchanging for token")
                // Clear the intent data immediately to prevent duplicate processing
                intent.data = null
                
                // Exchange code for access token
                lifecycleScope.launch {
                    try {
                        val result = spotifyService.exchangeCodeForToken(code)
                        if (result.isSuccess) {
                            android.util.Log.d("MusicPlayerActivity", "Successfully authenticated with Spotify")
                            android.widget.Toast.makeText(this@MusicPlayerActivity, "Successfully connected to Spotify!", android.widget.Toast.LENGTH_LONG).show()
                            
                            // Start monitoring playback state if user is host
                            if (isHost) {
                                startPlaybackStateMonitoring()
                            }
                        } else {
                            android.util.Log.e("MusicPlayerActivity", "Failed to exchange token: ${result.exceptionOrNull()?.message}")
                            showError("Spotify Login Failed", result.exceptionOrNull()?.message ?: "Unknown error")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MusicPlayerActivity", "Exception during token exchange: ${e.message}", e)
                        showError("Spotify Login Failed", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Handle Spotify callback in case activity was recreated
        handleSpotifyCallback()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        // Show confirmation dialog before leaving
        MaterialAlertDialogBuilder(this)
            .setTitle("Leave Session?")
            .setMessage("Are you sure you want to leave this music session?")
            .setPositiveButton("Leave") { _, _ ->
                finish()
            }
            .setNegativeButton("Stay", null)
            .show()
        return true
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel playback state monitoring
        playbackStateUpdateJob?.cancel()
        
        // Cleanup optimization helper to prevent memory leaks
        optimizationHelper.cleanup()
        
        // Update user status to offline
        sessionId?.let { id ->
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", "") ?: ""
            
            lifecycleScope.launch {
                firebaseService.updateUserStatus(id, deviceId, false)
            }
        }
    }

    private fun startPlaybackStateMonitoring() {
        // Cancel any existing monitoring
        playbackStateUpdateJob?.cancel()
        
        // Only monitor if user is the host and authenticated
        if (!isHost || !isSpotifyAuthenticated()) return
        
        android.util.Log.d("MusicPlayerActivity", "Starting playback state monitoring...")
        
        playbackStateUpdateJob = lifecycleScope.launch {
            while (true) {
                try {
                    val currentState = spotifyService.getCurrentPlaybackState()
                    if (currentState.isSuccess) {
                        val spotifyState = currentState.getOrNull()
                        updateUIWithSpotifyState(spotifyState)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MusicPlayerActivity", "Error checking playback state: ${e.message}")
                }
                
                // Check every 3 seconds
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    private fun stopPlaybackStateMonitoring() {
        android.util.Log.d("MusicPlayerActivity", "Stopping playback state monitoring...")
        playbackStateUpdateJob?.cancel()
        playbackStateUpdateJob = null
    }

    private fun isSpotifyAuthenticated(): Boolean {
        val prefs = getSharedPreferences("spotify_prefs", Context.MODE_PRIVATE)
        val accessToken = prefs.getString("access_token", null)
        val expiresAt = prefs.getLong("token_expires", 0)
        return accessToken != null && System.currentTimeMillis() < expiresAt
    }

    private fun updateUIWithSpotifyState(spotifyState: com.groupmusicplayer.services.SpotifyPlaybackState?) {
        lifecycleScope.launch {
            if (spotifyState != null) {
                android.util.Log.d("MusicPlayerActivity", "🎵 Updating UI with Spotify state:")
                android.util.Log.d("MusicPlayerActivity", "  Playing: ${spotifyState.isPlaying}")
                android.util.Log.d("MusicPlayerActivity", "  Track: ${spotifyState.item?.name ?: "None"}")
                
                // Update play/pause button
                binding.btnPlayPause.setImageResource(
                    if (spotifyState.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                
                // Update current track display
                if (spotifyState.item != null) {
                    binding.tvCurrentTrack.text = "${spotifyState.item.name} - ${spotifyState.item.artists.firstOrNull()?.name ?: "Unknown Artist"}"
                    binding.layoutCurrentTrack.visibility = View.VISIBLE
                    binding.layoutNoTrack.visibility = View.GONE
                    
                    // Sync current track to Firebase for other users to see
                    val currentTrack = CurrentTrack(
                        track = Track(
                            spotifyId = spotifyState.item.id,
                            title = spotifyState.item.name,
                            artist = spotifyState.item.artists.firstOrNull()?.name ?: "Unknown Artist",
                            album = spotifyState.item.album?.name ?: "",
                            duration = spotifyState.item.duration_ms ?: 0L
                        ),
                        isPlaying = spotifyState.isPlaying,
                        position = spotifyState.progressMs ?: 0L,
                        lastUpdated = System.currentTimeMillis()
                    )
                    
                    // Update Firebase with current track info
                    sessionId?.let { id ->
                        android.util.Log.d("MusicPlayerActivity", "Syncing current track to Firebase: ${spotifyState.item.name}")
                        lifecycleScope.launch {
                            firebaseService.updateCurrentTrack(id, currentTrack)
                        }
                    }
                } else {
                    binding.layoutCurrentTrack.visibility = View.GONE
                    binding.layoutNoTrack.visibility = View.VISIBLE
                    
                    // Clear current track in Firebase
                    sessionId?.let { id ->
                        android.util.Log.d("MusicPlayerActivity", "Clearing current track in Firebase")
                        lifecycleScope.launch {
                            firebaseService.updateCurrentTrack(id, null)
                        }
                    }
                }
                
                // Update our internal playback state
                val newPlaybackState = PlaybackState(
                    isPlaying = spotifyState.isPlaying,
                    position = spotifyState.progressMs ?: 0L,
                    lastUpdated = System.currentTimeMillis()
                )
                
                // Always update Firebase to keep all users in sync
                android.util.Log.d("MusicPlayerActivity", "📡 Syncing playback state to Firebase for all users...")
                sessionId?.let { id ->
                    lifecycleScope.launch {
                        firebaseService.updatePlaybackState(id, newPlaybackState)
                    }
                }
                
                playbackState = newPlaybackState
                
            } else {
                android.util.Log.d("MusicPlayerActivity", "No active Spotify playback")
                binding.layoutCurrentTrack.visibility = View.GONE
                binding.layoutNoTrack.visibility = View.VISIBLE
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            }
        }
    }

    private fun processControlRequest(request: Map<String, Any>) {
        val action = request["action"] as? String
        val requestedBy = request["requestedBy"] as? String
        
        android.util.Log.d("MusicPlayerActivity", "🎮 Processing control request: $action from $requestedBy")
        
        when (action) {
            "skip_next" -> {
                android.util.Log.d("MusicPlayerActivity", "Processing skip next request from non-host user")
                if (spotifyService.isAuthenticated()) {
                    lifecycleScope.launch {
                        try {
                            val result = spotifyService.skipNext()
                            if (result.isFailure) {
                                android.util.Log.e("MusicPlayerActivity", "Skip next failed: ${result.exceptionOrNull()?.message}")
                            } else {
                                android.util.Log.d("MusicPlayerActivity", "Skip next successful via remote request")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MusicPlayerActivity", "Skip next error: ${e.message}")
                        }
                    }
                }
            }
            "skip_previous" -> {
                android.util.Log.d("MusicPlayerActivity", "Processing skip previous request from non-host user")
                if (spotifyService.isAuthenticated()) {
                    lifecycleScope.launch {
                        try {
                            val result = spotifyService.skipPrevious()
                            if (result.isFailure) {
                                android.util.Log.e("MusicPlayerActivity", "Skip previous failed: ${result.exceptionOrNull()?.message}")
                            } else {
                                android.util.Log.d("MusicPlayerActivity", "Skip previous successful via remote request")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MusicPlayerActivity", "Skip previous error: ${e.message}")
                        }
                    }
                }
            }
            "play" -> {
                android.util.Log.d("MusicPlayerActivity", "Processing play request from non-host user")
                if (spotifyService.isAuthenticated()) {
                    lifecycleScope.launch {
                        try {
                            val result = spotifyService.play()
                            if (result.isFailure) {
                                android.util.Log.e("MusicPlayerActivity", "Play failed: ${result.exceptionOrNull()?.message}")
                            } else {
                                android.util.Log.d("MusicPlayerActivity", "Play successful via remote request")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MusicPlayerActivity", "Play error: ${e.message}")
                        }
                    }
                }
            }
            "pause" -> {
                android.util.Log.d("MusicPlayerActivity", "Processing pause request from non-host user")
                if (spotifyService.isAuthenticated()) {
                    lifecycleScope.launch {
                        try {
                            val result = spotifyService.pause()
                            if (result.isFailure) {
                                android.util.Log.e("MusicPlayerActivity", "Pause failed: ${result.exceptionOrNull()?.message}")
                            } else {
                                android.util.Log.d("MusicPlayerActivity", "Pause successful via remote request")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MusicPlayerActivity", "Pause error: ${e.message}")
                        }
                    }
                }
            }
            else -> {
                android.util.Log.w("MusicPlayerActivity", "Unknown control request action: $action")
            }
        }
    }

    private fun getCurrentUserId(): String {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_id", "") ?: ""
    }
} 