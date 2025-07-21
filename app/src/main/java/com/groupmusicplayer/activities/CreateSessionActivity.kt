package com.groupmusicplayer.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.groupmusicplayer.R
import com.groupmusicplayer.databinding.ActivityCreateSessionBinding
import com.groupmusicplayer.services.FirebaseService
import kotlinx.coroutines.launch

class CreateSessionActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityCreateSessionBinding
    private lateinit var firebaseService: FirebaseService
    private var isCreating = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        firebaseService = FirebaseService.getInstance()
        
        setupUI()
        setupToolbar()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.create_session_title)
        }
    }
    
    private fun setupUI() {
        binding.btnCreate.setOnClickListener {
            createSession()
        }
        
        // Auto-fill session name with device name
        val deviceName = Settings.Global.getString(contentResolver, "device_name") 
            ?: Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).take(6)
        binding.etSessionName.setText("${deviceName}'s Session")
        binding.etSessionName.selectAll()
    }
    
    private fun createSession() {
        if (isCreating) return
        
        val sessionName = binding.etSessionName.text.toString().trim()
        
        if (sessionName.isEmpty()) {
            binding.etSessionName.error = "Please enter a session name"
            return
        }
        
        if (sessionName.length > 50) {
            binding.etSessionName.error = "Session name too long (max 50 characters)"
            return
        }
        
        isCreating = true
        setLoadingState(true)
        
        lifecycleScope.launch {
            try {
                val deviceId = generateDeviceId()
                val result = firebaseService.createSession(sessionName, deviceId)
                
                if (result.isSuccess) {
                    val session = result.getOrThrow()
                    saveSessionToPrefs(session.sessionId, session.roomCode)
                    showSessionCreated(session.roomCode)
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    showError("Failed to create session", error)
                }
            } catch (e: Exception) {
                showError("Failed to create session", e.message ?: "Unknown error")
            } finally {
                isCreating = false
                setLoadingState(false)
            }
        }
    }
    
    private fun setLoadingState(loading: Boolean) {
        binding.btnCreate.isEnabled = !loading
        binding.etSessionName.isEnabled = !loading
        binding.progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnCreate.text = if (loading) getString(R.string.creating_session) else getString(R.string.btn_create)
    }
    
    private fun showSessionCreated(roomCode: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.session_created_title))
            .setMessage(getString(R.string.session_created_message, roomCode))
            .setPositiveButton("Continue") { _, _ ->
                navigateToMusicPlayer(roomCode)
            }
            .setNeutralButton("Copy Code") { _, _ ->
                copyRoomCodeToClipboard(roomCode)
                navigateToMusicPlayer(roomCode)
            }
            .setCancelable(false)
            .show()
    }
    
    private fun copyRoomCodeToClipboard(roomCode: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Room Code", roomCode)
        clipboard.setPrimaryClip(clip)
        
        // Show toast
        android.widget.Toast.makeText(
            this, 
            getString(R.string.room_code_copied), 
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    private fun navigateToMusicPlayer(roomCode: String) {
        val intent = Intent(this, MusicPlayerActivity::class.java).apply {
            putExtra("room_code", roomCode)
            putExtra("is_host", true)
            // Clear back stack so user can't go back to create session
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
    
    private fun saveSessionToPrefs(sessionId: String, roomCode: String) {
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_session_id", sessionId)
            .putString("last_room_code", roomCode)
            .putBoolean("is_host", true)
            .apply()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
} 