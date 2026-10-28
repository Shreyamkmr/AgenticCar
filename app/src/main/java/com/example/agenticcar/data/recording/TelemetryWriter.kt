package com.example.agenticcar.data.recording

import android.util.Log
import androidx.room.withTransaction
import com.example.agenticcar.data.db.EventEntity
import com.example.agenticcar.data.db.SignalBlockEntity
import com.example.agenticcar.data.db.TelemetryDatabase
import com.example.agenticcar.data.db.TripEntity
import com.example.agenticcar.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single writer for all telemetry. Producers never touch disk: [submit] is a
 * non-blocking `trySend`. A process-lifetime coroutine sleeps until work
 * arrives, gathers ~1 s of it and commits it in one transaction, so nothing
 * submitted while a service shuts down is lost and an idle app never wakes.
 */
@Singleton
class TelemetryWriter @Inject constructor(
    private val db: TelemetryDatabase,
    @ApplicationScope scope: CoroutineScope,
) {
    sealed interface Op {
        class Trip(val entity: TripEntity) : Op
        class Block(val entity: SignalBlockEntity) : Op
        class Event(val entity: EventEntity) : Op
    }

    private val channel = Channel<Op>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { db.telemetryDao().closeDanglingTrips() }
            while (true) {
                val first = channel.receive()
                delay(BATCH_WINDOW_MILLIS)
                val batch = ArrayList<Op>().apply { add(first) }
                while (true) batch += channel.tryReceive().getOrNull() ?: break
                try {
                    write(batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Dropping ${batch.size} telemetry writes", e)
                }
            }
        }
    }

    fun submit(op: Op) {
        channel.trySend(op)
    }

    private suspend fun write(batch: List<Op>) {
        // Only the latest summary of each trip matters.
        val trips = batch.filterIsInstance<Op.Trip>().associateBy { it.entity.id }.values.map { it.entity }
        val blocks = batch.filterIsInstance<Op.Block>().map { it.entity }
        val events = batch.filterIsInstance<Op.Event>().map { it.entity }
        val dao = db.telemetryDao()
        db.withTransaction {
            if (trips.isNotEmpty()) dao.upsertTrips(trips)
            if (blocks.isNotEmpty()) dao.insertBlocks(blocks)
            if (events.isNotEmpty()) dao.insertEvents(events)
        }
    }

    private companion object {
        const val TAG = "TelemetryWriter"
        const val BATCH_WINDOW_MILLIS = 1_000L
    }
}
