package com.example.agenticcar.service

import com.example.agenticcar.data.recording.DtcKind
import com.example.agenticcar.data.recording.ObdSessionInfo
import com.example.agenticcar.data.recording.Reading
import com.example.agenticcar.data.recording.TelemetryRecorder
import com.example.agenticcar.protocol.obd.Elm327Parser
import com.example.agenticcar.protocol.obd.ObdCommand
import com.example.agenticcar.protocol.obd.PidCatalog
import com.example.agenticcar.protocol.obd.PollTier
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * One connected session with an ELM327: initialise, discover everything the ECU
 * supports, then poll it forever on a tiered schedule.
 *
 * Scheduling: every supported PID has a period from its [PollTier]. Each loop
 * sends the most urgent due request; on CAN, up to [MAX_BATCH] due PIDs share one
 * request, which is the biggest throughput win on a slow Bluetooth adapter.
 * PIDs that never answer are dropped after [MAX_MISSES] attempts.
 */
class ObdSession(
    private val link: Elm327Link,
    private val recorder: TelemetryRecorder,
    private val adapterName: String,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private enum class Kind { MODE01, VOLTAGE, DTCS }

    private class Task(val kind: Kind, val pid: Int, val periodMillis: Long, val priority: Int, val decodable: Boolean) {
        var nextDue = 0L
        var misses = 0
        var answered = false
    }

    /** Runs until the link fails (throws) or the coroutine is cancelled. */
    suspend fun run(onReady: () -> Unit): Nothing {
        link.initialize()
        val version = query(ObdCommand.IDENTIFY, ObdCommand.AT_TIMEOUT_MS)?.let(::firstLine)
        val supported = discoverSupportedPids()
        if (supported.isEmpty()) throw IOException("No ECU answered. Is the ignition on?")
        val protocol = query(ObdCommand.DESCRIBE_PROTOCOL_NUMBER, ObdCommand.AT_TIMEOUT_MS)?.let(::firstLine)
        val batchSize = detectBatchSize(supported)
        val vin = query(ObdCommand.VIN, ObdCommand.DISCOVERY_TIMEOUT_MS)?.let(Elm327Parser::vin)

        recorder.onSessionStarted(
            ObdSessionInfo(
                adapterName = adapterName,
                adapterVersion = version,
                protocol = protocol,
                vin = vin,
                signalIds = supported.flatMap { pid -> PidCatalog.signalsOf(pid).map { it.id } } + PidCatalog.ADAPTER_VOLTAGE_ID,
                batchSize = batchSize,
            ),
        )
        onReady()
        poll(buildTasks(supported), batchSize)
    }

    private suspend fun discoverSupportedPids(): Set<Int> {
        val supported = sortedSetOf<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val response = query(ObdCommand.mode01(listOf(base)), ObdCommand.DISCOVERY_TIMEOUT_MS) ?: break
            val pids = Elm327Parser.supportedPids(response, base)
            if (pids.isEmpty()) break
            supported += pids
            // The last bit of each bitmap says whether the next range exists.
            if (base + 0x20 !in pids) break
            base += 0x20
        }
        return supported.filterNot(PidCatalog::isBitmapPid).toSortedSet()
    }

    /** Multi-PID requests are a CAN feature; probe with real PIDs instead of trusting the protocol number. */
    private suspend fun detectBatchSize(supported: Set<Int>): Int {
        val known = supported.filter { it in PidCatalog.pids }
        val candidates = known.filter { PidCatalog.pids.getValue(it).tier == PollTier.FAST }.take(MAX_BATCH)
            .ifEmpty { known.take(MAX_BATCH) }
        if (candidates.size < 2) return 1
        val response = query(ObdCommand.mode01(candidates), ObdCommand.DISCOVERY_TIMEOUT_MS) ?: return 1
        val answered = Elm327Parser.mode01(response, candidates).keys.count { it in candidates }
        return when {
            answered == candidates.size -> MAX_BATCH
            answered >= 2 -> answered
            else -> 1
        }
    }

    private fun buildTasks(supported: Set<Int>): MutableList<Task> {
        val tasks = supported.mapTo(ArrayList()) { pid ->
            val def = PidCatalog.pids[pid]
            val tier = def?.tier ?: PollTier.SLOW
            Task(Kind.MODE01, pid, tier.periodMillis, tier.ordinal, decodable = def != null)
        }
        tasks += Task(Kind.VOLTAGE, -1, PollTier.MEDIUM.periodMillis, PollTier.MEDIUM.ordinal, decodable = false)
        tasks += Task(Kind.DTCS, -1, DTC_PERIOD_MS, PollTier.STATIC.ordinal, decodable = false)
        return tasks
    }

    private suspend fun poll(tasks: MutableList<Task>, batchSize: Int): Nothing {
        var silentRequests = 0
        val byUrgency = compareBy<Task>({ it.priority }, { it.nextDue })
        while (true) {
            val now = monotonicMillis()
            val due = tasks.filter { it.nextDue <= now }.sortedWith(byUrgency)
            if (due.isEmpty()) {
                delay((tasks.minOf { it.nextDue } - now).coerceIn(5L, 250L))
                continue
            }
            val head = due.first()
            when (head.kind) {
                Kind.MODE01 -> {
                    val batch = if (head.decodable && batchSize > 1) {
                        due.filter { it.kind == Kind.MODE01 && it.decodable }.take(batchSize)
                    } else {
                        listOf(head)
                    }
                    if (pollMode01(batch, tasks)) {
                        silentRequests = 0
                    } else if (++silentRequests >= MAX_SILENT_REQUESTS) {
                        throw IOException("ECU stopped answering OBD requests")
                    }
                }
                Kind.VOLTAGE -> pollVoltage()
                Kind.DTCS -> pollDtcs()
            }
            if (head.kind != Kind.MODE01) head.nextDue = monotonicMillis() + head.periodMillis
        }
    }

    /** Returns whether the ECU answered at least one of the PIDs. */
    private suspend fun pollMode01(batch: List<Task>, tasks: MutableList<Task>): Boolean {
        val pids = batch.map { it.pid }
        val response = query(ObdCommand.mode01(pids), ObdCommand.OBD_TIMEOUT_MS)
        val now = monotonicMillis()
        val wall = wallMillis()
        batch.forEach { it.nextDue = now + it.periodMillis }
        val parsed = response?.let { Elm327Parser.mode01(it, pids) }.orEmpty()

        val readings = ArrayList<Reading>()
        for (task in batch) {
            val bytes = parsed[task.pid]
            if (bytes == null) {
                task.misses++
                // Advertised but never answering: stop wasting bandwidth on it.
                if (!task.answered && task.misses >= MAX_MISSES) tasks.remove(task)
                continue
            }
            task.misses = 0
            task.answered = true
            val def = PidCatalog.pids[task.pid]
            if (def != null) {
                def.decode(bytes).forEachIndexed { i, value ->
                    if (value != null && value.isFinite()) readings += Reading(def.signals[i].id, value)
                }
            } else {
                val raw = bytes.take(6).fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
                readings += Reading(PidCatalog.RAW_ID_BASE + task.pid, raw.toDouble())
            }
        }
        if (readings.isNotEmpty()) recorder.onReadings(wall, readings)
        return parsed.isNotEmpty()
    }

    private suspend fun pollVoltage() {
        val volts = query(ObdCommand.READ_VOLTAGE, ObdCommand.AT_TIMEOUT_MS)?.let(Elm327Parser::parseVoltage) ?: return
        recorder.onReadings(wallMillis(), listOf(Reading(PidCatalog.ADAPTER_VOLTAGE_ID, volts.toDouble())))
    }

    private suspend fun pollDtcs() {
        for (kind in DtcKind.entries) {
            val response = query(kind.command, ObdCommand.OBD_TIMEOUT_MS) ?: continue
            // "?" means the adapter rejected the mode (e.g. 0A on old ECUs); NO DATA means no codes.
            if (response.contains('?')) continue
            recorder.onDtcs(wallMillis(), kind, Elm327Parser.dtcs(response, kind.responseMode))
        }
    }

    /** A timeout yields null; a dead link propagates so the service reconnects. */
    private suspend fun query(command: String, timeoutMillis: Long): String? =
        try {
            link.execute(command, timeoutMillis)
        } catch (e: IOException) {
            if (!link.isConnected) throw e
            null
        }

    private fun firstLine(response: String): String? = Elm327Parser.clean(response).lineSequence().firstOrNull()

    private companion object {
        const val MAX_BATCH = 6
        const val MAX_MISSES = 5
        const val MAX_SILENT_REQUESTS = 12
        const val DTC_PERIOD_MS = 60_000L
    }
}
