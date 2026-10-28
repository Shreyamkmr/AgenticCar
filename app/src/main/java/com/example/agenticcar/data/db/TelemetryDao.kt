package com.example.agenticcar.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TelemetryDao {

    // region Writes (batched by TelemetryWriter inside one transaction)

    @Upsert
    suspend fun upsertTrips(trips: List<TripEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<SignalBlockEntity>)

    @Insert
    suspend fun insertEvents(events: List<EventEntity>)

    /** Trips left open by a process kill end at their last update. */
    @Query("UPDATE trips SET ended_at = updated_at WHERE ended_at IS NULL")
    suspend fun closeDanglingTrips()

    // endregion

    // region Trips

    @Query("SELECT * FROM trips ORDER BY id DESC LIMIT :limit")
    suspend fun recentTrips(limit: Int): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun trip(id: Long): TripEntity?

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun tripCount(): Int

    // endregion

    // region Signals

    @Query(
        """
        SELECT signal_id AS signalId, MIN(min_value) AS minValue, MAX(max_value) AS maxValue,
               SUM(mean_value * n) / SUM(n) AS meanValue, SUM(n) AS readings
        FROM signal_blocks WHERE trip_id = :tripId GROUP BY signal_id
        """,
    )
    suspend fun tripStats(tripId: Long): List<SignalStatsRow>

    /** Minute-granular stats over an absolute window that may span several trips. */
    @Query(
        """
        SELECT signal_id AS signalId, MIN(min_value) AS minValue, MAX(max_value) AS maxValue,
               SUM(mean_value * n) / SUM(n) AS meanValue, SUM(n) AS readings
        FROM signal_blocks WHERE trip_id + start_ms >= :fromMs AND trip_id + start_ms < :toMs
        GROUP BY signal_id
        """,
    )
    suspend fun statsBetween(fromMs: Long, toMs: Long): List<SignalStatsRow>

    @Query(
        """
        SELECT * FROM signal_blocks
        WHERE trip_id = :tripId AND signal_id IN (:signalIds) AND start_ms >= :fromMs AND start_ms < :toMs
        ORDER BY signal_id, start_ms
        """,
    )
    suspend fun blocks(tripId: Long, signalIds: List<Int>, fromMs: Int, toMs: Int): List<SignalBlockEntity>

    @Query("SELECT * FROM signal_blocks WHERE signal_id = :signalId ORDER BY trip_id DESC, start_ms DESC LIMIT 1")
    suspend fun latestBlock(signalId: Int): SignalBlockEntity?

    // endregion

    // region Events

    @Query(
        """
        SELECT * FROM events
        WHERE (:tripId IS NULL OR trip_id = :tripId) AND (:type IS NULL OR type = :type)
        ORDER BY t DESC LIMIT :limit
        """,
    )
    suspend fun events(tripId: Long?, type: String?, limit: Int): List<EventEntity>

    @Query("SELECT type, COUNT(*) AS count FROM events WHERE trip_id = :tripId GROUP BY type")
    suspend fun eventCounts(tripId: Long): List<EventCountRow>

    // endregion

    @Query(
        "SELECT COUNT(*) AS blockCount, SUM(n) AS readingCount, SUM(stored) AS storedCount, " +
            "SUM(LENGTH(data)) AS byteCount FROM signal_blocks",
    )
    suspend fun storageTotals(): StorageTotalsRow
}
