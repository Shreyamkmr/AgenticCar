package com.example.agenticcar.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos

enum class TileMode { LIVE, ANALYSIS }

private val Amber = Color(0xFFFBBF24)

/** Draws one tile's content on a card background; [data] decides what is shown, [mode] how it is labelled. */
@Composable
fun TileView(tile: Tile, data: TileData, mode: TileMode, modifier: Modifier = Modifier) {
    val label = SignalStyle.label(tile.signalId)
    val unit = SignalStyle.unit(tile.signalId)
    val scheme = MaterialTheme.colorScheme
    // A faint wash of the primary colour from the top-left corner gives each card some depth.
    val wash = Brush.linearGradient(listOf(scheme.primary.copy(alpha = 0.10f), Color.Transparent))
    Surface(
        modifier = modifier.fillMaxSize(),
        color = scheme.surfaceContainerHigh,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(wash).padding(10.dp)) {
            val small = minOf(maxWidth, maxHeight)
            val height = maxHeight
            Box(Modifier.semantics { contentDescription = "$label ${SignalStyle.format(tile.signalId, data.value)} $unit" }) {
                when (tile.type) {
                    TileType.GAUGE -> GaugeTile(tile, data, mode, label, unit, small)
                    TileType.TREND -> TrendTile(tile, data, mode, label, unit)
                    TileType.VALUE -> ValueTile(tile, data, mode, label, unit, height)
                    TileType.BAR -> BarTile(tile, data, mode, label, unit)
                    TileType.LAMP -> LampTile(tile, data, mode, label, small)
                    TileType.ROUTE -> RouteTile(tile, data, mode, label, unit)
                }
            }
        }
    }
}

private fun valueStyle(size: Dp) = TextStyle(
    fontSize = (size.value * 0.2f).coerceIn(14f, 56f).sp,
    fontWeight = FontWeight.SemiBold,
)

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) = Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
)

private fun prefix(mode: TileMode) = if (mode == TileMode.ANALYSIS) "avg " else ""

