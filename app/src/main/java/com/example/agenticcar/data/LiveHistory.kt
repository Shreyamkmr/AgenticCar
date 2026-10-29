package com.example.agenticcar.data

import com.example.agenticcar.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last few minutes of every live signal, kept in memory for the board's trend tiles.
 * Started with the app, so trends already have history when the board is opened.
 */
@Singleton
class LiveHistory @Inject constructor(
    telemetry: TelemetryState,
    @ApplicationScope scope: CoroutineScope,
) {
    private val lock = Any()
    private val buffers = HashMap<Int, ArrayDeque<Sample>>()

    private val _version = MutableStateFlow(0L)

    /** Bumped whenever a sample is added; collect it to redraw trends. */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        scope.launch {
            telemetry.liveSignals.collect { signals ->
                val now = System.currentTimeMillis()
                var added = false
                synchronized(lock) {
                    for ((id, value) in signals) {
                        val buffer = buffers.getOrPut(id) { ArrayDeque() }
                        val last = buffer.lastOrNull()
                        // A value is re-added only when it changed or a heartbeat is due, so idle signals stay cheap.
                        if (last == null || (last.v != value && now - last.t >= MIN_GAP_MS) || now - last.t >= HEARTBEAT_MS) {
                            buffer.addLast(Sample(now, value))
                            added = true
                        }
                        while (buffer.isNotEmpty() && now - buffer.first().t > WINDOW_MS) buffer.removeFirst()
                    }
                }
                if (added) _version.value++
            }
        }
    }

    /** Samples of [signalId] newer than [spanMs], oldest first. */
    fun window(signalId: Int, spanMs: Long = WINDOW_MS): List<Sample> {
        val from = System.currentTimeMillis() - spanMs
        return synchronized(lock) { buffers[signalId]?.filter { it.t >= from }.orEmpty() }
    }

    companion object {
        const val WINDOW_MS = 5 * 60_000L
        private const val MIN_GAP_MS = 100L
        private const val HEARTBEAT_MS = 1_000L
    }
}
