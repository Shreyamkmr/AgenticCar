package com.example.agenticcar.ui.board

import com.example.agenticcar.protocol.obd.PidCatalog

/**
 * Ready-made boards. Each has a hand-made landscape arrangement that fits one phone screen
 * (8 columns × 3 rows, no scrolling while driving) and a portrait one that scrolls.
 *
 * Template ids are fixed so they are seeded only once; tile ids are unique across both orientations.
 */
object BoardTemplates {

    private const val ID_SHORT_TRIM = 0x06
    private const val ID_LONG_TRIM = 0x07

    private class Builder(private val prefix: String) {
        val tiles = ArrayList<Tile>()
        fun tile(signal: Int, type: TileType, col: Int, row: Int, w: Int, h: Int, min: Double? = null, max: Double? = null) {
            tiles += Tile("$prefix${tiles.size + 1}", signal, type, col, row, w, h, min, max)
        }
    }

    private fun template(id: String, name: String, landscape: Builder.() -> Unit, portrait: Builder.() -> Unit) =
        BoardLayout(
            id = id,
            name = name,
            portrait = Builder("p").apply(portrait).tiles,
            landscape = Builder("l").apply(landscape).tiles,
        )

    /** Two big dials with the driver's inputs between them: the everyday driving view. */
    val cockpit = template(
        "tpl-cockpit", "Cockpit",
        landscape = {
            tile(PidCatalog.ID_RPM, TileType.GAUGE, 0, 0, 3, 3)
            tile(PidCatalog.ID_COOLANT, TileType.VALUE, 3, 0, 2, 1)
            tile(PidCatalog.ID_THROTTLE, TileType.BAR, 3, 1, 2, 1)
            tile(PidCatalog.ID_ENGINE_LOAD, TileType.BAR, 3, 2, 2, 1)
            tile(PidCatalog.ID_SPEED, TileType.GAUGE, 5, 0, 3, 3)
        },
        portrait = {
            tile(PidCatalog.ID_RPM, TileType.GAUGE, 0, 0, 2, 2)
            tile(PidCatalog.ID_SPEED, TileType.GAUGE, 2, 0, 2, 2)
            tile(PidCatalog.ID_THROTTLE, TileType.BAR, 0, 2, 2, 1)
            tile(PidCatalog.ID_ENGINE_LOAD, TileType.BAR, 2, 2, 2, 1)
            tile(PidCatalog.ID_COOLANT, TileType.VALUE, 0, 3, 2, 1)
            tile(PidCatalog.ADAPTER_VOLTAGE_ID, TileType.VALUE, 2, 3, 2, 1)
            tile(PidCatalog.ID_RPM, TileType.TREND, 0, 4, 4, 2)
            tile(PidCatalog.ID_MIL, TileType.LAMP, 0, 6, 1, 1)
            tile(PidCatalog.ID_DTC_COUNT, TileType.LAMP, 1, 6, 1, 1)
            tile(PidCatalog.ID_COOLANT, TileType.LAMP, 2, 6, 1, 1)
            tile(PidCatalog.ADAPTER_VOLTAGE_ID, TileType.LAMP, 3, 6, 1, 1)
        },
    )

    /** The map first: where you are, how fast, and the hills on the way. */
    val routeTerrain = template(
        "tpl-route", "Route & terrain",
        landscape = {
            tile(PidCatalog.ID_SPEED, TileType.ROUTE, 0, 0, 5, 3)
            tile(PidCatalog.ID_SPEED, TileType.VALUE, 5, 0, 3, 1)
            tile(PidCatalog.ID_GPS_ALTITUDE, TileType.TREND, 5, 1, 3, 1)
            tile(PidCatalog.ID_ROAD_GRADE, TileType.BAR, 5, 2, 3, 1)
        },
        portrait = {
            tile(PidCatalog.ID_SPEED, TileType.ROUTE, 0, 0, 4, 3)
            tile(PidCatalog.ID_SPEED, TileType.VALUE, 0, 3, 2, 1)
            tile(PidCatalog.ID_ROAD_GRADE, TileType.VALUE, 2, 3, 2, 1)
            tile(PidCatalog.ID_GPS_ALTITUDE, TileType.TREND, 0, 4, 4, 2)
            tile(PidCatalog.ID_ROAD_GRADE, TileType.TREND, 0, 6, 4, 2)
        },
    )

