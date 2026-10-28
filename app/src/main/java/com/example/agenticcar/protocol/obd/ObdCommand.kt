package com.example.agenticcar.protocol.obd

/** ELM327 command strings, sent verbatim followed by a carriage return. */
object ObdCommand {
    // Chip initialisation
    const val RESET = "ATZ"
    const val ECHO_OFF = "ATE0"
    const val LINEFEEDS_OFF = "ATL0"
    // Without spaces replies are ~30 % shorter, which matters on a 10-20 kbit/s SPP link.
    const val SPACES_OFF = "ATS0"
    const val HEADERS_OFF = "ATH0"
    const val AUTO_PROTOCOL = "AT SP 0"

    const val IDENTIFY = "ATI"
    const val DESCRIBE_PROTOCOL_NUMBER = "ATDPN"
    const val READ_VOLTAGE = "ATRV"

    const val STORED_DTCS = "03"
    const val PENDING_DTCS = "07"
    const val PERMANENT_DTCS = "0A"
    const val VIN = "0902"

    const val AT_TIMEOUT_MS = 1_000L
    const val RESET_TIMEOUT_MS = 3_000L
    const val OBD_TIMEOUT_MS = 2_500L
    // The first OBD request after AT SP 0 triggers a protocol search that can take seconds.
    const val DISCOVERY_TIMEOUT_MS = 10_000L

    val INIT_SEQUENCE = listOf(RESET, ECHO_OFF, LINEFEEDS_OFF, SPACES_OFF, HEADERS_OFF, AUTO_PROTOCOL)

    /** `01` followed by up to 6 PIDs, e.g. `010C0D11`. */
    fun mode01(pids: List<Int>): String = "01" + pids.joinToString("") { "%02X".format(it) }
}
