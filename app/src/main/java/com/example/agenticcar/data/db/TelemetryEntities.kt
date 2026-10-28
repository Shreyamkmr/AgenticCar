package com.example.agenticcar.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One engine-on session. [id] is the start time in epoch milliseconds, so it is
 * unique, sortable and lets blocks map their offsets back to wall-clock time.
 * Summary columns are updated incrementally while the trip is recorded.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val vin: String?,
    val protocol: String?,
    val adapter: String?,
    /** Comma-separated signal ids the ECU supported during this trip. */
    @ColumnInfo(name = "signal_ids") val signalIds: String,
    @ColumnInfo(name = "distance_km") val distanceKm: Double,
    @ColumnInfo(name = "fuel_used_l") val fuelUsedL: Double?,
    @ColumnInfo(name = "idle_s") val idleSeconds: Double,
    @ColumnInfo(name = "moving_s") val movingSeconds: Double,
    @ColumnInfo(name = "max_speed_kmh") val maxSpeedKmh: Double?,
    @ColumnInfo(name = "max_rpm") val maxRpm: Double?,
    @ColumnInfo(name = "max_coolant_c") val maxCoolantC: Double?,
    @ColumnInfo(name = "raw_samples") val rawSamples: Long,
    @ColumnInfo(name = "stored_samples") val storedSamples: Long,
    @ColumnInfo(name = "stored_bytes") val storedBytes: Long,
)

/**
 * One signal over one minute of one trip: exact min/max/mean of every reading,
 * plus the deadband-compressed samples delta/varint-encoded in [data]
 * (see [com.example.agenticcar.data.recording.BlockCodec]).
 *
 * The composite key clusters a signal's blocks in time order, which is the access
 * path of every series query.
 */
@Entity(tableName = "signal_blocks", primaryKeys = ["trip_id", "signal_id", "start_ms"])
class SignalBlockEntity(
    @ColumnInfo(name = "trip_id") val tripId: Long,
    @ColumnInfo(name = "signal_id") val signalId: Int,
    /** Block start, ms from trip start (a multiple of 60 000). */
    @ColumnInfo(name = "start_ms") val startMs: Int,
    /** Readings received in the block. */
    @ColumnInfo(name = "n") val readings: Int,
    /** Readings kept after deadband compression. */
    @ColumnInfo(name = "stored") val stored: Int,
    @ColumnInfo(name = "min_value") val minValue: Double,
    @ColumnInfo(name = "max_value") val maxValue: Double,
    @ColumnInfo(name = "mean_value") val meanValue: Double,
    @ColumnInfo(name = "data", typeAffinity = ColumnInfo.BLOB) val data: ByteArray,
)

/** Something worth an AI's attention: harsh driving, faults, threshold crossings. */
@Entity(tableName = "events", indices = [Index("trip_id"), Index("t")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trip_id") val tripId: Long?,
    /** Epoch milliseconds. */
    val t: Long,
    val type: String,
    val code: String? = null,
    val value: Double? = null,
)

data class SignalStatsRow(
    val signalId: Int,
    val minValue: Double,
    val maxValue: Double,
    val meanValue: Double,
    val readings: Long,
)

data class EventCountRow(val type: String, val count: Int)

/** Sums are null when the table is empty. */
data class StorageTotalsRow(val blockCount: Long, val readingCount: Long?, val storedCount: Long?, val byteCount: Long?)