    /** Temperatures, fuel trims, ignition and warning lamps: is the engine healthy? */
    val engineHealth = template(
        "tpl-engine", "Engine health",
        landscape = {
            tile(PidCatalog.ID_COOLANT, TileType.GAUGE, 0, 0, 2, 2)
            tile(ID_SHORT_TRIM, TileType.TREND, 2, 0, 3, 1)
            tile(ID_LONG_TRIM, TileType.TREND, 5, 0, 3, 1)
            tile(PidCatalog.ID_INTAKE_MAP, TileType.TREND, 2, 1, 3, 1)
            tile(PidCatalog.ID_TIMING_ADVANCE, TileType.TREND, 5, 1, 3, 1)
            tile(PidCatalog.ADAPTER_VOLTAGE_ID, TileType.VALUE, 0, 2, 2, 1)
            tile(PidCatalog.ID_INTAKE_AIR_TEMP, TileType.VALUE, 2, 2, 2, 1)
            tile(PidCatalog.ID_MIL, TileType.LAMP, 4, 2, 1, 1)
            tile(PidCatalog.ID_DTC_COUNT, TileType.LAMP, 5, 2, 1, 1)
            tile(PidCatalog.ID_COOLANT, TileType.LAMP, 6, 2, 1, 1)
            tile(PidCatalog.ADAPTER_VOLTAGE_ID, TileType.LAMP, 7, 2, 1, 1)
        },
        portrait = {
            tile(PidCatalog.ID_COOLANT, TileType.GAUGE, 0, 0, 2, 2)
            tile(PidCatalog.ADAPTER_VOLTAGE_ID, TileType.GAUGE, 2, 0, 2, 2)
            tile(ID_SHORT_TRIM, TileType.TREND, 0, 2, 4, 2)
            tile(ID_LONG_TRIM, TileType.TREND, 0, 4, 4, 2)
            tile(PidCatalog.ID_TIMING_ADVANCE, TileType.VALUE, 0, 6, 2, 1)
            tile(PidCatalog.ID_INTAKE_AIR_TEMP, TileType.VALUE, 2, 6, 2, 1)
            tile(PidCatalog.ID_INTAKE_MAP, TileType.TREND, 0, 7, 4, 2)
            tile(PidCatalog.ID_MIL, TileType.LAMP, 0, 9, 1, 1)
            tile(PidCatalog.ID_DTC_COUNT, TileType.LAMP, 1, 9, 1, 1)
            tile(PidCatalog.ID_COOLANT, TileType.LAMP, 2, 9, 1, 1)
            tile(PidCatalog.ADAPTER_VOLTAGE_ID, TileType.LAMP, 3, 9, 1, 1)
        },
    )

    /** Fuel use: the route coloured by consumption shows where the fuel went. */
    val economy = template(
        "tpl-economy", "Economy",
        landscape = {
            tile(PidCatalog.ID_FUEL_RATE_EST, TileType.ROUTE, 0, 0, 4, 3)
            tile(PidCatalog.ID_FUEL_RATE_EST, TileType.GAUGE, 4, 0, 2, 2)
            tile(PidCatalog.ID_RPM, TileType.VALUE, 6, 0, 2, 1)
            tile(PidCatalog.ID_SPEED, TileType.VALUE, 6, 1, 2, 1)
            tile(PidCatalog.ID_FUEL_RATE_EST, TileType.TREND, 4, 2, 4, 1)
        },
        portrait = {
            tile(PidCatalog.ID_FUEL_RATE_EST, TileType.GAUGE, 0, 0, 2, 2)
            tile(PidCatalog.ID_ENGINE_LOAD, TileType.GAUGE, 2, 0, 2, 2)
            tile(PidCatalog.ID_FUEL_RATE_EST, TileType.TREND, 0, 2, 4, 2)
            tile(PidCatalog.ID_FUEL_RATE_EST, TileType.ROUTE, 0, 4, 4, 3)
            tile(PidCatalog.ID_THROTTLE, TileType.BAR, 0, 7, 2, 1)
            tile(PidCatalog.ID_RPM, TileType.VALUE, 2, 7, 2, 1)
        },
    )

    /** Spirited driving: a big rev counter and how hard the pedal and engine are working. */
    val performance = template(
        "tpl-performance", "Performance",
        landscape = {
            tile(PidCatalog.ID_RPM, TileType.GAUGE, 0, 0, 3, 3)
            tile(PidCatalog.ID_THROTTLE, TileType.TREND, 3, 0, 5, 1)
            tile(PidCatalog.ID_SPEED, TileType.TREND, 3, 1, 5, 1)
            tile(PidCatalog.ID_ENGINE_LOAD, TileType.BAR, 3, 2, 3, 1)
            tile(PidCatalog.ID_TIMING_ADVANCE, TileType.VALUE, 6, 2, 2, 1)
        },
        portrait = {
            tile(PidCatalog.ID_RPM, TileType.GAUGE, 0, 0, 4, 3)
            tile(PidCatalog.ID_THROTTLE, TileType.TREND, 0, 3, 4, 2)
            tile(PidCatalog.ID_SPEED, TileType.TREND, 0, 5, 4, 2)
            tile(PidCatalog.ID_ENGINE_LOAD, TileType.BAR, 0, 7, 2, 1)
            tile(PidCatalog.ID_TIMING_ADVANCE, TileType.VALUE, 2, 7, 2, 1)
        },
    )

    val all: List<BoardLayout> = listOf(cockpit, routeTerrain, engineHealth, economy, performance)

    val descriptions: Map<String, String> = mapOf(
        cockpit.id to "Big RPM and speed dials with throttle, load and coolant between them",
        routeTerrain.id to "Live map coloured by speed, with altitude and road grade",
        engineHealth.id to "Coolant, fuel trims, manifold pressure, timing and warning lamps",
        economy.id to "Fuel use on the map, as a dial and over time",
        performance.id to "Large rev counter with throttle and speed traces",
    )
}
