package com.example.agenticcar.service

import com.example.agenticcar.protocol.obd.ObdCommand
import kotlinx.coroutines.delay

/**
 * A command/response channel to an ELM327-compatible adapter. Implemented by the
 * real Bluetooth SPP link and by [SimulatedElm327Link] for testing without a car.
 */
interface Elm327Link {
    val isConnected: Boolean

    /** Opens the link and returns a human-readable adapter name. */
    suspend fun connect(address: String): String

    /** Writes `<command>\r` and returns the raw text up to the `>` prompt. */
    suspend fun execute(command: String, timeoutMillis: Long = ObdCommand.OBD_TIMEOUT_MS): String

    /** Releases the link. Safe to call repeatedly from any thread. */
    fun close()

    /** Sends [ObdCommand.INIT_SEQUENCE]: ATZ, ATE0, ATL0, ATS0, ATH0, AT SP 0. */
    suspend fun initialize() {
        for (command in ObdCommand.INIT_SEQUENCE) {
            val reset = command == ObdCommand.RESET
            execute(command, if (reset) ObdCommand.RESET_TIMEOUT_MS else ObdCommand.AT_TIMEOUT_MS)
            // ATZ reboots the chip; give it time before the next command.
            if (reset) delay(500)
        }
    }
}
