package com.example.vlm_on_mobile.events

import com.example.vlm_on_mobile.tracking.MapEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

sealed class AppEvent {
    val timestampMs: Long = System.currentTimeMillis()

    data class ObjectNew(val entry: MapEntry) : AppEvent()
    data class ObjectReturned(val entry: MapEntry) : AppEvent()
    data class ObjectLost(val entry: MapEntry) : AppEvent()
    data class RotationStart(val direction: String) : AppEvent()
    data class RotationEnd(val yawDeltaDeg: Float, val pitchDeltaDeg: Float) : AppEvent()
    data class ObjectRemoved(val entry: MapEntry, val reason: String) : AppEvent()
}

class EventBuffer {

    private val channel = Channel<AppEvent>(Channel.UNLIMITED)
    val eventsFlow: Flow<AppEvent> = channel.receiveAsFlow()

    private val eventHistory = mutableListOf<AppEvent>()

    fun emit(event: AppEvent) {
        synchronized(eventHistory) {
            eventHistory.add(event)
        }
        channel.trySend(event)
    }

    fun getRecentEvents(sinceTimestampMs: Long): List<AppEvent> {
        return synchronized(eventHistory) {
            eventHistory.filter { it.timestampMs >= sinceTimestampMs }
        }
    }

    fun clear() {
        synchronized(eventHistory) {
            eventHistory.clear()
        }
    }
}
