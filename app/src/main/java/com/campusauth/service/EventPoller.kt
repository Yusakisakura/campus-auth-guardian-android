package com.campusauth.service

import android.util.Log
import com.campusauth.ffi.GuardianBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Single event consumer from Rust. Distributes to all subscribers.
 * Prevents ViewModel and Service from competing for the same channel.
 */
object EventPoller {

    private const val TAG = "EventPoller"

    private val _events = MutableSharedFlow<GuardianBridge.GuardianEvent>(
        replay = 8,                 // new subscribers get recent events immediately
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<GuardianBridge.GuardianEvent> = _events.asSharedFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var event = GuardianBridge.pollEvent()
                while (event != null) {
                    _events.emit(event)
                    event = GuardianBridge.pollEvent()
                }
                delay(1000)
            }
        }
        Log.i(TAG, "Event poller started")
    }
}