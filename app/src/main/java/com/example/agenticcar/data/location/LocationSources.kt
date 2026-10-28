package com.example.agenticcar.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.example.agenticcar.data.TelemetryState
import com.example.agenticcar.protocol.obd.PidCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** One position fix. Optional fields are null when the provider does not report them. */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    /** Metres above mean sea level when known, otherwise above the WGS84 ellipsoid. */
    val altitudeM: Double?,
    val speedMs: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double?,
)

interface LocationSource {
    fun fixes(): Flow<GpsFix>
}

/** The phone's GPS receiver at 1 Hz, without Google Play services. */
class AndroidLocationSource(private val context: Context) : LocationSource {

    @SuppressLint("MissingPermission") // checked by hasPermission(); the flow ends immediately otherwise
    override fun fixes(): Flow<GpsFix> = callbackFlow {
        if (!hasPermission(context)) {
            close()
            return@callbackFlow
        }
        val lm = context.getSystemService(LocationManager::class.java)
        // Converting to sea-level altitude reads a geoid model from disk, so the callback runs off the main thread.
        val converter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) AltitudeConverter() else null
        val listener = LocationListenerCompat { location -> trySend(location.toFix(converter)) }
        val request = LocationRequestCompat.Builder(INTERVAL_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .build()
        LocationManagerCompat.requestLocationUpdates(
            lm, LocationManager.GPS_PROVIDER, request, Dispatchers.IO.asExecutor(), listener,
        )
        awaitClose { LocationManagerCompat.removeUpdates(lm, listener) }
    }

    private fun Location.toFix(converter: Any?): GpsFix {
        var altitude: Double? = if (hasAltitude()) altitude else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasAltitude()) {
            runCatching {
                (converter as AltitudeConverter).addMslAltitudeToLocation(context, this)
                if (hasMslAltitude()) altitude = mslAltitudeMeters
            }.onFailure { Log.w(TAG, "Sea-level altitude unavailable; using ellipsoid height", it) }
        }
        return GpsFix(
            latitude = latitude,
            longitude = longitude,
            altitudeM = altitude,
            speedMs = if (hasSpeed()) speed.toDouble() else null,
            bearingDeg = if (hasBearing()) bearing.toDouble() else null,
            accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
        )
    }

    companion object {
        private const val TAG = "AndroidLocationSource"
        private const val INTERVAL_MS = 1_000L

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Drives a hilly route at the speed the simulated ECU reports, so elevation and grade analysis can
 * be tested at a desk. The terrain mixes a long ~7 % hill with shorter ~4 % rolls, and altitude
 * carries ±2 m of GPS-like noise.
 */
class SimulatedLocationSource(
    private val telemetryState: TelemetryState,
    private val random: Random = Random.Default,
) : LocationSource {

    override fun fixes(): Flow<GpsFix> = flow {
        var lat = START_LAT
        var lon = START_LON
        var distanceM = 0.0
        var lastNanos = System.nanoTime()
        while (true) {
            val speedKmh = telemetryState.liveSignals.value[PidCatalog.ID_SPEED] ?: 0.0
            val speedMs = speedKmh / 3.6
            val bearing = 90 + 35 * sin(distanceM / 2_500)
            // Real elapsed time, not the nominal interval: fixes arrive a little slower than 1 Hz.
            val now = System.nanoTime()
            val step = speedMs * (now - lastNanos) / 1e9
            lastNanos = now
            if (step > 0) {
                val moved = destination(lat, lon, bearing, step)
                lat = moved.first
                lon = moved.second
                distanceM += step
            }
            emit(
                GpsFix(
                    latitude = lat,
                    longitude = lon,
                    altitudeM = terrainAltitude(distanceM) + random.nextDouble(-2.0, 2.0),
                    speedMs = speedMs,
                    bearingDeg = (bearing + 360) % 360,
                    accuracyM = 4.0 + random.nextDouble(0.0, 2.0),
                ),
            )
            delay(INTERVAL_MS)
        }
    }

    companion object {
        // Peak District, UK: a plausible place for hills.
        private const val START_LAT = 53.3500
        private const val START_LON = -1.7800
        private const val INTERVAL_MS = 1_000L
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Base 250 m, a long hill (max slope 35/500 = 7 %) plus short rolls (6/150 = 4 %). */
        fun terrainAltitude(distanceM: Double): Double =
            250 + 35 * sin(distanceM / 500) + 6 * sin(distanceM / 150)

        private fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
            val d = distanceM / EARTH_RADIUS_M
            val b = Math.toRadians(bearingDeg)
            val p1 = Math.toRadians(lat)
            val l1 = Math.toRadians(lon)
            val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
            val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
            return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
        }
    }
}
