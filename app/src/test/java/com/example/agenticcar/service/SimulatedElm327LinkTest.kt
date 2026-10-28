package com.example.agenticcar.service

import com.example.agenticcar.protocol.obd.Elm327Parser
import com.example.agenticcar.protocol.obd.ObdCommand
import com.example.agenticcar.protocol.obd.PidCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SimulatedElm327LinkTest {

    private var now = 0L
    private val link = SimulatedElm327Link(clock = { now }, random = Random(42))

    private fun at(seconds: Int) {
        now = seconds * 1_000L
    }

    private fun decode(response: String, pids: List<Int>): Map<String, Double?> =
        Elm327Parser.mode01(response, pids).flatMap { (pid, bytes) ->
            val def = PidCatalog.pids.getValue(pid)
            def.signals.zip(def.decode(bytes)).map { (s, v) -> s.key to v }
        }.toMap()

    @Test
    fun discovery_findsEverySimulatedPid() = runBlocking {
        link.connect(SimulatedElm327Link.ADDRESS)
        link.initialize()
        val found = sortedSetOf<Int>()
        var base = 0
        while (true) {
            val pids = Elm327Parser.supportedPids(link.execute(ObdCommand.mode01(listOf(base))), base)
            found += pids
            if (base + 0x20 !in pids) break
            base += 0x20
        }
        assertEquals(SimulatedElm327Link.SUPPORTED_PIDS, found.filterNot(PidCatalog::isBitmapPid).toSet())
    }

    @Test
    fun cruise_sixPidBatch_multiFrameSpacesOff() = runBlocking {
        at(0)
        link.connect(SimulatedElm327Link.ADDRESS)
        link.initialize() // includes ATS0
        at(50)
        val pids = listOf(0x0C, 0x0D, 0x10, 0x11, 0x05, 0x14)
        val response = link.execute(ObdCommand.mode01(pids))
        assertTrue("multi-frame expected: $response", response.contains("0:"))

        val v = decode(response, pids)
        assertTrue(v.getValue("rpm")!! > 400)
        assertTrue(v.getValue("speed")!! in 95.0..105.0)
        assertTrue(v.getValue("maf")!! > 1.0)
        assertTrue(v.containsKey("o2_b1s1_voltage") && v.containsKey("o2_b1s1_stft"))
    }

    @Test
    fun dtcsAndVin() = runBlocking {
        at(0)
        link.connect(SimulatedElm327Link.ADDRESS)
        link.initialize()
        assertEquals(listOf("P0420"), Elm327Parser.dtcs(link.execute(ObdCommand.STORED_DTCS), 0x43))
        assertTrue(Elm327Parser.dtcs(link.execute(ObdCommand.PENDING_DTCS), 0x47).isEmpty())
        at(100)
        assertEquals(listOf("P0171"), Elm327Parser.dtcs(link.execute(ObdCommand.PENDING_DTCS), 0x47))
        assertEquals(SimulatedElm327Link.VIN, Elm327Parser.vin(link.execute(ObdCommand.VIN)))
    }
}
