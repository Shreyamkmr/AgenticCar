package com.example.agenticcar.protocol.obd

/**
 * Pure parsing of raw ELM327 text, unit-testable without hardware.
 *
 * Handles headers-off replies with or without spaces (`AT S0`), the
 * `SEARCHING...` banner, several ECUs answering on separate lines, and ISO-TP
 * multi-frame replies:
 *
 * ```
 * 00E            <- total byte count (hex)
 * 0: 41 0C 1A F8 0D 32
 * 1: 05 7B 04 80 11 33 00   <- trailing pad bytes are cut using the count
 * ```
 */
object Elm327Parser {

    private val FRAME = Regex("^([0-9A-F]):([0-9A-F]*)$")
    private val BYTE_COUNT = Regex("^[0-9A-F]{3}$")
    private val VOLTAGE = Regex("""(\d{1,2}(?:\.\d{1,2})?)\s*V?""", RegexOption.IGNORE_CASE)

    /**
     * Splits a response into one byte array per ECU message, joining multi-frame lines.
     * A multi-frame message with a missing or out-of-order frame, or fewer bytes than its count
     * announced, is dropped: decoding it would read one PID's bytes as another's value.
     */
    fun messages(response: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        var frames: ArrayList<Int>? = null
        var expected = -1
        var lastIndex = -1
        var broken = false

        fun finishMultiFrame() {
            frames?.let { bytes ->
                when {
                    broken || (expected > 0 && bytes.size < expected) -> Unit
                    expected > 0 -> out += bytes.subList(0, expected).toIntArray()
                    else -> out += bytes.toIntArray()
                }
            }
            frames = null
            expected = -1
            lastIndex = -1
            broken = false
        }

        for (line in clean(response).lines()) {
            val compact = line.filterNot { it.isWhitespace() }
            if (compact.isEmpty()) continue
            val frame = FRAME.matchEntire(compact)
            when {
                BYTE_COUNT.matches(compact) -> {
                    finishMultiFrame()
                    expected = compact.toInt(16)
                    frames = ArrayList()
                }
                frame != null -> {
                    val index = frame.groupValues[1].toInt(16)
                    if (index == 0 && frames?.isNotEmpty() == true) finishMultiFrame()
                    val target = frames ?: ArrayList<Int>().also { frames = it }
                    // Frame indices count 0, 1, ... F, 0 (ISO-TP sequence numbers wrap at 16).
                    if (index != (lastIndex + 1) % 16) broken = true
                    lastIndex = index
                    val bytes = hexBytes(frame.groupValues[2])
                    if (bytes == null) broken = true else bytes.forEach { target += it }
                }
                else -> {
                    val bytes = hexBytes(compact) ?: continue
                    finishMultiFrame()
                    out += bytes
                }
            }
        }
        finishMultiFrame()
        return out
    }

    /**
     * Decodes a (possibly multi-PID) Mode 01 reply into `pid -> data bytes`.
     * Data lengths come from [PidCatalog]; an uncatalogued PID can only be parsed
     * when it was requested alone, taking the rest of the message.
     */
    fun mode01(response: String, requested: List<Int>): Map<Int, IntArray> {
        val out = LinkedHashMap<Int, IntArray>()
        for (msg in messages(response)) {
            if (msg.isEmpty() || msg[0] != 0x41) continue
            var i = 1
            while (i < msg.size) {
                val pid = msg[i]
                val length = when {
                    PidCatalog.isBitmapPid(pid) -> 4
                    PidCatalog.pids[pid] != null -> PidCatalog.pids.getValue(pid).dataBytes
                    requested.size == 1 && requested[0] == pid -> msg.size - i - 1
                    else -> -1
                }
                if (length <= 0 || i + 1 + length > msg.size) break
                out.putIfAbsent(pid, msg.copyOfRange(i + 1, i + 1 + length))
                i += 1 + length
            }
        }
        return out
    }

    /** Union of PIDs advertised by every ECU answering the `01 <base>` bitmap request. */
    fun supportedPids(response: String, base: Int): Set<Int> {
        val out = sortedSetOf<Int>()
        for (msg in messages(response)) {
            if (msg.size < 6 || msg[0] != 0x41 || msg[1] != base) continue
            for (bit in 0 until 32) {
                if ((msg[2 + bit / 8] shr (7 - bit % 8)) and 1 == 1) out += base + bit + 1
            }
        }
        return out
    }

    /**
     * Trouble codes from a Mode 03/07/0A reply. CAN replies carry a count byte
     * before the code pairs (odd payload length); legacy protocols pad to 3 codes.
     */
    fun dtcs(response: String, responseMode: Int): List<String> {
        val codes = LinkedHashSet<String>()
        for (msg in messages(response)) {
            if (msg.isEmpty() || msg[0] != responseMode) continue
            var data = msg.copyOfRange(1, msg.size)
            if (data.size % 2 == 1) data = data.copyOfRange(1, data.size)
            for (i in 0 until data.size - 1 step 2) {
                val a = data[i]
                val b = data[i + 1]
                if (a == 0 && b == 0) continue
                codes += decodeDtc(a, b)
            }
        }
        return codes.toList()
    }

    /** `0x01 0x33` -> `P0133`. */
    fun decodeDtc(a: Int, b: Int): String =
        "${"PCBU"[a shr 6]}${(a shr 4) and 0x3}${"%X".format(a and 0xF)}${"%02X".format(b)}"

    /** VIN from a Mode 09 PID 02 reply; headers and line counters fall outside the VIN alphabet. */
    fun vin(response: String): String? {
        val chars = StringBuilder()
        for (msg in messages(response)) {
            if (msg.size < 3 || msg[0] != 0x49 || msg[1] != 0x02) continue
            for (i in 2 until msg.size) {
                val c = msg[i].toChar()
                if (c in '0'..'9' || (c in 'A'..'Z' && c !in "IOQ")) chars.append(c)
            }
        }
        return if (chars.length >= 17) chars.substring(chars.length - 17) else null
    }

    /** `AT RV` reply, e.g. `12.6V` or `14.1`. */
    fun parseVoltage(response: String): Float? =
        VOLTAGE.find(clean(response))?.groupValues?.get(1)?.toFloatOrNull()

    /** Normalises CR line endings, strips the `>` prompt and `SEARCHING...` noise. */
    fun clean(response: String): String =
        response
            .replace('\r', '\n')
            .replace(">", "")
            .replace("SEARCHING...", "", ignoreCase = true)
            .uppercase()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun hexBytes(hex: String): IntArray? {
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return IntArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }
    }
}
