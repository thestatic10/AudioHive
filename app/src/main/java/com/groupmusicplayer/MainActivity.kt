package com.groupmusicplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import com.groupmusicplayer.activities.CreateSessionActivity
import com.groupmusicplayer.activities.JoinSessionActivity
import com.groupmusicplayer.activities.MusicPlayerActivity
import com.groupmusicplayer.databinding.ActivityMainBinding
import com.groupmusicplayer.services.SpotifyService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var spotifyService: SpotifyService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        spotifyService = SpotifyService(this)
        
        setupUI()
        checkExistingSession()
    }
    
    private fun setupUI() {
        binding.btnCreateSession.setOnClickListener {
            if (spotifyService.isAuthenticated()) {
                startActivity(Intent(this, CreateSessionActivity::class.java))
            } else {
                // Show Spotify login prompt
                showSpotifyLogin()
            }
        }
        
        binding.btnJoinSession.setOnClickListener {
            startActivity(Intent(this, JoinSessionActivity::class.java))
        }
        
        binding.btnSpotifyLogin.setOnClickListener {
            android.util.Log.e("MainActivity", "🔴🔴🔴 SPOTIFY BUTTON CLICKED! 🔴🔴🔴")
            showSpotifyLogin()
        }
        
        // Update UI based on Spotify authentication status
        updateSpotifyAuthUI()
    }
    
    private fun updateSpotifyAuthUI() {
        if (spotifyService.isAuthenticated()) {
            binding.btnSpotifyLogin.text = "✓ Spotify Connected"
            binding.btnSpotifyLogin.isEnabled = false
            binding.btnCreateSession.isEnabled = true
            binding.btnCreateSession.alpha = 1.0f
            binding.tvSpotifyStatus.text = "Ready to create sessions"
        } else {
            binding.btnSpotifyLogin.text = "Connect to Spotify"
            binding.btnSpotifyLogin.isEnabled = true
            binding.btnCreateSession.isEnabled = false
            binding.btnCreateSession.alpha = 0.5f
            binding.tvSpotifyStatus.text = "Connect to Spotify to create sessions"
        }
    }
    
    private fun showSpotifyLogin() {
        android.util.Log.e("MainActivity", "🎵🎵🎵 SPOTIFY LOGIN DIALOG SHOWING! 🎵🎵🎵")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Spotify Authentication Required")
            .setMessage("To create sessions and control music, you need to connect your Spotify Premium account.\n\nThis will open your browser to log in to Spotify.")
            .setPositiveButton("Connect to Spotify") { _, _ ->
                android.util.Log.e("MainActivity", "🚀🚀🚀 USER CLICKED CONNECT TO SPOTIFY! 🚀🚀🚀")
                openSpotifyLogin()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun openSpotifyLogin() {
        try {
            val authUrl = spotifyService.getAuthorizationUrl()
            android.util.Log.d("MainActivity", "Opening Spotify auth URL: $authUrl")
            
            // Try Custom Tabs first (recommended approach)
            try {
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setUrlBarHidingEnabled(false)
                    .build()
                
                customTabsIntent.launchUrl(this, Uri.parse(authUrl))
                android.util.Log.d("MainActivity", "Successfully opened Custom Tabs")
                return
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Custom Tabs failed, trying regular browser: ${e.message}")
            }
            
            // Fallback to regular browser intent
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // Check if there's an app that can handle this intent
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                android.util.Log.d("MainActivity", "Successfully started browser intent")
            } else {
                android.util.Log.e("MainActivity", "No app found to handle browser intent")
                showError("Login Error", "No browser app found to open Spotify login. Please ensure you have a web browser installed.")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error opening Spotify login", e)
            showError("Login Error", "Failed to open Spotify login: ${e.message}")
        }
    }
    
    private fun showError(title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun checkExistingSession() {
        // Check if user was in a session before
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val lastSessionId = prefs.getString("last_session_id", null)
        val lastRoomCode = prefs.getString("last_room_code", null)
        
        if (lastSessionId != null && lastRoomCode != null) {
            // Show option to rejoin
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Rejoin Session?")
                .setMessage("You were previously in session: $lastRoomCode\n\nWould you like to rejoin?")
                .setPositiveButton("Rejoin") { _, _ ->
                    val intent = Intent(this, MusicPlayerActivity::class.java).apply {
                        putExtra("session_id", lastSessionId)
                        putExtra("room_code", lastRoomCode)
                        putExtra("rejoin", true)
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Start Fresh") { _, _ ->
                    // Clear stored session
                    prefs.edit()
                        .remove("last_session_id")
                        .remove("last_room_code")
                        .apply()
                }
                .show()
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyCallback()
    }
    
    override fun onResume() {
        super.onResume()
        handleSpotifyCallback()
        // Always update auth UI when resuming
        updateSpotifyAuthUI()
    }
    
    private fun handleSpotifyCallback() {
        val uri = intent?.data
        android.util.Log.d("MainActivity", "handleSpotifyCallback called with URI: $uri")
        
        if (uri != null && uri.scheme == "com.groupmusicplayer" && uri.host == "callback") {
            android.util.Log.d("MainActivity", "Processing Spotify callback")
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")
            
            if (error != null) {
                android.util.Log.e("MainActivity", "Spotify auth error: $error")
                showError("Spotify Login Failed", "Error: $error")
                // Clear the intent data to prevent reprocessing
                intent.data = null
                return
            }
            
            if (code != null) {
                android.util.Log.d("MainActivity", "Got authorization code, exchanging for token")
                // Clear the intent data immediately to prevent duplicate processing
                intent.data = null
                
                // Exchange code for token
                lifecycleScope.launch {
                    try {
                        val result = spotifyService.exchangeCodeForToken(code)
                        if (result.isSuccess) {
                            android.util.Log.d("MainActivity", "Successfully exchanged code for token")
                            updateSpotifyAuthUI()
                            // Show success message
                            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Success!")
                                .setMessage("Successfully connected to Spotify!")
                                .setPositiveButton("OK", null)
                                .show()
                        } else {
                            android.util.Log.e("MainActivity", "Failed to exchange code for token: ${result.exceptionOrNull()?.message}")
                            // Show error
                            showError("Authentication Failed", "Failed to connect to Spotify: ${result.exceptionOrNull()?.message}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Exception during token exchange", e)
                        showError("Authentication Failed", "Failed to connect to Spotify: ${e.message}")
                    }
                }
            }
        }
    }
} 