@Composable
private fun GaugeTile(tile: Tile, data: TileData, mode: TileMode, label: String, unit: String, tileSize: Dp) {
    val range = SignalStyle.range(tile, data.observedRange)
    fun fraction(v: Double?) = v?.let { ((it - range.start) / (range.endInclusive - range.start)).toFloat().coerceIn(0f, 1f) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val fillEnd = MaterialTheme.colorScheme.tertiary
    val warn = MaterialTheme.colorScheme.error
    val marker = MaterialTheme.colorScheme.onSurface
    val value = fraction(data.value)
    val lo = if (mode == TileMode.ANALYSIS) fraction(data.min) else null
    val hi = if (mode == TileMode.ANALYSIS) fraction(data.max) else null

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.aspectRatio(1f).fillMaxSize()) {
            val stroke = tileSize.toPx() * 0.09f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 135f, 270f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            // Analysis: the band the value moved within.
            if (lo != null && hi != null) {
                drawArc(fill.copy(alpha = 0.3f), 135f + 270f * lo, 270f * (hi - lo).coerceAtLeast(0.005f), false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            if (value != null) {
                // Rotated so the sweep gradient's 0..0.75 lies along the dial: primary into tertiary.
                rotate(135f) {
                    val brush = if (value > 0.85f) {
                        Brush.linearGradient(listOf(warn, warn))
                    } else {
                        Brush.sweepGradient(0f to fill, 0.75f to fillEnd, 1f to fill, center = center)
                    }
                    drawArc(brush, 0f, 270f * value, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            if (hi != null) {
                val angle = Math.toRadians(135.0 + 270.0 * hi)
                val r = arc.width / 2
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val outer = c + Offset((kotlin.math.cos(angle) * (r + stroke * 0.7f)).toFloat(), (kotlin.math.sin(angle) * (r + stroke * 0.7f)).toFloat())
                val inner = c + Offset((kotlin.math.cos(angle) * (r - stroke * 0.7f)).toFloat(), (kotlin.math.sin(angle) * (r - stroke * 0.7f)).toFloat())
                drawLine(marker, inner, outer, stroke * 0.25f, StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(SignalStyle.format(tile.signalId, data.value), style = valueStyle(tileSize))
            Caption(listOf(unit, label).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString(" · "))
            if (mode == TileMode.ANALYSIS && data.max != null) {
                Caption("avg · max ${SignalStyle.format(tile.signalId, data.max)}")
            }
        }
    }
}

@Composable
private fun TrendTile(tile: Tile, data: TileData, mode: TileMode, label: String, unit: String) {
    val line = MaterialTheme.colorScheme.primary
    val lineEnd = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val points = data.series
    val finite = SeriesTools.finite(points)
    val observed = if (finite.isEmpty()) null else finite.minOf { it.v }..finite.maxOf { it.v }
    // Trends auto-scale to what they show unless the tile fixes a range.
    val range = if (tile.min != null || tile.max != null) SignalStyle.range(tile, observed) else {
        SignalStyle.range(tile.copy(signalId = -1), observed)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${prefix(mode)}${SignalStyle.format(tile.signalId, data.value)} $unit".trim(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Caption(if (label.equals(unit, ignoreCase = true)) "" else label, Modifier.weight(1f))
            if (observed != null) {
                Caption("${SignalStyle.format(tile.signalId, observed.start)}–${SignalStyle.format(tile.signalId, observed.endInclusive)}")
            }
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            if (finite.size < 2) return@Canvas
            val from = data.from ?: points.first().t
            val to = (data.to ?: points.last().t).coerceAtLeast(from + 1)
            val span = (range.endInclusive - range.start).toFloat()
            fun x(t: Long) = (t - from).toFloat() / (to - from) * size.width
            fun y(v: Double) = size.height - ((v - range.start).toFloat() / span).coerceIn(0f, 1f) * size.height

            val path = Path()
            val area = Path()
            var open = false
            var areaStartX = 0f
            var lastX = 0f
            fun closeArea() {
                if (!open) return
                area.lineTo(lastX, size.height)
                area.lineTo(areaStartX, size.height)
                area.close()
            }
            for (p in points) {
                if (!p.v.isFinite()) {
                    closeArea()
                    open = false
                    continue
                }
                val px = x(p.t)
                val py = y(p.v)
                if (!open) {
                    path.moveTo(px, py)
                    area.moveTo(px, size.height)
                    area.lineTo(px, py)
                    areaStartX = px
                    open = true
                } else {
                    path.lineTo(px, py)
                    area.lineTo(px, py)
                }
                lastX = px
            }
            closeArea()
            drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), line.copy(alpha = 0f))))
            drawPath(path, Brush.horizontalGradient(listOf(line, lineEnd)), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun ValueTile(tile: Tile, data: TileData, mode: TileMode, label: String, unit: String, height: Dp) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Caption(label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (mode == TileMode.ANALYSIS) Caption("avg", Modifier.padding(bottom = 4.dp))
            Text(
                SignalStyle.format(tile.signalId, data.value),
                style = valueStyle(height * 2.2f),
                maxLines = 1,
            )
            Caption(unit, Modifier.padding(bottom = 4.dp))
        }
        if (data.min != null && data.max != null) {
            Caption("min ${SignalStyle.format(tile.signalId, data.min)} · max ${SignalStyle.format(tile.signalId, data.max)}")
        }
    }
}

@Composable
private fun BarTile(tile: Tile, data: TileData, mode: TileMode, label: String, unit: String) {
    val range = SignalStyle.range(tile, data.observedRange)
    fun fraction(v: Double?) = v?.let { ((it - range.start) / (range.endInclusive - range.start)).toFloat().coerceIn(0f, 1f) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val fillEnd = MaterialTheme.colorScheme.tertiary
    val marker = MaterialTheme.colorScheme.onSurface
    val value = fraction(data.value)
    val hi = if (mode == TileMode.ANALYSIS) fraction(data.max) else null
    val lo = if (mode == TileMode.ANALYSIS) fraction(data.min) else null
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption(label, Modifier.weight(1f))
            Text(
                "${prefix(mode)}${SignalStyle.format(tile.signalId, data.value)} $unit".trim(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(top = 4.dp)) {
            val r = size.height / 2
            drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            if (value != null) {
                drawRoundRect(Brush.horizontalGradient(listOf(fill, fillEnd), endX = size.width), size = Size(size.width * value, size.height), cornerRadius = CornerRadius(r))
            }
            for (f in listOfNotNull(lo, hi)) {
                drawLine(marker, Offset(size.width * f, -2f), Offset(size.width * f, size.height + 2f), 2.dp.toPx())
            }
        }
    }
}

@Composable
private fun LampTile(tile: Tile, data: TileData, mode: TileMode, label: String, size: Dp) {
    val threshold = SignalStyle.lampThreshold(tile)
    // In analysis a lamp shows whether it lit at any point of the selection.
    val reading = if (mode == TileMode.ANALYSIS) data.max else data.value
    val lit = reading != null && reading >= threshold
    val known = reading != null
    val color = when {
        !known -> MaterialTheme.colorScheme.outlineVariant
        lit -> if (tile.signalId == com.example.agenticcar.protocol.obd.PidCatalog.ID_MIL) Amber else MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.weight(1f).aspectRatio(1f)) {
            val r = this.size.minDimension / 2
            val c = Offset(this.size.width / 2, this.size.height / 2)
            if (lit) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), c, r), r, c)
            drawCircle(color, r * 0.55f, c)
            if (!lit) drawCircle(color, r * 0.55f, c, style = Stroke(r * 0.08f))
        }
        Caption(if (size < 72.dp) label else "$label${if (lit && mode == TileMode.ANALYSIS) " (was on)" else ""}")
    }
}

