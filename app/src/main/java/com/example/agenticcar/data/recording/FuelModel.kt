package com.example.agenticcar.data.recording

/** Fuel-flow models for petrol engines at stoichiometric mixture (lambda 1). */
object FuelModel {
    const val STOICH_AFR_PETROL = 14.7
    const val PETROL_DENSITY_G_PER_L = 745.0
    /** Typical volumetric efficiency of a road engine across normal driving. */
    const val DEFAULT_VOLUMETRIC_EFFICIENCY = 0.85
    private const val R_AIR = 287.05 // J/(kg·K)

    /** Fuel flow in L/h from a measured air mass flow in g/s. */
    fun fromMafLh(mafGs: Double): Double = mafGs / (STOICH_AFR_PETROL * PETROL_DENSITY_G_PER_L) * 3600

    /**
     * Speed-density air mass flow in g/s for engines without a MAF sensor: the cylinders breathe
     * displacement × VE of air at manifold pressure and intake temperature every two revolutions.
     */
    fun speedDensityMafGs(
        mapKpa: Double,
        intakeAirTempC: Double,
        rpm: Double,
        displacementL: Double,
        volumetricEfficiency: Double = DEFAULT_VOLUMETRIC_EFFICIENCY,
    ): Double {
        val airDensityKgM3 = mapKpa * 1000 / (R_AIR * (intakeAirTempC + 273.15))
        val intakeM3PerSecond = displacementL / 1000 * volumetricEfficiency * rpm / 120
        return airDensityKgM3 * intakeM3PerSecond * 1000
    }
}
