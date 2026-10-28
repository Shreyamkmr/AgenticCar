package com.example.agenticcar.data.recording

/**
 * Compact encoding of one signal's samples inside a one-minute block.
 *
 * Each stored sample is two zig-zag varints: the time delta in 10 ms ticks and
 * the delta of the quantised value (`round(value / resolution)`). A 250 ms
 * sample whose value moved by a few steps costs 2 bytes, against ~20 bytes for
 * a SQLite row with a timestamp and a REAL.
 */
object BlockCodec {
    const val TICK_MS = 10

    /** A decoded sample; [tMs] is milliseconds from trip start. */
    data class Point(val tMs: Int, val value: Double)

    class Encoder(private val blockStartMs: Int) {
        private var buffer = ByteArray(32)
        private var size = 0
        private var lastTick = 0
        private var lastQ = 0L

        var count = 0
            private set

        val sizeBytes: Int get() = size

        fun add(tRelMs: Int, quantised: Long) {
            val tick = (tRelMs - blockStartMs) / TICK_MS
            writeVarLong(zigzag((tick - lastTick).toLong()))
            writeVarLong(zigzag(quantised - lastQ))
            lastTick = tick
            lastQ = quantised
            count++
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun writeVarLong(value: Long) {
            var v = value
            while (true) {
                if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                if (v and 0x7FL.inv() == 0L) {
                    buffer[size++] = v.toByte()
                    return
                }
                buffer[size++] = ((v and 0x7F) or 0x80).toByte()
                v = v ushr 7
            }
        }
    }

    fun decode(data: ByteArray, blockStartMs: Int, resolution: Double): List<Point> {
        val out = ArrayList<Point>()
        var pos = 0
        var tick = 0L
        var q = 0L
        fun readVarLong(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = data[pos++].toLong()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) return result
                shift += 7
            }
        }
        while (pos < data.size) {
            tick += unzigzag(readVarLong())
            q += unzigzag(readVarLong())
            out += Point(blockStartMs + (tick * TICK_MS).toInt(), q * resolution)
        }
        return out
    }

    private fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)

    private fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1)
}
