package com.example.agenticcar.data.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelModelTest {

    // 1.2 L three-cylinder petrol (Peugeot 208 PureTech/VTi class).
    private val displacement = 1.2

    @Test
    fun warmIdle_isUnderOneLitrePerHour() {
        val maf = FuelModel.speedDensityMafGs(mapKpa = 30.0, intakeAirTempC = 20.0, rpm = 800.0, displacementL = displacement)
        val litresPerHour = FuelModel.fromMafLh(maf)
        assertTrue("was $litresPerHour", litresPerHour in 0.6..1.0)
    }

    @Test
    fun cruise50kmh_isAboutSixLitresPer100km() {
        val maf = FuelModel.speedDensityMafGs(mapKpa = 50.0, intakeAirTempC = 20.0, rpm = 2_000.0, displacementL = displacement)
        val per100km = FuelModel.fromMafLh(maf) / 50.0 * 100
        assertEquals(6.6, per100km, 1.0)
    }

    @Test
    fun mafConversion_matchesStoichiometry() =
        // 10 g/s of air at AFR 14.7 is 0.680 g/s of petrol, 3.29 L/h at 745 g/L.
        assertEquals(3.29, FuelModel.fromMafLh(10.0), 0.01)
}
