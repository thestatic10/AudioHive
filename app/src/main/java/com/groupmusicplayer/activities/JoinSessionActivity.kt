package com.groupmusicplayer.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.groupmusicplayer.R
import com.groupmusicplayer.databinding.ActivityJoinSessionBinding
import com.groupmusicplayer.services.FirebaseService
import kotlinx.coroutines.launch

class JoinSessionActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityJoinSessionBinding
    private lateinit var firebaseService: FirebaseService
    private var isJoining = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        firebaseService = FirebaseService.getInstance()
        
        setupUI()
        setupToolbar()
        loadSavedName()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.join_session_title)
        }
    }
    
    private fun setupUI() {
        binding.btnJoin.setOnClickListener {
            joinSession()
        }
        
        // Auto-format room code input
        binding.etRoomCode.setOnTextChangedListener { text ->
            val formatted = text.toString().uppercase().replace(Regex("[^A-Z0-9]"), "")
            if (formatted != text.toString()) {
                binding.etRoomCode.setText(formatted)
                binding.etRoomCode.setSelection(formatted.length)
            }
        }
        
        // Enable join button only when inputs are valid
        binding.etRoomCode.setOnTextChangedListener { validateInputs() }
        binding.etUserName.setOnTextChangedListener { validateInputs() }
    }
    
    private fun validateInputs() {
        val roomCode = binding.etRoomCode.text.toString().trim()
        val userName = binding.etUserName.text.toString().trim()
        
        binding.btnJoin.isEnabled = roomCode.length == 6 && userName.isNotEmpty() && !isJoining
    }
    
    private fun loadSavedName() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("last_user_name", "")
        if (!savedName.isNullOrEmpty()) {
            binding.etUserName.setText(savedName)
        }
    }
    
    private fun joinSession() {
        if (isJoining) return
        
        val roomCode = binding.etRoomCode.text.toString().trim().uppercase()
        val userName = binding.etUserName.text.toString().trim()
        
        // Debug logging
        android.util.Log.d("JoinSession", "Attempting to join session with roomCode: $roomCode, userName: $userName")
        
        // Validate inputs
        if (roomCode.length != 6) {
            binding.etRoomCode.error = getString(R.string.room_code_invalid)
            android.util.Log.d("JoinSession", "Invalid room code length: ${roomCode.length}")
            return
        }
        
        if (userName.isEmpty()) {
            binding.etUserName.error = getString(R.string.name_required)
            android.util.Log.d("JoinSession", "Empty user name")
            return
        }
        
        if (userName.length > 30) {
            binding.etUserName.error = "Name too long (max 30 characters)"
            android.util.Log.d("JoinSession", "User name too long: ${userName.length}")
            return
        }
        
        isJoining = true
        setLoadingState(true)
        
        // Show debug toast
        android.widget.Toast.makeText(this, "Attempting to join session...", android.widget.Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                android.util.Log.d("JoinSession", "Generating device ID...")
                val deviceId = generateDeviceId()
                android.util.Log.d("JoinSession", "Device ID: $deviceId")
                
                android.util.Log.d("JoinSession", "Calling firebaseService.joinSession...")
                val result = firebaseService.joinSession(roomCode, userName, deviceId)
                
                android.util.Log.d("JoinSession", "Join result: isSuccess = ${result.isSuccess}")
                
                if (result.isSuccess) {
                    val session = result.getOrThrow()
                    android.util.Log.d("JoinSession", "Successfully joined session: ${session.sessionName} (${session.sessionId})")
                    
                    saveSessionToPrefs(session.sessionId, session.roomCode, userName)
                    showJoinSuccess(session.sessionName, session.roomCode)
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    android.util.Log.e("JoinSession", "Failed to join session: $error")
                    showError("Failed to join session", error)
                }
            } catch (e: Exception) {
                android.util.Log.e("JoinSession", "Exception during join: ${e.message}", e)
                showError("Failed to join session", e.message ?: "Unknown error")
            } finally {
                isJoining = false
                setLoadingState(false)
            }
        }
    }
    
    private fun setLoadingState(loading: Boolean) {
        binding.btnJoin.isEnabled = !loading
        binding.etRoomCode.isEnabled = !loading
        binding.etUserName.isEnabled = !loading
        binding.progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnJoin.text = if (loading) getString(R.string.joining_session) else getString(R.string.btn_join)
        
        if (!loading) {
            validateInputs()
        }
    }
    
    private fun showJoinSuccess(sessionName: String, roomCode: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Joined Successfully!")
            .setMessage("You've joined \"$sessionName\"\n\nYou can now control the music together with everyone else in the session.")
            .setPositiveButton("Continue") { _, _ ->
                navigateToMusicPlayer(roomCode)
            }
            .setCancelable(false)
            .show()
    }
    
    private fun navigateToMusicPlayer(roomCode: String) {
        android.util.Log.d("JoinSession", "Navigating to MusicPlayer with room code: '$roomCode'")
        android.util.Log.d("JoinSession", "Room code length: ${roomCode.length}")
        
        val intent = Intent(this, MusicPlayerActivity::class.java).apply {
            putExtra("room_code", roomCode)
            putExtra("is_host", false)
            // Clear back stack so user can't go back to join session
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
    
    private fun showError(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
    
    private fun generateDeviceId(): String {
        // Generate a unique device ID
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        
        if (deviceId == null) {
            deviceId = "device_${System.currentTimeMillis()}_${(1000..9999).random()}"
            prefs.edit().putString("device_id", deviceId).apply()
        }
        
        return deviceId
    }
    
    private fun saveSessionToPrefs(sessionId: String, roomCode: String, userName: String) {
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_session_id", sessionId)
            .putString("last_room_code", roomCode)
            .putString("last_user_name", userName)
            .putBoolean("is_host", false)
            .apply()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

// Extension function for text change listener
fun android.widget.EditText.setOnTextChangedListener(action: (String) -> Unit) {
    this.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            action(s.toString())
        }
    })
}