// Route colour ramp, the app icon's teal → amber → rose: readable on every palette, light or dark.
private val RampLow = Color(0xFF2EE6A8)
private val RampMid = Color(0xFFFBBF24)
private val RampHigh = Color(0xFFF43F5E)

private fun ramp(f: Float): Color =
    if (f < 0.5f) lerp(RampLow, RampMid, (f * 2).coerceIn(0f, 1f)) else lerp(RampMid, RampHigh, ((f - 0.5f) * 2).coerceIn(0f, 1f))

private val NICE_METRES = listOf(20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000)
private const val METRES_PER_DEGREE_LAT = 111_320.0

/** ~60 m of latitude: a parked car is a dot rather than a zoomed-in scribble of GPS noise. */
private const val MIN_SPAN_DEG = 0.00055

/**
 * GPS track drawn straight onto a Canvas (no map, no network): an equirectangular projection fitted
 * to the tile, each segment coloured by the tile's signal, with a scale bar and north marker.
 * Live, the newest fix is the car with its heading; in analysis the end is a finish marker.
 */
@Composable
private fun RouteTile(tile: Tile, data: TileData, mode: TileMode, label: String, unit: String) {
    val points = data.route
    val values = points.map { it.value }.filter { it.isFinite() }
    val observed = if (values.isEmpty()) null else values.min()..values.max()
    val range = when {
        tile.min != null || tile.max != null -> SignalStyle.range(tile, observed)
        observed == null -> 0.0..1.0
        observed.endInclusive > observed.start -> observed
        else -> observed.start..observed.start + 1.0
    }
    val measurer = rememberTextMeasurer()
    val casing = MaterialTheme.colorScheme.surfaceContainerLowest
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.labelSmall.copy(color = ink)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("Route · $label", Modifier.weight(1f))
            if (observed != null) {
                Caption(SignalStyle.format(tile.signalId, range.start))
                Canvas(Modifier.width(48.dp).height(6.dp)) {
                    drawRoundRect(Brush.horizontalGradient(listOf(RampLow, RampMid, RampHigh)), cornerRadius = CornerRadius(size.height / 2))
                }
                Caption("${SignalStyle.format(tile.signalId, range.endInclusive)} $unit".trim())
            }
        }
        if (points.size < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Caption(if (mode == TileMode.LIVE) "Waiting for GPS…" else "No GPS track in this selection")
            }
            return@Column
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            val latMin = points.minOf { it.lat }
            val latMax = points.maxOf { it.lat }
            val lonMin = points.minOf { it.lon }
            val lonMax = points.maxOf { it.lon }
            val k = cos(Math.toRadians((latMin + latMax) / 2))
            val spanX = ((lonMax - lonMin) * k).coerceAtLeast(MIN_SPAN_DEG)
            val spanY = (latMax - latMin).coerceAtLeast(MIN_SPAN_DEG)
            val pad = 12.dp.toPx()
            val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
            val ox = (size.width - (lonMax - lonMin) * k * scale).toFloat() / 2
            val oy = (size.height - (latMax - latMin) * scale).toFloat() / 2
            fun at(p: RoutePoint) = Offset(ox + ((p.lon - lonMin) * k * scale).toFloat(), oy + ((latMax - p.lat) * scale).toFloat())
            fun colorOf(v: Double) =
                if (v.isFinite()) ramp(((v - range.start) / (range.endInclusive - range.start)).toFloat()) else ink.copy(alpha = 0.5f)

            val width = 3.5.dp.toPx()
            val casingPath = Path()
            points.forEachIndexed { i, p ->
                val o = at(p)
                if (i == 0 || p.breakBefore) casingPath.moveTo(o.x, o.y) else casingPath.lineTo(o.x, o.y)
            }
            drawPath(casingPath, casing, style = Stroke(width * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            for (i in 1 until points.size) {
                if (points[i].breakBefore) continue
                drawLine(colorOf((points[i - 1].value + points[i].value) / 2), at(points[i - 1]), at(points[i]), width, StrokeCap.Round)
            }

            val start = at(points.first())
            drawCircle(casing, width * 2f, start)
            drawCircle(RampLow, width * 1.3f, start)

            // End: the car with its heading while live, a finish marker in analysis.
            val end = at(points.last())
            if (mode == TileMode.LIVE) {
                val before = points.asReversed().map(::at).firstOrNull { (it - end).getDistance() > 1f }
                val heading = if (before != null) Math.toDegrees(atan2((end.y - before.y).toDouble(), (end.x - before.x).toDouble())).toFloat() else -90f
                val r = width * 2.6f
                drawCircle(casing, r * 1.15f, end)
                rotate(heading, pivot = end) {
                    val arrow = Path().apply {
                        moveTo(end.x + r, end.y)
                        lineTo(end.x - r * 0.7f, end.y - r * 0.75f)
                        lineTo(end.x - r * 0.35f, end.y)
                        lineTo(end.x - r * 0.7f, end.y + r * 0.75f)
                        close()
                    }
                    drawPath(arrow, Color.White)
                }
            } else {
                val s = width * 1.6f
                drawRect(casing, Offset(end.x - s * 1.3f, end.y - s * 1.3f), Size(s * 2.6f, s * 2.6f))
                drawRect(Color.White, Offset(end.x - s, end.y - s), Size(s * 2, s * 2))
                drawRect(RampHigh, Offset(end.x - s * 0.55f, end.y - s * 0.55f), Size(s * 1.1f, s * 1.1f))
            }

            // Scale bar, bottom-left.
            val metresPerPx = METRES_PER_DEGREE_LAT / scale
            val metres = NICE_METRES.lastOrNull { it / metresPerPx <= size.width * 0.3 } ?: NICE_METRES.first()
            val barPx = (metres / metresPerPx).toFloat()
            val y = size.height - 4.dp.toPx()
            val tick = 4.dp.toPx()
            val line = 1.5.dp.toPx()
            drawLine(ink, Offset(0f, y), Offset(barPx, y), line)
            drawLine(ink, Offset(0f, y), Offset(0f, y - tick), line)
            drawLine(ink, Offset(barPx, y), Offset(barPx, y - tick), line)
            val scaleText = measurer.measure(if (metres >= 1_000) "${metres / 1_000} km" else "$metres m", small)
            drawText(scaleText, topLeft = Offset(barPx + 4.dp.toPx(), y - scaleText.size.height + 2.dp.toPx()))

            // North, top-right.
            val north = measurer.measure("N ↑", small)
            drawText(north, topLeft = Offset(size.width - north.size.width, 0f))
        }
    }
}
