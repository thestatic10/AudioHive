package com.groupmusicplayer.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart

class UIAnimationHelper(private val context: Context) {
    
    companion object {
        const val FADE_DURATION = 300L
        const val SCALE_DURATION = 200L
        const val SLIDE_DURATION = 400L
    }
    
    /**
     * Animates play/pause button state change with smooth scaling and rotation
     */
    fun animatePlayPauseButton(button: ImageButton, newIcon: Int, callback: (() -> Unit)? = null) {
        // Scale down, change icon, scale up
        ObjectAnimator.ofFloat(button, "scaleX", 1f, 0.8f).apply {
            duration = SCALE_DURATION / 2
            doOnEnd {
                button.setImageResource(newIcon)
                ObjectAnimator.ofFloat(button, "scaleX", 0.8f, 1f).apply {
                    duration = SCALE_DURATION / 2
                    doOnEnd { callback?.invoke() }
                    start()
                }
            }
            start()
        }
        
        ObjectAnimator.ofFloat(button, "scaleY", 1f, 0.8f).apply {
            duration = SCALE_DURATION / 2
            doOnEnd {
                ObjectAnimator.ofFloat(button, "scaleY", 0.8f, 1f).apply {
                    duration = SCALE_DURATION / 2
                    start()
                }
            }
            start()
        }
    }
    
    /**
     * Provides haptic feedback for button presses
     */
    fun addButtonFeedback(button: View) {
        button.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Light haptic feedback
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    // Quick scale animation
                    v.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(100)
                        .start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // Restore scale
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .start()
                }
            }
            false // Don't consume the event
        }
    }
    
    /**
     * Smoothly transitions between views with fade effect
     */
    fun crossFadeViews(viewOut: View, viewIn: View) {
        viewIn.alpha = 0f
        viewIn.visibility = View.VISIBLE
        
        viewIn.animate()
            .alpha(1f)
            .setDuration(FADE_DURATION)
            .setListener(null)
        
        viewOut.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    viewOut.visibility = View.GONE
                    viewOut.alpha = 1f // Reset for future use
                }
            })
    }
    
    /**
     * Animates text change with fade effect
     */
    fun animateTextChange(view: android.widget.TextView, newText: String) {
        view.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION / 2)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.text = newText
                    view.animate()
                        .alpha(1f)
                        .setDuration(FADE_DURATION / 2)
                        .setListener(null)
                }
            })
    }
    
    /**
     * Slides view in from bottom with fade
     */
    fun slideInFromBottom(view: View) {
        view.translationY = view.height.toFloat()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(SLIDE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
    
    /**
     * Shows loading state with pulsing animation
     */
    fun showLoadingAnimation(view: View) {
        val pulseAnimator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.3f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        view.tag = pulseAnimator // Store to stop later
        pulseAnimator.start()
    }
    
    /**
     * Stops loading animation and restores view
     */
    fun hideLoadingAnimation(view: View) {
        val animator = view.tag as? ObjectAnimator
        animator?.cancel()
        view.alpha = 1f
        view.tag = null
    }
    
    /**
     * Bounces a view to draw attention
     */
    fun bounceView(view: View) {
        view.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(150)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }
    
    /**
     * Shakes a view to indicate error
     */
    fun shakeView(view: View) {
        val shake = ObjectAnimator.ofFloat(view, "translationX", 0f, 25f, -25f, 25f, -25f, 15f, -15f, 6f, -6f, 0f)
        shake.duration = 600
        shake.start()
    }
} 