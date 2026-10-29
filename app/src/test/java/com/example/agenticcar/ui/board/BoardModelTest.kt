package com.example.agenticcar.ui.board

import com.example.agenticcar.data.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardModelTest {

    private fun tile(id: String, col: Int, row: Int, w: Int, h: Int, type: TileType = TileType.VALUE) =
        Tile(id, 0x0C, type, col, row, w, h)

    private val board = listOf(tile("a", 0, 0, 2, 2), tile("b", 2, 0, 2, 1))

    @Test
    fun canPlace_rejectsOverlapAndOffGrid() {
        assertFalse(BoardGrid.canPlace(board, tile("c", 1, 1, 2, 1), 4))
        assertFalse(BoardGrid.canPlace(board, tile("c", 3, 3, 2, 1), 4))
        assertFalse(BoardGrid.canPlace(board, tile("c", -1, 3, 1, 1), 4))
        assertTrue(BoardGrid.canPlace(board, tile("c", 2, 1, 2, 1), 4))
    }

    @Test
    fun canPlace_ignoresTheTileBeingMoved() =
        assertTrue(BoardGrid.canPlace(board, tile("a", 0, 1, 2, 2), 4))

    @Test
    fun firstFreeSpot_fillsGapsTopLeftFirst() {
        assertEquals(2 to 1, BoardGrid.firstFreeSpot(board, 2, 1, 4))
        assertEquals(0 to 2, BoardGrid.firstFreeSpot(board, 4, 2, 4))
    }

    @Test
    fun reflow_toLandscape_packsWithoutOverlap() {
        val portrait = BoardLayoutStore.defaultLayout().portrait
        val landscape = BoardGrid.reflow(portrait, BoardGrid.LANDSCAPE_COLUMNS)
        assertEquals(portrait.size, landscape.size)
        for (t in landscape) assertTrue("$t", BoardGrid.canPlace(landscape, t, BoardGrid.LANDSCAPE_COLUMNS))
        assertTrue(BoardGrid.rowsUsed(landscape) < BoardGrid.rowsUsed(portrait))
    }

    @Test
    fun reflow_narrowsTilesWiderThanTheGrid() {
        val wide = listOf(tile("w", 0, 0, 8, 2, TileType.TREND))
        assertEquals(4, BoardGrid.reflow(wide, 4).single().width)
    }

    @Test
    fun templates_areValidInBothOrientations() {
        for (template in BoardTemplates.all) {
            for ((landscape, tiles) in listOf(false to template.portrait, true to template.landscape)) {
                val columns = BoardGrid.columns(landscape)
                assertTrue("${template.name} landscape=$landscape is empty", tiles.isNotEmpty())
                for (t in tiles) assertTrue("${template.name} landscape=$landscape $t", BoardGrid.canPlace(tiles, t, columns))
            }
            // Landscape boards are meant to be read at a glance while driving: one screen, no scrolling.
            assertTrue("${template.name} landscape rows", BoardGrid.rowsUsed(template.landscape) <= 3)
            val ids = (template.portrait + template.landscape).map { it.id }
            assertEquals("${template.name} tile ids", ids.size, ids.toSet().size)
            assertTrue("${template.name} description", BoardTemplates.descriptions[template.id]?.isNotBlank() == true)
        }
        assertEquals(BoardTemplates.all.size, BoardTemplates.all.map { it.id }.toSet().size)
    }

    @Test
    fun templates_surviveJsonRoundTrip() {
        val (layouts, _) = BoardJson.decode(BoardJson.encode(BoardTemplates.all, null))
        assertEquals(BoardTemplates.all, layouts)
    }

    @Test
    fun json_roundTripsLayouts() {
        val layout = BoardLayout(
            "id1", "Track day",
            portrait = listOf(Tile("t1", 0x0C, TileType.GAUGE, 0, 0, 2, 2, min = 800.0, max = 6500.0)),
            landscape = listOf(Tile("t1", 0x0C, TileType.GAUGE, 4, 0, 3, 3)),
        )
        val (layouts, active) = BoardJson.decode(BoardJson.encode(listOf(layout), "id1"))
        assertEquals(listOf(layout), layouts)
        assertEquals("id1", active)
    }

    @Test
    fun json_skipsUnknownTileTypes() {
        val text = """{"layouts":[{"id":"x","name":"A","portrait":[
            {"id":"1","signal":12,"type":"GAUGE","col":0,"row":0,"w":2,"h":2},
            {"id":"2","signal":12,"type":"HOLOGRAM","col":2,"row":0,"w":2,"h":2}]}]}"""
        assertEquals(listOf("1"), BoardJson.decode(text).first.single().portrait.map { it.id })
    }

    @Test
    fun decimate_keepsPeaks() {
        val samples = (0 until 1_000).map { Sample(it * 100L, if (it == 517) 9_999.0 else 900.0) }
        val out = SeriesTools.decimate(samples, 50)
        assertTrue(out.size <= 100)
        assertEquals(9_999.0, out.maxOf { it.v }, 0.0)
    }

    @Test
    fun collapseGaps_shrinksSilenceAndBreaksTheLine() {
        val samples = listOf(Sample(0, 1.0), Sample(1_000, 2.0), Sample(3_601_000, 3.0))
        val out = SeriesTools.collapseGaps(samples, 30_000)
        assertTrue(out.any { it.v.isNaN() })
        assertEquals(31_000L, out.last().t)
    }

    @Test
    fun route_pairsFixesWithColourAndBreaksOnGaps() {
        val lat = listOf(Sample(0, 51.0), Sample(1_000, 51.001), Sample(120_000, 51.002), Sample(121_000, 51.003))
        val lon = lat.map { Sample(it.t, -0.1) }
        val speed = listOf(Sample(0, 10.0), Sample(119_500, 40.0))
        val route = SeriesTools.route(lat, lon, speed, holdMs = 20_000, maxGapMs = 30_000, maxPoints = 100)
        assertEquals(4, route.size)
        assertEquals(listOf(false, false, true, false), route.map { it.breakBefore })
        assertEquals(10.0, route[1].value, 0.0)
        assertEquals(40.0, route[2].value, 0.0)
    }

    @Test
    fun route_thinsLongTracksButKeepsBreaksAndEnd() {
        // 10 000 fixes at 1 Hz with one 5-minute gap in the middle.
        val lat = (0 until 10_000).map { i -> Sample(i * 1_000L + if (i >= 5_000) 300_000L else 0L, 51.0 + i * 1e-5) }
        val lon = lat.map { Sample(it.t, 0.0) }
        val route = SeriesTools.route(lat, lon, emptyList(), 20_000, 30_000, maxPoints = 500)
        assertTrue("size ${route.size}", route.size in 400..520)
        assertEquals(1, route.count { it.breakBefore })
        assertEquals(lat.last().t, route.last().t)
    }

    @Test
    fun paletteSchemes_keepTextReadable() {
        for (palette in com.example.agenticcar.ui.theme.Palette.entries) {
            for (dark in listOf(false, true)) {
                for (vibrant in listOf(false, true)) {
                    val s = com.example.agenticcar.ui.theme.paletteScheme(palette, dark, vibrant)
                    assertTrue("$palette dark=$dark vibrant=$vibrant", com.example.agenticcar.ui.theme.contrast(s.onSurface, s.surface) >= 7f)
                    for ((name, pair) in listOf("primary" to (s.onPrimary to s.primary), "secondary" to (s.onSecondary to s.secondary), "tertiary" to (s.onTertiary to s.tertiary))) {
                        assertTrue("$palette dark=$dark vibrant=$vibrant $name", com.example.agenticcar.ui.theme.contrast(pair.first, pair.second) >= 4.5f)
                    }
                }
            }
        }
    }



    @Test
    fun summarizeHeld_weightsByHoldTime() {
        // 1000 rpm held 10 s, then a busy second of 3000s: the time-weighted mean stays near 1000.
        val samples = listOf(Sample(0, 1_000.0)) + (0 until 4).map { Sample(10_000L + it * 250, 3_000.0) } + Sample(11_000, 1_000.0)
        val data = SeriesTools.summarizeHeld(samples, 20_000)
        assertEquals(1_000.0, data.min!!, 0.0)
        assertEquals(3_000.0, data.max!!, 0.0)
        assertTrue("mean ${data.mean}", data.mean!! < 1_250)
    }
}
