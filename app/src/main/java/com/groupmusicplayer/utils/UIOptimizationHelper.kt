package com.groupmusicplayer.utils

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*

class UIOptimizationHelper {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val debounceJobs = mutableMapOf<String, Job>()
    
    /**
     * Debounces function calls to prevent excessive API calls or UI updates
     */
    fun debounce(key: String, delayMillis: Long = 300, action: suspend () -> Unit) {
        debounceJobs[key]?.cancel()
        debounceJobs[key] = CoroutineScope(Dispatchers.Main).launch {
            delay(delayMillis)
            action()
            debounceJobs.remove(key)
        }
    }
    
    /**
     * Throttles function calls to limit execution frequency
     */
    private val throttleTimestamps = mutableMapOf<String, Long>()
    
    fun throttle(key: String, intervalMillis: Long = 500, action: () -> Unit) {
        val now = System.currentTimeMillis()
        val lastExecution = throttleTimestamps[key] ?: 0
        
        if (now - lastExecution >= intervalMillis) {
            throttleTimestamps[key] = now
            action()
        }
    }
    
    /**
     * Provides optimistic UI updates while waiting for network/database operations
     */
    fun optimisticUpdate(
        immediateUpdate: () -> Unit,
        networkOperation: suspend () -> Unit,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        // Apply immediate UI change
        immediateUpdate()
        
        // Perform actual operation in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                networkOperation()
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }
    
    /**
     * Batches multiple UI updates to reduce layout passes
     */
    private val batchedUpdates = mutableListOf<() -> Unit>()
    private var batchUpdateJob: Job? = null
    
    fun batchUIUpdate(update: () -> Unit) {
        batchedUpdates.add(update)
        
        batchUpdateJob?.cancel()
        batchUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            delay(16) // ~1 frame delay to batch updates
            batchedUpdates.forEach { it() }
            batchedUpdates.clear()
        }
    }
    
    /**
     * Smart search with debouncing and caching
     */
    private val searchCache = mutableMapOf<String, Any>()
    
    fun smartSearch(
        query: String,
        debounceMs: Long = 300,
        searchAction: suspend (String) -> Any,
        onResult: (Any) -> Unit
    ) {
        // Check cache first
        searchCache[query]?.let { cachedResult ->
            onResult(cachedResult)
            return
        }
        
        // Debounce search
        debounce("search_$query", debounceMs) {
            try {
                val result = searchAction(query)
                searchCache[query] = result
                onResult(result)
            } catch (e: Exception) {
                android.util.Log.e("UIOptimization", "Search error: ${e.message}")
            }
        }
    }
    
    /**
     * Preloads data to improve perceived performance
     */
    fun preloadData(preloadAction: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                preloadAction()
            } catch (e: Exception) {
                android.util.Log.w("UIOptimization", "Preload failed: ${e.message}")
            }
        }
    }
    
    /**
     * Cleanup method to cancel all pending operations
     */
    fun cleanup() {
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
        batchUpdateJob?.cancel()
        batchedUpdates.clear()
        throttleTimestamps.clear()
        searchCache.clear()
    }
    
    companion object {
        @Volatile
        private var INSTANCE: UIOptimizationHelper? = null
        
        fun getInstance(): UIOptimizationHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UIOptimizationHelper().also { INSTANCE = it }
            }
        }
    }
} 