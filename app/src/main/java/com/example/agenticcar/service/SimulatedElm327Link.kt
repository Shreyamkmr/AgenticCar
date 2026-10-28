package com.example.agenticcar.service

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * In-process ELM327 + petrol-engine ECU for desk testing. It speaks the real
 * wire format, so discovery, multi-PID batching, ISO-TP multi-frame parsing,
 * DTC and VIN decoding, recording and the MCP tools all run unchanged:
 *
 *  - answers the `01 00/20/40/…` supported-PID bitmaps for [SUPPORTED_PIDS],
 *    including one PID the app cannot decode (0x4F) to exercise raw storage,
 *  - accepts up to 6 PIDs per request and frames long replies like CAN ISO-TP,
 *  - honours `AT S0`, prefixes the first OBD reply with `SEARCHING...`,
 *  - reports stored/permanent P0420 (MIL on) and a pending P0171 after 90 s.
 *
 * Telemetry follows a repeating [CYCLE_SECONDS] drive: engine off → idle →
 * accelerate through gears → cruise (with one harsh brake) → brake → idle → off.
 */
class SimulatedElm327Link(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : Elm327Link {

    @Volatile private var connectedAt: Long? = null
    private var spaces = true
    private var searched = false

    override val isConnected: Boolean get() = connectedAt != null

    override suspend fun connect(address: String): String {
        delay(CONNECT_DELAY_MILLIS)
        connectedAt = clock()
        spaces = true
        searched = false
        return DEVICE_NAME
    }

    override suspend fun execute(command: String, timeoutMillis: Long): String {
        val start = connectedAt ?: throw IOException("Simulated adapter not connected")
        delay(random.nextLong(MIN_LATENCY_MILLIS, MAX_LATENCY_MILLIS))
        val elapsed = (clock() - start) / 1000.0
        val cmd = command.uppercase().filterNot { it.isWhitespace() }

        return when {
            cmd == "ATZ" -> {
                spaces = true
                "\r\rELM327 v1.5\r\r"
            }
            cmd == "ATI" -> "ELM327 v1.5\r\r"
            cmd == "ATS0" -> { spaces = false; "OK\r\r" }
            cmd == "ATS1" -> { spaces = true; "OK\r\r" }
            cmd == "ATDPN" -> "A6\r\r"
            cmd == "ATRV" -> "%.1fV\r\r".format(stateAt(elapsed).voltage)
            cmd.startsWith("AT") -> "OK\r\r"
            cmd.startsWith("01") && cmd.length >= 4 -> mode01(cmd.substring(2), elapsed)
            cmd == "03" -> frame(dtcBytes(0x43, STORED_DTCS))
            cmd == "07" -> frame(dtcBytes(0x47, if (elapsed > 90) listOf("P0171") else emptyList()))
            cmd == "0A" -> frame(dtcBytes(0x4A, STORED_DTCS))
            cmd == "0902" -> frame(listOf(0x49, 0x02, 0x01) + VIN.map { it.code })
            else -> "?\r\r"
        }
    }

    override fun close() {
        connectedAt = null
    }

    private fun mode01(pidHex: String, elapsed: Double): String {
        val pids = pidHex.chunked(2).mapNotNull { it.toIntOrNull(16) }.take(6)
        val s = stateAt(elapsed)
        val bytes = arrayListOf(0x41)
        for (pid in pids) {
            val data = dataFor(pid, s, elapsed) ?: continue
            bytes += pid
            bytes += data
        }
        if (bytes.size == 1) return "NO DATA\r\r"
        val prefix = if (searched) "" else "SEARCHING...\r".also { searched = true }
        return prefix + frame(bytes)
    }

    /** Single line up to 7 bytes, otherwise CAN ISO-TP lines with a byte-count header and zero padding. */
    private fun frame(bytes: List<Int>): String {
        val sep = if (spaces) " " else ""
        fun hex(list: List<Int>) = list.joinToString(sep) { "%02X".format(it) }
        if (bytes.size <= 7) return hex(bytes) + "\r\r"
        val lines = arrayListOf("%03X".format(bytes.size), "0:$sep" + hex(bytes.take(6)))
        var index = 1
        var i = 6
        while (i < bytes.size) {
            val chunk = bytes.subList(i, minOf(i + 7, bytes.size)).toMutableList()
            while (chunk.size < 7) chunk += 0x00
            lines += "%X:".format(index % 16) + sep + hex(chunk)
            i += 7
            index++
        }
        return lines.joinToString("\r") + "\r\r"
    }

    private fun dtcBytes(mode: Int, codes: List<String>): List<Int> {
        val out = arrayListOf(mode, codes.size)
        for (code in codes) {
            val a = ("PCBU".indexOf(code[0]) shl 6) or (code[1].digitToInt() shl 4) or code[2].digitToInt(16)
            out += a
            out += code.substring(3, 5).toInt(16)
        }
        return out
    }

    private data class SimState(
        val running: Boolean,
        val rpm: Double,
        val speed: Double,
        val load: Double,
        val throttle: Double,
        val pedal: Double,
        val voltage: Double,
        val coolant: Double,
        val runTime: Double,
    )

    private fun stateAt(elapsed: Double): SimState {
        val t = elapsed % CYCLE_SECONDS
        val coolant = (AMBIENT_C + elapsed * 0.6).coerceAtMost(OPERATING_C)
        fun j(span: Double) = random.nextDouble(-span, span)
        val runTime = (t - 10).coerceAtLeast(0.0)

        return when {
            // Ignition on, engine off: ECU answers, alternator not charging.
            t < 10 -> SimState(false, 0.0, 0.0, 0.0, 0.0, 0.0, 12.4, coolant, 0.0)
            t < 20 -> SimState(true, 800 + j(30.0), 0.0, 18 + j(2.0), 3.0, 0.0, 14.2, coolant, runTime)
            // Accelerate 0 → 100 km/h through four gears (RPM saw-tooth).
            t < 40 -> {
                val p = (t - 20) / 20.0
                val gearPhase = (p * 4) % 1.0
                SimState(true, 1_500 + 2_200 * gearPhase + j(50.0), 100 * p, 70 + j(8.0), 45 + j(3.0), 55 + j(3.0), 14.3, coolant, runTime)
            }
            // Cruise ~100 km/h with one harsh brake at 60-62 s (16 km/h/s) and recovery.
            t < 80 -> {
                val speed = when {
                    t in 60.0..62.0 -> 100 - (t - 60) * 16
                    t in 62.0..66.0 -> 68 + (t - 62) * 8
                    else -> 100 + 3 * sin(t / 4)
                }
                val braking = t in 60.0..62.0
                SimState(true, 22 * speed + j(60.0), speed, if (braking) 6.0 else 28 + j(4.0), if (braking) 0.0 else 18 + j(2.0), if (braking) 0.0 else 22 + j(2.0), 14.1, coolant, runTime)
            }
            t < 95 -> {
                val p = (t - 80) / 15.0
                SimState(true, (2_000 * (1 - p)).coerceAtLeast(800.0) + j(40.0), 100 * (1 - p), 8 + j(2.0), 0.0, 0.0, 14.0, coolant, runTime)
            }
            t < 110 -> SimState(true, 780 + j(30.0), 0.0, 17 + j(2.0), 3.0, 0.0, 14.2, coolant, runTime)
            else -> SimState(false, 0.0, 0.0, 0.0, 0.0, 0.0, 12.5, coolant, 0.0)
        }
    }

    /** Raw data bytes for one PID, using the inverse of each SAE J1979 formula. */
    private fun dataFor(pid: Int, s: SimState, elapsed: Double): List<Int>? {
        if (pid % 0x20 == 0) return bitmap(pid)
        if (pid !in SUPPORTED_PIDS) return null
        val maf = if (s.running) s.rpm * s.load / 100 * 0.012 + 1.5 else 0.0
        val stft = if (s.running) random.nextDouble(-4.0, 4.0) else 0.0
        return when (pid) {
            0x01 -> listOf(0x81, 0x07, 0x65, 0x00) // MIL on, 1 confirmed DTC
            0x03 -> listOf(if (s.running) 2 else 0, 0)
            0x04 -> u8pct(s.load)
            0x05 -> u8(s.coolant + 40)
            0x06 -> u8trim(stft)
            0x07 -> u8trim(if (s.running) 6.3 else 0.0) // lean long-term trim, matches pending P0171
            0x0B -> u8(if (s.running) 30 + s.load * 0.7 else 101.0)
            0x0C -> u16(s.rpm * 4)
            0x0D -> u8(s.speed)
            0x0E -> u8((if (s.running) 10 + s.rpm / 1000 * 4 - s.load * 0.1 else 0.0) * 2 + 128)
            0x0F -> u8(30 - s.speed * 0.05 + 40)
            0x10 -> u16(maf * 100)
            0x11 -> u8pct(s.throttle)
            0x13 -> listOf(0x03)
            0x14 -> u8(if (s.running) (0.45 + 0.4 * sin(elapsed * 2 * PI * 0.8)) / 0.005 else 20.0) + u8trim(stft)
            0x15 -> u8((if (s.running) 0.65 + random.nextDouble(-0.02, 0.02) else 0.1) / 0.005) + listOf(0xFF)
            0x1C -> listOf(6)
            0x1F -> u16(s.runTime)
            0x21 -> u16(37.0)
            0x24 -> u16((1.0 + random.nextDouble(-0.02, 0.02)) * 32768) + u16(3.3 * 8192)
            0x2F -> u8pct(62 - elapsed / 3600 * 8 + random.nextDouble(-0.5, 0.5))
            0x30 -> listOf(12)
            0x31 -> u16(842.0)
            0x33 -> listOf(101)
            0x3C -> u16(((if (s.running) 350 + s.load * 5 + minOf(elapsed, 600.0) * 0.3 else 300.0) + 40) * 10)
            0x42 -> u16(s.voltage * 1000)
            0x43 -> u16(s.load * 1.1 * 255 / 100)
            0x44 -> u16((if (s.load > 65) 0.9 else 1.0) * 32768)
            0x45 -> u8pct(s.throttle * 0.9)
            0x46 -> u8(18.0 + 40)
            0x47 -> u8pct(s.throttle + 12)
            0x49 -> u8pct(s.pedal)
            0x4A -> u8pct(s.pedal / 2)
            0x4C -> u8pct(s.throttle)
            0x4D -> u16(95.0)
            0x4E -> u16(4_210.0)
            0x4F -> listOf(0x01, 0x02, 0x03, 0x04) // not in the app's catalog: stored raw
            0x51 -> listOf(1)
            0x5C -> u8((s.coolant + if (s.running) 4 else 0).coerceAtMost(105.0) + 40)
            0x5E -> u16(maf * 3600 / (14.7 * 745) * 20)
            0xA6 -> u32((48_231.4 + elapsed * 55 / 3600) * 10)
            else -> null
        }
    }

    private fun bitmap(base: Int): List<Int>? {
        if (base != 0 && SUPPORTED_PIDS.none { it > base }) return null
        var bits = 0L
        for (bit in 0 until 32) {
            val pid = base + bit + 1
            val chained = pid == base + 0x20 && SUPPORTED_PIDS.any { it > base + 0x20 }
            if (pid in SUPPORTED_PIDS || chained) bits = bits or (1L shl (31 - bit))
        }
        return listOf(((bits shr 24) and 0xFF).toInt(), ((bits shr 16) and 0xFF).toInt(), ((bits shr 8) and 0xFF).toInt(), (bits and 0xFF).toInt())
    }

    private fun u8(v: Double) = listOf(v.roundToInt().coerceIn(0, 255))
    private fun u8pct(v: Double) = u8(v * 255 / 100)
    private fun u8trim(v: Double) = u8(v * 128 / 100 + 128)
    private fun u16(v: Double): List<Int> {
        val x = v.roundToInt().coerceIn(0, 0xFFFF)
        return listOf(x shr 8, x and 0xFF)
    }
    private fun u32(v: Double): List<Int> {
        val x = v.roundToLong().coerceIn(0, 0xFFFFFFFFL)
        return listOf(((x shr 24) and 0xFF).toInt(), ((x shr 16) and 0xFF).toInt(), ((x shr 8) and 0xFF).toInt(), (x and 0xFF).toInt())
    }

    companion object {
        const val ADDRESS = "SIMULATED"
        const val DEVICE_NAME = "Simulated ELM327"
        const val VIN = "1HGCM82633A004352"

        val SUPPORTED_PIDS = setOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15,
            0x1C, 0x1F, 0x21, 0x24, 0x2F, 0x30, 0x31, 0x33, 0x3C, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x49,
            0x4A, 0x4C, 0x4D, 0x4E, 0x4F, 0x51, 0x5C, 0x5E, 0xA6,
        )
        private val STORED_DTCS = listOf("P0420")

        private const val CYCLE_SECONDS = 120.0
        private const val AMBIENT_C = 22.0
        private const val OPERATING_C = 90.0
        private const val CONNECT_DELAY_MILLIS = 600L
        private const val MIN_LATENCY_MILLIS = 15L
        private const val MAX_LATENCY_MILLIS = 45L
    }
}
